import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { gunzipSync } from 'node:zlib';
import WebSocket, { WebSocketServer } from 'ws';
import { Relay } from '../packages/relay/src/server.js';
import { secret, uuid } from '../packages/relay/src/crypto.js';
import { HostClient } from '../packages/plugin/src/client.js';
import { Collector } from '../packages/plugin/src/collector.js';
import { ControlSession } from '../packages/plugin/src/control.js';
import { SessionHub } from '../packages/plugin/src/hub.js';
import { ArchiveOutbox } from '../packages/plugin/src/outbox.js';
import { ArchivePusher } from '../packages/plugin/src/pusher.js';
import { CONTROL_PATH, decodeMuxFrame, encodeMuxFrame } from '../packages/core/src/index.js';
import type { TimelineRecord } from '../packages/core/src/index.js';

/**
 * v2 全链路的端到端回归：**真中继 + 真隧道 + 真插件四件套**，一次跑完
 *
 * ```
 * 假宿主 ──mux──► Collector ──► Hub ──┬──► Outbox ──► Pusher ──► POST /control/timeline
 *                                     └──► ControlSession ──► 手机（WS /native/v2/.../control）
 * 手机 ──POST /native/v2/.../sync──► 中继归档（14 天权威副本）
 * ```
 *
 * <p>它要钉的不变量，都是「单测各测一段、合起来却没人测」的那些：
 *
 * <p>①<b>实时与归档是同一批字节</b>。手机从控制流拿到的记录与随后从 `/v2/sync` 拿到的
 * 记录必须逐字段相等 —— 只要两边各自做过一次转换（v1 就是这样：实时走宿主原始事件、
 * 归档走裁剪过的快照），「同一条消息刷新一下变个样」就永远修不完。
 *
 * <p>②<b>FOCUS 立刻回放，且回放不越过本地水位</b>。插件的尾部窗口是内存里的，回放不等
 * 宿主、不等中继；`hasMore` 说的是「窗口以下还有」，手机据此去 `/v2/sync` 补。
 *
 * <p>③<b>超大正文经存根 + blob 之后仍能还原</b>。插件在唯一摄入点做大小策略，中继把
 * blob 加密落库，手机走 `/v2/blobs/:id` 取回。任何一环偷懒，「5MB 那条」就永远只剩占位符。
 *
 * <p>④<b>幂等</b>。同一批归档重投、同一条 `FOCUS` 重发、同一个 `cmdId` 重发，结果都不变。
 */

const sleep = (ms: number) => new Promise(r => setTimeout(r, ms));

async function until(check: () => boolean, ms: number, what: string) {
  const deadline = Date.now() + ms;
  while (!check()) {
    if (Date.now() > deadline) throw new Error(`timeout waiting for ${what}`);
    await sleep(20);
  }
}

/** 假 DSH 宿主：`/api/session/list`（RPC 信封）+ `/api/remote.mux`（follow 流）。
 *
 *  <p>只做真宿主做过的那几件事：开流后回一个 `snapshot`、之后按调用方给的剧本推
 *  `event` / `assistant-stream`。剧本由测试逐条 `push`，所以时序完全可控。 */
function fakeHost() {
  const followers = new Map<string, WebSocket>();
  const server = http.createServer((req, res) => {
    if (req.url === '/api/session/list') {
      res.setHeader('content-type', 'application/json');
      // RPC 信封：`{result:{ok,value:{items}}}`。读错这一层会静默得到「零会话」。
      res.end(JSON.stringify({ result: { ok: true, value: { items: [{ sessionId: SESSION, title: '联调会话', updatedAt: 1, running: true }] } } }));
      return;
    }
    res.writeHead(404); res.end();
  });
  const wsServer = new WebSocketServer({ server });
  wsServer.on('connection', ws => {
    ws.on('message', raw => {
      const frame = JSON.parse(raw.toString()) as { type?: string; streamId?: string };
      if (frame.type !== 'open' || typeof frame.streamId !== 'string') return;
      if (frame.streamId === 'arch-events') return;
      followers.set(frame.streamId, ws);
    });
  });
  return {
    server, wsServer, followers,
    /** 向某个 follow 流推一帧，形如真宿主的 `{type:'item',streamId,value}`。 */
    push(streamId: string, value: unknown) {
      const ws = followers.get(streamId);
      if (!ws) throw new Error(`no follower ${streamId}`);
      ws.send(JSON.stringify({ type: 'item', streamId, value }));
    },
    close: async () => {
      for (const ws of wsServer.clients) ws.terminate();
      wsServer.close();
      server.closeAllConnections();
      await new Promise<void>(r => server.close(() => r()));
    },
  };
}

