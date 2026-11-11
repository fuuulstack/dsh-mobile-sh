import { LIMITS, MiB } from '../../core/src/index.js';

/**
 * 中继侧容量预算：把 `LIMITS` 那套「按 768MiB 机器手调」的常量变成可配。
 *
 * 为什么需要它：那套数字是照着生产那台 2 核 768MiB 的 ECS 一格一格试出来的，写死在
 * `core/src/protocol.ts` 里。自托管的人拿一台 4GB 的 NAS 跑、拿一台 256MB 的玩具机跑，
 * 想要的数字并不一样——写死等于逼他们改代码重打包。所以这里给出**一个内存总闸**加三个
 * 直接命名的旋钮，其余字节预算按比例从总闸派生。
 *
 * 约束：这里的值只能**收窄**中继自己的接纳能力，不会让链路协议变形——
 *   - `streams` 参与 `hello-ack` 协商，插件侧还有自己的 64 上限（`negotiation` 的 schema
 *     就卡在 64），所以这个旋钮的实际范围是 1~64，调大不会有额外效果。
 *   - `maxBody` 再大也只到 `LIMITS.maxBody`（300MiB），因为手机端上传是分片走的。
 *   - `fragment`/`window` 完全不开放：它们是线格式契约，和容量无关，改了对端会直接
 *     握手失败（`unsupported-window`）。
 *
 * 环境变量（全部可选）：
 *   RELAY_MEMORY_MB    总内存预算，默认 768（就是标定基准；比例钳在 0.25x~2.5x）
 *   RELAY_MAX_HOSTS    同时在线的主机（电脑）数，默认 32
 *   RELAY_MAX_STREAMS  单主机并发流上限，默认 64，可下调到 1
 *   RELAY_MAX_BODY_MB  单请求体上限，默认 300
 *
 * 判据是「撞上它的会不会是正常用法」：这些都是兜底闸，正常家用撞不到；
 * 撞到了说明要么有人在打你，要么这台机器真的带不动那么多并发——两种情况下
 * 让它 503 都比让内核 OOM 杀掉整个中继好。
 */
export interface Capacity {
  /** 同时保持的插件隧道数（一台电脑一条）。 */
  maxHosts: number;
  /** 单主机并发流上限，协商给插件。 */
  streams: number;
  /** 单请求体上限。 */
  maxBody: number;
  /** 全进程中继侧的字节预留总闸（HTTP + WS 一起算）。 */
  nodeReservation: number;
  /** 全进程 WS 转发的字节预算。 */
  wsNodeBudget: number;
  /** 单主机 WS 转发的字节预算。 */
  wsHostBudget: number;
  /** 单条隧道 socket 的接收缓冲上限。 */
  httpQueue: number;
  /** 小额请求池（≤ largeThreshold 的那些）。 */
  smallBudget: number;
  /** 超过这个大小就走「大额」池，独占额度（防一堆大上传互相挤爆）。 */
  largeThreshold: number;
  /** 全进程 Node 流条数（每个转发出去的 HTTP/WS 各占一条）。 */
  nodeStreams: number;
  /** 实际生效的内存基准，只用于启动日志与排障。 */
  memoryMb: number;
  /** 这一份是不是默认值（没被任何环境变量改过）。 */
  isDefault: boolean;
}

/** 标定基准：`LIMITS` 里那些数字是在这个内存上试出来的。 */
const CALIBRATED_MB = 768;
const RATIO_MIN = 0.25;
const RATIO_MAX = 2.5;

function intEnv(env: NodeJS.ProcessEnv, name: string, fallback: number, min: number, max: number): number {
  const raw = env[name]?.trim();
  if (!raw) return fallback;
  if (!/^\d+$/.test(raw)) throw new Error(`${name} must be a positive integer (got "${raw}")`);
  const value = Number(raw);
  if (value < min || value > max) throw new Error(`${name} must be between ${min} and ${max} (got ${value})`);
  return value;
}

