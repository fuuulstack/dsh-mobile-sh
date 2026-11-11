/**
 * Durable archive outbox (v2 §protocol, §invariant 5 & 7).
 *
 * <p>v1 held unacknowledged records in a plain array with a hard cap, and when the
 * cap was hit it either dropped the oldest entries or set a "refetch a snapshot
 * later" flag. Both are the same failure: the record exists in the host, the phone
 * will never see it, and nothing on disk says so. A restart lost everything.
 *
 * <p>v2 writes every record to SQLite before it is offered to the network, and the
 * only transitions are `PENDING → IN_FLIGHT(batch) → ACKED`. A crash mid-batch
 * leaves rows in `IN_FLIGHT`; {@link reset} at startup returns them to `PENDING`,
 * so a record survives a worker kill, a relay outage and a host restart.
 *
 * <p>Why SQLite and not a file: the relay needs batches that are atomic and
 * idempotent, and the plugin needs "how deep is the backlog" cheaply. `node:sqlite`
 * ships with the Node 24 runtime the plugin host already runs (measured: the
 * desktop host reports `process.versions.node === 24.21.0` and `DatabaseSync` works),
 * so this costs no native dependency.
 *
 * <p>Everything here is synchronous on purpose: the outbox is a few hundred bytes
 * per row and the callers are already inside an event-loop turn that just parsed a
 * host frame. Handing it a promise would only add interleaving.
 */
import { DatabaseSync } from 'node:sqlite';
import type { TimelineRecord } from '../../core/src/index.js';

export type OutboxState = 'PENDING' | 'IN_FLIGHT' | 'ACKED';

/** One row as it is handed to the pusher. `payload` is the serialized record. */
export interface OutboxEntry {
  sessionId: string;
  seq: number;
  batchId: string | null;
  payload: string;
}

const SCHEMA = `
CREATE TABLE IF NOT EXISTS archive_outbox(
  session_id TEXT NOT NULL,
  seq INTEGER NOT NULL,
  state TEXT NOT NULL,
  batch_id TEXT,
  payload TEXT NOT NULL,
  bytes INTEGER NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  PRIMARY KEY(session_id, seq)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS outbox_state ON archive_outbox(state, seq);
CREATE TABLE IF NOT EXISTS outbox_meta(
  session_id TEXT PRIMARY KEY,
  meta TEXT NOT NULL,
  updated_at INTEGER NOT NULL
) WITHOUT ROWID;
-- Oversized payloads (> SYNC_V2.stubThreshold) are offloaded here and travel beside
-- their stub record, never inside it. Two reasons: the outbox row keeps the small
-- stub so ordering/watermarks stay cheap, and the relay stores the blob encrypted
-- at rest, which is what lets the phone fetch it later over /v2/blobs/:id.
CREATE TABLE IF NOT EXISTS archive_blobs(
  blob_id TEXT PRIMARY KEY,
  payload TEXT NOT NULL,
  bytes INTEGER NOT NULL,
  created_at INTEGER NOT NULL
) WITHOUT ROWID;
-- "The relay definitely has everything up to seq N for this session."
-- <p>A separate table because ACKED rows are pruned: without it, a pruned row would
-- make the coverage watermark go *backwards*, and the phone — which uses that number
-- to decide whether a gap is recoverable from the relay — would wrongly conclude it
-- is not.
CREATE TABLE IF NOT EXISTS outbox_watermark(
  session_id TEXT PRIMARY KEY,
  seq INTEGER NOT NULL
) WITHOUT ROWID;
`;

export interface OutboxOptions {
  /** `null` keeps everything in memory — used by unit tests and by hosts whose
   *  data directory is read-only. Losing the outbox then is a documented
   *  degradation, not an error, so it must not throw at construction. */
  path?: string | null;
  /** Hard ceiling on stored rows. Past this, new records are refused *loudly*
   *  (`overflowed` goes true and the caller reports a gap) instead of silently
   *  replacing an older confirmed-pending row. */
  maxRows?: number;
}

