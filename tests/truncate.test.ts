import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { Relay } from '../packages/relay/src/server.js';
import { HostClient } from '../packages/plugin/src/client.js';
import { secret,uuid } from '../packages/relay/src/crypto.js';

async function waitFor(fn:()=>boolean,ms=5000){const start=Date.now();while(!fn()){if(Date.now()-start>ms)throw new Error('wait timeout');await new Promise(r=>setTimeout(r,10));}}

// 146979 bytes of JSON, written in 46 chunks over ~3 s: the shape production has when a
// session page streams out of a busy DSH and the relay side is slow to drain it.
const BODY=(()=>{
  const records=Array.from({length:26},(_,i)=>({type:'event',event:{type:'assistant/message',seq:i,data:{message:{content:[{type:'text',text:'x'.repeat(5400)+i}]}}}}));
  return Buffer.from(JSON.stringify({type:'server-response',rpcId:'p',result:{ok:true,value:{cursor:188,hasMore:true,records}}}));
})();

test('a slow large JSON page survives repeated requests', {timeout:120000},async t=>{
  const upstream=http.createServer(async(req,res)=>{
    for await(const _c of req){}
    res.setHeader('content-type','application/json');
    res.setHeader('content-length',String(BODY.length));
    const step=Math.ceil(BODY.length/46);
    for(let o=0;o<BODY.length;o+=step){
      res.write(BODY.subarray(o,o+step));
      await new Promise(r=>setTimeout(r,60));
    }
    res.end();
  });upstream.listen(0,'127.0.0.1');await once(upstream,'listening');const upstreamPort=(upstream.address() as any).port;
  const relay=new Relay({baseUrl:'https://relay.example.test',database:':memory:',key:Buffer.alloc(32,3),requireTotp:false,testMode:true});
  const port=await relay.listen(0);
  let client:HostClient|undefined;
  t.after(async()=>{await client?.stop();await relay.close();await new Promise<void>(r=>upstream.close(()=>r()));});
  await relay.store.createAccount('a@example.test','long-test-password',false);
  const adminLogin=await relay.store.login('a@example.test','long-test-password','',false),admin=relay.store.session(adminLogin.token,'account');
  const enrollment={operationId:uuid(),hostPublicId:uuid(),hostTunnelToken:secret(),alias:'test'};
  const {hostId}=relay.store.enroll(relay.store.invitation(admin),enrollment);
  const phone=await relay.store.login('a@example.test','long-test-password','',false,'Android',true),s=relay.store.session(phone.token,'native');
  relay.store.pair(s,relay.store.createPair(admin,hostId).code);
  client=new HostClient({relayUrl:`ws://127.0.0.1:${port}/tunnel`,hostId,hostTunnelToken:enrollment.hostTunnelToken,upstreamOrigin:`http://127.0.0.1:${upstreamPort}`,getCookie:async()=>'dsh-auth-test=local-secret'});
  client.options.headers={Host:'relay.example.test'};
  client.connect();await waitFor(()=>!!relay.proxy.peers.get(hostId)?.ready);

  const ask=(omit?:string)=>new Promise<{status:number;length:number;head:string}>((resolve,reject)=>{
    const body=Buffer.from('{}');
    const req=http.request({hostname:'127.0.0.1',port,path:`/native/v2/hosts/${hostId}/rpc/page`,method:'POST',
      headers:{host:'relay.example.test',authorization:`Bearer ${phone.token}`,'content-type':'application/json','content-length':body.length,...(omit?{'x-dsh-omit':omit}:{})}},res=>{
      const chunks:Buffer[]=[];res.on('data',b=>chunks.push(b));
      res.on('end',()=>resolve({status:res.statusCode!,length:Buffer.concat(chunks).length,head:Buffer.concat(chunks).subarray(0,40).toString()}));
      res.on('error',e=>reject(e));
    });
    req.on('error',reject);req.end(body);
  });

  const failures:string[]=[];
  for(let i=0;i<8;i++){
    const plain=await ask();
    if(plain.status!==200||plain.length!==BODY.length)failures.push(`plain#${i} status=${plain.status} bytes=${plain.length} want=${BODY.length}`);
  }
  assert.deepEqual(failures,[],`truncated responses: ${failures.join('; ')}`);
});

