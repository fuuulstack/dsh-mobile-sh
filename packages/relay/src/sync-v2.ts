/** /v2/sync — paging and loop detection.
 *
 *  Split out of `server.ts` because this is the one piece of relay code with real
 *  algorithmic content: how a byte budget is spent across several sessions, how
 *  "there is more" is decided without a second COUNT(*), and how a client that
 *  stops making progress is told apart from a client that is simply busy.
 *
 *  Two rules from the design drive everything here:
 *
 *  <ul>
 *   <li><b>Empty result ≠ no work</b> (§invariant 8). A page that is cut short must
 *       still advance `nextCursorSeq` past whatever it did return, and must say
 *       `hasMore: true`. A cursor that does not move turns the phone's retry loop
 *       into a spin, which is exactly how "sync is stuck at 26/29 empty pages"
 *       happened in v1.</li>
 *   <li><b>Named sessions own the budget</b> (bug B9). A foreground request for one
 *       session must not have its page eaten by twenty other sessions that happened
 *       to be newer. The requester's priority decides the order and the budget.</li>
 *  </ul>
 *
 *  Everything here is synchronous on purpose: `node:sqlite` is a synchronous API and
 *  the relay is single-threaded, so an `async` page loop would only add interleaving
 *  points where another request could observe a half-updated archive.
 */

import { SYNC_V2 } from '../../core/src/index.js';
import type { Store } from './store.js';

export type SyncMode = 'FOREGROUND_SINGLE' | 'BACKGROUND_BATCH';

export interface SyncSessionRequest {
  sessionId: string;
  afterSeq: number;
  /** Higher wins. The foreground request for a session must outrank the
   *  background sweep, or opening a chat waits behind the cold-start backfill. */
  priority: number;
}

export interface SyncPageResult {
  sessionId: string;
  reqAfterSeq: number;
  nextCursorSeq: number;
  archiveCoveredSeq: number;
  hasMore: boolean;
  stopReason: 'TAIL_COMPLETE' | 'BUDGET_EXHAUSTED' | 'UNKNOWN_SESSION';
  /** Raw JSON text, one element per record, already decrypted and never parsed. */
  records: string[];
  bytes: number;
}

/** Parses and clamps one request body. Deliberately permissive about unknown keys
 *  (§schema evolution: never `additionalProperties: false`), strict about the three
 *  fields the paging depends on. */
export function readSyncRequest(raw: unknown): {
  mode: SyncMode;
  pageByteBudget: number;
  sessions: SyncSessionRequest[];
} {
  const dict = (typeof raw === 'object' && raw !== null ? raw : {}) as Record<string, unknown>;
  const mode: SyncMode = dict.mode === 'BACKGROUND_BATCH' ? 'BACKGROUND_BATCH' : 'FOREGROUND_SINGLE';
  const budget = Number(dict.pageByteBudget);
  const pageByteBudget = Number.isFinite(budget) && budget > 0
    ? Math.min(Math.max(Math.trunc(budget), 16 * 1024), 4 * 1024 * 1024)
    : SYNC_V2.pageByteBudget;
  const list = Array.isArray(dict.sessions) ? dict.sessions : [];
  const sessions: SyncSessionRequest[] = [];
  for (const entry of list.slice(0, SYNC_V2.maxSessionsPerRequest)) {
    if (typeof entry !== 'object' || entry === null) continue;
    const row = entry as Record<string, unknown>;
    if (typeof row.sessionId !== 'string' || !row.sessionId || row.sessionId.length > 128) continue;
    const after = Number(row.afterSeq);
    const priority = Number(row.priority);
    sessions.push({
      sessionId: row.sessionId,
      // A negative or non-numeric cursor means "I have nothing"; it must never be
      // interpreted as "start from the end", which would silently skip history.
      afterSeq: Number.isSafeInteger(after) && after >= 0 ? after : 0,
      priority: Number.isFinite(priority) ? Math.trunc(priority) : 0,
    });
  }
  return { mode, pageByteBudget, sessions };
}