export class ArchiveOutbox {
  private readonly db: DatabaseSync | null;
  private readonly maxRows: number;
  private overflowed = false;
  private readonly memory = new Map<string, { state: OutboxState; batchId: string | null; payload: string; bytes: number; attempts: number; createdAt: number }>();
  private readonly memoryMeta = new Map<string, string>();
  private readonly memoryBlobs = new Map<string, { payload: string; bytes: number; createdAt: number }>();
  private readonly memoryWatermark = new Map<string, number>();

  constructor(private readonly options: OutboxOptions = {}) {
    this.maxRows = options.maxRows ?? 20000;
    const path = options.path ?? null;
    this.db = path ? new DatabaseSync(path) : null;
    if (this.db) {
      // WAL keeps the flush loop from blocking the control channel's reads; the
      // outbox is written by one producer and read by one pusher, so NORMAL is
      // already durable across process death (only a machine crash can lose it).
      this.db.exec('PRAGMA journal_mode=WAL;PRAGMA synchronous=NORMAL;PRAGMA busy_timeout=2000;');
      this.db.exec(SCHEMA);
    }
  }

  /** Rows a previous run left in `IN_FLIGHT` were never acknowledged — the process
   *  died between "sent" and "the relay answered". Returning them to `PENDING` is
   *  what makes at-least-once delivery true across restarts. */
  reset(): number {
    if (!this.db) {
      let n = 0;
      for (const row of this.memory.values()) {
        if (row.state === 'IN_FLIGHT') { row.state = 'PENDING'; row.batchId = null; n++; }
      }
      return n;
    }
    const result = this.db.prepare("UPDATE archive_outbox SET state='PENDING',batch_id=NULL WHERE state='IN_FLIGHT'").run();
    return Number(result.changes);
  }

  /** Stores one record.
   *
   *  <p>Three outcomes, and the caller must tell them apart: `duplicate` is success
   *  (a snapshot replayed the same event), `full` is the one condition that needs
   *  repair. Collapsing them into a boolean made a replayed snapshot look like an
   *  overflow and an overflow look like a duplicate — which is exactly how a record
   *  goes missing without anyone noticing.
   */
  put(record: TimelineRecord, now = Date.now()): 'stored' | 'duplicate' | 'full' {
    const payload = JSON.stringify(record);
    const bytes = Buffer.byteLength(payload, 'utf8');
    const key = `${record.sessionId}\u0000${record.seq}`;
    if (!this.db) {
      if (this.memory.has(key)) return 'duplicate';
      let live = 0;
      for (const row of this.memory.values()) if (row.state !== 'ACKED') live++;
      if (live >= this.maxRows) { this.overflowed = true; return 'full'; }
      this.memory.set(key, { state: 'PENDING', batchId: null, payload, bytes, attempts: 0, createdAt: now });
      return 'stored';
    }
    const existing = this.db.prepare('SELECT 1 x FROM archive_outbox WHERE session_id=? AND seq=?').get(record.sessionId, record.seq);
    if (existing !== undefined) return 'duplicate';
    // Only unacknowledged rows count toward the ceiling. ACKED rows are bookkeeping
    // (the coverage watermark lives in its own table), so letting them block new
    // records would mean a full outbox could never drain itself: the pusher's ack
    // would free nothing and every later record would be refused forever.
    const count = this.db.prepare("SELECT COUNT(*) c FROM archive_outbox WHERE state!='ACKED'").get() as { c: number } | undefined;
    if ((count?.c ?? 0) >= this.maxRows) { this.overflowed = true; return 'full'; }
    this.db.prepare(`INSERT OR IGNORE INTO archive_outbox(session_id,seq,state,batch_id,payload,bytes,attempts,created_at)
      VALUES(?,?,'PENDING',NULL,?,?,0,?)`).run(record.sessionId, record.seq, payload, bytes, now);
    return 'stored';
  }

