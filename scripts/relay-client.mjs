#!/usr/bin/env node
// 手机侧协议客户端：以「手机」的身份直接调用中继的 /native/v2 接口，
// 用于在没有真机（或需要脚本化断言）时复现 App 的完整链路。
//
// 与 android/app/src/main/java/com/dsh/mobile/v2/net/RelayApi*.kt 的契约保持一致：
//   - 登录：POST /native/v2/login {email,password,code,label} → {token,deviceId}
//   - 会话 RPC：POST /native/v2/hosts/<id>/rpc/<method>
//     body = {type:'client-request', rpcId, method:'session/<method>', payload:{args}}
//   - 会话清单：GET /native/v2/hosts/<id>/sessions
//   - 差量：POST /native/v2/hosts/<id>/sync
//     body = {mode, pageByteBudget, sessions:[{sessionId,afterSeq,priority}]}
//
// **实时通路（审批/提问/流式）不在这里实现**：v2 的实时面是每个会话一条控制 WS
// `/native/v2/hosts/<id>/control`，握手要先发 HELLO，之后收 records/pulse/ack，
// 审批瀑布以 `INTERACTION` 帧下发。用 curl/node 手搓这条握手没有验收价值，真机
// 调试一律走 mobilewright + adb diag.log（见 AGENTS.md 六）。
//
// 用法：node scripts/relay-client.mjs <命令> [参数...]
//   login                                    登录并把 bearer 存到 artifacts/relay-client.json
//   hosts                                    列出主机（含 online/authorized）
//   pair <code>                              用配对码授权当前设备
//   rpc <hostId> <method> [argsJson]          通用会话 RPC（method 不带 session/ 前缀）
//   list <hostId>                             session/list
//   page <hostId> <sessionId> [limit]          session/page
//   prompt <hostId> <sessionId> <文本...>       session/prompt（纯文本）
//   search <hostId> <关键词>                   session/search
//   sessions <hostId>                         会话清单（v2 /sessions）
//   sync <hostId> <sessionId> [afterSeq] [pageByteBudget]  v2 差量一页
//   rotate                                    轮换 bearer 并落盘
//
// 环境变量：RELAY_ORIGIN（默认 https://relay.example.test）、RELAY_EMAIL、
//   RELAY_PASSWORD、RELAY_TOTP（base32 密钥）；也可复用 artifacts/relay-client.json 里的 bearer。
import { createHmac } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { randomUUID } from 'node:crypto';

const ORIGIN = (process.env.RELAY_ORIGIN ?? 'https://relay.example.test').replace(/\/$/, '');
const STATE = 'artifacts/relay-client.json';

// ---------- TOTP（RFC 6238，SHA-1 / 6 位 / 30 秒） ----------
function base32Decode(input) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  const clean = input.replace(/=+$/, '').toUpperCase().replace(/\s/g, '');
  let bits = 0, value = 0;
  const out = [];
  for (const ch of clean) {
    const idx = alphabet.indexOf(ch);
    if (idx < 0) continue;
    value = (value << 5) | idx;
    bits += 5;
    if (bits >= 8) { out.push((value >>> (bits - 8)) & 0xff); bits -= 8; }
  }
  return Buffer.from(out);
}

function totp(secret, at = Date.now()) {
  const counter = Math.floor(at / 1000 / 30);
  const buf = Buffer.alloc(8);
  buf.writeBigUInt64BE(BigInt(counter));
  const digest = createHmac('sha1', base32Decode(secret)).update(buf).digest();
  const offset = digest[digest.length - 1] & 0x0f;
  const code = ((digest[offset] & 0x7f) << 24 | digest[offset + 1] << 16 | digest[offset + 2] << 8 | digest[offset + 3]) % 1e6;
  return String(code).padStart(6, '0');
}

// ---------- 状态 ----------
function loadState() { try { return JSON.parse(readFileSync(STATE, 'utf8')); } catch { return {}; } }
function saveState(next) { mkdirSync('artifacts', { recursive: true }); writeFileSync(STATE, JSON.stringify(next, null, 2)); }

async function login() {
  const email = process.env.RELAY_EMAIL ?? 'admin@relay.local';
  const password = process.env.RELAY_PASSWORD;
  const secret = process.env.RELAY_TOTP;
  if (!password || !secret) throw new Error('需要 RELAY_PASSWORD 与 RELAY_TOTP 环境变量');
  const res = await fetch(`${ORIGIN}/native/v2/login`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ email, password, code: totp(secret), label: 'relay-client-script' }),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`登录失败 ${res.status}: ${text.slice(0, 300)}`);
  const json = JSON.parse(text);
  saveState({ ...loadState(), origin: ORIGIN, token: json.token, deviceId: json.deviceId, at: new Date().toISOString() });
  console.log(`登录成功 deviceId=${json.deviceId} token=${json.token.slice(0, 8)}…`);
  return json.token;
}

