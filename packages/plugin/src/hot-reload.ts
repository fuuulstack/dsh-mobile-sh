import fs from 'node:fs';
import { NAMING } from '../../core/src/index.js';
import { fileURLToPath } from 'node:url';
import { createRequire, registerHooks } from 'node:module';

/**
 * 插件自热重载：把磁盘上的新代码换成正在跑的插件，不重启应用。
 *
 * <p>**镜像 DSH 内核 HMR 的官方路径**（参考 `dsh-our-free-model/src/reload.js`，
 * 那是同类插件里已经在生产上跑通的实现）。内核自带的 HMR 故意不开文件监视，
 * 也没有「立刻重载这个插件」的 API；它的 `partialReload` 定义了正确顺序，
 * 这里逐步对照同一批原语实现：
 *
 * <ol>
 *   <li>快照当前 runtime 的 fibers 与它们的 config；</li>
 *   <li>把包里所有模块从 Node 的 ESM `internal.loadCache` 和 CJS `require.cache`
 *       里**备份并摘掉**（Node 24 的 LoadCache 是 Map 子类，但它的 `delete`
 *       只清一个 type 槽，必须用 `Map.prototype.delete` 直接操作）；</li>
 *   <li>重新 import 入口文件，让新源码真正被求值；</li>
 *   <li>`registry.delete(旧plugin)` 停掉旧 fibers，再用 `registry.plugin(新模块, config)`
 *       重新注册，并把 loader entry 重新挂到新 fiber 上；</li>
 *   <li>任一步失败就恢复缓存与旧注册，并如实报告「旧运行时是否恢复成功」。</li>
 * </ol>
 *
 * <p>**为什么不能用 `loader.update(id)`**：它比的是 entry 配置，配置没变就不会
 * 重新 import；即使重新 import，ESM 缓存也会返回旧模块。真正的换代码必须走
 * 上面第 2-4 步。
 *
 * <p>重载跑在普通 timer 上，不在被 dispose 的 fiber 内部——否则 dispose 会把
 * 交换过程本身一起取消掉。
 */

/** 全局单飞：同时只允许一次重载。 */
let inFlight: Promise<ReloadResult> | null = null;
const hooksKey = Symbol.for(NAMING.reloadHooksKey);
const reloadHooks: Map<string, unknown> = ((globalThis as Record<symbol, unknown>)[hooksKey] ??=
  new Map()) as Map<string, unknown>;

export interface ReloadResult {
  ok: boolean;
  error?: string;
  /** 失败时旧运行时是否成功恢复。 */
  restored?: boolean;
  fibers?: number;
}

/** 重载进行中？用于挡住重叠的交换。 */
export function isReloading(): boolean {
  return inFlight !== null;
}

/**
 * 包根目录（`file://`，结尾带斜杠）。
 *
 * <p>`../` 以**文件**为基准解析，因此 `<pkg>/dist/index.js` 得到 `<pkg>/`——正是
 * 缓存前缀需要的包根（`dist/` 与 `src/` 的键都落在这棵树下）。实测日志里
 * `package=.../dsh-mobile-sh/`，口径正确。
 */
export function packageUrlOf(entryModuleUrl: string): string {
  return new URL('../', entryModuleUrl).href;
}

/**
 * 入口模块**自身**的绝对 `file://` URL（去掉查询串与哈希）。
 *
 * <p>不要用 `new URL('../index.js', ...)` 反推：那是参考实现 `dsh-our-free-model`
 * 的布局约定（入口 = 包根 `index.js`）。v2 的产物入口是 `dist/index.js`，同一公式
 * 会指向包根，于是重新 import 报 `Cannot find module .../dsh-mobile-sh/index.js`
 * （2026-10-26 实测踩坑：检测层活了，重载却在第一步 import 就失败）。
 * 入口就是当前模块自己，唯一要处理的是剥掉热重载自己加的代查询串。
 */
export function entryUrlOf(entryModuleUrl: string): string {
  const url = new URL(entryModuleUrl);
  url.search = '';
  url.hash = '';
  return url.href;
}

/**
 * 把入口的「代」传给它依赖的每个子模块。
 *
 * <p>包的产物是多文件（dist/index.js 由 esbuild 打成单文件，但 src 下还有独立
 * 模块被 entry 静态引用）。只给入口加查询串没用——子模块仍会命中旧缓存。
 * 这个钩子把 parentURL 上的代透传给它解析出的每个包内模块。
 */
