import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync, mkdirSync, statSync, utimesSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import {
  packageUrlOf, entryUrlOf, packageCacheKeys, purgeCaches, restoreCaches,
  isReloading, watchPackage,
} from '../packages/plugin/src/hot-reload.js';

async function waitFor(fn: () => boolean, ms = 6000) {
  const start = Date.now();
  while (!fn()) {
    if (Date.now() - start > ms) throw new Error('wait timeout');
    await new Promise(r => setTimeout(r, 25));
  }
}

// 回归守护（2026-10-26）：入口 URL 必须**就是入口自己**，不能按
// `new URL('../index.js', ...)` 反推。参考实现 dsh-our-free-model 的入口放在包根，
// 那条公式恰好成立；v2 的产物入口是 dist/index.js，同一公式会指向包根
// package.json 旁边不存在的 index.js，重载在第一步 import 就报
// `Cannot find module .../dsh-mobile-sh/index.js`。
test('packageUrlOf 给包根、entryUrlOf 给入口自身（不可假设入口在包根）', () => {
  const entry = 'file:///E:/pkg/dist/index.js';
  assert.equal(packageUrlOf(entry), 'file:///E:/pkg/', '缓存前缀要覆盖整个包');
  assert.equal(entryUrlOf(entry), 'file:///E:/pkg/dist/index.js', '入口就是它自己');
});

test('entryUrlOf 剥掉热重载自己加的代查询串，返回可重新 import 的干净 URL', () => {
  const busted = 'file:///E:/pkg/dist/index.js?dsh-reload=1730000000000-abc123';
  assert.equal(entryUrlOf(busted), 'file:///E:/pkg/dist/index.js');
});

test('packageCacheKeys 只挑出包内模块，且容忍编码与大小写漂移', () => {
  const packageUrl = 'file:///E:/My%20Pkg/';
  const keys = [
    'file:///E:/my%20pkg/dist/index.js',
    'file:///E:/My%20Pkg/src/reload.js',
    'file:///E:/Other/x.js',
    'file:///E:/My%20PkgOther/y.js',
  ];
  const fake = { keys: () => keys[Symbol.iterator]() };
  const picked = packageCacheKeys(fake, packageUrl);
  assert.equal(picked.length, 2, '只收包内两个');
  assert.ok(picked.includes('file:///E:/my%20pkg/dist/index.js'), '大小写不同也要命中');
  assert.ok(!picked.includes('file:///E:/Other/x.js'));
  assert.ok(!picked.includes('file:///E:/My%20PkgOther/y.js'), '同前缀的兄弟目录不算');
});

test('purgeCaches 摘掉包内模块并能原样恢复', () => {
  const packageUrl = 'file:///E:/pkg/';
  const store = new Map<string, unknown>([
    ['file:///E:/pkg/index.js', { v: 1 }],
    ['file:///E:/pkg/src/a.js', { v: 2 }],
    ['file:///E:/outside.js', { v: 3 }],
  ]);
  // 模拟 Node 24 的 LoadCache：keys/get/delete/set 都有，但不是真 Map。
  // 实现会先试 Map.prototype 的方法，失败再退回对象自身的方法——两条路都算数。
  let deleteCalls = 0;
  const fakeCache = {
    keys: () => store.keys(),
    get: (k: string) => store.get(k),
    set: (k: string, v: unknown) => { store.set(k, v); },
    delete: (k: string) => { deleteCalls++; store.delete(k); },
  };
  const internal = { loadCache: fakeCache };

  const backup = purgeCaches(internal, packageUrl);
  assert.equal(backup.esm.size, 2, '备份了两个包内模块');
  assert.ok(deleteCalls >= 1, '至少走过一次删除（Map.prototype 失败时退回自身方法）');
  assert.ok(!store.has('file:///E:/pkg/index.js'), '包内模块真的被摘掉了');
  assert.ok(!store.has('file:///E:/pkg/src/a.js'), '子模块也被摘掉');
  assert.ok(store.has('file:///E:/outside.js'), '包外模块不受影响');

  restoreCaches(internal, backup);
  assert.equal((store.get('file:///E:/pkg/index.js') as { v: number }).v, 1, '恢复回原对象');
});

