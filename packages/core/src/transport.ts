import { EventEmitter } from 'node:events';
import WebSocket from 'ws';
import { Budget } from './budget.js';
import { decodeFrame, encodeControl, encodeFrame, FrameType, LIMITS, parseControl, type Control } from './protocol.js';

type Job={data:Buffer|string;resolve:()=>void;reject:(e:Error)=>void;size:number};
type WithoutStream<T> = T extends {streamId:number} ? Omit<T,'streamId'> : never;
/**
 * 一次「等对端信用」超过阈值的事件（见 {@link Tunnel.stallMs}）。
 *
 * 这是诊断「隧道看着正常但一个字节都推不动」的关键证据：流控信用耗尽时发送端
 * 完全静默，`pump()` 里没有待发作业，于是既没有 DATA 帧也没有错误——只有这条
 * 事件能把它和「本来就没数据」区分开。
 */
export type CreditStall={streamId:number;waitedMs:number;pending:number;buffered:number};
export class Tunnel extends EventEmitter {
  readonly channels=new Map<number,Channel>();
  private readonly closedStreams=new Set<number>();
  readonly receiveBudget:Budget;
  readonly sendBudget=new Budget(LIMITS.sendQueue,'send-queue');
  readonly controlBudget=new Budget(LIMITS.controlQueue,'control-queue');
  private queues=new Map<number,Job[]>();
  private active=false;
  private closed=false;
  private heartbeat?:NodeJS.Timeout;
  private seq=0; private lastPong=0;
  /**
   * 最后一次收到对端任何帧的时刻（ms，含 ping/pong/控制帧/二进制帧）。
   *
   * 在线判定读它，而不是「WebSocket 还开着没」：电脑关机、合盖、拔网线之后 TCP
   * 半开，`close` 事件可能几十秒后才来、甚至永远不来（NAT 侧静默丢弃）。那时
   * `ready` 一直是 true，手机端就永远显示「在线」。
   */
  lastSeen=Date.now();
  draining=false;
  /**
   * 自上次 {@link takeBufferedPeak} 以来 `socket.bufferedAmount` 见到的最高水位（字节）。
   *
   * 应用层发送缓冲是「还有没有帧出去」最直接的证据：它顶到 1MB 时 {@link pump} 会主动
   * 暂停发送，此后隧道上**一帧都不会新增**，可对端 WebSocket 栈照样回 pong——手机端
   * 看着连接一切正常，业务却静默。峰值单独拎出来的意义：它涨过 1MB，就说明无帧是
   * 发送端吸不动（手机侧接收慢 / 上行不足 / 对端处理不过来），而不是本来没东西可发。
   */
  private peakBuffered=0;
  constructor(readonly socket:WebSocket,receiveBudget=new Budget(LIMITS.httpQueue,'receive'),readonly window=LIMITS.window,readonly fragment=LIMITS.fragment,
    /**
     * 等对端 `flow` 信用超过这么久就 emit 一次 `stall`，默认 10s。
     *
     * 只用于观测，不改变行为：真正的超时仍由 `wait('credit')` 自己的 120s 兜底。
     * 阈值取 10s 是因为正常窗口（256KiB）在任何一个能用的链路上都远用不到这么久。
     */
    readonly stallMs=10_000){
    super();this.receiveBudget=receiveBudget;
    socket.on('message',(data,binary)=>{
      try{
        this.lastSeen=Date.now();
        const bytes=Buffer.isBuffer(data)?data:Buffer.concat(Array.isArray(data)?data:[Buffer.from(data)]);
        if(binary){const f=decodeFrame(bytes),c=this.channels.get(f.streamId);if(c)c.receive(f.type,f.payload);else if(!this.closedStreams.has(f.streamId))this.unknown(f.streamId);}
        else {const f=parseControl(bytes);
          if(f.type==='ping'){void this.control({v:3,type:'pong',seq:f.seq,t:f.t}).catch(()=>{});return;}
          if(f.type==='pong'){if(f.seq>this.lastPong&&f.seq<=this.seq)this.lastPong=f.seq;return;}
          if(f.type==='drain')this.draining=true;
          if('streamId'in f){const c=this.channels.get(f.streamId);if(c){c.control(f);return;}if(f.type!=='http-open'&&f.type!=='ws-open'){if(f.type!=='cancel'&&f.type!=='stream-closed'&&f.type!=='flow'&&!this.closedStreams.has(f.streamId))this.unknown(f.streamId);return;}}
          this.emit('control',f);
        }
      }catch{this.socket.close(4409,'protocol-error');}
    });
    socket.on('error',()=>{});
    socket.once('close',()=>this.shutdown());
  }
  startHeartbeat(){this.heartbeat=setInterval(()=>{if(this.seq-this.lastPong>=2){this.socket.close(4408,'heartbeat');return;}void this.control({v:3,type:'ping',seq:++this.seq,t:Date.now()}).catch(()=>{});},30000);this.heartbeat.unref();}
  /**
   * 立刻探一次活（不等 30s 心跳）：对端收到 ping 会当场回 pong，`lastSeen` 随之刷新。
   *
   * 手机拉主机列表时用它把「刚刚是不是还活着」问出来，代价只是一帧控制消息；
   * 对端已经死了的话这帧石沉大海，`lastSeen` 继续变旧，在线判定自然转成离线。
   */
  probe(){if(this.closed||this.socket.readyState!==WebSocket.OPEN)return;void this.control({v:3,type:'ping',seq:++this.seq,t:Date.now()}).catch(()=>{});}
  recordClosed(id:number){this.closedStreams.add(id);if(this.closedStreams.size>1024){const oldest=this.closedStreams.keys().next().value;if(oldest!==undefined)this.closedStreams.delete(oldest);}}
  /**
   * 取走缓冲峰值并清零（按上报窗口读，与 `HostMetrics.snapshot()` 的语义一致）。
   *
   * 不清零的话峰值会被历史顶住：一次 30 秒的带宽打满之后，之后每个窗口都报同一个
   * 数字，「现在到底还堵不堵」就再也看不出来了。
   */
  takeBufferedPeak(){const peak=this.peakBuffered;this.peakBuffered=0;return peak;}
  /** 尚未发出（还在应用层队列里）的字节数：信用停顿时它是「积压了多少」的直接读数。 */
  queuedBytes(){let total=0;for(const q of this.queues.values())for(const job of q)total+=job.size;return total;}
  private unknown(streamId:number){void this.control({v:3,type:'cancel',streamId,reason:'unknown-stream'}).catch(()=>{});}
  channel(id:number){if(this.closed||this.channels.has(id))throw new Error('duplicate-or-closed');const c=new Channel(this,id);this.channels.set(id,c);return c;}
  control(frame:Control){return this.enqueue('streamId'in frame?frame.streamId:0,encodeControl(frame),true);}
  binary(id:number,type:FrameType,payload?:Buffer){return this.enqueue(id,encodeFrame(type,id,payload),false);}
  private enqueue(id:number,data:Buffer|string,control:boolean):Promise<void>{
    if(this.closed||this.socket.readyState!==WebSocket.OPEN)return Promise.reject(new Error('tunnel-closed'));
    const size=Buffer.byteLength(data);let release:()=>void;
    try{release=(control?this.controlBudget:this.sendBudget).take(size);}catch(e){this.socket.close(4409,'queue-limit');return Promise.reject(e);}
    return new Promise<void>((resolve,reject)=>{
      const q=this.queues.get(id)??[];q.push({data,size,resolve:()=>{release();resolve();},reject:e=>{release();reject(e);}});this.queues.set(id,q);this.pump();
    });
  }
  private pump(){
    if(this.active||this.closed)return;this.active=true;
    const run=()=>{
      if(this.closed){this.active=false;return;}
      const buffered=this.socket.bufferedAmount??0;
      if(buffered>this.peakBuffered)this.peakBuffered=buffered;
      // 控制帧（流 0）单独放行到 controlHighWater。数据流顶满 1MiB 时如果连脉搏
      // 一起停，对端看到的是「既没有 records 也没有脉搏」的假死：手机会判死重连，
      // 插件再重放一遍尾部窗口，越堵越重。控制帧 ≤16KiB 且有 controlQueue 兜底，
      // 多放一档不会撑爆内存；真到 4MiB 说明链路已经废了，那时一起等才对。
      const controlWaiting=this.queues.has(0);
      const limit=controlWaiting?LIMITS.controlHighWater:1024*1024;
      if(buffered>=limit){setTimeout(run,5).unref();return;}
      // Map insertion order implements one fragment per stream per round. Control stream 0 wins.
      const key=controlWaiting?0:this.queues.keys().next().value as number|undefined;
      if(key===undefined){this.active=false;return;}
      const q=this.queues.get(key)!,job=q.shift()!;this.queues.delete(key);if(q.length)this.queues.set(key,q);
      this.socket.send(job.data,{binary:typeof job.data!=='string'},err=>{if(err)job.reject(err);else job.resolve();setImmediate(run);});
    };run();
  }
  async stop(code=1000){if(this.closed)return;this.socket.close(code);await new Promise<void>(resolve=>{
    const timer=setTimeout(()=>{this.socket.terminate();resolve();},2000);timer.unref();this.socket.once('close',()=>{clearTimeout(timer);resolve();});
  });}
  private shutdown(){if(this.closed)return;this.closed=true;clearInterval(this.heartbeat);for(const q of this.queues.values())for(const j of q)j.reject(new Error('tunnel-closed'));this.queues.clear();for(const c of this.channels.values())c.dispose();this.emit('close');}
}

