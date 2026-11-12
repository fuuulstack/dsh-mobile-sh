# dsh-mobile-sh 接手文档

> 更新于 2026-11-12 · 本轮：中继客户端类型隔离、旧部署启动兼容与 ChatGPT 独立 APK 渠道；保留 DSH 原有交接事项。

## 一、现在

**状态**：DSH 手机仍为 0.2.61/vc62，原 Android 543 项通过。中继新增 chatgpt/dsh 设备与接口隔离、旧主机 identify 补登记、已有账号升级免管理员明文密码；ChatGPT APK 独立台账与下载渠道已部署，关联仓库已发布 0.2.63。中继 265 项测试中 264 通过、1 跳过，类型、命名与打包门禁通过；DSH 版本台账未变。

**卡点**：DSH 真机长按删除未复现；同轮两段折叠行待修；子智能体活体仅假宿主验证；「已中断」误报修复及其余条目待真机复验。

**下一步**：修折叠行，复验中断状态、子代理胶囊与删除；ChatGPT 手机控制在关联仓库继续验收。旧分支清理需用户授权。

## 二、环境与入口

| 项目 | 事实 |
|---|---|
| 工作目录 | `~/WebstormProjects/dsh-mobile-sh`（main；git 走 Clash 代理 `127.0.0.1:7890`，直连 GitHub 报 `LibreSSL SSL_ERROR_SYSCALL`） |
| Node | macOS 默认 v22 会让 `npm run check` 报 `argon2` 导出缺失；先 `export PATH="$HOME/.nvm/versions/node/v24.13.0/bin:$PATH"` |
| TS 门禁 | 项目根 `npm run check` ＝ tsc + 命名门禁 + 265 项测试（264 通过、1 跳过）+ esbuild 打包；全新 clone 先 `npm run build`（`tests/plugin-ui.test.ts` 要读 `dist/client.js`） |
| Android 门禁 | `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug`；JDK 17 + `ANDROID_HOME`（系统 JDK 26 会让 Gradle 8.13 报 major version 70）；`set -o pipefail`（`| tail` 吞退出码） |
| 签名 / 发版 | release 用服务器 `dsh-release.keystore`，`ANDROID_KEYSTORE` 四件套全走环境变量，指纹用 `apksigner verify --print-certs` 核对；`bash scripts/release-android.sh <ver> "说明"`，目标与清单地址由 `RELAY_URL` / `RELEASE_SSH_HOST` / `RELEASE_BASE_URL` / `-PreleaseFallbackManifestUrl` 注入 |
| 本机插件 | `~/.dsh/profiles/desktop/node_modules/dsh-mobile-sh/dist/`；同版本号别用 `dsh plugin add`（不重新解包，坑 31），直接覆盖 5 个 dist 文件并看热重载日志；装的是哪版看 `dist/build-info.json` |
| 调试 | 移动端一律 mobilewright（`~/.agents/mcp/mobilewright/mobilewright-cli`；模拟器 id `test_emulator`、adb `emulator-5554`）；别与 `uiautomator dump` 同用（抢 UiAutomationService，坑 59） |
| 文档入口 | `README.md`（项目说明 / 用法 / 安装 / MIT）· `docs/PRD.md`（需求 / 契约 / 验收）· `AGENTS.md`（功能 / 代码地图 / 最近坑）· `docs/pitfalls.md`（坑全表 1~95）· `docs/RELAY-DEPLOY.md`（部署）· `docs/plugin-ui.md`（插件面板）· `LICENSE`（MIT 协议全文）· `HANDOFF.md`（本文） |
| 关联 ChatGPT 仓库 | `https://github.com/fuuulstack/chatgpt-mobile-sh`；Tauri／IPC、Android 0.2.63 与手机设置 ACK 的交接在该仓库 `HANDOFF.md` |
| 客户端类型 | 手机 `X-Client-Type: chatgpt` 或 `dsh`，缺省 `dsh`；主机注册 `clientType`，已有 ChatGPT 主机通过认证 `/control/identify` 补登记 |
| APK 渠道 | DSH 保持 `/api/dsh-mobile-sh-apk/downloads/` 与 `releases` 表；ChatGPT 使用 `/api/chatgpt-mobile-sh-apk/downloads/` 与 `chatgpt_releases` 表；文件、清单、计数和清理独立 |
| 发布约束 | `/control/release` 按已认证主机类型选渠道，不能用 query 覆盖；ChatGPT 清单额外带 `clientType`／`applicationId`；真实部署地址和凭据沿用私有运维配置，不写入本仓库 |

环境与命令全文见 `docs/RELAY-DEPLOY.md` 及 `AGENTS.md`「五、构建 / 测试 / 发布」「六、调试」。

## 三、任务

