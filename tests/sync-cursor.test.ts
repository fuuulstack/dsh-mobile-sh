import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { Relay } from '../packages/relay/src/server.js';
import { secret, uuid } from '../packages/relay/src/crypto.js';
import { SYNC_V2 } from '../packages/core/src/index.js';

/**
 * 阶段 B：`/native/v2/hosts/:id/sync` 的续传协议（docs/PRD.md §6.4 / §6.5）。
 *
 * 这一组测试把「响应到底覆盖到哪个归档位置」钉成契约：每一页必须带
 * `nextCursorSeq`，且它只能等于**真正放进这一批 records 里的最后一个 seq**。
 * v1 的 `/sync`（`deltas` + `nextCursor` + `latestSeq`）已随 v1 面整体下线，
 * 本文件原来测的是那套形状，现在整体改钉 v2 的 `results` + `nextCursorSeq`。
 * 换的是字段名，**保证一条没减少**：
 *
 * - 空结果不等于没活干：被预算截断的一页必须把游标推过它确实送出的那些，并报
 *   `hasMore: true`。游标不动会把手机的补差循环变成原地打转，v1 的
 *   「26/29 次空页、正文一动不动」就是这么来的。
 * - 刚好满页（240/240）不等于「还有」：`hasMore` 靠多读一行探测出来，而不是从
 *   「页不满」猜——v1 正是这么永久丢掉第 241 条之后的。
 * - 点名要哪个会话就只回答哪个会话，中继不替客户端扫它没点名的会话。
 */

function request(port: number, path: string, token: string, data?: Buffer, method = 'POST') {
  return new Promise<{ status: number; body: Buffer }>((resolve, reject) => {
    const req = http.request({
      hostname: '127.0.0.1', port, path, method,
      headers: { host: 'relay.example.test', authorization: `Bearer ${token}`, ...(data ? { 'content-type': 'application/json', 'content-length': data.length } : {}) },
    }, res => {
      const chunks: Buffer[] = []; res.on('data', b => chunks.push(b));
      res.on('end', () => resolve({ status: res.statusCode!, body: Buffer.concat(chunks) }));
    });
    req.on('error', reject); req.end(data);
  });
}

type Record_ = { event?: { seq: number }; seq?: number; kind?: string; payload?: { type?: string } };
type Page = {
  sessionId: string; reqAfterSeq: number; nextCursorSeq: number; archiveCoveredSeq: number;
  hasMore: boolean; stopReason: string; records: Record_[];
};
type Body = { hostId: string; results: Page[] };

/** 一套「已经有 N 条归档记录」的中继，返回可以直接打 `/v2/sync` 的句柄。 */
/** `bigSeq`/`bigPad` 让**同一个会话里只把某一条**撑大——超大记录的场景必须和
 *  正常记录同处一条时间线，否则测不出「替换掉这一行之后后面还发不发得出去」。 */
async function fixture(t: test.TestContext, sessions: Array<{ id: string; seqs: number[]; pad?: number; bigSeq?: number; bigPad?: number }>) {
  const relay = new Relay({ baseUrl: 'https://relay.example.test', database: ':memory:', key: Buffer.alloc(32, 9), requireTotp: false, testMode: true });
  const port = await relay.listen(0);
  t.after(async () => { await relay.close(); });
  await relay.store.createAccount('a@example.test', 'long-test-password', false);
  const admin = relay.store.session((await relay.store.login('a@example.test', 'long-test-password', '', false)).token, 'account');
  const hostTunnelToken = secret();
  const { hostId } = relay.store.enroll(relay.store.invitation(admin), { operationId: uuid(), hostPublicId: uuid(), hostTunnelToken, alias: 'cursor-host' });
  const phone = await relay.store.login('a@example.test', 'long-test-password', '', false, 'Android', true);
  relay.store.pair(relay.store.session(phone.token, 'native'), relay.store.createPair(admin, hostId).code);

  // 归档要写 v2 的 `timeline_records`（`/v2/sync` 只读这张表）。写入走插件的同一条
  // 通路 `archiveTimeline`，`byteSize: 0` 让服务端按实际文本长度算，`record` 用
  // `{event:{...}}` —— 这正是手机 `Projector` 读的形状。
  const entries = sessions.flatMap(s => s.seqs.map(seq => ({
    sessionId: s.id, seq, prevSeq: seq - 1, turnId: `turn-${s.id}`,
    kind: 'ASSISTANT_TEXT', timestampMs: 1000 + seq, byteSize: 0,
    record: { event: { type: 'assistant/message', seq, data: { message: { content: [{ type: 'text', text: 'x'.repeat(s.bigSeq === seq ? (s.bigPad ?? 8) : (s.pad ?? 8)) }] } } } },
  })));
  if (entries.length) relay.store.archiveTimeline(hostId, `seed-${uuid()}`, entries);

  const post = (cursors: Record<string, number>, extra: Record<string, unknown> = {}) => request(
    port, `/native/v2/hosts/${hostId}/sync`, phone.token,
    Buffer.from(JSON.stringify({
      mode: 'FOREGROUND_SINGLE',
      sessions: Object.entries(cursors).map(([sessionId, afterSeq]) => ({ sessionId, afterSeq, priority: 10 })),
      ...extra,
    })),
  );
  const sync = async (cursors: Record<string, number>, extra: Record<string, unknown> = {}) => {
    const res = await post(cursors, extra);
    assert.equal(res.status, 200);
    return JSON.parse(res.body.toString('utf8')) as Body;
  };
  return { relay, hostId, sync, post, entries };
}

