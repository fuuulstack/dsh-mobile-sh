import assert from 'node:assert/strict';
import test from 'node:test';
import { HostMetrics, startMetricsReporter } from '../packages/plugin/src/metrics.js';

/** 同步忙等：占住主线程，模拟宿主被一段同步代码（序列化、加密、大 JSON）堵住。 */
function block(ms: number) { const until = Date.now() + ms; while (Date.now() < until) { /* spin */ } }

test('event loop lag reflects real blocking time', { timeout: 10000 }, async () => {
  // 采样间隔 100ms、阻塞 500ms：预期中的那几次 tick 全部被推迟，lag 之和必然
  // 接近「阻塞时长 - 一个采样间隔」，max 也会顶到 300ms 以上。
  const metrics = new HostMetrics(100);
  try {
    block(500);
    await new Promise(r => setTimeout(r, 250));
    const snap = metrics.snapshot();
    assert.ok(snap.eventLoop.samples >= 3, `应有至少 3 个样本，实际 ${snap.eventLoop.samples}`);
    assert.ok(snap.eventLoop.maxMs >= 200, `max 应 ≥200ms，实际 ${snap.eventLoop.maxMs}`);
    assert.ok(snap.eventLoop.p99Ms >= 100, `p99 应 ≥100ms，实际 ${snap.eventLoop.p99Ms}`);
    assert.ok(snap.windowMs > 0, '窗口长度必须是正数，否则「每秒多少次」算不出来');
  } finally { metrics.close(); }
});

test('an idle event loop reports near-zero lag', { timeout: 10000 }, async () => {
  // 反向用例：没有卡顿就必须读出 0 附近。若这个测试失败而上面通过，说明指标
  // 只是「恒为正的噪音」，那种指标会把正常抖动误报成故障。
  const metrics = new HostMetrics(100);
  try {
    await new Promise(r => setTimeout(r, 600));
    const snap = metrics.snapshot();
    assert.ok(snap.eventLoop.samples >= 3, `应有样本，实际 ${snap.eventLoop.samples}`);
    assert.ok(snap.eventLoop.maxMs < 80, `空闲时 max 应很小，实际 ${snap.eventLoop.maxMs}`);
  } finally { metrics.close(); }
});

test('snapshot resets the window so one stall does not poison later ones', { timeout: 10000 }, async () => {
  const metrics = new HostMetrics(100);
  try {
    block(500);
    await new Promise(r => setTimeout(r, 250));
    const first = metrics.snapshot();
    assert.ok(first.eventLoop.maxMs >= 200, `第一个窗口该看见卡顿，实际 ${first.eventLoop.maxMs}`);
    // 第二个窗口只空闲：max 必须掉下来。不清窗口的话它永远是 500ms，
    // 于是「刚刚卡了」与「半小时前卡过一次」就再也分不开了。
    await new Promise(r => setTimeout(r, 600));
    const second = metrics.snapshot();
    assert.ok(second.eventLoop.maxMs < first.eventLoop.maxMs, `第二个窗口应更平稳：${second.eventLoop.maxMs} vs ${first.eventLoop.maxMs}`);
  } finally { metrics.close(); }
});

test('sample ring stays bounded', { timeout: 20000 }, async () => {
  // 环长固定：跑过容量之后 samples 不该继续增长，否则长跑进程会缓慢吃内存。
  const metrics = new HostMetrics(1);
  try {
    await new Promise(r => setTimeout(r, 600));
    const snap = metrics.snapshot();
    assert.ok(snap.eventLoop.samples <= 240, `样本数必须有上界，实际 ${snap.eventLoop.samples}`);
  } finally { metrics.close(); }
});

test('loopback failures are counted per errno and reported as a window rate', () => {
  const metrics = new HostMetrics(100000);
  try {
    for (let i = 0; i < 3; i++) metrics.noteLoopbackAttempt();
    assert.equal(metrics.noteLog('loopback POST /api/session/list failed: ECONNRESET'), true);
    assert.equal(metrics.noteLog('loopback POST /api/session/list failed: ECONNRESET'), true);
    assert.equal(metrics.noteLog('loopback GET /api/session/list failed: ETIMEDOUT'), true);
    const snap = metrics.snapshot();
    assert.equal(snap.loopback.failures, 3, '窗口内失败数');
    assert.equal(snap.loopback.total, 3, '窗口内尝试数');
    assert.deepEqual(snap.loopback.byCode, { ECONNRESET: 2, ETIMEDOUT: 1 });

    // 第二窗口没有任何失败：速率必须归零。累计值不归零的话，「502 正在发生」
    // 与「三天前有过 3 次」在面板上是同一个数。
    metrics.noteLoopbackAttempt();
    const next = metrics.snapshot();
    assert.equal(next.loopback.failures, 0);
    assert.equal(next.loopback.total, 1);
    // 但分 errno 的累计值保留：它是「这台机器历史上最容易怎么坏」的画像。
    assert.deepEqual(next.loopback.byCode, { ECONNRESET: 2, ETIMEDOUT: 1 });
  } finally { metrics.close(); }
});