test('fast upstream response with delayed client write does not truncate from post-close cancel', {timeout:30000}, async t => {
  const upstream=http.createServer(async(req,res)=>{
    for await(const _c of req){}
    res.setHeader('content-type','application/json');
    res.setHeader('content-length',String(BODY.length));
    res.end(BODY);
  });
  upstream.listen(0,'127.0.0.1');await once(upstream,'listening');const upstreamPort=(upstream.address() as any).port;
  const relay=new Relay({baseUrl:'https://relay.example.test',database:':memory:',key:Buffer.alloc(32,3),requireTotp:false,testMode:true});
  const port=await relay.listen(0);
  let client:HostClient|undefined;
  t.after(async()=>{await client?.stop();await relay.close();await new Promise<void>(r=>upstream.close(()=>r()));});
  await relay.store.createAccount('a@example.test','long-test-password',false);
  const adminLogin=await relay.store.login('a@example.test','long-test-password','',false),admin=relay.store.session(adminLogin.token,'account');
  const enrollment={operationId:uuid(),hostPublicId:uuid(),hostTunnelToken:secret(),alias:'test'};
  const {hostId}=relay.store.enroll(relay.store.invitation(admin),enrollment);
  const phone=await relay.store.login('a@example.test','long-test-password','',false,'Android',true),s=relay.store.session(phone.token,'native');
  relay.store.pair(s,relay.store.createPair(admin,hostId).code);
  client=new HostClient({relayUrl:`ws://127.0.0.1:${port}/tunnel`,hostId,hostTunnelToken:enrollment.hostTunnelToken,upstreamOrigin:`http://127.0.0.1:${upstreamPort}`,getCookie:async()=>'dsh-auth-test=local-secret'});
  client.options.headers={Host:'relay.example.test'};
  client.connect();await waitFor(()=>!!relay.proxy.peers.get(hostId)?.ready);

  const origHttp = relay.proxy.http.bind(relay.proxy);
  relay.proxy.http = async (req, res, hId, sessionRow, native) => {
    const origWrite = res.write.bind(res);
    let chunkIndex = 0;
    res.write = function(chunk: any, ...args: any[]) {
      chunkIndex++;
      if (chunkIndex === 3) {
        setTimeout(() => {
          // Simulate the exact race: a late cancel (reason: unknown-stream) arrives at relay while
          // response data is still being flushed to the client after host closed the stream.
          const peer = relay.proxy.peers.get(hostId);
          if (peer) {
            for (const ch of (peer.tunnel as any).channels.values()) {
              ch.control({ v: 3, type: 'cancel', streamId: ch.id, reason: 'unknown-stream' });
            }
          }
          origWrite(chunk, ...args);
        }, 30);
        return false;
      }
      return origWrite(chunk, ...args);
    } as any;
    return origHttp(req, res, hId, sessionRow, native);
  };

  const body = Buffer.from('{}');
  const result = await new Promise<{status:number;length:number;error?:string}>((resolve) => {
    const req = http.request({
      hostname: '127.0.0.1', port, path: `/native/v2/hosts/${hostId}/rpc/page`, method: 'POST',
      headers: { host: 'relay.example.test', authorization: `Bearer ${phone.token}`, 'content-type': 'application/json', 'content-length': body.length }
    }, (res) => {
      let total = 0;
      res.on('data', chunk => total += chunk.length);
      res.on('end', () => resolve({ status: res.statusCode!, length: total }));
      res.on('error', e => resolve({ status: res.statusCode!, length: total, error: e.message }));
      res.on('close', () => {
        if (!res.complete) resolve({ status: res.statusCode!, length: total, error: 'premature-close' });
      });
    });
    req.on('error', e => resolve({ status: 500, length: 0, error: e.message }));
    req.end(body);
  });
  assert.equal(result.status, 200);
  assert.equal(result.length, BODY.length);
  assert.equal(result.error, undefined);
});

test('transport tombstone and flow exemption suppress spurious unknown-stream cancels', async () => {
  const { EventEmitter } = await import('node:events');
  const { Tunnel } = await import('../packages/core/src/transport.js');
  const { Budget } = await import('../packages/core/src/budget.js');
  const sent: any[] = [];
  const fakeSocket = new EventEmitter() as any;
  fakeSocket.readyState = 1;
  fakeSocket.send = (data: any, ...args: any[]) => {
    if (typeof data === 'string') sent.push(JSON.parse(data));
    const cb = args.find(a => typeof a === 'function');
    cb?.();
  };
  fakeSocket.close = () => {};
  const tunnel = new Tunnel(fakeSocket, new Budget(1024 * 1024));
  const ch = tunnel.channel(41);
  ch.dispose(); // disposed channel, streamId 41 added to closedStreams tombstone

  // 1. Deliver trailing flow frame for disposed stream 41
  fakeSocket.emit('message', Buffer.from(JSON.stringify({ v: 3, type: 'flow', streamId: 41, delta: 32768 })), false);

  // 2. Deliver trailing control frame for disposed stream 41 (tombstoned)
  fakeSocket.emit('message', Buffer.from(JSON.stringify({ v: 3, type: 'stream-ready', streamId: 41 })), false);

  // 3. Deliver flow frame for unknown stream 999
  fakeSocket.emit('message', Buffer.from(JSON.stringify({ v: 3, type: 'flow', streamId: 999, delta: 32768 })), false);

  // Assert no spurious cancels were generated
  const spuriousCancels = sent.filter(f => f.type === 'cancel' && f.reason === 'unknown-stream');
  assert.equal(spuriousCancels.length, 0, `Spurious cancels sent: ${JSON.stringify(spuriousCancels)}`);

  // 4. Verify that an unexpected non-exempt frame on an UNKNOWN stream (e.g. stream 889 never seen) DOES trigger unknown
  fakeSocket.emit('message', Buffer.from(JSON.stringify({ v: 3, type: 'stream-ready', streamId: 889 })), false);
  const expectedCancels = sent.filter(f => f.type === 'cancel' && f.streamId === 889 && f.reason === 'unknown-stream');
  assert.equal(expectedCancels.length, 1);
});
