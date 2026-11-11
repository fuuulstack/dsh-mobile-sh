import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { createHash } from 'node:crypto';
import { existsSync, mkdtempSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Relay } from '../packages/relay/src/server.js';
import { secret, uuid } from '../packages/relay/src/crypto.js';
import { RELEASE_PREFIX } from '../packages/relay/src/release.js';

/**
 * 中继自己托管 APK 分发这条链路。
 *
 * <p>为什么值得单独一组：这是中继第一次**对外提供二进制文件**。文件下载与 JSON 接口
 * 的失败模式完全不同——少一个 `accept-ranges` 手机就没法续传、`content-length` 差一
 * 个字节 OkHttp 会读超时、`.tmp` 没 rename 会留下「看着能下、装上就崩」的残包。这些
 * 在单测里全看不出来，只有真的发一次 HTTP 请求、真的读文件才暴露。
 */

interface Booted {
  relay: Relay;
  port: number;
  hostId: string;
  token: string;
  dir: string;
  close: () => Promise<void>;
}

async function boot(options: { pinned?: boolean; keep?: number } = {}): Promise<Booted> {
  const dir = mkdtempSync(join(tmpdir(), 'relay-release-'));
  const relay = new Relay({
    ...(options.pinned ? { baseUrl: 'https://relay.example.test' } : {}),
    database: ':memory:',
    key: Buffer.alloc(32, 7),
    requireTotp: false,
    testMode: true,
    releaseDir: join(dir, 'releases'),
    releaseKeep: options.keep ?? 5,
  });
  // 先把账号与主机造好、最后才 listen：夹具中途失败时不会有「已经 accept 连接、
  // 但测试已经放弃」的半成品服务器把事件循环吊住（第一次跑就是这样挂满 44 秒的）。
  const account = await relay.store.createAccount('admin@relay.local', 'relay-test-password', false);
  const token = secret(32);
  const { hostId } = relay.store.enroll(relay.store.invitation({ account_id: account.id }), {
    operationId: uuid(), hostPublicId: uuid(), hostTunnelToken: token, alias: '发布机',
  });
  const port = await relay.listen(0);
  return {
    relay, port, hostId, token, dir,
    close: async () => { await relay.close(); rmSync(dir, { recursive: true, force: true }); },
  };
}

function request(
  port: number,
  path: string,
  options: { method?: string; headers?: Record<string, string>; body?: Buffer } = {},
): Promise<{ status: number; body: Buffer; headers: http.IncomingHttpHeaders }> {
  return new Promise((resolve, reject) => {
    const req = http.request(
      { hostname: '127.0.0.1', port, path, method: options.method ?? 'GET', headers: options.headers ?? {} },
      res => {
        const chunks: Buffer[] = [];
        res.on('data', (b: Buffer) => chunks.push(b));
        res.on('end', () => resolve({ status: res.statusCode!, body: Buffer.concat(chunks), headers: res.headers }));
      },
    );
    req.on('error', reject);
    req.end(options.body);
  });
}

function fakeApk(size: number, seed: number): Buffer {
  // 内容不重要，字节分布要够散：sha256 必须能区分两个包。
  const buf = Buffer.alloc(size);
  for (let i = 0; i < size; i++) buf[i] = (i * 31 + seed) & 0xff;
  return buf;
}

function sha256(buf: Buffer): string {
  return createHash('sha256').update(buf).digest('hex');
}