  /** What {@link claim} would take, without claiming it.
   *
   *  <p>Needed because the batch id is derived from the batch's contents: the pusher
   *  has to know which rows it is about to send before it can name the batch. Reading
   *  the keys off the `state` index is a plain index scan; nothing is mutated, so a
   *  crash between peek and claim costs nothing.
   */
  peek(limit: number, maxBytes: number): OutboxEntry[] {
    const take = (rows: { session_id: string; seq: number; payload: string; bytes: number }[]) => {
      const out: OutboxEntry[] = [];
      let bytes = 0;
      for (const row of rows) {
        // Same accounting as {@link ArchiveOutbox.claim}: a stub's blob travels in the
        // request, so budgeting on `row.bytes` alone would let a 27 MiB body look like
        // 5 KiB. `peek` runs first and decides the candidate set, so fixing only
        // `claim` would leave the poison batch reachable.
        const cost = row.bytes + this.blobBytesOf(row.payload);
        if (out.length && bytes + cost > maxBytes) break;
        out.push({ sessionId: row.session_id, seq: row.seq, batchId: null, payload: row.payload });
        bytes += cost;
        if (out.length >= limit) break;
      }
      return out;
    };
    if (!this.db) {
      const rows = [...this.memory.entries()]
        .filter(([, row]) => row.state === 'PENDING')
        .sort((a, b) => a[1].createdAt - b[1].createdAt || Number(a[0].split('\u0000')[1]) - Number(b[0].split('\u0000')[1]))
        .slice(0, limit)
        .map(([key, row]) => ({ session_id: key.slice(0, key.indexOf('\u0000')), seq: Number(key.split('\u0000')[1]), payload: row.payload, bytes: row.bytes }));
      return take(rows);
    }
    const rows = this.db.prepare(`SELECT session_id,seq,payload,bytes FROM archive_outbox
      WHERE state='PENDING' ORDER BY created_at ASC, seq ASC LIMIT ?`).all(limit) as
      { session_id: string; seq: number; payload: string; bytes: number }[];
    return take(rows);
  }

  /** Claims up to `limit` pending rows / `maxBytes` for one batch id.
   *
   *  <p>FIFO by insertion (rowid order is unavailable on a WITHOUT ROWID table, so
   *  the ordering key is `(created_at, seq)`): the phone is waiting for a
   *  contiguous tail, and serving newest-first would leave permanent holes below.
   */
  claim(batchId: string, limit: number, maxBytes: number): OutboxEntry[] {
    const take = (rows: { session_id: string; seq: number; payload: string; bytes: number }[]) => {
      const out: OutboxEntry[] = [];
      let bytes = 0;
      for (const row of rows) {
        // A stub record is ~1.5 KiB on disk but its blob rides in the same request.
        // Budgeting on `row.bytes` alone let a batch of three 9 MiB tool results look
        // like 5 KiB, so the request body was 27 MiB and the relay's 4 MiB inflated-body
        // ceiling rejected it — permanently: the rows stay PENDING, the batch id is
        // derived from them, so every retry recomputes the same doomed batch.
        const cost = row.bytes + this.blobBytesOf(row.payload);
        if (out.length && bytes + cost > maxBytes) break;
        out.push({ sessionId: row.session_id, seq: row.seq, batchId, payload: row.payload });
        bytes += cost;
        if (out.length >= limit) break;
      }
      return out;
    };
    if (!this.db) {
      const rows = [...this.memory.entries()]
        .filter(([, row]) => row.state === 'PENDING')
        .sort((a, b) => a[1].createdAt - b[1].createdAt || Number(a[0].split('\u0000')[1]) - Number(b[0].split('\u0000')[1]));
      const picked = take(rows.slice(0, limit).map(([key, row]) => ({
        session_id: key.slice(0, key.indexOf('\u0000')),
        seq: Number(key.split('\u0000')[1]),
        payload: row.payload,
        bytes: row.bytes,
      })));
      for (const entry of picked) {
        const row = this.memory.get(`${entry.sessionId}\u0000${entry.seq}`)!;
        row.state = 'IN_FLIGHT'; row.batchId = batchId;
      }
      return picked;
    }
    const rows = this.db.prepare(`SELECT session_id,seq,payload,bytes FROM archive_outbox
      WHERE state='PENDING' ORDER BY created_at ASC, seq ASC LIMIT ?`).all(limit) as
      { session_id: string; seq: number; payload: string; bytes: number }[];
    const picked = take(rows);
    if (!picked.length) return picked;
    const mark = this.db.prepare("UPDATE archive_outbox SET state='IN_FLIGHT',batch_id=? WHERE session_id=? AND seq=?");
    this.transaction(() => { for (const entry of picked) mark.run(batchId, entry.sessionId, entry.seq); });
    return picked;
  }

