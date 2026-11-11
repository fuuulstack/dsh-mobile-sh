/**
 * 项目命名单一事实源。
 *
 * 对外正式项目名是 dsh-mobile-sh；这里集中管理 npm 包名、镜像名、环境变量前缀、
 * 插件凭据记录名与 UI 路由前缀。各 package.json 的 name 字段无法从 TS 导入，由
 * scripts/check-naming.mjs 校验一致。Android applicationId/namespace 仍为
 * com.dsh.mobile.v2，用于保持 APK 覆盖安装身份；APK 显示名在 android/gradle.properties。
 */
export const NAMING = {
  /** npm 插件包名（DSH 宿主 plugins 配置引用的就是它） */
  pluginPackage: 'dsh-mobile-sh',
  /** 工作区根 package.json 名（信息性） */
  workspace: 'dsh-mobile-sh-workspace',
  /** deploy/compose.yaml 的 docker 镜像名（镜像 tag 走 compose 变量，缺省值须与此一致） */
  dockerImage: 'dsh-mobile-sh-relay',
  /** 环境变量前缀：DSH_MOBILE_SH_RELAY / _ENROLL_INVITATION / _HOT_RELOAD */
  envPrefix: 'DSH_MOBILE_SH',
  /** 插件在宿主 credentials 文件里的记录名 */
  credentialRecord: 'mobile-sh/tunnel',
  /** 热重载的进程级 hooks 注册键（Symbol.for 用） */
  reloadHooksKey: 'dsh-mobile-sh.reload-hooks',
  /** storageDomain 域描述符名（数据域标识；改名等于换一块存储） */
  storageDomainName: 'mobile_sh',
  /** 热重载 trace 落盘路径 */
  hotReloadTraceFile: '/tmp/mobile-sh-hotreload.log',
  /** Android applicationId / namespace（信息性镜像，真身在 android/gradle.properties） */
  androidPackage: 'com.dsh.mobile.v2',
} as const;

/** 环境变量名：envName('RELAY') === 'DSH_MOBILE_SH_RELAY'。 */
export const envName = (suffix: string): string => `${NAMING.envPrefix}_${suffix}`;

/**
 * Build identity of whatever component is running.
 *
 * <p>The three halves ship on their own schedules — relay image, plugin tarball, APK —
 * and "which build am I talking to" was previously unanswerable from the phone. The
 * value is baked in at bundle time (`scripts/build.mjs` passes
 * `DSH_MOBILE_SH_BUILD_HASH`), so it changes on every `npm run build` even when the
 * version number does not; that is deliberate, because a same-version rebuild is
 * exactly the case where a stale install hides.
 *
 * <p>Falls back to a runtime stamp when the define is absent (tsx running the source,
 * as every test does).
 */
export const BUILD_HASH: string = (() => {
  const baked = typeof process === 'undefined' ? undefined : process.env.DSH_MOBILE_SH_BUILD_HASH;
  if (baked && baked.length > 0) return baked;
  return `src.${new Date().toISOString().slice(0, 13).replace(/[-:T]/g, '')}`;
})();
