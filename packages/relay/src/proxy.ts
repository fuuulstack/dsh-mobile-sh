import http, { type IncomingMessage, type ServerResponse } from 'node:http';
import { isIP } from 'node:net';
import type { Duplex } from 'node:stream';
import WebSocket, { WebSocketServer } from 'ws';
import { Admission, Budget, capabilities, Channel, checkOrigin, defaultNegotiation, bodyInfo, headerObject, HttpError, LIMITS,
  MessageBridge, pump, rawPairs, safePath, sanitizeHeaders, Tunnel, writeTo, type Control, type Identity, type Negotiation } from '../../core/src/index.js';
import { uuid } from './crypto.js';
import { Store, type Row } from './store.js';
import { DEFAULT_CAPACITY, type Capacity } from './limits.js';

type Peer={tunnel:Tunnel;accountId:string;epoch:string;workerEpoch:number;limits:Negotiation;admission:Admission;next:number;ready:boolean};
type Active={identity:Identity;cancel:()=>void};
/**
 * 超过这么久没收到这台主机的任何一帧，就当它离线。
 *
 * 心跳 30s 一次（`Tunnel.startHeartbeat`），取 45s＝1.5 个周期：活着的主机绝不会
 * 被误判，掉线的主机 45s 内一定翻成离线。调小会在宿主事件循环偶发卡顿时误报。
 */
const PEER_LIVENESS_MS=45_000;
/** 距上次收到帧超过这么久，查询在线状态时顺手补一帧 ping（不足则不必问）。 */
const PEER_PROBE_MS=10_000;
/** Client IP for rate limiting and unauthenticated budgets.
 *
 *  In production every request arrives from the reverse proxy, so the socket peer
 *  is always the proxy itself and a naive `remoteAddress` key collapses all
 *  callers into one bucket: five stray login attempts from anywhere then lock out
 *  every user behind a shared 429. The proxy sets `X-Forwarded-For` to the peer it
 *  saw, so the last comma-separated entry is the client address to charge. */
export function clientIp(req:IncomingMessage,trustedProxy?:string,testMode?:boolean,trustAnyPeer?:boolean):string{
  const remote=req.socket.remoteAddress??'';
  const trusted=trustAnyPeer||(!testMode&&(remote===trustedProxy||remote==='127.0.0.1'||remote==='::ffff:127.0.0.1'||remote==='::1'));
  if(!trusted)return remote;
  const header=req.headers['x-forwarded-for'];
  if(typeof header!=='string')return remote;
  const last=header.split(',').pop()!.trim();
  return isIP(last)?last:remote;
}
/**
 * 这个来源地址配不配被服务。
 *
 * <p>判据只有一条：**声明了反代就只认那个地址**。反代地址是运维手写的常量，其余
 * 来源要么是绕过反代直连（应当拒绝），要么是扫段（也应当拒绝）。
 *
 * <p>**没声明反代就是直连部署，不做这层拦截。** 早先实现在这种情况下会拒绝一切非
 * loopback 来源——本地 `npm start` 看不出问题（127.0.0.1 放行），但容器里
 * `remoteAddress` 是 Docker 网桥网关，于是 `docker run -e RELAY_PASSWORD …` 起来后
 * 每个请求都是 `403 · untrusted-proxy`：一行命令能起服务，却一个页面都打不开。
 * 直连时不存在「借反代洗 IP」的空间（{@link clientIp} 不采信 XFF，限流键就是真实
 * socket 地址），拦下来只是把可用部署判成故障。
 */
export function trustedPeer(remote:string,trustedProxy?:string,testMode?:boolean,trustAnyPeer?:boolean):boolean{
  if(testMode||trustAnyPeer)return true;
  if(!trustedProxy)return true;
  return remote===trustedProxy||remote==='127.0.0.1'||remote==='::ffff:127.0.0.1'||remote==='::1';
}