function enableFreshDependencies(packageUrl: string): void {
  if (reloadHooks.has(packageUrl)) return;
  const packagePath = fs.realpathSync(fileURLToPath(packageUrl));
  const hook = registerHooks({
    resolve(specifier, context, nextResolve) {
      const resolved = nextResolve(specifier, context);
      const parentPath = context.parentURL?.startsWith('file:')
        ? fs.realpathSync(fileURLToPath(new URL(context.parentURL)))
        : null;
      const resolvedPath = resolved.url.startsWith('file:')
        ? fs.realpathSync(fileURLToPath(new URL(resolved.url)))
        : null;
      if (parentPath === null || resolvedPath === null
        || !parentPath.startsWith(packagePath) || !resolvedPath.startsWith(packagePath)) return resolved;
      const generation = new URL(context.parentURL!).searchParams.get('dsh-reload');
      if (generation === null) return resolved;
      const target = new URL(resolved.url);
      target.searchParams.set('dsh-reload', generation);
      return { ...resolved, url: target.href };
    },
  });
  reloadHooks.set(packageUrl, hook);
}

/** 包目录内的所有 loadCache 键。比较时兼容大小写与百分号编码漂移。 */
export function packageCacheKeys(loadCache: { keys: () => Iterable<string> }, packageUrl: string): string[] {
  const base = decodeURIComponent(packageUrl).replace(/\/+$/, '').toLowerCase() + '/';
  const keys: string[] = [];
  for (const key of loadCache.keys()) {
    if (typeof key !== 'string') continue;
    let normalized: string;
    try { normalized = decodeURIComponent(key).toLowerCase(); } catch { normalized = key.toLowerCase(); }
    if (normalized.startsWith(base)) keys.push(key);
  }
  return keys;
}

/** 备份并从两个模块缓存里摘掉包内的模块。 */
export function purgeCaches(
  internal: { loadCache?: unknown } | undefined,
  packageUrl: string,
): { esm: Map<string, unknown>; cjs: Map<string, object> } {
  const backup = { esm: new Map<string, unknown>(), cjs: new Map<string, object>() };
  const cache = internal?.loadCache as {
    keys?: () => Iterable<string>;
    get?: (k: string) => unknown;
    delete?: (k: string) => unknown;
  } | undefined;
  if (cache && typeof cache.keys === 'function' && typeof cache.get === 'function'
    && typeof cache.delete === 'function') {
    const read = (key: string) => {
      try { return Map.prototype.get.call(cache, key); } catch { try { return cache.get!(key); } catch { return undefined; } }
    };
    const drop = (key: string) => {
      // Node 24 的 LoadCache.delete 只清一个 type 槽，必须走 Map.prototype。
      try { Map.prototype.delete.call(cache, key); return; } catch { /* 不是真 Map，退回自身方法 */ }
      try { cache.delete!(key); } catch { /* best effort */ }
    };
    for (const key of packageCacheKeys(cache as { keys: () => Iterable<string> }, packageUrl)) {
      backup.esm.set(key, read(key));
      drop(key);
    }
  }
  const require = createRequire(import.meta.url) as { cache: Record<string, unknown> };
  for (const key of backup.esm.keys()) {
    try {
      const filePath = fileURLToPath(key);
      if (require.cache[filePath] !== undefined) {
        backup.cjs.set(filePath, require.cache[filePath] as object);
        delete require.cache[filePath];
      }
    } catch {
      // 非 file: URL，CJS 侧无事可做
    }
  }
  return backup;
}

/** 把 purge 的备份放回去（回滚路径）。 */
export function restoreCaches(
  internal: { loadCache?: unknown } | undefined,
  backup: { esm: Map<string, unknown>; cjs: Map<string, object> },
): void {
  const cache = internal?.loadCache as { set?: (k: string, v: unknown) => unknown } | undefined;
  for (const [key, job] of backup.esm) {
    try { Map.prototype.set.call(cache, key, job); } catch { try { cache?.set!(key, job); } catch { /* best effort */ } }
  }
  const require = createRequire(import.meta.url) as { cache: Record<string, unknown> };
  for (const [filePath, module] of backup.cjs) require.cache[filePath] = module;
}

