/**
 * 插件 UI 端到端验收脚本（真中继 + TLS 前置 + 真插件 lifecycle + 真 HTTP）。
 *
 * 拓扑与 `scripts/local-e2e.ts` 一致——因为插件只接受 `wss://`，真实部署也一定是
 * 「TLS 前置 + 后面的明文中继」：
 *
 * ```
 *   浏览器/插件 ──https/wss──▶ TLS 前置(8443, 自签 localhost) ──http──▶ 中继(内部端口)
 * ```
 *
 * 覆盖「用户实际会点的两下」：
 *   1. 打开 /dsh-mobile-sh/embed 拿到页面（中继地址框、鉴权密码框、生成二维码按钮）；
 *   2. POST /api/settings 保存中继地址 → 插件用新地址重连；
 *   3. POST /api/pair 生成二维码 → 用返回的 8 位码模拟手机兑换 → 拿到 hostId；
 *   4. 拿不到浏览器会话判定时必须 503（fail closed）。
 *
 * 用法：npx tsx scripts/e2e-plugin-ui.ts
 * TLS 物料：复用 artifacts/fixture-cert.pem + fixture-key.pem（scripts/init-android-fixture.ps1 产物）。
 */
import http from 'node:http';
import https from 'node:https';
import net from 'node:net';
import { once } from 'node:events';
import { createHash } from 'node:crypto';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { WebSocketServer } from 'ws';
import { Relay } from '../packages/relay/src/server.js';
import { activate, type Context, type PluginConfig } from '../packages/plugin/src/lifecycle.js';
import { registerUiRoutes, UI_PREFIX } from '../packages/plugin/src/ui-routes.js';
import { defaultNegotiation } from '../packages/core/src/index.js';
import { uuid, secret } from '../packages/relay/src/crypto.js';

const config: PluginConfig = { enabled: true, relayUrl: '', maxTunnelRequestBodyBytes: 1048576, maxConcurrentStreams: 8,
  connectTimeoutMs: 3000, httpIdleTimeoutMs: 5000, upgradePaths: ['/api/remote.mux'] };

const fail = (message: string): never => { console.error(`✖ ${message}`); process.exitCode = 1; throw new Error(message); };
const ok = (message: string) => console.log(`✔ ${message}`);

const P12 = 'artifacts/android-fixture.p12';
const P12_PASSPHRASE = 'local-fixture-only';
if (!existsSync(P12)) fail(`缺少 TLS 物料 ${P12}（先跑 scripts/init-android-fixture.ps1）`);
// 自签证书：本脚本自己发起的 HTTPS 请求要跳过校验（浏览器侧由用户点「继续」）。
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';

const dir = mkdtempSync(join(tmpdir(), 'mobile-sh-e2e-'));
const settingsFile = join(dir, 'config.json');
const cleanup: Array<() => unknown> = [];

// 1) 真中继（内部明文，in-memory，testMode 免 TOTP）
const relay = new Relay({ baseUrl: 'https://relay.local.test', database: ':memory:', key: Buffer.alloc(32, 4), requireTotp: false, testMode: true });
const relayPort = await relay.listen(0);
cleanup.push(() => relay.close());
await relay.store.createAccount('e2e@example.test', 'long-test-password', false);
const web = await relay.store.login('e2e@example.test', 'long-test-password', '', false, '网页');
const invitation = relay.store.invitation(relay.store.session(web.token, 'account'));
ok(`中继就绪（内部 127.0.0.1:${relayPort}），已建账号并签发注册邀请`);

// 2) 假 DSH 上游（cookie 交换 + 空的 session/list + 隧道 mux）
const upstream = http.createServer((req, res) => {
  if (req.url?.startsWith('/api/session/list')) {
    res.setHeader('content-type', 'application/json');
    res.end(JSON.stringify({ result: { ok: true, value: { items: [] } } }));
    return;
  }
  const name = 'dsh-auth-' + createHash('sha256').update(req.headers.host ?? '').digest('base64url');
  res.writeHead(303, { location: './', 'set-cookie': `${name}=e2e-cookie; Max-Age=2592000; HttpOnly` });
  res.end();
});
upstream.listen(0, '127.0.0.1');
await once(upstream, 'listening');
const upstreamOrigin = `http://127.0.0.1:${(upstream.address() as { port: number }).port}`;
const mux = new WebSocketServer({ server: upstream });
mux.on('connection', (ws) => {
  ws.on('message', (raw) => {
    const frame = JSON.parse(raw.toString()) as { type: string };
    if (frame.type === 'authenticate') {
      ws.send(JSON.stringify({ v: 3, type: 'auth-ok', hostId, tunnelEpoch: uuid(), policy: { requireTotp: false } }));
    }
    if (frame.type === 'hello') {
      ws.send(JSON.stringify({ v: 3, type: 'hello-ack', capabilities: ['flow', 'drain', 'ws-message'], limits: { ...defaultNegotiation, maxBody: 1048576, streams: 8 } }));
    }
  });
});
cleanup.push(async () => { for (const ws of mux.clients) ws.terminate(); mux.close(); await new Promise<void>((r) => upstream.close(() => r())); });

