# AGENTS.md

给「接手这个仓库的 AI / 人」看的入口说明。项目说明、用法与安装先看 `README.md`，MIT 协议见 `LICENSE`；深度细节在 `docs/PRD.md`（产品需求 / 架构契约 / 验收标准）、`HANDOFF.md`（交接）、`docs/RELAY-DEPLOY.md`（部署）、`docs/pitfalls.md`（踩过的坑全表）与 `docs/plugin-ui.md`（插件面板专题）。

## 一、这是什么

DSH（DeepSeek Harness）桌面端的手机遥控器。电脑上跑 DSH，装一个中继插件；手机 App 连中继，就能看/发/管所有会话。

```
手机 App (Kotlin/Compose)  ──TLS──►  中继 relay (Node)  ◄──WS 长隧道──  插件 plugin (跑在 DSH 桌面进程里)  ──HTTP──►  DSH 宿主 (127.0.0.1)
```

中继不存业务状态，只做**鉴权 + 转发 + 14 天会话归档**。宿主的接口只在 loopback 上，手机永远直连不到。

本项目是 DSH 桌面端的 Kotlin/Compose 手机遥控器，对外正式名 `dsh-mobile-sh`；历史 Java 版本仅作参考。

## 二、功能

- **登录**：8 位设备配对码（无需邮箱/密码/TOTP）；也支持 `dsh://pair?relay=…&login=…&code=…` 深链/扫码**一码配齐**（登录中继 + 配对主机一步完成）
- **会话**：列表（标题 + 首行预览 + 运行中转圈 + 平台图标）、搜索、三种排序、下拉刷新、长按删除（仅本机）、6 项「更多」菜单（三档排序 / 输入配对码 / 刷新会话 / 设置）
- **聊天**：流式回复、思考内容、工具步骤（整轮折成一行摘要，点开底部抽屉看全链路）、审批卡片、提问卡片、权限三档（只读/工作区/完全）、模型与思考等级、附件三入口（相册/拍照/文件）、Markdown、回到最新
- **折叠行两行 + 操作小标题**（对齐桌面端）：折起来后仍看得出此刻在干什么——**第一行**是 `已处理 N 个操作 · <操作名称> · <小标题>`（小标题＝模型写在工具参数 `description` 里那句话，桌面端 `工具调用 · ops_bash · 改成十秒桶` 的最后一段；没有描述就只显示操作名，不拿命令冒充），前导图标按类型换（思考 `sparkles` / 命令 `terminal` / 技能 `package`）、**整轮没跑完时一直在转**；**第二行**是预览内容（跑命令＝命令本身，思考＝按实际宽度取尾部的流式预览）。展开后思考详情跟着流式长，抽屉里正在跑的那一步也转菊花
- **附件回显**：历史消息里用户自己发的图**真的画出来**（96dp 圆角缩略图），不再是「一行文件名」。durable 日志只留 `attachmentId`，字节走 `session/attachment` 现取，解码在共享单线程 + `LruCache`（48 张，键＝`会话id/附件id`）；取不到时那一格写明原因——「附件传输已关闭」/「图片加载失败」，两者不能混
- **逐字吐字 + 贴底跟随**：流式正文按**打字机**一个个放出来（`ui/Typewriter.kt`），速度随**积压量**变——积压少一 tick 一个字，积压多按 12 个 tick（≈200ms）追平的比例加速，落定立刻整段给；聊天页尾行、落定正文（`message.streaming`）、一级抽屉旁白、二级抽屉思考正文四处共用。同时**贴底才自动滚**：聊天页 `LazyColumn` 与两级抽屉（`verticalScroll`）都接 `ui/AutoScroll.kt`，判据是「上次贴底 + 现在能往下滚了」，用户往上翻历史时一个动作都不做
- **发送钮三态**：宿主在跑且输入区空 → 「停止」；输入区有字（或待发附件）→ 一律「发送」，此时单击是**排队**（`mode:"queue"`，等当前这轮跑完）、**长按**是插话（`mode:"steer"`，插进正在跑的这轮）
- **排队消息卡片**（对齐桌面端）：输入框上方逐条列出宿主 inbox 里还没被领走的消息（左侧 ↩ 图标 + 文本），右侧编辑/插话/删除三个动作；数据来自 `session/projections` 的 `values.inbox`（`next-turn`/`next-step` 两桶）+ 实时 `agent/inbox/spliced`，动作走 `session/updateQueue`
- **胶囊行**：**独占一行**（消息区与输入框之间的布局流里，居中并排，不悬浮）的圆形图标胶囊，点开各自的底部清单弹层；**只画有数据的**，最多三颗、顺序固定；一颗都没有时整行不发射、不占位；**子智能体那颗只数「明确在跑」的**（`running != false`，老插件不发该字段时按「不知道」照常显示），见坑 93/94
  - **任务清单**（`list_checks`）：数据来自 `session/projections` 的 `values.todos` + 实时 `todo/write` 事件；弹层顶部写「已完成 / 总数」，每行标 DONE/DOING/TODO（`in_progress` 单标，与计数只认 completed 的判据故意不同）；全部完成时胶囊中性退背景
  - **后台任务**（`clock`）：数据来自归档事件 `job/registered` / `job/settled`（宿主 projections 里**没有** jobs，`job/list` 又是无自然结束的常驻流，不值得为胶囊开一条）；弹层每行「标签 + 已运行 N 秒/分秒」。为此在落盘过滤里给这两个事件开了口子，并把它们归类成独立的 `RecordKind.JOB_EVENT`（见坑 91）
  - **子智能体**（`bot`）：数据来自 `session/projections` 的 `values.subagentCatalog`（`slimSession` 也保留一份，免得依赖生产日志里常 ECONNRESET 的 `/rpc/projections`）；弹层每行「标签 + 模式」（`one-shot`/`external`/`continuable`/`unknown`，认不出的原样显示）
