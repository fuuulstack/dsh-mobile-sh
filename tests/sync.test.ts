import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { gzipSync } from 'node:zlib';
import { Relay } from '../packages/relay/src/server.js';
import { clientIp } from '../packages/relay/src/proxy.js';
import { secret, uuid, totp } from '../packages/relay/src/crypto.js';
function request(port:number,path:string,token:string,data?:Buffer,method='POST',extra:Record<string,string>={}) {return new Promise<{status:number;body:Buffer}>((resolve,reject)=>{
  const req=http.request({hostname:'127.0.0.1',port,path,method,headers:{host:'relay.example.test',authorization:`Bearer ${token}`,...(data?{'content-type':'application/json','content-length':data.length}:{}),...extra}},res=>{
    const chunks:Buffer[]=[];res.on('data',b=>chunks.push(b));res.on('end',()=>resolve({status:res.statusCode!,body:Buffer.concat(chunks)}));
  });req.on('error',reject);req.end(data);
});}

/** Minimal trusted-proxy shape: the socket peer is the proxy, the client rides XFF. */
test('clientIp charges the last X-Forwarded-For entry when the peer is the proxy',()=>{
  const req={socket:{remoteAddress:'127.0.0.1'},headers:{'x-forwarded-for':'203.0.113.7'}} as unknown as http.IncomingMessage;
  assert.equal(clientIp(req,'127.0.0.1',false),'203.0.113.7');
  // Append mode (nginx $proxy_add_x_forwarded_for): the last entry is the one our proxy added.
  const appended={socket:{remoteAddress:'127.0.0.1'},headers:{'x-forwarded-for':'1.1.1.1, 203.0.113.9'}} as unknown as http.IncomingMessage;
  assert.equal(clientIp(appended,'127.0.0.1',false),'203.0.113.9');
  // Spoofed junk never becomes a rate key; direct test connections keep the socket address.
  const junk={socket:{remoteAddress:'127.0.0.1'},headers:{'x-forwarded-for':'not-an-ip'}} as unknown as http.IncomingMessage;
  assert.equal(clientIp(junk,'127.0.0.1',false),'127.0.0.1');
  const direct={socket:{remoteAddress:'198.51.100.4'},headers:{'x-forwarded-for':'203.0.113.7'}} as unknown as http.IncomingMessage;
  assert.equal(clientIp(direct,undefined,false),'198.51.100.4');
  const testMode={socket:{remoteAddress:'10.0.0.9'},headers:{}} as unknown as http.IncomingMessage;
  assert.equal(clientIp(testMode,undefined,true),'10.0.0.9');
});

test('login rate limits are per real client IP behind the trusted proxy',{timeout:20000},async t=>{
  // Regression: every key used to be req.socket.remoteAddress, which behind nginx is
  // always the proxy itself — five stray attempts from one address locked every user out.
  // The trusted-proxy topology needs testMode:false (testMode assumes direct connections),
  // which in turn requires TOTP accounts and the reverse proxy to be the socket peer.
  const relay=new Relay({baseUrl:'https://relay.example.test',database:':memory:',key:Buffer.alloc(32,3),requireTotp:true,testMode:false,trustedProxy:'127.0.0.1'});
  const port=await relay.listen(0);
  t.after(async()=>{await relay.close();});
  const accountA=await relay.store.createAccount('a@example.test','long-test-password');
  const accountB=await relay.store.createAccount('b@example.test','another-long-password');
  // 邮箱维度的限流桶是独立的一道：攻击者把同一个邮箱打满后，换 IP 也进不来。
  // 所以「别的客户端不受影响」必须用另一个账号来证明。
  const attempt=(xff:string,email:string,password:string,secret:string)=>request(port,'/native/v2/login','',Buffer.from(JSON.stringify({email,password,code:totp(secret)})),'POST',{'x-forwarded-for':xff});
  for(let i=0;i<5;i++)await attempt('203.0.113.7','a@example.test','wrong-password-x',accountA.totp!);
  // The attacker's IP is now throttled…
  assert.equal((await attempt('203.0.113.7','a@example.test','wrong-password-x',accountA.totp!)).status,429);
  // …while a different client behind the same proxy can still reach the endpoint.
  assert.equal((await attempt('198.51.100.9','b@example.test','another-long-password',accountB.totp!)).status,200);
});

