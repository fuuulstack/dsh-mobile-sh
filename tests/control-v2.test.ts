import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { ArchiveOutbox } from '../packages/plugin/src/outbox.js';
import { SessionHub } from '../packages/plugin/src/hub.js';
import { ControlSession } from '../packages/plugin/src/control.js';
import { SYNC_V2, decodeMuxFrame, encodeMuxFrame, type PluginToPhone, type PhoneToPlugin } from '../packages/core/src/index.js';

/**
 * 插件侧控制流（`CONTROL_PATH`）的回归。
 *
 * <p>这条 socket 同时承担三件必须各自正确的事：立刻回放（不等宿主）、每 5 秒无条件
 * 发脉搏（长推理不能被误判成死连接）、以及写操作的幂等（手机 3 秒 ACK 竞速会把同一个
 * `cmdId` 经 HTTPS 旁路重发一次）。任何一件错，用户看到的都是「卡住了」或「点一次
 * 审批生效两次」，而两者都不抛异常 —— 所以断言一律落在**实际发出的帧**与**实际打到
 * 宿主的请求**上。
 *
 * <p>`MessageBridge` 是真的，只有 `Channel` 是替身：手写一个「按帧序号收发」的假
 * 对端就等于再实现一遍同一套流控，那样测的是假对端而不是被测代码。
 */

type SentFrame = PluginToPhone;

/**
 * `Channel` 的替身：够 `MessageBridge` 用即可。
 *
 * 出站（插件 → 手机）：bridge 先 `send({ws-message,messageId,byteLength})` 再
 * `data(bytes)`，这里按 messageId 攒回完整正文。
 * 入站（手机 → 插件）：测试 `fromPhone()` 发头、再喂正文，bridge 交给 ControlSession。
 */
class FakeChannel extends EventEmitter {
  /** 控制帧（ws-accept / ws-message 头 / ws-message-ready …）。 */
  readonly control: Record<string, unknown>[] = [];
  private readonly bodies = new Map<number, Buffer[]>();
  /** 每个 ws-message 头声明的正文长度：收满了就要像真手机一样回 delivered。 */
  private readonly expected = new Map<number, number>();
  private receive?: (bytes: Buffer) => Promise<void> | void;
  private nextMessageId = 1;

  async send(frame: Record<string, unknown>): Promise<void> {
    this.control.push(frame);
    if (frame.type === 'ws-message') this.expected.set(frame.messageId as number, frame.byteLength as number);
  }

  async data(bytes: Buffer): Promise<void> {
    // 正文没有自带 id：紧跟在最近一个 ws-message 头之后。
    const header = [...this.control].reverse().find(f => f.type === 'ws-message');
    if (!header) throw new Error('data without header');
    const id = header.messageId as number;
    const list = this.bodies.get(id) ?? [];
    list.push(Buffer.from(bytes));
    this.bodies.set(id, list);
    const want = this.expected.get(id) ?? 0;
    const got = list.reduce((n, b) => n + b.length, 0);
    // 真手机在收满一条消息后才回 `delivered`；bridge 的发送窗口（WINDOW=4）靠它
    // 推进。缺了这个 ack，第一批 4 条之后就再也不发了 —— 那是**替身**的缺失，
    // 不是被测代码的缺陷，但会让「大回放切帧」看起来只发了几帧就停住。
    if (got >= want && !this.acknowledged.has(id)) {
      this.acknowledged.add(id);
      // 必须晚一拍：bridge 在 `await channel.data(b)` **之后**才注册 `delivered`
      // 的监听，同步 emit 会打在空处，然后卡在 WINDOW=4 再不动。真手机的 ack 本来
      // 也要过一趟网络，晚一拍才是它的真实时序。
      setImmediate(() => this.emit('control', { v: 3, type: 'ws-message-ready', messageId: id, phase: 'delivered' }));
    }
  }
  private readonly acknowledged = new Set<number>();

  startReceive(handler: (bytes: Buffer) => Promise<void> | void): void {
    this.receive = handler;
  }

  /** 模拟手机发来一帧：先发头，再把正文交给 bridge 的接收回调。 */
  async fromPhone(frame: PhoneToPlugin): Promise<void> {
    const body = Buffer.from(encodeMuxFrame(frame), 'utf8');
    const messageId = this.nextMessageId++;
    this.emit('control', { v: 3, type: 'ws-message', messageId, opcode: 'text', byteLength: body.length });
    await this.settle();
    const receive = this.receive;
    if (!receive) throw new Error('bridge never reserved a receive store');
    await receive(body);
    await this.settle();
  }

  /** 让 bridge 的 promise 链跑完（它是 then 串起来的，没有事件可等）。 */
  async settle(): Promise<void> {
    for (let i = 0; i < 8; i++) await new Promise<void>(r => setImmediate(r));
  }

  /** 插件发出去的所有协议帧，按发送顺序。 */
  frames(): SentFrame[] {
    const out: SentFrame[] = [];
    for (const header of this.control) {
      if (header.type !== 'ws-message') continue;
      const body = Buffer.concat(this.bodies.get(header.messageId as number) ?? []);
      if (!body.length) continue;
      const frame = decodeMuxFrame(body.toString('utf8'));
      // decodeMuxFrame 的返回类型是两个方向的联合：这条 socket 上只有插件往外发，
      // 所以这里能安全地收窄。
      if (frame) out.push(frame as SentFrame);
    }
    return out;
  }