- **新建会话**：点「新建」只开**本地草稿**，发出第一条消息才真正 `create`（避免电脑端凭空多一条空会话、并被广播到别的设备）
- **后台任务页**：三点菜单里的「后台任务」入口，静止占位屏（数据源已改走胶囊行）
- **设置**：连接 hero（平台头像 + 延迟/上行/下行实时速率）、断线自动重连、显示思考、编辑内容、版本管理与 App 内更新、设备管理
- **更新**：检查 `version.json` → 下载（Range 续传 + sha256 校验）→ `PackageInstaller` 会话安装
- **插件 UI**：DSH 侧栏「手机访问」面板、设置页（填中继地址/密码、生成配对二维码）

## 三、业务链路

**① 登录 + 配对**
`POST /native/v2/device-code`（或 `/login` 邮箱+TOTP）换 bearer → 存 `TokenVault`（AndroidKeyStore AES-GCM）→ `/native/v2/hosts` 拿主机列表（只挑 `authorized:true`）→ `POST /native/v2/pair` 用配对码换 host 授权。

**② 看会话**
`GET /native/v2/hosts/:id/sessions`（会话清单，含 `blank`/`running`/标题投影）→ 立即渲染缓存；首行预览**由手机从本地 Room 时间线现算**（`AppViewModel.previewOf` 走 `Preview.kt` 口径，缓存缺失时异步预热，绝不在列表里同步查库）。实时靠 SSE `/native/v2/hosts/:id/changes`（变更帧只报信号、不带正文）驱动 `/sync` 补差。

**③ 发消息**
**v2 通路（现行）**：写操作走控制流的 `CMD` 帧（`prompt`/`interaction`/`queue`/`cancel`）→ 插件 `ControlSession` 打宿主 → 回 `CMD_ACK`；同 `cmdId` 重发只打宿主一次（3 秒 ACK 竞速的幂等底座）。读侧订阅同一条控制流：进会话先渲染本地 Room → `FOCUS_SESSION(sinceSeq=local_acked)` 让插件**立刻**回放它内存里的尾部（不等宿主）→ 并行 `POST /native/v2/hosts/:id/sync` 补差 → 落盘后推水位。
**回落通路（只在控制流不在位时用）**：`POST /native/v2/hosts/:id/rpc/prompt` 经中继直打宿主，**插件不参与**，所以「只执行一次」没有保证——只在 `actor == null`（还没进过会话）或 ack 回 `not-connected`（宿主必然一个字节都没收到）时走，且 `requestId` 原样带过去让宿主按幂等键认出是同一条。`rpc/create`、`rpc/rename`、`rpc/cancel`、`rpc/updateQueue`、`rpc/attachment`、`rpc/search` 也走这条（都是**读或不改变时间线语义**的直调）。**正文与历史只剩 v2 一条路**：`SessionAccumulator` 已删（历史重构中移除，约 2184 行），审批/提问走控制流 `INTERACTION` 帧、排队与任务走归档的 `INBOX_SPLICE`/`TASK_LIST` 记录，都不再经 `$events`。

**④ 归档回填（离线也能看历史）**
插件 `Collector` 定时/事件触发（500ms 防抖）拉 `/api/session/list` → follow 会话与 `$events` → 每条宿主事件交给 `SessionHub`（**唯一编号点**：分配 `prevSeq`、执行大小策略、写 SQLite outbox、广播给聚焦手机）→ `ArchivePusher` 每 250ms 把 outbox 排空到 `POST /control/timeline`（内容派生 `batchId`，重投成 no-op）→ 中继加密落库（14 天 TTL、每会话 2000 条）→ 手机 `POST /native/v2/hosts/:id/sync` 增量取回写 Room。**实时与归档是同一批字节**，手机只有一个投影器。

**⑤ 更新**
`GET version.json` → 下载到外部缓存 `updates/` → `ApkInstaller` 走 `PackageInstaller` 会话（`ApkProvider` 只放行该目录）→ 系统安装器。

## 四、代码地图

### TS 三包（`packages/`）

