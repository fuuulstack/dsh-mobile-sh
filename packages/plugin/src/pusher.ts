/**
 * Drains the durable outbox into the relay's v2 archive (v2 §protocol "归档三态幂等").
 *
 * <p>The push loop owns exactly one mutable fact — which batch id is in flight — because
 * everything else already lives in SQLite. That is what makes the failure handling
 * short: a network error is `release(batchId)`, a success is `ack(batchId)`, and the
 * next tick starts from whatever SQLite says. There is no in-memory queue whose
 * ordering could drift out of sync with what the relay actually has.
 *
 * <p>The batch id is **derived from the batch contents**, not random. The plugin can
 * therefore retry a timed-out push with the same id, and the relay's `archive_batches`
 * table turns the second copy into a no-op even if the first one only half landed.
 * A random id would make every retry a fresh batch and lean entirely on the
 * `(session, seq)` primary key — which absorbs duplicates but not duplicated rows in
 * `archive_batches`, and leaves the relay unable to answer "did batch X already
 * apply?" without scanning.
 */
import { createHash } from 'node:crypto';
import { gzipSync } from 'node:zlib';
import { SYNC_V2, type TimelineRecord } from '../../core/src/index.js';
import type { ArchiveOutbox, OutboxEntry } from './outbox.js';
import type { HostMetrics } from './metrics.js';

export interface PusherOptions {
  outbox: ArchiveOutbox;
  relayOrigin: string;
  hostId: string;
  hostTunnelToken: string;
  onLog?: (message: string) => void;
  metrics?: HostMetrics;
  /** Rows per batch. The relay accepts 2000; the wire is gzipped, and a smaller batch
   *  keeps the ack latency (and therefore the phone's wait) low. */
  batchRows?: number;
  batchBytes?: number;
  /** How often to look for work. The hub also kicks it directly after an ingest, so
   *  this is only the floor for `meta`-only work and for post-failure retries. */
  intervalMs?: number;
  fetch?: typeof fetch;
  now?: () => number;
  /** Called after a batch is acknowledged: capacity just freed up, so anything the hub
   *  was holding back can be retried. Wiring it here rather than on a timer means the
   *  retry happens exactly when it can succeed. */
  onDrained?: () => void;
}

export class ArchivePusher {
  private timer?: NodeJS.Timeout;
  private running = false;
  private failures = 0;
  private stopping = false;

  constructor(private readonly options: PusherOptions) {}

  start(): void {
    if (this.timer) return;
    this.timer = setInterval(() => void this.flush(), this.options.intervalMs ?? 250);
    this.timer.unref();
    void this.flush();
  }

  async stop(): Promise<void> {
    this.stopping = true;
    clearInterval(this.timer);
    this.timer = undefined;
    await this.flush().catch(() => {});
  }

  /** Ask for a flush now. Safe to call on every ingested record: `running` collapses
   *  a burst into one in-flight batch plus at most one follow-up pass. */
  kick(): void {
    if (this.running || this.stopping) return;
    void this.flush();
  }

