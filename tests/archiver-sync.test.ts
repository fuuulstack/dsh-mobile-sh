import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { WebSocketServer } from 'ws';
import { gunzipSync } from 'node:zlib';
import { ArchiveOutbox } from '../packages/plugin/src/outbox.js';
import { SessionHub } from '../packages/plugin/src/hub.js';
import { ArchivePusher } from '../packages/plugin/src/pusher.js';
import { Collector } from '../packages/plugin/src/collector.js';

/**
 * 采集段的端到端回归：真 `Collector` 打假宿主、真 `ArchivePusher` 打假中继。
 *
 * <p>行为契约与 v1 同名测试一致（不重复上传、溢出能恢复、meta 不重推、被拒批次
 * 与 meta 同命运），但**断言口径整体换了**：v1 断言的是「Archiver 内存队列里还剩
 * 什么」，v2 断言的是「中继实际收到了什么」与「SQLite 里的水位推进到哪」。这样
 * 实现从内存数组换成 outbox 时测试不用跟着改 —— 它验的是外部可观察行为。
 */

type Batch = {
  hostId: string;
  batchId: string;
  records: Array<{ sessionId: string; seq: number; prevSeq?: number }>;
  meta: Array<{ sessionId: string; item: unknown }>;
};

const sleep = (ms: number) => new Promise(r => setTimeout(r, ms));

/** 假 DSH 宿主：`/api/session/list`（RPC 形状）+ 归档用的 `/api/remote.mux`。
 *  `onFollow` 在每次收到 follow 的 `open` 帧时回调 —— 溢出恢复走的正是
 *  「重新 follow → 宿主重发快照」这条路。 */
function fakeHost(
  onFollow: (ws: { send(data: string): void }, streamId: string) => void,
  listItem: () => unknown = () => ({ sessionId: 'sess-1', title: 'T', updatedAt: 1 }),
  listItems?: () => unknown[],
) {
  const server = http.createServer((req, res) => {
    if (process.env.DEBUG_FIXTURE) console.log('[fixture]', req.method, req.url);
    if (req.url === '/api/session/list') {
      res.setHeader('content-type', 'application/json');
      // 注意 RPC 信封：真宿主回的是 `{result:{ok,value:{items}}}`，不是裸 `{items}`。
      // 读错这一层会静默得到「零会话」，与桌面空闲完全不可区分。
      res.end(JSON.stringify({ result: { ok: true, value: { items: listItems ? listItems() : [listItem()] } } }));
      return;
    }
    res.writeHead(404); res.end();
  });
  const wsServer = new WebSocketServer({ server });
  wsServer.on('connection', ws => {
    ws.on('message', raw => {
      const frame = JSON.parse(raw.toString()) as { type?: string; streamId?: string };
      if (frame.type !== 'open' || frame.streamId === 'arch-events') return;
      onFollow(ws as unknown as { send(data: string): void }, String(frame.streamId));
    });
  });
  return {
    server, wsServer,
    close: async () => {
      for (const ws of wsServer.clients) ws.terminate();
      wsServer.close();
      // fetch/undici keeps sockets alive; without this the fixture's teardown waits for
      // an idle keep-alive timeout and the whole test file looks hung.
      server.closeAllConnections();
      await new Promise<void>(r => server.close(() => r()));
    },
  };
}

/** 宿主 `$events` 上那条「列表变了」的 kick，形状照真宿主来。 */
function kickList(host: { wsServer: { clients: Set<{ send(data: string): void }> } }, type: string) {
  for (const ws of host.wsServer.clients) {
    // `onEventsFrame` 认的是 `value.type === 'emit'` + `value.event`，不是 `data`。
    ws.send(JSON.stringify({ type: 'item', streamId: 'arch-events', value: { type: 'emit', event: type } }));
  }
}

function snapshot(streamId: string, seqs: number[]) {
  return JSON.stringify({
    type: 'item', streamId,
    value: {
      type: 'snapshot', session: { sessionId: 'sess-1', title: 'T' },
      records: seqs.map(seq => ({ event: { seq, type: 'user/message', data: { message: { content: [{ type: 'text', text: `m${seq}` }] } } } })),
    },
  });
}

