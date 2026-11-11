/**
 * Loopback collector: the only thing in the plugin that talks to the host's mux
 * (v2 §1.2 "插件 Worker = 唯一采集与编号者").
 *
 * <p>What this file is *not*: a queue, a watermark, or a retry loop. v1's archiver was
 * all three — an in-memory array with a `maxSeq` it advanced only after a successful
 * push, an overflow path that dropped the oldest entries and then tried to refetch full
 * snapshots to paper over the hole, and a dedupe bug where a snapshot re-delivered while
 * an HTTP push was still in flight got uploaded a second time. Every one of those
 * failures came from keeping the same fact (what has been archived?) in memory.
 *
 * <p>Here the host's events go straight into {@link SessionHub}, which numbers them and
 * writes them to a SQLite outbox in the same call. Durability is the outbox's problem,
 * delivery is the pusher's problem, and this file only has to answer "which sessions am
 * I following, and how do I get reconnected". That is a much smaller thing to get right.
 *
 * <p>Records are never trimmed on the way in: `applySizePolicy` is applied by the hub, so
 * an oversized assistant message becomes a <2 KiB stub plus a blob at the moment it is
 * first seen, and the wire cost of every later retry is already bounded.
 */
import WebSocket from 'ws';
import { contentReasoning, isInternalSession, runningSessions, slimSession, SYNC_V2 } from '../../core/src/index.js';
import type { SessionHub } from './hub.js';
import type { ArchiveOutbox } from './outbox.js';
import type { HostMetrics } from './metrics.js';

export interface CollectorOptions {
  hub: SessionHub;
  outbox: ArchiveOutbox;
  upstreamOrigin: string;
  getCookie: () => Promise<string>;
  onLog?: (message: string) => void;
  metrics?: HostMetrics;
  /** Sessions to follow. The host lists newest-updated first and everything else is
   *  reachable through the archive, so this only bounds *live* coverage. */
  topK?: number;
  intervalMs?: number;
  kickDebounceMs?: number;
  reconnectBaseMs?: number;
  maxReconnectMs?: number;
}

type Frame = { type?: unknown; streamId?: unknown; value?: unknown; emit?: unknown };

/** streamId of the app-level `$events` substream. Deliberately absent from
 *  `streams`: it belongs to no single session. */
const EVENTS_STREAM = 'arch-events';

/** `$events` frames that mean "the session list is stale" rather than "here is content". */
const LIST_KICK = /^api-session\//;

export class Collector {
  private socket?: WebSocket;
  private listTimer?: NodeJS.Timeout;
  private kickTimer?: NodeJS.Timeout;
  private retryTimer?: NodeJS.Timeout;
  private stopping = false;
  private reconnectFailures = 0;
  /** streamId -> sessionId for the follow substreams we opened. */
  private streams = new Map<string, string>();
  /**
   * Monotonic source of follow `streamId`s.
   *
   * <p>It used to be `arch-${followed.size + 1}`, which recycles ids: unfollowing a
   * session lowers the size, so the next follow hands a brand-new session a streamId
   * the host still has an open stream on. Every frame that stream produces is then
   * attributed to the new session — real-time and archive both mis-filed, silently.
   * The host's mux protocol has no "close this substream" frame we can rely on, so the
   * only safe rule is that an id is never reused.
   */
  private nextStreamId = 0;
  /** Streams we stopped following. Their frames are dropped, never re-attributed. */
  private readonly evictedStreams = new Set<string>();
  /** Frames dropped because their stream was evicted. Reported in the list refresh log. */
  private evictedFrames = 0;
  /** Live reasoning per session: the attempt it belongs to and the text so far.
   *  Reset on `start`, cleared on `end`; a chunk from a stale attempt is ignored. */
  private readonly deltas = new Map<string, { attemptId: string; text: string; reasoning: string; index: number }>();
  /** How many approval/question waterfalls the host has pushed since start. */
  private waterfalls = 0;

  /**
   * The host's id for this worker's `$events` connection.
   *
   * <p>Answering a waterfall requires naming the client the host delivered it to.
   * The phone has its own `$events` connection with a different id, so an answer
   * carrying the phone's id is silently dropped as "not delivered to this client" —
   * which is exactly how an approval card comes back after every reconnect. The
   * plugin answers with its own id, and then the answer is always accepted.
   */
  private eventsClientId = '';

