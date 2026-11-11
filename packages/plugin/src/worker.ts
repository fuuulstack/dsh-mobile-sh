/**
 * 中继转发子进程（中期-13 解耦 + v2 采集/归档重写）。
 *
 * <p>为什么要有这个进程：插件原本把「隧道 + loopback 转发 + 归档」全部跑在 **DSH 宿主
 * 主进程的事件循环**里。宿主是桌面应用，主线程同时要跑 UI、网关和所有插件——它一卡，
 * loopback 请求就发不出去（`ECONNRESET` / 响应超时），中继把 `upstream-error` 四个字
 * 原样变成 502 写回手机；反过来插件自身的加密、gzip、JSON 解析也在抢同一条线程。
 * 两侧互相拖累，而且**没有任何隔离手段**：插件能做的只有「少占一点」。
 *
 * <p>所以把这一层整体挪出去：本文件由 `supervisor.ts` 用 `fork()` 拉起，独占一个事件循环。
 * 宿主进程里只剩「读设置 / 兑换 cookie / 把状态转述给 UI」这几件轻活。
 *
 * <p><b>v2 数据流</b>（每一条记录只经过一次）：
 *
 * ```
 * 宿主 mux ──► Collector ──► SessionHub ──┬──► ArchiveOutbox (SQLite) ──► ArchivePusher ──► 中继
 *                                        └──► ControlSession ──► 手机（聚焦时）
 * ```
 *
 * <p>Hub 同时是编号者与分发点，所以手机上看到的实时记录与中继里存的归档记录**逐字节一致**，
 * 手机只需要一个投影器。这与 v1 的「实时走 mux 原始事件、归档走 /sync 裁剪过的记录」是
 * 两种不同的东西，正是 v2 要消掉的那层分叉。
 *
 * <p><b>安全边界</b>：子进程只走 stdin 上的 IPC，不开放任何端口；宿主鉴权 cookie 与中继
 * 凭据由父进程下发，子进程不落盘、不写日志（日志一律回父进程写）。
 *
 * <p><b>故障姿态</b>：崩溃即退出，父进程按退避重启。重启期间手机侧隧道断开，中继侧表现为
 * `host offline`——这是**明确可诊断**的降级，好过宿主陪着一起卡。未确认的归档记录在
 * SQLite 里，重启后原地继续，不靠重放快照重建缺口。
 */
import { HostClient } from './client.js';
import { Collector } from './collector.js';
import { ControlSession } from './control.js';
import { SessionHub } from './hub.js';
import { HostMetrics, startMetricsReporter } from './metrics.js';
import { ArchiveOutbox } from './outbox.js';
import { ArchivePusher } from './pusher.js';
import type { WorkerCommand, WorkerEvent } from './worker-protocol.js';

let client: HostClient | undefined;
let collector: Collector | undefined;
let pusher: ArchivePusher | undefined;
let outbox: ArchiveOutbox | undefined;
let hub: SessionHub | undefined;
let stopMetrics: (() => void) | undefined;
let depthTimer: NodeJS.Timeout | undefined;
/** Drops ACKED outbox rows the relay already holds. Nothing else ever deletes them. */
let pruneTimer: NodeJS.Timeout | undefined;

/** How long an ACKED row is kept. Long enough to inspect a batch that just landed,
 *  short enough that the outbox stays a working set instead of a second archive. */
const ACKED_RETENTION_MS = 10 * 60_000;
/** Prune cadence. Cheap (two DELETEs on indexed columns), so a minute is fine. */
const PRUNE_INTERVAL_MS = 60_000;
let cookie = '';
let cookieAt = 0;
let refresh: Promise<string> | undefined;
let state = 'starting';
/** The generation the parent stamped on this child (v2 §E3). Echoed in HELLO_ACK and
 *  every pulse so a stale sibling's frames are recognisably stale. */
let workerEpoch = 0;
let buildHash = '';
/** 指标在子进程里采样：它现在才真的代表「转发链路所在的那个事件循环」。 */
const metrics = new HostMetrics();

const send = (event: WorkerEvent) => {
  try {
    // 父进程已退出：跟着退，别留下一条幽灵隧道占着 hostId。
    if (!process.send) process.exit(0);
    process.send(event);
  } catch {
    process.exit(0);
  }
};

