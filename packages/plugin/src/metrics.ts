/**
 * 宿主侧运行指标采集（中期-12）。
 *
 * <p>为什么需要它：502 的根因在中继那边只能看到 `upstream-error` 四个字——中继根本
 * 不知道插件为什么失败。真正的原因有且只有两个来源：①loopback 连接被对端重置
 * （`ECONNRESET`/`ECONNREFUSED`/`ETIMEDOUT`），②宿主事件循环卡住导致请求迟迟发不出去
 * （`client.ts` 的 `responseTimeout`/`req.timeout` 兜底 destroy，最后也压成 502）。
 * 这两件事**只有插件进程内看得见**，所以必须由插件主动上报，否则运维永远在猜。
 *
 * <p>上报通道刻意不复用隧道控制帧：`controlSchema` 是 `strictObject`，旧中继收到不认识的
 * `type` 会按 4409 断开整条隧道——为了一条指标把线上插件踢下线是不可接受的。这里改走一条
 * 独立的 HTTPS POST（`/control/metrics`），旧中继返回 404、插件静默忽略，新中继续保持
 * 后向兼容。
 */
export interface MetricsSnapshot {
  /** 采样窗口长度（毫秒）。 */
  windowMs: number;
  /** 事件循环滞后，单位毫秒。p99 是「卡顿」最灵敏的单一指标。 */
  eventLoop: { samples: number; meanMs: number; p50Ms: number; p99Ms: number; maxMs: number };
  /** loopback 失败计数：出问题的永远是某几个 errno，按 code 分开看才知道该修哪。 */
  loopback: { failures: number; total: number; byCode: Record<string, number> };
  /** 隧道状态与重连次数（重连频繁说明链路在抖，而不是宿主在卡）。 */
  tunnel: {
    state: string;
    reconnects: number;
    /**
     * 本窗口内应用层发送缓冲（`socket.bufferedAmount`）的最高水位，字节。
     *
     * `>= 1 MiB` 意味着 `Tunnel.pump` 真的停过泵：那段时间隧道上**一帧都不出**，
     * 但对端 WebSocket 栈照样回 pong，手机侧看着完全正常。它是「无帧」到底是
     * 「本来没东西可发」还是「发送端吸不动」的分水岭。
     */
    bufferedPeakBytes: number;
    /**
     * 本窗口内流控信用等待超过阈值（默认 10s）的次数。
     *
     * 与 `bufferedPeakBytes` 互补：缓冲低 + stall 高 = **对端不消费**（手机侧接收慢，
     * 或下游写不动导致收不回信用）；缓冲高 + stall 高 = 本端发不出去（上行不足）。
     */
    stalls: number;
  };
  /** 归档推送队列深度与推送代价：持续上涨就是中继/上行带宽跟不上。 */
  archiver: {
    queued: number;
    inFlight: boolean;
    sending: number;
    /**
     * 最近一次 `/control/archive` 的往返耗时（毫秒）。
     *
     * <p>它是「手机看到消息晚」里**唯一由插件侧决定**的那一段：记录已经在队列里、
     * 也发出去了，但中继还没确认。与 `queued` 一起读才有意义——`queued` 大而
     * `pushMs` 正常 = 上行带宽不够（还在排队）；`queued` 不大而 `pushMs` 飙升
     * = 中继慢或在重试（记录卡在 sending）。
     */
    pushMs: number;
    /** 最近一次推送的实际请求体字节数（压缩后）。用来判断「批太小」还是「批太大」。 */
    batchBytes: number;
    /** 本窗口内被拒/失败的推送次数。稳态下必须恒为 0。 */
    pushFailures: number;
    /** 扣在 hub 内存里、进不了 outbox 的记录条数（outbox 满了的降级量）。 */
    heldBack: number;
    /**
     * 「宿主把 seq 倒回去重编」的迹象次数。
     *
     * <p>归档去重只靠 ring + outbox 主键，整条链路依赖「seq 永不复用」这个假设。
     * 一旦宿主重启后对既有会话重新计数，新事件全部撞主键被静默丢弃，**归档会无声
     * 停止增长**。这个数在稳态下必须是 0；非 0 就说明假设破了，该去看宿主。
     */
    renumbered: number;
  };
}