  /** The `$events` client id, or '' before the host's `ready` frame arrives. */
  get eventsClient(): string {
    return this.eventsClientId;
  }
  /** sessionId -> streamId; membership defines the top-K follow set. */
  private followed = new Map<string, string>();
  /** Serialized meta per session, so an unchanged title is not re-encrypted every minute. */
  private lastMeta = new Map<string, string>();

  constructor(private readonly options: CollectorOptions) {}

  start(): void {
    if (this.stopping) return;
    const interval = this.options.intervalMs ?? 60000;
    this.listTimer = setInterval(() => void this.refreshSessions(), interval);
    this.listTimer.unref();
    void this.refreshSessions();
    this.connect();
  }

  async stop(): Promise<void> {
    this.stopping = true;
    clearInterval(this.listTimer);
    clearTimeout(this.kickTimer);
    clearTimeout(this.retryTimer);
    this.listTimer = this.kickTimer = this.retryTimer = undefined;
    this.socket?.close(1000, 'collector-stop');
    this.socket = undefined;
  }

  private log(message: string): void {
    this.options.onLog?.(message);
  }

  // ---- session list -> follow set + metadata ----
  private async refreshSessions(): Promise<void> {
    if (this.stopping) return;
    try {
      const items = await this.listSessions();
      // 活体表在过滤之前建：子代理各自是一条会话，`isInternalSession` 会把那些行
      // 整条删掉，之后再想给 `subagentCatalog` 盖「在不在跑」就没数据了。
      const running = runningSessions(items);
      const slim = items
        .filter(item => !isInternalSession(item))
        .map(item => slimSession(item, running));
      for (const item of slim) {
        const id = (item as { sessionId?: unknown }).sessionId;
        if (typeof id !== 'string' || id.length > 128) continue;
        const encoded = JSON.stringify(item);
        if (this.lastMeta.get(id) === encoded) continue;
        // Metadata is what the phone's session list is drawn from, and it is tiny next
        // to a timeline. It rides the same outbox so there is one durability story,
        // not two.
        this.options.outbox.putMeta(id, item);
        this.lastMeta.set(id, encoded);
      }
      const topK = this.options.topK ?? 20;
      const wanted = slim
        .map(i => (i as { sessionId?: unknown }).sessionId)
        .filter((v): v is string => typeof v === 'string')
        .slice(0, topK);
      for (const id of wanted) {
        if (this.followed.has(id)) continue;
        const streamId = `arch-${++this.nextStreamId}`;
        this.followed.set(id, streamId);
        this.streams.set(streamId, id);
        this.sendFollow(streamId, id);
      }
      for (const [id, streamId] of [...this.followed]) {
        if (wanted.includes(id)) continue;
        this.followed.delete(id);
        // Keep `streams` pointing at the session we *think* it belongs to? No: drop the
        // mapping so late frames from the host's still-open stream fall through to
        // `unknown streamId` instead of being filed under whatever session is next.
        this.streams.delete(streamId);
        this.evictedStreams.add(streamId);
      }
      // The follow set is the one thing that decides whether a session is live at all;
      // log it when it changes so "why did this session stop updating" has an answer.
      if (this.evictedFrames > 0) {
        this.log(`dropped ${this.evictedFrames} frame(s) from ${this.evictedStreams.size} unfollowed stream(s)`);
        this.evictedFrames = 0;
      }
    } catch (error) {
      this.log(`collector list refresh failed: ${error instanceof Error ? error.message : 'unknown'}`);
    }
  }

  private listSessions(): Promise<unknown[]> {
    return new Promise((resolve, reject) => {
      void this.options.getCookie().then(async cookie => {
        const origin = new URL(this.options.upstreamOrigin);
        try {
          this.options.metrics?.noteLoopbackAttempt();
          // `agent: false` for the same reason as every other loopback call: the host
          // answers `keep-alive: timeout=5` but the pooled socket only dies once the FIN
          // is read, and the next request then picks a socket the peer already closed.
          const response = await fetch(new URL('/api/session/list', origin), {
            method: 'POST',
            agent: false,
            redirect: 'error',
            signal: AbortSignal.timeout(15000),
            headers: { 'content-type': 'application/json', cookie, host: origin.host, origin: origin.origin },
            body: JSON.stringify({
              type: 'client-request',
              rpcId: `collector-${Date.now()}`,
              method: 'session/list',
              payload: { args: { _request: {} } },
            }),
          } as RequestInit & { agent: false });
          if (!response.ok) throw new Error(`list-${response.status}`);
          // The host answers RPC-style: `{result:{ok:true,value:{items:[…]}}}`. Reading
          // `body.items` here would silently yield "no sessions" against a real host,
          // which is indistinguishable from an idle desktop.
          const body = (await response.json()) as { result?: { ok?: unknown; value?: { items?: unknown } } };
          resolve(body.result?.ok === true && Array.isArray(body.result.value?.items) ? body.result.value.items : []);
        } catch (error) {
          this.options.metrics?.noteLog(`loopback POST /api/session/list failed: ${error instanceof Error ? error.message : 'unknown'}`);
          reject(error);
        }
      }, reject);
    });
  }