  /** 只关心某一类帧。 */
  framesOf<T extends SentFrame['type']>(type: T): Extract<SentFrame, { type: T }>[] {
    return this.frames().filter((f): f is Extract<SentFrame, { type: T }> => f.type === type);
  }
}

type Delivered = { url: string; body: unknown };

function relayOf(delivered: Delivered[], failPaths: string[] = [], failTimes = 1): typeof fetch {
  const remaining = new Map<string, number>();
  return (async (input: string | URL | Request, init?: RequestInit) => {
    const url = String(input);
    const path = new URL(url).pathname;
    delivered.push({ url, body: init?.body });
    // `failPaths` 让某条 loopback RPC 回 500，用来验「这一步失败之后发生了什么」。
    // `failTimes` 是**前几次**失败：验回队时必须让第一次 prompt 失败、第二次成功，
    // 否则测的是「回队也失败」那条分支。
    const hit = failPaths.find(p => path === p || path.endsWith(p));
    if (hit) {
      const left = remaining.get(hit) ?? failTimes;
      if (left > 0) {
        remaining.set(hit, left - 1);
        return new Response('boom', { status: 500 });
      }
    }
    return new Response('{}', { status: 200 });
  }) as typeof fetch;
}

function setup(options: { tailSize?: number; delivered?: Delivered[]; failPaths?: string[]; failTimes?: number } = {}) {
  const outbox = new ArchiveOutbox({ path: null });
  const hub = new SessionHub({ outbox, tailSize: options.tailSize ?? 50 });
  const channel = new FakeChannel();
  const delivered = options.delivered ?? [];
  const session = new ControlSession(channel as never, {
    hub,
    upstreamOrigin: 'http://127.0.0.1:9749',
    getCookie: async () => 'dsh-auth-test=1',
    workerEpoch: 7,
    buildHash: 'abc123',
    fetch: relayOf(delivered, options.failPaths, options.failTimes),
  });
  return { outbox, hub, channel, session, delivered };
}

function event(seq: number, text = `m${seq}`) {
  return { type: 'user/message', seq, data: { message: { content: [{ type: 'text', text }] } } };
}

// ---------------------------------------------------------------------------
// 握手
// ---------------------------------------------------------------------------
test('accept acknowledges the upgrade and HELLO answers with epoch and build hash', async () => {
  const { channel, session, outbox } = setup();
  await session.accept();
  assert.equal(channel.control[0]?.type, 'ws-accept', '必须先回 ws-accept，否则中继不给手机开流');

  await channel.fromPhone({ type: 'HELLO', buildHash: 'phone-99', capabilities: ['records', 'pulse', 'cmd'] });
  const ack = channel.framesOf('HELLO_ACK')[0];
  assert.ok(ack, 'HELLO 必须有应答');
  assert.equal(ack!.buildHash, 'abc123');
  assert.equal(ack.workerEpoch, 7, '手机据此判断「插件的包是不是旧的」');
  session.close();
  outbox.close();
});

test('a frame that is not ours is ignored instead of tearing the socket down', async () => {
  const { channel, session, outbox } = setup();
  await session.accept();
  const raw = Buffer.from(JSON.stringify({ hello: 'world' }), 'utf8');
  // 直接把这个非协议正文交给 bridge 的接收口：它必须是「解码返回 undefined → 忽略」。
  const messageId = 99;
  channel.emit('control', { v: 3, type: 'ws-message', messageId: 1, opcode: 'text', byteLength: raw.length });
  await channel.settle();
  const receive = (channel as unknown as { receive?: (b: Buffer) => Promise<void> }).receive;
  assert.ok(receive);
  await receive!(raw);
  await channel.settle();
  assert.equal(channel.frames().length, 0, '外来帧不该产出任何协议帧');
  session.close();
  outbox.close();
});

// ---------------------------------------------------------------------------
// 聚焦：立刻回放，绝不等宿主
// ---------------------------------------------------------------------------
test('focus replays the tail synchronously and reports the gap below it', async () => {
  const { channel, hub, session, outbox } = setup({ tailSize: 4 });
  for (const seq of [1, 2, 3, 4, 5, 6]) hub.ingest('s1', event(seq));
  await session.accept();
  // 手机只到 1，而内存里只剩 3..6：2 已经滑出窗口，那是中继归档才能补的一段。
  await channel.fromPhone({ type: 'FOCUS_SESSION', sessionId: 's1', sinceSeq: 1 });

  const frames = channel.framesOf('RECORDS');
  assert.equal(frames.length, 1, '一次聚焦只发一帧回放');
  assert.deepEqual(frames[0]!.records.map(r => r.seq), [3, 4, 5, 6], '窗口里有什么就给什么');
  assert.equal(frames[0]!.hasMore, true, '2 滑出了窗口，hasMore 必须说出来——不能靠沉默');
  // 窗口顶端已经追上最新：这一帧就是完整的尾部，剩下的缺口归 /v2/sync。
  assert.equal(frames[0]!.stopReason, 'TAIL_COMPLETE');
  session.close();
  outbox.close();
});

