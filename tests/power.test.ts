import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import {
  PowerSaveKeeper, createSpawnBlocker, detectBlocker, DEFAULT_GRACE_MS,
  type PowerSaveBlockerLike, type SpawnedChild,
} from '../packages/plugin/src/power.js';

/**
 * 假的时间源：所有定时器都进一个 Map，由测试手动推进。
 *
 * <p>真定时器等 3 分钟不现实，而宽限期又是「进程还活着但隧道死了不能一直扣着电脑」
 * 的唯一保险——它必须被真实测到。
 */
function fakeTimers() {
  let nextId = 1;
  const pending = new Map<number, () => void>();
  return {
    timers: {
      set(fn: () => void, _ms: number) { const id = nextId++; pending.set(id, fn); return id; },
      clear(handle: unknown) { pending.delete(handle as number); },
    },
    /** 推进到所有待触发的定时器都跑一遍。 */
    fire() { const fns = [...pending.values()]; pending.clear(); for (const fn of fns) fn(); },
    get size() { return pending.size; },
  };
}

/** 记录每次 start/stop 的假 blocker。 */
function fakeBlocker(options: { startThrows?: boolean; stopThrows?: boolean; id?: number; isStartedThrows?: boolean } = {}) {
  const calls: string[] = [];
  const started = new Set<number>();
  let nextId = options.id ?? 42;
  const blocker: PowerSaveBlockerLike = {
    start(type) {
      calls.push(`start:${type}`);
      if (options.startThrows) throw new Error('no power api');
      const id = nextId++;
      started.add(id);
      return id;
    },
    stop(id) {
      calls.push(`stop:${id}`);
      if (options.stopThrows) throw new Error('stop failed');
      return started.delete(id);
    },
    isStarted(id) {
      if (options.isStartedThrows) throw new Error('isStarted failed');
      return started.has(id);
    },
  };
  return { blocker, calls, started };
}

const fakeProc = () => new EventEmitter() as unknown as Pick<NodeJS.Process, 'on' | 'off'> & EventEmitter;

test('disabled by default: nothing is held even when the tunnel is ready', () => {
  const { blocker, calls } = fakeBlocker();
  const keeper = new PowerSaveKeeper({ blocker });
  assert.deepEqual(keeper.view(), { enabled: false, active: false, supported: true });
  keeper.noteState('ready');
  assert.deepEqual(calls, [], '未开启时不该碰系统的电源接口');
  assert.equal(keeper.view().active, false);
});

test('enabling while the tunnel is ready takes the assertion immediately', () => {
  const { blocker, calls } = fakeBlocker({ id: 7 });
  const keeper = new PowerSaveKeeper({ blocker });
  keeper.setEnabled(true);
  assert.deepEqual(calls, [], '还没连上就不该持有断言');
  keeper.noteState('ready');
  assert.deepEqual(calls, ['start:prevent-app-suspension']);
  assert.deepEqual(keeper.view(), { enabled: true, active: true, supported: true });
});

test('turning the switch off releases the assertion right away', () => {
  const { blocker, calls } = fakeBlocker({ id: 7 });
  const keeper = new PowerSaveKeeper({ blocker });
  keeper.setEnabled(true);
  keeper.noteState('ready');
  keeper.setEnabled(false);
  assert.deepEqual(calls, ['start:prevent-app-suspension', 'stop:7']);
  assert.equal(keeper.view().active, false);
});

test('a crash path always releases: the process exit hook stops the blocker synchronously', () => {
  // 这是用户明确要求的那一条：程序崩了不能让电脑一直无法休眠。
  const { blocker, calls, started } = fakeBlocker({ id: 9 });
  const proc = fakeProc();
  const keeper = new PowerSaveKeeper({ blocker, proc });
  keeper.setEnabled(true);
  keeper.noteState('ready');
  assert.equal(started.has(9), true);
  proc.emit('exit');
  assert.deepEqual(calls, ['start:prevent-app-suspension', 'stop:9']);
  assert.equal(started.size, 0, '断言必须已被释放');
  assert.equal(keeper.view().active, false);
});

