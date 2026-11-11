/**
 * 转发子进程的守护（中期-13）。
 *
 * <p>职责只有四件：拉起子进程、把命令转下去、把状态转上来、崩了按退避重启。**它自己不做
 * 任何网络 I/O** —— 一旦它开始转发字节，解耦就白做了（又回到宿主事件循环里）。
 *
 * <p>为什么用 `fork()` 而不是 `worker_threads`：线程仍与宿主共享同一个进程的堆与 GC，
 * 一次长 GC 照样把所有线程一起停住；而我们要隔离的正是「宿主主线程被 UI/网关占住」这类
 * 整进程级停顿。独立进程的代价是多一份运行时，换来的是**宿主卡死时手机侧隧道照常**。
 * 代价实测（2026-10-29，macOS arm64，DSH 桌面 + 11 个订阅会话 + 1 条隧道）：
 * worker RSS ≈ 142MB（bundle 0.7MB，其余是 V8 堆与 ws/zod 的模块）。这比「只多一份小型
 * Node 运行时」的直觉要贵，所以在低内存机器上它是有成本的——但换来的是父进程侧非 loopback
 * 连接数稳定为 0（可用 `lsof -p <host pid> -a -i TCP` 自查，这正是隔离生效的判据）。
 */
import { fork, type ChildProcess } from 'node:child_process';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import type { WorkerCommand, WorkerEvent } from './worker-protocol.js';

/**
 * 定位子进程入口：优先构建产物 `worker.js`，没有就退到源码 `worker.ts`。
 *
 * <p>为什么要退化：测试用 `tsx` 直接跑 `packages/plugin/src/*.ts`，此时 `dist/` 根本不存在，
 * 只认 `worker.js` 会让所有插件级测试在 fork 那一刻就 ENOENT 失败（表现为「状态永远不是
 * ready」——一个完全指不到根因的错误）。源码模式下同时要把父进程的 `execArgv` 一起带下去，
 * 否则子进程里没有 tsx 的 loader，`.ts` 入口无法执行。
 */
export function resolveWorkerFile(nearImportMetaUrl: string): { file: string; execArgv: string[] } {
  const asBuilt = fileURLToPath(new URL('./worker.js', nearImportMetaUrl));
  if (existsSync(asBuilt)) return { file: asBuilt, execArgv: [] };
  const asSource = fileURLToPath(new URL('./worker.ts', nearImportMetaUrl));
  // 带 loader 的 execArgv（tsx 的 --require/--import）必须原样传下去。
  return { file: asSource, execArgv: process.execArgv };
}

export interface ForwarderOptions {
  /** 子进程入口（构建产物 `dist/worker.js`，源码模式下自动退到 `worker.ts`）。 */
  workerFile: string;
  /** 子进程的 execArgv（源码模式要带 tsx 的 loader，构建产物为空）。 */
  execArgv?: string[];
  /** 宿主鉴权 cookie 的兑换方：只有父进程拿得到 `ctx.connection`。 */
  getCookie: () => Promise<string>;
  onLog: (message: string) => void;
  onState: (state: string) => void;
  /**
   * 重启退避基数（默认 1000ms，翻倍到 30s 封顶）与 SIGKILL 兜底时长（默认 3000ms）。
   *
   * <p>只为测试而开放：真实退避一轮要跑满 31 秒、杀卡死子进程要等 3 秒，单测里既慢又
   * 掩盖不住时序问题。生产路径一律用默认值。
   */
  restartBaseMs?: number;
  killTimeoutMs?: number;
}

/** 重启退避：1s 起翻倍，封顶 30s；一分钟内超过 5 次就停手并报错。 */
const RESTART_MAX_MS = 30000;
const RESTART_WINDOW_MS = 60000;
const RESTART_LIMIT = 5;