test('a large replay is split into frames that each fit one tunnel message', async () => {
  // Live regression: a 147-record replay went out as ONE websocket frame of ~460 KiB.
  // The tunnel's credit window is smaller than that, so the send parked on flow
  // control with a few KiB outstanding (`tunnel credit stall 10s pending=4KiB`) and
  // the phone received HELLO_ACK and then nothing, forever.
  const { channel, hub, session, outbox } = setup({ tailSize: 400 });
  // 每条 ~2 KiB：整段回放仍在 hub 的 512 KiB 预算之内（所以 120 条全都该发出去），
  // 但远超 16 KiB 的攒批额度，必然要切成多帧。
  const body = 'x'.repeat(2 * 1024);
  for (let seq = 1; seq <= 120; seq++) hub.ingest('s1', { type: 'tool/result', seq, data: { output: body } });
  await session.accept();
  await channel.fromPhone({ type: 'FOCUS_SESSION', sessionId: 's1', sinceSeq: 0 });

  const frames = channel.framesOf('RECORDS');
  assert.ok(frames.length > 1, '120 条 2KiB 的记录不可能塞进一帧');
  const seqs = frames.flatMap(f => f.records.map(r => r.seq));
  assert.deepEqual(seqs, Array.from({ length: 120 }, (_, i) => i + 1), '切帧不许丢、不许重、不许乱序');
  for (const frame of frames) {
    // 判据是 SYNC_V2.coalesceBytes 量级，不是「比原来小」——留一帧的余量即可。
    // 这条护栏与 coalesceBytes 是同一个数：把它调大就必须回到这里重新量一次，
    // 否则切帧预算会悄悄越过隧道信用窗口（坑 9 的 `tunnel credit stall`）。
    const bytes = Buffer.byteLength(JSON.stringify(frame), 'utf8');
    assert.ok(
      bytes <= SYNC_V2.coalesceBytes * 1.5,
      `单帧 ${bytes} 字节，超过一条隧道消息的实际配额（coalesceBytes=${SYNC_V2.coalesceBytes}）`,
    );
  }
  // 完整回放没有缺口时，任何一片都不该说自己还有更多。
  assert.deepEqual(frames.map(f => f.hasMore), frames.map(() => false));
  session.close();
  outbox.close();
});

test('focus with nothing below sinceSeq says hasMore false so no sync is wasted', async () => {
  const { channel, hub, session, outbox } = setup({ tailSize: 4 });
  for (const seq of [1, 2, 3, 4, 5, 6]) hub.ingest('s1', event(seq));
  await session.accept();
  // sinceSeq 还落在窗口里（窗口是 3..6）：手机没有缺口，不该再去 /v2/sync。
  await channel.fromPhone({ type: 'FOCUS_SESSION', sessionId: 's1', sinceSeq: 3 });
  const frame = channel.framesOf('RECORDS')[0];
  assert.deepEqual(frame?.records.map(r => r.seq), [4, 5, 6]);
  assert.equal(frame?.hasMore, false, '3 还在窗口里，就没有缺的');
  session.close();
  outbox.close();
});

test('a brand-new phone (sinceSeq 0) is told there is more below the tail window', async () => {
  // 装机后第一次进会话：本地一条都没有，afterSeq 就是 0。hub 只留尾部窗口，
  // 更老的部分只能在归档里 —— 这一档恰恰最需要 hasMore=true。
  const { channel, hub, session, outbox } = setup({ tailSize: 4 });
  for (const seq of [1, 2, 3, 4, 5, 6]) hub.ingest('s1', event(seq));
  await session.accept();
  await channel.fromPhone({ type: 'FOCUS_SESSION', sessionId: 's1', sinceSeq: 0 });
  const frame = channel.framesOf('RECORDS')[0];
  assert.deepEqual(frame?.records.map(r => r.seq), [3, 4, 5, 6], '只回放窗口内的尾部');
  assert.equal(frame?.hasMore, true, 'sinceSeq=0 时窗口下面一定还有，必须让手机去 /v2/sync');
  session.close();
  outbox.close();
});

test('focus at the tail sends no empty frame', async () => {
  const { channel, hub, session, outbox } = setup();
  for (const seq of [1, 2, 3]) hub.ingest('s1', event(seq));
  await session.accept();
  await channel.fromPhone({ type: 'FOCUS_SESSION', sessionId: 's1', sinceSeq: 3 });
  assert.equal(channel.framesOf('RECORDS').length, 0, '没有新记录就不该发空帧');
  session.close();
  outbox.close();
});

test('live records reach a focused session as they are ingested', async () => {
  const { channel, hub, session, outbox } = setup();
  await session.accept();
  await channel.fromPhone({ type: 'FOCUS_SESSION', sessionId: 's1', sinceSeq: 0 });
  hub.ingest('s1', event(10));
  // 实时记录走 50ms 攒批（`SYNC_V2.coalesceMs`）：等定时器就是又回到时钟竞速，
  // 所以测试直接把它抖出来。
  session.flushNow();
  await channel.settle();
  const frames = channel.framesOf('RECORDS');
  assert.deepEqual(frames.map(f => f.records.map(r => r.seq)), [[10]]);
  session.close();
  outbox.close();
});

