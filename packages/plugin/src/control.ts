/**
 * The plugin side of the phone's control stream (v2 §protocol "通道收敛").
 *
 * <p>The phone keeps exactly one websocket open for a session — {@link CONTROL_PATH} —
 * and the plugin terminates it. Nothing here is bridged to the host: the host's own
 * `/api/remote.mux` stays a byte pipe that only the archiver uses. That is the whole
 * point of the redesign: the phone no longer parses DSH envelopes, so live and archived
 * records have the same shape and the phone runs one projector.
 *
 * <p>Three things happen on this socket, and they are deliberately independent:
 *
 *  - **Replay + live records.** `FOCUS_SESSION` answers from the hub's tail window
 *    (instant, no round trip to the host) and then subscribes for new records. Anything
 *    older than the window is the relay's job via `/v2/sync`; the phone is told so by
 *    `hasMore`, never by silence.
 *  - **Pulses.** One `SESSION_PULSE` every 5s per focused session. This is what makes a
 *    60-second reasoning step distinguishable from a dead tunnel: the phone sees a fresh
 *    `latestSeq`/`silentDurationMs` and renders "deep thinking (45s)" instead of
 *    tearing down and replaying history. Absence of a pulse for 12s is the phone's
 *    licence to reconnect — so the timer must never be gated on host activity.
 *  - **Writes.** A `CMD` frame carries an idempotency key. The phone sets a 3-second
 *    ACK race and re-sends the same `cmdId` over HTTPS if this socket turns out to be
 *    half-dead, so the ack cache below is not an optimisation — it is what stops one
 *    approval click from being applied twice.
 */
import type { Channel } from '../../core/src/index.js';
import { MessageBridge, decodeMuxFrame, encodeMuxFrame, recordBytes, SYNC_V2, pulse, type HostState, type PhoneToPlugin, type PluginToPhone, type SessionPulse } from '../../core/src/index.js';
import type { SessionHub } from './hub.js';
import { loopbackPost } from './loopback.js';
import type { TimelineRecord } from '../../core/src/index.js';

/** `op` values the phone may send, and the loopback RPC each one maps to. Keeping
 *  the table here (rather than in a switch inside the handler) makes it obvious that
 *  the plugin is a *router* for writes, not a business layer: every entry is a plain
 *  passthrough to an endpoint the desktop app itself calls. */
const CMD_ROUTES: Record<
  string,
  {
    path: string;
    /** RPC method the host expects. Session RPCs are namespaced (`session/prompt`),
     *  the events answer is not (`$events/result`). Deriving this from the URL path
     *  produced a bare `prompt`, which the host rejects as an unknown method — the
     *  whole v2 write path answered `CMD_ACK{ok:false}` while the phone dutifully
     *  showed "发送失败". */
    method: string;
    /** Session RPCs take `{args:{request:<payload>}}`; `$events/result` takes the
     *  arguments bare (`{args:{clientId,eventId,outcome}}`). Sending the session
     *  shape to `$events/result` makes the host see no `clientId` and silently drop
     *  the answer — the approval card then reappears on the next reconnect. */
    wrap: 'request' | 'bare';
  }
> = {
  prompt: { path: '/api/session/prompt', method: 'session/prompt', wrap: 'request' },
  interaction: { path: '/api/$events/result', method: '$events/result', wrap: 'bare' },
  queue: { path: '/api/session/updateQueue', method: 'session/updateQueue', wrap: 'request' },
  cancel: { path: '/api/session/cancel', method: 'session/cancel', wrap: 'request' },
};

const ACK_CACHE = 256;

export interface ControlOptions {
  hub: SessionHub;
  upstreamOrigin: string;
  getCookie: () => Promise<string>;
  workerEpoch: number;
  buildHash: string;
  onLog?: (message: string) => void;
  /** Bytes still queued for this phone, reported in every pulse so the app can tell
   *  "the host is slow" from "my link is slow" without asking anyone. */
  bufferedBytes?: () => number;
  /** The worker's own `$events` client id. An approval answer must name the client the
   *  host delivered the waterfall to; the phone's id is a different connection and the
   *  host silently drops an answer that names it. */
  eventsClientId?: () => string;
  /** Injectables exist for the tests only. */
  now?: () => number;
  fetch?: typeof fetch;
}

