// 本机真实端到端联调环境：真中继（TLS 前置 8443）+ 真 DSH 插件 + 真会话记录。
// 与 scripts/android-fixture.ts 的区别：fixture 用内存中继和假上游；本脚本起的是产品自身的
// Relay + Store，供本机真实 DSH 实例（profile=mobilee2e）注册主机，安卓 App 直连本机 8443。
// 只监听 127.0.0.1，不对外暴露；TLS 证书复用 init-android-fixture.ps1 生成的 self-signed p12。
import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs';
import { randomBytes } from 'node:crypto';
import { gunzipSync } from 'node:zlib';
import http from 'node:http';
import https from 'node:https';
import net from 'node:net';
import { once } from 'node:events';
import { Relay } from '../packages/relay/src/server.js';

const BASE = 'https://relay.local.test';
const DIR = 'artifacts';
const DB = `${DIR}/local-e2e.db`;
const KEYFILE = `${DIR}/local-e2e.key`;
const P12 = `${DIR}/android-fixture.p12`;
const PASSPHRASE = 'local-fixture-only';
const EMAIL = 'e2e@example.test';
const PASSWORD = 'local-e2e-password';
const RELAY_PORT = Number(process.env.LOCAL_E2E_RELAY_PORT ?? 18080);
const FRONT_PORT = 8443;

const log = (...v: unknown[]) => console.log(new Date().toISOString().slice(11, 19), ...v);
const json = (res: http.ServerResponse, value: unknown) => {
  res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' });
  res.end(JSON.stringify(value, null, 2));
};

mkdirSync(DIR, { recursive: true });
if (!existsSync(KEYFILE)) writeFileSync(KEYFILE, randomBytes(32), { mode: 0o600 });
const key = readFileSync(KEYFILE);
// TLS 材料：优先 PEM 对（macOS LibreSSL 导不出 Node 24 接受的 p12——旧 RC2/3DES 被拒）；
// p12 仍是 Windows init-android-fixture.ps1 流程的产物。两处都没有才算没初始化。
const PEM_CERT = `${DIR}/fixture-cert.pem`;
const PEM_KEY = `${DIR}/fixture-key.pem`;
const usePem = existsSync(PEM_CERT) && existsSync(PEM_KEY);
if (!usePem && !existsSync(P12)) throw new Error(`缺少 TLS 证书：生成 ${PEM_CERT}/${PEM_KEY}（openssl 自签 localhost）或运行 scripts/init-android-fixture.ps1`);

// 真中继：testMode 下不要求 TOTP、不校验来源地址；生产形态 (https 域名 + TOTP) 由 nginx/systemd 承担。
const relay = new Relay({ baseUrl: BASE, database: DB, key, requireTotp: false, testMode: true, listenHost: '127.0.0.1', port: RELAY_PORT });
const store = relay.store;
const port = await relay.listen();
log(`relay listening on 127.0.0.1:${port} (baseUrl=${BASE})`);

let account = store.get('SELECT * FROM accounts WHERE email=?', EMAIL);
if (!account) {
  await store.createAccount(EMAIL, PASSWORD, false);
  account = store.get('SELECT * FROM accounts WHERE email=?', EMAIL);
  log(`account created: ${EMAIL}`);
}
const admin = store.get('SELECT * FROM accounts WHERE email=?', EMAIL)!;
// Store 的会话/邀请类接口都按 {account_id} 形状取账号（见 scripts/docker-provision.ts 的用法）。
const acct = () => ({ account_id: String(admin.id) });

// 一次性注册邀请（5 分钟有效、用后即废），交给插件通过环境变量读取。
const invitation = store.invitation(acct());
const state = {
  baseUrl: BASE,
  appEndpoint: `https://localhost:${FRONT_PORT}`,
  pluginRelayUrl: `wss://localhost:${FRONT_PORT}/tunnel`,
  email: EMAIL,
  password: PASSWORD,
  invitation,
  testCa: 'android/app/src/debug/res/raw/test_ca.pem',
  relayPort: port,
  frontPort: FRONT_PORT,
};
writeFileSync(`${DIR}/local-e2e-state.json`, JSON.stringify(state, null, 2));
log(`invitation minted (5 分钟内有效): ${invitation}`);

// 本机辅助接口（仅 127.0.0.1 的 TLS 前置暴露），便于取配对码与核对中继状态。
function localRoute(req: http.IncomingMessage, res: http.ServerResponse, url: URL) {
  if (url.pathname === '/__local/state') {
    json(res, {
      accounts: store.get('SELECT count(*) n FROM accounts')?.n,
      hosts: store.all('SELECT id,alias,public_id,revoked FROM hosts').map(h => ({
        ...h,
        // 隧道在线状态：中继的 peers 表在 Proxy 实例上，插件连接后 ready=true。
        online: !!(relay.proxy as unknown as { peers: Map<string, { ready?: boolean }> }).peers.get(String(h.id))?.ready,
      })),
      devices: store.all('SELECT id,label,revoked FROM devices'),
      grants: store.all('SELECT host_id,device_id,revoked FROM grants'),
      tunnels: store.get('SELECT count(*) n FROM tickets')?.n,
    });
    return;
  }
  if (url.pathname === '/__local/invitation') {
    const token = store.invitation(acct());
    log(`invitation re-minted: ${token}`);
    json(res, { invitation: token });
    return;
  }
  if (url.pathname === '/__local/pair') {
    const hostId = url.searchParams.get('hostId') ?? (store.get('SELECT id FROM hosts WHERE revoked=0 ORDER BY rowid DESC')?.id as string | undefined);
    if (!hostId) { json(res, { error: 'no-host' }); return; }
    const pair = store.createPair(acct(), hostId);
    log(`pair code for ${hostId}: ${pair.code}`);
    json(res, pair);
    return;
  }
  // 设备登录码（device_codes 表；App 登录 POST /native/v2/device-code 消费的就是它）。
  // 与 /__local/pair 的「主机配对码」是两张表两个流程，不通用——别再拿错（2026-10-26 实测踩坑）。
  if (url.pathname === '/__local/device-code') {
    const label = url.searchParams.get('label') ?? '验收设备';
    const device = store.createDeviceCode(acct(), label);
    log(`device code for login: ${device.code} (有效期 ${Math.round((device.expires - Date.now()) / 60000)} 分钟)`);
    json(res, device);
    return;
  }
  json(res, { error: 'unknown-local-route' });
}

