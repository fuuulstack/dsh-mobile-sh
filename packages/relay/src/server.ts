import http, { type IncomingMessage, type ServerResponse } from 'node:http';
import { createReadStream, readFileSync, mkdirSync, renameSync, statSync, unlinkSync, writeFileSync } from 'node:fs';
import { createHash, randomBytes } from 'node:crypto';
import { dirname, join } from 'node:path';
import { isIP } from 'node:net';
import { gunzipSync } from 'node:zlib';
import QRCode from 'qrcode';
import { z } from 'zod';
import { HttpError, CONTROL_PATH, filterEvent, NO_OMIT, parseOmit } from '../../core/src/index.js';
import { equal, hash, secret } from './crypto.js';
import { NATIVE_TTL, Store, type Row } from './store.js';
import { clientIp, Proxy, rejectSocket, trustedPeer } from './proxy.js';
import { capacityFromEnv, describeCapacity } from './limits.js';
import {
  isValidVersion, manifestOf, normalizeNotes, publishedAtOf, releaseFileName,
  RELEASE_KEEP_DEFAULT, RELEASE_LATEST_ALIAS, RELEASE_MAX_MB_DEFAULT, RELEASE_PREFIX,
} from './release.js';
import { ChangeBus, streamChanges, type Change } from './changes.js';
import { SyncStats } from './sync-stats.js';
import { pageRequest, readSyncRequest, SyncLoopGuard } from './sync-v2.js';
import type { BackfillCursor } from './store.js';

/** 主机寻址方式。
 *
 *  <p>`subdomain`：`<hostId>.relay.example.com`——域名部署的唯一形态，Cookie 能按子域
 *  天然隔离，`__Host-` 前缀也成立。
 *
 *  <p>`path`：`https://<ip>:<port>/h/<hostId>/...`——**裸 IP 部署的唯一可行形态**。
 *  `1.2.3.4` 上挂不出 `abc.1.2.3.4` 这种子域（它不是合法主机名），所以只能把主机 id
 *  放进路径。代价是所有主机共用同一个 origin，于是 Cookie 无法按主机隔离——中继因此
 *  改用 `relay-host` 这个普通 Cookie 名（见 {@link Relay.ck}），并且仍然把会话绑定在
 *  `host_id` 上，越权访问别的主机依旧 401。
 *
 *  <p>`auto`（缺省）：baseUrl 的 hostname 是 IP 字面量就用 `path`，否则 `subdomain`。
 *  这条规则的意义是「裸 IP 开箱即用」——用户什么都不配也不会踩到子域解析失败。 */
export type HostRouting='auto'|'subdomain'|'path';
export interface RelayConfig {
  /**
   * 对外地址，**可选**。
   *
   * <p>钉住（`https://relay.example.com`）时所有绝对链接、Cookie 前缀、Origin 校验
   * 都在启动时定死，这是推荐姿势。不钉时按每个请求的 `Host` + `X-Forwarded-Proto`
   * 现推（{@link Relay.originOf}），为的是「一行 `docker run` 就能跑」——那种场景下
   * 操作者往往还没有域名、甚至先在本机 `localhost:8080` 上点两下试试。
   */
  baseUrl?:string;database:string;key:Buffer;requireTotp:boolean;trustedProxy?:string;listenHost?:string;port?:number;testMode?:boolean;metricsToken?:string;
  /**
   * 单一密码模式（`RELAY_PASSWORD` 被设置时由 {@link configFromEnv} 置位）。
   *
   * <p>只用于两件事：① 绕过 `public-relay-requires-totp` 的护栏（因为「配了一个密码」
   * 本身就是操作者的显式决定，而这个模式的价值就在于不强迫用户装验证器 App）；
   * ② 启动日志里明确写出「动态验证码已关闭」，让这件事不可能被忘掉。
   */
  passwordMode?:boolean;
  /** 首次启动要自动创建的账号（`serve` 里幂等建号用）。 */
  adminEmail?:string;
  /** 同上。**只用于首次创建**，已存在则跳过；用完就从环境里删掉。 */
  adminPassword?:string;
  /**
   * 允许 `http://`（明文）作为对外地址。
   *
   * <p>缺省 **false**：公网明文意味着令牌、归档正文、配对码全部在链路上裸奔，
   * 不能作为默认行为。但裸 IP 部署往往拿不到证书（IP 证书要单独签发，Let's Encrypt
   * 也不发 IP 证书），所以必须留一个显式开关，让部署方自己承担这个决定。
   *
   * <p>例外：loopback（`127.0.0.1` / `localhost` / `::1`）上的明文**永远**放行，
   * 那是本地联调，不经过任何链路。
   */
  allowInsecure?:boolean;
  /** 主机寻址方式，缺省 `auto`（见 {@link HostRouting}）。 */
  hostRouting?:HostRouting;
  /**
   * 显式关闭 TOTP 的凭据（由 `RELAY_REQUIRE_TOTP=false` 置位）。
   *
   * <p>{@link requireTotp} 为 false 时构造函数会拒绝启动（`public-relay-requires-totp`），
   * 除非走测试模式。裸 IP 的一行命令部署里操作者往往没有验证器 App，所以留一条
   * **必须显式写出来**的逃生口：把 `RELAY_REQUIRE_TOTP=false` 写进环境变量，
   * 就表示「我知道这意味着网页登录只剩密码」。默认值仍然是开——悄悄降级才是灾难。
   */
  requireTotpExplicit?:boolean;
  /**
   * 接受任意来源地址（`RELAY_TRUSTED_PROXY=*`）。
   *
   * <p>没有反代直连部署时必须打开：容器经 Docker NAT 出网时 `remoteAddress` 恒为
   * 网桥网关，不可能等于 `trustedProxy`，那条「只信任反代」的护栏会把所有请求判成
   * `untrusted-proxy`。代价是 `X-Forwarded-For` 变成可伪造的——按 IP 的限流因此
   * 只能当「别打穿数据库」的兜底，真正的凭据防护仍靠密码与 TOTP。
   */
  trustAnyPeer?:boolean;
  /**
   * 部署方自定义的「主机注册密码」（可选）。
   *
   * <p>为什么除了邀请还要这条：一次性邀请要求人去中继网页点一次、5 分钟内用掉，
   * 日常「重装插件 / 加第二台机器」很别扭。这里允许 operator 在环境里配一个固定密码，
   * 插件设置页填它即可注册；多台主机可重复使用，不消耗邀请。
   *
   * <p>安全边界：① 只能注册到**指定的那一个账号**（`enrollAccount`），不是任意账号；
   * ② 比较走 `equal()` 的定长比较；③ 与邀请路径共用 `host-limit`（≤10 台）。
   * 生产建议用长随机串，它就是「能把这台机器注册进中继」的凭据。
   */
  enrollPassword?:string;
  /** 自定义注册密码归属的账号邮箱（配了 enrollPassword 就必须配它）。 */
  enrollAccount?:string;
  /**
   * APK 发布文件的落盘目录（卷上的 `releases/`）。
   *
   * <p>缺省从 `database` 推出来：`/data/relay.db` → `/data/releases/`。刻意**不**把
   * 安装包塞进 SQLite——十五六 MB 的二进制放进库，每次 WAL checkpoint 都要搬它一遍，
   * 而它一辈子只会被顺序读一次。
   */
  releaseDir?:string;
  /** 卷上保留几个版本（缺省 5）。多留无益：没人会装三个版本以前的包，只是占盘。 */
  releaseKeep?:number;
  /** 单次上传的字节上限（缺省 64 MiB）。 */
  releaseMaxBytes?:number;
}
const esc=(v:unknown)=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]!));
/**
 * SSE 变更帧里最多带多少字符正文（JSON 长度近似）。
 *
 * <p>取舍：手机端游标普遍只落后几条，带上这批正文就能直接渲染，省掉一次公网
 * `/sync`；但 SSE 是一条长连接，帧太大既会把慢手机顶到 `QUEUE_LIMIT`，也会让
 * 中继为一次推送做无谓的序列化。16KB 与 `/sync` 的单会话 idle 预算同量级，超了
 * 就截断并置 `truncated`，由手机补一次 `/sync`——退化和推正文之前完全一样。
 */
const CHANGE_BODY_BUDGET=16*1024;
/** 单个会话在一条变更帧里的正文上限，防止一个大会话把整批预算吃光。 */
const CHANGE_BODY_PER_SESSION=8*1024;
/** 单条归档记录的协议硬上限（JSON 字节）。超过它的记录在 /sync 里明确报
 *  recordTooLarge，而不是静默截断或假装传完——手机据此把该会话标成
 *  「记录过大、跳过」，游标不推进，但也不会无限重试。 */
const MAX_SINGLE_RECORD=1024*1024;
/**
 * 限流分两档：**扛流量的**放宽，**扛凭据的**保持严格。
 *
 * <p>这是自托管中继，一个部署通常只有一个人用（一台电脑 + 一两部手机）。
 * 按公网多租户的量级去限 `/sync`、`/changes`、`/archive`、`/metrics`，只会
 * 罚到自己：2026-10-29 冷启动实测，`/sync` 的 30 次/分钟被四十多个会话的
 * 补页瞬间打满，一路 429 拖了三分钟 —— 而那些请求没有一条是攻击，全是正常读。
 *
 * <p>所以两档的判据完全不同：
 * <ul>
 *   <li>**资源类**（sync / changes / archive / metrics / handoff / unpair）：唯一
 *       目的是别让一个坏掉的客户端把中继或 SQLite 打穿。给到「正常用量的几十倍」
 *       即可；真要发现异常看 `/metrics` 的 `syncStats`，那才是对的地方。</li>
 *   <li>**凭据类**（login / device-code / enroll / pair）：这几条防的是**猜**。
 *       设备码 8 位、密码、注册口令都有限，放开等于送人爆破窗口。正常人一分钟
 *       不会登录五次、也不会兑五次设备码，所以这档保持原值不动。</li>
 * </ul>
 */
const RATE={
  /** 单台设备的 `/sync`：一次冷启动几十个会话各补几页，撞不上了。 */
  sync:[600,60_000],
  /** SSE 变更流重连：正常就一条长连接，断网重连才会重发。 */
  changes:[120,60_000],
  /** 插件推归档：500ms 防抖后批量推，一分钟几十次。 */
  archive:[600,60_000],
  /** 主机指标上报：默认 60s 一次，留出重连补齐的余量。 */
  metrics:[60,10_000],
  /** 网页侧跳转与解绑一类的一次性动作。 */
  handoff:[120,60_000],
  unpair:[120,60_000],
} as const satisfies Record<string,[number,number]>;
export function cookies(req:IncomingMessage){const out:Record<string,string>=Object.create(null);for(const p of (req.headers.cookie??'').split(';')){const i=p.indexOf('=');if(i>0){const name=p.slice(0,i).trim();if(name in out)throw new HttpError(400,'duplicate-cookie');out[name]=p.slice(i+1).trim();}}return out;}
function page(res:ServerResponse,title:string,body:string,formAction="'self'",script=''){
  const nonce=secret(16);res.writeHead(200,{'content-type':'text/html; charset=utf-8','cache-control':'no-store','referrer-policy':'no-referrer',
    'content-security-policy':`default-src 'none'; style-src 'nonce-${nonce}'; script-src 'nonce-${nonce}'; img-src data:; form-action ${formAction}; base-uri 'none'; frame-ancestors 'none'`});
  res.end(`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${esc(title)} · DSH Mobile</title><style nonce="${nonce}">:root{color-scheme:light dark;font-family:system-ui,sans-serif}body{max-width:48rem;margin:4rem auto;padding:0 1.2rem;line-height:1.6}header{border-bottom:1px solid #8885;padding-bottom:1rem}h1{font-size:1.7rem}label{display:block;margin:1rem 0}input,button{font:inherit;padding:.65rem;max-width:100%;box-sizing:border-box}input{display:block;width:100%}button{cursor:pointer}section,article{padding:1.2rem 0;border-bottom:1px solid #8885}code{overflow-wrap:anywhere}img{max-width:16rem}a{color:inherit}small{display:block}</style><header>DSH Mobile · 自建中继</header><main><h1>${esc(title)}</h1>${body}</main>${script?`<script nonce="${nonce}">${script}</script>`:''}</html>`);
}
const hidden=(name:string,value:string)=>`<input type="hidden" name="${name}" value="${esc(value)}">`;
const form=(action:string,csrf:string,body:string)=>`<form method="post" action="${action}">${hidden('csrf',csrf)}${body}</form>`;
async function body(req:IncomingMessage,limit=4096):Promise<Record<string,string>>{
  const chunks:Buffer[]=[];let n=0;for await(const chunk of req){n+=chunk.length;if(n>limit)throw new HttpError(413,'body-limit');chunks.push(chunk);}
  const data=Buffer.concat(chunks).toString('utf8');
  if(req.headers['content-type']?.split(';')[0]==='application/json'){const v=JSON.parse(data);if(!v||typeof v!=='object'||Array.isArray(v))throw new HttpError(400,'invalid-body');return v;}
  if(req.headers['content-type']?.split(';')[0]!=='application/x-www-form-urlencoded')throw new HttpError(415,'content-type');
  const p=new URLSearchParams(data),out:Record<string,string>=Object.create(null);for(const [k,v]of p){if(k in out)throw new HttpError(400,'duplicate-field');out[k]=v;}return out;
}
/** 二进制请求体（APK 上传）。与 {@link jsonBody} 分开：这里不做 gzip 解压、不做 JSON
 *  解析，只要字节；上限由调用方按 `releaseMaxBytes` 传入。 */