/**
 * A WebSocket-shaped adapter over a tunnel channel.
 *
 * <p>{@link MessageBridge} already implements the message framing and the delivered-ack
 * window that the relay speaks, and re-implementing it for this one local endpoint
 * would be two implementations of the same protocol that must agree by hand — the exact
 * class of bug the redesign removes. So the local endpoint pretends to be a socket:
 * bytes handed to `send` are frames *from* the phone, bytes pushed with `push` are
 * frames *to* it.
 */
export class ChannelSocket {
  readyState = 1;
  private handlers = new Map<string, Set<(...args: never[]) => void>>();
  private closeHandlers: Array<(code: number, reason: Buffer) => void> = [];
  /**
   * 手上来一帧。第二个参数是**对端声明的 opcode**。
   *
   * <p>`MessageBridge.deliver()` 是把整个消息交给 `socket.send(bytes,{binary})`
   * 的，opcode 只在那个 options 里。假 socket 若把它丢掉（以前就是这样），回给
   * 手机时二进制帧会被当成文本帧发出去——非 UTF-8 的字节能直接让中继那条
   * `ws.send` 抛错、整条流被 abort。所以 opcode 必须跟着字节一起走。
   */
  onFrame?: (raw: Buffer, binary: boolean) => void;

  constructor(private readonly log: (message: string) => void) {}

  on(event: string, handler: (...args: never[]) => void): void {
    let set = this.handlers.get(event);
    if (!set) { set = new Set(); this.handlers.set(event, set); }
    set.add(handler);
  }
  off(event: string, handler: (...args: never[]) => void): void { this.handlers.get(event)?.delete(handler); }
  once(event: string, handler: (code: number, reason: Buffer) => void): void {
    if (event === 'close') this.closeHandlers.push(handler);
  }
  /**
   * 立刻回一个 `pong`。
   *
   * <p>坑 54 的真身（2026-10-30 现场）：这里原来是空实现，注释说「隧道已经在
   * 心跳，这里回 pong 是编造」。但 `MessageBridge` 的构造函数**无条件**
   * `startProbe()` → `probePeer(socket, () => this.abort(1001))`，而
   * `probePeer` 的 `strikes` 只有在收到 `pong` 时才重置：
   *
   * ```ts
   * if (strikes >= missed) { clearInterval(timer); onDead(); return; }
   * strikes++; socket.ping();     // wsPingInterval=15000, wsMissedPongs=2
   * ```
   *
   * <p>空实现 ⇒ 永远没有 pong ⇒ `strikes` 单调递增 ⇒ **第 45 秒必然 abort**
   * （15s×3）。`abort` 走 `socket.close()` → `MessageBridge` 的
   * `socket.once('close')` → `onClose()` = `ControlSession.close()` →
   * `clearInterval(pulseTimer)`。脉搏从此永久停发，而 `abort()` 只关本地假
   * socket、**不会通知中继与手机**，于是手机侧 `alive()` 一直是 true、
   * `recover()` 一直走「连接仍在」分支，可插件的 `ControlSession` 已经
   * `closed = true`，`focus()` 第一行 `if (this.closed) return` 直接返回 ——
   * 永久死锁。
   *
   * <p>实测：`accept()` 在 13:42:58.453，脉搏 5 秒一条发到 13:43:38.493
   * （第 8 条），第 9 条应在 13:43:43.49，正好落在 13:42:58.453+45s =
   * 13:43:43.453 之后 40 毫秒。此后 recover 间隔严格等于
   * `noPulseGraceMs(0..4)` = 12/24/48/96/120 秒。
   *
   * <p>为什么回 pong 不算编造：这个假 socket 冒充的是手机端的 ws，而手机端
   * ws 的活性由隧道层的 `Tunnel.startHeartbeat()` 保证（30 秒 ping、连续 2 次
   * 无 pong 就 `close(4408)`）。隧道一死，`channel.once('disposed')` 会让
   * Bridge 自己 abort，probe 根本不需要参与。Bridge 既然无条件要 probe，这里
   * 就必须诚实应答，否则它探测的是自己的沉默。
   */
  ping(): void { this.emit('pong'); }

  /** Bytes arriving from the phone (called by MessageBridge). */
  send(data: Buffer | string, options?: unknown, callback?: (error?: Error) => void): void {
    const raw = Buffer.isBuffer(data) ? data : Buffer.from(data);
    try { this.onFrame?.(raw, (options as { binary?: boolean } | undefined)?.binary === true); callback?.(); } catch (error) { callback?.(error as Error); }
  }
  /** Queue bytes for the phone (called by us). */
  push(raw: Buffer, binary = false): void { this.emit('message', raw, binary); }

