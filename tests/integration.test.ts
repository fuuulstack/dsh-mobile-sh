import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import WebSocket,{WebSocketServer} from 'ws';
import { Relay } from '../packages/relay/src/server.js';
import { HostClient } from '../packages/plugin/src/client.js';
import { ChannelSocket } from '../packages/plugin/src/control.js';
import { MessageBridge } from '../packages/core/src/index.js';
import { secret,uuid } from '../packages/relay/src/crypto.js';

/**
 * 只回声的 v2 控制端点。
 *
 * <p>v2 把手机的 `…/control` 套接字**终结在插件里**（`packages/plugin/src/client.ts`
 * 的 `CONTROL_PATH` 分支），不再桥到宿主的上游 WS。本用例要验的是「中继 ↔ 插件的
 * 字节透传」这一段：手机发什么，原样收回来。所以这里不建真的 `ControlSession`
 * （它会讲协议帧：ready/replay/pulse），而是拿 `ChannelSocket` 做一个哑端点——
 * 收到的每个**完整消息**（`send()` 拿到的是 `MessageBridge.deliver()` 重组好的
 * 整包，不是分片）原样 `push()` 回去。
 *
 * <p>不传 `control` 的话插件会回 `stream-reject 503 control-disabled`
 * （`client.ts:113`），这正是本用例一度失败的原因。
 */
const echoControl = (channel: import('../packages/core/src/index.js').Channel) => {
  const socket=new ChannelSocket(()=>{});
  socket.onFrame=(raw,binary)=>socket.push(raw,binary);
  const bridge=new MessageBridge(socket as never,channel,4*1024*1024,()=>{});
  return {
    accept:async()=>{await channel.send({v:3,type:'ws-accept',protocol:''});void bridge;},
    close:()=>socket.close(),
  };
};

async function waitFor(fn:()=>boolean,ms=5000){const start=Date.now();while(!fn()){if(Date.now()-start>ms)throw new Error('wait timeout');await new Promise(r=>setTimeout(r,10));}}
function request(port:number,path:string,token:string,data?:Buffer,method='POST') {return new Promise<{status:number;body:Buffer;headers:http.IncomingHttpHeaders}>((resolve,reject)=>{
  const req=http.request({hostname:'127.0.0.1',port,path,method,headers:{host:'relay.example.test',authorization:`Bearer ${token}`,...(data?{'content-type':'application/json','content-length':data.length}:{})}},res=>{
    const chunks:Buffer[]=[];res.on('data',b=>chunks.push(b));res.on('end',()=>resolve({status:res.statusCode!,body:Buffer.concat(chunks),headers:res.headers}));
  });req.on('error',reject);req.end(data);
});}
test('Android bearer -> Relay -> Host plugin HTTP + WS, device A revoke preserves B tunnel', {timeout:20000},async t=>{
  const upstream=http.createServer(async(req,res)=>{
    assert.equal(req.headers.cookie,'dsh-auth-test=local-secret');assert.equal(req.headers.origin,`http://127.0.0.1:${upstreamPort}`);
    const chunks:Buffer[]=[];for await(const c of req)chunks.push(c);res.setHeader('set-cookie','dsh-auth-test=must-not-leak');res.setHeader('content-type','application/json');res.end(Buffer.concat(chunks));
  });upstream.listen(0,'127.0.0.1');await once(upstream,'listening');const upstreamPort=(upstream.address() as any).port;
  const echo=new WebSocketServer({noServer:true});upstream.on('upgrade',(req,socket,head)=>echo.handleUpgrade(req,socket,head,ws=>ws.on('message',(b,binary)=>ws.send(b,{binary}))));
  const relay=new Relay({baseUrl:'https://relay.example.test',database:':memory:',key:Buffer.alloc(32,3),requireTotp:false,testMode:true});const port=await relay.listen(0);
  let client:HostClient|undefined;
  t.after(async()=>{await client?.stop();await relay.close();echo.close();await new Promise<void>(r=>upstream.close(()=>r()));});
  await relay.store.createAccount('a@example.test','long-test-password',false);
  const adminLogin=await relay.store.login('a@example.test','long-test-password','',false),admin=relay.store.session(adminLogin.token,'account');
  const enrollment={operationId:uuid(),hostPublicId:uuid(),hostTunnelToken:secret(),alias:'test'};const {hostId}=relay.store.enroll(relay.store.invitation(admin),enrollment);
  const phoneA=await relay.store.login('a@example.test','long-test-password','',false,'Android A',true),a=relay.store.session(phoneA.token,'native');
  const phoneB=await relay.store.login('a@example.test','long-test-password','',false,'Android B',true),b=relay.store.session(phoneB.token,'native');
  relay.store.pair(a,relay.store.createPair(admin,hostId).code);relay.store.pair(b,relay.store.createPair(admin,hostId).code);
  client=new HostClient({relayUrl:`ws://127.0.0.1:${port}/tunnel`,hostId,hostTunnelToken:enrollment.hostTunnelToken,upstreamOrigin:`http://127.0.0.1:${upstreamPort}`,getCookie:async()=>'dsh-auth-test=local-secret',control:echoControl});
  // Test only: Host header points at the canonical domain while transport stays on loopback.
  client.options.headers={Host:'relay.example.test'};
  client.connect();await waitFor(()=>!!relay.proxy.peers.get(hostId)?.ready);
  const payload=Buffer.from(JSON.stringify({message:'中文'.repeat(200000)}));const response=await request(port,`/native/v2/hosts/${hostId}/rpc/prompt`,phoneA.token,payload);
  assert.equal(response.status,200);assert.deepEqual(response.body,payload);assert.equal(response.headers['set-cookie'],undefined);
  const ws=new WebSocket(`ws://127.0.0.1:${port}/native/v2/hosts/${hostId}/control`,{headers:{Host:'relay.example.test',Authorization:`Bearer ${phoneB.token}`}});await once(ws,'open');
  const text='中文🙂'.repeat(20000),message=once(ws,'message');ws.send(text);const [received,binary]=await message;assert.equal(binary,false);assert.equal(received.toString(),text);
  // Several frames may already be in ws's parser when socket.pause() is called.
  const burst=Array.from({length:32},(_,i)=>({data:i%3===0?Buffer.from([i,0,255]):i%3===1?'连续回复🙂'+i:'',binary:i%3===0}));
  const replies:{data:Buffer;binary:boolean}[]=[];
  const collect=(data:WebSocket.RawData,binary:boolean)=>replies.push({data:Buffer.from(data as Buffer),binary});ws.on('message',collect);
  for(const item of burst)ws.send(item.data,{binary:item.binary});
  await waitFor(()=>replies.length===burst.length);ws.off('message',collect);
  assert.deepEqual(replies,burst.map(item=>({data:Buffer.from(item.data),binary:item.binary})));
  const epoch=relay.proxy.peers.get(hostId)!.epoch;relay.store.revoke(admin,'device',a.device_id);
  assert.equal((await request(port,`/native/v2/hosts/${hostId}/rpc/list`,phoneA.token,Buffer.from('{}'))).status,401);
  assert.equal((await request(port,`/native/v2/hosts/${hostId}/rpc/list`,phoneB.token,Buffer.from('{}'))).status,200);
  assert.equal(relay.proxy.peers.get(hostId)!.epoch,epoch);const again=once(ws,'message');ws.send('still-online');assert.equal((await again)[0].toString(),'still-online');ws.close();
});