/**
 * 指标载荷的 schema 版本。
 *
 * <p>为什么必须显式带：中继与插件是**分开部署**的（老中继接新插件、新中继接老插件
 * 都会真实发生）。没有版本号时，唯一的兼容手段是「所有新字段都给 default」，一旦
 * 哪天需要**改语义**（不是加字段）就完全没法判断对端是老还是新——只能靠猜。
 * 升级口径：加字段不涨版本（新字段给 default，老中继读不到也不报错）；改语义必涨，
 * 中继按版本决定怎么解释。
 */
export const METRICS_SCHEMA_VERSION = 1;

/** 单次上报的结构：插件身份 + 一份快照。 */
export interface MetricsReport extends MetricsSnapshot {
  /** 见 [METRICS_SCHEMA_VERSION]。老中继不认识它也能收（多出来的字段会被 strictObject 拒——所以中继侧同时加了默认值）。 */
  schemaVersion: number;
  hostId: string;
  /** 插件进程已运行时长（毫秒）。 */
  uptimeMs: number;
  /** 上报时刻（epoch ms）。中继用它算「多久没收到了」。 */
  at: number;
}

/** 采样间隔：250ms 足够看见「卡了 1 秒」，又不会自己成为负载。 */
const SAMPLE_MS = 250;
/** 保留最近 240 个样本（约 1 分钟）：窗口比上报周期（60s）略长，不会切掉边缘。 */
const SAMPLE_WINDOW = 240;

/**
 * 进程内累加器。
 *
 * <p>只做累加与快照，**不做任何 I/O**：它跑在 DSH 宿主的主进程里，自己的开销必须
 * 小到看不见（一个 250ms 定时器 + 几个 Map 自增）。
 *
 * <p>为什么不用 `perf_hooks.monitorEventLoopDelay`：实测（Windows，Node 24）它的
 * 读数会饱和——主线程忙等 2000ms，直方图的 `max` 只有 33.65ms（resolution=20）
 * / 21.45ms（resolution=11），且与阻塞时长无关。原因是 Windows 上该直方图的分辨率
 * 受 UV 定时器精度约束。宿主跑在 Windows 桌面是常态，用一个「卡 2 秒也只报 32ms」的
 * 指标等于没有指标。这里改成**实测计时器滞后**：`setTimeout(250)` 实际睡了多久，
 * 差值就是事件循环被占住的时间，跨平台语义一致。
 */
export class HostMetrics {
  private readonly startedAt = Date.now();
  private failures = 0;
  private total = 0;
  private readonly byCode = new Map<string, number>();
  private state = 'starting';
  private reconnects = 0;
  /** 首次 ready 是「连上」，之后每次 ready 才是「重连」。 */
  private everReady = false;
  /**
   * Records the outbox could not accept and the hub is holding in memory.
   *
   * <p>Reported separately from `archiver.queued` because the two mean opposite
   * things about where the problem is: `queued` growing says the uplink is slow,
   * `heldBack` growing says the *outbox* is full while pushes may be succeeding —
   * i.e. the local cap is too small for the session count, not the network.
   */
  private heldBack = 0;
  private renumbered = 0;
  private archiverQueued = 0;
  private archiverInFlight = false;
  private archiverSending = 0;
  private pushMs = 0;
  private batchBytes = 0;
  private pushFailures = 0;
  /** 本窗口内发送缓冲峰值与信用停顿次数（由 `HostClient` 从 Tunnel 上采集后打点）。 */
  private bufferedPeak = 0;
  private stalls = 0;
  /** 发送缓冲峰值的读源；隧道重建时由 `client.ts` 换成新的。 */
  private peakSource?: () => number;
  /** 上一份快照的时刻与计数：失败率要按窗口算，累计值在长跑进程里会趋于无穷。 */
  private lastSnapshotAt = Date.now();
  private lastFailures = 0;
  private lastTotal = 0;
  /** 样本环：固定长度，避免长时间运行后数组无界增长。 */
  private readonly samples: number[] = [];
  private sampleTimer?: NodeJS.Timeout;
  private expectedAt = 0;
  private closed = false;