test('code keys are sanitised so a path cannot become a metric label', () => {
  const metrics = new HostMetrics(100000);
  try {
    metrics.noteLog('loopback GET /api/session/../../etc/passwd failed: socket hang up');
    metrics.noteLog('loopback GET /x failed: <script>');
    const { byCode } = metrics.snapshot().loopback;
    for (const key of Object.keys(byCode)) {
      assert.match(key, /^[A-Za-z0-9_.-]{1,40}$/, `指标键必须受限，实际 ${key}`);
    }
  } finally { metrics.close(); }
});

test('unrelated log lines are ignored', () => {
  const metrics = new HostMetrics(100000);
  try {
    assert.equal(metrics.noteLog('archiver push failed: fetch failed'), false);
    assert.equal(metrics.noteLog('ready'), false);
    assert.equal(metrics.snapshot().loopback.failures, 0);
  } finally { metrics.close(); }
});

test('the first ready is a connect; only returning to ready counts as a reconnect', () => {
  const metrics = new HostMetrics(100000);
  try {
    metrics.noteState('starting');
    metrics.noteState('connecting');
    metrics.noteState('ready');        // 首次连上：不是重连
    assert.equal(metrics.snapshot().tunnel.reconnects, 0);
    metrics.noteState('connecting');
    metrics.noteState('ready');        // 掉线又回来：算一次
    metrics.noteState('ready');        // 同状态重复上报：不重复计数
    assert.equal(metrics.snapshot().tunnel.reconnects, 1);
  } finally { metrics.close(); }
});

test('archiver queue depth is carried through to the report', () => {
  const metrics = new HostMetrics(100000);
  try {
    metrics.noteArchiver(412, true, 37);
    const snap = metrics.snapshot();
    assert.equal(snap.archiver.queued, 412);
    assert.equal(snap.archiver.inFlight, true);
    // sending 与 queued 是两回事：412 条还在队列里、37 条已经出队在等确认。
    assert.equal(snap.archiver.sending, 37);
  } finally { metrics.close(); }
});

test('push cost is reported, and failures accumulate within a window', () => {
  // 手机「消息晚到」里插件侧唯一可控的一段就是这次往返：排队（queued 大）与
  // 中继慢（pushMs 高）必须能分开读，否则只能看到「同步慢」这一个笼统结论。
  const metrics = new HostMetrics(100000);
  try {
    metrics.notePush(180, 4096, true);
    let snap = metrics.snapshot();
    assert.equal(snap.archiver.pushMs, 180);
    assert.equal(snap.archiver.batchBytes, 4096);
    assert.equal(snap.archiver.pushFailures, 0);

    // 失败要按窗口累计：单看最近一次会把「偶发失败」读成 0。
    metrics.notePush(9000, 1024, false);
    metrics.notePush(9500, 1024, false);
    snap = metrics.snapshot();
    assert.equal(snap.archiver.pushMs, 9500);
    assert.equal(snap.archiver.pushFailures, 2);

    // 报过即清零：下一个窗口读的是「这段时间」而不是历史累计。
    assert.equal(metrics.snapshot().archiver.pushFailures, 0);
  } finally { metrics.close(); }
});

test('a metrics push that comes back 400 is reported, not silently swallowed', async () => {
  const metrics = new HostMetrics(100000);
  const logs: string[] = [];
  const replies = [400, 400, 200, 400];
  let calls = 0;
  const stop = startMetricsReporter({
    metrics, relayOrigin: 'http://127.0.0.1:1', hostId: 'h', hostTunnelToken: 't',
    intervalMs: 5, onLog: m => logs.push(m),
    fetchImpl: (async () => new Response('{}', { status: replies[calls++] ?? 200 })) as typeof fetch,
  });
  try {
    await new Promise(r => setTimeout(r, 80));
  } finally { stop(); metrics.close(); }
  // fetch 只在网络层失败时 reject；HTTP 400 照样 resolve。不看 response.ok 就会
  // 出现「中继连续 400、两端日志全绿、监控彻底瞎掉」（评审现场原样）。
  assert.ok(logs.some(l => l.includes('metrics push rejected: 400')), `expected a rejection log, got ${JSON.stringify(logs)}`);
  // 同一个状态码反复失败只记一行（400 → 400 是同一个不变故障），恢复后再失败才重新记。
  assert.equal(logs.filter(l => l.includes('rejected: 400')).length, 2);
});

