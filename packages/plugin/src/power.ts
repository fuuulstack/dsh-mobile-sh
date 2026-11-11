/**
 * 「手机连着时别让电脑睡着」——持有系统防休眠断言。
 *
 * <p>**为什么必须放在宿主主进程**：断言属于「一个活着的进程」，而插件的转发层
 * （`worker.ts`）是 `fork()` 出来的纯 Node 子进程。本模块挂在 `lifecycle.ts` 上
 * （它就跑在宿主主进程里），子进程完全不知情。
 *
 * <p>**两条通道**（[detectBlocker] 依次尝试，能用就用）：
 * ① `electron.powerSaveBlocker`——最正统，但**目前取不到**：DSH 插件宿主进程是以
 *    `ELECTRON_RUN_AS_NODE=1` 启动的（`ps -Eww -p <host>` 可见），该模式下 Electron
 *    不注入 `'electron'` 内置模块，`require('electron')` 只会去 node_modules 找一个
 *    名叫 electron 的包然后失败（实测 `Cannot find module 'electron'`，
 *    `globalThis.require('electron')` 得到 null）。见 [detectPowerSaveBlocker]。
 * ② 系统命令兜底（[createSpawnBlocker]）：macOS 用 `caffeinate -i -w <pid>`、
 *    Linux 用 `systemd-inhibit --what=idle`。子进程用 `-w <本进程 pid>` 绑定生命周期，
 *    **宿主一死它自己就退**——比在任何语言里写 release 都更可靠的崩溃兜底。
 *
 * <p>这里选 `prevent-app-suspension` 而不是 `prevent-display-sleep`：要的是「系统不休眠、
 * 隧道不断」，屏幕该黑就黑——手机遥控器的使用场景里人根本不在电脑前，亮着屏只是白耗电。
 *
 * <p>**崩溃兜底（2026-10-29 用户明确要求）**：绝不能让「插件挂了、电脑再也不休眠」。
 * 四道保险，缺一不可：
 * ① 断言活不过宿主进程——`electron.powerSaveBlocker` 是 `IOPMAssertion` /
 *    `SetThreadExecutionState`，天然绑定进程；子进程通道则用 `caffeinate -w <pid>`，
 *    宿主 `kill -9`、崩溃、断电重启都不会留下残影；
 * ② `process.on('exit')` 里再同步 `stop()` 一次，覆盖正常退出与 `process.exit()`；
 * ③ **只在隧道真的 ready 时持有**：一旦落回 unpaired/degraded，宽限期
 *    （[DEFAULT_GRACE_MS]）到点自动放掉——进程还在但功能已经死了，这是唯一「卡住」的形态；
 * ④ 每一次电源 API / 子进程调用都包在 try/catch 里，取不到就当作不支持（页面显示为不可用），
 *    绝不能因为电源 API 抛错把插件主流程带崩。
 *
 * <p>另外：`start()` 返回的 id 必须立刻存下来（`this.held`），否则一旦在拿到 id 之后、
 * 存下来之前抛错，这条断言就再也没人能释放。
 */

import { spawn as spawnProcess } from 'node:child_process';
import { existsSync } from 'node:fs';
import { createRequire } from 'node:module';

/** `electron.powerSaveBlocker` 的最小面（不引 electron 类型，第三方插件拿不到它）。 */
export interface PowerSaveBlockerLike {
  /** 返回本次断言的 id；取负值表示没起来。 */
  start(type: 'prevent-app-suspension' | 'prevent-display-sleep'): number;
  stop(id: number): boolean;
  isStarted(id: number): boolean;
}

/** 断言类型：阻止系统休眠（但允许息屏）。 */
export type PowerSaveType = 'prevent-app-suspension' | 'prevent-display-sleep';

/** 隧道掉线后还能再撑多久才放掉断言（重连握手、插件热重载都在秒级完成）。 */
export const DEFAULT_GRACE_MS = 180_000;

