import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { Relay } from '../packages/relay/src/server.js';
import { secret, uuid } from '../packages/relay/src/crypto.js';

/**
 * 裸 IP 自建部署（`docker run -p 8080:8080`，对外是 `http://1.2.3.4:8080`）。
 *
 * <p>这套用例守的是三条互相牵连的假设，任何一条回退都会让「一行命令起服务」
 * 在真实机器上失效：
 * <ol>
 *   <li>**能起来**：`RELAY_BASE_URL=http://<ip>:<port>` 不再被
 *       `https-domain-required` 拒绝，但必须显式给 `RELAY_ALLOW_INSECURE=true`
 *       （否则等于把令牌与归档正文默认摊在明文链路上）。</li>
 *   <li>**路由得通**：`1.2.3.4` 挂不出子域，主机只能走 `/h/<hostId>/…` 前缀；
 *       `Host` 头在裸 IP 下永远是 `1.2.3.4:8080`，不能作为寻址依据。</li>
 *   <li>**Cookie 落得下**：`__Host-` 前缀要求 `Secure`，明文 http 下浏览器会整条
 *       丢弃 Set-Cookie，表现为「登录成功但立刻又回到登录页」。</li>
 * </ol>
 */

function request(port: number, path: string, options: { method?: string; headers?: Record<string, string>; body?: Buffer } = {}) {
  return new Promise<{ status: number; body: string; headers: http.IncomingHttpHeaders }>((resolve, reject) => {
    const req = http.request(
      { hostname: '127.0.0.1', port, path, method: options.method ?? 'GET', headers: options.headers ?? {} },
      res => {
        const chunks: Buffer[] = [];
        res.on('data', b => chunks.push(b));
        res.on('end', () => resolve({ status: res.statusCode!, body: Buffer.concat(chunks).toString('utf8'), headers: res.headers }));
      },
    );
    req.on('error', reject);
    req.end(options.body);
  });
}

test('bare IP over plain http starts only with the explicit insecure switch', async () => {
  // 明文裸 IP：没有 RELAY_ALLOW_INSECURE 就必须拒绝启动。
  assert.throws(
    () => new Relay({ baseUrl: 'http://203.0.113.7:8080', database: ':memory:', key: Buffer.alloc(32, 5), requireTotp: false, testMode: true }),
    /https-domain-required/,
  );
  const relay = new Relay({
    baseUrl: 'http://203.0.113.7:8080', database: ':memory:', key: Buffer.alloc(32, 5),
    requireTotp: false, testMode: true, allowInsecure: true,
  });
  try {
    assert.equal(relay.insecure, true, '明文部署要自认明文，cookie 与日志都靠它分支');
    assert.equal(relay.routing, 'path', 'IP 字面量挂不出子域，auto 必须解析成路径路由');
    assert.equal(relay.origin, 'http://203.0.113.7:8080');
    // 端口是合法的：`docker run -p 8080:8080` 后面还有一层 NAT，不可能只给 443。
    assert.equal(relay.hostBase('a'.repeat(32)), `http://203.0.113.7:8080/h/${'a'.repeat(32)}`);
    assert.equal(relay.hostOrigin('a'.repeat(32)), 'http://203.0.113.7:8080', '路径模式下所有主机共用同一个 origin');
  } finally { await relay.close(); }
});