  // ---- loopback follow mux ----
  private connect(): void {
    if (this.stopping) return;
    const url = new URL(this.options.upstreamOrigin);
    url.protocol = 'ws:';
    url.pathname = '/api/remote.mux';
    void this.options.getCookie().then(cookie => {
      if (this.stopping) return;
      // The host sends a whole `snapshot` in ONE frame: up to `maxMessages: 40`
      // messages, and the size policy only runs **after** the frame arrives. So a
      // snapshot carrying a few large tool outputs easily passes the old 4 MiB cap —
      // 40 x 100 KiB is already 4 MiB. Exceeding `maxPayload` is a 1009 close, which
      // this side answers with a reconnect, and the same snapshot kills the next
      // connection too: the collector spins while every session goes stale.
      //
      // The cap has to sit above "one maximum record" (the host can legitimately send
      // a payload the size policy will later offload), so it is derived from the
      // policy's own ceiling rather than guessed.
      const ws = new WebSocket(url, {
        headers: { cookie },
        perMessageDeflate: false,
        handshakeTimeout: 15000,
        maxPayload: SYNC_V2.hardCeiling + 4 * 1024 * 1024,
      });
      this.socket = ws;
      ws.on('error', error => this.log(`mux error ${error instanceof Error ? error.message : 'unknown'}`));
      ws.once('open', () => {
        this.reconnectFailures = 0;
        // Stream ids are scoped to one connection: a fresh socket has none of the old
        // streams, so nothing can arrive for an evicted id any more. Clearing here is
        // what keeps the set from growing for the life of the worker.
        this.evictedStreams.clear();
        this.evictedFrames = 0;
        // `$events` first: it is what turns "the list changed" into a refresh within
        // seconds instead of at the next poll.
        ws.send(JSON.stringify({ type: 'open', streamId: 'arch-events', endpoint: '$events', payload: { args: {} } }));
        for (const [id, streamId] of this.followed) this.sendFollow(streamId, id);
      });
      ws.on('message', data => this.onFrame(data.toString('utf8')));
      ws.once('close', (code, reason) => {
        this.socket = undefined;
        this.log(`mux closed code=${code} reason=${reason.toString().slice(0, 80)}`);
        if (!this.stopping) this.scheduleReconnect('mux-closed');
      });
    }, error => {
      this.log(`mux cookie rejected: ${error instanceof Error ? error.message : 'unknown'}`);
      this.scheduleReconnect('cookie-failed');
    });
  }

  /**
   * Every host frame is `{type:'item', streamId, value}` — including the `$events`
   * substream, whose `value` is `{type:'emit'|'waterfall'|'cancel'|'ready'}` rather
   * than a timeline item.
   *
   * <p>Getting that shape wrong fails silently. An earlier version looked for a
   * top-level `type:'emit'` and never matched, and looked the `$events` streamId up
   * in `this.streams` (where it is never registered), so every frame of it returned
   * early. The `api-session/*` list kick — the whole reason `$events` is opened
   * first — therefore never fired: a retitled session only showed up at the next
   * 60 s poll instead of within a second.
   */
  private onFrame(text: string): void {
    let frame: Frame;
    try { frame = JSON.parse(text) as Frame; } catch { return; }
    if (!frame || typeof frame !== 'object') return;
    if (frame.type !== 'item' || typeof frame.streamId !== 'string') return;
    const value = frame.value as Record<string, unknown> | null | undefined;
    if (!value || typeof value !== 'object') return;
    // `$events` is app-level, not session-scoped: handled before the follow-stream
    // lookup, which would otherwise look up an id that is deliberately not there.
    if (frame.streamId === EVENTS_STREAM) { this.onEventsFrame(value); return; }
    const sessionId = this.streams.get(frame.streamId);
    if (!sessionId) {
      // A frame from a stream we stopped following. It used to be re-attributed to
      // whatever session happened to get that id next; now the id is never reused, so
      // the only correct answer is to drop it — loudly enough to be visible in the log
      // when it happens, because it means the host is still streaming something we no
      // longer want.
      if (this.evictedStreams.has(frame.streamId)) this.evictedFrames++;
      return;
    }
    if (value.type === 'snapshot') this.onSnapshot(sessionId, value);
    else if (value.type === 'event') this.onEvent(sessionId, value);
    else if (value.type === 'assistant-stream') this.onStream(sessionId, value);
  }

