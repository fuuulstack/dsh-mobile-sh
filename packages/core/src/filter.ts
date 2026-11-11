/** Outbound content filters for the phone tunnel.
 *
 *  The phone shows far less than the desktop UI: it renders text bubbles and
 *  nothing else. Reasoning traces, tool payloads (file edits included) and the
 *  harness context records are the bulk of a session stream, so the host plugin
 *  drops them before they reach the relay at all.
 *
 *  Two invariants keep the stream protocol intact:
 *  - frames are never removed, only emptied. The phone discards a live attempt
 *    whose revision is not strictly contiguous, so a reasoning-only chunk has to
 *    survive as a frame with no text.
 *  - envelopes keep their exact key set: `/api/remote.mux` validates them with
 *    exactKeys, so only `value` is rewritten.
 */

export type Omit = 'reasoning' | 'tool' | 'context';

export interface OmitOptions {
  reasoning: boolean;
  tool: boolean;
  context: boolean;
}

type Dict = Record<string, unknown>;

const isDict = (v: unknown): v is Dict => typeof v === 'object' && v !== null && !Array.isArray(v);

/** Events the phone's "model process" panel is built from — **removed in v2**.
 *
 *  This set used to gate `omit.context`: every event not named here collapsed to
 *  `{type, seq, data:{omitted:type}}` before it reached the tunnel. The list was
 *  wrong three times in production and each fix appended one more name —
 *  `tool/call`/`tool/result` rendered as rows that opened an empty sheet,
 *  `todo/write` never reached the task pill, `agent/inbox/spliced` never reached
 *  the queue cards. An allow-list like this cannot be completed by construction:
 *  the host may emit any event type at any time, and the phone's projector is the
 *  only component that knows which ones matter. v2 deletes the mechanism instead
 *  of extending it — structural decisions belong to the projector, and the archive
 *  stores what the host said. What survives here is only the bandwidth choice
 *  (`omit.reasoning`/`omit.tool`) plus one narrow user/message case below.
 */

/** `x-dsh-since: <seq>` — the newest event the phone already holds locally.
 *
 *  DSH has no "follow from seq" request: its follow opening frame always replays a
 *  whole page. The host still has to read that page from the local harness, but the
 *  relay is billed per byte, so the plugin trims the replay down to the part the
 *  phone is missing before it ever reaches the tunnel. The phone only sends this
 *  header when its local cache covers the trimmed prefix (including the process
 *  panels), so dropping every record at or below `since` loses nothing. */
export function parseSince(raw: string | string[] | undefined): number | undefined {
  const text = Array.isArray(raw) ? raw[0] : raw;
  if (typeof text !== 'string' || text.trim() === '') return undefined;
  const value = Number(text);
  return Number.isSafeInteger(value) && value >= 0 ? value : undefined;
}

/** The phone asked for no filtering; used when only `x-dsh-since` is present. */
export const NO_OMIT: OmitOptions = { reasoning: false, tool: false, context: false };

/** `x-dsh-omit: reasoning,tool` — the parts the phone does not want to receive. */
export function parseOmit(raw: string | string[] | undefined): OmitOptions | undefined {
  const text = Array.isArray(raw) ? raw.join(',') : raw;
  if (typeof text !== 'string') return undefined;
  const names = new Set(text.toLowerCase().split(',').map(s => s.trim()).filter(Boolean));
  const picked: OmitOptions = { reasoning: names.has('reasoning'), tool: names.has('tool'), context: names.has('context') };
  return picked.reasoning || picked.tool || picked.context ? picked : undefined;
}

/** Replaces a bulky payload with its size, keeping the field type the reader expects. */
function summarize(value: unknown): unknown {
  if (typeof value === 'string') return JSON.stringify({ omitted: value.length });
  return value;
}

/** Returns the input identity when nothing matched: callers use `result !== input`
 *  to decide whether a frame changed at all. */