const seqsOf = (p: Page) => p.records.map(r => r.event!.seq);

test('a session that fits in one page reports hasMore=false and nextCursorSeq at the newest seq', { timeout: 20000 }, async t => {
  const { sync } = await fixture(t, [{ id: 'session-a', seqs: [1, 2, 3] }]);
  const body = await sync({ 'session-a': 0 });
  assert.equal(body.results.length, 1);
  const [page] = body.results;
  assert.deepEqual(seqsOf(page!), [1, 2, 3]);
  assert.equal(page!.nextCursorSeq, 3);
  assert.equal(page!.archiveCoveredSeq, 3);
  assert.equal(page!.hasMore, false);
  assert.equal(page!.stopReason, 'TAIL_COMPLETE');
});

test('exactly pageRecords records are a complete page, not a false "more"', { timeout: 20000 }, async t => {
  const { sync } = await fixture(t, [{ id: 'session-a', seqs: Array.from({ length: SYNC_V2.pageRecords }, (_, i) => i + 1) }]);
  const body = await sync({ 'session-a': 0 });
  const [page] = body.results;
  assert.equal(page!.records.length, SYNC_V2.pageRecords);
  assert.equal(page!.nextCursorSeq, SYNC_V2.pageRecords);
  // 全给完了，第 241 条并不存在 —— 不能因为「刚好等于上限」就喊 hasMore。
  assert.equal(page!.hasMore, false);
});

test('one record past a full page comes back as two pages, the second starting exactly after the first', { timeout: 20000 }, async t => {
  const total = SYNC_V2.pageRecords + 1;
  const { sync } = await fixture(t, [{ id: 'session-a', seqs: Array.from({ length: total }, (_, i) => i + 1) }]);
  const first = await sync({ 'session-a': 0 });
  const [p1] = first.results;
  assert.equal(p1!.records.length, SYNC_V2.pageRecords);
  assert.equal(p1!.nextCursorSeq, SYNC_V2.pageRecords);
  assert.equal(p1!.hasMore, true, '第 241 条存在，必须报 hasMore');
  // 关键回归：旧实现这里会是 false —— 第 241 条永久丢失。
  const second = await sync({ 'session-a': p1!.nextCursorSeq });
  const [p2] = second.results;
  assert.deepEqual(seqsOf(p2!), [total]);
  assert.equal(p2!.nextCursorSeq, total);
  assert.equal(p2!.hasMore, false);
});