async function rawBody(req:IncomingMessage,limit:number):Promise<Buffer>{
  const chunks:Buffer[]=[];let n=0;
  for await(const chunk of req){n+=chunk.length;if(n>limit)throw new HttpError(413,'body-limit');chunks.push(chunk);}
  return Buffer.concat(chunks);
}

/** JSON body for machine callers: accepts a gzip request body (the plugin's archive
 *  pushes are chat text and compress 4-6x, which is real money on a home uplink). */
async function jsonBody(req:IncomingMessage,limit:number):Promise<Record<string,unknown>>{
  const chunks:Buffer[]=[];let n=0;for await(const chunk of req){n+=chunk.length;if(n>limit)throw new HttpError(413,'body-limit');chunks.push(chunk);}
  let data=Buffer.concat(chunks);
  if(req.headers['content-encoding']==='gzip'){
    try{data=gunzipSync(data);}catch{throw new HttpError(400,'invalid-gzip');}
    // The wire limit above bounds the compressed size; bound the inflated size too,
    // so a crafted small body cannot balloon after decompression.
    //
    // <p>This used to be a flat 4 MiB, which was **smaller than one legal record**:
    // the plugin's size policy allows a payload up to `SYNC_V2.hardCeiling` (10 MiB)
    // and ships the offloaded blob inside the same `/control/timeline` request. A
    // single 9 MiB tool result therefore could never be archived — and because the
    // batch id is derived from the rows, every retry recomputed the same doomed
    // batch and the whole outbox stalled behind it. The inflated ceiling has to sit
    // above one maximum record plus its envelope, so `limit` (the caller's wire
    // budget) governs again and this only catches decompression bombs.
    if(data.length>limit)throw new HttpError(413,'body-limit');
  }
  const v=JSON.parse(data.toString('utf8'));
  if(!v||typeof v!=='object'||Array.isArray(v))throw new HttpError(400,'invalid-body');
  return v as Record<string,unknown>;
}
/**
 * Rows copied per legacy->v2 migration chunk. Small enough that one call costs a few
 * milliseconds of event loop even when every row needs decrypt + classify + encrypt.
 */
const BACKFILL_CHUNK=50;
/** Peak live bytes per chunk (decrypted text + rebuilt envelopes, roughly 2-3x this). */
const BACKFILL_MAX_BYTES=2*1024*1024;
/** Wait before the first chunk so the relay is serving traffic before it migrates. */
const BACKFILL_FIRST_DELAY_MS=30_000;
/** Breathing room between chunks — see `startBackfill`. */
const BACKFILL_PAUSE_MS=50;

/**
 * Parse one `Range: bytes=…` header against a known length.
 *
 * <p>Three outcomes, and the caller must tell them apart: `undefined` = no range
 * asked for, serve the whole thing; `null` = a range was asked for that cannot be
 * satisfied (bad syntax, or `start >= length`), answer 416; otherwise the inclusive
 * byte interval to send. Only the single-interval form is supported because that is
 * all a resumable download sends — a multi-range request is answered with the full
 * body rather than a `multipart/byteranges` response nobody asked for.
 *
 * <p>`end` is clamped to `length-1`: the suffix form (`bytes=-500`) and the open form
 * (`bytes=1000-`) are both legal and both must resolve to a concrete end.
 */
/** 读一个整数环境变量并夹在 `[min,max]` 内。
 *
 *  <p>越界直接抛而不是静默钳位：`RELAY_MAX_STREAMS=1024` 是**写错了**，悄悄按 64 跑
 *  会让人以为参数没生效、接着去猜别的原因。错误信息里带变量名与合法范围。 */
function intVar(name:string,fallback:number,min:number,max:number):number{
  const raw=process.env[name]?.trim();
  if(!raw)return fallback;
  const value=Number(raw);
  if(!Number.isInteger(value)||value<min||value>max)throw new Error(`${name} must be an integer between ${min} and ${max}`);
  return value;
}

function parseRange(header:string|undefined,length:number):{start:number;end:number}|null|undefined{
  if(!header)return undefined;
  const m=/^bytes=(\d*)-(\d*)$/.exec(header.trim());
  if(!m)return undefined;
  const [,rawStart,rawEnd]=m;
  if(rawStart===''&&rawEnd==='')return null;
  let start:number,end:number;
  if(rawStart===''){
    // `bytes=-500`: the *last* 500 bytes. `-0` asks for nothing.
    const suffix=Number(rawEnd);
    if(!Number.isSafeInteger(suffix)||suffix<=0)return null;
    start=Math.max(0,length-suffix);end=length-1;
  }else{
    start=Number(rawStart);
    end=rawEnd===''?length-1:Number(rawEnd);
    if(!Number.isSafeInteger(start)||!Number.isSafeInteger(end))return null;
    if(end<start)return null;
    end=Math.min(end,length-1);
  }
  // An empty resource cannot satisfy any byte range; a start past the end likewise.
  if(length===0||start>end||start>=length)return null;
  return {start,end};
}

export class Relay {
  readonly store:Store;readonly proxy:Proxy;readonly server:http.Server;readonly origin:string;
  /** 会话变更的写扩散总线：归档写入即推送，手机端因此不必「先收信号再拉一次」。 */
  readonly changes=new ChangeBus();
  /** 各主机最近一次上报的运行指标（中期-12）。只留最新一份：它回答「现在多糟」，
   *  不回答「过去怎样」——后者交给外部时序库，中继的 SQLite 不该被指标打爆。 */
  readonly hostMetrics=new Map<string,Record<string,unknown>&{receivedAt:number}>();
  /** `/sync` 的服务端侧计数器（请求数、页数、字节、零进展、同游标重复）。 */
  readonly syncStats=new SyncStats();
  /** v2 同步的计数器与死循环检测器。分开放：两套协议的语义不同，混在一起读不出
   *  「到底哪条路径卡住了」。 */
  readonly syncStatsV2={requests:0,pages:0,records:0,bytes:0,zeroProgress:0};
  // v1 -> v2 archive migration. `copied` is monotonic within a process and lets an
  // operator confirm the upgrade actually carried history instead of trusting a log
  // line; `failures` staying at 0 is the "no session lost" signal.
  readonly backfill={copied:0,failures:0,chunks:0,running:false,done:false};
  readonly syncLoop=new SyncLoopGuard();
  /**
   * `/control/timeline` write latency, as a ring of the last 256 batches.
   *
   * <p>Why a p95 and not a mean: the failure that matters is "one batch took 4 seconds
   * while fifty normal ones took 6 ms" — a mean of 84 ms hides it completely, and it is
   * exactly the shape you get when SQLite's writer lock is held by `prune()` or by a
   * checkpoint. The ring is bounded so this never becomes a slow leak in a process that
   * runs for weeks.
   */
  readonly archiveWrites={count:0,failures:0,recent:[] as number[]};
  private cleanup:NodeJS.Timeout;
  /** 实际生效的主机寻址方式（`auto` 在这里被解析成确定值）。 */
  readonly routing:'subdomain'|'path';
  /** 对外是否明文 http。**钉住地址时**才是常量；未钉住时逐请求判定（{@link secure}）。 */
  readonly insecure:boolean;
  /** 对外地址钉死了没有。没钉 = 一行命令的默认形态。 */
  readonly pinned:boolean;
  /** APK 发布文件的落盘目录。 */
  readonly releaseDir:string;
  /** 保留的发布版本数。 */
  readonly releaseKeep:number;
  /** 单次上传上限（字节）。 */
  readonly releaseMaxBytes:number;
  /**
   * 解析 `req.url` 用的假基址。
   *
   * <p>那些 `new URL(req.url, base)` 只读 `pathname`/`searchParams`，基址取什么都不影响
   * 结果——但取空串会直接抛 `Invalid URL`。所以未钉地址时给它一个保留域名兜底。
   */
  private readonly urlBase:string;
  constructor(readonly config:RelayConfig){
    const raw=config.baseUrl?.trim();
    const u=raw?new URL(raw):undefined;
    if(u&&(u.pathname!=='/'||u.search||u.hash))throw new Error('base-url-must-be-origin');
    const loopback=!!u&&(u.hostname==='localhost'||u.hostname==='127.0.0.1'||u.hostname==='::1'||u.hostname==='[::1]');
    // 明文只在两种情形放行：显式 `RELAY_ALLOW_INSECURE=true`，或 loopback 上的本地联调。
    // 裸 IP 部署通常拿不到证书（IP 证书要单独签发），所以必须有这条显式开关；
    // 但默认拒绝——令牌、归档正文、配对码在明文链路上裸奔不能是默认行为。
    this.insecure=!!u&&u.protocol==='http:'&&(config.allowInsecure===true||loopback);
    if(u&&u.protocol!=='https:'&&!this.insecure)throw new Error('https-domain-required');
    if(!config.requireTotp&&!config.testMode&&!config.requireTotpExplicit)throw new Error('public-relay-requires-totp');
    this.pinned=!!u;
    this.origin=u?u.origin:'';
    this.urlBase=this.origin||'http://relay.invalid';
    // 未钉地址时子域模式无从谈起（连自己叫什么都要现问，没法把 `<id>.<域名>` 拼出来），
    // 所以强制 path：`/h/<hostId>` 是唯一不依赖域名的寻址方式。
    this.routing=!u?'path':config.hostRouting==='path'||config.hostRouting==='subdomain'
      ?config.hostRouting
      :(isIP(u.hostname)?'path':'subdomain');
    // APK 发布：文件本体在卷上，台账进库。目录从 database 推导，部署方不必多配一个
    // 变量；`:memory:` 是测试库，退到一个相对目录（测试会显式传 releaseDir）。
    this.releaseDir=config.releaseDir?.trim()
      ||(config.database.startsWith(':')?join('data','releases'):join(dirname(config.database),'releases'));
    this.releaseKeep=config.releaseKeep??RELEASE_KEEP_DEFAULT;
    this.releaseMaxBytes=config.releaseMaxBytes??RELEASE_MAX_MB_DEFAULT*1024*1024;
    this.store=new Store(config.database,config.key);
    this.proxy=new Proxy(this.store,this.urlBase,config.requireTotp,config.trustedProxy,config.testMode,config.trustAnyPeer,this.hostOrigin.bind(this),capacityFromEnv(),!u);
    this.server=http.createServer({maxHeaderSize:32768,requestTimeout:0},(req,res)=>{void this.handle(req,res).catch(e=>this.error(res,e));});
    this.server.headersTimeout=70000;this.server.keepAliveTimeout=65000;
    this.server.on('upgrade',(req,socket,head)=>{try{
      const host=this.target(req);
      // The phone's v2 control socket (and the only socket a v2 app opens):
      // the plugin terminates CONTROL_PATH itself. The legacy v1 stream route — which
      // bridged to `/api/remote.mux` — is gone; no shipped app has opened it since v2.
      // Rewriting `req.url` here (rather than teaching Proxy a second mode) keeps the
      // relay a pure router — it never learns what a pulse or a record looks like.
      const control=/^\/native\/v2\/hosts\/([a-f0-9]{32})\/control$/.exec(req.url??'');
      if(host==='account'&&control){this.nativeOnly(req);const id=control[1]!,s=this.store.session(this.bearer(req),'native',id);req.url=CONTROL_PATH;void this.proxy.upgrade(req,socket,head,id,s,true).catch(e=>rejectSocket(socket,e instanceof HttpError?e.status:502));}
      else if(host==='account'&&req.url==='/tunnel')this.proxy.acceptTunnel(req,socket,head);
      else if(host!=='account'){const s=this.store.session(cookies(req)[this.ck('relay-host',req)]??'','host',host);void this.proxy.upgrade(req,socket,head,host,s).catch(e=>rejectSocket(socket,e instanceof HttpError?e.status:502));}
      else rejectSocket(socket,404);
    }catch(e){rejectSocket(socket,e instanceof HttpError?e.status:400);}});
    this.server.on('clientError',(_e,socket)=>rejectSocket(socket,400));
    // Legacy archive rows are copied into the v2 timeline once, then kept in sync by
    // the anti-join inside `backfillTimeline` (which is why running it on every tick
    // is cheap). Doing it here rather than in the HTTP path means an upgraded relay
    // serves history immediately instead of waiting for the first phone to ask.
    // Chunked **and yielded**: `backfillTimeline` copies at most a few hundred rows
    // per call, and each call goes through `setTimeout(...,0)` so the event loop can
    // serve phones in between. Doing the whole archive in one synchronous pass on a
    // 2-core / 768 MB host thrashes it badly enough that sshd cannot finish its
    // banner exchange - which is exactly what the first version of this did.
    this.startBackfill();
    this.cleanup=setInterval(()=>{try{this.store.prune();}catch{this.store.pruneFailures++;}this.syncLoop.prune();this.backfill.done=false;if(!this.backfill.running)this.startBackfill();},3600000);this.cleanup.unref();
  }
  /**
   * 会话 Cookie 的**实际名字**。
   *
   * <p>`__Host-` 前缀有三条硬要求：`Secure`、`Path=/`、不得带 `Domain`。前两条在明文
   * http 下无法同时满足（浏览器会直接丢弃整条 Set-Cookie），所以明文部署只能退回普通
   * 名字。少了前缀就少了「别的子域不能覆写」这层保护——但明文部署本来也不该对外服务，
   * 这里的取舍是把「能用」放在「多一层浏览器侧护栏」之前，并把这件事记在 {@link insecure} 上。
   */
  ck(name:'relay-login'|'relay-account'|'relay-bind'|'relay-host',req?:IncomingMessage){return this.secure(req)?`__Host-${name}`:name;}
  /** 组装 Set-Cookie。明文下不带 `Secure`，否则浏览器整条丢弃。 */
  private setCookie(name:'relay-login'|'relay-account'|'relay-bind'|'relay-host',value:string,maxAge:number,req?:IncomingMessage){
    return `${this.ck(name,req)}=${value}; Path=/; ${this.secure(req)?'Secure; ':''}HttpOnly; SameSite=Lax; Max-Age=${maxAge}`;
  }
  /**
   * 本次请求是不是走 HTTPS（决定 `Secure` 与 `__Host-` 前缀）。
   *
   * <p>钉住地址时是常量，与以前完全一致。未钉住时只能看请求：`X-Forwarded-Proto`
   * 优先（反代终止 TLS 时唯一线索），其次是 socket 自己有没有加密。取不到就按明文——
   * 这个方向的错误是「少一层浏览器护栏但能用」，反过来（在 http 页面上发 `Secure`
   * Cookie）会让浏览器**整条丢弃**，表现为登录成功却立刻掉回登录页，更难查。
   */
  private secure(req?:IncomingMessage){
    if(!req)return !this.insecure;
    if(this.pinned)return !this.insecure;
    return this.protoOf(req)==='https';
  }
  private protoOf(req:IncomingMessage):'http'|'https'{
    if(this.pinned)return this.insecure?'http':'https';
    const first=String(req.headers['x-forwarded-proto']??'').split(',')[0]!.trim().toLowerCase();
    if(first==='https')return 'https';
    if(first==='http')return 'http';
    return (req.socket as {encrypted?:boolean}).encrypted?'https':'http';
  }
  /**
   * 本次请求的对外 origin。
   *
   * <p>只用于「非拼成绝对 URL 就发不出去」的地方：手机扫的配对二维码、`dsh://pair` 深链。
   * **重定向一律用相对路径**——`Host` 头由客户端控制，拿它拼 Location 等于开一个
   * 开放重定向，钉住地址时更是没必要。
   */
  originOf(req?:IncomingMessage):string{
    if(this.origin)return this.origin;
    const host=req?.headers.host;
    if(!host)throw new HttpError(400,'host');
    return `${this.protoOf(req!)}://${host}`;
  }
  /**
   * 某台主机在浏览器里的基地址。
   *
   * <p>子域模式是 `https://<hostId>.<域名>`；路径模式（裸 IP 唯一可行形态）是
   * `<origin>/h/<hostId>`。所有「跳进主机」的链接都必须走这里，否则裸 IP 部署下
   * 浏览器会去解析 `1.2.3.4` 的「子域」——那不是合法主机名，直接 DNS 失败。
   */
  hostBase(hostId:string,req?:IncomingMessage){return this.routing==='path'?`${this.originOf(req)}/h/${hostId}`:`https://${hostId}.${new URL(this.origin).host}`;}
  /**
   * 某台主机的浏览器 Origin。
   *
   * <p>路径模式下所有主机共用同一个 origin（路径不参与 Origin 头），因此返回值与
   * `this.origin` 相同——`checkOrigin` 比较的是 Origin 头，它永远不含路径。
   */
  hostOrigin(hostId:string,req?:IncomingMessage){return this.routing==='path'?this.originOf(req):this.hostBase(hostId,req);}
  /**
   * Advance the legacy->v2 migration by one chunk and reschedule itself.
   *
   * <p>Single-flight by construction (`backfill.running`): the hourly prune hook and
   * the constructor both call it, and two interleaved passes would race on the turn
   * carry map for no benefit. Stops as soon as a chunk reports `done`, or on the
   * first error - a migration that keeps retrying a broken row never finishes and
   * burns CPU on a machine that has none to spare.
   */
  private startBackfill():void{
    // Emergency lever: a migration that misbehaves must be stoppable without
    // redeploying the binary, on a host that may not even accept an SSH session.
    if(process.env.RELAY_BACKFILL==='off'){this.backfill.done=true;return;}
    if(this.backfill.running)return;
    // `done` is reset before every scheduled run (see the hourly hook): a v1 client
    // may still be pushing to `/control/archive` after the first pass finished, and
    // those rows would otherwise never reach the v2 timeline. Within one run it stays
    // set so a second caller cannot restart the scan it just finished.
    if(this.backfill.done)return;
    this.backfill.running=true;
    let cursor:BackfillCursor|undefined;
    let first=true;
    const step=()=>{
      if(first){first=false;}
      try{
        const result=this.store.backfillTimeline({after:cursor,limit:BACKFILL_CHUNK,maxBytes:BACKFILL_MAX_BYTES});
        this.backfill.copied+=result.copied;
        this.backfill.chunks++;
        if(result.done){this.backfill.done=true;this.backfill.running=false;return;}
        cursor=result.cursor;
        // No cursor means the SELECT returned nothing usable (undecryptable rows):
        // treat it as done rather than spinning on the same page forever.
        if(!cursor){this.backfill.done=true;this.backfill.running=false;return;}
        // A real pause, not `0`: the box has two cores and is also serving phones,
        // so the migration must lose every race it enters with live traffic.
        setTimeout(step,BACKFILL_PAUSE_MS).unref();
      }catch{
        this.backfill.failures++;
        this.backfill.running=false;
      }
    };
    setTimeout(step,BACKFILL_FIRST_DELAY_MS).unref();
  }

