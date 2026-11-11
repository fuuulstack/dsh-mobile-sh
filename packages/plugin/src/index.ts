import Schema from '@deepseek-ai/schemastery';
import { z } from 'zod';
import { activate, type Context, type PluginConfig } from './lifecycle.js';
import { registerUiRoutes, type UiContext } from './ui-routes.js';
import { selfReload, watchPackage, type ReloadContext } from './hot-reload.js';
import { LIMITS, NAMING, envName } from '../../core/src/index.js';

export const inject=['webServer','connection','credentials','storageDomain'];
export const Config=Schema.object({
  relayUrl:Schema.transform(Schema.string().default(''),(v:string)=>{if(!v)return v;const u=new URL(v);if(u.protocol!=='wss:'||u.username||u.password||u.pathname!=='/tunnel'||u.search||u.hash)throw new Error('relayUrl must be wss://<authority>/tunnel');return u.href;}),
  enabled:Schema.boolean().default(true),
  // 设置页保存的位置；留空用 <DSH_HOME>/mobile-sh/config.json。测试与多实例部署可以指向别处。
  settingsFile:Schema.string().default(''),
  maxTunnelRequestBodyBytes:Schema.number().step(1).min(1).max(LIMITS.maxBody).default(LIMITS.maxBody),
  maxConcurrentStreams:Schema.number().step(1).min(1).max(64).default(64),
  connectTimeoutMs:Schema.number().step(1).min(1000).max(60000).default(15000),
  httpIdleTimeoutMs:Schema.number().step(1).min(1000).max(600000).default(120000),
  upgradePaths:Schema.array(Schema.string()).default(['/api/remote.mux']),
});
const hostSchema=z.strictObject({hostPublicId:z.string(),relayOrigin:z.string(),hostId:z.string().optional()});
// storageDomain 的域描述符。字段与宿主 @deepseek-ai/dsh-storage-domain 的 defineDomain()/
// domainTable() 产出完全一致（defineDomain 只校验并原样返回 spec，domainTable 返回
// {valueSchema}）。该包无法从插件目录解析：宿主只为第一方 bundle 提供 @deepseek-ai/*，
// 第三方插件必须自带实现；而宿主会在 storageDomain.open() 时重新校验描述符并驱动
// backend，所以这里给出等价字面量，不再持有该依赖。
const spec={name:NAMING.storageDomainName,version:1,tables:{host:{valueSchema:hostSchema}}};

/**
 * 插件入口。
 *
 * <p>除了正常启动，这里还挂了一个**自热重载**：改磁盘上的代码后自动换掉正在跑的
 * 插件，不重启应用。走的是内核 HMR 的官方路径（清 ESM/CJS 缓存 + 重新注册 fiber），
 * 不是 `loader.update` —— 后者比的是 entry 配置，配置没变不会重新 import，
 * 即使 import 了也会被 ESM 缓存挡住。实现与取舍见 hot-reload.ts 文件头。
 *
 * <p>用环境变量 {@code DSH_MOBILE_SH_HOT_RELOAD=0} 关掉（生产建议关：安装目录不会
 * 自己改写，省掉一个目录监视器）。
 */
export function apply(ctx:Context,config:PluginConfig){
  // 宿主主进程的 stdio 被 Chromium 接管，console 在排障时拿不到（mac e2e 实证过），
  // 「热重载装上没装上」必须有个能读的落点，所以同时写一份文件。
  const trace=(message:string)=>{
    ctx.logger.info('mobile-sh %s',message);
    try{require('node:fs').appendFileSync('/tmp/mobile-sh-hotreload.log',`${new Date().toISOString()} ${message}\n`);}catch{/* 日志失败不影响主流程 */}
  };
  const {service}=activate(ctx,config,spec);
  // 插件 UI（设置页 + 配对二维码）：独立路由，由浏览器侧的侧栏面板以 iframe 打开。
  // 注册失败不能让插件整体不工作——隧道与归档是核心功能，UI 只是操作面。
  try{
    const disposeUi=registerUiRoutes(ctx as unknown as UiContext,service);
    ctx.effect(()=>disposeUi,'mobile-sh: ui routes');
    trace('ui routes registered at /dsh-mobile-sh');
  }catch(e){
    trace(`ui routes failed: ${e instanceof Error?e.message:String(e)}`);
  }
  if(process.env[envName('HOT_RELOAD')]==='0'){trace('hot reload disabled by env');return;}
  // 重载要清模块缓存（loader.internal.loadCache）并重新注册 fiber（registry）。
  // 注意这两个在 DSH 的 Context 上是**属性**，不是 ctx.get() 的服务名——
  // ctx.get('registry') 返回 undefined，实测就卡在这里。
  const c=ctx as unknown as {
    loader?: unknown; registry?: unknown; fiber?: unknown;
    _ctx?:{loader?:unknown;registry?:unknown}; root?:{loader?:unknown;registry?:unknown};
  };
  const loader=c.loader??c._ctx?.loader??c.root?.loader;
  const registry=c.registry??c._ctx?.registry??c.root?.registry;
  if(!loader||!registry){
    // 把实际有哪些键记下来，下次缺什么一眼能看出来，不用再猜。
    let keys:string[]=[];
    try{keys=Object.keys(c).concat(Object.keys((c as {_ctx?:object})._ctx??{})).slice(0,40);}catch{/* 反射失败不影响主流程 */}
    trace(`hot reload unavailable: loader=${!!loader} registry=${!!registry} fiber=${!!c.fiber} keys=${keys.join(',')}`);
    return;
  }
  const reloadCtx=ctx as unknown as ReloadContext;
  const entry=(()=>{
    try{
      // Windows 下 pathname 形如 /E:/...，去掉前导斜杠才是真实路径。
      return decodeURIComponent(new URL(import.meta.url).pathname).replace(/^\/([A-Za-z]:)/,'$1');
    }catch{return '';}
  })();
  if(!entry){trace('hot reload unavailable: 拿不到入口路径');return;}
  const packageDir=entry.replace(/[\\/][^\\/]+$/,'');
  // armed 那行带上「能不能拿到 runtime」的结论：如果这里 fiber 已经是空的，
  // 后面的 selfReload 一定卡在 [2]，不用等改文件就能看出来。
  const hasFiber=!!(c.fiber as {runtime?:unknown}|undefined)?.runtime;
  trace(`hot reload armed: dir=${packageDir} fiber.runtime=${hasFiber}`);

  const doReload=()=>{void selfReload(reloadCtx,{log:trace}).then(r=>{
    if(!r.ok)trace(`hot reload 失败: ${r.error}`);
  });};
  // 目录监视：recursive 是关键——只盯单文件在 Electron 主进程里实测不触发。
  const stopWatch=watchPackage(packageDir,{onChange:doReload,log:trace});
  // ctx.effect 的**回调体会立即执行**，它返回的函数才是 fiber 卸载时执行的 disposer
  // （cordis 4.0.5 fiber.effect: "execute runs immediately; the disposers it produces
  // are collected and run ... when the fiber unloads"）。原写法把 stopWatch() 写进
  // 回调体 → 监视器与轮询在 armed 之后立刻被自己停掉，表现正是「装上了但永不触发」；
  // paused 一个标志同时杀死 fs.watch 与轮询两条路径，而插件其他定时器不受影响。
  ctx.effect(()=>stopWatch,'mobile-sh: hot reload watcher');
  // 也给一个程序化触发（设置页/脚本不必等文件变化）。
  ctx.provide('mobileShReload',()=>doReload());
}