  async flush(): Promise<void> {
    if (this.running || this.stopping) return;
    const batchRows = this.options.batchRows ?? 500;
    const batchBytes = this.options.batchBytes ?? 256 * 1024;
    // The batch id has to be derived from the batch, but the batch cannot be claimed
    // before it has an id. Peeking the row keys first costs one indexed read on the
    // `state` index and nothing else; the claim right after is the one that mutates.
    const peek = this.options.outbox.peek(batchRows, batchBytes);
    const meta = this.options.outbox.listMeta(300);
    if (!peek.length && !meta.length) return;
    const batchId = batchIdOf(this.options.hostId, peek, meta);
    const claimed = peek.length ? this.options.outbox.claim(batchId, batchRows, batchBytes) : [];
    if (!claimed.length && !meta.length) return;

    this.running = true;
    this.options.metrics?.noteArchiver(this.options.outbox.depth().pending, false, this.options.outbox.depth().inflight);
    const startedAt = (this.options.now ?? Date.now)();
    const dep = this.options.outbox.depth();
    this.options.metrics?.noteArchiver(dep.pending, true, dep.inflight);

    // The record is stored serialized in the outbox, so it is spliced into the request
    // as text. Parsing and re-stringifying a 200 KiB assistant message per push is pure
    // CPU on a process that shares an event loop with the tunnel.
    // Stub records point at a blob the relay has to store encrypted, so the payload
    // travels in the same request — one round trip, one batch id, one ack. A blob that
    // was already pruned simply drops out; the stub still records that a payload
    // existed, which is what the phone paints.
    const blobIds = claimed.map(entry => stubBlobId(entry.payload)).filter((id): id is string => typeof id === 'string');
    const blobs = this.options.outbox.blobs(blobIds);
    const records = claimed.map(entry => {
      const blobId = stubBlobId(entry.payload);
      const payload = blobId === undefined ? undefined : blobs.get(blobId);
      return {
        sessionId: entry.sessionId,
        seq: entry.seq,
        prevSeq: fieldOf(entry.payload, 'prevSeq'),
        turnId: fieldOf(entry.payload, 'turnId'),
        kind: fieldOf(entry.payload, 'kind'),
        timestampMs: fieldOf(entry.payload, 'timestampMs'),
        byteSize: Buffer.byteLength(entry.payload, 'utf8'),
        blobId,
        record: entry.payload,
        ...(payload === undefined ? {} : { payload: JSON.parse(payload) }),
      };
    });
    const body = JSON.stringify({
      hostId: this.options.hostId,
      batchId,
      records,
      meta: meta.map(item => ({ sessionId: item.sessionId, item: item.meta })),
    });
    const gzipped = gzipSync(Buffer.from(body, 'utf8'), { level: 6 });
    try {
      const response = await (this.options.fetch ?? fetch)(new URL('/control/timeline', this.options.relayOrigin), {
        method: 'POST',
        redirect: 'error',
        signal: AbortSignal.timeout(20000),
        headers: {
          authorization: `Bearer ${this.options.hostTunnelToken}`,
          'content-type': 'application/json',
          'content-encoding': 'gzip',
        },
        body: gzipped,
      });
      if (!response.ok) throw new Error(`push-rejected-${response.status}`);
      if (claimed.length) this.options.outbox.ack(batchId);
      if (meta.length) this.options.outbox.dropMeta(meta.map(item => item.sessionId));
      this.failures = 0;
      this.options.metrics?.notePush((this.options.now ?? Date.now)() - startedAt, gzipped.length, true);
      // Capacity just freed: this is the only moment when retrying a record the hub
      // held back can succeed, so it belongs here rather than on a timer.
      this.options.onDrained?.();
    } catch (error) {
      // Releasing is what makes the retry safe: the rows go back to PENDING with their
      // `attempts` bumped, and the *derived* batch id means the next attempt reuses the
      // same value. `meta` is left in place for the same reason.
      if (claimed.length) this.options.outbox.release(batchId);
      const cause = error instanceof Error && error.cause instanceof Error ? ` cause=${error.cause.message}` : '';
      this.options.metrics?.notePush((this.options.now ?? Date.now)() - startedAt, gzipped.length, false);
      this.failures++;
      // Log the first failure of a streak and then every tenth: a relay outage produces
      // one line per 250ms tick, which buries everything else in the log.
      if (this.failures === 1 || this.failures % 10 === 0) {
        this.options.onLog?.(`archive push failed (${this.failures}x): ${error instanceof Error ? error.message : 'unknown'}${cause}`);
      }
    } finally {
      this.running = false;
      const after = this.options.outbox.depth();
      this.options.metrics?.noteArchiver(after.pending, false, after.inflight);
    }
  }
}

/** Order-independent (rows arrive FIFO) yet content-derived, so the same backlog
 *  retried produces the same id. */
function batchIdOf(hostId: string, entries: OutboxEntry[], meta: Array<{ sessionId: string }>): string {
  const hash = createHash('sha256').update(hostId);
  for (const entry of entries) hash.update(`${entry.sessionId}\u0000${entry.seq}\u0000`);
  if (!entries.length) for (const item of meta) hash.update(`m:${item.sessionId}\u0000`);
  return `${entries.length}-${hash.digest('base64url').slice(0, 22)}`;
}

/** Reads one scalar out of an already-serialized record without building the object.
 *  The fields are at the top level of the JSON the outbox stored, and the values are
 *  plain numbers/short strings, so a prefix search is exact enough here. */
function fieldOf(payload: string, key: string): unknown {
  const marker = `"${key}":`;
  const at = payload.indexOf(marker);
  if (at < 0) return undefined;
  const start = at + marker.length;
  if (payload[start] === '"') {
    const end = payload.indexOf('"', start + 1);
    return end < 0 ? undefined : payload.slice(start + 1, end);
  }
  let end = start;
  while (end < payload.length && !',}'.includes(payload[end]!)) end++;
  const raw = payload.slice(start, end).trim();
  if (raw === 'null' || raw === '') return undefined;
  const asNumber = Number(raw);
  return Number.isFinite(asNumber) ? asNumber : undefined;
}

/** The stub's blob id, when the record was oversized and the payload was offloaded. */
function stubBlobId(payload: string): string | undefined {
  if (!payload.includes('"stub"')) return undefined;
  const blobId = fieldOf(payload, 'blobId');
  return typeof blobId === 'string' ? blobId : undefined;
}

export { SYNC_V2 };
