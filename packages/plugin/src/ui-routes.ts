/**
 * 插件 UI 的 HTTP 面：`/dsh-mobile-sh/*`。
 *
 * <p>为什么用宿主 webServer 的独立路由而不是往 SPA 里塞组件：
 * 这个页面要处理中继鉴权密码与配对二维码，两者都属于「不该进前端状态树」的东西。
 * 独立路由可以让整页跑在 `default-src 'none'` 的 CSP 下，且浏览器侧只需要一个
 * iframe（见 `browser.ts`）——客户端半因此不需要打包 React 组件、也不用同步宿主主题。
 *
 * <p>鉴权：走 `ctx.connection.admit(req)`，与宿主 `/api` 同一条准入判定
 * （Host/Origin 围栏 + 浏览器会话）。**失败即拒绝**（fail closed）：拿不到
 * connection 服务时不放行——这个页面能改中继地址、能签配对码，不能靠「服务缺失
 * 就默认允许」来兜底。
 */
import type { IncomingMessage, ServerResponse } from 'node:http';
import { randomBytes } from 'node:crypto';
import { renderPage, type PageState } from './ui-page.js';
import { SettingsError } from './settings.js';
import { PairingError } from './pairing.js';
import type { MobileShService } from './lifecycle.js';

/** 路由前缀。客户端半与页面内的相对请求都以它为准。 */
export const UI_PREFIX = '/dsh-mobile-sh';

/** 本模块用到的最小 ctx 面（webServer 注册 + connection 准入）。 */
export interface UiContext {
  webServer: {
    getOrigin(): string;
    register(route: { kind: 'exact' | 'prefix'; path: string; handler: (req: IncomingMessage, res: ServerResponse) => void | Promise<void> }): () => void;
  };
  connection?: {
    /** 宿主 0.2.x 的准入判定：`{peer}` 通过，`{rejection}` 是 HTTP 状态码。 */
    admit?(req: IncomingMessage): { peer?: unknown; rejection?: number };
  };
  logger: { info(...v: unknown[]): void; warn(...v: unknown[]): void };
}

/** 请求体上限：设置页只有两个短字符串，64 KiB 已经非常宽裕。 */
const BODY_LIMIT = 64 * 1024;

function json(res: ServerResponse, status: number, value: unknown): void {
  const body = JSON.stringify(value);
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' });
  res.end(body);
}

async function readJson(req: IncomingMessage): Promise<Record<string, unknown>> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of req) {
    size += (chunk as Buffer).length;
    if (size > BODY_LIMIT) throw new SettingsError('body-limit', '请求体过大');
    chunks.push(chunk as Buffer);
  }
  if (size === 0) return {};
  const type = (req.headers['content-type'] ?? '').split(';')[0]?.trim().toLowerCase();
  if (type !== 'application/json') throw new SettingsError('content-type', '请求必须是 application/json');
  try {
    const parsed = JSON.parse(Buffer.concat(chunks).toString('utf8')) as unknown;
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('not-object');
    return parsed as Record<string, unknown>;
  } catch {
    throw new SettingsError('invalid-body', '请求体不是合法 JSON');
  }
}

/**
 * 注册 UI 路由。
 *
 * @param ctx 宿主上下文（webServer + connection）
 * @param service 插件对外服务面（读写设置、签发配对码）
 * @param embeddedOrigin 页面自身源，仅用于日志与 CSP 说明
 * @returns disposer（插件卸载时摘掉路由）
 */