| 目录/文件 | 职责 |
|---|---|
| `core/src/naming.ts` | **命名单一事实源**（正式名、env 前缀、credential 记录名）。改名只改这里 + `android/gradle.properties` |
| `core/src/{transport,bridge,protocol}.ts` | WS 隧道分帧/多路复用/协议帧 |
| `core/src/{filter,budget,security}.ts` | 上下文过滤、配额与限流、路径与请求头清洗 |
| `plugin/src/client.ts` | 隧道客户端（握手 auth→hello→ready、心跳、http-open/ws-open 分流） |
| `core/src/records.ts` | **v2 记录契约**：`TimelineRecord`、`RecordKind`、`SYNC_V2` 全部阈值、`applySizePolicy`（>256KiB 存根化 / >10MiB 截断） |
| `core/src/classify.ts` | **v2 事件归类**：宿主事件 → `RecordKind`；**未知事件一律 `META_PATCH` 原样透传**，这是「新事件不用登记」的制度保证 |
| `core/src/mux.ts` | **v2 控制流帧**：`CONTROL_PATH`、`RECORDS`、`SESSION_PULSE`、`CMD`/`CMD_ACK`、`HELLO`/`HELLO_ACK` |
| `plugin/src/collector.ts` | **v2 采集**：follow 会话 + `$events`，一律交给 `Hub.ingest`；不含队列与水位 |
| `plugin/src/hub.ts` | **v2 唯一编号点**：分配 `prevSeq`、执行大小策略、写 outbox、广播给聚焦的手机；outbox 满时进内存 backlog 等 `retryPending()` |
| `plugin/src/outbox.ts` | **v2 归档队列**：`node:sqlite` 三态 PENDING/IN_FLIGHT/ACKED + 水位表 + blob 离载表 |
| `plugin/src/pusher.ts` | **v2 归档推送**：250ms 轮询 outbox → `/control/timeline`；`batchId` 由内容派生，超时重投成 no-op |
| `plugin/src/control.ts` | **v2 控制流会话**：在插件里终结手机的 WS（立刻回放尾部、每 5 秒无条件脉搏、`cmdId` 幂等） |
| `relay/src/sync-v2.ts` | **v2 同步**：`pageSession` 探针判 `hasMore`、`pageRequest` 按 priority 分预算、`SyncLoopGuard` 死循环检测 |
| `plugin/src/lifecycle.ts` | 注册/续期/吊销（enroll 时上报 `platform: process.platform`） |
| `plugin/src/{ui-page,ui-routes,browser,settings,pairing}.ts` | 插件 UI：服务端渲染页 + iframe 内嵌 + 设置模型 + 配对码 |
| `plugin/src/hot-reload.ts` | 热重载（`ctx.effect` 必须返回停止函数） |
| `plugin/src/power.ts` | 防休眠断言：先试 Electron `powerSaveBlocker`，取不到退系统命令（`caffeinate -i -w <pid>` / `systemd-inhibit`）；只在隧道 ready 时持有，掉线宽限 3 分钟自动放掉，`exit` 钩子兜底；`supported=false` 时页面画成不可用 |
| `relay/src/server.ts` | 全部对外路由：账户网页面 / `/native/v2/*`（手机）/ `/control/*`（主机）/ `/metrics`。**v1 前缀与 `/control/archive` 一律 410 `v1-gone`**，见坑 63 |
| `relay/src/store.ts` | SQLite：账号、主机、配对码、设备码、归档（v1 `records`/`session_meta` + v2 `timeline_records`/`timeline_blobs`/`archive_batches`，两套并存） |
| `relay/src/proxy.ts` | 隧道路由、字节流代理、`notePlatform` 兜底 |
| `relay/src/changes.ts` | SSE 写扩散（推完即弃，不重放） |
| `relay/src/{crypto,cli}.ts` | 字段加密 / `init-key`、`create-account`、`serve` |

对外契约速查：
- 手机侧（**只有 v2**，v1 前缀一律 410）：`/native/v2/{login,device-code,rotate,hosts,pair,unpair,logout}`、`…/hosts/:id/{changes,sessions,upload}`、`…/hosts/:id/rpc/{list,create,prompt,cancel,page,projections,modelCatalog,selectModel,rename,attachment,search,command,updateQueue}`
- 手机侧 v2 专属：`POST /native/v2/hosts/:id/sync`（`{mode,pageByteBudget,sessions:[{sessionId,afterSeq,priority}]}`）、`GET /native/v2/hosts/:id/sessions`、`GET /native/v2/hosts/:id/blobs/:blobId`、WS `/native/v2/hosts/:id/control`（插件终结；HELLO/records/pulse/ack + 审批提问走 `INTERACTION` 帧，见 `core/src/mux.ts`）
- 主机侧：`/control/{enroll,pair,timeline,metrics}`；WS `/api/remote.mux`（插件与本机宿主之间）+ `/__dsh/v2/control`（v2，插件终结）
- 插件网页面：`/`、`/pair`、`/device-codes`、`/open`、`/pair/create`、`/device-code/create`；插件内嵌设置页 `${UI_PREFIX}/api/{status,settings,pair,power}`（`/api/power` 是 POST `{enabled:boolean}`，纯本机开关，不触发重连）

### Android（`android/app/src/main/java/com/dsh/mobile/v2/`）

