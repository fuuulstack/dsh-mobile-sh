import { z } from 'zod';
import { randomBytes } from 'node:crypto';
import { dirname, join } from 'node:path';
import { exchangeCookie } from './client.js';
import { Forwarder, resolveWorkerFile } from './supervisor.js';
import { safePath, NAMING, BUILD_HASH, envName } from '../../core/src/index.js';
import { defaultSettingsPath, normalizeEnrollSecret, normalizeRelayUrl, readSettingsFile, resolveSettings, writeSettingsFile,
  SettingsError, type ResolvedSettings, type StoredSettings } from './settings.js';
import { requestPairCode, pairingUri, pairingQrSvg, svgDataUrl, PairingError, type PairCode } from './pairing.js';
import { PowerSaveKeeper, detectBlocker, type PowerSaveView } from './power.js';

export type PluginConfig={relayUrl:string;enabled:boolean;maxTunnelRequestBodyBytes:number;maxConcurrentStreams:number;connectTimeoutMs:number;httpIdleTimeoutMs:number;upgradePaths:string[];
  /** 设置文件路径；留空用 `<DSH_HOME>/mobile-sh/config.json`。 */
  settingsFile?:string};
const identityFields={hostPublicId:z.string().regex(/^[a-f0-9]{32}$/),hostTunnelToken:z.string().regex(/^[A-Za-z0-9_-]{43}$/),operationId:z.string().regex(/^[a-f0-9]{32}$/),hostId:z.string().regex(/^[a-f0-9]{32}$/).optional()};
// 这台机器**在别的中继上**的身份：键是中继 origin，值是当时那份凭据。
// 存在的理由见 run() 里「中继已变更」那一段——主机 id 是中继局部的，
// 换中继就重新 enroll 会让手机侧全部授权变成孤儿（2026-11-05 生产事故）。
const historySchema=z.record(z.string(),z.strictObject({...identityFields,savedAt:z.number().int().nonnegative().optional()}));
const recordSchema=z.strictObject({...identityFields,relayOrigin:z.string(),history:historySchema.optional()});
type RecordState=z.infer<typeof recordSchema>;
type HistoryEntry=z.infer<typeof historySchema>[string];
/** 存档保留几个中继的身份；超出按 savedAt 淘汰最旧的。 */
const HISTORY_LIMIT=5;
const identityOf=(v:RecordState):RecordState=>({hostPublicId:v.hostPublicId,hostTunnelToken:v.hostTunnelToken,operationId:v.operationId,...(v.hostId?{hostId:v.hostId}:{}),relayOrigin:v.relayOrigin});
const pruneHistory=(h:Record<string,HistoryEntry>):void=>{const keys=Object.keys(h);if(keys.length<=HISTORY_LIMIT)return;
  keys.sort((a,b)=>(h[a]?.savedAt??0)-(h[b]?.savedAt??0));
  for(const key of keys.slice(0,keys.length-HISTORY_LIMIT))delete h[key];};
// The narrow facade is deliberately isolated from runtime imports; actual Loader validation is a release gate.
export interface Context {
  // cordis 4.0.5 的 effect 接受同步或异步 disposer（SyncEffect | Effect），
  // 这里按实际用法放宽：生命周期 stop 是 async，热加载 stopWatch 是同步。
  effect(fn:()=>()=>void|Promise<void>,label:string):unknown;
  get(name:string):any;
  provide(name:string,value:unknown):void;
  logger:{info:(...v:unknown[])=>void;warn:(...v:unknown[])=>void};
  webServer:{getOrigin():string};connection:{authenticatedUrl(origin:string):string};
  credentials:{readRecord(key:string):Promise<unknown>;modifyRecord(key:string,fn:(current:unknown)=>unknown):Promise<unknown>};
  storageDomain:{open(spec:unknown):Promise<{table:(name:string)=>{get:(key:string)=>unknown;put:(key:string,value:unknown)=>Promise<unknown>};close:()=>Promise<void>}>};
}

/**
 * 设置页要读的当前设置。
 *
 * <p>{@link enrollSecret} 是用户自己在设置页填过的密码原文（2026-10-28 按用户要求改：
 * 密码要能回填，不要每次登录都清空）。它只来自设置文件，来自环境变量/patch 的那份不回显——
 * 那是部署方注入的一次性值，没有回填的意义。页面只经宿主 loopback + 浏览器会话准入提供，
 * 且带 `cache-control: no-store`，不落缓存。
 */
