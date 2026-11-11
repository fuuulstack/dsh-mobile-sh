import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { createHash, randomBytes } from 'node:crypto';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { WebSocketServer } from 'ws';
import { gunzipSync } from 'node:zlib';
import { activate, type Context, type PluginConfig } from '../packages/plugin/src/lifecycle.js';
import { defaultNegotiation } from '../packages/core/src/index.js';
import { uuid, secret } from '../packages/relay/src/crypto.js';

/**
 * 每个测试进程独一份的设置文件路径（且**不存在**）。
 *
 * <p>必须显式指定：`settingsFile` 留空会回退到 `<DSH_HOME>/mobile-sh/config.json`，
 * 那是本机真实配置。开发机上一旦保存过设置，这里就会读到真实 relayUrl 并覆盖
 * 测试传入的 patch 地址——表现为「隧道连不上、state 永远不是 ready」，
 * 而失败信息完全指不到真实原因（2026-10-27 实测踩坑）。
 */
const isolatedSettings = join(tmpdir(), `mobile-sh-test-${process.pid}-${randomBytes(4).toString('hex')}.json`);
const defaults:PluginConfig={enabled:true,relayUrl:'',settingsFile:isolatedSettings,maxTunnelRequestBodyBytes:1048576,maxConcurrentStreams:8,connectTimeoutMs:1000,httpIdleTimeoutMs:1000,upgradePaths:['/api/remote.mux']};
async function waitFor(check:()=>boolean){const start=Date.now();while(!check()){if(Date.now()-start>3000)throw new Error('plugin readiness timeout');await new Promise(r=>setTimeout(r,10));}}

test('plugin starts immediately (readiness is idempotent), uses table handle and grant record, exchanges cookie and disposes',async t=>{
  let cookieRequests=0,authenticated=false,domainClosed=0,opens=0;
  const server=http.createServer((req,res)=>{
    // The archiver is a second loopback client: it lists sessions too. Answer with an
    // empty list so it stays quiet; the strict assertions below only apply to the
    // cookie exchange the lifecycle itself must perform.
    if(req.url?.startsWith('/api/session/list')){res.setHeader('content-type','application/json');res.end(JSON.stringify({result:{ok:true,value:{items:[]}}}));return;}
    // 指标上报也打在这个 origin 上（`/control/metrics`）。这条用例里的假宿主与假中继
    // 是同一台服务器，所以这里同样放行——它不参与 cookie 交换的断言。
    if(req.url?.startsWith('/control/')){res.setHeader('content-type','application/json');res.end('{"ok":true}');return;}
    assert.equal(req.url,'/?token=local-fixture');cookieRequests++;
    const name='dsh-auth-'+createHash('sha256').update(req.headers.host!).digest('base64url');
    res.writeHead(303,{location:'./','set-cookie':`${name}=fixture-cookie; Max-Age=2592000; HttpOnly`});res.end();
  });server.listen(0,'127.0.0.1');await once(server,'listening');
  const origin=`http://127.0.0.1:${(server.address() as any).port}`,hostId=uuid(),hostPublicId=uuid(),hostToken=secret();
  // relayOrigin 必须与 normalizeRelayUrl 从同一个输入推出的 origin 一致：明文
  // `ws://127.0.0.1:PORT/tunnel` 对应 `http://127.0.0.1:PORT`。写成 https 会被
  // lifecycle 判成「换了一台中继」，进而丢掉这条凭据、退回 unpaired。
  const identity={kind:'grant',payload:{hostId,hostPublicId,hostTunnelToken:hostToken,operationId:uuid(),relayOrigin:origin}};
  const wsServer=new WebSocketServer({server});
  wsServer.on('connection',ws=>ws.on('message',raw=>{
    const f=JSON.parse(raw.toString());
    if(f.type==='authenticate'){assert.equal(f.credential,hostToken);authenticated=true;ws.send(JSON.stringify({v:3,type:'auth-ok',hostId,tunnelEpoch:uuid(),policy:{requireTotp:true}}));}
    if(f.type==='hello')ws.send(JSON.stringify({v:3,type:'hello-ack',capabilities:['flow','drain','ws-message'],limits:{...defaultNegotiation,maxBody:1048576,streams:8}}));
  }));
  let ready:(()=>void)|undefined,dispose:(()=>void|Promise<void>)|undefined,service:any;
  const writes:unknown[]=[];
  const ctx:Context={
    effect:fn=>{dispose=fn();},get:name=>name==='appReady'?{onReady:(fn:()=>void)=>{ready=fn;return ()=>{ready=undefined;};}}:undefined,
    provide:(name,value)=>{assert.equal(name,'mobileSh');service=value;},logger:{info:()=>{},warn:()=>{}},
    webServer:{getOrigin:()=>origin},connection:{authenticatedUrl:()=>origin+'/?token=local-fixture'},
    credentials:{readRecord:async()=>identity,modifyRecord:async()=>{throw new Error('stored identity must not be overwritten');}},
    storageDomain:{open:async()=>{opens++;return {table:name=>{assert.equal(name,'host');return {get:()=>undefined,put:async(key,value)=>{assert.equal(key,'identity');writes.push(value);}};},close:async()=>{domainClosed++;}};}},
  };
  t.after(async()=>{await dispose?.();for(const ws of wsServer.clients)ws.terminate();wsServer.close();await new Promise<void>(r=>server.close(()=>r()));});
  activate(ctx,{...defaults,relayUrl:origin.replace('http:','ws:')+'/tunnel'},{});
  // 启动时机契约（2026-10-26 改）：inject 已保证依赖就绪，run() 立即启动。
  // 历史上「只等 appReady.onReady」在插件晚于应用就绪时永不回调，会永久挂起；
  // 因此这里断言「已经开跑」，并用 ready!() 验证幂等兜底不会重复打开 domain。
  await waitFor(()=>opens===1);
  ready!();
  await waitFor(()=>service.status().state==='ready');
  assert.equal(opens,1);
  assert.ok(authenticated);assert.equal(cookieRequests,1);assert.deepEqual(writes,[{hostId,hostPublicId,relayOrigin:identity.payload.relayOrigin}]);
  assert.equal(service.status().cookieReady,true);
  await dispose!();await service.disconnect();assert.equal(domainClosed,1);assert.equal(service.status().state,'disabled');assert.equal(service.status().cookieReady,false);
});

test('unload during domain open closes the acquired handle without accessing credentials',async()=>{
  let release!:(value:any)=>void,dispose!:(()=>void|Promise<void>),service:any,closed=0;
  const ctx:Context={effect:fn=>{dispose=fn();},get:()=>undefined,provide:(_name,value)=>{service=value;},logger:{info:()=>{},warn:()=>{}},
    webServer:{getOrigin:()=>{throw new Error('must not start after unload');}},connection:{authenticatedUrl:()=>''},
    credentials:{readRecord:async()=>{throw new Error('must not read after unload');},modifyRecord:async()=>{}},
    storageDomain:{open:()=>new Promise(r=>{release=r;})},
  };
  activate(ctx,{...defaults,relayUrl:'wss://relay.example.test/tunnel'},{});await waitFor(()=>!!release);
  const stopped=dispose();release({table:()=>{throw new Error('must not write after unload');},close:async()=>{closed++;}});await stopped;
  assert.equal(closed,1);assert.equal(service.status().state,'disabled');
});