  /** Rate-limit key: the real client address, not the proxy in front of it (see clientIp). */
  private ip(req:IncomingMessage){return clientIp(req,this.config.trustedProxy,this.config.testMode,this.config.trustAnyPeer);}
  /**
   * 把这一批归档记录折成「每会话一条」的变更。
   *
   * <p>同一批里一个会话可能有多条记录（一轮对话的若干事件），手机只需要知道
   * 「最新到哪了 + 首行是什么」，所以按 sessionId 归并、取最大 seq。
   */
  private changesOf(hostId:string,records:Array<{sessionId:string;seq:number;record:unknown}>):Change[]{
    if(!records||records.length===0)return [];
    const latest=new Map<string,number>();
    // 按会话归并本批记录：这批已经是插件按归档口径过滤过的正文，直接回推给手机
    // 就是可渲染的数据，不必让手机再发一次 /sync 去同一张表里读一遍。
    const bodies=new Map<string,Array<{seq:number;record:unknown}>>();
    for(const r of records){
      if(!r||typeof r.sessionId!=='string'||!r.sessionId)continue;
      const prev=latest.get(r.sessionId);
      if(prev===undefined||r.seq>prev)latest.set(r.sessionId,r.seq);
      const list=bodies.get(r.sessionId);
      if(list)list.push({seq:r.seq,record:r.record});
      else bodies.set(r.sessionId,[{seq:r.seq,record:r.record}]);
    }
    const out:Change[]=[];
    const now=Date.now();
    let budget=CHANGE_BODY_BUDGET;
    for(const [sessionId,seq]of latest){
      // 标题/运行态仍走元数据；首行预览由手机用本地已落盘的消息现算，不走这条旁路。
      const meta=this.store.archiveItem(hostId,sessionId);
      const change:Change={sessionId,latestSeq:seq,title:meta?.title??'',running:meta?.running??false,at:now};
      const list=(bodies.get(sessionId)??[]).sort((a,b)=>a.seq-b.seq);
      if(list.length>0&&budget>0){
        // 逐条序列化累加：只认「放得下」的整条，绝不截半个 JSON（手机端按整条解析）。
        const keep:unknown[]=[];let used=0;let truncated=false;
        for(const item of list){
          let size:number;
          try{size=JSON.stringify(item.record)?.length??0;}catch{truncated=true;break;}
          if(keep.length>0&&used+size>CHANGE_BODY_PER_SESSION){truncated=true;break;}
          if(used+size>budget){truncated=true;break;}
          keep.push(item.record);used+=size;
        }
        if(keep.length>0){change.records=keep;budget-=used;}
        // 少推了就明确告诉手机「还得补一次 /sync」，别让它以为这就是全部。
        // 全都装得下时不下发这个字段——省掉每帧一个恒定 false。
        if(truncated||keep.length<list.length)change.truncated=true;
      }
      out.push(change);
    }
    return out;
  }
  /**
   * 判定这条请求属于账户面还是某台主机，并**就地改写 `req.url`**。
   *
   * <p>两条寻址路径的差别只在这里：
   * <ul>
   *   <li>子域：`Host: <hostId>.<域名>` —— 主机 id 在 Host 头里，路径原样不动。</li>
   *   <li>路径：`/h/<hostId>/…` —— 主机 id 在路径前缀里，剥掉前缀后交给下游，
   *       于是 `proxy.http` / `proxy.upgrade` / `/__relay/*` 都不必知道自己活在哪种模式。</li>
   * </ul>
   *
   * <p>裸 IP 部署下 Host 头就是 `1.2.3.4:8080`，永远不可能带子域，所以路径模式不是
   * 「另一种风格」而是**唯一可行形态**。
   */
  private target(req:IncomingMessage){
    const remote=req.socket.remoteAddress??'';
    if(!trustedPeer(remote,this.config.trustedProxy,this.config.testMode,this.config.trustAnyPeer))throw new HttpError(403,'untrusted-proxy');
    const h=req.headers.host;if(!h)throw new HttpError(400,'host');
    if(this.routing==='path'){
      // 前缀必须整段命中：`/h/<32 位 hex>` 后面要么直接结束，要么接 `/`。
      // 不做「模糊匹配」，否则 `/h/<id>evil` 也会被当成那台主机。
      const m=/^\/h\/([a-f0-9]{32})(?=\/|$)(.*)$/.exec(req.url??'/');
      if(m){req.url=m[2]||'/';return m[1]!;}
      // 账户面在路径模式下只认根 origin 上的路径；带 `/h/` 但 id 不合法的一律 404，
      // 免得把「打错的链接」当成账户页去渲染。
      if((req.url??'').startsWith('/h/'))throw new HttpError(404,'not-found');
      return 'account';
    }
    const base=new URL(this.origin).host;
    if(h===base)return 'account';const suffix='.'+base;if(h.endsWith(suffix)){const id=h.slice(0,-suffix.length);if(/^[a-f0-9]{32}$/.test(id))return id;}throw new HttpError(404,'not-found');
  }
  /**
   * 校验浏览器送来的 `Origin` 头。
   *
   * <p>钉住地址时严格相等（原行为）。未钉地址时只比 host：TLS 在反代处终止，中继看到的
   * 永远是 `http:`，而浏览器 Origin 头写的是 `https:`——死比 scheme 会把每一次正常提交
   * 都判成 403。host 才是这条校验真正要防的东西（别的站点的页面来打接口），scheme 在
   * 这个位置没有防御价值。
   */
  private originCheck(req:IncomingMessage,expected?:string){
    const want=expected??this.originOf(req);
    const got=req.headers.origin;
    if(got===want)return;
    if(!this.pinned&&got&&this.sameHost(got,want))return;
    throw new HttpError(403,'origin');
  }
  private sameHost(a:string,b:string){
    const host=(v:string)=>{try{const u=new URL(v);const dflt=u.protocol==='https:'?'443':u.protocol==='http:'?'80':'';return (u.port&&u.port!==dflt?`${u.hostname}:${u.port}`:u.hostname).toLowerCase();}catch{return '';}};
    const x=host(a);return x!==''&&x===host(b);
  }
  private nativeOnly(req:IncomingMessage){if(req.headers.origin||req.headers['sec-fetch-site'])throw new HttpError(403,'native-client-required');}
  /**
   * `Authorization: Bearer <token>` 里的 token。
   *
   * <p>**不再锁死 43 位**（2026-10-27 按用户要求改）：43 位是中继自己用
   * `secret(32)` 签发的邀请/隧道凭据的长度，但部署方也可以在环境里配自定义密码，
   * 那种密码由用户自己定长度。这里只做「非空且不含控制/空白字符」的格式检查，
   * 真正的有效性一律由 `store.session()` / `store.authenticate()` 的哈希比对判定。
   */
  // ============================================================
  // APK 发布：/control/release 上传，{@link RELEASE_PREFIX} 下发
  // ============================================================

  /**
   * 三个 release 路由共用的主机凭据校验。
   *
   * <p>用 `hostId + hostTunnelToken` 而不是新开一个 `RELAY_RELEASE_TOKEN`：发布者就是
   * 那台跑着 DSH 的机器，它的隧道凭据本来就在本地（`~/.dsh/.credentials.yaml`），
   * 发布脚本一条命令就能取到；撤销隧道凭据即同时撤销发布能力，不需要多记一个密钥。
   */
  private releaseHost(req:IncomingMessage,url:URL){
    const hostId=url.searchParams.get('hostId')??'';
    if(!/^[a-f0-9]{32}$/.test(hostId))throw new HttpError(403,'release-rejected');
    return this.store.authenticate(hostId,this.bearer(req));
  }

