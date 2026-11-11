/** TimelineRecord — the single unit of conversation storage on all three sides.
 *
 *  Before v2 every hop invented its own shape: the plugin pushed
 *  `{sessionId, seq, record}` blobs, the relay stored them as opaque ciphertext,
 *  and the phone re-guessed "what is this event" from the raw DSH event type at
 *  render time. Three parsers, three sets of bugs, and no field could be indexed.
 *
 *  A TimelineRecord is the plugin's *classification* of one host event plus the
 *  raw payload, verbatim. The classification (`kind`) is what the relay indexes
 *  and what the phone's projector switches on for coarse decisions; `payload` is
 *  the untouched host event, so a new event type needs no relay or schema change
 *  and reaches the phone exactly as the host produced it. That is the whole
 *  reason `filter.ts` can stop keeping a whitelist of "events worth forwarding":
 *  there is no longer a hop that has to understand the payload to carry it.
 *
 *  `seq` is assigned by the plugin, which is the only component that sees the
 *  host stream. It is strictly increasing per session but never assumed
 *  contiguous — `prevSeq` is the witness that a gap exists, and the phone's
 *  watermark may only advance across a contiguous transaction (§4.3).
 */

/** Coarse classification. Deliberately small: the fine-grained event type lives
 *  in `payload.type`, so adding one does not require a relay migration. */
export const RECORD_KINDS = [
  'USER_INPUT',
  'STEP_THINKING',
  'STEP_TOOL',
  'ASSISTANT_TEXT',
  'APPROVAL_REQ',
  'APPROVAL_RES',
  'QUESTION_REQ',
  'QUESTION_RES',
  'TURN_END',
  /** The host's pending-message queue changed (`agent/inbox/spliced`).
   *
   *  Carries a splice (`target` / `start` / `removedCount` / `inserted`), not a
   *  whole queue: the host applies the same splice to its own `inbox`, so the
   *  phone replays it instead of re-fetching `session/projections`. Both sides
   *  end up with the same array without a second round trip.
   *
   *  It is a real session-log event carrying a `seq`, which is why it can be
   *  numbered and archived like anything else. The v1 path could only read the
   *  queue from `/rpc/projections`, and that request failed with ECONNRESET
   *  often enough (4 of 4 in one afternoon) that the cards silently stopped
   *  updating while the host went on queueing.
   */
  'INBOX_SPLICE',
  /** The host rewrote its task list (`todo/write`). Whole-list replacement, not
   *  a delta - the host always sends every item, so the phone replaces too. */
  'TASK_LIST',
  'META_PATCH',
  /** Live-only: producer-side streaming text/reasoning delta. Never archived —
   *  the settled ASSISTANT_TEXT / STEP_THINKING record supersedes it. */
  'STREAM_DELTA',
  /** Producer-side: payload exceeded the hard ceiling and was cut down to head
   *  and tail. Rendered as a visible error row, never silently dropped. */
  'ERROR_OVERSIZED_TRUNCATED',
] as const;
export type RecordKind = (typeof RECORD_KINDS)[number];

export interface RecordStub {
  /** Opaque id the phone exchanges for the full payload at `GET /v2/blobs/:id`. */
  blobId: string;
  blobSize: number;
  /** First ~2 KiB of the payload, enough to paint a readable row while the
   *  blob download is pending or offline. */
  previewHead: string;
}

export interface TimelineRecord {
  sessionId: string;
  seq: number;
  prevSeq: number;
  turnId: string;
  stepId?: string;
  kind: RecordKind;
  timestampMs: number;
  clientMsgId?: string;
  payload: unknown;
  /** Present when the payload was offloaded to a blob (see SYNC_V2). */
  stub?: RecordStub;
  /** Present on ERROR_OVERSIZED_TRUNCATED records. */
  truncated?: { originalBytes: number };
}

/** Wire/storage limits for the v2 sync path. */
export const SYNC_V2 = Object.freeze({
  /** One record at or above this is offloaded to a blob and replaced by a stub. */
  stubThreshold: 256 * 1024,
  /** Nothing above this is stored at all; it is truncated to head+tail. */
  hardCeiling: 10 * 1024 * 1024,
  /** A stub payload must stay under this, or the phone cannot page past it. */
  stubBudget: 2 * 1024,
  /** Head and tail kept by an ERROR_OVERSIZED_TRUNCATED record. */
  truncateKeep: 32 * 1024,
  /** Default page budget for one /v2/sync request. */
  pageByteBudget: 512 * 1024,
  /** Maximum records the relay puts in one page (probe uses limit+1). */
  pageRecords: 240,
  /** A stub record still has to move `nextCursorSeq` forward, so a page of pure
   *  stubs can never look like zero progress. */
  maxSessionsPerRequest: 32,
  /** Plugin-side coalescing window and batch size.
   *
   *  `coalesceMs` 是**首帧延迟**，不是吞吐旋钮：`STREAM_DELTA` 也走同一条 `batch()`，
   *  把它从 50ms 抬到 100ms 只会让流式正文多等一帧（评审 §三.4 拿 RTT 论证吞吐，
   *  方向错了）。`coalesceBytes` 才是帧大小预算——它同时被 `emitRecords()` 当切帧
   *  上限，改它必须同步 `tests/control-v2.test.ts` 的隧道配额护栏。 */
  coalesceMs: 50,
  coalesceBytes: 32 * 1024,
  /** Pulse cadence. The phone's dead-peer threshold is 2 misses = 12 s, but it is
   *  derived on the Kotlin side (`SyncCoordinator.PULSE_DEAD_MS`), not from here. */
  pulseMs: 5000,
});