  /** The relay accepted this batch. Acknowledged rows are kept for a while so the
   *  latest-seq watermark is cheap to recompute, then pruned. */
  ack(batchId: string): number {
    if (!this.db) {
      let n = 0;
      for (const [key, row] of this.memory) {
        if (row.batchId !== batchId || row.state !== 'IN_FLIGHT') continue;
        row.state = 'ACKED'; n++;
        const sessionId = key.slice(0, key.indexOf('\u0000'));
        const seq = Number(key.slice(key.indexOf('\u0000') + 1));
        if (seq > (this.memoryWatermark.get(sessionId) ?? 0)) this.memoryWatermark.set(sessionId, seq);
      }
      return n;
    }
    // Both statements in ONE transaction. Previously the UPDATE autocommitted and the
    // watermark bump ran in a separate transaction, so a crash in between left rows
    // ACKED while the watermark stayed behind. `MAX(seq) WHERE state='ACKED'` masked
    // that until `prune()` deleted the rows — after which `ackedSeq()` genuinely went
    // backwards and the phone re-pulled records the relay had already pruned.
    let changed = 0;
    this.transaction(() => {
      const sessionIdOf = this.db!.prepare('SELECT session_id s,seq FROM archive_outbox WHERE batch_id=? AND state=\'IN_FLIGHT\'');
      const rows = sessionIdOf.all(batchId) as { s: string; seq: number }[];
      const result = this.db!.prepare("UPDATE archive_outbox SET state='ACKED',batch_id=NULL WHERE batch_id=? AND state='IN_FLIGHT'").run(batchId);
      changed = Number(result.changes);
      const bump = this.db!.prepare(`INSERT INTO outbox_watermark(session_id,seq) VALUES(?,?)
        ON CONFLICT(session_id) DO UPDATE SET seq=MAX(seq,excluded.seq)`);
      for (const row of rows) bump.run(row.s, row.seq);
    });
    return changed;
  }

  /** The push failed: hand the batch back. `attempts` grows so a poison record can
   *  be identified without a separate table. */
  release(batchId: string): number {
    if (!this.db) {
      let n = 0;
      for (const row of this.memory.values()) if (row.batchId === batchId && row.state === 'IN_FLIGHT') { row.state = 'PENDING'; row.batchId = null; row.attempts++; n++; }
      return n;
    }
    const result = this.db.prepare(`UPDATE archive_outbox SET state='PENDING',batch_id=NULL,attempts=attempts+1
      WHERE batch_id=? AND state='IN_FLIGHT'`).run(batchId);
    return Number(result.changes);
  }

  /** Rows still waiting or in flight — the number the UI and metrics report. */
  depth(): { pending: number; inflight: number; acked: number } {
    if (!this.db) {
      let pending = 0, inflight = 0, acked = 0;
      for (const row of this.memory.values()) {
        if (row.state === 'PENDING') pending++;
        else if (row.state === 'IN_FLIGHT') inflight++;
        else acked++;
      }
      return { pending, inflight, acked };
    }
    const row = this.db.prepare(`SELECT
      SUM(state='PENDING') pending,SUM(state='IN_FLIGHT') inflight,SUM(state='ACKED') acked
      FROM archive_outbox`).get() as { pending: number | null; inflight: number | null; acked: number | null } | undefined;
    return { pending: Number(row?.pending ?? 0), inflight: Number(row?.inflight ?? 0), acked: Number(row?.acked ?? 0) };
  }