  constructor(readonly sampleMs = SAMPLE_MS) {
    // 采样定时器 unref：它不该阻止进程退出（插件被卸载后进程要能正常结束）。
    this.sampleTimer = setInterval(() => this.sample(), this.sampleMs);
    this.sampleTimer.unref();
    this.expectedAt = Date.now() + this.sampleMs;
  }

  /**
   * 一次采样：`setInterval` 只在事件循环空闲时才被派发，实际到达时间与「应到时间」
   * 的差就是这段窗口里主线程被占住的总时长。
   */
  private sample(): void {
    if (this.closed) return;
    const now = Date.now();
    const lag = now - this.expectedAt;
    // lag 为负说明定时器提前（时钟回拨/系统时间调整）：按 0 记，不让它污染均值。
    this.samples.push(lag > 0 ? lag : 0);
    if (this.samples.length > SAMPLE_WINDOW) this.samples.shift();
    // 以「本次实际时刻」为下一次的基准，累加不会漂移。
    this.expectedAt = now + this.sampleMs;
    // 顺带把发送缓冲峰值捞进本窗口（读源自己清零，所以这里只在涨的时候更新）。
    if (this.peakSource) {
      try {
        this.noteBufferedPeak(this.peakSource());
      } catch {
        // 读源属于隧道，可能正好在这条采样里被销毁；观测点绝不能影响主流程。
        this.peakSource = undefined;
      }
    }
  }

  /** 隧道状态变化；同一状态重复上报不计数。 */
  noteState(next: string): void {
    if (next === this.state) return;
    // 「连上 → 掉线 → 又连上」才算一次重连。启动时第一次 ready 是连上，不是重连；
    // 否则每次插件启动都会凭空多一次重连，让「链路在抖」的判据失真。
    if (next === 'ready') {
      if (this.everReady) this.reconnects++;
      this.everReady = true;
    }
    this.state = next;
  }

  /**
   * 从插件日志里挑出 loopback 失败。
   *
   * <p>不额外埋点：`client.ts`/`archiver.ts` 失败时本来就会 `onLog('loopback <METHOD> <path> failed: <code>')`，
   * 直接在那条日志上做匹配，既不会漏（同一个失败点）也不会重复（没有第二处埋点）。
   *
   * @returns true 表示这条日志是一次 loopback 失败，已被计入。
   */
  noteLog(message: string): boolean {
    const m = /^loopback \S+ \S+ failed: (\S+)/.exec(message);
    if (!m) return false;
    this.failures++;
    // errno 之外还可能是 Error.message（"socket hang up" 含空格）：只保留首段，
    // 且限定字符集，避免把用户可控的路径片段灌进指标键。
    const code = (m[1]!.match(/^[A-Za-z0-9_.-]{1,40}/)?.[0]) ?? 'unknown';
    this.byCode.set(code, (this.byCode.get(code) ?? 0) + 1);
    return true;
  }

  /** loopback 请求总数（成功 + 失败）；由 client 在每次 open 时打点。 */
  noteLoopbackAttempt(): void { this.total++; }

  /**
   * 归档队列/在途深度。
   *
   * @param queued 队列里还没取出的**记录**条数。
   * @param inFlight 此刻是否有一批正在等中继响应。
   * @param sending 已出队、尚未收到 200 的记录条数。这个读数与 queued 分开很重要：
   *   queued 掉到 0 而 sending 还挂着 = 卡在 HTTP；两者一起涨 = 中继收不动。
   */
  noteArchiver(queued: number, inFlight: boolean, sending = 0): void {
    this.archiverQueued = queued;
    this.archiverInFlight = inFlight;
    this.archiverSending = sending;
  }