export class Forwarder {
  private child: ChildProcess | undefined;
  private command: Extract<WorkerCommand, { type: 'start' }> | undefined;
  private stopping = false;
  /**
   * 正在**主动**杀子进程（`start()` 的换新、`stop()` 的收摊）。
   *
   * <p>没有这个标志时 `start()` 会自伤：它先 `kill()` 再 `spawn()`，而 kill 期间
   * `stopping` 是 false（那不是「停止服务」，是「换一个」），退出事件于是被当成意外崩溃，
   * 额外排一次退避重启 —— 结果是**同时跑两个 worker**，两套隧道抢同一个 hostId。
   */
  private killing = false;
  private restarts: number[] = [];
  private timer: NodeJS.Timeout | undefined;
  private failures = 0;
  private state = 'starting';
  private cookieReady = false;
  /**
   * Bumped on every fork so two generations of the worker can be told apart (v2 §E3).
   *
   * <p>`kill()` waits for `exit`, but the wait has a 3s ceiling and a blocked child
   * does not answer `stop`. Without a generation number the relay — which routes by
   * host id — would happily mix frames from the dying child with the new one's, and a
   * pulse carrying an old `latestSeq` looks exactly like a phone that fell behind.
   * Starts at 0 and increments before each spawn; only the child that reports the
   * highest value is allowed to own the tunnel.
   */
  private workerEpoch = 0;
  private readonly restartBaseMs: number;
  private readonly killTimeoutMs: number;

  constructor(private readonly options: ForwarderOptions) {
    this.restartBaseMs = options.restartBaseMs ?? 1000;
    this.killTimeoutMs = options.killTimeoutMs ?? 3000;
  }

  /** 当前状态（UI 的 `status()` 直接读）。 */
  status(): { state: string; cookieReady: boolean } {
    return { state: this.state, cookieReady: this.cookieReady };
  }

  /** 子进程 pid；为 null 表示转发层此刻**不存在**（没起来/已退出/正在退避）。 */
  pid(): number | null {
    return this.child?.pid ?? null;
  }

  /** The generation number stamped onto the current (or most recent) child. Read by the
   *  UI so an operator can tell a hot-reloaded worker from a stale one. */
  epoch(): number {
    return this.workerEpoch;
  }

  /**
   * 起一轮。重复调用是安全的：先停旧的再起新的（设置页保存后会走这条路）。
   */
  async start(command: Extract<WorkerCommand, { type: 'start' }>): Promise<void> {
    this.command = command;
    this.stopping = false;
    this.failures = 0;
    this.restarts = [];
    await this.kill();
    this.spawn();
  }

  /** 停止并等子进程真的退出。 */
  async stop(): Promise<void> {
    this.stopping = true;
    this.command = undefined;
    clearTimeout(this.timer);
    this.timer = undefined;
    await this.kill();
    this.state = 'disabled';
    this.cookieReady = false;
  }

  /**
   * 直接下发一份 cookie（父进程刚兑换好的那份）。
   *
   * <p>启动路径上父进程本来就要先兑换一次 cookie 才能做 upgrade 探测，这一份顺手给子进程，
   * 省掉它「起来 → 发现没 cookie → need-cookie → 父进程再兑换」的一个来回。子进程还没起来
   * 时调用是安全的：命令会排队，`spawn()` 之后随 `start` 一起到的 `cookie` 也会覆盖它。
   */
  pushCookie(cookie: string): void {
    this.cookieReady = !!cookie;
    this.send({ type: 'cookie', cookie });
  }

