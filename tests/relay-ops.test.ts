import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { Relay } from '../packages/relay/src/server.js';
import { HostClient } from '../packages/plugin/src/client.js';
import { secret, uuid, hash } from '../packages/relay/src/crypto.js';
import { safePath } from '../packages/core/src/index.js';

async function waitFor(fn:()=>boolean,ms=5000){const start=Date.now();while(!fn()){if(Date.now()-start>ms)throw new Error('wait timeout');await new Promise(r=>setTimeout(r,10));}}
function request(port:number,path:string,token:string,data?:Buffer,method='POST',extra:Record<string,string>={}) {return new Promise<{status:number;body:Buffer}>((resolve,reject)=>{
  const req=http.request({hostname:'127.0.0.1',port,path,method,headers:{host:'relay.example.test',authorization:`Bearer ${token}`,...(data?{'content-type':'application/json','content-length':data.length}:{}),...extra}},res=>{
    const chunks:Buffer[]=[];res.on('data',b=>chunks.push(b));res.on('end',()=>resolve({status:res.statusCode!,body:Buffer.concat(chunks)}));
  });req.on('error',reject);req.end(data);
});}

test('native clients isolate host lists, pairing and host operations by client type',{timeout:20000},async t=>{
  const relay=new Relay({baseUrl:'https://relay.example.test',database:':memory:',key:Buffer.alloc(32,3),requireTotp:false,testMode:true});
  const port=await relay.listen(0);t.after(()=>relay.close());
  await relay.store.createAccount('types@example.test','long-test-password',false);
  const login=await relay.store.login('types@example.test','long-test-password','',false,'Android',true);
  const session=relay.store.session(login.token,'native');
  const account={...session,kind:'account'};
  const hostToken=secret();
  const legacy=relay.store.enrollWithAccount(session.account_id,{operationId:uuid(),hostPublicId:uuid(),hostTunnelToken:hostToken,alias:'Legacy'});
  const chat=relay.store.enrollWithAccount(session.account_id,{operationId:uuid(),hostPublicId:uuid(),hostTunnelToken:secret(),alias:'ChatGPT',clientType:'chatgpt'});
  assert.equal(relay.store.get('SELECT client_type FROM hosts WHERE id=?',legacy.hostId)!.client_type,'dsh');
  // 旧授权即使曾串过类型，也不能借缓存中的 hostId 继续越过类型过滤。
  for(const host of [legacy,chat])relay.store.pair(session,relay.store.createPair(account,host.hostId).code);
  const list=async(type?:string)=>{
    const r=await request(port,'/native/v2/hosts',login.token,undefined,'GET',type?{'x-client-type':type}:{});
    assert.equal(r.status,200);return JSON.parse(r.body.toString()).items;
  };
  assert.deepEqual((await list()).map((h:any)=>h.hostId),[legacy.hostId]);
  assert.deepEqual((await list('dsh')).map((h:any)=>h.hostId),[legacy.hostId]);
  assert.deepEqual((await list('chatgpt')).map((h:any)=>h.hostId),[chat.hostId]);
  assert.equal((await list('chatgpt'))[0].clientType,'chatgpt');
  assert.equal((await request(port,'/native/v2/hosts',login.token,undefined,'GET',{'x-client-type':'other'})).status,400);
  for(const [hostId,type] of [[legacy.hostId,'chatgpt'],[chat.hostId,'dsh']]){
    for(const suffix of ['sessions','changes','blobs/example']){
      assert.equal((await request(port,`/native/v2/hosts/${hostId}/${suffix}`,login.token,undefined,'GET',{'x-client-type':type!})).status,404);
    }
    for(const suffix of ['sync','rpc/projections']){
      assert.equal((await request(port,`/native/v2/hosts/${hostId}/${suffix}`,login.token,Buffer.from('{}'),'POST',{'x-client-type':type!})).status,404);
    }
    assert.equal((await request(port,'/native/v2/unpair',login.token,Buffer.from(JSON.stringify({hostId})),'POST',{'x-client-type':type!})).status,404);
  }
  const pair=relay.store.createPair(account,chat.hostId);
  assert.equal((await request(port,'/native/v2/pair',login.token,Buffer.from(JSON.stringify({code:pair.code})))).status,403);
  assert.equal((await request(port,'/native/v2/pair',login.token,Buffer.from(JSON.stringify({code:pair.code})),'POST',{'x-client-type':'chatgpt'})).status,200,'wrong type must not consume the code');
  const identify=Buffer.from(JSON.stringify({hostId:legacy.hostId,clientType:'chatgpt'}));
  assert.equal((await request(port,'/control/identify',secret(),identify)).status,403);
  assert.equal((await request(port,'/control/identify',hostToken,identify)).status,200);
  assert.equal((await list()).length,0);
  assert.equal((await list('chatgpt')).length,2);
});