| 分包 | 文件（按重要性） | 职责 |
|---|---|---|
| 根 | `MainActivity.kt` | 单 Activity，`singleTask`；深链入口；安装权限回前台刷新（**截图限制已取消**，不再置 `FLAG_SECURE`） |
| | `ApkInstaller.kt` / `ApkProvider.kt` | PackageInstaller 会话安装 / 受限 ContentProvider |
| `data/` | `Timeline.kt` | **v2 记录模型**：`TimelineRecord`、`RecordKind`、三水位 value class（`LocalAckedSeq`/`ServerMetaSeq`/`ArchiveCoveredSeq` 编译期互不通用） |
| | `TimelineDb.kt` | **v2 Room 库**：`timeline_records`（PK `session_id,seq`）、`session_metadata`（三水位分列）、`interaction_ledger`；`commit()` 是 `@Transaction`，**先 insertAll 再 saveMeta** |
| | `TimelineStore.kt` | Room 实现的读写门面 + 进程级单例；水位 `max(旧,新)` 只进不退；`fillStub`/`pendingStubs` 供存根补齐 |
| | `Projector.kt` | **v2 投影器（纯函数）**：`TimelineRecord[] + Overlay → TurnUiModel[]`；槽位硬编码顺序、同 seq 以 SQLite 为准、存根给可见占位 |
| | `TurnUiModel.kt` | **v2 渲染模型**：`TurnUiModel`（LazyColumn key `turn_$turnId`）、`ProcessPanelNode`、`InteractionCard` |
| | `Preview.kt` | 首行预览口径（取最后一条助手正文的首行非空文本） |
| | `Model.kt` | 全部数据类（Session/Message/Step/Attachment/Approval/Question/Catalog…） |
| | `SessionCache.kt` | prefs 缓存（`dsh-v2-cache`）：会话列表、消息、草稿、游标；含 `trimForDisk` |
| | `TokenVault.kt` | prefs `dsh-v2-session` + AndroidKeyStore AES-GCM 存令牌 |
| | `Diag.kt` / `CrashLog.kt` | `files/diag.log`（256KB 轮转）/ 崩溃日志 |
| | `AttachmentLoader.kt` | 附件解码、压缩到限额 |
| `net/` | `RelayApiV2.kt` | **v2 网络**：`/native/v2/hosts/:id/sync`、`/blobs/:id`、WS `/control`（`decodeMuxFrame` 不认识就丢帧，不拆 socket） |
| | `ChatSyncActor.kt` | **v2 同步状态机**：本地先出 → 开控制流 → 补差；不碰 UI 线程、不持有 `Model.Message`；存根补齐限速 3 秒 |
| | `SyncCoordinator.kt` | **v2 单飞与判死纯函数**：`decideRun`/`halfDead`/`advance`/`backoffMs`，注入时钟便于假时钟单测 |
| | `RelayChatTransport.kt` | `ChatSyncActor` 的真实实现；**传输层不重试**（重试只归 actor） |
| | `ToolParsing.kt` | 工具调用/结果的字段解析（callId/命令/skill 标记/label） |
| | `RelayApi.kt` | HTTP/WS/SSE 底座：主客户端、`downloadClient`（HTTP/1.1 + 续传）、`fetch`、上传、`rawCall`（裸 GET，供 blob） |
| | `RelayGateway.kt` | **账号/主机/会话的 JSON 翻译 + 回落直调**（`login`/`listHosts`/`pair`/`listSessions`/`create`/`rename`/`sendPrompt`/`updateQueue`/`cancel`/`attachment`/`search`/`watchChanges`）。**没有时间线折叠**：正文与历史由 `ChatSyncActor` + `Projector` 承担 |
| | `Json.kt` / `HostErrors.kt` | JSON 小工具 / 宿主错误文案映射 |
| `ui/` | `AppRoot.kt` | 路由与五屏装配（LOGIN/HOME/CHAT/SETTINGS/SCAN） |
| | `AppViewModel.kt` | 全局状态机：登录、主机、会话列表、更新、搜索、菜单 |
| | `HomeScreen.kt` | 会话列表、菜单、空态 |
| | `ChatScreen.kt` | 时间线、气泡、过程折叠行、底部抽屉（`ProcessTimelineSheet`/`StepDetailSheet`）、顶栏 |
| | `ChatViewModel.kt` | 单会话状态：发送/重试/草稿/审批提问；纯函数 `dropSupersededSameSeq`/`groupSteps`/`foldSummary`/`mergeTurnPanels`（一轮的多块面板并成一行摘要） |
| | `Composer.kt` | 输入区：权限胶囊、模型胶囊、附件、发送/停止 |
| | `SettingsScreen.kt` / `TasksScreen.kt` / `ScanScreen.kt` / `LoginScreen.kt` | 各屏 |
| | `Markdown.kt` / `MarkdownText.kt` | Markdown 解析（Kotlin 移植）/ Compose 渲染 |
| | `Theme.kt` / `Widgets.kt` / `Interactions.kt` / `LocalSearch.kt` | 设计 token、通用组件、审批提问卡片、本地搜索 |

**`Projector` 是这块最绕的地方**（`data/Projector.kt`）：纯函数 `TimelineRecord[] + Overlay → TurnUiModel[]`，一轮之内槽位顺序硬编码（userMessage → processPanel → assistantBlocks → interactions），**不再有 `seq - 0.25` 那种浮点偏移**；同 seq 以 SQLite 为准、存根给可见占位、`applyStreaming` 只在 `lastBlock` 上且要求 `startsWith`。历史那套按 `(turn, step)` 现场折叠的 `SessionAccumulator` 已删。坑 12/27 讲的是老实现，读的时候记得它已经不在了。

### 其它目录

`tests/` 262 项 TS 测试 · `android/app/src/test/` 539 项 JVM 单测 · `scripts/`（`local-e2e.ts` 本地中继、`smoke-fake-host.ts` 假宿主、`release-android.sh` 发版、`docker-test.ts` 容器端到端、`build.mjs` 打包、`check-naming.mjs` 命门禁、`review/` mobilewright 驱动）· `deploy/`（`Dockerfile` + `compose.yaml` + `nginx.conf.example` + 部署说明 `README.md`）· `artifacts/`（本地联调夹具，gitignore）

## 五、构建 / 测试 / 发布

```bash
# TS：类型检查 + 命名门禁 + 262 项测试 + esbuild 打包（包产物到 packages/*/dist/）
npm run check

# Android（本机 JDK 17；系统 JDK 26 会让 Gradle 8.13 报 major version 70）
cd android && JAVA_HOME=$HOME/Library/Java/JavaVirtualMachines/jbr-17.0.14/Contents/Home \
  ./gradlew :app:testDebugUnitTest :app:assembleDebug

# 发版（抬 android/app/build.gradle 的 versionCode/versionName，再：）
cd android && ./gradlew :app:assembleRelease && cd ..
scripts/release-android.sh 0.2.10 "本次说明"
```

发版细节：脚本把 APK 传到 `static-host:/var/www/dsh-mobile/` 并写 `version.json`（先传包、后写清单）。App 轮询 `https://downloads.example.test/api/ybs/downloads/version.json`。

**签名（必须看）**：线上历史包一律由服务器那把 `dsh-release.keystore` 签（`static-host:/path/to/release-keys/`，root 600；口令 `YOUR_PASSWORD`、别名 `YOUR_KEY_ALIAS`、SHA-256 `<线上签名证书指纹>`）。**未设 `ANDROID_KEYSTORE` 时 release 回落本机 `~/.android/debug.keystore`，那是另一把（`<本机调试证书指纹>`），签出来装不上、报签名冲突**——2026-10-28 发 0.2.15 就这么踩过一次。发版前：