test('a byte budget that runs dry mid-page moves nextCursorSeq only as far as it actually delivered', { timeout: 20000 }, async t => {
  const { sync } = await fixture(t, [{ id: 'session-a', seqs: Array.from({ length: 100 }, (_, i) => i + 1), pad: 2000 }]);
  const body = await sync({ 'session-a': 0 }, { pageByteBudget: 16 * 1024 });
  const [page] = body.results;
  assert.ok(page!.records.length > 0 && page!.records.length < 100, `应当只装下一部分，实际 ${page!.records.length}`);
  const delivered = seqsOf(page!);
  // 游标必须停在真正送达的最后一条上，不能是 100（水位）也不能是 0。
  assert.equal(page!.nextCursorSeq, delivered[delivered.length - 1]);
  assert.equal(page!.hasMore, true);
  assert.equal(page!.stopReason, 'BUDGET_EXHAUSTED');
  // 从这里续传，剩下的必须一条不少。
  const rest = await sync({ 'session-a': page!.nextCursorSeq });
  assert.deepEqual([...delivered, ...seqsOf(rest.results[0]!)], Array.from({ length: 100 }, (_, i) => i + 1));
});

test('a single record larger than the normal budget still ships alone instead of stalling', { timeout: 20000 }, async t => {
  // 一条 ~30KiB 的记录 + 16KiB 预算是评审实测的现场形态：预算放不下也要单独发一条，
  // 否则客户端拿着空页原地打转，正文水位一动不动。
  const { sync } = await fixture(t, [{ id: 'session-a', seqs: [1, 2], pad: 30000 }]);
  const body = await sync({ 'session-a': 0 }, { pageByteBudget: 16 * 1024 });
  const [page] = body.results;
  assert.equal(page!.records.length, 1, '预算放不下也要单独发一条，不能返回空页');
  assert.deepEqual(seqsOf(page!), [1]);
  assert.equal(page!.nextCursorSeq, 1);
  assert.equal(page!.hasMore, true, '后面还有 seq 2');
});

test('a record past the protocol hard ceiling becomes a visible marker and never blocks the cursor', { timeout: 30000 }, async t => {
  // v1 的形态是「一条超大记录 + 小预算 = 同一个游标永远重来」，也就是永久卡死。
  // v2 的取舍写死在 `pageSession` 里：超硬上限的那一行**替换成显式的截断标记**，
  // 游标照常推过它 —— 手机看到一条「原文被扣下了」，比整条时间线停在这里好。
  const { sync } = await fixture(t, [{ id: 'session-a', seqs: [1, 2], bigSeq: 1, bigPad: SYNC_V2.hardCeiling }]);
  const body = await sync({ 'session-a': 0 }, { pageByteBudget: 16 * 1024 });
  const [page] = body.results;
  // 替身很小，所以这一页把后面那条也带上了 —— 替换掉一行就等于把那 10 MiB 从
  // 预算里拿掉了，这是「标记」相对「原样转发」的第二个好处。
  assert.equal(page!.records.length, 2, `超大记录换成一个替身后，余量还够发下一条：${JSON.stringify(page!.records.map(r => r.kind ?? r.event!.seq))}`);
  const marker = page!.records[0]!;
  assert.equal(marker.kind, 'ERROR_OVERSIZED_TRUNCATED');
  assert.equal(marker.seq, 1);
  assert.equal(marker.payload!.type, 'error/oversized-truncated');
  assert.equal(page!.records[1]!.event!.seq, 2);
  assert.equal(page!.nextCursorSeq, 2, '游标必须推过它，否则永远重来');
  assert.equal(page!.hasMore, false);
  // 续传不能把那条超大记录又发一遍。
  const rest = await sync({ 'session-a': page!.nextCursorSeq });
  assert.deepEqual(seqsOf(rest.results[0]!), []);
});

test('non-contiguous seqs (3, 28, 59) page correctly: the cursor follows real seqs, never cursor+1', { timeout: 20000 }, async t => {
  const { sync } = await fixture(t, [{ id: 'session-a', seqs: [3, 28, 59] }]);
  const body = await sync({ 'session-a': 0 });
  const [page] = body.results;
  assert.deepEqual(seqsOf(page!), [3, 28, 59]);
  assert.equal(page!.nextCursorSeq, 59);
  assert.equal(page!.hasMore, false);
  // 游标设在 28：只剩 59 —— 而且不能因为 29 不存在就报缺口。
  const rest = await sync({ 'session-a': 28 });
  assert.deepEqual(seqsOf(rest.results[0]!), [59]);
});