  /**
   * `$events` carries the list kick plus the approval / question waterfalls.
   *
   * <p>Waterfalls are counted, not ingested: they carry no `seq` (the turn has not
   * settled) and the host re-delivers them on every new `$events` connection, so
   * numbering them into the archive would mint a fresh row per reconnect. Phones
   * still receive them over the v1 path, and the Room `interaction_ledger` is what
   * makes a re-delivery idempotent — keyed on the host's `eventId`, never a
   * timestamp. Counting them keeps "approvals are not on the v2 timeline" visible
   * in the log instead of looking like the host never asked anything.
   */
  private onEventsFrame(value: Record<string, unknown>): void {
    if (value.type === 'ready') {
      if (typeof value.clientId === 'string') this.eventsClientId = value.clientId;
      return;
    }
    if (value.type === 'emit') {
      const type = value.event;
      if (typeof type === 'string' && LIST_KICK.test(type) && !this.kickTimer) {
        // A streaming turn emits dozens of these. Refresh on the trailing edge: the
        // records themselves do not wait for this timer.
        this.kickTimer = setTimeout(() => {
          this.kickTimer = undefined;
          void this.refreshSessions();
        }, this.options.kickDebounceMs ?? 500);
        this.kickTimer.unref();
      }
      return;
    }
    if (value.type === 'waterfall' || value.type === 'cancel') {
      this.waterfalls++;
      if (this.waterfalls === 1) this.log('approval waterfall on $events: forwarding over the control channel');
      // 审批/提问瀑布**只有这一条**到手机的路。v1 时代手机自己开一条 `$events` WS 去
      // 接宿主重投，中继的 v1 路由删掉之后那条路没了；插件是唯一同时握着宿主 `$events`
      // 与手机控制通道的进程，所以必须由它转。转的是原样的 payload：卡片刻画器在手机
      // 上，插件不该多一层解析（多一层就多一处会跟宿主协议脱节的地方）。
      this.options.hub.publishInteraction({ type: 'INTERACTION', kind: value.type, payload: value });
      return;
    }
  }

  private onSnapshot(sessionId: string, value: Record<string, unknown>): void {
    const records = Array.isArray(value.records) ? value.records : [];
    let added = 0;
    for (const entry of records) {
      const event = (entry as { event?: unknown } | null)?.event;
      if (!event || typeof event !== 'object') continue;
      if (this.options.hub.ingest(sessionId, event as { type?: unknown; seq?: unknown; data?: unknown })) added++;
    }
    if (added) this.log(`snapshot ${sessionId.slice(0, 12)} +${added} records (${records.length} seen)`);
  }

  private onEvent(sessionId: string, value: Record<string, unknown>): void {
    const event = value.event;
    if (!event || typeof event !== 'object') return;
    this.options.hub.ingest(sessionId, event as { type?: unknown; seq?: unknown; data?: unknown });
  }

  /** Reasoning as it is being written.
   *
   *  <p>These frames carry no `seq` — the turn they belong to has not settled, so
   *  there is nothing to number yet. They go to the hub as live-only deltas: phones
   *  currently focused see the text grow, nothing is archived, and the settled
   *  `assistant/message` that follows is what becomes a timeline row. Dropping them
   *  instead (what v1's archiver did) left the phone showing a static "deep thinking"
   *  for the whole minute it took the model to write — the exact symptom the
   *  "must look alive" rule exists to prevent.
   */
  private onStream(sessionId: string, value: Record<string, unknown>): void {
    const frame = value.frame as Record<string, unknown> | undefined;
    if (!frame || typeof frame !== 'object') return;
    const attemptId = typeof frame.attemptId === 'string' ? frame.attemptId : '';
    const kind = frame.type;
    if (kind === 'start') {
      this.deltas.set(sessionId, { attemptId, text: '', reasoning: '', index: -1 });
      this.options.hub.noteDelta(sessionId);
      return;
    }
    if (kind === 'end') {
      const state = this.deltas.get(sessionId);
      if (!attemptId || state?.attemptId === attemptId) this.deltas.delete(sessionId);
      return;
    }
    if (kind !== 'chunk') return;
    const state = this.deltas.get(sessionId);
    // A chunk from an older attempt is a leftover: honouring it would splice two
    // different answers together.
    if (!state || (attemptId && state.attemptId !== attemptId)) return;
    // The host numbers chunks and may repeat one; an index that did not advance is
    // the same text arriving twice.
    const index = typeof frame.index === 'number' ? frame.index : state.index + 1;
    if (index <= state.index) return;
    state.index = index;
    const chunk = frame.chunk as Record<string, unknown> | undefined;
    if (chunk && typeof chunk === 'object') {
      const reasoning = deltaReasoning(chunk);
      if (reasoning) {
        state.reasoning += reasoning;
        this.options.hub.ingestReasoning(sessionId, state.reasoning);
      }
      const text = deltaText(chunk);
      if (text) state.text += text;
    }
    this.options.hub.ingestDelta(sessionId, state.text);
  }