test('unfocus stops the live feed for that session only', async () => {
  const { channel, hub, session, outbox } = setup();
  await session.accept();
  await channel.fromPhone({ type: 'FOCUS_SESSION', sessionId: 's1', sinceSeq: 0 });
  await channel.fromPhone({ type: 'FOCUS_SESSION', sessionId: 's2', sinceSeq: 0 });
  await channel.fromPhone({ type: 'UNFOCUS_SESSION', sessionId: 's1' });
  hub.ingest('s1', event(11));
  hub.ingest('s2', event(12));
  session.flushNow();
  await channel.settle();
  const seen = channel.framesOf('RECORDS').flatMap(f => f.records.map(r => `${r.sessionId}:${r.seq}`));
  assert.equal(seen.includes('s1:11'), false, '已 unfocus 的会话不再推送');
  assert.equal(seen.includes('s2:12'), true, '别的会话不受影响');
  session.close();
  outbox.close();
});

test('a burst of live records becomes one frame instead of eighteen', async () => {
  const { channel, hub, session, outbox } = setup();
  await session.accept();
  await channel.fromPhone({ type: 'FOCUS_SESSION', sessionId: 's1', sinceSeq: 0 });
  const before = channel.framesOf('RECORDS').length;
  // 一轮真实对话大约 18 条记录。逐条成帧的话手机要收 18 个 ws-message
  // （每个还带 12 字节隧道头 + 一条控制帧），攒批之后应该只剩一帧。
  for (let seq = 1; seq <= 18; seq++) hub.ingest('s1', event(seq));
  session.flushNow();
  await channel.settle();
  const added = channel.framesOf('RECORDS').slice(before);
  assert.equal(added.length, 1, '18 条记录必须合成一帧');
  assert.deepEqual(added[0]!.records.map(r => r.seq), Array.from({ length: 18 }, (_, i) => i + 1), '合批不能改变顺序');
  session.close();
  outbox.close();
});

test('records queued for a session that is unfocused are dropped, not sent', async () => {
  const { channel, hub, session, outbox } = setup();
  await session.accept();
  await channel.fromPhone({ type: 'FOCUS_SESSION', sessionId: 's1', sinceSeq: 0 });
  const before = channel.framesOf('RECORDS').length;
  hub.ingest('s1', event(30));
  // 还没到 50ms，记录还在攒批里；这时手机切走了。
  await channel.fromPhone({ type: 'UNFOCUS_SESSION', sessionId: 's1' });
  session.flushNow();
  await channel.settle();
  assert.equal(channel.framesOf('RECORDS').length, before, '给已离开的会话发记录只会让下次聚焦时重画旧行');
  session.close();
  outbox.close();
});

// ---------------------------------------------------------------------------
// 脉搏：长推理与死连接的唯一分界线
// ---------------------------------------------------------------------------
test('a pulse goes out for every focused session even when nothing happens', async () => {
  const { channel, hub, session, outbox } = setup();
  for (const seq of [1, 2]) hub.ingest('s1', event(seq));
  await session.accept();
  await channel.fromPhone({ type: 'FOCUS_SESSION', sessionId: 's1', sinceSeq: 0 });
  const before = channel.framesOf('SESSION_PULSE').length;

  // 定时器 5 秒一次；这里直接把时间推进去，不去 sleep。
  await new Promise<void>(r => setTimeout(r, 5_100));
  const pulses = channel.framesOf('SESSION_PULSE');
  assert.ok(pulses.length > before, '无事发生也必须发脉搏——v1 卡 16 分钟就是因为「有事才发」');
  const last = pulses.at(-1)!;
  assert.equal(last.sessionId, 's1');
  assert.equal(last.latestSeq, 2, '带最新 seq，手机才能判断自己落后多少');
  assert.equal(last.workerEpoch, 7, '插件重启后 epoch 变了，手机要能发现');
  session.close();
  outbox.close();
});

test('pulse says THINKING_SILENT while a turn runs with no output', async () => {
  const { channel, hub, session, outbox } = setup();
  hub.ingest('s1', event(1));
  hub.noteRunning('s1', true);
  await session.accept();
  await channel.fromPhone({ type: 'FOCUS_SESSION', sessionId: 's1', sinceSeq: 0 });
  await new Promise<void>(r => setTimeout(r, 5_100));
  const last = channel.framesOf('SESSION_PULSE').at(-1)!;
  // 真实的 silentDurationMs 取决于时钟，这里只断言状态分类：
  // 运行中且没有增量 = 深度思考，手机据此显示「正在深度思考 (Ns)」而不是重连。
  assert.equal(last.hostState === 'THINKING_SILENT' || last.hostState === 'STREAMING', true, `got ${last.hostState}`);
  session.close();
  outbox.close();
});

