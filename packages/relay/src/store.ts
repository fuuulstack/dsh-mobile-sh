import { DatabaseSync } from 'node:sqlite';
import { EventEmitter } from 'node:events';
import { randomInt } from 'node:crypto';
import { decrypt, encrypt, equal, hash, passwordHash, passwordValid, secret, totpSecret, totpStep, uuid } from './crypto.js';
import { HttpError } from '../../core/src/security.js';
import type { Identity } from '../../core/src/protocol.js';
import { classify } from '../../core/src/classify.js';

export type Row=Record<string,any>;
/**
 * 主机平台白名单。
 *
 * <p>手机端只认这三类图标（Apple / Windows / 通用）。值最终会变成客户端的一个
 * drawable，所以宁可丢掉未知取值，也不把它当自由文本存下来——将来要加 Linux，
 * 在这里加一项、在手机端加一个图标即可，两边同一份枚举。
 */
const PLATFORMS=new Set(['macos','windows','linux']);
export function normalizePlatform(value:unknown):string{
  if(typeof value!=='string')return '';const v=value.trim().toLowerCase();
  if(PLATFORMS.has(v))return v;
  // Node 的 process.platform 用 'darwin'，语义上是 macOS；映射放在服务端，
  // 免得每个客户端各写一份同义词表。
  if(v==='darwin'||v==='mac'||v==='macos'||v==='osx')return 'macos';
  if(v==='win32'||v==='win'||v==='windows')return 'windows';
  return '';
}
/** 浏览器会话：8 小时，关掉浏览器就该作废。 */
export const ACCOUNT_TTL=8*3600*1000;
/**
 * 移动设备令牌：30 天，滑动续期。
 *
 * 真机踩过一次：native 会话跟浏览器会话一样只有 8 小时，手机隔夜就没令牌，
 * 全部 native 端点统一 401，而唯一的自救接口 /native/v2/rotate 自己也要求有效会话——
 * 于是 App 只能停在「登录已过期」，用户必须重新输密码 + TOTP。设备凭据的语义是长期。
 */
export const NATIVE_TTL=30*24*3600*1000;
/**
 * 设备配对码的有效期。
 *
 * <p>码是在中继网页上生成、再填进手机 App 的，中间隔着人肉操作，所以比主机配对码
 * （5 分钟）宽松；但它等价于账号级凭据，也不该给到「忘了用、过夜还能用」的程度。
 */
export const DEVICE_CODE_TTL=15*60*1000;
/** Cloud retention for conversation records: two weeks, then the hourly prune drops them.
 *  The archive exists so a phone can incrementally back-fill every session without the
 *  desktop being online; anything older than this is simply not the cloud's problem. */
export const ARCHIVE_TTL=14*86400000;
/** Rolling per-session window: bounds one runaway session instead of letting it define
 *  the storage bill for every other session on the host. */
export const ARCHIVE_PER_SESSION=2000;
/** Resume point for the legacy->v2 migration: the last row already examined. */
export interface BackfillCursor{hostId:string;sessionId:string;seq:number}
/** Outcome of one bounded migration chunk; `done` means nothing is left behind it. */
export interface BackfillResult{copied:number;done:boolean;cursor?:BackfillCursor}