const SESSION = 'sess-v2';
/** 一条用户消息事件，形如宿主发的那样。 */
function userEvent(seq: number, text: string) {
  return { seq, type: 'user/message', data: { source: { kind: 'user' }, message: { content: [{ type: 'text', text }] } } };
}
function assistantEvent(seq: number, text: string) {
  return { seq, type: 'assistant/message', data: { message: { content: [{ type: 'text', text }] } } };
}
function snapshotOf(events: unknown[]) {
  return { type: 'snapshot', session: { sessionId: SESSION, title: '联调会话' }, records: events.map(event => ({ event })) };
}

type Sent = { records?: TimelineRecord[]; sessionId?: string; hasMore?: boolean; type?: string };

/** 取一条 blob（可选带 `Range`），回状态码、头与正文。
 *
 *  <p>用手写 `node:http` 而不是 `fetch`：`Host` 头在 undici 里是禁写头，会被静默丢掉，
 *  而中继按 `Host` 路由 —— 少了它一律 404，看起来像「路由没实现」。 */
async function blobGet(port: number, path: string, range?: string, token?: string) {
  return await new Promise<{ status: number; text: string; headers: Record<string, string | undefined> }>((resolve, reject) => {
    const headers: Record<string, string> = { Host: 'relay.example.test' };
    if (token) headers.authorization = `Bearer ${token}`;
    if (range) headers.range = range;
    const req = http.request({ host: '127.0.0.1', port, path, method: 'GET', headers }, res => {
      const chunks: Buffer[] = [];
      res.on('data', c => chunks.push(c));
      res.on('end', () => resolve({
        status: res.statusCode!,
        text: Buffer.concat(chunks).toString('utf8'),
        headers: res.headers as Record<string, string | undefined>,
      }));
    });
    req.on('error', reject);
    req.end();
  });
}

/** 手机侧：连 `/native/v2/hosts/:id/control`，按 mux 帧协议收发。
 *
 *  <p>手写的这一小段就是 Android `RelayApiV2` 的行为（`HELLO` → `HELLO_ACK`、
 *  `FOCUS_SESSION` → `RECORDS`…），故意不含任何重试与补差逻辑：这个测试要验的是
 *  「链路上真的收到了什么」，不是「客户端会不会自己救回来」。 */
async function phoneOf(port: number, hostId: string, token: string) {
  const ws = new WebSocket(`ws://127.0.0.1:${port}/native/v2/hosts/${hostId}/control`, {
    headers: { Host: 'relay.example.test', Authorization: `Bearer ${token}` },
  });
  const frames: Sent[] = [];
  const pulses: Sent[] = [];
  ws.on('message', raw => {
    const frame = decodeMuxFrame(Buffer.from(raw as Buffer).toString('utf8')) as (Sent & { type: string }) | undefined;
    if (!frame) return;
    if (frame.type === 'RECORDS') frames.push(frame);
    else if (frame.type === 'SESSION_PULSE') pulses.push(frame);
  });
  await once(ws, 'open');
  const send = (frame: unknown) => ws.send(encodeMuxFrame(frame as never));
  const recordsOf = (sessionId = SESSION): TimelineRecord[] => frames.filter(f => f.sessionId === sessionId).flatMap(f => f.records ?? []);
  return {
    ws, frames, pulses, send, recordsOf,
    /** 等到某个会话的累计记录数达到 n（或超时）。 */
    async waitRecords(n: number) {
      await until(() => recordsOf().length >= n, 8000, `${n} records`);
      return recordsOf();
    },
    close: () => ws.terminate(),
  };
}

function sync(port: number, hostId: string, token: string, afterSeq: number, sessionId = SESSION) {
  return new Promise<{ status: number; body: any; headers: http.IncomingHttpHeaders }>((resolve, reject) => {
    const body = JSON.stringify({ mode: 'FOREGROUND_SINGLE', pageByteBudget: 512 * 1024, sessions: [{ sessionId, afterSeq, priority: 100 }] });
    const req = http.request({
      host: '127.0.0.1', port, path: `/native/v2/hosts/${hostId}/sync`, method: 'POST',
      headers: { Host: 'relay.example.test', authorization: `Bearer ${token}`, 'content-type': 'application/json', 'content-length': Buffer.byteLength(body) },
    }, res => {
      const chunks: Buffer[] = []; res.on('data', c => chunks.push(c));
      res.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8');
        let parsed: any = text;
        try { parsed = JSON.parse(text); } catch { /* keep the raw text for the assertion message */ }
        resolve({ status: res.statusCode!, body: parsed, headers: res.headers });
      });
    });
    req.on('error', reject); req.end(body);
  });
}