/** 可注入的时间源与定时器，单测里全部替换掉（不要用真定时器等 3 分钟）。 */
export interface PowerSaveTimers {
  set(fn: () => void, ms: number): unknown;
  clear(handle: unknown): void;
}

export interface PowerSaveOptions {
  /** electron 的 powerSaveBlocker；取不到（非 Electron 宿主）时整个功能不可用。 */
  readonly blocker?: PowerSaveBlockerLike | undefined;
  readonly onLog?: (message: string) => void;
  /** 只用于挂 `exit` 钩子；默认 `process`。 */
  readonly proc?: Pick<NodeJS.Process, 'on' | 'off'> | undefined;
  readonly graceMs?: number;
  readonly type?: PowerSaveType;
  readonly timers?: PowerSaveTimers;
}

/** 设置页/状态栏读的那一小块。 */
export interface PowerSaveView {
  /** 用户的意图（设置文件里的开关）。 */
  readonly enabled: boolean;
  /** 当前是否真的持有系统断言。 */
  readonly active: boolean;
  /** 当前宿主是否具备这个能力（拿不到 Electron API 时为 false）。 */
  readonly supported: boolean;
}

const defaultTimers: PowerSaveTimers = {
  set: (fn, ms) => { const handle = setTimeout(fn, ms); handle.unref?.(); return handle; },
  clear: (handle) => clearTimeout(handle as NodeJS.Timeout),
};

const describe = (error: unknown): string => (error instanceof Error ? error.message : String(error));

/**
 * 防休眠断言的持有者。
 *
 * <p>唯一入口是 [setEnabled]（用户开关）与 [noteState]（隧道状态变化），两者都会收敛到
 * [acquire]/[release]。任何一条路径上都先判 `enabled && ready && !disposed`，
 * 所以不存在「关了开关却还持有」或「隧道早断了还持有」的中间态。
 */
export class PowerSaveKeeper {
  private readonly blocker: PowerSaveBlockerLike | undefined;
  private readonly onLog: (message: string) => void;
  private readonly proc: Pick<NodeJS.Process, 'on' | 'off'> | undefined;
  private readonly graceMs: number;
  private readonly type: PowerSaveType;
  private readonly timers: PowerSaveTimers;
  /** 已持有的断言 id；undefined = 没持有。 */
  private held: number | undefined;
  private enabled = false;
  private ready = false;
  private disposed = false;
  private grace: unknown;
  private graceArmed = false;
  private exitHooked = false;
  private readonly onExit = (): void => { this.releaseSync(); };

  constructor(options: PowerSaveOptions = {}) {
    this.blocker = options.blocker;
    this.onLog = options.onLog ?? (() => {});
    this.proc = options.proc ?? (typeof process === 'undefined' ? undefined : process);
    this.graceMs = options.graceMs ?? DEFAULT_GRACE_MS;
    this.type = options.type ?? 'prevent-app-suspension';
    this.timers = options.timers ?? defaultTimers;
  }

  /** 宿主是否有这个能力。没有时页面把开关画成不可用，而不是假装生效。 */
  get supported(): boolean {
    return this.blocker !== undefined;
  }

  view(): PowerSaveView {
    return { enabled: this.enabled, active: this.held !== undefined, supported: this.supported };
  }

  /**
   * 应用用户的开关意图。
   *
   * <p>语义是「记住意图 + 尽力而为」：现在不具备条件（隧道没连上）就只记下来，
   * 等 [noteState] 报 ready 时再真正持有。
   */
  setEnabled(enabled: boolean): PowerSaveView {
    if (this.disposed) return this.view();
    this.enabled = enabled;
    if (!enabled) this.release('用户关闭了防休眠');
    else if (this.ready) this.acquire();
    return this.view();
  }