test('native token rotation keeps a 60 s grace on the old bearer and issues a fresh one',{timeout:20000},async t=>{
  const relay=new Relay({baseUrl:'https://relay.example.test',database:':memory:',key:Buffer.alloc(32,3),requireTotp:false,testMode:true});const port=await relay.listen(0);
  t.after(async()=>{await relay.close();});
  await relay.store.createAccount('a@example.test','long-test-password',false);
  const login=await relay.store.login('a@example.test','long-test-password','',false,'Android',true);
  const before=await request(port,'/native/v2/hosts',login.token,undefined,'GET');
  assert.equal(before.status,200);
  const rotated=await request(port,'/native/v2/rotate',login.token,Buffer.from('{}'));
  assert.equal(rotated.status,200);
  const newToken=JSON.parse(rotated.body.toString('utf8')).token as string;
  assert.equal(typeof newToken,'string');assert.notEqual(newToken,login.token);
  // The rotation is audited.
  assert.ok(relay.store.all('SELECT * FROM audit WHERE action=?','token-rotated').length>=1);
  // The old bearer keeps a bounded grace window: a rotate response lost on a flaky link
  // must leave the phone a retry path that is not "retype password + TOTP".
  const oldRow=relay.store.get('SELECT * FROM sessions WHERE id_hash=?',hash(login.token))!;
  assert.ok(oldRow.expires-Date.now()<=60000,`old bearer must be capped at the 60 s grace, saw ${Math.round((oldRow.expires-Date.now())/1000)}s`);
  assert.equal((await request(port,'/native/v2/hosts',login.token,undefined,'GET')).status,200,'old bearer still answers inside the grace');
  assert.equal((await request(port,'/native/v2/hosts',newToken,undefined,'GET')).status,200);
  // Once the grace passes, the old bearer is dead for good.
  relay.store.run('UPDATE sessions SET expires=? WHERE id_hash=?',Date.now()-1000,oldRow.id_hash);
  assert.equal((await request(port,'/native/v2/hosts',login.token,undefined,'GET')).status,401);
});

test('native device tokens live 30 days and an expired one can still rotate',{timeout:20000},async t=>{
  const relay=new Relay({baseUrl:'https://relay.example.test',database:':memory:',key:Buffer.alloc(32,3),requireTotp:false,testMode:true});const port=await relay.listen(0);
  t.after(async()=>{await relay.close();});
  await relay.store.createAccount('a@example.test','long-test-password',false);
  const login=await relay.store.login('a@example.test','long-test-password','',false,'Android',true);
  const row=relay.store.session(login.token,'native');
  const ttl=row.expires-Date.now();
  // 真机踩过的坑：设备令牌只有 8 小时，手机隔夜就全端点 401。
  assert.ok(ttl>29*24*3600*1000,`native TTL 应为 30 天，实测 ${Math.round(ttl/3600000)} 小时`);
  // 浏览器会话语义不变：关掉浏览器就该作废。
  const web=await relay.store.login('a@example.test','long-test-password','',false);
  const webRow=relay.store.session(web.token,'account');
  assert.ok(webRow.expires-Date.now()<9*3600*1000&&webRow.expires-Date.now()>7*3600*1000,'account TTL stays 8 hours');
  // 模拟隔夜过期：普通调用一律 401，rotate 必须还能过——它是手机唯一的自救路径。
  relay.store.run('UPDATE sessions SET expires=? WHERE id_hash=?',Date.now()-1000,row.id_hash);
  assert.equal((await request(port,'/native/v2/hosts',login.token,undefined,'GET')).status,401);
  const rotated=await request(port,'/native/v2/rotate',login.token,Buffer.from('{}'));
  assert.equal(rotated.status,200);
  const fresh=JSON.parse(rotated.body.toString('utf8')) as {token:string;expiresIn:number};
  assert.equal(fresh.expiresIn,30*24*3600);
  assert.equal((await request(port,'/native/v2/hosts',login.token,undefined,'GET')).status,401);
  assert.equal((await request(port,'/native/v2/hosts',fresh.token,undefined,'GET')).status,200);
});