test('v2 end to end: control stream, archive, /v2/sync and blobs agree on every byte', { timeout: 40000 }, async t => {
  const host = fakeHost();
  host.server.listen(0, '127.0.0.1'); await once(host.server, 'listening');
  const upstreamPort = (host.server.address() as any).port;
  const relay = new Relay({ baseUrl: 'https://relay.example.test', database: ':memory:', key: Buffer.alloc(32, 7), requireTotp: false, testMode: true });
  const port = await relay.listen(0);

  let client: HostClient | undefined;
  let collector: Collector | undefined;
  let pusher: ArchivePusher | undefined;
  let outbox: ArchiveOutbox | undefined;
  let phone: Awaited<ReturnType<typeof phoneOf>> | undefined;
  t.after(async () => {
    phone?.close();
    await collector?.stop(); await pusher?.stop(); outbox?.close();
    await client?.stop(); await relay.close(); await host.close();
  });

  // ---- 账户 / 主机 / 手机，全部走真 store ----
  await relay.store.createAccount('a@example.test', 'long-test-password', false);
  const adminLogin = await relay.store.login('a@example.test', 'long-test-password', '', false);
  const admin = relay.store.session(adminLogin.token, 'account');
  const enrollment = { operationId: uuid(), hostPublicId: uuid(), hostTunnelToken: secret(), alias: 'v2 联调' };
  const { hostId } = relay.store.enroll(relay.store.invitation(admin), enrollment);
  const phoneLogin = await relay.store.login('a@example.test', 'long-test-password', '', false, 'Android V2', true);
  relay.store.pair(relay.store.session(phoneLogin.token, 'native'), relay.store.createPair(admin, hostId).code);

  // ---- 插件四件套，接线顺序与 worker.ts 完全一致 ----
  const getCookie = async () => 'dsh-auth-test=1';
  outbox = new ArchiveOutbox({ path: null });
  pusher = new ArchivePusher({
    outbox, relayOrigin: `http://127.0.0.1:${port}`, hostId, hostTunnelToken: enrollment.hostTunnelToken,
    intervalMs: 30, fetch: scopedFetch(),
    onDrained: () => { hub.retryPending(); },
  });
  pusher.start();
  const hub = new SessionHub({ outbox, onRecord: () => pusher!.kick() });
  collector = new Collector({ hub, outbox, upstreamOrigin: `http://127.0.0.1:${upstreamPort}`, getCookie, topK: 5, intervalMs: 60_000, kickDebounceMs: 60, reconnectBaseMs: 40 });
  client = new HostClient({
    relayUrl: `ws://127.0.0.1:${port}/tunnel`, hostId, hostTunnelToken: enrollment.hostTunnelToken,
    upstreamOrigin: `http://127.0.0.1:${upstreamPort}`, getCookie,
    // 测试里隧道走 loopback，但中继按 Host 分账户面 —— 给隧道一个自定义头即可。
    control: channel => new ControlSession(channel, { hub, upstreamOrigin: `http://127.0.0.1:${upstreamPort}`, getCookie, workerEpoch: 3, buildHash: 'test.001' }),
  });
  (client.options as { headers?: Record<string, string> }).headers = { Host: 'relay.example.test' };
  client.connect();
  await until(() => !!relay.proxy.peers.get(hostId)?.ready, 8000, 'tunnel ready');
  collector.start();

  // ---- 宿主推一段历史 + 一条超大正文 ----
  const big = 'X'.repeat(300 * 1024); // > SYNC_V2.stubThreshold (256 KiB)
  await until(() => host.followers.size > 0, 8000, 'collector follow');
  const streamId = [...host.followers.keys()][0]!;
  host.push(streamId, snapshotOf([userEvent(1, '帮我看看这个仓库'), assistantEvent(2, '好的，先读一下结构。')]));
  // 晚到的 seq（不是紧跟其后）：prevSeq 必须是 2，而不是「最后收到的那个」。
  host.push(streamId, { type: 'event', event: assistantEvent(5, big) });
  // 采集是异步的（ws → Collector → Hub），先等三条真的进了 outbox 再推。
  await until(() => outbox!.depth().acked >= 3, 8000, 'three records archived');

  // ---- 中继归档：三条都在，且存根指向 blob ----
  const summary = relay.store.timelineSummary(hostId);
  assert.equal(summary.length, 1);
  assert.equal(summary[0]!.latestSeq, 5);
  assert.equal(summary[0]!.count, 3);
  const archived = relay.store.timelineSlice(hostId, SESSION, 0, 10).map(row => JSON.parse(row.json) as TimelineRecord);
  assert.deepEqual(archived.map(r => r.seq), [1, 2, 5]);
  assert.deepEqual(archived.map(r => r.prevSeq), [0, 1, 2]); // 5 的前驱是 2，不是「上一个收到的事件」
  // `blobSize` is the serialized payload (300 KiB of text plus the event envelope), so
  // it is a lower bound on the text, not an equality: asserting equality here would be
  // asserting the envelope's byte count, which is not the contract.
  assert.ok(archived[2]!.stub!.blobSize >= Buffer.byteLength(big));

  // ---- 手机从这里开始：先拿到归档（离线也能看历史），再开控制流 ----
  const first = await sync(port, hostId, phoneLogin.token, 0);
  assert.equal(first.status, 200);
  const page = first.body.results[0];
  assert.equal(page.sessionId, SESSION);
  assert.equal(page.nextCursorSeq, 5);
  assert.equal(page.hasMore, false);
  assert.equal(page.records.length, 3);
  // 首行预览**不走网络**：手机从本地 Room 的助手正文现算（`data/Preview.kt`）。
  // 曾经有一条「Tail-1 预览捎带」——首屏额外带一条 `firstAssistantPreviewRecord`
  // 让没打开过的会话也能画列表预览。它从来没被 Android 侧消费过（`RelayApiV2`
  // 解析后无读方），2026-11-01 连同 `store.firstAssistantRecord()` 一起删掉了。
  assert.equal(page.records[1].seq, 2);

  // ---- 增量：从 2 之后只有 seq 5，且与归档逐字节一致 ----
  const second = await sync(port, hostId, phoneLogin.token, 2);
  const delta = second.body.results[0];
  assert.deepEqual(delta.records.map((r: TimelineRecord) => r.seq), [5]);
  assert.deepEqual(delta.records[0], archived[2]);

  // ---- 存根指向的正文能取回，且就是原来那 300 KiB ----
  const blobPathEarly = `/native/v2/hosts/${hostId}/blobs/${encodeURIComponent(archived[2]!.stub!.blobId)}`;
  const blob = await blobGet(port, blobPathEarly, undefined, phoneLogin.token);
  assert.equal(blob.status, 200, `blob fetch failed: ${blob.text.slice(0, 200)} (id=${archived[2]!.stub!.blobId})`);
  const recovered = JSON.parse(blob.text) as { data: { message: { content: Array<{ text: string }> } } };
  assert.equal(recovered.data.message.content[0]!.text, big);

  // ---- 同一条 blob 可以分段续传，拼回来逐字节相同 ----
  //
  // A 300 KiB (in production up to 10 MiB) body over a phone uplink gets cut in the
  // middle all the time. Without `Range` every cut means starting over, so the
  // "5 MB reply" that the stub path exists for would never actually arrive.
  const blobPath = `/native/v2/hosts/${hostId}/blobs/${encodeURIComponent(archived[2]!.stub!.blobId)}`;
  const total = Buffer.byteLength(blob.text, 'utf8');
  const range = await blobGet(port, blobPath, `bytes=${Math.floor(total / 2)}-`, phoneLogin.token);
  assert.equal(range.status, 206, `range request failed: ${range.text.slice(0, 120)}`);
  assert.equal(range.headers['content-range'], `bytes ${Math.floor(total / 2)}-${total - 1}/${total}`);
  assert.equal(
    Buffer.concat([Buffer.from(blob.text, 'utf8').subarray(0, Math.floor(total / 2)), Buffer.from(range.text, 'utf8')]).toString('utf8'),
    blob.text,
  );
  // A suffix range means "the last N bytes", not "from N onwards".
  const tail = await blobGet(port, blobPath, 'bytes=-40', phoneLogin.token);
  assert.equal(tail.status, 206);
  assert.equal(tail.text, blob.text.slice(-40));
  // Unsatisfiable: 416 with the total in `content-range`, never a silent full body —
  // otherwise a client retries the same offset forever.
  const past = await blobGet(port, blobPath, `bytes=${total + 10}-`, phoneLogin.token);
  assert.equal(past.status, 416);
  assert.equal(past.headers['content-range'], `bytes */${total}`);
  // No Range at all still answers 200 and advertises resumability.
  assert.equal(blob.headers['accept-ranges'], 'bytes');

  // ---- 控制流：HELLO 带回 epoch 与 build hash ----
  phone = await phoneOf(port, hostId, phoneLogin.token);
  phone.send({ tag: 'dsh-v2', type: 'HELLO', buildHash: 'android.9', capabilities: ['records', 'pulse', 'cmd'] });
  await until(() => phone!.pulses.length >= 0, 100, 'noop'); // 让出一次事件循环
  const hello = await new Promise<any>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('no HELLO_ACK')), 8000);
    const listener = (raw: WebSocket.RawData) => {
      const frame = decodeMuxFrame(Buffer.from(raw as Buffer).toString('utf8')) as any;
      if (frame?.type === 'HELLO_ACK') { clearTimeout(timer); phone!.ws.off('message', listener); resolve(frame); }
    };
    phone!.ws.on('message', listener);
  });
  assert.equal(hello.workerEpoch, 3);
  assert.equal(hello.buildHash, 'test.001');

  // ---- FOCUS：立刻回放尾部，且**不越过**手机自己的水位 ----
  phone.send({ tag: 'dsh-v2', type: 'FOCUS_SESSION', sessionId: SESSION, sinceSeq: 2 });
  await until(() => phone!.recordsOf().length > 0, 8000, 'focus replay');
  const replayed = phone!.recordsOf();
  assert.deepEqual(replayed.map(r => r.seq), [5], 'replay starts after sinceSeq, not from zero');
  // 关键不变量：控制流与归档是同一批字节，不做任何额外转换。
  assert.deepEqual(replayed[0], archived[2]);

  // ---- 实时：新事件同时到达手机与归档，字节仍然一致 ----
  host.push(streamId, { type: 'event', event: assistantEvent(6, '结构看完了。') });
  await until(() => phone!.recordsOf().length >= 2, 8000, 'live record');
  const live = phone!.recordsOf().at(-1)!;
  assert.equal(live.seq, 6);
  await until(() => outbox!.depth().acked >= 4, 8000, 'live record archived');
  const afterLive = relay.store.timelineSlice(hostId, SESSION, 5, 10).map(row => JSON.parse(row.json) as TimelineRecord);
  assert.deepEqual(JSON.parse(JSON.stringify(live)), afterLive.at(-1));

  // ---- 幂等：重发同一 FOCUS 不多出一条，重投同一批归档不新增行 ----
  const before = phone!.recordsOf().length;
  phone.send({ tag: 'dsh-v2', type: 'FOCUS_SESSION', sessionId: SESSION, sinceSeq: 5 });
  await until(() => phone!.recordsOf().length > before, 8000, 'second focus replay');
  assert.deepEqual(phone!.recordsOf().slice(before).map(r => r.seq), [6]);

  const batch = { hostId, batchId: 'replayed-batch', records: [{ sessionId: SESSION, seq: 1, prevSeq: 0, turnId: `${SESSION}:t0`, kind: 'USER_INPUT', timestampMs: 1, record: JSON.stringify({ seq: 1 }), payload: { seq: 1 } }] };
  const replay = await postJson(port, '/control/timeline', enrollment.hostTunnelToken, batch);
  assert.equal(replay.status, 200);
  assert.deepEqual({ accepted: replay.body.accepted, duplicate: replay.body.duplicate }, { accepted: 0, duplicate: 1 });

  // ---- 脉搏：聚焦中的会话每 5 秒一帧，无事发生也发 ----
  await until(() => phone!.pulses.length > 0, 12000, 'session pulse');
  const pulse = phone!.pulses.at(-1) as any;
  assert.equal(pulse.sessionId, SESSION);
  assert.equal(pulse.workerEpoch, 3);
  assert.ok(pulse.latestSeq >= 6);
});