```bash
# 1. 取服务器 keystore（放到已 gitignore 的 artifacts/ 下）
ssh static-host "sudo -n cat /path/to/release-keys/dsh-release.keystore" > artifacts/tmp/keys/dsh-release.keystore
# 2. 注入四件套再构建
export ANDROID_KEYSTORE=$PWD/artifacts/tmp/keys/dsh-release.keystore \
       ANDROID_KEYSTORE_PASSWORD=YOUR_STORE_PASSWORD \
       ANDROID_KEY_ALIAS=YOUR_KEY_ALIAS \
       ANDROID_KEY_PASSWORD=YOUR_KEY_PASSWORD
cd android && ./gradlew :app:assembleRelease && cd ..
# 3. 签完对指纹，必须与线上签名证书指纹一致
#    Windows 的 build-tools 里只有 apksigner.bat（没有无扩展名的 shell 脚本），
#    而且它要能起 java，所以先 export JAVA_HOME（本机 /e/SDK/jbr17）
"$ANDROID_SDK/build-tools/35.0.0/apksigner.bat" verify --print-certs \
  android/app/build/outputs/apk/release/app-release.apk | grep 'SHA-256 digest'
```

生产中继：`ssh relay-host`（云主机），systemd `dsh-mobile-sh.service` → `/opt/dsh-mobile-sh/packages/relay/dist/cli.js serve`，env 在 `/etc/dsh-mobile-sh.env`，TLS 由 nginx 前置到 `https://relay.example.test`。部署 = scp dist + `systemctl restart`。

## 六、调试：mobilewright 优先

**移动端调 UI 一律先用 mobilewright，别手写 `adb shell input` + `uiautomator dump`。**

```bash
M=~/.agents/mcp/mobilewright/mobilewright-cli   # 不在 PATH
$M devices                                     # 真机 YOUR_DEVICE_SERIAL（Android 真机）/ 模拟器 test_emulator
$M -d YOUR_DEVICE_SERIAL launch com.dsh.mobile.v2
$M -d <id> snapshot                            # 屏幕树 + ref
$M -d <id> find --text "检查更新"               # 也可 --role/--name/--test-id/--label，--first 取首个
$M -d <id> tap e65                             # 或 tap 540,1200；也有 longpress/doubletap
$M -d <id> fill e7 "文本"                       # 点击+清空+输入；已聚焦用 type
$M -d <id> swipe up / button BACK              # HOME/BACK/POWER/ENTER/APP_SWITCH…
$M -d <id> expect visible --text "安装完成" --timeout 20000   # 自动等，断言失败非零退出
$M -d <id> screenshot -o out.png               # 必须带 -o，默认写 screenshot.png
$M -d <id> logs --filter tag=DSH --limit 200
```

要点：①**ref 每次动作后失效**，先 `snapshot`/`find` 再 `tap`；②`-s <name>` 开多个会话，可同时驱动两台设备；③`--json` 出结构化输出；④`$M -d <id> --help` 看子命令选项。注意 mobilewright 的设备 id 是 adb 序列号或模拟器名（`test_emulator`），跟 `adb devices` 里的 `emulator-5554` 不是同一个串。

补充手段（mobilewright 覆盖不到时）：
- 诊断日志：`adb shell run-as com.dsh.mobile.v2 cat files/diag.log`（tag：`chat`/`stream`/`events`/`sync`/`te`/`probe`/`update`）
- 缓存实况：`run-as … cat shared_prefs/dsh-v2-cache.xml`
- 真机开 `FLAG_SECURE` → `screencap` 全黑；要截图/量像素**用模拟器**（`emulator-5554`）
- 快速回登录态：`adb shell "am start -a android.intent.action.VIEW -d 'dsh://pair?relay=https://localhost:8443&login=<8位>&code=<8位>'"`（**必须整条包在 `adb shell "…"` 里**，否则 `&` 被本地 shell 吃掉）
- 本地联调：中继 `node node_modules/tsx/dist/cli.mjs scripts/local-e2e.ts`（TLS 8443 / relay 18080）+ 假宿主 `scripts/smoke-fake-host.ts`；模拟器重启后必须重跑 `adb reverse tcp:8443 tcp:8443`
- **造审批/提问卡**：假宿主开了测试钩子 `curl -s "http://127.0.0.1:<假上游端口>/__smoke/approval?sessionId=smoke-session-1"`（端口看 `/tmp/smoke.log` 的 `fake upstream on 127.0.0.1:PORT`）。它照真宿主语义实现：开流先发 `ready{clientId}`、新连接建立时把所有未结算 waterfall 重投一遍、`$events/result` 只认投递过的那个 clientId。所以「点完审批卡又冒出来」这类 bug 现在能在本地跑出来

## 七、踩过的坑

**全表在 `docs/pitfalls.md`**（1~94 条，按主题分了「构建与环境 / 网络与更新 / 数据与渲染 / 测试工具 / UI 细节 / 网络与隧道 / 配对与设备身份 / 插件运行环境 / 限流 / 排队消息 / v2 重构」等小节）。
分家的原因：这份清单本身就 60 多 KB，而 `AGENTS.md` 的指令预算是 65536 字节——2026-11-02 加第 57 条时把文件顶爆，DSH 直接把尾部截掉，等于新坑白写。**新坑一律先写进 `docs/pitfalls.md`**，`AGENTS.md` 这里只留最近几条。

下面是最近几条（最容易被正在改的东西撞上）：