test('device pairing code logs a phone in without email, password or TOTP',{timeout:20000},async t=>{
  const relay=new Relay({baseUrl:'https://relay.example.test',database:':memory:',key:Buffer.alloc(32,3),requireTotp:false,testMode:true});const port=await relay.listen(0);
  t.after(async()=>{await relay.close();});
  await relay.store.createAccount('a@example.test','long-test-password',false);
  // 码是在网页上生成的，生成者仍然要登录——但手机端从此只需要这一个码。
  const web=await relay.store.login('a@example.test','long-test-password','',false,'网页',true);
  const created=relay.store.createDeviceCode(relay.store.session(web.token,'native'),'老大的手机');

  const paired=await request(port,'/native/v2/device-code','',Buffer.from(JSON.stringify({code:created.code,label:'测试真机'})));
  assert.equal(paired.status,200);
  const token=JSON.parse(paired.body.toString('utf8')).token as string;
  assert.equal(typeof token,'string');
  // 换到的令牌与账号登录同权：能列主机、TTL 30 天。
  assert.equal((await request(port,'/native/v2/hosts',token,undefined,'GET')).status,200);
  assert.ok(relay.store.session(token,'native').expires-Date.now()>29*24*3600*1000);

  // 一个码只能用一次；第二次必须是 403，不能变成万能钥匙。
  const again=await request(port,'/native/v2/device-code','',Buffer.from(JSON.stringify({code:created.code,label:'另一台'})));
  assert.equal(again.status,403);
  // 猜错也一样拒。
  assert.equal((await request(port,'/native/v2/device-code','',Buffer.from(JSON.stringify({code:'00000000'})))).status,403);
  // 换出来的设备是独立的一行：多台设备各持一码，撤销互不影响。
  const devices=relay.store.all('SELECT * FROM devices');
  assert.ok(devices.length>=2,`网页 + 手机各一台设备，实测 ${devices.length}`);
});

test('device pairing code expires and can be revoked before use',{timeout:20000},async t=>{
  const relay=new Relay({baseUrl:'https://relay.example.test',database:':memory:',key:Buffer.alloc(32,3),requireTotp:false,testMode:true});const port=await relay.listen(0);
  t.after(async()=>{await relay.close();});
  await relay.store.createAccount('a@example.test','long-test-password',false);
  const web=await relay.store.login('a@example.test','long-test-password','',false,'浏览器');
  const s=relay.store.session(web.token,'account');

  const stale=relay.store.createDeviceCode(s,'过期的码');
  relay.store.run('UPDATE device_codes SET expires=? WHERE id=?',Date.now()-1000,stale.id);
  assert.equal((await request(port,'/native/v2/device-code','',Buffer.from(JSON.stringify({code:stale.code})))).status,403);

  const killed=relay.store.createDeviceCode(s,'被撤销的码');
  relay.store.revokeDeviceCode(s,killed.id);
  assert.equal((await request(port,'/native/v2/device-code','',Buffer.from(JSON.stringify({code:killed.code})))).status,403);
  // 撤销要留下审计痕迹，否则「码被人用过没有」永远查不清。
  assert.ok(relay.store.all("SELECT * FROM audit WHERE action='device-code-revoked'").length>=1);
  assert.ok(relay.store.all("SELECT * FROM audit WHERE action='device-code-created'").length>=2);
});

test('revoking one device leaves the other phones working',{timeout:20000},async t=>{
  const relay=new Relay({baseUrl:'https://relay.example.test',database:':memory:',key:Buffer.alloc(32,3),requireTotp:false,testMode:true});const port=await relay.listen(0);
  t.after(async()=>{await relay.close();});
  await relay.store.createAccount('a@example.test','long-test-password',false);
  const admin=await relay.store.login('a@example.test','long-test-password','',false,'网页',true);
  const s=relay.store.session(admin.token,'native');

  // 两台手机各持一个码：这就是「多设备共存」的形态。
  const first=relay.store.createDeviceCode(s,'旧手机');
  const second=relay.store.createDeviceCode(s,'新手机');
  const a=JSON.parse((await request(port,'/native/v2/device-code','',Buffer.from(JSON.stringify({code:first.code,label:'旧手机'})))).body.toString('utf8')).token as string;
  const b=JSON.parse((await request(port,'/native/v2/device-code','',Buffer.from(JSON.stringify({code:second.code,label:'新手机'})))).body.toString('utf8')).token as string;
  assert.equal((await request(port,'/native/v2/hosts',a,undefined,'GET')).status,200);
  assert.equal((await request(port,'/native/v2/hosts',b,undefined,'GET')).status,200);

  relay.store.revoke(s,'device',relay.store.session(a,'native').device_id);
  assert.equal((await request(port,'/native/v2/hosts',a,undefined,'GET')).status,401);
  assert.equal((await request(port,'/native/v2/hosts',b,undefined,'GET')).status,200,'撤销一台不能连坐其它设备');
  // 生成码的人自己也还在。
  assert.equal((await request(port,'/native/v2/hosts',admin.token,undefined,'GET')).status,200);
});