/** 重载时实际拿到的最小上下文形状（只声明用到的成员）。 */
export interface ReloadContext {
  loader?: {
    internal?: { loadCache?: unknown };
    unwrapExports?: (exports: unknown) => { apply?: unknown };
    import?: (url: string, stack: () => unknown[]) => Promise<unknown>;
  };
  registry?: {
    get: (plugin: unknown) => { fibers?: Iterable<unknown>; Config?: unknown; name?: unknown } | undefined;
    delete: (plugin: unknown) => unknown;
    plugin: (plugin: unknown, config: unknown) => { ctx: { fiber: unknown } };
  };
  fiber?: { runtime?: { callback?: unknown }; parent?: unknown; uid?: unknown; _config?: unknown };
}

/**
 * 就地重载调用方的插件实例。
 *
 * @returns 成功时 `{ok:true, fibers}`；失败时 `{ok:false, error, restored}`。
 */
export function selfReload(ctx: ReloadContext, options: { log?: (m: string) => void } = {}): Promise<ReloadResult> {
  if (inFlight !== null) return inFlight;
  const log = options.log ?? (() => {});
  inFlight = (async (): Promise<ReloadResult> => {
    const packageUrl = packageUrlOf(import.meta.url);
    const entryUrl = entryUrlOf(import.meta.url);
    const loader = ctx.loader;
    const registry = ctx.registry;
    log(`[1] loader=${!!loader} registry=${!!registry} package=${packageUrl.slice(-40)}`);
    if (!loader || !registry) return { ok: false, error: '上下文没有 loader/registry', restored: false };

    // 插件身份就是 registry 用作 key 的那个 exports 对象。
    const callback = ctx.fiber?.runtime?.callback;
    const previous = callback as { apply?: unknown } | undefined;
    log(`[2] fiber=${!!ctx.fiber} runtime.callback=${typeof callback}`);
    if (!previous) {
      // 把 fiber 上的实际形状记下来，下次一眼看出该取哪个字段。
      let shape = '';
      try {
        const f = ctx.fiber as unknown as Record<string, unknown>;
        shape = `fiberKeys=${Object.keys(f).slice(0, 20).join(',')}`;
        const rt = f.runtime as Record<string, unknown> | undefined;
        if (rt) shape += ` runtimeKeys=${Object.keys(rt).slice(0, 20).join(',')}`;
      } catch { /* 反射失败不影响主流程 */ }
      return { ok: false, error: `拿不到当前 runtime（插件不是按常规方式注册的）${shape}`, restored: false };
    }
    const runtime = registry.get(previous as unknown);
    type FiberLike = {
      entry?: { fiber?: { uid?: unknown } } | undefined;
      uid?: unknown;
      inject?: unknown;
      _config?: unknown;
      await: () => Promise<unknown>;
      parent: { registry: { plugin: (p: unknown, c: unknown) => { ctx: { fiber: unknown } } } };
    };
    const fiberList = [...((runtime?.fibers ?? []) as Iterable<FiberLike>)];
    log(`[3] runtime=${!!runtime} fibers=${fiberList.length}`);
    if (!runtime) {
      // registry 里有这个 callback 吗？拿出来比对，避免「key 不同」被当成「没注册」。
      let probe = '';
      try {
        const reg = registry as unknown as { keys?: () => Iterable<unknown> };
        if (typeof reg.keys === 'function') probe = `registryKeys=${[...reg.keys()].length}`;
      } catch { /* best effort */ }
      return { ok: false, error: `registry 里找不到这个插件 ${probe}`, restored: false };
    }
    const fibers = fiberList.map(fiber => {
      const entry = fiber.entry?.fiber?.uid === fiber.uid ? fiber.entry : undefined;
      const plugin = typeof previous === 'function'
        ? { apply: previous, inject: { ...(fiber.inject as object) }, Config: runtime?.Config, name: runtime?.name }
        : previous;
      return { fiber, entry, plugin, config: entry === undefined ? fiber._config : (entry as { options?: { config?: unknown } }).options?.config };
    });
    if (fibers.length === 0) return { ok: false, error: 'runtime 没有活着的 fiber', restored: false };

    const internal = loader.internal;
    log(`[4] loader.internal=${!!internal} loadCache=${!!internal?.loadCache}`);
    const backup = purgeCaches(internal, packageUrl);
    log(`[5] 摘掉 ${backup.esm.size} 个 ESM + ${backup.cjs.size} 个 CJS 缓存`);
    if (backup.esm.size === 0) {
      // 一个都没摘掉：loadCache 的键格式和我猜的不一样。把真实键样本记下来。
      let sample = '';
      try {
        const cache = internal?.loadCache as { keys?: () => Iterable<unknown> } | undefined;
        if (cache && typeof cache.keys === 'function') {
          sample = [...cache.keys()].slice(0, 5).map(k => String(k).slice(-60)).join(' | ');
        } else sample = 'loadCache 没有 keys()';
      } catch (e) { sample = `读取失败 ${errText(e)}`; }
      log(`[5b] 缓存键样本: ${sample}`);
      log(`[5c] 期望前缀: ${decodeURIComponent(packageUrl).slice(-60)}`);
    }

    const importFresh = async (url: string) =>
      loader.unwrapExports!(await loader.import!(url, () => [])) as { apply?: unknown };
    let replacement: { apply?: unknown };
    let busted = false;
    try {
      replacement = await importFresh(entryUrl);
      log(`[6] 首次 import 完成，apply=${typeof replacement?.apply} 与旧模块同一对象=${replacement === previous}`);
      if (!replacement || typeof replacement.apply !== 'function') throw new Error('新模块没有导出 apply()');
      if (backup.esm.size === 0 || replacement === previous || replacement.apply === previous
        || (typeof previous.apply === 'function' && replacement.apply === previous.apply)) {
        // 缓存摘除在这个内核上没生效（loadCache 形状随 Node 版本变过）。
        // 用「代」查询串强制入口与其依赖都重新求值。
        enableFreshDependencies(packageUrl);
        const freshUrl = new URL(entryUrl);
        freshUrl.searchParams.set('dsh-reload', `${Date.now()}-${Math.random().toString(36).slice(2)}`);
        replacement = await importFresh(freshUrl.href);
        busted = true;
        log(`[7] cache-bust 后 apply=${typeof replacement?.apply} 仍是旧模块=${replacement === previous}`);
        if (!replacement || typeof replacement.apply !== 'function') throw new Error('新模块没有导出 apply()');
        if (replacement === previous || replacement.apply === previous
          || (typeof previous.apply === 'function' && replacement.apply === previous.apply)) {
          throw new Error('清缓存后拿到的仍是旧插件');
        }
      }
    } catch (error) {
      restoreCaches(internal, backup);
      log(`[FAIL] 重新导入失败: ${errText(error)}`);
      return { ok: false, error: `重新导入失败，旧代码仍在跑（${errText(error)}）`, restored: true };
    }
    log(`[8] 已拿到新模块（cache-bust=${busted}），开始换注册`);

    try {
      registry.delete(previous);
      await Promise.all(fibers.map(({ fiber }) => fiber.await()));
      log(`[9] 旧 fiber 已释放`);
    } catch (error) {
      // 旧 fiber 无论报什么都已经停了；下面的重新注册仍是正确动作，
      // 所以这里只记日志、不向上抛，以免跳过注册。
      log(`[9] 旧 fiber 释放报告 ${errText(error)}`);
    }

    try {
      const created: FiberLike[] = [];
      for (const { fiber, entry, config } of fibers) {
        const registered = fiber.parent.registry.plugin(replacement, config);
        const fresh = registered.ctx.fiber as FiberLike;
        if (entry !== undefined) {
          (fresh as { entry?: unknown }).entry = entry;
          (entry as { fiber?: unknown }).fiber = fresh;
        }
        created.push(fresh);
      }
      await Promise.all(created.map(fiber => fiber.await()));
      log(`[10] 完成，重新注册 ${created.length} 个 fiber`);
      return { ok: true, fibers: created.length };
    } catch (error) {
      log(`[10-FAIL] 新代码启动失败: ${errText(error)}`);
      // 新代码启动失败：缓存与注册都退回旧版本。
      restoreCaches(internal, backup);
      try { registry.delete(replacement); } catch { /* 可能压根没起来 */ }
      try {
        const restored: FiberLike[] = [];
        for (const { fiber, entry, plugin, config } of fibers) {
          const registered = fiber.parent.registry.plugin(plugin, config);
          const fresh = registered.ctx.fiber as FiberLike;
          if (entry !== undefined) {
            (fresh as { entry?: unknown }).entry = entry;
            (entry as { fiber?: unknown }).fiber = fresh;
          }
          restored.push(fresh);
        }
        await Promise.all(restored.map(fiber => fiber.await()));
        return { ok: false, error: `新代码启动失败，已恢复上一版本（${errText(error)}）`, restored: true };
      } catch (restoreError) {
        return {
          ok: false,
          error: `新代码启动失败（${errText(error)}）；运行时恢复也失败（${errText(restoreError)}）`,
          restored: false,
        };
      }
    }
  })();
  return inFlight.finally(() => { inFlight = null; });
}