export function registerUiRoutes(ctx: UiContext, service: MobileShService): () => void {
  const disposers: Array<() => void> = [];

  /** 准入：必须通过宿主浏览器会话；拿不到 connection 就拒绝。 */
  const admitted = (req: IncomingMessage, res: ServerResponse): boolean => {
    const admit = ctx.connection?.admit;
    if (typeof admit !== 'function') {
      json(res, 503, { ok: false, error: '宿主未提供 connection 服务，无法验证浏览器会话' });
      return false;
    }
    const result = admit.call(ctx.connection, req);
    if (result.rejection !== undefined) {
      // 401 让前端知道「去登录 DSH」，403 是 Host/Origin 围栏（配置问题）。
      json(res, result.rejection, { ok: false, error: result.rejection === 401 ? '浏览器会话已失效，请刷新 DSH 页面' : '请求被宿主拒绝（Host/Origin 校验未通过）' });
      return false;
    }
    return true;
  };

  const pageState = (embedded: boolean): PageState => {
    const view = service.settings();
    const status = service.status();
    return {
      state: status.state,
      hostId: status.hostId,
      relayOrigin: view.relayOrigin,
      relayUrl: view.relayUrl,
      relaySource: view.relaySource,
      secretConfigured: view.secretConfigured,
      enrollSecret: view.enrollSecret,
      // 老中继/老插件组合下 powerSave 可能缺失：兜底成「关闭 + 不支持」，页面画成不可用。
      powerSave: view.powerSave ?? { enabled: false, active: false, supported: false },
      version: status.version,
      embedded,
    };
  };

  const servePage = (embedded: boolean) => (req: IncomingMessage, res: ServerResponse): void => {
    if ((req.method ?? 'GET') !== 'GET') {
      res.writeHead(405, { allow: 'GET', 'content-type': 'text/plain; charset=utf-8' });
      res.end('method not allowed');
      return;
    }
    if (!admitted(req, res)) return;
    // nonce 必须同时出现在响应头与页面标签上：写进 `'unsafe-inline'` 以外的方式是为了
    // 万一将来有人在页面里插了一段 `<script>`，浏览器会直接拒掉。
    const nonce = randomBytes(16).toString('base64');
    const html = renderPage(pageState(embedded), nonce);
    res.writeHead(200, {
      'content-type': 'text/html; charset=utf-8',
      'cache-control': 'no-store',
      'referrer-policy': 'no-referrer',
      'x-content-type-options': 'nosniff',
      // 页面自身不带外部资源：样式与脚本都走 nonce，二维码是 data: URL。
      // frame-ancestors 放开到 self 是必须的——面板用 iframe 内嵌同一个源。
      'content-security-policy': `default-src 'none'; img-src data:; style-src 'nonce-${nonce}'; script-src 'nonce-${nonce}'; connect-src 'self'; form-action 'none'; base-uri 'none'; frame-ancestors 'self'`,
    });
    res.end(html);
  };

  /**
   * 连接状态查询（页面保存后轮询它，直到 ready 或失败）。
   *
   * <p>为什么需要它：`POST /api/settings` 返回时重连才刚**开始**——`saveSettings()`
   * 里的 `restart()` 只是把新隧道挂上，握手是异步的。早期版本保存后只显示
   * 「正在重连…」就再无下文，用户完全不知道到底连上没有（实测反馈）。
   *
   * <p>只暴露 `state` 与 `hostId`：这两个正是「连上没有」的判据，其余诊断字段
   * 页面已经不再展示，不开无谓的口子。
   */
  const handleStatus = (req: IncomingMessage, res: ServerResponse): void => {
    if ((req.method ?? 'GET') !== 'GET') { json(res, 405, { ok: false, error: 'method not allowed' }); return; }
    if (!admitted(req, res)) return;
    const status = service.status();
    // powerSave 一并回：隧道从 connecting 走到 ready 时，页面上的「正在防休眠」才会变准。
    json(res, 200, { ok: true, state: status.state, hostId: status.hostId, powerSave: service.settings().powerSave });
  };

  const handleSettings = async (req: IncomingMessage, res: ServerResponse): Promise<void> => {
    if ((req.method ?? 'GET') !== 'POST') { json(res, 405, { ok: false, error: 'method not allowed' }); return; }
    if (!admitted(req, res)) return;
    try {
      const body = await readJson(req);
      const relayUrl = typeof body.relayUrl === 'string' ? body.relayUrl : undefined;
      const enrollSecret = typeof body.enrollSecret === 'string' ? body.enrollSecret : undefined;
      const powerSave = typeof body.powerSave === 'boolean' ? body.powerSave : undefined;
      const saved = await service.saveSettings({ ...(relayUrl === undefined ? {} : { relayUrl }), ...(enrollSecret === undefined ? {} : { enrollSecret }), ...(powerSave === undefined ? {} : { powerSave }) });
      json(res, 200, { ok: true, relayUrl: saved.relayUrl, relaySource: saved.relaySource, powerSave: saved.powerSave });
    } catch (error) {
      if (error instanceof SettingsError) { json(res, 400, { ok: false, error: error.message, code: error.code }); return; }
      const message = error instanceof Error ? error.message : String(error);
      ctx.logger.warn('mobile-sh settings save failed: %s', message);
      json(res, 500, { ok: false, error: `保存失败：${message}` });
    }
  };

  /**
   * 单独切换「防休眠」：不走 saveSettings（那条路会重连中继，且需要重新校验域名/密码）。
   *
   * <p>用一个独立端点而不是复用 `/api/settings`，是因为这个开关是纯本机行为，
   * 用户点它的时候心里想的是「别让电脑睡着」，不是「改连接配置」。
   */
  const handlePower = async (req: IncomingMessage, res: ServerResponse): Promise<void> => {
    if ((req.method ?? 'GET') !== 'POST') { json(res, 405, { ok: false, error: 'method not allowed' }); return; }
    if (!admitted(req, res)) return;
    try {
      const body = await readJson(req);
      if (typeof body.enabled !== 'boolean') { json(res, 400, { ok: false, error: 'enabled 必须是布尔值' }); return; }
      json(res, 200, { ok: true, powerSave: service.setPowerSave(body.enabled) });
    } catch (error) {
      if (error instanceof SettingsError) { json(res, 400, { ok: false, error: error.message, code: error.code }); return; }
      const message = error instanceof Error ? error.message : String(error);
      ctx.logger.warn('mobile-sh power toggle failed: %s', message);
      json(res, 500, { ok: false, error: `切换失败：${message}` });
    }
  };

  const handlePair = async (req: IncomingMessage, res: ServerResponse): Promise<void> => {
    if ((req.method ?? 'GET') !== 'POST') { json(res, 405, { ok: false, error: 'method not allowed' }); return; }
    if (!admitted(req, res)) return;
    try {
      const result = await service.pair();
      // login 不下发给页面：一码配齐的登录码只活在二维码 URI 里，页面人工码只展示
      // 配对码本身（用户手输兜底路径用不到登录码，扫码才需要）。
      json(res, 200, { ok: true, code: result.code, expiresAt: result.expiresAt, ttlSeconds: result.ttlSeconds, uri: result.uri, qr: result.qr });
    } catch (error) {
      if (error instanceof PairingError) {
        // 中继不可达是 502（上游问题），其余是 400（用户要改配置）。
        json(res, error.code === 'relay-unreachable' ? 502 : 400, { ok: false, error: error.message, code: error.code });
        return;
      }
      const message = error instanceof Error ? error.message : String(error);
      ctx.logger.warn('mobile-sh pair failed: %s', message);
      json(res, 500, { ok: false, error: `生成配对码失败：${message}` });
    }
  };

  const guard = (handler: (req: IncomingMessage, res: ServerResponse) => void | Promise<void>) =>
    (req: IncomingMessage, res: ServerResponse): void => { void Promise.resolve(handler(req, res)).catch((error: unknown) => {
      if (res.headersSent) { res.destroy(); return; }
      ctx.logger.warn('mobile-sh ui route error: %s', error instanceof Error ? error.message : String(error));
      json(res, 500, { ok: false, error: '内部错误' });
    }); };

  disposers.push(ctx.webServer.register({ kind: 'exact', path: `${UI_PREFIX}`, handler: guard(servePage(false)) }));
  disposers.push(ctx.webServer.register({ kind: 'exact', path: `${UI_PREFIX}/embed`, handler: guard(servePage(true)) }));
  disposers.push(ctx.webServer.register({ kind: 'exact', path: `${UI_PREFIX}/api/status`, handler: guard(handleStatus) }));
  disposers.push(ctx.webServer.register({ kind: 'exact', path: `${UI_PREFIX}/api/settings`, handler: guard(handleSettings) }));
  disposers.push(ctx.webServer.register({ kind: 'exact', path: `${UI_PREFIX}/api/power`, handler: guard(handlePower) }));
  disposers.push(ctx.webServer.register({ kind: 'exact', path: `${UI_PREFIX}/api/pair`, handler: guard(handlePair) }));

  return () => { for (const dispose of disposers.splice(0)) dispose(); };
}