test('each session keeps its own nextCursorSeq when the global budget runs out', { timeout: 20000 }, async t => {
  const { sync } = await fixture(t, [
    { id: 'session-a', seqs: [1, 2, 3], pad: 6000 },
    { id: 'session-b', seqs: [1, 2, 3], pad: 6000 },
  ]);
  const body = await sync({ 'session-a': 0, 'session-b': 0 }, { pageByteBudget: 16 * 1024 });
  assert.ok(body.results.length >= 1);
  for (const page of body.results) {
    // 空页只能停在请求的那个游标上（这里就是 0），有页就必须停在这页最后一条上。
    assert.equal(page.nextCursorSeq, seqsOf(page).at(-1) ?? 0);
  }
  const cursors = Object.fromEntries(body.results.map(p => [p.sessionId, p.nextCursorSeq]));
  const rest = await sync(cursors, { pageByteBudget: 1024 * 1024 });
  const seen = new Map<string, number[]>();
  for (const page of [...body.results, ...rest.results]) {
    seen.set(page.sessionId, [...(seen.get(page.sessionId) ?? []), ...seqsOf(page)]);
  }
  for (const id of ['session-a', 'session-b']) {
    assert.deepEqual(seen.get(id), [1, 2, 3], `${id} 的记录必须一条不少地补齐`);
  }
});

test('records that land between two calls are counted against the newer watermark', { timeout: 20000 }, async t => {
  const { relay, hostId, sync } = await fixture(t, [{ id: 'session-a', seqs: [1, 2] }]);
  const first = await sync({ 'session-a': 0 });
  assert.equal(first.results[0]!.nextCursorSeq, 2);
  // 请求之间宿主又推进了两条：本轮的 archiveCoveredSeq 必须是新的水位，
  // 而 nextCursorSeq 只覆盖这次真正送回的那些。
  relay.store.archiveTimeline(hostId, `more-${uuid()}`, [3, 4].map(seq => ({
    sessionId: 'session-a', seq, prevSeq: seq - 1, turnId: 'turn-session-a',
    kind: 'ASSISTANT_TEXT', timestampMs: 1000 + seq, byteSize: 0,
    record: { event: { type: 'assistant/message', seq, data: { message: { content: [{ type: 'text', text: 'y' }] } } } },
  })));
  const second = await sync({ 'session-a': 2 });
  const [page] = second.results;
  assert.equal(page!.archiveCoveredSeq, 4);
  assert.deepEqual(seqsOf(page!), [3, 4]);
  assert.equal(page!.nextCursorSeq, 4);
  assert.equal(page!.hasMore, false);
});

test('a client that sends a nonsense cursor is clamped, not 500', { timeout: 20000 }, async t => {
  const { relay, hostId, sync } = await fixture(t, [{ id: 'session-a', seqs: [1, 2] }]);
  // 负游标、多余字段都要被吞掉。负游标绝不能被解释成「从末尾开始」——那会静默跳过历史。
  const body = await sync({ 'session-a': -1 }, { tier: 'recent', nonsense: true });
  assert.equal(body.results.length, 1);
  assert.equal(body.results[0]!.reqAfterSeq, 0);
  assert.deepEqual(seqsOf(body.results[0]!), [1, 2]);
  assert.equal(body.results[0]!.nextCursorSeq, 2);
  assert.ok(Array.isArray(relay.store.sessionItems(hostId)));
});

test('sync stats make repeat downloads and repeat pages visible', { timeout: 20000 }, async t => {
  // 手机侧只会看到「消息晚到」，看不到「到底跑了几趟、每趟有没有进展」。
  // 评审现场「同一批 3/28/21 条记录被反复下载十几分钟、正文却一动不动」
  // 在服务端本来是可数的——这里把它钉成契约。
  const { relay, sync } = await fixture(t, [{ id: 'sess-a', seqs: [3, 28, 59] }]);
  // v2 的计数器是普通对象，不是 v1 那个带 `snapshot()` 的类。
  const stats = () => relay.syncStatsV2;

  const page = await sync({ 'sess-a': 0 });
  assert.deepEqual(seqsOf(page.results[0]!), [3, 28, 59]);
  assert.equal(stats().requests, 1);
  assert.equal(stats().pages, 1);
  assert.equal(stats().records, 3);
  assert.ok(stats().bytes > 0, '字节数必须真的累加，否则「预算被谁吃掉」无从判断');
  assert.equal(stats().zeroProgress, 0);

  // 客户端记住了游标：第二轮问的是 59，服务端没有新东西可给。
  const caughtUp = await sync({ 'sess-a': 59 });
  assert.deepEqual(seqsOf(caughtUp.results[0]!), []);
  assert.equal(caughtUp.results[0]!.stopReason, 'TAIL_COMPLETE');
  assert.equal(stats().requests, 2);
  assert.equal(stats().records, 3, '空页不该往上加记录数');
  // 这一页是真的一条没给，但它说清了 `hasMore: false`，所以不算零进展。
  assert.equal(stats().zeroProgress, 0, '已经追平的会话不是零进展');
});