function filterContent(value: unknown, omit: OmitOptions, toolOutput = false): unknown {
  if (!Array.isArray(value)) return value;
  let changed = false;
  const out: unknown[] = [];
  for (const part of value) {
    if (!isDict(part)) { out.push(part); continue; }
    const type = part.type;
    if (type === 'reasoning' && omit.reasoning) { changed = true; continue; }
    if (omit.tool && type === 'tool-call') { out.push({ ...part, arguments: summarize(part.arguments) }); changed = true; continue; }
    if (omit.tool && toolOutput && type === 'text' && typeof part.text === 'string') { out.push({ ...part, text: summarize(part.text) }); changed = true; continue; }
    out.push(part);
  }
  return changed ? out : value;
}

/** Filters one conversation event.
 *
 *  <p>v2 removed the whole-event suppression that used to sit at the top of this
 *  function (`omit.context && !PANEL_EVENTS.has(type)` → `{data:{omitted}}`). The
 *  surviving transformations are all *content* choices: drop reasoning parts, clip
 *  tool arguments/output, and blank the payload of injected user/message records
 *  (runtime context, skill catalogues, memory snapshots) while keeping their kind.
 *  Everything else passes through untouched, including event types this file has
 *  never heard of — the relay's job is to carry them, not to judge them. */
export function filterEvent(event: unknown, omit: OmitOptions): unknown {
  if (!isDict(event)) return event;
  const type = typeof event.type === 'string' ? event.type : '';
  const data = event.data;
  if (!isDict(data)) return event;
  let changed = false;
  const next: Dict = { ...data };
  const touch = (key: string, value: unknown) => { next[key] = value; changed = true; };
  if (omit.tool && (type === 'job/registered' || type === 'job/settled') && typeof next.label === 'string' && next.label.length > 120) touch('label', next.label.slice(0, 120));
  if (omit.tool && type === 'tool/call') { const summarized = summarize(next.arguments); if (summarized !== next.arguments) touch('arguments', summarized); }
  if (omit.tool && type === 'tool/result' && isDict(next.message)) {
    const content = filterContent(next.message.content, omit, true);
    if (content !== next.message.content) touch('message', { ...next.message, content });
  }
  if (type === 'assistant/message') {
    // `data.stream` replays the same content the message already carries; the phone
    // streams from live frames and reads the final text from the message, so the
    // replay copy is pure duplication once anything is filtered.
    if ((omit.reasoning || omit.tool) && next.stream !== undefined) { delete next.stream; changed = true; }
    if (isDict(next.message)) {
      const content = filterContent(next.message.content, omit);
      if (content !== next.message.content) touch('message', { ...next.message, content });
    }
    if (next.content !== undefined) {
      const content = filterContent(next.content, omit);
      if (content !== next.content) touch('content', content);
    }
  }
  if (type === 'user/message') {
    // Runtime context, skill catalogs and memory snapshots are written back as user
    // messages. The bulk sits in `source`, not `content`: the skill catalog alone
    // carries every installed skill description. Keep the kind so the phone can still
    // tell injected records from typed input, drop the payload.
    if (omit.context) {
      const source = next.source;
      if (isDict(source) && typeof source.kind === 'string' && source.kind !== 'user') {
        touch('content', []);
        touch('source', { kind: source.kind });
      }
    }
    if (next.content !== undefined) {
      const content = filterContent(next.content, omit);
      if (content !== next.content) touch('content', content);
    }
  }
  return changed ? { ...event, data: next } : event;
}

function filterFrame(frame: unknown, omit: OmitOptions): unknown {
  if (!isDict(frame) || frame.type !== 'chunk') return frame;
  const chunk = frame.chunk;
  if (!isDict(chunk)) return frame;
  if (omit.reasoning && chunk.type === 'reasoning-delta') return { ...frame, chunk: { ...chunk, text: '' } };
  if (omit.tool && chunk.type === 'tool-call-delta') return { ...frame, chunk: { ...chunk, argumentsDelta: '' } };
  return frame;
}

