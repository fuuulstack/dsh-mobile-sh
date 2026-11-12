import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { gunzipSync } from 'node:zlib';
import { ArchiveOutbox } from '../packages/plugin/src/outbox.js';
import { SYNC_V2, applySizePolicy, recordBytes } from '../packages/core/src/index.js';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { SessionHub } from '../packages/plugin/src/hub.js';
import { ArchivePusher } from '../packages/plugin/src/pusher.js';

/**
 * 插件侧 v2 归档流水线的回归：`SessionHub → ArchiveOutbox → ArchivePusher`。
 *
 * <p>这三段合起来回答了 §invariant 5 与 §7：插件拿到的每一条记录都必须先落盘、
 * 只上传一次、并且在失败后能原样重发。老版本（内存队列 + splice 丢最旧）在这三件
 * 事上都出过错，而且**错误全都在插件内部静默发生** —— 中继那边主键幂等会把重复
 * 吸收掉，缺失的 seq 则永久留在水位之下，手机看到的只是「正文缺一截」。
 *
 * <p>所以断言一律落在**中继真正收到的批次**与**落盘的水位**上，不看插件内部状态：
 * 一份实现只要行为对，用什么数据结构都行。
 */

type Batch = {
  hostId: string;
  batchId: string;
  records: Array<{ sessionId: string; seq: number; prevSeq?: number; blobId?: string; record: unknown; payload?: unknown }>;
  meta: Array<{ sessionId: string; item: unknown }>;
};

/** 可编程的中继接收器：`onBatch` 决定这一批是接受、拒绝还是先扣住。 */
function relaySink(onBatch: (batch: Batch, reply: (status?: number) => void) => void) {
  const server = http.createServer((req, res) => {
    const chunks: Buffer[] = [];
    req.on('data', chunk => chunks.push(chunk));
    req.on('end', () => {
      const body = Buffer.concat(chunks);
      const batch = JSON.parse(gunzipSync(body).toString('utf8')) as Batch;
      onBatch(batch, (status = 200) => { res.writeHead(status); res.end(status === 200 ? '{}' : 'boom'); });
    });
  });
  return { server, close: async () => { await new Promise<void>(r => server.close(() => r())); } };
}

const sleep = (ms: number) => new Promise(r => setTimeout(r, ms));

function event(seq: number, text = `m${seq}`) {
  return { type: 'user/message', seq, data: { message: { content: [{ type: 'text', text }] } } };
}

function seqsOf(batches: Batch[]) {
  const counts = new Map<number, number>();
  for (const batch of batches) for (const record of batch.records) counts.set(record.seq, (counts.get(record.seq) ?? 0) + 1);
  return counts;
}

// ---------------------------------------------------------------------------
// 队列与任务：两种原本被当成 META_PATCH 丢掉的持久事件
// ---------------------------------------------------------------------------
test('the inbox splice and the task list become their own kinds, not META_PATCH', () => {
  const outbox = new ArchiveOutbox({ path: null });
  const hub = new SessionHub({ outbox });
  // Both are real session-log events with a seq; v1 could only read the same
  // data from `/rpc/projections`, which failed often enough that the cards
  // silently froze. Classifying them here is what puts them on the timeline.
  const splice = hub.ingest('s1', {
    type: 'agent/inbox/spliced',
    seq: 5,
    data: { target: 'next-turn', start: 0, removedCount: 0, inserted: [{ id: 'q1', content: [{ type: 'text', text: '排队这句话' }] }] },
  });
  assert.equal(splice?.kind, 'INBOX_SPLICE');
  const tasks = hub.ingest('s1', {
    type: 'todo/write',
    seq: 6,
    data: { todos: [{ content: '做一件事', status: 'in_progress' }] },
  });
  assert.equal(tasks?.kind, 'TASK_LIST');
  // The payload is carried verbatim: the phone is the only side that parses it,
  // so a new field in either event needs no relay or schema change.
  assert.deepEqual((tasks?.payload as any).data.todos, [{ content: '做一件事', status: 'in_progress' }]);
});