test('a control stream survives a tunnel restart and keeps pulsing', { timeout: 40000 }, async t => {
  // 评审 §六.1 要求的那条：手机停在会话里，**隧道**断掉再起来，控制流必须自己恢复
  // 并且继续出脉搏。此前只测了「正常流程」：开流 → replay → pulse，中间什么都没断。
  // 于是「插件 worker 重启后手机端永久静默」这一类缺陷在测试里完全看不见。
  const host = fakeHost();
  host.server.listen(0, '127.0.0.1'); await once(host.server, 'listening');
  const upstreamPort = (host.server.address() as any).port;
  const relay = new Relay({ baseUrl: 'https://relay.example.test', database: ':memory:', key: Buffer.alloc(32, 7), requireTotp: false, testMode: true });
  const port = await relay.listen(0);

  let client: HostClient | undefined;
  let collector: Collector | undefined;
  let pusher: ArchivePusher | undefined;
  let outbox: ArchiveOutbox | undefined;
  let phone: Awaited<ReturnType<typeof phoneOf>> | undefined;
  t.after(async () => {
    phone?.close();
    await collector?.stop(); await pusher?.stop(); outbox?.close();
    await client?.stop(); await relay.close(); await host.close();
  });

  await relay.store.createAccount('a@example.test', 'long-test-password', false);
  const adminLogin = await relay.store.login('a@example.test', 'long-test-password', '', false);
  const admin = relay.store.session(adminLogin.token, 'account');
  const enrollment = { operationId: uuid(), hostPublicId: uuid(), hostTunnelToken: secret(), alias: '重连联调' };
  const { hostId } = relay.store.enroll(relay.store.invitation(admin), enrollment);
  const phoneLogin = await relay.store.login('a@example.test', 'long-test-password', '', false, 'Android V2', true);
  relay.store.pair(relay.store.session(phoneLogin.token, 'native'), relay.store.createPair(admin, hostId).code);

  const getCookie = async () => 'dsh-auth-test=1';
  const wire = (epoch = 3) => {
    outbox = new ArchiveOutbox({ path: null });
    pusher = new ArchivePusher({
      outbox, relayOrigin: `http://127.0.0.1:${port}`, hostId, hostTunnelToken: enrollment.hostTunnelToken,
      intervalMs: 30, fetch: scopedFetch(),
      onDrained: () => { hub?.retryPending(); },
    });
    pusher.start();
    const hub: SessionHub = new SessionHub({ outbox, onRecord: () => pusher!.kick() });
    collector = new Collector({ hub, outbox, upstreamOrigin: `http://127.0.0.1:${upstreamPort}`, getCookie, topK: 5, intervalMs: 60_000, kickDebounceMs: 60, reconnectBaseMs: 40 });
    client = new HostClient({
      relayUrl: `ws://127.0.0.1:${port}/tunnel`, hostId, hostTunnelToken: enrollment.hostTunnelToken,
      upstreamOrigin: `http://127.0.0.1:${upstreamPort}`, getCookie,
      control: channel => new ControlSession(channel, { hub, upstreamOrigin: `http://127.0.0.1:${upstreamPort}`, getCookie, workerEpoch: epoch, buildHash: 'test.001' }),
    });
    (client.options as { headers?: Record<string, string> }).headers = { Host: 'relay.example.test' };
    client.connect();
    return hub;
  };
  let hub: SessionHub | undefined = wire();
  await until(() => !!relay.proxy.peers.get(hostId)?.ready, 8000, 'tunnel ready');
  collector!.start();
  await until(() => host.followers.size > 0, 8000, 'collector follow');
  const streamId = [...host.followers.keys()][0]!;
  host.push(streamId, snapshotOf([userEvent(1, '在吗'), assistantEvent(2, '在。')]));
  await until(() => outbox!.depth().acked >= 2, 8000, 'first records archived');

  // 手机进来并聚焦。sinceSeq=1，所以回放里应该有 seq 2 —— 若一条都没有，
  // `recordsOf()` 会一直是空数组，下面的断言就成了「等一个永远不会来的东西」。
  phone = await phoneOf(port, hostId, phoneLogin.token);
  phone.send({ tag: 'dsh-v2', type: 'HELLO', buildHash: 'android.9', capabilities: ['records', 'pulse', 'cmd'] });
  await until(() => phone!.pulses.length >= 0, 100, 'noop'); // 让出一次事件循环，等 HELLO_ACK
  phone.send({ tag: 'dsh-v2', type: 'FOCUS_SESSION', sessionId: SESSION, sinceSeq: 1 });
  await until(() => phone!.recordsOf().length > 0, 8000, 'focus replay');
  assert.deepEqual(phone.recordsOf().map(r => r.seq), [2]);
  await until(() => phone!.pulses.length > 0, 12000, 'pulse before restart');
  const pulsesBefore = phone.pulses.length;

  // ---- 隧道断开：插件侧整条链路停掉，中继那边的 peer 消失 ----
  await client!.stop();
  collector = undefined; pusher = undefined; outbox?.close(); outbox = undefined; hub = undefined;
  await until(() => !relay.proxy.peers.get(hostId)?.ready, 8000, 'tunnel gone');

  // 中继**不会**替手机保留这条控制流：隧道一没，手机这条 WS 就被 terminate。
  // 手机必须自己发现并重连（`RelayApiV2` 的退避重连），这不是缺陷而是契约——
  // 在这里钉住它，免得将来有人为了「省一次重连」把死 socket 留在界面上。
  await until(() => phone!.ws.readyState === WebSocket.CLOSED, 8000, 'phone socket closed by relay');
  phone.close();

  // ---- 重新拉起：新的 worker 世代（epoch 3 → 4）----
  hub = wire(4);
  await until(() => !!relay.proxy.peers.get(hostId)?.ready, 8000, 'tunnel back');
  collector!.start();
  await until(() => host.followers.size > 0, 8000, 'collector refollow');

  // 手机重连：新 socket、HELLO、再 FOCUS。HELLO_ACK 必须报出新的 worker 世代，
  // 否则「装上新插件、手机还连着旧世代」这类问题永远看不出来。
  phone = await phoneOf(port, hostId, phoneLogin.token);
  phone.send({ tag: 'dsh-v2', type: 'HELLO', buildHash: 'android.9', capabilities: ['records', 'pulse', 'cmd'] });
  const hello2 = await new Promise<any>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('no HELLO_ACK after restart')), 8000);
    const listener = (raw: WebSocket.RawData) => {
      const frame = decodeMuxFrame(Buffer.from(raw as Buffer).toString('utf8')) as any;
      if (frame?.type === 'HELLO_ACK') { clearTimeout(timer); phone!.ws.off('message', listener); resolve(frame); }
    };
    phone!.ws.on('message', listener);
  });
  assert.equal(hello2.workerEpoch, 4, '重连后必须看到新的 worker 世代');

  // 新 worker 的内存尾巴是空的（它刚起来），手机只能靠归档补历史——这条也顺带钉住
  // 「重连后不能从零重放整段历史」：afterSeq=2 只该拿回它后面还没有的东西。
  const after = await sync(port, hostId, phoneLogin.token, 2);
  assert.equal(after.status, 200);
  assert.equal(after.body.results[0].nextCursorSeq, 2, '归档里到 seq 2 为止，重连不该倒退');

  // 新事件必须照样到达重连后的手机（这是「隧道重启后不再静默」的核心断言）。
  phone.send({ tag: 'dsh-v2', type: 'FOCUS_SESSION', sessionId: SESSION, sinceSeq: 2 });
  await until(() => phone!.pulses.length >= 0, 100, 'noop');
  host.push(streamId, { type: 'event', event: assistantEvent(3, '还在。') });
  await until(() => phone!.recordsOf().some(r => r.seq === 3), 12000, 'records after tunnel restart');
  await until(() => phone!.pulses.length > 0, 15000, 'pulse after tunnel restart');

  // 脉搏里的 latestSeq 只能前进，不能因为重连而归零（手机据此判断「我落后了」）。
  const last = phone.pulses.at(-1) as any;
  assert.ok(last.latestSeq >= 3, `重连后 latestSeq 必须 >= 3，实际 ${last.latestSeq}`);
  // 手机侧同一条会话的记录不许出现重复 seq。
  const seqs = phone.recordsOf().map(r => r.seq);
  assert.equal(new Set(seqs).size, seqs.length, `重连后出现重复 seq：${seqs.join(',')}`);
});

