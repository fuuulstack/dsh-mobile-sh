/**
 * Per-session record authority (v2 §1.2 "插件 Worker = 唯一采集与编号者").
 *
 * <p>Everything the phone will ever see about a session passes through here once:
 * a host event becomes a {@link TimelineRecord} with a `prevSeq` link, gets written
 * to the durable outbox, lands in a bounded in-memory tail, and is handed to every
 * phone currently focused on that session. The archive push and the live stream
 * therefore carry byte-identical records — the phone runs one projector, not two.
 *
 * <p>{@link TimelineRecord.prevSeq} is the load-bearing field. The host's own `seq`
 * is monotonic but not contiguous (gaps appear across restarts and compaction), so
 * "last seq + 1" is not a safe expectation. Instead each record points at its
 * immediate predecessor *among the seqs this plugin has actually seen*, or 0 when it
 * is the first link in the chain. A phone holding up to N can then write anything
 * whose `prevSeq <= N` without creating a hole, and must park anything else in the
 * uncommitted buffer until the gap is filled — which is exactly the invariant the
 * relay's `/v2/sync` pages satisfy.
 *
 * <p>The tail is bounded twice (count and bytes) because a single assistant message
 * can be hundreds of kilobytes: a 200-entry ring of oversized messages would be tens
 * of megabytes in a process that shares a 768MB box with the desktop host.
 */
import type { ArchiveOutbox } from './outbox.js';
import type { HostState, InteractionFrame, TimelineRecord } from '../../core/src/index.js';
import { applySizePolicy, classify, isStructural, recordBytes } from '../../core/src/index.js';

export interface HubOptions {
  outbox: ArchiveOutbox;
  /** Records kept per session for focus replay. */
  tailSize?: number;
  /** Byte ceiling per session tail. */
  tailBytes?: number;
  /** Total byte ceiling across all sessions. */
  totalBytes?: number;
  now?: () => number;
  /** Called when the outbox refuses a record because it is full. The caller uses it
   *  for observability; the record itself is *not* handed over for repair, because
   *  the hub already holds it (see {@link SessionHub.retryPending}). */
  onOverflow?: (sessionId: string) => void;
  /** Called after a record became durable. The worker uses it to nudge the pusher,
   *  so a new record leaves within a tick instead of at the next poll. */
  onRecord?: (record: TimelineRecord) => void;
}

export interface PulseSnapshot {
  latestSeq: number;
  archiveCoveredSeq: number;
  hostState: HostState;
  silentDurationMs: number;
}

export type RecordListener = (record: TimelineRecord) => void;

interface TailState {
  turnId: string;
  ring: TimelineRecord[];
  bytes: number;
  latestSeq: number;
  lastStructuralAt: number;
  lastDeltaAt: number;
  running: boolean;
  listeners: Set<RecordListener>;
}

/** Ceiling on records held for a full outbox. The outbox itself holds 20 000 rows; a
 *  backlog of this size past that means something is badly wrong, and holding an
 *  unbounded copy in memory would take the whole worker down. Records past it are
 *  counted in {@link SessionHub.dropped} so the loss is visible, never silent.
 *
 *  <p>Why a byte ceiling on top of the row count: the count alone assumes records are
 *  small. They are not all small — a record just under `stubThreshold` (256 KiB) still
 *  lands here whole, so 5000 of them would be 1.2 GiB, not the ~100 MiB a realistic
 *  mix averages out to. 32 MiB is the point past which holding more in a worker that
 *  shares a 768 MB box with the host does more harm than the records are worth.
 *
 *  <p>Eviction is FIFO: the newest record is the one the phone has not seen, so it is
 *  the one worth keeping. Every eviction is counted in {@link SessionHub.dropped}.
 */
const MAX_BACKLOG = 5000;
const MAX_BACKLOG_BYTES = 32 * 1024 * 1024;

/** Structural events are what the user can see; deltas only mean "still typing". */
const STREAM_DELTA_WINDOW_MS = 4000;
const THINKING_SILENT_AFTER_MS = 2000;
/** 未结算瀑布的尾部窗口上限。宿主正常最多同时挂个位数张卡。 */
const MAX_INTERACTIONS = 16;