  /**
   * 记一次 `/control/archive` 的结果。
   *
   * @param ok 中继是否真的确认（HTTP 2xx）。4xx/5xx 与网络异常都算失败——
   *   「推了但没成功」和「推成功了」在指标上必须分得开，否则失败会被速率掩盖。
   */
  /**
   * Depth of the hub's in-memory backlog.
   *
   * <p>Non-zero is not automatically alarming — it is how a full outbox degrades
   * instead of dropping records — but a value that keeps climbing across windows
   * means pushes are not keeping up with ingest at all.
   */
  noteHeldBack(count: number): void {
    this.heldBack = Math.max(0, Math.round(count));
  }

  /** 累计值（不是窗口值）：seq 复用是不可恢复事件，累计为 0 才说明假设成立。 */
  noteRenumbered(total: number): void {
    this.renumbered = Math.max(0, Math.round(total));
  }

  notePush(ms: number, batchBytes: number, ok: boolean): void {
    this.pushMs = Math.max(0, Math.round(ms));
    this.batchBytes = Math.max(0, Math.round(batchBytes));
    if (!ok) this.pushFailures++;
  }

  /**
   * 记一次「应用层发送缓冲到过这么高」。
   *
   * <p>调用点应当先 `Tunnel.takeBufferedPeak()` 取走并清零再打点，这样每个窗口报的是
   * **本窗口**的峰值，而不是「历史最高」（后者会被一次带宽打满永久顶住）。
   */
  noteBufferedPeak(bytes: number): void {
    if (bytes > this.bufferedPeak) this.bufferedPeak = bytes;
  }

  /**
   * 绑定「取走发送缓冲峰值」的读源（通常是 `tunnel.takeBufferedPeak`）。
   *
   * <p>为什么由 `sample()` 每 250ms 去拉，而不是另起一个定时器：读源本身是「取走并
   * 清零」，拉得越勤峰值越不可能被漏掉（一次 200ms 的 2MB 尖峰，5 秒一次的采样就有
   * 可能完全看不见），而多一个定时器就多一份卡顿嫌疑。隧道断开后置回 `undefined`。
   */
  bindBufferedPeak(source: (() => number) | undefined): void {
    this.peakSource = source;
  }

  /**
   * 记一次流控信用停顿（等对端 `flow` 超过 `Tunnel.stallMs`）。
   *
   * <p>只计数不记明细：日志里已经有 `streamId`/`pending`/`buffered`，指标只需要速率。
   */
  noteStall(): void { this.stalls++; }

  /** 取一份快照并**清空样本环**（否则 p99 永远被历史峰值污染）。 */
  snapshot(): MetricsSnapshot {
    const now = Date.now();
    const windowMs = now - this.lastSnapshotAt;
    const sorted = [...this.samples].sort((a, b) => a - b);
    const at = (q: number) => (sorted.length ? sorted[Math.min(sorted.length - 1, Math.floor(q * sorted.length))]! : 0);
    const mean = sorted.length ? sorted.reduce((s, v) => s + v, 0) / sorted.length : 0;
    const snapshot: MetricsSnapshot = {
      windowMs,
      eventLoop: {
        samples: sorted.length,
        meanMs: Math.round(mean * 100) / 100,
        p50Ms: at(0.5),
        p99Ms: at(0.99),
        maxMs: sorted.length ? sorted[sorted.length - 1]! : 0,
      },
      // 窗口内的失败率：累计值在长跑进程里会趋于无穷，看速率才有意义。
      loopback: {
        failures: this.failures - this.lastFailures,
        total: this.total - this.lastTotal,
        byCode: Object.fromEntries(this.byCode),
      },
      tunnel: {
        state: this.state,
        reconnects: this.reconnects,
        bufferedPeakBytes: this.bufferedPeak,
        stalls: this.stalls,
      },
      archiver: {
        queued: this.archiverQueued,
        inFlight: this.archiverInFlight,
        sending: this.archiverSending,
        pushMs: this.pushMs,
        batchBytes: this.batchBytes,
        pushFailures: this.pushFailures,
        heldBack: this.heldBack,
        renumbered: this.renumbered,
      },
    };
    this.samples.length = 0;
    this.lastSnapshotAt = now;
    this.lastFailures = this.failures;
    this.lastTotal = this.total;
    // 峰值与停顿同样按窗口清零：报了之后从零开始，下一个窗口读数是「这段时间」的。
    this.bufferedPeak = 0;
    this.stalls = 0;
    this.pushFailures = 0;
    return snapshot;
  }