test('digest endpoints are gone for good',{timeout:20000},async t=>{
  const relay=new Relay({baseUrl:'https://relay.example.test',database:':memory:',key:Buffer.alloc(32,3),requireTotp:false,testMode:true});const port=await relay.listen(0);
  t.after(async()=>{await relay.close();});
  await relay.store.createAccount('a@example.test','long-test-password',false);
  const admin=await relay.store.login('a@example.test','long-test-password','',false,'Android',true);
  // v2 评审裁定：digest 预览通道下线（2026-10-28 连 archiver 的 snippet 旁路也一起
  // 拆了，首行改由手机本地现算）。老客户端打到这两个端点必须得到明确的 404，
  // 而不是 500 或静默兼容。
  assert.equal((await request(port,'/native/v2/digest',admin.token,Buffer.from(JSON.stringify({hostId:'f'.repeat(32),sessionId:'s',lastSeq:1,role:'user',preview:'x'})))).status,404);
  assert.equal((await request(port,'/native/v2/digests?hostId='+'f'.repeat(32),admin.token,undefined,'GET')).status,404);
});

test('metrics endpoint reports host liveness and audit health, optional bearer gate',{timeout:20000},async t=>{
  const open=new Relay({baseUrl:'https://relay.example.test',database:':memory:',key:Buffer.alloc(32,3),requireTotp:false,testMode:true});const openPort=await open.listen(0);
  const gated=new Relay({baseUrl:'https://relay.example.test',database:':memory:',key:Buffer.alloc(32,3),requireTotp:false,testMode:true,metricsToken:'metrics-secret-token-1234'});const gatedPort=await gated.listen(0);
  t.after(async()=>{await open.close();await gated.close();});
  await open.store.createAccount('a@example.test','long-test-password',false);
  const adminLogin=await open.store.login('a@example.test','long-test-password','',false);
  const enrollment={operationId:uuid(),hostPublicId:uuid(),hostTunnelToken:secret(),alias:'metrics-host'};const {hostId}=open.store.enroll(open.store.invitation(open.store.session(adminLogin.token,'account')),enrollment);
  const metrics=await request(openPort,'/metrics','',undefined,'GET');
  const body=JSON.parse(metrics.body.toString('utf8'));
  // No tunnel is connected: the host exists and reports offline.
  const host=body.hosts.find((h:{hostId:string})=>h.hostId===hostId);
  assert.equal(host.online,false);assert.equal(typeof body.uptimeSeconds,'number');assert.equal(body.auditFailures,0);
  // v2 评审 P1：SSE 写扩散健康面——在线订阅数与累计丢弃条数必须上报。
  assert.equal(body.sseOnline,0);
  assert.equal(body.changeDropped,0);
  // 订阅一条 SSE 后在线数要涨；慢消费者丢弃计数在 ChangeBus 单测里覆盖。
  // SSE 端点走 native()（session(native)+grant(hostId)）：account token 无 grant
  // 必然 401，订阅建立不了。所以这里走 native 登录 + pair。
  const phone=await open.store.login('a@example.test','long-test-password','',false,'Android',true);
  open.store.pair(open.store.session(phone.token,'native'),open.store.createPair(open.store.session(adminLogin.token,'account'),hostId).code);
  const sseReq=http.get({hostname:'127.0.0.1',port:openPort,path:'/native/v2/hosts/'+hostId+'/changes',headers:{host:'relay.example.test',authorization:'Bearer '+phone.token}},()=>{});
  await waitFor(()=>open.changes.size()===1);
  const after=JSON.parse((await request(openPort,'/metrics','',undefined,'GET')).body.toString('utf8'));
  assert.equal(after.sseOnline,1,'SSE 订阅后 sseOnline 应为 1');
  assert.equal(after.changeDropped,0);
  sseReq.destroy();
  // Gated deployment rejects unauthenticated reads.
  assert.equal((await request(gatedPort,'/metrics','',undefined,'GET')).status,401);
  assert.equal((await request(gatedPort,'/metrics','',undefined,'GET',{authorization:'Bearer metrics-secret-token-1234'})).status,200);
  // 带 @ 的 token 必须照样能用：部署方会把中继密码直接填成 metrics token。
  const special=new Relay({baseUrl:'https://relay.example.test',database:':memory:',key:Buffer.alloc(32,3),requireTotp:false,testMode:true,metricsToken:'relay@test-metrics'});const specialPort=await special.listen(0);
  t.after(async()=>{await special.close();});
  assert.equal((await request(specialPort,'/metrics','',undefined,'GET',{authorization:'Bearer relay@test-metrics'})).status,200);
  // 长度不足 16 的 token 与错 token 一样被拒（fail closed，不给短口令留口子）。
  assert.equal((await request(specialPort,'/metrics','',undefined,'GET',{authorization:'Bearer relay@test'})).status,401);
});