export class SessionHub {
  private readonly tails = new Map<string, TailState>();
  private readonly tailSize: number;
  private readonly tailBytes: number;
  private readonly totalBytes: number;
  private readonly now: () => number;
  private total = 0;
  /** Records the outbox refused, oldest first. Bounded: see MAX_BACKLOG. */
  private readonly backlog: TimelineRecord[] = [];
  private backlogBytes = 0;
  private droppedRecords = 0;
  /**
   * Duplicate seqs carrying a **different event type** than the one we already stored.
   *
   * <p>That is the signature of the host renumbering a session: the same seq now means
   * something else. A legitimate replay re-sends the same event, so its type matches
   * and it is not counted. Non-zero here means the archive is about to stop growing.
   */
  private suspiciousDuplicates = 0;
  /**
   * 未结算的审批/提问瀑布，按 `eventId` 记账。
   *
   * <p>它不是记录，因此**不落 outbox、不进归档**——断线后没有任何东西能把它补回来。
   * 而「手机上什么时候才需要它」跟「宿主什么时候问」并不同时：审批往往在人还停在
   * 会话列表页时就到了，等他点进聊天页，一条 `$events` 早已推完。v1 时代靠「重开
   * `$events` 让宿主重投」兜底，那条路已随 v1 路由删掉，所以这里必须自己留尾部窗口，
   * 并在每台手机新订阅时重投一次。
   *
   * <p>有界：`MAX_INTERACTIONS`。宿主不可能同时挂几百个未结算的提问；真到那个量级，
   * 丢掉的都是早该被结算的陈旧卡片。
   */
  private readonly interactions = new Map<string, InteractionFrame>();
  private readonly interactionListeners = new Set<(frame: InteractionFrame) => void>();

  constructor(private readonly options: HubOptions) {
    this.tailSize = options.tailSize ?? 200;
    this.tailBytes = options.tailBytes ?? 2 * 1024 * 1024;
    this.totalBytes = options.totalBytes ?? 16 * 1024 * 1024;
    this.now = options.now ?? Date.now;
  }

  private tail(sessionId: string): TailState {
    let state = this.tails.get(sessionId);
    if (!state) {
      state = { turnId: '', ring: [], bytes: 0, latestSeq: 0, lastStructuralAt: this.now(), lastDeltaAt: 0, running: false, listeners: new Set() };
      this.tails.set(sessionId, state);
    }
    return state;
  }

