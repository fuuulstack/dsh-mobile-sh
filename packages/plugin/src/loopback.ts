/**
 * Every loopback call the worker makes to the desktop host goes through here.
 *
 * <p>Two rules, both learned the hard way:
 *
 *  - **Never reuse a socket.** The host answers `keep-alive: timeout=5`, but a pooled
 *    socket stays in the agent until the FIN is actually read. The host process shares
 *    one thread with its UI, so it stalls often; the next request then picks a socket
 *    the peer has already closed and dies with `ECONNRESET`, which the relay faithfully
 *    turns into a `502 upstream-error` the user reads as "the phone is broken". A fresh
 *    connect costs ~0.5 ms on loopback, so pooling buys nothing worth that.
 *  - **Ask for identity encoding.** A gzipped body parses as garbage, and a caller that
 *    cannot tell garbage from content will quietly archive nothing.
 *
 * <p>There is deliberately no retry. The bodies we send are already-consumed streams in
 * v1's code paths and small JSON here; the idempotency that matters (one approval click
 * applied once) is enforced by the `cmdId` cache in {@link ControlSession}, not by
 * replaying HTTP requests.
 */
import http from 'node:http';

export interface LoopbackResponse {
  status: number;
  body: string;
}

export function loopbackPost(
  origin: string,
  path: string,
  body: string,
  timeoutMs = 20000,
  extra: Record<string, string> = {},
): Promise<LoopbackResponse> {
  return new Promise((resolve, reject) => {
    const url = new URL(origin);
    const req = http.request({
      agent: false,
      hostname: '127.0.0.1',
      port: Number(url.port) || 80,
      path,
      method: 'POST',
      timeout: timeoutMs,
      headers: {
        host: url.host,
        origin: url.origin,
        'sec-fetch-site': 'same-origin',
        'content-type': 'application/json',
        'content-length': String(Buffer.byteLength(body)),
        'accept-encoding': 'identity',
        ...extra,
      },
    }, res => {
      const chunks: Buffer[] = [];
      res.on('data', (chunk: Buffer) => chunks.push(chunk));
      res.on('end', () => resolve({ status: res.statusCode ?? 0, body: Buffer.concat(chunks).toString('utf8') }));
      res.on('error', reject);
    });
    req.once('timeout', () => req.destroy(new Error('loopback-timeout')));
    req.once('error', reject);
    req.end(body);
  });
}