  /**
   * 隧道状态变化。
   *
   * @param state 插件对外状态（`ready` 是全链路连上中继且握手完成）
   */
  noteState(state: string): void {
    if (this.disposed) return;
    this.ready = state === 'ready';
    if (!this.enabled) return;
    if (this.ready) {
      this.clearGrace();
      this.acquire();
      return;
    }
    this.armGrace();
  }

  /** 插件卸载：关掉开关并释放。可以被重复调用。 */
  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.enabled = false;
    this.release('插件已卸载');
  }

  private acquire(): void {
    if (this.held !== undefined || this.disposed || !this.enabled || !this.ready) return;
    const blocker = this.blocker;
    if (!blocker) return;
    try {
      // 必须在 start() 之后**立刻**赋值：晚一步抛错就再也没有释放这条断言的凭据。
      const id = blocker.start(this.type);
      if (typeof id !== 'number' || id < 0) throw new Error(`powerSaveBlocker.start() returned ${String(id)}`);
      this.held = id;
      this.hookExit();
      this.onLog(`防休眠已开启（句柄=${id}，${this.type === 'prevent-app-suspension' ? '阻止系统休眠' : '阻止息屏'}）`);
    } catch (error) {
      this.onLog(`防休眠开启失败（宿主的电源 API 不可用）：${describe(error)}`);
    }
  }

  private release(reason: string): void {
    this.clearGrace();
    const id = this.held;
    if (id === undefined) return;
    this.held = undefined;
    this.unhookExit();
    const blocker = this.blocker;
    if (!blocker) return;
    try {
      // isStarted 只是省一次无谓的 stop：它自己也可能抛（宿主已经清过这个 id），
      // 那就直接尝试 stop —— stop 对未知 id 返回 false，不会有害。
      let started = true;
      try { started = blocker.isStarted(id); } catch { started = true; }
      if (started) blocker.stop(id);
      this.onLog(`防休眠已释放（句柄=${id}，${reason}）`);
    } catch (error) {
      this.onLog(`防休眠释放失败（断言会在进程退出时由系统回收）：${describe(error)}`);
    }
  }

  /** `exit` 回调里只能做同步、不能抛的事，也不打日志（stdout 可能已经关了）。 */
  private releaseSync(): void {
    const id = this.held;
    if (id === undefined) return;
    this.held = undefined;
    this.unhookExit();
    try { this.blocker?.stop(id); } catch { /* 退出路径上尽力而为 */ }
  }

  private armGrace(): void {
    if (this.graceArmed || this.disposed) return;
    this.graceArmed = true;
    this.grace = this.timers.set(() => {
      this.graceArmed = false;
      this.grace = undefined;
      if (this.disposed || !this.enabled || this.ready) return;
      this.release('隧道长时间未连上');
    }, this.graceMs);
  }

  private clearGrace(): void {
    if (!this.graceArmed) return;
    this.graceArmed = false;
    const handle = this.grace;
    this.grace = undefined;
    if (handle !== undefined) this.timers.clear(handle);
  }

  private hookExit(): void {
    if (this.exitHooked || !this.proc) return;
    this.exitHooked = true;
    try { this.proc.on('exit', this.onExit); } catch { this.exitHooked = false; }
  }

  private unhookExit(): void {
    if (!this.exitHooked || !this.proc) return;
    this.exitHooked = false;
    try { this.proc.off('exit', this.onExit); } catch { /* 退出路径上忽略 */ }
  }
}

/**
 * 从宿主进程里取 `electron.powerSaveBlocker`。
 *
 * <p>**不能用静态 `require('electron')`**：插件是打包产物（esbuild bundle），安装目录里
 * 没有 electron 这个包，静态引用会让打包期就报 `Could not resolve "electron"`。
 * 只能把 require 变成「运行期变量再调用」，esbuild 才会原样留下这次调用。
 *
 * <p>Electron 主进程会拦截 `Module._load` 对 `'electron'` 的请求，所以 `createRequire`
 * 出来的 require 一样能拿到（它与宿主的 require 走同一条 `Module._load`）。
 *
 * @returns 可用时返回 powerSaveBlocker；非 Electron 宿主返回 undefined（功能降级，不报错）
 */