  close(): void { this.readyState = 3; for (const handler of this.closeHandlers.splice(0)) handler(1000, Buffer.alloc(0)); }
  terminate(): void { this.close(); }
  private emit(event: string, ...args: unknown[]): void {
    for (const handler of this.handlers.get(event) ?? []) (handler as (...a: unknown[]) => void)(...args);
  }
  /** Surfaced so the bridge's error path can shut the whole session down. */
  fail(reason: string): void { this.log(`control socket failure: ${reason}`); this.close(); }
}

export class ControlSession {
  private readonly socket: ChannelSocket;
  private bridge?: MessageBridge;
  private readonly focused = new Set<string>();
  private readonly unsubscribers = new Map<string, () => void>();
  private readonly acks = new Map<string, PluginToPhone>();
  /** cmdId → 正在执行的那次 RPC 的 promise（并发重入去重，见 {@link command}）。 */
  private readonly inflight = new Map<string, Promise<PluginToPhone>>();
  /**
   * Records waiting to be batched, per session.
   *
   * <p>Without this every `tool/call`, every `assistant-stream` completion and every
   * `todo/write` becomes its own websocket message: a single turn is ~18 frames of a
   * few hundred bytes each, which is pure framing overhead once the tunnel adds a
   * 12-byte header plus a `ws-message` control frame per fragment. Batching them
   * costs one timer and cuts the frame count by roughly an order of magnitude.
   */
  private readonly pendingRecords = new Map<string, TimelineRecord[]>();
  private flushTimer?: NodeJS.Timeout;
  private readonly now: () => number;
  private pulseTimer?: NodeJS.Timeout;
  private closed = false;

  constructor(private readonly channel: Channel, private readonly options: ControlOptions) {
    this.now = options.now ?? Date.now;
    this.socket = new ChannelSocket(message => options.onLog?.(message));
    this.socket.onFrame = raw => this.receive(raw);
  }

  /** Completes the handshake towards the relay and starts the bridge. Mirrors what
   *  `HostClient.open` does for a host upgrade, minus the upstream socket. */
  async accept(): Promise<void> {
    this.bridge = new MessageBridge(this.socket as never, this.channel, 4 * 1024 * 1024, () => this.close());
    // 脉搏定时器**必须**在 `await ws-accept` 之前建起来。
    //
    // <p>坑 54（2026-10-30 生产现场）：这里原来是
    // `await this.channel.send({type:'ws-accept'})` 之后才 `setInterval`。
    // 而 `bridge` 在上一行就建好了——于是只要那个 send 的 Promise 迟迟不
    // resolve（隧道上流控、socket 缓冲高水位、对端窗口未开都会），就会出现一种
    // 极难看的半死状态：**focus 与 records 全部正常工作**（它们走 bridge 的
    // receive 路径），**但一条脉搏都不发**。手机端 `lastPulseAt` 永远是 0，
    // 12 秒后判死重连，插件重放尾部窗口，再卡一次——插件日志里就是每 12 秒
    // 一条 `focus … replay=200`，紧跟 `tunnel credit stall 10s`。
    //
    // <p>`ws-accept` 只是一次握手通知，它的**完成**不该成为心跳的前提：心跳
    // 是「我还在」的证据，越是在链路不顺时越要发。`close()` 里会 clearInterval，
    // 提前建不会泄漏。
    this.startPulseTimer();
    await this.channel.send({ v: 3, type: 'ws-accept', protocol: '' });
  }

  /** 幂等：`accept()` 与任何自愈路径都可以调用，不会叠出两个定时器。 */
  private startPulseTimer(): void {
    if (this.pulseTimer) return;
    this.pulseTimer = setInterval(() => this.emitPulses(), SYNC_V2.pulseMs);
    this.pulseTimer.unref();
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    clearInterval(this.pulseTimer);
    clearTimeout(this.flushTimer);
    this.pendingRecords.clear();
    for (const off of this.unsubscribers.values()) off();
    this.unsubscribers.clear();
    this.focused.clear();
    this.socket.close();
    this.bridge?.abort(1000);
  }