// ---------------------------------------------------------------------------
// ç¼å·ï¼seq åªæåºä¸è¿ç»­，prevSeq 才是「接得上」的凭据
// ---------------------------------------------------------------------------
test('hub links each record to its predecessor and refuses a duplicate seq', () => {
  const outbox = new ArchiveOutbox({ path: null });
  const hub = new SessionHub({ outbox });

  assert.equal(hub.ingest('s1', event(1))?.prevSeq, 0, '链首的 prevSeq 是 0');
  // 宿主重启后 seq 会跳（这里刻意跳过 2、4）：序号连续不是可以假设的事。
  assert.equal(hub.ingest('s1', event(5))?.prevSeq, 1);
  assert.equal(hub.ingest('s1', event(3))?.prevSeq, 1, '晚到的 seq 3 指向它真正的前驱 1，而不是「最后收到的那个」');
  assert.equal(hub.ingest('s1', event(3)), undefined, '同一个 seq 重投不得产生第二条记录');
  assert.equal(hub.latestSeq('s1'), 5);
  outbox.close();
});

test('hub drops events that carry no usable seq instead of inventing one', () => {
  const outbox = new ArchiveOutbox({ path: null });
  const hub = new SessionHub({ outbox });
  // 编造 seq 会污染手机的水位：那之后所有真实记录都被判成「已经见过」。
  assert.equal(hub.ingest('s1', { type: 'api-session/updated' }), undefined);
  assert.equal(hub.ingest('s1', { type: 'user/message', seq: -1 }), undefined);
  assert.equal(hub.ingest('s1', { type: 'user/message', seq: 1.5 }), undefined);
  assert.equal(hub.latestSeq('s1'), 0);
  outbox.close();
});

// ---------------------------------------------------------------------------
// 上传：一批只上传一次，失败原样重发且复用同一个 batch id
// ---------------------------------------------------------------------------
test('a push lands every record exactly once, and a second flush has nothing to do', async t => {
  const batches: Batch[] = [];
  const relay = relaySink((batch, reply) => { batches.push(batch); reply(); });
  relay.server.listen(0, '127.0.0.1');
  await once(relay.server, 'listening');
  t.after(() => relay.close());

  const outbox = new ArchiveOutbox({ path: null });
  t.after(() => outbox.close());
  const hub = new SessionHub({ outbox });
  for (const seq of [1, 2, 3]) hub.ingest('s1', event(seq));

  const pusher = new ArchivePusher({
    outbox, relayOrigin: `http://127.0.0.1:${(relay.server.address() as any).port}`,
    hostId: 'h1', hostTunnelToken: 'tok', intervalMs: 60_000,
  });
  await pusher.flush();
  await pusher.flush();

  assert.equal(batches.length, 1, '确认过的批次不得重发');
  assert.deepEqual([...seqsOf(batches).keys()].sort((a, b) => a - b), [1, 2, 3]);
  assert.equal(seqsOf(batches).get(1), 1);
  assert.deepEqual(outbox.depth(), { pending: 0, inflight: 0, acked: 3 });
  assert.equal(outbox.ackedSeq('s1'), 3, '确认水位应推进到 3');
});

test('a rejected push goes back to pending and the retry reuses the same batch id', async t => {
  const attempts: Batch[] = [];
  let failures = 0;
  const relay = relaySink((batch, reply) => {
    attempts.push(batch);
    failures++;
    reply(failures <= 2 ? 503 : 200);
  });
  relay.server.listen(0, '127.0.0.1');
  await once(relay.server, 'listening');
  t.after(() => relay.close());

  const outbox = new ArchiveOutbox({ path: null });
  t.after(() => outbox.close());
  const hub = new SessionHub({ outbox });
  hub.ingest('s1', event(1));
  hub.ingest('s1', event(2));
  outbox.putMeta('s1', { sessionId: 's1', title: 'T' });

  const pusher = new ArchivePusher({
    outbox, relayOrigin: `http://127.0.0.1:${(relay.server.address() as any).port}`,
    hostId: 'h1', hostTunnelToken: 'tok', intervalMs: 60_000,
  });
  for (let i = 0; i < 3; i++) await pusher.flush();

  assert.equal(attempts.length, 3, '两次被拒、一次接受');
  // 内容派生的 batch id：重试必须复用同一个值，中继那边才能把第二份认成 no-op。
  assert.equal(new Set(attempts.map(batch => batch.batchId)).size, 1, `batch id 漂移了：${attempts.map(b => b.batchId).join(' / ')}`);
  assert.equal(attempts[0]!.meta.length, 1, 'meta 与 records 同批同命运');
  assert.equal(outbox.listMeta(10).length, 0, '成功的批次才允许清掉 meta');
  assert.equal(outbox.ackedSeq('s1'), 2);
});