async function publish(
  booted: Booted,
  payload: Buffer,
  version: string,
  extra: { notes?: string; sha?: string; token?: string; hostId?: string; host?: string } = {},
) {
  const params = new URLSearchParams({
    hostId: extra.hostId ?? booted.hostId,
    version,
    ...(extra.notes !== undefined ? { notes: extra.notes } : {}),
  });
  return request(booted.port, `/control/release?${params}`, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${extra.token ?? booted.token}`,
      'content-type': 'application/vnd.android.package-archive',
      // 钉住域名时请求必须真是发给那个域名的：子域路由靠 Host 认主机，用 127.0.0.1
      // 打进去会被判成「不属于这个中继」，那是正确行为、不是缺陷。
      ...(extra.host ? { host: extra.host } : {}),
      ...(extra.sha ? { 'x-content-sha256': extra.sha } : {}),
    },
    body: payload,
  });
}

test('空台账时清单是 404；发布之后清单指向本中继自己的下载面', async () => {
  const b = await boot();
  try {
    const empty = await request(b.port, `${RELEASE_PREFIX}/version.json`);
    assert.equal(empty.status, 404, '一个版本都没发过时，清单不能谎报');
    assert.match(empty.body.toString(), /release-missing/);

    const payload = fakeApk(4096, 1);
    const put = await publish(b, payload, '0.3.1', { notes: '第一版\n修了两处' });
    assert.equal(put.status, 200);
    const result = JSON.parse(put.body.toString());
    assert.equal(result.version, '0.3.1');
    assert.equal(result.size, payload.length);
    assert.equal(result.sha256, sha256(payload), '服务端自己算一遍摘要，不信任客户端');

    const manifest = await request(b.port, `${RELEASE_PREFIX}/version.json`);
    assert.equal(manifest.status, 200);
    const release = JSON.parse(manifest.body.toString());
    assert.equal(release.latest, '0.3.1');
    assert.equal(release.size, payload.length);
    assert.equal(release.sha256, sha256(payload));
    // 说明文字收敛成单行：App 那行是单行省略号，换行会被吃掉一半。
    assert.equal(release.notes, '第一版 修了两处');
    // 下载地址必须指到**这台中继**（未钉地址时按请求 Host 推），否则「切到中继」等于没切。
    assert.match(release.url, new RegExp(`^http://127\\.0\\.0\\.1:${b.port}${RELEASE_PREFIX}/dsh-mobile-0\\.3\\.1\\.apk$`));
    assert.match(release.latestUrl, new RegExp(`/dsh-mobile-0\\.3\\.1|latest\\.apk$`));
    assert.ok(!release.publishedAt.includes('.'), 'publishedAt 只到秒，毫秒位是噪音');
  } finally { await b.close(); }
});

test('包本体可以整块下载，也能用 Range 续传同一段字节', async () => {
  const b = await boot();
  try {
    const payload = fakeApk(10_000, 3);
    await publish(b, payload, '0.3.2');

    const full = await request(b.port, `${RELEASE_PREFIX}/dsh-mobile-0.3.2.apk`);
    assert.equal(full.status, 200);
    assert.equal(full.headers['content-type'], 'application/vnd.android.package-archive');
    assert.equal(full.headers['accept-ranges'], 'bytes', '没有这一行，手机的续传器就不敢续了');
    assert.equal(Number(full.headers['content-length']), payload.length);
    assert.ok(full.body.equals(payload), '字节必须一模一样，否则 sha256 校验会失败');

    const part = await request(b.port, `${RELEASE_PREFIX}/dsh-mobile-0.3.2.apk`, {
      headers: { range: 'bytes=4000-4999' },
    });
    assert.equal(part.status, 206);
    assert.equal(part.headers['content-range'], `bytes 4000-4999/${payload.length}`);
    assert.ok(part.body.equals(payload.subarray(4000, 5000)), '断点续传取到的是同一段字节');

    const bad = await request(b.port, `${RELEASE_PREFIX}/dsh-mobile-0.3.2.apk`, {
      headers: { range: `bytes=${payload.length}-` },
    });
    assert.equal(bad.status, 416, '越界的 Range 必须是 416，而不是悄悄给整包');
    assert.equal(bad.headers['content-range'], `bytes */${payload.length}`);
  } finally { await b.close(); }
});

test('latest.apk 永远指向最后发布的那个版本', async () => {
  const b = await boot();
  try {
    await publish(b, fakeApk(1024, 5), '0.3.3');
    const second = fakeApk(2048, 9);
    await publish(b, second, '0.3.4');

    const alias = await request(b.port, `${RELEASE_PREFIX}/latest.apk`);
    assert.equal(alias.status, 200);
    assert.ok(alias.body.equals(second), 'latest.apk 必须跟着最新版本走，手工分发靠它');
    const manifest = JSON.parse((await request(b.port, `${RELEASE_PREFIX}/version.json`)).body.toString());
    assert.equal(manifest.latest, '0.3.4');
  } finally { await b.close(); }
});