test('host-reported metrics land on /metrics with an age, and are host-scoped',{timeout:20000},async t=>{
  // 中期-12：插件主动上报宿主侧指标。中继此前对「宿主为什么 502」完全瞎——
  // 它只知道 `fail upstream-error`，分不清是事件循环卡住还是 loopback 被重置。
  const relay=new Relay({baseUrl:'https://relay.example.test',database:':memory:',key:Buffer.alloc(32,3),requireTotp:false,testMode:true});
  const port=await relay.listen(0);
  t.after(async()=>{await relay.close();});
  await relay.store.createAccount('a@example.test','long-test-password',false);
  const admin=await relay.store.login('a@example.test','long-test-password','',false);
  const a={operationId:uuid(),hostPublicId:uuid(),hostTunnelToken:secret(),alias:'host-a'};
  const b={operationId:uuid(),hostPublicId:uuid(),hostTunnelToken:secret(),alias:'host-b'};
  const aId=relay.store.enroll(relay.store.invitation(relay.store.session(admin.token,'account')),a).hostId;
  const bId=relay.store.enroll(relay.store.invitation(relay.store.session(admin.token,'account')),b).hostId;

  const payload={hostId:aId,windowMs:60000,
    eventLoop:{samples:240,meanMs:1.5,p50Ms:1,p99Ms:41.2,maxMs:512},
    loopback:{failures:7,total:900,byCode:{ECONNRESET:7}},
    tunnel:{state:'ready',reconnects:2,bufferedPeakBytes:0,stalls:0},archiver:{queued:120,inFlight:true,sending:0,pushMs:0,batchBytes:0,pushFailures:0},uptimeMs:3600000,at:Date.now()};
  const posted=await request(port,'/control/metrics',a.hostTunnelToken,Buffer.from(JSON.stringify(payload)));
  assert.equal(posted.status,200,posted.body.toString('utf8'));

  const body=JSON.parse((await request(port,'/metrics','',undefined,'GET')).body.toString('utf8'));
  const row=body.hostMetrics.find((m:{hostId:string})=>m.hostId===aId);
  assert.ok(row,`未上报的主机也要出现在 hostMetrics 里（标 missing）: ${JSON.stringify(body.hostMetrics)}`);
  assert.equal(row.eventLoop.p99Ms,41.2,'p99 是判「宿主卡住还是链路抖动」的单一指标，必须原样透出');
  assert.equal(row.eventLoop.maxMs,512);
  assert.deepEqual(row.loopback.byCode,{ECONNRESET:7});
  assert.equal(row.tunnel.reconnects,2);
  assert.equal(row.archiver.queued,120);
  assert.ok(typeof row.ageMs==='number'&&row.ageMs>=0,'必须带 ageMs：过期数据与实时数据在面板上看起来一样会误导排障');
  // 没有上报过的主机不能伪装成有过数据。
  const missing=body.hostMetrics.find((m:{hostId:string})=>m.hostId===bId);
  assert.equal(missing.missing,true);

  // 凭据必须是这台主机的隧道凭据：拿别的主机的 token 不能替它上报。
  const crossed=await request(port,'/control/metrics',b.hostTunnelToken,Buffer.from(JSON.stringify({...payload})));
  assert.equal(crossed.status,403,'跨主机写指标必须被拒（否则一台机器可以伪造另一台的健康数据）');

  // 只为观测服务：脏数据不能写进来，也不能把中继搞崩。
  const bad=await request(port,'/control/metrics',a.hostTunnelToken,Buffer.from(JSON.stringify({...payload,eventLoop:{meanMs:-1,p50Ms:0,p99Ms:0,maxMs:0}})));
  assert.equal(bad.status,400);

  // 升级窗口：新插件带中继还不认识的字段时，必须照收不误。
  // 这里曾经是 strictObject——评审现场「/control/metrics 连续 400、两端日志全绿、
  // 监控彻底瞎掉」就是多加两个字段换来的。字段集只会越来越长，不能靠同步发版。
  const forward=await request(port,'/control/metrics',a.hostTunnelToken,Buffer.from(JSON.stringify({
    ...payload,schemaVersion:2,someFutureField:{nested:[1,2,3]},
  })));
  assert.equal(forward.status,200,'不认识的新字段不能让整份上报作废');
  const after=JSON.parse((await request(port,'/metrics','',undefined,'GET')).body.toString('utf8'));
  const forwardRow=after.hostMetrics.find((m:{hostId:string})=>m.hostId===aId);
  assert.equal(forwardRow.someFutureField.nested.length,3,'原样收下，排障时看得到');
  assert.equal(forwardRow.schemaVersion,2);
  // 老插件不带 schemaVersion：默认按 1 解释，不能因此判 400。
  const legacy=await request(port,'/control/metrics',a.hostTunnelToken,Buffer.from(JSON.stringify(payload)));
  assert.equal(legacy.status,200);
  const legacyRow=JSON.parse((await request(port,'/metrics','',undefined,'GET')).body.toString('utf8'))
    .hostMetrics.find((m:{hostId:string})=>m.hostId===aId);
  assert.equal(legacyRow.schemaVersion,1,'缺省版本号必须补成 1');
});