/** `/control/timeline` 用的 fetch 替身。
 *
 *  <p>中继按 `Host` 头分账户面，而测试里隧道与 HTTP 都走 loopback：`undici` 的
 *  `Headers` 会把 `Host` 当禁写头丢掉，于是请求会以 `127.0.0.1:port` 到达并被
 *  404 掉。这里用 `node:http` 直接发，Host 由我们自己设 —— 与 `local-e2e` 用真
 *  域名 + TLS 达到同一个效果，但不依赖证书。 */
function scopedFetch(): typeof fetch {
  return ((input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input));
    return new Promise((resolve, reject) => {
      const body = init?.body as Buffer | undefined;
      const req = http.request({
        host: '127.0.0.1', port: Number(url.port), path: url.pathname, method: init?.method ?? 'POST',
        headers: { Host: 'relay.example.test', ...(init?.headers as Record<string, string>), ...(body ? { 'content-length': body.length } : {}) },
      }, res => {
        const chunks: Buffer[] = [];
        res.on('data', c => chunks.push(c));
        res.on('end', () => resolve({ ok: (res.statusCode ?? 500) < 300, status: res.statusCode ?? 500 } as Response));
      });
      req.on('error', reject);
      req.end(body);
    }) as Promise<Response>;
  }) as typeof fetch;
}