  /**
   * Forces the pending batch out.
   *
   * <p>Only tests call this: production flushes on the 50 ms timer, on the 16 KiB
   * threshold and before every pulse, so a caller never needs to. Waiting for a
   * timer in a test is the same race the coalescing was meant to make impossible.
   */
  flushNow(): void { this.flushRecords(); }

  /**
   * 接上审批/提问瀑布：先订阅增量，再把尾部窗口重投一遍。
   *
   * <p>顺序反了会漏：先重投再订阅，两步之间到达的瀑布就掉在地上。幂等——同一条
   * socket 上收到两次 `HELLO`（重连后手机重发握手）不会叠出两个订阅者。
   *
   * <p>为什么必须重投：瀑布不落盘、不进归档。人在会话列表页时审批就到了的情形很常见，
   * 等他点进聊天页，增量早推完了。v1 时代靠「重开 `$events` 让宿主重投」兜底，那条路
   * 已随 v1 路由删除；现在这里的尾部窗口是唯一的来源。
   */
  private attachInteractions(): void {
    if (this.unsubscribers.has('$interactions')) return;
    this.unsubscribers.set('$interactions', this.options.hub.subscribeInteractions(frame => this.send(frame)));
    for (const frame of this.options.hub.pendingInteractions()) this.send(frame);
  }

  /** Frames that were not ours. Non-zero means the phone and the plugin disagree
   *  about the wire tag — worth seeing, never worth closing the socket over. */
  foreignFrames = 0;

  private send(frame: PluginToPhone): void {
    if (this.closed) return;
    this.socket.push(Buffer.from(encodeMuxFrame(frame), 'utf8'));
  }

  private receive(raw: Buffer): void {
    const frame = decodeMuxFrame(raw.toString('utf8'));
    if (!frame) {
      // Foreign frame: ignore, never tear the socket down. Counted so a silent
      // control stream can be told apart from one that is receiving junk.
      this.foreignFrames++;
      return;
    }
    const value = frame as PhoneToPlugin;
    if (value.type === 'HELLO') {
      this.send({ type: 'HELLO_ACK', buildHash: this.options.buildHash, capabilities: ['records', 'pulse', 'cmd'], workerEpoch: this.options.workerEpoch });
      this.attachInteractions();
      return;
    }
    if (value.type === 'FOCUS_SESSION') { this.focus(value.sessionId, value.sinceSeq, value.byteBudget); return; }
    if (value.type === 'UNFOCUS_SESSION') { this.unfocus(value.sessionId); return; }
    if (value.type === 'CMD') {
      // 每一条写操作都记一行：这次事故里手机发出 CMD、插件一个字节都没收到，
      // 而两端都没有任何日志能把「命令丢在哪一跳」指出来。
      this.options.onLog?.(`cmd ${value.op} id=${value.cmdId.slice(0, 8)}`);
      void this.command(value);
      return;
    }
  }

  /** Replays what the tail still holds, then subscribes.
   *
   *  <p>The replay is answered synchronously from memory — never by asking the host —
   *  because the phone must paint before the network does anything else. `hasMore`
   *  says whether records below the window exist; the phone fills those from
   *  `/v2/sync` with its own `afterSeq`, so the two paths cannot disagree about what
   *  "old" means.
   */
  private focus(sessionId: string, sinceSeq: number, byteBudget = SYNC_V2.pageByteBudget): void {
    if (this.closed) return;
    this.unfocus(sessionId);
    this.focused.add(sessionId);
    this.unsubscribers.set(sessionId, this.options.hub.subscribe(sessionId, record => this.batch(sessionId, record)));
    const records = this.options.hub.replay(sessionId, sinceSeq, byteBudget);
    // `sinceSeq === 0` 是「这台手机还没有这条会话的任何记录」，恰恰是最需要
    // 告诉它「下面还有」的那一档：hub 只留尾部窗口，老的部分只能在归档里。
    // 原来这里多了个 `sinceSeq > 0` 的门槛，把这一档整个漏掉（靠 actor 启动时
    // 无条件 `dirty = true` 兜住，但那是巧合不是契约）。
    const hasMore = this.options.hub.latestSeq(sessionId) > 0 && hasMoreBelow(this.options.hub, sessionId, sinceSeq);
    this.emitRecords(sessionId, records, hasMore, records.length ? 'TAIL_COMPLETE' : 'QUEUE_EMPTY');
    this.options.onLog?.(`focus ${sessionId.slice(0, 12)} since=${sinceSeq} replay=${records.length} hasMore=${hasMore}`);
  }