test('safePath admits only the exact $events unary route and rejects variants',()=>{
  assert.equal(safePath('/api/$events/result'),'/api/$events/result');
  assert.equal(safePath('/api/session/list'),'/api/session/list');
  assert.equal(safePath('/api/commands/execute'),'/api/commands/execute');
  assert.throws(()=>safePath('/api/$events'));
  assert.throws(()=>safePath('/api/$events/x'));
});

test('command RPC lands on the commands service, never on the prompt path',{timeout:20000},async t=>{
  // Regression: the phone used to change a permission preset by *prompting* `/permission <preset>`.
  // A prompt reaches the model as ordinary text — the model answers that it switched, while the
  // host never emits a permission/preset event and the UI just repaints itself locally. The slash
  // command has to go through commands/execute, which the relay must rewrite to /api/commands/execute.
  const seen:{path?:string;method?:string;body?:string}[]=[];
  const upstream=http.createServer((req,res)=>{
    const chunks:Buffer[]=[];req.on('data',b=>chunks.push(b));req.on('end',()=>{
      seen.push({path:req.url,method:(JSON.parse(Buffer.concat(chunks).toString('utf8')) as {method?:string}).method});
      res.setHeader('content-type','application/json');res.end(JSON.stringify({result:{ok:true,value:{commandId:'cmd-1',result:{kind:'success',text:'preset read-only'}}}}));
    });
  });upstream.listen(0,'127.0.0.1');await once(upstream,'listening');const upstreamPort=(upstream.address() as any).port;
  const relay=new Relay({baseUrl:'https://relay.example.test',database:':memory:',key:Buffer.alloc(32,3),requireTotp:false,testMode:true});const port=await relay.listen(0);
  let client:HostClient|undefined;
  t.after(async()=>{await client?.stop();await relay.close();await new Promise<void>(r=>upstream.close(()=>r()));});
  await relay.store.createAccount('a@example.test','long-test-password',false);
  const adminLogin=await relay.store.login('a@example.test','long-test-password','',false);
  const admin=relay.store.session(adminLogin.token,'account');
  const enrollment={operationId:uuid(),hostPublicId:uuid(),hostTunnelToken:secret(),alias:'command-host'};const {hostId}=relay.store.enroll(relay.store.invitation(admin),enrollment);
  const phone=await relay.store.login('a@example.test','long-test-password','',false,'Android',true);
  relay.store.pair(relay.store.session(phone.token,'native'),relay.store.createPair(admin,hostId).code);
  client=new HostClient({relayUrl:`ws://127.0.0.1:${port}/tunnel`,hostId,hostTunnelToken:enrollment.hostTunnelToken,upstreamOrigin:`http://127.0.0.1:${upstreamPort}`,getCookie:async()=>'dsh-auth-test=local-secret'});
  client.options.headers={Host:'relay.example.test'};
  client.connect();await waitFor(()=>!!relay.proxy.peers.get(hostId)?.ready);
  // The exact wire shape the phone sends for a permission switch.
  const message=JSON.stringify({type:'client-request',rpcId:'r1',method:'commands/execute',payload:{args:{agentId:'session-1',line:'/permission read-only',submittedAttachments:[]}}});
  const response=await request(port,`/native/v2/hosts/${hostId}/rpc/command`,phone.token,Buffer.from(message));
  assert.equal(response.status,200);
  assert.equal(seen.length,1);
  assert.equal(seen[0]!.path,'/api/commands/execute');
  assert.equal(seen[0]!.method,'commands/execute');
  assert.equal(JSON.parse(response.body.toString('utf8')).result.ok,true);
});