/**
 * Pages one session.
 *
 * <p>The probe reads `pageRecords + 1` rows so `hasMore` is a fact about the data
 * rather than a guess from a short page: exactly-full pages (240 of 240) are the
 * common case for a busy session, and inferring "done" from `rows.length < limit`
 * is what permanently lost record 241 onward in v1.
 *
 * <p>Budget handling differs from v1 in one important way: **a record that does not
 * fit still ends the page with the cursor advanced past everything before it**. v1
 * retried the same cursor forever when a single record exceeded the per-response
 * budget, which is the "one 31 KiB record + 16 KiB idle budget = infinite loop"
 * bug. Here the first record of a page is always emitted (the budget is a target,
 * not a hard cap), and a record above the hard ceiling is replaced by a visible
 * truncation marker so the cursor can move past it.
 */
export function pageSession(
  store: Store,
  hostId: string,
  request: SyncSessionRequest,
  byteBudget: number,
): SyncPageResult {
  const covered = store.archiveCovered(hostId, request.sessionId);
  const empty: SyncPageResult = {
    sessionId: request.sessionId,
    reqAfterSeq: request.afterSeq,
    // Not moving the cursor on an empty page would be correct only if the archive
    // were definitely empty; `covered` says how far it actually reaches, so a phone
    // that is ahead of the archive is told the truth instead of being rewound.
    nextCursorSeq: Math.max(request.afterSeq, Math.min(request.afterSeq, covered)),
    archiveCoveredSeq: covered,
    hasMore: false,
    // Only a session the archive has never seen is "unknown". A phone that has
    // already consumed everything (`afterSeq >= covered`) is *caught up*, and
    // saying UNKNOWN_SESSION there is a lie that sends whoever reads the logs
    // hunting for a missing session that is in fact fully synced.
    stopReason: covered === 0 && request.afterSeq === 0 ? 'UNKNOWN_SESSION' : 'TAIL_COMPLETE',
    records: [],
    bytes: 0,
  };
  const probed = store.timelineSlice(hostId, request.sessionId, request.afterSeq, SYNC_V2.pageRecords + 1);
  if (!probed.length) return empty;

  const overflowed = probed.length > SYNC_V2.pageRecords;
  const page = overflowed ? probed.slice(0, SYNC_V2.pageRecords) : probed;

  const out: string[] = [];
  let spent = 0;
  let lastSeq = request.afterSeq;
  let exhausted = false;
  for (const row of page) {
    let text = row.json;
    let size = row.byteSize > 0 ? row.byteSize : Buffer.byteLength(text, 'utf8');
    // Legacy or hand-written rows can exceed the ceiling the plugin enforces. The
    // relay must not forward 40 MiB into a phone's websocket, and it must not skip
    // the row either (that would be a silent hole). Emit a truncation marker.
    if (size > SYNC_V2.hardCeiling) {
      text = JSON.stringify({
        sessionId: request.sessionId,
        seq: row.seq,
        prevSeq: row.prevSeq,
        turnId: row.turnId,
        kind: 'ERROR_OVERSIZED_TRUNCATED',
        timestampMs: row.timestampMs,
        payload: {
          type: 'error/oversized-truncated',
          originalBytes: size,
          note: 'record exceeded the relay hard ceiling; payload withheld',
        },
        truncated: { originalBytes: size },
      });
      size = Buffer.byteLength(text, 'utf8');
    }
    if (spent > 0 && spent + size > byteBudget) {
      exhausted = true;
      break;
    }
    out.push(text);
    spent += size;
    lastSeq = row.seq;
  }

  // A page that could not take even its first record (a single giant row and a
  // tiny budget) still advances: it emitted that row above, so `out` is non-empty.
  const done = !overflowed && !exhausted;
  const result: SyncPageResult = {
    sessionId: request.sessionId,
    reqAfterSeq: request.afterSeq,
    nextCursorSeq: lastSeq,
    archiveCoveredSeq: covered,
    hasMore: !done,
    stopReason: done ? 'TAIL_COMPLETE' : 'BUDGET_EXHAUSTED',
    records: out,
    bytes: spent,
  };
  return result;
}

/**
 * Orders the requested sessions and spends one budget across them.
 *
 * <p>Priority descending, then the cursor that is furthest behind — a session that
 * needs 500 records is more urgent than one that needs 1, and both are more urgent
 * than a session the phone is already caught up on (which is dropped entirely: an
 * answer with `TAIL_COMPLETE` and zero records is still useful, so caught-up
 * sessions are answered from `covered` without reading any rows).
 */