export class Store extends EventEmitter {
  readonly db:DatabaseSync;
  /** Audit rows that could not be persisted; surfaced through /metrics as a degradation signal. */
  auditFailures=0;
  /** Hourly prune passes that threw; a climbing count means rate_limits and expired sessions
   *  are accumulating with nobody looking. Surfaced through /metrics next to auditFailures. */
  pruneFailures=0;
  constructor(filename:string,readonly key:Buffer,readonly now=()=>Date.now()){
    super();if(key.length!==32)throw new Error('deployment-key-length');this.db=new DatabaseSync(filename);
    // WAL 是「一个写者多个读者」的前提：中继的读来自手机同步页，写来自插件归档，
    // 两者在同一个进程里交错。`synchronous=NORMAL` 只在 WAL 下成立——断电最多丢掉
    // 最后几个事务，不会写出半个页；丢掉的那几条归档由插件的 outbox 重推，凭据类写入
    // 丢一次等价于「这次没写成」，比把每次 fsync 都等满划算得多（归档是突发写）。
    this.db.exec(`PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000;
      PRAGMA synchronous=NORMAL; PRAGMA temp_store=MEMORY; PRAGMA cache_size=-16000;
      CREATE TABLE IF NOT EXISTS schema_version(version INTEGER NOT NULL);
      INSERT INTO schema_version SELECT 1 WHERE NOT EXISTS(SELECT 1 FROM schema_version);
      CREATE TABLE IF NOT EXISTS accounts(id TEXT PRIMARY KEY,email TEXT UNIQUE NOT NULL,password_hash TEXT NOT NULL,totp TEXT,last_totp INTEGER NOT NULL DEFAULT -1,disabled INTEGER NOT NULL DEFAULT 0);
      CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY,account_id TEXT NOT NULL REFERENCES accounts(id),label TEXT NOT NULL,revoked INTEGER NOT NULL DEFAULT 0);
      CREATE TABLE IF NOT EXISTS hosts(id TEXT PRIMARY KEY,account_id TEXT NOT NULL REFERENCES accounts(id),public_id TEXT NOT NULL,alias TEXT NOT NULL,token_hash TEXT UNIQUE NOT NULL,revoked INTEGER NOT NULL DEFAULT 0,platform TEXT NOT NULL DEFAULT '',UNIQUE(account_id,public_id));
      CREATE TABLE IF NOT EXISTS grants(host_id TEXT NOT NULL REFERENCES hosts(id),device_id TEXT NOT NULL REFERENCES devices(id),account_id TEXT NOT NULL REFERENCES accounts(id),version INTEGER NOT NULL DEFAULT 1,revoked INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(host_id,device_id));
      CREATE TABLE IF NOT EXISTS sessions(id_hash TEXT PRIMARY KEY,kind TEXT NOT NULL,account_id TEXT NOT NULL REFERENCES accounts(id),device_id TEXT NOT NULL REFERENCES devices(id),host_id TEXT REFERENCES hosts(id),parent TEXT,expires INTEGER NOT NULL,revoked INTEGER NOT NULL DEFAULT 0,csrf_hash TEXT NOT NULL,verified INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS invitations(hash TEXT PRIMARY KEY,account_id TEXT NOT NULL REFERENCES accounts(id),expires INTEGER NOT NULL,used INTEGER NOT NULL DEFAULT 0);
      CREATE TABLE IF NOT EXISTS receipts(operation TEXT PRIMARY KEY,invitation TEXT NOT NULL,public_id TEXT NOT NULL,token_hash TEXT NOT NULL,host_id TEXT NOT NULL REFERENCES hosts(id),expires INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS pairs(id TEXT PRIMARY KEY,account_id TEXT NOT NULL,host_id TEXT NOT NULL,code_hash TEXT NOT NULL,token_hash TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,expires INTEGER NOT NULL,used INTEGER NOT NULL DEFAULT 0);
      CREATE TABLE IF NOT EXISTS device_codes(id TEXT PRIMARY KEY,account_id TEXT NOT NULL REFERENCES accounts(id),code_hash TEXT NOT NULL,label TEXT NOT NULL,expires INTEGER NOT NULL,used INTEGER NOT NULL DEFAULT 0,used_by TEXT,revoked INTEGER NOT NULL DEFAULT 0,attempts INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS session_digest(session_id TEXT NOT NULL,host_id TEXT NOT NULL,last_seq INTEGER NOT NULL,last_at INTEGER NOT NULL,preview TEXT NOT NULL,role TEXT NOT NULL,updated_at INTEGER NOT NULL,PRIMARY KEY(session_id,host_id));
      CREATE TABLE IF NOT EXISTS requests(id TEXT PRIMARY KEY,host_id TEXT NOT NULL,bind_hash TEXT NOT NULL,expires INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS tickets(hash TEXT PRIMARY KEY,request_id TEXT NOT NULL,session_id TEXT NOT NULL,host_id TEXT NOT NULL,device_id TEXT NOT NULL,bind_hash TEXT NOT NULL,expires INTEGER NOT NULL,used INTEGER NOT NULL DEFAULT 0);
      CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY,at INTEGER NOT NULL,account_id TEXT,action TEXT NOT NULL,result TEXT NOT NULL,subject TEXT);
      CREATE INDEX IF NOT EXISTS session_device ON sessions(device_id);
      CREATE INDEX IF NOT EXISTS audit_at ON audit(at);
      CREATE TABLE IF NOT EXISTS rate_limits(key TEXT PRIMARY KEY,count INTEGER NOT NULL,expires INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS records(host_id TEXT NOT NULL,session_id TEXT NOT NULL,seq INTEGER NOT NULL,record TEXT NOT NULL,updated_at INTEGER NOT NULL,PRIMARY KEY(host_id,session_id,seq)) WITHOUT ROWID;
      CREATE INDEX IF NOT EXISTS records_ttl ON records(updated_at);
      CREATE TABLE IF NOT EXISTS session_meta(host_id TEXT NOT NULL,session_id TEXT NOT NULL,item TEXT NOT NULL,updated_at INTEGER NOT NULL,PRIMARY KEY(host_id,session_id)) WITHOUT ROWID;
      -- v2 archive: one row per TimelineRecord, carrying the two fields a reader needs
      -- *without* decrypting the payload (ordering witness + turn grouping). The relay
      -- never parses business payloads; the record column stays ciphertext.
      CREATE TABLE IF NOT EXISTS timeline_records(
        host_id TEXT NOT NULL,session_id TEXT NOT NULL,seq INTEGER NOT NULL,
        prev_seq INTEGER NOT NULL DEFAULT -1,turn_id TEXT NOT NULL DEFAULT '',
        kind TEXT NOT NULL DEFAULT '',timestamp_ms INTEGER NOT NULL DEFAULT 0,
        byte_size INTEGER NOT NULL DEFAULT 0,blob_id TEXT,
        record TEXT NOT NULL,updated_at INTEGER NOT NULL,
        PRIMARY KEY(host_id,session_id,seq)) WITHOUT ROWID;
      CREATE INDEX IF NOT EXISTS timeline_ttl ON timeline_records(updated_at);
      -- timelineSummary() runs on EVERY /v2/sync page and every /control/timeline
      -- push, and it is GROUP BY session_id over one host's whole timeline. Without
      -- this index that is a scan of every row the host ever archived - measured on
      -- the production relay (49 749 rows / 120 sessions): 83 ms of synchronous
      -- node:sqlite work per call, on a 2-core box where it blocks the event loop
      -- that also serves the tunnel. With the covering index the same query is
      -- 2.9 ms (28x). It covers host_id (seek) plus session_id and updated_at (both
      -- aggregated), so the table itself is never touched.
      CREATE INDEX IF NOT EXISTS timeline_coverage
        ON timeline_records(host_id,session_id,updated_at);

      -- Offloaded payloads. Kept out of the record row so a 5 MB tool dump never has to
      -- be decrypted to page past it: the phone pages on the stub and fetches the blob
      -- only when that row is actually on screen.
      CREATE TABLE IF NOT EXISTS timeline_blobs(
        blob_id TEXT PRIMARY KEY,host_id TEXT NOT NULL,session_id TEXT NOT NULL,
        seq INTEGER NOT NULL,byte_size INTEGER NOT NULL,payload TEXT NOT NULL,created_at INTEGER NOT NULL);
      -- Archive push bookkeeping. batch_id is what makes a retried batch idempotent:
      -- the plugin re-sends the same batch after a timeout, and rows already landed are
      -- recognised by the primary key, so a replay is a no-op instead of a duplicate.
      CREATE TABLE IF NOT EXISTS archive_batches(
        batch_id TEXT PRIMARY KEY,host_id TEXT NOT NULL,records INTEGER NOT NULL,
        created_at INTEGER NOT NULL);

      -- APK 发布台账。中继从「只转发会话」扩展成「也托管安装包分发」之后，版本清单
      -- 必须有个可查询的来源：文件本体放在卷上的 releases/ 目录（十几 MB 的二进制
      -- 不进 SQLite，否则每次 checkpoint 都要搬它一遍），这里只记它是什么、多大、
      -- 校验和、谁写的说明。version 当主键：同一个版本号重传就是覆盖，不会长出两行。
      CREATE TABLE IF NOT EXISTS releases(
        version TEXT PRIMARY KEY,file_name TEXT NOT NULL,byte_size INTEGER NOT NULL,
        sha256 TEXT NOT NULL,notes TEXT NOT NULL DEFAULT '',min_supported TEXT NOT NULL DEFAULT '',
        published_at TEXT NOT NULL,created_at INTEGER NOT NULL,downloads INTEGER NOT NULL DEFAULT 0);

      -- ---- 二级索引：按「真实会跑的查询」逐条建，不是按「看起来该有」建 ----
      --
      -- 上面那五条索引是随表一起长出来的（TTL、设备会话、审计时间、timeline 覆盖）。
      -- 剩下这些热点原本都是全表扫：库小的时候没有代价，可是它们的行数会随历史累积
      -- （每台手机每次登录都写一行 device_codes，每次扫码都写一行 pairs，prune 一小时
      -- 才跑一次），而其中好几条落在猜码路径上——/native/v2/device-code 与
      -- /control/pair 都被限流挡在 5~10 次/分钟，但扫描成本仍与历史行数成正比，
      -- 等于给爆破加了一个「越大越慢」的隐性配额，也给正常登录叠了一层无关延迟。
      --
      -- 全部是 CREATE INDEX IF NOT EXISTS：对已有生产库是纯增量，建一次顺序扫描
      -- （实测 5 万行 timeline_records 建索引约 30ms），不会因为升级而拒绝启动。

      -- 同一台手机重复登录要复用同一条 devices 行（grants 挂在 device_id 上），
      -- 所以「按账号 + 机型标签找活着的设备」是登录主路径。
      CREATE INDEX IF NOT EXISTS devices_account_label ON devices(account_id,label);
      -- 账号面每次渲染主机列表、每次注册新主机都要数「这个账号还有几台活着的」。
      CREATE INDEX IF NOT EXISTS hosts_account ON hosts(account_id);
      -- 吊销设备时按 device_id 反查授权；prune 的 NOT EXISTS 子查询也走这里
      -- （原来是按设备逐行扫整张 grants，设备越多清得越慢）。
      CREATE INDEX IF NOT EXISTS grants_device ON grants(device_id);

      -- 配对码：WHERE account_id=? AND (code_hash=? OR token_hash=?)。SQLite 对 OR
      -- 会用两条索引各取一次再合并（OR-optimization），所以两个列各建一条，不要
      -- 指望复合索引——复合索引的前缀列是 account_id，OR 的另一支用不上它。
      CREATE INDEX IF NOT EXISTS pairs_code ON pairs(code_hash);
      CREATE INDEX IF NOT EXISTS pairs_token ON pairs(token_hash);
      -- 设备码：手机登录只认 code_hash（不经过账号），另有账号面板的列表查询。
      CREATE INDEX IF NOT EXISTS device_codes_code ON device_codes(code_hash);
      CREATE INDEX IF NOT EXISTS device_codes_account ON device_codes(account_id,created_at);
      -- 设备码本身也有 TTL：prune 的 DELETE ... WHERE expires<? 是整表级别的。
      CREATE INDEX IF NOT EXISTS device_codes_expires ON device_codes(expires);

      -- 归档清理：这四条 DELETE 都是按时间列扫，不建索引就是「每小时全表扫一遍」，
      -- 而 blob 表里存的是 5MB 级的工具转储，扫它等于每小时把整个库读一遍。
      CREATE INDEX IF NOT EXISTS session_meta_ttl ON session_meta(updated_at);
      CREATE INDEX IF NOT EXISTS session_digest_ttl ON session_digest(updated_at);
      CREATE INDEX IF NOT EXISTS timeline_blobs_created ON timeline_blobs(created_at);
      -- 单会话超限时按 (host_id,session_id,seq) 删尾：没有这条就只能扫全表。
      CREATE INDEX IF NOT EXISTS timeline_blobs_session ON timeline_blobs(host_id,session_id,seq);
      CREATE INDEX IF NOT EXISTS archive_batches_created ON archive_batches(created_at);
      -- 版本清单按发布时间倒序取最新一条，保留策略按同一个顺序裁剪旧版本。
      CREATE INDEX IF NOT EXISTS releases_created ON releases(created_at);

      -- 过期行清理：prune 对这几张表跑 DELETE … WHERE expires<?，都是只增不改的
      -- 临时表（邀请、票据、握手请求、登录会话、限流桶），不建索引就会随历史无限变慢。
      CREATE INDEX IF NOT EXISTS invitations_expires ON invitations(expires);
      CREATE INDEX IF NOT EXISTS receipts_expires ON receipts(expires);
      CREATE INDEX IF NOT EXISTS pairs_expires ON pairs(expires);
      CREATE INDEX IF NOT EXISTS requests_expires ON requests(expires);
      CREATE INDEX IF NOT EXISTS tickets_expires ON tickets(expires);
      CREATE INDEX IF NOT EXISTS sessions_expires ON sessions(expires);
      CREATE INDEX IF NOT EXISTS rate_limits_expires ON rate_limits(expires);
    `);
    // 就地补列：CREATE TABLE IF NOT EXISTS 不会给已存在的表加字段，而生产库里已经有
    // 十几台主机的行。加列用 ALTER TABLE ADD COLUMN（SQLite 的原地操作，不重写表），
    // 默认空串让老行明确表示「这台主机没上报过平台」，手机端据此退回通用图标。
    // schema_version 保持 1：这是一次向后兼容的纯增量，老版本代码读不到新列也不受影响。
    if(!(this.all('PRAGMA table_info(hosts)') as Row[]).some(c=>c.name==='platform'))
      this.db.exec(`ALTER TABLE hosts ADD COLUMN platform TEXT NOT NULL DEFAULT ''`);
    // 附件传输开关：按**设备**记在 devices 上，不是手机本地的显示偏好。
    // 设置页那个开关只是它的遥控器——关掉之后中继拒绝为这台设备转发
    // `session/attachment`（回读图片字节）与 `uploadFileBinary`（发文件），
    // 字节因此根本不过中继。默认 1：老设备的行补列后行为不变。
    if(!(this.all('PRAGMA table_info(devices)') as Row[]).some(c=>c.name==='attachments'))
      this.db.exec(`ALTER TABLE devices ADD COLUMN attachments INTEGER NOT NULL DEFAULT 1`);
    if(this.get('SELECT version FROM schema_version')?.version!==1)throw new Error('unsupported-database-version');
  }
  get(sql:string,...args:any[]):Row|undefined{return this.db.prepare(sql).get(...args) as Row|undefined;}
  all(sql:string,...args:any[]):Row[]{return this.db.prepare(sql).all(...args) as Row[];}
  run(sql:string,...args:any[]){return this.db.prepare(sql).run(...args);}
  /** Turn id carried across chunks for sessions whose rows have no `turn` field. */
  private readonly backfillTurn=new Map<string,string>();
  transaction<T>(fn:()=>T):T {this.db.exec('BEGIN IMMEDIATE');try{const result=fn();this.db.exec('COMMIT');return result;}catch(e){this.db.exec('ROLLBACK');throw e;}}
  audit(account:string|null,action:string,subject:string|null=null,result='ok'){try{this.run('INSERT INTO audit(at,account_id,action,result,subject) VALUES(?,?,?,?,?)',this.now(),account,action,result,subject);}catch{this.auditFailures++;}}
  rate(key:string,limit:number,period:number){const row=this.get('SELECT * FROM rate_limits WHERE key=?',key),now=this.now();
    if(row&&row.expires>now){if(row.count>=limit)throw new HttpError(429,'rate-limit');this.run('UPDATE rate_limits SET count=count+1 WHERE key=?',key);}
    else this.run('INSERT OR REPLACE INTO rate_limits VALUES(?,?,?)',key,1,now+period);
  }
  async createAccount(email:string,password:string,withTotp=true){
    if(!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email))throw new Error('email');const ph=await passwordHash(password),id=uuid(),totp=withTotp?totpSecret():undefined;
    this.transaction(()=>{this.run('INSERT INTO accounts(id,email,password_hash,totp) VALUES(?,?,?,?)',id,email.toLowerCase(),ph,totp?encrypt(this.key,totp):null);this.audit(id,'account-created');});return {id,totp};
  }
  /**
   * 给已有账号补一把 TOTP secret。
   *
   * <p>存在意义是「先起服务、后来才决定开两步验证」：`RELAY_REQUIRE_TOTP=true` 需要账号
   * 里有 secret 才可能登录，而账号是密码模式建出来的（当时没 secret），仓库里也没有
   * 「已登录后再注册验证器」的界面——不补这一步，打开开关就等于把所有人锁在门外。
   * 返回明文只为了让它能被打印一次，调用方拿到就得打出去，不要再存别处。
   */
  ensureTotp(accountId:string):string|undefined{
    const a=this.get('SELECT totp FROM accounts WHERE id=?',accountId);
    if(!a||a.totp)return undefined;
    const s=totpSecret();
    this.run('UPDATE accounts SET totp=?,last_totp=0 WHERE id=?',encrypt(this.key,s),accountId);
    this.audit(accountId,'totp-enabled');
    return s;
  }
  async login(email:string,password:string,code:string,requireTotp:boolean,label='浏览器',native=false){
    const a=this.get('SELECT * FROM accounts WHERE email=?',email.toLowerCase());
    if(!a||a.disabled){
      // Equalize timing with the real-password path (one argon2 pass against a dummy record),
      // then audit the miss: the rate limits cap probing, the audit row makes it visible.
      await passwordValid(password,'argon2id$AAAAAAAAAAAAAAAAAAAAAA$AAAAAAAAAAAAAAAAAAAAAA');
      this.audit(null,'login',hash(email.toLowerCase()),'failed');throw new HttpError(401,'login-failed');
    }
    if(!await passwordValid(password,a.password_hash)){this.audit(a.id,'login',null,'failed');throw new HttpError(401,'login-failed');}
    // 第二步验证**只看配置**：requireTotp 关着时账号里那把 secret 是休眠的。
    // 早先的判据是「账号里有 secret 就必须给码」，而 `provision` 又总是建带 secret 的
    // 账号——于是单密码模式的容器明明打印 `TOTP=OFF`，登录却要一个从来没展示过用途
    // 的验证码（用户看到的就是「一行命令起来了，但登录 401」）。
    let step=-1;
    if(requireTotp){
      if(!a.totp){this.audit(a.id,'login',null,'failed');throw new HttpError(401,'totp-not-enrolled');}
      step=totpStep(decrypt(this.key,a.totp),code,this.now());
      if(step<0||step<=a.last_totp){this.audit(a.id,'login',null,'failed');throw new HttpError(401,'login-failed');}
    }
    return this.transaction(()=>{
      const fresh=this.get('SELECT * FROM accounts WHERE id=?',a.id)!;
      if(fresh.disabled||step>=0&&step<=fresh.last_totp)throw new HttpError(401,'login-failed');
      if(step>=0)this.run('UPDATE accounts SET last_totp=? WHERE id=?',step,a.id);
      // Re-login on the same labeled device reuses its row: without this every 30-day token
      // rotation minted a permanent new "已登录设备" entry and the management page grew forever.
      const existing=this.get('SELECT id FROM devices WHERE account_id=? AND label=? AND revoked=0',a.id,label.slice(0,80));
      const deviceId=existing?.id??uuid(),token=secret(),csrf=secret();
      // 列名写全，不用 `VALUES(?,?,?,0)`：devices 后来补了 `attachments`，
      // 按位置插会在补列之后当场炸（"5 columns but 4 values"）。加列不该
      // 逼着每个插入点跟着改，所以插入一律显式列名。
      if(!existing)this.run('INSERT INTO devices(id,account_id,label,revoked) VALUES(?,?,?,0)',deviceId,a.id,label.slice(0,80));
      this.run('INSERT INTO sessions VALUES(?,?,?,?,?,?,?,?,?,?)',hash(token),native?'native':'account',a.id,deviceId,null,null,this.now()+(native?NATIVE_TTL:ACCOUNT_TTL),0,hash(csrf),this.now());
      this.audit(a.id,'login',deviceId);return {token,csrf,deviceId};
    });
  }
  session(token:string,kind:'account'|'host'|'native',hostId?:string,options:{expiredOk?:boolean}={}){
    if(!token)throw new HttpError(401,'session-required');
    // 过期检查留到取回行之后：设备令牌（native）过期时，用户手上只有这个旧 bearer，
    // 唯一的自救接口 rotate 必须放行，否则移动端每 8 小时都得重输密码 + TOTP。
    const s=this.get(`SELECT s.* FROM sessions s JOIN accounts a ON a.id=s.account_id JOIN devices d ON d.id=s.device_id
      WHERE s.id_hash=? AND s.kind=? AND s.revoked=0 AND a.disabled=0 AND d.revoked=0`,hash(token),kind);
    if(!s)throw new HttpError(401,'session-expired');
    const live=s.expires>this.now();
    if(!live&&!(kind==='native'&&options.expiredOk))throw new HttpError(401,'session-expired');
    if(kind==='host'){
      if(s.host_id!==hostId)throw new HttpError(404,'not-found');
      const parent=this.get('SELECT * FROM sessions WHERE id_hash=? AND revoked=0 AND expires>?',s.parent,this.now());if(!parent)throw new HttpError(401,'session-expired');
      this.grant(s,hostId!);
    }if(kind==='native'){
      // 滑动续期：剩余寿命不到一半才写库，活跃设备因此永不过期，闲置 30 天自然作废。
      if(live&&s.expires-this.now()<NATIVE_TTL/2)this.run('UPDATE sessions SET expires=? WHERE id_hash=?',this.now()+NATIVE_TTL,s.id_hash);
      if(hostId)this.grant(s,hostId);
    }return s;
  }
  csrf(s:Row,value:string,recent=false){if(!equal(s.csrf_hash,hash(value)))throw new HttpError(403,'csrf');if(recent&&this.now()-s.verified>300000)throw new HttpError(403,'reauth-required');}
  reverify(s:Row,code:string,requireTotp:boolean){const a=this.get('SELECT * FROM accounts WHERE id=?',s.account_id)!;
    if(a.totp){const step=totpStep(decrypt(this.key,a.totp),code,this.now());if(step<0||step<=a.last_totp)throw new HttpError(403,'verification-failed');
      this.transaction(()=>{this.run('UPDATE accounts SET last_totp=? WHERE id=?',step,a.id);this.run('UPDATE sessions SET verified=? WHERE id_hash=?',this.now(),s.id_hash);});
    }else if(requireTotp)throw new HttpError(403,'totp-required');else throw new HttpError(403,'login-again');
  }
  grant(s:Row,hostId:string){
    const g=this.get(`SELECT g.* FROM grants g JOIN hosts h ON h.id=g.host_id WHERE g.host_id=? AND g.device_id=? AND g.account_id=? AND h.account_id=? AND g.revoked=0 AND h.revoked=0`,hostId,s.device_id,s.account_id,s.account_id);
    if(!g){
      // 主机已被撤销时回 410 Gone：明确表达「永久没了、别再试」。
      // 不能用 401/403——客户端把这两个码一律当成令牌失效去调 /rotate，轮换成功后
      // 仍被拒，于是每秒轮换一次（实测一分钟涨 270 条审计）；也不能用 404，
      // 那会被当成路径问题无限重连（旧插件每 3 秒一次，一天两万条访问日志）。
      const h=this.get('SELECT revoked FROM hosts WHERE id=? AND account_id=?',hostId,s.account_id);
      if(h&&h.revoked)throw new HttpError(410,'host-revoked');
      throw new HttpError(404,'not-found');
    }
    return g;
  }
  /**
   * 这台设备能不能让附件字节过中继。
   *
   * 读的是 `devices.attachments`——**不是**手机的本地偏好：手机只是遥控器，
   * 真正拦字节的地方在这里，所以换一台设备/清数据重装都不会把开关「忘了」。
   * 设备行不存在时按「允许」处理会让策略形同虚设，因此取不到就拒绝。
   */
  attachmentsAllowed(s:Row):boolean{
    const d=this.get('SELECT attachments FROM devices WHERE id=? AND revoked=0',s.device_id);
    return !!d && d.attachments!==0;
  }
  /** 写开关；返回落库后的值，手机端据此回填界面（而不是自己假设成功）。 */
  setAttachments(s:Row,enabled:boolean):boolean{
    this.run('UPDATE devices SET attachments=? WHERE id=? AND account_id=?',enabled?1:0,s.device_id,s.account_id);
    this.audit(s.account_id,'attachments-'+(enabled?'on':'off'),s.device_id);
    return this.attachmentsAllowed(s);
  }
  invitation(s:Row){const token=secret();return this.transaction(()=>{this.run('INSERT INTO invitations VALUES(?,?,?,0)',hash(token),s.account_id,this.now()+300000);this.audit(s.account_id,'invitation-created');return token;});}
  enroll(invitation:string,input:{operationId:string;hostPublicId:string;hostTunnelToken:string;alias:string;platform?:string}){
    const digest=hash(invitation),tokenHash=hash(input.hostTunnelToken);
    // platform 可选：老插件不上报，收到的也不是任意字符串——它最终会变成手机端的图标，
    // 所以白名单到三个值，其余一律存空串（手机端退回通用图标），不给脏数据留通道。
    const platform=normalizePlatform(input.platform);
    return this.transaction(()=>{
      const receipt=this.get('SELECT * FROM receipts WHERE operation=?',input.operationId);
      if(receipt){if(receipt.expires<this.now()||!equal(receipt.invitation,digest)||!equal(receipt.token_hash,tokenHash)||receipt.public_id!==input.hostPublicId)throw new HttpError(403,'enroll-rejected');return {hostId:receipt.host_id};}
      const i=this.get('SELECT i.* FROM invitations i JOIN accounts a ON a.id=i.account_id WHERE i.hash=? AND i.used=0 AND i.expires>? AND a.disabled=0',digest,this.now());if(!i)throw new HttpError(403,'enroll-rejected');
      if(this.get('SELECT count(*) n FROM hosts WHERE account_id=? AND revoked=0',i.account_id)!.n>=10)throw new HttpError(429,'host-limit');
      const id=uuid();this.run('INSERT INTO hosts VALUES(?,?,?,?,?,0,?)',id,i.account_id,input.hostPublicId,input.alias.slice(0,80),tokenHash,platform);this.run('UPDATE invitations SET used=1 WHERE hash=?',digest);
      this.run('INSERT INTO receipts VALUES(?,?,?,?,?,?)',input.operationId,digest,input.hostPublicId,tokenHash,id,this.now()+600000);this.audit(i.account_id,'host-enrolled',id);return {hostId:id};
    });
  }
  /**
   * 补写平台：老插件 enroll 时没这个字段，升级后的插件带着同一个 tunnel 凭据重连时
   * 不会再走 enroll（identity 里已有 hostId），所以给认证成功的隧道一个补写点。
   * 只在库里还是空串时写，避免把已有值覆盖成空。
   */
  notePlatform(hostId:string,platform:string){
    const value=normalizePlatform(platform);if(!value)return;
    this.run("UPDATE hosts SET platform=? WHERE id=? AND (platform='' OR platform IS NULL)",value,hostId);
  }
  /**
   * 部署方自定义的「主机注册密码」落在哪个账号上。
   *
   * <p>为什么需要这一步：一次性邀请（`invitation()`）本身带着 `account_id`，
   * 而自定义密码只是一个字符串，中继必须额外知道它属于哪个账号。
   *
   * @param email 账户邮箱
   * @returns 账户 id；账户不存在或已停用时返回 undefined
   */
  accountIdForEnroll(email:string):string|undefined{
    if(!email)return undefined;
    const a=this.get('SELECT id,disabled FROM accounts WHERE email=?',email.toLowerCase());
    return a&&!a.disabled?(a.id as string):undefined;
  }
  /**
   * 用**部署方自定义密码**注册主机（多台可重复使用，不消耗邀请）。
   *
   * <p>与 `enroll()` 的差别只在于凭据来源：`enroll()` 消费 `invitations` 表里的一次性
   * 邀请，这里由调用方先验过自定义密码、直接给出账号。写库动作（hosts + receipts）
   * 与一次性邀请路径完全一致，手机端与隧道凭据都不用区分来源。
   *
   * @param accountId 归属账号
   * @param input 主机身份与别名
   * @returns 主机 id
   */
  enrollWithAccount(accountId:string,input:{operationId:string;hostPublicId:string;hostTunnelToken:string;alias:string;platform?:string}){
    const tokenHash=hash(input.hostTunnelToken);
    const platform=normalizePlatform(input.platform);
    return this.transaction(()=>{
      const receipt=this.get('SELECT * FROM receipts WHERE operation=?',input.operationId);
      // 幂等重放：同一 operationId 只落一台主机（网络抖动时插件会重试）。
      if(receipt){if(!equal(receipt.token_hash,tokenHash)||receipt.public_id!==input.hostPublicId)throw new HttpError(403,'enroll-rejected');return {hostId:receipt.host_id as string};}
      if(this.get('SELECT count(*) n FROM hosts WHERE account_id=? AND revoked=0',accountId)!.n>=10)throw new HttpError(429,'host-limit');
      const id=uuid();
      this.run('INSERT INTO hosts VALUES(?,?,?,?,?,0,?)',id,accountId,input.hostPublicId,input.alias.slice(0,80),tokenHash,platform);
      this.run('INSERT INTO receipts VALUES(?,?,?,?,?,?)',input.operationId,hash('custom-password'),input.hostPublicId,tokenHash,id,this.now()+600000);
      this.audit(accountId,'host-enrolled',id);return {hostId:id};
    });
  }
  authenticate(hostId:string,token:string){const h=this.get('SELECT h.* FROM hosts h JOIN accounts a ON a.id=h.account_id WHERE h.id=? AND h.revoked=0 AND a.disabled=0',hostId);if(!h||!equal(h.token_hash,hash(token)))throw new HttpError(403,'credential-rejected');return h;}
  createPair(s:Row,hostId:string){const h=this.get('SELECT * FROM hosts WHERE id=? AND account_id=? AND revoked=0',hostId,s.account_id);if(!h)throw new HttpError(404,'not-found');
    const id=uuid(),code=randomInt(0,100000000).toString().padStart(8,'0'),token=secret(16);
    this.transaction(()=>{this.run('INSERT INTO pairs VALUES(?,?,?,?,?,0,?,0)',id,s.account_id,hostId,hash(code),hash(token),this.now()+300000);this.audit(s.account_id,'pair-created',hostId);});return {id,code,token};
  }
  /**
   * 主机自助签发配对码（插件 UI 的「生成二维码」）。
   *
   * <p>与 `createPair` 的差别只在**授权来源**：网页那条要求一个 account 会话行
   * （人已经登录中继），这条要求主机自己的隧道凭据（`hostId` + `hostTunnelToken`）。
   * 两者写的是同一张 pairs 表、同一套 TTL 与一次性语义，所以手机端 `/native/v2/pair`
   * 不需要区分码是谁签的。
   *
   * <p>为什么不复用 `createPair` 的 account 会话：插件手里没有浏览器 cookie，
   * 也不该有——让它为了签一个码去走一遍邮箱密码 + TOTP，等于把人类凭据复制到
   * 桌面进程里。
   *
   * @param hostId 主机 id
   * @param token 该主机的隧道凭据（43 位 base64url）
   * @returns 8 位数字码与到期时间戳
   * @throws HttpError 403 凭据不匹配/已撤销
   */
  createPairForHost(hostId:string,token:string){
    const h=this.authenticate(hostId,token);
    const id=uuid(),code=randomInt(0,100000000).toString().padStart(8,'0'),pairToken=secret(16),expires=this.now()+300000;
    this.transaction(()=>{
      this.run('INSERT INTO pairs VALUES(?,?,?,?,?,0,?,0)',id,h.account_id,hostId,hash(code),hash(pairToken),expires);
      this.audit(h.account_id,'pair-created',hostId);
    });
    // accountId 一起返回：「一码配齐」要用它给同一账号签发设备登录码。
    return {code,expiresAt:expires,accountId:h.account_id as string};
  }
  /**
   * 给指定账号签发设备登录码（主机凭据路径：插件「生成二维码」时与配对码同批下发）。
   *
   * <p>与 `createDeviceCode(s, …)` 写同一张 device_codes 表、同一次性语义；差别只在
   * 凭据来源——网页那条要求账号会话，这条要求主机隧道凭据（经 `createPairForHost`
   * 的调用方已验过）。手机端 `/native/v2/device-code` 兑换时不区分码是谁签的。
   *
   * @param accountId 归属账号（取自主机行，保证与配对码同门）
   * @param label 设备标签（审计里可见）
   * @param ttlMs 有效期；与配对码同窗（5 分钟）时「一码两成分」不会先后失效
   * @returns 8 位数字码
   */
  createDeviceCodeForAccount(accountId:string,label:string,ttlMs=DEVICE_CODE_TTL){
    const id=uuid(),code=randomInt(0,100000000).toString().padStart(8,'0'),expires=this.now()+ttlMs;
    this.transaction(()=>{
      this.run('INSERT INTO device_codes VALUES(?,?,?,?,?,0,null,0,0,?)',id,accountId,hash(code),(label||'手机').slice(0,80),expires,this.now());
      this.audit(accountId,'device-code-created',id);
    });
    return code;
  }
  pair(s:Row,value:string){const digest=hash(value);return this.transaction(()=>{
    const p=this.get('SELECT * FROM pairs WHERE account_id=? AND (code_hash=? OR token_hash=?) AND expires>? AND used=0 AND attempts<5',s.account_id,digest,digest,this.now());
    // Charge only the row this code actually matches: the old blanket increment burned every
    // active pairing code of the account (a typo on one device killed the QR on another),
    // while the account-level rate limit above already caps blind brute force.
    if(!p){this.run('UPDATE pairs SET attempts=attempts+1 WHERE account_id=? AND used=0 AND expires>? AND (code_hash=? OR token_hash=?)',s.account_id,this.now(),digest,digest);return null;}
    if(!this.get('SELECT id FROM hosts WHERE id=? AND account_id=? AND revoked=0',p.host_id,s.account_id))return null;
    this.run('INSERT INTO grants VALUES(?,?,?,1,0) ON CONFLICT(host_id,device_id) DO UPDATE SET revoked=0,version=version+1',p.host_id,s.device_id,s.account_id);this.run('UPDATE pairs SET used=1 WHERE id=?',p.id);this.audit(s.account_id,'device-paired',p.host_id);return p.host_id as string;
  });}

  // ============================================================
  // 设备配对码：手机端唯一的登录凭据
  //
  // 邮箱 + 密码 + TOTP 是给人用的，不是给每台手机配一次用的。设备配对码把「登录」
  // 降级成「在网页上点一次生成、在手机上填一次」：一个码换一台设备的长期令牌，
  // 码本身一次性、短时效、可单独撤销，多台设备各持一码互不影响。
  // ============================================================

  createDeviceCode(s:Row,label:string,ttlMs=DEVICE_CODE_TTL){
    const id=uuid(),code=randomInt(0,100000000).toString().padStart(8,'0'),expires=this.now()+ttlMs;
    this.transaction(()=>{this.run('INSERT INTO device_codes VALUES(?,?,?,?,?,0,null,0,0,?)',id,s.account_id,hash(code),(label||'手机').slice(0,80),expires,this.now());this.audit(s.account_id,'device-code-created',id);});
    return {id,code,expires};
  }
  deviceCodes(s:Row){return this.all('SELECT * FROM device_codes WHERE account_id=? ORDER BY created_at DESC LIMIT 50',s.account_id);}
  revokeDeviceCode(s:Row,id:string){const c=this.get('SELECT * FROM device_codes WHERE id=? AND account_id=?',id,s.account_id);if(!c)throw new HttpError(404,'not-found');this.run('UPDATE device_codes SET revoked=1 WHERE id=?',id);this.audit(s.account_id,'device-code-revoked',id);return c;}
  /**
   * 用一个设备配对码换 native 令牌：不需要邮箱、密码、TOTP。
   *
   * <p>码本身就是账号级凭据（换出来的令牌能操作该账号全部已授权主机），所以安全性
   * 全部落在三件事上：一次性、15 分钟时效、按 IP 限流（调用方负责 rate）。
   * 这里只保证「同一个码不能用第二次」和「过期/已撤销的码换不到东西」。
   */
  consumeDeviceCode(code:string,label:string){
    const digest=hash(code),now=this.now();
    return this.transaction(()=>{
      const c=this.get('SELECT * FROM device_codes WHERE code_hash=? AND used=0 AND revoked=0 AND expires>?',digest,now);
      if(!c)throw new HttpError(403,'code-rejected');
      const a=this.get('SELECT * FROM accounts WHERE id=? AND disabled=0',c.account_id);if(!a)throw new HttpError(403,'code-rejected');
      // 同一个 label 复用设备行，与 login() 同一口径。每次都新建一行的话 grants 全挂在旧行上，
      // 新行对每台主机都是 authorized=false —— 手机端表现为「再配一台电脑之后，之前配好的电脑
      // 全成了未配对、设备管理里也消失」。设备身份该由「是哪台手机」决定，与这次用哪个码登录无关。
      const tag=(label||'手机').slice(0,80);
      const existing=this.get('SELECT id FROM devices WHERE account_id=? AND label=? AND revoked=0 ORDER BY rowid DESC',a.id,tag);
      const deviceId=existing?.id??uuid(),token=secret();
      if(!existing)this.run('INSERT INTO devices(id,account_id,label,revoked) VALUES(?,?,?,0)',deviceId,a.id,tag);
      this.run('INSERT INTO sessions VALUES(?,?,?,?,?,?,?,?,?,?)',hash(token),'native',a.id,deviceId,null,null,now+NATIVE_TTL,0,hash(secret()),now);
      this.run('UPDATE device_codes SET used=1,used_by=? WHERE id=?',deviceId,c.id);
      this.audit(a.id,'device-code-used',deviceId);
      return {token,deviceId,expiresIn:NATIVE_TTL/1000};
    });
  }
  startHandoff(hostId:string){if(!this.get('SELECT id FROM hosts WHERE id=? AND revoked=0',hostId))throw new HttpError(404,'not-found');const bind=secret(),requestId=uuid();this.run('INSERT INTO requests VALUES(?,?,?,?)',requestId,hostId,hash(bind),this.now()+300000);return {bind,requestId};}
  handoffRequest(s:Row,id:string){const r=this.get('SELECT * FROM requests WHERE id=? AND expires>?',id,this.now());if(!r)throw new HttpError(403,'handoff-expired');this.grant(s,r.host_id);return r;}
  issueTicket(s:Row,requestId:string){return this.transaction(()=>{const r=this.handoffRequest(s,requestId),ticket=secret();this.run('INSERT INTO tickets VALUES(?,?,?,?,?,?,?,0)',hash(ticket),requestId,s.id_hash,r.host_id,s.device_id,r.bind_hash,this.now()+30000);this.audit(s.account_id,'ticket-issued',r.host_id);return {ticket,hostId:r.host_id};});}
  consumeTicket(hostId:string,ticket:string,bind:string){return this.transaction(()=>{
    const t=this.get('SELECT * FROM tickets WHERE hash=? AND used=0 AND expires>?',hash(ticket),this.now());
    if(!t||t.host_id!==hostId||!equal(t.bind_hash,hash(bind)))throw new HttpError(403,'ticket-rejected');
    const s=this.get('SELECT * FROM sessions WHERE id_hash=? AND kind=? AND revoked=0 AND expires>?',t.session_id,'account',this.now());
    if(!s||!this.get('SELECT id FROM accounts WHERE id=? AND disabled=0',s.account_id)||!this.get('SELECT id FROM devices WHERE id=? AND revoked=0',s.device_id))throw new HttpError(403,'ticket-rejected');
    this.grant(s,hostId);const token=secret();this.run('UPDATE tickets SET used=1 WHERE hash=?',t.hash);
    this.run('INSERT INTO sessions VALUES(?,?,?,?,?,?,?,?,?,?)',hash(token),'host',s.account_id,s.device_id,hostId,s.id_hash,Math.min(s.expires,this.now()+12*3600000),0,hash(secret()),s.verified);this.audit(s.account_id,'host-session-created',hostId);return token;
  });}
  identity(s:Row,hostId:string):Identity{const g=this.grant(s,hostId);return {accountId:s.account_id,deviceId:s.device_id,hostId,sessionId:s.id_hash,grantVersion:g.version,traceId:uuid()};}
  /** Reissue the native bearer of one live native session: same account and device, new secret.
   *  The old bearer keeps a 60 s grace window instead of dying on the spot: the new token
   *  travels in the response, and if that response is lost on a flaky link the phone retries
   *  rotate with the still-live old one instead of being locked to password+TOTP. A leaked
   *  old token gains at most those 60 seconds. */
  rotateNative(s:Row){const token=secret();return this.transaction(()=>{
    this.run('UPDATE sessions SET expires=? WHERE id_hash=? AND revoked=0',Math.min(s.expires,this.now()+60000),s.id_hash);
    this.run('INSERT INTO sessions VALUES(?,?,?,?,?,?,?,?,?,?)',hash(token),'native',s.account_id,s.device_id,null,null,this.now()+NATIVE_TTL,0,hash(secret()),s.verified);
    this.audit(s.account_id,'token-rotated',s.device_id);return token;});}
  sessionTokenCheck(s:Row,hostId:string){
    const fresh=this.get(`SELECT s.* FROM sessions s JOIN accounts a ON a.id=s.account_id JOIN devices d ON d.id=s.device_id
      WHERE s.id_hash=? AND s.revoked=0 AND s.expires>? AND a.disabled=0 AND d.revoked=0`,s.id_hash,this.now());
    if(!fresh)throw new HttpError(401,'session-expired');
    if(fresh.kind!=='native'&&(fresh.host_id!==hostId||fresh.kind!=='host'||!this.get('SELECT id_hash FROM sessions WHERE id_hash=? AND revoked=0 AND expires>?',fresh.parent,this.now())))throw new HttpError(401,'session-expired');
    this.grant(fresh,hostId);
  }
  revoke(s:Row,kind:'device'|'host'|'grant'|'account'|'session',target:string,hostId?:string){
    this.transaction(()=>{
      if(kind==='device'){if(!this.get('SELECT id FROM devices WHERE id=? AND account_id=?',target,s.account_id))throw new HttpError(404,'not-found');this.run('UPDATE devices SET revoked=1 WHERE id=?',target);this.run('UPDATE sessions SET revoked=1 WHERE device_id=?',target);this.run('UPDATE grants SET revoked=1,version=version+1 WHERE device_id=?',target);}
      if(kind==='host'){if(!this.get('SELECT id FROM hosts WHERE id=? AND account_id=?',target,s.account_id))throw new HttpError(404,'not-found');this.run('UPDATE hosts SET revoked=1 WHERE id=?',target);this.run('UPDATE sessions SET revoked=1 WHERE host_id=?',target);}
      if(kind==='grant'){this.run('UPDATE grants SET revoked=1,version=version+1 WHERE device_id=? AND host_id=? AND account_id=?',target,hostId!,s.account_id);this.run('UPDATE sessions SET revoked=1 WHERE device_id=? AND host_id=? AND account_id=?',target,hostId!,s.account_id);}
      if(kind==='account'){if(target!==s.account_id)throw new HttpError(403,'forbidden');this.run('UPDATE accounts SET disabled=1 WHERE id=?',target);}
      if(kind==='session'){target=s.id_hash;this.run('UPDATE sessions SET revoked=1 WHERE id_hash=? OR parent=?',target,target);}
      this.audit(s.account_id,`${kind}-revoked`,target);
    });this.emit('revoked',{kind,target,hostId,accountId:s.account_id});
  }
  // ============================================================
  // v2：digest 通道已下线（0.1.20 裁定，评审再确认）；2026-10-28 连 archiver 的
  // snippet 旁路也一起拆掉——列表首行改由手机用本地已落盘的消息现算。
  // /native/v2/digest* 端点与 noteSessionMessage/digests 已删，
  // session_digest 表保留仅为老库兼容。
  // ============================================================

  archiveRecords(hostId:string,entries:{sessionId:string;seq:number;record:unknown}[]){
    const valid=entries.filter(e=>typeof e.sessionId==='string'&&e.sessionId.length<=128&&Number.isSafeInteger(e.seq)&&e.seq>=0);
    if(!valid.length)return;const now=this.now();
    // 加密全部放在事务外：AES-GCM 是纯 CPU 活，一条 1000 行的批次在事务里逐条加密
    // 会把 BEGIN IMMEDIATE 的排他窗口拉到几十毫秒，期间 /sync、登录、SSE 变更里的
    // 每条 archiveItem 都得排队等锁（node:sqlite 是同步 API，锁等待直接阻塞事件循环）。
    const rows=valid.map(e=>[hostId,e.sessionId,e.seq,encrypt(this.key,JSON.stringify(e.record)),now] as const);
    this.transaction(()=>{for(const r of rows)this.run('INSERT OR REPLACE INTO records VALUES(?,?,?,?,?)',...r);});
  }
  /**
   * 写入会话列表项。
   *
   * <p>插件推上来的是 {@code session/list} 的完整 item。写入走浅合并而不是覆盖：
   * 一次只带部分字段的推送不该把 title/projections 抹掉，否则列表上的会话名会
   * 突然变空。（2026-10-28 之前这里还要合 {@code snippet} 补丁，那条首行旁路
   * 已拆——首行现在由手机用本地消息现算。）
   */
  archiveMeta(hostId:string,entries:{sessionId:string;item:unknown}[]){
    const valid=entries.filter(e=>typeof e.sessionId==='string'&&e.sessionId.length<=128);
    if(!valid.length)return;this.saveMeta(hostId,valid);}

  /** archiveMeta 的批处理核：一次批量 SELECT 旧行、事务外解密与合并、事务内只写。 */
  private saveMeta(hostId:string,valid:{sessionId:string;item:unknown}[]){
    const now=this.now();const key=this.key;
    const ids=[...new Set(valid.map(e=>e.sessionId))];
    const placeholders=ids.map(()=>'?').join(',');
    const existing=new Map<string,string>();
    if(ids.length){
      for(const r of this.all(`SELECT session_id sessionId,item FROM session_meta WHERE host_id=? AND session_id IN (${placeholders})`,hostId,...ids) as Row[])
        existing.set(r.sessionId as string,r.item as string);
    }
    // 解密 + 浅合并 + 加密全部在事务外完成：旧实现每个会话都要在 BEGIN IMMEDIATE
    // 里 SELECT → 解密 → 合并 → 加密 → 写，300 条 meta 就是 300 次排他锁内的
    // AES-GCM + 300 次 prepare，事务窗口被拉到几十毫秒，期间所有 /sync 请求排队。
    // 同一批次里同会话可能出现多条 item（先 title、后 running）。旧实现逐条读改写，
    // 后一条能读到前一条的落地结果；批量预读后必须先按出现顺序把这些 patch 折成
    // 一条，否则「后者整体覆盖前者」会把先到的 title 抹掉。
    const patches=new Map<string,Record<string,unknown>>();
    for(const e of valid){
      const patch=(e.item&&typeof e.item==='object'&&!Array.isArray(e.item))?e.item as Record<string,unknown>:{};
      const acc=patches.get(e.sessionId);
      if(acc)Object.assign(acc,patch);else patches.set(e.sessionId,{...patch});
    }
    const rows=[...patches].map(([sessionId,patch])=>{
      let merged:Record<string,unknown>=patch;
      const old=existing.get(sessionId);
      if(old!==undefined){try{merged={...(JSON.parse(decrypt(key,old)) as Record<string,unknown>),...patch};}catch{merged=patch;}}
      return [hostId,sessionId,encrypt(key,JSON.stringify(merged)),now] as const;
    });
    this.transaction(()=>{for(const r of rows)this.run('INSERT OR REPLACE INTO session_meta VALUES(?,?,?,?)',...r);});
  }
  /** Per-session coverage: newest archived seq and freshness, newest sessions first. */
  /** Last-known slimmed session list items, so a phone can render its list (and offline
   *  history) even while the desktop host is down. */  sessionItems(hostId:string){
    return this.all('SELECT session_id sessionId,item,updated_at updatedAt FROM session_meta WHERE host_id=? ORDER BY updated_at DESC',hostId)
      .flatMap(r=>{try{return [{sessionId:r.sessionId,item:JSON.parse(decrypt(this.key,r.item)),updatedAt:r.updatedAt}];}catch{return [];}});
  }
  /**
   * 单个会话的列表项，已解出标题与运行态。
   *
   * <p>没有首行预览。曾经有过：插件把一条 {@code snippet} 作为补丁推上来，这里
   * 解出来随变更发给手机。那条旁路 2026-10-28 下线了——它和手机本地真正落盘的
   * 消息是两条独立水位，会出现「列表已经显示最新一行、点进会话却没这条回复」。
   * 首行现在由手机从本地消息现算。
   */
  archiveItem(hostId:string,sessionId:string):{title:string;running:boolean}|undefined{
    const row=this.get('SELECT item FROM session_meta WHERE host_id=? AND session_id=?',hostId,sessionId.slice(0,128));
    if(!row)return undefined;
    try{
      const item=JSON.parse(decrypt(this.key,row.item as string)) as {running?:unknown;projections?:{values?:{title?:unknown}}};
      const title=typeof item.projections?.values?.title==='string'?item.projections.values.title:'';
      return {title,running:item.running===true};
    }catch{return undefined;}
  }

  // ============================================================
  // v2 archive: timeline records keyed by (host, session, seq)
  // ============================================================

  /**
   * Lands one archive batch idempotently.
   *
   * <p>Three things happen here that the v1 `archiveRecords` did not do:
   *
   * <ol>
   *   <li><b>Replays are no-ops.</b> The plugin's outbox keeps a record in
   *       {@code IN_FLIGHT} until it sees this batch's id acknowledged, so a
   *       timeout means a retry — and the retry arrives with rows that may
   *       already be here. `INSERT OR IGNORE` leaves the first copy alone. The
   *       old `INSERT OR REPLACE` rewound `updated_at` and, worse, allowed a
   *       stale replay to overwrite a newer record for the same seq.</li>
   *   <li><b>Encryption stays outside the transaction.</b> AES-GCM is pure CPU;
   *       a thousand rows encrypted inside BEGIN IMMEDIATE stretches the
   *       exclusive window to tens of milliseconds, and {@code node:sqlite} is
   *       synchronous — every /sync, login and SSE change waits on the lock.</li>
   *   <li><b>Ordering fields are stored in the clear.</b> {@code prev_seq},
   *       {@code turn_id}, {@code kind} and {@code byte_size} are what /v2/sync
   *       and the preview query need; keeping them out of the ciphertext is what
   *       lets the relay page without parsing (or even decrypting) payloads.</li>
   * </ol>
   *
   * <p>Returns the count of rows that were genuinely new, so the endpoint can
   * report progress honestly instead of echoing the submitted count.
   */
  archiveTimeline(hostId:string,batchId:string,entries:{
    sessionId:string;seq:number;prevSeq:number;turnId:string;kind:string;
    timestampMs:number;byteSize:number;blobId?:string;record:unknown;payload?:unknown;
  }[]):{accepted:number;duplicate:number}{
    const valid=entries.filter(e=>typeof e.sessionId==='string'&&e.sessionId.length<=128
      &&Number.isSafeInteger(e.seq)&&e.seq>=0);
    if(!valid.length)return {accepted:0,duplicate:0};
    const now=this.now();
    // Encrypt first, transaction second — see (2) above.
    const rows=valid.map(e=>[
      hostId,e.sessionId,e.seq,Number.isSafeInteger(e.prevSeq)?e.prevSeq:-1,
      String(e.turnId??'').slice(0,128),String(e.kind??'').slice(0,48),
      Number.isFinite(e.timestampMs)?Math.trunc(e.timestampMs):now,
      Number.isFinite(e.byteSize)?Math.trunc(e.byteSize):0,
      // `record` arrives already serialized (the plugin stores it as text in the outbox
      // and splices it into the push body verbatim, to avoid a parse/stringify of every
      // 200 KiB message). Re-stringifying a string would store a *quoted* JSON literal:
      // `/v2/sync` then hands the phone a string where it expects a record object, and
      // the whole page renders as "no seq". Accept both shapes, keep the text.
      e.blobId??null,encrypt(this.key,typeof e.record==='string'?e.record:JSON.stringify(e.record)),now,
    ] as const);
    // Blobs are encrypted separately and only stored when the plugin actually
    // offloaded a payload (v2 §protocol: > 256 KiB becomes a stub).
    const blobs=valid.flatMap(e=>{
      if(!e.blobId||e.payload===undefined)return [];
      const text=typeof e.payload==='string'?e.payload:JSON.stringify(e.payload);
      // `byte_size` is NOT NULL: the phone needs it to size the download and to decide
      // whether a Range retry is worth it, so it is always the fetched text's length.
      return [[e.blobId,hostId,e.sessionId,e.seq,Buffer.byteLength(text,'utf8'),
        encrypt(this.key,text),now] as const];
    });
    let accepted=0;
    this.transaction(()=>{
      for(const r of rows){
        const result=this.run(`INSERT OR IGNORE INTO timeline_records
          (host_id,session_id,seq,prev_seq,turn_id,kind,timestamp_ms,byte_size,blob_id,record,updated_at)
          VALUES(?,?,?,?,?,?,?,?,?,?,?)`,...r);
        if(Number(result.changes)>0)accepted++;
      }
      for(const b of blobs)
        this.run('INSERT OR IGNORE INTO timeline_blobs(blob_id,host_id,session_id,seq,byte_size,payload,created_at) VALUES(?,?,?,?,?,?,?)',...b);
      this.run('INSERT OR IGNORE INTO archive_batches(batch_id,host_id,records,created_at) VALUES(?,?,?,?)',batchId,hostId,accepted,now);
    });
    return {accepted,duplicate:valid.length-accepted};
  }

  /** True when this exact batch id was already applied (a retry that landed
   *  before the ack was lost). Lets the endpoint answer a replay in O(1). */
  archiveBatchSeen(batchId:string):boolean{
    return this.get('SELECT 1 x FROM archive_batches WHERE batch_id=?',batchId)!==undefined;
  }

  /**
   * Copies legacy (v1) archive rows into the v2 timeline, so an upgraded relay
   * does not lose the fourteen days of history it already holds.
   *
   * <p>The relay must not parse business payloads, so the copy is **structural
   * only**: `kind` is left empty and `prev_seq` is derived in SQL with the window
   * function the design doc calls for. The phone classifies a row when it reads
   * it (it already runs `classify`-equivalent logic in `Projector`), which is why
   * an empty `kind` is not a hole — `RecordKind.of("")` falls back to `META_PATCH`
   * and the projector attaches it to the running turn.
   *
   * <p>Idempotent by construction: the `INSERT ... SELECT` skips rows the timeline
   * already covers, so calling this on every prune tick is a cheap anti-join rather
   * than a re-encryption of the whole table. Legacy rows stay in `records`
   * untouched for rollback; only the v2 table grows.
   *
   * <p>`byte_size` is the ciphertext length of the legacy row, which is the byte
   * count the phone would have to page past. It is an upper bound on the record
   * size, never smaller than the plaintext, so budget accounting stays honest.
   */

  /**
   * Mirror a v1 `/control/archive` push straight into the v2 timeline.
   *
   * <p>Without this, a host still running the v1 plugin keeps feeding `records` while
   * a v2 phone reads `timeline_records`, and the two tables drift: the phone shows
   * history up to whenever the migration last ran and then silently stops. The hourly
   * mop-up (`Relay.startBackfill`) eventually closes the gap, which is exactly the
   * "my newest messages are missing for an hour" failure mode nobody can debug.
   *
   * <p>The plaintext is already in memory at the call site, so this shares the same
   * envelope builder as the migration instead of re-reading and re-decrypting. Bounded
   * by the route's own `max(1000)` schema, so it cannot become a memory hazard.
   */
  mirrorLegacy(hostId:string,entries:Array<{sessionId:string;seq:number;record:unknown}>):number{
    if(!entries.length)return 0;
    const prepared:Array<{sessionId:string;seq:number;prevSeq:number;turnId:string;kind:string;updatedAt:number;text:string}>=[];
    const now=this.now();
    for(const entry of entries){
      const text=typeof entry.record==='string'?entry.record:JSON.stringify(entry.record);
      const event=(()=>{
        try{
          const parsed=JSON.parse(text) as {event?:unknown};
          return parsed&&typeof parsed==='object'&&'event'in parsed?parsed.event:parsed;
        }catch{return undefined;}
      })();
      const key=`${hostId}/${entry.sessionId}`;
      const classified=classify(event,entry.sessionId,this.backfillTurn.get(key)??'');
      if(classified.turnId)this.backfillTurn.set(key,classified.turnId);
      const prev=this.get(`SELECT MAX(seq) m FROM (
          SELECT seq FROM records WHERE host_id=? AND session_id=? AND seq<?
          UNION ALL SELECT seq FROM timeline_records WHERE host_id=? AND session_id=? AND seq<?
        )`,hostId,entry.sessionId,entry.seq,hostId,entry.sessionId,entry.seq);
      const prevSeq=typeof prev?.m==='number'?prev.m as number:0;
      prepared.push({sessionId:entry.sessionId,seq:entry.seq,prevSeq,turnId:classified.turnId,
        kind:classified.kind,updatedAt:now,
        text:JSON.stringify({
          sessionId:entry.sessionId,seq:entry.seq,prevSeq,turnId:classified.turnId,
          ...(classified.stepId?{stepId:classified.stepId}:{}),
          kind:classified.kind,timestampMs:now,
          payload:event??{type:'migrated/unparsed'},
        })});
    }
    let copied=0;
    this.transaction(()=>{
      for(const row of prepared){
        const result=this.run(`INSERT OR IGNORE INTO timeline_records
          (host_id,session_id,seq,prev_seq,turn_id,kind,timestamp_ms,byte_size,blob_id,record,updated_at)
          VALUES(?,?,?,?,?,?,?,?,NULL,?,?)`,
          hostId,row.sessionId,row.seq,row.prevSeq,row.turnId.slice(0,128),row.kind,
          row.updatedAt,Buffer.byteLength(row.text,'utf8'),encrypt(this.key,row.text),row.updatedAt);
        if(Number(result.changes)>0)copied++;
      }
    });
    return copied;
  }

  /**
   * Copy legacy v1 archive rows into the v2 timeline, **one bounded chunk per call**.
   *
   * <p>This runs on a 2-core / 768 MB host that is also serving phones, so it must
   * never materialise the archive in memory. An earlier version did exactly that -
   * one unbounded `SELECT` over `records`, then `decrypt` + re-`encrypt` for every
   * row into two arrays - and it thrashed the production host hard enough that sshd
   * could not complete a banner exchange. The cursor below makes the work resumable:
   * one call reads at most `limit` rows, and the caller yields to the event loop
   * between calls (see `Relay.startBackfill`).
   *
   * <p>`prev_seq` is an indexed `MAX(seq)` lookup rather than
   * `LAG(...) OVER (PARTITION BY session)`. The window function needs the whole
   * session in memory at once, and a session may hold 2000 rows of up to 1 MB each.
   * The lookup answers the same question - "which seq immediately precedes this one
   * in the merged timeline" - with two primary-key probes, and it stays correct when
   * the two tables interleave (the new archiver may already have written higher seqs
   * for a session that also has legacy rows).
   *
   * <p>Rows whose ciphertext will not open (a rotated key, a half-written row) are
   * skipped rather than failing the whole migration: a migration that aborts on one
   * bad row never finishes.
   */
  backfillTimeline(options:{ after?:BackfillCursor; limit?:number; maxBytes?:number; hostId?:string } = {}):BackfillResult{
    const after=options.after;
    const limit=Math.max(1,Math.min(2000,options.limit??200));
    // Byte ceiling as well as a row ceiling: legacy rows can be up to 1 MB of
    // ciphertext each, so "100 rows" is anywhere between 100 KB and 100 MB of live
    // strings once decrypted. The byte budget is what actually bounds the peak.
    const maxBytes=Math.max(64*1024,options.maxBytes??2*1024*1024);
    const scoped=typeof options.hostId==='string'&&options.hostId!=='';
    const rows=this.all(`SELECT o.host_id hostId,o.session_id sessionId,o.seq seq,o.record record,o.updated_at updatedAt
      FROM records o
      WHERE ${scoped?'o.host_id=? AND ':''}NOT EXISTS(SELECT 1 FROM timeline_records t
          WHERE t.host_id=o.host_id AND t.session_id=o.session_id AND t.seq=o.seq)
        ${after?'AND (o.host_id>? OR (o.host_id=? AND (o.session_id>? OR (o.session_id=? AND o.seq>?))))':''}
      ORDER BY o.host_id,o.session_id,o.seq
      LIMIT ?`,
      ...(scoped?[options.hostId as string]:[]),
      ...(after?[after.hostId,after.hostId,after.sessionId,after.sessionId,after.seq]:[]),
      limit) as Row[];
    if(!rows.length)return {copied:0,done:true};

    // Decrypt and classify before opening the transaction: AES-GCM inside
    // BEGIN IMMEDIATE stretches the write lock, and node:sqlite is synchronous, so
    // the whole event loop would sit in it.
    //
    // Classification is a *one-off migration* concession, not a new steady-state
    // rule: the relay still never inspects a payload on the forwarding or paging
    // path. Leaving `kind` empty would hand the phone fourteen days of history as
    // META_PATCH, which its projector renders as invisible noise. The payload itself
    // is preserved byte-for-byte inside the envelope, so nothing is rewritten.
    const prepared:Array<{hostId:string;sessionId:string;seq:number;prevSeq:number;turnId:string;kind:string;updatedAt:number;text:string}>=[];
    let last:BackfillCursor|undefined;
    let spent=0;
    for(const row of rows){
      const hostId=row.hostId as string,sessionId=row.sessionId as string,seq=row.seq as number;
      // Stop *before* the row that would blow the budget, so the cursor still points
      // at the last row actually examined and the next call resumes exactly there.
      if(prepared.length&&spent>=maxBytes)break;
      last={hostId,sessionId,seq};
      spent+=Buffer.byteLength(row.record as string,'utf8');
      let text:string;
      try{text=decrypt(this.key,row.record as string);}catch{continue;}
      const event=(()=>{
        try{
          const parsed=JSON.parse(text) as {event?:unknown};
          return parsed&&typeof parsed==='object'&&'event'in parsed?parsed.event:parsed;
        }catch{return undefined;}
      })();
      // Turn ids are carried **per session**: rows arrive ordered by
      // (host,session,seq), so a single variable would leak one session's turn into
      // the next session's first record and group two unrelated conversations. The
      // map lives on the instance because a session may span two chunks.
      const key=`${hostId}/${sessionId}`;
      const classified=classify(event,sessionId,this.backfillTurn.get(key)??'');
      // An event with no `turn` keeps the previous turn; the first one in a session
      // stays orphaned and the phone synthesises an implicit turn for it (by design).
      if(classified.turnId)this.backfillTurn.set(key,classified.turnId);
      // **Re-wrap, do not copy.** A v1 archive row is `{event:{type,seq,data}}`; a v2
      // record is the envelope around it (`{sessionId,seq,prevSeq,turnId,kind,
      // timestampMs,payload}`). Storing the old text verbatim would hand the phone
      // records with no `seq` and no `kind`, and its parser would drop every one -
      // the conversation would look empty while the archive is full.
      const prev=this.get(`SELECT MAX(seq) m FROM (
          SELECT seq FROM records WHERE host_id=? AND session_id=? AND seq<?
          UNION ALL SELECT seq FROM timeline_records WHERE host_id=? AND session_id=? AND seq<?
        )`,hostId,sessionId,seq,hostId,sessionId,seq);
      const prevSeq=typeof prev?.m==='number'?prev.m as number:0;
      const envelope=JSON.stringify({
        sessionId,seq,prevSeq,turnId:classified.turnId,
        ...(classified.stepId?{stepId:classified.stepId}:{}),
        kind:classified.kind,
        timestampMs:row.updatedAt as number,
        // `event` is undefined only when the stored text was not JSON at all; a
        // migration must never throw on one bad row, so fall back to a marker.
        payload:event??{type:'migrated/unparsed'},
      });
      prepared.push({hostId,sessionId,seq,prevSeq,turnId:classified.turnId,kind:classified.kind,updatedAt:row.updatedAt as number,text:envelope});
    }

    let copied=0;
    if(prepared.length)this.transaction(()=>{
      for(const row of prepared){
        const result=this.run(`INSERT OR IGNORE INTO timeline_records
          (host_id,session_id,seq,prev_seq,turn_id,kind,timestamp_ms,byte_size,blob_id,record,updated_at)
          VALUES(?,?,?,?,?,?,?,?,NULL,?,?)`,
          row.hostId,row.sessionId,row.seq,row.prevSeq,row.turnId.slice(0,128),row.kind,
          row.updatedAt,Buffer.byteLength(row.text,'utf8'),encrypt(this.key,row.text),row.updatedAt);
        if(Number(result.changes)>0)copied++;
      }
    });
    // The migration is finished only when the query itself ran dry (a short page) and
    // we did not stop early on the byte budget.
    const done=rows.length<limit&&prepared.length===rows.length;
    if(done)this.backfillTurn.clear();
    return {copied,done,...(last?{cursor:last}:{})};
  }

  /**
   * One page of a session's timeline, for /v2/sync.
   *
   * <p>Reads `limit` rows straight off the primary key (no table scan, no sort —
   * the PK is `(host,session,seq)`), so the heap cost is bounded by the page, not
   * by the session. The caller probes with `limit = pageRecords + 1` and treats a
   * full-set answer as "there is more"; that is how `hasMore` avoids a second
   * `COUNT(*)` over a 2000-row session.
   *
   * <p><b>`json` is decrypted text, not a parsed object.</b> The record was stored
   * as `JSON.stringify(record)` behind AES-GCM, so decrypting gives back a valid
   * JSON fragment. Handing the fragment straight to the response writer means one
   * decrypt + zero parse + zero re-stringify per record, and no object graph is
   * ever built for payloads the relay has no business understanding. Paging 240
   * records costs the relay a few hundred KiB of short-lived strings instead of a
   * full duplicate tree it would then throw away.
   */
  timelineSlice(hostId:string,sessionId:string,afterSeq:number,limit:number):{
    seq:number;prevSeq:number;turnId:string;kind:string;timestampMs:number;
    byteSize:number;blobId:string|null;json:string;
  }[]{
    return this.all(`SELECT seq,prev_seq prevSeq,turn_id turnId,kind,timestamp_ms timestampMs,
      byte_size byteSize,blob_id blobId,record FROM timeline_records
      WHERE host_id=? AND session_id=? AND seq>? ORDER BY seq ASC LIMIT ?`,
      hostId,sessionId.slice(0,128),afterSeq,limit)
      .flatMap(r=>{
        // A row that will not decrypt is a key rotation casualty. Dropping it (rather
        // than failing the page) keeps /sync usable: the phone advances past it and
        // reports a gap instead of being stuck forever at that seq.
        try{return [{
          seq:r.seq as number,prevSeq:r.prevSeq as number,turnId:r.turnId as string,
          kind:r.kind as string,timestampMs:r.timestampMs as number,
          byteSize:r.byteSize as number,blobId:(r.blobId as string|null)??null,
          json:decrypt(this.key,r.record as string),
        }];}catch{return [];}
      });
  }

  /** Newest archived seq per session, plus record count — the list page's coverage. */
  timelineSummary(hostId:string){
    return this.all(`SELECT session_id sessionId,MAX(seq) latestSeq,MAX(updated_at) updatedAt,COUNT(*) count
      FROM timeline_records WHERE host_id=? GROUP BY session_id ORDER BY updatedAt DESC`,hostId);
  }

  timelineTail(hostId:string,sessionId:string):number{
    return (this.get('SELECT MAX(seq) m FROM timeline_records WHERE host_id=? AND session_id=?',
      hostId,sessionId.slice(0,128))?.m??0) as number;
  }



  /** One offloaded payload, as decrypted JSON text. Returns `undefined` when the blob
   *  was pruned or never landed; the phone then renders the stub's `previewHead` and
   *  an explicit "original not available" line rather than an empty row. */
  timelineBlob(hostId:string,blobId:string):{json:string;byteSize:number}|undefined{
    const row=this.get('SELECT payload,byte_size byteSize FROM timeline_blobs WHERE blob_id=? AND host_id=?',blobId,hostId);
    if(!row)return undefined;
    try{return {json:decrypt(this.key,row.payload as string),byteSize:row.byteSize as number};}
    catch{return undefined;}
  }

  /** Coverage of one session: newest archived seq. Used to fill `archiveCoveredSeq`
   *  in /v2/sync answers, so the phone can tell "archive caught up" from "host has
   *  more that has not been archived yet". */
  archiveCovered(hostId:string,sessionId:string):number{return this.timelineTail(hostId,sessionId);}

  // ============================================================
  // APK 发布台账（文件本体在卷上，这里只存元数据）
  // ============================================================

  /**
   * 写入/覆盖一个发布版本。
   *
   * <p>`INSERT OR REPLACE` 而不是 `INSERT`：同一个版本号重传是常见的（签名姿势不对、
   * 说明写错一个字），把「重传」当错误只会逼人去手工删行。覆盖时保留下载计数——
   * 那个数字描述的是「这个版本被下载过多少次」，不是「这一行存在多久」。
   */
  releasePut(r:{version:string;file_name:string;byte_size:number;sha256:string;
    notes?:string;min_supported?:string;published_at:string;account_id?:string|null}){
    const prev=this.releaseGet(r.version);
    this.transaction(()=>{
      this.run(`INSERT OR REPLACE INTO releases(version,file_name,byte_size,sha256,notes,min_supported,published_at,created_at,downloads)
        VALUES(?,?,?,?,?,?,?,?,?)`,
        r.version,r.file_name,r.byte_size,r.sha256,r.notes??'',r.min_supported??'',r.published_at,
        prev?Number(prev.created_at):this.now(),prev?Number(prev.downloads):0);
      // 归属账号尽量落下来：这条审计要回答「谁把包放到这台中继上的」。
      this.audit(r.account_id??null,'release-published',r.version);
    });
  }

  releaseGet(version:string){return this.get('SELECT * FROM releases WHERE version=?',version);}

  /** 最新发布（清单里 `latest` 的来源）。按 `created_at` 而不是版本号排序：
   *  版本号是作者写的，`0.10` 与 `0.9` 谁更新得靠分段比较，而「最后发布的那个」
   *  语义上就该是台账里的最新一行。 */
  releaseLatest(){return this.get('SELECT * FROM releases ORDER BY created_at DESC LIMIT 1');}

  releaseList(limit=20){return this.all('SELECT * FROM releases ORDER BY created_at DESC LIMIT ?',limit);}

  /** 超出版本数保留策略的旧版本（返回版本号，调用方负责删卷上的文件）。 */
  releaseTrim(keep:number){
    const rows=this.all('SELECT version FROM releases ORDER BY created_at DESC LIMIT -1 OFFSET ?',Math.max(0,keep));
    if(rows.length===0)return [] as string[];
    this.transaction(()=>{for(const r of rows)this.run('DELETE FROM releases WHERE version=?',r.version);});
    return rows.map(r=>r.version as string);
  }

  releaseDelete(version:string){this.run('DELETE FROM releases WHERE version=?',version);}

  releaseCountDownload(version:string){this.run('UPDATE releases SET downloads=downloads+1 WHERE version=?',version);}

  prune(){const now=this.now();this.transaction(()=>{
    this.run('DELETE FROM audit WHERE at<?',now-90*86400000);
    for(const table of ['invitations','receipts','pairs','requests','tickets','rate_limits','device_codes'])this.run(`DELETE FROM ${table} WHERE expires<?`,now-600000);
    this.run('DELETE FROM sessions WHERE expires<?',now-86400000);
    // Devices whose sessions are all gone and that hold no grant are pure history: browsers
    // after their 8 h cookie died, or logins superseded by the label-reuse above. Revoke
    // instead of delete so audit rows keep pointing at something.
    this.run(`UPDATE devices SET revoked=1 WHERE revoked=0
      AND NOT EXISTS(SELECT 1 FROM sessions WHERE device_id=devices.id AND revoked=0 AND expires>?)
      AND NOT EXISTS(SELECT 1 FROM grants WHERE device_id=devices.id AND revoked=0)`,now);
    // Archive retention: TTL first, then the per-session rolling window; digests piggyback
    // on the audit horizon (they are cheap rows, but not worth keeping forever either).
    this.run('DELETE FROM records WHERE updated_at<?',now-ARCHIVE_TTL);
    this.run('DELETE FROM session_meta WHERE updated_at<?',now-ARCHIVE_TTL);
    this.run('DELETE FROM session_digest WHERE updated_at<?',now-90*86400000);
    const fat=this.all(`SELECT host_id hostId,session_id sessionId FROM records GROUP BY host_id,session_id HAVING COUNT(*)>?`,ARCHIVE_PER_SESSION);
    for(const f of fat)this.run(`DELETE FROM records WHERE host_id=? AND session_id=? AND seq<(SELECT MIN(seq) FROM (SELECT seq FROM records WHERE host_id=? AND session_id=? ORDER BY seq DESC LIMIT ?))`,f.hostId,f.sessionId,f.hostId,f.sessionId,ARCHIVE_PER_SESSION);
    // v2 store: same retention, same rolling window. Blobs go first (they are the
    // big rows and the FK-less reference means nothing else will free them), then
    // the records that pointed at them. Batch receipts are pure bookkeeping and only
    // need to outlive the plugin's retry horizon.
    this.run('DELETE FROM archive_batches WHERE created_at<?',now-86400000);
    this.run('DELETE FROM timeline_records WHERE updated_at<?',now-ARCHIVE_TTL);
    this.run('DELETE FROM timeline_blobs WHERE created_at<?',now-ARCHIVE_TTL);
    const fat2=this.all(`SELECT host_id hostId,session_id sessionId FROM timeline_records GROUP BY host_id,session_id HAVING COUNT(*)>?`,ARCHIVE_PER_SESSION);
    for(const f of fat2){
      this.run(`DELETE FROM timeline_records WHERE host_id=? AND session_id=? AND seq<(SELECT MIN(seq) FROM (SELECT seq FROM timeline_records WHERE host_id=? AND session_id=? ORDER BY seq DESC LIMIT ?))`,f.hostId,f.sessionId,f.hostId,f.sessionId,ARCHIVE_PER_SESSION);
      // Blobs whose record fell out of the window are unreachable: the phone only
      // learns a blobId from a stub record, and that record is gone.
      this.run(`DELETE FROM timeline_blobs WHERE host_id=? AND session_id=? AND seq<(SELECT MIN(seq) FROM (SELECT seq FROM timeline_records WHERE host_id=? AND session_id=? ORDER BY seq DESC LIMIT ?))`,f.hostId,f.sessionId,f.hostId,f.sessionId,ARCHIVE_PER_SESSION);
    }
  });}
  /**
   * 关闭前把统计信息交给查询规划器收尾。
   *
   * <p>`PRAGMA optimize` 是 SQLite 给长期连接准备的「只在需要时 ANALYZE」入口：它不会
   * 每次全库统计，只在某张表的索引选择明显失真时补采样。容器重启很频繁（改配置、
   * 升级镜像），放在 close 里等于「每次生命周期结束都顺便校准一次」，成本可忽略。
   */
  close(){try{this.db.exec('PRAGMA optimize');}catch{}this.db.close();}
}
