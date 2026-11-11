#!/usr/bin/env node
// 中继「账户面」（浏览器 cookie 表单）客户端：用于在没有人操作浏览器时完成
// 管理员侧动作 —— 登录、给某台主机创建配对码、给账户创建主机注册邀请。
//
// 与 packages/relay/src/server.ts 的契约：
//   GET  /login            → Set-Cookie __Host-relay-login=<nonce>，表单 csrf=nonce
//   POST /login            form {email,password,code,label,csrf} → Set-Cookie __Host-relay-account
//   GET  /                 → 刷新 csrf（refreshCsrf），页面内表单带该 csrf
//   POST /pair/create      form {csrf,hostId} → HTML，含「人工码：<strong>XXXX</strong>」
//   POST /invitation       form {csrf} → HTML，含 <code>邀请码</code>
// 所有 POST 都要通过 originCheck，因此必须带 Origin/Referer。
//
// 用法：node scripts/relay-admin.mjs <pair|invite> [hostId]
// 环境变量：RELAY_ORIGIN、RELAY_EMAIL、RELAY_PASSWORD、RELAY_TOTP
import { createHmac } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';

const ORIGIN = (process.env.RELAY_ORIGIN ?? 'https://relay.example.test').replace(/\/$/, '');

function base32Decode(input) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  const clean = input.replace(/=+$/, '').toUpperCase().replace(/\s/g, '');
  let bits = 0, value = 0;
  const out = [];
  for (const ch of clean) {
    const idx = alphabet.indexOf(ch);
    if (idx < 0) continue;
    value = (value << 5) | idx; bits += 5;
    if (bits >= 8) { out.push((value >>> (bits - 8)) & 0xff); bits -= 8; }
  }
  return Buffer.from(out);
}

function totp(secret, at = Date.now()) {
  const counter = Math.floor(at / 1000 / 30);
  const buf = Buffer.alloc(8); buf.writeBigUInt64BE(BigInt(counter));
  const digest = createHmac('sha1', base32Decode(secret)).update(buf).digest();
  const offset = digest[digest.length - 1] & 0x0f;
  const code = ((digest[offset] & 0x7f) << 24 | digest[offset + 1] << 16 | digest[offset + 2] << 8 | digest[offset + 3]) % 1e6;
  return String(code).padStart(6, '0');
}

const jar = new Map();
function absorb(res) {
  const raw = res.headers.getSetCookie?.() ?? [];
  for (const c of raw) {
    const [pair] = c.split(';');
    const i = pair.indexOf('=');
    if (i > 0) jar.set(pair.slice(0, i).trim(), pair.slice(i + 1).trim());
  }
}
const cookieHeader = () => [...jar].filter(([, v]) => v).map(([k, v]) => `${k}=${v}`).join('; ');

async function req(path, { method = 'GET', form } = {}) {
  const headers = {
    cookie: cookieHeader(),
    origin: ORIGIN,
    referer: `${ORIGIN}${path}`,
    'user-agent': 'relay-admin-script/1.0',
  };
  let body;
  if (form) { headers['content-type'] = 'application/x-www-form-urlencoded'; body = new URLSearchParams(form).toString(); }
  const res = await fetch(`${ORIGIN}${path}`, { method, headers, body, redirect: 'manual' });
  absorb(res);
  return { status: res.status, location: res.headers.get('location'), text: await res.text() };
}

async function login() {
  const first = await req('/login');
  const nonce = jar.get('__Host-relay-login');
  if (!nonce) throw new Error(`GET /login 未下发 __Host-relay-login（HTTP ${first.status}）`);
  const res = await req('/login', {
    method: 'POST',
    form: {
      email: process.env.RELAY_EMAIL ?? 'admin@relay.local',
      password: process.env.RELAY_PASSWORD,
      code: totp(process.env.RELAY_TOTP),
      label: 'relay-admin-script',
      csrf: nonce,
    },
  });
  if (res.status !== 303 && res.status !== 302) throw new Error(`登录被拒 HTTP ${res.status}: ${res.text.slice(0, 300)}`);
  if (!jar.get('__Host-relay-account')) throw new Error('登录未下发 __Host-relay-account');
  console.log(`登录成功（HTTP ${res.status} → ${res.location}）`);
}

async function csrf() {
  const res = await req('/');
  if (res.status !== 200) throw new Error(`GET / 失败 HTTP ${res.status}: ${res.text.slice(0, 300)}`);
  const m = /name="csrf" value="([^"]+)"/.exec(res.text);
  if (!m) throw new Error('首页未找到 csrf 字段');
  return m[1];
}

const cmd = process.argv[2];
try {
  if (cmd === 'totp') {
    // 只打印当前动态验证码：真机测试要人工/脚本往 App 里填。
    const left = 30 - (Math.floor(Date.now() / 1000) % 30);
    console.log(`${totp(process.env.RELAY_TOTP)}  （${left}s 后失效）`);
    process.exit(0);
  }
  await login();
  const token = await csrf();
  if (cmd === 'invite') {
    const res = await req('/invitation', { method: 'POST', form: { csrf: token } });
    const m = /<code>([^<]+)<\/code>/.exec(res.text);
    if (!m) throw new Error(`未拿到邀请码：${res.text.slice(0, 300)}`);
    mkdirSync('artifacts', { recursive: true });
    writeFileSync('artifacts/relay-enrollment-invitation.txt', m[1]);
    console.log(`主机注册邀请（5 分钟有效，已写入 artifacts/relay-enrollment-invitation.txt）：\n${m[1]}`);
  } else if (cmd === 'pair') {
    const res = await req('/pair/create', { method: 'POST', form: { csrf: token, hostId: process.argv[3] ?? '' } });
    const m = /人工码：<strong>([^<]+)<\/strong>/.exec(res.text);
    if (!m) throw new Error(`未拿到配对码：${res.text.slice(0, 400)}`);
    console.log(`设备配对码（5 分钟有效）：${m[1]}`);
  } else {
    console.log('用法：node scripts/relay-admin.mjs <pair [hostId] | invite>');
  }
} catch (error) {
  console.error(`失败：${error.message}`);
  process.exit(1);
}