  /**
   * `POST /control/release?hostId=…&version=…&notes=…` —— 上传一个安装包。
   *
   * <p>请求体是**裸 APK 字节**（`curl --data-binary @app-release.apk`），元数据走查询串。
   * 不用 multipart：这里只有一个二进制 + 三个短字符串，multipart 的边界解析没有收益，
   * 却会让「上传」这件事在脚本里多一层拼装。
   *
   * <p>顺序是「先落盘、再写台账」：台账是清单的来源，清单是「这个版本可以下了」的唯一
   * 信号。反过来的话，一次成功的清单写入可能指向一个还不存在的文件。
   */
  private async controlRelease(req:IncomingMessage,res:ServerResponse,url:URL,method:string){
    const host=this.releaseHost(req,url);
    const json=(value:unknown)=>{res.writeHead(200,{'content-type':'application/json','cache-control':'no-store'});res.end(JSON.stringify(value));};
    const base=this.originOf(req)+RELEASE_PREFIX;
    if(method==='GET'){
      json({items:this.store.releaseList(50).map(r=>manifestOf(r,base)),keep:this.releaseKeep,prefix:base});
      return;
    }
    if(method==='DELETE'){
      const version=(url.searchParams.get('version')??'').trim();
      if(!isValidVersion(version))throw new HttpError(400,'release-version');
      this.store.releaseDelete(version);
      try{unlinkSync(join(this.releaseDir,releaseFileName(version)));}catch{}
      json({ok:true,version});return;
    }
    if(method!=='POST')throw new HttpError(405,'method');
    // 与 /control/pair 同档：发布是低频人工动作，限流只防「脚本写错在循环里刷」。
    this.store.rate('release:'+host.id,20,3600000);
    const version=(url.searchParams.get('version')??'').trim();
    if(!isValidVersion(version))throw new HttpError(400,'release-version');
    const notes=normalizeNotes(url.searchParams.get('notes')??'');
    const minSupported=(url.searchParams.get('minSupported')??'').trim();
    if(minSupported&&!isValidVersion(minSupported))throw new HttpError(400,'release-min-supported');
    const payload=await rawBody(req,this.releaseMaxBytes);
    if(payload.length===0)throw new HttpError(400,'release-empty');
    const sha256=createHash('sha256').update(payload).digest('hex');
    // 客户端声明的摘要只是**交叉校验**：脚本算过一遍就传上来，服务端再算一遍，
    // 不一致说明传输被截断（或者两个文件根本不是同一个）。不声明也接受。
    const claimed=(req.headers['x-content-sha256']??'').toString().trim().toLowerCase();
    if(claimed&&claimed!==sha256)throw new HttpError(400,'release-sha256-mismatch');
    mkdirSync(this.releaseDir,{recursive:true});
    const fileName=releaseFileName(version);
    const target=join(this.releaseDir,fileName);
    // 先写 .tmp 再 rename：中途被杀不会在卷上留一个「看着能下、装上就崩」的残包。
    writeFileSync(`${target}.tmp`,payload,{mode:0o644});
    renameSync(`${target}.tmp`,target);
    const publishedAt=publishedAtOf(Date.now());
    this.store.releasePut({
      version,file_name:fileName,byte_size:payload.length,sha256,notes,
      min_supported:minSupported,published_at:publishedAt,
      account_id:(host.account_id as string)??null,
    });
    const pruned=this.store.releaseTrim(this.releaseKeep);
    for(const v of pruned){try{unlinkSync(join(this.releaseDir,releaseFileName(v)));}catch{}}
    json({
      ok:true,version,size:payload.length,sha256,publishedAt,
      url:`${base}/${fileName}`,manifest:`${base}/version.json`,pruned,
    });
  }