  private unfocus(sessionId: string): void {
    this.focused.delete(sessionId);
    const off = this.unsubscribers.get(sessionId);
    if (off) { off(); this.unsubscribers.delete(sessionId); }
  }

  private emitRecords(sessionId: string, records: TimelineRecord[], hasMore: boolean, stopReason: 'BUDGET_EXHAUSTED' | 'TAIL_COMPLETE' | 'QUEUE_EMPTY'): void {
    if (!records.length && !hasMore) return;
    // A replay of 200 records is ~500 KiB of JSON. One frame that size cannot cross
    // the tunnel: a single websocket message is capped at LIMITS.wsMessage and the
    // channel's credit window is smaller still, so the send parks on flow control
    // with a few KiB outstanding and the phone sees nothing at all — measured live
    // as `tunnel credit stall 10s pending=4KiB` right after `focus ... replay=147`.
    // Splitting at the same budget the live path already batches with keeps every
    // frame well inside one message, and `hasMore` still travels on the last piece.
    for (let at = 0; at < records.length || at === 0; ) {
      const piece: TimelineRecord[] = [];
      let bytes = 0;
      while (at < records.length) {
        const size = recordBytes(records[at]!);
        if (piece.length && bytes + size > SYNC_V2.coalesceBytes) break;
        piece.push(records[at]!);
        bytes += size;
        at++;
      }
      const last = at >= records.length;
      this.send({ type: 'RECORDS', sessionId, records: piece, hasMore: hasMore && last, stopReason });
      if (last) break;
    }
  }

  /**
   * Queues one live record for the next batch.
   *
   * <p>Deliberately no `hasMore`: a live record is by definition at the tail, and the
   * phone's own `afterSeq` decides what is missing below it. Saying `hasMore` here
   * would make the phone re-request a gap it is already filling.
   *
   * <p>The timer is a *maximum* delay, not a debounce that resets on every record —
   * a turn that emits continuously would otherwise never flush, and the phone would
   * sit on a silent stream with a pulse ticking. Flushing is also forced as soon as
   * the accumulated payload passes [SYNC_V2.coalesceBytes], so a burst of large tool
   * results cannot build up an unbounded buffer behind a 50 ms timer.
   */
  private batch(sessionId: string, record: TimelineRecord): void {
    if (this.closed || !this.focused.has(sessionId)) return;
    const queued = this.pendingRecords.get(sessionId);
    if (queued) queued.push(record); else this.pendingRecords.set(sessionId, [record]);
    this.flushTimer ??= setTimeout(() => this.flushRecords(), SYNC_V2.coalesceMs);
    this.flushTimer.unref();
    if (this.queuedBytes(sessionId) >= SYNC_V2.coalesceBytes) this.flushRecords(sessionId);
  }

  private queuedBytes(sessionId: string): number {
    const queued = this.pendingRecords.get(sessionId);
    if (!queued) return 0;
    return queued.reduce((sum, record) => sum + recordBytes(record), 0);
  }

  /** Sends whatever is queued. `only` flushes a single session and leaves the rest. */
  private flushRecords(only?: string): void {
    clearTimeout(this.flushTimer);
    this.flushTimer = undefined;
    for (const [sessionId, records] of [...this.pendingRecords]) {
      if (only !== undefined && sessionId !== only) continue;
      this.pendingRecords.delete(sessionId);
      // `focused` can drop between queueing and flushing (unfocus while a batch is
      // in the timer): sending then would leak records for a session the phone has
      // already left, and it would re-paint stale rows on the next focus.
      if (!records.length || !this.focused.has(sessionId)) continue;
      this.emitRecords(sessionId, records, false, 'TAIL_COMPLETE');
    }
    // Anything still queued (because `only` filtered it out) needs its timer back.
    if (this.pendingRecords.size) {
      this.flushTimer = setTimeout(() => this.flushRecords(), SYNC_V2.coalesceMs);
      this.flushTimer.unref();
    }
  }