// ---------------------------------------------------------------------------
// 超大单条：存根 + blob，绝不留洞
// ---------------------------------------------------------------------------
test('an oversized payload is offloaded to a stub that points at a shipped blob', async t => {
  const batches: Batch[] = [];
  const relay = relaySink((batch, reply) => { batches.push(batch); reply(); });
  relay.server.listen(0, '127.0.0.1');
  await once(relay.server, 'listening');
  t.after(() => relay.close());

  const outbox = new ArchiveOutbox({ path: null });
  t.after(() => outbox.close());
  const hub = new SessionHub({ outbox });

  // 300 KiB 单条大于存根阈值（256 KiB）：既不能整条塞进 outbox，也不能丢。
  const big = 'x'.repeat(300 * 1024);
  const record = hub.ingest('s1', event(1, big));
  assert.ok(record?.stub, '超阈值记录必须变成存根');
  assert.equal(record!.stub!.blobSize > 256 * 1024, true);

  const pusher = new ArchivePusher({
    outbox, relayOrigin: `http://127.0.0.1:${(relay.server.address() as any).port}`,
    hostId: 'h1', hostTunnelToken: 'tok', intervalMs: 60_000,
  });
  await pusher.flush();

  const [sent] = batches[0]!.records;
  assert.equal(sent!.blobId, 's1:1', 'blob id 必须由 (session, seq) 确定，重试才幂等');
  assert.ok(sent!.payload !== undefined, 'blob 必须随批次同行，否则中继存不下它');
  assert.ok(JSON.stringify(sent!.payload).includes(big), 'blob 原文不能被截断');
  // 存根本身很小，所以整批仍然廉价 —— 这才是「重试便宜」的实际含义。
  assert.ok(Buffer.byteLength(JSON.stringify(sent!.record), 'utf8') < 4 * 1024);
});

test('a payload past the hard ceiling becomes a record that says it was truncated', () => {
  const outbox = new ArchiveOutbox({ path: null });
  const hub = new SessionHub({ outbox });
  const huge = 'y'.repeat(11 * 1024 * 1024);
  const record = hub.ingest('s1', event(1, huge));
  assert.equal(record?.kind, 'ERROR_OVERSIZED_TRUNCATED');
  assert.equal(record?.truncated?.originalBytes !== undefined, true);
  assert.equal(record?.stub, undefined, '超过硬上限的走截断标记，不再走存根');
  outbox.close();
});

// ---------------------------------------------------------------------------
// 水位：ACKED 行会被 GC，但覆盖位置不能跟着倒退
// ---------------------------------------------------------------------------
test('pruning acknowledged rows does not roll the coverage watermark back', () => {
  const outbox = new ArchiveOutbox({ path: null });
  const hub = new SessionHub({ outbox });
  for (const seq of [1, 2, 3]) hub.ingest('s1', event(seq));

  const claimed = outbox.claim('b1', 10, 1024 * 1024);
  assert.equal(claimed.length, 3);
  outbox.ack('b1');
  assert.equal(outbox.ackedSeq('s1'), 3);

  // 水位表独立于 archive_outbox：ACKED 行全被删掉之后，覆盖位置依然要说 3。
  outbox.prune(-1);
  assert.equal(outbox.depth().acked, 0, 'ACKED 行应已被回收');
  assert.equal(outbox.ackedSeq('s1'), 3, '回收不得让水位倒退，否则手机会误判缺口不可恢复');
  outbox.close();
});