未完成（已完成里程碑不再逐条列，见 `git log` 与 `docs/PRD.md`）：

- [ ] **两段折叠行**：`Projector.applyStreaming` 的 overlay 在 `exact` / `fallback` / 合成轮三条分支里重复建面板；会话落盘零 REASONING 记录，属纯旁路。
- [ ] **真机长按删除**：确认用户侧 APK 版本与「长按后到底发生了什么」（菜单没出 / 删了又回来）；模拟器三条删除通路均正常。
- [ ] **子智能体胶囊真链路**：真派一个子代理，看第三颗胶囊亮起又自己灭掉（现在只在假宿主桩上验过）。
- [ ] **真机逐项验收**：附件拍照 / 文件、插件 UI 真机点选等剩余条目。
- [ ] **a2a-az 模型参数**（仓库外）：本机 `~/.dsh/profiles/desktop/cordis.patch.yml` 的 `a2a-az` 约 48 个模型补 `input` 与 `reasoningEfforts`，数据源 model.dev，其它参数不动。

里程碑（已完成）：三端 v2 重构上线（中继 `/v2/sync` + 插件 outbox / 控制流 + 手机 Room / `Projector`）；v1 面 410 下线；中继 Docker 化与内建 APK 托管；裸 IP 部署；0.2.60 三颗胶囊；发布前隐私审计；2026-11-12 修复「已中断」误报（抽屉同源 / 断流宽限 / 插件脉搏，坑 95）。

- [x] 设备列表、配对／解绑、会话／归档、HTTP／WS 控制均校验客户端类型，旧 DSH 客户端兼容。
- [x] 已有有效账号的旧部署启动不再要求重新提供管理员明文密码，新库仍保留初始化门禁。
- [x] ChatGPT 独立 APK 管理与分发，覆盖同版本隔离、Range、计数、清理、删除及跨渠道拒绝测试。
- [x] 已部署中继并发布关联 ChatGPT 0.2.63，公开清单、TLS 下载和包哈希验证通过。

## 四、细节在哪

