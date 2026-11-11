import http from 'node:http';
import { createHash } from 'node:crypto';
import WebSocket from 'ws';
import type { HostMetrics } from './metrics.js';
import { Admission, Budget, capabilities, Channel, chunkItemBytes, CONTROL_PATH, defaultNegotiation, filterRpcBody, headerObject, HttpError, LIMITS, MessageBridge, NO_OMIT, parseOmit, parseSince,
  pump, rawPairs, safePath, sanitizeHeaders, Tunnel, writeTo, type Control, type CreditStall, type Negotiation, type Open, type OmitOptions } from '../../core/src/index.js';

/** Builds the in-plugin terminus for the phone's control socket (v2 §protocol 通道收敛).
 *
 *  <p>A factory rather than an object because the caller needs the channel to build
 *  the session, and the session is what owns the replay/pulse state — the two cannot
 *  be constructed in one expression. */
export type ControlFactory = (channel: Channel) => { accept: () => Promise<void>; close: () => void };


export interface HostOptions {
  relayUrl:string;hostId:string;hostTunnelToken:string;upstreamOrigin:string;
  getCookie:()=>Promise<string>;refreshCookie?:()=>Promise<unknown>;
  maxBody?:number;maxStreams?:number;upgradePaths?:string[];connectTimeoutMs?:number;httpIdleTimeoutMs?:number;
  onState?:(state:string)=>void;onTerminal?:(code:number)=>void;
  /** Loopback failures are otherwise invisible: the relay only records `fail upstream-error`. */
  onLog?:(message:string)=>void;
  /** 宿主侧运行指标收集器（中期-12）。没有它时行为与以前一致，只是不上报。 */
  metrics?:HostMetrics;
  /** Host-side handler for the phone's v2 control socket, if this build has one. */
  control?:ControlFactory;
  /** Generation stamped by the supervisor. Sent on `authenticate` so the relay can
   *  refuse a worker generation older than the one it already holds — a wedged child
   *  that outlives its SIGTERM would otherwise reclaim the tunnel with stale code and
   *  a stale outbox. Optional: the relay treats a missing value as 0. */
  workerEpoch?:number;
  /** Test transport override; the public plugin config never accepts arbitrary headers. */
  headers?:Record<string,string>;
}
/** Ceiling for a response the plugin is willing to hold in memory to filter it.
 *  Filtering needs the whole JSON body, so anything larger streams through untouched.
 *  At most REWRITE_CONCURRENCY of these buffers may exist at once per host — the
 *  admission budget charges request bodies, not rewrite buffers, and an unbounded
 *  set of them could OOM the desktop host process the plugin lives in. */
const REWRITE_LIMIT=64*1024*1024;
const REWRITE_CONCURRENCY=2;

