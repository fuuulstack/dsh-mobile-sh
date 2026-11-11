import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { Relay } from '../packages/relay/src/server.js';
import { ChangeBus, streamChanges } from '../packages/relay/src/changes.js';
import { uuid, secret } from '../packages/relay/src/crypto.js';

function request(port: number, path: string, token: string, data?: Buffer, method = 'POST') {
  return new Promise<{ status: number; body: Buffer }>((resolve, reject) => {
    const req = http.request({
      hostname: '127.0.0.1', port, path, method,
      headers: { host: 'relay.example.test', authorization: `Bearer ${token}`, ...(data ? { 'content-type': 'application/json', 'content-length': data.length } : {}) },
    }, res => {
      const chunks: Buffer[] = [];
      res.on('data', b => chunks.push(b));
      res.on('end', () => resolve({ status: res.statusCode!, body: Buffer.concat(chunks) }));
    });
    req.on('error', reject);
    req.end(data);
  });
}

/** 开一条 SSE 长连接，把收到的 change 帧收集起来。 */
function openChanges(port: number, hostId: string, token: string) {
  return new Promise<{ close: () => void; changes: Array<Record<string, unknown>>; ready: Promise<void> }>((resolve, reject) => {
    let settled = false;
    const changes: Array<Record<string, unknown>> = [];
    const req = http.request({
      hostname: '127.0.0.1', port, path: `/native/v2/hosts/${hostId}/changes`, method: 'GET',
      headers: { host: 'relay.example.test', authorization: `Bearer ${token}`, accept: 'text/event-stream' },
    }, res => {
      if (res.statusCode !== 200) { reject(new Error(`status ${res.statusCode}`)); return; }
      let buffer = '';
      res.setEncoding('utf8');
      res.on('data', chunk => {
        buffer += chunk;
        let idx: number;
        while ((idx = buffer.indexOf('\n\n')) >= 0) {
          const frame = buffer.slice(0, idx);
          buffer = buffer.slice(idx + 2);
          if (frame.startsWith('event: change')) {
            const line = frame.split('\n').find(l => l.startsWith('data: '));
            if (line) { try { changes.push(JSON.parse(line.slice(6)) as Record<string, unknown>); } catch { /* 忽略坏帧 */ } }
          }
          if (!settled) { settled = true; resolve({ close: () => req.destroy(), changes, ready: Promise.resolve() }); }
        }
      });
      res.on('end', () => { if (!settled) { settled = true; resolve({ close: () => req.destroy(), changes, ready: Promise.resolve() }); } });
    });
    req.on('error', e => { if (!settled) reject(e); });
    req.end();
  });
}

async function waitFor(fn: () => boolean, ms = 4000) {
  const start = Date.now();
  while (!fn()) { if (Date.now() - start > ms) throw new Error('wait timeout'); await new Promise(r => setTimeout(r, 20)); }
}

test('subscribers only receive changes for their own host', { timeout: 20000 }, async t => {
  const relay = new Relay({ baseUrl: 'https://relay.example.test', database: ':memory:', key: Buffer.alloc(32, 9), requireTotp: false, testMode: true });
  const port = await relay.listen(0);
  t.after(async () => { await relay.close(); });

  await relay.store.createAccount('a@example.test', 'long-test-password', false);
  const admin = relay.store.session((await relay.store.login('a@example.test', 'long-test-password', '', false)).token, 'account');
  const oneToken = secret(); const twoToken = secret();
  const one = relay.store.enroll(relay.store.invitation(admin), { operationId: uuid(), hostPublicId: uuid(), hostTunnelToken: oneToken, alias: 'host-one' });
  const two = relay.store.enroll(relay.store.invitation(admin), { operationId: uuid(), hostPublicId: uuid(), hostTunnelToken: twoToken, alias: 'host-two' });  const phone = await relay.store.login('a@example.test', 'long-test-password', '', false, 'Android', true);
  const native = relay.store.session(phone.token, 'native');
  relay.store.pair(native, relay.store.createPair(admin, one.hostId).code);
  relay.store.pair(native, relay.store.createPair(admin, two.hostId).code);

  const feed = await openChanges(port, one.hostId, phone.token);
  t.after(() => feed.close());
  await waitFor(() => relay.changes.size() === 1);

  await request(port, '/control/timeline', twoToken, Buffer.from(JSON.stringify({
    hostId: two.hostId,
    batchId: 'other-host-1',
    records: [{ sessionId: 'session-x', seq: 1, kind: 'ASSISTANT_TEXT', record: { event: { seq: 1 } } }],
  })));

  await new Promise(r => setTimeout(r, 200));
  assert.equal(feed.changes.length, 0, '别的主机的变更不该推给这条订阅');
});

