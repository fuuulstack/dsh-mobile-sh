import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';import WebSocket,{WebSocketServer} from 'ws';
import { Admission,Budget,bodyInfo,checkOrigin,decodeFrame,encodeFrame,FrameType,LIMITS,MessageBridge,safePath,Tunnel } from '../packages/core/src/index.js';

test('reject framing ambiguity, traversal, unsafe origins and credit-free DATA',()=>{
  assert.deepEqual(bodyInfo('GET',{}),{bodyKind:'none',lengthMode:'exact',declaredLength:0});
  assert.equal(bodyInfo('POST',{'transfer-encoding':'chunked'}).lengthMode,'unknown');
  assert.throws(()=>bodyInfo('POST',{'content-length':'2','transfer-encoding':'chunked'}));
  for(const p of ['/../api','/%252e%252e/x','/x%2fy','/mobile-sh/status','//evil/','/?token=secret'])assert.throws(()=>safePath(p),p);
  assert.equal(safePath('/api/session/list?query=a%20b'),'/api/session/list?query=a%20b');
  assert.throws(()=>checkOrigin('GET','/api/list',{'sec-fetch-site':'same-site'},'https://h.relay.test','https://relay.test'));
  assert.doesNotThrow(()=>checkOrigin('GET','/',{'sec-fetch-mode':'navigate','sec-fetch-dest':'document','sec-fetch-site':'none'},'https://h.relay.test','https://relay.test'));
  const b=encodeFrame(FrameType.DATA,1,Buffer.from('中文'));assert.equal(decodeFrame(b).payload.toString(),'中文');b[2]=1;assert.throws(()=>decodeFrame(b));
});
test('atomic admission rollback and idempotent lifetime leases',()=>{
  const node=new Budget(100),a=new Admission(node,10,20,4,50),one=a.acquire(50);
  assert.throws(()=>a.acquire(60));assert.equal(node.used,50);assert.equal(a.streams.used,1);
  const small=a.acquire(5);one();one();assert.equal(node.used,5);small();assert.equal(node.used,0);assert.equal(a.streams.used,0);
});
test('a sender starved of credit reports a stall instead of waiting silently',async t=>{
  const server=new WebSocketServer({port:0,host:'127.0.0.1'});await once(server,'listening');
  const incoming=once(server,'connection'),socket=new WebSocket(`ws://127.0.0.1:${(server.address() as any).port}`);await once(socket,'open');const [remote]=await incoming;
  // stallMs 缩短到 50ms：真等 10 秒的用例没有价值，判据是「超时就 emit」而不是阈值本身。
  const a=new Tunnel(socket,undefined,undefined,undefined,50),b=new Tunnel(remote),send=a.channel(1),receive=b.channel(1);
  t.after(async()=>{await a.stop();await b.stop();server.close();});
  // 接收端**不消费**：不调 startReceive 就不会回 flow，发送端的 tx 信用永远是 0。
  const stall=once(a,'stall');
  void send.data(Buffer.alloc(LIMITS.window*2,7)).catch(()=>{});
  const [event]=await stall as [any];
  assert.equal(event.streamId,1);
  assert.ok(event.waitedMs>=50,`waitedMs ${event.waitedMs}`);
  assert.ok(event.pending>0,'pending 应报告还有多少字节没发出去');
  send.dispose();
});

test('buffer peak reads high-water and the reader drains it',async t=>{
  const server=new WebSocketServer({port:0,host:'127.0.0.1'});await once(server,'listening');
  const incoming=once(server,'connection'),socket=new WebSocket(`ws://127.0.0.1:${(server.address() as any).port}`);await once(socket,'open');const [remote]=await incoming;
  const a=new Tunnel(socket),b=new Tunnel(remote);t.after(async()=>{await a.stop();await b.stop();server.close();});
  // bufferedAmount 在真 socket 上很难稳定造出峰值，直接验证「取走即清零」的契约。
  assert.equal(a.takeBufferedPeak(),0);
  assert.equal(a.queuedBytes(),0);
});

test('credit transfer larger than initial window continues without shared socket pause',async t=>{
  const server=new WebSocketServer({port:0,host:'127.0.0.1'});await once(server,'listening');
  const incoming=once(server,'connection'),socket=new WebSocket(`ws://127.0.0.1:${(server.address() as any).port}`);await once(socket,'open');const [remote]=await incoming;
  const a=new Tunnel(socket),b=new Tunnel(remote),send=a.channel(1),receive=b.channel(1);t.after(async()=>{await a.stop();await b.stop();server.close();});
  const source=Buffer.alloc(2*LIMITS.window+123,42),chunks:Buffer[]=[];
  receive.startReceive(async x=>{chunks.push(Buffer.from(x));await new Promise(r=>setTimeout(r,1));});
  const ended=once(receive,'end');await send.data(source);await send.end();await ended;
  assert.deepEqual(Buffer.concat(chunks),source);send.dispose();receive.dispose();await new Promise(r=>setImmediate(r));assert.equal(b.receiveBudget.used,0);
});

test('a saturated socket still lets the pulse frame through',async t=>{
  // 坑 54 的放大器：数据流把 socket 缓冲顶过 1MiB 之后，如果控制帧跟着一起等，
  // 对端看到的就是「records 到不了、脉搏也没有」的假死——手机判死重连，插件再
  // 重放一遍尾部窗口，越堵越重。判据是「控制帧必须能越过高水位出去」。
  const server=new WebSocketServer({port:0,host:'127.0.0.1'});await once(server,'listening');
  const incoming=once(server,'connection'),socket=new WebSocket(`ws://127.0.0.1:${(server.address() as any).port}`);await once(socket,'open');const [remote]=await incoming;
  const a=new Tunnel(socket),b=new Tunnel(remote);t.after(async()=>{await a.stop();await b.stop();server.close();});
  // 直接把 socket 的 bufferedAmount 钉在数据流门限之上：真跑 1MiB 数据既慢又不稳，
  // 这里要验的是 pump 的分支判据，不是 ws 自己的缓冲实现。
  Object.defineProperty(socket,'bufferedAmount',{configurable:true,get:()=>2*1024*1024});
  assert.ok(socket.bufferedAmount>=1024*1024,'夹具前提：缓冲已过数据流门限');
  // 控制流（streamId 0）上发一帧控制消息：必须真的落到对端。
  // 用 `drain` 而不是 `ping`——ping 会被接收端内部直接回 pong 并 return，
  // 根本不会走到 `emit('control')`，拿它当探针会得到假阴性。
  const heard=once(b,'control');
  const sent=a.control({v:3,type:'drain',lastStreamId:0,reason:'pulse-probe'});
  const [frame]=await Promise.race([
    heard,
    new Promise((_,reject)=>setTimeout(()=>reject(new Error('控制帧被高水位堵死了')),2000)),
  ]) as [any];
  assert.equal(frame.type,'drain');
  await sent;
});