// 3) TLS 前置：与 local-e2e.ts 同形（HTTP 与 upgrade 都透传到中继，Host 改写成中继的 baseUrl host）
const front = https.createServer({ pfx: readFileSync(P12), passphrase: P12_PASSPHRASE }, (req, res) => {
  const proxy = http.request({ host: '127.0.0.1', port: relayPort, path: req.url, method: req.method,
    headers: { ...req.headers, host: new URL('https://relay.local.test').host } }, (up) => {
    res.writeHead(up.statusCode!, up.headers);
    up.pipe(res);
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
cleanup.push(async () => { await new Promise<void>((r) => front.close(() => r())); });
const relayOrigin = `https://localhost:${frontPort}`;
ok(`TLS 前置就绪（${relayOrigin}）→ 中继 127.0.0.1:${relayPort}`);

// 4) 插件：先用真邀请在中继上注册这台主机，再把同一份身份交给插件
//    （等价于「用户第一次填地址 + 鉴权密码并保存」之后的稳定状态）。
const hostTunnelToken = secret(), hostPublicId = uuid(), operationId = uuid();
const enrolled = await relay.store.enroll(invitation, { operationId, hostPublicId, hostTunnelToken, alias: 'e2e 主机', platform: 'win32' });
const hostId = enrolled.hostId;
const identity = { kind: 'grant', payload: { hostId, hostPublicId, hostTunnelToken, operationId, relayOrigin } };
ok(`主机已注册到中继：hostId=${hostId.slice(0, 8)}…`);
let dispose: (() => void | Promise<void>) | undefined;
let service: { status(): { state: string; hostId: string | null; relayOrigin: string | null }; settings(): { relaySource: string; relayUrl: string }; saveSettings(v: unknown): Promise<unknown>; pair(): Promise<{ code: string; uri: string; qr: string; ttlSeconds: number }> } | undefined;
const ctx = {
  effect: (fn: () => () => void | Promise<void>) => { dispose = fn(); },
  get: () => undefined,
  provide: (_name: string, value: unknown) => { service = value as typeof service; },
  logger: { info: () => {}, warn: () => {} },
  webServer: { getOrigin: () => upstreamOrigin },
  connection: { authenticatedUrl: () => `${upstreamOrigin}/?token=e2e` },
  credentials: { readRecord: async () => identity, modifyRecord: async () => {} },
  storageDomain: { open: async () => ({ table: () => ({ get: () => undefined, put: async () => {} }), close: async () => {} }) },
} as unknown as Context;
const { service: svc } = activate(ctx, { ...config, settingsFile }, {});
if (!service) fail('插件未提供 mobileSh 服务');

// 5) UI 路由挂在独立 HTTP 上（等价于宿主 webServer）
const routes: Array<{ path: string; handler: (req: http.IncomingMessage, res: http.ServerResponse) => unknown }> = [];
const ui = http.createServer((req, res) => {
  const path = new URL(req.url ?? '/', 'http://127.0.0.1').pathname;
  const route = routes.find((r) => r.path === path);
  if (!route) { res.writeHead(404); res.end(); return; }
  void route.handler(req, res);
});
ui.listen(0, '127.0.0.1');
await once(ui, 'listening');
const uiPort = (ui.address() as { port: number }).port;
cleanup.push(async () => { await dispose?.(); await new Promise<void>((r) => ui.close(() => r())); rmSync(dir, { recursive: true, force: true }); });

// 模拟「已登录的 DSH 浏览器」：connection.admit 永远通过
const uiCtx: { webServer: unknown; connection?: { admit(): { peer: object } }; logger: { info(): void; warn(): void } } = {
  webServer: { getOrigin: () => `http://127.0.0.1:${uiPort}`, register: (route: { path: string }) => { routes.push(route as never); return () => {}; } },
  connection: { admit: () => ({ peer: {} }) },
  logger: { info: () => {}, warn: () => {} },
};
cleanup.push(registerUiRoutes(uiCtx as never, svc as never));
ok(`UI 路由就绪（${UI_PREFIX}），宿主上游 ${upstreamOrigin}`);

const request = (method: string, path: string, body?: unknown): Promise<{ status: number; text: string; headers: http.IncomingHttpHeaders }> => new Promise((resolve, reject) => {
  const data = body === undefined ? undefined : Buffer.from(JSON.stringify(body));
  const req = http.request({ hostname: '127.0.0.1', port: uiPort, path, method,
    headers: data ? { 'content-type': 'application/json', 'content-length': data.length } : {} }, (res) => {
    const chunks: Buffer[] = [];
    res.on('data', (b) => chunks.push(b));
    res.on('end', () => resolve({ status: res.statusCode!, text: Buffer.concat(chunks).toString('utf8'), headers: res.headers }));
  });
  req.on('error', reject);
  req.end(data);
});

// --- ① 页面 ---
const page = await request('GET', `${UI_PREFIX}/embed`);
if (page.status !== 200) fail(`页面返回 ${page.status}`);
for (const needle of ['id="relay"', 'id="secret"', 'id="pair"', '生成二维码', '保存并重连', `${UI_PREFIX}/api/`]) {
  if (!page.text.includes(needle)) fail(`页面缺少 ${needle}`);
}
if (!/nonce-/.test(String(page.headers['content-security-policy'] ?? ''))) fail('页面缺少 nonce CSP');
if (/Bearer [A-Za-z0-9_-]{43}/.test(page.text)) fail('页面回显了鉴权密码');
ok('① 设置页可打开：中继地址/鉴权密码输入框 + 生成二维码按钮 + nonce CSP + 密码不回显');

// --- ② 保存中继地址 ---
const saved = await request('POST', `${UI_PREFIX}/api/settings`, { relayUrl: `localhost:${frontPort}` });
if (saved.status !== 200) fail(`保存设置返回 ${saved.status}：${saved.text}`);
const savedBody = JSON.parse(saved.text) as { ok: boolean; relayUrl: string };
if (savedBody.relayUrl !== `wss://localhost:${frontPort}/tunnel`) fail(`地址未规范化：${savedBody.relayUrl}`);
if (svc.settings().relaySource !== 'file') fail(`设置来源应为 file，实际 ${svc.settings().relaySource}`);
ok(`② 保存中继地址 localhost:${frontPort} → 规范化 ${savedBody.relayUrl}，来源切到设置文件，已触发重连`);

// --- ③ 生成二维码 ---
const pair = await request('POST', `${UI_PREFIX}/api/pair`, {});
if (pair.status !== 200) fail(`生成二维码返回 ${pair.status}：${pair.text}`);
const pairBody = JSON.parse(pair.text) as { ok: boolean; code: string; uri: string; qr: string; ttlSeconds: number };
if (!/^\d{8}$/.test(pairBody.code)) fail(`配对码格式不对：${pairBody.code}`);
if (!pairBody.uri.startsWith('dsh://pair?')) fail(`二维码内容不是 App 协议：${pairBody.uri}`);
if (!pairBody.qr.startsWith('data:image/svg+xml;base64,')) fail('二维码不是 SVG data URL');
if (pairBody.ttlSeconds <= 0) fail(`二维码 TTL 异常：${pairBody.ttlSeconds}`);
const uriRelay = new URL(pairBody.uri).searchParams.get('relay');
if (uriRelay !== relayOrigin) fail(`二维码里的中继地址应为 ${relayOrigin}，实际 ${uriRelay}`);
ok(`③ 生成二维码：码 ${pairBody.code}，${pairBody.ttlSeconds}s 有效，内容 ${pairBody.uri.slice(0, 64)}…`);

// --- ④ 手机兑换这个码 ---
const phone = await relay.store.login('e2e@example.test', 'long-test-password', '', false, '手机', true);
const redeemed = relay.store.pair(relay.store.session(phone.token, 'native'), pairBody.code);
if (redeemed !== hostId) fail(`手机兑换得到 ${redeemed}，期望 ${hostId}`);
const again = relay.store.pair(relay.store.session(phone.token, 'native'), pairBody.code);
if (again !== null) fail('配对码应当一次性，重复兑换不该成功');
ok(`④ 手机用该码兑换成功 → 授权到主机 ${hostId.slice(0, 8)}…（重复兑换被拒）`);

// --- ⑤ 鉴权失败即拒绝 ---
delete uiCtx.connection;
const denied = await request('GET', `${UI_PREFIX}/embed`);
if (denied.status !== 503) fail(`缺 connection 服务时应 503，实际 ${denied.status}`);
ok('⑤ 拿不到浏览器会话判定时 fail-closed（503），不默认放行');

console.log(`\n验收结论：插件 UI 全链路通过（TLS 前置 ${relayOrigin} · 状态 ${svc.status().state} · 中继来源 ${svc.settings().relaySource}）`);
for (const fn of cleanup.reverse()) { try { await fn(); } catch { /* 清理失败不影响结论 */ } }
if (process.exitCode === 0) console.log('全部通过。');
