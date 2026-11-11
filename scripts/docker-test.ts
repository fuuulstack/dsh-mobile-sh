import { execFileSync } from 'node:child_process';
import { writeFileSync,mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { build } from 'esbuild';
import http from 'node:http';
import { once } from 'node:events';
import { HostClient } from '../packages/plugin/src/client.js';
import { secret,uuid } from '../packages/relay/src/crypto.js';
import assert from 'node:assert/strict';

const name='dsh-mobile-sh-local-test',volume='dsh-mobile-sh-local-test-data',image='dsh-mobile-sh-relay:0.3.0';
// 镜像里唯一的必填项就是这一个密码（同时也是主机注册密码）。这里显式写上，
// 顺带钉住「一行命令只需要 RELAY_PASSWORD」这条产品承诺不被悄悄加回第二个必填。
const password='docker-local-test-password',email='admin@relay.local';
const docker=(...args:string[])=>execFileSync('docker',args,{encoding:'utf8',stdio:['ignore','pipe','pipe']}).trim();
const result:{checks:string[];image:string;container:string;port?:number;retained?:boolean;imageId?:string;testedAt?:string}={checks:[],image,container:name};
mkdirSync('artifacts',{recursive:true});
await build({entryPoints:['scripts/docker-provision.ts'],outfile:'artifacts/docker-provision.mjs',bundle:true,platform:'node',target:'node24',format:'esm'});
// Only remove test resources with this exact dedicated name, never user services or volumes.
try{docker('rm','-f',name);}catch{}
try{docker('volume','rm',volume);}catch{}
docker('volume','create',volume);
const gateway=JSON.parse(docker('network','inspect','bridge'))[0].IPAM.Config[0].Gateway;
const env=['-e','RELAY_BASE_URL=https://relay.example.test','-e',`RELAY_PASSWORD=${password}`,'-e','RELAY_SECRET_KEY_FILE=/data/relay.key','-e','DATABASE_URL=/data/relay.db','-e','RELAY_LISTEN_HOST=0.0.0.0','-e',`RELAY_TRUSTED_PROXY=${gateway}`];
const fixture=resolve('artifacts/docker-provision.mjs').replaceAll('\\','/');
const provision=(action:string,...args:string[])=>JSON.parse(docker('run','--rm','-v',`${volume}:/data`,'-v',`${fixture}:/tmp/provision.mjs:ro`,...env,'--entrypoint','node',image,'/tmp/provision.mjs',action,...args));
const account=provision('init');
docker('run','-d','--name',name,'--health-interval=1s','--health-timeout=3s','--read-only','--cap-drop=ALL','--security-opt=no-new-privileges:true','--tmpfs','/tmp:rw,size=16m','-p','127.0.0.1::8080','-v',`${volume}:/data`,...env,image);
let port=Number(docker('port',name,'8080/tcp').split(':').at(-1));result.port=port;
function request(path:string,token='',data?:object,headers:Record<string,string>={}){return new Promise<{status:number;body:any}>((resolve,reject)=>{
  const bytes=data===undefined?undefined:Buffer.from(JSON.stringify(data));const req=http.request({host:'127.0.0.1',port,path,method:bytes?'POST':'GET',headers:{host:'relay.example.test',...(token?{authorization:`Bearer ${token}`} :{}),...(bytes?{'content-type':'application/json','content-length':String(bytes.length)}:{}),...headers}},res=>{let text='';res.on('data',b=>text+=b);res.on('end',()=>{let body:any=text;try{body=JSON.parse(text);}catch{}resolve({status:res.statusCode!,body});});});req.on('error',reject);req.end(bytes);
});}
let plugin:HostClient|undefined,upstream:http.Server|undefined;
try{
  let health;for(let i=0;i<50;i++){try{health=await request('/healthz');if(health.status===200)break;}catch{}await new Promise(r=>setTimeout(r,100));}
  assert.equal(health?.status,200,'container proxy trust / health');result.checks.push('non-root readonly container health');
  // 启动横幅是「一行命令」的可见证据：TOTP 关着（单密码模式的默认）与容量数字都得在
  // `docker logs` 第一屏，否则运维根本不知道自己开的是什么。
  const boot=docker('logs',name);
  assert.match(boot,/Password mode: admin=admin@relay\.local TOTP=OFF/);
  assert.match(boot,/Capacity (default|custom) /);
  assert.match(boot,/already exists, skipped provisioning/);
  // 单密码模式：只给邮箱 + 密码，不带验证码。
  const login=await request('/native/v2/login','',{email,password,label:'Docker Android test'});assert.equal(login.status,200);const token=login.body.token;result.checks.push('single-password login, TOTP off by default');
  const hostToken=secret();const enroll=await request('/control/enroll',account.invitation,{operationId:uuid(),hostPublicId:uuid(),hostTunnelToken:hostToken,alias:'Docker电脑'});assert.equal(enroll.status,200);const hostId=enroll.body.hostId;
  const pair=provision('pair',hostId);assert.equal((await request('/native/v2/pair',token,{code:pair.code})).status,200);result.checks.push('account-bound enroll and device pair persisted in volume');
  // 假宿主只做一件事：记下真的收到了哪句话，并把宿主的回答原样吐回去。
  let promptSeen:string|undefined,resolvePrompt:()=>void=()=>{};
  const promptArrived=new Promise<void>(r=>{resolvePrompt=r;});
  upstream=http.createServer(async(req,res)=>{
    assert.equal(req.headers.cookie,'dsh-auth-container=host-only','插件必须带上宿主的会话 cookie');
    let text='';for await(const b of req)text+=b;const f=JSON.parse(text);
    const prompt=f.payload.args.request.content?.[0]?.text as string|undefined;
    if(prompt){promptSeen=prompt;resolvePrompt();}
    res.setHeader('set-cookie','dsh-auth-container=must-strip');res.setHeader('content-type','application/json');res.end(JSON.stringify({type:'server-response',rpcId:f.rpcId,result:{ok:true,value:{accepted:true}}}));
  });upstream.listen(0,'127.0.0.1');await once(upstream,'listening');const upPort=(upstream.address() as any).port;
  plugin=new HostClient({relayUrl:`ws://127.0.0.1:${port}/tunnel`,headers:{Host:'relay.example.test'},hostId,hostTunnelToken:hostToken,upstreamOrigin:`http://127.0.0.1:${upPort}`,getCookie:async()=>'dsh-auth-container=host-only'});
  let connected=false;plugin.options.onState=s=>connected=s==='ready';plugin.connect();for(let i=0;i<100&&!connected;i++)await new Promise(r=>setTimeout(r,50));assert.ok(connected);
  // 手机的 v1 流式 WS（`/native/v2/hosts/:id/stream`）已经删了，v2 唯一的长连接是
  // `/control`，它的协议级行为由 `tests/integration-v2.test.ts` 在进程内覆盖。
  // 这里要证的只是**容器里的这一跳**：请求真的落到了假宿主，回答也真的从容器里回来了。
  const rpc=await request(`/native/v2/hosts/${hostId}/rpc/prompt`,token,{type:'client-request',rpcId:uuid(),method:'session/prompt',payload:{args:{request:{sessionId:'local',requestId:uuid(),content:[{type:'text',text:'你好Docker'}],mode:'queue'}}}});
  assert.equal(rpc.status,200,rpc.body);
  await Promise.race([promptArrived,new Promise((_,rej)=>setTimeout(()=>rej(new Error('prompt never reached the fake host inside the container')),10_000))]);
  assert.equal(promptSeen,'你好Docker');
  assert.match(JSON.stringify(rpc.body),/"accepted":true/,rpc.body);
  result.checks.push('native chat HTTP send round-trip through container relay and host plugin');
  await plugin.stop();plugin=undefined;docker('restart',name);
  port=Number(docker('port',name,'8080/tcp').split(':').at(-1));result.port=port;
  for(let i=0;i<50;i++){try{if((await request('/healthz')).status===200)break;}catch{}await new Promise(r=>setTimeout(r,100));}
  assert.equal((await request('/native/v2/hosts',token)).body.items[0].hostId,hostId);result.checks.push('container restart preserves SQLite accounts, grants and bearer sessions');
  assert.equal((await request('/native/v2/hosts',token,undefined,{Origin:'https://evil.test'})).status,403);result.checks.push('browser origin rejected on native bearer endpoint');
  const inspect=JSON.parse(docker('inspect',name))[0];assert.equal(inspect.Config.User,'node');assert.equal(inspect.HostConfig.ReadonlyRootfs,true);
  // 镜像里的 HEALTHCHECK 带 --start-period=10s：容器重启后这段时间内状态停在
  // `starting`（探针照跑，但状态机要等起始期过完才切 `healthy`），所以等待预算
  // 必须覆盖起始期，不能只留几秒。
  for(let i=0;i<200;i++){if(docker('inspect',name,'--format','{{.State.Health.Status}}')==='healthy')break;await new Promise(r=>setTimeout(r,100));}
  assert.equal(docker('inspect',name,'--format','{{.State.Health.Status}}'),'healthy');result.checks.push('Docker HEALTHCHECK healthy after restart');
  result.imageId=inspect.Image;result.testedAt=new Date().toISOString();
  const stored=provision('inspect');assert.equal(stored.hosts,1);assert.ok(stored.audit>0);
  writeFileSync('artifacts/docker-fixture-identity.json',JSON.stringify({hostId,hostToken,totp:account.totp}));
  result.retained=true;writeFileSync('artifacts/docker-test-result.json',JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));
}finally{await plugin?.stop();if(upstream)await new Promise<void>(resolve=>upstream!.close(()=>resolve()));}