export interface SettingsView {
  readonly relayUrl: string;
  readonly relayOrigin: string | null;
  readonly relaySource: ResolvedSettings['source'];
  readonly secretConfigured: boolean;
  /** 已保存的中继鉴权密码明文；没有可回填的值时为空串。 */
  readonly enrollSecret: string;
  readonly settingsFile: string;
  /** 「隧道连上时阻止电脑休眠」的开关与当前实际状态（Electron 的 powerSaveBlocker，取不到时退系统命令）。 */
  readonly powerSave: PowerSaveView;
}

/** `mobileSh` 服务对外暴露的面：设置页与热重载都用它，不额外开 HTTP 状态端点。 */
export interface MobileShService {
  status():{state:string;hostId:string|null;relayOrigin:string|null;cookieReady:boolean;storageReady:boolean;version:string;probe:unknown;upstreamVersion:string|null};
  disconnect():Promise<void>;
  settings():SettingsView;
  /** 保存中继地址/鉴权密码并立即重连；抛 SettingsError 时 UI 直接展示 message。 */
  saveSettings(input:{relayUrl?:string;enrollSecret?:string;powerSave?:boolean}):Promise<SettingsView>;
  /**
   * 单独切换「防休眠」（不重连、不需要重新填密码）。
   *
   * <p>它是纯本机行为，与中继地址无关，所以不走 saveSettings 的那条「先落盘再重连」路径。
   */
  setPowerSave(enabled:boolean):SettingsView;
  /** 向中继要一个配对码并画成二维码（data URL）。 */
  pair():Promise<{code:string;expiresAt:number;ttlSeconds:number;uri:string;qr:string}>;
}
export function activate(ctx:Context,config:PluginConfig,spec:unknown):{stop:()=>Promise<void>;service:MobileShService}{
  let stopped=false,state='starting',cookie='',cookieAt=0;
  /**
   * 转发层跑在**子进程**里（中期-13 解耦）。
   *
   * <p>宿主的隧道、loopback 转发、归档原本都在 DSH 宿主主进程的事件循环里，和它的 UI、
   * 网关共用一条线程：宿主一卡，手机侧就 502；插件自己的加密与 JSON 解析也在抢同一条线程。
   * 现在这一层整体挪进子进程（`worker.ts`），宿主进程只留「读设置 / 兑换 cookie / 转述状态」。
   * 详见 `worker.ts` 与 `supervisor.ts` 的文件头。
   */
  let forwarder:Forwarder|undefined;
  let domain:Awaited<ReturnType<Context['storageDomain']['open']>>|undefined;
  let boot:Promise<void>|undefined,retry:NodeJS.Timeout|undefined,cancelReady:(()=>void)|undefined,stopTask:Promise<void>|undefined;
  let refresh:Promise<string>|undefined;
  /** 当前生效的设置（文件 > patch）。保存后原地替换并重连，不重启 DSH。 */
  const settingsPath=config.settingsFile===''||config.settingsFile===undefined?defaultSettingsPath():config.settingsFile;
  let stored:StoredSettings|undefined=readSettingsFile(settingsPath);
  let resolved:ResolvedSettings=resolveSettings(stored,config.relayUrl);
  /** 最近一次 enroll 得到的身份（配对码要用它的 hostId + 隧道凭据）。 */
  let identity:RecordState|undefined;
  /** 每轮重连递增；旧的 run() 发现代号变了就自己退出，避免两条隧道抢同一个 hostId。 */
  let generation=0;
  let restarting:Promise<void>|undefined;
  /** Three-state upgrade probe result, reported through status() once measured. */
  let probe:{result:'present-ok'|'present-unauthenticated'|'suspected-missing'|'probe-error',at:number}|undefined;
  /**
   * Probe one configured upgrade path with a throwaway WebSocket: 101 with a successful
   * handshake is present-ok; an explicit 401/403 is present-unauthenticated (the path exists,
   * the probe simply carries no session); a TCP reset with no HTTP response is only
   * suspected-missing; everything else (timeout, DNS, other statuses) is probe-error and must
   * not be read as "path absent". The probe socket is closed immediately after the verdict.
   */
  const probeUpgrade=async(path:string,cookieValue:string,origin:string):Promise<'present-ok'|'present-unauthenticated'|'suspected-missing'|'probe-error'>=>{
    const {WebSocket:Ws}=await import('ws');
    return await new Promise(resolve=>{
      const u=new URL(path,origin);u.protocol='ws:';
      let settled=false;const done=(v:'present-ok'|'present-unauthenticated'|'suspected-missing'|'probe-error')=>{if(settled)return;settled=true;try{ws.terminate();}catch{}resolve(v);};
      const ws=new Ws(u,{headers:{cookie:cookieValue},handshakeTimeout:8000});
      ws.once('open',()=>done('present-ok'));
      ws.once('unexpected-response',(_r:unknown,res:{statusCode?:number})=>{const code=res.statusCode??0;done(code===401||code===403?'present-unauthenticated':'probe-error');});
      ws.once('error',(err:NodeJS.ErrnoException)=>{done(err.message.includes('ECONNRESET')?'suspected-missing':'probe-error');});
      ws.once('close',()=>{done('suspected-missing');});
    });
  };
  const transition=(next:string)=>{
    state=next;ctx.logger.info('mobile-sh state=%s',state);
    // 防休眠只在隧道真的连上时持有：unpaired/degraded/connecting 都会进宽限期，
    // 到点自动放掉断言（进程还活着但功能已死的场景，见 power.ts 文件头）。
    try{power.noteState(next);}catch(e){ctx.logger.warn('mobile-sh power state failed: %s',e instanceof Error?e.message:String(e));}
  };
  // 落盘 trace 提到 activate 顶层：L52/L53 的 disabled/unpaired 早退发生在 run() 之前，
  // trace 若定义在 run() 内部，这几条最关键的「配置没进来」路径将永远没有证据（本轮实测踩坑：
  // relayUrl 为空导致 unpaired，插件存活但零日志、零隧道，排查无从下手）。
  const trace=(message:string)=>{ctx.logger.info('mobile-sh %s',message);try{require('node:fs').appendFileSync('/tmp/mobile-sh-plugin.log',`${new Date().toISOString()} ${message}\n`);}catch{/* 日志失败不影响主流程 */}};
  /**
   * 「手机连着时别让电脑睡着」（2026-10-29 用户要求）。
   *
   * <p>断言必须由**宿主主进程**持有：转发层已经搬进 fork 出来的子进程（见下方 forwarder
   * 注释），而防休眠要绑在「活着的宿主」上。崩溃兜底的细节见 power.ts 文件头。
   *
   * <p>`detectBlocker` 先试 Electron 的 `powerSaveBlocker`，取不到再退系统命令
   * （`caffeinate` / `systemd-inhibit`）——DSH 宿主以 `ELECTRON_RUN_AS_NODE=1` 运行，
   * 前者必然取不到，见 power.ts 的 `detectPowerSaveBlocker`。
   */
  // 构造必须在 trace 之后：detectBlocker 会在构造期间同步回调 onLog，
  // 而 trace 是 `const`——放在上面会撞 TDZ，通道不可用的原因就永远打不出来。
  const power=new PowerSaveKeeper({blocker:detectBlocker(message=>trace(message)),onLog:message=>trace(message)});
  // 用户的开关意图在这里落地（tunnel 还没连上时只记住，不持有断言）。
  try{power.setEnabled(resolved.powerSave);}catch(e){ctx.logger.warn('mobile-sh power init failed: %s',e instanceof Error?e.message:String(e));}
  // 把「宿主到底有没有电源接口」写进 trace：non-Electron 宿主下这个开关必然是死的，
  // 现场只有这一行能区分「用户没开」和「开了但宿主不支持」。
  trace(`防休眠：enabled=${resolved.powerSave} supported=${power.supported} electron=${process.versions.electron??'none'}`);
  /** 停掉转发层（子进程），保留 storageDomain（重连不该反复开关数据域）。 */
  const stopTunnel=async()=>{
    clearTimeout(retry);retry=undefined;
    await forwarder?.stop();forwarder=undefined;
  };
  const stop=()=>stopTask??= (async()=>{
    stopped=true;transition('stopping');
    // 先放掉系统防休眠断言：卸载路径上哪怕后面 await 卡住，电脑也不该被一直扣着不休眠。
    try{power.dispose();}catch(e){ctx.logger.warn('mobile-sh power dispose failed: %s',e instanceof Error?e.message:String(e));}
    cancelReady?.();await stopTunnel();await boot?.catch(()=>{});cookie='';await domain?.close();domain=undefined;transition('disabled');})();
  // cordis 4.0.5：ctx.effect 的回调体**立即执行**，返回的函数才是 fiber 卸载时的 disposer。
  // 所以这里必须「返回 stop」而不是「调用 stop()」——写成调用会让插件在 activate 期间自我停掉。
  ctx.effect(()=>stop,'mobile-sh: lifecycle');
  const status=()=>({state,hostId:identity?.hostId??null,relayOrigin:resolved.relayOrigin,cookieReady:!!cookie,storageReady:!!domain,version:'0.2.0',probe,
    upstreamVersion:probe?.result==='present-ok'?process.env.DSH_VERSION??null:null,
    // 解耦后真正跑转发的那个进程的 pid：为 null 说明子进程没起来，
    // 这时手机侧必然是 offline —— 比「state=connected 但没有 pid」好判断得多。
    workerPid:forwarder?.pid()??null});
  const settingsView=():SettingsView=>({relayUrl:resolved.relayUrl,relayOrigin:resolved.relayOrigin,relaySource:resolved.source,
    secretConfigured:!!(stored?.enrollSecret||process.env[envName('ENROLL_INVITATION')]),enrollSecret:stored?.enrollSecret??'',settingsFile:settingsPath,
    powerSave:power.view()});

  const run=async(gen:number)=>{
    if(stopped||gen!==generation)return;
    trace(`run() 开始 relayUrl=${resolved.relayUrl||'(空)'}`);
    try{
      if(!domain)domain=await ctx.storageDomain.open(spec);
      if(stopped||gen!==generation)return;
      const origin=ctx.webServer.getOrigin(),u=new URL(origin);if(u.protocol!=='http:'||u.hostname!=='127.0.0.1'){transition('unpaired');trace(`requires an http://127.0.0.1 web profile origin, got ${origin}; plugin idle until the profile changes`);return;}
      if(!resolved.relayUrl||!resolved.relayOrigin){transition('unpaired');trace('未配置中继地址，unpaired');return;}
      // 中继的 HTTP 面协议**跟着隧道走**：wss → https，ws → http。
      // 早先这里写死 https，理由是「中继只允许 https」；裸 IP 自建部署（明文 http）
      // 出现后那条假设不成立，写死会让 /control/enroll 连到一个根本不存在的 TLS 端口。
      const relay=new URL(resolved.relayUrl);relay.protocol=relay.protocol==='ws:'?'http:':'https:';relay.pathname='/';
      const storedRecord=await ctx.credentials.readRecord(NAMING.credentialRecord);
      // 只读出来，先不建：下面要按「中继是否变更」决定复用还是丢弃。
      if(storedRecord)identity=recordSchema.parse((storedRecord as {payload:unknown}).payload);
      if(stopped||gen!==generation)return;
      /** 这份凭据要落盘的内容：当前身份 + 其它中继上的身份存档。 */
      let history:Record<string,HistoryEntry>={...(identity?.history??{})};
      const save=async(state:RecordState)=>ctx.credentials.modifyRecord(NAMING.credentialRecord,()=>({kind:'grant',payload:{...identityOf(state),history}}));
      // 凭据是**绑定中继**的：换一个中继域名后，旧凭据在那个中继上不存在。
      //
      // 2026-10-27 按「极简 UI」调整：以前这里直接 unpaired 并让人去手动清凭据，
      // 但用户能做的只有「改域名 + 填密码」这一件事，所以现在只要**这次设置里带了
      // 密码**，就自动丢弃旧身份、用新密码重新注册；没带密码时才停在 unpaired
      // （那时确实需要人提供凭据，不能自己瞎猜）。
      if(identity!==undefined&&identity.relayOrigin!==relay.origin){
        const invitation=resolved.enrollSecret||process.env[envName('ENROLL_INVITATION')];
        const from=identity.relayOrigin;
        // 换中继**不等于换机器**：先把当前身份按它的 origin 存档，再去新中继上找。
        // 这样「切到自建中继试完再切回生产」不会让这台机器在生产上变成一台新主机
        // （主机 id 是中继局部的，手机授权全挂在上面的 hostId 上——2026-11-05 实测：
        // 切一趟 self-hosted 再切回来，手机就再也收不到消息了，因为它是按旧 hostId 授权的）。
        history={...history,[from]:{...identityOf(identity),savedAt:Date.now()}};
        pruneHistory(history);
        const saved=history[relay.origin];
        if(saved!==undefined){
          identity={hostPublicId:saved.hostPublicId,hostTunnelToken:saved.hostTunnelToken,operationId:saved.operationId,relayOrigin:relay.origin,...(saved.hostId?{hostId:saved.hostId}:{})};
          trace(`中继已变更：${from} → ${relay.origin}，复用该中继上的既有身份 ${saved.hostId?.slice(0,8)??'(缺 hostId，将补注册)'}`);
          await save(identity);
        }else if(!invitation){
          transition('unpaired');trace(`stored credential belongs to ${from} but settings point at ${relay.origin}; 未提供新密码，无法在新中继上重新注册`);return;
        }else{
          trace(`中继已变更：${from} → ${relay.origin}，旧身份已存档，将在新中继上重新注册（切回来仍是同一台主机）`);
          identity=undefined;
          await ctx.credentials.modifyRecord(NAMING.credentialRecord,()=>({kind:'grant',payload:{...identityOf({...history[from]!,relayOrigin:from}),history}}));
        }
      }
      if(identity===undefined){
        // 邀请来源有两条：设置页保存的（优先）与一次性环境变量（无 UI 的部署方式）。
        const invitation=resolved.enrollSecret||process.env[envName('ENROLL_INVITATION')];
        if(!invitation){transition('unpaired');trace('无凭据记录且无注册邀请，unpaired');return;}
        const fresh:RecordState={hostPublicId:randomBytes(16).toString('hex'),hostTunnelToken:randomBytes(32).toString('base64url'),operationId:randomBytes(16).toString('hex'),relayOrigin:relay.origin};
        await ctx.credentials.modifyRecord(NAMING.credentialRecord,()=>({kind:'grant',payload:{...fresh,history}}));
        identity=recordSchema.parse((await ctx.credentials.readRecord(NAMING.credentialRecord) as {payload:unknown}).payload);
      }
      if(stopped||gen!==generation)return;
      if(!identity.hostId){
        const invitation=resolved.enrollSecret||process.env[envName('ENROLL_INVITATION')];
        if(!invitation){transition('unpaired');trace('凭据缺 hostId 且无注册邀请，unpaired');return;}
        const response=await fetch(new URL('/control/enroll',relay),{method:'POST',redirect:'error',signal:AbortSignal.timeout(15000),headers:{authorization:`Bearer ${invitation}`,'content-type':'application/json'},body:JSON.stringify({operationId:identity.operationId,hostPublicId:identity.hostPublicId,hostTunnelToken:identity.hostTunnelToken,alias:process.env.COMPUTERNAME??'DSH 主机',platform:process.platform})});
        if(!response.ok)throw new Error('enroll-rejected');const result=z.object({hostId:z.string().regex(/^[a-f0-9]{32}$/)}).parse(await response.json());identity={...identity,hostId:result.hostId};
        await save(identity);
        // 部署方注入的环境变量是一次性的：注册成功后从进程环境里清掉，避免常驻进程一直留着它。
        // 但**设置页保存的密码要留在设置文件里**（2026-10-28 按用户要求改）：
        // 中继侧的真实凭据是 `RELAY_ENROLL_PASSWORD` 这类可重复使用的长期密码，
        // 而不是一次性的 43 位邀请；以前跟着清掉，导致用户每次打开面板看到的都是空输入框，
        // 每次登录都要重新找一遍密码。凭据失效时用户改密码重存即可覆盖它。
        delete process.env[envName('ENROLL_INVITATION')];
      }
      if(stopped||gen!==generation)return;
      await domain.table('host').put('identity',{hostPublicId:identity.hostPublicId,hostId:identity.hostId,relayOrigin:identity.relayOrigin});if(stopped||gen!==generation)return;
      const getCookie=()=>{
        if(cookie&&Date.now()-cookieAt<25*86400000)return Promise.resolve(cookie);
        return refresh??=exchangeCookie(origin,ctx.connection.authenticatedUrl(origin)).then(v=>{cookie=v;cookieAt=Date.now();return v;}).finally(()=>{refresh=undefined;});
      };
      await getCookie();if(stopped||gen!==generation)return;
      // Probe the configured upgrade paths once per start with the freshly exchanged cookie.
      // A path that answers present-unauthenticated or worse never blocks the tunnel itself;
      // the verdict is recorded for status() so a wrong upgradePaths config is diagnosable.
      try{
        const first=config.upgradePaths[0]!;
        const verdict=await probeUpgrade(first,cookie,origin);
        probe={result:verdict,at:Date.now()};
        ctx.logger.info('mobile-sh upgrade probe %s -> %s',first,verdict);
        if(verdict==='suspected-missing'||verdict==='probe-error')ctx.logger.warn('mobile-sh upgrade path %s not confirmed (%s); tunnel continues with the configured path',first,verdict);
      }catch{probe={result:'probe-error',at:Date.now()};}
      if(stopped||gen!==generation)return;
      // 转发层交给子进程（中期-13 解耦）：宿主的隧道、loopback 转发、归档不再跑在本进程的
      // 事件循环里。父进程只负责「拉起 + 下发 cookie + 转述状态」，一句网络 I/O 都不做。
      // 入口优先取构建产物 dist/worker.js，测试（tsx 直跑源码）时自动退到 worker.ts。
      const worker=resolveWorkerFile(import.meta.url);
      forwarder=new Forwarder({
        workerFile:worker.file,
        execArgv:worker.execArgv,
        getCookie,
        onLog:trace,
        // 子进程报上来的状态直接当本插件状态用：UI 的 status() 读的就是它。
        onState:s=>transition(s),
      });
      await forwarder.start({
        type:'start',relayUrl:resolved.relayUrl,relayOrigin:relay.origin,hostId:identity.hostId!,hostTunnelToken:identity.hostTunnelToken,
        upstreamOrigin:origin,upgradePaths:config.upgradePaths,maxBody:config.maxTunnelRequestBodyBytes,maxStreams:config.maxConcurrentStreams,
        connectTimeoutMs:config.connectTimeoutMs,httpIdleTimeoutMs:config.httpIdleTimeoutMs,
        // Durable archive outbox: a push that is in flight when the host is killed must
        // still be deliverable after the restart, so it cannot live in a Map. Sits next
        // to the settings file, i.e. under the same DSH home the user already backs up.
        outboxPath: join(dirname(settingsPath),'outbox.sqlite'),
        // Reported to the phone so the settings screen can show which build is running
        // on the desktop — the only reliable way to catch a stale tgz install.
        buildHash:BUILD_HASH,
      });
      // cookie 已经兑换好，直接下发，省掉子进程一次往返（它第一次 loopback 就要用）。
      forwarder.pushCookie(cookie);
    }catch(e){
      if(gen!==generation||stopped)return;
      transition('degraded');trace(`initialization failed (${e instanceof Error?e.message:'unknown-error'}); retrying in 30s`);
      retry=setTimeout(()=>{boot=run(generation);},30000);
    }
  };
  const start=()=>{if(!stopped&&!boot)boot=run(generation);};
  /**
   * 用新设置重连：停掉隧道层 → 重新读设置 → 起新一轮。
   *
   * <p>串行化（restarting）很重要：设置页连点保存会并发进这里，两轮同时 stop+start
   * 会让旧 client 的 stop() 落到新 client 上，表现为「保存成功但隧道消失」。
   */
  const restart=():Promise<void>=>restarting??=(async()=>{
    generation++;
    transition('connecting');
    await stopTunnel();
    await boot?.catch(()=>{});
    boot=undefined;
    if(!stopped)start();
  })().finally(()=>{restarting=undefined;});

  const service:MobileShService={
    status,disconnect:stop,settings:settingsView,
    async saveSettings(input){
      const nextUrl=input.relayUrl===undefined?undefined:normalizeRelayUrl(input.relayUrl).url;
      const nextSecret=input.enrollSecret===undefined||input.enrollSecret.trim()===''?undefined:normalizeEnrollSecret(input.enrollSecret);
      const nextPower=typeof input.powerSave==='boolean'?input.powerSave:undefined;
      const merged:StoredSettings={version:1,
        ...((nextUrl??stored?.relayUrl)?{relayUrl:nextUrl??stored?.relayUrl!}:{}),
        ...((nextSecret??stored?.enrollSecret)?{enrollSecret:nextSecret??stored?.enrollSecret!}:{}),
        ...((nextPower??stored?.powerSave)?{powerSave:true}:{})};
      writeSettingsFile(settingsPath,merged);
      stored=merged;resolved=resolveSettings(stored,config.relayUrl);
      // 防休眠是本机行为，与中继无关：先应用意图，再走重连（重连后 ready 会再拉一次）。
      try{power.setEnabled(resolved.powerSave);}catch(e){trace(`应用防休眠设置失败：${e instanceof Error?e.message:String(e)}`);}
      trace(`设置已更新：relayUrl=${resolved.relayUrl} secret=${merged.enrollSecret?'已配置':'未配置'} powerSave=${resolved.powerSave}，准备重连`);
      await restart();
      return settingsView();
    },
    setPowerSave(enabled){
      const merged:StoredSettings={...(stored??{}),version:1,powerSave:enabled};
      try{writeSettingsFile(settingsPath,merged);stored=merged;resolved=resolveSettings(stored,config.relayUrl);}
      catch(e){trace(`防休眠设置落盘失败：${e instanceof Error?e.message:String(e)}`);}
      try{power.setEnabled(enabled);}catch(e){trace(`应用防休眠设置失败：${e instanceof Error?e.message:String(e)}`);}
      trace(`防休眠已${enabled?'开启':'关闭'}（supported=${power.supported} active=${power.view().active}）`);
      return settingsView();
    },
    async pair(){
      if(!identity?.hostId)throw new PairingError('not-enrolled','主机尚未注册到中继，请先保存中继地址与鉴权密码');
      if(!resolved.relayOrigin)throw new PairingError('no-relay','尚未配置中继地址');
      const code:PairCode=await requestPairCode({relayOrigin:resolved.relayOrigin,hostId:identity.hostId,hostTunnelToken:identity.hostTunnelToken});
      // login 一并拼进二维码：手机扫一下就完成「登录 + 配对」；老中继不签 login 时
      // pairingUri 会省掉该参数，手机退回旧的两步流程。
      const uri=pairingUri(resolved.relayOrigin,code.code,code.login);
      const qr=svgDataUrl(await pairingQrSvg(uri));
      return {...code,uri,qr};
    },
  };
  ctx.provide('mobileSh',service);
  trace(`activate() enabled=${config.enabled} relayUrl=${resolved.relayUrl||'(空)'} source=${resolved.source} settingsFile=${settingsPath}`);
  if(!config.enabled){transition('disabled');trace('enabled=false，插件停用');return {stop,service};}
  for(const path of config.upgradePaths){if(safePath(path)!==path||path.includes('?')||!config.upgradePaths.length||config.upgradePaths.length>8)throw new Error('invalid-upgradePaths');}
  if(!resolved.relayUrl){transition('unpaired');trace('relayUrl 为空（设置文件与 patch 都没有），unpaired：不建隧道，等待设置页保存');return {stop,service};}
  // 启动时机：inject 已保证 webServer/credentials/storageDomain 就绪，run() 需要的
  // 上游 origin 此刻可读，因此**立即启动**是安全的。历史上「只等 appReady.onReady」
  // 的写法在「插件晚于应用就绪才被装载」（profile patch 新增插件、热重载重注册）时
  // 会永久挂起：onReady 注册进去时 ready 事件早已触发，回调永不发生，run() 从不执行，
  // 表现为插件存活但无隧道、且任何落盘日志都不出现（本轮实测踩坑）。
  // start 内部有 `if(!stopped&&!boot)` 幂等保护，两路都触发也只跑一次。
  const startNow=()=>{ctx.logger.info('mobile-sh starting immediately (webServer ready)');start();};
  const ready=ctx.get('appReady');
  if(ready){
    ctx.logger.info('mobile-sh readiness via appReady');
    startNow();
    // 兜底：若上面因故早退，应用就绪事件再给一次机会（幂等）。
    cancelReady=ready.onReady(()=>{if(!stopped&&!boot)startNow();});
  }
  else {
    ctx.logger.info('mobile-sh readiness via loader.await');
    startNow();
    queueMicrotask(()=>{Promise.resolve(ctx.get('loader')?.await()).then(()=>{if(!stopped&&!boot)startNow();}).catch(()=>transition('degraded'));});
  }
  // 返回可停实例：热重载靠它做「停旧启新」，否则两条隧道会抢同一个 hostId。
  return {stop,service};
}
export { SettingsError };