function liveEvent(streamId: string, seq: number) {
  return JSON.stringify({ type: 'item', streamId, value: { type: 'event', event: { seq, type: 'user/message', data: { message: { content: [{ type: 'text', text: `e${seq}` }] } } } } });
}

function relaySink(onBatch: (batch: Batch, reply: (status?: number) => void) => void) {
  const server = http.createServer((req, res) => {
    const chunks: Buffer[] = [];
    req.on('data', b => chunks.push(b));
    req.on('end', () => {
      const batch = JSON.parse(gunzipSync(Buffer.concat(chunks)).toString('utf8')) as Batch;
      onBatch(batch, (status = 200) => { res.writeHead(status); res.end(status === 200 ? '{}' : 'boom'); });
    });
  });
  return {
    server,
    close: async () => {
      server.closeAllConnections();
      await new Promise<void>(r => server.close(() => r()));
    },
  };
}

function seqCounts(batches: Batch[]) {
  const counts = new Map<number, number>();
  for (const batch of batches) for (const r of batch.records) counts.set(r.seq, (counts.get(r.seq) ?? 0) + 1);
  return counts;
}

/** 一把组装好三段的夹具；`collector` 由调用方 `.start()`，收尾统一挂 `t.after`。
 *
 *  <p>接线顺序与 `worker.ts` 完全一致，因为**乱接线会改变可观察行为**：pusher 必须先
 *  建、hub 的 `onRecord` 才有落点（否则记录要等下一次轮询）；`onDrained` 必须指回
 *  `hub.retryPending()`（否则溢出后记录永远卡在内存里）。测试要验的是生产装配，不是
 *  一个更聪明的替身。
 */
function pipeline(t: any, host: ReturnType<typeof fakeHost>, relayPort: number, extra: { maxRows?: number } = {}) {
  const outbox = new ArchiveOutbox({ path: null, maxRows: extra.maxRows });
  let collector: Collector | undefined;
  let overflowed = 0;
  // 40ms 轮询（生产是 250ms）：测试要控的是「谁在推」，不是「多久推一次」。
  const pusher: ArchivePusher = new ArchivePusher({
    outbox, relayOrigin: `http://127.0.0.1:${relayPort}`, hostId: 'h1', hostTunnelToken: 'tok', intervalMs: 40,
    // 一批刚腾出位置，正是重试被退回记录的时刻。
    onDrained: () => { hub.retryPending(); },
  });
  pusher.start();
  const hub = new SessionHub({
    outbox,
    // 溢出不是「重新 follow 就能补」：宿主重发的快照带的是同一批 seq，hub 按 seq 去重，
    // 这些重投根本进不了 outbox。记录只能留在 hub 里，等 pusher 排空后重试。
    onOverflow: () => { overflowed++; },
    onRecord: () => pusher.kick(),
  });
  collector = new Collector({
    hub, outbox,
    upstreamOrigin: `http://127.0.0.1:${(host.server.address() as any).port}`,
    getCookie: async () => 'cookie', topK: 5, intervalMs: 60_000, kickDebounceMs: 80, reconnectBaseMs: 40,
  });
  t.after(async () => { await collector!.stop(); await pusher.stop(); outbox.close(); });
  return { outbox, hub, pusher, collector, overflowed: () => overflowed };
}

/** 等待某个条件成立，超时即失败。轮询而不是 sleep 固定时长：CI 上时序会漂。 */
async function until(check: () => boolean, ms: number, what: string) {
  const deadline = Date.now() + ms;
  while (!check()) {
    if (Date.now() > deadline) throw new Error(`timeout waiting for ${what}`);
    await sleep(20);
  }
}

