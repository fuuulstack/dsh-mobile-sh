import { z } from 'zod';

export const MiB = 1024 * 1024;
export const LIMITS = Object.freeze({
  fragment: 32768, window: 262144, control: 16384, headers: 12288,
  // Calibrated for the 768 MiB production box. The node reservation used to claim 1 GiB —
  // more than the machine has, which is the same as having no ceiling at all: the kernel
  // would swap-thrash long before the budget refused anything. A single WS message is
  // capped at 4 MiB because snapshots already travel as 24 KiB chunk frames; 16 MiB
  // singles only ever appeared as accidental tool dumps.
  httpQueue: 32 * MiB, sendQueue: 8 * MiB, controlQueue: 262144,
  maxBody: 300 * MiB, smallBudget: 64 * MiB, largeThreshold: 16 * MiB,
  // 768MiB 机器校准（评审修复）：单条 WS 消息 4MiB（快照已走 24KiB 分帧，16MiB 大单条
  // 只会出现在意外的大工具转储）；wsStreams 8 给「宿主受限预取」路线留够并发余量，
  // 但每条预留 5×4MiB=20MiB，8 条封顶 160MiB，仍受 nodeReservation 384MiB 总闸约束。
  nodeReservation: 384 * MiB, streams: 64, pending: 16, pendingWait: 60000,
  wsMessage: 4 * MiB, wsStreams: 8, wsHostBudget: 192 * MiB, wsNodeBudget: 256 * MiB,
  /** A phone that is killed, backgrounded past the OS limit or dropped off the
   *  network never sends a close frame, so the socket stays half-open until TCP
   *  gives up minutes later. Probe dead peers instead of waiting for that. */
  wsPingInterval: 15000, wsMissedPongs: 2, wsCloseGrace: 2000,
  /**
   * 控制帧（流 0）单独放行的 socket 缓冲上限，比数据流那 1MiB 门限宽一档。
   *
   * 为什么不能一视同仁：控制流上跑的是脉搏，那是「连接还活着」的唯一证据。
   * 大块 records 把 socket 缓冲顶过 1MiB 之后，若控制帧也跟着停，对端看到的就是
   * 「records 到不了、脉搏也没有」的假死——手机据此判死重连，插件再重放一遍尾部
   * 窗口，越堵越重（坑 54 的放大器之一；现场 `tunnel credit stall pending=11KiB
   * buffered=0KiB` 正是这一条）。控制帧本身 ≤16KiB 且有 controlQueue 兜底，多放
   * 一档不会撑爆内存；真堵到 4MiB 说明链路已经废了，那时一起等才对。
   */
  controlHighWater: 4 * MiB,
});
export const capabilities = ['flow', 'drain', 'ws-message'] as const;
const id = z.number().int().min(1).max(0x7fffffff).refine(n => n % 2 === 1);
const host = z.string().regex(/^[a-f0-9]{32}$/);
const headers = z.array(z.tuple([z.string().max(256), z.string().max(LIMITS.headers)]))
  .max(128).refine(h => Buffer.byteLength(JSON.stringify(h)) <= LIMITS.headers);
export type HeaderPairs = z.infer<typeof headers>;
export const identitySchema = z.strictObject({ accountId: z.string().max(64), deviceId: z.string().max(64),
  hostId: host, sessionId: z.string().max(128), grantVersion: z.number().int().positive(), traceId: z.string().max(64) });
export type Identity = z.infer<typeof identitySchema>;
const base = { v: z.literal(3) };
const stream = { ...base, streamId: id };
const negotiation = z.strictObject({ fragment: z.number().int().min(1024).max(LIMITS.fragment),
  window: z.number().int().min(1024).max(LIMITS.window), maxBody: z.number().int().min(1).max(LIMITS.maxBody),
  maxWsMessage: z.number().int().min(1).max(LIMITS.wsMessage), streams: z.number().int().min(1).max(64) });
export type Negotiation = z.infer<typeof negotiation>;
export const defaultNegotiation: Negotiation = {fragment: LIMITS.fragment, window: LIMITS.window,
  maxBody: LIMITS.maxBody, maxWsMessage: LIMITS.wsMessage, streams: LIMITS.streams};