test('the bus drops frames for a slow subscriber instead of queueing without bound', () => {
  const bus = new ChangeBus();
  let seen = 0;
  const off = bus.subscribe('h'.repeat(32), 'device', () => { seen++; });
  // 第一条正常送达；此后模拟「写不出去」的慢客户端不应让总线无限排队。
  bus.publish('h'.repeat(32), [{ sessionId: 's', latestSeq: 1, title: '', running: false, at: Date.now() }]);
  assert.equal(seen, 1);
  off();
  bus.publish('h'.repeat(32), [{ sessionId: 's', latestSeq: 2, title: '', running: false, at: Date.now() }]);
  assert.equal(seen, 1, '退订之后不再收到');
  assert.equal(bus.size(), 0);
});

test('a stuck SSE socket is counted as backpressure, and only its notifications are dropped', () => {
  // ChangeBus 里那个同步 pending 计数器不代表 socket 队列：sink() 调 res.write()
  // 立刻返回，pending++/-- 在一帧内就闭合，永远是 0。真正能说明堵住的只有
  // res.write() 返回 false + 此后一直没 drain。
  const bus = new ChangeBus();
  const written: string[] = [];
  let blocked = true;
  const fakeReq = { once: () => {} } as unknown as Parameters<typeof streamChanges>[0];
  let drainHandler: (() => void) | undefined;
  const fakeRes = {
    writableEnded: false,
    writeHead: () => {},
    write: (chunk: string) => {
      if (chunk.startsWith(': ping')) return true;
      written.push(chunk);
      return !blocked; // 第一条就顶到高水位，之后一直没 drain
    },
    once: (event: string, fn: () => void) => { if (event === 'drain') drainHandler = fn; },
    end: () => {},
  } as unknown as Parameters<typeof streamChanges>[1];

  streamChanges(fakeReq, fakeRes, bus, 'h'.repeat(32), 'device');
  const changes = [1, 2, 3].map(seq => ({ sessionId: 's', latestSeq: seq, title: '', running: false, at: Date.now() }));
  const delivered = bus.publish('h'.repeat(32), changes);

  // 只有第一条真的写进去了；后两条被丢弃，但**归档记录一条没丢**，手机按游标补拉。
  assert.equal(written.filter(w => w.startsWith('event: change')).length, 1);
  assert.equal(bus.stats().drains, 1, 'res.write() 返回 false 才算一次真实背压');
  assert.equal(bus.stats().dropped, 2, '堵住期间的变更通知被丢弃');
  // publish 的返回值必须和实际写出去的帧数一致：被 sink 丢弃的那些不算送达。
  assert.equal(delivered, 1, 'publish 只把真写进去的算作 delivered');

  // drain 之后恢复推送。
  blocked = false;
  drainHandler?.();
  bus.publish('h'.repeat(32), [{ sessionId: 's', latestSeq: 4, title: '', running: false, at: Date.now() }]);
  assert.equal(written.filter(w => w.startsWith('event: change')).length, 2);
  assert.equal(bus.stats().drains, 1, '恢复之后不再累加');
});

