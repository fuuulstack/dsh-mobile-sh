import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Relay, configFromEnv, requireCredentials, PASSWORD_MODE_EMAIL, type RelayConfig } from '../packages/relay/src/server.js';
import { secret, totp, uuid } from '../packages/relay/src/crypto.js';
import { capacityFor, capacityFromEnv, DEFAULT_CAPACITY } from '../packages/relay/src/limits.js';
import { trustedPeer } from '../packages/relay/src/proxy.js';

/**
 * 「一行命令就能跑起来」这条承诺的最小可执行定义。
 *
 * <p>它包含三件互相独立、但少一件就不成立的事：
 * <ol>
 *   <li>**只配一个密码**：`RELAY_PASSWORD=…` 之后，管理员密码、主机注册密码、
 *       管理员邮箱全部有合法默认值，服务能起、能登录、能注册主机。</li>
 *   <li>**不配地址也能给出正确链接**：容器不知道自己叫什么，`RELAY_BASE_URL`
 *       必须可选，绝对链接（二维码、深链）按请求的 `Host` + `X-Forwarded-Proto`
 *       现推，Cookie 的 `Secure` / `__Host-` 也按请求决定。</li>
 *   <li>**可选参数只影响容量**：`RELAY_MAX_STREAMS` 之类只收窄本端接纳能力，
 *       不改变线格式——否则对端会以 4422 `unsupported-window` 断连。</li>
 * </ol>
 *
 * <p>为什么这些事值得写成测试而不是写进 README：默认值错了的表现不是报错，而是
 * 「能起但登录 403」「二维码扫出来是 localhost」「https 站点上 Cookie 被浏览器丢掉」
 * 这些各自看起来都像别的 bug 的现象。默认值必须被钉住。
 */

/** 会被 configFromEnv 读到的变量。`DATABASE_URL` 没有 RELAY_ 前缀，漏了它前一个用例的临时库会串味。 */
const OWNED = (k: string) => k.startsWith('RELAY_') || k === 'DATABASE_URL' || k === 'PORT';
function snapshot(): Record<string, string | undefined> {
  const out: Record<string, string | undefined> = {};
  for (const k of Object.keys(process.env)) if (OWNED(k)) out[k] = process.env[k];
  return out;
}
function apply(vars: Record<string, string | undefined>): void {
  for (const k of Object.keys(process.env)) if (OWNED(k)) delete process.env[k];
  for (const [k, v] of Object.entries(vars)) if (v !== undefined) process.env[k] = v;
}
function restore(saved: Record<string, string | undefined>): void {
  for (const k of Object.keys(process.env)) if (OWNED(k)) delete process.env[k];
  for (const [k, v] of Object.entries(saved)) if (v !== undefined) process.env[k] = v;
}

/**
 * 每个用例都拿一个干净的密钥文件：`loadKey` 会自动生成，不该在仓库里留下垃圾。
 *
 * <p>清理必须是 best-effort。Windows 上删不掉仍被 sqlite 句柄打开的库文件，而 `after`
 * 钩子按注册顺序跑——`keyDir` 在用例体的 `relay.close()` 之前注册，于是 `rmSync` 先跑、
 * 必然 `EPERM`。它抛出去的后果不只是用例变红：钩子一抛，同一用例后面那个
 * `relay.close()` 就不再执行，监听中的 server 把事件循环钉住，`node --test` 跑完
 * 却永不退出（`npm run check` 会一直挂着）。真正的收尾交给 `relay.close()`，
 * 临时目录留给系统清理。
 */
function keyDir(t: { after: (fn: () => void) => void }): string {
  const dir = mkdtempSync(join(tmpdir(), 'relay-bootstrap-'));
  t.after(() => {
    try { rmSync(dir, { recursive: true, force: true }); }
    catch { /* Windows：库文件仍被 sqlite 打开的句柄占着，见上 */ }
  });
  return dir;
}
function config(t: { after: (fn: () => void) => void }, vars: Record<string, string | undefined>): RelayConfig {
  const dir = keyDir(t), saved = snapshot();
  try { apply({ RELAY_SECRET_KEY_FILE: join(dir, 'relay.key'), DATABASE_URL: join(dir, 'relay.db'), ...vars }); return configFromEnv(); }
  finally { restore(saved); }
}

