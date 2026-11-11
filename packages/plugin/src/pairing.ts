/**
 * 手机配对：向中继要一个 8 位配对码，并把它渲染成 App 能扫的二维码。
 *
 * <p>链路选择：配对码必须由**中继**签发（它是唯一知道「这台主机属于哪个账号」的一方），
 * 但手机端扫码时只认 `dsh://pair?relay=…&code=…`（见 android ScanScreen.parsePairing）。
 * 所以插件做两件事：用自己已有的隧道凭据向中继换码，再把码拼成 App 认识的 URL 画成二维码。
 *
 * <p>凭据：用的是隧道那条 `hostTunnelToken`，不引入任何新密钥。中继侧在
 * `POST /control/pair` 里用 `store.authenticate(hostId, token)` 校验——和
 * `/control/archive` 同一个凭据、同一套失败语义（403 即「这台主机没被授权」）。
 *
 * <p>二维码在**服务端**画成 SVG（`qrcode` 已在插件依赖里，dist 内联）：浏览器侧不需要
 * 额外的 canvas 库，也不用把配对码交给前端脚本；DOM 里就是一段 `<img src="data:image/svg+xml…">`。
 */
import QRCode from 'qrcode';
import { z } from 'zod';

/** 中继返回的配对码。TTL 由中继决定（当前 5 分钟），这里只做展示。 */
export interface PairCode {
  readonly code: string;
  /** 一码配齐：与配对码同批签发的设备登录码，手机兑换后即完成登录，全程不进用户视线。 */
  readonly login: string;
  readonly expiresAt: number;
  readonly ttlSeconds: number;
}

/** 配对码响应的线上格式；字段名与 relay `POST /control/pair` 一致。 */
const pairResponse = z.object({
  code: z.string().regex(/^\d{8}$/),
  /** 老中继不带 login（只回配对码）：此时手机仍需走旧的两步登录。 */
  login: z.string().regex(/^\d{8}$/).optional(),
  expiresAt: z.number().int().positive(),
});

export class PairingError extends Error {
  constructor(readonly code: string, message: string, readonly status = 0) {
    super(message);
    this.name = 'PairingError';
  }
}

/**
 * 向中继申请一个配对码。
 *
 * <p>错误分层很关键：UI 要能区分「中继没连上」和「中继拒绝了我」——前者让用户查网络，
 * 后者让用户去中继管理页确认主机还在。所以 4xx 带 `status`，网络/超时统一为
 * `relay-unreachable`。
 *
 * @param options.relayOrigin 中继 https 源（如 `https://relay.example.com`）
 * @param options.hostId 本机在中继上的主机 id
 * @param options.hostTunnelToken 隧道凭据（43 位 base64url）
 * @param options.fetchImpl 测试注入
 */
export async function requestPairCode(options: {
  relayOrigin: string;
  hostId: string;
  hostTunnelToken: string;
  timeoutMs?: number;
  fetchImpl?: typeof fetch;
}): Promise<PairCode> {
  const doFetch = options.fetchImpl ?? fetch;
  let response: Response;
  try {
    response = await doFetch(new URL('/control/pair', options.relayOrigin), {
      method: 'POST',
      redirect: 'error',
      signal: AbortSignal.timeout(options.timeoutMs ?? 15000),
      headers: { authorization: `Bearer ${options.hostTunnelToken}`, 'content-type': 'application/json' },
      body: JSON.stringify({ hostId: options.hostId }),
    });
  } catch (error) {
    const reason = error instanceof Error ? error.message : String(error);
    throw new PairingError('relay-unreachable', `连不上中继（${reason}）`);
  }
  if (!response.ok) {
    const detail = await response.text().catch(() => '');
    // 403 = 凭据被撤销/主机被删；404 = 主机不在这个账号下。两者都要人处理，不能自动重试。
    const message = response.status === 403
      ? '中继拒绝签发配对码：这台主机的凭据已失效，请在中继管理页重新注册主机'
      : response.status === 404
        ? '中继上找不到这台主机，请在中继管理页确认它仍存在'
        : `中继返回 ${response.status}${detail ? `：${detail.slice(0, 120)}` : ''}`;
    throw new PairingError(response.status === 403 ? 'credential-rejected' : 'relay-error', message, response.status);
  }
  let parsed: z.infer<typeof pairResponse>;
  try {
    parsed = pairResponse.parse(await response.json());
  } catch {
    throw new PairingError('relay-response', '中继返回的配对码格式不正确');
  }
  const ttlSeconds = Math.max(0, Math.round((parsed.expiresAt - Date.now()) / 1000));
  // login 兜底成空串：老中继不签登录码（只回配对码），调用方按「只有 code」降级。
  return { code: parsed.code, login: parsed.login ?? '', expiresAt: parsed.expiresAt, ttlSeconds };
}

/**
 * 手机 App 的扫一扫认这个形状。
 *
 * <p>`login` 是「一码配齐」的关键（2026-10-27 按用户要求改）：手机扫这一个码就完成
 * 「登录中继 + 配对主机」，登录码只存在于二维码内容里，用户全程看不见也不用手填。
 * 老版 App 忽略多余参数、只读 `relay` 与 `code`，因此新码对旧 App 仍兼容（只是
 * 还得走旧的两步登录）。
 *
 * @param relayOrigin 中继 https 源
 * @param code 8 位主机配对码
 * @param login 8 位设备登录码（与配对码同批签发）；空串表示老中继、不拼进 URI
 */
export function pairingUri(relayOrigin: string, code: string, login = ''): string {
  const loginPart = login ? `&login=${encodeURIComponent(login)}` : '';
  return `dsh://pair?relay=${encodeURIComponent(relayOrigin)}&code=${encodeURIComponent(code)}${loginPart}`;
}

/**
 * 把配对 URI 画成 SVG。
 *
 * <p>用 SVG 而不是 PNG data URL：矢量在小屏和高分屏都清楚，体积也只有几 KB，
 * 而且 `img-src data:` 就够，CSP 不用放开 blob/script。
 *
 * @param uri 配对 URI
 * @param size 显示边长（px），仅影响 SVG 的 width/height，不影响模块数
 */
export async function pairingQrSvg(uri: string, size = 220): Promise<string> {
  return await QRCode.toString(uri, { type: 'svg', margin: 1, width: size, errorCorrectionLevel: 'M' });
}

/** SVG 文本 → data URL（页面里直接给 `<img src>`）。 */
export function svgDataUrl(svg: string): string {
  return `data:image/svg+xml;base64,${Buffer.from(svg, 'utf8').toString('base64')}`;
}