  /**
   * `GET {RELEASE_PREFIX}/version.json` 与 `…/<文件名>`。
   *
   * <p>支持 Range：手机的更新器就是按 Range 续传的（`RelayApi.rawBytes` 分块落盘），
   * 一批 200 MB 的家宽链路上断一次就得从头下，体验上等于「更新失败」。
   */
  private serveRelease(req:IncomingMessage,res:ServerResponse,path:string){
    const name=path.slice(RELEASE_PREFIX.length+1);
    if(name==='version.json'){
      const latest=this.store.releaseLatest();
      if(!latest){res.writeHead(404,{'content-type':'application/json','cache-control':'no-store'});res.end('{"error":"release-missing"}');return;}
      res.writeHead(200,{'content-type':'application/json; charset=utf-8','cache-control':'no-store'});
      res.end(JSON.stringify(manifestOf(latest,this.originOf(req)+RELEASE_PREFIX)));return;
    }
    const row=name===RELEASE_LATEST_ALIAS
      ?this.store.releaseLatest()
      :this.store.get('SELECT * FROM releases WHERE file_name=?',name);
    if(!row){res.writeHead(404,{'content-type':'application/json','cache-control':'no-store'});res.end('{"error":"release-missing"}');return;}
    const file=join(this.releaseDir,String(row.file_name));
    let size:number;
    try{size=statSync(file).size;}
    catch{res.writeHead(404,{'content-type':'application/json','cache-control':'no-store'});res.end('{"error":"release-file-missing"}');return;}
    const range=parseRange(req.headers.range,size);
    if(range===null){res.writeHead(416,{'content-range':`bytes */${size}`,'cache-control':'no-store'});res.end();return;}
    const start=range?range.start:0,end=range?range.end:size-1;
    // 只有「从头开始的那次请求」记一次下载：续传的 206 会再来好几趟，按请求计数会把
    // 一次安装数成七八次。
    if(start===0)this.store.releaseCountDownload(String(row.version));
    // 安装包内容不可变（同名重传会换 sha256，清单随之更新），所以可以放心让中间层
    // 缓存几百秒：手机断网重试时少打一次中继。
    res.writeHead(range?206:200,{
      'content-type':'application/vnd.android.package-archive',
      'content-length':String(end-start+1),
      'accept-ranges':'bytes',
      'cache-control':'public, max-age=300',
      ...(range?{'content-range':`bytes ${start}-${end}/${size}`}:{}),
    });
    const stream=createReadStream(file,{start,end});
    stream.on('error',()=>{res.destroy();});
    stream.pipe(res);
  }
  private bearer(req:IncomingMessage){const token=/^Bearer ([^\s\u0000-\u001f\u007f]{1,512})$/.exec(req.headers.authorization??'')?.[1];if(!token)throw new HttpError(401,'bearer-required');return token;}
  private account(req:IncomingMessage){return this.store.session(cookies(req)[this.ck('relay-account',req)]??'','account');}
  private csrf(req:IncomingMessage,s:Row,b:Record<string,string>,recent=false){this.originCheck(req);this.store.csrf(s,b.csrf??'',recent);}
  private refreshCsrf(s:Row){const csrf=secret();this.store.run('UPDATE sessions SET csrf_hash=? WHERE id_hash=?',hash(csrf),s.id_hash);return csrf;}
  private redirect(res:ServerResponse,to:string,set?:string[]){res.writeHead(303,{location:to,'cache-control':'no-store','referrer-policy':'no-referrer',...(set?{'set-cookie':set}:{})});res.end();}
  private async handle(req:IncomingMessage,res:ServerResponse){
    const target=this.target(req),url=new URL(req.url??'/',this.urlBase),path=url.pathname,method=req.method??'GET';
    res.setHeader('x-content-type-options','nosniff');res.setHeader('referrer-policy','no-referrer');
    if(target!=='account'){
      if(path==='/__relay/start'&&method==='GET'){
        this.store.rate('handoff:'+this.ip(req),...RATE.handoff);const r=this.store.startHandoff(target);
        // 路径模式下账户面与主机面同 origin，用**相对** Location 就够了（也更安全：
        // Host 头由客户端控制，拿它拼绝对地址就是个开放重定向）。子域模式下这一步要
        // 跨到账户域，只能写绝对地址，且必须是钉住的 origin 而不是本次请求的 Host。
        this.redirect(res,this.routing==='path'?`/open?requestId=${r.requestId}`:`${this.origin}/open?requestId=${r.requestId}`,[this.setCookie('relay-bind',r.bind,300,req)]);return;
      }
      if(path==='/__relay/session'&&method==='POST'){
        // `sec-fetch-site` 在子域模式是 `same-site`（账户域 → 主机子域），路径模式则是
        // `same-origin`（同一个 origin，只有路径不同）。两者都是「浏览器自己发起的导航」，
        // 必须都放行；放行别的值就等于允许跨站表单打进来。
        // Origin 校验比的是**账户页**的 origin：这个表单是在账户页渲染、由账户页提交的，
        // 只是 action 指向主机地址。路径模式下 hostOrigin 与 this.origin 相同，子域模式
        // 下两者不同，所以这里必须用 this.origin。
        this.originCheck(req);const site=req.headers['sec-fetch-site'];if(site&&site!=='same-site'&&site!=='same-origin'||req.headers['sec-fetch-mode']!=='navigate'||req.headers['sec-fetch-dest']!=='document')throw new HttpError(403,'navigation-required');
        const b=await body(req),token=this.store.consumeTicket(target,b.ticket??'',cookies(req)[this.ck('relay-bind',req)]??'');
        // 路径模式下 `'/'` 是账户首页，进主机必须回到 `/h/<id>`；子域模式下 hostBase 是
        // 绝对地址，Location 同样合法。所以两种模式共用一条写法。
        this.redirect(res,this.hostBase(target,req),[this.setCookie('relay-host',token,8*3600,req),this.setCookie('relay-bind','',0,req)]);return;
      }
      let s:Row;try{s=this.store.session(cookies(req)[this.ck('relay-host',req)]??'','host',target);}catch(e){if(path==='/'&&method==='GET'&&e instanceof HttpError&&e.status===401){this.redirect(res,this.hostBase(target,req)+'/__relay/start');return;}throw e;}
      await this.proxy.http(req,res,target,s);return;
    }
    if(path==='/healthz'&&method==='GET'){res.writeHead(200,{'content-type':'text/plain','cache-control':'no-store'});res.end('ok');return;}
    // APK 下载面：公开可读。手机检查更新时**还没登录**（首次安装后第一件事就是查更新），
    // 所以这里不能要求任何凭据；能公开的代价只是「谁能访问中继谁就能下安装包」。
    // 上传走 /control/release，那里才要主机凭据。
    if(path.startsWith(RELEASE_PREFIX+'/')&&method==='GET'){this.serveRelease(req,res,path);return;}
    if(path==='/metrics'&&method==='GET'){
      // Ops surface for a protected management plane: host liveness, capacity and audit health.
      // Not exposed on account/host subdomains and not part of the phone contract.
      // Fail closed: without a configured token the only caller that may read this is the
      // test harness — an open endpoint leaks host inventory and online hours to anyone.
      // Bearer 的字符集只排除空白，不再限定 base64url：部署方常直接复用那个带 @ / : 的
      // 中继密码当 token，限死字符集会让「填了正确的 token 仍然 401」，而 401 的 reason
      // 是同一个 metrics-token，看不出是格式问题。长度下限 16 仍然保留。
      if(this.config.metricsToken){const m=/^Bearer (\S{16,128})$/.exec(req.headers.authorization??'')?.[1];if(!m||!equal(m,this.config.metricsToken))throw new HttpError(401,'metrics-token');}
      else if(!this.config.testMode)throw new HttpError(401,'metrics-token-required');
      const peers=[...this.proxy.peers.values()];
      const hosts=this.store.all('SELECT id FROM hosts WHERE revoked=0').map(h=>({hostId:h.id,online:this.proxy.online(h.id)}));
      const active=this.proxy.peers.size;
      // SSE 写扩散的健康面：在线订阅数、累计丢弃条数、累计真实背压次数。
      // drains 是「res.write() 返回 false」的次数——只有它代表 socket 真的堵了；
      // dropped 是堵住期间被丢弃的**通知**（归档记录一条不丢，手机按游标补拉）。
      const {online:sseOnline,dropped:changeDropped,drains:changeDrains}=this.changes.stats();
      const auditFailures=this.store.auditFailures;
      const pruneFailures=this.store.pruneFailures;
      const auditRows=this.store.get('SELECT count(*) n FROM audit')?.n??0;
      // 宿主侧指标（中期-12）：插件上报，中继只做最新值缓存。
      // `eventLoop.p99Ms` 是判断「502 是宿主卡住还是链路抖动」的唯一单一指标：
      // 502 时刻 p99 同时冲高 → 宿主事件循环；p99 平稳而 loopback.failures 上涨
      // → loopback 连接被重置（keep-alive 死 socket / 宿主重启）。
      // `ageMs` 必须一起看：超过两三个上报周期说明插件已经不在了，字段是过期数据。
      const now=Date.now();
      const hostMetrics=hosts.map(h=>{
        const m=this.hostMetrics.get(h.hostId);
        return m?{hostId:h.hostId,...m,ageMs:now-m.receivedAt}:{hostId:h.hostId,missing:true};
      });
      // /sync 的轮次与浪费：手机侧只会表现为「消息晚到」，看不出是发得太多还是
      // 每次都没进展。zeroProgress 与 repeats 稳态下都应为 0。
      const syncStats=this.syncStats.snapshot();
      // p95 over the ring, computed here rather than maintained incrementally: 256
      // samples sort in microseconds and an exact number is worth more than an
      // estimator nobody can reproduce from the raw counts.
      const writes=this.archiveWrites;
      const sorted=writes.recent.length?[...writes.recent].sort((a,b)=>a-b):[];
      const p95=sorted.length?sorted[Math.min(sorted.length-1,Math.floor(sorted.length*0.95))]:0;
      const archiveWriteMs={count:writes.count,failures:writes.failures,samples:sorted.length,p95,last:sorted.length?sorted[sorted.length-1]:0};
      const body=JSON.stringify({hosts,active,sseOnline,changeDropped,changeDrains,auditFailures,pruneFailures,auditRows,hostMetrics,syncStats,syncV2:this.syncStatsV2,syncLoopEntries:this.syncLoop.size(),backfill:this.backfill,archiveWriteMs,uptimeSeconds:Math.floor(process.uptime())},null,0);
      res.writeHead(200,{'content-type':'application/json','cache-control':'no-store'});res.end(body);return;
    }
    // v1 面已整体下线，而且必须是**显式的 410**，不能放任它掉进下面「按主机路由」
    // 的兜底分支——那条分支会拿不到 `relay-host` cookie 而回 401 `session-required`，
    // 老 App 会把 401 读成「登录过期」，静默清凭据并逼用户重新配对，而不是升级。
    // 410 是唯一诚实的答复：这条路没有了，请换版本。
    if(path.startsWith('/native/v1/'))throw new HttpError(410,'v1-gone');
    if(path.startsWith('/native/v2/')){await this.nativeV2(req,res,path,method);return;}
    if(path==='/control/archive')throw new HttpError(410,'v1-gone');
    if(path==='/control/enroll'&&method==='POST'){
      this.store.rate('enroll:global',100,3600000);this.store.rate('enroll:ip:'+this.ip(req),10,3600000);
      // 凭据不再锁 43 位：允许部署方自定义长度（见 bearer() 的注释）。
      const token=/^Bearer ([^\s\u0000-\u001f\u007f]{1,512})$/.exec(req.headers.authorization??'')?.[1];if(!token)throw new HttpError(403,'enroll-rejected');
      const b=z.strictObject({operationId:z.string().regex(/^[a-f0-9]{32}$/),hostPublicId:z.string().regex(/^[a-f0-9]{32}$/),hostTunnelToken:z.string().regex(/^[A-Za-z0-9_-]{43}$/),alias:z.string().min(1).max(80),platform:z.string().max(32).optional()}).parse(await body(req));
      const invitation=this.store.get('SELECT account_id FROM invitations WHERE hash=?',hash(token));if(invitation)this.store.rate('enroll:account:'+invitation.account_id,10,3600000);
      // 两条注册路径，凭据二选一：
      // ① 一次性邀请（中继网页签发，5 分钟、用后即焚）；
      // ② 部署方在环境里配的自定义密码（RELAY_ENROLL_PASSWORD），可重复使用。
      // 自定义密码走**定长比较**，避免用响应时间逐字符试探；且只能注册到
      // RELAY_ENROLL_ACCOUNT 指定的那一个账号，不是任意账号。
      const custom=this.config.enrollPassword;
      if(custom&&this.config.enrollAccount&&equal(token,custom)){
        const accountId=this.store.accountIdForEnroll(this.config.enrollAccount);
        if(!accountId){this.store.audit(null,'host-enrolled','enroll-account-missing','failed');throw new HttpError(403,'enroll-rejected');}
        this.store.rate('enroll:account:'+accountId,30,3600000);
        const result=this.store.enrollWithAccount(accountId,b);
        res.writeHead(200,{'content-type':'application/json','cache-control':'no-store'});res.end(JSON.stringify(result));return;
      }
      const result=this.store.enroll(token,b);res.writeHead(200,{'content-type':'application/json','cache-control':'no-store'});res.end(JSON.stringify(result));return;
    }
    if(path==='/control/pair'&&method==='POST'){
      // 主机自助签发「一码配齐」：设备登录码 + 主机配对码一次下发。
      //
      // 为什么必须在中继侧算：配对码的作用是「在这台主机所属的账号下给一台新设备写
      // grant」，只有中继知道 host → account 的归属，也只有中继的 pairs 表是手机端
      // /native\/v2\/pair 真正会查的那张表。插件本地造一个码没有任何一方会认。
      //
      // 登录码同批签发（2026-10-27 按用户要求改）：旧流程手机要先在中继网页生成登录码、
      // 再扫主机配对码，扫两个码。现在插件点一次「生成二维码」，中继把两个码一起给出，
      // 插件拼进同一个二维码——手机扫一下就完成「登录 + 配对」，登录码全程不进用户视线。
      //
      // 鉴权沿用隧道凭据（hostId + hostTunnelToken），与 /control/archive 同一对、
      // 同一失败语义：不引入新密钥，撤销隧道凭据即同时撤销签码能力。
      const token=/^Bearer ([^\s\u0000-\u001f\u007f]{1,512})$/.exec(req.headers.authorization??'')?.[1];
      if(!token)throw new HttpError(403,'pair-rejected');
      const b=z.strictObject({hostId:z.string().regex(/^[a-f0-9]{32}$/)}).parse(await body(req));
      // 与网页 /pair/create 同一档限流（5 分钟 10 次），防「连点刷新」把 pairs 表刷爆。
      this.store.rate('pair-create:'+b.hostId,10,300000);
      const created=this.store.createPairForHost(b.hostId,token);
      // 登录码按主机的账号签发：手机兑换后落在同一账号，与配对码指向的主机天然同门。
      // TTL 与配对码同窗（5 分钟），一码里的两个成分不会一先一后失效。
      const loginCode=this.store.createDeviceCodeForAccount(created.accountId,'手机扫码',300000);
      res.writeHead(200,{'content-type':'application/json','cache-control':'no-store'});
      res.end(JSON.stringify({...created,login:loginCode}));return;
    }
    if(path==='/control/timeline'&&method==='POST'){
      // v2 archive push: the plugin's outbox sends TimelineRecords it has already
      // classified, and this route replaces `/control/archive` for every v2 plugin.
      //
      // <p>Why a new route instead of extending `/control/archive`: the payload shape
      // is different (ordering witnesses, kind, byte size, optional blob) and the
      // idempotency contract is different (a batch id makes a retry a no-op). An old
      // relay would silently drop the extra fields — which the phone would then read
      // as "this record has no prevSeq" — so version skew has to be a 404, not a
      // partial accept.
      //
      // <p>`batchId` is what lets the plugin retry blindly. It is derived from the
      // batch contents rather than a random id, so a retry after a timeout uses the
      // same value and `archive_batches` recognises it even if only half the rows
      // landed the first time.
      const token=/^Bearer ([^\s\u0000-\u001f\u007f]{1,512})$/.exec(req.headers.authorization??'')?.[1];
      if(!token)throw new HttpError(403,'archive-rejected');
      const b=z.object({
        hostId:z.string().regex(/^[a-f0-9]{32}$/),
        batchId:z.string().min(1).max(128),
        records:z.array(z.object({
          sessionId:z.string().min(1).max(128),
          seq:z.number().int().nonnegative(),
          prevSeq:z.number().int().optional(),
          turnId:z.string().max(128).optional(),
          kind:z.string().max(48).optional(),
          timestampMs:z.number().optional(),
          byteSize:z.number().int().nonnegative().optional(),
          blobId:z.string().max(200).optional(),
          record:z.unknown(),
          payload:z.unknown().optional(),
        })).max(2000),
        meta:z.array(z.object({sessionId:z.string().min(1).max(128),item:z.unknown()})).max(300).optional(),
      }).parse(await jsonBody(req,24*1024*1024));
      this.store.authenticate(b.hostId,token);
      this.store.rate('archive:'+b.hostId,...RATE.archive);
      const started=Date.now();
      const {accepted,duplicate}=this.store.archiveTimeline(b.hostId,b.batchId,b.records.map(r=>({
        sessionId:r.sessionId,seq:r.seq,prevSeq:r.prevSeq??-1,turnId:r.turnId??'',
        kind:r.kind??'',timestampMs:r.timestampMs??Date.now(),byteSize:r.byteSize??0,
        blobId:r.blobId,record:r.record,payload:r.payload,
      })));
      // Timed around the store call only: the numbers this answers are "is the writer
      // lock contended" and "is the table growing out of its page cache", not "how slow
      // is the network". Recorded before the summary query so a slow summary does not
      // masquerade as a slow write.
      this.archiveWrites.count++;
      const recent=this.archiveWrites.recent;
      recent.push(Date.now()-started);
      if(recent.length>256)recent.shift();
      if(b.meta?.length)this.store.archiveMeta(b.hostId,b.meta);
      const sessions:Record<string,number>={};
      for(const row of this.store.timelineSummary(b.hostId))sessions[String(row.sessionId)]=Number(row.latestSeq)||0;
      // **只广播「有事发生」，不捎正文**。
      //
      // 早先这里完全不 publish，理由是「v2 删了 SSE change 总线，只有 v1 app 会读，
      // 而 v1 app 渲染不了 v2 行」——那句话对**正文**成立，对**信号**不成立：
      // v2 手机仍然开着 `/native\/v2\/changes`（`AppViewModel.openStreams` →
      // `gateway.watchChanges`），它靠这一帧触发 `requestSessionSync` 再走 `/sync`
      // 拉正文。不 publish 的后果是**列表页永远不刷新**：只有进聊天页、控制流
      // 聚焦之后才会有数据，退出来才看得到新预览（2026-11-02 用户实测）。
      //
      // 代价已经被 `changesOf` 控制住：这里只发 `{sessionId, latestSeq}`，
      // **不带 `records`**，所以不会有每条记录一次 JSON.stringify 的开销，
      // 也不会撞 `CHANGE_BODY_BUDGET`。手机收到后自己决定要拉哪一段。
      const changes: Change[] = [];
      const latestBySession = new Map<string, number>();
      for (const r of b.records) {
        const prev = latestBySession.get(r.sessionId);
        if (prev === undefined || r.seq > prev) latestBySession.set(r.sessionId, r.seq);
      }
      const at = Date.now();
      for (const [sessionId, latestSeq] of latestBySession) {
        // 标题/运行态仍取 v1 那份元数据（`archiveItem`）——它由插件同时通过
        // `/control/archive` 的 `meta` 推上来，v2 行里没有这两个字段。
        const meta = this.store.archiveItem(b.hostId, sessionId);
        changes.push({
          sessionId,
          latestSeq,
          title: meta?.title ?? '',
          running: meta?.running ?? false,
          at,
        });
      }
      if (changes.length > 0) this.changes.publish(b.hostId, changes);
      res.writeHead(200,{'content-type':'application/json','cache-control':'no-store'});
      res.end(JSON.stringify({ok:true,accepted,duplicate,sessions}));return;
    }
    if(path==='/control/release'){await this.controlRelease(req,res,url,method);return;}
    if(path==='/control/metrics'&&method==='POST'){
      // 宿主侧运行指标（中期-12）：事件循环延迟、loopback 失败率、隧道重连数、归档队列深度。
      //
      // <p>为什么要有这条路由：502 在中继侧永远只表现为 `fail upstream-error` 四个字，
      // 「宿主事件循环卡住」与「loopback 连接被重置」这两种成因在中继那侧长得一模一样。
      // 只有插件进程内看得见，所以由插件主动推上来，中继只存最新一份并合并进 /metrics。
      //
      // <p>为什么不塞进隧道控制帧：`controlSchema` 是 strictObject，旧中继遇到不认识的
      // type 会按 4409 整条断开——为了指标把线上隧道踢下线不可接受。独立 HTTP 路由让
      // 旧中继返回 404、插件静默忽略，天然向后兼容。
      const token=/^Bearer ([^\s\u0000-\u001f\u007f]{1,512})$/.exec(req.headers.authorization??'')?.[1];
      if(!token)throw new HttpError(403,'metrics-rejected');
      // 用 looseObject 而不是 strictObject：字段集必须**能长大**。
      // 插件与中继是分开部署的，新插件带新字段推到旧中继上时，strictObject 会把整份
      // 上报判 400——于是升级窗口里监控集体失明（评审现场正是「连续 400」，而原因
      // 只是多了两个字段）。现在：认识的字段照常校验，不认识的原样收下并保存在
      // `hostMetrics` 里（谁想看都能看），语义不明确的字段由插件侧的
      // `schemaVersion` 判定。反过来「老插件推少了字段」靠每个新字段的 `default(0)`。
      const b=z.looseObject({
        hostId:z.string().regex(/^[a-f0-9]{32}$/),
        windowMs:z.number().int().nonnegative().max(86400000),
        // samples 是采样点数：中继必须能分辨「p99=40ms 但只采到 2 个点」和「采满 240 个点」，
        // 前者是无意义读数。缺了它，strictObject 会把整份上报判 400（实测踩过）。
        eventLoop:z.looseObject({samples:z.number().int().nonnegative(),meanMs:z.number().nonnegative(),p50Ms:z.number().nonnegative(),p99Ms:z.number().nonnegative(),maxMs:z.number().nonnegative()}),
        loopback:z.looseObject({failures:z.number().int().nonnegative(),total:z.number().int().nonnegative(),byCode:z.record(z.string().max(64),z.number().int().nonnegative())}),
        // bufferedPeakBytes / stalls 是「无帧」归因的两个分水岭。旧版插件不上报它们，
        // 所以这里给 default(0)：老插件照样能上报，只是两个新字段读作 0。
        tunnel:z.looseObject({state:z.string().max(32),reconnects:z.number().int().nonnegative(),
          bufferedPeakBytes:z.number().int().nonnegative().default(0),stalls:z.number().int().nonnegative().default(0)}),
        // sending：已出队、尚未被 200 确认的记录条数。queued 掉到 0 而 sending 还挂着
        // = 卡在 HTTP 上；两者一起涨 = 中继收不动。旧插件不上报，给 default(0)。
        // pushMs / batchBytes / pushFailures：归档推送本身的代价。手机侧只会表现为
        // 「消息晚到」，分不清是排队（queued 大）还是中继慢（pushMs 高）。
        // 旧插件不上报，全部给 default(0)。
        archiver:z.looseObject({queued:z.number().int().nonnegative(),inFlight:z.boolean(),
          sending:z.number().int().nonnegative().default(0),
          pushMs:z.number().int().nonnegative().default(0),
          batchBytes:z.number().int().nonnegative().default(0),
          pushFailures:z.number().int().nonnegative().default(0),
          // heldBack：outbox 满了、记录被 hub 扣在内存里的条数。它与 queued 指
          // 向两个相反的方向——queued 涨是上行慢，heldBack 涨是本地容量不够
          // （推送可能一直在成功）。旧插件不上报，给 default(0)。
          heldBack:z.number().int().nonnegative().default(0),
          // renumbered：宿主把 seq 倒回去重编的迹象次数（累计值）。非 0 意味着
          // 归档从那一刻起会静默停止增长——新事件全撞 outbox 主键被丢弃。
          // 旧插件不上报，给 default(0)。
          renumbered:z.number().int().nonnegative().default(0)}),
        uptimeMs:z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),
        at:z.number().int().nonnegative(),
        // 缺省 1：本字段是后加的，升级窗口里老插件不会带它。
        schemaVersion:z.number().int().positive().max(1000).default(1),
      }).parse(await jsonBody(req,64*1024));
      this.store.authenticate(b.hostId,token);
      // 上报频率由插件定（默认 60s），这里只兜底防「被打穿」：同一主机 10 秒最多 5 次。
      this.store.rate('metrics:'+b.hostId,...RATE.metrics);
      // 只留最新一份：指标是「现在多糟」而不是历史序列，存历史要么上时序库要么爆库。
      const {hostId,...rest}=b;
      this.hostMetrics.set(hostId,{...rest,receivedAt:Date.now()});
      res.writeHead(200,{'content-type':'application/json','cache-control':'no-store'});res.end('{"ok":true}');return;
    }
    if(path==='/login'&&method==='GET'){
      const nonce=secret();res.setHeader('set-cookie',this.setCookie('relay-login',nonce,300,req));
      page(res,'登录',form('/login',nonce,'<label>邮箱<input name="email" type="email" required autocomplete="username"></label><label>密码<input name="password" type="password" required autocomplete="current-password"></label><label>动态验证码<input name="code" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}"></label><label>设备名称<input name="label" maxlength="80" value="手机浏览器"></label><button>登录</button>'));return;
    }
    if(path==='/login'&&method==='POST'){
      this.originCheck(req);const b=await body(req),c=cookies(req);if(!b.csrf||c[this.ck('relay-login',req)]!==b.csrf)throw new HttpError(403,'csrf');
      this.store.rate('login:ip:'+this.ip(req),5,60000);this.store.rate('login:email:'+hash(b.email??''),5,60000);
      const r=await this.store.login(b.email??'',b.password??'',b.code??'',this.config.requireTotp,b.label);this.redirect(res,'/',[this.setCookie('relay-account',r.token,8*3600,req),this.setCookie('relay-login','',0,req)]);return;
    }
    let s:Row;try{s=this.account(req);}catch(e){if(method==='GET'){this.redirect(res,'/login');return;}throw e;}
    if(method==='GET'&&(path==='/'||path==='/pair'||path==='/open'||path==='/device-codes')){
      const csrf=this.refreshCsrf(s);
      if(path==='/pair'){
        page(res,'授权这台设备',`<p>授权后，这台设备可以操作主机上的 DSH，包括执行终端命令。仅向你信任的设备授权。</p>${form('/pair',csrf,'<label>配对码<input name="value" id="pair-value" required autocomplete="off"></label><button>确认授权</button>')}` ,"'self'",`const t=location.hash.slice(1);history.replaceState(null,'','/pair');if(t)document.getElementById('pair-value').value=t;`);return;
      }
      if(path==='/open'){
        const r=this.store.handoffRequest(s,url.searchParams.get('requestId')??'');page(res,'打开主机',form('/open',csrf,hidden('requestId',r.id)+'<button>继续进入主机</button>'));return;
      }
      if(path==='/device-codes'){
        page(res,'手机登录配对码',`<p>用配对码登录手机 App：每台设备各生成一个码，填进 App 即可，无需邮箱密码。码 15 分钟内有效、用一次即废；多台设备互不影响，可随时单独撤销。</p>
        ${form('/device-code/create',csrf,'<label>设备名称<input name="label" maxlength="80" placeholder="老大的手机"></label><button>生成配对码</button>')}
        <h2>历史配对码</h2>${this.store.deviceCodes(s).map(c=>{
          const state=c.revoked?'已撤销':c.used?'已使用':c.expires<this.store.now()?'已过期':'待使用';
          const used=c.used&&c.used_by?this.store.get('SELECT label FROM devices WHERE id=?',c.used_by):null;
          return `<article><h2>${esc(c.label)}</h2><p>${state}${used?` · 设备：${esc(used.label)}`:''} · ${new Date(c.created_at).toLocaleString('zh-CN')}</p>${c.used||c.revoked?'':form('/device-code/revoke',csrf,hidden('id',c.id)+'<button>撤销</button>')}</article>`;
        }).join('')||'<p>还没有生成过配对码。</p>'}<p><a href="/">返回</a></p>`);return;
      }
      const hosts=this.store.all('SELECT * FROM hosts WHERE account_id=? AND revoked=0',s.account_id);
      const devices=this.store.all('SELECT * FROM devices WHERE account_id=? AND revoked=0',s.account_id);
      page(res,'你的主机',`<p>当前浏览器需要为每台主机单独配对。<a href="/pair">输入配对码</a></p>${hosts.map(h=>{
        const g=this.store.get('SELECT * FROM grants WHERE host_id=? AND device_id=? AND revoked=0',h.id,s.device_id),online=this.proxy.online(h.id);
        return `<article><h2>${esc(h.alias)}</h2><p>${online?'在线':'离线'}</p>${g?`<a href="${esc(this.hostBase(h.id,req))}/__relay/start">打开主机</a>`:'<small>本设备尚未获得授权</small>'}${form('/pair/create',csrf,hidden('hostId',h.id)+'<button>生成设备配对码</button>')}${form('/revoke',csrf,hidden('kind','host')+hidden('target',h.id)+'<button>撤销主机</button>')}</article>`;
      }).join('')||'<p>还没有主机。创建注册邀请，然后在电脑端配置 Host 插件。</p>'}
      <section>${form('/invitation',csrf,'<button>创建主机注册邀请</button>')} <a href="/device-codes">手机登录配对码</a></section><h2>已登录设备</h2>${devices.map(d=>`<article>${esc(d.label)}${d.id===s.device_id?'（当前设备）':''}${form('/revoke',csrf,hidden('kind','device')+hidden('target',d.id)+'<button>撤销设备</button>')}</article>`).join('')}
      <section>${form('/verify',csrf,'<label>动态验证码<input name="code" inputmode="numeric" required></label><button>重新验证敏感操作</button>')}</section>${form('/logout',csrf,'<button>退出登录</button>')}`);return;
    }
    if(method!=='POST')throw new HttpError(404,'not-found');const b=await body(req);
    this.csrf(req,s,b,['/invitation','/pair/create','/revoke','/device-code/create','/device-code/revoke'].includes(path));
    if(path==='/invitation'){const invitation=this.store.invitation(s);page(res,'主机注册邀请',`<p>5 分钟内有效，仅展示一次。请在电脑端通过一次性 secret 环境输入，避免写入历史或仓库。</p><code>${invitation}</code><p><a href="/">返回</a></p>`);return;}
    if(path==='/pair/create'){
      const p=this.store.createPair(s,b.hostId??''),link=`${this.originOf(req)}/pair#${p.token}`,qr=await QRCode.toDataURL(link,{margin:1});
      page(res,'配对新设备',`<p>配对会授予这台电脑完整 DSH 操作能力。5 分钟内有效，用后失效。</p><p>人工码：<strong>${p.code}</strong></p><img src="${qr}" alt="设备配对二维码"><p><a href="${esc(link)}">在本设备配对</a></p><a href="/">返回</a>`);return;
    }
    if(path==='/device-code/create'){
      const c=this.store.createDeviceCode(s,String(b.label??''));
      // 二维码内容是 App 扫一扫认识的协议：dsh://pair?relay=...&code=...
      // 手机端解析出中继地址和 8 位码就能直接登录，不用手输。
      const payload=`dsh://pair?relay=${encodeURIComponent(this.originOf(req))}&code=${c.code}`;
      const qr=await QRCode.toDataURL(payload,{margin:1,width:260});
      page(res,'手机登录配对码',`<p>用手机 App 的「扫一扫」对准下面的二维码，或者把 8 位码填进登录页。</p>
        <img src="${qr}" alt="配对二维码" style="width:260px;height:260px">
        <p style="font-size:32px;letter-spacing:6px"><strong>${esc(c.code)}</strong></p>
        <p>15 分钟内有效，用一次即废。过期时间：${new Date(c.expires).toLocaleString('zh-CN')}</p>
        <p><a href="/device-codes">返回配对码列表</a></p>`);return;
    }
    if(path==='/device-code/revoke'){this.store.revokeDeviceCode(s,String(b.id??''));this.redirect(res,'/device-codes');return;}
    if(path==='/pair'){this.store.rate('pair:'+s.account_id,10,60000);this.store.rate('pair:ip:'+this.ip(req),10,60000);const hostId=this.store.pair(s,b.value??'');if(!hostId)throw new HttpError(403,'pair-rejected');this.redirect(res,this.hostBase(hostId,req)+'/__relay/start');return;}
    if(path==='/open'){
      const r=this.store.issueTicket(s,b.requestId??''),origin=this.hostOrigin(r.hostId,req);
      // 表单 action 走 hostBase（路径模式下带 `/h/<id>`），而 CSP form-action 与 Origin
      // 校验认的是 origin（不含路径）——两者在子域模式下恰好同值，路径模式下不同。
      page(res,'建立主机会话',`<form id="handoff" method="post" action="${esc(this.hostBase(r.hostId,req))}/__relay/session">${hidden('ticket',r.ticket)}<button>进入主机</button></form>`,origin,"document.getElementById('handoff').submit();");return;
    }
    if(path==='/verify'){this.store.reverify(s,b.code??'',this.config.requireTotp);this.redirect(res,'/');return;}
    if(path==='/revoke'){const kind=z.enum(['device','host','grant','account']).parse(b.kind);this.store.revoke(s,kind,b.target??'',b.hostId);this.redirect(res,'/');return;}
    if(path==='/logout'){this.store.revoke(s,'session',s.id_hash);this.redirect(res,'/login',[this.setCookie('relay-account','',0,req)]);return;}
    throw new HttpError(404,'not-found');
  }
  private async native(req:IncomingMessage,res:ServerResponse,path:string,method:string){
    this.nativeOnly(req);const url=new URL(req.url??'/',this.urlBase);const json=(value:unknown)=>{res.writeHead(200,{'content-type':'application/json','cache-control':'no-store'});res.end(JSON.stringify(value));};
    if(path==='/native/v2/login'&&method==='POST'){
      const b=await body(req);this.store.rate('native-login:'+this.ip(req),5,60000);this.store.rate('login:email:'+hash(b.email??''),5,60000);
      const r=await this.store.login(b.email??'',b.password??'',b.code??'',this.config.requireTotp,b.label??'Android',true);json({token:r.token,expiresIn:NATIVE_TTL/1000,deviceId:r.deviceId});return;
    }
    // 设备配对码登录：手机端唯一的入口，不需要邮箱/密码/TOTP。
    // 必须排在会话校验之前——它本来就是用来换会话的，手上什么都没有。
    // 码等价于账号凭据，所以三道闸：按 IP 限流、全局限流、失败加长封锁。
    if(path==='/native/v2/device-code'&&method==='POST'){
      // `this.ip(req)`, not `req.socket.remoteAddress`: behind nginx the socket address
      // is always 127.0.0.1, so every phone on earth shared one 5/min bucket — five
      // device-code attempts from anyone locked out *all* new installs. The helper is
      // used everywhere else in this file (login, enroll, pair); this line was the
      // lone straggler.
      this.store.rate('device-code:'+this.ip(req),5,60000);
      this.store.rate('device-code:global',60,60000);
      const b=await body(req);
      try{
        const r=this.store.consumeDeviceCode(String(b.code??'').trim(),String(b.label??''));
        json({token:r.token,expiresIn:r.expiresIn,deviceId:r.deviceId});
      }catch(e){
        this.store.rate('device-code-fail:'+req.socket.remoteAddress,10,600000);throw e;
      }
      return;
    }
    // 轮换是唯一允许"会话已过期"仍通过的 native 调用：手机隔夜过期时手上只有这个旧 bearer，
    // 把 rotate 也锁死就等于逼用户重新输密码 + TOTP。
    const s=path==='/native/v2/rotate'&&method==='POST'
      ?this.store.session(this.bearer(req),'native',undefined,{expiredOk:true})
      :this.store.session(this.bearer(req),'native');
    if(path==='/native/v2/rotate'&&method==='POST'){
      // The old bearer is revoked inside; the caller must persist the new one before the next request.
      const token=this.store.rotateNative(s);json({token,expiresIn:NATIVE_TTL/1000});return;
    }
    if(path==='/native/v2/hosts'&&method==='GET'){
      // platform 直接透传库里的白名单值，空串表示这台主机没上报过（老插件），
      // 手机端据此退回通用服务器图标。
      // 在线是「最近真的说过话」而不是「隧道还开着」：电脑关机后 TCP 可能半开，
      // 只看连接状态会让绿点一直亮着。顺手对整个列表补一次探测，下一次拉取就是真相。
      const rows=this.store.all('SELECT id,alias,platform FROM hosts WHERE account_id=? AND revoked=0',s.account_id);
      for(const h of rows)this.proxy.probe(h.id);
      json({items:rows.map(h=>({hostId:h.id,alias:h.alias,platform:h.platform??'',online:this.proxy.online(h.id),authorized:!!this.store.get('SELECT host_id FROM grants WHERE host_id=? AND device_id=? AND revoked=0',h.id,s.device_id)}))});return;
    }
    const changesPath=/^\/native\/v2\/hosts\/([a-f0-9]{32})\/changes$/.exec(path);
    if(changesPath&&method==='GET'){
      // 写扩散的消费端：一条只下行的 SSE 流，插件每推一批归档就带一条变更下来。
      // 手机拿到就渲染，不必再发 /sync —— 这正是 OpenIM 里「推送自带数据」的位置。
      // 断线期间的变更不做重放：手机重连后走一次 /sync 按游标补齐即可。
      this.store.grant(s,changesPath[1]!);this.store.rate('changes:'+s.id_hash,...RATE.changes);
      this.nativeOnly(req);
      streamChanges(req,res,this.changes,changesPath[1]!,s.device_id);
      return;
    }
    if(path==='/native/v2/pair'&&method==='POST'){
      this.store.rate('pair:'+s.account_id,10,60000);const b=await body(req),id=this.store.pair(s,b.code??'');if(!id)throw new HttpError(403,'pair-rejected');json({hostId:id});return;
    }
    // 解绑：这台手机不再能操作那台主机。撤的是 grant（设备↔主机），
    // 不是 device —— 后者会连这台手机的全部主机一起废掉，语义差得远。
    if(path==='/native/v2/unpair'&&method==='POST'){
      const b=await body(req),hostId=String(b.hostId??'');
      if(!/^[a-f0-9]{32}$/.test(hostId))throw new HttpError(400,'host-required');
      this.store.rate('unpair:'+s.account_id,...RATE.unpair);
      this.store.revoke(s,'grant',s.device_id,hostId);
      json({ok:true});return;
    }
    // 会话摘要通道已下线（0.1.20 裁定；2026-10-28 连 archiver 的 snippet 旁路也一起
    // 拆掉）。列表首行改由手机用本地已落盘的消息现算——旁路和正文是两条独立水位，
    // 会出现「列表里已经看到最新一行、点进会话却没这条回复」。旧的 /native\/v2\/digest、
    // /native\/v2\/digests 端点、store.noteSessionMessage 与 archiveItem 的 preview
    // 一并移除；session_digest 表保留在 schema 里只做 prune，老库升级不炸。
    if(path==='/native/v2/logout'&&method==='POST'){this.store.revoke(s,'session',s.id_hash);json({ok:true});return;}
    // 附件传输开关（按设备）。手机端设置页那个「传输附件」开关读写的就是这里：
    // 它控制的不是「本机画不画缩略图」，而是中继要不要为这台设备搬运附件字节。
    // 单独一条端点而不是塞进 rpc 白名单：这条不碰宿主，纯中继策略。
    if(path==='/native/v2/attachments'&&(method==='GET'||method==='POST')){
      if(method==='GET'){json({enabled:this.store.attachmentsAllowed(s)});return;}
      const b=await body(req);
      if(typeof b.enabled!=='boolean')throw new HttpError(400,'enabled-required');
      json({enabled:this.store.setAttachments(s,b.enabled)});return;
    }
    const rpc=/^\/native\/v2\/hosts\/([a-f0-9]{32})\/rpc\/(list|create|prompt|cancel|page|projections|modelCatalog|selectModel|rename|attachment|search|command|updateQueue)$/.exec(path);
    if(rpc&&method==='POST'){
      this.store.grant(s,rpc[1]!);
      // 读图（session/attachment）是附件字节的出口，和 upload 一样受这台设备的开关管辖。
      // 回 403 而不是静默返回空图：手机要能区分「这台设备被关掉了」和「图坏了」。
      if(rpc[2]==='attachment'&&!this.store.attachmentsAllowed(s))throw new HttpError(403,'attachments-disabled');
      // `command` is the slash-command executor (`/permission <preset>` and friends), which lives
      // under the commands service rather than the session one: the phone cannot change a permission
      // preset through `session/prompt`, because a prompt reaches the model as ordinary text and the
      // model just answers that it cannot switch modes. The rest are session RPCs.
      //
      // <p>`eventsResult`（往宿主的 `$events/result` 直接回一个 waterfall 结果）**已删除**：
      // v2 的审批/提问一律走控制通道的 `INTERACTION` 帧，由插件的账本结算。留着这条转发
      // 等于给手机开了一个绕过账本的后门——「点过的卡还能再答一次」正是从这儿来的。
      req.url=rpc[2]==='command'?'/api/commands/execute':'/api/session/'+rpc[2];
      await this.proxy.http(req,res,rpc[1]!,s,true);return;
    }
    // 文件附件：宿主对非图片附件要求先用 `fileUploads/upload` 换一个 receiptId，
    // 再把 {type:'file', receiptId} 塞进 prompt。上游这条路由收原始字节（不是 JSON），
    // 路径是 /api/session/uploadFileBinary?sessionId=…&name=…，所以单独一条转发，
    // 不能并进上面的 rpc 白名单——那一条走的是 session/<method> 的命名。
    const upload=/^\/native\/v2\/hosts\/([a-f0-9]{32})\/upload$/.exec(path);
    if(upload&&method==='POST'){
      this.store.grant(s,upload[1]!);
      // 附件字节的入口，同样受这台设备的开关管辖：关掉之后文件根本不会上传到宿主。
      if(!this.store.attachmentsAllowed(s))throw new HttpError(403,'attachments-disabled');
      const query=new URL(req.url??'/',this.urlBase).searchParams;
      const sessionId=String(query.get('sessionId')??'');
      if(!sessionId)throw new HttpError(400,'session-required');
      // 只放行 sessionId 与 name 两个参数：其余查询串一律不往下透传，
      // 免得手机端借这条路由拼出宿主上别的路径参数。
      const name=query.get('name');
      req.url='/api/session/uploadFileBinary?sessionId='+encodeURIComponent(sessionId)+
        (name?`&name=${encodeURIComponent(name)}`:'');
      // 上游只认 application/octet-stream，手机端带什么类型都盖掉，否则宿主直接 415。
      req.headers['content-type']='application/octet-stream';
      await this.proxy.http(req,res,upload[1]!,s,true);return;
    }
    throw new HttpError(404,'not-found');
  }
  /**
   * `/native/v2/*` — the phone's sync surface.
   *
   * <p>Kept as its own method rather than folded into {@link native} because the
   * two contracts disagree on almost everything that matters: v1 answers with
   * DSH-shaped event envelopes the phone has to re-parse, v2 answers with
   * TimelineRecords that the plugin already classified and that the relay never
   * inspects. Sharing a handler would mean sharing a response writer, and the
   * first thing to leak would be v1's `filterEvent` call.
   *
   * <p>Rate limiting here is deliberately not a request counter — see
   * {@link SyncLoopGuard}. A cold start legitimately pages hundreds of times a
   * minute; only a cursor that does not move is treated as a fault.
   */
  private async nativeV2(req:IncomingMessage,res:ServerResponse,path:string,method:string){
    // 只有这三条是 v2 专属面。其余（登录/设备码/轮换/主机列表/配对/清单以外的宿主
    // 代理）都住在 `native()` 里，v2 未命中时回落过去。
    //
    // <p>**先判路径、再鉴权**：`/native/v2/login` 与 `/native/v2/device-code` 本来
    // 就是用来换 bearer 的，手上什么都没有。把 `this.store.session(...)` 提到最前面
    // 会把它们直接打成 401——迁移当天实测就是这样，登录全线失败。
    const v2Only=/^\/native\/v2\/hosts\/[a-f0-9]{32}\/(sync|sessions)$/.test(path)
      ||/^\/native\/v2\/hosts\/[a-f0-9]{32}\/blobs\//.test(path);
    if(!v2Only){await this.native(req,res,path,method);return;}
    this.nativeOnly(req);
    const json=(value:string)=>{res.writeHead(200,{'content-type':'application/json; charset=utf-8','cache-control':'no-store'});res.end(value);};
    const s=this.store.session(this.bearer(req),'native');
    const sync=/^\/native\/v2\/hosts\/([a-f0-9]{32})\/sync$/.exec(path);
    if(sync&&method==='POST'){
      const hostId=sync[1]!;
      this.store.grant(s,hostId);
      const {mode,pageByteBudget,sessions}=readSyncRequest(await jsonBody(req,64*1024));
      if(sessions.length===0){json(JSON.stringify({hostId,results:[]}));return;}
      // The loop guard only applies to a **single-session** request.
      //
      // <p>It used to run over every session in the batch, which turned one starving
      // cursor into a whole-batch 429: a background batch legitimately revisits the
      // same `afterSeq` for a session whose page budget keeps getting eaten by the
      // others, and after the sixth such round inside ten seconds every session in
      // that request — including the ones that were making progress — got rejected.
      // A batch moves many cursors; one stuck cursor there is a client scheduling
      // problem, not a spin. The foreground single-session path is the one that can
      // actually spin, and that is the only place worth braking.
      if(sessions.length===1){
        const only=sessions[0]!;
        const wait=this.syncLoop.note(s.device_id,only.sessionId,only.afterSeq);
        if(wait>0){
          res.writeHead(429,{'content-type':'application/json; charset=utf-8','retry-after':String(wait),'cache-control':'no-store'});
          res.end(JSON.stringify({error:'sync-loop',retryAfter:wait,sessionId:only.sessionId}));
          return;
        }
      }
      const {results,bytes}=pageRequest(this.store,hostId,mode,sessions,pageByteBudget);
      // Records go out as raw fragments rather than as parsed objects: each one was
      // already serialized when the plugin pushed it, so `JSON.stringify` of a
      // parsed copy would duplicate the whole page in memory for no benefit.
      const parts=results.map(result=>{
        const head=`{"sessionId":${JSON.stringify(result.sessionId)},"reqAfterSeq":${result.reqAfterSeq}`
          +`,"nextCursorSeq":${result.nextCursorSeq},"archiveCoveredSeq":${result.archiveCoveredSeq}`
          +`,"hasMore":${result.hasMore},"stopReason":${JSON.stringify(result.stopReason)}`;
        return `${head},"records":[${result.records.join(',')}]}`;
      });
      this.syncStatsV2.requests++;
      this.syncStatsV2.bytes+=bytes;
      this.syncStatsV2.records+=results.reduce((n,r)=>n+r.records.length,0);
      this.syncStatsV2.zeroProgress+=results.filter(r=>r.records.length===0&&r.hasMore).length;
      this.syncStatsV2.pages+=results.length;
      json(`{"hostId":${JSON.stringify(hostId)},"results":[${parts.join(',')}]}`);
      return;
    }
    // The id is `${sessionId}:${seq}`, and every sane HTTP client percent-encodes the
    // colon (Android's `URLEncoder.encode` does, and `encodeURIComponent` in the
    // browser will too). Matching only literal ids meant a real phone could never fill
    // a stub back in — the unit tests passed because they built the URL by hand.
    // Accept the encoded form and decode before the lookup.
    const blob=/^\/native\/v2\/hosts\/([a-f0-9]{32})\/blobs\/([A-Za-z0-9_:.%~-]{1,600})$/.exec(path);
    if(blob&&method==='GET'){
      const hostId=blob[1]!;
      this.store.grant(s,hostId);
      let blobId:string;
      try{blobId=decodeURIComponent(blob[2]!);}catch{throw new HttpError(400,'blob-id');}
      if(blobId.length>200)throw new HttpError(404,'blob-missing');
      const found=this.store.timelineBlob(hostId,blobId);
      // A pruned blob is not a 500: the record that pointed at it is still in the
      // phone's timeline as a stub, and "no longer available" is the honest answer.
      if(!found){res.writeHead(404,{'content-type':'application/json','cache-control':'no-store'});res.end('{"error":"blob-missing"}');return;}
      // Range requests are not a nicety here: a blob is up to 10 MiB of tool output
      // fetched over a phone uplink, and losing the connection at 9 MiB used to mean
      // starting over. `bytes=` is the only unit HTTP defines for this and the only
      // one a client will send.
      //
      // Slicing works on the UTF-8 **bytes**, not the string: the range is a byte
      // range, so cutting the decoded string would hand back a length that disagrees
      // with `content-range` for any multi-byte character at the boundary.
      const body=Buffer.from(found.json,'utf8');
      const range=parseRange(req.headers.range,body.length);
      const headers={'content-type':'application/json; charset=utf-8','cache-control':'no-store',
        'accept-ranges':'bytes','content-length':String(range?range.end-range.start+1:body.length)};
      if(range===null){
        // A syntactically valid Range the resource cannot satisfy must be 416 with
        // `content-range: bytes */total`, not a silent full-body 200 — otherwise the
        // client keeps re-requesting the same unsatisfiable offset forever.
        res.writeHead(416,{...headers,'content-length':'0','content-range':`bytes */${body.length}`});res.end();return;
      }
      if(range){
        res.writeHead(206,{...headers,'content-range':`bytes ${range.start}-${range.end}/${body.length}`});
        res.end(body.subarray(range.start,range.end+1));return;
      }
      res.writeHead(200,headers);res.end(body);
      return;
    }
    // 会话清单：中继用自己的 `session_meta` 就地答复，不走隧道。
    //
    // <p>v2 之前这份清单必须经隧道问宿主 `/api/session/list`（v1 的 `/rpc/list`），
    // 于是「电脑不在线就看不到列表」和「探测循环每次都把 loopback 连接重置打进
    // 日志」是同一个根因。插件推正文时顺手更新每个会话的列表项，中继本来就存着
    // 一份，本地答即可——离线可读是它顺带的结果，不是新加的承诺。
    const sessionList=/^\/native\/v2\/hosts\/([a-f0-9]{32})\/sessions$/.exec(path);
    if(sessionList&&method==='GET'){
      const hostId=sessionList[1]!;
      this.store.grant(s,hostId);
      json(JSON.stringify({items:this.store.sessionItems(hostId).map(row=>row.item)}));
      return;
    }
    await this.native(req,res,path,method);
  }
  private error(res:ServerResponse,error:unknown){if(res.destroyed)return;if(res.headersSent){res.destroy();return;}const status=error instanceof HttpError?error.status:error instanceof z.ZodError||error instanceof SyntaxError?400:500;res.writeHead(status,{'content-type':'text/html; charset=utf-8','cache-control':'no-store','referrer-policy':'no-referrer'});res.end(`<p>${status} · ${esc(error instanceof HttpError?error.message:'request-failed')}</p><p><a href="/">返回</a></p>`);}
  listen(port=this.config.port??8080){return new Promise<number>((resolve,reject)=>{this.server.once('error',reject);this.server.listen(port,this.config.listenHost??'127.0.0.1',()=>{this.server.off('error',reject);resolve((this.server.address() as import('node:net').AddressInfo).port);});});}
  async close(){clearInterval(this.cleanup);await this.proxy.close();await new Promise<void>(resolve=>{this.server.close(()=>resolve());this.server.closeAllConnections();});this.store.close();}
}
/**
 * 从环境变量组装配置。
 *
 * <p>设计原则：**一行 `docker run -e RELAY_PASSWORD=… ` 就能起服务**。除密码之外的一切
 * 都有可用缺省——地址可以不配（按请求推）、账号自动生成、配对密码复用同一个密码、
 * SQLite 落在数据卷、密钥首次启动自动生成。
 *
 * <table>
 *   <caption>环境变量</caption>
 *   <tr><th>变量</th><th>缺省</th><th>作用</th></tr>
 *   <tr><td>`RELAY_PASSWORD`</td><td>无</td><td>**唯一必填**。同时用作网页登录密码与主机注册密码。</td></tr>
 *   <tr><td>`RELAY_BASE_URL`</td><td>按请求推</td><td>对外地址。配了就钉住，所有绝对链接/Cookie 前缀都以它为准。</td></tr>
 *   <tr><td>`RELAY_SECRET_KEY_FILE`</td><td>data/relay.key</td><td>部署密钥。文件不存在就就地生成（容器首次启动自动完成）。</td></tr>
 *   <tr><td>`RELAY_ALLOW_INSECURE`</td><td>false</td><td>允许明文 http 对外服务。裸 IP + 无证书时必须打开。</td></tr>
 *   <tr><td>`RELAY_HOST_ROUTING`</td><td>auto / path</td><td>`auto` / `subdomain` / `path`，见 {@link HostRouting}。未钉地址时强制 path。</td></tr>
 *   <tr><td>`RELAY_REQUIRE_TOTP`</td><td>配了密码则 false</td><td>网页登录是否要动态验证码。`true` 打开、`false` 显式关闭。</td></tr>
 *   <tr><td>`RELAY_TRUSTED_PROXY`</td><td>无</td><td>反代地址。写 `*` 表示接受任意来源（无反代直连部署）。</td></tr>
 *   <tr><td>`RELAY_ENROLL_PASSWORD`</td><td>= `RELAY_PASSWORD`</td><td>主机注册密码（配对密码），可重复使用。</td></tr>
 *   <tr><td>`RELAY_ENROLL_ACCOUNT`</td><td>= 管理员邮箱</td><td>注册密码归属账号的邮箱。</td></tr>
 *   <tr><td>`RELAY_ADMIN_EMAIL`</td><td>admin@relay.local</td><td>首次启动时自动建账号。</td></tr>
 *   <tr><td>`RELAY_ADMIN_PASSWORD`</td><td>= `RELAY_PASSWORD`</td><td>同上。只用于首次创建，已存在则跳过。</td></tr>
 *   <tr><td>`RELAY_MEMORY_MB`</td><td>768</td><td>内存预算基准，按比例缩放所有字节闸（见 `limits.ts`）。</td></tr>
 *   <tr><td>`RELAY_MAX_HOSTS`</td><td>32</td><td>同时在线的主机（电脑）数上限。</td></tr>
 *   <tr><td>`RELAY_MAX_STREAMS`</td><td>64</td><td>单主机并发流上限，可下调到 1。</td></tr>
 *   <tr><td>`RELAY_MAX_BODY_MB`</td><td>300</td><td>单请求体上限。</td></tr>
 *   <tr><td>`DATABASE_URL`</td><td>data/relay.db</td><td>SQLite 路径。</td></tr>
 *   <tr><td>`RELAY_LISTEN_HOST`</td><td>127.0.0.1</td><td>监听地址。容器内要 `0.0.0.0`。</td></tr>
 *   <tr><td>`PORT`</td><td>8080</td><td>监听端口。</td></tr>
 *   <tr><td>`RELAY_METRICS_TOKEN`</td><td>无</td><td>`/metrics` 的 Bearer。不给则端点关闭（fail closed）。</td></tr>
 * </table>
 *
 * <p>密码与归属账号必须同时具备——只给密码而没有归属账号时无法确定主机该落在谁的
 * 账户下，宁可启动失败也不要静默忽略。但配了 `RELAY_PASSWORD` 之后归属账号由管理员
 * 邮箱自动补上（这也是「一个密码就够」的关键：主机注册进来的那台机器就在你登录的
 * 账号里，不需要再理解「注册密码 / 归属账号」这两层概念）。
 */