test('https domain keeps subdomain routing, https IP switches to path routing', async () => {
  const domain = new Relay({ baseUrl: 'https://relay.example.test', database: ':memory:', key: Buffer.alloc(32, 5), requireTotp: false, testMode: true });
  try {
    assert.equal(domain.routing, 'subdomain');
    assert.equal(domain.insecure, false);
    assert.equal(domain.hostBase('b'.repeat(32)), `https://${'b'.repeat(32)}.relay.example.test`);
  } finally { await domain.close(); }
  // 同一台机器换了地址形态，路由方式要跟着变，不需要额外配置。
  const ip = new Relay({ baseUrl: 'https://203.0.113.7', database: ':memory:', key: Buffer.alloc(32, 5), requireTotp: false, testMode: true });
  try {
    assert.equal(ip.routing, 'path', 'IP 上的 https 也挂不出子域');
    assert.equal(ip.hostBase('c'.repeat(32)), `https://203.0.113.7/h/${'c'.repeat(32)}`);
  } finally { await ip.close(); }
  // 显式指定优先于自动判定：把域名强行按路径路由（前置反代剥不掉子域时用得上）。
  const forced = new Relay({ baseUrl: 'https://relay.example.test', database: ':memory:', key: Buffer.alloc(32, 5), requireTotp: false, testMode: true, hostRouting: 'path' });
  try { assert.equal(forced.hostBase('d'.repeat(32)), `https://relay.example.test/h/${'d'.repeat(32)}`); } finally { await forced.close(); }
});

test('plain http deploy still refuses a half-open TOTP downgrade', () => {
  // `RELAY_REQUIRE_TOTP=false` 必须由操作者**显式**给：光是关掉 TOTP 不足以启动，
  // 否则「忘了配 TOTP」会被当成「决定不要 TOTP」。
  assert.throws(
    () => new Relay({ baseUrl: 'http://203.0.113.7:8080', database: ':memory:', key: Buffer.alloc(32, 5), requireTotp: false, allowInsecure: true }),
    /public-relay-requires-totp/,
  );
  const ok = new Relay({
    baseUrl: 'http://203.0.113.7:8080', database: ':memory:', key: Buffer.alloc(32, 5),
    requireTotp: false, requireTotpExplicit: true, allowInsecure: true,
  });
  return ok.close();
});

test('path routing rewrites /h/<hostId> and leaves the account plane on bare paths', async t => {
  const relay = new Relay({
    baseUrl: 'http://203.0.113.7:8080', database: ':memory:', key: Buffer.alloc(32, 5),
    requireTotp: false, testMode: true, allowInsecure: true,
  });
  const port = await relay.listen(0);
  t.after(async () => { await relay.close(); });
  await relay.store.createAccount('a@example.test', 'long-test-password', false);
  const login = await relay.store.login('a@example.test', 'long-test-password', '', false, '浏览器');
  const account = relay.store.session(login.token, 'account');
  const hostTunnelToken = secret();
  const { hostId } = relay.store.enroll(relay.store.invitation(account), {
    operationId: uuid(), hostPublicId: uuid(), hostTunnelToken, alias: '裸IP主机',
  });

  // 账户面：根路径就是账户页，`Host` 头是 `127.0.0.1:port` 也不影响判定。
  const health = await request(port, '/healthz');
  assert.equal(health.status, 200, '健康检查走账户面，容器 HEALTHCHECK 靠它');

  // 未登录进主机 → 303 到 `/h/<id>/__relay/start`（不是账户首页）。
  const boot = await request(port, `/h/${hostId}/`);
  assert.equal(boot.status, 303);
  assert.equal(boot.headers.location, `http://203.0.113.7:8080/h/${hostId}/__relay/start`, '裸 IP 下必须把浏览器送回带前缀的主机地址');

  // `/h/<id>/__relay/start` 走主机面：签发 handoff 并带 bind cookie 跳到账户面 /open。
  const start = await request(port, `/h/${hostId}/__relay/start`);
  assert.equal(start.status, 303);
  // 路径模式下这一步是**相对** Location：账户面与主机面同 origin，写绝对地址等于
  // 拿客户端可控的 Host 头拼跳转目标（开放重定向）。子域模式才需要绝对地址。
  assert.match(start.headers.location as string, /^\/open\?requestId=/);
  const bindCookie = (start.headers['set-cookie'] as unknown as string[])[0]!;
  assert.match(bindCookie, /^relay-bind=/, '明文部署不能带 __Host- 前缀');
  assert.doesNotMatch(bindCookie, /Secure/, '明文下带 Secure 会被浏览器整条丢弃');

  // 主机 id 不合法 → 404，不能被当成账户页去渲染。
  assert.equal((await request(port, '/h/not-a-host-id/')).status, 404);
  // 格式合法但库里没有：`/h/<id>` 已被认作主机面，于是走「未登录 → 去签发」的 303，
  // 签发那一步才因为主机不存在返回 404。关键是它**没有**落到账户首页。
  assert.equal((await request(port, '/h/' + 'f'.repeat(32) + '/')).status, 303);
  assert.equal((await request(port, '/h/' + 'f'.repeat(32) + '/__relay/start')).status, 404);
});