test('a blob survives pruning while its stub record is still pending', () => {
  // 回归：`prune()` 早先按 `created_at` 一刀切删 blob，会把**还没推出去**的存根
  // 指向的正文删掉，pusher 随后发出一条指向空气的 stub（手机端永远补不上）。
  // 判据是「记录还在，blob 就还在」，与时间无关。
  //
  // 两条分支都要跑：`path: null` 走内存实现，`path: <file>` 走 SQL。只测内存那次
  // 反向验证是**假绿**——把 SQL 里的 `NOT IN` 拿掉，内存分支根本不受影响。
  for (const mode of ['memory', 'sqlite'] as const) {
    const dir = mode === 'sqlite' ? mkdtempSync(join(tmpdir(), 'dsh-outbox-')) : null;
    const outbox = new ArchiveOutbox({ path: dir ? join(dir, 'outbox.sqlite') : null });
    try {
      const big = 'Z'.repeat(300 * 1024); // > SYNC_V2.stubThreshold(256 KiB)
      const record = {
        sessionId: 's1', seq: 1, prevSeq: 0, turnId: 's1:t1', kind: 'STEP_TOOL' as const,
        timestampMs: 1, payload: { type: 'tool/result', data: { output: big } },
      };
      const stub = applySizePolicy(record, (blobId, payload) => outbox.putBlob(blobId, payload));
      assert.ok(stub.stub, `[${mode}] 这条应当被离载成存根`);
      outbox.put(stub);

      // 记录还是 PENDING（没 claim 过），blob 必须留着 —— 哪怕它已经「很老」。
      outbox.prune(-1);
      assert.equal(outbox.blobs(['s1:1']).size, 1, `[${mode}] 存根还在待推，blob 不许被回收`);

      // 推完并确认之后，blob 才可以跟着走。
      outbox.claim('b1', 10, 1024 * 1024);
      outbox.ack('b1');
      outbox.prune(-1);
      assert.equal(outbox.blobs(['s1:1']).size, 0, `[${mode}] 记录已确认，blob 应被回收`);
    } finally {
      outbox.close();
      if (dir) rmSync(dir, { recursive: true, force: true });
    }
  }
});

test('acknowledging a batch advances the watermark and pruning cannot rewind it', () => {
  // `ack()` 原本是两步：UPDATE 自动提交，水位另起一个事务。中间崩一次就会留下
  // 「行已 ACKED、水位还落后」的状态 —— `MAX(seq) WHERE ACKED` 会暂时掩盖它，
  // 直到 `prune()` 把行删掉，`ackedSeq()` 真的倒退，手机于是重拉中继已经清掉的历史。
  // 现在两条语句在同一个事务里，下面的断言把「同进同退」钉住。
  const outbox = new ArchiveOutbox({ path: null });
  try {
    const hub = new SessionHub({ outbox });
    for (const seq of [1, 2, 3]) hub.ingest('s1', event(seq));
    outbox.claim('b1', 10, 1024 * 1024);
    outbox.ack('b1');

    // 行还在的时候：水位是 3
    assert.equal(outbox.ackedSeq('s1'), 3, 'ack 之后水位必须立刻到位');
    // 行删掉之后：水位仍然是 3（靠独立的水位表，不靠残留行）
    outbox.prune(-1);
    assert.equal(outbox.depth().acked, 0, 'ACKED 行应已被回收');
    assert.equal(outbox.ackedSeq('s1'), 3, '回收不得让水位倒退');
  } finally {
    outbox.close();
  }
});

test('a batch budget counts the blob that rides along with a stub', () => {
  // 毒批次：`peek/claim` 只按 `row.bytes`（存根 ≈1.5 KiB）挑行，而 blob 是 pusher
  // 另外附加进同一个请求的。三条 9 MiB 工具结果因此看起来只有 5 KiB，实际请求体
  // 27 MiB —— 超过中继的请求体上限就是 413，且**永久**：行一直是 PENDING，batchId
  // 由这些行派生，每次重试都算出同一个必死的批次，整个 outbox 堵在它后面。
  const outbox = new ArchiveOutbox({ path: null });
  try {
    for (let seq = 1; seq <= 3; seq++) {
      const rec = {
        sessionId: 's1', seq, prevSeq: seq - 1, turnId: 't', kind: 'STEP_TOOL' as const, timestampMs: seq,
        payload: { type: 'tool/result', data: { output: 'X'.repeat(9 * 1024 * 1024) } },
      };
      const stub = applySizePolicy(rec, (blobId, payload) => outbox.putBlob(blobId, payload));
      assert.ok(stub.stub, `seq ${seq} 应当被离载成存根`);
      outbox.put(stub);
    }

    // 足够大的预算下三条都能出，说明不是「永远只挑一条」。
    assert.equal(outbox.peek(500, 40 * 1024 * 1024).length, 3, '大预算下三条都该被挑中');

    // 4 MiB 预算：每条 9 MiB，所以只允许挑一条（首条必发是既定契约）。
    const peek = outbox.peek(500, 4 * 1024 * 1024);
    assert.equal(peek.length, 1, `4 MiB 预算挑了 ${peek.length} 条存根`);
    const claimed = outbox.claim('b1', 500, 4 * 1024 * 1024);
    assert.equal(claimed.length, 1, 'claim 必须和 peek 用同一套账');
  } finally {
    outbox.close();
  }
});