/**
 * 每来源 IP 的「认证前」额度：同一 IP 最多 `perIp` 条并发未认证隧道。
 *
 * <p>**必须有界**。这张表的键是公网 IP，来源无穷；早期实现只在建隧道时 `set`、
 * 从来不删，扫描器换个地址就来一条，768MB 的机器上跑几天就是几十万条常驻。
 * 现在按 IP 记引用计数，降到 0 就把这一项删掉——`Budget.used` 才是限流依据，
 * 空掉的 IP 没有任何理由继续占着内存。
 *
 * <p>另外**失败路径也必须能回滚**：`take()` 抛错时若已经 `set` 了一条 `open=0`
 * 的空壳，没有人会再来清它，于是「每次被拒都留一条」比不删更糟。
 */
export class UnauthSlots {
  private readonly map=new Map<string,{budget:Budget,open:number}>();
  constructor(private readonly perIp=4,private readonly name='ip-unauth'){}
  /** 取一个名额；返回释放函数（幂等）。额度用尽时抛 `ip-unauth-capacity`。 */
  acquire(ip:string):()=>void{
    const slot=this.map.get(ip)??{budget:new Budget(this.perIp,this.name),open:0};
    this.map.set(ip,slot);
    let give:()=>void;
    try{give=slot.budget.take(1);}
    catch(e){if(slot.open<=0&&this.map.get(ip)===slot)this.map.delete(ip);throw e;}
    slot.open++;
    let held=true;
    return ()=>{if(!held)return;held=false;give();slot.open--;
      if(slot.open<=0&&this.map.get(ip)===slot)this.map.delete(ip);};
  }
  /** 当前记着的 IP 条数（运维与测试用：它必须跟着并发数回落）。 */
  size():number{return this.map.size;}
}