| 主题 | 文件 | 章节 |
|---|---|---|
| 项目说明 / 用法 / 安装 / MIT | README.md | 全文 |
| MIT 协议全文 | LICENSE | 全文 |
| 产品需求 + 架构契约 + 验收标准 | docs/PRD.md | 全文 |
| 接手入口：功能 / 业务链路 / 代码地图 | AGENTS.md | 全文 |
| 踩过的坑全表（1~95，过时条目压成一行注记） | docs/pitfalls.md | 全文 |
| 中继部署（Docker / 裸 IP / APK 托管） | docs/RELAY-DEPLOY.md | 全文 |
| 插件 UI（需求边界 / 验收 / 取舍） | docs/plugin-ui.md | 全文 |
| 命名清单（唯一改动点） | packages/core/src/naming.ts | 全文 |
| 命名校验（check 内嵌） | scripts/check-naming.mjs | 全文 |
| v2 记录契约（`TimelineRecord` / `SYNC_V2` / 大小策略） | packages/core/src/records.ts | 全文 |
| v2 事件归类（未知事件一律 `META_PATCH` 透传） | packages/core/src/classify.ts | 全文 |
| v2 控制流协议（`CONTROL_PATH` / `RECORDS` / `SESSION_PULSE` / `CMD`） | packages/core/src/mux.ts | 全文 |
| 事件过滤（content 级转换；已无 `PANEL_EVENTS` 白名单） | packages/core/src/filter.ts | 全文 |
| v2 编号与分发（唯一采集点、outbox、聚焦广播） | packages/plugin/src/hub.ts | 全文 |
| v2 归档队列（三态 / 水位表 / blob 离载） | packages/plugin/src/outbox.ts | 全文 |
| v2 归档推送（内容派生 `batchId`） | packages/plugin/src/pusher.ts | 全文 |
| v2 控制流会话（尾部回放 / 无条件脉搏 / cmdId 幂等） | packages/plugin/src/control.ts | 全文 |
| v2 采集（follow + `$events`，loopback `agent:false`） | packages/plugin/src/collector.ts | 全文 |
| 隧道客户端（http-open / ws-open 分流、心跳） | packages/plugin/src/client.ts | 全文 |
| 宿主指标（事件循环滞后 / loopback 失败率） | packages/plugin/src/metrics.ts | 全文 |
| 热重载目录指纹（含 `ctimeMs`） | packages/plugin/src/hot-reload.ts | 全文 |
| 插件生命周期与凭据（按中继分键） | packages/plugin/src/lifecycle.ts | 全文 |
| v2 中继同步（`pageSession` / `pageRequest` / `SyncLoopGuard`） | packages/relay/src/sync-v2.ts | 全文 |
| 对外路由 / 410 v1 / 变更帧正文预算 / `/metrics` | packages/relay/src/server.ts | 全文 |
| 归档存储（v1/v2 两套并存、加解密在事务外） | packages/relay/src/store.ts | 全文 |
| SSE 写扩散与丢弃计数（`Change.records?` / `truncated?`） | packages/relay/src/changes.ts | 全文 |
| 中继自管 APK 分发（`/control/release` + 公开下载） | packages/relay/src/release.ts | 全文 |
| 容量参数（按 `RELAY_MEMORY_MB` 缩放字节预算） | packages/relay/src/limits.ts | 全文 |
| 自包含构建与守卫 | scripts/build.mjs | 全文 |
| 本地联调环境（真中继 + TLS 前置） | scripts/local-e2e.ts | 全文 |
| 假宿主（waterfall 语义 + 审批钩子 + 按会话分桶） | scripts/smoke-fake-host.ts | 全文 |
| 发版脚本（先包后清单，环境变量注入目标） | scripts/release-android.sh | 全文 |
| 部署 compose | deploy/compose.yaml | 全文 |
| v2 时间线存储（Room 实体 / 三水位 / 审批账本） | android/app/src/main/java/com/dsh/mobile/v2/data/TimelineDb.kt | 全文 |
| v2 投影器（`TimelineRecord[] → TurnUiModel[]`） | android/app/src/main/java/com/dsh/mobile/v2/data/Projector.kt | 全文 |
| 首行预览（本地派生） | android/app/src/main/java/com/dsh/mobile/v2/data/Preview.kt | 全文 |
| 数据模型 | android/app/src/main/java/com/dsh/mobile/v2/data/Model.kt | 全文 |
| 六跳打点（无 `T2_RELAY`） | android/app/src/main/java/com/dsh/mobile/v2/data/MessageTrace.kt | 全文 |
| v2 同步 actor（单飞 / 12 秒判半死 / 增量恢复） | android/app/src/main/java/com/dsh/mobile/v2/net/ChatSyncActor.kt | 全文 |
| 单飞与判死规则（纯函数，注入时钟） | android/app/src/main/java/com/dsh/mobile/v2/net/SyncCoordinator.kt | 全文 |
| v2 控制流客户端（`/control` WS / `/sync` / `/blobs`） | android/app/src/main/java/com/dsh/mobile/v2/net/RelayApiV2.kt | 全文 |
| 网络收发（WS / SSE / 下载 / `ChangeFeed`） | android/app/src/main/java/com/dsh/mobile/v2/net/RelayApi.kt | 全文 |
| 业务 API（v2 后已无 `SessionAccumulator`） | android/app/src/main/java/com/dsh/mobile/v2/net/RelayGateway.kt | 全文 |
| 应用状态机（登录 / 主机 / 列表 / 预览） | android/app/src/main/java/com/dsh/mobile/v2/ui/AppViewModel.kt | 全文 |
| 聊天流桥接（时间线 / 回声 / 折叠行 / 抽屉） | android/app/src/main/java/com/dsh/mobile/v2/ui/ChatViewModel.kt | 全文 |
| 聊天页（时间线 / 折叠行 / 两级抽屉） | android/app/src/main/java/com/dsh/mobile/v2/ui/ChatScreen.kt | 全文 |
| Composer（权限 / 模型 / 发送三态 / 长按 steer） | android/app/src/main/java/com/dsh/mobile/v2/ui/Composer.kt | 全文 |
| 设计令牌 | android/app/src/main/java/com/dsh/mobile/v2/ui/Theme.kt | 全文 |
| 待答卡片账本（去重 / 回放 / 可重来） | android/app/src/main/java/com/dsh/mobile/v2/ui/InteractionLedger.kt | 全文 |
| App 内更新安装（PackageInstaller 会话） | android/app/src/main/java/com/dsh/mobile/v2/ApkInstaller.kt | 全文 |
| Android JVM 单测（543 项） | android/app/src/test/java/com/dsh/mobile/v2/ | 全文 |
| v2 测试基线（插件） | tests/pipeline-v2.test.ts | 全文 |
| v2 测试基线（控制流 + 端到端） | tests/control-v2.test.ts | 全文 |
| 事件过滤回归（未来事件原样透传） | tests/filter.test.ts | 全文 |
| 续传协议与游标 | tests/sync-cursor.test.ts | 全文 |
| 插件 UI 测试基线 | tests/plugin-ui.test.ts | 全文 |
| 中继运维测试基线 | tests/relay-ops.test.ts | 全文 |
| 客户端类型与旧部署升级说明 | README.md | 启动中继 |
| 两类 APK 渠道隔离回归 | tests/relay-release.test.ts | 全文 |
| 已有账号升级启动回归 | tests/relay-bootstrap.test.ts | 全文 |