test('asking for one session never lets idle sessions eat its budget', { timeout: 20000 }, async t => {
  // 现场回归：手机只点名要 session-target，但它不是「最近更新」的那几个，
  // 旧实现把 summary 全遍历一遍，其它会话按各自游标各吃一份预算、占满会话上限，
  // 点名的那个反被跳过 —— 29 次单会话补页里 26 次是空响应。
  // v2 的做法是**根本不扫没点名的会话**：请求里只有 target，响应里就只该有 target。
  const others = Array.from({ length: 6 }, (_, i) => ({ id: `session-idle-${i}`, seqs: [1, 2, 3] }));
  const { sync } = await fixture(t, [...others, { id: 'session-target', seqs: [7, 8] }]);

  const body = await sync({ 'session-target': 0 }, { pageByteBudget: 200 * 1024 });
  const ids = body.results.map(p => p.sessionId);
  assert.deepEqual(ids, ['session-target'], `只问一个会话就只该回一个，多回的都是白啃预算：${JSON.stringify(ids)}`);
  assert.deepEqual(seqsOf(body.results[0]!), [7, 8]);
});

test('a session behind by hundreds of records catches up in a few pages, not hundreds', { timeout: 20000 }, async t => {
  // 现场回归：单会话补页给 16KB，而记录常见 15~31KB → 每轮只进一条
  // （from=921 → next=922，水位 1588）。这里按真实预算算收敛轮数。
  const seqs = Array.from({ length: 300 }, (_, i) => i + 1);
  const { sync } = await fixture(t, [{ id: 'session-big', seqs, pad: 3000 }]);
  let cursor = 0;
  let rounds = 0;
  while (rounds < 50) {
    rounds++;
    const body = await sync({ 'session-big': cursor }, { pageByteBudget: 200 * 1024 });
    const page = body.results[0]!;
    assert.ok(page.nextCursorSeq > cursor, `第 ${rounds} 轮游标没有前进，会原地打转`);
    cursor = page.nextCursorSeq;
    if (!page.hasMore) break;
  }
  assert.equal(cursor, 300, '落后 300 条必须靠少数几页补齐');
  assert.ok(rounds <= 10, `落 300 条用了 ${rounds} 轮，预算仍然太小`);
});

test('a self-hosted relay does not throttle normal reads, but still throttles credential guessing', { timeout: 30000 }, async t => {
  // 2026-10-29 现场：冷启动四十多个会话补页，`/sync` 那档 30 次/分钟被瞬间打满，
  // 一路 429 拖了三分钟——没有一条请求是攻击，全是正常读。这是自托管中继，一个部署
  // 通常只有一个人用，所以资源类限流的唯一目的应该是「别被坏掉的客户端打穿」，
  // 而不是按公网多租户的量级去卡。凭据类（登录/设备码）防的是猜，必须保持严格。
  const sessions = Array.from({ length: 80 }, (_, i) => ({ id: `session-n${i}`, seqs: [1, 2, 3] }));
  const { hostId, post } = await fixture(t, sessions);
  for (const session of sessions) {
    const res = await post({ [session.id]: 0 });
    assert.notEqual(res.status, 429, `正常读被打成限流：${session.id} / ${res.body.toString('utf8').slice(0, 120)}`);
    assert.equal(res.status, 200);
  }
  assert.ok(hostId, 'fixture 必须真的建出主机');
});