// ---------------------------------------------------------------
// 宿主列表里的 running 必须出现在脉搏里：插件重启后不许谎报 IDLE（坑 85 反向）
// ---------------------------------------------------------------
test('session list running flag reaches the pulse even with no timeline records', { timeout: 15000 }, async t => {
  const relay = relaySink((_batch, reply) => reply());
  relay.server.listen(0, '127.0.0.1'); await once(relay.server, 'listening');
  t.after(() => relay.close());

  const host = fakeHost(
    () => {},
    () => ({ sessionId: 'sess-1', title: 'T', updatedAt: 1 }),
    () => [{ sessionId: 'sess-1', title: 'T', updatedAt: 1, running: true }],
  );
  host.server.listen(0, '127.0.0.1'); await once(host.server, 'listening');
  t.after(() => host.close());

  const { hub, collector } = pipeline(t, host, (relay.server.address() as any).port);
  collector.start();
  await until(() => hub.pulse('sess-1').hostState !== 'IDLE', 5000, '脉搏报出宿主的运行态');
});

// ---------------------------------------------------------------
// B-1：在途批次未确认时，快照重投同一个 seq 不得再排一次队
// ---------------------------------------------------------------
test('a snapshot replayed while a push is in flight does not duplicate records', { timeout: 20000 }, async t => {
  const batches: Batch[] = [];
  let release: (() => void) | undefined;
  const relay = relaySink((batch, reply) => {
    batches.push(batch);
    if (batches.length === 1) release = () => reply();
    else reply();
  });
  relay.server.listen(0, '127.0.0.1'); await once(relay.server, 'listening');
  t.after(() => relay.close());

  let followed = 0;
  const host = fakeHost((ws, streamId) => {
    followed++;
    // 每次 follow 都给同一段快照：第二次就是「在途期间的重投」。
    ws.send(snapshot(streamId, [1, 2, 3]));
    if (followed === 1) setTimeout(() => { ws.send(liveEvent(streamId, 3)); ws.send(liveEvent(streamId, 4)); }, 120);
  });
  host.server.listen(0, '127.0.0.1'); await once(host.server, 'listening');
  t.after(() => host.close());

  const { hub, collector } = pipeline(t, host, (relay.server.address() as any).port);
  collector.start();
  await until(() => !!release, 5000, '第一批在途');
  await until(() => hub.latestSeq('sess-1') >= 4, 5000, '尾部到 4');
  release!();
  await until(() => seqCounts(batches).size >= 4, 5000, '四个 seq 都到达中继');

  const counts = seqCounts(batches);
  assert.deepEqual([...counts.keys()].sort((a, b) => a - b), [1, 2, 3, 4], '四个 seq 都应到达中继');
  for (const [seq, n] of counts) assert.equal(n, 1, `seq ${seq} 上传了 ${n} 次；在途记录不得重复入队`);
});

// ---------------------------------------------------------------
// B-2：outbox 满时记录不得消失 —— 留在 hub 里等排空后落盘
// ---------------------------------------------------------------
test('seqs refused by a full outbox are held in memory and stored once it drains', { timeout: 25000 }, async t => {
  const batches: Batch[] = [];
  const relay = relaySink((batch, reply) => { batches.push(batch); reply(); });
  relay.server.listen(0, '127.0.0.1'); await once(relay.server, 'listening');
  t.after(() => relay.close());

  const all = Array.from({ length: 20 }, (_, i) => i + 1);
  let followed = 0;
  const host = fakeHost((ws, streamId) => { followed++; ws.send(snapshot(streamId, all)); });
  host.server.listen(0, '127.0.0.1'); await once(host.server, 'listening');
  t.after(() => host.close());

  // 只放得下 5 条未确认记录：另外 15 条必须被拒。
  const { outbox, hub, collector, overflowed } = pipeline(t, host, (relay.server.address() as any).port, { maxRows: 5 });
  collector.start();

  await until(() => overflowed() > 0, 8000, '一次溢出');
  // 溢出之后那些被拒的记录要靠「pusher 排空 → 腾出位置 → 重试」闭合，而不是重新 follow：
  // 宿主重发的快照带的是同一批 seq，hub 按 seq 去重，重投根本进不了 outbox。
  await until(() => seqCounts(batches).size >= 20, 15000, '20 条全部到达中继');

  const missing = all.filter(seq => !seqCounts(batches).has(seq));
  assert.deepEqual(missing, [], `溢出后这些 seq 永久丢失：${missing.join(',')}`);
  const counts = seqCounts(batches);
  for (const [seq, n] of counts) assert.equal(n, 1, `seq ${seq} 上传了 ${n} 次；重试不得造成重复`);
  assert.equal(followed, 1, '恢复靠内存 hold-back + 排空重试，不该重新 follow');
  assert.equal(hub.pending, 0, '排空后内存里不该还压着记录');
  assert.equal(hub.dropped, 0, 'hold-back 上限内不应丢任何记录');
  assert.equal(outbox.full, true, '拒绝要留下痕迹，供观测与诊断');
  assert.equal(hub.latestSeq('sess-1'), 20, '尾巴本身不受 outbox 容量影响');
});

