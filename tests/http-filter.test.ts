import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import zlib from 'node:zlib';
import { once } from 'node:events';
import { Relay } from '../packages/relay/src/server.js';
import { HostClient } from '../packages/plugin/src/client.js';
import { secret,uuid } from '../packages/relay/src/crypto.js';

async function waitFor(fn:()=>boolean,ms=5000){const start=Date.now();while(!fn()){if(Date.now()-start>ms)throw new Error('wait timeout');await new Promise(r=>setTimeout(r,10));}}

function request(port:number,path:string,token:string,data?:Buffer,headers:Record<string,string>={}){
  return new Promise<{status:number;body:Buffer;headers:http.IncomingHttpHeaders}>((resolve,reject)=>{
    const req=http.request({hostname:'127.0.0.1',port,path,method:'POST',headers:{host:'relay.example.test',authorization:`Bearer ${token}`,'content-type':'application/json','accept-encoding':'gzip','content-length':data?.length??0,...headers}},res=>{
      const chunks:Buffer[]=[];res.on('data',b=>chunks.push(b));res.on('end',()=>resolve({status:res.statusCode!,body:Buffer.concat(chunks),headers:res.headers}));
    });req.on('error',reject);req.end(data);
  });
}

const page={type:'server-response',rpcId:'p1',result:{ok:true,value:{cursor:40,hasMore:false,records:[
  {event:{type:'request/header',seq:1,time:1,data:{header:{config:'c'.repeat(20000)}}}},
  {event:{type:'assistant/message',seq:2,time:2,data:{message:{content:[{type:'reasoning',text:'r'.repeat(20000)},{type:'text',text:'kept'}]}}}},
]}}};
const body=Buffer.from(JSON.stringify(page));

/** Relay + host + phone around one upstream, so each test only supplies the host's quirks. */
async function fixture(t:any,upstreamHandler:http.RequestListener){
  const upstream=http.createServer(upstreamHandler);
  upstream.listen(0,'127.0.0.1');await once(upstream,'listening');const upstreamPort=(upstream.address() as any).port;
  const relay=new Relay({baseUrl:'https://relay.example.test',database:':memory:',key:Buffer.alloc(32,3),requireTotp:false,testMode:true});const port=await relay.listen(0);
  let client:HostClient|undefined;
  t.after(async()=>{await client?.stop();await relay.close();await new Promise<void>(r=>upstream.close(()=>r()));});
  await relay.store.createAccount('a@example.test','long-test-password',false);
  const adminLogin=await relay.store.login('a@example.test','long-test-password','',false),admin=relay.store.session(adminLogin.token,'account');
  const enrollment={operationId:uuid(),hostPublicId:uuid(),hostTunnelToken:secret(),alias:'test'};const {hostId}=relay.store.enroll(relay.store.invitation(admin),enrollment);
  const phone=await relay.store.login('a@example.test','long-test-password','',false,'Android',true),s=relay.store.session(phone.token,'native');
  relay.store.pair(s,relay.store.createPair(admin,hostId).code);
  client=new HostClient({relayUrl:`ws://127.0.0.1:${port}/tunnel`,hostId,hostTunnelToken:enrollment.hostTunnelToken,upstreamOrigin:`http://127.0.0.1:${upstreamPort}`,getCookie:async()=>'dsh-auth-test=local-secret'});
  client.options.headers={Host:'relay.example.test'};
  client.connect();await waitFor(()=>!!relay.proxy.peers.get(hostId)?.ready);
  const call=(omit?:string)=>request(port,`/native/v2/hosts/${hostId}/rpc/page`,phone.token,Buffer.from('{}'),omit?{'x-dsh-omit':omit}:{});
  return {call};
}

/** DSH answers history pages chunked with no content-length, so a filter that insists on a
 *  declared size never runs. This pins the behaviour end to end: the phone asks for omit,
 *  the host must still hand back a valid, smaller body. */
test('a chunked history page is filtered on the way to the phone',{timeout:20000},async t=>{
  const {call}=await fixture(t,async(req,res)=>{
    for await(const _ of req){/* drain */}
    // No content-length: chunked, exactly like the real host.
    res.writeHead(200,{'content-type':'application/json','transfer-encoding':'chunked'});
    for(let o=0;o<body.length;o+=4096)res.write(body.subarray(o,o+4096));
    res.end();
  });
  const plain=await call();
  assert.equal(plain.status,200);
  assert.equal(plain.body.length,body.length,'without the preference the page passes through');

  const filtered=await call('reasoning,tool,context');
  assert.equal(filtered.status,200);
  // v2 deleted the event allow-list, so a history page shrinks by the *content* it
  // carries (reasoning parts, tool args/output) and by nothing else. `request/header`
  // used to be blanked wholesale; now its 20 KB config survives, which is why the page
  // is smaller than the original but nowhere near half.
  assert.ok(filtered.body.length<body.length,`filtered ${filtered.body.length} vs ${body.length}`);
  const out=JSON.parse(filtered.body.toString('utf8'));
  assert.equal(out.result.value.cursor,40,'the envelope survives so paging keeps working');
  assert.equal(out.result.value.records[1].event.data.message.content[0].text,'kept','the answer text is kept');
  assert.ok(!filtered.body.toString('utf8').includes('r'.repeat(1000)),'the reasoning blob is gone');
  assert.ok(filtered.body.toString('utf8').includes('c'.repeat(1000)),'an event outside the projector keeps its payload');
});

/** The host compresses when asked, and the relay then forwards gzip. A filter that parses the
 *  compressed bytes sees garbage and silently passes them through, so the preference must
 *  make the host answer with the identity encoding instead. */
test('a host that compresses is asked for identity so the page can still be filtered',{timeout:20000},async t=>{
  const seen:string[]=[];
  const {call}=await fixture(t,async(req,res)=>{
    for await(const _ of req){/* drain */}
    seen.push(String(req.headers['accept-encoding']??''));
    const accept=String(req.headers['accept-encoding']??'');
    if(accept.includes('gzip')){
      res.writeHead(200,{'content-type':'application/json','content-encoding':'gzip'});
      res.end(zlib.gzipSync(body));
      return;
    }
    res.writeHead(200,{'content-type':'application/json','content-length':String(body.length)});
    res.end(body);
  });

  const plain=await call();
  assert.equal(plain.status,200);
  assert.ok(seen[0]!.includes('gzip'),'the default path keeps asking for compression');

  const filtered=await call('reasoning,tool,context');
  assert.equal(filtered.status,200);
  assert.equal(seen[1],'', 'the filter must turn compression off before the request goes out');
  assert.equal(filtered.headers['content-encoding'],undefined,'the phone gets plain JSON');
  assert.ok(filtered.body.length<body.length,`filtered ${filtered.body.length} vs ${body.length}`);
  assert.equal(JSON.parse(filtered.body.toString('utf8')).result.value.records.length,2);
});