export class Channel extends EventEmitter {
  private tx=0;private rx=0;private release?:()=>void;private receiveTask=Promise.resolve();
  private handler?:(b:Buffer)=>Promise<void>;
  private bounded?:number;private granted=0;private consumed=0;private flowTimer?:NodeJS.Timeout;
  private _ended=false;closed=false;
  get ended(){return this._ended;}
  constructor(readonly tunnel:Tunnel,readonly id:number){super();}
  send(frame:WithoutStream<Control>){return this.tunnel.control({...frame,streamId:this.id} as Control);}
  control(frame:Control){
    if(frame.type==='flow'){
      if(this.tx+frame.delta>this.tunnel.window)throw new Error('credit-overflow');this.tx+=frame.delta;this.emit('credit');
    }else if(frame.type==='cancel'||frame.type==='stream-closed'){this.emit('cancel',frame);}
    else this.emit('control',frame);
  }
  startReceive(handler:(b:Buffer)=>Promise<void>,length?:number){
    if(this.closed||this.rx!==0)throw new Error('receive-state');
    if(!this.release)this.release=this.tunnel.receiveBudget.take(this.tunnel.window);
    this.handler=handler;this.bounded=length;this.granted=0;this.consumed=0;this._ended=false;
    this.grant(Math.min(this.tunnel.window,length??this.tunnel.window));
  }
  private grant(n:number){if(!n||this.closed)return;this.rx+=n;this.granted+=n;void this.send({v:3,type:'flow',delta:n}).catch(()=>this.dispose());}
  private flush(){clearTimeout(this.flowTimer);this.flowTimer=undefined;if(this.closed||this._ended)return;
    const n=Math.min(this.consumed,this.bounded===undefined?this.consumed:this.bounded-this.granted);this.consumed-=n;this.grant(n);
  }
  receive(type:FrameType,payload:Buffer){
    if(this.closed)return;
    if(type===FrameType.RESET){this.emit('cancel',{reason:'reset'});return;}
    if(type===FrameType.END){if(this._ended)throw new Error('duplicate-end');this._ended=true;this.receiveTask=this.receiveTask.then(()=>{if(!this.closed)this.emit('end');});return;}
    if(this._ended||!this.handler||payload.length>this.rx)throw new Error('flow-violation');
    this.rx-=payload.length;const handler=this.handler;
    this.receiveTask=this.receiveTask.then(async()=>{
      if(this.closed)return;await handler(payload);this.consumed+=payload.length;
      if(this.consumed>=this.tunnel.window/2||this.rx===0)this.flush();
      else if(!this.flowTimer){this.flowTimer=setTimeout(()=>this.flush(),10);this.flowTimer.unref();}
    }).catch(()=>{this.emit('cancel',{reason:'downstream-write'});});
  }
  async data(bytes:Buffer){for(let offset=0;offset<bytes.length;){
    if(!this.tx&&!this.closed){
      // 信用空 → 一帧都发不出去，而且**不会报错**，只能干等对端回 flow。
      // 所以超时那一刻就 emit，**不等它恢复**：现场最常见的情形恰恰是永远不恢复，
      // 那时候唯一的证据就是「还卡着」这一条；等它恢复了才记录等于什么都没留下。
      const stallingFrom=Date.now();
      const probe=setTimeout(()=>{
        if(this.closed)return;
        const event:CreditStall={streamId:this.id,waitedMs:Date.now()-stallingFrom,pending:bytes.length-offset,buffered:this.tunnel.socket.bufferedAmount??0};
        this.tunnel.emit('stall',event);
      },this.tunnel.stallMs);
      probe.unref();
      try{while(!this.tx&&!this.closed)await this.wait('credit');}finally{clearTimeout(probe);}
    }
    if(this.closed)throw new Error('channel-closed');const n=Math.min(bytes.length-offset,this.tx,this.tunnel.fragment);this.tx-=n;
    await this.tunnel.binary(this.id,FrameType.DATA,bytes.subarray(offset,offset+n));offset+=n;
  }}
  end(){return this.tunnel.binary(this.id,FrameType.END);}
  wait(event:string,timeout=120000):Promise<void>{return new Promise((resolve,reject)=>{
    const cleanup=()=>{clearTimeout(timer);this.off(event,done);this.off('disposed',abort);};
    const done=()=>{cleanup();resolve();},abort=()=>{cleanup();reject(new Error('channel-closed'));};
    const timer=setTimeout(()=>{cleanup();reject(new Error('channel-timeout'));},timeout);timer.unref();this.once(event,done);this.once('disposed',abort);if(this.closed)abort();
  });}
  cancel(reason='cancelled'){if(this.closed)return;void this.send({v:3,type:'cancel',reason}).catch(()=>{});this.emit('cancel',{reason});}
  dispose(){if(this.closed)return;this.closed=true;clearTimeout(this.flowTimer);this.tunnel.recordClosed(this.id);this.tunnel.channels.delete(this.id);this.emit('disposed');
    // Retain the receive reservation until the last asynchronous writer relinquishes its buffer.
    void this.receiveTask.finally(()=>{this.release?.();this.release=undefined;});}
}
