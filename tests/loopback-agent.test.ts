import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { Relay } from '../packages/relay/src/server.js';
import { HostClient } from '../packages/plugin/src/client.js';
import { secret,uuid } from '../packages/relay/src/crypto.js';

async function waitFor(fn:()=>boolean,ms=5000){const start=Date.now();while(!fn()){if(Date.now()-start>ms)throw new Error('wait timeout');await new Promise(r=>setTimeout(r,10));}}

function request(port:number,path:string,token:string,data?:Buffer){
  return new Promise<{status:number;body:Buffer}>((resolve,reject)=>{
    const req=http.request({hostname:'127.0.0.1',port,path,method:'POST',headers:{host:'relay.example.test',authorization:`Bearer ${token}`,'content-type':'application/json','content-length':data?.length??0}},res=>{
      const chunks:Buffer[]=[];res.on('data',b=>chunks.push(b));res.on('end',()=>resolve({status:res.statusCode!,body:Buffer.concat(chunks)}));
    });req.on('error',reject);req.end(data);
  });
}

/** Relay + host + phone around one upstream, plus the loopback log the plugin now emits. */
async function fixture(t:any,upstreamHandler:http.RequestListener){
  const upstream=http.createServer(upstreamHandler);
  const connections:number[]=[];upstream.on('connection',()=>connections.push(Date.now()));
  upstream.listen(0,'127.0.0.1');await once(upstream,'listening');const upstreamPort=(upstream.address() as any).port;
  const relay=new Relay({baseUrl:'https://relay.example.test',database:':memory:',key:Buffer.alloc(32,3),requireTotp:false,testMode:true});const port=await relay.listen(0);
  let client:HostClient|undefined;
  const logs:string[]=[];
  t.after(async()=>{await client?.stop();await relay.close();await new Promise<void>(r=>upstream.close(()=>r()));});
  await relay.store.createAccount('a@example.test','long-test-password',false);
  const adminLogin=await relay.store.login('a@example.test','long-test-password','',false),admin=relay.store.session(adminLogin.token,'account');
  const enrollment={operationId:uuid(),hostPublicId:uuid(),hostTunnelToken:secret(),alias:'test'};const {hostId}=relay.store.enroll(relay.store.invitation(admin),enrollment);
  const phone=await relay.store.login('a@example.test','long-test-password','',false,'Android',true),s=relay.store.session(phone.token,'native');
  relay.store.pair(s,relay.store.createPair(admin,hostId).code);
  client=new HostClient({relayUrl:`ws://127.0.0.1:${port}/tunnel`,hostId,hostTunnelToken:enrollment.hostTunnelToken,upstreamOrigin:`http://127.0.0.1:${upstreamPort}`,getCookie:async()=>'dsh-auth-test=local-secret',onLog:m=>logs.push(m)});
  client.options.headers={Host:'relay.example.test'};
  client.connect();await waitFor(()=>!!relay.proxy.peers.get(hostId)?.ready);
  const call=()=>request(port,`/native/v2/hosts/${hostId}/rpc/list`,phone.token,Buffer.from('{}'));
  return {call,connections,logs};
}

const ok=(res:http.ServerResponse)=>{res.writeHead(200,{'content-type':'application/json','content-length':'2'});res.end('{}');};

/** The host advertises `keep-alive: timeout=5` and drops idle sockets on its own schedule.
 *  A pooled loopback socket survives in the agent until the FIN is read, so a stalled host
 *  event loop turns the next request into ECONNRESET — which the relay reports to the phone
 *  as a 502 `upstream-error` (28 of those landed in nginx on 2026-10-28). Each loopback
 *  request must therefore open its own connection; reuse is what made the pool dangerous. */
test('every loopback request opens its own connection instead of reusing a pooled socket',{timeout:20000},async t=>{
  const {call,connections}=await fixture(t,async(req,res)=>{
    for await(const _ of req){/* drain */}
    // Exactly what the real DSH gateway sends, including the short idle timeout.
    res.setHeader('connection','keep-alive');res.setHeader('keep-alive','timeout=5');ok(res);
  });
  for(let i=0;i<3;i++)assert.equal((await call()).status,200);
  assert.equal(connections.length,3,'the plugin must not reuse a loopback socket between requests');
});

/** A dead upstream has to leave a trail. Before this the error object was dropped on the
 *  floor and the relay log only ever said `fail upstream-error`, which is why 28 phone-side
 *  502s took a day to trace back to the loopback hop. */
test('a loopback failure is logged with its error code and still answers 502 upstream-error',{timeout:20000},async t=>{
  let hits=0;
  const {call,logs}=await fixture(t,async(req,res)=>{
    for await(const _ of req){/* drain */}
    if(++hits===1){ok(res);return;}
    req.socket.destroy(); // the host drops the connection mid-flight
  });
  assert.equal((await call()).status,200,'the first call warms the tunnel');
  const res=await call();
  assert.equal(res.status,502);
  assert.equal(res.body.toString('utf8'),'upstream-error','the phone still gets the relay reason verbatim');
  assert.ok(logs.some(l=>/^loopback POST \/api\/session\/list failed: \S+/.test(l)),`expected a loopback failure log, got ${JSON.stringify(logs)}`);
});
