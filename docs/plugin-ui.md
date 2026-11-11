# 插件 UI：实现要点与验收标准

> 任务 t-mvqkyuvn-5wm26a「新增：插件UI」· 2026-10-27
> 需求原文：**插件需要有 UI，可以填写 relay 地址，中继鉴权密码，生成二维码。**

## 一、需求边界

### 做什么

| # | 能力 | 落点 |
|---|---|---|
| 1 | 左侧栏出现「手机访问」面板行，点开是插件设置页 | `packages/plugin/src/browser.ts`（客户端半） |
| 2 | 页面可填写**中继地址**并保存，保存后立即用新地址重连，不重启 DSH | `settings.ts` + `lifecycle.ts` 的 `saveSettings`/`restart` |
| 3 | 页面可填写**中继鉴权密码**（用户自定义，长度不限），保存后用于首次注册；注册成功自动清除 | `settings.ts` + `lifecycle.ts` 的 enroll 分支 |
| 4 | 点「生成二维码」得到手机 App 可扫的配对二维码（含 8 位码与失效倒计时） | `pairing.ts` + 中继新增 `POST /control/pair` |
| 5 | 设置页可脱离面板直接用浏览器访问 `/dsh-mobile-sh` | `ui-routes.ts` |

### 不做什么（明确排除，避免范围蔓延）

- **不做**中继侧的账号管理 UI（登录/改密/设备列表）——那是中继网页自己的职责，插件不复制。
- **不做**二维码的本地签发：配对码必须由中继签（只有它知道 host→account 归属，手机端兑换查的也是中继的 `pairs` 表）。插件只做「拿码 + 画图」。
- **不引入**新的长期密钥：签码沿用隧道凭据 `hostTunnelToken`，撤销隧道凭据即同时撤销签码能力。
- **不把** React 组件塞进主 SPA：设置页是宿主渲染的独立页面，面板用 iframe 内嵌（理由见 `ui-page.ts` 文件头）。
- **不回显**鉴权密码，**不落盘**配对码。

## 二、验收标准（可执行）

| # | 标准 | 验证方式 |
|---|---|---|
| A1 | 中继地址任意常见写法都收敛成同一值：`relay.example.com` / `https://relay.example.com` / 带尾斜杠 / `wss://…/tunnel` → `wss://relay.example.com/tunnel` | `tests/plugin-ui.test.ts` · relay address normalizes… |
| A2 | 非法地址在**写盘前**被拒：域名上的明文、带 userinfo、路径不是 `/tunnel`、带 query/hash、空值；明文只放行 loopback 与 **IP 字面量**（裸 IP 自建部署），且 `origin` 跟着协议走（`ws`→`http`、`wss`→`https`） | 同上 + `saving an invalid relay address is rejected…` |
| A3 | 中继鉴权密码**不限长度**（用户自定义），只拒绝空值、含空白/控制字符（会破坏 Authorization 头）、超过 512 位 | `relay secret is user-defined…` + `relay enroll accepts a user-defined password of any length` |
| A4 | 保存后设置落盘到 `config.json`，且**文件优先于** patch 的 `relayUrl`；文件损坏时退回 patch 而不是插件停摆 | `settings file round-trips…` + `saving settings from the UI…` |
| A5 | 保存后立即重连：旧隧道被停、按新地址重新建连，无需重启 DSH | `saving settings from the UI writes the file and reconnects the tunnel` |
| A6 | 中继只对**凭据正确的主机**签发配对码（错/缺凭据一律 403），同一档限流 5 分钟 10 次 | `relay issues a pair code…` + `relay pair endpoint rate-limits…` |
| A7 | 手机端能用这个码兑换到授权（端到端：签发 → `/native/v1/pair` 等价路径 → 得到同一 hostId），且码一次性 | `relay issues a pair code…`（后半段） |
| A8 | 二维码内容是 App 认的 `dsh://pair?relay=…&code=…`，且以 SVG data URL 交付（无外部资源） | `pairing URI keeps the shape the Android scanner parses` + `QR renders as an SVG data URL…` |
| A9 | UI 路由**失败即拒绝**：拿不到 `connection.admit` 时返回 503，绝不默认放行 | `ui routes fail closed without a connection service…` |
| A10 | 页面不回显鉴权密码；CSP 用 nonce 而非 `unsafe-inline` | `ui page renders settings, hides the secret…` |
| A11 | 未注册到中继时点「生成二维码」给出「先完成中继设置」的提示，而不是 500 | `pair() refuses before enrollment…` |
| A12 | 全部相关测试通过：`npm run check`（tsc + 命名校验 + 83 项测试 + esbuild 打包） | 命令退出码 0 |

## 三、实现要点

### 数据与设置

- `packages/plugin/src/settings.ts`：`normalizeRelayUrl` / `normalizeEnrollSecret` / `readSettingsFile` / `writeSettingsFile` / `resolveSettings`。
- 优先级 **文件 > patch**：文件只在用户点保存后存在，属显式选择；patch 是 operator 写的缺省值。
- 原子写（临时文件 + rename）避免半截 JSON 让下次启动静默回到 unpaired；权限 0600，因为里面有中继鉴权密码。
- 损坏的文件按「未配置」处理并退回 patch——配置文件手改坏了不该让插件整体起不来。
- **鉴权密码不限长度**（2026-10-27 按用户要求改）：早期版本硬性要求 43 位 base64url（那是中继 `/invitation` 自动签发的邀请格式），用户自己设的短密码根本存不进去。现在只拒绝空值、含空白/控制字符（会破坏 `Authorization: Bearer` 头）、超过 512 位。中继侧的 `bearer()` / `/control/enroll` / `/control/pair` / `/control/archive` 同步放开，真正的有效性仍由 `store.authenticate()` 的哈希比对判定。