// ---------------------------------------------------------------
// B-3：内容没变的 meta 不得每次刷新都重推
// ---------------------------------------------------------------
test('unchanged session meta is sent once, not on every list refresh', { timeout: 20000 }, async t => {
  const batches: Batch[] = [];
  const relay = relaySink((batch, reply) => { batches.push(batch); reply(); });
  relay.server.listen(0, '127.0.0.1'); await once(relay.server, 'listening');
  t.after(() => relay.close());

  const host = fakeHost((ws, streamId) => ws.send(snapshot(streamId, [1])));
  host.server.listen(0, '127.0.0.1'); await once(host.server, 'listening');
  t.after(() => host.close());

  const { collector } = pipeline(t, host, (relay.server.address() as any).port);
  collector.start();
  // 首次推送要串起「拉一次 session/list → ingest → outbox → pusher 250ms 轮询」四跳，
  // 跑在全量套件里时机器一忙就会顶到 5 秒（实测 Windows 上偶发）。这里等的是
  // 「有没有推出去」，不是「多快推出去」，所以给足余量；用例自身的 timeout 是 20 秒。
  await until(() => batches.reduce((n, b) => n + b.meta.length, 0) >= 1, 15000, 'meta 首次送达');
  // 连刷三次列表：内容一字未变，不应再多推一次 meta。
  const refresh = (collector as unknown as { refreshSessions(): Promise<void> }).refreshSessions.bind(collector);
  await refresh();
  await refresh();
  await refresh();
  await sleep(200);

  assert.equal(batches.reduce((n, b) => n + b.meta.length, 0), 1, '同一个 slim item 连续刷新应只推一次');
});

// ---------------------------------------------------------------
// B-4：被拒的批次里 meta 不能凭空消失（records 与 meta 同命运）
// ---------------------------------------------------------------
test('meta stays staged until the batch carrying it is accepted', { timeout: 20000 }, async t => {
  const accepted: Batch[] = [];
  let attempts = 0;
  const relay = relaySink((batch, reply) => {
    attempts++;
    if (attempts <= 2) { reply(503); return; }
    accepted.push(batch); reply();
  });
  relay.server.listen(0, '127.0.0.1'); await once(relay.server, 'listening');
  t.after(() => relay.close());

  const host = fakeHost((ws, streamId) => ws.send(snapshot(streamId, [1, 2])));
  host.server.listen(0, '127.0.0.1'); await once(host.server, 'listening');
  t.after(() => host.close());

  const { outbox, collector } = pipeline(t, host, (relay.server.address() as any).port);
  collector.start();
  await until(() => accepted.length > 0, 8000, '一次成功推送');

  assert.deepEqual([...seqCounts(accepted).keys()].sort((a, b) => a - b), [1, 2], '被拒批次的 records 必须重排后重发');
  assert.equal(accepted.reduce((n, b) => n + b.meta.length, 0), 1, '被拒批次的 meta 也必须重排后重发');
  // 假中继在 handler 里就 `reply()` 了，pusher 还要 await 响应体才 ack + dropMeta；
  // 直接断言会撞上这个窗口，所以要等到水位真的落定。
  await until(() => outbox.listMeta(10).length === 0, 5000, 'meta 在批次被接受后清掉');
});