  /** One pulse per focused session per tick, unconditionally.
   *
   *  <p>The timer is not "emit when something happened" — that is precisely how v1's
   *  watchdogs ended up silent during a 16-minute stall. A quiet session still pulses
   *  with `hostState: THINKING_SILENT`, and the phone renders the elapsed silence
   *  instead of guessing.
   */
  private emitPulses(): void {
    if (this.closed) return;
    // Flush before pulsing: a pulse reports `latestSeq`, so a phone that trusted it
    // while records sat in the batcher would think it had seen more than it had and
    // skip the gap. One flush per 5 s costs nothing and removes that class of skew.
    this.flushRecords();
    const buffered = this.options.bufferedBytes?.() ?? 0;
    for (const sessionId of this.focused) {
      const snapshot = this.options.hub.pulse(sessionId);
      const frame: SessionPulse = pulse({
        sessionId,
        latestSeq: snapshot.latestSeq,
        archiveCoveredSeq: snapshot.archiveCoveredSeq,
        hostState: snapshot.hostState as HostState,
        silentDurationMs: snapshot.silentDurationMs,
        tunnelBufferBytes: buffered,
        workerEpoch: this.options.workerEpoch,
      });
      this.send(frame);
      this.pulsesSent++;
      // 每个聚焦只记**第一帧**：坑 54 的排查里，「脉搏到底有没有发出去」是
      // 唯一无法从两端日志反推的事——插件静默、中继不解析、手机侧取不到日志。
      // 一行 per focus 成本可以忽略，却能把「发不出」和「发了没到」分开。
      if (!this.pulsedOnce.has(sessionId)) {
        this.pulsedOnce.add(sessionId);
        this.options.onLog?.(
          `pulse begin ${sessionId.slice(0, 12)} latest=${frame.latestSeq} covered=${frame.archiveCoveredSeq} state=${frame.hostState} buffered=${Math.round(buffered / 1024)}KiB`,
        );
      }
    }
  }

  /** 本次连接上累计发出的脉搏数（诊断用；`/metrics` 与设置页都读它）。 */
  get sentPulses(): number { return this.pulsesSent; }
  private pulsesSent = 0;
  /** 已经为哪些聚焦记过「第一帧脉搏」的日志，避免每 5 秒刷屏。 */
  private readonly pulsedOnce = new Set<string>();

  /** Runs one write and acknowledges it exactly once per `cmdId`.
   *
   *  <p>A repeated `cmdId` replays the cached ack and touches nothing upstream: the
   *  phone's 3-second ACK race re-sends over HTTPS when this socket looks dead, and the
   *  two copies can both land. Applying an approval twice is not a lost update here —
   *  the host would see a second `$events/result` for an already-settled waterfall.
   */
  private async command(frame: Extract<PhoneToPlugin, { type: 'CMD' }>): Promise<void> {
    const cached = this.acks.get(frame.cmdId);
    if (cached) { this.send(cached); return; }
    // **In-flight** duplicates are the other half of idempotency, and the one that used
    // to be missing: the cache above only answers a repeat that arrives *after* the
    // first copy finished. Two copies can be in flight at once — the phone re-sends over
    // HTTPS when the control socket looks dead, while the original is still waiting on
    // the host — and both would execute. Applying an approval twice is not a lost update:
    // the host would see a second `$events/result` for an already-settled waterfall.
    //
    // Registering the promise means the second copy awaits the first one's ack instead
    // of starting its own RPC.
    const running = this.inflight.get(frame.cmdId);
    if (running) { this.send(await running); return; }
    const route = CMD_ROUTES[frame.op];
    if (!route) { this.send({ type: 'CMD_ACK', cmdId: frame.cmdId, ok: false, error: 'unknown-op' }); return; }
    // T3→T4：从解出这条 CMD 到宿主答复。不是「整条链路」的耗时——手机与中继
    // 那两跳在 ack 抵达手机时才收口，两端时钟又不可比，所以只报时长、不报时刻。
    // An approval answer is accepted only from the client the waterfall was delivered
    // to. The phone cannot know this worker's `$events` id, so the plugin fills it in —
    // leaving the phone's own id here made every answer vanish and every card return.
    const payload = route.wrap === 'bare' && frame.op === 'interaction'
      ? { ...(frame.payload as Record<string, unknown>), clientId: this.options.eventsClientId?.() ?? '' }
      : frame.payload;
    const started = Date.now();
    const work = (async (): Promise<PluginToPhone> => {
      try {
        await this.steerOrSend(frame, route, payload);
        // 宿主认了这一答，就把这条从尾部窗口摘掉：否则每次有新手机连上来都会把它
        // 重投一次，用户点过的卡会反复复活——坑 25 在 v2 通道上的同一个坑。
        if (frame.op === 'interaction') {
          const eventId = (frame.payload as { eventId?: unknown } | null)?.eventId;
          if (typeof eventId === 'string' && eventId) this.options.hub.settleInteraction(eventId);
        }
        return { type: 'CMD_ACK', cmdId: frame.cmdId, ok: true, pluginMs: Date.now() - started };
      } catch (error) {
        return { type: 'CMD_ACK', cmdId: frame.cmdId, ok: false, pluginMs: Date.now() - started, error: error instanceof Error ? error.message : 'rpc-failed' };
      }
    })();
    this.inflight.set(frame.cmdId, work);
    let ack: PluginToPhone;
    try {
      ack = await work;
    } finally {
      this.inflight.delete(frame.cmdId);
    }
    this.acks.set(frame.cmdId, ack);
    if (this.acks.size > ACK_CACHE) { const first = this.acks.keys().next().value; if (first !== undefined) this.acks.delete(first); }
    this.send(ack);
  }

