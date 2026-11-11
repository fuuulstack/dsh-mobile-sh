import type { Readable, Writable } from 'node:stream';
import { isUtf8 } from 'node:buffer';
import WebSocket from 'ws';
import { Channel } from './transport.js';
import type { Control } from './protocol.js';
import { LIMITS } from './protocol.js';

export function writeTo(target:Writable,bytes:Buffer):Promise<void>{
  return new Promise((resolve,reject)=>{
    if(target.destroyed){reject(new Error('writer-closed'));return;}
    const failed=(e?:Error)=>{cleanup();reject(e??new Error('writer-closed'));};
    const cleanup=()=>{target.off('error',failed);target.off('close',failed);};
    target.once('error',failed);target.once('close',failed);
    target.write(bytes,(error?:Error|null)=>{cleanup();if(error)reject(error);else resolve();});
  });
}
export async function pump(source:Readable,channel:Channel,max:number,exact?:number,onProgress?:()=>void,rewrite?:(bytes:Buffer)=>Buffer){
  // A rewrite needs the whole body: JSON is not line framed, so a chunk boundary can fall
  // anywhere. Buffering is bounded by `max`, and only plain JSON responses ask for it.
  let count=0;const held:Buffer[]=[];
  for await(const chunk of source){const b=Buffer.isBuffer(chunk)?chunk:Buffer.from(chunk);count+=b.length;
    if(count>max||(exact!==undefined&&count>exact))throw new Error('body-overrun');onProgress?.();
    if(rewrite)held.push(b);else await channel.data(b);
  }if(exact!==undefined&&count!==exact)throw new Error('body-underrun');
  if(rewrite){const body=rewrite(Buffer.concat(held,count));if(body.length)await channel.data(body);}
  await channel.end();
}
function validClose(code:number){return code>=3000&&code<=4999||[1000,1001,1002,1003,1007,1008,1009,1010,1011,1012,1013,1014].includes(code);}

/** A phone that is killed or loses its network sends no close frame, so without a
 *  probe the relay holds the stream, its reservation and the host's slot until TCP
 *  gives up. Ping the peer and reclaim the stream once it stops answering. */
export function probePeer(socket:WebSocket,onDead:()=>void,interval:number=LIMITS.wsPingInterval,missed:number=LIMITS.wsMissedPongs){
  if(!interval)return ()=>{};
  let strikes=0;
  const timer=setInterval(()=>{
    if(socket.readyState!==WebSocket.OPEN){clearInterval(timer);return;}
    if(strikes>=missed){clearInterval(timer);onDead();return;}
    strikes++;socket.ping();
  },interval);
  timer.unref();
  const seen=()=>{strikes=0;};
  socket.on('pong',seen);
  return ()=>{clearInterval(timer);socket.off('pong',seen);};
}

