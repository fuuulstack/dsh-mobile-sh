import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { EventEmitter } from 'node:events';
import WebSocket, { WebSocketServer } from 'ws';
import { MessageBridge, probePeer } from '../packages/core/src/bridge.js';
import type { Channel } from '../packages/core/src/transport.js';

/**
 * Mirrors the DSH mux heartbeat: a ping every `interval` ms, terminated after
 * `missed` pongs. A peer that stops reading frames stops answering pings, so
 * any backpressure that pauses the socket looks like a dead peer and the mux
 * kills a stream that was still delivering bytes.
 */
const PING_MS = 200, MISSED = 2;

/** A channel whose acknowledgements are slow, the way a phone on a bad link behaves. */
class SlowChannel extends EventEmitter {
  readonly writes: Buffer[] = [];
  constructor(private readonly ackDelay: number) { super(); }
  async send(frame: unknown) {
    await new Promise(r => setTimeout(r, this.ackDelay));
    // Echo the handshakes the bridge waits on, so its queue drains normally.
    const f = frame as { type?: string; messageId?: number };
    if (f.type === 'ws-message' && f.messageId !== undefined) {
      queueMicrotask(() => {
        this.emit('control', { v: 3, type: 'ws-message-ready', messageId: f.messageId, phase: 'begin' });
        queueMicrotask(() => this.emit('control', { v: 3, type: 'ws-message-ready', messageId: f.messageId, phase: 'delivered' }));
      });
    }
  }
  async data(bytes: Buffer) { this.writes.push(bytes); }
  startReceive() {}
}

test('a slow channel must not stop the bridge reading, or the upstream heartbeat kills it', { timeout: 30000 }, async () => {
  const upstream = http.createServer((_req, res) => res.end('{}'));
  upstream.listen(0, '127.0.0.1');
  await once(upstream, 'listening');
  const wss = new WebSocketServer({ noServer: true });
  let terminated = false, pongs = 0;
  upstream.on('upgrade', (req, socket, head) => wss.handleUpgrade(req, socket, head, ws => {
    let missed = 0;
    ws.on('pong', () => { missed = 0; pongs++; });
    const timer = setInterval(() => {
      if (missed >= MISSED) { terminated = true; clearInterval(timer); ws.terminate(); return; }
      missed++;
      ws.ping();
    }, PING_MS);
    let n = 0;
    const beat = setInterval(() => {
      if (ws.readyState !== WebSocket.OPEN) return;
      if (n++ >= 12) { clearInterval(beat); return; }
      ws.send(Buffer.alloc(64 * 1024, n), () => {});
    }, 15);
    ws.on('close', () => { clearInterval(timer); clearInterval(beat); });
  }));

  const socket = new WebSocket(`ws://127.0.0.1:${(upstream.address() as any).port}/`, { maxPayload: 16 * 1024 * 1024 });
  await once(socket, 'open');
  const channel = new SlowChannel(PING_MS * 2);
  let closed = false;
  new MessageBridge(socket, channel as unknown as Channel, 16 * 1024 * 1024, () => { closed = true; });

  await new Promise(r => setTimeout(r, PING_MS * 20));

  const seen = { terminated, pongs, closed, writes: channel.writes.length };
  socket.terminate();
  wss.close();
  await new Promise<void>(r => upstream.close(() => r()));

  assert.equal(seen.terminated, false, 'the upstream must keep receiving pongs while a write is outstanding');
  assert.ok(seen.pongs >= 5, `expected several pongs, saw ${seen.pongs}`);
  assert.equal(seen.closed, false, 'the bridge must not be torn down');
  assert.ok(seen.writes > 0, 'every message must still reach the channel');
});

/**
 * The opposite direction: the relay no longer learns that the phone is gone.
 * A killed process cannot send a close frame, so without an outbound probe the
 * stream keeps its reservation until TCP gives up, which can take minutes.
 */
test('the probe reclaims a peer that stops answering pings', { timeout: 20000 }, async () => {
  // autoPong is a constructor option: a killed process never answers a ping.
  const wss = new WebSocketServer({ noServer: true, autoPong: false });
  const server = http.createServer((_req, res) => res.end('{}'));
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  let pings = 0;
  server.on('upgrade', (req, socket, head) => wss.handleUpgrade(req, socket, head, ws => {
    ws.on('ping', () => { pings++; });
  }));

  const socket = new WebSocket(`ws://127.0.0.1:${(server.address() as any).port}/`);
  await once(socket, 'open');
  const PROBE = 100, MISSED = 2;
  let dead = false;
  const stop = probePeer(socket, () => { dead = true; }, PROBE, MISSED);

  await new Promise(r => setTimeout(r, PROBE * (MISSED + 3)));
  const seen = { pings, dead };

  stop();socket.terminate();wss.close();
  await new Promise<void>(r => server.close(() => r()));

  assert.ok(seen.pings >= MISSED, `expected probes before giving up, saw ${seen.pings}`);
  assert.equal(seen.dead, true, 'a peer that stops answering must be reclaimed');
});

test('the probe leaves a healthy peer alone', { timeout: 20000 }, async () => {
  const wss = new WebSocketServer({ noServer: true });
  const server = http.createServer((_req, res) => res.end('{}'));
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  server.on('upgrade', (req, socket, head) => wss.handleUpgrade(req, socket, head, () => {}));

  const socket = new WebSocket(`ws://127.0.0.1:${(server.address() as any).port}/`);
  await once(socket, 'open');
  const PROBE = 100, MISSED = 2;
  let dead = false, pongs = 0;
  socket.on('pong', () => { pongs++; });
  const stop = probePeer(socket, () => { dead = true; }, PROBE, MISSED);

  await new Promise(r => setTimeout(r, PROBE * (MISSED + 4)));
  const seen = { dead, pongs, state: socket.readyState };

  stop();socket.terminate();wss.close();
  await new Promise<void>(r => server.close(() => r()));

  assert.equal(seen.pongs >= MISSED, true, `a live peer must answer, saw ${seen.pongs}`);
  assert.equal(seen.dead, false, 'answering pings must keep the stream open');
  assert.equal(seen.state, WebSocket.OPEN, 'the socket must still be usable');
});
