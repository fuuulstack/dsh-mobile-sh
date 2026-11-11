/**
 * 宿主进程 ↔ 转发子进程之间的 IPC 契约（中期-13）。
 *
 * <p>只有两件事要穿过这条管道：**命令下行**（起/停/换 cookie）与**状态上行**（状态、
 * 日志、需要新 cookie）。HTTP 转发与归档的字节流一律不经过这里——它们走子进程自己的
 * 隧道，父进程连看都看不到。所以这份协议刻意做得很窄：多一个字段就是多一个跨进程
 * 不兼容面。
 */
import type { MetricsSnapshot } from './metrics.js';

/** 父进程 → 子进程。 */
export type WorkerCommand =
  | {
      type: 'start';
      relayUrl: string;
      relayOrigin: string;
      hostId: string;
      hostTunnelToken: string;
      upstreamOrigin: string;
      upgradePaths: string[];
      maxBody: number;
      maxStreams: number;
      connectTimeoutMs: number;
      httpIdleTimeoutMs: number;
      /**
       * Monotonic per-host, bumped by the Forwarder on every fork (v2 §E3).
       *
       * <p>A restart can leave the old child alive for a few hundred ms — blocked in a
       * synchronous decrypt, or ignoring SIGTERM mid-write. Both children then hold a
       * tunnel under the same host id, and the relay would forward half the phone's
       * requests to a process that is about to die. It cannot tell them apart by socket
       * alone; it can by this number. The phone sees it in `HELLO_ACK` and in every
       * pulse, so a stale child's pulses are recognisably stale.
       *
       * <p>Optional from the caller's point of view: the Forwarder stamps it, so no
       * call site can forget to advance it.
       */
      workerEpoch?: number;
      /** Where the durable archive outbox lives; null keeps it in memory (tests only). */
      outboxPath?: string | null;
      /** `git rev-parse --short HEAD` + build time, echoed to the phone's settings screen. */
      buildHash?: string;
    }
  /** 宿主鉴权 cookie：子进程够不到 `ctx.connection`，只能由父进程兑换后下发。 */
  | { type: 'cookie'; cookie: string }
  | { type: 'stop' };

/** 子进程 → 父进程。 */
export type WorkerEvent =
  | { type: 'ready' }
  /** 状态全量推（几百字节，不值得做增量）。`probe` 是 upgrade 探测结论，可缺省。 */
  | { type: 'state'; state: string; cookieReady: boolean; probe?: unknown }
  | { type: 'log'; message: string }
  /** 401 触发：父进程重新兑换 `authenticatedUrl` 后回一条 `cookie`。 */
  | { type: 'need-cookie' }
  | { type: 'stopped' }
  /** 致命错误：父进程记日志并按退避重启。 */
  | { type: 'fatal'; error: string };

/** 父进程持有的、最近一次子进程上报的状态。UI 的 `status()` 直接读它。 */
export interface WorkerState {
  state: string;
  cookieReady: boolean;
  probe: unknown;
}

/** 一条指标快照，父进程透传用途（当前未使用，留作后续在宿主侧聚合）。 */
export type WorkerMetrics = MetricsSnapshot;