test('v2 timeline push fills the relay-side session list; the v1 surface is 404',{timeout:20000},async t=>{
  const relay=new Relay({baseUrl:'https://relay.example.test',database:':memory:',key:Buffer.alloc(32,3),requireTotp:false,testMode:true});
  const port=await relay.listen(0);
  t.after(async()=>{await relay.close();});
  await relay.store.createAccount('a@example.test','long-test-password',false);
  const adminLogin=await relay.store.login('a@example.test','long-test-password','',false);
  const admin=relay.store.session(adminLogin.token,'account');
  const hostTunnelToken=secret();
  const {hostId}=relay.store.enroll(relay.store.invitation(admin),{operationId:uuid(),hostPublicId:uuid(),hostTunnelToken,alias:'archive-host'});
  const phone=await relay.store.login('a@example.test','long-test-password','',false,'Android',true);
  relay.store.pair(relay.store.session(phone.token,'native'),relay.store.createPair(admin,hostId).code);

  const item={sessionId:'session-a',updatedAt:5,running:false,projections:{values:{title:'Archived'}}};
  const push=(path:string,token:string,payload:unknown)=>new Promise<{status:number;body:Buffer}>((resolve,reject)=>{
    const buf=Buffer.from(JSON.stringify(payload));
    const req=http.request({hostname:'127.0.0.1',port,path,method:'POST',headers:{host:'relay.example.test',authorization:`Bearer ${token}`,'content-type':'application/json','content-length':buf.length}},res=>{
      const chunks:Buffer[]=[];res.on('data',b=>chunks.push(b));res.on('end',()=>resolve({status:res.statusCode!,body:Buffer.concat(chunks)}));
    });req.on('error',reject);req.end(buf);
  });
  const record=(seq:number,text:string)=>({sessionId:'session-a',seq,kind:'ASSISTANT_TEXT',prevSeq:seq-1,record:{event:{type:'assistant/message',seq,data:{message:{content:[{type:'text',text}]}}}}});

  // The push the v2 plugin makes. `meta` is the slim list item the phone renders.
  const pushed=await push('/control/timeline',hostTunnelToken,{hostId,batchId:'batch-1',records:[record(1,'first'),record(2,'second'),record(3,'third')],meta:[{sessionId:'session-a',item}]});
  assert.equal(pushed.status,200);
  assert.deepEqual(JSON.parse(pushed.body.toString('utf8')).sessions,{'session-a':3});
  // A wrong tunnel credential must not be able to poison the archive.
  assert.equal((await push('/control/timeline',secret(),{hostId,batchId:'batch-bad',records:[],meta:[]})).status,403);

  // The list is answered by the relay itself now: no tunnel to the desktop, so it
  // renders while the desktop is down.
  const list=await request(port,`/native/v2/hosts/${hostId}/sessions`,phone.token,Buffer.alloc(0),'GET');
  assert.equal(list.status,200);
  const items=JSON.parse(list.body.toString('utf8')).items as {sessionId:string;projections:{values:{title:string}}}[];
  assert.equal(items.length,1);
  assert.equal(items[0]!.sessionId,'session-a');
  assert.equal(items[0]!.projections.values.title,'Archived');

  // v1 不再兼容：手机侧与插件侧的老面一律 410 Gone。**不能是 401/404**：
  // 401 会被老 App 读成「登录过期」而清凭据，404 会被读成「打错路径」而重试。
  const gone:[string,string][]=[
    ['/native/v1/login','POST'],
    [`/native/v1/hosts/${hostId}/changes`,'GET'],
    [`/native/v1/hosts/${hostId}/sync`,'POST'],
    [`/native/v1/hosts/${hostId}/rpc/list`,'POST'],
    ['/control/archive','POST'],
  ];
  for(const [path,method] of gone){
    const res=await request(port,path,phone.token,Buffer.from('{}'),method);
    assert.equal(res.status,410,`${method} ${path} should be gone for good`);
  }
});

test('records older than two weeks are pruned; the per-session window bounds growth',{timeout:20000},async t=>{
  const relay=new Relay({baseUrl:'https://relay.example.test',database:':memory:',key:Buffer.alloc(32,4),requireTotp:false,testMode:true});
  await relay.listen(0);
  t.after(async()=>{await relay.close();});
  const now=Date.now();
  const fresh={sessionId:'s-fresh',seq:1,record:{event:{type:'assistant/message',seq:1,data:{}}}};
  const stale={sessionId:'s-stale',seq:1,record:{event:{type:'assistant/message',seq:1,data:{}}}};
  relay.store.archiveRecords('host-1',[fresh,stale]);
  relay.store.run('UPDATE records SET updated_at=? WHERE session_id=?',now-15*86400000,'s-stale');
  // 2010 records in one session: the rolling window keeps the newest 2000.
  const bulk=Array.from({length:2010},(_,i)=>({sessionId:'s-bulk',seq:i+1,record:{event:{type:'assistant/message',seq:i+1,data:{}}}}));
  relay.store.archiveRecords('host-1',bulk);
  relay.store.prune();
  assert.equal(relay.store.get('SELECT count(*) n FROM records WHERE session_id=?','s-stale')!.n,0,'past-TTL rows are gone');
  assert.equal(relay.store.get('SELECT count(*) n FROM records WHERE session_id=?','s-fresh')!.n,1,'fresh rows survive');
  assert.equal(relay.store.get('SELECT count(*) n FROM records WHERE session_id=?','s-bulk')!.n,2000,'the window trims the oldest of an oversized session');
  assert.equal(relay.store.get('SELECT MAX(seq) m FROM records WHERE session_id=?','s-bulk')!.m,2010,'the newest record is the one that survives');
});