  /** Turns one host event into a numbered record and fans it out.
   *
   *  <p>Returns `undefined` for events that carry no `seq` (host plumbing) — those
   *  are dropped rather than given an invented number, because a fabricated seq
   *  would poison the phone's watermark.
   */
  ingest(sessionId: string, event: { type?: unknown; seq?: unknown; data?: unknown }): TimelineRecord | undefined {
    const seq = typeof event.seq === 'number' && Number.isSafeInteger(event.seq) && event.seq >= 0 ? event.seq : undefined;
    const type = typeof event.type === 'string' ? event.type : undefined;
    if (seq === undefined || type === undefined) return undefined;
    const state = this.tail(sessionId);
    const known = state.ring.find(entry => entry.seq === seq);
    if (known) {
      // A duplicate seq is either a legitimate replay (the host re-sends the same events
      // after a reconnect) or the host restarting its numbering for a session it had
      // already numbered. The second case is unrecoverable from here — every new event
      // collides with a stored row and is dropped — and it used to be **completely
      // silent**: the archive simply stopped growing, with no log line anywhere.
      //
      // Tell them apart by the event **type**. A replay repeats the same event, so the
      // type matches. A renumbering arrives as a different event at a seq we already
      // spent. Comparing types is exact and costs nothing; comparing seq against the
      // watermark would flag every snapshot replay, which is constant traffic.
      const heldType = (known.payload as { type?: unknown } | undefined)?.type;
      if (typeof heldType === 'string' && typeof type === 'string' && heldType !== type) {
        this.suspiciousDuplicates++;
      }
      return undefined;
    }

    const classification = classify({ type, seq, data: event.data } as never, sessionId, state.turnId);
    state.turnId = classification.turnId;
    const prevSeq = this.predecessor(state, seq);
    let record: TimelineRecord = {
      sessionId,
      seq,
      prevSeq,
      turnId: classification.turnId,
      stepId: classification.stepId,
      kind: classification.kind,
      timestampMs: this.now(),
      payload: { type, seq, data: event.data },
    };
    // Size policy runs once, here, before anything is stored or sent: an oversized
    // payload becomes a stub plus a durable blob, and past the hard ceiling it becomes
    // a record that says it was truncated. Doing this at the single ingest point is
    // what makes every later retry cheap and every consumer agree on the same bytes.
    record = applySizePolicy(record, (blobId, payload) => this.options.outbox.putBlob(blobId, payload));
    if (seq > state.latestSeq) state.latestSeq = seq;
    if (classification.kind === 'TURN_END') state.running = false;
    else if (classification.kind === 'USER_INPUT') state.running = true;
    if (isStructural(classification.kind)) state.lastStructuralAt = this.now();

    const stored = this.options.outbox.put(record);
    if (stored === 'stored') {
      this.options.onRecord?.(record);
    } else if (stored === 'full') {
      // The outbox is full. The record is *not* dropped: it stays here in a bounded
      // backlog and is retried once the pusher drains something. Re-fetching a snapshot
      // cannot repair this — the host's replay would carry the same seqs, and this hub
      // de-duplicates by seq, so the replayed copies never reach the outbox again.
      // Keeping the record is the only route that does not lose it.
      const bytes = recordBytes(record);
      if (this.backlog.length < MAX_BACKLOG && this.backlogBytes + bytes < MAX_BACKLOG_BYTES) {
        this.backlog.push(record);
        this.backlogBytes += bytes;
      } else {
        // FIFO: the newest record is the one the phone has not seen, so evict the oldest.
        const dropped = this.backlog.shift();
        if (dropped) {
          this.backlogBytes -= recordBytes(dropped);
          this.droppedRecords++;
        }
        this.backlog.push(record);
        this.backlogBytes += bytes;
      }
      this.options.onOverflow?.(sessionId);
    }
    this.push(state, record);
    for (const listener of state.listeners) listener(record);
    return record;
  }

  /** Retries the records the outbox refused while it was full.
   *
   *  <p>Called after the pusher acknowledges a batch: that is the moment capacity
   *  frees up, and the alternative (a timer that polls) would keep retrying against a
   *  still-full outbox. Records are retried oldest-first so the hole this leaves is a
   *  suffix rather than a scatter, which keeps `prevSeq` chains meaningful for the
   *  phone.
   *
   *  <p>Returns how many became durable.
   */
  retryPending(): number {
    if (!this.backlog.length) return 0;
    let stored = 0;
    while (this.backlog.length) {
      const record = this.backlog[0]!;
      const outcome = this.options.outbox.put(record);
      if (outcome === 'full') break;
      this.backlog.shift();
      this.backlogBytes -= recordBytes(record);
      if (outcome === 'stored') { stored++; this.options.onRecord?.(record); }
    }
    return stored;
  }

  /** Records still waiting for outbox capacity. Surfaced as a metric: a number that
   *  stays above zero means the archive is falling behind and the phone will have a
   *  hole below `latestSeq`. */
  get pending(): number { return this.backlog.length; }

  /** Records evicted from the backlog ceiling — the only path in v2 that can lose a
   *  record, and it is reported rather than silent. */
  get dropped(): number { return this.droppedRecords; }

  /** Duplicates that look like host-side seq reuse rather than a replay. */
  get renumbered(): number { return this.suspiciousDuplicates; }

  /** A live typing delta. Not a timeline record (it never reaches the relay), but
   *  it moves the pulse from THINKING_SILENT to STREAMING so the phone shows
   *  "writing" instead of "deep thinking". */
  noteDelta(sessionId: string): void {
    const state = this.tail(sessionId);
    state.lastDeltaAt = this.now();
    state.running = true;
  }