export class Proxy {
  readonly peers=new Map<string,Peer>();private active=new Set<Active>();
  // 这几个 Budget 的额度来自 capacity，而 TS 的字段初始化器跑在构造函数体之前、拿不到
  // 构造参数，所以只能先声明、在构造函数里赋值（别顺手改回 `= new Budget(...)` 的形式）。
  private readonly wsServer:WebSocketServer;
  private readonly tunnelServer:WebSocketServer;
  private readonly reservations:Budget;private readonly wsNode:Budget;
  private readonly receives:Budget;private readonly streams:Budget;
  private readonly pending=new Budget(128,'pending');private readonly pendingHosts=new Map<string,Budget>();
  private readonly unauth=new Budget(32,'unauth');
  /** 每来源 IP 的「认证前」额度，见 {@link UnauthSlots}。 */
  private readonly unauthIp=new UnauthSlots(4);
  private onRevoke:(e:Row)=>void;
  /**
   * @param hostOrigin 某台主机的浏览器 Origin。子域模式是 `https://<id>.<域名>`，
   *   路径模式就是中继自己的 origin（同一个 origin 只差路径）。由 `Relay` 注入，
   *   `Proxy` 本身不关心是哪种寻址方式。
   */
  constructor(readonly store:Store,readonly baseOrigin:string,readonly requireTotp:boolean,readonly trustedProxy?:string,readonly testMode?:boolean,readonly trustAnyPeer?:boolean,readonly hostOrigin?:(hostId:string,req:IncomingMessage)=>string,readonly capacity:Capacity=DEFAULT_CAPACITY,
    /**
     * 中继没钉对外地址时为真：Origin 校验只比 host、不比 scheme。
     *
     * <p>理由见 `checkOrigin` 的 `sameHost` 参数——TLS 在反代处终止时中继分不清
     * 请求到底是 http 还是 https，死比 scheme 会把所有正常表单判成 403。
     */
    readonly sameHostOrigin?:boolean){
    // wsMessage / fragment 不跟容量走：那是线格式契约，改了插件会 4422 unsupported-window。
    this.wsServer=new WebSocketServer({noServer:true,perMessageDeflate:false,maxPayload:LIMITS.wsMessage});
    this.tunnelServer=new WebSocketServer({noServer:true,perMessageDeflate:false,maxPayload:LIMITS.fragment+12});
    this.reservations=new Budget(capacity.nodeReservation,'node-reservations');
    this.wsNode=new Budget(capacity.wsNodeBudget,'ws-node');
    this.receives=new Budget(capacity.httpQueue,'relay-receive');
    this.streams=new Budget(capacity.nodeStreams,'node-streams');
    this.onRevoke=e=>{
      for(const a of this.active){const i=a.identity;
        if(e.kind==='account'&&i.accountId===e.target||e.kind==='device'&&i.deviceId===e.target||e.kind==='host'&&i.hostId===e.target||e.kind==='session'&&i.sessionId===e.target||e.kind==='grant'&&i.deviceId===e.target&&i.hostId===e.hostId)a.cancel();
        // Logout also invalidates child host sessions. Resolve their parent without trusting caller headers.
        else if(e.kind==='session'&&this.store.get('SELECT parent FROM sessions WHERE id_hash=?',i.sessionId)?.parent===e.target)a.cancel();
      }
      for(const [host,p]of this.peers)if(e.kind==='host'&&host===e.target||e.kind==='account'&&p.accountId===e.target)void p.tunnel.stop(e.kind==='host'?4403:4401);
    };store.on('revoked',this.onRevoke);
  }
  acceptTunnel(req:IncomingMessage,socket:Duplex,head:Buffer){
    let releases:Array<()=>void>=[];
    let releaseIp:(()=>void)|undefined;
    try{
      if(this.peers.size>=this.capacity.maxHosts)throw new Error('tunnel-limit');
      const ip=clientIp(req,this.trustedProxy,this.testMode,this.trustAnyPeer);
      releaseIp=this.unauthIp.acquire(ip);
      releases=[this.unauth.take(1),releaseIp];
    }
    catch{
      releaseIp?.();
      releases.forEach(f=>f());
      rejectSocket(socket,429);return;
    }
    this.tunnelServer.handleUpgrade(req,socket,head,ws=>{
      const tunnel=new Tunnel(ws,this.receives);let stage='auth',peer:Peer|undefined,hostId='';
      const timeout=setTimeout(()=>ws.close(4402,'auth-timeout'),5000);timeout.unref();
      const releaseAuth=()=>{releases.forEach(f=>f());releases=[];};
      tunnel.on('control',(f:Control)=>{
        if(stage==='auth'){
          if(f.type!=='authenticate'){ws.close(4409,'auth-required');return;}
          try{
            const host=this.store.authenticate(f.hostId,f.credential);hostId=host.id;
            // 升级后的插件会在 authenticate 里带平台；老插件不带，库里的空串就一直是空。
            // 只在成功认证之后补写，避免任何未授权连接改到主机档案。
            if(f.platform)this.store.notePlatform(hostId,f.platform);
            // A stale worker generation must lose, not win. Swapping the plugin package
            // SIGTERMs the old child and SIGKILLs it after a timeout; if it is wedged in
            // its own event loop the kill is late and it reconnects *after* the new one.
            // Without this comparison the relay just takes the newest connection and the
            // phone ends up talking to the dead generation (old code, old outbox file).
            const incoming=f.workerEpoch??0;
            const present=this.peers.get(hostId);
            if(present&&incoming<present.workerEpoch){ws.close(4423,'stale-epoch');return;}
            const previous=present;if(previous){this.peers.delete(hostId);void previous.tunnel.stop(4001);}
            peer={tunnel,accountId:host.account_id,epoch:uuid(),workerEpoch:incoming,limits:defaultNegotiation,admission:new Admission(this.reservations,this.capacity.largeThreshold,this.capacity.smallBudget,this.capacity.streams,this.capacity.wsHostBudget),next:1,ready:false};
            this.peers.set(hostId,peer);stage='hello';releaseAuth();
            void tunnel.control({v:3,type:'auth-ok',hostId,tunnelEpoch:peer.epoch,policy:{requireTotp:this.requireTotp}}).catch(()=>{});
          }catch{ws.close(4401,'credential-rejected');}return;
        }
        if(stage==='hello'){
          // Subset, not equality: a newer plugin may announce an extra capability this
          // relay has never heard of; what matters is that the ones this transport
          // implements are all present.
          if(f.type!=='hello'||!capabilities.every(c=>f.capabilities.includes(c))){ws.close(4422,'capabilities');return;}
          peer!.limits={fragment:Math.min(f.limits.fragment,LIMITS.fragment),window:Math.min(f.limits.window,LIMITS.window),maxBody:Math.min(f.limits.maxBody,this.capacity.maxBody),maxWsMessage:Math.min(f.limits.maxWsMessage,LIMITS.wsMessage),streams:Math.min(f.limits.streams,this.capacity.streams)};
          // This transport currently implements the fixed V4 fragment/window contract.
          if(peer!.limits.fragment!==LIMITS.fragment||peer!.limits.window!==LIMITS.window){ws.close(4422,'unsupported-window');return;}
          clearTimeout(timeout);peer!.ready=true;stage='ready';tunnel.startHeartbeat();
          void tunnel.control({v:3,type:'hello-ack',capabilities:['flow','drain','ws-message'],limits:peer!.limits}).catch(()=>{});return;
        }
        if(f.type==='status')return;
        if(f.type==='drain'){peer!.ready=false;const timer=setTimeout(()=>void tunnel.stop(4000),30000);timer.unref();return;}
        ws.close(4409,'unexpected-control');
      });
      tunnel.once('close',()=>{clearTimeout(timeout);releaseAuth();if(peer&&this.peers.get(hostId)===peer)this.peers.delete(hostId);});
    });
  }
  private peer(hostId:string){const p=this.peers.get(hostId);if(!p?.ready||p.tunnel.draining)throw new HttpError(503,'host-offline');return p;}
  /**
   * 一台主机此刻是否算「在线」——手机端绿点的唯一事实源。
   *
   * 判据是「这条隧道最近真的说过话」，不是「WebSocket 还开着」：电脑关机 /
   * 合盖 / 拔网线时 TCP 往往半开，`close` 要几十秒才来、甚至永远不来，只看
   * `ready` 会让手机一直显示在线。心跳 30s 一次，活着的隧道 `lastSeen` 最多
   * 每 30s 刷新一次，所以取 {@link PEER_LIVENESS_MS}（45s）既不会误判健康
   * 主机，又能在掉线 45s 内翻成离线。
   */
  online(hostId:string,now=Date.now()){
    const p=this.peers.get(hostId);
    if(!p?.ready||p.tunnel.draining)return false;
    return now-p.tunnel.lastSeen<PEER_LIVENESS_MS;
  }
  /**
   * 顺手探一次活：`online` 只查表、不等网络，先发一帧 ping 能让下一次查询反映真相。
   *
   * 刚说过话的隧道不必再问（{@link PEER_PROBE_MS}），免得手机端每次拉列表都白跑一帧。
   */
  probe(hostId:string,now=Date.now()){
    const p=this.peers.get(hostId);
    if(!p?.ready||p.tunnel.draining)return;
    if(now-p.tunnel.lastSeen<PEER_PROBE_MS)return;
    p.tunnel.probe();
  }
  private next(p:Peer){if(p.next>0x7fffffff){p.ready=false;void p.tunnel.control({v:3,type:'drain',lastStreamId:0x7fffffff,reason:'id-exhausted'}).catch(()=>{});throw new HttpError(503,'draining');}const n=p.next;p.next+=2;return n;}
  private async acquire(p:Peer,hostId:string,req:IncomingMessage,bytes:number,wsBytes=0):Promise<()=>void>{
    const get=()=>{const releases:Array<()=>void>=[];try{releases.push(this.streams.take(1));if(wsBytes)releases.push(this.wsNode.take(wsBytes));releases.push(p.admission.acquire(bytes,wsBytes));return ()=>releases.reverse().forEach(f=>f());}catch(e){releases.reverse().forEach(f=>f());throw e;}};
    try{return get();}catch{}
    if(wsBytes)throw new HttpError(503,'capacity');const b=this.pendingHosts.get(hostId)??new Budget(16,'host-pending');this.pendingHosts.set(hostId,b);
    const releases:Array<()=>void>=[];try{releases.push(this.pending.take(1),b.take(1));}catch{releases.forEach(f=>f());throw new HttpError(503,'pending-capacity');}
    req.pause();const start=Date.now();try{while(Date.now()-start<LIMITS.pendingWait){if(req.destroyed||this.peers.get(hostId)!==p||!p.ready)throw new HttpError(503,'host-offline');try{return get();}catch{}await new Promise<void>(resolve=>{const t=setTimeout(resolve,25);t.unref();});}throw new HttpError(503,'capacity-timeout');}finally{releases.forEach(f=>f());}
  }
  async http(req:IncomingMessage,res:ServerResponse,hostId:string,s:Row,native=false){
    const method=req.method??'GET',rawPath=safePath(req.url??'/');if(!native)checkOrigin(method,rawPath,req.headers,this.hostOrigin?this.hostOrigin(hostId,req):`https://${hostId}.${new URL(this.baseOrigin).host}`,this.baseOrigin,false,this.sameHostOrigin);
    const info=bodyInfo(method,req.headers),p=this.peer(hostId),bytes=info.lengthMode==='unknown'?p.limits.maxBody:info.declaredLength!;
    if(bytes>p.limits.maxBody)throw new HttpError(413,'body-limit');
    req.pause();const release=await this.acquire(p,hostId,req,bytes);let c:Channel;
    try{this.store.sessionTokenCheck(s,hostId);c=p.tunnel.channel(this.next(p));}catch(e){release();throw e;}
    const identity=this.store.identity(s,hostId);let done=false,responseStarted=false,hostClosed=false,wrote=0;
    // A host whose TCP path lives but whose plugin stopped answering would otherwise pin
    // its admission reservation (streams, large-body budget) until the client gives up —
    // nginx would happily keep the socket open for an hour. Three clocks: first response
    // byte, per-chunk idle during the body, and a hard total for very slow but steady
    // transfers. All three fail the channel, which releases the reservation.
    const clearTimers=()=>{clearTimeout(firstResponse);clearTimeout(idle);clearTimeout(total);};
    const firstResponse=setTimeout(()=>fail(504,'upstream-timeout'),60000);firstResponse.unref();
    let idle:NodeJS.Timeout|undefined;
    const armIdle=()=>{clearTimeout(idle);idle=setTimeout(()=>fail(504,'idle-timeout'),120000);idle.unref();};
    armIdle();
    const total=setTimeout(()=>fail(504,'total-timeout'),900000);total.unref();
    // Opt-in timing trace. The truncation fault this guards against only shows up on a real
    // WAN with a fast upstream, so being able to read the event order from the journal is
    // the difference between a fix and a guess. One env lookup per event when disabled.
    const trace=(step:string,extra='')=>{if(process.env.MOBILE_SH_TRACE)process.stderr.write(`[http ${rawPath} ${String(bytes)}B] ${step} ${extra} written=${String(res.writableLength)} finished=${String(res.writableFinished)}\n`);};
    const active:Active={identity,cancel:()=>{trace('revoked');c.cancel('revoked');if(!res.headersSent){res.writeHead(401);res.end('session-revoked');}else res.destroy();finish();}};
    const finish=()=>{if(done)return;done=true;trace('finish');clearTimers();this.active.delete(active);release();c.dispose();};this.active.add(active);
    const fail=(status:number,reason:string)=>{if(done)return;trace('fail',reason);void c.send({v:3,type:'cancel',reason}).catch(()=>{});if(!res.headersSent){res.writeHead(status,{'cache-control':'no-store'});res.end(reason);}else res.destroy();finish();};
    c.on('control',(f:Control)=>{
      if(f.type==='stream-ready'){
        void pump(req,c,p.limits.maxBody,info.lengthMode==='exact'?info.declaredLength:undefined).catch(()=>fail(400,'request-interrupted'));
      }else if(f.type==='http-response'){
        if(responseStarted){fail(502,'duplicate-response');return;}responseStarted=true;clearTimeout(firstResponse);
        try{const pairs=sanitizeHeaders(f.headers,'response');res.writeHead(f.status,headerObject(pairs));c.startReceive(b=>{wrote+=b.length;armIdle();return writeTo(res,b);});}catch{fail(502,'response-headers');}
      }else if(f.type==='stream-reject')fail(f.status,f.reason);
    });
    c.on('end',()=>{trace('channel-end',`started=${String(responseStarted)} hostClosed=${String(hostClosed)}`);if(!responseStarted){fail(502,'missing-response');return;}res.end();if(hostClosed&&res.writableFinished)finish();});
    c.on('cancel',(f:Control)=>{trace('channel-cancel',f.type==='stream-closed'?'stream-closed':('reason='+(f as {reason?:string}).reason));if(f.type==='stream-closed'){hostClosed=true;if(res.writableFinished)finish();}else if(hostClosed||c.ended){return;}else fail(502,'upstream-cancelled');});
    c.once('disposed',()=>{trace('channel-disposed');if(!done)fail(503,'tunnel-disconnected');});
    res.once('finish',()=>{trace('res-finish');if(hostClosed)finish();});res.once('close',()=>{trace('res-close',`finished=${String(res.writableFinished)}`);trace('bytes',`wrote=${String(wrote)}`);if(!res.writableFinished)fail(499,'client-disconnected');});
    try{await c.send({v:3,type:'http-open',identity,method,rawPath,headers:sanitizeHeaders(rawPairs(req.rawHeaders),'request'),...info});}catch{fail(431,'request-headers');}
  }
  async upgrade(req:IncomingMessage,socket:Duplex,head:Buffer,hostId:string,s:Row,native=false){
    const rawPath=safePath(req.url??'/');if(!native)checkOrigin('GET',rawPath,req.headers,this.hostOrigin?this.hostOrigin(hostId,req):`https://${hostId}.${new URL(this.baseOrigin).host}`,this.baseOrigin,true,this.sameHostOrigin);
    const p=this.peer(hostId),release=await this.acquire(p,hostId,req,0,p.limits.maxWsMessage*5);let c:Channel;
    try{this.store.sessionTokenCheck(s,hostId);c=p.tunnel.channel(this.next(p));}catch(e){release();throw e;}
    const identity=this.store.identity(s,hostId);let done=false,ws:WebSocket|undefined,rejected=false;
    const finish=()=>{if(done)return;done=true;this.active.delete(active);release();c.dispose();};
    const active:Active={identity,cancel:()=>{c.cancel('revoked');if(ws)ws.close(4401,'revoked');else socket.destroy();finish();}};this.active.add(active);
    const timeout=setTimeout(()=>{socket.destroy();c.cancel('upgrade-timeout');finish();},15000);timeout.unref();
    c.on('control',(f:Control)=>{
      if(f.type==='ws-accept'){
        clearTimeout(timeout);try{
          this.store.sessionTokenCheck(s,hostId);
          const requested=(req.headers['sec-websocket-protocol']??'').split(',').map(v=>v.trim()).filter(Boolean);
          if(f.protocol&&!requested.includes(f.protocol))throw new Error('protocol');
          // A per-upgrade server avoids global mutable protocol selection.
          const server=new WebSocketServer({noServer:true,perMessageDeflate:false,maxPayload:p.limits.maxWsMessage,handleProtocols:()=>f.protocol||false});
          server.handleUpgrade(req,socket,head,value=>{ws=value;new MessageBridge(ws,c,p.limits.maxWsMessage,finish);ws.once('close',()=>server.close());});
        }catch{socket.destroy();c.cancel('upgrade-rejected');finish();}
      }else if(f.type==='ws-reject'||f.type==='stream-reject'){
        clearTimeout(timeout);rejected=true;const status=f.status;socket.write(`HTTP/1.1 ${status} ${http.STATUS_CODES[status]??'Rejected'}\r\nConnection: close\r\n\r\n`);
        c.startReceive(b=>writeTo(socket,b));if(f.type==='stream-reject'){socket.end();finish();}
      }
    });
    c.on('end',()=>{if(rejected){socket.end();finish();}});
    c.on('cancel',(f:Control)=>{if(f.type==='stream-closed'&&rejected)return;ws?.terminate();socket.destroy();finish();});
    c.once('disposed',()=>{clearTimeout(timeout);if(!done){ws?.terminate();socket.destroy();finish();}});
    socket.once('close',()=>{if(!done){c.cancel('client-disconnected');finish();}});
    await c.send({v:3,type:'ws-open',identity,rawPath,headers:sanitizeHeaders(rawPairs(req.rawHeaders),'request'),protocols:(req.headers['sec-websocket-protocol']??'').split(',').map(s=>s.trim()).filter(Boolean)});
  }
  async close(){this.store.off('revoked',this.onRevoke);for(const a of [...this.active])a.cancel();await Promise.all([...this.peers.values()].map(p=>p.tunnel.stop(4001)));this.wsServer.close();this.tunnelServer.close();}
}
export function rejectSocket(socket:Duplex,status:number){if(!socket.destroyed)socket.end(`HTTP/1.1 ${status} ${http.STATUS_CODES[status]??'Rejected'}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);}
