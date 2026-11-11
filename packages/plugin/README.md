# DSH Mobile SH Host 插件

先在仓库根目录运行 `npm ci && npm run build`，再通过 DSH 的 `plugin_manager`、`action: install_bundle` 安装本目录的绝对路径。不要手工编辑 profile。

完整项目说明、Agent 安装与手动安装步骤见项目仓库根目录 `README.md`；本包附带 `LICENSE`（MIT），第三方依赖许可证汇总见 `THIRD_PARTY_NOTICES.md` 与 `dist/NOTICE.txt`。

## 插件 UI（0.2.0 起）

装好之后左侧栏多一行「手机访问」，点开是设置页：填**中继地址**、**中继鉴权密码**，保存即生效（不重启 DSH），再点「生成二维码」用手机 App 的「扫一扫」配对。页面也可以直接用浏览器打开 `/dsh-mobile-sh`（需要已登录的 DSH 浏览器会话）。

- 中继地址填域名即可（`relay.example.com`），会自动补成 `wss://relay.example.com/tunnel`；也接受 `https://…`、`wss://…/tunnel`。明文 `http/ws` 只在 `localhost`/`127.0.0.1` 上放行，用于本地联调与排障。
- 中继鉴权密码用你在中继上设定的那个即可（单一密码模式下至少 12 字符）；若用中继自动签发的「主机注册邀请」，直接粘那串（通常较长）。它只用于**首次注册**：注册成功后插件自动清除，之后靠 `ctx.credentials` 里的长期令牌重连，不再需要它。
- 设置落在 `<DSH_HOME>/mobile-sh/config.json`（默认 `~/.dsh/mobile-sh/config.json`，权限 0600），**优先于** profile 里 `cordis.patch.yml` 的 `relayUrl`。想回到 patch 的地址，删掉这个文件即可。
- 二维码由中继签发（插件用隧道凭据向 `POST /control/pair` 换 8 位码），5 分钟有效、一次性；手机上兑换后即获得这台主机的访问授权。
- 页面不回显鉴权密码；配对码不落盘，只在这次响应里出现。

## 无 UI 的部署方式

启动 DSH 前设置 `DSH_MOBILE_SH_RELAY=wss://你的中继域名/tunnel`。首次注册另以一次性 secret 环境设置 `DSH_MOBILE_SH_ENROLL_INVITATION`，从已登录且近期验证的中继管理页取得；成功后插件从本进程环境删除此值。不要把邀请写入仓库、profile 或命令历史。长期令牌通过 `ctx.credentials` 持久化，不下发手机。

`DSH_MOBILE_SH_HOT_RELOAD=0` 关掉开发期的自热重载（生产建议关）。

## 支持面

只支持 Node 24.12+ 与 DSH Web profile 的 HTTP `127.0.0.1` 监听。发布前执行 `npm run check` 与 `npm pack --workspace packages/plugin --dry-run` 作为构建门禁；宿主 API 不可用时保持 degraded/unpaired。状态由 `ctx.mobileSh.status()` 查询；UI 的读写走 `/dsh-mobile-sh/api/*`，两处都需要已登录的浏览器会话（`ctx.connection.admit`），拿不到 connection 服务时一律拒绝。