test('a tunnel that stays down gives the assertion back after the grace period', () => {
  // 「进程还活着但功能已经死了」是唯一会长期扣住电脑的形态，必须自动收手。
  const clock = fakeTimers();
  const { blocker, calls } = fakeBlocker({ id: 11 });
  const keeper = new PowerSaveKeeper({ blocker, timers: clock.timers, graceMs: DEFAULT_GRACE_MS });
  keeper.setEnabled(true);
  keeper.noteState('ready');
  keeper.noteState('degraded');
  assert.equal(clock.size, 1, '掉线后应当只排一个宽限定时器');
  assert.equal(keeper.view().active, true, '宽限期内不退让，避免重连抖动反复开关');
  clock.fire();
  assert.deepEqual(calls, ['start:prevent-app-suspension', 'stop:11']);
  assert.equal(keeper.view().active, false);
  assert.equal(keeper.view().enabled, true, '开关意图要保留，下次连上还能自动生效');
});

test('a tunnel that comes back inside the grace window cancels the release', () => {
  const clock = fakeTimers();
  const { blocker, calls } = fakeBlocker({ id: 13 });
  const keeper = new PowerSaveKeeper({ blocker, timers: clock.timers, graceMs: 1000 });
  keeper.setEnabled(true);
  keeper.noteState('ready');
  keeper.noteState('connecting');
  keeper.noteState('ready');
  assert.equal(clock.size, 0, '回到 ready 必须撤掉宽限定時器');
  clock.fire();
  assert.deepEqual(calls, ['start:prevent-app-suspension'], '已经连上了就不该再释放');
  assert.equal(keeper.view().active, true);
});

test('a throwing power API never breaks the plugin', () => {
  const { blocker } = fakeBlocker({ startThrows: true });
  const logs: string[] = [];
  const keeper = new PowerSaveKeeper({ blocker, onLog: (m) => logs.push(m) });
  keeper.setEnabled(true);
  keeper.noteState('ready'); // 不得抛出
  assert.equal(keeper.view().active, false);
  assert.match(logs.join('\n'), /防休眠开启失败/);
});

test('a blocker that reports a negative id is treated as failure and does not leak', () => {
  const { blocker, calls } = fakeBlocker({ id: -1 });
  const logs: string[] = [];
  const keeper = new PowerSaveKeeper({ blocker, onLog: (m) => logs.push(m) });
  keeper.setEnabled(true);
  keeper.noteState('ready');
  assert.equal(keeper.view().active, false);
  assert.match(logs.join('\n'), /returned -1/);
  keeper.setEnabled(false);
  assert.deepEqual(calls, ['start:prevent-app-suspension'], '没有拿到 id 就绝不该去 stop');
});

test('a throwing stop is swallowed: the assertion dies with the process anyway', () => {
  const { blocker } = fakeBlocker({ stopThrows: true });
  const logs: string[] = [];
  const keeper = new PowerSaveKeeper({ blocker, onLog: (m) => logs.push(m) });
  keeper.setEnabled(true);
  keeper.noteState('ready');
  keeper.setEnabled(false); // 不得抛出
  assert.equal(keeper.view().active, false);
  assert.match(logs.join('\n'), /防休眠释放失败/);
});

test('dispose releases and is idempotent', () => {
  const { blocker, calls } = fakeBlocker({ id: 21 });
  const keeper = new PowerSaveKeeper({ blocker });
  keeper.setEnabled(true);
  keeper.noteState('ready');
  keeper.dispose();
  keeper.dispose();
  keeper.setEnabled(true); // 卸载后再开也不得复活
  keeper.noteState('ready');
  assert.deepEqual(calls, ['start:prevent-app-suspension', 'stop:21']);
  assert.equal(keeper.view().enabled, false);
});

test('a host without the power API degrades instead of pretending', () => {
  const keeper = new PowerSaveKeeper({ blocker: undefined });
  assert.equal(keeper.supported, false);
  assert.doesNotThrow(() => keeper.setEnabled(true));
  keeper.noteState('ready');
  assert.deepEqual(keeper.view(), { enabled: true, active: false, supported: false });
});

test('isStarted throwing still lets the assertion be released', () => {
  // 宿主已经清过这个 id 时 isStarted 可能抛；这时必须退回直接 stop，否则断言永远漏。
  const { blocker, calls } = fakeBlocker({ id: 31, isStartedThrows: true });
  const keeper = new PowerSaveKeeper({ blocker });
  keeper.setEnabled(true);
  keeper.noteState('ready');
  keeper.setEnabled(false);
  assert.deepEqual(calls, ['start:prevent-app-suspension', 'stop:31']);
});

// —— 系统命令兜底通道（Electron API 取不到时的第二选择）——

