import http from 'node:http';
import https from 'node:https';
import net from 'node:net';
import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';
import { readFileSync,writeFileSync } from 'node:fs';
import { once } from 'node:events';
import { WebSocketServer } from 'ws';
import { Relay } from '../packages/relay/src/server.js';
import { HostClient } from '../packages/plugin/src/client.js';
import { secret,uuid,totp } from '../packages/relay/src/crypto.js';

const feeds=new Set<import('ws').WebSocket>(),records:any[]=[];let seq=0;
function emit(value:any){for(const ws of feeds)ws.send(JSON.stringify({type:'item',streamId:'chat',value}));}
const upstream=http.createServer(async(req,res)=>{
  let text='';for await(const c of req)text+=c;const envelope=JSON.parse(text),method=envelope.method,args=envelope.payload?.args;
  if(!args||Object.keys(envelope.payload).length!==1)throw new Error('DSH args envelope missing');
  const request=args.request;let value:any={};
  if(method==='session/list')value={items:[]};
  else if(method==='session/create')value={sessionId:'test-session'};
  else if(method==='session/prompt'){
    if(!request?.requestId||!request?.content?.[0]?.text)throw new Error('invalid prompt');
    const user={type:'event',event:{seq:++seq,time:Date.now(),type:'user/message',data:{id:uuid(),role:'user',content:request.content}}};records.push(user);emit(user);
    const answer='来自电脑插件的回复：'+request.content[0].text;
    emit({type:'assistant-stream',frame:{type:'start',attemptId:'a'}});
    emit({type:'assistant-stream',frame:{type:'chunk',attemptId:'a',index:0,chunk:{type:'text-delta',index:0,text:answer}}});
    const event={type:'event',event:{seq:++seq,time:Date.now(),type:'assistant/message',data:{message:{id:uuid(),role:'assistant',content:[{type:'text',text:answer}]}}}};
    records.push(event);setTimeout(()=>emit(event),150);value={accepted:true};
  }else if(method==='session/cancel')value={};else {res.writeHead(404);res.end();return;}
  res.setHeader('content-type','application/json');res.end(JSON.stringify({type:'server-response',rpcId:envelope.rpcId,result:{ok:true,value}}));
});upstream.listen(0,'127.0.0.1');await once(upstream,'listening');const upPort=(upstream.address() as net.AddressInfo).port;
const mux=new WebSocketServer({noServer:true});upstream.on('upgrade',(req,socket,head)=>mux.handleUpgrade(req,socket,head,ws=>{
  feeds.add(ws);ws.once('close',()=>feeds.delete(ws));ws.on('message',bytes=>{const f=JSON.parse(bytes.toString());if(f.type==='open'){
    if(f.endpoint!=='session/follow'||!f.payload?.args?.request?.address)throw new Error('invalid follow envelope');
    ws.send(JSON.stringify({type:'item',streamId:f.streamId,value:{type:'snapshot',cursor:seq,records,hasMore:false,header:{id:'test-session'},projections:{}}}));
  }});
}));
const dockerMode=process.argv.includes('--docker');
let relay:Relay|undefined,port:number,hostId:string,hostToken:string,pairCode:string,fixtureTotp='';
if(dockerMode){
  const docker=(...args:string[])=>execFileSync('docker',args,{encoding:'utf8',stdio:['ignore','pipe','pipe']}).trim();
  const identity=JSON.parse(readFileSync('artifacts/docker-fixture-identity.json','utf8'));
  port=Number(docker('port','dsh-mobile-sh-local-test','8080/tcp').split(':').at(-1));
  hostId=identity.hostId;hostToken=identity.hostToken;fixtureTotp=identity.totp;
  const helper=resolve('artifacts/docker-provision.mjs').replaceAll('\\','/');
  pairCode=JSON.parse(docker('run','--rm','-v','dsh-mobile-sh-local-test-data:/data','-v',`${helper}:/tmp/provision.mjs:ro`,'-e','RELAY_SECRET_KEY_FILE=/data/relay.key','-e','DATABASE_URL=/data/relay.db','--entrypoint','node','dsh-mobile-sh-relay:0.2.0','/tmp/provision.mjs','pair',hostId)).code;
}else{
  relay=new Relay({baseUrl:'https://relay.example.test',database:':memory:',key:Buffer.alloc(32,6),requireTotp:false,testMode:true});port=await relay.listen(0);
  await relay.store.createAccount('android@example.test','local-android-test-password',false);
  const login=await relay.store.login('android@example.test','local-android-test-password','',false),admin=relay.store.session(login.token,'account');
  const input={operationId:uuid(),hostPublicId:uuid(),hostTunnelToken:secret(),alias:'模拟电脑'},host=relay.store.enroll(relay.store.invitation(admin),input);
  hostId=host.hostId;hostToken=input.hostTunnelToken;pairCode=relay.store.createPair(admin,hostId).code;
}
const plugin=new HostClient({relayUrl:`ws://127.0.0.1:${port}/tunnel`,hostId,hostTunnelToken:hostToken,upstreamOrigin:`http://127.0.0.1:${upPort}`,getCookie:async()=>'dsh-auth-fixture=local',headers:{Host:'relay.example.test'}});plugin.connect();
const tls=https.createServer({pfx:readFileSync('artifacts/android-fixture.p12'),passphrase:'local-fixture-only'},(req,res)=>{
  const proxy=http.request({host:'127.0.0.1',port,path:req.url,method:req.method,headers:{...req.headers,host:'relay.example.test'}},up=>{res.writeHead(up.statusCode!,up.headers);up.pipe(res);});proxy.on('error',()=>{res.writeHead(502);res.end();});req.pipe(proxy);
});tls.on('upgrade',(req,socket,head)=>{
  const raw=net.connect(port,'127.0.0.1',()=>{const headers=[`GET ${req.url} HTTP/1.1`,'Host: relay.example.test'];for(const [k,v]of Object.entries(req.headers))if(k!=='host')headers.push(`${k}: ${v}`);raw.write(headers.join('\r\n')+'\r\n\r\n');if(head.length)raw.write(head);socket.pipe(raw).pipe(socket);});raw.on('error',()=>socket.destroy());socket.on('error',()=>raw.destroy());socket.once('close',()=>raw.destroy());
});tls.listen(8443,'127.0.0.1');await once(tls,'listening');
const saveState=()=>writeFileSync('artifacts/android-fixture-state.json',JSON.stringify({pairCode,totpCode:fixtureTotp?totp(fixtureTotp):'',email:dockerMode?'docker@example.test':'android@example.test',password:dockerMode?'docker-local-test-password':'local-android-test-password',hostAlias:dockerMode?'Docker电脑':'模拟电脑',relay:dockerMode?'docker':'in-process'}));
saveState();const stateTimer=setInterval(saveState,1000);stateTimer.unref();
console.log(`Isolated Android TLS fixture ready on 8443 (${dockerMode?'Docker':'in-process'} Relay); test input saved to artifacts/android-fixture-state.json`);
const stop=async()=>{clearInterval(stateTimer);await plugin.stop();await relay?.close();tls.closeAllConnections();tls.close();mux.close();upstream.close();process.exit(0);};process.on('SIGINT',stop);process.on('SIGTERM',stop);