// ---------------------------------------------------------------
// P0：follow 的 streamId 绝不能复用
// ---------------------------------------------------------------
test('a follow stream id is never reused after a session drops out of the top-K', { timeout: 20000 }, async t => {
  // 事故形态：`streamId = arch-${followed.size + 1}`。注意复用**不在同一次刷新里**
  // 发生——`wanted` 循环先 add 后 remove，那一瞬间 size 还没降。真正的坑要跨两次刷新：
  //   ① 列表缩到只剩 A  → B 被 unfollow，size 2→1
  //   ② 列表变成 A、C   → C 拿到 arch-(1+1)=arch-2，正是宿主仍在推的 B 那条流
  // 于是 B 的帧全部记到 C 名下，实时与归档同时错账，且没有任何日志。
  const followed: string[] = [];
  let list: unknown[] = [
    { sessionId: 'sess-A', title: 'A', updatedAt: 1 },
    { sessionId: 'sess-B', title: 'B', updatedAt: 2 },
  ];
  const host = fakeHost(
    (_ws, streamId) => { followed.push(streamId); },
    undefined,
    () => list,
  );
  host.server.listen(0, '127.0.0.1'); await once(host.server, 'listening');
  t.after(() => host.close());

  const relay = relaySink((_batch, reply) => reply());
  relay.server.listen(0, '127.0.0.1'); await once(relay.server, 'listening');
  t.after(() => relay.close());

  const { collector } = pipeline(t, host, (relay.server.address() as any).port);
  collector.start();
  await until(() => followed.length >= 2, 8000, 'two follows');
  const firstTwo = [...followed];

  // ① 缩：B 掉出 top-K
  list = [{ sessionId: 'sess-A', title: 'A', updatedAt: 1 }];
  kickList(host, 'api-session/updated');
  await sleep(300); // 让 debounce(80ms) + 一次 refresh 走完

  // ② 涨：C 进来
  list = [
    { sessionId: 'sess-A', title: 'A', updatedAt: 1 },
    { sessionId: 'sess-C', title: 'C', updatedAt: 3 },
  ];
  kickList(host, 'api-session/updated');
  await until(() => followed.length >= 3, 8000, 'third follow');
  const third = followed[2]!;

  assert.ok(
    !firstTwo.includes(third),
    `新 follow 复用了旧 streamId ${third}（已分配：${followed.join(',')}）`,
  );
  assert.equal(new Set(followed).size, followed.length, `streamId 出现重复：${followed.join(',')}`);
});

// ---------------------------------------------------------------
// 采集 WS 的单帧上限必须容得下一次快照
// ---------------------------------------------------------------
test('a snapshot frame larger than 4 MiB does not kill the collector connection', { timeout: 25000 }, async t => {
  // 事故形态：`maxPayload: 4 * 1024 * 1024`，而宿主把整份 `snapshot`（最多 40 条消息）
  // 放在**一帧**里发，大小策略是帧到达之后才跑的。几条大工具输出就能超过 4 MiB →
  // ws 以 1009 关闭 → 采集侧重连 → 同一份快照再次打死连接 → 所有会话采集饿死。
  const big = 'X'.repeat(6 * 1024 * 1024); // 6 MiB，超过旧的 4 MiB 上限
  const host = fakeHost((ws, streamId) => {
    ws.send(JSON.stringify({
      type: 'item', streamId,
      value: {
        type: 'snapshot',
        session: { sessionId: 'sess-1', title: 'T' },
        records: [
          { event: { seq: 1, type: 'user/message', data: { message: { content: [{ type: 'text', text: 'hi' }] } } } },
          { event: { seq: 2, type: 'tool/result', data: { output: big } } },
        ],
      },
    }));
  });
  host.server.listen(0, '127.0.0.1'); await once(host.server, 'listening');
  t.after(() => host.close());

  const relay = relaySink((_batch, reply) => reply());
  relay.server.listen(0, '127.0.0.1'); await once(relay.server, 'listening');
  t.after(() => relay.close());

  const { outbox, hub, collector } = pipeline(t, host, (relay.server.address() as any).port);
  collector.start();

  // 判据是「那两条真的进了 outbox」，而不是「连接没报错」：1009 关闭时 ws 也会
  // 触发 close 而不一定抛错，只看异常会把故障漏掉。
  await until(() => outbox.depth().acked + outbox.depth().pending + outbox.depth().inflight >= 2, 15000, 'both records archived');
  assert.ok(hub.latestSeq('sess-1') >= 2, '大快照必须被完整接收');
});