/** 状态变化一律全量推一份（几百字节，不值得做增量）。 */
const emit = (next?: string) => {
  if (next !== undefined) state = next;
  send({ type: 'state', state, cookieReady: !!cookie });
};
const log = (message: string) => {
  metrics.noteLog(message);
  send({ type: 'log', message });
};

/** 等父进程下发 cookie 的调用方（同一时刻可能有多条通道在等）。 */
let cookieWaiters: Array<(value: string) => void> = [];

/**
 * 取宿主鉴权 cookie。
 *
 * <p>`authenticatedUrl` 是宿主签发的一次性登录 URL，子进程**够不到** `ctx.connection`，
 * 兑换只能由父进程做，这里只等结果。cookie 有效期 25 天（宿主 `Max-Age`），过期后
 * `HostClient` 会清空再走一次这条路，父进程重新兑换。
 *
 * <p>等待必须有超时：父进程若卡在自己的事件循环里（正是我们要隔离的那种故障），子进程
 * 不能陪着挂——15 秒后按「cookie 不可用」失败，让手机看到 503 而不是无限等。
 */
const getCookie = (): Promise<string> => {
  if (cookie && Date.now() - cookieAt < 25 * 86400000) return Promise.resolve(cookie);
  if (refresh) return refresh;
  refresh = new Promise<string>(resolve => {
    const settle = (value: string) => {
      clearTimeout(timer);
      resolve(value);
    };
    const timer = setTimeout(() => {
      cookieWaiters = cookieWaiters.filter(w => w !== settle);
      resolve('');
    }, 15000);
    timer.unref();
    cookieWaiters.push(settle);
    send({ type: 'need-cookie' });
  }).then(value => {
    refresh = undefined;
    // 空串表示超时：抛出去让调用方走 503，而不是拿空 cookie 去打宿主。
    if (!value) throw new Error('cookie-unavailable');
    return value;
  });
  return refresh;
};

/** 收到父进程下发的 cookie：唤醒所有等待者。 */
const acceptCookie = (value: string) => {
  cookie = value;
  cookieAt = Date.now();
  const waiters = cookieWaiters;
  cookieWaiters = [];
  for (const settle of waiters) settle(value);
  emit();
};