// ---------------------------------------------------------------------------
// 写操作：同一个 cmdId 只落一次
// ---------------------------------------------------------------------------
test('a command is routed to the host endpoint and acknowledged once', async () => {
  const { channel, session, delivered, outbox } = setup();
  await session.accept();
  await channel.fromPhone({ type: 'CMD', cmdId: 'c1', op: 'prompt', payload: { sessionId: 's1', text: 'hi' } });
  const ack = channel.framesOf('CMD_ACK')[0] as {
    type: string;
    cmdId: string;
    ok: boolean;
    pluginMs?: number;
  };
  // `pluginMs` 是 T3→T4 的时长（六跳打点用），不参与相等比较——它是实测值。
  assert.deepEqual({ ...ack, pluginMs: undefined }, { type: 'CMD_ACK', cmdId: 'c1', ok: true, pluginMs: undefined });
  assert.equal(typeof ack.pluginMs, 'number', '必须报插件耗时，否则手机上永远缺 T3');
  assert.ok((ack.pluginMs ?? -1) >= 0 && (ack.pluginMs ?? 1e9) < 60_000);
  assert.equal(delivered.length, 1);
  assert.equal(delivered[0]!.url, 'http://127.0.0.1:9749/api/session/prompt');
  // 方法名与信封也必须对：宿主认 `session/prompt` + `{args:{request:...}}`。
  // 从 URL 推名字会得到裸 `prompt`，宿主当未知方法拒掉——而 ack 仍会回 ok:false，
  // 手机只看到一句「发送失败」，查不到这一层。
  // `init.body` 是已序列化的字符串（不是对象）——这里解析它，正是要证明
  // 「线上真的长这样」，而不是证明「我们可以传一个对象进去」。
  const sent = JSON.parse(String(delivered[0]!.body)) as {
    method?: string;
    payload?: { args?: { request?: { sessionId?: string; text?: string } } };
  };
  assert.equal(sent.method, 'session/prompt');
  assert.deepEqual(sent.payload?.args?.request, { sessionId: 's1', text: 'hi' });
  session.close();
  outbox.close();
});

test('an interaction answer goes bare, because $events/result has no request envelope', async () => {
  const { channel, session, delivered, outbox } = setup();
  await session.accept();
  await channel.fromPhone({
    type: 'CMD',
    cmdId: 'i1',
    op: 'interaction',
    payload: { clientId: 'c-1', eventId: 'e1', outcome: { kind: 'result', value: 'allowed-once' } },
  });
  assert.equal(delivered.length, 1);
  assert.equal(delivered[0]!.url, 'http://127.0.0.1:9749/api/$events/result');
  const sent = JSON.parse(String(delivered[0]!.body)) as {
    method?: string;
    payload?: { args?: { clientId?: string; request?: unknown } };
  };
  assert.equal(sent.method, '$events/result');
  // 包成 `{request:...}` 的话宿主看不到 clientId，会静默丢弃这次回答——
  // 表现就是「点完审批卡，下次重连它又回来了」。
  // 插件用它自己 `$events` 连接的 id 覆盖手机传来的那个：宿主只认「收到这条
  // waterfall 的连接」，手机填自己的 id 会被静默丢弃（卡片下次重连又回来）。
  // 这个用例没有 `$events`，所以是空串——关键是**不是**手机传的 `c-1`。
  assert.notEqual(sent.payload?.args?.clientId, 'c-1');
  assert.equal(sent.payload?.args?.clientId, '');
  assert.equal(sent.payload?.args?.request, undefined);
  session.close();
  outbox.close();
});

test('a repeated cmdId replays the cached ack and never reaches the host twice', async () => {
  const { channel, session, delivered, outbox } = setup();
  await session.accept();
  await channel.fromPhone({ type: 'CMD', cmdId: 'approve-1', op: 'interaction', payload: { eventId: 'e1' } });
  // 手机 3 秒 ACK 竞速会经 HTTPS 旁路重发同一个 cmdId，两份都可能到达插件。
  await channel.fromPhone({ type: 'CMD', cmdId: 'approve-1', op: 'interaction', payload: { eventId: 'e1' } });
  const acks = channel.framesOf('CMD_ACK');
  assert.equal(acks.length, 2, '每一次请求都要回一个 ack，否则重发的那条会一直等');
  // 缓存重放的 ack 连 pluginMs 一起原样回：手机据此算出的 T3 两次必须一致，
  // 否则「重发」在时间线上看起来像宿主又干了活。
  assert.deepEqual(acks[0], acks[1]);
  assert.equal(delivered.length, 1, '宿主只能收到一次——第二份就是给已结算的审批再投一次');
  session.close();
  outbox.close();
});

test('an unknown op is refused without touching the host', async () => {
  const { channel, session, delivered, outbox } = setup();
  await session.accept();
  await channel.fromPhone({ type: 'CMD', cmdId: 'x1', op: 'drop-tables' as never, payload: {} });
  const ack = channel.framesOf('CMD_ACK')[0];
  assert.equal(ack?.ok, false);
  assert.equal(ack?.error, 'unknown-op');
  assert.equal(delivered.length, 0);
  session.close();
  outbox.close();
});