// TLS 前置：证书用 self-signed test CA；强制 Host 为 relay.local.test（中继按 Host 路由账户面/主机面）。
const front = https.createServer(usePem
  ? { key: readFileSync(PEM_KEY), cert: readFileSync(PEM_CERT) }
  : { pfx: readFileSync(P12), passphrase: PASSPHRASE }, (req, res) => {
  const url = new URL(req.url ?? '/', `https://localhost:${FRONT_PORT}`);
  if (url.pathname.startsWith('/__local/')) return localRoute(req, res, url);
  // 调试用：把 native 请求（含登录体）打进日志，便于核对 App 实际发送的字段。
  if (url.pathname.startsWith('/native/')) {
    const chunks: Buffer[] = [];
    req.on('data', c => chunks.push(c as Buffer));
    req.on('end', () => {
      const raw = Buffer.concat(chunks);
      log(`${req.method} ${url.pathname} <- ${raw.toString('utf8').slice(0, 1200)}`);
      // 关键：转发前删掉 accept-encoding，否则宿主用 gzip 回包，日志里读不到拒绝原因。
      const fwdHeaders: Record<string, unknown> = { ...req.headers, host: new URL(BASE).host, 'content-length': String(raw.length) };
      delete fwdHeaders['accept-encoding'];
      delete fwdHeaders['Accept-Encoding'];
      const proxy = http.request({ host: '127.0.0.1', port, path: req.url, method: req.method, headers: fwdHeaders as never }, up => {
        // 回包也打进日志：RPC 的宿主拒绝原因只在这里可见（App 侧只看到 Toast）。
        const out: Buffer[] = [];
        up.on('data', c => out.push(c as Buffer));
        up.on('end', () => {
          let text = Buffer.concat(out).toString('utf8');
          if (String(up.headers['content-encoding'] ?? '').includes('gzip')) {
            try { text = gunzipSync(Buffer.concat(out)).toString('utf8'); } catch { /* 保持原文 */ }
          }
          log(`${req.method} ${url.pathname} -> ${up.statusCode} ${text.slice(0, 1200)}`);
        });
        res.writeHead(up.statusCode!, up.headers);
        up.pipe(res);
      });
      proxy.on('error', e => { log('proxy error', e.message); res.writeHead(502); res.end(); });
      proxy.end(raw);
    });
    return;
  }
  const proxy = http.request({ host: '127.0.0.1', port, path: req.url, method: req.method, headers: { ...req.headers, host: new URL(BASE).host } }, up => {
    res.writeHead(up.statusCode!, up.headers);
    up.pipe(res);
  });
  proxy.on('error', e => { log('proxy error', e.message); res.writeHead(502); res.end(); });
  req.pipe(proxy);
});
front.on('upgrade', (req, socket, head) => {
  const raw = net.connect(port, '127.0.0.1', () => {
    const headers = [`GET ${req.url} HTTP/1.1`, `Host: ${new URL(BASE).host}`];
    for (const [k, v] of Object.entries(req.headers)) if (k !== 'host') headers.push(`${k}: ${v}`);
    raw.write(headers.join('\r\n') + '\r\n\r\n');
    if (head.length) raw.write(head);
    socket.pipe(raw).pipe(socket);
  });
  raw.on('error', e => { log('upgrade error', e.message); socket.destroy(); });
  socket.on('error', () => raw.destroy());
  socket.once('close', () => raw.destroy());
});
front.listen(FRONT_PORT, '127.0.0.1');
// Node 的默认 keep-alive 只有 5s，比 App 的 8s 探测间隔还短，于是每轮探测都撞上
// 一条已被对端关掉的池化 socket（unexpected end of stream）。生产前面是 nginx
// （keepalive_timeout 75s），这里对齐到同一量级，别让本地环境造出生产没有的抖动。
front.keepAliveTimeout = 65_000;
await once(front, 'listening');
log(`TLS front listening on https://localhost:${FRONT_PORT} (证书: ${P12})`);
log(`App 端点: ${state.appEndpoint} | 插件 relayUrl: ${state.pluginRelayUrl}`);

const stop = async () => { log('shutting down'); front.close(); await relay.close(); process.exit(0); };
process.on('SIGINT', () => void stop());
process.on('SIGTERM', () => void stop());