54. **「一条脉搏都还没到」这一支看门狗没有一次性守卫，会每 12 秒自我重连**。2026-10-30 实测（模拟器 + 生产中继）：`control ws open` → 12 秒后 `control recover #N` 配 `SocketException: Socket closed` → 再 `ws open` → 再 recover，17:54:06 一路循环到 17:55:24（`#1`～`#6`），直到 17:55:31 第一条脉搏才停。**症状不是「连不上」，而是这 78 秒里发消息会落回 v1**（diag 留着 `control command prompt refused: socket=present open=false`）。根因是 `tick()` 两个判死分支**不对称**：`halfDead()` 那支有 `halfDeadDeclared` 守卫，而 `lastPulseAt <= 0` 那支只判 `now - armedAt >= PULSE_DEAD_MS`，偏偏 `recover()` 把 `armedAt` 重置成 `now`——于是每 12 秒必然再命中一次。而插件的第一条脉搏要等它自己的控制会话建好（实测 ~70 秒），phone 每 12 秒踢一次又把这个过程往回推，是**「恢复动作本身阻止了它正在等的那个东西」**。
   **修法**（已落地，2026-11-01）：①这一支改成按**连续自救次数**做渐进宽限（`SyncCoordinator.noPulseGraceMs(recoveries)`：第一次 12 秒，之后每救一次翻倍，封顶 120 秒，`recoveries` 只在 `pulse()` 里归零）；②`recover()` 不再无条件 `closeControl()`——先 `controlAlive()` + `focus()` 试一次，成功就只记「连接仍在，聚焦已重挂」，只有聚焦失败或第二次连续自救才真正重开 socket（`ChatSyncActor.kt:422-487`）；③判据：`grep 'control recover' diag.log` 不该出现**连续等间隔**（≈`PULSE_DEAD_MS`）的多条。**2026-11-01 复测：这条循环在当前链路上复现不出来**——模拟器 + 本地中继静置 6 分 51 秒，`control pulse` 75 帧（间隔 4.989~5.025s 无断档）、`recover` 0 行、`ws open` 0 次。当初那轮的现场是假宿主反复重启造成 39 秒 `503` 空窗，属**真断流下的正确自救**。所以「24 秒」这个数字本身是设计值（两个 `PULSE_DEAD_MS` 窗口），不是实测周期，别拿它当 bug 判据。

55. **打字机与「贴底跟随」是两件独立的事，判据不能混用**。`ui/Typewriter.kt` 只在**渲染层**放慢已收到的文本（网络层不动），速度跟**积压量**走：`typewriterChunk(pending)` 按 `TYPEWRITER_CATCHUP_TICKS`(12，≈200ms) 向上取整，积压少一 tick 一个字、积压多一 tick 吐一大把。三条容易写错：①推进下标必须用 `codePointCount`/`offsetByCodePoints`，按 UTF-16 单元切会切出孤立代理对，emoji 变方块；②进度存 `rememberSaveable` 而不是 `remember`——调用点在 `LazyColumn` 的 item 内，滚出屏幕 composition 就销毁，`remember` 会丢进度、滚回来从头再打；③「还是不是同一段文本」只能靠 `target.startsWith(previous)` 判，比长度会漏（换段落、上游重发都常见），不归零就会从中途开始吐。
    `ui/AutoScroll.kt` 的判据是「**上次贴着底** 且 **现在能往下滚了**」（`canScrollForward` false→true），这就是「只有贴底才跟随」，不需要额外的意图位。四个规矩：①**首次量测不算数**——抽屉刚打开、列表刚首帧时 `canScrollForward` 也是 false→true，那是「刚量出来」不是「有新内容」；②`!isScrollInProgress` 守卫不能省：打字机每 16ms 动一次，而 `followTail`/`userDragging` 要经一次重组才进闭包（`rememberUpdatedState`），`canScrollForward` 的翻转却是立刻可见的——少这道守卫，用户刚往上拖的那一帧就被拽回底（v1 老 bug）；③`LaunchedEffect` 只按 `listState`/`scrollState` 重启，会变的入参都要 `rememberUpdatedState` 转手，否则永远读第一帧旧值；④`LazyColumn` 版滚 `scrollToItem(tail, tailScrollOffset(height))`（量不到高度时给 1_000_000，越界即夹到底），`ScrollState` 版直接 `scrollTo(maxValue)`。回归在 `TypewriterTest` 的 `贴底且内容变长时应该滚` / `用户翻上去了就一个动作都不做`。
    **`ScrollState` 版（两级抽屉）多一条坑：内容总高只能用 `maxValue + viewportSize`（`contentHeightOf`），不能只看 `maxValue`**。内容不满一屏时 `maxValue` **恒为 0**，撑满才 0→800：拿它比较，「长高了没有」判成 `0 > 0` 假，「是不是首帧」又被 `maxValue <= 0` 吃掉，于是**第一次长过一屏永远不滚**（2026-11-01 用户实测）。首帧判据换成 `viewportSize <= 0`——视口量出来就不会回 0。现场证据靠运行时打点：`StickToBottomOnGrowth(scrollState)` 的 `crossing` 分支**只记「不满一屏 → 第一次撑满」那一次**（思考时内容每 58px 长一次，逐次记会冲掉 256KB 的 diag.log）。实测一行：`scroll sheet scroll h=1749 last=1743 vp=1743 fwd=true atBottom=true dragging=false`——`last == vp` 就是「恰好等于视口」那刻。验这条要让**思考在抽屉打开之后继续长**：假宿主原来 7 句循环每 250ms 一片、两秒就停，为此加了 `SMOKE_REASONING_MS` / `SMOKE_REASONING_TOTAL`（默认 -1 一直推）。
    另一条排查口诀：**抽屉里点的是哪一轮**。`uiautomator dump` 里同一屏有多条「已处理 N 个操作」，`head -1` 拿到的是**最上面那条旧轮**（`step.text` 是落定值、`running` 已假），点进去当然不长——要按 `bounds` 的 y **取最大**。验增量还必须让 `prompt` 走 v2 控制流：`STREAM_DELTA` 只在控制流上广播、**从不归档也不编号**，`prompt refused: socket=present open=false` 一出现就说明这轮回退到了 v1。