  /** Fans out the reasoning text a turn is currently producing.
   *
   *  <p>Same live-only contract as {@link ingestDelta} — never archived, never
   *  numbered — but a **separate frame type** (`assistant-reasoning`). Keeping it
   *  apart is not cosmetic: the phone renders `assistant-stream` as the answer
   *  bubble and `assistant-reasoning` as the folded panel's tail preview. Sharing
   *  one buffer put the thinking straight into the chat transcript (2026-10-30).
   *
   *  <p>`text` is the whole reasoning so far, not the latest fragment — the phone
   *  replaces rather than appends, so a lost frame heals itself on the next one.
   */
  ingestReasoning(sessionId: string, text: string, turnId?: string): TimelineRecord | undefined {
    if (!text) return undefined;
    const state = this.tail(sessionId);
    const record: TimelineRecord = {
      sessionId,
      seq: state.latestSeq,
      prevSeq: state.latestSeq,
      turnId: turnId || state.turnId,
      kind: 'STREAM_DELTA',
      timestampMs: this.now(),
      payload: { type: 'assistant-reasoning', data: { text } },
    };
    this.noteDelta(sessionId);
    for (const listener of state.listeners) listener(record);
    return record;
  }

  ingestDelta(sessionId: string, text: string, turnId?: string): TimelineRecord | undefined {
    if (!text) return undefined;
    const state = this.tail(sessionId);
    const record: TimelineRecord = {
      sessionId,
      seq: state.latestSeq,
      prevSeq: state.latestSeq,
      turnId: turnId || state.turnId,
      kind: 'STREAM_DELTA',
      timestampMs: this.now(),
      payload: { type: 'assistant-stream', data: { text } },
    };
    this.noteDelta(sessionId);
    for (const listener of state.listeners) listener(record);
    return record;
  }

  /** Marks a turn as started without a record — used when the host reports status
   *  through session metadata rather than a timeline event.
   *
   *  Only ever raises the flag: `session/list` is a 60-second snapshot and must not
   *  clear a `running` that `USER_INPUT` / `noteDelta` set a moment ago. Ending a
   *  turn is `TURN_END`'s job in `ingest()` (see pitfall 85). */
  noteRunning(sessionId: string, running: boolean): void {
    if (running) this.tail(sessionId).running = true;
  }

  latestSeq(sessionId: string): number {
    const state = this.tails.get(sessionId);
    return state ? state.latestSeq : 0;
  }

  /** Oldest seq still held for this session — 0 when the tail is empty.
   *
   *  <p>The control channel compares this against the phone's `sinceSeq` to decide
   *  `hasMore`. That has to be a range test, not "is anything left": a phone that
   *  already has the whole tail also asks for `sinceSeq = latestSeq`, and telling it
   *  `hasMore` there would make it call `/v2/sync` on every focus for nothing.
   */
  oldestSeq(sessionId: string): number {
    const state = this.tails.get(sessionId);
    const first = state?.ring[0];
    return first ? first.seq : 0;
  }

  /** Records the tail still remembers after `sinceSeq`. Used when the phone focuses
   *  a session: it re-renders instantly from live memory and lets `/v2/sync` fill
   *  anything below the window. */
  replay(sessionId: string, sinceSeq: number, budgetBytes: number): TimelineRecord[] {
    const state = this.tails.get(sessionId);
    if (!state) return [];
    const out: TimelineRecord[] = [];
    let bytes = 0;
    for (const record of state.ring) {
      if (record.seq <= sinceSeq) continue;
      const size = recordBytes(record);
      if (out.length && bytes + size > budgetBytes) break;
      out.push(record);
      bytes += size;
    }
    return out;
  }

  subscribe(sessionId: string, listener: RecordListener): () => void {
    const state = this.tail(sessionId);
    state.listeners.add(listener);
    return () => { state.listeners.delete(listener); };
  }

  /** Drops subscribers of a disconnected phone without touching the tail. */
  clearListeners(): void {
    for (const state of this.tails.values()) state.listeners.clear();
  }

  /**
   * 收下一条宿主瀑布并扇出给所有手机。
   *
   * <p>`eventId` 是宿主的身份（`waterfall` 帧里就有），也是手机回答时 `$events/result`
   * 要回指的那个 id；用它当键才能让「同一张卡被重投两次」收敛成一条。没有 id 的帧
   * （少见，`cancel` 有时不带）不入账，只扇出——留着一个永远结算不掉的占位比丢掉一条
   * 更糟。
   */
  publishInteraction(frame: InteractionFrame): void {
    const eventId = (frame.payload as { eventId?: unknown } | null)?.eventId;
    if (typeof eventId === 'string' && eventId) {
      if (frame.kind === 'cancel') this.interactions.delete(eventId);
      else {
        // 重投一次就把它挪到尾部：Map 的插入序就是重投顺序。
        this.interactions.delete(eventId);
        this.interactions.set(eventId, frame);
        while (this.interactions.size > MAX_INTERACTIONS) {
          const oldest = this.interactions.keys().next();
          if (oldest.done) break;
          this.interactions.delete(oldest.value);
        }
      }
    }
    this.fanOutInteraction(frame);
  }