/** 按 MiB 取整：预算数字看着整齐，日志里也对得上。 */
const mib = (bytes: number): number => Math.max(1, Math.round(bytes / MiB)) * MiB;

/**
 * 按内存基准缩放字节预算。
 *
 * 只缩字节、不缩条数：并发条数由 `maxHosts`/`streams` 显式管，跟内存不是线性关系
 * （一台 4GB 的机器也不会想同时挂 150 台电脑）。
 */
export function capacityFor(memoryMb: number): Capacity {
  const ratio = Math.min(RATIO_MAX, Math.max(RATIO_MIN, memoryMb / CALIBRATED_MB));
  const scaled = mib(ratio === 1 ? LIMITS.nodeReservation : LIMITS.nodeReservation * ratio);
  return {
    maxHosts: 32,
    streams: LIMITS.streams,
    maxBody: ratio === 1 ? LIMITS.maxBody : mib(LIMITS.maxBody * ratio),
    nodeReservation: scaled,
    wsNodeBudget: ratio === 1 ? LIMITS.wsNodeBudget : mib(LIMITS.wsNodeBudget * ratio),
    wsHostBudget: ratio === 1 ? LIMITS.wsHostBudget : mib(LIMITS.wsHostBudget * ratio),
    httpQueue: ratio === 1 ? LIMITS.httpQueue : mib(LIMITS.httpQueue * ratio),
    smallBudget: ratio === 1 ? LIMITS.smallBudget : mib(LIMITS.smallBudget * ratio),
    largeThreshold: ratio === 1 ? LIMITS.largeThreshold : mib(LIMITS.largeThreshold * ratio),
    nodeStreams: 512,
    memoryMb: Math.round(CALIBRATED_MB * ratio * 100) / 100,
    isDefault: true,
  };
}

/** `LIMITS` 原样对应的默认预算：不设任何环境变量时中继行为与以前逐字节一致。 */
export const DEFAULT_CAPACITY: Readonly<Capacity> = Object.freeze({ ...capacityFor(CALIBRATED_MB), memoryMb: CALIBRATED_MB });

export function capacityFromEnv(env: NodeJS.ProcessEnv = process.env): Capacity {
  const memoryMb = intEnv(env, 'RELAY_MEMORY_MB', CALIBRATED_MB, 128, 8192);
  const base = capacityFor(memoryMb);
  const capacity: Capacity = {
    ...base,
    memoryMb,
    maxHosts: intEnv(env, 'RELAY_MAX_HOSTS', base.maxHosts, 1, 1024),
    streams: intEnv(env, 'RELAY_MAX_STREAMS', base.streams, 1, LIMITS.streams),
    maxBody: intEnv(env, 'RELAY_MAX_BODY_MB', Math.round(base.maxBody / MiB), 1, LIMITS.maxBody / MiB) * MiB,
  };
  // smallBudget 是给 ≤largeThreshold 的请求用的池子：被调小到比门槛还低时，所有小额
  // 请求都会被一次性拒掉（而不是排队），这种配置属于「悄悄把服务弄坏」，直接兜回来。
  capacity.largeThreshold = Math.min(capacity.largeThreshold, capacity.smallBudget);
  capacity.isDefault = capacity.maxHosts === 32 && capacity.streams === LIMITS.streams
    && capacity.maxBody === LIMITS.maxBody && memoryMb === CALIBRATED_MB;
  return capacity;
}

/** 启动时打一行，运维不用翻代码就知道这台机器吃的是哪套预算。 */
export function describeCapacity(c: Capacity): string {
  return `${c.isDefault ? 'default' : 'custom'} memory=${c.memoryMb}MiB hosts=${c.maxHosts} streams=${c.streams}`
    + ` maxBody=${c.maxBody / MiB}MiB reservation=${c.nodeReservation / MiB}MiB ws=${c.wsNodeBudget / MiB}MiB`;
}