test('handoff from account page lands on the prefixed host path', async t => {
  const relay = new Relay({
    baseUrl: 'http://203.0.113.7:8080', database: ':memory:', key: Buffer.alloc(32, 5),
    requireTotp: false, testMode: true, allowInsecure: true,
  });
  const port = await relay.listen(0);
  t.after(async () => { await relay.close(); });
  await relay.store.createAccount('b@example.test', 'long-test-password', false);
  const web = await relay.store.login('b@example.test', 'long-test-password', '', false, '浏览器');
  const account = relay.store.session(web.token, 'account');
  const { hostId } = relay.store.enroll(relay.store.invitation(account), {
    operationId: uuid(), hostPublicId: uuid(), hostTunnelToken: secret(), alias: '第二台',
  });
  // 「打开主机」只对已授权的主机渲染，所以先把这个浏览器设备配对上。
  relay.store.pair(account, relay.store.createPair(account, hostId).code);

  // 账户页的主机列表：链接必须带 `/h/<id>` 前缀，否则裸 IP 下点进去是账户首页。
  const page = await request(port, '/', { headers: { cookie: `relay-account=${web.token}` } });
  assert.equal(page.status, 200);
  assert.ok(page.body.includes(`/h/${hostId}/__relay/start`), '主机列表的「打开主机」链接要带路径前缀');
  assert.ok(!page.body.includes(`${hostId}.203.0.113.7`), '绝不能拼出 IP 的「子域」');

  // /open（POST 那一跳才是真正签发 ticket 的页面）：表单 action 带前缀，
  // CSP form-action 只认 origin（不含路径）。
  const csrf = /name="csrf" value="([^"]+)"/.exec(page.body)![1]!;
  const handoff = relay.store.startHandoff(hostId);
  const open = await request(port, '/open', {
    method: 'POST',
    headers: {
      cookie: `relay-account=${web.token}; relay-bind=${handoff.bind}`,
      origin: 'http://203.0.113.7:8080',
      'content-type': 'application/x-www-form-urlencoded',
    },
    body: Buffer.from(new URLSearchParams({ csrf, requestId: handoff.requestId }).toString()),
  });
  assert.equal(open.status, 200);
  assert.ok(open.body.includes(`action="http://203.0.113.7:8080/h/${hostId}/__relay/session"`), '表单要提交到带前缀的主机地址');
  assert.match(open.headers['content-security-policy'] as string, /form-action http:\/\/203\.0\.113\.7:8080/);
});

test('secure deployment keeps the __Host- cookie prefix and Secure attribute', async t => {
  const relay = new Relay({ baseUrl: 'https://relay.example.test', database: ':memory:', key: Buffer.alloc(32, 5), requireTotp: false, testMode: true });
  const port = await relay.listen(0);
  t.after(async () => { await relay.close(); });
  await relay.store.createAccount('c@example.test', 'long-test-password', false);
  await relay.store.login('c@example.test', 'long-test-password', '', false, '浏览器');
  const { hostId } = relay.store.enroll(relay.store.invitation(relay.store.session((await relay.store.login('c@example.test', 'long-test-password', '', false, '网页')).token, 'account')), {
    operationId: uuid(), hostPublicId: uuid(), hostTunnelToken: secret(), alias: '域名主机',
  });
  const start = await request(port, '/__relay/start', { headers: { host: `${hostId}.relay.example.test` } });
  assert.equal(start.status, 303);
  const cookie = (start.headers['set-cookie'] as unknown as string[])[0]!;
  assert.match(cookie, /^__Host-relay-bind=/, 'https 部署必须保留 __Host- 前缀');
  assert.match(cookie, /Secure/);
});