  /** 手机上答完了（或宿主自己取消了）——把这条从尾部窗口里摘掉，别再重投。 */
  settleInteraction(eventId: string): void {
    this.interactions.delete(eventId);
  }

  /** 未结算瀑布的快照，按到达顺序。新订阅的手机先收这一段，再收后续增量。 */
  pendingInteractions(): InteractionFrame[] {
    return [...this.interactions.values()];
  }

  /**
   * 订阅瀑布。
   *
   * @returns 退订函数。新订阅者**不会**自动收到尾部窗口——重投是订阅者自己的动作
   * （见 {@link pendingInteractions}），因为它必须在发过 `HELLO_ACK`、确认这条通道
   * 真的通了之后才发，否则重投会掉进一个还没建好的 socket。
   */
  subscribeInteractions(listener: (frame: InteractionFrame) => void): () => void {
    this.interactionListeners.add(listener);
    return () => { this.interactionListeners.delete(listener); };
  }

  private fanOutInteraction(frame: InteractionFrame): void {
    for (const listener of [...this.interactionListeners]) {
      try { listener(frame); } catch {
        // 一台手机的推送失败不能连坐其余手机：审批卡在别人手机上还得能用。
      }
    }
  }

  /** The numbers behind a `SESSION_PULSE`. `archiveCoveredSeq` is read from the
   *  outbox, so a phone that is behind knows whether the relay can already serve
   *  the gap (`covered >= latest`) or must wait for the host. */
  pulse(sessionId: string): PulseSnapshot {
    const state = this.tails.get(sessionId);
    if (!state) return { latestSeq: 0, archiveCoveredSeq: 0, hostState: 'IDLE', silentDurationMs: 0 };
    const now = this.now();
    const silentDurationMs = Math.max(0, now - state.lastStructuralAt);
    const streaming = now - state.lastDeltaAt < STREAM_DELTA_WINDOW_MS;
    const hostState: HostState = streaming
      ? 'STREAMING'
      : state.running && silentDurationMs >= THINKING_SILENT_AFTER_MS
        ? 'THINKING_SILENT'
        : state.running ? 'STREAMING' : 'IDLE';
    return {
      latestSeq: state.latestSeq,
      archiveCoveredSeq: this.options.outbox.ackedSeq(sessionId),
      hostState,
      silentDurationMs,
    };
  }

  /** Sessions with a live subscriber, for the pulse emitter's work set. */
  focused(): string[] {
    const out: string[] = [];
    for (const [sessionId, state] of this.tails) if (state.listeners.size) out.push(sessionId);
    return out;
  }

  private predecessor(state: TailState, seq: number): number {
    let best = 0;
    for (const entry of state.ring) if (entry.seq < seq && entry.seq > best) best = entry.seq;
    return best;
  }

  private push(state: TailState, record: TimelineRecord): void {
    const size = recordBytes(record);
    state.ring.push(record);
    state.bytes += size;
    this.total += size;
    while (state.ring.length > this.tailSize || state.bytes > this.tailBytes) {
      const dropped = state.ring.shift();
      if (!dropped) break;
      const freed = recordBytes(dropped);
      state.bytes -= freed;
      this.total -= freed;
    }
    // Cross-session ceiling: evict from whichever tail is oldest rather than
    // letting one runaway session starve the others.
    while (this.total > this.totalBytes) {
      let oldest: TailState | undefined;
      let oldestAt = Number.POSITIVE_INFINITY;
      for (const candidate of this.tails.values()) {
        const first = candidate.ring[0];
        if (!first) continue;
        if (first.seq < oldestAt) { oldestAt = first.seq; oldest = candidate; }
      }
      if (!oldest) break;
      const dropped = oldest.ring.shift();
      if (!dropped) break;
      const freed = recordBytes(dropped);
      oldest.bytes -= freed;
      this.total -= freed;
    }
  }
}
