/**
 * 插件 UI 的可编辑设置：中继地址 + 中继鉴权密码。
 *
 * <p>为什么另开一份文件而不是直接改 profile 的 cordis.patch.yml：
 * patch 是部署清单（operator 手写、随 profile 备份、可能被 DSH 重写），
 * 而这里存的是用户在设置页里点「保存」产生的**运行时偏好**。两者语义不同：
 * patch 给缺省值，本文件给用户显式选择的值，且必须能在不重启 DSH 的情况下生效。
 *
 * <p>存储位置固定在 {@code <DSH_HOME>/mobile-sh/config.json}（默认
 * {@code ~/.dsh/mobile-sh/config.json}），权限 0600——里面含主机注册邀请，
 * 邀请等价于「能把这台机器注册进中继」的凭据，不能给同机其他用户读。
 *
 * <p>本模块只做「规范化 + 校验 + 读写」，不碰网络、不碰隧道；副作用全在 lifecycle。
 */
import { readFileSync, writeFileSync, mkdirSync, renameSync, chmodSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { homedir } from 'node:os';

/**
 * 中继鉴权密码的合法字符集与长度区间。
 *
 * <p>**不锁死位数**（2026-10-27 按用户要求改）：早期版本硬性要求 43 位 base64url
 * （那是中继 `/invitation` 自动签发的邀请码格式），结果用户自己设的短密码根本存不进去。
 * 现在密码由用户自定义，插件只做两件事：
 * ① 拒绝会破坏 HTTP `Authorization: Bearer` 头的字符（空白、控制字符）；
 * ② 给一个宽松的长度区间挡住明显的误输入（空串、粘贴了整段 JSON）。
 *
 * <p>真正的强度校验在中继侧：`store.invitation()` 签发的邀请恒为 43 位，
 * 而部署方在 `/etc/...env` 里配的自定义密码由中继的 `hash()` 比对，长度不限。
 */
export const ENROLL_SECRET_MIN = 1;
export const ENROLL_SECRET_MAX = 512;
/** 不允许出现在 Bearer 头里的字符：空白与控制字符。 */
export const ENROLL_SECRET_FORBIDDEN = /[\s\u0000-\u001f\u007f]/;

/** 规范化后的中继地址（wss://host[:port]/tunnel）。 */
export type RelayTarget = {
  /** 隧道 WebSocket 地址。 */
  readonly url: string;
  /** 中继的 https 源；主机凭据按它绑定，换源等于换中继。 */
  readonly origin: string;
  readonly host: string;
};

export class SettingsError extends Error {
  constructor(readonly code: string, message: string) {
    super(message);
    this.name = 'SettingsError';
  }
}

/**
 * 把用户输入的中继地址规范成 `wss://<authority>/tunnel`。
 *
 * <p>用户实际会贴的东西五花八门：`relay.example.com`、`https://relay.example.com`、
 * `https://relay.example.com/`、`wss://relay.example.com/tunnel`，甚至网页地址栏里
 * 复制出来的带路径 URL。全部收敛成同一个值，否则「保存成功但连不上」会变成
 * 一个只有看日志才能诊断的坑。
 *
 * @param input 原始输入
 * @returns 规范化结果；`origin` 用于和已存凭据比对（凭据是绑定中继的）
 * @throws SettingsError 协议不对、带 userinfo、路径不是 /tunnel、带 query/hash
 */
export function normalizeRelayUrl(input: string): RelayTarget {
  const raw = (input ?? '').trim();
  if (!raw) throw new SettingsError('relay-required', '请填写中继地址');
  // 没写协议时按 https 补：用户粘贴域名是最常见的用法。
  const withScheme = /^[A-Za-z][A-Za-z0-9+.-]*:\/\//.test(raw) ? raw : `https://${raw}`;
  let parsed: URL;
  try {
    parsed = new URL(withScheme);
  } catch {
    throw new SettingsError('relay-invalid', '中继地址格式不正确');
  }
  const protocol = parsed.protocol.toLowerCase();
  // 只接受 https / wss。明文 http/ws 例外**只给两类地址**：
  //   ① loopback —— 本地联调（tests/ 里的假中继、直连内部端口排障）；
  //   ② **IP 字面量** —— 自建裸 IP 部署（`docker run` 起在 `http://1.2.3.4:8080`）。
  //      IP 证书要单独向 CA 申请，自建场景通常没有；中继那边也要求
  //      `RELAY_ALLOW_INSECURE=true` 才肯以明文启动，所以这条不是默认敞开的门。
  // 域名保持必须 https：那是最常见的手滑（把 https 写成 http），静默降级等于把
  // 令牌和归档正文暴露在链路上，而用户看不出任何区别。
  const loopback = parsed.hostname === 'localhost' || parsed.hostname === '127.0.0.1' || parsed.hostname === '::1' || parsed.hostname === '[::1]';
  const secure = protocol === 'https:' || protocol === 'wss:';
  const plain = protocol === 'http:' || protocol === 'ws:';
  const literal = isIPLiteral(parsed.hostname);
  if (!secure && !(plain && (loopback || literal))) {
    throw new SettingsError('relay-scheme', '中继地址必须是 https:// 或 wss://（明文 http/ws 仅限 localhost 或 IP 地址）');
  }
  if (parsed.username || parsed.password) throw new SettingsError('relay-userinfo', '中继地址不能包含用户名或密码');
  if (parsed.search || parsed.hash) throw new SettingsError('relay-query', '中继地址不能带查询串或锚点');
  if (!parsed.hostname) throw new SettingsError('relay-host', '中继地址缺少域名');
  const pathname = parsed.pathname.replace(/\/+$/, '');
  if (pathname !== '' && pathname !== '/tunnel') {
    throw new SettingsError('relay-path', '中继地址的路径只能是 /tunnel（例如 wss://relay.example.com/tunnel）');
  }
  const authority = parsed.host;
  // origin 的协议必须**跟着输入走**：`/control/enroll`、`/control/pair`、`/control/archive`
  // 都是打在这个 origin 上的真实 HTTP 请求，明文部署下写成 `https://` 会直接连不上。
  return { url: `${secure ? 'wss' : 'ws'}://${authority}/tunnel`, origin: `${secure ? 'https' : 'http'}://${authority}`, host: parsed.hostname };
}

/** `1.2.3.4` / `::1` / `[::1]` 这类 IP 字面量（URL 会把 IPv6 的方括号留在 hostname 里）。 */
function isIPLiteral(hostname: string): boolean {
  const h = hostname.replace(/^\[|\]$/g, '');
  return /^\d{1,3}(\.\d{1,3}){3}$/.test(h) || /^[0-9a-f:]+$/i.test(h) && h.includes(':');
}

/**
 * 规范化中继鉴权密码。
 *
 * <p>**不限定长度**：密码由用户自定义（中继侧的邀请码是 43 位，但部署方自己设的
 * 短密码同样合法）。这里只做三件事：
 * ① 去掉从网页复制时常带上的 `Bearer ` 前缀与首尾空白；
 * ② 拒绝空值；
 * ③ 拒绝含空白/控制字符的值——它们会破坏 `Authorization: Bearer <pwd>` 头，
 *    让请求在中继那一侧变成「凭据无效」，而用户在页面上看不出原因。
 *
 * @param input 原始输入
 * @returns 去掉首尾空白与 Bearer 前缀后的密码原文
 * @throws SettingsError 空值、含非法字符、或长度超出合理区间
 */
export function normalizeEnrollSecret(input: string): string {
  const raw = (input ?? '').trim().replace(/^Bearer\s+/i, '').trim();
  if (!raw) throw new SettingsError('secret-required', '请填写中继鉴权密码');
  if (ENROLL_SECRET_FORBIDDEN.test(raw)) {
    throw new SettingsError('secret-format', '中继鉴权密码不能包含空格、换行等空白字符（它们会破坏 Authorization 头）');
  }
  if (raw.length < ENROLL_SECRET_MIN || raw.length > ENROLL_SECRET_MAX) {
    throw new SettingsError('secret-length', `中继鉴权密码长度应在 ${ENROLL_SECRET_MIN}–${ENROLL_SECRET_MAX} 之间（当前 ${raw.length}）`);
  }
  return raw;
}

/** 落盘形态。字段可选：只保存用户显式设置过的项。 */
export interface StoredSettings {
  readonly version: 1;
  readonly relayUrl?: string;
  /**
   * 中继鉴权密码。
   *
   * <p>2026-10-28 起**注册成功后保留**（原先会清掉）：中继侧真正的凭据是部署方配置的
   * 长期密码，不是一次性邀请；清掉的结果是用户每次打开面板都要重新找一遍密码。
   */
  readonly enrollSecret?: string;
  /**
   * 「手机连着时别让电脑睡着」（2026-10-29 按用户要求加）。
   *
   * <p>缺省视为**关**：它会持有系统防休眠断言（Electron `powerSaveBlocker`，
   * 取不到时退 `caffeinate` / `systemd-inhibit`），属于会改变整台机器行为的开关，
   * 不能默认替用户打开。
   */
  readonly powerSave?: boolean;
}

export interface ResolvedSettings {
  /** 已规范化的中继地址；未配置时为空串（插件停在 unpaired）。 */
  readonly relayUrl: string;
  readonly relayOrigin: string | null;
  readonly enrollSecret: string;
  /** 设置来自哪里：`file` 表示用户在 UI 里保存过，`config` 表示只有 patch 缺省值。 */
  readonly source: 'file' | 'config' | 'none';
  /** 「隧道连上时阻止电脑休眠」是否开启；未设置过为 false。 */
  readonly powerSave: boolean;
}

/** 默认配置文件路径：`<DSH_HOME>/mobile-sh/config.json`。 */
export function defaultSettingsPath(): string {
  const override = process.env.DSH_HOME;
  const home = resolve(override !== undefined && override.length > 0 ? override : join(homedir(), '.dsh'));
  return join(home, 'mobile-sh', 'config.json');
}

/** 只读取并校验落盘内容；文件不存在或损坏都返回 undefined（不抛，UI 会显示未配置）。 */
export function readSettingsFile(path: string): StoredSettings | undefined {
  let text: string;
  try {
    text = readFileSync(path, 'utf8');
  } catch {
    return undefined;
  }
  try {
    const parsed = JSON.parse(text) as Record<string, unknown>;
    if (!parsed || typeof parsed !== 'object' || parsed.version !== 1) return undefined;
    const relayUrl = typeof parsed.relayUrl === 'string' ? parsed.relayUrl : undefined;
    const enrollSecret = typeof parsed.enrollSecret === 'string' ? parsed.enrollSecret : undefined;
    // 非布尔值一律当作「没设置过」：手改出来的 "true" 字符串不该悄悄打开系统防休眠。
    const powerSave = typeof parsed.powerSave === 'boolean' ? parsed.powerSave : undefined;
    return { version: 1, ...(relayUrl ? { relayUrl } : {}), ...(enrollSecret ? { enrollSecret } : {}), ...(powerSave === undefined ? {} : { powerSave }) };
  } catch {
    // 手改坏了不要让插件整体起不来：当作未配置，UI 会提示重新保存。
    return undefined;
  }
}

/**
 * 原子写入设置文件（0600）。
 *
 * <p>先写临时文件再 rename：断电/强杀时不会留下半截 JSON，而半截 JSON 的后果是
 * 下次启动静默回到 unpaired。POSIX 上 rename 覆盖是原子的；Windows 上
 * `renameSync` 对已存在文件也可能失败，因此失败时退回直接覆盖。
 *
 * @param path 目标路径
 * @param value 要保存的设置
 */
export function writeSettingsFile(path: string, value: StoredSettings): void {
  mkdirSync(dirname(path), { recursive: true, mode: 0o700 });
  const text = `${JSON.stringify(value, null, 2)}\n`;
  const temp = `${path}.tmp`;
  writeFileSync(temp, text, { mode: 0o600 });
  try {
    renameSync(temp, path);
  } catch {
    writeFileSync(path, text, { mode: 0o600 });
  }
  try {
    chmodSync(path, 0o600);
  } catch {
    // Windows 没有 POSIX 权限位；目录 ACL 已经限定了可读范围。
  }
}

/**
 * 合并「文件里的用户设置」与「patch 里的部署缺省值」。
 *
 * <p>优先级：文件 > patch 配置。理由是文件只会在用户点了保存之后才存在，
 * 属于显式选择；patch 是 operator 写的缺省值，用户改完应当立即覆盖它。
 *
 * @param stored 落盘设置（可能不存在）
 * @param configRelayUrl patch 提供的 relayUrl（可能为空）
 */
export function resolveSettings(stored: StoredSettings | undefined, configRelayUrl: string): ResolvedSettings {
  const secret = stored?.enrollSecret ?? '';
  // 防休眠只有「用户显式打开」才算开：patch 不提供这项，缺省 false。
  const powerSave = stored?.powerSave === true;
  const fromFile = (stored?.relayUrl ?? '').trim();
  const fromConfig = (configRelayUrl ?? '').trim();
  if (fromFile) {
    try {
      const target = normalizeRelayUrl(fromFile);
      return { relayUrl: target.url, relayOrigin: target.origin, enrollSecret: secret, powerSave, source: 'file' };
    } catch {
      // 文件里的值坏了（老版本写的、手改错的）：退回 patch 缺省值，而不是整插件停摆。
    }
  }
  if (fromConfig) {
    try {
      const target = normalizeRelayUrl(fromConfig);
      return { relayUrl: target.url, relayOrigin: target.origin, enrollSecret: secret, powerSave, source: 'config' };
    } catch {
      return { relayUrl: '', relayOrigin: null, enrollSecret: secret, powerSave, source: 'none' };
    }
  }
  return { relayUrl: '', relayOrigin: null, enrollSecret: secret, powerSave, source: 'none' };
}