/** 假 spawn：记录起过哪些命令，返回可被 kill / 可被判死的假子进程。 */
function fakeSpawn() {
  const calls: string[] = [];
  const live: Array<{ pid: number; exit: () => void; fail: (e: Error) => void }> = [];
  let nextPid = 9001;
  const spawn = (command: string, args: readonly string[]): SpawnedChild => {
    calls.push([command, ...args].join(' '));
    let onExit: (() => void) | undefined;
    let onError: ((e: Error) => void) | undefined;
    const pid = nextPid++;
    // 假子进程只要满足 spawn 通道实际用到的那几项；重载签名在这里用宽松实现再断言。
    const child = {
      pid,
      killed: false,
      kill() { child.killed = true; onExit?.(); return true; },
      once(event: string, listener: unknown) {
        if (event === 'error') onError = listener as (e: Error) => void;
        else onExit = listener as () => void;
        return child;
      },
      unref() { /* noop */ },
    } as unknown as SpawnedChild;
    live.push({ pid, exit: () => onExit?.(), fail: (e) => onError?.(e) });
    return child;
  };
  return { spawn, calls, live };
}

const okExists = () => true;

test('the spawn fallback binds the blocker to the host pid and dies with it', () => {
  const { spawn, calls } = fakeSpawn();
  const blocker = createSpawnBlocker({ platform: 'darwin', pid: 4242, spawn, exists: okExists });
  assert.ok(blocker, 'darwin 上应当有 caffeinate 通道');
  const id = blocker!.start('prevent-app-suspension');
  assert.ok(id > 0);
  // 句柄就是子进程自己的 pid：现场 `pgrep -fl caffeinate` 能直接对上这条日志。
  assert.equal(id, 9001);
  // -i = 阻止空闲休眠（屏幕可灭），-w 4242 = 宿主进程退出即结束。
  assert.deepEqual(calls, ['/usr/bin/caffeinate -i -w 4242']);
  assert.equal(blocker!.isStarted(id), true);
  assert.equal(blocker!.stop(id), true);
  assert.equal(blocker!.isStarted(id), false);
});

test('stop on an unknown id is a no-op, not a throw', () => {
  const { spawn } = fakeSpawn();
  const blocker = createSpawnBlocker({ platform: 'darwin', spawn, exists: okExists })!;
  assert.equal(blocker.stop(77), false);
});

test('a missing system command degrades to undefined instead of pretending', () => {
  const { spawn, calls } = fakeSpawn();
  const blocker = createSpawnBlocker({ platform: 'darwin', spawn, exists: () => false });
  assert.equal(blocker, undefined);
  assert.deepEqual(calls, [], '命令不存在时不该起任何进程');
});

test('an unsupported platform has no command fallback', () => {
  const { spawn } = fakeSpawn();
  assert.equal(createSpawnBlocker({ platform: 'win32', spawn, exists: okExists }), undefined);
});

test('the child exiting on its own keeps the bookkeeping honest', () => {
  const { spawn, live } = fakeSpawn();
  const blocker = createSpawnBlocker({ platform: 'darwin', spawn, exists: okExists })!;
  const id = blocker.start('prevent-app-suspension');
  // 宿主崩溃后 caffeinate 自己退出：这条断言必须从簿记里消失，否则之后 stop 会对着死 pid 干活。
  live[0]!.exit();
  assert.equal(blocker.isStarted(id), false);
});

test('detectBlocker prefers the electron api and only falls back when it is missing', () => {
  const electronLike = fakeBlocker({ id: 5 }).blocker;
  const { blocker: spawnedLike, calls: spawnCalls } = fakeBlocker({ id: 6 });
  const logs: string[] = [];

  // 有 Electron API：不得去建系统命令通道。
  let fallbackAsked = 0;
  const first = detectBlocker((m) => logs.push(m), {
    electron: () => electronLike,
    spawned: () => { fallbackAsked += 1; return spawnedLike; },
  });
  assert.equal(first, electronLike);
  assert.equal(fallbackAsked, 0, 'Electron 通道可用时不该建子进程通道');

  // 没有 Electron API（当前 DSH 的真实情况）：必须退到系统命令通道。
  const second = detectBlocker((m) => logs.push(m), {
    electron: () => undefined,
    spawned: () => spawnedLike,
  });
  assert.equal(second, spawnedLike);
  assert.deepEqual(spawnCalls, [], '构造通道本身不该启动断言');

  // 两条都不通：返回 undefined（页面显示「不可用」），绝不能抛。
  const third = detectBlocker((m) => logs.push(m), { electron: () => undefined, spawned: () => undefined });
  assert.equal(third, undefined);
});