export function configFromEnv():RelayConfig{
  const keyFile=process.env.RELAY_SECRET_KEY_FILE?.trim()||'data/relay.key';
  const baseUrl=process.env.RELAY_BASE_URL?.trim()||undefined;
  const password=process.env.RELAY_PASSWORD?.trim()||undefined;
  const adminEmail=(process.env.RELAY_ADMIN_EMAIL?.trim()||(password?PASSWORD_MODE_EMAIL:undefined))?.toLowerCase();
  const adminPassword=process.env.RELAY_ADMIN_PASSWORD||password;
  let enrollPassword=process.env.RELAY_ENROLL_PASSWORD?.trim()||undefined;
  let enrollAccount=process.env.RELAY_ENROLL_ACCOUNT?.trim().toLowerCase()||undefined;
  // 单一密码模式：一个 RELAY_PASSWORD 同时当登录密码和主机注册密码，注册进来的主机
  // 落在管理员账号下。显式给了 RELAY_ENROLL_* 的话以显式值为准（进阶用法不受影响）。
  if(password){
    if(adminEmail){enrollPassword??=password;enrollAccount??=adminEmail;}
  }
  if(enrollPassword&&!enrollAccount)throw new Error('RELAY_ENROLL_ACCOUNT is required when RELAY_ENROLL_PASSWORD is set');
  if(enrollAccount&&!enrollPassword)throw new Error('RELAY_ENROLL_PASSWORD is required when RELAY_ENROLL_ACCOUNT is set');
  if(!adminEmail&&adminPassword)throw new Error('RELAY_ADMIN_EMAIL is required when RELAY_ADMIN_PASSWORD is set');
  const routing=(process.env.RELAY_HOST_ROUTING??'auto').trim().toLowerCase();
  if(routing!=='auto'&&routing!=='subdomain'&&routing!=='path')throw new Error('RELAY_HOST_ROUTING must be auto, subdomain or path');
  const trustedProxy=process.env.RELAY_TRUSTED_PROXY?.trim();
  const totp=process.env.RELAY_REQUIRE_TOTP?.trim();
  return {
    ...(baseUrl?{baseUrl}:{}),database:process.env.DATABASE_URL??'data/relay.db',key:loadKey(keyFile),
    // 配了单一密码就默认不要 TOTP：要让用户装验证器 App 才肯起服务，这个「一行命令」
    // 就不成立了。显式写 RELAY_REQUIRE_TOTP=true 仍然能打开。
    requireTotp:totp?totp!=='false':!password,
    // 「显式写了 false」与「没写」必须区分：前者是操作者自己决定降级，后者要走护栏。
    // 配了密码同理——那就是操作者对这个部署的显式选择。
    requireTotpExplicit:!!password||totp==='false',
    passwordMode:!!password,
    ...(adminEmail?{adminEmail}:{}),...(adminEmail&&adminPassword?{adminPassword}:{}),
    allowInsecure:process.env.RELAY_ALLOW_INSECURE==='true',
    hostRouting:routing as HostRouting,
    listenHost:process.env.RELAY_LISTEN_HOST??'127.0.0.1',port:Number(process.env.PORT??8080),
    trustedProxy:trustedProxy==='*'?undefined:trustedProxy,
    trustAnyPeer:trustedProxy==='*',
    metricsToken:process.env.RELAY_METRICS_TOKEN,
    // APK 发布：目录缺省跟着 database 走，保留数与上传上限给默认值就够用——
    // 这两个数字存在的意义是「哪天包变大/要留更多版本时不用改代码」。
    ...(process.env.RELAY_RELEASE_DIR?.trim()?{releaseDir:process.env.RELAY_RELEASE_DIR.trim()}:{}),
    releaseKeep:intVar('RELAY_RELEASE_KEEP',RELEASE_KEEP_DEFAULT,1,50),
    releaseMaxBytes:intVar('RELAY_RELEASE_MAX_MB',RELEASE_MAX_MB_DEFAULT,1,512)*1024*1024,
    ...(enrollPassword&&enrollAccount?{enrollPassword,enrollAccount}:{}),
  };
}
/**
 * 启动前的凭据检查。
 *
 * <p>放在 `serve` 里而不是 {@link configFromEnv} 里：`configFromEnv` 是**纯映射**，
 * 连 `create-account` 这类子命令也用它（那时还没有密码是合法的）。而 `serve` 没有
 * 账号就等于起了一个谁也登不进去的服务——用户看到的只是「登录失败」，不会想到
 * 是自己忘了配密码，所以这一条必须当场炸在 `docker logs` 第一屏。
 */