test('a host error becomes a failed ack instead of a dead stream', async () => {
  const outbox = new ArchiveOutbox({ path: null });
  const hub = new SessionHub({ outbox });
  const channel = new FakeChannel();
  const session = new ControlSession(channel as never, {
    hub,
    upstreamOrigin: 'http://127.0.0.1:9749',
    getCookie: async () => 'c',
    workerEpoch: 1,
    buildHash: 'h',
    fetch: (async () => new Response('nope', { status: 500 })) as typeof fetch,
  });
  await session.accept();
  await channel.fromPhone({ type: 'CMD', cmdId: 'c9', op: 'cancel', payload: {} });
  const ack = channel.framesOf('CMD_ACK')[0];
  assert.equal(ack?.ok, false);
  assert.equal(ack?.error, 'host-500');
  session.close();
  outbox.close();
});

// ---------------------------------------------------------------------------
// 关闭
// ---------------------------------------------------------------------------
test('close stops pulses and stops fanning records out', async () => {
  const { channel, hub, session, outbox } = setup();
  await session.accept();
  await channel.fromPhone({ type: 'FOCUS_SESSION', sessionId: 's1', sinceSeq: 0 });
  session.close();
  const before = channel.frames().length;
  hub.ingest('s1', event(77));
  await new Promise<void>(r => setTimeout(r, 5_100));
  assert.equal(channel.frames().length, before, '关掉之后既不该推记录也不该发脉搏');
  outbox.close();
});

// ---------------------------------------------------------------------------
// 坑 54：ws-accept 卡住时脉搏不能一起卡住
// ---------------------------------------------------------------------------

/** `send()` 永远不 resolve 的通道：模拟隧道上流控 / 缓冲高水位把 ws-accept 挂住。 */
class StalledAcceptChannel extends FakeChannel {
  override async send(frame: Record<string, unknown>): Promise<void> {
    if (frame.type === 'ws-accept') return new Promise<void>(() => {});
    return super.send(frame);
  }
}

test('a stalled ws-accept must not silence the pulse timer', async () => {
  // 生产现场（2026-10-30，坑 54）：`accept()` 里脉搏定时器建在
  // `await channel.send({type:'ws-accept'})` **之后**。只要那个 Promise 不 resolve，
  // 就会出现一种极难看的半死状态——focus 与 records 全部正常（它们走 bridge 的
  // receive 路径），**但一条脉搏都不发**。手机端 `lastPulseAt` 永远是 0，12 秒后
  // 判死重连、插件重放整个尾部窗口、再卡一次，如此循环。
  //
  // 这条用例钉死的是「心跳的启动不许依赖握手通知的完成」：心跳是「我还在」的
  // 证据，越在链路不顺时越要发。
  const outbox = new ArchiveOutbox({ path: null });
  const hub = new SessionHub({ outbox, tailSize: 50 });
  const channel = new StalledAcceptChannel();
  const logs: string[] = [];
  const session = new ControlSession(channel as never, {
    hub,
    upstreamOrigin: 'http://127.0.0.1:9749',
    getCookie: async () => 'dsh-auth-test=1',
    workerEpoch: 7,
    buildHash: 'abc123',
    fetch: relayOf([]),
    onLog: line => logs.push(line),
  });

  // 故意**不** await：accept 会永远挂在 ws-accept 上。
  void session.accept();
  await channel.settle();
  await channel.fromPhone({ type: 'FOCUS_SESSION', sessionId: 's1', sinceSeq: 0 });
  hub.ingest('s1', event(1));

  const before = channel.framesOf('SESSION_PULSE').length;
  await new Promise<void>(r => setTimeout(r, 5_100));

  const pulses = channel.framesOf('SESSION_PULSE');
  assert.ok(
    pulses.length > before,
    'ws-accept 没回也必须发脉搏：否则手机端判死重连，而插件看起来一切正常',
  );
  assert.equal(pulses.at(-1)!.sessionId, 's1');
  assert.ok(
    logs.some(l => l.startsWith('pulse begin s1')),
    '第一帧脉搏必须留一行日志：坑 54 排查时「到底有没有发出去」是唯一无法从两端日志反推的事',
  );
  session.close();
  outbox.close();
});

/**
 * 坑 54 的真身：假 socket 的 `ping()` 曾经是空实现。
 *
 * <p>`MessageBridge` 的构造函数**无条件** `startProbe()` →
 * `probePeer(socket, () => this.abort(1001))`，而 `probePeer` 的 `strikes` 只有
 * 收到 `pong` 才重置：
 *
 * ```ts
 * if (strikes >= missed) { clearInterval(timer); onDead(); return; }
 * strikes++; socket.ping();     // wsPingInterval=15000, wsMissedPongs=2
 * ```
 *
 * <p>空实现 ⇒ 永远没有 pong ⇒ 第 45 秒（15s×3）必然 abort ⇒
 * `ControlSession.close()` ⇒ `clearInterval(pulseTimer)`。脉搏从此永久停发，
 * 而 `abort()` 只关本地假 socket、**不通知中继与手机**（`stopped=true` 让
 * `socket.once('close')` 里的 `ws-close` 被跳过）⇒ 手机侧 `alive()` 一直是
 * true，`recover()` 一直走「连接仍在」分支，可插件已经 `closed=true`、
 * `focus()` 第一行就 return —— 永久死锁。
 *
 * <p>2026-10-30 现场时间戳：`accept()` 13:42:58.453，脉搏发到 13:43:38.493
 * （第 8 条），第 9 条应在 13:43:43.49，正好落在 13:42:58.453+45s =
 * 13:43:43.453 之后 40 毫秒。
 */