56. **判死判据必须是「有多新鲜」，不能是「有没有过」**。`tick()` 与 `halfDead()` 原来都拿「这两个时钟还是不是 0」当闸门（`lastSeen = maxOf(lastPulseAt, lastDataAt)` / `if (lastPulseAt <= 0) return false`）。这是**只会错一次、错了就永久**的写法：收到过一条 records，`lastDataAt` 就再不是 0；自救过一次，`recover()` 就把 `lastPulseAt` 归零。两条一起失效之后，「records 一直在到、脉搏一条不来」（坑 54 的另一半）**再没有任何东西会判死**：`hostState` / `running` 冻在最后一帧旧脉搏上，长推理进度行不再走——「看着在跑、其实在冻着」。
    现在的口径是 `SyncCoordinator.pulseEvidenceLost(now, armedAt, lastPulseAt, lastDataAt)`：真脉搏 12 秒内算新鲜；records 可以给脉搏**当替身**，但替身有期限 `PULSE_SUBSTITUTE_MAX_MS`（120 秒，重放窗口 10~15 秒的八倍余量）（重放时脉搏压在队尾十几秒正常，判死会重连→重放→再堵）。`halfDead()` 的替身分支同样加了期限（不复用 `pulseEvidenceLost`：那支的 `armedAt` 属于「可反复自救」，`halfDead` 只判一次）。
    三条规矩：①`recover()` 归零之后靠 `armedAt` 兜空档；②`lastDataAt` 在 `recover()` 里**不要跟着清零**，清了会把最健康的连接在 12 秒后误判；③回归必须双向钉——「records 一直在到、脉搏两分钟没来 → 必须判死」和「脉搏 98 秒没来、records 一秒前刚到 → 不许判死」。用例：`SyncCoordinatorTest` 的 `records stop substituting for the pulse after two minutes`、`ChatSyncActorTest` 的 `records alone do not keep a pulseless control stream alive forever`。

57. **`/control/timeline` 不发变更帧 ⇒ 会话列表页预览永远不刷新**：`watchChanges` 是列表页唯一的「有变化」信号，现已在该路由归档后补 `changes.publish`。细节见 `docs/pitfalls.md` 第 57 条。

58. **热重载的目录指纹只拼 `mtime + size`，看不见「整包替换」**。`npm pack` 把 tar 内所有文件 mtime 归一化成常量 `499162500`（1985-10-26），而「只换一行等长 build hash」的重建连 size 都不变 → 新旧目录指纹逐字节相同，`check()` 早退，`fs.watch` 与轮询双双失明，日志只剩心跳、跑的还是旧代码。修法：指纹补 **`ctimeMs`**（tar 与 `utimes` 都设不了它）。应急：`touch <profile>/node_modules/dsh-mobile-sh/dist/*`。判据：`grep '发现变更' /tmp/mobile-sh-hotreload.log` + 新的 `ready`。细节与部署步骤见 `docs/pitfalls.md` 第 58 条。

59. **`adb shell uiautomator dump` 与 mobilewright 争同一个 UiAutomationService，后到的那条当场崩、`cat` 出来的是上次的旧树**。2026-11-02 实测（模拟器 `test_emulator`）：mobilewright 的 `snapshot` 明明停在聊天页的权限菜单，同一时刻 `adb -s emulator-5554 shell uiautomator dump /sdcard/ui2.xml` 却吐出一棵**登录页**的树（`登录与配对` / `中继地址 https://localhost:8443` / `连接失败，请检查网络后刷新`），先 `rm -f` 也照样——`cat` 读的是**上一次留下的文件**（更早一次甚至读到旧会话的 Settings 页，版本号是 0.2.5）。真报错在 logcat 里：`IllegalStateException: UiAutomationService … already registered!`（`com.android.commands.uiautomator.Launcher` → `DumpCommand.run`），我一开始把它当成「App 自己跳回登录页」去查，白绕一圈。规矩：调试期间只用一条腿走路，用 mobilewright 就别敲 `uiautomator dump`；dump 的 stdout 看到 `Killed`/异常就别 `cat`；判据以 mobilewright 的 `snapshot`（或 diag.log）为准。细节与像素取证手段（遮罩亮度、导轨列直方图、图标/文字分列取色）见 `docs/pitfalls.md` 第 59 条。

**数据库 / 落盘**
61. **改了 Room 实体却没抬 `@Database(version)`，整条本地时间线会静默失效，症状像网络问题**。2026-11-02 真机（0.2.45）实测：列表页预览永久停在旧值、进会话页也补不到新内容；日志里只有 `sync v2 session <id> failed: sync-loop` 与 `连续失败 3 次`。真因是早期改动加了 `stub_preview_head` 列但版本号还是 1 → Room 比对 `room_master_table.identityHash` 不符，抛 `IllegalStateException: Room cannot verify the data integrity`；于是 `commit` 被 `runCatching` 吞成 `wrote=0`、`watermark()` 也被吞成 `from=0`、中继判「同一个 afterSeq 反复问」返回 429。**取证**：`diag.log` 里那条 `commit … 失败: IllegalStateException…`（本轮才补的 `onFailure` 打印）+ `databases/dsh-timeline.db` 的 mtime 停住且**没有 `-wal`**；把库拉回来用 `python sqlite3` 查 `room_master_table` 与 `android/app/schemas/**/<最高版本>.json` 的 identityHash 比对（本次 `3847d1cc…` vs `11b306db…`，差集列只有 `stub_preview_head`）。**修法**：抬到 version 2 + `Migration(1, 2)` 加列 + `.addMigrations(...)`；`fallbackToDestructiveMigration()` 救不了这种升级。护栏 `SchemaVersionTest`（读源码文本，Room 的 `@Database` 是 CLASS 保留）钉「schema 版本 == 注解版本」「已发布哈希不许变」「相邻版本必须有 Migration」，写完**反向验一次**必须红。细节见 `docs/pitfalls.md` 第 61 条。