export const controlSchema = z.discriminatedUnion('type', [
  // platform 可选：老插件不发，新插件带一次就够了（服务端只在库里为空时补写）。
  // 放在 authenticate 而不是 hello，是因为它属于「这台主机是什么」的身份信息，
  // 和凭据一起到达才不会被无凭据的连接改写。
  // workerEpoch 可选：老插件不发。它的用途是让中继拒绝「上一代还没死透的 worker」——
  // 换插件包时旧子进程可能卡在自己的事件循环里，SIGKILL 兜底之前它会重连并把新 worker
  // 挤下线，而中继只看「最后连上来的那个」，分不出谁更新。
  z.strictObject({...base, type:z.literal('authenticate'), hostId:host, credential:z.string().regex(/^[A-Za-z0-9_-]{43}$/), platform:z.string().max(32).optional(), workerEpoch:z.number().int().nonnegative().max(2**31).optional()}),
  z.strictObject({...base, type:z.literal('auth-ok'), hostId:host, tunnelEpoch:z.string().max(64), policy:z.strictObject({requireTotp:z.boolean()})}),
  ...(['hello','hello-ack'] as const).map(type => z.strictObject({...base,type:z.literal(type),
    // Free-form strings, not the fixed enum: a peer may announce a capability this
    // build has never heard of, and the subset check on each side decides whether the
    // ones it needs are present. The old `.max(3)` on the enum meant every capability
    // addition was a three-component lockstep release with a hard-fail window.
    capabilities:z.array(z.string().min(1).max(32)).max(16),limits:negotiation})),
  z.strictObject({...stream,type:z.literal('http-open'),identity:identitySchema, method:z.string().regex(/^[A-Z]+$/).max(16),rawPath:z.string().max(8192),headers,
    bodyKind:z.enum(['none','bytes']), lengthMode:z.enum(['exact','unknown']), declaredLength:z.number().int().nonnegative().max(LIMITS.maxBody).optional()}),
  z.strictObject({...stream,type:z.literal('ws-open'),identity:identitySchema,rawPath:z.string().max(8192),headers,protocols:z.array(z.string().max(256)).max(16)}),
  z.strictObject({...stream,type:z.literal('stream-ready')}),
  z.strictObject({...stream,type:z.literal('stream-reject'),status:z.number().int().min(400).max(599),reason:z.string().max(128)}),
  z.strictObject({...stream,type:z.literal('http-response'),status:z.number().int().min(200).max(599),headers}),
  z.strictObject({...stream,type:z.literal('ws-accept'),protocol:z.string().max(256)}),
  z.strictObject({...stream,type:z.literal('ws-reject'),status:z.number().int().min(300).max(599),headers}),
  z.strictObject({...stream,type:z.literal('flow'),delta:z.number().int().positive().max(LIMITS.window)}),
  z.strictObject({...stream,type:z.literal('ws-message'),messageId:z.number().int().positive().max(Number.MAX_SAFE_INTEGER),opcode:z.enum(['text','binary']),byteLength:z.number().int().nonnegative().max(LIMITS.wsMessage)}),
  z.strictObject({...stream,type:z.literal('ws-message-ready'),messageId:z.number().int().positive(),phase:z.enum(['begin','delivered'])}),
  z.strictObject({...stream,type:z.literal('ws-close'),code:z.number().int().min(1000).max(4999),reason:z.string().refine(s=>Buffer.byteLength(s)<=123)}),
  ...(['cancel','stream-closed'] as const).map(type=>z.strictObject({...stream,type:z.literal(type),reason:z.string().max(128)})),
  z.strictObject({...base,type:z.literal('drain'),lastStreamId:z.number().int().min(0).max(0x7fffffff),reason:z.string().max(128)}),
  ...(['ping','pong'] as const).map(type=>z.strictObject({...base,type:z.literal(type),seq:z.number().int().nonnegative(),t:z.number().nonnegative()})),
  z.strictObject({...base,type:z.literal('status'),state:z.enum(['disabled','unpaired','starting','connecting','ready','degraded','revoked','stopping']),cookieReady:z.boolean(),storageReady:z.boolean(),version:z.string().max(64)}),
]);
export type Control = z.infer<typeof controlSchema>;
export type Open = Extract<Control,{type:'http-open'|'ws-open'}>;
export function parseControl(data:Buffer):Control {
  if(data.length>LIMITS.control) throw new Error('control-too-large');
  return controlSchema.parse(JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(data)));
}
export function encodeControl(frame:Control):string {
  controlSchema.parse(frame);const s=JSON.stringify(frame);
  if(Buffer.byteLength(s)>LIMITS.control) throw new Error('control-too-large');return s;
}
export enum FrameType { DATA=1, END=2, RESET=3 }
export function encodeFrame(type:FrameType,streamId:number,payload:Buffer=Buffer.alloc(0)):Buffer {
  id.parse(streamId);if(payload.length>LIMITS.fragment||(type!==FrameType.DATA&&payload.length))throw new Error('invalid-frame');
  const b=Buffer.allocUnsafe(12+payload.length);b[0]=3;b[1]=type;b.writeUInt16BE(0,2);b.writeUInt32BE(streamId,4);b.writeUInt32BE(payload.length,8);payload.copy(b,12);return b;
}
export function decodeFrame(b:Buffer):{type:FrameType;streamId:number;payload:Buffer} {
  if(b.length<12||b[0]!==3||b.readUInt16BE(2)!==0)throw new Error('invalid-frame');
  const type=b[1]!,streamId=b.readUInt32BE(4),n=b.readUInt32BE(8);id.parse(streamId);
  if(![1,2,3].includes(type)||n!==b.length-12||n>LIMITS.fragment||(type!==1&&n!==0))throw new Error('invalid-frame');
  return {type,streamId,payload:b.subarray(12)};
}