const isDict = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v);

/** UTF-8 byte size of the serialized payload, without keeping the string. */
export function payloadBytes(record: TimelineRecord): number {
  if (record.stub) return Buffer.byteLength(record.stub.previewHead, 'utf8');
  try {
    const text = JSON.stringify(record.payload);
    return text === undefined ? 0 : Buffer.byteLength(text, 'utf8');
  } catch {
    return 0;
  }
}

/** Approximate on-the-wire size of a record, used for page budgeting. */
export function recordBytes(record: TimelineRecord): number {
  // The envelope itself is ~120 bytes of fixed keys; payload dominates.
  return payloadBytes(record) + 160;
}

/**
 * First `keep` **bytes** of `text`, decoded back to a string without splitting a
 * multi-byte character.
 *
 * <p>Why not `text.slice(0, keep)`: that counts UTF-16 units. CJK is 3 bytes per
 * unit, so a 1 536-unit slice is 4.5 KiB on the wire — three times the budget the
 * caller asked for, and `recordBytes()` (which measures bytes) then disagrees with
 * the policy that produced it.
 */
function utf8Head(text: string, keep: number): string {
  if (keep <= 0) return '';
  const bytes = Buffer.from(text, 'utf8');
  if (bytes.length <= keep) return text;
  // `toString` on a byte-truncated buffer replaces the dangling sequence with U+FFFD;
  // dropping a trailing replacement char keeps the head clean.
  return bytes.subarray(0, keep).toString('utf8').replace(/\uFFFD$/, '');
}

function head(payload: unknown, keep: number): string {
  if (typeof payload === 'string') return payload.slice(0, keep);
  try {
    const text = JSON.stringify(payload);
    return typeof text === 'string' ? text.slice(0, keep) : '';
  } catch {
    return '';
  }
}

function headTail(payload: unknown, keep: number): string {
  if (typeof payload === 'string') {
    return payload.length <= keep * 2
      ? payload
      : `${payload.slice(0, keep)}\n…[truncated ${payload.length - keep * 2} chars]…\n${payload.slice(-keep)}`;
  }
  try {
    const text = JSON.stringify(payload);
    if (typeof text !== 'string' || text.length <= keep * 2) return head(payload, keep * 2);
    return `${text.slice(0, keep)}\n…[truncated ${text.length - keep * 2} chars]…\n${text.slice(-keep)}`;
  } catch {
    return '';
  }
}

/** Applies the size policy to one record before it is stored or shipped.
 *
 *  §invariant 5: nothing is ever dropped silently. A payload too big to move is
 *  either replaced by a stub that points at a downloadable blob, or — past the
 *  hard ceiling — becomes a record that *says* it was truncated, so the phone can
 *  paint "this message was too large" instead of showing a hole in the timeline.
 *
 *  Deterministic in `record.seq`, so the same record always yields the same
 *  blobId and a retried archive push is idempotent.
 *
 *  Serialize once. The obvious implementation calls `payloadBytes()` (which
 *  stringifies the payload) and then stringifies it again to build the preview,
 *  which for a 256 KiB+ tool result means doing that work twice on the hot path.
 */
export function applySizePolicy(
  record: TimelineRecord,
  putBlob?: (blobId: string, payload: unknown) => void,
): TimelineRecord {
  if (record.stub || record.kind === 'STREAM_DELTA') return record;

  // 先序列化一次，计字节，决定策略
  let json: string;
  let bytes: number;
  try {
    json = JSON.stringify(record.payload);
    bytes = Buffer.byteLength(json, 'utf8');
  } catch {
    return record;
  }

  if (bytes < SYNC_V2.stubThreshold) return record;
  if (bytes > SYNC_V2.hardCeiling) {
    return {
      ...record,
      kind: 'ERROR_OVERSIZED_TRUNCATED',
      payload: {
        type: 'error/oversized-truncated',
        originalBytes: bytes,
        head: headTail(record.payload, SYNC_V2.truncateKeep),
      },
      truncated: { originalBytes: bytes },
    };
  }

  const blobId = `${record.sessionId}:${record.seq}`;
  putBlob?.(blobId, record.payload);
  // The preview must fit `stubBudget` **in bytes**, not in UTF-16 units. Slicing the
  // string first and measuring later means a CJK payload (3 bytes/char) produces a
  // ~4.5 KiB head against a 2 KiB budget, so `recordBytes()` over-reports and the
  // phone's page budget is spent on previews that were supposed to be tiny.
  // `Buffer.from` truncates at a byte offset, which can land mid-codepoint; decoding
  // with `toString('utf8')` then yields U+FFFD for that one trailing char, so trim
  // back to the last complete sequence.
  const previewHead = utf8Head(json, SYNC_V2.stubBudget - 512);
  return {
    ...record,
    payload: { type: 'stub/payload', blobId, blobSize: bytes },
    stub: { blobId, blobSize: bytes, previewHead },
  };
}

/** True when `record` continues the timeline at `after` without a gap. */
export function follows(record: TimelineRecord, after: number): boolean {
  return record.prevSeq === after || record.seq === after + 1;
}

export function isTimelineRecord(value: unknown): value is TimelineRecord {
  if (!isDict(value)) return false;
  return typeof value.sessionId === 'string'
    && typeof value.seq === 'number'
    && typeof value.prevSeq === 'number'
    && typeof value.turnId === 'string'
    && typeof value.timestampMs === 'number'
    && (RECORD_KINDS as readonly string[]).includes(value.kind as string)
    && 'payload' in value;
}