test('a batch that would exceed the relay body limit is split, not stalled', () => {
  // 反向钉子：修好预算之后，「一条 9 MiB 记录」仍然要能单独出队（首条必发），
  // 而中继的请求体上限必须容得下它 —— 否则再好的预算也只是把 413 从三条变成一条。
  const outbox = new ArchiveOutbox({ path: null });
  try {
    const rec = {
      sessionId: 's1', seq: 1, prevSeq: 0, turnId: 't', kind: 'STEP_TOOL' as const, timestampMs: 1,
      payload: { type: 'tool/result', data: { output: 'X'.repeat(9 * 1024 * 1024) } },
    };
    const stub = applySizePolicy(rec, (blobId, payload) => outbox.putBlob(blobId, payload));
    outbox.put(stub);
    const claimed = outbox.claim('b1', 500, 256 * 1024);
    assert.equal(claimed.length, 1, '单条超预算也必须发出去，否则这条永远到不了中继');
    const blobId = (JSON.parse(claimed[0]!.payload) as { stub?: { blobId?: string } }).stub?.blobId;
    assert.ok(blobId, '存根必须带 blobId');
    const blob = outbox.blobs([blobId!]).get(blobId!);
    assert.ok(blob && Buffer.byteLength(blob, 'utf8') > 4 * 1024 * 1024,
      '这条 blob 必须大于旧的 4 MiB 解压上限，否则钉不住那个回归');
  } finally {
    outbox.close();
  }
});

// ---------------------------------------------------------------------------
// 溢出：拒绝要出声，记录仍然到手机；恢复靠重新 follow 快照
// ---------------------------------------------------------------------------
test('a full outbox refuses loudly, still streams the record, and asks for a re-follow', () => {
  const outbox = new ArchiveOutbox({ path: null, maxRows: 2 });
  const overflowed: string[] = [];
  const hub = new SessionHub({ outbox, onOverflow: sessionId => overflowed.push(sessionId) });

  const seen: number[] = [];
  hub.subscribe('s1', record => seen.push(record.seq));

  hub.ingest('s1', event(1));
  hub.ingest('s1', event(2));
  hub.ingest('s1', event(3));

  assert.deepEqual(overflowed, ['s1'], '存不下必须报出来，不能静默扔掉');
  assert.deepEqual(seen, [1, 2, 3], '落不了盘的记录仍要送到手机上，用户至少看得见');
  assert.equal(outbox.full, true);
  outbox.close();
});

// ---------------------------------------------------------------------------
// 回放：聚焦时从内存尾部给增量，绝不要求宿主重跑
// ---------------------------------------------------------------------------
test('a focus replay serves the in-memory tail from sinceSeq and reports the gap below it', () => {
  const outbox = new ArchiveOutbox({ path: null });
  const hub = new SessionHub({ outbox, tailSize: 4 });
  for (const seq of [1, 2, 3, 4, 5, 6]) hub.ingest('s1', event(seq));

  // tailSize=4 只留最近四条，所以 1、2 已经滑出内存 —— 手机要靠 /v2/sync 补。
  assert.deepEqual(hub.replay('s1', 3, 1024 * 1024).map(r => r.seq), [4, 5, 6]);
  assert.equal(hub.oldestSeq('s1'), 3, '最老的可回放 seq 决定了 hasMore 的判断');
  assert.deepEqual(hub.replay('s1', 0, 1024 * 1024).map(r => r.seq), [3, 4, 5, 6]);
  outbox.close();
});