  private spawn(): void {
    const command = this.command;
    // `this.child` 非空说明已经有一个在跑：重复 `spawn()` 只可能来自重启定时器与
    // 手动 `start()` 撞车，多起一个就是两条隧道抢同一个 hostId，必须挡住。
    if (!command || this.stopping || this.child) return;
    const child = fork(this.options.workerFile, [], {
      // `silent` 让子进程的 stdout/stderr 也走管道：否则它会直接写到宿主的终端上，
      // 而那些输出在桌面宿主里根本看不见（stdio 被 Chromium 接管），日志等于丢失。
      silent: true,
      // `ELECTRON_RUN_AS_NODE` 必须显式钉死：DSH 桌面宿主是 Electron，`process.execPath`
      // 指向的是 Electron 二进制。这个变量一旦丢失（宿主自己的启动方式不同、或某些打包
      // 路径把它清掉），fork 出来的就不是 Node 进程而是一个要开窗口的 Electron 应用——
      // 表现是子进程立刻退出、桌面多一个空窗口。设成 '1' 对纯 Node 宿主是无害的。
      env: { ...process.env, ...(process.versions.electron ? { ELECTRON_RUN_AS_NODE: '1' } : {}) },
      // 源码模式（tsx 直跑 .ts）必须继承父进程的 loader，生产构建下这里是空数组。
      execArgv: this.options.execArgv ?? [],
    });
    this.child = child;
    child.on('message', (raw: unknown) => this.onMessage(raw as WorkerEvent));
    // 子进程崩溃时这两路都会来，统一交给 exit 处理，避免重复重启。
    child.on('error', error => this.options.onLog(`worker error: ${error.message}`));
    child.on('exit', (code, signal) => this.onExit(code, signal));
    for (const stream of [child.stdout, child.stderr]) {
      stream?.on('data', (chunk: Buffer) => {
        // 子进程的 stderr 只会来自未捕获的底层错误（我们自己的异常都走了 `fatal`），
        // 所以原样转成宿主日志，不要静默丢弃。
        const text = chunk.toString('utf8').trim();
        if (text) this.options.onLog(`worker stderr: ${text.slice(0, 400)}`);
      });
    }
    // fork 之后立刻把启动参数递过去：Node 的 IPC 管道会替我们排队，不必等 `ready`
    // （等 `ready` 反而会漏掉「子进程一起来就崩」那种最需要参数现场的情况）。
    //
    // The epoch is stamped here rather than at the call site: every caller that can
    // spawn (initial start, restart backoff, settings save) goes through this one line,
    // so a new generation cannot be forgotten.
    child.send({ ...command, workerEpoch: ++this.workerEpoch });
  }

  private onMessage(event: WorkerEvent): void {
    switch (event?.type) {
      case 'state':
        this.state = event.state;
        this.cookieReady = event.cookieReady;
        this.options.onState(this.state);
        break;
      case 'log':
        this.options.onLog(event.message);
        break;
      case 'need-cookie':
        // 父进程兑换后下发；失败也只是让子进程那一次请求拿 503，不影响后续。
        void this.options.getCookie().then(
          cookie => this.send({ type: 'cookie', cookie }),
          () => this.options.onLog('cookie exchange failed'),
        );
        break;
      case 'fatal':
        this.options.onLog(`worker fatal: ${event.error}`);
        break;
      case 'ready':
      case 'stopped':
      default:
        break;
    }
  }

  private onExit(code: number | null, signal: string | null): void {
    const child = this.child;
    this.child = undefined;
    this.cookieReady = false;
    // `stop()` / `start()` 主动杀的不算故障。
    if (this.stopping || this.killing || !this.command) return;
    if (child?.exitCode === 0 && code === 0) return;
    const now = Date.now();
    this.restarts = this.restarts.filter(at => now - at < RESTART_WINDOW_MS);
    if (this.restarts.length >= RESTART_LIMIT) {
      this.state = 'degraded';
      this.options.onState(this.state);
      this.options.onLog(`worker 一分钟内重启 ${this.restarts.length} 次，停止重试；最后一次 exit=${code ?? signal}`);
      return;
    }
    this.restarts.push(now);
    const delay = Math.min(this.restartBaseMs * 2 ** this.failures++, RESTART_MAX_MS);
    this.options.onLog(`worker exited (code=${code ?? signal})，${delay}ms 后重启`);
    this.state = 'connecting';
    this.options.onState(this.state);
    this.timer = setTimeout(() => this.spawn(), delay);
    this.timer.unref?.();
  }

  private send(command: WorkerCommand): void {
    try {
      this.child?.send(command);
    } catch {
      /* 管道已断：exit 事件会接上重启用逻辑 */
    }
  }

  private kill(): Promise<void> {
    const child = this.child;
    if (!child) return Promise.resolve();
    this.child = undefined;
    return new Promise<void>(resolve => {
      this.killing = true;
      const done = () => { this.killing = false; resolve(); };
      // SIGKILL 兜底：`stop` 命令走不通（子进程卡在自己的事件循环里）时不能一直等。
      const timer = setTimeout(() => {
        try { child.kill('SIGKILL'); } catch { /* 已经没了 */ }
        done();
      }, this.killTimeoutMs);
      timer.unref?.();
      child.once('exit', () => { clearTimeout(timer); done(); });
      try { child.send({ type: 'stop' } satisfies WorkerCommand); } catch { /* 管道已断 */ }
    });
  }
}