  /**
   * 队列卡上的「立即发送」：宿主在跑就 steer，空闲就直接 prompt。
   *
   * <p>为什么这个判断必须在这里做：`updateQueue` 的 `steer` 只认
   * `agent.status === "running"`，空闲时直接抛 `session/steer-unavailable`
   * （`dsh-api-session-controller/lib/index.js` 里那一行判断）。而手机看到的
   * `running` 来自脉搏，最多可能差一个 tick——用户按下去那一刻宿主刚好跑完，
   * 手机还以为是 running，就会收到一条「不能插话」的错误，而他要的只是
   * 「现在把这句话发出去」。
   *
   * <p>只有插件能看见实时的 `hostState`（`hub.pulse()`），所以在这里分流：
   * - `IDLE` → 先用 `remove` 把这条从队列里撤掉，再 `session/prompt` 发出去。
   *   必须先删：队列里的那条不会被自动清掉，宿主空闲时也只是「等一下会执行」，
   *   直接 prompt 会让同一句话在队列和正文里各出现一次。
   * - 其它（`STREAMING` / `THINKING_SILENT`）→ 原样走 steer。
   *
   * <p>`next-step` 桶不参与：宿主只允许 `next-turn` 被 steer，`next-step` 一律按
   * `updateQueue` 原样透传（它会自己报错，那是真实语义）。
   */
  private async steerOrSend(
    frame: Extract<PhoneToPlugin, { type: 'CMD' }>,
    route: (typeof CMD_ROUTES)[string],
    payload: unknown,
  ): Promise<void> {
    const steer = readSteer(frame.payload);
    if (frame.op !== 'queue' || steer === undefined) {
      await this.rpc(route.path, route.method, route.wrap, payload);
      return;
    }
    const state = this.options.hub.pulse(steer.sessionId).hostState;
    if (state !== 'IDLE') {
      await this.rpc(route.path, route.method, route.wrap, payload);
      return;
    }
    // 空闲：先撤队列项，再直接发。撤不下来就说明它已经被领走了（真的在跑），
    // 此时退回 steer 让宿主自己回答「能不能插」。
    try {
      await this.rpc(
        '/api/session/updateQueue',
        'session/updateQueue',
        'request',
        { sessionId: steer.sessionId, itemId: steer.itemId, action: { kind: 'remove' } },
      );
    } catch {
      await this.rpc(route.path, route.method, route.wrap, payload);
      return;
    }
    // 从这里往下：队列项**已经撤掉了**。prompt 再失败，用户那句话就既不在队列里
    // 也不在宿主那边 —— 界面上只剩一句错误提示，消息本身丢了。所以失败必须把文本
    // 重新塞回队列（`mode:'queue'` 就是「等这一轮跑完再执行」，与它原来的位置等价），
    // 并把「已回队」写进错误串，让用户知道不用重新打一遍。
    //
    // requestId 用 `requeue-` 前缀：和下面那条 `steer-` 分开，避免宿主把两次调用
    // 当成同一请求去重（`steer-${cmdId}` 已被本次尝试用过）。
    // 从这里往下：队列项**已经撤掉了**。prompt 再失败，用户那句话就既不在队列里
    // 也不在宿主那边 —— 界面上只剩一句错误提示，消息本身丢了。所以失败必须把文本
    // 重新塞回队列（`mode:'queue'` 就是「等这一轮跑完再执行」，与它原来的位置等价），
    // 并把「已回队」写进错误串，让用户知道不用重新打一遍。
    //
    // requestId 用 `requeue-` 前缀：和上面那条 `steer-` 分开，避免宿主把两次调用
    // 当成同一请求去重（`steer-${cmdId}` 已被本次尝试用过）。
    try {
      await this.rpc('/api/session/prompt', 'session/prompt', 'request', {
        sessionId: steer.sessionId,
        requestId: `steer-${frame.cmdId}`,
        mode: 'queue',
        content: [{ type: 'text', text: steer.text }],
      });
    } catch (error) {
      const reason = error instanceof Error ? error.message : 'rpc-failed';
      try {
        await this.rpc('/api/session/prompt', 'session/prompt', 'request', {
          sessionId: steer.sessionId,
          requestId: `requeue-${frame.cmdId}`,
          mode: 'queue',
          content: [{ type: 'text', text: steer.text }],
        });
        throw new Error(`${reason}; 消息已放回队列`);
      } catch (requeueError) {
        // 回队也失败：至少别把原始失败掩盖成「回队失败」，两件事都要说出来。
        const second = requeueError instanceof Error ? requeueError.message : 'rpc-failed';
        if (second.includes('消息已放回队列')) throw requeueError;
        throw new Error(`${reason}; 回队也失败（${second}），请重新发送`);
      }
    }
  }