/** Returns the input identity when neither trimming nor filtering changed anything.
 *  Exported for the host plugin's archiver, which reuses the snapshot trimming when
 *  replays arrive over its own loopback follow streams. */
export function filterSnapshot(value: Dict, omit: OmitOptions, since?: number): Dict {
  const next: Dict = { ...value };
  let changed = false;
  if (Array.isArray(value.records)) {
    // `since` is the newest event the phone already has on disk, process panels
    // included. The host had to read the whole page from the local harness either
    // way, but only the tail is worth paying relay egress for. Records stay
    // contiguous, so the phone can detect a gap.
    const kept = since === undefined ? value.records
      : value.records.filter(r => !(isDict(r) && isDict(r.event) && typeof r.event.seq === 'number' && r.event.seq <= since));
    if (kept.length !== value.records.length) changed = true;
    const mapped = kept.map(r => {
      if (!isDict(r) || !isDict(r.event)) return r;
      const event = filterEvent(r.event, omit);
      return event === r.event ? r : { ...r, event };
    });
    for (let i = 0; i < mapped.length; i++) if (mapped[i] !== kept[i]) { changed = true; break; }
    next.records = mapped;
  }
  const baseline = value.assistantStream;
  if (isDict(baseline)) {
    const active = baseline.activeAttempt;
    // The live attempt replays as `[{time, chunk}]`; empty the reasoning and tool
    // deltas so the phone rebuilds the same text it would have streamed.
    if (isDict(active) && Array.isArray(active.stream)) {
      const source = active.stream as unknown[];
      const stream = source.map(e => {
        if (!isDict(e)) return e;
        const chunk = filterChunk(e.chunk, omit);
        return chunk === e.chunk ? e : { ...e, chunk };
      });
      const touched = stream.some((e, i) => e !== source[i]);
      if (touched) { next.assistantStream = { ...baseline, activeAttempt: { ...active, stream } }; changed = true; }
    }
  }
  return changed ? next : value;
}

function filterChunk(chunk: unknown, omit: OmitOptions): unknown {
  if (!isDict(chunk)) return chunk;
  if (omit.reasoning && chunk.type === 'reasoning-delta') return { ...chunk, text: '' };
  if (omit.tool && chunk.type === 'tool-call-delta') return { ...chunk, argumentsDelta: '' };
  return chunk;
}

/** Rewrites one `/api/remote.mux` text frame, preserving its exact key set.
 *  A rewritten frame is used whenever a rule matched, even if the JSON came out
 *  longer (a summarized stub can beat a short original by a few bytes): `omit`
 *  is a content decision, not a bandwidth heuristic. */
export function filterItemText(text: string, omit: OmitOptions, since?: number): string {
  let message: unknown;
  try { message = JSON.parse(text); } catch { return text; }
  if (!isDict(message) || message.type !== 'item') return text;
  const value = message.value;
  if (!isDict(value)) return text;
  let next: unknown;
  if (value.type === 'snapshot') {
    next = filterSnapshot(value, omit, since);
    if (next === value) return text;
  } else if (value.type === 'event') {
    const event = filterEvent(value.event, omit);
    if (event === value.event) return text;
    next = { ...value, event };
  } else if (value.type === 'assistant-stream') {
    const frame = filterFrame(value.frame, omit);
    if (frame === value.frame) return text;
    next = { ...value, frame };
  } else return text;
  return JSON.stringify({ ...message, value: next });
}

/** Buffer form used by the message bridge; non-text frames pass through untouched. */
export function filterItemBytes(bytes: Buffer, binary: boolean, omit: OmitOptions, since?: number): Buffer {
  if (binary) return bytes;
  const text = bytes.toString('utf8');
  const next = filterItemText(text, omit, since);
  return next === text ? bytes : Buffer.from(next, 'utf8');
}

/** Target size of one snapshot batch on the wire. */
export const SNAPSHOT_BATCH = 24 * 1024;

/** Rough UTF-8 size of a JSON value without serializing it: chunk batches are
 *  budgeted by estimate, the exact wire size only matters when the batch is
 *  wrapped. 1.5× the UTF-16 length bounds ASCII exactly and CJK comfortably. */