  report(hostId: string): MetricsReport {
    const snap = this.snapshot();
    return {
      ...snap,
      schemaVersion: METRICS_SCHEMA_VERSION,
      hostId,
      uptimeMs: Date.now() - this.startedAt,
      at: Date.now(),
    };
  }

  close(): void {
    this.closed = true;
    if (this.sampleTimer) { clearInterval(this.sampleTimer); this.sampleTimer = undefined; }
  }
}

/**
 * 定时把快照 POST 到中继的 `/control/metrics`。
 *
 * <p>为什么是「插件推」而不是「中继拉」：中继够不到宿主，loopback 只在插件进程这一侧。
 * 为什么不是每次都推：一份快照是几百字节，60 秒一次对手机侧带宽毫无影响，但足够画出
 * 「502 时刻事件循环 p99 是多少」这条曲线——这正是评审里 H1 假设唯一缺的证据。
 *
 * <p>失败一律吞掉：指标是观测面，任何情况下都不能反过来影响隧道。中继不认这条路由
 * （旧版返回 404）时静默跳过，不重试、不告警。
 */
export function startMetricsReporter(options: {
  metrics: HostMetrics;
  relayOrigin: string;
  hostId: string;
  hostTunnelToken: string;
  intervalMs?: number;
  fetchImpl?: typeof fetch;
  onLog?: (message: string) => void;
}): () => void {
  const interval = options.intervalMs ?? 60000;
  const doFetch = options.fetchImpl ?? fetch;
  let stopped = false;
  /**
   * 上一次失败的样子（`status:400` 这种），用来限频：状态码变了才记一行。
   *
   * <p>这条限频不是洁癖。评审现场出现过「`/control/metrics` 连续 400，插件每 60 秒
   * 记一行，日志里全是它」——而 400 是 schema 不兼容这种**不变**的故障，重复记录
   * 不但没有信息量，还会把真正的业务日志埋掉。
   */
  let lastFailure = '';
  const noteFailure = (shape: string, message: string) => {
    if (shape === lastFailure) return;
    lastFailure = shape;
    options.onLog?.(message);
  };
  const push = async () => {
    if (stopped) return;
    let body: string;
    try { body = JSON.stringify(options.metrics.report(options.hostId)); } catch { return; }
    try {
      const response = await doFetch(new URL('/control/metrics', options.relayOrigin), {
        method: 'POST', redirect: 'error', signal: AbortSignal.timeout(10000),
        headers: { authorization: `Bearer ${options.hostTunnelToken}`, 'content-type': 'application/json' },
        body,
      });
      // 关键：`fetch` 只在网络层失败时 reject，HTTP 4xx/5xx 照样 resolve。
      // 早先这里完全不看 `response.ok`，于是 schema 不兼容、token 失效这些**真的
      // 让监控瞎掉**的故障在插件侧一声不响（评审现场：中继连续 400，两端日志全绿）。
      if (!response.ok) {
        noteFailure(`status:${response.status}`, `metrics push rejected: ${response.status}`);
        return;
      }
      lastFailure = '';
    } catch (e) {
      // 只记一行、不重试：这是可观测性旁路，重试只会在一台中继故障时变成日志风暴。
      noteFailure(`error:${e instanceof Error ? e.name : 'unknown'}`,
        `metrics push failed: ${e instanceof Error ? e.message : 'unknown'}`);
    }
  };
  const timer = setInterval(() => void push(), interval);
  timer.unref();
  void push();
  // 只停定时器与采样器；HostMetrics 由调用方持有（跨重连保留累积量）。
  return () => { stopped = true; clearInterval(timer); };
}