function token() {
  const t = loadState().token;
  if (!t) throw new Error('尚无 bearer，先执行 login');
  return t;
}

async function call(path, { method = 'GET', body, auth = true } = {}) {
  const headers = {};
  if (auth) headers.authorization = `Bearer ${token()}`;
  if (body !== undefined) headers['content-type'] = 'application/json';
  const res = await fetch(`${ORIGIN}${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* 保持原文 */ }
  return { status: res.status, json, text };
}

function rpcBody(m, args, wrap = true) {
  // 与 RelayApi.java:67-70 一致：session/list 的参数名为 _request，其余为 request，
  // modelCatalog 不包壳；$events/result 走它自己的 args 形状。
  let shaped = args;
  if (wrap && !m.startsWith('$')) {
    if (m === 'list') shaped = { _request: args };
    else if (m !== 'modelCatalog') shaped = { request: args };
  }
  return { type: 'client-request', rpcId: randomUUID(), method: m.startsWith('$') ? m : `session/${m}`, payload: { args: shaped } };
}

async function rpc(hostId, method, args = {}) {
  const body = rpcBody(method, args);
  const r = await call(`/native/v2/hosts/${hostId}/rpc/${method}`, { method: 'POST', body });
  if (r.status !== 200) throw new Error(`rpc ${method} → ${r.status}: ${r.text.slice(0, 400)}`);
  const result = r.json?.result;
  if (result && result.ok === false) throw new Error(`rpc ${method} 被 DSH 拒绝：${result.error?.code ?? ''} ${result.error?.message ?? ''}`);
  return result && 'value' in result ? result.value : r.json;
}

// ---------- 命令 ----------
const [cmd, ...rest] = process.argv.slice(2);
const J = (v) => JSON.stringify(v, null, 2);

try {
  if (cmd === 'login') await login();
  else if (cmd === 'hosts') console.log(J((await call('/native/v2/hosts')).json));
  else if (cmd === 'pair') console.log(J((await call('/native/v2/pair', { method: 'POST', body: { code: rest[0] } })).json));
  else if (cmd === 'rpc') console.log(J(await rpc(rest[0], rest[1], rest[2] ? JSON.parse(rest[2]) : {})));
  else if (cmd === 'list') {
    const r = await rpc(rest[0], 'list', {});
    const items = r?.result?.result?.items ?? r?.result?.items ?? r?.items ?? r;
    console.log(J(items));
  } else if (cmd === 'page') console.log(J(await rpc(rest[0], 'page', { sessionId: rest[1], limit: Number(rest[2] ?? 40) })));
  else if (cmd === 'prompt') {
    // 与 HttpRelayGateway.sendPrompt 一致：宿主只认 requestId/mode/content/clientTimeZone 这一形状。
    const args = {
      sessionId: rest[1],
      requestId: randomUUID(),
      mode: 'queue',
      content: [{ type: 'text', text: rest.slice(2).join(' ') }],
      clientTimeZone: 'Asia/Shanghai',
    };
    console.log(J(await rpc(rest[0], 'prompt', args)));
  } else if (cmd === 'search') console.log(J(await rpc(rest[0], 'search', { query: rest[1] ?? '' })));
  else if (cmd === 'rotate') console.log(J((await call('/native/v2/rotate', { method: 'POST', body: {} })).json?.token?.slice(0, 12) + '…'));
  else if (cmd === 'sessions') {
    console.log(J((await call(`/native/v2/hosts/${rest[0]}/sessions`)).json));
  } else if (cmd === 'sync') {
    // v2 差量：一次一页，游标就是 afterSeq。服务端只对 FOREGROUND_SINGLE 做自旋刹车，
    // 所以这里刻意只发单会话——批量轨道不会被 429，调试时看不到刹车行为。
    const body = {
      mode: 'FOREGROUND_SINGLE',
      pageByteBudget: Number(rest[3] ?? 524288),
      sessions: [{ sessionId: rest[1], afterSeq: Number(rest[2] ?? 0), priority: 'FOREGROUND' }],
    };
    console.log(J((await call(`/native/v2/hosts/${rest[0]}/sync`, { method: 'POST', body })).json));
  } else {
    console.log(readFileSync(new URL(import.meta.url), 'utf8').split('\n').slice(0, 30).join('\n'));
  }
} catch (error) {
  console.error(`失败：${error.message}`);
  process.exit(1);
}