function postJson(port: number, path: string, token: string, body: unknown) {
  return new Promise<{ status: number; body: any }>((resolve, reject) => {
    const text = JSON.stringify(body);
    const req = http.request({
      host: '127.0.0.1', port, path, method: 'POST',
      headers: { Host: 'relay.example.test', authorization: `Bearer ${token}`, 'content-type': 'application/json', 'content-length': Buffer.byteLength(text) },
    }, res => {
      const chunks: Buffer[] = []; res.on('data', c => chunks.push(c));
      res.on('end', () => {
        const raw = Buffer.concat(chunks);
        // The archive endpoint answers gzip when asked; we did not ask, but be explicit.
        const plain = res.headers['content-encoding'] === 'gzip' ? gunzipSync(raw) : raw;
        let parsed: any = plain.toString('utf8');
        try { parsed = JSON.parse(parsed); } catch { /* keep raw */ }
        resolve({ status: res.statusCode!, body: parsed });
      });
    });
    req.on('error', reject); req.end(text);
  });
}

/**
 * 换插件包时，上一代 worker 可能「还没死透就重连」。
 *
 * <p>`supervisor.kill()` 先发 `{type:'stop'}` 等 `killTimeoutMs`（3 秒）再 SIGKILL。
 * 一个卡在自己事件循环里的子进程在这 3 秒里仍持有隧道的重连定时器：中继这边一旦看到
 * 新 worker 的隧道，旧 worker 的重连就会在它之后到达，而「后到的胜出」正是中继 v1 的
 * 行为。结果是手机对着**旧代码 + 旧 outbox 文件**说话，而插件设置页显示一切正常。
 *
 * <p>钉两件事：①新一代上线后，老一代的 authenticate 必须被拒（4423），且不能把新隧道
 * 挤下线；②反过来的顺序（旧的先连、新的后连）必须正常接管 —— 否则正常升级也连不上。
 */