  /** Highest acknowledged seq per session: the watermark the phone's tail already
   *  covers, and the `sinceSeq` a focus replay can start from. */
  ackedSeq(sessionId: string): number {
    if (!this.db) {
      let max = this.memoryWatermark.get(sessionId) ?? 0;
      for (const [key, row] of this.memory) {
        if (row.state !== 'ACKED') continue;
        if (key.slice(0, key.indexOf('\u0000')) !== sessionId) continue;
        const seq = Number(key.slice(key.indexOf('\u0000') + 1));
        if (seq > max) max = seq;
      }
      return max;
    }
    const row = this.db.prepare(`SELECT MAX(
        COALESCE((SELECT seq FROM outbox_watermark WHERE session_id=?),0),
        COALESCE((SELECT MAX(seq) FROM archive_outbox WHERE session_id=? AND state='ACKED'),0)
      ) m`).get(sessionId, sessionId) as { m: number | null } | undefined;
    return Number(row?.m ?? 0);
  }

  /** Drops acknowledged rows. Called on a timer: a 2000-record session would
   *  otherwise keep its whole history twice (outbox + relay archive).
   *
   *  <p>Why this has to run on a timer at all: the outbox only counts rows that are
   *  **not** ACKED toward its 20 000-row ceiling (see {@link ArchiveOutbox.put}), so
   *  unpruned ACKED rows do not block anything — they just grow forever. Measured on
   *  this machine: 10 246 ACKED rows / 27 MiB after three days, i.e. ~9 MiB a day,
   *  ~3.3 GiB a year, all of it dead weight that the relay already holds.
   *
   *  <p>Two things it deliberately does **not** touch: `outbox_watermark` (the
   *  coverage watermark lives in its own table precisely so pruning cannot rewind it)
   *  and rows that are still PENDING/IN_FLIGHT.
   *
   *  <p>A blob row is dropped only when its stub record is gone too. Deleting blobs
   *  by `created_at` alone would orphan a stub whose record is still waiting to be
   *  pushed, and the pusher would then send a stub pointing at nothing.
   *
   *  @param ackedOlderThan how long an ACKED row must have been sitting there.
   *  @returns rows deleted from `archive_outbox`.
   */
  prune(ackedOlderThan: number, now = Date.now()): number {
    const cutoff = now - ackedOlderThan;
    if (!this.db) {
      let n = 0;
      for (const [key, row] of this.memory) {
        if (row.state === 'ACKED' && row.createdAt < cutoff) { this.memory.delete(key); n++; }
      }
      for (const id of this.memoryBlobKeys()) {
        // `${sessionId}:${seq}` — split on the LAST colon, session ids can contain any.
        const at = id.lastIndexOf(':');
        const key = at < 0 ? null : `${id.slice(0, at)}\u0000${id.slice(at + 1)}`;
        if (key === null || !this.memory.has(key)) this.memoryBlobs.delete(id);
      }
      return n;
    }
    let deleted = 0;
    this.transaction(() => {
      const result = this.db!.prepare("DELETE FROM archive_outbox WHERE state='ACKED' AND created_at<?").run(cutoff);
      deleted = Number(result.changes);
      // Blobs follow their records: a payload whose stub row is gone is dead weight.
      // The join key is the record key, not `created_at` alone — a blob whose record is
      // still PENDING/IN_FLIGHT must survive, or the pusher ships a stub pointing at
      // nothing. `blob_id` is `${sessionId}:${seq}`, so it is reconstructed from the
      // record row rather than stored twice.
      this.db!.prepare(`DELETE FROM archive_blobs WHERE created_at<?
        AND blob_id NOT IN (SELECT session_id||':'||seq FROM archive_outbox)`)
        .run(cutoff);
    });
    return deleted;
  }

  /** Memory-mode blob ids, so pruning does not have to reach into the map's type. */
  private memoryBlobKeys(): string[] {
    return [...this.memoryBlobs.keys()];
  }