/** 建一个已配对好、能开 SSE 的中继，返回现成的推送/订阅句柄。 */
/**
 * v2 归档通路（`/control/timeline`）也必须扇出变更帧 —— 2026-11-02 修的就是这里。
 *
 * <p>原来这条路由**完全不 publish**，理由写在注释里：「v2 删了 SSE 变更总线，只有
 * 收得到它的 v1 app 会读，而 v1 app 渲染不了 v2 行」。那句话对**正文**成立，对
 * **信号**不成立：v2 手机（`AppViewModel.openStreams` → `gateway.watchChanges`）一直
 * 开着 `/native/v2/changes`，全靠这一帧触发 `requestSessionSync` 再去 `/sync` 拉正文。
 * 不 publish 的后果是**会话列表页永远不会知道自己该刷新**：只有进聊天页、控制流
 * 聚焦之后才有数据，所以现象是「必须进一次聊天页再退出来，预览才更新」。
 *
 * <p>这条路径只发信号、**不捎正文**：v2 行是宿主原始事件，逐条 JSON 一遍塞进变更帧
 * 既费 CPU 又会去撞 `CHANGE_BODY_BUDGET`，而手机本来就要按游标去 `/sync` 拉。
 */
test('v2 timeline push fans out a signal-only change (no bodies)', { timeout: 20000 }, async t => {
  const relay = new Relay({ baseUrl: 'https://relay.example.test', database: ':memory:', key: Buffer.alloc(32, 9), requireTotp: false, testMode: true });
  const port = await relay.listen(0);
  t.after(async () => { await relay.close(); });

  await relay.store.createAccount('a@example.test', 'long-test-password', false);
  const admin = relay.store.session((await relay.store.login('a@example.test', 'long-test-password', '', false)).token, 'account');
  const enrollment = { operationId: uuid(), hostPublicId: uuid(), hostTunnelToken: secret(), alias: 'timeline-fanout-host' };
  const enrolled = relay.store.enroll(relay.store.invitation(admin), enrollment); const hostId = enrolled.hostId;
  const phone = await relay.store.login('a@example.test', 'long-test-password', '', false, 'Android', true);
  relay.store.pair(relay.store.session(phone.token, 'native'), relay.store.createPair(admin, hostId).code);

  const feed = await openChanges(port, hostId, phone.token);
  t.after(() => feed.close());
  await waitFor(() => relay.changes.size() === 1);

  const push = await request(port, '/control/timeline', enrollment.hostTunnelToken, Buffer.from(JSON.stringify({
    hostId,
    batchId: 'timeline-fanout-1',
    records: [
      { sessionId: 'session-1', seq: 100, kind: 'ASSISTANT_TEXT', record: { type: 'assistant/message', seq: 100, data: {} }, payload: { type: 'assistant/message', seq: 100, data: {} } },
      { sessionId: 'session-1', seq: 104, kind: 'TURN_END', record: { type: 'turn/end', seq: 104, data: {} }, payload: { type: 'turn/end', seq: 104, data: {} } },
      { sessionId: 'session-2', seq: 7, kind: 'USER_INPUT', record: { type: 'user/message', seq: 7, data: {} }, payload: { type: 'user/message', seq: 7, data: {} } },
    ],
    // 标题与运行态来自同一批带上来的仓库清单项（v2 行里没有这两个字段）。
    meta: [{ sessionId: 'session-1', item: { running: true, projections: { values: { title: '列表页预览' } } } }],
  })));
  assert.equal(push.status, 200);
  assert.equal(push.body.subarray(0, 1).toString('utf8'), '{', '归档推送应返回 JSON');

  await waitFor(() => feed.changes.length >= 2);
  const byId = new Map(feed.changes.map(c => [String(c.sessionId), c]));
  const s1 = byId.get('session-1')!;
  assert.equal(s1.latestSeq, 104, '同一会话的多条记录收敛成一条变更，取最大 seq');
  assert.equal(s1.title, '列表页预览', '标题取自仓库清单项');
  assert.equal(s1.running, true, '运行态取自仓库清单项');
  assert.equal('records' in s1, false, 'v2 通路只发信号，不捎正文');
  assert.equal(byId.get('session-2')!.latestSeq, 7, '每个变化过的会话各一条');
  assert.equal(typeof s1.at, 'number');

  // 空记录不该造出空变更（`changes.publish` 一次也不该被调到）。
  const before = feed.changes.length;
  await request(port, '/control/timeline', enrollment.hostTunnelToken, Buffer.from(JSON.stringify({
    hostId, batchId: 'timeline-fanout-empty', records: [],
  })));
  await new Promise(r => setTimeout(r, 300));
  assert.equal(feed.changes.length, before, '没有记录就不要扇出');
});
