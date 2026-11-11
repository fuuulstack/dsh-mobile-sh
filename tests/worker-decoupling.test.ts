import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Forwarder, resolveWorkerFile } from '../packages/plugin/src/supervisor.js';

/**
 * 中期-13（解耦）的守护进程本身。
 *
 * <p>这些测试**不需要**一个可用的中继或宿主：它们只验证 `Forwarder` 作为「子进程守护」
 * 的行为契约——能不能把参数递进去、崩了会不会按退避重启、主动停止会不会留下幽灵进程。
 * 真正的转发行为由 `plugin.test.ts` / `integration.test.ts` 覆盖。
 */

/** 一个最小的假子进程：按命令回事件，不作任何网络 I/O。 */
function fakeWorker(dir: string, body: string): string {
  const file = join(dir, 'fake-worker.mjs');
  writeFileSync(file, body);
  return file;
}

function tempDir(t: { after: (fn: () => void) => void }): string {
  const dir = mkdtempSync(join(tmpdir(), 'mobile-sh-worker-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  return dir;
}

function waitFor(check: () => boolean, timeoutMs = 5000, message = 'condition not met'): Promise<void> {
  const start = Date.now();
  return new Promise<void>((resolve, reject) => {
    const tick = () => {
      if (check()) return resolve();
      if (Date.now() - start > timeoutMs) return reject(new Error(message));
      setTimeout(tick, 10);
    };
    tick();
  });
}

/** 收集一次运行的日志/状态，供断言用。 */
function recorder() {
  const logs: string[] = [];
  const states: string[] = [];
  return { logs, states, onLog: (m: string) => logs.push(m), onState: (s: string) => states.push(s) };
}

test('resolveWorkerFile prefers the built worker and falls back to source with the loader', () => {
  // 指向一个真实存在 worker.js 的目录（本仓库的 dist 目录，跑过 build 就有）。
  const built = resolveWorkerFile(new URL('../packages/plugin/dist/index.js', import.meta.url).href);
  if (built.file.endsWith('worker.js')) {
    assert.deepEqual(built.execArgv, [], '构建产物不需要任何 loader 参数');
  }
  // 指向源码目录：dist 里没有 worker.js 时必须退到 worker.ts，并把 loader 带下去，
  // 否则子进程拿到一个 .ts 路径却不会编译，起来就崩。
  const source = resolveWorkerFile(new URL('../packages/plugin/src/lifecycle.ts', import.meta.url).href);
  assert.ok(source.file.endsWith('worker.ts'), `expected worker.ts, got ${source.file}`);
  assert.deepEqual(source.execArgv, process.execArgv, '源码模式必须继承父进程的 loader');
});

test('the start command reaches the worker and its state changes reach the parent', async t => {
  const dir = tempDir(t);
  const file = fakeWorker(dir, `
    process.on('message', m => {
      if (m.type === 'start') {
        // 把收到的关键参数回显一条日志，用来证明 IPC 真的带过去了。
        process.send({ type: 'log', message: 'start:' + m.hostId + ':' + m.maxStreams });
        process.send({ type: 'state', state: 'connected', cookieReady: false });
      }
      if (m.type === 'cookie') process.send({ type: 'state', state: 'ready', cookieReady: true });
      if (m.type === 'stop') process.exit(0);
    });
  `);
  const rec = recorder();
  const forwarder = new Forwarder({ workerFile: file, execArgv: [], getCookie: async () => 'c', onLog: rec.onLog, onState: rec.onState });
  t.after(() => forwarder.stop());

  await forwarder.start({
    type: 'start', relayUrl: 'wss://relay.test/tunnel', relayOrigin: 'https://relay.test', hostId: 'h1', hostTunnelToken: 't',
    upstreamOrigin: 'http://127.0.0.1:1', upgradePaths: ['/api/remote.mux'], maxBody: 1024, maxStreams: 8,
    connectTimeoutMs: 1000, httpIdleTimeoutMs: 1000,
  });
  await waitFor(() => rec.states.includes('connected'), 5000, 'worker state never reached the parent');
  assert.ok(rec.logs.some(l => l === 'start:h1:8'), `start params did not arrive: ${rec.logs.join(' | ')}`);

  // cookie 下行：状态要回传，cookieReady 也要跟着变。
  forwarder.pushCookie('cookie-value');
  await waitFor(() => rec.states.includes('ready'), 5000, 'cookie command did not reach the worker');
  assert.equal(forwarder.status().cookieReady, true);
});

test('need-cookie asks the parent to exchange one and pushes it back', async t => {
  const dir = tempDir(t);
  const file = fakeWorker(dir, `
    process.on('message', m => {
      if (m.type === 'start') process.send({ type: 'need-cookie' });
      if (m.type === 'cookie') process.send({ type: 'log', message: 'got:' + m.cookie });
      if (m.type === 'stop') process.exit(0);
    });
  `);
  const rec = recorder();
  let exchanges = 0;
  const forwarder = new Forwarder({
    workerFile: file, execArgv: [], onLog: rec.onLog, onState: rec.onState,
    getCookie: async () => { exchanges++; return 'exchanged'; },
  });
  t.after(() => forwarder.stop());

  await forwarder.start({
    type: 'start', relayUrl: 'wss://relay.test/tunnel', relayOrigin: 'https://relay.test', hostId: 'h', hostTunnelToken: 't',
    upstreamOrigin: 'http://127.0.0.1:1', upgradePaths: [], maxBody: 1, maxStreams: 1, connectTimeoutMs: 1, httpIdleTimeoutMs: 1,
  });
  await waitFor(() => rec.logs.includes('got:exchanged'), 5000, 'cookie handshake did not round-trip');
  assert.equal(exchanges, 1);
});

test('a crashing worker restarts with backoff and gives up after the window limit', async t => {
  const dir = tempDir(t);
  // 一起来就崩：验证父进程的重启路径（这是子进程化最实际的风险点）。
  const file = fakeWorker(dir, `process.exit(1);`);
  const rec = recorder();
  // 退避基数压到 10ms：真实退避一轮要 1+2+4+8+16=31 秒，单测里没有价值。
  const forwarder = new Forwarder({ workerFile: file, execArgv: [], getCookie: async () => 'c', onLog: rec.onLog, onState: rec.onState, restartBaseMs: 10 });
  t.after(() => forwarder.stop());

  await forwarder.start({
    type: 'start', relayUrl: 'wss://relay.test/tunnel', relayOrigin: 'https://relay.test', hostId: 'h', hostTunnelToken: 't',
    upstreamOrigin: 'http://127.0.0.1:1', upgradePaths: [], maxBody: 1, maxStreams: 1, connectTimeoutMs: 1, httpIdleTimeoutMs: 1,
  });
  // 一分钟内 5 次封顶：等到它报 degraded 就说明退避与止损都在。
  await waitFor(() => rec.states.includes('degraded'), 20000, 'worker never gave up after repeated crashes');
  assert.ok(rec.logs.some(l => l.includes('停止重试')), `expected a give-up log, got: ${rec.logs.join(' | ')}`);
});

test('stop kills the worker and leaves no live pid behind', async t => {
  const dir = tempDir(t);
  // 这个假 worker 故意**不**处理 stop：用来验证 kill() 的 SIGKILL 兜底确实能收掉卡住的子进程。
  const file = fakeWorker(dir, `process.on('message', () => {}); setInterval(() => {}, 1000);`);
  const rec = recorder();
  // SIGKILL 兜底压到 200ms：这个用例等的就是「子进程不响应 stop」那条路。
  const forwarder = new Forwarder({ workerFile: file, execArgv: [], getCookie: async () => 'c', onLog: rec.onLog, onState: rec.onState, killTimeoutMs: 200 });
  t.after(() => forwarder.stop());

  await forwarder.start({
    type: 'start', relayUrl: 'wss://relay.test/tunnel', relayOrigin: 'https://relay.test', hostId: 'h', hostTunnelToken: 't',
    upstreamOrigin: 'http://127.0.0.1:1', upgradePaths: [], maxBody: 1, maxStreams: 1, connectTimeoutMs: 1, httpIdleTimeoutMs: 1,
  });
  await waitFor(() => forwarder.pid() !== null, 5000, 'worker never started');
  const pid = forwarder.pid()!;
  await forwarder.stop();
  assert.equal(forwarder.pid(), null, 'forwarder still reports a pid after stop');
  assert.equal(forwarder.status().state, 'disabled');
  // 进程真的没了（SIGKILL 兜底路径也算通过）。
  await waitFor(() => { try { process.kill(pid, 0); return false; } catch { return true; } }, 5000, 'worker process survived stop()');
});

test('a missing worker file surfaces as a restart loop, never as a silent no-op', async t => {
  const rec = recorder();
  const forwarder = new Forwarder({
    workerFile: join(tmpdir(), 'definitely-not-a-worker-9de1f0.js'), execArgv: [], getCookie: async () => 'c', onLog: rec.onLog, onState: rec.onState,
  });
  t.after(() => forwarder.stop());
  await forwarder.start({
    type: 'start', relayUrl: 'wss://relay.test/tunnel', relayOrigin: 'https://relay.test', hostId: 'h', hostTunnelToken: 't',
    upstreamOrigin: 'http://127.0.0.1:1', upgradePaths: [], maxBody: 1, maxStreams: 1, connectTimeoutMs: 1, httpIdleTimeoutMs: 1,
  });
  await waitFor(() => rec.logs.some(l => l.includes('worker exited')), 8000, 'a missing worker file was swallowed silently');
  assert.equal(forwarder.pid(), null);
});

test('calling start() twice replaces the worker instead of running two', async t => {
  const dir = tempDir(t);
  // 每个实例把自己的 pid 报上来：测试数一数同时活着几个。
  const file = fakeWorker(dir, `
    process.on('message', m => {
      if (m.type === 'start') process.send({ type: 'log', message: 'up:' + process.pid });
      if (m.type === 'stop') process.exit(0);
    });
    setInterval(() => {}, 1000);
  `);
  const rec = recorder();
  // 退避基数压到 30ms：如果「主动换新」被误判成崩溃，误排的重启会很快真的执行，
  // 而不是老老实实等 1 秒 —— 否则这个用例会在定时器触发前就断言完，假绿。
  const forwarder = new Forwarder({ workerFile: file, execArgv: [], getCookie: async () => 'c', onLog: rec.onLog, onState: rec.onState, restartBaseMs: 30 });
  t.after(() => forwarder.stop());
  const command = {
    type: 'start' as const, relayUrl: 'wss://relay.test/tunnel', relayOrigin: 'https://relay.test', hostId: 'h', hostTunnelToken: 't',
    upstreamOrigin: 'http://127.0.0.1:1', upgradePaths: [], maxBody: 1, maxStreams: 1, connectTimeoutMs: 1, httpIdleTimeoutMs: 1,
  };

  await forwarder.start(command);
  await waitFor(() => rec.logs.some(l => l.startsWith('up:')), 5000, 'first worker never came up');
  const first = Number(rec.logs.find(l => l.startsWith('up:'))!.slice(3));

  // 第二次 start 是设置页「保存」会走的路径：旧进程必须被换掉，不能并存。
  await forwarder.start(command);
  await waitFor(() => rec.logs.filter(l => l.startsWith('up:')).length === 2, 5000, 'second worker never came up');

  // 等过退避窗口：误排的重启此刻已经执行，重复的子进程会现形。
  await new Promise(r => setTimeout(r, 400));
  const ups = rec.logs.filter(l => l.startsWith('up:')).map(l => Number(l.slice(3)));
  const alive = (pid: number) => { try { process.kill(pid, 0); return true; } catch { return false; } };
  assert.equal(alive(first), false, `the first worker (pid ${first}) is still alive alongside the new one`);
  assert.equal(ups.length, 2, `expected exactly one replacement, got workers: ${ups.join(',')}`);
  assert.equal(alive(ups[1]!), true, 'the replacement worker is not running');
  assert.equal(forwarder.pid(), ups[1]);
});