  /** Offloads an oversized payload beside its stub record.
   *
   *  <p>The hub applies `applySizePolicy` the moment a record is first seen, so an
   *  800 KiB assistant message becomes a <2 KiB stub in the outbox plus a row here.
   *  Every later retry then costs the stub, not the message, and the payload still
   *  reaches the relay exactly once (as the batch entry's optional `payload`), which
   *  stores it encrypted for `GET /v2/blobs/:blobId`.
   */
  putBlob(blobId: string, payload: unknown, now = Date.now()): void {
    const text = typeof payload === 'string' ? payload : JSON.stringify(payload);
    if (typeof text !== 'string') return;
    const bytes = Buffer.byteLength(text, 'utf8');
    if (!this.db) { this.memoryBlobs.set(blobId, { payload: text, bytes, createdAt: now }); return; }
    this.db.prepare('INSERT OR IGNORE INTO archive_blobs(blob_id,payload,bytes,created_at) VALUES(?,?,?,?)')
      .run(blobId, text, bytes, now);
  }

  /** The serialized payloads for the given blob ids, for the pusher to attach to a
   *  batch. Missing ids are simply absent — a blob pruned after its record was
   *  acked is no longer this process's problem. */
  /**
   * Bytes of the blob a stub record points at, or 0 for an ordinary record.
   *
   * <p>The pusher ships the blob inside the same request as the stub, so the batch
   * budget has to account for it — see {@link ArchiveOutbox.claim}. Reading the size
   * off the stub's own `blobSize` avoids a second query: the plugin wrote that number
   * from the same payload it stored, and a pruned blob simply contributes nothing.
   */
  private blobBytesOf(payload: string): number {
    if (!payload.includes('"stub"')) return 0;
    // `blobSize` appears twice in a stub record (`payload.blobSize` and `stub.blobSize`)
    // and both carry the same number, so the first match is enough.
    const match = /"blobSize":(\d+)/.exec(payload);
    if (!match) return 0;
    const size = Number(match[1]);
    return Number.isFinite(size) && size > 0 ? size : 0;
  }

  blobs(blobIds: string[]): Map<string, string> {
    const out = new Map<string, string>();
    if (!blobIds.length) return out;
    if (!this.db) {
      for (const id of blobIds) { const row = this.memoryBlobs.get(id); if (row) out.set(id, row.payload); }
      return out;
    }
    const read = this.db.prepare('SELECT payload FROM archive_blobs WHERE blob_id=?');
    for (const id of blobIds) {
      const row = read.get(id) as { payload: string } | undefined;
      if (row) out.set(id, row.payload);
    }
    return out;
  }

  /** Session list metadata staging (title / projections), same durability story. */
  putMeta(sessionId: string, meta: unknown, now = Date.now()): void {
    const text = JSON.stringify(meta);
    if (!this.db) { this.memoryMeta.set(sessionId, text); return; }
    this.db.prepare('INSERT OR REPLACE INTO outbox_meta(session_id,meta,updated_at) VALUES(?,?,?)').run(sessionId, text, now);
  }

  listMeta(limit = 300): Array<{ sessionId: string; meta: unknown }> {
    if (!this.db) return [...this.memoryMeta].slice(0, limit).map(([sessionId, text]) => ({ sessionId, meta: JSON.parse(text) }));
    const rows = this.db.prepare('SELECT session_id,meta FROM outbox_meta ORDER BY updated_at DESC LIMIT ?').all(limit) as { session_id: string; meta: string }[];
    return rows.map(row => ({ sessionId: row.session_id, meta: JSON.parse(row.meta) }));
  }

  dropMeta(sessionIds: string[]): void {
    if (!sessionIds.length) return;
    if (!this.db) { for (const id of sessionIds) this.memoryMeta.delete(id); return; }
    const stmt = this.db.prepare('DELETE FROM outbox_meta WHERE session_id=?');
    this.transaction(() => { for (const id of sessionIds) stmt.run(id); });
  }

  /** True once a `put` was refused because the outbox is full. The caller turns
   *  this into a visible gap marker rather than pretending the record was saved. */
  get full(): boolean { return this.overflowed; }

  close(): void {
    this.db?.close();
  }

  private transaction(run: () => void): void {
    if (!this.db) { run(); return; }
    this.db.exec('BEGIN IMMEDIATE');
    try { run(); this.db.exec('COMMIT'); } catch (error) { try { this.db.exec('ROLLBACK'); } catch { /* already unwound */ } throw error; }
  }
}