function roughSize(v: unknown): number {
  if (typeof v === 'string') return v.length * 1.5 + 2;
  if (typeof v === 'number' || typeof v === 'boolean') return 8;
  if (v === null || v === undefined) return 4;
  if (Array.isArray(v)) { let n = 2; for (const e of v) n += roughSize(e) + 1; return n; }
  if (isDict(v)) { let n = 2; for (const [k, e] of Object.entries(v)) n += k.length + 4 + roughSize(e) + 1; return n; }
  return 8;
}

/**
 * Splits an oversized snapshot into a `snapshot-begin` / `snapshot-records`… /
 * `snapshot-end` sequence.
 *
 * A snapshot arrives as a single WebSocket message, so the phone has nothing to
 * paint until its last byte lands. Measured on a 509 KB session over a ~550 ms /
 * 5 %-loss link that is ~50 s of blank screen — far past the app's first-screen
 * watchdog, which then reports a failure that is not one. The byte count is the
 * same either way; re-emitting the records as several messages costs no extra
 * egress and lets the phone paint each batch as it arrives and say how much is
 * still coming.
 *
 * Returns `undefined` when the frame is not a snapshot, or already fits in one
 * message, so callers keep the single-message path unchanged.
 */
export function chunkSnapshotText(text: string, omit: OmitOptions, since?: number, budget = SNAPSHOT_BATCH): string[] | undefined {
  let message: unknown;
  try { message = JSON.parse(text); } catch { return undefined; }
  if (!isDict(message) || message.type !== 'item') return undefined;
  const value = message.value;
  if (!isDict(value) || value.type !== 'snapshot') return undefined;
  const filtered = filterSnapshot(value, omit, since);
  const records = Array.isArray(filtered.records) ? filtered.records : [];
  // A snapshot small enough to arrive in one piece is not worth splitting: one
  // message is cheaper than several and the phone paints it in a single pass.
  if (records.length < 2 || Buffer.byteLength(text) <= budget) return undefined;
  const wrap = (v: Dict) => JSON.stringify({ ...message, value: v });
  const head: Dict = {};
  for (const [key, entry] of Object.entries(filtered)) {
    if (key !== 'records' && key !== 'assistantStream') head[key] = entry;
  }
  const total = records.length;
  const out: string[] = [wrap({ ...head, type: 'snapshot-begin', total })];
  let batch: unknown[] = [], bytes = 0, done = 0;
  const flush = () => {
    if (!batch.length) return;
    done += batch.length;
    out.push(wrap({ type: 'snapshot-records', records: batch, done, total }));
    batch = []; bytes = 0;
  };
  for (const record of records) {
    const size = roughSize(record);
    if (batch.length && bytes + size > budget) flush();
    batch.push(record); bytes += size;
  }
  flush();
  const tail: Dict = { type: 'snapshot-end' };
  if (filtered.assistantStream !== undefined) tail.assistantStream = filtered.assistantStream;
  out.push(wrap(tail));
  return out;
}

/** Buffer form of {@link chunkSnapshotText}: always at least one message. */
export function chunkItemBytes(bytes: Buffer, binary: boolean, omit: OmitOptions, since?: number): Buffer[] {
  if (binary) return [bytes];
  const text = bytes.toString('utf8');
  const parts = chunkSnapshotText(text, omit, since);
  if (parts) return parts.map(part => Buffer.from(part, 'utf8'));
  const next = filterItemText(text, omit, since);
  return [next === text ? bytes : Buffer.from(next, 'utf8')];
}

/** `session/list` and `session/page` answers over plain HTTP get the same treatment
 *  as the tunnel frames: a page of history is as expensive as a snapshot. Both
 *  rewrites are keyed on the upstream path, never on the JSON shape — `search`
 *  also answers with `{items:[…]}` and used to have its `title`/`snippet` slimmed
 *  away because the filter guessed "items means session list". Anything else is
 *  returned byte-for-byte untouched. */