function request(port: number, path: string, options: { method?: string; headers?: Record<string, string>; body?: string } = {}) {
  return new Promise<{ status: number; body: string; headers: http.IncomingHttpHeaders }>((resolve, reject) => {
    const req = http.request(
      { hostname: '127.0.0.1', port, path, method: options.method ?? 'GET', headers: options.headers ?? {} },
      res => { const c: Buffer[] = []; res.on('data', b => c.push(b)); res.on('end', () => resolve({ status: res.statusCode!, body: Buffer.concat(c).toString('utf8'), headers: res.headers })); },
    );
    req.on('error', reject);
    req.end(options.body);
  });
}
/** 从 `set-cookie` 里取某个 cookie 的值（不含属性）。 */
function cookie(list: string | string[] | undefined, name: string): string | undefined {
  const all = Array.isArray(list) ? list : list ? [list] : [];
  for (const c of all) { const first = c.split(';')[0]!; if (first.startsWith(`${name}=`)) return first.slice(name.length + 1); }
  return undefined;
}
const form = (o: Record<string, string>) => new URLSearchParams(o).toString();
const FORM_HEADERS = { 'content-type': 'application/x-www-form-urlencoded' };

test('一个 RELAY_PASSWORD 推导出登录账号与主机注册密码', t => {
  const c = config(t, { RELAY_PASSWORD: 'correct-horse-battery' });
  assert.equal(c.passwordMode, true);
  assert.equal(c.baseUrl, undefined, '地址不配时必须是 undefined，而不是空串或 localhost');
  assert.equal(c.adminEmail, PASSWORD_MODE_EMAIL);
  assert.equal(c.adminPassword, 'correct-horse-battery');
  assert.equal(c.enrollAccount, PASSWORD_MODE_EMAIL, '主机注册也要落在管理员账号下，否则进去看不到主机');
  assert.equal(c.enrollPassword, 'correct-horse-battery');
  assert.equal(c.requireTotp, false, '要用户先装验证器 App 才肯起服务，这个「一行命令」就不成立了');
  assert.equal(c.requireTotpExplicit, true, '配了密码就是操作者对这个部署的显式选择，别被 TOTP 护栏拦下');
  const saved = snapshot();
  try { apply({ RELAY_SECRET_KEY_FILE: join(keyDir(t), 'k'), RELAY_PASSWORD: 'pw' }); assert.equal(configFromEnv().database, 'data/relay.db'); }
  finally { restore(saved); }
  // 密钥文件不配也要能起：容器里没人会先跑 `init-key`。
  assert.equal((c.key as Buffer).length, 32);
});

test('显式变量永远压过推导值，TOTP 也能被显式打开', t => {
  const c = config(t, {
    RELAY_PASSWORD: 'pw', RELAY_ADMIN_EMAIL: 'Me@Example.Test',
    RELAY_ENROLL_ACCOUNT: 'Hosts@Example.Test', RELAY_ENROLL_PASSWORD: 'other-pw',
  });
  assert.equal(c.adminEmail, 'me@example.test', '邮箱统一小写，否则大小写不同的两份账号会互相看不见');
  assert.equal(c.enrollAccount, 'hosts@example.test');
  assert.equal(c.enrollPassword, 'other-pw');
  assert.equal(config(t, { RELAY_PASSWORD: 'pw', RELAY_REQUIRE_TOTP: 'true' }).requireTotp, true);
  assert.equal(config(t, { RELAY_PASSWORD: 'pw', RELAY_REQUIRE_TOTP: 'false' }).requireTotp, false);
});

test('老的三组变量照旧可用，没配密码时不进单一密码模式', t => {
  const c = config(t, { RELAY_BASE_URL: 'https://relay.example.test', RELAY_ADMIN_EMAIL: 'a@example.test', RELAY_ADMIN_PASSWORD: 'admin-pw' });
  assert.equal(c.passwordMode, false);
  assert.equal(c.baseUrl, 'https://relay.example.test');
  assert.equal(c.requireTotp, true, '没配单一密码就必须保留 TOTP 护栏');
  assert.equal(c.requireTotpExplicit, false);
  assert.equal(c.enrollPassword, undefined);
});

