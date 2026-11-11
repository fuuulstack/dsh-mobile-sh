# dsh-mobile-sh 产品需求文档（PRD）

> 文档核对于 2026-11-11 · 对应版本：Android **0.2.60（vc 61）** · 新建公开库首次提交
>
> 本文档由 13 份设计与评审稿合并而成（`docs/arch-redesign-prompt.md`、`docs/current-arch-prompt.md`、
> `docs/redesign-arch-gemini-v1.md`、`docs/redesign-arch-gemini-v2.md`、`docs/dev-fetch-kimi-v1.md`、
> `docs/review.md`、`docs/review-502.md`、`docs/review-arch-glm-v1.md`、`docs/review-arch-mimo-v1.md`、
> `docs/review-fetch-ds-v1.md`、`docs/review-fetch-gemini-v1.md`、`docs/review-fetch-kimi-v1.md`、
> `docs/ui-taste-review.md`）。**合并后那些文件已删除**；本文档是它们唯一的继任者。
>
> 规模与自动化基线于 2026-11-11 对着当前代码核实；协议常量以三端源码为准，改代码要同步本文。
> 设计稿里被现实推翻的判断集中记在 §9.2，**不要重走**。

---

## 目录

- [0. 本文档怎么读](#0-本文档怎么读)
- [1. 产品定位与边界](#1-产品定位与边界)
- [2. 用户与场景](#2-用户与场景)
- [3. 系统架构](#3-系统架构)
- [4. 功能需求](#4-功能需求)
- [5. 非功能需求](#5-非功能需求)
- [6. 数据模型与协议契约](#6-数据模型与协议契约)
- [7. 交互硬规则](#7-交互硬规则)
- [8. 验收标准](#8-验收标准)
- [9. 明确不做与已推翻的判断](#9-明确不做与已推翻的判断)
- [10. 遗留项与后续](#10-遗留项与后续)

---

## 0. 本文档怎么读

### 0.1 四类文档的分工

| 文档 | 回答什么 | 变更频率 |
|---|---|---|
| **`docs/PRD.md`（本文）** | 要做什么、为什么这样做、契约是什么、怎么算做完了 | 需求或架构变化时 |
| `HANDOFF.md` | 现在到哪一步了、下一步做什么 | 每次交付 |
| `docs/pitfalls.md` | 哪些做法**已经试过并失败**，为什么 | 每次踩坑 |
| `docs/RELAY-DEPLOY.md` | 中继怎么部署、怎么验 | 部署方式变化时 |

`docs/plugin-ui.md`（插件面板）与 `docs/pitfalls.md`（踩过的坑）是专题说明；本文只给需求与验收，实现细节看那两份。

### 0.2 三条阅读原则

1. **§4 功能需求是产品契约**，改行为先改这里。
2. **§6 的常量与字段是跨端契约**，任何一端单独改动都会炸（历史上踩过至少 5 次）。
3. **§9.2 是被证伪的设计**。看起来合理但已实测不成立，写新方案前先读。

---

## 1. 产品定位与边界

### 1.1 一句话

DSH（DeepSeek Harness）桌面端的**手机遥控器**：电脑上跑 DSH，装一个中继插件；手机 App 连中继，就能看、发、管这台电脑上的**所有会话**。

### 1.2 平台三段式（不可改）

```
手机 App (Kotlin/Compose)  ──TLS/WSS──►  中继 relay (Node)  ◄──WS 长隧道──  插件 plugin（跑在 DSH 桌面进程里）  ──HTTP──►  DSH 宿主 (127.0.0.1)
```

这条拓扑是**产品前提**，不是实现细节：

- 宿主的 HTTP 接口只监听 loopback，**手机永远直连不到**。
- 中继在公网、有 TLS、是唯一入口。
- 插件跑在用户电脑上、由用户自己安装、能访问本机 127.0.0.1。
- 因此「手机 ↔ 电脑」的任何通信都必须**穿过中继**，且中继**没有**宿主的凭据。

推论（决定了后面所有设计）：

- 中继必须自己持有归档，否则电脑关机后手机什么都看不到。
- 中继不能解析业务事件做过滤，否则新事件类型会被静默吞掉（§9.2 第 3 条）。
- 手机必须有本地持久化，否则弱网下每次进会话都要重拉。

### 1.3 做什么 / 不做什么

**做**：

- 手机端完整替代桌面端**看与发**的能力（列表、聊天、审批、排队、任务、模型/权限切换）。
- 离线可读：电脑关机也能翻历史（14 天内）。
- 一码配齐：扫码同时完成「登录中继 + 配对主机」。

**不做**：

- 不做多租户 SaaS。这是**自托管**工具，一个中继通常服务一个人/一个团队。
- 不做桌面端的替代品：不做文件树编辑、不做终端、不做工作区管理。
- 不在手机上跑模型、不做本地推理。
- 不实现宿主的任何业务逻辑（比如自己去解析 diff、自己算 token）。

### 1.4 现状规模（2026-11-11）

| 项 | 数值 |
|---|---|
| Android 主源码 | 25 169 行 / `com/dsh/mobile/v2/` 下 52 个文件 |
| 插件源码 | 6 052 行 / 23 个文件 |
| 中继 | `server.ts` 约 1 400 行路由 + `store.ts` / `proxy.ts` / `changes.ts` / `sync-v2.ts` / `release.ts` / `limits.ts` |
| 自动化测试 | TS **262**（261 pass / 1 skip）、Android **539**（全绿） |
| 线上版本 | 0.2.60 / vc 61 |

---

## 2. 用户与场景

### 2.1 目标用户

**一个人，两台设备**：主力开发在电脑上跑 DSH（可能同时开着几个工作区），人离开工位时用手机接着看/接着发。

不是「团队协作」，不是「给不会用命令行的人用」。用户已经理解 agent、工具调用、审批是什么。

### 2.2 五个核心场景

| # | 场景 | 关键诉求 |
|---|---|---|
| S1 | **离开工位，看它跑完没有** | 打开 App 三秒内看到会话列表与「运行中」标记；点进去看到正在做什么 |
| S2 | **在电梯里发一句话，回来收结果** | 弱网/断网也能发出去（排队），回到工位时结果已在手机上 |
| S3 | **半夜它卡在审批上** | 手机收到卡片，点「允许一次」，agent 继续跑 |
| S4 | **翻三天前的会话找一段结论** | 电脑关机也能搜、能翻、能复制 |
| S5 | **手机上发现 App 有新版** | App 内检查更新 → 下载 → 安装，不跳浏览器 |

### 2.3 非目标场景（明确不考虑）

- 多用户同时操作同一台电脑（一个会话被两个人同时发消息）。
- 手机上做代码 review 并提交 PR。
- 用手机当第二屏镜像桌面 UI。

---

## 3. 系统架构

### 3.1 三端职责

| 端 | 职责 | 明确不负责 |
|---|---|---|
| **手机 App** | UI 渲染、纯函数投影器（记录→界面模型）、同步协调器、本地 Room 存储、审批账本 | 不解释业务事件语义、不拼 prompt、不管归档 |
| **中继 relay** | 鉴权（账号/设备/主机授权）、**14 天加密归档**（权威副本）、透明隧道（字节级转发）、SSE 变更信号 | **不解析业务事件**、不做事件白名单、不存业务状态机 |
| **插件 plugin** | 采集宿主事件并**唯一编号**（分配 `seq`/`prevSeq`）、SQLite outbox 缓冲、攒批推送、终结手机控制流、上报指标 | 不渲染 UI（除设置面板）、不做重试风暴 |
| **宿主 DSH** | 运行时真相：会话、事件流、审批、模型 | 不知道手机存在 |

### 3.2 权威源裁定

| 问题 | 权威源 | 理由 |
|---|---|---|
| 会话此刻在跑什么 | 宿主 | 它是运行时 |
| 这条事件的序号是多少 | **插件 Worker** | 唯一编号点，避免两端各自编号错位 |
| 归档里到底有什么 | 中继 | 手机可能被清数据，中继是唯一长期副本 |
| 界面上该显示什么 | 手机（本地 Room + 内存 overlay 投影） | 离线也要能渲染 |
| 这条审批结算了没有 | 手机 `interaction_ledger` + 宿主 | 宿主每次新连接会重投未结算项，账本负责去重 |

### 3.3 三条数据链路

**① 实时（前台，进会话后）**

```
宿主 $events ──WS──► 插件 Collector ──► SessionHub.ingest()
                                          ├─ 分配 seq/prevSeq
                                          ├─ applySizePolicy（超大存根化）
                                          ├─ 写 outbox（落盘）
                                          └─ 广播 RECORDS 帧（切 32KiB/片）
                                                │
手机 ◄── 隧道 ◄──────────────────────────────────┘
  └─ ChatSyncActor.feed.records() → Room.commit() → Projector → UI
```

**② 归档（后台，列表页也在跑）**

```
插件 pusher（250ms 轮询）──POST /control/timeline──► 中继 timeline_records
                                                       │
                                                       ├─ changes.publish({sessionId,latestSeq})（只发信号）
                                                       └─ 手机 SSE 收到信号 → POST /native/v2/hosts/:id/sync
```

**③ 控制（手机 → 宿主）**

```
手机 CommandFrame{cmdId,op,payload} ──隧道──► 插件 ControlSession
                                                ├─ 同 cmdId 命中缓存直接重放（幂等）
                                                ├─ inflight Map 在途去重
                                                └─ loopback POST → 宿主（agent:false，不重试）
                                                     └─ CommandAck{cmdId,ok,error?,pluginMs}
```

审批回答**必须**用插件自己 `$events` 流的 `clientId`，不能用手机的（宿主会当「没投递给你」丢弃）。这是「点完审批又冒出来」的根因之一。

### 3.4 部署拓扑

```
┌─ 用户电脑 ────────────────────────────┐        ┌─ 云主机（2 核 / 768MB）──────┐
│ DSH 桌面宿主 (127.0.0.1:9749)         │        │ nginx (TLS 443)              │
│   └ 插件 dsh-mobile-sh             │        │   └ relay (127.0.0.1:8088)   │
│       ├ 父进程：隧道/生命周期/热重载   │◄──WS──►│       ├ /native/v2/*  手机面 │
│       └ Worker：采集/编号/outbox/push │        │       ├ /control/*    主机面 │
└───────────────────────────────────────┘        │       └ SQLite（14 天归档）  │
                                                 └──────────────────────────────┘
                                                                ▲
                                                    手机 App ────┘ TLS + WSS
```

- 中继资源上限是**硬约束**：2 核 / 768MB。任何 O(N) 的全表操作、任何同步大循环都会把机器压死（真实发生过，见 §5.6）。
- 插件 Worker 用 `fork()` 而非 `worker_threads`：线程与宿主共享堆与 GC，宿主一卡 Worker 跟着卡。

---

## 4. 功能需求

> 每条格式：**需求**（产品契约）→ **实现要点**（现状怎么做的）→ **验收判据**（怎么算做完）。

### 4.1 登录与配对

**需求**

- 支持两种入口：①**扫码/深链一码配齐**（登录中继 + 配对主机一步完成）；②手动输入中继地址 + 8 位设备码。
- 8 位设备码**不需要**邮箱/密码/TOTP。
- 同一台手机重复登录**必须复用同一条设备记录**，否则之前配好的主机会全部变成「未配对」。
- 令牌存 AndroidKeyStore（AES-GCM），不落明文。
- 支持设备管理：查看已配对设备、单台解绑。

**实现要点**

- 深链：`dsh://pair?relay=<url>&code=<8位>&login=<8位>`（`login` 可空，兼容老中继）；`AndroidManifest.xml` 注册 intent-filter，`MainActivity` `singleTask` 转发。
- 设备码：`POST /native/v2/device-code` → bearer；`POST /native/v2/pair` 用码换 host 授权。
- 设备身份：手机**不自报花名**，一律传 `null` 由网关填机型。中继按 `label` 查未吊销的同名设备行复用（`store.consumeDeviceCode` 与 `store.login` 同口径）。
- 令牌：`TokenVault`（prefs `dsh-v2-session` + KeyStore AES-GCM）。
- 登录中继限流：IP 5/min + 全局 60/min + 失败 10/10min（**凭据类不放宽**，见 §5.4）。

**验收判据**

- 扫码后无需再输任何东西即进列表页。
- 同一台手机连续扫两次码，设置页设备列表里**只有一台**设备，且两台主机的授权都还在。
- `run-as … cat shared_prefs/dsh-v2-session.xml` 看不到明文令牌。

### 4.2 会话列表

**需求**

- 展示：标题、**首行预览**、运行中转圈、平台图标、更新时间（中文相对时间）。
- 三种排序（按工作区 / 按日期 / 正在运行）+ 下拉刷新 + 搜索。
- 长按会话：后台任务 / 重命名 / 删除（仅删本机缓存）。
- 点「更多」：按工作区 / 按日期 / 正在运行 / 输入配对码 / 刷新会话 / 设置（**6 项**）。
- 首行预览必须**与正文同源**：手机从本地已落盘的助手正文现算，不依赖宿主的 snippet。
- 停在列表页时，别的设备/电脑发的新消息也要能自己刷出来。

**实现要点**

- 预览：`data/Preview.kt` 取最后一条 `role==ASSISTANT && panelId==""` 消息的第一行非空文本；用户输入/思考/工具调用全跳过。宿主 `session/list` 不返回 snippet，中继也不转发它。
- 预览三级来源：Room 完整记录 → 存根记录的 `stub_preview_head`（UTF-8 字节截断，CJK 安全）→ `payloadJson.take(2048)`。
- 排序默认 `BY_WORKSPACE`；`SortMode` 枚举里 `RECENT` 目前**无使用点**（只有定义）。
- 列表刷新：`loadSessions()` → `fillPreviews()` → 批量 `POST /native/v2/sync`（`BACKGROUND_BATCH`，一次最多 32 会话，每会话预算 `max(16KiB, 512KiB/min(会话数,8))`，最多 20 轮）。
- 列表页**不是定时轮询**：靠 SSE 变更信号触发一次增量同步。SSE 断线重连后，`ChangesReconnect` 判断「这次连上之前掉过」才补一次 `loadSessions(silent=true)`；冷启动首次连上不重复补。
- 中继 `hosts/:id/sessions` 由中继本地答（`sessionItems(hostId)`），**不走隧道** ⇒ 电脑关机也能列会话。
- 在线状态：`Proxy.online()` = 隧道 `lastSeen` 在 45s 内（心跳 30s 一次），不看 WebSocket 开没开（TCP 半开时 `close` 可能几十秒才来）。落盘前把 `online` 清成 false，绿点只认中继本次答复。

**验收判据**

- 冷启动先出缓存列表（不白屏），再增量替换。
- 在电脑上发一条消息，手机停在列表页 ≤10 秒后预览自己变。
- 电脑关机 → 列表仍可读，主机显示离线，点进会话能看历史。

### 4.3 聊天

**需求**

- 流式回复、思考内容、工具步骤、Markdown 渲染、附件（相册/拍照/文件）。
- 权限三档（仅可查看 / 工作区内修改 / 完全权限）、模型与思考等级可切。
- 发送钮**三态**：
  - 宿主在跑 + 输入区空 → 「停止」
  - 输入区有字（或待发附件）→ 一律「发送」；**单击 = 排队**（`mode:"queue"`，等这轮跑完），**长按 = 插话**（`mode:"steer"`，插进正在跑的这一轮）
- 用户上翻历史后，新内容**不得**把他拽回底部；出现「回到最新」入口。
- 键盘弹出时把最新消息顶到键盘上方。
- 草稿空态：新会话未发消息时给引导文案，而不是一片空白。

**实现要点**

- 时间线由 `TurnUiModel` 承载，**槽位硬编码**：`userMessage → processPanel → assistantBlocks → interactions`。
- 一轮可以有**多段**（`TurnSegment`）：一段正文闭合一段，渲染顺序「折叠栏 → 它产出的正文」。解决「一轮 24 个 step 全挤在一个折叠栏下」与桌面端不一致的问题。
- `TurnState = {RUNNING, WAITING_INTERACTION, COMPLETED, INTERRUPTED}`。
- 贴底：`ui/AutoScroll.kt` 的 `stickOnGrowth` / `stickOnContentGrowth` / `StickToBottomOnGrowth`；`contentHeightOf(maxValue, viewportSize) = maxValue + viewportSize`（用旧视口算会差一屏）。
- 输入贴底：键盘「由无到有」的边沿主动贴尾，`scrollToItem(尾部)` 会被列表夹到底部。
- 打字机：`Typewriter`，追赶量 `(pending + CATCHUP_TICKS - 1) / CATCHUP_TICKS`（恒定 ~170ms）。**不要用次线性反馈**（`pending^0.7/8` 会随输入速率线性落后，永不收敛）。
- 权限胶囊：`PERMISSION_PRESETS` = `read-only`（仅可查看）/ `workspace-write`（工作区内修改）/ `danger-full-access`（完全权限）。
- 模型胶囊带思考等级（`choice.efforts` 来自宿主 `reasoning.efforts`）。

**验收判据**

- 发一条长回复，正文从尾部逐字增长，不整段跳。
- 上翻后新消息到达，视口不动；点「回到最新」回到底部并恢复跟随。
- 输入框有字时按钮显示「发送」，单击后卡片出现在输入框上方（排队），长按则直接插进当前轮。

### 4.4 过程展示（折叠行 + 抽屉）

**需求**

- 一轮的所有非正文步骤**收成一行**，任何时候都报数：「已处理 N 个操作」。
- 运行中这一行要能看出**此刻在干什么**：前导图标按类型换（思考 `sparkles` / 命令 `terminal` / 技能 `package`），**整轮没跑完时图标一直转**，尾部报操作名或思考尾部预览。
- 点开抽屉看全链路（每步详情、耗时）。
- 折叠行报的「N 个操作」与抽屉点开后的条数**必须同源**。

**实现要点**

- 文案：运行中 `已处理 N 个操作 · 思考：<尾部预览>`；落定后 `已处理 N 个操作 · 用时 X 秒`。
- 一个工具都没调时：运行中报「正在分析」、落定后报「已完成分析」，**不编「已处理 0 个操作」这种假数字**。
- 「这一轮是否还在跑」用 `foldLive(message, sessionRunning)` 判——宿主每步都发 `step/end`，只看 `message.running` 会在中途误报。
- 折叠行**单行**（`maxLines = 1`），思考预览用 `INLINE_THINKING_LIMIT = 12`（13sp 扣掉图标与箭头只剩二十来字，22 会放不下，可见的就不是尾部了）。
- 有活思考时**尾部不再挂操作名**（`思考：…` 已经把话说了，再挂工具名既挤爆一行又让人困惑）。
- 抽屉每帧取**活对象**（`resolvePanel(rows, panelId, turn)`），不能 `remember` 住快照，否则打开后内容冻住。
- 计数统一走 `Model.Message.toolCount()`（= `steps.count { kind == TOOL && actionIndex in actions.indices }`），折叠行/抽屉标题/面板页脚三处同源。

**验收判据**

- 单条思考也进折叠行（不单独成行），一轮里只出现**一个**「已处理 N 个操作」。
- 运行中图标在转，尾部的思考预览逐字变化（用 `snapshot` 读文本，OCR 看不出来）。
- 折叠行说 8 个操作，点开抽屉正好 8 条。

### 4.5 审批与提问卡片

**需求**

- 宿主需要审批/提问时，手机弹出卡片，可直接作答。
- **只显示属于当前会话的卡片**——别的会话的审批不得弹进当前聊天页（否则点「允许一次」等于给别的会话放权）。
- 卡片 id 必须**稳定**：宿主每次新连接会重投未结算的 waterfall，时间戳做 id 会让同一张卡每次多堆一条。
- 点过的卡片不得因为重投而复活。
- 回答失败要让卡片**回来**（可重试）；宿主 cancel 帧要清账。

**实现要点**

- `interactionId = "${sessionId}:${seq}"`，`seq == 0` 时退到 `"${sessionId}:${eventId}"`，**严禁时间戳**。
- `InteractionLedger`（Room `interaction_ledger`，PK `(session_id, interaction_id)`）：`LedgerState = {PENDING, CLICKED_INFLIGHT, RESOLVED, CANCELLED}`；重投只对 `PENDING` 生效。
- 归属过滤：`interactionBelongs(agentId, sessionId)`，`agentId`/`sessionId` 为空时放行（字段缺失/草稿页）。
- `$events` 是 **app 级一条流**，开机时建一条，**不要为了「验真」重开**——重开会触发宿主向新 client 逐个重投，效果是「点一次复活一次」。
- 回答走 `$events/result`，`clientId` 用**插件自己**那条流的 id。

**验收判据**

- A 会话产生审批时，在 B 会话页看不到这张卡。
- 点「允许一次」后卡片消失，重连/重进会话不再出现。
- 回答失败（如网络断）后卡片回到可点状态。

### 4.6 排队消息卡片

**需求**

- 输入框上方逐条列出宿主 inbox 里还没被领走的消息。
- 每条三个动作：编辑 / 插话 / 删除。
- 动作失败后必须**重读队列**（否则卡片会挂在界面上两分钟，而宿主那边早被领走）。

**实现要点**

- 数据源：`session/projections` 的 `values.inbox`（`next-turn` / `next-step` 两桶，条目是完整 message 对象，`id` 是随机 UUID）+ 实时 `agent/inbox/spliced`。
- **没有「列队列」的 RPC**，只有一个变更接口：`session/updateQueue{sessionId, itemId, action:{kind:"edit"|"remove"|"steer"}}`。
- `steer` 只对 `next-turn` 且 `agent.status==="running"` 有效，否则回 `session/steer-unavailable`。
- 实时增量语义是 `toSpliced`（`target` / `start` / `removedCount?` / `inserted`），App 侧 `applyInboxSplice` 复刻下标钳位。
- `edit` 只接受纯文本（带图条目编辑后图片会丢，宿主直接拒）。

**验收判据**

- 发两条排队消息，卡片按顺序出现在输入框上方。
- 点删除后卡片立即消失，且宿主 inbox 里也没了。
- 断网时点删除 → 报错后卡片**刷新成宿主真实状态**。

### 4.7 任务胶囊

**需求**

- 会话页右上角悬浮 `任务: n/n`（已完成的 todo / 总数）。
- 全部完成时中性退背景，不抢注意力。

**实现要点**

- 数据源：`session/projections` 的 `values.todos` + 实时 `todo/write` 事件。
- 点开 `TaskListSheet` 传**此刻**的 todos（不是快照）。

**验收判据**

- 宿主 `todo/write` 后胶囊数字立即变。
- 全部完成后胶囊不再高亮。

### 4.8 新建会话

**需求**

- 点「新建」只开**本地草稿**，发出第一条消息才真正 `create`。
- 理由：避免电脑端凭空多一条空会话、并被广播到别的设备。

**实现要点**

- 草稿 `id == ""`：`markSessionRead`、prefs key、`remember(session.id)` 都要吃空串。
- 创建失败不能只写 note（输入框已清空），要把这句话作为**失败回声**留在时间线里好重发。

**验收判据**

- 点新建后电脑端会话列表**不新增**任何条目。
- 发第一条消息后会话才出现，且草稿页无缝切成正式会话。

### 4.9 后台任务

**需求**

- 一览正在运行的会话，可直接点进去。

**实现要点**

- `Route.TASKS` → `TasksScreen`；入口在「更多」菜单与会话长按菜单。

### 4.10 设置

**需求（6 组）**

1. **中继服务器**：连接 hero（平台头像 + 延迟/上行/下行实时速率）、断线自动重连开关。
2. **对话偏好**：显示思考、编辑内容等。
3. **调试**：诊断日志入口。
4. **版本管理**：检查更新、下载、安装。
5. **设备管理**：已配对设备列表（与顶栏主机下拉**共用同一口径**）。
6. **账户**：退出登录。

**实现要点**

- 连接 hero 的延迟/速率是实时采样，不是静态文案。
- 设备管理的在线小圆点与列表页主机绿点同源。

### 4.11 更新

**需求**

- App 内检查更新 → 下载（支持续传）→ 校验 → 系统安装器。
- 不得跳浏览器（ColorOS 会截走安装 Intent 导致静默失败）。

**实现要点**

- 清单 `https://downloads.example.test/api/ybs/downloads/version.json`。
- 下载：`downloadClient`（HTTP/1.1 + Range 续传 + sha256 校验 + 清残包）。必须允许跨路由重试（静态更新域名走 Cloudflare，DNS 同时下发 A/AAAA，无 IPv6 出口时 OkHttp 不回退）。
- 安装：`PackageInstaller` 会话；`ApkProvider` 只放行 `getExternalCacheDir()/updates/`。
- 签名：线上历史包一律由服务器 `dsh-release.keystore` 签（SHA-256 `<线上签名证书指纹>`）。未设 `ANDROID_KEYSTORE` 会回落 debug keystore（另一把，签出来装不上）。

**验收判据**

- 检查更新能显示新版本号与说明。
- 下载中断后重试从断点续传。
- 安装完成后 `versionCode` 真的变了（不是「显示装完、版本不变」）。

### 4.12 插件 UI

**需求**

- DSH 侧栏出现「手机访问」面板。
- 面板内可填中继地址与中继鉴权密码、保存、查看连接状态。
- 可生成配对二维码（扫码即配齐）。
- 可开关「连接时阻止电脑休眠」。

**实现要点**

- `UI_PREFIX = '/dsh-mobile-sh'`，注册 6 条 exact 路由：主页面 / `/embed` / `/api/status` / `/api/settings` / `/api/power` / `/api/pair`，全部过 `guard()`（fail-closed）。
- 二维码在服务端画成 SVG（`img-src data:` 即可，CSP 不必放开 blob/script）。
- 防休眠**不能依赖 Electron API**：宿主是 `ELECTRON_RUN_AS_NODE=1` 的纯 Node 模式，`require('electron')` 失败。走系统命令兜底（macOS `caffeinate -i -w <宿主pid>` / Linux `systemd-inhibit --what=idle`），`-w <pid>` 把子进程绑在宿主生命周期上。
- 只在隧道 ready 时持有防休眠断言，掉线宽限 3 分钟自动放掉；`supported=false` 时页面画成不可用。

细节见 `docs/plugin-ui.md`。

**验收判据**

- 面板能在 DSH 侧栏打开，明暗主题都正常。
- 生成二维码后用手机扫，一步进列表页。
- 勾上防休眠后 `pmset -g assertions`（macOS）能看到 `caffeinate` 持有断言。

---

## 5. 非功能需求

### 5.1 性能

| 指标 | 目标 | 现状证据 |
|---|---|---|
| 首屏 p95 | < 5s（冷启动不白屏，先出缓存） | 缓存优先渲染 + 增量替换 |
| 消息发出到上屏 p95 | < 5s（正常网络） | `MessageTrace` 六跳打点可测 |
| 列表页增量收敛 | 几十秒内 | SSE 信号 → 单次 `/sync` |
| 单页同步 | ≤ 512KiB / 240 条 | 实测冷启动一页 501 017 字节 / 240 条 / 3.79s |
| 中继归档写 p95 | 监控不设阈值 | `/metrics` 的 `archiveWriteMs`（环 256 样本，**用 p95 不用均值**——均值 84ms 会盖住「一批 4s、另外五十批 6ms」的尖峰） |

### 5.2 可靠性：十条不变量

每一条都对应一次真实事故，**改架构时逐条对照**。

| # | 不变量 | 违反会怎样 | 现状实现 |
|---|---|---|---|
| 1 | **半死自愈**：12 秒无脉搏判半死，**只增量恢复，绝不 since=0** | 界面永久停在「深度思考」，实际 `turn/end` 早到了 | `SyncCoordinator`：`PULSE_MS 5000` / `PULSE_MISSES 2` / `PULSE_DEAD_MS 12000`；`armedAt` 保证「一条脉搏都没到」时也能判死；`recover()` 走 closeControl → openControl → focus → 增量补差 |
| 2 | **三水位物理分列** | 拿元数据水位当正文水位，导致永久漏拉 | `SessionWatermarks(serverMeta, archiveCovered, localAcked)`，三个 value class 编译期隔离；`/v2/sync` 唯一合法 `afterSeq` 是 `localAckedSeq` |
| 3 | **序号只排序、不要求连续** | 缺一个号就卡死 | `prevSeq` 只用于判断链，`nextCursorSeq` = 真正放进这一批的最后一个 seq |
| 4 | **先落盘、后推水位** | 崩在中间就永久丢消息 | Room `commit()` 是 `@Transaction`：先 `insertAll` 再 `saveMeta`；`advance()` = `min(nextCursor, landed)`，只进不退 |
| 5 | **不许静默丢弃** | 用户以为没消息，其实被吞了 | 超硬上限记 `ERROR_OVERSIZED_TRUNCATED`（保留首尾 32KiB）并**渲染成可见错误行**；`STOP_REASONS` 六种都要有 UI 对应 |
| 6 | **同会话单飞** | 并发补差互相覆盖游标 | `SyncCoordinator.decideRun(inFlight, pending, cooldown, now)` |
| 7 | **同批不重复上传** | 中继重复计数、归档膨胀 | `batchIdOf()` **内容派生** + `INSERT OR IGNORE` 幂等 |
| 8 | **空结果 ≠ 没工作** | 排障把「已追平」当「丢失」 | `UNKNOWN_SESSION` 判据是 `covered === 0 && afterSeq === 0`（不是 `covered <= afterSeq`）；存根记录也必须推高 `nextCursorSeq` |
| 9 | **重连有界退避 + 抖动** | 惊群、或无限重试打爆中继 | `backoffMs` / `cooldownMs` |
| 10 | **单点卡死不扩散** | 一个坏会话拖垮整批 | `SyncLoopGuard` **只在 `sessions.length===1` 时生效**；批量请求里一个卡住的游标不该 429 掉整批 |

### 5.3 安全与隐私

- **宿主接口只在 loopback**，手机永远直连不到。
- 归档在**中继侧字段加密**（`RELAY_SECRET_KEY_FILE`）。
- 手机令牌进 AndroidKeyStore（AES-GCM），不落明文。
- 审批只对**所属会话**生效（`interactionBelongs`）。
- 卡片 id 稳定（`sessionId:seq`），不随时间变化。
- `/metrics` 必须 Bearer 鉴权（`RELAY_METRICS_TOKEN`），**fail closed**。
- 插件面板路由全部 `guard()`，fail-closed。
- 生产 keystore 不进仓库（`artifacts/` 整体 gitignore）。

### 5.4 限流：两档

判据不是「这个数字大不大」，而是「**撞上它的会不会是正常用法**」。

**资源类（放宽）** —— `server.ts` 顶部 `RATE` 表是唯一事实源：

| 路由 | 额度 |
|---|---|
| `/sync` | 600 / 60s |
| `/changes` | 120 / 60s |
| `/archive`（`/control/timeline`） | 600 / 60s |
| `/metrics` | 60 / 10s |
| `handoff` / `unpair` | 120 / 60s |

理由：自托管中继按公网多租户量级去卡，只会罚到自己。实测冷启动四十多个会话补页就能把 `/sync` 的 30/min 打满，一路 429 拖三分钟，而那些全是正常读。

**凭据类（不动）** —— 防的是猜（8 位设备码、密码、注册口令）：

- `login` IP 5 / 60s
- `device-code` IP 5 / 60s + 全局 60 / 60s + 失败 10 / 10min
- `enroll`、`pair` 保持原值

**异常检测**：`SyncLoopGuard(10000, 5, 5000)` —— 同 `(deviceId, sessionId, afterSeq)` 10 秒内 >5 次 → 429。

### 5.5 可观测性

**中继 `/metrics`**：

- `syncStatsV2 = {requests, pages, records, bytes, zeroProgress}`
- `backfill = {copied, failures, chunks, running, done}`
- `changes.stats() = {online, dropped, drains}`
- `archiveWriteMs = {count, failures, samples, p95, last}`
- `hostMetrics[].tunnel`（**读法见 §5.5.1**）

**插件指标**（`METRICS_SCHEMA_VERSION = 1`，60s `POST /control/metrics`，失败不重试）：

```
windowMs
eventLoop{samples,meanMs,p50Ms,p99Ms,maxMs}
loopback{failures,total,byCode}
tunnel{state,reconnects,bufferedPeakBytes,stalls}
archiver{queued,inFlight,sending,pushMs,batchBytes,pushFailures,heldBack,renumbered}
```

- **窗口增量**，`snapshot()` 报过即清零 ⇒ `0/0` 不能读成「没堵过」，要连着几个窗口看。
- schema 版本口径：**加字段不涨版本，改语义必涨**。
- 事件循环滞后**不用** `perf_hooks.monitorEventLoopDelay`（实测卡 2000ms 只报 33.65ms），自己采样。

**Android `MessageTrace` 六跳**：

`T1_SEND → T3_PLUGIN → T4_HOST → T5_FRAME → T6_ONSCREEN`。

**没有 `T2_RELAY`**：中继按设计从不解析业务帧，给它盖时间戳就得在 2 核机上 `JSON.parse` 每一帧。`T3_PLUGIN` 由 `CMD_ACK.pluginMs` 反推，只报时长不报时间戳（两端时钟不可比）。

#### 5.5.1 隧道「活着但一帧不出」的两种成因

看 `hostMetrics[].tunnel` 能分开读：

| 现象 | 含义 | 处置方向 |
|---|---|---|
| `bufferedPeakBytes >= 1 MiB` | 插件侧 `Tunnel.pump()` 停过泵（应用层发送缓冲满 1MB 就 `setTimeout(run,5)` 空转）→ **本端发不出去** | 上行不足 / 中继吸不动 |
| `bufferedPeakBytes` 低但 `stalls` 高 | 发送端在等对端 `flow` 信用超过 10s → **对端不消费** | 手机接收慢，或下游写不动导致收不回信用 |

`stall` 探针在超时那一刻就 emit、**不等恢复**——现场最常见的正是永远不恢复。

### 5.6 资源上限（2 核 / 768MB）

- 任何**同步无上限**的 `SELECT` + 逐行加解密都会把机器压死。真实事故：回填在 `Relay` 构造函数里同步跑全表 → 用户态无响应 31 分钟，`load average: 1.19, 4.22, 10.23`。
  - 修法：分块（`BACKFILL_CHUNK=50`、`BACKFILL_MAX_BYTES=2MiB`）+ 游标条件 + `setTimeout` 让出事件循环 + 紧急开关 `RELAY_BACKFILL=off`。实测 47 131 行 / 130 秒 / 943 chunks、load < 0.8。
- 回填 `done=true` 不是终态：v1 插件仍在推，所以每小时 cleanup 里 `done=false` 再跑一轮。
- 高频查询必须有覆盖索引。真实事故：`timelineSummary` 1082.3ms → 加 `timeline_coverage(host_id, session_id, updated_at)` 后 3.2ms（**338×**），索引占 4.8MiB。
- 插件 Worker 内存 `--max-old-space-size=320`；SQLite `cache_size=-16000`、`temp_store=FILE`。
- 归档容量：**14 天 TTL**、**每会话 2000 条**。

---

## 6. 数据模型与协议契约

> 这一节的值是**跨端契约**。改任何一个，三端 + 测试要一起改。

### 6.1 `TimelineRecord`（唯一记录格式）

```ts
{
  sessionId: string
  seq: number            // 插件分配的单调序号，只排序不要求连续
  prevSeq: number | null // 链式校验用
  turnId: string         // `${sessionId}:t${turn}`
  stepId?: string        // `${turnId}:s${step}`
  kind: RecordKind
  timestampMs: number
  clientMsgId?: string
  payload: unknown
  stub?: boolean         // 超大内容已外置到 blob
  truncated?: { originalBytes: number }
}
```

**主键 `(sessionId, seq)`。**

### 6.2 `RECORD_KINDS`（14 种）

| kind | 含义 | 备注 |
|---|---|---|
| `USER_INPUT` | 用户消息 | |
| `STEP_THINKING` | 思考步骤 | |
| `STEP_TOOL` | 工具步骤 | |
| `ASSISTANT_TEXT` | 助手正文 | |
| `APPROVAL_REQ` / `APPROVAL_RES` | 审批请求 / 结算 | |
| `QUESTION_REQ` / `QUESTION_RES` | 提问请求 / 结算 | |
| `TURN_END` | 一轮结束 | |
| `INBOX_SPLICE` | 宿主队列变更 | 带 `seq`，可归档 |
| `TASK_LIST` | 任务列表**整表替换** | 宿主每次都发全量 |
| `META_PATCH` | 未知事件**原样透传** | 兜底，保证新事件不被吞 |
| `STREAM_DELTA` | 流式增量 | **live-only，永不归档** |
| `ERROR_OVERSIZED_TRUNCATED` | 超硬上限被截 | 渲染成可见错误行 |

**审批/提问不是会话记录**（无 `seq`、不落盘），必须走单独的 `InteractionFrame`。

### 6.3 三水位

| 水位 | 含义 | 谁能改 |
|---|---|---|
| `serverMetaSeq` | 宿主元数据水位 | 宿主 |
| `archiveCoveredSeq` | 中继归档覆盖到哪 | 中继 |
| `localAckedSeq` | 手机本地确认到哪 | 手机 |

**`/v2/sync` 唯一合法的 `afterSeq` 是 `localAckedSeq`。** 三个 value class 编译期隔离，防止串用。

### 6.4 协议帧（`packages/core/src/mux.ts`）

| 帧 | 字段 |
|---|---|
| `HELLO` | `buildHash`, `capabilities` |
| `HELLO_ACK` | `buildHash`, `capabilities`, `workerEpoch` |
| `SESSION_PULSE` | `sessionId`, `latestSeq`, `archiveCoveredSeq`, `hostState`, `silentDurationMs`, `tunnelBufferBytes`, `workerEpoch` |
| `RECORDS` | `sessionId`, `records[]`, `hasMore`, `stopReason?` |
| `FOCUS_SESSION` | `sessionId`, `sinceSeq`, `byteBudget?` |
| `UNFOCUS` | — |
| `CMD` | `cmdId`, `op: 'prompt'|'interaction'|'queue'|'cancel'`, `payload` |
| `CMD_ACK` | `cmdId`, `ok`, `error?`, `pluginMs?` |
| `INTERACTION` | `kind: 'waterfall'|'cancel'`, `payload` |
| `ERROR` | `code`, `message` |

- `STOP_REASONS = ['BUDGET_EXHAUSTED','TAIL_COMPLETE','QUEUE_EMPTY','NOT_FOCUSED','UNKNOWN_SESSION','SHUTDOWN']`
- `HostState = 'THINKING_SILENT' | 'STREAMING' | 'IDLE'`
- 帧带 `tag: 'dsh-v2'`；`decodeMuxFrame` 对外来帧返 `undefined` —— **畸形帧不是错误**，滚动升级期必须忽略而非断连。
- `CMD_ACK` **只报时长不报时间戳**（两端时钟不可比）。

### 6.5 关键常量

**`SYNC_V2`**（`packages/core/src/records.ts`）

| 常量 | 值 | 含义 |
|---|---|---|
| `stubThreshold` | 256 KiB | 超过就外置成 blob |
| `hardCeiling` | 10 MiB | 超过就截断（不存） |
| `stubBudget` | 2 KiB | 存根本身的上限 |
| `truncateKeep` | 32 KiB | 截断时保留的首尾 |
| `pageByteBudget` | 512 KiB | 单页默认预算（请求可钳到 `[16KiB, 4MiB]`） |
| `pageRecords` | 240 | 单页记录数上限（探针用 `limit+1`） |
| `maxSessionsPerRequest` | 32 | 批量补差上限 |
| `coalesceMs` | 50 | **首帧延迟**，不是吞吐旋钮 |
| `coalesceBytes` | 32 KiB | 帧大小预算（也是 `emitRecords()` 的切帧上限） |
| `pulseMs` | 5000 | 脉搏周期 |

**`LIMITS`**（`packages/core/src/protocol.ts`）

`fragment 32KiB` / `window 256KiB` / `control 16KiB` / `headers 12KiB` / `httpQueue 32MiB` / `sendQueue 8MiB` / `controlQueue 256KiB` / `maxBody 300MiB` / `streams 64` / `pending 16` / `wsMessage 4MiB` / `wsStreams 8` / `wsHostBudget 192MiB` / `wsNodeBudget 256MiB` / `wsPingInterval 15s` / `wsMissedPongs 2`。
`MessageBridge.WINDOW = 4`。

**Android 侧**（`SyncCoordinator` / `ChatSyncActor`）

`PULSE_MS 5000` / `PULSE_MISSES 2` / `PULSE_DEAD_MS 12000` / `PULSE_SUBSTITUTE_MAX_MS 120000` / `ACK_RACE_MS 3000` / `PROMPT_ACK_RACE_MS 25000` / `TAIL_LIMIT 200` / `EARLIER_PAGE 30` / `MAX_ROUNDS 20` / `PRIORITY_FOREGROUND 100` / `PAGE_BYTE_BUDGET 512KiB`。

### 6.6 存储

**手机 Room**（`TimelineDb`，`@Database(version = 3)`）

| 表 | 主键 | 说明 |
|---|---|---|
| `timeline_records` | `(session_id, seq)` | 索引 4 个：`(session_id,seq)`、`(session_id,kind,seq)`、`(session_id,kind,has_body,seq)`、`(created_at)` |
| `session_metadata` | `session_id` | 三水位 + title/updated_at/running/blank/preview |
| `interaction_ledger` | `(session_id, interaction_id)` | 审批账本 |

**插件 SQLite**（`outbox.ts`）：`archive_outbox`（索引 `(state, seq)`）、`outbox_meta`、`archive_blobs`、`outbox_watermark`。

- `put()` 返回 `'stored' | 'duplicate' | 'full'`，**不能压成 boolean**（三态语义不同）。
- 容量只数**未确认**行（`state != 'ACKED'`）。
- 覆盖水位单独存表：ACKED 行会被 prune，靠 `MAX(seq)` 会倒退。
- 容量计算必须算 `row.bytes + blobSize`（只按记录字节挑行会让一批 3 条存根变成 27MiB 请求体）。

**中继 SQLite**（`node:sqlite` 的 `DatabaseSync`）：账号、主机、配对码、设备码、`timeline_records`、`timeline_blobs`、`archive_batches`。

### 6.7 中继路由

| 面 | 路由 |
|---|---|
| **手机 v2** | `/native/v2/{login,device-code,rotate,hosts,pair,unpair,logout,attachments}`；`/native/v2/hosts/:id/{sync,blobs/:blobId,sessions}`；`/native/v2/hosts/:id/rpc/(list|create|prompt|cancel|page|projections|modelCatalog|selectModel|rename|attachment|search|command|updateQueue)` |
| **主机** | `/control/{enroll,pair,timeline,metrics}`；WS `/api/remote.mux` |
| **运维** | `/metrics`（Bearer） |
| **已下线** | `/native/v1/*` 与 `/control/archive` → **410 `v1-gone`** |

- `eventsResult` **已删除**：留着等于给手机开绕过审批账本的后门。
- blob 端点支持 **HTTP Range**（三态：`200` 全量 / `206` 区间 / `416` 越界带 `content-range: bytes */total`），按 **UTF-8 字节**切片，一律带 `accept-ranges: bytes`。
- blob id 正则允许 `%` 并 `decodeURIComponent`（Android 的 `URLEncoder` 会把 `:` 编成 `%3A`）。
- `/native/v2/sync` 响应**手工拼 JSON 字符串**（records 原样以 fragment 拼入），不 parse 再 stringify。

---

## 7. 交互硬规则

五条从真实用户反馈提炼的硬规则，任何 UI 改动都不得违反：

1. **长度变化不跳**：文字从一行变两行时，上方内容不得位移。
2. **流式显示尾部**：单行折叠栏里必须看到**最新**的字，不是开头（用 `takeLast` + 足够小的额度）。
3. **键盘顶起最新条**：键盘由无到有的边沿主动贴尾。
4. **上翻即停跟随**：用户上翻后不再自动拽回底部，给「回到最新」入口。
5. **配色只走主题 token**（`Ds.*`），不引入绿色，不照搬参考稿里的非主题色。

---

## 8. 验收标准

### 8.1 自动化基线

| 套件 | 数量 | 命令 |
|---|---|---|
| TS | **262**（261 pass / 1 skip） | `npm run check`（Node **24**） |
| Android JVM | **539** | `cd android && ./gradlew :app:testDebugUnitTest`（JDK 17 + `ANDROID_HOME`） |

> 构建环境硬约束：Node 必须 24（22 会让 `npm run check` 报 `argon2` 导出缺失）；Gradle 必须 JDK 17（系统 JDK 26 会让 Gradle 8.13 报 major version 70）；`./gradlew … | tail` 会吞退出码，必须 `set -o pipefail`。

### 8.2 端到端验收场景

| # | 场景 | 判据 |
|---|---|---|
| 1 | 冷启动进列表 | 不白屏，先出缓存，≤5s 收敛 |
| 2 | 扫码配齐 | 一次扫码完成登录 + 配对，无需再输入 |
| 3 | 发消息 | 发出到上屏 <5s，正文逐字增长 |
| 4 | 弱网发消息 | 断网时发出 → 进队列，恢复后送达 |
| 5 | 审批卡片 | 只显示本会话；点过一次不再复活 |
| 6 | 排队卡片 | 三条动作都生效，失败后刷新 |
| 7 | 任务胶囊 | `todo/write` 后立即变 |
| 8 | 电脑关机 | 列表可读、历史可翻、搜索可用 |
| 9 | 停在列表页 | 新消息 ≤10s 自动刷出预览 |
| 10 | 半死连接 | 12s 内自愈，不永久卡「深度思考」 |
| 11 | 大记录 | >256KiB 走 blob，手机能翻过去；>10MiB 显示截断错误行 |
| 12 | 更新 | 检查 → 续传下载 → 安装 → 版本真的变 |
| 13 | 插件面板 | 侧栏可开、能存设置、能出二维码、防休眠断言可见 |
| 14 | 多设备 | 同一台手机重登，主机授权不丢 |

### 8.3 验收记录

历史验收：第二轮 0.2.24 / vc 25 在云端真环境、**全程无假宿主**完成，裁决 Pass；原验收记录文档已删除，结论并入本文 §8。

---

## 9. 明确不做与已推翻的判断

### 9.1 不做

- **不做事件白名单**。名单三次出错、每次只追加一个名字、按构造不可能补全（`todo/write`、`agent/inbox/spliced` 都踩过）。现在未知事件一律 `META_PATCH` 原样透传。
- **不做 SSE 为主通道**。v2 只留一条 WSS + HTTPS；SSE 仅作变更信号。
- **不在中继解析业务事件**。
- **不用时间戳做任何 id**（卡片、批次、消息）。
- **不在手机上全量重放**（`since=0`）——除非用户手动清数据。
- **不引入 Electron API**（宿主是纯 Node 模式）。

### 9.2 被实测推翻的设计（写新方案前必读）

| 原判断 | 现实 | 证据 |
|---|---|---|
| 「隧道 credit stall 是断流主因」 | **证伪** | stall 发生时 `buffered=0KiB`，且现场最常见的 stall 是永远不恢复 |
| 「512KiB 缓冲溢出即丢帧 + 控制帧」（`STREAM_OVERFLOW_FALLBACK`） | **从未实现** | `STREAM_OVERFLOW_FALLBACK` / `overflowBytes` / `isOverflow` 全仓零命中；真实背压只有隧道信用窗口 256KiB/流 + pump 停发 |
| 中继用 `better-sqlite3` | 用的是 `node:sqlite` 的 `DatabaseSync` | `packages/relay/src/store.ts:1` |
| 插件 Worker 用 `worker_threads` | 用 `fork()` | `supervisor.ts:152`；线程与宿主共享堆与 GC |
| `coalesceBytes = 16KiB` | **32KiB** | `records.ts` |
| Room `version = 2` | **version = 3** | `TimelineDb.kt:375` |
| 「打字机用次线性反馈 `pending^0.7/8`」 | 稳态积压随输入速率线性增长、永不收敛（1000 字/秒落后 1.0s，3750 字/秒落后 1.8s） | 改成恒定 ~170ms 的追赶 |
| 「`coalesceMs` 是吞吐旋钮，调到 100ms 能省带宽」 | 它是**首帧延迟**，抬高只会让流式正文多等一帧 | `STREAM_DELTA` 也走同一条 `batch()` |
| 「`hasMore` 用 `limit+1` 探针判断空页」 | 方向看反了 | 见 `sync-cursor.test.ts` 契约 |
| 「`distinctBy` 去重同槽消息」 | 会保留同槽多条 = 引入 bug | |
| 「`+=` 拼字符串是 O(n²)」 | Kotlin 本就编译成 StringBuilder | |
| 「`advance()` 用记录列表推游标」 | 已用 `records.maxOf { seq }` | |
| 「给中继每一帧盖时间戳做 `T2_RELAY`」 | 中继按设计不解析业务帧；2 核机上 `JSON.parse` 每帧不可接受 | `MessageTrace` 只有 5 跳 |

### 9.3 已随 v1 下线作废的历史判断

`docs/review.md`（2026-10-26 第一版评审）的多数条目随 v1 面整体 410 作废，包括「三条物理长连接三套重试器」「digest + snippet 双预览」「/sync 逐行解密」。保留下来仍有价值的只有健康机制清单：幂等写、按 seq 有序、信用分片、SSE 慢消费者丢新不丢旧、连接池 probe、三时钟、凭证安全、`/metrics` 令牌闭环。

---

## 10. 遗留项与后续

### 10.1 已知取舍（不是 bug）

- **列表页不轮询**，只靠 SSE 信号。SSE 断线期间的变更靠重连后一次增量同步补齐——用户已明确接受这个成本。
- **`SortMode.RECENT` 枚举存在但无使用点**。要么接上、要么删掉，别当成已实现功能。
- **每会话 2000 条上限**的全表 trim 在 2 核机上实测 0.5~1.3ms（走覆盖索引），不是性能问题。

### 10.2 待裁定 / 待做

- **方案 A：变更帧带 v2 正文**（省一次 `/sync` 往返）——**部分落地**：`changes.ts` 的 `Change.records?` / `truncated?` 与 `server.ts` 的 `CHANGE_BODY_BUDGET`（16KB）已实现，SSE 变更帧可带正文；但 `/control/timeline` 的归档扇出仍不带 records，手机自行决定拉哪段。
- **真机验收**：HANDOFF「三、任务」仍有若干条目要真机（测试真机 `YOUR_DEVICE_SERIAL`）。
- **未完成事项**集中记在 `HANDOFF.md`「三、任务」，本文不维护待办清单。

### 10.3 文档维护约定

- 新坑写进 `docs/pitfalls.md`（**不要写进 `AGENTS.md`**：那份有 65536 字节指令预算，坑表体积远超它，DSH 会截掉尾部）。
- 本文档的常量表改动后，必须同步三端代码与对应测试；`SYNC_V2.coalesceBytes` 还被 `tests/control-v2.test.ts` 的隧道配额护栏盯着。
- `HANDOFF.md` 的「细节在哪」表由 `~/.agents/skills/agent-handoff/check.mjs` 校验：文件必须存在、章节标题子串匹配（写「全文」只验文件）；「一、现在」≤400 字是软警告。