export class HostClient {
  tunnel?:Tunnel;private stopping=false;private socket?:WebSocket;private retry?:NodeJS.Timeout;private failures=0;
  private admission:Admission;private queue=new Budget(LIMITS.httpQueue,'host-queue');
  private rewrites=0;private rewriteWaiters:Array<()=>void>=[];
  readonly limits:Negotiation;
  private bridges=new Set<()=>void>();
  constructor(readonly options:HostOptions){
    const u=new URL(options.upstreamOrigin);if(u.protocol!=='http:'||u.hostname!=='127.0.0.1'||u.pathname!=='/')throw new Error('upstream-must-be-loopback');
    this.limits={...defaultNegotiation,maxBody:options.maxBody??LIMITS.maxBody,streams:options.maxStreams??LIMITS.streams};
    this.admission=new Admission(new Budget(Number.MAX_SAFE_INTEGER),LIMITS.largeThreshold,LIMITS.smallBudget,this.limits.streams,LIMITS.wsHostBudget);
  }
  connect(){if(this.stopping)return;this.options.onState?.('connecting');
    const socket=new WebSocket(this.options.relayUrl,{perMessageDeflate:false,maxPayload:LIMITS.fragment+12,handshakeTimeout:15000,headers:this.options.headers});this.socket=socket;
    socket.once('open',()=>{
      if(this.stopping){socket.close();return;}
      const tunnel=new Tunnel(socket,this.queue);this.tunnel=tunnel;let stage='auth';
      // 观测接线：峰值交给 metrics 每 250ms 拉走，stall 事件转成计数 + 一条日志。
      // 两者都只读，任何情况下都不得影响转发 —— 所以 catch 掉异常、日志走 onLog。
      this.options.metrics?.bindBufferedPeak(()=>tunnel.takeBufferedPeak());
      tunnel.on('stall',(event:CreditStall)=>{
        this.options.metrics?.noteStall();
        const pending=Math.round(event.pending/1024);
        const buffered=Math.round(event.buffered/1024);
        this.options.onLog?.(`tunnel credit stall ${Math.round(event.waitedMs/1000)}s stream=${event.streamId} pending=${pending}KiB buffered=${buffered}KiB`);
      });
      const timer=setTimeout(()=>socket.close(4402,'handshake-timeout'),5000);timer.unref();
      tunnel.on('control',(f:Control)=>{
        if(stage==='auth'&&f.type==='auth-ok'&&f.hostId===this.options.hostId){stage='hello';void tunnel.control({v:3,type:'hello',capabilities:['flow','drain','ws-message'],limits:this.limits}).catch(()=>{});return;}
        if(stage==='hello'&&f.type==='hello-ack'){
          if(!capabilities.every(c=>f.capabilities.includes(c))||f.limits.maxBody>this.limits.maxBody){socket.close(4422);return;}
          clearTimeout(timer);stage='ready';this.failures=0;tunnel.startHeartbeat();this.options.onState?.('ready');return;
        }
        if(stage!=='ready'){socket.close(4422,'handshake-order');return;}
        if(f.type==='http-open'||f.type==='ws-open')this.open(tunnel,f);
        else if(f.type==='drain'){const t=setTimeout(()=>void tunnel.stop(4000),30000);t.unref();}
        else socket.close(4409,'unexpected-control');
      });
      void tunnel.control({v:3,type:'authenticate',hostId:this.options.hostId,credential:this.options.hostTunnelToken,platform:process.platform,...(this.options.workerEpoch===undefined?{}:{workerEpoch:this.options.workerEpoch})}).catch(()=>{});
      socket.once('close',()=>clearTimeout(timer));
    });
    socket.on('error',()=>{});
    socket.once('close',(code)=>{
      this.tunnel=undefined;
      // 峰值读源属于刚销毁的隧道：不解绑的话 metrics 会一直读一个死 socket 的
      // bufferedAmount（读到的永远是那个终值，把之后的每个窗口都顶住）。
      this.options.metrics?.bindBufferedPeak(undefined);
      if(this.stopping)return;
      if([4401,4403,4422].includes(code)){this.options.onState?.('revoked');this.options.onTerminal?.(code);return;}
      this.options.onState?.('connecting');const ceiling=code===4409&&++this.failures>=3?300000:30000;
      const delay=code===4000?0:Math.min(500*2**Math.min(this.failures++,10),ceiling)*(0.8+Math.random()*0.4);
      this.retry=setTimeout(()=>this.connect(),delay);this.retry.unref();
    });
  }
  private open(t:Tunnel,f:Open){
    let c:Channel|undefined,release:(()=>void)|undefined;
    try{
      if(t.draining||f.identity.hostId!==this.options.hostId)throw new HttpError(403,'host-mismatch');safePath(f.rawPath);
      // v2: the phone's control socket terminates here, not at the host.
      //
      // The plugin already parses and numbers every host event, so it can answer a
      // replay from its own tail and emit pulses without a single loopback round trip.
      // Bridging to the host instead would put a JSON parse on the lowest-latency
      // stream in the system (the byte-level mux) and still leave the plugin unable to
      // answer "what does your tail hold" — which is the question a reconnecting phone
      // asks first. Deliberately outside the admission budget: these bytes never enter
      // the tunnel, and charging them against `wsHostBudget` would let a phone's control
      // stream starve real mux upgrades.
      if(f.type==='ws-open'&&f.rawPath.split('?')[0]===CONTROL_PATH){
        const ch=t.channel(f.streamId);
        const factory=this.options.control;
        if(!factory){void t.control({v:3,type:'stream-reject',streamId:f.streamId,status:503,reason:'control-disabled'}).catch(()=>{});}
        else{const session=factory(ch);ch.once('disposed',()=>session.close());void session.accept().catch(()=>session.close());}
        return;
      }
      if(f.type==='ws-open'&&!(this.options.upgradePaths??['/api/remote.mux']).includes(f.rawPath.split('?')[0]!))throw new HttpError(404,'upgrade-path');
      // The phone states which content classes it wants to receive; the relay forwards
      // custom request headers verbatim, so this survives the hop without a protocol change.
      const omit=parseOmit(f.headers.find(([k])=>k==='x-dsh-omit')?.[1]);
      // The phone already has everything up to this seq cached, so the opening replay
      // only needs the tail. Both headers ride the same request.
      const since=parseSince(f.headers.find(([k])=>k==='x-dsh-since')?.[1]);
      const n=f.type==='http-open'?(f.lengthMode==='unknown'?this.limits.maxBody:f.declaredLength??0):0;
      if(n>this.limits.maxBody)throw new HttpError(413,'body-limit');
      if(f.type==='http-open'&&(f.bodyKind==='none'&&n!==0||f.lengthMode==='exact'&&f.declaredLength===undefined))throw new HttpError(400,'body-metadata');
      release=this.admission.acquire(n,f.type==='ws-open'?5*this.limits.maxWsMessage:0);c=t.channel(f.streamId);
      const ch=c;let leaseReleased=false;
      const releaseLease=()=>{if(!leaseReleased){leaseReleased=true;release?.();}};
      let done=false,ioDone:Promise<void>=Promise.resolve();
      const finish=()=>{if(done)return;done=true;this.bridges.delete(cancel);releaseLease();void Promise.race([ioDone,new Promise(r=>setTimeout(r,1000))]).finally(()=>{ch.dispose();});};
      let ioCancel=()=>{};
      const cancel=()=>{ioCancel();finish();};this.bridges.add(cancel);ch.once('cancel',cancel);ch.once('disposed',()=>{ioCancel();finish();});
      this.options.getCookie().then(cookie=>{
        if(ch.closed)return;const u=new URL(this.options.upstreamOrigin);
        const pairs=sanitizeHeaders(f.headers,'request'),headers=headerObject(pairs);
        headers.host=u.host;headers.origin=u.origin;headers['sec-fetch-site']='same-origin';headers.cookie=cookie;
        if(f.type==='http-open'){
          delete headers['content-length'];if(f.lengthMode==='exact')headers['content-length']=String(f.declaredLength??0);
          // Ask for the identity encoding on the calls that get rewritten: the filter needs the
          // JSON in memory, and a gzipped body would parse as garbage and pass through
          // untouched, quietly losing the savings. Scoped to the session RPC path so a large
          // non-JSON answer is still compressed over the tunnel. This has to happen before the
          // request is created — clearing the header on the response is too late to matter.
          const isSessionList=f.rawPath.includes('session/list');
          if((omit||isSessionList)&&f.rawPath.startsWith('/api/session/')){delete headers['accept-encoding'];delete headers['if-none-match'];}
          // agent:false — do NOT reuse loopback sockets. The host answers `keep-alive: timeout=5`,
          // but the pooled socket stays in the agent until the FIN is actually read; if the host
          // process stalls its event loop (it is a desktop app sharing one thread with the UI and
          // with us), the next request picks a socket the peer already closed and dies with
          // ECONNRESET. A fresh connect costs ~0.5ms on loopback, so reuse buys nothing here.
          const req=http.request({agent:false,hostname:'127.0.0.1',port:Number(u.port),path:f.rawPath,method:f.method,headers,timeout:this.options.connectTimeoutMs??15000});
          this.options.metrics?.noteLoopbackAttempt();
          ioDone=new Promise(resolve=>req.once('close',()=>resolve()));
          ioCancel=()=>req.destroy();let received=0;
          const responseTimeout=setTimeout(()=>{req.destroy();},this.options.httpIdleTimeoutMs??120000);responseTimeout.unref();
          req.once('error',e=>{const code=(e as NodeJS.ErrnoException).code??e.message;this.options.onLog?.(`loopback ${f.method} ${f.rawPath} failed: ${code}`);this.options.metrics?.noteLog(`loopback ${f.method} ${f.rawPath} failed: ${code}`);void ch.send({v:3,type:'stream-reject',status:502,reason:'upstream-error'}).catch(()=>{});finish();});
          req.once('timeout',()=>req.destroy());
          req.once('response',res=>{
            clearTimeout(responseTimeout);req.setTimeout(0);if(res.statusCode===401)void this.options.refreshCookie?.().catch(()=>{});
            if(res.headers['content-type']?.split(';')[0]!=='text/event-stream')res.setTimeout(this.options.httpIdleTimeoutMs??120000,()=>res.destroy());
            let responseHeaders;try{responseHeaders=sanitizeHeaders(rawPairs(res.rawHeaders),'response',u.origin);}catch{res.destroy();finish();return;}
            // History pages come back over plain HTTP, where a page of a busy session is as
            // expensive as a snapshot. Filtering has to buffer the whole body, so it only
            // applies to answers the relay could not have sent incrementally anyway: plain
            // JSON, and small enough to hold. A declared length is a cheap early out, but
            // the host answers most pages chunked, so an absent length must still filter.
            // Filtering changes the length, so the declared length and its validators have
            // to go or a cache would keep serving the unfiltered body.
            const json=res.headers['content-type']?.split(';')[0]==='application/json';
            const declared=Number(res.headers['content-length']);
            const rewrite=(omit||isSessionList)&&json&&!(Number.isSafeInteger(declared)&&declared>REWRITE_LIMIT);
            // rawPairs keeps the upstream's original casing, so compare case-insensitively:
            // a surviving `Content-Length` describes the *unfiltered* body, and the relay then
            // waits for bytes that never arrive — the client sees a response that never ends.
            // Validators go for the same reason: the ETag no longer matches the filtered bytes.
            if(rewrite)responseHeaders=responseHeaders.filter(([k])=>{const n=k.toLowerCase();return n!=='content-length'&&n!=='etag'&&n!=='content-md5'&&n!=='digest';});
            // Buffering a response for rewrite bypasses the admission budget (which charges
            // the request), so the buffers need their own ceiling: acquire one of two slots
            // before the body starts, release when the filtered bytes are on the channel.
            // While waiting the pump has not started, so the upstream socket buffers in
            // its kernel window — bounded, unlike an unbounded set of 64 MiB rewrites.
            let releaseRewrite:()=>void=()=>{};
            void ch.send({v:3,type:'http-response',status:res.statusCode??502,headers:responseHeaders})
              .then(async()=>{
                if(rewrite)releaseRewrite=await this.acquireRewrite();
                return pump(res,ch,rewrite?REWRITE_LIMIT:Number.MAX_SAFE_INTEGER,undefined,undefined,rewrite?b=>filterRpcBody(b,omit??NO_OMIT,f.rawPath):undefined);
              })
              .then(async()=>{releaseRewrite();if(!req.writableEnded)req.destroy();await ch.send({v:3,type:'stream-closed',reason:'closed'}).catch(()=>{});finish();})
              .catch(()=>{releaseRewrite();ch.cancel('response-error');finish();});
          });
          ch.startReceive(async b=>{received+=b.length;if(received>this.limits.maxBody||f.lengthMode==='exact'&&received>f.declaredLength!)throw new Error('body-limit');await writeTo(req,b);},f.lengthMode==='exact'?f.declaredLength:undefined);
          ch.once('end',()=>{if(f.lengthMode==='exact'&&received!==f.declaredLength){ch.cancel('body-underrun');return;}req.end();});
          req.once('close',()=>clearTimeout(responseTimeout));
          void ch.send({v:3,type:'stream-ready'}).catch(cancel);
        }else{
          const path=new URL(f.rawPath,u);path.protocol='ws:';
          const ws=new WebSocket(path,f.protocols,{headers,perMessageDeflate:false,maxPayload:this.limits.maxWsMessage,handshakeTimeout:this.options.connectTimeoutMs??15000});ioCancel=()=>ws.terminate();
          ioDone=new Promise(resolve=>ws.once('close',()=>resolve()));
          ws.on('error',()=>{if(ws.readyState!==WebSocket.OPEN){void ch.send({v:3,type:'ws-reject',status:502,headers:[]}).then(()=>ch.end()).finally(finish).catch(()=>{});}});
          ws.once('unexpected-response',(_req,res)=>{
            if(res.statusCode===401)void this.options.refreshCookie?.().catch(()=>{});
            void ch.send({v:3,type:'ws-reject',status:res.statusCode??502,headers:sanitizeHeaders(rawPairs(res.rawHeaders),'response',u.origin)})
              .then(()=>pump(res,ch,65536)).finally(finish).catch(()=>{});
          });
          ws.once('open',()=>{
            ws.pause();new MessageBridge(ws,ch,this.limits.maxWsMessage,finish,omit||since!==undefined?bytes=>chunkItemBytes(bytes,false,omit??NO_OMIT,since):undefined);
            void ch.send({v:3,type:'ws-accept',protocol:ws.protocol}).then(()=>ws.resume()).catch(cancel);
          });
        }
      }).catch(()=>{void ch.send({v:3,type:'stream-reject',status:503,reason:'cookie-unavailable'}).then(finish).catch(finish);});
    }catch(e){release?.();c?.dispose();void t.control({v:3,type:'stream-reject',streamId:f.streamId,status:e instanceof HttpError?e.status:503,reason:'admission-rejected'}).catch(()=>{});}
  }
  private async acquireRewrite():Promise<()=>void>{
    while(this.rewrites>=REWRITE_CONCURRENCY)await new Promise<void>(resolve=>this.rewriteWaiters.push(resolve));
    this.rewrites++;let released=false;
    return ()=>{if(released)return;released=true;this.rewrites--;this.rewriteWaiters.shift()?.();};
  }
  async stop(){this.stopping=true;clearTimeout(this.retry);for(const stop of [...this.bridges])stop();if(this.tunnel)await this.tunnel.stop();else this.socket?.terminate();this.options.onState?.('disabled');}
}

export function exchangeCookie(origin:string,url:string):Promise<string>{return new Promise((resolve,reject)=>{
  // agent:false — same loopback keep-alive hazard as HostClient.open().
  const u=new URL(origin);const req=http.get(url,{agent:false,headers:{Host:u.host},timeout:15000},res=>{
    const name='dsh-auth-'+createHash('sha256').update(u.host).digest('base64url');
    const cookie=res.headers['set-cookie']?.find(c=>c.startsWith(name+'=')&&/Max-Age=[1-9]\d*/i.test(c));
    res.resume();if(res.statusCode!==303||!cookie||res.headers.location!=='./')reject(new Error('cookie-exchange'));else resolve(cookie.split(';',1)[0]!);
  });req.once('timeout',()=>req.destroy(new Error('cookie-timeout')));req.once('error',reject);
});}