test('半开配置给出指名道姓的错误，而不是起一半', t => {
  // 什么都没配：`configFromEnv` 是纯映射（`create-account` 也要用它），不在这里判；
  // `serve` 用 requireCredentials 当场拒绝，否则会起一个谁也登不进去的服务。
  const bare = config(t, {});
  assert.equal(bare.adminEmail, undefined);
  assert.throws(() => requireCredentials(bare), /RELAY_PASSWORD is required/);
  assert.doesNotThrow(() => requireCredentials(config(t, { RELAY_PASSWORD: 'pw' })));
  assert.throws(() => config(t, { RELAY_ADMIN_PASSWORD: 'pw' }), /RELAY_ADMIN_EMAIL is required/);
  assert.throws(() => config(t, { RELAY_ENROLL_PASSWORD: 'pw' }), /RELAY_ENROLL_ACCOUNT is required/);
  assert.throws(() => config(t, { RELAY_ENROLL_ACCOUNT: 'a@example.test' }), /RELAY_ENROLL_PASSWORD is required/);
});

test('容量参数缺省等于原来的常量，只会往里收窄', t => {
  assert.deepEqual(capacityFromEnv({}), DEFAULT_CAPACITY);
  const small = capacityFor(256);
  assert.ok(small.nodeReservation < DEFAULT_CAPACITY.nodeReservation, '768MiB 的标定值放到 256MiB 机器上必须缩');
  assert.ok(small.maxBody <= DEFAULT_CAPACITY.maxBody);
  // 条数不缩：4GB 的机器也不想挂 150 台电脑。只能显式配。
  assert.equal(small.maxHosts, DEFAULT_CAPACITY.maxHosts);
  assert.equal(capacityFromEnv({ RELAY_MAX_HOSTS: '8' }).maxHosts, 8, 'RELAY_MAX_HOSTS 是唯一能改条数的旋钮');
  assert.throws(() => capacityFromEnv({ RELAY_MAX_STREAMS: '1024' }), /between 1 and 64/);
});

test('来源信任只在声明了反代时收窄，直连部署不把自己挡在门外', () => {
  // 直连（没声明反代）必须放行任何来源：容器里 remoteAddress 是 Docker 网桥地址，
  // 一旦在这里拒绝，`docker run -e RELAY_PASSWORD …` 起来就是「能起服务但一个页面
  // 都打不开」，全部 403 · untrusted-proxy。
  assert.equal(trustedPeer('192.168.215.1', undefined), true);
  assert.equal(trustedPeer('203.0.113.9', ''), true);
  // 声明了反代就只认它（以及 loopback，方便健康检查与运维本机排查）。
  assert.equal(trustedPeer('127.0.0.1', '127.0.0.1'), true);
  assert.equal(trustedPeer('::1', '127.0.0.1'), true);
  assert.equal(trustedPeer('10.1.2.3', '127.0.0.1'), false);
  // `*`（trustAnyPeer）与测试模式不设闸门。
  assert.equal(trustedPeer('203.0.113.9', undefined, false, true), true);
  assert.equal(trustedPeer('203.0.113.9', '127.0.0.1', true), true);
});