// ---------------------------------------------------------------
// 循环护栏：只护单会话请求，不许误伤整批
// ---------------------------------------------------------------
test('a stuck cursor in a multi-session batch does not 429 the whole batch', { timeout: 20000 }, async t => {
  // 事故形态：护栏对 batch 里**每个**会话记数，于是某个「预算被别的会话吃光」的
  // 会话每轮带同一个 afterSeq 回来，10 秒内第 6 次起**整批** 429 —— 包括正在
  // 前进的那些。注释当时自己写的是「只护第一个会话」，代码与注释矛盾。
  const { relay, hostId } = await fixture(t, [
    { id: 'session-a', seqs: [1, 2, 3] },
    { id: 'session-b', seqs: [1, 2, 3] },
  ]);
  const phone = await relay.store.login('a@example.test', 'long-test-password', '', false, 'Android', true);
  // 重新配一次对，拿到这只手机自己的 token 上下文（fixture 里那只已经登录过）。
  relay.store.pair(relay.store.session(phone.token, 'native'), relay.store.createPair(relay.store.session(phone.token, 'native'), hostId).code);

  const batch = () => Buffer.from(JSON.stringify({
    mode: 'BACKGROUND_BATCH',
    pageByteBudget: 512 * 1024,
    sessions: [
      { sessionId: 'session-a', afterSeq: 0, priority: 10 },
      { sessionId: 'session-b', afterSeq: 0, priority: 10 },
    ],
  }));
  const path = `/native/v2/hosts/${hostId}/sync`;

  // 连续 8 轮都带同一个 afterSeq（模拟「预算被吃光、游标不动」）。单会话路径
  // 第 6 次就该 429；batch 路径一次都不该。
  for (let round = 0; round < 8; round++) {
    const res = await request(port0(), path, phone.token, batch());
    assert.equal(res.status, 200, `第 ${round + 1} 轮 batch 被 429 了：${res.body.toString('utf8').slice(0, 120)}`);
  }

  // 同一只手机、同一会话，走**单会话**路径时护栏必须照常生效。
  let throttled = 0;
  for (let round = 0; round < 8; round++) {
    const res = await request(port0(), path, phone.token, Buffer.from(JSON.stringify({
      mode: 'FOREGROUND_SINGLE',
      pageByteBudget: 512 * 1024,
      sessions: [{ sessionId: 'session-a', afterSeq: 0, priority: 100 }],
    })));
    if (res.status === 429) throttled++;
  }
  assert.ok(throttled > 0, '单会话原地打转必须被护栏拦住，否则手机端会 hot loop');

  function port0() {
    return (relay.server.address() as { port: number }).port;
  }
});

// ---------------------------------------------------------------
// timelineSummary 必须走覆盖索引
// ---------------------------------------------------------------
test('the per-session coverage query uses a covering index, not a table scan', { timeout: 20000 }, async t => {
  // `timelineSummary()` 挂在**每一页** `/v2/sync` 和每一次 `/control/timeline` 上，
  // 而它是「某台主机的整条时间线 GROUP BY session_id」。没有覆盖索引时那是一次
  // 全表扫 + 每行回表（生产实测 49 749 行 / 120 会话：warm 83 ms 同步阻塞，
  // 2 核机上直接卡住同时服务隧道的事件循环）。
  //
  // 判据不看耗时（CI 上不稳），看**查询计划**：必须是 COVERING INDEX，
  // 出现 SCAN 或 SEARCH ... USING INDEX（非 covering，需要回表）都算失败。
  const { relay } = await fixture(t, [{ id: 'session-a', seqs: [1, 2, 3] }]);
  const db = (relay.store as unknown as { db: { prepare(sql: string): { all(...a: unknown[]): Array<{ detail?: string }> } } }).db;
  const plan = db.prepare(`EXPLAIN QUERY PLAN
    SELECT session_id sessionId,MAX(seq) latestSeq,MAX(updated_at) updatedAt,COUNT(*) count
    FROM timeline_records WHERE host_id=? GROUP BY session_id ORDER BY updatedAt DESC`).all('h1');
  const text = plan.map(row => row.detail ?? '').join(' | ');
  assert.ok(
    text.includes('COVERING INDEX timeline_coverage'),
    `timelineSummary 没有走覆盖索引，计划是：${text}`,
  );
  assert.ok(!/\bSCAN timeline_records\b/.test(text), `出现了全表扫：${text}`);
});