test('a revoked host answers 410 Gone so stale clients stop, while an unpaired one stays 404',{timeout:20000},async t=>{
  // 撤销主机后必须给一个「永久没了」的信号：401/403 会让客户端误判为令牌失效去轮换，
  // 轮换成功仍被拒 → 每秒一次死循环（生产实测一分钟涨 270 条审计）；404 又会被当成
  // 路径问题无限重连（旧插件每 3 秒一次，一天两万条访问日志）。410 两者都能避开。
  const relay=new Relay({baseUrl:'https://relay.example.test',database:':memory:',key:Buffer.alloc(32,5),requireTotp:false,testMode:true});const port=await relay.listen(0);
  t.after(async()=>{await relay.close();});
  await relay.store.createAccount('a@example.test','long-test-password',false);
  const admin=relay.store.session((await relay.store.login('a@example.test','long-test-password','',false)).token,'account');
  const {hostId}=relay.store.enroll(relay.store.invitation(admin),{operationId:uuid(),hostPublicId:uuid(),hostTunnelToken:secret(),alias:'doomed-host'});
  const phone=await relay.store.login('a@example.test','long-test-password','',false,'Android',true);
  relay.store.pair(relay.store.session(phone.token,'native'),relay.store.createPair(admin,hostId).code);

  // 授权在：能进到 RPC 层（这里没有宿主在跑，因此不是 401）。
  const paired=await request(port,`/native/v2/hosts/${hostId}/sync`,phone.token,Buffer.from('{}'));
  assert.notEqual(paired.status,410);

  relay.store.revoke(admin,'host',hostId);

  const revoked=await request(port,`/native/v2/hosts/${hostId}/sync`,phone.token,Buffer.from('{}'));
  assert.equal(revoked.status,410);

  // 从未授权过的另一台主机仍然回 404，别把「没配对」也说成凭据失效。
  const {hostId:otherId}=relay.store.enroll(relay.store.invitation(admin),{operationId:uuid(),hostPublicId:uuid(),hostTunnelToken:secret(),alias:'unpaired-host'});
  const unpaired=await request(port,`/native/v2/hosts/${otherId}/sync`,phone.token,Buffer.from('{}'));
  assert.equal(unpaired.status,404);
});


test('the attachment switch is a per-device relay policy, not a phone-local display flag',{timeout:20000},async t=>{
  // 这个开关的语义是「中继要不要为这台设备搬附件字节」。三条必须成立：
  //  ① 默认开（老设备补列后行为不变）；
  //  ② 关掉之后**中继自己**拒绝，不靠手机自觉——否则换台设备/清数据就绕过去了；
  //  ③ 只影响这一台设备：同一账号的另一台手机照样能传。
  const relay=new Relay({baseUrl:'https://relay.example.test',database:':memory:',key:Buffer.alloc(32,7),requireTotp:false,testMode:true});const port=await relay.listen(0);
  t.after(async()=>{await relay.close();});
  await relay.store.createAccount('a@example.test','long-test-password',false);
  const admin=relay.store.session((await relay.store.login('a@example.test','long-test-password','',false)).token,'account');
  const {hostId}=relay.store.enroll(relay.store.invitation(admin),{operationId:uuid(),hostPublicId:uuid(),hostTunnelToken:secret(),alias:'attach-host'});
  const phone=await relay.store.login('a@example.test','long-test-password','',false,'Android',true);
  relay.store.pair(relay.store.session(phone.token,'native'),relay.store.createPair(admin,hostId).code);

  const read=await request(port,'/native/v2/attachments',phone.token,undefined,'GET');
  assert.equal(read.status,200);
  assert.equal(JSON.parse(read.body.toString('utf8')).enabled,true,'a device that never touched the switch must default to enabled');

  const off=await request(port,'/native/v2/attachments',phone.token,Buffer.from('{"enabled":false}'));
  assert.equal(off.status,200);
  assert.equal(JSON.parse(off.body.toString('utf8')).enabled,false,'the response echoes the stored value, not the requested one');

  // 读图与上传两条通路都必须被中继自己拦下：403 而不是静默成功。
  const image=await request(port,`/native/v2/hosts/${hostId}/rpc/attachment`,phone.token,Buffer.from(JSON.stringify({request:{sessionId:'s',attachmentId:'a'}})));
  assert.equal(image.status,403,'session/attachment must be refused at the relay once the device opted out');
  assert.match(image.body.toString('utf8'),/attachments-disabled/,'the reason must name the switch so the phone can explain it instead of "image broken"');
  const upload=await request(port,`/native/v2/hosts/${hostId}/upload?sessionId=s&name=a.png`,phone.token,Buffer.from([1,2,3]));
  assert.equal(upload.status,403,'uploadFileBinary must be refused too: the bytes must not reach the host');

  // 另一台设备各记各的：按设备，不是按账号。
  const tablet=await relay.store.login('a@example.test','long-test-password','',false,'iPad',true);
  relay.store.pair(relay.store.session(tablet.token,'native'),relay.store.createPair(admin,hostId).code);
  const tabletRead=await request(port,'/native/v2/attachments',tablet.token,undefined,'GET');
  assert.equal(JSON.parse(tabletRead.body.toString('utf8')).enabled,true,'the switch is per device; a sibling device keeps its own setting');

  // 打开之后立刻恢复（不是「下次登录才生效」）。
  const on=await request(port,'/native/v2/attachments',phone.token,Buffer.from('{"enabled":true}'));
  assert.equal(JSON.parse(on.body.toString('utf8')).enabled,true);
  const imageAgain=await request(port,`/native/v2/hosts/${hostId}/rpc/attachment`,phone.token,Buffer.from(JSON.stringify({request:{sessionId:'s',attachmentId:'a'}})));
  assert.notEqual(imageAgain.status,403,'re-enabling must take effect on the very next request');
});