/** Bounded source queue and one pre-reserved target store per direction. No RPC parsing. */
export class MessageBridge {
  /** Messages the sender may have awaiting their `delivered` ack before it must pause.
   *  Stop-and-wait (window 1) cost two phone RTTs per message — every snapshot chunk
   *  and every stream delta — which on a 500 ms link throttled live streaming to
   *  ~1 msg/s until the 128-message queue cliff aborted the mux stream. Bodies still
   *  serialize on the single channel; only the ack round-trips overlap. The receiver
   *  opens the next message when its header lands (the previous body's final byte has
   *  already returned the channel's flow credit to zero), so both sides of a bridge
   *  pair must understand it: relay and plugin upgrade together, relay first. */
  private static readonly WINDOW=4;
  private nextSend=1;private nextReceive=1;
  private queuedMessages=0;private queuedBytes=0;private stopped=false;private tail=Promise.resolve();
  private pendingAcks:Promise<void>[]=[];
  private probe?:()=>void;
  /** `rewrite` may shrink an outbound message: the frame restates byteLength from the
   *  result, so the peer's pre-reserved store always matches the bytes it receives. */
  constructor(readonly socket:WebSocket,readonly channel:Channel,readonly max:number,readonly onClose:()=>void,
    readonly rewrite?:(bytes:Buffer,binary:boolean)=>Buffer[]){
    socket.on('error',()=>this.abort());
    socket.on('message',(raw,binary)=>{
      if(this.stopped)return;const inbound=Buffer.isBuffer(raw)?raw:Buffer.concat(Array.isArray(raw)?raw:[Buffer.from(raw)]);
      // The byte ceiling spans the pipeline window: W in-flight messages may each be
      // up to `max`, so a single-message ceiling would abort exactly the traffic the
      // window is meant to keep moving.
      if(this.queuedBytes+inbound.length>MessageBridge.WINDOW*this.max||this.queuedMessages>=128){this.abort(1009);return;}
      const parts=this.rewrite?this.rewrite(inbound,binary):[inbound];
      for(const p of parts)if(p.length>max){this.abort(1009);return;}
      // Never pause the source. The upstream pings every couple of seconds and drops
      // a peer that stops answering, so pausing to apply backpressure looks like a
      // dead connection and kills the tunnel mid-message. The byte and message
      // ceilings above bound the queue instead.
      this.queuedBytes+=inbound.length;this.queuedMessages++;
      this.tail=this.tail.then(async()=>{
        // One source message may expand into several phone-visible messages: an
        // oversized snapshot is re-emitted as begin/records/end so the phone can
        // paint it while the rest is still in flight.
        for(const b of parts){
          if(this.stopped)return;
          const id=this.nextSend++;
          // Header, body and the next header travel in order on the channel; the
          // receiver reserves its store when the header lands and its channel's flow
          // credit is back to zero, so data never races the reservation and waiting
          // for the `begin` ack bought nothing.
          void this.channel.send({v:3,type:'ws-message',messageId:id,opcode:binary?'binary':'text',byteLength:b.length}).catch(()=>{});
          if(b.length)await this.channel.data(b);
          // Flow control: pause once WINDOW messages await their delivered ack. The
          // peer sends that ack only after its socket accepted the bytes, so a slow
          // downstream stalls here instead of piling up until the abort ceiling.
          const delivered=this.expect(id,'delivered',b.length);void delivered.catch(()=>{});
          this.pendingAcks.push(delivered);
          if(this.pendingAcks.length>=MessageBridge.WINDOW)await this.pendingAcks.shift()!;
        }
      }).catch(()=>this.abort()).finally(()=>{
        this.queuedBytes-=inbound.length;this.queuedMessages--;
      });
    });
    channel.on('control',(f:Control)=>{try{
      if(f.type==='ws-message')this.openMessage(f);
      if(f.type==='ws-close'){
        if(!validClose(f.code))throw new Error('close-code');socket.close(f.code,f.reason);this.stopped=true;
      }
    }catch{this.abort();}});
    channel.on('end',()=>this.abort());channel.on('cancel',()=>this.abort());
    channel.once('disposed',()=>this.abort());
    socket.once('close',(code,reason)=>{
      this.stopProbe();
      if(!this.stopped)void channel.send({v:3,type:'ws-close',code:validClose(code)?code:1011,reason:reason.length<=123?reason.toString('utf8'):''}).catch(()=>{});
      this.stopped=true;onClose();
    });
    this.startProbe();
  }
  /** A phone that is killed or loses its network sends no close frame, so without a
   *  probe the relay holds the stream, its reservation and the host's attention until
   *  TCP times out. Ping the peer and reclaim the stream once it stops answering. */
  private startProbe(){this.probe=probePeer(this.socket,()=>this.abort(1001));}
  private stopProbe(){if(this.probe){this.probe();this.probe=undefined;}}
  private expect(id:number,phase:'begin'|'delivered',bytes=0):Promise<void>{return new Promise((resolve,reject)=>{
    const cleanup=()=>{clearTimeout(timer);this.channel.off('control',listener);this.channel.off('disposed',failed);};
    const listener=(f:Control)=>{if(f.type==='ws-message-ready'&&f.messageId===id&&f.phase===phase){cleanup();resolve();}};
    const failed=()=>{cleanup();reject(new Error('closed'));};
    // Large bodies legitimately take minutes on a slow link; the timeout must cover
    // the transfer, not just the round trip (20 ms/KB ≈ a 400 Kbps floor).
    const timer=setTimeout(()=>{cleanup();reject(new Error('message-timeout'));},120000+bytes*20);timer.unref();this.channel.on('control',listener);this.channel.once('disposed',failed);
  });}
  private openMessage(f:Extract<Control,{type:'ws-message'}>){
    if(f.messageId!==this.nextReceive++||f.byteLength>this.max)throw new Error('message-state');
    // The previous message's final byte already zeroed the channel's receive credit,
    // so its session is closed even if `deliver` is still flushing to the socket.
    // Each message's receive handler closes over its own buffer; deliveries queue on
    // the socket in message order, which is the order the phone must see.
    const cur={id:f.messageId,bytes:Buffer.allocUnsafe(f.byteLength),offset:0,binary:f.opcode==='binary'};
    this.channel.startReceive(async b=>{
      if(cur.offset+b.length>cur.bytes.length)throw new Error('message-length');
      b.copy(cur.bytes,cur.offset);cur.offset+=b.length;
      if(cur.offset===cur.bytes.length)await this.deliver(cur);
    },f.byteLength);
    void this.channel.send({v:3,type:'ws-message-ready',messageId:f.messageId,phase:'begin'}).then(()=>{if(!f.byteLength)return this.deliver(cur);}).catch(()=>this.abort());
  }
  private async deliver(cur:{id:number;bytes:Buffer;binary:boolean}){
    if(!cur.binary&&!isUtf8(cur.bytes))throw new Error('invalid-utf8');
    await new Promise<void>((resolve,reject)=>this.socket.send(cur.bytes,{binary:cur.binary},e=>e?reject(e):resolve()));
    await this.channel.send({v:3,type:'ws-message-ready',messageId:cur.id,phase:'delivered'});
  }
  abort(code=1011){if(this.stopped&&this.socket.readyState===WebSocket.CLOSED)return;this.stopProbe();this.stopped=true;
    if(this.socket.readyState===WebSocket.OPEN)this.socket.close(code);else if(this.socket.readyState===WebSocket.CONNECTING)this.socket.terminate();
    const timer=setTimeout(()=>{if(this.socket.readyState!==WebSocket.CLOSED)this.socket.terminate();},LIMITS.wsCloseGrace);timer.unref();
  }
}