export function filterRpcBody(bytes: Buffer, omit: OmitOptions, rawPath: string): Buffer {
  const path = rawPath.split('?')[0]!;
  if (path !== '/api/session/list' && path !== '/api/session/page') return bytes;
  const text = bytes.toString('utf8');
  let message: unknown;
  try { message = JSON.parse(text); } catch { return bytes; }
  if (!isDict(message)) return bytes;
  const result = message.result;
  if (!isDict(result) || result.ok !== true) return bytes;
  const value = result.value;
  if (!isDict(value)) return bytes;
  if (path === '/api/session/list' && Array.isArray(value.items)) {
    // One entry per session ships the full projection set — forty kilobytes of token
    // usage, subagent timing and inbox state the phone never reads. Keep what the
    // list renders and drop internal/subagent sessions like dsh-mnemon:delegated.
    //
    // 活体表**必须**在过滤之前建：`subagentCatalog` 里的子代理是**自己的会话**，
    // 而 `isInternalSession` 恰好把那些行删掉（`parentSessionId` 非空即内部）。
    // 先算表再过滤，才能给每条子代理盖上「它自己那条会话此刻在不在跑」。
    const running = runningSessions(value.items);
    const items = value.items
      .filter(item => !isInternalSession(item))
      .map(item => slimSession(item, running));
    const next = JSON.stringify({ ...message, result: { ...result, value: { ...value, items } } });
    return Buffer.from(next, 'utf8');
  }
  if (Array.isArray(value.records)) {
    const records = value.records.map(r => {
      if (!isDict(r) || !isDict(r.event)) return r;
      const event = filterEvent(r.event, omit);
      return event === r.event ? r : { ...r, event };
    });
    const next = JSON.stringify({ ...message, result: { ...result, value: { ...value, records } } });
    return Buffer.from(next, 'utf8');
  }
  return bytes;
}

/** Check if a session item is an internal/subagent review session (e.g. dsh-mnemon:delegated). */
export function isInternalSession(item: unknown): boolean {
  if (!isDict(item)) return false;
  if ('parentSessionId' in item && Boolean(item.parentSessionId)) return true;
  if (item.origin === 'subagent') return true;

  const projections = item.projections;
  const values = isDict(projections) ? (isDict(projections.values) ? projections.values : projections) : undefined;
  const title = (values && typeof values.title === 'string')
    ? values.title
    : (typeof item.title === 'string' ? item.title : '');

  // Filter out titles containing dsh-mnemon:delegated or delegated review
  if (title.includes('dsh-mnemon:delegated') || title.includes('[dsh-mnemon:') || title.includes('delegated review')) {
    return true;
  }
  if (values && isDict(values.subagent)) {
    const ident = values.subagent.identity;
    if (isDict(ident) && typeof ident.label === 'string' && /mnemon|delegat/i.test(ident.label)) {
      return true;
    }
  }
  if (values && isDict(values.mnemonSubagentTokenUsage)) {
    return true;
  }
  return false;
}

/** 这份 `session/list` 响应里，哪些会话正在跑（`sessionId -> running`）。
 *
 *  **必须在过滤之前调用**：`subagentCatalog` 里的子代理各自就是一条会话，而
 *  `isInternalSession` 恰好把那些行整条删掉（`parentSessionId` 非空即内部），
 *  先建表再过滤，才拿得到它们此刻的 `running`。
 *
 *  宿主在列表条目上给的就是布尔 `running`（客户端自己也是这么读的：
 *  `this.scopes.get(childId)?.session.getSnapshot().running ?? false`）。 */
export function runningSessions(items: readonly unknown[]): Map<string, boolean> {
  const running = new Map<string, boolean>();
  for (const item of items) {
    if (!isDict(item)) continue;
    const id = item.sessionId;
    if (typeof id === 'string' && id.length > 0) running.set(id, item.running === true);
  }
  return running;
}