test('the attachment switch survives a database reopen: it is stored, not held in memory',{timeout:20000},async()=>{
  // 重启中继后开关必须还在。存内存的话用户会看到「我明明关了，第二天又开了」，
  // 而且那正是这个功能唯一的价值所在（按设备长期生效）。
  const dir=await import('node:fs').then(fs=>fs.mkdtempSync('/tmp/dsh-attach-'));
  const file=dir+'/relay.db';
  const first=new Relay({baseUrl:'https://relay.example.test',database:file,key:Buffer.alloc(32,9),requireTotp:false,testMode:true});
  await first.store.createAccount('a@example.test','long-test-password',false);
  const login=await first.store.login('a@example.test','long-test-password','',false,'Android',true);
  first.store.setAttachments(first.store.session(login.token,'native'),false);
  await first.close();

  const second=new Relay({baseUrl:'https://relay.example.test',database:file,key:Buffer.alloc(32,9),requireTotp:false,testMode:true});
  try {
    const reopened=second.store.session(login.token,'native');
    assert.equal(second.store.attachmentsAllowed(reopened),false,'the switch must be read back from devices.attachments after a restart');
  } finally { await second.close(); await import('node:fs').then(fs=>fs.rmSync(dir,{recursive:true,force:true})); }
});

test('device-code rate limiting keys on the forwarded client ip, not the nginx socket',{timeout:20000},async t=>{
  // nginx 前置下 `req.socket.remoteAddress` 恒为 127.0.0.1，于是**所有**手机共用
  // 一个 5 次/分钟的桶：任何人试五次设备码，全体新装/重扫码都进 429。
  // 判据：两个不同的 X-Forwarded-For 必须各自有独立配额。
  const relay=new Relay({baseUrl:'https://relay.example.test',database:':memory:',key:Buffer.alloc(32,11),requireTotp:false,testMode:true,trustAnyPeer:true});
  const port=await relay.listen(0);
  t.after(async()=>{await relay.close();});
  await relay.store.createAccount('a@example.test','long-test-password',false);
  const admin=relay.store.session((await relay.store.login('a@example.test','long-test-password','',false)).token,'account');
  // 造 12 个一次性设备码，供两个「不同 IP」各烧 5 次。
  const codes:string[]=[];
  for(let i=0;i<12;i++)codes.push(relay.store.createDeviceCode(admin,'T').code);

  const hit=(code:string,ip:string)=>request(port,'/native/v2/device-code','',Buffer.from(JSON.stringify({code,label:'T'})),'POST',{'x-forwarded-for':ip});
  // A 从**同一个 IP** 烧满配额（5 次，全是错码 → 403 而不是 429）
  for(let i=0;i<5;i++){
    const res=await hit('00000000','10.0.0.1');
    assert.notEqual(res.status,429,`A 组第 ${i+1} 次就被限流了`);
  }
  // A 的第 6 次必须 429（同一个 IP 配额用完了）
  assert.equal((await hit('00000000','10.0.0.1')).status,429,'同一 IP 超配额必须 429');
  // B 是**另一个 IP**，它必须还能用 —— 这正是「全体共用一个桶」时做不到的事。
  const other=await hit(codes[0]!,'203.0.113.7');
  assert.notEqual(other.status,429,'不同 IP 被同一个桶限流 = 全体新装被锁死');
});