**三端同线 / 部署一致性**
67. **中继已把 v1 全部 410、而本机插件还停在旧构建 ⇒ 审批卡「画得出来、点不动」**。2026-11-03 实测：装着的是旧构建，V1 下线之后的插件才发控制流 `INTERACTION` 帧，旧版的 `waterfall` 分支只打一行 `phone still answers over the v1 path` 就丢掉。手机拿审批卡有两条来源——控制流帧（需要新插件）与归档投影（`Projector.approvalCard()` 显式 `answerable = false`，只读）——旧插件只满足第二条，于是卡片理由/工具名都在、**只有按钮是灰的**，用户点了以为批过了而那一轮还卡着。判据用**特征串不用哈希**：`grep -c INTERACTION <dist>/worker.js` 旧 `0` / 新 `4`，或直接读 `dist/build-info.json` 的 `build` 字段（`build.mjs` 注了时间戳，同源码重建 shasum 必然不等）。升级就覆盖 `dist/` 那 5 个文件、先 `cp -p` 备份，等 1 秒轮询打出 `[10] 完成，重新注册 1 个 fiber` + `ready`。**教训**：中继一关旧路径，插件侧的替代通道必须已到位——顺序是「插件与手机先发版，中继最后关」。细节见 `docs/pitfalls.md` 第 67 条。

70. **`ASSISTANT_TEXT` 名不副实：一回合里 70% 的「助手正文」记录根本没有正文，任何按它计数的分页都是假的**。`packages/core/src/classify.ts:95-96` 对宿主每次 `assistant/message` 无条件归类，而大量只含 `type=reasoning`。生产库 7774 条里 5763 条无正文（67.0MB）；全库正文 473K 字符 vs 思考 13308K 字符（28×）。下游症状三连：列表预览偶发整行空白、点一次「载入更早」只多 1~6 段正文、进会话第一屏几乎全是思考与工具。**正解不是「多扫几条」**——第一版倒扫 + 上限 64 条，被「50 条正文 + 200 条纯思考」打穿（预览整行消失）。改成写入时算 `has_body` 列（真解析 `previewHead()`，不用 payload 字面量，否则思考正文里恰好出现 `"type":"text"` 会假阳性），读侧 `bodyPage(has_body=1)` 索引点查。老库迁移允许字面量近似（对生产库 7786 条比对：`bodyReal=1980 / bodyFast=1980 / 漏 0 / 误 0`），运行期一律真解析。

71. **`LaunchedEffect` 的 key 里含自己写的那个 state，赋值就会把当前协程当场取消**。2026-11-03「载入更早」保位失效就是它：`pendingAnchor = null` 改掉 key → 协程取消 → 后面的 `withFrameNanos`/`scrollToItem` 一个都不执行。**判据很硬**：加一条「滚动之后」的日志，从不打印就是协程被取消。修法：滚动移进 `rememberCoroutineScope().launch { }`（`scope` 必须声明在 `LaunchedEffect` 之前）。同处还有三个坑：①锚点判据不能用「行数变多」（流式每帧往尾部追加，加载没回来就先满足）；②`mergeTurnPanels` 把用户看着的 `#segN` 并成 `#seg0`，`panelId` 身份失效，必须带 `seq` 兜底；③`firstVisibleItemIndex` 可能命中 leading 行，要取 `layoutInfo.visibleItemsInfo.firstOrNull { it.index >= leading }`。

72. **内存投影窗口被「重读尾部」一把换掉，用户翻出来的历史就随下方输出一起蒸发**。`ChatSyncActor` 的 `records` 是内存窗口，`loadEarlier()` 并进来的前部，被每次落盘末尾那句 `records = store.tail(...)` 换回「最后 200 条」⇒ 用户看历史、对面还在打字，翻出来的内容瞬间消失。**不能靠过滤内存修**（中间那段可能落进了 Room 却从没进过内存，过滤会让时间线凭空多洞），数据库是权威：`ChatStore.between(sinceSeq, beforeSeq)` 连续读回再拼新尾部（`refreshTail()`）。代价是窗口可能长于 `TAIL_LIMIT`，上限由 `PER_SESSION_LIMIT=2000` 兜住；`project()` 实测 200 条 1.01ms / 1000 条 1.70ms / 2000 条 3.27ms（JVM）⇒ 一开始就读 2000 条不值得，懒加载窗口 + 刷新时连续读回才对。

74. **`LazyColumn` 迭代的是 `mergeTurnPanels` 之后的 `rows`，不是 `state.messages`——任何拿 `messages.size` 算列表下标的算式都是错的，而且错得看不出来**。`itemsIndexed(rows, key = …)` 用的是合并后列表，`tailItemIndex(state)` 却用 `state.messages.size`：一轮 4 块相邻面板 + 1 条提问时 `messages=5`、`rows=2`，旧算式给出 4，列表最后一项是 1（探针实测 `PROBE messages=5 rows=2 tailItemIndex=4 lastRendered=1`）。**线上没炸是因为 `scrollToItem` 对越界下标会夹到末项**——但 `itemHeightOf(4)` 查不到可见项恒返 0，`tailScrollOffset(0)` 退回 `1_000_000` 哨兵值，「盖过末项高度」退化成「赌一个足够大的数」。修法：`tailItemIndex(state, rowCount)` 显式吃第二参数、四处调用点全传 `rows.size`，**刻意不给默认值**（默认值等于让每个新调用点默默踩同一个坑）。细节见 `docs/pitfalls.md` 第 74 条。