export function detectPowerSaveBlocker(onLog?: (message: string) => void): PowerSaveBlockerLike | undefined {
  const electron = process.versions?.electron;
  if (!electron) return undefined;
  const notes: string[] = [];
  // ELECTRON_RUN_AS_NODE=1 时没有 'electron' 内置模块，先记一条判据再继续尝试，
  // 免得现场只看到一句「Cannot find module」。
  if (process.env.ELECTRON_RUN_AS_NODE === '1') notes.push('ELECTRON_RUN_AS_NODE=1（进程以纯 Node 模式运行）');
  const load = createRequire(import.meta.url) as (id: string) => unknown;
  const attempts: Array<[string, () => unknown]> = [
    ['createRequire', () => load('electron')],
    ['globalThis.require', () => (globalThis as { require?: (id: string) => unknown }).require?.('electron')],
  ];
  for (const [label, attempt] of attempts) {
    try {
      const mod = attempt() as { powerSaveBlocker?: PowerSaveBlockerLike } | undefined;
      const blocker = mod?.powerSaveBlocker;
      if (blocker && typeof blocker.start === 'function' && typeof blocker.stop === 'function') return blocker;
      notes.push(`${label}: 拿到了模块但没有 powerSaveBlocker（keys=${mod && typeof mod === 'object' ? Object.keys(mod).slice(0, 6).join(',') || '空' : String(mod)}）`);
    } catch (error) {
      notes.push(`${label}: ${error instanceof Error ? error.message : String(error)}`);
    }
  }
  // 这一行是现场唯一的判据：能不能防休眠取决于宿主有没有电源接口。
  onLog?.(`powerSaveBlocker 不可用（electron=${electron}）：${notes.join(' | ')}`);
  return undefined;
}


/** 系统命令兜底的抽象：起一个「只要我还活着就别睡」的看门狗子进程。 */
export interface SpawnBlockerOptions {
  /** 要绑定的 pid（默认本进程）；子进程用它判活，宿主一死立即退出。 */
  readonly pid?: number | undefined;
  readonly platform?: NodeJS.Platform | undefined;
  /** 注入 spawn（单测用）；签名与 `node:child_process.spawn` 一致但更窄。 */
  readonly spawn?: ((command: string, args: readonly string[]) => SpawnedChild) | undefined;
  /** 注入「命令是否存在」的判断（单测用）。 */
  readonly exists?: ((path: string) => boolean) | undefined;
  readonly onLog?: (message: string) => void;
}

/** spawn 出来的子进程的最小面。 */
export interface SpawnedChild {
  readonly pid?: number | undefined;
  killed?: boolean | undefined;
  kill(signal?: NodeJS.Signals): boolean;
  once(event: 'error', listener: (error: Error) => void): unknown;
  once(event: 'exit', listener: (code: number | null, signal: NodeJS.Signals | null) => void): unknown;
  unref(): void;
}

const PLATFORM_COMMANDS: Partial<Record<NodeJS.Platform, { path: string; args: (pid: number) => string[] }>> = {
  // -i = 阻止「用户空闲导致的系统休眠」（屏幕可以灭），-w <pid> = 目标进程退出即结束。
  darwin: { path: '/usr/bin/caffeinate', args: (pid) => ['-i', '-w', String(pid)] },
  // systemd-inhibit 会把命令当子进程跑；--what=idle 只拦空闲睡眠。给的命令是 sleep，
  // 存活时间由我们 stop() 时 kill 掉决定。
  linux: { path: '/usr/bin/systemd-inhibit', args: () => ['--what=idle', '--why=DSH 手机遥控器已连接', 'sleep', 'infinity'] },
};