export function requireCredentials(config:RelayConfig):void{
  if(!config.adminEmail||!config.adminPassword)
    throw new Error('RELAY_PASSWORD is required to bootstrap the relay (or set RELAY_ADMIN_EMAIL and RELAY_ADMIN_PASSWORD)');
}
/** 单一密码模式下自动生成的账号邮箱。够用来登录，也不至于被人误当成真实邮箱。 */
export const PASSWORD_MODE_EMAIL='admin@relay.local';
/**
 * 读部署密钥；不存在就**就地生成**。
 *
 * <p>为什么允许自动生成：一行命令启动的容器里没人会先跑 `init-key`，而没有密钥
 * 服务根本起不来。密钥落在数据卷里（`RELAY_SECRET_KEY_FILE`），容器重启后复用，
 * 所以「生成一次、此后一直用它」的语义成立。文件已存在时只读不写，不会覆盖
 * 部署方自己放的密钥。
 *
 * <p>权限给 0600：这是能把数据库里所有字段解开的唯一凭据，和数据库本身同级敏感。
 */
export function loadKey(file:string):Buffer{
  try{return readFileSync(file);}
  catch(e){
    if((e as NodeJS.ErrnoException).code!=='ENOENT')throw e;
    const key=randomBytes(32);mkdirSync(dirname(file),{recursive:true});
    writeFileSync(file,key,{flag:'wx',mode:0o600});return key;
  }
}
