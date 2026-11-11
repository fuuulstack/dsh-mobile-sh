import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { mkdtempSync, readFileSync, rmSync, writeFileSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { WebSocketServer } from 'ws';
import { Relay } from '../packages/relay/src/server.js';
import { defaultNegotiation } from '../packages/core/src/index.js';
import { uuid, secret, hash } from '../packages/relay/src/crypto.js';
import { activate, type Context, type PluginConfig } from '../packages/plugin/src/lifecycle.js';
import { normalizeRelayUrl, normalizeEnrollSecret, readSettingsFile, writeSettingsFile, resolveSettings, SettingsError } from '../packages/plugin/src/settings.js';
import { pairingUri, requestPairCode, pairingQrSvg, svgDataUrl, PairingError } from '../packages/plugin/src/pairing.js';
import { registerUiRoutes, UI_PREFIX } from '../packages/plugin/src/ui-routes.js';
import { renderPage } from '../packages/plugin/src/ui-page.js';

const defaults:PluginConfig={enabled:true,relayUrl:'',maxTunnelRequestBodyBytes:1048576,maxConcurrentStreams:8,connectTimeoutMs:1000,httpIdleTimeoutMs:1000,upgradePaths:['/api/remote.mux']};
async function waitFor(check:()=>boolean,ms=5000){const start=Date.now();while(!check()){if(Date.now()-start>ms)throw new Error('timeout');await new Promise(r=>setTimeout(r,10));}}
const tempDir=()=>{const dir=mkdtempSync(join(tmpdir(),'mobile-sh-ui-'));return {dir,cleanup:()=>rmSync(dir,{recursive:true,force:true})};};

// ============================================================
// 一、中继地址与鉴权密码的规范化（UI 输入的边界）
// ============================================================
test('relay address normalizes every shape a user actually pastes',()=>{
  // 裸域名是最常见的输入：自动补 https 再收敛成 wss。
  assert.deepEqual(normalizeRelayUrl('relay.example.com'),{url:'wss://relay.example.com/tunnel',origin:'https://relay.example.com',host:'relay.example.com'});
  assert.equal(normalizeRelayUrl('https://relay.example.com').url,'wss://relay.example.com/tunnel');
  assert.equal(normalizeRelayUrl('https://relay.example.com/').url,'wss://relay.example.com/tunnel');
  assert.equal(normalizeRelayUrl('  wss://relay.example.com/tunnel  ').url,'wss://relay.example.com/tunnel');
  // 本地联调：带端口的中继前置（scripts/local-e2e.ts 用 8443）。
  assert.equal(normalizeRelayUrl('localhost:8443').url,'wss://localhost:8443/tunnel');
  // loopback 明文放行，且 origin 跟着输入走——中继的 HTTP 面（/control/*）与隧道
  // 必须同协议，否则明文部署下写死 https 会直接连不上（裸 IP 自建就是这样跑的）。
  const plain=normalizeRelayUrl('ws://127.0.0.1:9443/tunnel');
  assert.equal(plain.url,'ws://127.0.0.1:9443/tunnel');
  assert.equal(plain.origin,'http://127.0.0.1:9443','origin 跟着输入协议走');
  // 裸 IP 部署：`docker run -p 8080:8080` 对外就是 `http://1.2.3.4:8080`。
  const bareIp=normalizeRelayUrl('http://203.0.113.7:8080');
  assert.equal(bareIp.url,'ws://203.0.113.7:8080/tunnel');
  assert.equal(bareIp.origin,'http://203.0.113.7:8080');
  // IP 上的 https 也认（自签/内网证书场景）。
  assert.equal(normalizeRelayUrl('https://203.0.113.7:8080').origin,'https://203.0.113.7:8080');
  // 拒绝：公网明文、userinfo、路径、query、空值。
  assert.throws(()=>normalizeRelayUrl('http://relay.example.com'),(e:unknown)=>e instanceof SettingsError&&e.code==='relay-scheme');
  assert.throws(()=>normalizeRelayUrl('ws://relay.example.com/tunnel'),(e:unknown)=>e instanceof SettingsError&&e.code==='relay-scheme');
  assert.throws(()=>normalizeRelayUrl('wss://user:pass@relay.example.com/tunnel'),(e:unknown)=>e instanceof SettingsError&&e.code==='relay-userinfo');
  assert.throws(()=>normalizeRelayUrl('wss://relay.example.com/other'),(e:unknown)=>e instanceof SettingsError&&e.code==='relay-path');
  assert.throws(()=>normalizeRelayUrl('wss://relay.example.com/tunnel?x=1'),(e:unknown)=>e instanceof SettingsError&&e.code==='relay-query');
  assert.throws(()=>normalizeRelayUrl('   '),(e:unknown)=>e instanceof SettingsError&&e.code==='relay-required');
});

test('relay secret is user-defined: any length, only whitespace is rejected',()=>{
  const token=secret();
  // 中继自动签发的邀请（43 位）照样能用。
  assert.equal(normalizeEnrollSecret(token),token);
  // 从网页复制常带 "Bearer " 与首尾空白：不该让用户去猜哪里多了一个字符。
  assert.equal(normalizeEnrollSecret(`  Bearer ${token}\n`),token);
  // 用户自定义的短密码必须能存进去（2026-10-27 按用户要求去掉 43 位硬约束）。
  for (const custom of ['short', 'a', 'MyP@ssw0rd!', '密码123', 'x'.repeat(200)]) {
    assert.equal(normalizeEnrollSecret(custom), custom, `自定义密码 ${JSON.stringify(custom)} 应当原样接受`);
  }
  // 只有会破坏 Authorization 头的东西才拒绝。
  assert.throws(()=>normalizeEnrollSecret('has space'),(e:unknown)=>e instanceof SettingsError&&e.code==='secret-format');
  assert.throws(()=>normalizeEnrollSecret('line\nbreak'),(e:unknown)=>e instanceof SettingsError&&e.code==='secret-format');
  assert.throws(()=>normalizeEnrollSecret('tab\there'),(e:unknown)=>e instanceof SettingsError&&e.code==='secret-format');
  assert.throws(()=>normalizeEnrollSecret(''),(e:unknown)=>e instanceof SettingsError&&e.code==='secret-required');
  assert.throws(()=>normalizeEnrollSecret('x'.repeat(513)),(e:unknown)=>e instanceof SettingsError&&e.code==='secret-length');
});

test('settings file round-trips, survives corruption and outranks the patch default',()=>{
  const {dir,cleanup}=tempDir();
  try{
    const path=join(dir,'config.json');
    assert.equal(readSettingsFile(path),undefined,'missing file reads as unconfigured');
    writeSettingsFile(path,{version:1,relayUrl:'wss://relay.example.com/tunnel'});
    assert.deepEqual(readSettingsFile(path),{version:1,relayUrl:'wss://relay.example.com/tunnel'});
    // 用户显式保存的值覆盖 patch 缺省值；凭据里的邀请在文件里保留。
    const resolved=resolveSettings({version:1,relayUrl:'wss://relay.example.com/tunnel',enrollSecret:secret()},'wss://patch.example.com/tunnel');
    assert.equal(resolved.source,'file');assert.equal(resolved.relayOrigin,'https://relay.example.com');assert.ok(resolved.enrollSecret.length>0);
    // 文件被手改坏 → 退回 patch，而不是让插件整体起不来。
    writeFileSync(path,'{ not json');
    assert.equal(readSettingsFile(path),undefined);
    assert.equal(resolveSettings(undefined,'wss://patch.example.com/tunnel').source,'config');
    // 只有 patch 时也能给出可用地址。
    assert.equal(resolveSettings(undefined,'wss://patch.example.com/tunnel').relayUrl,'wss://patch.example.com/tunnel');
    // 两者都没有 → none，插件停在 unpaired 等 UI 保存。
    assert.equal(resolveSettings(undefined,'').source,'none');
  }finally{cleanup();}
});

// ============================================================
// 二、配对码：中继签发 + 二维码
// ============================================================
test('pairing URI keeps the shape the Android scanner parses',()=>{
  const uri=pairingUri('https://relay.example.com','12345678');
  assert.equal(uri,'dsh://pair?relay=https%3A%2F%2Frelay.example.com&code=12345678');
  // 一码配齐：login 拼进同一个 URI，手机扫一下完成「登录 + 配对」。
  const full=pairingUri('https://relay.example.com','12345678','87654321');
  assert.equal(full,'dsh://pair?relay=https%3A%2F%2Frelay.example.com&code=12345678&login=87654321');
  const parsed=new URL(full);
  assert.equal(parsed.searchParams.get('login'),'87654321','手机端 parsePairing 读 query.login');
  // 老中继不签 login 时不拼该参数：旧 URI 形状不变，老 App 照常兼容。
  assert.ok(!pairingUri('https://relay.example.com','12345678').includes('login'));
});

test('QR renders as an SVG data URL without external resources',async()=>{
  const svg=await pairingQrSvg(pairingUri('https://relay.example.com','12345678'));
  assert.match(svg,/^<svg /);
  assert.match(svg,/shape-rendering="crispEdges"/);
  const url=svgDataUrl(svg);
  assert.match(url,/^data:image\/svg\+xml;base64,/);
  assert.equal(Buffer.from(url.split(',')[1]!,'base64').toString('utf8'),svg);
});

test('pair code request maps relay failures to actionable errors',async()=>{
  const hostId=uuid(),token=secret();
  // 403 = 凭据被撤销：必须是 credential-rejected，不能让人以为「再点一次就好」。
  const forbidden=async()=>new Response('credential-rejected',{status:403});
  await assert.rejects(()=>requestPairCode({relayOrigin:'https://relay.example.test',hostId,hostTunnelToken:token,fetchImpl:forbidden as unknown as typeof fetch}),
    (e:unknown)=>e instanceof PairingError&&e.code==='credential-rejected'&&e.status===403);
  const notFound=async()=>new Response('',{status:404});
  await assert.rejects(()=>requestPairCode({relayOrigin:'https://relay.example.test',hostId,hostTunnelToken:token,fetchImpl:notFound as unknown as typeof fetch}),
    (e:unknown)=>e instanceof PairingError&&e.code==='relay-error');
  // 网络层失败统一成 relay-unreachable（UI 提示查网络，而不是查配置）。
  const offline=async()=>{throw new Error('getaddrinfo ENOTFOUND');};
  await assert.rejects(()=>requestPairCode({relayOrigin:'https://relay.example.test',hostId,hostTunnelToken:token,fetchImpl:offline as unknown as typeof fetch}),
    (e:unknown)=>e instanceof PairingError&&e.code==='relay-unreachable');
  // 中继回了 200 但格式不对：不能把畸形码画成二维码。
  const malformed=async()=>new Response(JSON.stringify({code:'nope',expiresAt:Date.now()+1000}),{status:200,headers:{'content-type':'application/json'}});
  await assert.rejects(()=>requestPairCode({relayOrigin:'https://relay.example.test',hostId,hostTunnelToken:token,fetchImpl:malformed as unknown as typeof fetch}),
    (e:unknown)=>e instanceof PairingError&&e.code==='relay-response');
});

// ============================================================
// 三、中继 /control/pair：主机凭据换配对码（端到端）
// ============================================================
test('relay issues a pair code to an authenticated host and the phone can redeem it',{timeout:20000},async t=>{
  const relay=new Relay({baseUrl:'https://relay.example.test',database:':memory:',key:Buffer.alloc(32,3),requireTotp:false,testMode:true});
  const port=await relay.listen(0);
  t.after(async()=>{await relay.close();});
  await relay.store.createAccount('a@example.test','long-test-password',false);
  // 主机注册：与 lifecycle 的 enroll 路径同形。
  const web=await relay.store.login('a@example.test','long-test-password','',false,'网页');
  const invitation=relay.store.invitation(relay.store.session(web.token,'account'));
  const hostTunnelToken=secret(),hostPublicId=uuid(),operationId=uuid();
  const enrolled=await relay.store.enroll(invitation,{operationId,hostPublicId,hostTunnelToken,alias:'验收机',platform:'win32'});
  const hostId=enrolled.hostId;

  const post=(path:string,body:unknown,token?:string)=>new Promise<{status:number;body:string}>((resolve,reject)=>{
    const data=Buffer.from(JSON.stringify(body));
    const req=http.request({hostname:'127.0.0.1',port,path,method:'POST',headers:{host:'relay.example.test','content-type':'application/json','content-length':data.length,...(token?{authorization:`Bearer ${token}`}:{})}},res=>{
      const chunks:Buffer[]=[];res.on('data',b=>chunks.push(b));res.on('end',()=>resolve({status:res.statusCode!,body:Buffer.concat(chunks).toString('utf8')}));
    });req.on('error',reject);req.end(data);
  });

  // 错误凭据必须 403，且不能泄漏「主机是否存在」的差别。
  assert.equal((await post('/control/pair',{hostId},secret())).status,403);
  assert.equal((await post('/control/pair',{hostId})).status,403);
  // 正确凭据 → 8 位码（一码配齐：login 同批签发）。
  const issued=await post('/control/pair',{hostId},hostTunnelToken);
  assert.equal(issued.status,200,issued.body);  const parsed=JSON.parse(issued.body) as {code:string;login:string;expiresAt:number};
  assert.match(parsed.code,/^\d{8}$/);
  assert.match(parsed.login,/^\d{8}$/,'login 与配对码同批下发，手机扫码后自动登录');
  assert.ok(parsed.expiresAt>Date.now(),'码必须还在有效期内');
  // login 兑换 native 令牌：手机「扫一下就登录」的那一步。
  // （/native/v2/device-code 有 nativeOnly 围栏，测试里直接走 store 同一条实现。）
  const r=relay.store.consumeDeviceCode(parsed.login,'扫码') as {token:string};
  assert.ok(r.token.length>0,'login 必须能兑换成 native 令牌');
  // 同一个 login 一次性。
  assert.throws(()=>relay.store.consumeDeviceCode(parsed.login,'扫码'),'login 一次性，重复兑换必须失败');
  // 手机端兑换配对码：拿到 hostId（grant 写入）。
  const phone=await relay.store.login('a@example.test','long-test-password','',false,'手机',true);
  const pairedHost=relay.store.pair(relay.store.session(phone.token,'native'),parsed.code);
  assert.equal(pairedHost,hostId,'手机兑换后应当被授权到这台主机');
  // 一次性：同一个码不能再用。
  assert.equal(relay.store.pair(relay.store.session(phone.token,'native'),parsed.code),null);
});

test('relay enroll accepts a user-defined password of any length',{timeout:20000},async t=>{
  const relay=new Relay({baseUrl:'https://relay.example.test',database:':memory:',key:Buffer.alloc(32,4),requireTotp:false,testMode:true});
  const port=await relay.listen(0);
  t.after(async()=>{await relay.close();});
  await relay.store.createAccount('b@example.test','long-test-password',false);
  const web=await relay.store.login('b@example.test','long-test-password','',false,'网页');
  const account=relay.store.session(web.token,'account');
  // 用户自定义的短密码（不是 43 位）：直接写进 invitations 表，模拟部署方自己设的密码。
  const custom='MyP@ssw0rd!';
  relay.store.run('INSERT INTO invitations VALUES(?,?,?,0)',hash(custom),account.account_id,Date.now()+300000);
  const enroll=async(password:string)=>{
    const data=Buffer.from(JSON.stringify({operationId:uuid(),hostPublicId:uuid(),hostTunnelToken:secret(),alias:'自定义密码机',platform:'win32'}));
    return await new Promise<{status:number;body:string}>((resolve,reject)=>{
      const req=http.request({hostname:'127.0.0.1',port,path:'/control/enroll',method:'POST',
        headers:{host:'relay.example.test','content-type':'application/json','content-length':data.length,authorization:`Bearer ${password}`}},res=>{
        const chunks:Buffer[]=[];res.on('data',b=>chunks.push(b));res.on('end',()=>resolve({status:res.statusCode!,body:Buffer.concat(chunks).toString('utf8')}));
      });req.on('error',reject);req.end(data);
    });
  };
  // 旧实现会在这里 403（正则锁死 43 位），现在必须放行。
  const ok=await enroll(custom);
  assert.equal(ok.status,200,ok.body);
  assert.match((JSON.parse(ok.body) as {hostId:string}).hostId,/^[a-f0-9]{32}$/);
  // 错误的密码仍然 403。
  assert.equal((await enroll('wrong-password')).status,403);
});

test('relay enrolls hosts with the operator-set password from env (RELAY_ENROLL_PASSWORD)',{timeout:20000},async t=>{
  // 部署方在环境里配的自定义注册密码：长任意、可重复使用、只能注册到指定账号。
  // 这是「填一次密码就能反复注册多台主机」的正路——用户不用每次去网页点邀请。
  const relay=new Relay({baseUrl:'https://relay.example.test',database:':memory:',key:Buffer.alloc(32,9),requireTotp:false,testMode:true,
    enrollPassword:'Asuka01183!',enrollAccount:'Ops@Example.Test'});
  const port=await relay.listen(0);
  t.after(async()=>{await relay.close();});
  await relay.store.createAccount('ops@example.test','long-test-password',false);
  // 归属账号按邮箱解析，且大小写不敏感（configFromEnv 已 lower；这里再验一次显式小写）。
  assert.equal(relay.store.accountIdForEnroll('ops@example.test')?.length,32);
  assert.equal(relay.store.accountIdForEnroll('nobody@example.test'),undefined);

  const enroll=(password:string)=>new Promise<{status:number;body:string}>((resolve,reject)=>{
    const data=Buffer.from(JSON.stringify({operationId:uuid(),hostPublicId:uuid(),hostTunnelToken:secret(),alias:'env 密码机',platform:'win32'}));
    const req=http.request({hostname:'127.0.0.1',port,path:'/control/enroll',method:'POST',
      headers:{host:'relay.example.test','content-type':'application/json','content-length':data.length,authorization:`Bearer ${password}`}},res=>{
      const chunks:Buffer[]=[];res.on('data',b=>chunks.push(b));res.on('end',()=>resolve({status:res.statusCode!,body:Buffer.concat(chunks).toString('utf8')}));
    });req.on('error',reject);req.end(data);
  });

  // 自定义密码（11 位、含标点）→ 注册成功。
  const first=await enroll('Asuka01183!');
  assert.equal(first.status,200,first.body);
  const hostId=(JSON.parse(first.body) as {hostId:string}).hostId;
  assert.match(hostId,/^[a-f0-9]{32}$/);
  // 可重复使用：第二台主机用同一个密码也注册成功（这正是它区别于一次性邀请的地方）。
  const second=await enroll('Asuka01183!');
  assert.equal(second.status,200,second.body);
  assert.notEqual((JSON.parse(second.body) as {hostId:string}).hostId,hostId,'第二台主机应当是新主机');
  // 错误密码 → 403；且不因「长度对了」放行。
  assert.equal((await enroll('Asuka01183')).status,403);
  assert.equal((await enroll('')).status,403);
  // 注册进的主机确实属于指定账号。
  const owner=relay.store.get('SELECT account_id FROM hosts WHERE id=?',hostId);
  assert.equal(owner?.account_id,relay.store.accountIdForEnroll('ops@example.test'));
});

test('configFromEnv rejects a half-configured enrollment password pair',async t=>{
  const { configFromEnv } = await import('../packages/relay/src/server.js');
  const { writeFileSync, unlinkSync } = await import('node:fs');
  // configFromEnv 会真的去读 key 文件，给一个真实存在的临时文件。
  const {dir,cleanup}=tempDir();
  t.after(()=>cleanup());
  const keyFile=join(dir,'relay.key');
  writeFileSync(keyFile,Buffer.alloc(32,1));
  const restore=(k:string,v:string|undefined)=>{ if(v===undefined) delete process.env[k]; else process.env[k]=v; };
  const had={base:process.env.RELAY_BASE_URL,key:process.env.RELAY_SECRET_KEY_FILE,pwd:process.env.RELAY_ENROLL_PASSWORD,acct:process.env.RELAY_ENROLL_ACCOUNT};
  process.env.RELAY_BASE_URL='https://relay.example.test';
  process.env.RELAY_SECRET_KEY_FILE=keyFile;
  try{
    // 只给密码不给账号：无法确定主机归属，启动就拒绝——静默忽略会更糟。
    process.env.RELAY_ENROLL_PASSWORD='some-password';delete process.env.RELAY_ENROLL_ACCOUNT;
    assert.throws(()=>configFromEnv(),/RELAY_ENROLL_ACCOUNT/);
    // 只给账号不给密码：同样拒绝。
    delete process.env.RELAY_ENROLL_PASSWORD;process.env.RELAY_ENROLL_ACCOUNT='a@example.test';
    assert.throws(()=>configFromEnv(),/RELAY_ENROLL_PASSWORD/);
    // 两个都给（或都不给）则通过。
    process.env.RELAY_ENROLL_PASSWORD='some-password';process.env.RELAY_ENROLL_ACCOUNT='a@example.test';
    const ok=configFromEnv();
    assert.equal(ok.enrollPassword,'some-password');
    assert.equal(ok.enrollAccount,'a@example.test');
    delete process.env.RELAY_ENROLL_PASSWORD;delete process.env.RELAY_ENROLL_ACCOUNT;
    const plain=configFromEnv();
    assert.equal(plain.enrollPassword,undefined);
  }finally{
    restore('RELAY_BASE_URL',had.base);restore('RELAY_SECRET_KEY_FILE',had.key);
    restore('RELAY_ENROLL_PASSWORD',had.pwd);restore('RELAY_ENROLL_ACCOUNT',had.acct);
  }
});

test('relay pair endpoint rate-limits a host that keeps clicking',{timeout:20000},async t=>{
  const relay=new Relay({baseUrl:'https://relay.example.test',database:':memory:',key:Buffer.alloc(32,3),requireTotp:false,testMode:true});
  const port=await relay.listen(0);
  t.after(async()=>{await relay.close();});
  await relay.store.createAccount('a@example.test','long-test-password',false);
  const web=await relay.store.login('a@example.test','long-test-password','',false,'网页');
  const invitation=relay.store.invitation(relay.store.session(web.token,'account'));
  const hostTunnelToken=secret();
  const {hostId}=await relay.store.enroll(invitation,{operationId:uuid(),hostPublicId:uuid(),hostTunnelToken,alias:'限流机'});
  const statuses:number[]=[];
  for(let i=0;i<12;i++){
    statuses.push(await new Promise<number>((resolve,reject)=>{
      const data=Buffer.from(JSON.stringify({hostId}));
      const req=http.request({hostname:'127.0.0.1',port,path:'/control/pair',method:'POST',headers:{host:'relay.example.test','content-type':'application/json','content-length':data.length,authorization:`Bearer ${hostTunnelToken}`}},res=>{res.resume();res.on('end',()=>resolve(res.statusCode!));});
      req.on('error',reject);req.end(data);
    }));
  }
  // 前 10 次放行、之后 429（与网页 /pair/create 同一档限流）。
  assert.equal(statuses.filter(s=>s===200).length,10,statuses.join(','));
  assert.ok(statuses.slice(10).every(s=>s===429),statuses.join(','));
});

// ============================================================
// 六、槽位注册：必须排队等待，不能前置判断
// ============================================================
test('panel registration queues slot injects instead of gating on spec()',async()=>{
  const { registerPanel, PANEL_ID, PANEL_ORDER, PANEL_LABEL } = await import('../packages/plugin/src/panel.js');
  const registered: Array<{ slot: string; options: Record<string, unknown> }> = [];
  const injected: string[] = [];
  // 关键复现：槽位**此刻还没声明**（sidebar.panellist 由 ui-sidebar 自己的 apply 声明）。
  // 旧实现在这里 spec() 拿到 undefined 就永久 return，侧栏永远没有这一行，
  // 而控制台/宿主日志/graph 全都没有异常——极难定位。
  const slots = {
    spec: () => undefined,
    inject: (key: string, cb: () => unknown) => { injected.push(key); cb(); return () => {}; },
    register: (options: Record<string, unknown>) => { registered.push({ slot: String(options.name), options }); return () => {}; },
  };
  const dispose = registerPanel(slots as never, { main: () => null, sidebar: () => null });
  assert.deepEqual(injected.sort(), ['main', 'sidebar.panellist'], '两个槽都要发出 inject，即便 spec() 现在还看不到');
  assert.equal(registered.length, 2);
  const sidebar = registered.find((r) => r.slot === 'sidebar.panellist');
  assert.equal(sidebar?.options.id, PANEL_ID);
  assert.equal(sidebar?.options.order, PANEL_ORDER);
  assert.equal((sidebar?.options.label as () => string)(), PANEL_LABEL);
  const main = registered.find((r) => r.slot === 'main');
  assert.equal(main?.options.key, PANEL_ID, 'main 是 keyed 槽，必须用 key 对齐侧栏行的 id');
  assert.doesNotThrow(() => dispose());
});

test('a half-finished panel registration is rolled back before rethrowing',async()=>{
  const { registerPanel } = await import('../packages/plugin/src/panel.js');
  let released = 0;
  const slots = {
    inject: (key: string, cb: () => unknown) => {
      if (key === 'sidebar.panellist') throw new Error('slot conflict');
      cb(); return () => { released++; };
    },
    register: () => () => {},
  };
  // 只挂上 main、侧栏抛错时必须把自己那一半摘掉，否则「侧栏有行、主区空白」。
  assert.throws(() => registerPanel(slots as never, { main: () => null, sidebar: () => null }), /slot conflict/);
  assert.equal(released, 1, '已注册的 main 必须被释放');
});

test('client half stays silent when the composition has no slot system',async()=>{
  // 不直接 import browser.ts：它 `import react`（宿主模块表提供），Node 侧没有这个包。
  // 这里验证的是同一段判定逻辑——没有 slots 服务时必须静默返回、不能抛，
  // 否则 headless / 老宿主组合下整个 GUI 启动失败。
  const { registerPanel } = await import('../packages/plugin/src/panel.js');
  const guarded = (ctx: { get?: (name: string) => unknown }) => {
    const slots = (ctx.get?.('slots') ?? (ctx as unknown as Record<string, unknown>).slots) as
      | { inject?: unknown; register?: unknown } | undefined;
    if (slots === undefined || typeof slots.inject !== 'function' || typeof slots.register !== 'function') return false;
    registerPanel(slots as never, { main: () => null, sidebar: () => null });
    return true;
  };
  assert.equal(guarded({ get: () => undefined }), false);
  assert.equal(guarded({ get: () => ({}) }), false);
  assert.equal(guarded({ get: () => ({ inject: () => () => {}, register: () => () => {} }) }), true);
  // 源码层面守住：入口必须先判空再注册，不能直接 registerPanel(ctx.slots)。
  const { readFileSync } = await import('node:fs');
  const source = readFileSync('packages/plugin/src/browser.ts', 'utf8');
  assert.match(source, /if \(slots === undefined \|\| typeof slots\.inject !== 'function'/);
});

// ============================================================
// 七、打包产物：lazy-CJS 外壳与宿主模块表
// ============================================================
test('built client bundle follows the lazy-CJS contract',async()=>{
  const { readFileSync } = await import('node:fs');
  const bundle = readFileSync('packages/plugin/dist/client.js', 'utf8');
  const pkg = JSON.parse(readFileSync('packages/plugin/package.json', 'utf8')) as { name: string; dsh: { client: Record<string, unknown> } };
  // 外壳 id 必须等于包名，否则宿主的 require 解析不到这个 bundle。
  assert.match(bundle, /^window\.__ModuleLoader__\.load\(\{ id: /);
  assert.ok(bundle.includes(`id: ${JSON.stringify(pkg.name)}`), 'id 必须等于包名');
  // factory 自己声明 module/exports（宿主只注入 require）。
  assert.match(bundle, /factory: \(require\) => \{\s*var module = \{ exports: \{\} \}; var exports = module\.exports;/);
  // React 必须来自宿主模块表，不能内联。
  assert.match(bundle, /require\("react"\)/);
  assert.doesNotMatch(bundle, /^\s*import\s/m);
  // 不要再加 immediately：那会排到 ui-sidebar 之前，槽位还没声明。
  assert.equal(pkg.dsh.client.immediately, undefined, 'immediately 会把插件排到槽位声明之前');
});

// ============================================================
// 四、UI 路由：鉴权、页面、保存设置、生成二维码
// ============================================================
test('ui routes fail closed without a connection service and serve the page when admitted',{timeout:20000},async t=>{
  const registered:Array<{kind:string;path:string;handler:(req:http.IncomingMessage,res:http.ServerResponse)=>unknown}>=[];
  const ctx={webServer:{getOrigin:()=>'http://127.0.0.1:3000',register:(route:any)=>{registered.push(route);return ()=>{};}},
    logger:{info:()=>{},warn:()=>{}}};
  const service={status:()=>({state:'unpaired',hostId:null,relayOrigin:null,cookieReady:false,storageReady:false,version:'0.2.0',probe:undefined,upstreamVersion:null}),
    disconnect:async()=>{},
    settings:()=>({relayUrl:'',relayOrigin:null,relaySource:'none' as const,secretConfigured:false,settingsFile:'/tmp/x.json'}),
    saveSettings:async()=>{throw new SettingsError('relay-required','请填写中继地址');},
    setPowerSave:()=>({relayUrl:'',relayOrigin:null,relaySource:'none' as const,secretConfigured:false,settingsFile:'/tmp/x.json',powerSave:{enabled:false,active:false,supported:false}}),
    pair:async()=>{throw new PairingError('not-enrolled','主机尚未注册到中继');}};
  const dispose=registerUiRoutes(ctx as never,service as never);
  t.after(()=>dispose());
  assert.deepEqual(registered.map(r=>r.path).sort(),[`${UI_PREFIX}`,`${UI_PREFIX}/api/pair`,`${UI_PREFIX}/api/power`,`${UI_PREFIX}/api/settings`,`${UI_PREFIX}/api/status`,`${UI_PREFIX}/embed`].sort());

  // 没有 connection.admit → 503，绝不放行（这个页面能改中继地址、能签配对码）。
  const server=http.createServer((req,res)=>{const route=registered.find(r=>r.path===new URL(req.url!,'http://127.0.0.1').pathname);if(!route){res.writeHead(404);res.end();return;}void route.handler(req,res);});
  server.listen(0,'127.0.0.1');await once(server,'listening');
  t.after(async()=>{await new Promise<void>(r=>server.close(()=>r()));});
  const port=(server.address() as {port:number}).port;
  const get=(path:string)=>new Promise<{status:number;body:string;headers:http.IncomingHttpHeaders}>((resolve,reject)=>{
    http.get({hostname:'127.0.0.1',port,path},res=>{const chunks:Buffer[]=[];res.on('data',b=>chunks.push(b));res.on('end',()=>resolve({status:res.statusCode!,body:Buffer.concat(chunks).toString('utf8'),headers:res.headers}));}).on('error',reject);
  });
  const denied=await get(`${UI_PREFIX}/embed`);
  assert.equal(denied.status,503);
  assert.match(denied.body,/connection/);
});

test('ui page is minimal: host-only input, version pill, no diagnostics',()=>{
  const html=renderPage({state:'ready',hostId:'a'.repeat(32),relayOrigin:'https://relay.example.com',
    relayUrl:'wss://relay.example.com/tunnel',relaySource:'file',secretConfigured:true,enrollSecret:'saved-password',
    powerSave:{enabled:false,active:false,supported:true},version:'0.2.0',embedded:true},'nonce-value');
  // 只回填域名：wss 地址由插件生成，用户不该在协议串里编辑。
  assert.match(html,/id="relay"[^>]*value="relay\.example\.com"/);
  assert.doesNotMatch(html,/value="wss:\/\//,'输入框里不应出现 wss 地址');
  // 版本号缩成标题旁的小胶囊。
  assert.match(html,/<span class="ver">v0\.2\.0<\/span>/);
  // 密码回填（2026-10-28 用户要求：不要每次登录都清空），但仍以掩码框呈现。
  assert.match(html,/id="secret"[^>]*type="password"[^>]*value="saved-password"/);
  assert.doesNotMatch(html,/Bearer [A-Za-z0-9_-]{43}/);
  // 整体版式居中：内容列外包一层居中容器，标题与状态行本身也居中。
  assert.match(html,/<div class="wrap">/);
  assert.match(html,/\.head\{[^}]*justify-content:center/);
  assert.match(html,/\.status\{[^}]*justify-content:center/);
  // 与操作无关的诊断行已删除。
  assert.doesNotMatch(html,/上游 DSH/);
  assert.doesNotMatch(html,/升级通道探测/);
  assert.doesNotMatch(html,/主机 id/);
  // 解释性段落也删掉（极简）。
  assert.doesNotMatch(html,/会自动补成/);
  assert.doesNotMatch(html,/一次性凭据/);
  // 脚本与样式都带 nonce。
  assert.match(html,/<style nonce="nonce-value">/);
  assert.match(html,/<script nonce="nonce-value">/);
  // 保存后要能查状态（否则用户只看到「正在连接」不知道结果）。
  assert.match(html,/dsh-mobile-sh\/api\//);
  assert.match(html,/get\('status'\)/);
  assert.match(html,/data-embedded="true"/);
});

test('saving does not wipe the password box and an empty secret renders an empty value',()=>{
  // 保存成功后脚本不得清空密码框——这是「不要每次登录都清空」的前端半边。
  const html=renderPage({state:'ready',hostId:null,relayOrigin:null,
    relayUrl:'',relaySource:'none',secretConfigured:false,enrollSecret:'',
    powerSave:{enabled:false,active:false,supported:true},version:'0.2.0',embedded:false},'n');
  assert.doesNotMatch(html,/\$\{?.*getElementById\('secret'\)\.value\s*=\s*''/);
  assert.doesNotMatch(html,/secret'\)\.value=''/);
  assert.match(html,/id="secret"[^>]*value=""/);
  // 没存过密码时占位文案是引导性的，不能假装「已设置」。
  assert.doesNotMatch(html,/已设置，留空不修改/);
});

test('a password supplied by deployment config is not echoed back into the page',()=>{
  // 来自环境变量 / patch 的那份是一次性值，只报「已配置」不回显。
  const html=renderPage({state:'unpaired',hostId:null,relayOrigin:null,
    relayUrl:'',relaySource:'config',secretConfigured:true,enrollSecret:'',
    powerSave:{enabled:false,active:false,supported:true},version:'0.2.0',embedded:true},'n');
  assert.match(html,/id="secret"[^>]*value=""/);
  assert.match(html,/placeholder="已由部署配置提供（不回显）"/);
});

test('the saved password survives a successful enroll instead of being scrubbed',{timeout:20000},async t=>{
  const {dir,cleanup}=tempDir();
  const settingsFile=join(dir,'config.json');
  // 注册这一步直接打桩 fetch：lifecycle 恒把中继 HTTP 面写成 https（见 run() 里的
  // `relay.protocol='https:'`），为了这条断言去起一个自签 TLS 中继只会把测试变脆。
  // 打桩正好只替换「中继回 200 并给出 hostId」这一个边界，其余流程都是真的。
  const realFetch=globalThis.fetch;
  const enrollCalls:Array<{url:string;auth:string}>=[];
  /** 凭据存储的落点：readRecord/modifyRecord 必须真的读写它。 */
  let record:unknown=undefined;
  globalThis.fetch=(async (input:never,init?:never)=>{
    const req=input as unknown as URL;
    const headers=(init as unknown as {headers?:Record<string,string>}|undefined)?.headers??{};
    enrollCalls.push({url:String(req),auth:headers.authorization??''});
    return new Response(JSON.stringify({hostId:'f'.repeat(32)}),{status:200,headers:{'content-type':'application/json'}});
  }) as unknown as typeof fetch;
  let dispose:(()=>void|Promise<void>)|undefined,service:any;
  const ctx:Context={effect:fn=>{dispose=fn();},get:()=>undefined,provide:(_n,value)=>{service=value;},logger:{info:()=>{},warn:()=>{}},
    webServer:{getOrigin:()=>'http://127.0.0.1:3000'},connection:{authenticatedUrl:()=>''},
    // 凭据存储要真的「写进去再读回来」：run() 写完立刻回读校验，假的 no-op 会走到
    // 解析失败分支，enroll 根本不会发生（整个测试变成假绿）。
    credentials:{readRecord:async()=>record as never,modifyRecord:async(_k:string,fn:(c:unknown)=>unknown)=>{record=await fn(record);}},
    storageDomain:{open:async()=>({table:()=>({get:()=>undefined,put:async()=>{}}),close:async()=>{}})}};
  t.after(async()=>{globalThis.fetch=realFetch;await dispose?.();cleanup();});

  writeSettingsFile(settingsFile,{version:1,relayUrl:'wss://relay.example.test/tunnel',enrollSecret:'my-long-lived-password'});
  activate(ctx,{...defaults,settingsFile},{});
  await waitFor(()=>enrollCalls.length>0,5000);
  // 注册用的是设置文件里那个密码（不是空串，也没被改成别的东西）。
  assert.equal(enrollCalls[0]!.auth,'Bearer my-long-lived-password');
  // 注册成功之后（2026-10-28 用户要求）：密码仍在设置文件里，仍能读回来给页面回填。
  // 旧实现在这里会把它清掉，于是用户每次打开面板看到的都是空输入框。
  assert.equal(readSettingsFile(settingsFile)?.enrollSecret,'my-long-lived-password','密码不该再被注册流程清掉');
  assert.equal(service.settings().enrollSecret,'my-long-lived-password');
  assert.equal(service.settings().secretConfigured,true);
  // 页面能拿到它 → 输入框有值（渲染层的断言见上面两条 renderPage 测试）。
  const html=renderPage({state:'ready',hostId:'f'.repeat(32),relayOrigin:'https://relay.example.test',
    relayUrl:'wss://relay.example.test/tunnel',relaySource:'file',secretConfigured:true,
    enrollSecret:service.settings().enrollSecret,
    powerSave:{enabled:false,active:false,supported:true},version:'0.2.0',embedded:true},'n');
  assert.match(html,/id="secret"[^>]*value="my-long-lived-password"/);
});
test('relayHostOf extracts the domain the user originally typed',async()=>{
  const { relayHostOf } = await import('../packages/plugin/src/ui-page.js');
  assert.equal(relayHostOf('wss://relay.example.com/tunnel'),'relay.example.com');
  assert.equal(relayHostOf('wss://localhost:8443/tunnel'),'localhost:8443');
  assert.equal(relayHostOf(''),'');
  assert.equal(relayHostOf('not a url'),'');
});

test('changing the relay domain with a password re-enrolls instead of stalling unpaired',{timeout:20000},async t=>{
  const {dir,cleanup}=tempDir();
  const settingsFile=join(dir,'config.json');
  let dispose:(()=>void|Promise<void>)|undefined,service:any;
  // 已存凭据绑在「旧中继」上；新设置指向另一个域名。
  const oldOrigin='https://old-relay.example.test';
  const identity={kind:'grant',payload:{hostId:uuid(),hostPublicId:uuid(),hostTunnelToken:secret(),operationId:uuid(),relayOrigin:oldOrigin}};
  let written:unknown=identity;
  const ctx:Context={effect:fn=>{dispose=fn();},get:()=>undefined,provide:(_n,value)=>{service=value;},logger:{info:()=>{},warn:()=>{}},
    webServer:{getOrigin:()=>'http://127.0.0.1:3000'},connection:{authenticatedUrl:()=>''},
    credentials:{readRecord:async()=>written as never,modifyRecord:async(_k:string,fn:(c:unknown)=>unknown)=>{written=await fn(written);}},
    storageDomain:{open:async()=>({table:()=>({get:()=>undefined,put:async()=>{}}),close:async()=>{}})}};
  t.after(async()=>{await dispose?.();cleanup();});
  activate(ctx,{...defaults,settingsFile},{});
  // 换域名 + 带密码：必须丢掉旧身份并按新密码重新注册（不再停在 unpaired 让人去手清凭据）。
  const saved=await service.saveSettings({relayUrl:'new-relay.example.test',enrollSecret:'custom-pwd'});
  assert.equal(saved.relayOrigin,'https://new-relay.example.test');
  await waitFor(()=>written!==undefined&&written!==null,3000);
  const payload=(written as {payload:{relayOrigin:string}}|undefined)?.payload;
  assert.equal(payload?.relayOrigin,'https://new-relay.example.test','旧凭据必须被新中继的身份替换');
});
test('switching back to a relay reuses the host identity saved for it',{timeout:20000},async t=>{
  // 2026-11-05 生产事故：切到自建中继试完再切回 relay.example.test，插件重新 enroll 出了
  // 一个新的 hostId，而手机的每条授权（grants）都挂在旧 hostId 上 —— 手机从此收不到任何实时
  // 数据，界面还永远停在「运行中」。规矩：换中继只是换入口，不是换机器。
  const {dir,cleanup}=tempDir();
  const settingsFile=join(dir,'config.json');
  const production='https://relay.example.test';
  const selfHosted='http://203.0.113.10';
  // 这台机器在**生产**上的身份；手机授权挂在这个 hostId 上。
  const productionHostId=uuid();
  // 当前主记录指向自建中继（上次切过去时生成的），生产那份躺在 history 里。
  let written:unknown={kind:'grant',payload:{hostId:uuid(),hostPublicId:uuid(),hostTunnelToken:secret(),operationId:uuid(),relayOrigin:selfHosted,
    history:{[production]:{hostId:productionHostId,hostPublicId:uuid(),hostTunnelToken:secret(),operationId:uuid(),savedAt:1}}}};
  let enrolls=0;
  const realFetch=globalThis.fetch;
  globalThis.fetch=(async (input:never)=>{
    if(String(input).includes('/control/enroll'))enrolls++;
    // 换 cookie 那一步失败即可：本用例只关心身份，不关心隧道。
    throw new Error('offline-in-test');
  }) as unknown as typeof fetch;
  let dispose:(()=>void|Promise<void>)|undefined,service:any;
  const ctx:Context={effect:fn=>{dispose=fn();},get:()=>undefined,provide:(_n,v)=>{service=v;},logger:{info:()=>{},warn:()=>{}},
    webServer:{getOrigin:()=>'http://127.0.0.1:3000'},connection:{authenticatedUrl:()=>''},
    credentials:{readRecord:async()=>written as never,modifyRecord:async(_k:string,fn:(c:unknown)=>unknown)=>{written=await fn(written);}},
    storageDomain:{open:async()=>({table:()=>({get:()=>undefined,put:async()=>{}}),close:async()=>{}})}};
  t.after(async()=>{globalThis.fetch=realFetch;await dispose?.();cleanup();});

  writeSettingsFile(settingsFile,{version:1,relayUrl:'wss://relay.example.test/tunnel',enrollSecret:'my-long-lived-password'});
  activate(ctx,{...defaults,settingsFile},{});
  await waitFor(()=>service?.status().hostId===productionHostId,5000);
  assert.equal(enrolls,0,'切回旧中继必须复用既有身份，不能重新注册');
  const payload=(written as {payload:{relayOrigin:string;hostId?:string;history:Record<string,unknown>}}).payload;
  assert.equal(payload.relayOrigin,production);
  assert.equal(payload.hostId,productionHostId);
  // 自建中继那份也被存档了：再切回去仍是同一台主机，来回切不再掉手机授权。
  assert.ok(payload.history[selfHosted],'旧中继的身份必须存档');
});

test('saving settings from the UI writes the file and reconnects the tunnel',{timeout:20000},async t=>{
  const {dir,cleanup}=tempDir();
  const settingsFile=join(dir,'config.json');
  // 假宿主：只提供 cookie 交换；隧道 WS 接受握手，验证「保存后真的重连了」。
  let cookieRequests=0,tunnelOpens=0;
  const server=http.createServer((req,res)=>{
    if(req.url?.startsWith('/api/session/list')){res.setHeader('content-type','application/json');res.end(JSON.stringify({result:{ok:true,value:{items:[]}}}));return;}
    cookieRequests++;
    // cookie 名由 exchangeCookie 按 Host 的 sha256 推出，写死一个名字会被当成「没换到」。
    const name='dsh-auth-'+createHash('sha256').update(req.headers.host!).digest('base64url');
    res.writeHead(303,{location:'./','set-cookie':`${name}=fixture-cookie; Max-Age=2592000; HttpOnly`});res.end();
  });
  server.listen(0,'127.0.0.1');await once(server,'listening');
  const origin=`http://127.0.0.1:${(server.address() as {port:number}).port}`;
  const wsServer=new WebSocketServer({server});
  wsServer.on('connection',ws=>{tunnelOpens++;ws.on('message',raw=>{const f=JSON.parse(raw.toString());
    if(f.type==='authenticate')ws.send(JSON.stringify({v:3,type:'auth-ok',hostId:hostId,tunnelEpoch:uuid(),policy:{requireTotp:true}}));
    if(f.type==='hello')ws.send(JSON.stringify({v:3,type:'hello-ack',capabilities:['flow','drain','ws-message'],limits:{...defaultNegotiation,maxBody:1048576,streams:8}}));
  });});
  const hostId=uuid(),hostPublicId=uuid(),hostToken=secret();
  const identity={kind:'grant',payload:{hostId,hostPublicId,hostTunnelToken:hostToken,operationId:uuid(),relayOrigin:origin}};
  let dispose:(()=>void|Promise<void>)|undefined,service:any;
  const ctx:Context={effect:fn=>{dispose=fn();},get:()=>undefined,provide:(_n,value)=>{service=value;},logger:{info:()=>{},warn:()=>{}},
    webServer:{getOrigin:()=>origin},connection:{authenticatedUrl:()=>origin+'/?token=local-fixture'},
    credentials:{readRecord:async()=>identity,modifyRecord:async()=>{}},
    storageDomain:{open:async()=>({table:()=>({get:()=>undefined,put:async()=>{}}),close:async()=>{}})}};
  t.after(async()=>{await dispose?.();for(const ws of wsServer.clients)ws.terminate();wsServer.close();await new Promise<void>(r=>server.close(()=>r()));cleanup();});

  activate(ctx,{...defaults,relayUrl:origin.replace('http:','ws:')+'/tunnel',settingsFile}, {});
  // 一次启动会开三条 WS：升级探测、隧道本体、归档客户端。它们的时序不保证能被
  // 逐个观察到（同一 tick 内完成时计数会直接跳过去），所以只断言「开过了」。
  await waitFor(()=>tunnelOpens>=1);
  await waitFor(()=>service.status().state==='ready',5000);
  const opensBeforeSave=tunnelOpens;
  assert.equal(service.settings().relaySource,'config','初始地址来自 patch');
  // UI 保存一个「裸域名」：规范化后应当写进设置文件并触发重连。
  const saved=await service.saveSettings({relayUrl:'localhost:18443',enrollSecret:secret()});
  assert.equal(saved.relayUrl,'wss://localhost:18443/tunnel');
  assert.equal(saved.relaySource,'file');
  assert.equal(saved.secretConfigured,true);
  const onDisk=readSettingsFile(settingsFile);
  assert.equal(onDisk?.relayUrl,'wss://localhost:18443/tunnel');
  assert.ok(onDisk?.enrollSecret,'邀请要落盘，直到注册成功才清除');
  // 权限：设置文件含一次性邀请，必须 0600（Windows 上 POSIX 位不生效，跳过该断言）。
  if(process.platform!=='win32')assert.equal(statSync(settingsFile).mode&0o777,0o600);
  // 重连发生：保存前那条隧道被停掉，状态离开 ready（新地址 18443 上没有中继，
  // 所以最终会落到 connecting/degraded；关键断言是「不是原来那条 ready」）。
  await waitFor(()=>service.status().state!=='ready',5000);
  // 旧隧道确实被关过：HostClient.stop() 会把状态打成 disabled，随后 restart 改成 connecting。
  assert.notEqual(service.status().relayOrigin,'https://127.0.0.1:'+new URL(origin).port);
  assert.equal(service.status().relayOrigin,'https://localhost:18443');
  assert.ok(opensBeforeSave>=1);
});

test('saving an invalid relay address is rejected before anything is written',{timeout:20000},async t=>{
  const {dir,cleanup}=tempDir();
  const settingsFile=join(dir,'config.json');
  let dispose:(()=>void|Promise<void>)|undefined,service:any;
  const ctx:Context={effect:fn=>{dispose=fn();},get:()=>undefined,provide:(_n,value)=>{service=value;},logger:{info:()=>{},warn:()=>{}},
    webServer:{getOrigin:()=>'http://127.0.0.1:3000'},connection:{authenticatedUrl:()=>''},
    credentials:{readRecord:async()=>undefined,modifyRecord:async()=>{}},
    storageDomain:{open:async()=>({table:()=>({get:()=>undefined,put:async()=>{}}),close:async()=>{}})}};
  t.after(async()=>{await dispose?.();cleanup();});
  activate(ctx,{...defaults,settingsFile},{});
  await assert.rejects(()=>service.saveSettings({relayUrl:'http://relay.example.com'}),/https/);
  assert.equal(readSettingsFile(settingsFile),undefined,'校验失败不得落盘');
  // 密码含空白会破坏 Authorization 头，必须在写盘前拒掉。
  await assert.rejects(()=>service.saveSettings({enrollSecret:'has space'}),/空白/);
  assert.equal(readSettingsFile(settingsFile),undefined);
});

test('pair() refuses before enrollment and points the user at the settings form',{timeout:20000},async t=>{
  const {dir,cleanup}=tempDir();
  let dispose:(()=>void|Promise<void>)|undefined,service:any;
  const ctx:Context={effect:fn=>{dispose=fn();},get:()=>undefined,provide:(_n,value)=>{service=value;},logger:{info:()=>{},warn:()=>{}},
    webServer:{getOrigin:()=>'http://127.0.0.1:3000'},connection:{authenticatedUrl:()=>''},
    credentials:{readRecord:async()=>undefined,modifyRecord:async()=>{}},
    storageDomain:{open:async()=>({table:()=>({get:()=>undefined,put:async()=>{}}),close:async()=>{}})}};
  t.after(async()=>{await dispose?.();cleanup();});
  activate(ctx,{...defaults,settingsFile:join(dir,'config.json')},{});
  await assert.rejects(()=>service.pair(),(e:unknown)=>e instanceof PairingError&&e.code==='not-enrolled');
});