/**
 * 用系统命令持有防休眠断言（Electron API 取不到时的兜底）。
 *
 * <p>语义完全对齐 [PowerSaveBlockerLike]：`start()` 起子进程并返回它的 pid（负数 = 失败），
 * `stop(id)` 杀掉那个 pid。之所以用 pid 当 id，是因为它天然唯一且可跨进程核对。
 *
 * <p>**这三条是这个兜底能成立的前提**：
 * ① `caffeinate -w <pid>` / `systemd-inhibit` 的子进程都绑着宿主进程——宿主没了它们自己退，
 *    `kill -9` 也不会留下「电脑再也不休眠」的残影；
 * ② 命令不存在（精简系统、容器里）时 `exists()` 判假直接返回 undefined，功能降级为不可用；
 * ③ 子进程 `unref()`，不拖住宿主退出。
 */
export function createSpawnBlocker(options: SpawnBlockerOptions = {}): PowerSaveBlockerLike | undefined {
  const platform = options.platform ?? process.platform;
  const command = PLATFORM_COMMANDS[platform];
  if (!command) return undefined;
  const exists = options.exists ?? existsSync;
  if (!exists(command.path)) {
    options.onLog?.(`系统防休眠命令不存在：${command.path}（平台 ${platform}）`);
    return undefined;
  }
  const pid = options.pid ?? process.pid;
  const spawn = options.spawn ?? defaultSpawn;
  const children = new Map<number, SpawnedChild>();
  return {
    start(type) {
      // 类型由命令参数决定，这里只有 -i（= prevent-app-suspension）可用；
      // prevent-display-sleep 需要 -d，收敛在 args() 里即可。
      const args = type === 'prevent-display-sleep' && platform === 'darwin'
        ? ['-d', '-w', String(pid)]
        : command.args(pid);
      const child = spawn(command.path, args);
      if (child.pid === undefined) return -1;
      // 用子进程自己的 pid 当句柄：日志里能直接对上 `pgrep -fl caffeinate` 的那一行。
      const id = child.pid;
      children.set(id, child);
      child.once('error', (error) => {
        children.delete(id);
        options.onLog?.(`系统防休眠命令失败：${error.message}`);
      });
      child.once('exit', () => { children.delete(id); });
      child.unref();
      return id;
    },
    stop(id) {
      const child = children.get(id);
      if (!child) return false;
      children.delete(id);
      try { return child.kill(); } catch { return false; }
    },
    isStarted(id) { return children.has(id); },
  };
}

function defaultSpawn(command: string, args: readonly string[]): SpawnedChild {
  return spawnProcess(command, [...args], { stdio: 'ignore', detached: false }) as unknown as SpawnedChild;
}

/**
 * 依次尝试「Electron API → 系统命令」，返回第一个能用的防休眠通道。
 *
 * <p>顺序有意义：Electron API 是进程内断言，最干净；系统命令要多一个子进程，
 * 只在宿主环境确实拿不到 Electron API 时才用（当前 DSH 就是这种情况）。
 */
export function detectBlocker(
  onLog?: (message: string) => void,
  /** 仅测试用：替换两条通道的探测，免得单测真去 require electron / 真起进程。 */
  detect: { readonly electron?: () => PowerSaveBlockerLike | undefined; readonly spawned?: () => PowerSaveBlockerLike | undefined } = {},
): PowerSaveBlockerLike | undefined {
  const electron = (detect.electron ?? (() => detectPowerSaveBlocker(onLog)))();
  if (electron) return electron;
  const spawned = (detect.spawned ?? (() => createSpawnBlocker({ onLog })))();
  if (spawned) {
    const where = process.platform === 'darwin' ? 'caffeinate' : process.platform === 'linux' ? 'systemd-inhibit' : 'systemd-inhibit';
    onLog?.(`防休眠改用系统命令通道（${where}），断言随宿主进程退出自动释放`);
  }
  return spawned;
}