// ---------------------------------------------------------------------------
// 脉冲：长推理期间心跳照发，这是「连接活着但一个字不来」唯一的解药
// ---------------------------------------------------------------------------
test('pulse distinguishes silent thinking from streaming and keeps reporting', () => {
  let clock = 1_000;
  const outbox = new ArchiveOutbox({ path: null });
  const hub = new SessionHub({ outbox, now: () => clock });
  assert.equal(hub.pulse('nobody').hostState, 'IDLE', '没有尾巴的会话就是空闲');

  hub.ingest('s1', event(1));
  assert.equal(hub.pulse('s1').hostState, 'STREAMING', '刚有用户输入 = 一轮正在起头');

  hub.noteRunning('s1', true);
  clock += 3_000;
  assert.equal(hub.pulse('s1').hostState, 'THINKING_SILENT', '运行中且没有增量 = 深度思考');
  assert.equal(hub.pulse('s1').silentDurationMs >= 3_000, true);

  hub.noteDelta('s1');
  assert.equal(hub.pulse('s1').hostState, 'STREAMING', '有增量就是在出字');
  outbox.close();
});

test('a session reported running by the host is not idle right after a plugin restart', () => {
  // 插件重启后 tails 是空的；60 秒一次的 session/list 必须能把运行态补回来，
  // 否则手机会把没打 turn/end 的轮次画成「已中断」（2026-11-12 反馈）。
  let clock = 100_000;
  const outbox = new ArchiveOutbox({ path: null });
  const hub = new SessionHub({ outbox, now: () => clock });
  assert.equal(hub.pulse('s1').hostState, 'IDLE', '还没接到宿主列表时空闲');
  hub.noteRunning('s1', true);
  assert.notEqual(hub.pulse('s1').hostState, 'IDLE', '宿主说在跑就不能报空闲');
  // 60 秒快照不许反过来清掉刚由 USER_INPUT / noteDelta 置起来的实时态。
  hub.noteRunning('s1', false);
  assert.notEqual(hub.pulse('s1').hostState, 'IDLE', '旧的 false 快照不能盖掉实时运行态');
  outbox.close();
});

// ---------------------------------------------------------------------------
// 实时思考：assistant-stream 帧不落盘、不进时间线，但手机必须看得见在长
// ---------------------------------------------------------------------------
test('a live delta reaches focused phones without entering the timeline', () => {
  const outbox = new ArchiveOutbox({ path: null });
  const hub = new SessionHub({ outbox });
  hub.ingest('s1', event(1));

  const before = outbox.depth().pending;
  const seen: { seq: number; kind: string; text: string }[] = [];
  hub.subscribe('s1', record => {
    if (record.kind === 'STREAM_DELTA') seen.push({ seq: record.seq, kind: record.kind, text: String((record.payload as { data?: { text?: string } }).data?.text) });
  });

  const delta = hub.ingestDelta('s1', '先看一下用户这句话想要什么。');
  assert.ok(delta, '有文本就该产出增量');
  assert.equal(delta!.kind, 'STREAM_DELTA');
  assert.equal(seen.length, 1, '聚焦中的手机会收到');

  // 累计文本会随下一片变长，手机端是替换不是追加。
  hub.ingestDelta('s1', '先看一下用户这句话想要什么。要判断涉及哪几个文件。');
  assert.equal(seen.at(-1)!.text, '先看一下用户这句话想要什么。要判断涉及哪几个文件。');

  // 增量绝不落盘：否则 /v2/sync 的游标会指向一个永远在动的目标。
  assert.equal(outbox.depth().pending, before, '增量不进 outbox（只有先前那条确定性记录在里面）');
  assert.deepEqual(hub.replay('s1', 0, 1024 * 1024).map(r => r.kind), ['USER_INPUT'], '回放里只有那条确定性记录，没有增量');
  assert.equal(outbox.ackedSeq('s1'), 0);

  // 空文本不该产出记录（重启后第一帧常是空字符串）。
  assert.equal(hub.ingestDelta('s1', ''), undefined);

  // 但脉搏必须跟着变成 STREAMING：v1 的「深度思考」卡死就是这里没信号。
  assert.equal(hub.pulse('s1').hostState, 'STREAMING');
  outbox.close();
});

