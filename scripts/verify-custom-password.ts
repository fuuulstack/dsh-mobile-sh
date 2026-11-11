/**
 * 用 ~/Documents/keys 里的真实密码验证「自定义长度密码」注册链路。
 *
 * 拓扑与生产一致：TLS 前置 → 中继；插件侧只用到 normalizeEnrollSecret + enroll。
 * 只打印长度与结果，绝不打印密码原文。
 */
import http from 'node:http';
import https from 'node:https';
import net from 'node:net';
import { once } from 'node:events';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { homedir } from 'node:os';
import { Relay } from '../packages/relay/src/server.js';
import { normalizeEnrollSecret } from '../packages/plugin/src/settings.js';
import { uuid, secret, hash } from '../packages/relay/src/crypto.js';

const P12 = 'artifacts/android-fixture.p12';
const PASS = 'local-fixture-only';
if (!existsSync(P12)) { console.error(`缺少 ${P12}`); process.exit(1); }
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';

// 1) 从 keys 文件读用户真实密码
const keysFile = join(homedir(), 'Documents', 'keys');
const line = readFileSync(keysFile, 'utf8').split(/\r?\n/).find((l) => l.startsWith('dsh-mobile-sh-relay-pwd='));
if (!line) { console.error('keys 文件里没有 dsh-mobile-sh-relay-pwd'); process.exit(1); }
const rawPwd = line.slice('dsh-mobile-sh-relay-pwd='.length);
console.log(`① 读到密码：长度 ${rawPwd.length}（内容不打印）`);
const pwd = normalizeEnrollSecret(rawPwd);
console.log(`② 规范化通过：长度 ${pwd.length}（旧实现要求 43 位，这里会直接报错）`);

// 2) 中继 + TLS 前置
const relay = new Relay({ baseUrl: 'https://relay.local.test', database: ':memory:', key: Buffer.alloc(32, 7), requireTotp: false, testMode: true });
const relayPort = await relay.listen(0);
await relay.store.createAccount('keys@example.test', 'long-test-password', false);
const web = await relay.store.login('keys@example.test', 'long-test-password', '', false, '网页');
const account = relay.store.session(web.token, 'account');

// 3) 把这个自定义密码写成一条邀请（等价于部署方在环境里配的密码）
relay.store.run('INSERT INTO invitations VALUES(?,?,?,0)', hash(pwd), account.account_id, Date.now() + 300000);

const front = https.createServer({ pfx: readFileSync(P12), passphrase: PASS }, (req, res) => {
  const proxy = http.request({ host: '127.0.0.1', port: relayPort, path: req.url, method: req.method,
    headers: { ...req.headers, host: new URL('https://relay.local.test').host } }, (up) => {
    res.writeHead(up.statusCode!, up.headers); up.pipe(res);
  });
  proxy.on('error', (e) => { res.writeHead(502); res.end(String(e)); });
  req.pipe(proxy);
});
front.on('upgrade', (req, socket, head) => {
  const raw = net.connect(relayPort, '127.0.0.1', () => {
    const headers = [`GET ${req.url} HTTP/1.1`, `Host: ${new URL('https://relay.local.test').host}`];
    for (const [k, v] of Object.entries(req.headers)) if (k !== 'host') headers.push(`${k}: ${v}`);
    raw.write(headers.join('\r\n') + '\r\n\r\n');
    if (head.length) raw.write(head);
    socket.pipe(raw).pipe(socket);
  });
  raw.on('error', () => socket.destroy());
  socket.on('error', () => raw.destroy());
  socket.once('close', () => raw.destroy());
});
front.listen(0, '127.0.0.1');
await once(front, 'listening');
const frontPort = (front.address() as { port: number }).port;

// 4) 用这个密码注册主机（走真实的 HTTPS 前置）
const body = Buffer.from(JSON.stringify({ operationId: uuid(), hostPublicId: uuid(), hostTunnelToken: secret(), alias: 'keys 验证机', platform: 'win32' }));
const enroll = (password: string) => new Promise<{ status: number; text: string }>((resolve, reject) => {
  const req = https.request({ hostname: '127.0.0.1', port: frontPort, path: '/control/enroll', method: 'POST', rejectUnauthorized: false,
    headers: { host: `localhost:${frontPort}`, 'content-type': 'application/json', 'content-length': body.length, authorization: `Bearer ${password}` } }, (res) => {
    const chunks: Buffer[] = [];
    res.on('data', (b) => chunks.push(b));
    res.on('end', () => resolve({ status: res.statusCode!, text: Buffer.concat(chunks).toString('utf8') }));
  });
  req.on('error', reject); req.end(body);
});

const enrolled = await enroll(pwd);
console.log(`③ 用该密码注册主机：HTTP ${enrolled.status} ${enrolled.text.slice(0, 120)}`);
if (enrolled.status !== 200) { console.error('✖ 注册失败'); process.exit(1); }
const hostId = (JSON.parse(enrolled.text) as { hostId: string }).hostId;
console.log(`④ 主机已注册：hostId=${hostId.slice(0, 8)}…`);

const wrong = await enroll('definitely-wrong');
console.log(`⑤ 错误密码：HTTP ${wrong.status}（应为 403）`);
if (wrong.status !== 403) { console.error('✖ 错误密码竟然通过了'); process.exit(1); }

console.log('\n结论：自定义长度密码（非 43 位）可以正常注册主机，错误密码仍被拒绝。');
await new Promise<void>((r) => front.close(() => r()));
await relay.close();