test('TOTP 只看配置：关着时休眠 secret 不拦人，开着时缺码就是 401', async t => {
  // 这个用例钉的是一整条真实故障链：`provision` 曾经总是建带 TOTP secret 的账号，
  // 而 `store.login` 的判据是「账号里有 secret 就必须给码」——于是单密码模式的容器
  // 一边打印 `TOTP=OFF`，一边要求一个从未展示过用途的验证码，用户看到的是
  // 「一行命令起来了，但登录 401 · login-failed」。
  const login = (port: number, body: object) => request(port, '/native/v2/login', {
    method: 'POST', headers: { host: 'relay.example.test', 'content-type': 'application/json' }, body: JSON.stringify(body),
  });
  const password = 'correct-horse-battery';
  const creds = (code?: string) => ({ email: PASSWORD_MODE_EMAIL, password, ...(code === undefined ? {} : { code }) });

  // ① 配置关：账号里躺着 secret（升级前建的），登录仍然只要密码。
  const off = new Relay(config(t, { RELAY_PASSWORD: password }));
  const offPort = await off.listen(0);
  t.after(async () => { await off.close(); });
  const dormant = await off.store.createAccount(PASSWORD_MODE_EMAIL, password, true);
  assert.ok(dormant.totp, '前提：账号里确实有一把 secret');
  const mapped = await login(offPort, creds());
  assert.equal(mapped.status, 200, mapped.body);

  // ② 配置开：同一个账号、同一个密码，缺码必须拒绝，给码才进得去。
  const on = new Relay(config(t, { RELAY_PASSWORD: password, RELAY_REQUIRE_TOTP: 'true' }));
  const onPort = await on.listen(0);
  t.after(async () => { await on.close(); });
  const enabled = await on.store.createAccount(PASSWORD_MODE_EMAIL, password, true);
  assert.equal((await login(onPort, creds())).status, 401, '开着 TOTP 却只给密码，必须拒绝');
  assert.equal((await login(onPort, creds(totp(enabled.totp!)))).status, 200);

  // ③ 后来才打开开关：账号里没有 secret，`ensureTotp` 补一把——否则谁也别想再进去
  //    （界面里没有「给已有账号补注册验证器」的入口，只有开关本身）。
  const later = new Relay(config(t, { RELAY_PASSWORD: password, RELAY_REQUIRE_TOTP: 'true' }));
  const laterPort = await later.listen(0);
  t.after(async () => { await later.close(); });
  const bare = await later.store.createAccount(PASSWORD_MODE_EMAIL, password, false);
  assert.equal((await login(laterPort, creds())).status, 401);
  const added = later.store.ensureTotp(bare.id);
  assert.ok(added, '开关打开时必须能把这个账号的 secret 补出来');
  assert.equal(later.store.ensureTotp(bare.id), undefined, '已有 secret 时不能再造一把（旧验证器会静默失效）');
  assert.equal((await login(laterPort, creds(totp(added!)))).status, 200);
});