/**
 * 监视包目录，任何源码变化都触发热重载。
 *
 * <p>默认关闭：生产安装不会改写自己的文件，设置页有显式重载按钮。这里给开发用。
 *
 * <p>**同时用 watch 与轮询**：`fs.watch(dir,{recursive:true})` 是快路径，但它在
 * Electron 主进程里实测**完全不触发**（同一段代码在独立 node 进程里正常）。只靠
 * watch 的表现就是「改了文件毫无反应」，所以再加一条按目录指纹的轮询兜底。
 * 两条路都汇到同一个 check()，谁先看到都算数。
 *
 * @returns 停止函数
 */
export function watchPackage(
  packageDir: string,
  hooks: { onChange: () => void; log?: (m: string) => void; debounceMs?: number; pollMs?: number },
): () => void {
  const log = hooks.log ?? (() => {});
  const debounceMs = hooks.debounceMs ?? 600;
  const pollMs = hooks.pollMs ?? 1000;
  let timer: NodeJS.Timeout | undefined;
  let poller: NodeJS.Timeout | undefined;
  let paused = false;
  let watcher: fs.FSWatcher | undefined;
  let fingerprint = fingerprintOf(packageDir);

  const check = (how: string) => {
    if (paused) return;
    const next = fingerprintOf(packageDir);
    if (next === fingerprint) return;
    fingerprint = next;
    log(`${how} 发现变更，触发热重载`);
    hooks.onChange();
  };

  try {
    watcher = fs.watch(packageDir, { recursive: true });
    watcher.on('change', () => {
      if (paused) return;
      if (timer) clearTimeout(timer);
      timer = setTimeout(() => { timer = undefined; check('watch'); }, debounceMs);
      timer.unref?.();
    });
    watcher.on('error', error => log(`watcher 出错（${errText(error)}）`));
  } catch (error) {
    log(`无法 watch ${packageDir}（${errText(error)}），改用轮询`);
  }

  // 兜底轮询：watch 在 Electron 主进程里不触发，这条才是实际生效的那条。
  // 用递归 setTimeout 而不是 setInterval：每次自己续命，宿主空闲时也不会被挂起。
  // 每 30 次 tick 记一条心跳，能确认「它真的在跑」而不刷屏。
  let ticks = 0;
  const tick = () => {
    if (paused) return;
    ticks++;
    if (ticks % 30 === 0) log(`轮询心跳 #${ticks}（间隔 ${pollMs}ms）`);
    check('轮询');
    poller = setTimeout(tick, pollMs);
    poller.unref?.();
  };
  poller = setTimeout(tick, pollMs);
  poller.unref?.();

  return () => {
    paused = true;
    if (timer) clearTimeout(timer);
    if (poller) clearTimeout(poller);
    try { watcher?.close(); } catch { /* 已关闭 */ }
  };
}