export function pageRequest(
  store: Store,
  hostId: string,
  mode: SyncMode,
  requests: SyncSessionRequest[],
  pageByteBudget: number,
): { results: SyncPageResult[]; bytes: number } {
  const results: SyncPageResult[] = [];
  if (!requests.length) return { results, bytes: 0 };
  const covered = new Map<string, number>();
  for (const row of store.timelineSummary(hostId)) {
    covered.set(String(row.sessionId), Number(row.latestSeq) || 0);
  }
  const ordered = [...requests].sort((a, b) => {
    if (b.priority !== a.priority) return b.priority - a.priority;
    const gapA = (covered.get(a.sessionId) ?? 0) - a.afterSeq;
    const gapB = (covered.get(b.sessionId) ?? 0) - b.afterSeq;
    if (gapA !== gapB) return gapB - gapA;
    return a.sessionId.localeCompare(b.sessionId);
  });
  // A foreground single-session request gets the whole budget for that session;
  // splitting it across a handful of stragglers would make opening a chat feel
  // like a cold start. A background sweep shares, because its whole job is to make
  // progress on many sessions within the same request.
  const share = mode === 'FOREGROUND_SINGLE'
    ? pageByteBudget
    : Math.max(16 * 1024, Math.floor(pageByteBudget / Math.max(1, Math.min(ordered.length, 8))));
  let remaining = pageByteBudget;
  for (const request of ordered) {
    if (remaining <= 0) {
      // Out of budget. Report the untried sessions honestly rather than omitting
      // them: a missing entry is indistinguishable from "the relay forgot me".
      results.push({
        sessionId: request.sessionId,
        reqAfterSeq: request.afterSeq,
        nextCursorSeq: request.afterSeq,
        archiveCoveredSeq: covered.get(request.sessionId) ?? 0,
        hasMore: true,
        stopReason: 'BUDGET_EXHAUSTED',
        records: [],
        bytes: 0,
      });
      continue;
    }
    const budget = Math.min(share, remaining);
    const page = pageSession(store, hostId, request, budget);
    results.push(page);
    remaining -= page.bytes;
  }
  return { results, bytes: pageByteBudget - remaining };
}

/**
 * Tells "busy" apart from "spinning".
 *
 * <p>The design's rule is that rate limiting must never punish a client that is
 * making progress: a cold start legitimately issues hundreds of pages a minute and
 * v1's flat 600/min cap turned that into minutes of 429s. So there is no request
 * count limit at all here. What is limited is a client asking for *the same
 * cursor* over and over — the only way that happens is a bug (bad `nextCursorSeq`,
 * a client that never persists the page, a projection that cannot advance).
 *
 * <p>Five repeats inside ten seconds is already far more than a healthy client
 * needs (a healthy client's cursor moves on every request), and the block lasts
 * five seconds, which is long enough for a retry storm to die but short enough that
 * a genuine one-off hiccup is not punished for a minute.
 */
export class SyncLoopGuard {
  private readonly seen = new Map<string, { afterSeq: number; count: number; at: number }>();
  private readonly windowMs: number;
  private readonly limit: number;
  private readonly blockMs: number;

  constructor(windowMs = 10_000, limit = 5, blockMs = 5_000) {
    this.windowMs = windowMs;
    this.limit = limit;
    this.blockMs = blockMs;
  }

  /** Returns the seconds to wait when this request looks like a spin, else 0. */
  note(deviceId: string, sessionId: string, afterSeq: number, now = Date.now()): number {
    const key = `${deviceId}\u0000${sessionId}`;
    const prior = this.seen.get(key);
    if (!prior || prior.afterSeq !== afterSeq || now - prior.at > this.windowMs) {
      this.seen.set(key, { afterSeq, count: 1, at: now });
      return 0;
    }
    const count = prior.count + 1;
    this.seen.set(key, { afterSeq, count, at: now });
    if (count <= this.limit) return 0;
    // Keep the entry so the block persists across the next few asks; resetting the
    // count here would let a spinning client through on every sixth request.
    return Math.ceil(this.blockMs / 1000);
  }

  /** Drops entries that have aged out. Called from the relay's maintenance timer so
   *  the map cannot grow with the number of sessions a device has ever opened. */
  prune(now = Date.now()): void {
    for (const [key, value] of this.seen) {
      if (now - value.at > this.windowMs * 6) this.seen.delete(key);
    }
  }

  size(): number {
    return this.seen.size;
  }
}