test('保留策略只留 keep 个版本，文件从卷上一起删掉', async () => {
  const b = await boot({ keep: 2 });
  try {
    for (const [index, version] of ['0.3.5', '0.3.6', '0.3.7'].entries()) {
      await publish(b, fakeApk(512 + index, index), version);
    }
    const list = JSON.parse((await request(b.port, `/control/release?hostId=${b.hostId}`, {
      headers: { authorization: `Bearer ${b.token}` },
    })).body.toString());
    assert.deepEqual(list.items.map((i: { latest: string }) => i.latest).sort(), ['0.3.6', '0.3.7']);

    const gone = await request(b.port, `${RELEASE_PREFIX}/dsh-mobile-0.3.5.apk`);
    assert.equal(gone.status, 404, '被裁掉的版本不能再被下载');
    assert.equal(existsSync(join(b.dir, 'releases', 'dsh-mobile-0.3.5.apk')), false,
      '台账删了、卷上的包也要删，否则盘会一直涨');
    // 只留下 keep 个文件，没有 .tmp 残渣。
    assert.deepEqual(readdirSync(join(b.dir, 'releases')).sort(), ['dsh-mobile-0.3.6.apk', 'dsh-mobile-0.3.7.apk']);
  } finally { await b.close(); }
});

test('凭据不对、缺 hostId、版本号带路径分隔符：一律拒绝且不改台账', async () => {
  const b = await boot();
  try {
    const payload = fakeApk(64, 2);
    const wrongToken = await publish(b, payload, '0.3.8', { token: secret(32) });
    assert.equal(wrongToken.status, 403);

    const noHost = await publish(b, payload, '0.3.8', { hostId: '' });
    assert.equal(noHost.status, 403, '缺 hostId 时不能退化成「任意主机都能发」');

    const traversal = await publish(b, payload, '../../etc/passwd');
    assert.equal(traversal.status, 400, '版本号直接拼进文件名，必须白名单');

    const anonymous = await request(b.port, `/control/release?hostId=${b.hostId}`);
    assert.equal(anonymous.status, 401, '列版本列表也要凭据');

    // 三次失败都必须什么都没写下去。
    const manifest = await request(b.port, `${RELEASE_PREFIX}/version.json`);
    assert.equal(manifest.status, 404);
    assert.equal(existsSync(join(b.dir, 'releases')), false, '失败的请求不该留下目录或残包');
  } finally { await b.close(); }
});

test('声明的 sha256 与实收字节不符时拒收；DELETE 之后清单回到 404', async () => {
  const b = await boot();
  try {
    const payload = fakeApk(2048, 4);
    const mismatched = await publish(b, payload, '0.3.9', { sha: sha256(Buffer.from('not-the-apk')) });
    assert.equal(mismatched.status, 400, '传输被截断时不能把半截包登记成可下载');
    assert.equal(existsSync(join(b.dir, 'releases', 'dsh-mobile-0.3.9.apk')), false);

    const ok = await publish(b, payload, '0.3.9', { sha: sha256(payload) });
    assert.equal(ok.status, 200);

    const del = await request(b.port, `/control/release?hostId=${b.hostId}&version=0.3.9`, {
      method: 'DELETE', headers: { authorization: `Bearer ${b.token}` },
    });
    assert.equal(del.status, 200);
    assert.equal((await request(b.port, `${RELEASE_PREFIX}/version.json`)).status, 404);
    assert.equal(existsSync(join(b.dir, 'releases', 'dsh-mobile-0.3.9.apk')), false);
  } finally { await b.close(); }
});

test('钉住地址时清单用钉住的 origin，而不是请求里的 Host', async () => {
  const b = await boot({ pinned: true });
  try {
    await publish(b, fakeApk(128, 8), '0.3.10', { host: 'relay.example.test' });
    const manifest = JSON.parse((await request(b.port, `${RELEASE_PREFIX}/version.json`, {
      headers: { host: 'relay.example.test' },
    })).body.toString());
    // 钉住的意义就在这里：客户端的 Host 头不可信，清单里的地址必须是部署方声明的那个。
    assert.equal(manifest.url, `https://relay.example.test${RELEASE_PREFIX}/dsh-mobile-0.3.10.apk`);
    assert.equal(manifest.latestUrl, `https://relay.example.test${RELEASE_PREFIX}/latest.apk`);
  } finally { await b.close(); }
});