test('the fake socket answers ping with pong so the bridge probe never kills a live control stream', async () => {
  const { ChannelSocket } = await import('../packages/plugin/src/control.js');
  const socket = new ChannelSocket(() => {});
  let pongs = 0;
  socket.on('pong' as never, (() => { pongs += 1; }) as never);

  socket.ping();
  socket.ping();

  assert.equal(
    pongs,
    2,
    'ping() 必须回 pong：否则 MessageBridge 的 probePeer 会在 45 秒后 abort 掉这条活着的控制流',
  );
});

test('a control session outlives the bridge probe window instead of going silent after 45 seconds', async () => {
  // 用真实的 15s/2 次门限太慢，这里直接验 probePeer 的判据：把 interval 压到 20ms、
  // missed 压到 2，喂 200ms（相当于 10 个 tick）。假 socket 若不应答 pong，
  // abort 会在第 3 个 tick 就发生。
  const { probePeer } = await import('../packages/core/src/index.js');
  const { ChannelSocket } = await import('../packages/plugin/src/control.js');
  const socket = new ChannelSocket(() => {});
  let died = false;
  const stop = probePeer(socket as never, () => { died = true; }, 20, 2);

  await new Promise<void>(r => setTimeout(r, 200));
  stop();

  assert.equal(
    died,
    false,
    '假 socket 冒充的是手机 ws，其活性由隧道层心跳保证；Bridge 的 probe 必须收到 pong，否则它探测的是自己的沉默',
  );
});

// ---------------------------------------------------------------------------
// 队列卡的「立即发送」：宿主在跑就 steer，空闲就直接发
// ---------------------------------------------------------------------------

/** 一条 steer 命令；`text` 是那条排队消息的正文。 */
function steerCmd(cmdId: string, text = '现在发这句话'): PhoneToPlugin {
  return {
    type: 'CMD',
    cmdId,
    op: 'queue',
    payload: { sessionId: 's1', itemId: 'q1', action: { kind: 'steer', text } },
  };
}

test('steering while the host is idle sends the message instead of asking to steer', async () => {
  // 空闲时 `updateQueue` 的 steer 只回 `session/steer-unavailable`，用户以为发送失败。
  // 他按的是「立即发送」，所以这里要把队列项撤掉再直接 prompt 一次。
  const delivered: Delivered[] = [];
  const { channel, session, outbox } = setup({ delivered });
  await session.accept();
  // 没有任何记录流过 ⇒ hub 认为这个会话空闲。
  await channel.fromPhone(steerCmd('k1'));

  const paths = delivered.map(d => new URL(d.url).pathname);
  assert.deepEqual(
    paths,
    ['/api/session/updateQueue', '/api/session/prompt'],
    '空闲时必须先撤队列项再直接发，顺序反了同一句话会既在队列里又被发出去',
  );
  const remove = JSON.parse(delivered[0]!.body as string);
  assert.equal(remove.payload.args.request.action.kind, 'remove');
  const prompt = JSON.parse(delivered[1]!.body as string);
  assert.equal(prompt.payload.args.request.content[0].text, '现在发这句话');
  assert.equal(channel.framesOf('CMD_ACK')[0]?.ok, true);
  session.close();
  outbox.close();
});

test('a failed send after the queue item was removed puts the message back', async () => {
  // 「立即发送」在宿主空闲时是两步：① updateQueue remove 撤掉队列项 ② session/prompt
  // 真正发出去。①成功、②失败（20 秒超时 / 5xx）时，用户那句话既不在队列里也不在
  // 宿主那边 —— 界面上只剩一句错误提示，消息本身没了。
  //
  // 修法：②失败就用 `mode:'queue'` 把文本塞回队列（等价于它原来的位置），并把
  // 「已放回队列」写进错误串，用户不用重新打一遍。
  const delivered: Delivered[] = [];
  const { channel, session, outbox } = setup({ delivered, failPaths: ['/api/session/prompt'], failTimes: 1 });
  await session.accept();
  await channel.fromPhone(steerCmd('k3'));

  const requests = delivered.map(d => ({
    path: new URL(d.url).pathname,
    body: JSON.parse(d.body as string).payload.args.request,
  }));
  // ①撤队列项 ②发失败 ③回队
  assert.equal(requests.length, 3, `期望 3 次 RPC，实际：${requests.map(r => r.path).join(' → ')}`);
  assert.equal(requests[0]!.path, '/api/session/updateQueue');
  assert.equal(requests[0]!.body.action.kind, 'remove');
  assert.equal(requests[1]!.path, '/api/session/prompt');
  assert.equal(requests[2]!.path, '/api/session/prompt', '失败之后必须再发一次把消息放回队列');
  assert.equal(requests[2]!.body.content[0].text, '现在发这句话', '回队的必须是原文');
  assert.equal(requests[2]!.body.mode, 'queue');
  // requestId 必须换一个：`steer-${cmdId}` 已经被本次尝试用掉了。
  assert.notEqual(requests[2]!.body.requestId, requests[1]!.body.requestId, '回队不能复用同一个 requestId');

  const ack = channel.framesOf('CMD_ACK')[0];
  assert.equal(ack?.ok, false, '整体仍然是失败：消息没发出去');
  assert.ok(String(ack?.error).includes('已放回队列'), `错误串要告诉用户消息没丢，实际：${ack?.error}`);
  session.close();
  outbox.close();
});