### 生命周期

- `activate()` 现在返回 `{stop, service}`，并把 `service` 挂到 `ctx.provide('mobileSh', …)`（原有 `status`/`disconnect` 保留，新增 `settings`/`saveSettings`/`pair`）。
- 重连用**代号**（`generation`）做代际隔离：旧 `run()` 发现代号变了就自行退出，避免两条隧道抢同一个 hostId。
- `restart()` 串行化（`restarting` promise）：设置页连点保存不会让旧 client 的 `stop()` 落到新 client 上。
- 注册成功后清空设置文件里的邀请（一次性凭据留在磁盘上只会误用）。

### 中继

- `packages/relay/src/store.ts` 新增 `createPairForHost(hostId, token)`：先 `authenticate` 再写 pairs 表，与网页 `/pair/create` 写同一张表、同一 TTL、同一次性语义。
- `packages/relay/src/server.ts` 新增 `POST /control/pair`：鉴权走 `Bearer <hostTunnelToken>`，限流键按主机 id（与网页同档），返回 `{code, expiresAt}`。
- 为什么不复用 `createPair(s, hostId)`：它要一个 account 会话行，而插件手里没有（也不该有）浏览器 cookie；让桌面进程为了签一个码去走邮箱密码 + TOTP 等于把人类凭据复制到进程里。

### 页面与路由

- `ui-routes.ts` 注册 4 条路由：`/dsh-mobile-sh`（整页）、`/embed`（内嵌版）、`/api/settings`、`/api/pair`。
- 鉴权统一走 `ctx.connection.admit(req)`，与宿主 `/api` 同一条准入判定；**服务缺失即 503**。
- CSP：`default-src 'none'` + nonce 的 style/script + `img-src data:` + `frame-ancestors 'self'`。
- `ui-page.ts` 服务端渲染，含明暗两套配色（`prefers-color-scheme`），不依赖宿主主题 token。

### 客户端半

- `browser.ts` 只依赖 `slots`：`sidebar.panellist` 出一行 + `main` 放 iframe；两个槽位缺一就不注册（半个面板会在点开时留下空白主区）。
- 打包：`scripts/build.mjs` 用 esbuild 出 CJS 再包一层 `window.__ModuleLoader__.load({id, factory})`，`react` 保持 external（宿主模块表提供）。守卫断言：必须有 `require("react")`、不得有 ESM import、必须带外壳。
- `packages/plugin/package.json` 新增 `"./client"` 导出与 `dsh.client`（`platform: web`、`immediately: true`）。

## 四、已知取舍

| 取舍 | 原因 | 影响 |
|---|---|---|
| 页面用 iframe 内嵌，不是原生 React 面板 | 设置页要收密码、画二维码；独立页面能跑严格 CSP，客户端半因此不打包 React、不跟宿主主题 | 面板内滚动由 iframe 自己处理；高度取 `100%` + `min-height: 640px` |
| 明文 `http/ws` 只放行 loopback 与 IP 字面量，且 `origin` 跟着协议走 | 裸 IP 自建部署（`docker run -p 8080:8080`，对外是 `http://1.2.3.4:8080`）拿不到 IP 证书；中继侧还要显式 `RELAY_ALLOW_INSECURE=true` 才肯明文启动。域名保持必须 https：那是最常见的手滑，静默降级会把令牌与归档正文摊在链路上 | 域名填 `http://` 仍会被拒并提示改用 https；IP 上的注册/签码/归档与隧道**同协议**，否则会连到一个不存在的 TLS 端口 |
| 设置文件优先于 patch | 用户显式保存必须能覆盖 operator 缺省值 | 想回到 patch 地址需删掉 `config.json` |
| 二维码在服务端渲染成 SVG | 前端不需要 canvas 库，配对码不进前端状态树 | 页面必须与宿主同源（面板就是这么用的） |

## 五、怎么复验

```bash
npm run check                            # tsc + 命名校验 + 88 项测试 + esbuild 打包
npx tsx scripts/e2e-plugin-ui.ts         # 真中继 + TLS 前置 + 真 lifecycle：页面→保存→二维码→手机兑换
npx tsx scripts/verify-custom-password.ts # 用 ~/Documents/keys 里的真实密码验证「自定义长度」注册链路
```

端到端脚本用的 TLS 物料是 `artifacts/android-fixture.p12`（`scripts/init-android-fixture.ps1` 产物，
口令 `local-fixture-only`，仅本机测试用）。脚本里 `NODE_TLS_REJECT_UNAUTHORIZED=0` 只影响它自己发起的
请求，不写进任何配置。

`verify-custom-password.ts` 从 `~/Documents/keys` 的 `dsh-mobile-sh-relay-pwd` 读密码，
**只打印长度、不打印内容**；它把该密码写成一条邀请再走真实 HTTPS 前置注册，用来证明
「非 43 位的自定义密码能注册、错密码仍 403」。