test('未钉地址也能起服务：origin 与安全属性都按请求推导', async t => {
  const relay = new Relay(config(t, { RELAY_PASSWORD: 'correct-horse-battery' }));
  const port = await relay.listen(0);
  t.after(async () => { await relay.close(); });
  assert.equal(relay.pinned, false);
  assert.equal(relay.origin, '', '没配地址时不能编一个 origin 出来');
  assert.equal(relay.routing, 'path', '连自己叫什么都不知道，拼不出 <id>.<域名>，只能走 /h/<id>');

  await relay.store.createAccount(PASSWORD_MODE_EMAIL, 'correct-horse-battery', false);

  // ① https 反代终止 TLS：Cookie 必须带 Secure 与 __Host- 前缀。
  const secure = await request(port, '/login', { headers: { host: 'relay.example.test', 'x-forwarded-proto': 'https' } });
  assert.equal(secure.status, 200);
  const secureCookie = (secure.headers['set-cookie'] as unknown as string[])[0]!;
  assert.match(secureCookie, /^__Host-relay-login=/, secureCookie);
  assert.match(secureCookie, /Secure/, 'https 站点上没有 Secure，浏览器会整条丢弃');

  // ② 明文直连（局域网里 `http://192.168.x.x:8080`）：不能带 Secure，也不能带 __Host-。
  const plain = await request(port, '/login', { headers: { host: '192.168.1.9:8080', 'x-forwarded-proto': 'http' } });
  const plainCookie = (plain.headers['set-cookie'] as unknown as string[])[0]!;
  assert.match(plainCookie, /^relay-login=/, '明文下带 __Host- 前缀也落不下（它要求 Secure）');
  assert.doesNotMatch(plainCookie, /Secure/);

  // ③ 登录：Origin 头只有 host 与推导出来的 origin 一致，scheme 不参与比较——
  //    中继看到的是 http（TLS 在反代处终止），浏览器写的是 https。
  const nonce = cookie(secure.headers['set-cookie'], '__Host-relay-login')!;
  const ok = await request(port, '/login', {
    method: 'POST',
    headers: {
      host: 'relay.example.test', 'x-forwarded-proto': 'https', ...FORM_HEADERS,
      origin: 'https://relay.example.test', cookie: `__Host-relay-login=${nonce}`,
    },
    body: form({ csrf: nonce, email: PASSWORD_MODE_EMAIL, password: 'correct-horse-battery' }),
  });
  assert.equal(ok.status, 303, ok.body);
  assert.match((ok.headers.location as string), /^\//, '重定向必须是相对路径');
  const account = cookie(ok.headers['set-cookie'], '__Host-relay-account');
  assert.ok(account, '登录成功必须落 account cookie');

  // ④ 别的站点的页面来打接口 → 403（这是 originCheck 唯一真正要防的东西）。
  const evil = await request(port, '/login', {
    method: 'POST',
    headers: { host: 'relay.example.test', 'x-forwarded-proto': 'https', ...FORM_HEADERS, origin: 'https://evil.test', cookie: `__Host-relay-login=${nonce}` },
    body: form({ csrf: nonce, email: PASSWORD_MODE_EMAIL, password: 'correct-horse-battery' }),
  });
  assert.equal(evil.status, 403);
  assert.match(evil.body, /origin/);
});

test('未钉地址时二维码与深链用的是这次请求的地址，不是 localhost', async t => {
  const relay = new Relay(config(t, { RELAY_PASSWORD: 'correct-horse-battery' }));
  const port = await relay.listen(0);
  t.after(async () => { await relay.close(); });
  await relay.store.createAccount(PASSWORD_MODE_EMAIL, 'correct-horse-battery', false);
  const headers = { host: 'relay.example.test', 'x-forwarded-proto': 'https' };

  const login = await request(port, '/login', { headers });
  const nonce = cookie(login.headers['set-cookie'], '__Host-relay-login')!;
  const signed = await request(port, '/login', {
    method: 'POST', headers: { ...headers, ...FORM_HEADERS, origin: 'https://relay.example.test', cookie: `__Host-relay-login=${nonce}` },
    body: form({ csrf: nonce, email: PASSWORD_MODE_EMAIL, password: 'correct-horse-battery' }),
  });
  const accountCookie = cookie(signed.headers['set-cookie'], '__Host-relay-account')!;
  const account = relay.store.session(accountCookie, 'account');
  const hostTunnelToken = secret();
  const { hostId } = relay.store.enroll(relay.store.invitation(account), {
    operationId: uuid(), hostPublicId: uuid(), hostTunnelToken, alias: '测试主机',
  });

  // 页面的 csrf 与表单里的不同（每次刷新都换），统一从 HTML 里取。
  const page = await request(port, '/', { headers: { ...headers, cookie: `__Host-relay-account=${accountCookie}` } });
  assert.equal(page.status, 200, page.body);
  const csrf = /name="csrf" value="([^"]+)"/.exec(page.body)![1]!;
  const authed = { ...headers, ...FORM_HEADERS, origin: 'https://relay.example.test', cookie: `__Host-relay-account=${accountCookie}` };

  // ① 设备配对二维码的落地链接：这是页面里以文本形态出现的绝对地址，能直接断言。
  const paired = await request(port, '/pair/create', { method: 'POST', headers: authed, body: form({ csrf, hostId }) });
  assert.equal(paired.status, 200, paired.body);
  assert.match(paired.body, /https:\/\/relay\.example\.test\/pair#[A-Za-z0-9_-]+/, '配对链接必须来自这次请求的 Host 与 X-Forwarded-Proto');
  assert.doesNotMatch(paired.body, /localhost/, '写死 localhost 时手机扫码后连不上任何东西');

  // ② 手机深链（dsh://pair?relay=…）在页面里只以二维码图片存在，所以这里断言
  //    「页面里没有任何写死的地址」——它和上面那条走的是同一个 originOf。
  const created = await request(port, '/device-code/create', { method: 'POST', headers: authed, body: form({ csrf, label: '测试机' }) });
  assert.equal(created.status, 200, created.body);
  assert.match(created.body, /[0-9]{8}/, '8 位配对码要能人工抄');
  assert.doesNotMatch(created.body, /localhost|relay\.invalid/);
});