/** 给一条 `subagentCatalog` 条目盖上它自己那条会话的活体状态。
 *
 *  目录条目本身**没有任何状态字段**（只有 `{id, createdAt, mode, label?}`），
 *  所以「跑完了没有」只能从别处拿。桌面端也是这么干的：状态取子代理自己那条
 *  会话的 `running`，且 `mode === 'external'` 的整条跳过（它没有本机会话）。
 *
 *  `external` 这里给 `false` 而不是省略：省略是「不知道」，而 external 是
 *  **知道它不归本机管**，胶囊不该为它亮着。 */
function stampRunning(entry: unknown, running: Map<string, boolean>): unknown {
  if (!isDict(entry) || typeof entry.id !== 'string') return entry;
  if (entry.mode === 'external') return { ...entry, running: false };
  return { ...entry, running: running.get(entry.id) === true };
}

/** Keep only what the session list renders. `parentSessionId`/`origin` stay even
 *  though the list drops internal sessions here: the phone runs its own second-line
 *  filter against exactly these fields, and stripping them upstream left that
 *  defense matching nothing whenever an older plugin regressed. Exported for the
 *  host plugin's archiver, which stores the same slimmed items as list metadata.
 *
 *  `running` 是 `runningSessions()` 建好的活体表（可选）：给了就给
 *  `subagentCatalog` 每条盖章，没给就原样透传（老调用点行为不变）。 */
export function slimSession(item: unknown, running?: Map<string, boolean>): unknown {
  if (!isDict(item)) return item;
  const kept: Dict = {};
  // `snippet` 曾经也留在这里。2026-10-28 按用户要求把整条通道下线：会话首行
  // 预览不该由旁路单独生成（插件算一遍、中继存一份、手机再收一次），那样列表
  // 上的首行会比本地真正拉到的消息更新——「列表里已经看到最新一行，点进去却没有
  // 这条回复」。现在首行一律由手机从本地已落盘的消息现算，列表与正文同源同水位。
  // Everything else in the item stays out (token usage, inbox, subagent timing —
  // that is where the forty kilobytes come from).
  for (const key of ['sessionId', 'parentSessionId', 'origin', 'cwd', 'updatedAt', 'running', 'blank', 'agentAvailable', 'formatStatus'])
    if (key in item) kept[key] = item[key];
  const projections = item.projections;
  if (isDict(projections)) {
    const values = isDict(projections.values) ? projections.values : projections;
    const slimmed: Dict = { title: values.title ?? '' };
    // `modelSelection` and `permissions` are two small objects the composer renders
    // directly: the model chip shows the selected model and effort, the permission chip
    // shows the session's preset. Both used to be dropped here, so the phone always
    // displayed its "default model" placeholder no matter what the session was set to.
    // Everything else in `values` (token usage, context breakdown, inbox, subagent
    // timing) stays out — that is where the forty kilobytes come from.
    if (isDict(values.modelSelection)) slimmed.modelSelection = values.modelSelection;
    if (isDict(values.permissions)) slimmed.permissions = values.permissions;
    // `subagentCatalog` 是这条会话派过的子智能体名单
    // (`{id, createdAt, mode, label?}`)：条数等于真正派过几次、每条约六十字节，
    // 不是 token usage / context breakdown 那种几十 KB 的大块。
    // 留它是为了让手机上那颗「子智能体」胶囊不必依赖 `/rpc/projections` ——
    // 那条路在生产日志里 4/4 都是 ECONNRESET（见 ChatViewModel 的同名注释）。
    //
    // 目录条目**不带状态**，所以列表原样送过去会让胶囊把所有派过的子代理都算成
    // 「在跑」（用户 2026-11-10 报的就是这个）。这里顺手盖上它自己那条会话的
    // `running`，手机才分得出哪些真在跑。
    if (Array.isArray(values.subagentCatalog))
      slimmed.subagentCatalog = running === undefined
        ? values.subagentCatalog
        : values.subagentCatalog.map(entry => stampRunning(entry, running));
    kept.projections = { values: slimmed };
  }
  return kept;
}