/**
 * 目录指纹：递归收集文件的名字 + mtime + size。
 *
 * <p>必须递归：多文件产物里子模块（`src/*.js`）改动也得触发，只看顶层会漏。
 * 递归 stat 有成本，但这是 1 秒一次、只在开发期开着的路径，不构成负担。
 * 跳过 node_modules 与点目录，深度设上限，避免扫到大树。
 */
function fingerprintOf(dir: string, depth = 0): string {
  if (depth > 3) return '';
  try {
    const entries = fs.readdirSync(dir, { withFileTypes: true });
    const parts: string[] = [];
    for (const entry of entries) {
      if (entry.name === 'node_modules' || entry.name.startsWith('.')) continue;
      const full = `${dir}/${entry.name}`;
      if (entry.isDirectory()) {
        const nested = fingerprintOf(full, depth + 1);
        if (nested) parts.push(nested);
        continue;
      }
      try {
        const st = fs.statSync(full);
        // ctimeMs 必须一起算：`npm pack` 会把 tar 内所有文件的 mtime 归一化成同一个
        // 常量（1985-10-26），而「只换一行等长 build hash」的重建连 size 都不变 ——
        // 光看 mtime+size 会得出「指纹没变」而不触发重载（2026-11-02 实测：换了包，
        // 日志只剩心跳，跑的还是旧代码）。ctime 由内核在写入时盖章，tar 与 utimes 都
        // 设不了它；普通读不改 ctime，所以不会引入误触发。
        parts.push(`${entry.name}:${st.mtimeMs}:${st.size}:${st.ctimeMs}`);
      } catch { /* 文件刚被删，跳过 */ }
    }
    return parts.sort().join('|');
  } catch {
    return '';
  }
}

function errText(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