test('buffer peak and stalls are reported per window, then reset', () => {
  const metrics = new HostMetrics(100000);
  try {
    metrics.noteBufferedPeak(300);
    metrics.noteStall();
    metrics.noteStall();
    const first = metrics.snapshot();
    assert.equal(first.tunnel.bufferedPeakBytes, 300);
    assert.equal(first.tunnel.stalls, 2);
    // 窗口语义：报过就清零，否则一次带宽打满会把之后每个窗口都顶住。
    const second = metrics.snapshot();
    assert.equal(second.tunnel.bufferedPeakBytes, 0);
    assert.equal(second.tunnel.stalls, 0);
  } finally { metrics.close(); }
});

test('buffer peak keeps the max inside one window', () => {
  const metrics = new HostMetrics(100000);
  try {
    metrics.noteBufferedPeak(4096);
    metrics.noteBufferedPeak(1024);
    assert.equal(metrics.snapshot().tunnel.bufferedPeakBytes, 4096);
  } finally { metrics.close(); }
});

test('the peak source is drained by the sampler; a throwing source unbinds itself', async () => {
  const metrics = new HostMetrics(10);
  try {
    let value = 8192;
    metrics.bindBufferedPeak(() => { const v = value; value = 0; return v; });
    // 采样是 10ms 一次：等一小会儿，让「取走并清零」的读源被真正读过。
    await new Promise((r) => setTimeout(r, 60));
    assert.equal(metrics.snapshot().tunnel.bufferedPeakBytes, 8192);
    // 读源抛错（隧道正在销毁）不能影响采样，更不能把进程带崩。
    metrics.bindBufferedPeak(() => { throw new Error('tunnel gone'); });
    await new Promise((r) => setTimeout(r, 60));
    assert.equal(metrics.snapshot().tunnel.bufferedPeakBytes, 0);
  } finally { metrics.close(); }
});

test('report carries host identity and a monotonic uptime', () => {
  const metrics = new HostMetrics(100000);
  try {
    const r = metrics.report('a'.repeat(32));
    assert.equal(r.hostId, 'a'.repeat(32));
    assert.ok(r.uptimeMs >= 0);
    assert.ok(Math.abs(r.at - Date.now()) < 5000);
  } finally { metrics.close(); }
});

test('reporter posts to /control/metrics and swallows transport failures', async () => {
  const metrics = new HostMetrics(100000);
  const seen: Array<{ url: string; body: unknown; auth: string | undefined }> = [];
  const stop = startMetricsReporter({
    metrics, relayOrigin: 'https://relay.example.test', hostId: 'b'.repeat(32), hostTunnelToken: 't'.repeat(43),
    intervalMs: 60_000,
    fetchImpl: (async (input: RequestInfo | URL, init?: RequestInit) => {
      seen.push({
        url: String(input),
        body: JSON.parse(String(init?.body)),
        auth: (init?.headers as Record<string, string> | undefined)?.authorization,
      });
      return new Response('{"ok":true}', { status: 200 });
    }) as typeof fetch,
  });
  try {
    // 启动即推一次：等它落地（实现里是 void push()，给一个宏任务的时间）。
    await new Promise(r => setTimeout(r, 50));
    assert.equal(seen.length, 1, '启动时必须先推一份，否则第一个 60 秒是盲区');
    assert.equal(seen[0]!.url, 'https://relay.example.test/control/metrics');
    assert.equal(seen[0]!.auth, `Bearer ${'t'.repeat(43)}`);
    assert.equal((seen[0]!.body as { hostId: string }).hostId, 'b'.repeat(32));
    assert.ok('eventLoop' in (seen[0]!.body as object));
  } finally { stop(); }

  // 传输失败不能抛出、不能重试：指标是旁路，不许反过来影响隧道。
  const logs: string[] = [];
  const stop2 = startMetricsReporter({
    metrics, relayOrigin: 'https://relay.example.test', hostId: 'b'.repeat(32), hostTunnelToken: 't'.repeat(43),
    intervalMs: 60_000, onLog: m => logs.push(m),
    fetchImpl: (async () => { throw new TypeError('fetch failed'); }) as typeof fetch,
  });
  try {
    await new Promise(r => setTimeout(r, 50));
    assert.ok(logs.some(l => l.startsWith('metrics push failed')), `应记录一行失败日志，实际 ${JSON.stringify(logs)}`);
  } finally { stop2(); metrics.close(); }
});

test('stopping the reporter stops further pushes', async () => {
  const metrics = new HostMetrics(100000);
  let calls = 0;
  const stop = startMetricsReporter({
    metrics, relayOrigin: 'https://relay.example.test', hostId: 'c'.repeat(32), hostTunnelToken: 't'.repeat(43),
    intervalMs: 10,
    fetchImpl: (async () => { calls++; return new Response('{"ok":true}', { status: 200 }); }) as typeof fetch,
  });
  await new Promise(r => setTimeout(r, 60));
  stop();
  const after = calls;
  await new Promise(r => setTimeout(r, 80));
  assert.equal(calls, after, '停掉之后不能再上报（隧道重连时旧上报器必须真的停）');
  metrics.close();
});