test('steering while the host is running goes straight to updateQueue', async () => {
  const delivered: Delivered[] = [];
  const { channel, session, hub, outbox } = setup({ delivered });
  await session.accept();
  // 一条正在被消费的记录会让 hub 把 hostState 置成 STREAMING/THINKING_SILENT。
  hub.noteRunning('s1', true);
  await channel.fromPhone(steerCmd('k2'));

  const paths = delivered.map(d => new URL(d.url).pathname);
  assert.deepEqual(paths, ['/api/session/updateQueue'], '在跑就必须走 steer，不能擅自替用户发出去');
  const body = JSON.parse(delivered[0]!.body as string);
  assert.equal(body.payload.args.request.action.kind, 'steer');
  session.close();
  outbox.close();
});

test('a queue edit is never rewritten into a prompt', async () => {
  // 只有 steer 有「空闲就走 prompt」的分流。edit/remove 必须原样透传。
  const delivered: Delivered[] = [];
  const { channel, session, outbox } = setup({ delivered });
  await session.accept();
  await channel.fromPhone({
    type: 'CMD',
    cmdId: 'k3',
    op: 'queue',
    payload: { sessionId: 's1', itemId: 'q1', action: { kind: 'edit', text: '改一下' } },
  });
  const paths = delivered.map(d => new URL(d.url).pathname);
  assert.deepEqual(paths, ['/api/session/updateQueue']);
  session.close();
  outbox.close();
});

// ---------------------------------------------------------------------------
// cmdId 幂等：并发在途的同一 cmdId 只能执行一次
// ---------------------------------------------------------------------------
test('two copies of the same cmdId in flight execute once, not twice', async () => {
  // ack 缓存只挡「第一份已经跑完之后」的重发。手机在控制流看起来死了的时候会走
  // HTTPS 旁路重发**同一个 cmdId**，而原件此刻还挂在宿主上等答复 —— 两份同时在途，
  // 缓存里都还没有。审批回答跑两次不是丢更新：宿主会收到第二条针对已结算 waterfall 的
  // `$events/result`。
  //
  // 判据：宿主只该被打到一次。
  //
  // 通道是串行的（`FakeChannel` 一次只留一个 receive store），所以「并发」在这里的
  // 真实形态是：第一份的宿主 RPC 还挂着，第二份从旁路进来。用一个可控的 fetch
  // 把第一次调用停住，就能精确复现那个窗口。
  const delivered: Delivered[] = [];
  let releaseFirst: (() => void) | undefined;
  const gate = new Promise<void>(resolve => { releaseFirst = resolve; });
  let calls = 0;
  const gatedFetch = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = String(input);
    delivered.push({ url, body: init?.body });
    if (new URL(url).pathname === '/api/session/prompt') {
      calls += 1;
      if (calls === 1) await gate; // 第一份停在宿主那边
    }
    return new Response('{}', { status: 200 });
  }) as typeof fetch;

  const outbox = new ArchiveOutbox({ path: null });
  const hub = new SessionHub({ outbox, tailSize: 50 });
  const channel = new FakeChannel();
  const session = new ControlSession(channel as never, {
    hub,
    upstreamOrigin: 'http://127.0.0.1:9749',
    getCookie: async () => 'dsh-auth-test=1',
    workerEpoch: 7,
    buildHash: 'abc123',
    fetch: gatedFetch,
  });
  await session.accept();

  const cmd = steerCmd('dup-1');
  const first = channel.fromPhone(cmd);       // 卡在 prompt 上
  await channel.settle();
  await channel.fromPhone(cmd);               // 同一 cmdId 的第二份
  await channel.settle();

  // 此刻第一份还没返回：第二份必须已经在等它的结果，而不是自己再打一次宿主。
  assert.equal(calls, 1, `同一 cmdId 在途时不许重复执行，实际打了 ${calls} 次宿主`);

  releaseFirst?.();
  await first;
  await channel.settle();

  assert.equal(calls, 1, '第一份返回后也不该再执行一次');
  const acks = channel.framesOf('CMD_ACK').filter(f => f.cmdId === 'dup-1');
  assert.ok(acks.length >= 1, '至少要有 ack');
  assert.ok(acks.every(a => a.ok === true), `ack 必须是成功的：${JSON.stringify(acks)}`);
  session.close();
  outbox.close();
});