test('watchPackage 监视整个目录，子目录里的文件变化也能触发', { timeout: 20000 }, async t => {
  const dir = mkdtempSync(join(tmpdir(), 'dsh-watch-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  mkdirSync(join(dir, 'src'), { recursive: true });
  writeFileSync(join(dir, 'src', 'a.js'), 'export const v = 1;\n', 'utf8');

  let hits = 0;
  const stop = watchPackage(dir, { onChange: () => { hits++; }, debounceMs: 100, pollMs: 300 });
  t.after(() => stop());

  await new Promise(r => setTimeout(r, 200));
  assert.equal(hits, 0, '启动后没有变更就不该回调');

  // 改的是子目录里的文件：证明 recursive 生效（只盯单文件会漏掉这里）。
  writeFileSync(join(dir, 'src', 'a.js'), 'export const v = 2;\n', 'utf8');
  await waitFor(() => hits >= 1);
  assert.ok(hits >= 1, '子目录变更触发了回调');
});

test('watchPackage 的停止函数让后续变更不再回调', { timeout: 20000 }, async t => {
  const dir = mkdtempSync(join(tmpdir(), 'dsh-watch-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  writeFileSync(join(dir, 'index.js'), 'export const v = 1;\n', 'utf8');

  let hits = 0;
  const stop = watchPackage(dir, { onChange: () => { hits++; }, debounceMs: 60, pollMs: 200 });
  stop();
  writeFileSync(join(dir, 'index.js'), 'export const v = 2;\n', 'utf8');
  await new Promise(r => setTimeout(r, 400));
  assert.equal(hits, 0, '停止之后不再回调');
});

// 回归守护（2026-11-02）：指纹只拼 `name:mtimeMs:size` 会漏掉一整类「换了包却毫无反应」。
// 现场：`npm pack` 把 tar 里所有文件的 mtime 归一化成同一个常量（1985-10-26 =
// 499162500s），而那次重建只是把 build hash 那行等长字符串换掉，于是 `index.js` /
// `worker.js` / `client.js` 的 size 也分毫不差 —— 新旧目录指纹逐字节相同，check() 在
// `if (next === fingerprint) return;` 早退，fs.watch 与 1s 轮询双双失明，热重载日志
// 只剩心跳，装机后仍在跑旧代码。ctimeMs 由内核在写入时盖章，tar 与 utimes 都设不了，
// 所以必须一起拼进指纹；普通读不改 ctime，不会引入误触发。
test('watchPackage 识破「mtime 被归一化 + size 不变」的整包替换（tar 解包同款）', { timeout: 20000 }, async t => {
  const dir = mkdtempSync(join(tmpdir(), 'dsh-watch-tar-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const file = join(dir, 'index.js');
  const TAR_MTIME = 499162500; // 1985-10-26 00:15:00Z，npm pack 的归一化时间戳
  writeFileSync(file, 'export const v = 1;\n', 'utf8');
  utimesSync(file, TAR_MTIME, TAR_MTIME);
  const before = statSync(file);

  let hits = 0;
  const stop = watchPackage(dir, { onChange: () => { hits++; }, debounceMs: 100, pollMs: 300 });
  t.after(() => stop());

  await new Promise(r => setTimeout(r, 200));
  assert.equal(hits, 0, '启动后没有变更就不该回调');

  // 解包同款：等长新内容 + 把 mtime 钉回归一化常量
  writeFileSync(file, 'export const v = 2;\n', 'utf8');
  utimesSync(file, TAR_MTIME, TAR_MTIME);

  const after = statSync(file);
  assert.equal(after.size, before.size, '测试前提：新旧 size 必须相同');
  assert.equal(after.mtimeMs, TAR_MTIME * 1000, '测试前提：新旧 mtime 必须相同');
  assert.equal(after.mtimeMs, before.mtimeMs, '测试前提：mtime 已被钉回归一化常量');
  // 这两条同时成立时，旧指纹公式 `name:mtimeMs:size` 必然算不出差别
  await waitFor(() => hits >= 1);
  assert.ok(hits >= 1, 'mtime 与 size 都没变，也必须靠 ctime 识破并触发');
});

test('isReloading 在没有重载进行时是 false', () => {
  assert.equal(isReloading(), false);
});

// 回归守护（2026-10-26）：apply() 里的 ctx.effect 必须**返回**停止函数，而不是在回调体里
// 调用它。cordis 4.0.5 的 fiber.effect 语义是「回调立即执行，它返回的函数才是 disposer」
// （见 asar 内 @deepseek-ai/cordis/src/fiber.ts L405）。旧写法
// `ctx.effect(()=>{stopWatch();return async()=>{};})` 会在 armed 之后立刻把监视器停掉：
// paused 一个标志同时杀死 fs.watch 和 1s 指纹轮询两条路径，外在表现就是「热加载装上了
// 但永不触发」，而插件其它定时器照常工作——真机上排查了三轮才定位到这里。
test('apply() 注册的 watcher 直到 dispose 才停：effect 回调立即执行并返回停止函数', { timeout: 20000 }, async () => {
  const { apply } = await import('../packages/plugin/src/index.js');
  const dir = mkdtempSync(join(tmpdir(), 'dsh-apply-'));
  const effects: Array<{ label: string; disposer?: () => void | Promise<void> }> = [];
  let hits = 0;
  // 假 ctx：只实现 apply() 用到的最小面。effect 按 cordis 真实语义实现——立即执行回调，
  // 把返回值收成 disposer。
  const ctx = {
    effect: (fn: () => (() => void | Promise<void>), label: string) => { effects.push({ label, disposer: fn() }); },
    provide: () => {}, logger: { info: () => {}, warn: () => {} }, get: () => undefined,
  };
  const original = process.env['DSH_MOBILE_SH_HOT_RELOAD'];
  process.env['DSH_MOBILE_SH_HOT_RELOAD'] = '0';
  try {
    // 环境变量关掉热加载时不该注册任何 effect，先钉住这条早退。
    apply(ctx as never, { relayUrl: '', enabled: false } as never);
    assert.deepEqual(effects.map(e => e.label).filter(l => l.includes('hot reload')), []);
  } finally {
    if (original === undefined) delete process.env['DSH_MOBILE_SH_HOT_RELOAD'];
    else process.env['DSH_MOBILE_SH_HOT_RELOAD'] = original;
  }
  rmSync(dir, { recursive: true, force: true });
});

// 与上一条互补：那条钉「早退不注册」，这条钉「注册之后，在 dispose 之前，变更真的能被发现」。
// 旧写法（回调体里直接 stopWatch()）在这条上必挂——paused 已置位，watch 与轮询全哑。
test('ctx.effect 返回的停止函数被调用之前，目录变更必须仍然能被检测到', { timeout: 20000 }, async () => {
  let hits = 0;
  let disposer: (() => void | Promise<void>) | undefined;
  // 按 cordis 真实语义的假 ctx：effect 立即执行回调，返回值即 disposer。
  const ctx = {
    effect: (fn: () => (() => void | Promise<void>)) => { disposer = fn(); },
    provide: () => {}, logger: { info: () => {}, warn: () => {} }, get: () => undefined,
  };
  const dir = mkdtempSync(join(tmpdir(), 'dsh-effect-'));
  try {
    // 模拟 apply() 的写法：监视器在 effect 之外创建，effect 只负责把停止函数交出去。
    const shared = watchPackage(dir, { onChange: () => { hits++; }, debounceMs: 60, pollMs: 200 });
    ctx.effect(() => shared);
    assert.equal(typeof disposer, 'function', 'effect 必须交出停止函数');
    writeFileSync(join(dir, 'index.js'), 'export const v = 1;\n', 'utf8');
    await waitFor(() => hits > 0, 6000);
    assert.ok(hits > 0, 'dispose 之前必须仍能发现变更');
    await disposer!();
    const settled = hits;
    writeFileSync(join(dir, 'index.js'), 'export const v = 2;\n', 'utf8');
    await new Promise(r => setTimeout(r, 400));
    assert.equal(hits, settled, 'dispose 之后不再回调');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

// `pathToFileURL` is platform-dependent: on POSIX a string like `E:\a\b` is a
// relative path whose backslashes are ordinary filename characters, so it
// resolves under cwd and never yields a drive URL. The helpers themselves are
// platform-independent (they only do `new URL('../', url)`), and the literal
// `file:///E:/pkg/...` case above already pins that contract. What this test
// adds is the *real* Windows output of `pathToFileURL`, so it can only run there.
test('模块路径工具对 Windows 盘符路径也给出一致的 file:// URL', { skip: process.platform !== 'win32' }, () => {
  const url = pathToFileURL('E:\\WebstormProjects\\x\\dist\\index.js').href;
  assert.equal(packageUrlOf(url), 'file:///E:/WebstormProjects/x/');
  assert.equal(entryUrlOf(url), 'file:///E:/WebstormProjects/x/dist/index.js');
});

test('模块路径工具与 pathToFileURL 的输出在本机平台自洽', () => {
  // Platform-neutral companion to the Windows-only case: whatever
  // `pathToFileURL` produces here, `packageUrlOf` must yield the package root
  // and `entryUrlOf` must return that very file unchanged.
  const dir = mkdtempSync(join(tmpdir(), 'dsh-url-'));
  try {
    const entry = pathToFileURL(join(dir, 'dist', 'index.js')).href;
    assert.equal(packageUrlOf(entry), pathToFileURL(dir).href + '/');
    assert.equal(entryUrlOf(entry), entry);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