test('worker epoch: a stale generation cannot reclaim the tunnel from a newer one', { timeout: 30000 }, async t => {
  const relay = new Relay({ baseUrl: 'https://relay.example.test', database: ':memory:', key: Buffer.alloc(32, 9), requireTotp: false, testMode: true });
  const port = await relay.listen(0);
  t.after(async () => { await relay.close(); });

  await relay.store.createAccount('b@example.test', 'long-test-password', false);
  const login = await relay.store.login('b@example.test', 'long-test-password', '', false);
  const admin = relay.store.session(login.token, 'account');
  const enrollment = { operationId: uuid(), hostPublicId: uuid(), hostTunnelToken: secret(), alias: 'epoch' };
  const { hostId } = relay.store.enroll(relay.store.invitation(admin), enrollment);

  /** 手工走 auth → hello → ready；`closed()` 给出隧道收到的关闭码（未关闭时为 null）。 */
  const dial = (workerEpoch: number) => new Promise<{ ws: WebSocket; closed: () => number | null }>(resolve => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}/tunnel`, { headers: { Host: 'relay.example.test' } });
    let code: number | null = null;
    ws.on('close', c => { code = c; });
    ws.on('message', raw => {
      const f = JSON.parse(String(raw));
      if (f.type === 'auth-ok') ws.send(JSON.stringify({ v: 3, type: 'hello', capabilities: ['flow', 'drain', 'ws-message'], limits: { fragment: 32768, window: 262144, maxBody: 300 * 1024 * 1024, maxWsMessage: 4 * 1024 * 1024, streams: 64 } }));
    });
    ws.on('open', () => {
      ws.send(JSON.stringify({ v: 3, type: 'authenticate', hostId, credential: enrollment.hostTunnelToken, workerEpoch }));
      resolve({ ws, closed: () => code });
    });
  });

  // 新一代（epoch 2）先上线。
  const fresh = await dial(2);
  await until(() => relay.proxy.peers.get(hostId)?.ready === true, 5000, 'gen-2 ready');
  assert.equal(relay.proxy.peers.get(hostId)?.workerEpoch, 2);

  // 老一代（epoch 1）迟到重连：必须被拒，且不能顶掉新一代。
  const stale = await dial(1);
  await until(() => stale.closed() !== null, 5000, 'stale generation rejected');
  assert.equal(stale.closed(), 4423, 'a worker older than the live one is refused');
  assert.equal(relay.proxy.peers.get(hostId)?.workerEpoch, 2, 'the newer generation keeps the tunnel');
  assert.equal(relay.proxy.peers.get(hostId)?.ready, true);

  // 新一代（epoch 3）迟到上线：正常接管，旧的被温和踢掉。
  const newer = await dial(3);
  await until(() => relay.proxy.peers.get(hostId)?.workerEpoch === 3, 5000, 'gen-3 takes over');
  await until(() => fresh.closed() !== null, 5000, 'gen-2 retired');
  assert.equal(relay.proxy.peers.get(hostId)?.ready, true);
  assert.equal(relay.proxy.peers.get(hostId)?.workerEpoch, 3);

  fresh.ws.close(); stale.ws.close(); newer.ws.close();
});