test('a delta for a session with no records yet still reports it is streaming', () => {
  const outbox = new ArchiveOutbox({ path: null });
  const hub = new SessionHub({ outbox });
  // 会话刚开、第一条确定性记录还没到，seq 只能是 0。
  const delta = hub.ingestDelta('s9', '正在读文件');
  assert.ok(delta);
  assert.equal(delta!.seq, 0, '还没有任何记录时用 0，不是一个编造的数字');
  assert.equal(hub.pulse('s9').hostState, 'STREAMING');
  assert.equal(hub.latestSeq('s9'), 0, 'latestSeq 不能被增量推高');
  outbox.close();
});

test('a stub preview is truncated by utf-8 bytes, not utf-16 units', () => {
  // CJK 是 3 字节/字：按 UTF-16 单元切 1536 个 = 4528 字节，是 `stubBudget`(2 KiB)
  // 的两倍多。`recordBytes()` 量的是字节，于是策略自己产出的记录违反了它自己的预算，
  // 手机按字节分配的页预算被这些「本来该很小」的存根吃光。
  for (const [name, ch] of [['ascii', 'a'], ['cjk', '中'], ['emoji', '🙂']] as const) {
    const record = {
      sessionId: 's1', seq: 1, prevSeq: 0, turnId: 't', kind: 'STEP_TOOL' as const, timestampMs: 1,
      payload: { type: 'tool/result', data: { output: ch.repeat(300 * 1024) } },
    };
    const stub = applySizePolicy(record, () => {});
    assert.ok(stub.stub, `[${name}] 应当被离载成存根`);
    const head = stub.stub.previewHead;
    assert.ok(
      Buffer.byteLength(head, 'utf8') <= SYNC_V2.stubBudget - 512,
      `[${name}] previewHead ${Buffer.byteLength(head, 'utf8')} 字节，超过预算 ${SYNC_V2.stubBudget - 512}`,
    );
    // 整条存根（含信封）也不能超 stubBudget，否则「存根很小」这个前提就不成立了。
    assert.ok(recordBytes(stub) <= SYNC_V2.stubBudget, `[${name}] 整条存根 ${recordBytes(stub)} 字节超出预算`);
    // 截断不能留下半个字符：既不能有 U+FFFD，也不能有孤立代理。
    assert.ok(!head.includes('\uFFFD'), `[${name}] 截断留下了替换符`);
    let lone = 0;
    for (let i = 0; i < head.length; i++) {
      const code = head.charCodeAt(i);
      if (code >= 0xd800 && code <= 0xdbff) {
        const next = head.charCodeAt(i + 1);
        if (next >= 0xdc00 && next <= 0xdfff) i++; else lone++;
      } else if (code >= 0xdc00 && code <= 0xdfff) lone++;
    }
    assert.equal(lone, 0, `[${name}] 截断留下了 ${lone} 个孤立代理`);
  }
});

test('a host that renumbers seqs is visible instead of silently stopping the archive', () => {
  // 归档去重只靠 ring + outbox 主键，整条链路依赖「seq 永不复用」。宿主重启后对既有
  // 会话重新计数时，新事件全部撞主键被丢弃 —— 归档**无声**停止增长，日志里一个字都没有。
  //
  // 判据不能用「seq 是否低于水位」：正常重放（重连后宿主重发同一批事件）重复的 seq
  // 一样低于水位，那样每个快照回放都会被记成故障。用**事件类型**：重放是同一个事件，
  // 类型一致；重编是同一个 seq 变成了别的东西。
  const outbox = new ArchiveOutbox({ path: null });
  try {
    const hub = new SessionHub({ outbox, tailSize: 50 });
    for (const seq of [1, 2, 3]) hub.ingest('s1', event(seq));
    outbox.claim('b1', 10, 1024 * 1024);
    outbox.ack('b1');

    // 正常重放：同样的 seq、同样的事件类型。
    for (const seq of [1, 2, 3]) hub.ingest('s1', event(seq));
    assert.equal(hub.renumbered, 0, '正常重放不是 seq 复用');

    // 宿主倒回去重编：seq 2 变成了另一种事件。
    hub.ingest('s1', { type: 'turn/end', seq: 2, data: { reason: 'done' } });
    assert.equal(hub.renumbered, 1, '同一个 seq 换了事件类型 = 宿主重编，必须可见');
  } finally {
    outbox.close();
  }
});