  private sendFollow(streamId: string, sessionId: string): void {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) return;
    // The request shape must match what the phone/host already agree on. The host
    // rejects other combinations with an empty error frame, and that failure is silent
    // from here — the session simply never produces records.
    this.socket.send(JSON.stringify({
      type: 'open',
      streamId,
      endpoint: 'session/follow',
      payload: { args: { request: { address: { kind: 'session', sessionId }, assistantStream: true, maxMessages: 40, turnWindow: { minMessages: 16, minTurns: 2 } } } },
    }));
  }

  /** Bounded exponential backoff with jitter. The outbox is untouched across a
   *  reconnect: unacked rows simply stay pending, which is the whole point of making it
   *  durable instead of replaying snapshots to reconstruct gaps. */
  private scheduleReconnect(reason: string): void {
    if (this.stopping) return;
    const base = Math.min((this.options.reconnectBaseMs ?? 1000) * 2 ** this.reconnectFailures, this.options.maxReconnectMs ?? 30000);
    this.reconnectFailures++;
    const delay = Math.round(base * (0.8 + Math.random() * 0.4));
    this.log(`mux reconnect in ${delay}ms (${reason}, attempt ${this.reconnectFailures})`);
    clearTimeout(this.retryTimer);
    this.retryTimer = setTimeout(() => this.connect(), delay);
    this.retryTimer.unref();
  }

  /** Page budget the hub uses when a phone focuses a session: one page, same ceiling
   *  the relay applies, so a replay never exceeds what a sync page would have cost. */
  get replayBudget(): number {
    return SYNC_V2.pageByteBudget;
  }
}

/** Human-readable text of one streaming chunk.
 *
 *  <p>The host sends richer chunks than plain text: `reasoning-delta` carries the
 *  thinking, `text-delta` the visible answer, and tool deltas their arguments. Only
 *  the first two belong in the live preview — a tool argument streamed into it would
 *  flash raw JSON in the "thinking" line.
 */
export function deltaText(chunk: Record<string, unknown>): string {
  // 只有 `text-delta` 是**正文**。早先这里先看 `chunk.text`，于是 `reasoning-delta`
  // 的思考文本也被当成正文塞进同一个缓冲，手机上表现为「思考过程直接铺在聊天页、
  // 折叠行里反而什么都没有」（2026-10-30 反馈）。判据必须是 type，不是字段在不在：
  // 两种 chunk 都带 `text` 字段，区别只在 type。
  const type = typeof chunk.type === 'string' ? chunk.type : '';
  if (type === 'reasoning-delta' || type === 'thinking-delta') return '';
  if (type === 'tool-delta' || type === 'tool-call-delta') return '';
  const direct = chunk.text;
  if (typeof direct === 'string') return direct;
  return contentReasoning([chunk]);
}

/** 思考增量：与 {@link deltaText} 互补，只认 reasoning 类 chunk。 */
export function deltaReasoning(chunk: Record<string, unknown>): string {
  const type = typeof chunk.type === 'string' ? chunk.type : '';
  if (type === 'reasoning-delta' || type === 'thinking-delta') {
    const text = chunk.text;
    if (typeof text === 'string') return text;
    const delta = chunk.delta;
    if (typeof delta === 'string') return delta;
  }
  // 没有 delta 形态时退回 content part：`contentReasoning` 吃的是 content 数组，
  // 所以有 `chunk.content` 就用它，否则把 chunk 自己当一个 part 试。
  const content = Array.isArray(chunk.content) ? chunk.content : [chunk];
  return contentReasoning(content);
}