  /** Loopback RPC with the same `agent:false` rule as every other host call (a pooled
   *  socket the host has already closed turns into a 502 the user reads as "phone is
   *  broken"). Writes are not retried: the request body is already consumed, and the
   *  phone's own idempotency key covers the retry that matters. */
  private async rpc(
    path: string,
    method: string,
    wrap: 'request' | 'bare',
    payload: unknown,
  ): Promise<void> {
    const cookie = await this.options.getCookie();
    const args = wrap === 'request' ? { request: payload } : payload;
    const body = JSON.stringify({
      type: 'client-request',
      rpcId: `cmd-${this.now()}`,
      method,
      payload: { args },
    });
    if (this.options.fetch) {
      // Tests inject a fetch to observe what would reach the host.
      const url = new URL(this.options.upstreamOrigin);
      const response = await this.options.fetch(new URL(path, this.options.upstreamOrigin), {
        method: 'POST',
        redirect: 'error',
        signal: AbortSignal.timeout(20000),
        headers: { 'content-type': 'application/json', cookie, host: url.host, origin: url.origin },
        body,
      });
      if (!response.ok) throw new Error(`host-${response.status}`);
      return;
    }
    // Production goes through `loopbackPost`, which is the only place with
    // `agent: false`. Node's global fetch keeps a pooled agent, so "we set the header
    // and hoped" is exactly how v1 produced `502 upstream-error` on a stalled host.
    const response = await loopbackPost(this.options.upstreamOrigin, path, body, 20000, { cookie });
    if (response.status < 200 || response.status >= 300) throw new Error(`host-${response.status}`);
  }
}

/** 从一条 `queue` 命令里取出「立即发送」需要的三个字段；不是 steer 就返回 undefined。 */
function readSteer(payload: unknown): { sessionId: string; itemId: string; text: string } | undefined {
  if (typeof payload !== 'object' || payload === null) return undefined;
  const p = payload as { sessionId?: unknown; itemId?: unknown; action?: { kind?: unknown; text?: unknown } };
  if (p.action?.kind !== 'steer') return undefined;
  const text = typeof p.action.text === 'string' ? p.action.text.trim() : '';
  if (typeof p.sessionId !== 'string' || typeof p.itemId !== 'string' || text.length === 0) return undefined;
  return { sessionId: p.sessionId, itemId: p.itemId, text };
}

/** True when the phone is missing records older than the hub's tail window. The hub
 *  answers from `replay`; if its oldest held seq is above `sinceSeq + 1` and below
 *  `latestSeq`, something fell out of the window and only the archive can supply it. */
function hasMoreBelow(hub: SessionHub, sessionId: string, sinceSeq: number): boolean {
  const oldest = hub.oldestSeq(sessionId);
  return oldest > 0 && oldest > sinceSeq + 1;
}