const start = (command: Extract<WorkerCommand, { type: 'start' }>) => {
  const {
    relayOrigin, relayUrl, hostId, hostTunnelToken, upstreamOrigin,
    upgradePaths, maxBody, maxStreams, connectTimeoutMs, httpIdleTimeoutMs,
  } = command;
  workerEpoch = command.workerEpoch ?? workerEpoch;
  buildHash = command.buildHash ?? buildHash;
  // 指标上报器先起：隧道第一次握手失败就已经是「卡顿」证据。
  stopMetrics = startMetricsReporter({ metrics, relayOrigin, hostId, hostTunnelToken, onLog: log });

  // One durable outbox per host. `reset()` moves rows a previous incarnation left
  // IN_FLIGHT back to PENDING: the batch may or may not have landed, and the push is
  // idempotent by content-derived batch id, so re-sending is the correct choice.
  outbox = new ArchiveOutbox({ path: command.outboxPath === undefined ? null : command.outboxPath });
  outbox.reset();
  hub = new SessionHub({
    outbox,
    // Collapse a burst of refusals into one re-follow: an outbox that filled up during a
    // snapshot produces one refusal per record, and re-following per record would ask the
    // host for the same snapshot dozens of times in a row.
    onOverflow: () => log('archive outbox full: holding records in memory until it drains'),
    onRecord: () => pusher?.kick(),
  });
  pusher = new ArchivePusher({
    outbox, relayOrigin, hostId, hostTunnelToken, onLog: log, metrics,
    // A batch just left the outbox: retry whatever the hub was holding back.
    onDrained: () => { const n = hub?.retryPending() ?? 0; if (n) log(`outbox drained, ${n} held-back records stored`); },
  });
  pusher.start();
  // Created after the pusher so the hub's `onRecord` kick has somewhere to land.
  collector = new Collector({ hub, outbox, upstreamOrigin, getCookie, onLog: log, metrics });
  collector.start();

  client = new HostClient({
    relayUrl, hostId, hostTunnelToken, upstreamOrigin, getCookie,
    refreshCookie: () => {
      cookie = '';
      send({ type: 'need-cookie' });
      return Promise.resolve();
    },
    maxBody, maxStreams, upgradePaths, connectTimeoutMs, httpIdleTimeoutMs, metrics,
    // Stamped by the supervisor on every spawn; the relay uses it to refuse a worker
    // generation that is older than the one it already holds.
    workerEpoch,
    onState: next => {
      metrics.noteState(next);
      emit(next);
      log(next);
    },
    onLog: log,
    // The phone's control socket terminates inside this process, not at the host.
    control: channel => new ControlSession(channel, {
      hub: hub!,
      upstreamOrigin,
      getCookie,
      workerEpoch,
      buildHash,
      onLog: log,
      // Reported in every pulse so the app can tell "the host is slow" from "my link
      // is slow" without asking anyone.
      bufferedBytes: () => client?.tunnel?.queuedBytes() ?? 0,
      // The answer to an approval must name the client the host delivered it to —
      // this worker's own `$events` connection, not the phone's.
      eventsClientId: () => collector?.eventsClient ?? '',
    }),
  });
  client.connect();

  // Outbox depth is the only external evidence that the uplink is behind: the phone
  // just sees "syncing is slow" and cannot tell the relay from the plugin.
  depthTimer = setInterval(() => {
    if (!outbox) return;
    const depth = outbox.depth();
    metrics.noteArchiver(depth.pending, depth.inflight > 0, depth.inflight);
    metrics.noteHeldBack(hub?.pending ?? 0);
    // 非 0 表示宿主把 seq 倒回去重编了：归档从这一刻起会静默停止增长（新事件全撞
    // outbox 主键被丢弃）。稳态必须是 0，所以按累计值上报而不是窗口值。
    metrics.noteRenumbered(hub?.renumbered ?? 0);
  }, 1000);
  depthTimer.unref();

  // The outbox counts only unacknowledged rows toward its ceiling, so ACKED rows never
  // block anything — they just accumulate. Measured before this timer existed: 10 246
  // ACKED rows / 27 MiB after three days (~9 MiB/day) of a database whose useful
  // contents were 3 rows. `outbox_watermark` is a separate table on purpose, so pruning
  // cannot rewind the coverage watermark.
  pruneTimer = setInterval(() => {
    try {
      const removed = outbox?.prune(ACKED_RETENTION_MS);
      if (removed) log(`outbox pruned ${removed} acknowledged rows`);
    } catch (error) {
      log(`outbox prune failed: ${error instanceof Error ? error.message : String(error)}`);
    }
  }, PRUNE_INTERVAL_MS);
  pruneTimer.unref();

  emit('connecting');
};

const shutdown = async () => {
  clearInterval(depthTimer);
  depthTimer = undefined;
  clearInterval(pruneTimer);
  pruneTimer = undefined;
  stopMetrics?.();
  stopMetrics = undefined;
  await collector?.stop();
  collector = undefined;
  await pusher?.stop();
  pusher = undefined;
  await client?.stop();
  client = undefined;
  outbox?.close();
  outbox = undefined;
  hub = undefined;
  emit('disabled');
};

process.on('message', (raw: unknown) => {
  const command = raw as WorkerCommand;
  switch (command?.type) {
    case 'start':
      start(command);
      break;
    case 'cookie':
      acceptCookie(command.cookie);
      break;
    case 'stop':
      void shutdown().finally(() => {
        send({ type: 'stopped' });
        process.exit(0);
      });
      break;
    default:
      break;
  }
});

// 父进程消失（管道关闭）时自杀，否则会留下一条幽灵隧道占着同一个 hostId。
process.on('disconnect', () => process.exit(0));
process.on('uncaughtException', error => {
  send({ type: 'fatal', error: error instanceof Error ? error.message : String(error) });
  process.exit(1);
});
process.on('unhandledRejection', error => {
  send({ type: 'fatal', error: error instanceof Error ? error.message : String(error) });
  process.exit(1);
});

send({ type: 'ready' });

export {};
