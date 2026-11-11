# 踩过的坑（全表）

`AGENTS.md` 的指令预算是 65536 字节，放不下全表（2026-11-02 曾把最新一条直接截断）。
所以全表在这里，`AGENTS.md` 只留「指针 + 最近几条」。**新坑先写这里，再在 `AGENTS.md` 里加一行指针或短条目。**
**编号 1~94 保持稳定**：已过时的条目压成一行「已过时」注记而不删号，因为源码注释里仍有 `坑N` 引用。

## 全表

**构建与环境**
1. 命名单一事实源是 `packages/core/src/naming.ts` + `android/gradle.properties`，`scripts/check-naming.mjs` 会校验 package.json / compose / cordis.patch 是否同步。改名前先看它。
2. `settings.gradle` 用 `RepositoriesMode.PREFER_SETTINGS`：机器级 `~/.gradle/init.gradle` 会往项目注入镜像仓库，`FAIL_ON_PROJECT_REPOS` 会直接构建失败。
3. `./gradlew … | tail` 会吞退出码，必须 `set -o pipefail`，否则失败也显示 exit 0。
4. **本地联调的 TLS 是「根 CA + 叶子」两段式，改一张必须重生成另一张**：debug 变体独有 `network_security_config` 把 `res/raw/test_ca.pem` 钉成信任锚，而 `scripts/local-e2e.ts` 优先用 `artifacts/fixture-cert.pem` + `fixture-key.pem` 当服务端证书（找不到才回落到 `android-fixture.p12`）。两边的公钥必须来自同一次生成，否则 debug 包连本地中继必然 `Trust anchor for certification path not found`。物料一律用 `pwsh -NoProfile -File scripts/init-android-fixture.ps1` 重生成（它同时写 CA、叶子、PEM 私钥、p12 和 `res/raw/test_ca.pem`），**不要手工 openssl 拼**；`res/raw/test_ca.pem` 入库、`artifacts/` 整体 gitignore。2026-10-29 就是因为只换了根 CA 没重生成叶子，模拟器上一路 TLS 失败。
   另注：`X509EnhancedKeyUsageExtension` 必须真塞 serverAuth 的 OID（`1.3.6.1.5.5.7.3.1`），传空集合会生成 `Extended Key Usage: 0.`，Android 照样拒。
5. 中继的**主机寻址有两套**，由 `RELAY_HOST_ROUTING`（缺省 `auto`）决定：`subdomain` 按 `Host` 头认 `<hostId>.域名`（`curl 127.0.0.1:18080` 不带 `Host: relay.local.test` 一律 404）；`path` 认 URL 前缀 `/h/<hostId>/…`，**根本不看 Host**——`auto` 在 `RELAY_BASE_URL` 的主机名是 IP 字面量时选 `path`（`1.2.3.4` 挂不出子域，裸 IP 部署只能这样）。排查「主机打不开」先确认自己在哪种模式：路径模式下漏写 `/h/<id>` 会被当成账户面，子域模式下用 IP 访问会 404。

**网络 / 更新**
6. 静态更新域名走 Cloudflare，DNS 同时下发 A 与 AAAA。OkHttp `retryOnConnectionFailure(false)` 在无 IPv6 出口的网络里**不会回退 IPv4，直接失败**。所以幂等 GET（`fetch`/下载）必须用允许跨路由重试的客户端，并钉 HTTP/1.1；下载还要 `Range` 续传 + sha256 校验 + 清残包。
7. **ColorOS 会截走安装 Intent**：`ACTION_VIEW + content://` 的读授权只跟着「被解析到的那个包」，转手给 `com.oplus.appdetail` 后拿不到权限，安装**静默失败、版本不变**。改 `PackageInstaller` 会话；`ApkProvider` 只放行 `getExternalCacheDir()/updates/`。
8. Android 的「第三方应用」「涉敏感权限」两道拦截是系统强制的，App 侧无法跳过。「点检查更新没反应」多半是清单请求要几秒，不是 bug。

**数据 / 渲染**
9. 首页首行预览**没有任何专用通道**：宿主 `session/list` 不返回 snippet，中继也不再转发它。预览一律由 App 从本地落盘的助手正文现算（`data/Preview.kt`，取最后一条 `role==ASSISTANT && panelId==""` 消息的第一行非空文本；用户输入/思考/工具调用全跳过）。别去 `/sync` 里找 snippet。
10. **（已过时）** 面板 `order = panelOrder(seq)` 的浮点偏移是 v1 `timelineOf` 口径；v2 由 `Projector` 的槽位硬编码顺序取代（`docs/PRD.md` §6）。
11. **（已过时）** `dropSupersededSameSeq` 与「面板别走 `onMessage`」都是 v1 `SessionAccumulator` 的规则；该类已删（历史重构中移除），v2 用 `Projector` 纯函数投影。
12. **（已过时，源码注释仍引此号）** 原判据「`SessionAccumulator.frame()` 分流看 `changed.panelId.isNotEmpty()`，不能比 `changed === process`」只适用于已删除的 v1 累加器；v2 的等价要求是「未知事件一律 `META_PATCH` 原样透传」（坑 30）。
13. 回归测试要**复刻真宿主的事件序列**（真宿主不一定发 `step/end`）；带了 `step/end` 面板会提前 `running=false`，bug 测不出来、用例假绿。这条口径在 v2 投影器测试里同样成立。
14. 新建会话是**草稿**（`id == ""`）：`markSessionRead`、prefs key、`remember(session.id)` 都要吃空串；创建失败要把原话作为失败回声留在时间线里好重发。
15. **（已过时）** `SessionCache` 的 `kotlinx.serialization` 深拷贝与「给 `Model.*` 加字段必须给默认值」是 v1 prefs 缓存口径；v2 正文走 Room，见 `TimelineDb.kt`。

**测试工具**
16. `npm install --no-save a b c` 每次只保留本次列出的包，**要一次装齐**，否则上次装的会被删。
17. 真机是 release 包（非 debuggable）→ `run-as` 不可用；`uiautomator dump` 在 ColorOS 上也时好时坏。取证优先 mobilewright 的 `snapshot`/`logs`。
18. 假宿主（`scripts/smoke-fake-host.ts`）必须按会话分桶（`sessions` Map），否则新建的会话会被写死的冒烟会话覆盖，看起来像「消息发丢了」。
19. 假宿主推实时增量要**按 turn 过滤**，不能写死 `slice(-N)`：一轮的条数随 `STEPS_PER_TURN` 变（现在 18 条），截错了 `turn/start`/`user/message` 就推不到，手机上表现为助手正文跑到用户气泡上方、面板只剩半截。
20. **（已过时）** `turn/end` 交回打了 `footnote` 的面板（`return footnoted ?: last`）是 v1 累加器口径；v2 的收尾文案由 `Projector` / `foldSummary` 决定。
21. 验收 UI 细节时**先看落盘字段，别盯着截图猜**：v2 看 Room `timeline_records` 的 `running` / `footnote` / `order`，比看截图可靠。
22. `$events` 是 **app 级一条流**，`waterfall` 帧里的 `agentId` 就是会话 id（宿主侧 `sessionId: agent.id`）。卡片必须过 `interactionBelongs(agentId, sessionId)`：不过滤会把别的会话的审批弹进当前聊天页，点「允许一次」等于给别的会话放权。`agentId` 或 `sessionId` 为空时放行（字段缺失/草稿页）。
23. 卡片通知的 id **必须是 `eventId`**，不能是时间戳。宿主对未结算的 waterfall 会在每次 `$events` 新连接时重投，时间戳做 id 会让同一张卡每次多堆一条通知。
24. 「一直在弹审批」还有一个非 bug 的常见来源：会话预设是 `workspace-write` + `approval/policy=ask`，第一条要写出工作区的命令（Gradle 读写 `~/.gradle`）就挂起等审批，**agent 整轮停在那里**。手机端点权限胶囊切「完全权限」即可（等价桌面端 `/permission danger-full-access`）。查证看 session 日志里 `approval/asked` 到 `approval/decided` 的间隔。
25. **`$events` 只在开机时建一条，别为了「验真」重开它**。宿主 `startRemoteEvent` 末尾会遍历 `pendingRemoteEvents` 向新 client 逐个重投，所以每进一次会话就 `resyncInteractions()` 的效果是：没结算的审批跟着重来一次，用户点一次卡就复活一次——这才是「一直在弹审批」的真身（2026-10-28 用户报「开了完全权限 0.2.13 依旧一直弹」）。待答卡片改由 `InteractionLedger`（`ui/InteractionLedger.kt`，纯 Kotlin 可单测）记账：`$events` 收到就 `note`，进会话用 `replayInteractions` 回放，点过的 `markAnswering` 静默后续重投，回答失败 `forget` 让它能回来，宿主 cancel 帧 `cancel` 清账。回答也**不需要**先重开流：宿主收到 `kind:"result"` 当场结算。

**UI 细节**
26. `thinkingPreview(streaming = true)` 取的是尾部（`"…" + takeLast(limit-1)`），而 `Text` 是 `maxLines = 1` + `TextOverflow.Ellipsis`，放不下就从**末尾**省略 —— 可见的仍是窗口的**开头**，每个新字只把窗口整体挪一位，看着「刷一下就再没变过」。所以思考预览的额度必须按**这一行实际能放多少字**算（`inlineThinkingBudget(usedWidth, prefixPx, charPx)`，`INLINE_THINKING_LIMIT`=12 只是量不到宽度时的兜底下限）。折叠行改成两行之后预览独占第二行、`prefixPx` 给 0，额度宽松多了；但它仍是单行 `maxLines = 1`，规矩不变。换额度后要用 `snapshot` 看这一行的**文本**是否在变（OCR 截图看不出细节）。
27. **「一条消息上下各冒一次『正在分析』」= 面板锚定漏了晚到的用户消息**。宿主 `turn/start` 比 `user/message` 早一个 seq：`turn/start` 就建好面板，紧接着 `step/start` 把 `processStep` 绑上，等 `user/message` 到达时 `anchorPanelAfter` 里那句「已绑定 step 就早退」直接把锚定跳过 —— 首块面板留在气泡**上方**，后面几步的面板却在下方，一轮渲染成上下两截。修法是双保险：`panelOrder(seq)` 保证面板 `order` 不早于「最近一条用户消息 + 0.25」（`lastUserSeq` 由 `user/message` 与 `seed(cached)` 维护），`anchorPanelAfter` 删掉那个早退。回归测试要按「`turn/start` → `step/start` → `tool/call` → `user/message` → `assistant/message`」的真实顺序造事件，光发 `turn/start` 再发 `user/message` 是测不出来的。
28. 折叠行/抽屉要「看得出此刻在干什么」，判据都从 `ChatViewModel` 的纯函数取：`currentActivity(message)` 倒着找最后一条步骤（思考→`思考`、技能→`技能`+技能名、命令→`执行命令`+命令、其它工具→工具名+参数），它同时把 `ToolAction.subtitle`（模型写的 `description`）带进 `ProcessActivity`，折叠行第一行由 `foldHead(title, activity, running)` 拼、第二行由 `foldPreview(...)` 给；`resolvePanel(rows, panelId, turn)` 让抽屉每帧拿**活对象**——直接 `remember` 住 `Model.Message` 快照的话，抽屉打开后内容就冻住了，流式思考与菊花都不会动。
    另：`foldHead` 的 `running` 参数只管纯思考那一支（落定后不挂「思考」标签），工具/技能落定后**照样**报名字与小标题——桌面端已完成的调用也一直挂着摘要，回看时才知道这一步想干什么。

**网络 / 隧道**
29. **手机端「请求失败（502）」几乎都是插件侧复用死连接**：宿主 9749 回 `keep-alive: timeout=5`，但池里的 socket 要等 FIN 真被读到才算失效；桌面宿主事件循环一卡（它和 UI 共用线程），下一个请求就挑到对端已关的 socket → `ECONNRESET` → `client.ts` 当场 `stream-reject 502 upstream-error`（**不重试**），中继把这个 reason 字面量当响应体写回去，nginx 日志里就是 `502 14`。三处 loopback 请求（`client.ts` 的 `http-open` 与 `exchangeCookie`、`archiver.ts` 的 `listSessions`）一律 `agent: false`——loopback 建连实测 0.7ms vs 复用 0.21ms，0.5ms 换掉一整类偶发故障。别加重试：请求体是 `startReceive` 流式写进 req 的，重放要缓冲整个 body。诊断口诀：`502 14` → `upstream-error`（14 字节＝reason 原文）；`502 18` → `upstream-cancelled`/`duplicate-response`；`504` 才是宿主不答。插件日志里现在有 `loopback <METHOD> <path> failed: <code>`，先看这个码再猜。
30. **~~新事件要过中继的 `PANEL_EVENTS` 白名单~~ → v2 已把白名单整个删掉**。历史上 `filter.ts` 对非白名单事件只转发 `type/seq`、`data` 一律剥掉，`todo/write` 与 `agent/inbox/spliced` 都因此踩过（App 侧解析、假宿主、单测全对，真链路上永远显示不出来，diag 里只有 `omitted`）；那份名单三次出错、每次只补一个名字，**按构造不可能补全**。现在 `filterEvent()` 只做 content 级别的转换（丢 reasoning part、截断 tool 参数/输出），未知事件一律原样透传，`classify()` 把它归成 `META_PATCH` 存进时间线。**别再把白名单加回来**：`tests/filter.test.ts` 里有一个故意编的未来事件名（`brand/new/event-from-a-later-host`）在钉这条。
31. **改 tgz 内容但版本号不变时，`dsh plugin --profile desktop add <tgz>` 不会重新解包**：pnpm（`nodeLinker: hoisted`）只把 lockfile 的 integrity 换掉，`node_modules/dsh-mobile-sh` 仍是旧目录——结果是「明明装了新包，跑的还是旧代码」。可靠做法是 `rm -rf node_modules/dsh-mobile-sh` 后 `tar -xzf <tgz> -C node_modules && mv node_modules/package node_modules/dsh-mobile-sh`，再 `sha256sum dist/index.js` 与 `packages/plugin/dist/index.js` 对齐，最后看 `/tmp/mobile-sh-hotreload.log` 有没有 `[10] 完成，重新注册 1 个 fiber` + `ready`。中继侧同理：`packages/*/dist/` 在 `.gitignore` 里、云端 `/opt/dsh-mobile-sh` 不是 git 仓库，部署就是 scp `cli.js` + `NOTICE.txt` 再 `systemctl restart dsh-mobile-sh.service`，落地后用 `grep -c 'todo/write' cli.js` 这类特征串确认真的换了（旧包先 `cp -p` 成 `cli.js.bak-<时间戳>`）。

32. **（已过时）** v1 `RelayApi.startWatchdog()` 的 45s 无帧判死已由 v2 `SESSION_PULSE` + `SyncCoordinator.halfDead()`（12s）取代，见坑 42 / 54 / 56。

**配对 / 设备身份**
33. **同一台手机重新扫码登录必须复用同一条 `devices` 行**。`grants` 挂在 `device_id` 上，所以「每兑换一次设备码就 INSERT 一条新 devices」会让这台手机之前配好的主机全部变 `authorized=false`——用户看到的是「再配一台电脑之后，原来的电脑在下拉框里成了『未配对』、设备管理里也没了」。`store.consumeDeviceCode` 与 `store.login` 现在同一口径：按 `label` 查未吊销的同名设备行，有就复用。手机端别自报花名（深链扫码曾传死字符串 `手机扫码`、手动登录传 `Build.MODEL`，同一台手机因此变成两台设备），一律传 `null` 由网关填机型。中继的 `online` 同理要有单一事实源：`Proxy.online()`＝「`tunnel.lastSeen` 在 45s 内」（心跳 30s 一次），别看 `WebSocket` 开没开——电脑关机后 TCP 半开，`close` 可能几十秒才来；App 侧 `hostsForDisk()` 落盘前把 `online` 清成 false，绿点只认中继这次答复。
34. **隧道「活着但一帧不出」有两个完全不同的成因，`/metrics` 里能分开读**：看 `hostMetrics[].tunnel`。
   - `bufferedPeakBytes >= 1 MiB` → 插件侧 `Tunnel.pump()` 停过泵（应用层发送缓冲满 1MB 就 `setTimeout(run,5)` 空转），**本端发不出去**：上行不足 / 中继吸不动。
   - `bufferedPeakBytes` 低但 `stalls` 高 → 发送端在等对端 `flow` 信用超过 10s，**对端不消费**：手机侧接收慢，或下游写不动导致收不回信用（`Channel.data()` 里 `await this.wait('credit')` 卡住，socket 却完全健康）。
   - 两者都是窗口增量（`snapshot()` 报过即清零），所以 `0/0` 不能读成「没堵过」，要连着几个窗口看。插件日志同时会有 `tunnel credit stall <n>s stream=<id> pending=<KiB> buffered=<KiB>`。
   - `stall` 探针在超时那一刻就 emit、**不等恢复**——现场最常见的正是永远不恢复。

**插件运行环境**
35. **DSH 的插件宿主是纯 Node 模式，拿不到任何 Electron API**。`@deepseek-ai/dsh-desktop-host` 那个进程（`ps -Eww -p <pid> | grep ELECTRON_RUN_AS_NODE` 可见 `=1`）是以 `ELECTRON_RUN_AS_NODE=1` 起来的：`process.versions.electron` 有值（44.7.0）**不代表**能用 Electron 模块——该模式下 Electron 不注入 `'electron'` 内置模块，`require('electron')` 只会去 node_modules 找一个同名的包然后失败，`globalThis.require('electron')` 返回 null。判据：插件日志里 `powerSaveBlocker 不可用（electron=…）：ELECTRON_RUN_AS_NODE=1 … | createRequire: Cannot find module 'electron'`。
   所以「防休眠」这类系统能力必须走**系统命令兜底**（`packages/plugin/src/power.ts` 的 `createSpawnBlocker`）：macOS `caffeinate -i -w <宿主pid>`、Linux `systemd-inhibit --what=idle`。`-w <pid>` 把子进程绑在宿主生命周期上 —— `kill -9` 宿主时它自己就退（已实测），这比在任何语言里写 release 都可靠。新加 Electron API 之前先确认这个宿主到底给不给。
36. **折叠行「已处理 N 个操作」与抽屉点开后的条数必须同源**。老实现三处各数一套：折叠行数本组的 `Step.TOOL`、抽屉标题数整块 `actions.size`、抽屉逐条渲染又要求 `step.actionIndex in actions.indices`——一轮里多块面板合并（`mergePanels`）时越界的步骤会被置 `actionIndex = -1`，这些「幽灵步骤」在折叠行里被计数、在抽屉里却不渲染，用户看到的就是「说 8 个、点开 6 个」。现在统一走 `Model.Message.toolCount()`（＝`steps.count { kind == TOOL && actionIndex in actions.indices }`），折叠行 / 抽屉标题 / 面板页脚三处都取它。另外 `actions` 与 TOOL `steps` 理论上一直成对（`tool/call` 与 `tool-call-delta` 两条路径都同时 add），所以别在别处单独删一个。
37. **折叠行是「整轮一行」，操作数任何时候都报**。老写法拼 `title + thinkingPreview` 配 `maxLines = 2` 会折两行；更早的 `groupSteps` 还要求「连续 ≥2 条才折」，于是单条思考自己走 `ThinkingStepRow`，一轮里就会出现「已处理 N 个操作」紧挨着「深度思考」、或多个并排（2026-10-29 用户实测反馈）。现在的规矩：①`groupSteps` 把一轮里**所有非 TEXT 步骤收进同一个 `StepGroup.Fold`**，旁白（TEXT）各自一行，`PROCESS_FOLD_MIN` 已删；②**运行中也报数**——`foldSummary` 不再按 `running` 返回空串，运行中拼成 `已处理 N 个操作 · 思考：<尾部预览>`，落定后是 `已处理 N 个操作 · 用时 X 秒`；一个工具都还没调时运行中报「正在分析」、落定后报「已完成分析」，**不编「已处理 0 个操作」这种假数字**（2026-10-29 用户第二次反馈「『已处理X个操作』也没有出现」，此前那版运行中返回空串，真机上只剩一段无头无尾的思考正文）；③`took` 只给收尾那组（`message.footnote`）；④**有活思考时尾部不再挂操作名**（`running && !live && activity != null`）——`思考：…` 已经把话说了，再挂一个工具名既挤爆一行，也让用户疑惑「明明在思考，右边为什么写 ops_bash」；⑤「这一轮是否还在跑」必须用 `foldLive(message, sessionRunning)` 判——宿主每步都发 `step/end`，`message.running` 走完一步就 false，但整轮可能还在跑，只看它会在中途误报「已处理 N 个操作」。改文案记得同步 `UiLogicTest` 的 `foldSummary*`/`groupSteps*`/`foldLive*` 与 `ModelTest` 的 `processTitle` 断言。
**限流**
38. **这是自托管中继，限流分两档：资源类放宽，凭据类不动**。`server.ts` 顶部的 `RATE` 表是唯一事实源。按公网多租户的量级去卡 `/sync`、`/changes`、`/archive`、`/metrics` 只会罚到自己：2026-10-29 实测冷启动四十多个会话补页就把 `/sync` 的 30 次/分钟打满，一路 429 拖三分钟，而那些请求全是正常读。现在 `sync` 600/分、`changes` 120/分、`archive` 600/分、`metrics` 60/10s、`handoff`/`unpair` 120/分；`login`/`device-code`/`enroll`/`pair` **保持原值**——那几档防的是猜（8 位设备码、密码、注册口令），放开等于送人爆破窗口。判断「该不该放宽」的标准不是「这个数字大不大」，而是「撞上它的会不会是正常用法」。客户端 `SyncRateGate` 相应放宽到 120（仍留五倍余量），`SYNC_CONCURRENCY` 8、`MAX_BATCH_ROUNDS` 20。回归用例：`tests/sync-cursor.test.ts` 的「80 次连续 /sync 不得出现任何 429」。

**排队消息**
39. **排队消息只有一个变更接口，没有「列队列」的 RPC**。宿主 `session/projections` 的 `values.inbox` 是 `{"next-turn":[…],"next-step":[…]}` 两桶（条目就是完整 message 对象，`id` 是随机 UUID，不是 `requestId`）；三个动作全走 `session/updateQueue{sessionId,itemId,action:{kind:"edit"|"remove"|"steer"}}`。`steer` 只对 `next-turn` 且 `agent.status==="running"` 有效，否则 `session/steer-unavailable`。实时增量是 `agent/inbox/spliced{target,start,removedCount?,inserted}`，语义 `toSpliced`——v1 时代它必须登记进 `PANEL_EVENTS` 才不被抹掉（见坑 30，v2 已废白名单）。`edit` 只接受纯文本内容（带图条目编辑后图片会丢，宿主侧直接拒绝非 text 的 part）。App 侧 `applyInboxSplice` 是 `ChatViewModel.kt` 的**顶层纯函数**（`ChatViewModel` 构造要 `AppViewModel`，放成员里就没法单测），复刻宿主 `toSpliced` 的下标钳位语义。

**v2 重构**
40. **归档队列必须是磁盘上的三态，不是内存数组**。v1 的 `archiver.ts` 用内存 `queue` + `maxQueue` 上限，满了 `splice` 丢最旧——丢掉的 seq 再也不会上传，中继那边永久缺一段，而手机只会看到「历史莫名少了几条」。v2 改成 `packages/plugin/src/outbox.ts`：`node:sqlite` 的 `DatabaseSync`（**宿主是 Node 24.21，`node:sqlite` 可用，不必引 better-sqlite3 这类原生依赖**），表 `archive_outbox(session_id, seq, state, batch_id, payload, bytes, attempts, created_at)` 三态 `PENDING → IN_FLIGHT(batchId) → ACKED`。四条容易写错的规矩：①`put()` 返回 `'stored' | 'duplicate' | 'full'`，**不能压成 boolean**——`duplicate` 是成功（快照重投同一事件）、`full` 才是要修的，混在一起记录就无声消失了；②容量只数**未确认**行（`state != 'ACKED'`），让 ACKED 也占额度的话「满了 → ack → 还是满」永远腾不出来，之后每条都被永久拒绝；③覆盖水位单独存 `outbox_watermark` 表，不能靠 `MAX(seq) WHERE state='ACKED'`——ACKED 行会被 prune 掉，水位会**倒退**，手机据此误判缺口不可恢复然后重拉已有的历史；④outbox 满时记录进 `hub` 的内存 backlog（上限 5000），**`pusher` 成功 ack 的那一刻回调 `onDrained() → hub.retryPending()`** 重试——重新 follow / 重发快照都修不了，因为 `hub` 按 `(sessionId, seq)` 去重，宿主重放的是同一批 seq。启动时先 `outbox.reset()`（IN_FLIGHT → PENDING），跨重启至少投一次，靠中继 `archive_batches` + `INSERT OR IGNORE` 幂等去重。
41. **`CONTROL_PATH` 必须在 `upgrade-path` 白名单检查之前拦下，且中继只看路由**。手机 v2 的 WS 连的是 `/native/v2/hosts/:id/control`，中继在 `server.on('upgrade')` 里把它**改写成** `CONTROL_PATH`（`/__dsh/v2/control`）后走同一条隧道——中继不理解 pulse、不理解 record，保持纯路由。到插件侧，`client.ts` 的 `open()` 里 `safePath()` 之后、`(this.options.upgradePaths ?? ['/api/remote.mux']).includes(...)` 那一行**之前**就要 `return`，把 channel 交给 `ControlFactory` 造出的 `ControlSession`：那些字节从不进隧道，也不该进 `admission` 预算。放在白名单检查之后就永远 404——默认 `upgradePaths` 只有 `/api/remote.mux`。另外 `Channel` 不是 `WebSocket`，`ControlSession` 里用 `ChannelSocket` 适配（`on/off/once/send/push/close/terminate/ping`）复用 `MessageBridge`，别手写第二套流控。
42. **「socket 连着但一个字都不来」在 v2 由 SESSION_PULSE 兜，判死阈值 12 秒，恢复只能走增量**。插件 `ControlSession` 对每个 focused 会话**每 5 秒无条件发一帧** `SESSION_PULSE{latestSeq, archiveCoveredSeq, hostState, silentDurationMs, tunnelBufferBytes, workerEpoch}`——不按「有事发生」触发，因为 v1 卡 16 分钟正是「没事发生就静默」加上看门狗只认 socket 状态。手机侧 `SyncCoordinator.PULSE_DEAD_MS = 12_000`（不是 2×5s，脉搏要过隧道与一次系统调度，压到 10 秒会把正常抖动判死；也不能松到 30 秒，那就是 v1）。判定之后**只做两件事**：`POST /native/v2/hosts/:id/sync` 从 `local_acked_seq` 拉增量 + 在新 WS 上重发 `FOCUS_SESSION(sinceSeq=local_acked_seq)` 让插件回放内存尾部；**绝不 `since=0` 全量重放**（v1 看门狗的修法，v2 里那等于把整段历史再走一遍，而且 `hasMore` 会一直为真，永远追不平）。`hostState === 'THINKING_SILENT'` 时界面显示「正在深度思考 (Ns)...」而不是重连——长推理没输出不是故障。

43. **v2 有四条「只在真实链路上才暴露」的缺陷，都源于单测的对位假设不一致**：①`store.archiveTimeline` 曾对 `record` 再 `JSON.stringify` 一次——插件 `outbox.put()` 存的是**已序列化好的文本**，pusher 原样拼进请求体，中继再包一层就成了「JSON 字符串字面量」，`/v2/sync` 下发后手机 `recordOf()` 取不到 `seq`，整页渲染成空（单测两边传的都是对象，所以绿的）；判据 `typeof e.record==='string' ? e.record : JSON.stringify(e.record)`，blob 侧同理。②blob 路由正则曾限定 `[A-Za-z0-9_:.-]`，而 Android 的 `URLEncoder.encode` 把 blobId 里的 `:` 编成 `%3A` → 真机永远 404 `blob-missing`（测试手工拼 URL 所以绿的）；现放宽到 `[A-Za-z0-9_:.%~-]{1,600}` + `decodeURIComponent`。③`timeline_blobs.byte_size` 是 `NOT NULL`，INSERT 少传一列时 `INSERT OR IGNORE` **静默跳过整行**：archive 行落库、blob 行没落，存根永远补不上——SQLite 的 `OR IGNORE` 连 NOT NULL 违反都吞，别指望它报错。④`store.backfillTimeline()` 起初把 v1 的 `{event:{…}}` 原文直接抄进 `timeline_records`，而 v1 归档行是**事件**、v2 记录是**信封**（`{sessionId,seq,prevSeq,kind,payload}`）——抄过去就下发 240 条没有 `seq`/`kind` 的行，手机逐条丢弃，14 天历史看着是空的；现在回填**重新包信封**（内层 event 仍逐字节原样放进 `payload`）。
   这四条由 `tests/integration-v2.test.ts`（真 `Relay` + 真隧道 + 插件四件套）与 `tests/store.test.ts` 的 backfill 用例钉着——**跨端改动必须先跑它**。判据：拿到一条下发记录，先看它有没有 `seq`/`kind`，再看 `payload` 里是不是原事件。

44. **控制流的实时记录必须攒批，而且脉搏前必须先 flush**。`control.ts` 的 `batch()` 把聚焦会话的实时记录按 `SYNC_V2.coalesceMs`(50ms)/`coalesceBytes`(16KiB) 合成一帧：一轮对话约 18 条，逐条成帧就是 18 个 `ws-message`（每个还带 12 字节隧道头 + 一条控制帧）。两个配套判据：①定时器是**最大延迟**不是 debounce（连续出字的回合否则永远不 flush）；②`emitPulses()` 里先 `flushRecords()` 再发脉搏，否则 `latestSeq` 会报一个手机还没见到的值，手机据此判定「已经追平」而跳过缺口。`unfocus` 后队列里的记录要丢弃——发给已经离开的会话只会让下次聚焦重画旧行。

45. **控制流的写操作也必须走 `loopbackPost`，不能用 Node 的全局 `fetch`**。`control.ts` 的 `rpc()` 起初直接 `fetch(...)`，注释里还写着「同样的 `agent:false` 规则」——但全局 `fetch`（undici）自带连接池，`agent:false` 这句只对 `node:http` 生效，压根没传到。于是 v2 的 prompt / 审批回答 / 排队操作正好落在坑 29 那个故障模式上：宿主事件循环一卡（它和 UI 共用线程），池里的死 socket 被挑中 → `ECONNRESET` → 手机看到「请求失败」。现在生产路径走 `loopbackPost(origin, path, body, timeout, {cookie})`（唯一一处 `agent:false`），只有测试注入的 `options.fetch` 才走 `fetch`。判据：**任何打 127.0.0.1 的请求，先 `grep` 一遍它有没有绕开 `loopbackPost`**。

46. **翻历史读本地表，不读网络**。v2 的容量口径是「本地与中继 1:1，每会话 2000 条」，所以 `ChatSyncActor.loadEarlier()` 只查 `timeline_records WHERE seq < 最老那条`（`TimelineDao.earlier`），**不发任何请求、不推进水位**。三个容易写错的地方：①不能用 `OFFSET`——新记录不断写进来时同一行会被读两遍或跳过，按 `seq < beforeSeq` 才是稳定的；②`hasEarlier` 不能拿 `seq > 1` 判（中继的 2000 条滚动窗口可能已经把更早的滚掉了），要真去问一下本地表还有没有；③`ChatViewModel.onEarlier` 里那句 `hasMore = false` 是 v1 的答复语义，v2 必须改读 `actor.hasEarlier`，否则用户往上滑一次就再也翻不动。
   另：每会话 2000 条的上限由 `TimelineDao.commit` 在**同一个事务**里滚掉（`keep` 参数 + `seq NOT IN (最新 N 个)`）。别用 `created_at` 排序来裁——那是**落盘**时间，翻页补回的老记录 `created_at` 比 seq 新得多，按它删会把刚补回来的历史当成「最旧」立刻丢掉。

47. **六跳打点里没有 `T2_RELAY`，这不是漏做**。`MessageTrace` 记 `T1_SEND` / `T3_PLUGIN` / `T4_HOST` / `T5_FRAME` / `T6_ONSCREEN`。v2 的中继是**透明字节管道**，按设计从不解析业务帧（唯一例外是 `/v2/sync` 的分页读取）；要在它那里盖「收到时刻」就得 `JSON.parse` 每一帧，而那正是这次重构要根治的毛病（2 核 / 768MB 的中继被业务解析吃掉 CPU 与堆）。`T3_PLUGIN` 有值本身就证明请求穿过了中继。三条配套规矩：①插件的时刻**不报时间戳只报时长**（`CMD_ACK.pluginMs`）——两端时钟可能差几十秒，相减会得到负数或几分钟；②`pluginMs` 超 60 秒按「没报」处理（多半是宿主编译了一轮 Gradle）；③`T5` 的关联键是**「该会话第一条还没打 `T5` 的待追踪消息」**，不是 `requestId`——宿主事件里没有这个字段的回声，`RelayGateway.sendPrompt` 生成的 requestId 只能到宿主为止。
   判据：缺哪一跳就说明断在那一跳**之前**。只有 `T1` → 写操作没到插件；有 `T4` 没 `T5` → 宿主没回。审批/提问仍走旧链路，天然缺 `T3`——这就是「这句话走的是旧接口」的可观测痕迹。④只有**打到 `T6`**或被 `noteFailed` 放弃的记录才落一行 `Diag.log("trace", …)`——半截的句子写进日志会被读成「已经上屏了」。`FAILED` 刻意留在 `HOPS` 之外：它是结论不是途经点，混进跳列表会让 `missing()` 把失败记录报成「全部到齐」。

48. **归档写入的指标要用 p95，不能用均值**。`/metrics` 的 `archiveWriteMs={count,failures,samples,p95,last}` 只包 `store.archiveTimeline()`，**不包**随后那次 `timelineSummary()`（慢的 summary 不该伪装成慢的写入）。真正要抓的形状是「一批 4 秒、另外五十批 6 毫秒」——均值 84ms 完全盖住那个尖峰，而那正是 SQLite 写锁被 `prune()` 或 WAL checkpoint 按住的样子。环里留 256 个样本，长跑进程里不会缓慢泄漏。

49. **（已过时）** v1 prefs 正文与 v1 归档游标是迁移时丢弃项；`SessionCache.purgeLegacyMessageBodies()` 只做一次性清理，**旧游标刻意不迁**，v2 唯一合法 `afterSeq` 是 Room 的 `local_acked_seq`（坑 51）。

50. **worker epoch 必须由中继**比较**，只透传等于没做**。`supervisor.kill()` 先发 `{type:'stop'}` 等 `killTimeoutMs`（3 秒）再 SIGKILL；一个卡在自己事件循环里的旧子进程在这 3 秒里仍持有隧道重连定时器，而中继 v1 的语义是「后连上来的胜出」——于是换插件包之后手机对着**旧代码 + 旧 outbox 文件**说话，插件设置页却一切正常。现在 `authenticate` 帧带可选 `workerEpoch`（`supervisor` 每次 `spawn()` 时 `++`，`worker.ts` 转手交给 `HostClient`，`client.ts` 发出去），中继在 `acceptTunnel` 的 auth 阶段比较：`incoming < present.workerEpoch` → `4423 stale-epoch` 直接关，**不碰**现有 peer。老插件不发这个字段，按 0 处理，行为与以前一致。回归在 `tests/integration-v2.test.ts` 的「worker epoch: a stale generation cannot reclaim the tunnel」——它同时钉「旧的不许顶掉新的」与「新的能正常接管旧的」两向，只测前者会放过正常升级连不上的情形。

**v2 批量补拉的游标与 stopReason**
51. **批量补拉的 `afterSeq` 只能来自 Room 的 `local_acked_seq`，不能来自 prefs 里的 v1 归档游标**。2026-10-30 云端 + 模拟器实测：`batchSyncV2()` 用 `cache.archiveCursor()` 拼请求，于是每 2.5 秒发一次 `from=0`，中继照发同一页 112 条，`advance()` 把水位推到 5350 却**没写回**那个源，下一轮又读回 0 —— diag 里连续四行 `v2 24b46afa from=0 records=112 landed=5350 next=5350 hasMore=true` 之后 `v2 batch loop guard hit, backing off`，症状是「冷启动一直在同步、列表补不完」。修法：`batchSyncV2` 先 `timeline.allMeta()` 取 `localAckedSeq` 建 `roomCursors`，只在某会话**从未被 v2 同步过**时才回落到老游标（通常是 0，正好等于「从头拉」）。这正是三水位分列的第二个用处——`archiveCursor` 是 v1 的单水位，它同时被当成「元数据进度」和「内容进度」，谁动都会误伤另一个。
52. **`stopReason` 里 `UNKNOWN_SESSION` 只能表示「归档里从没有这个会话」**，不能表示「已经追平」。原来写的是 `covered <= afterSeq`，把「追平」也归成未知会话：同一批实测里 29 个**完全同步完**的会话在 diag 里被标成 `UNKNOWN_SESSION`，看起来像中继丢了归档，实际 `MAX(seq)` 与手机的 `afterSeq` 分毫不差（`763dbf11 covered=3385 afterSeq=3385`）。判据改成 `covered === 0 && afterSeq === 0`。日志里的 stopReason 是排障的第一现场，语义错一次就会把人引到错的方向查半天；`tests/store.test.ts` 的 `an already-synced session reports TAIL_COMPLETE, never UNKNOWN_SESSION` 钉住了这条。

53. **存根正文必须支持 Range 断点续传，且 416 要分两种读法**。`packages/relay/src/server.ts` 的 `parseRange(header,length)` 三态：`undefined`＝没要求（发全量）、`null`＝要求了但无法满足（回 `416` + `content-range: bytes */N`）、否则闭区间。切片一律对 **UTF-8 字节**（`Buffer.from(json,'utf8')`）而不是字符串——Range 是字节区间，按字符切出来的 `content-range` 对不上任何多字节边界。手机侧 `net/BlobPlan.kt` 是纯函数：`200` 必须 **`reset()` 后整块替换**（当 `206` 追加会拼出重复字节，界面上只像「那段内容重复了一遍」，不像 bug）；`416` + `offset>0` 是**正常收尾**（越界的唯一正常来源就是已经拿完），`416` + `offset==0` 才是 `range-rejected`；重试从**同一 offset** 起步而不是从零。`tests/integration-v2.test.ts` 用逐字节比对钉住（`Host` 头在 undici 里是禁写头会被静默丢掉，所以那段必须手写 `node:http`）。

54. **「一条脉搏都还没到」这一支看门狗没有一次性守卫，会每 12 秒自我重连**。2026-10-30 实测（模拟器 + 生产中继）：`control ws open` → 12 秒后 `control recover #N` 配 `SocketException: Socket closed` → 再 `ws open` → 再 recover，17:54:06 一路循环到 17:55:24（`#1`～`#6`），直到 17:55:31 第一条脉搏才停。**症状不是「连不上」，而是这 78 秒里发消息会落回 v1**（diag 留着 `control command prompt refused: socket=present open=false`）。根因是 `tick()` 两个判死分支**不对称**：`halfDead()` 那支有 `halfDeadDeclared` 守卫，而 `lastPulseAt <= 0` 那支只判 `now - armedAt >= PULSE_DEAD_MS`，偏偏 `recover()` 把 `armedAt` 重置成 `now`——于是每 12 秒必然再命中一次。而插件的第一条脉搏要等它自己的控制会话建好（实测 ~70 秒），phone 每 12 秒踢一次又把这个过程往回推，是**「恢复动作本身阻止了它正在等的那个东西」**。
   **修法**（已落地，2026-11-01）：①这一支改成按**连续自救次数**做渐进宽限（`SyncCoordinator.noPulseGraceMs(recoveries)`：第一次 12 秒，之后每救一次翻倍，封顶 120 秒，`recoveries` 只在 `pulse()` 里归零）；②`recover()` 不再无条件 `closeControl()`——先 `controlAlive()` + `focus()` 试一次，成功就只记「连接仍在，聚焦已重挂」，只有聚焦失败或第二次连续自救才真正重开 socket（`ChatSyncActor.kt:422-487`）；③判据：`grep 'control recover' diag.log` 不该出现**连续等间隔**（≈`PULSE_DEAD_MS`）的多条。**2026-11-01 复测：这条循环在当前链路上复现不出来**——模拟器 + 本地中继静置 6 分 51 秒，`control pulse` 75 帧（间隔 4.989~5.025s 无断档）、`recover` 0 行、`ws open` 0 次。当初那轮的现场是假宿主反复重启造成 39 秒 `503` 空窗，属**真断流下的正确自救**。所以「24 秒」这个数字本身是设计值（两个 `PULSE_DEAD_MS` 窗口），不是实测周期，别拿它当 bug 判据。

55. **打字机与「贴底跟随」是两件独立的事，判据不能混用**。`ui/Typewriter.kt` 只在**渲染层**放慢已收到的文本（网络层不动），速度跟**积压量**走：`typewriterChunk(pending)` 按 `TYPEWRITER_CATCHUP_TICKS`(12，≈200ms) 向上取整，积压少一 tick 一个字、积压多一 tick 吐一大把。三条容易写错：①推进下标必须用 `codePointCount`/`offsetByCodePoints`，按 UTF-16 单元切会切出孤立代理对，emoji 变方块；②进度存 `rememberSaveable` 而不是 `remember`——调用点在 `LazyColumn` 的 item 内，滚出屏幕 composition 就销毁，`remember` 会丢进度、滚回来从头再打；③「还是不是同一段文本」只能靠 `target.startsWith(previous)` 判，比长度会漏（换段落、上游重发都常见），不归零就会从中途开始吐。
    `ui/AutoScroll.kt` 的判据是「**上次贴着底** 且 **现在能往下滚了**」（`canScrollForward` false→true），这就是「只有贴底才跟随」，不需要额外的意图位。四个规矩：①**首次量测不算数**——抽屉刚打开、列表刚首帧时 `canScrollForward` 也是 false→true，那是「刚量出来」不是「有新内容」；②`!isScrollInProgress` 守卫不能省：打字机每 16ms 动一次，而 `followTail`/`userDragging` 要经一次重组才进闭包（`rememberUpdatedState`），`canScrollForward` 的翻转却是立刻可见的——少这道守卫，用户刚往上拖的那一帧就被拽回底（v1 老 bug）；③`LaunchedEffect` 只按 `listState`/`scrollState` 重启，会变的入参都要 `rememberUpdatedState` 转手，否则永远读第一帧旧值；④`LazyColumn` 版滚 `scrollToItem(tail, tailScrollOffset(height))`（量不到高度时给 1_000_000，越界即夹到底），`ScrollState` 版直接 `scrollTo(maxValue)`。回归在 `TypewriterTest` 的 `贴底且内容变长时应该滚` / `用户翻上去了就一个动作都不做`。
    **`ScrollState` 版（两级抽屉）多一条坑：内容总高只能用 `maxValue + viewportSize`（`contentHeightOf`），不能只看 `maxValue`**。内容不满一屏时 `maxValue` **恒为 0**，撑满才 0→800：拿它比较，「长高了没有」判成 `0 > 0` 假，「是不是首帧」又被 `maxValue <= 0` 吃掉，于是**第一次长过一屏永远不滚**（2026-11-01 用户实测）。首帧判据换成 `viewportSize <= 0`——视口量出来就不会回 0。现场证据靠运行时打点：`StickToBottomOnGrowth(scrollState)` 的 `crossing` 分支**只记「不满一屏 → 第一次撑满」那一次**（思考时内容每 58px 长一次，逐次记会冲掉 256KB 的 diag.log）。实测一行：`scroll sheet scroll h=1749 last=1743 vp=1743 fwd=true atBottom=true dragging=false`——`last == vp` 就是「恰好等于视口」那刻。验这条要让**思考在抽屉打开之后继续长**：假宿主原来 7 句循环每 250ms 一片、两秒就停，为此加了 `SMOKE_REASONING_MS` / `SMOKE_REASONING_TOTAL`（默认 -1 一直推）。
    另一条排查口诀：**抽屉里点的是哪一轮**。`uiautomator dump` 里同一屏有多条「已处理 N 个操作」，`head -1` 拿到的是**最上面那条旧轮**（`step.text` 是落定值、`running` 已假），点进去当然不长——要按 `bounds` 的 y **取最大**。验增量还必须让 `prompt` 走 v2 控制流：`STREAM_DELTA` 只在控制流上广播、**从不归档也不编号**，`prompt refused: socket=present open=false` 一出现就说明这轮回退到了 v1。

56. **判死判据必须是「有多新鲜」，不能是「有没有过」**。`tick()` 与 `halfDead()` 原来都拿「这两个时钟还是不是 0」当闸门（`lastSeen = maxOf(lastPulseAt, lastDataAt)` / `if (lastPulseAt <= 0) return false`）。这是**只会错一次、错了就永久**的写法：收到过一条 records，`lastDataAt` 就再不是 0；自救过一次，`recover()` 就把 `lastPulseAt` 归零。两条一起失效之后，「records 一直在到、脉搏一条不来」（坑 54 的另一半）**再没有任何东西会判死**：`hostState` / `running` 冻在最后一帧旧脉搏上，长推理进度行不再走——「看着在跑、其实在冻着」。
    现在的口径是 `SyncCoordinator.pulseEvidenceLost(now, armedAt, lastPulseAt, lastDataAt)`：真脉搏 12 秒内算新鲜；records 可以给脉搏**当替身**，但替身有期限 `PULSE_SUBSTITUTE_MAX_MS`（120 秒，重放窗口 10~15 秒的八倍余量）（重放时脉搏压在队尾十几秒正常，判死会重连→重放→再堵）。`halfDead()` 的替身分支同样加了期限（不复用 `pulseEvidenceLost`：那支的 `armedAt` 属于「可反复自救」，`halfDead` 只判一次）。
    三条规矩：①`recover()` 归零之后靠 `armedAt` 兜空档；②`lastDataAt` 在 `recover()` 里**不要跟着清零**，清了会把最健康的连接在 12 秒后误判；③回归必须双向钉——「records 一直在到、脉搏两分钟没来 → 必须判死」和「脉搏 98 秒没来、records 一秒前刚到 → 不许判死」。用例：`SyncCoordinatorTest` 的 `records stop substituting for the pulse after two minutes`、`ChatSyncActorTest` 的 `records alone do not keep a pulseless control stream alive forever`。

57. **`/control/timeline` 当初故意不发变更帧，手机侧 `watchChanges` 却是列表页唯一的「有变化」信号 ⇒ 预览永不刷新**。v2 重构那条注释的前提（「手机也不听变更帧了」）是错的；现已在该路由归档后 `changes.publish`。排障口径：中继有帧 → 手机才触发 `/sync` → Room 更新 → 重算预览。

**打包 / 部署（插件）**
58. **热重载的目录指纹只拼 `mtime + size` 会漏掉一整类「换了包却毫无反应」**。2026-11-02 实测（本机 `~/.dsh/profiles/desktop`）：把新打的 tgz 解包盖到 `node_modules/dsh-mobile-sh/` 之后，`/tmp/mobile-sh-hotreload.log` 只剩 `轮询心跳 #N（间隔 1000ms）`，装机后跑的还是旧代码。根因在 `packages/plugin/src/hot-reload.ts` 的 `fingerprintOf()`：它逐文件拼 `name:mtimeMs:size`，而 **`npm pack` 会把 tar 内所有文件的 mtime 归一化成同一个常量 `499162500`（1985-10-26）**；那次重建只是把 build hash 那行**等长**字符串换掉，于是 `index.js` / `worker.js` / `client.js` 的 size 也分毫不差（实测三对全是 `499162500 916838` / `499162500 784009` / `499162500 4605`）→ 新旧目录指纹逐字节相同，`check()` 在 `if (next === fingerprint) return;` 早退，`fs.watch` 与 1 秒轮询**双双失明**。
   修法：把 **`ctimeMs` 一起拼进指纹**（`` `${entry.name}:${st.mtimeMs}:${st.size}:${st.ctimeMs}` ``）。ctime 由内核在 inode 写入时盖章，**tar 与 `utimes` 都设不了它**，普通读也不改它——零额外 I/O 就能识破「重新解包同一份 1985 mtime 的文件」，也不会引入误触发。回归在 `tests/hot-reload.test.ts`：写等长新内容 + `utimesSync` 把 mtime 钉回 `TAR_MTIME`，先断言 `after.size === before.size` 与 `after.mtimeMs === TAR_MTIME * 1000`（**这两条是前提断言，缺了就是假绿**），再等回调。写完先**去掉 `ctimeMs` 跑一遍确认真的红**（现场症状是 `wait timeout`），再加回来。
   现场应急（包里还没带这个修法时）：`touch <profile>/node_modules/dsh-mobile-sh/dist/*`——只改 mtime 就能让指纹变。判据一律是 `grep '发现变更' /tmp/mobile-sh-hotreload.log` 有输出 + 插件日志出现一条新的 `ready`，**不能看命令有没有打印 esbuild 输出**。
   **可靠部署步骤**：`npm run build` → `npm pack ./packages/plugin --pack-destination <dir>` → 新 tgz 覆盖 `~/.dsh/profiles/desktop/vendor/dsh-mobile-sh-0.2.0.tgz` → `rm -rf <profile>/node_modules/dsh-mobile-sh && mkdir && tar -xzf <tgz> -C <目录> --strip-components=1`（先备份目录与旧 tgz）。`dsh plugin --profile desktop add <tgz>` 在**版本号不变**时不会重新解包（见坑 31）。
   **配套**：`scripts/build.mjs` 的 buildHash = `git rev-parse --short HEAD` + `toISOString().slice(0,13)` 去掉 `-:T`，所以**同一小时内、同一 HEAD 的两次构建连 `dist/build-info.json` 都一模一样**——「装的是不是新构建」只能靠内容哈希（已装 dist 与仓库 dist 三个产物逐一 `sha256sum` 相等）或「重启后有没有真的 `ready`」。`buildHash` 只落在 `lifecycle.ts` 的上报体与 `control.ts` 的 `HELLO_ACK`，**插件不往自己日志写**；中继也不持久化它（`/metrics` 的 `hosts[]` 只有 `{hostId, online}`，生产库 `hosts` 表没有 build_hash 列）——别指望从服务端读回本机在跑哪一版。

**调试工具（mobilewright / uiautomator）**
59. **`adb shell uiautomator dump` 与 mobilewright 争同一个 UiAutomationService，后到的那条当场崩、`cat` 出来的是上次的旧树**。2026-11-02 实测（模拟器 `test_emulator`）：mobilewright 的 `snapshot` 明明停在聊天页的权限菜单，同一时刻 `adb -s emulator-5554 shell uiautomator dump /sdcard/ui2.xml` 却吐出一棵**登录页**的树（`登录与配对` / `中继地址 https://localhost:8443` / `连接失败，请检查网络后刷新`），第二次 dump 之前先 `rm -f` 也没用。真报错埋在 logcat 里：`java.lang.IllegalStateException: UiAutomationService android.accessibilityservice.IAccessibilityServiceClient$Stub$Proxy@… already registered!`（`com.android.commands.uiautomator.Launcher` → `DumpCommand.run` → `UiAutomationShellWrapper.connect`）。**mobilewright 与 uiautomator 抢的是同一个 UiAutomationService**，谁先连上谁独占；后到的 dump 命令当场崩，`cat` 就把上一次留下的文件当成现状——那次读到的是旧会话的 Settings 页（版本号还写着 `v0.2.5`），差点被当成「App 自己跳回设置页」去查。
    三条规矩：①**调试期间只用一条腿走路**——用 mobilewright 就别敲 `adb shell uiautomator dump`，真要 dump 就先让 mobilewright 会话退出；②dump 命令的 stdout 必须看它自己的回显（`UI hierchary dumped to: …`），看到 `Killed` 或异常就别 `cat`；③判据永远以 mobilewright 的 `snapshot`（或 App 自己的 `files/diag.log`）为准，设备 / App 的当前状态从来不在 `/sdcard/*.xml` 里。
    **顺带把本轮真正好用的取证手段钉在这**（像素级、可复现，不用肉眼猜）：①**全屏遮罩** → 同一区域裁剪的平均亮度前后对比（`0.2126R+0.7152G+0.0722B`）；亮色 `bgScrim = 0x1A000000` 实测让背景降约 10%（分带测得 0.899~0.936，只有被菜单卡盖住的那一带不动）。②**竖向导轨** → 逐列统计「恰好等于 `Ds.borderStrong`（亮色 `#E5E7EB`）的像素数」，命中列会突然冒出一根 900+ 的柱子（`x=98/99/100`，1dp×3 设备像素），未修前全屏只有 1~2 个散点。③**「红色只留图标」这类配色断言** → 把「图标列区间」和「文字列区间」分开做颜色直方图：图标列纯 `(220,38,38)` = `Ds.statusError`，文字列墨色 `(18,19,22)` = `Ds.textPrimary`、一个红点都没有。④截图只用来看**结构与量像素**，**不要靠肉眼判断「白屏」**：1216×2640、285KB 的 PNG 非白像素占 5.7%，目视仍然像一片空白（我误判过一次）。
    另注：**PyMuPDF/PIL 在本机 `python3` 里就有**（macOS 侧 PIL 12.0.0），不必另找运行时；`python3 -c "from PIL import Image; …"` 直接算直方图即可。

60. **`question/*` 这一套事件在真宿主上不存在，别再照着它写功能**。实测 88 个会话 0 条 `question/*`，`@deepseek-ai` 全库 grep 零命中——提问卡与审批卡**同走 `approval/request` waterfall**，只是 `kind` 不同。`packages/core/src/classify.ts` 里的 `QUESTION_TYPES` / `QUESTION_DONE` 是为不存在的上游准备的空壳，留着无害（万一上游将来加），但**任何新功能都不该指望它命中**；手机侧提问卡的数据来源只能是 waterfall 里 `kind` 为提问的那一支。

**数据库 / 落盘**
61. **改了 Room 实体却没抬 `@Database(version)`，会让整条本地时间线静默失效——症状看起来像网络问题**。2026-11-02 真机（`YOUR_DEVICE_SERIAL`，0.2.45）实测：会话列表预览永久停在旧值、进会话页也补不到新内容。手机侧日志里只有 `sync v2 session <id> failed: sync-loop`（30 秒内百余次）与 `连续失败 3 次，交给下一次触发`，怎么看都像网络或中继的问题。
   真因在落盘那一层：早期改动给 `timeline_records` 加了 `stub_preview_head` 列，**`@Database(version)` 还是 1**。Room 打开库时比对 `room_master_table.identityHash`，发现与当前实体不符，直接抛 `IllegalStateException: Room cannot verify the data integrity. Looks like you've changed schema but forgot to update the version number`。后果是**连锁的**：
   - `TimelineStore.commit` 每次抛异常，被调用点的 `runCatching{}.getOrDefault(0)` 吞掉 → 一条记录都不落盘（日志里 `wrote=0`，与「服务端一条没给」长得一模一样）；
   - `timeline.watermark()` 同样抛，`runCatching{}.getOrDefault(LocalAckedSeq(0))` 让 `from=0` 恒成立；
   - 中继判定「同一个 `afterSeq` 反复问」⇒ 429 ⇒ 手机只看到 `sync-loop` 并退避；
   - 界面靠进程内缓存照常能看、能滚，于是**最像症状的层（网络/中继/预览逻辑）全是无辜的**。
   **取证关键**：`files/diag.log` 里的 `commit <id> 失败: IllegalStateException: Room cannot verify the data integrity…`（这条以前被 `runCatching` 完全吞掉，本轮才补上 `onFailure` 打印）；以及 `databases/` 里 `dsh-timeline.db` 的 **mtime 停住不动** + 没有 `-wal`（WAL 模式下没有 `-wal` 就等于「没人写过」）。把库拉回来用 `python sqlite3` 查 `room_master_table` 的 identityHash，与 `android/app/schemas/**/<最高版本>.json` 的 `identityHash` 一比即出差集。本次两侧分别是 `3847d1ccf52b46e603607a20580d65bd`（设备，= 该早期改动之前的 v1）与 `11b306dbe27e4400a2b0a07b84302799`（代码），差集列只有 `stub_preview_head`。
   **修法**：抬版本到 2 + `Migration(1, 2)` 执行 `ALTER TABLE timeline_records ADD COLUMN stub_preview_head TEXT NOT NULL DEFAULT ''`，并在 builder 上 `.addMigrations(...)`。**别指望 `fallbackToDestructiveMigration()`**：它只在「版本号变了且没有对应迁移」时兜底，对「改了 schema 但没抬版本」这种半吊子升级完全无能为力。
   两条配套硬要求：①`runCatching` 吞掉落盘异常时**必须打日志**，并且**不能把落盘失败当成游标推进**（否则会一路用同一个 `from` 问下去）；②回归护栏 `android/app/src/test/java/com/dsh/mobile/v2/data/SchemaVersionTest.kt` + `src/test/resources/schema-hashes.json` 钉住「导出的 schema 版本 == `@Database(version)`」「已发布版本的 identityHash 不许变」「相邻版本必须有 `Migration`」。写完先**反向验一次**（把 `version = 2` 改回 1 跑一遍，对应的测试必须红）。
   两个附带坑：**Room 的 `@Database` 是 `RetentionPolicy.CLASS`，运行期反射拿不到**，单测只能读源码文本；**用 `version = 1` 构建过一次，Room 就会把 `1.json` 覆盖成当前 schema**（本次踩到，得用 `git show <改列之前的提交>:android/app/schemas/.../1.json` 还原），否则「已发布哈希不许变」这条永远发现不了问题。
   验证口径（可复现）：装完后 `user_version = 2`、`room_master_table` 哈希 == 代码侧哈希、`dsh-timeline.db-wal` 出现且 mtime 走字，日志从 `from=0` 变成 `from=<水位> … stop=TAIL_COMPLETE`，并出现 `preview warm host=… refreshed=N`。

**删除 v1 面（2026-11-02 全量迁移）**
62. **Kotlin 的行内块注释是「可嵌套」的：KDoc 里写一个 `/native/v1/*` 就能让整个文件「Unclosed comment」**。修 v1→v2 时把路径写成通配符（`/native/v1/*` 路由一起删除）是很自然的措辞，但 Kotlin 的 `/* … */` **支持嵌套**，KDoc 里的 `/*` 会再开一层注释，后面的 `*/` 只关掉内层，文件末尾留下未闭合的块注释。表现是编译报 `e: … Unclosed comment`，**指向文件最后一行**（不是出事的那一行），满仓找错很费时（本轮在 `SyncPlannerTest.kt:92` 又踩一次）。
    规避：注释里描述路径通配一律用 `…` 或 `§`（`/native/v1/…`），**不要用 `*`**；`//` 行注释里写 `/*` 无害，但为了一致也统一不用。排查口诀：**看到 `Unclosed comment` 就直接 `grep -n '/\*' <文件>`，第 2 个及以后的命中就是凶手**。

63. **下线 v1 面必须回显式 410，绝不能放任它掉进「按 Host 头路由」的兜底分支**。`packages/relay/src/server.ts` 的兜底分支会拿不到 `relay-host` cookie 而回 401 `session-required`——老 App 把 401 读成「登录过期」，静默清凭据并逼用户重新配对，**看起来像中继坏了**，用户不会去升级。所以 `/native/v1/` 前缀与 `/control/archive` 两条都在最前面 `throw new HttpError(410,'v1-gone')`，并有回归测试钉住「必须是 410、不能是 401/404」（`tests/sync.test.ts`）。同一轮里被删掉的 v2 面还有 `/native/v1/…/rpc/eventsResult`：它往宿主 `$events/result` 直接回 waterfall 结果，等于给手机留了一条**绕过插件账本**的后门——「点过的审批卡还能再答一次」正是从这儿来的，v2 的审批一律走控制通道的 `INTERACTION` 帧。

64. **`archivedSessionIds` 在 v2 里没有任何写入端——归档会话不会再从手机列表里隐藏（已知缺口，不是本轮引入的）**。追查法是三条 grep 定案：①宿主 `$events` 的帧白名单只有四种（`['type','clientId','host']`=`ready`、`['type','eventId']`=`cancel`、`['type','event','args']`=`emit`、`['type','event','eventId','agentId','request']`=`waterfall`，见 app.asar 里的 `hasExactRemoteEventKeys`），**根本没有 `workspace` 帧**；②asar 里 `archivedSessionIds` 只是 `workspace/archiveSession` / `workspace/unarchiveSession` 两个 RPC 的**返回结构**；③`packages/{relay,plugin,core}/src` 里 grep `archivedSessions|workspace` 只剩 `naming.ts` 的一行命名前缀。手机侧那份归档集合来自 v1 `/sync` 的归档元数据（`ArchiveSync`/`ArchiveMeta`，写入方法本来就没有调用方），所以它随 v1 路由一起消失**不是回归，是纠错**。
    要做回来只有一条正路：插件在 `refreshSessions()` 里顺带拉宿主的 `workspace` 投影拿 `archivedSessionIds`，经控制通道下发（和 `INTERACTION` 帧同机制）。在那之前，手机列表里会看到桌面端已归档的会话——`blank` 字段仍能把「只有 workspace/context 的会话」滤掉，所以观感影响有限。空会话判定现在只认宿主的 `blank` + 10 分钟宽限 + 本地是否已有 user/assistant 消息（`AppViewModel.isSessionEmpty`）。

65. **列表页预览根本不是「从归档推下来的」——它是手机本地现算的，而这个「现算」曾经被 v1 的旧消息文件永久劫持**。2026-11-02 用户报「pp-paradise 那个会话预览还停在『真机验证通过…』，实际已经到『已上线：发布编号…』了」。
   先厘清两条独立水位：中继 `timeline_records` 里该会话 `ASSISTANT_TEXT` 最新一条**已经是「已上线…」**（解密核过），手机 Room 也同步到了——**数据两层都是对的，坏在取值那一步**。`AppViewModel.previewOf` 是三级回退，第二级读 `cache.loadMessages()` 即 `files/messages/*.json`，**命中就 `return`**，于是：
   - 那个文件的**唯一写入点**是 `ChatViewModel.persist()`（在 `close()` 里），也就是「上次退出聊天页时的快照」。会话在后台跑完整轮，只要没再进过聊天页，文件不变 ⇒ 预览永远停在旧句；
   - 它还把旧文本**回填进 `previewCache`**，`invalidatePreview()` 清完下一帧又读回同一份重新缓存 —— **自锁**，`progressed>0` 那条「数据变了就重算」的通路被彻底堵死；
   - 它还是**整文件读 + 全量 JSON 反序列化**，而 `previewOf` 在 `withReadAt` 里对每一行都调一次：冷启动 50 个会话 = 主线程 50 次全量反序列化。
   这也精确解释了用户现象里最迷惑的一点：**「进一次聊天页再退出来就好了」**——`persist()` 顺手把文件刷成了最新。
   **修法**：删掉第二级，只留「内存缓存 → 排队 Room 预热」。不会让列表变白，因为 `withReadAt` 取到空串时保留 prefs 旧值（`preview = fresh.ifEmpty { s.preview }`），用户看到的是「过一会儿自己更新了」。
   **验真必须用冷启动配对实验**：停在列表页时两条路都会更新（靠 change 帧触发的同步），**该实验不具区分力**；只有进程重启走批量同步+预热才暴露差异。本轮做法是同一份磁盘、同一中继、同一假宿主，只换代码：回退版冷启动后 T+4s 到 T+28s 恒为「第 1 轮回复…BG-PREVIEW-XYZ」，修复版 T+4s 即为「第 3 轮回复…CTRL-TURN3」。
   护栏 `android/app/src/test/java/com/dsh/mobile/v2/ui/PreviewSourceTest.kt` 读源码断言 `previewOf` 函数体**不含 `loadMessages`**、且**必须含 `warmQueue.enqueue` 与 `scheduleWarm`**（private + 依赖 Context，JVM 单测起不来，读源码是既有取舍，先例见 `SchemaVersionTest`）。写 KDoc 时 **不要写 `files/messages/*.json`**：`*/` 会提前闭合注释，编译报 20+ 条 `Expecting a top level declaration`（同 62 号坑的家族）。

66. 验证同步放大类实验，**先确认台架自己的节拍**：假宿主是「一条一条喂」的，量出来的 `/sync` 频次不能当生产结论；先量台架再量系统。

**三端同线 / 部署一致性**
67. **三端（中继 / 插件 / APK）是同一条协议线上的三节，改协议面必须同时到位**。中继把 v1 全 410 后，旧插件发不出控制流 `INTERACTION` 帧 ⇒ 审批卡「画得出来、点不动」；正确顺序是先发插件与 APK、最后才让中继关旧路径。
    判据用**特征串不是哈希**（`build.mjs` 注入含时间戳的 build hash，同源码重建 `shasum` 必然不等）；装的是哪一版看 `dist/build-info.json`。同版本号别用 `dsh plugin add`（坑 31），热重载指纹见坑 58。

**并发 / 有界状态（2026-11-03 一轮清账）**
68. **共享格式化器不能是有状态的，但「并发缺陷」的回归测试不能靠概率**。`data/Diag.kt` 原来用 `SimpleDateFormat` 当单例格式化器，而 `Diag.log()` 的调用点横跨主线程与 `Dispatchers.IO`（`ChatViewModel` 的 actorScope）——`SimpleDateFormat` 内部有 `Calendar`，并发 `format` 会互相踩，轻则时间戳错乱重则 `ArrayIndexOutOfBoundsException`。改成不可变的 `DateTimeFormatter` + `LocalTime.now()`（minSdk 26 就有 `java.time`，不必 desugar）。
    **但别给它写「压测式」回归用例**：我按 8 线程 × 2000 次、再抬到 16 × 20000 次去反向验证，把实现换回 `SimpleDateFormat` 后**依然全绿**——JVM 上这种竞态窗口太窄，用例只会给后来人一个「已验证」的假象。既然反向验证证不出红，就删掉它，把理由写在 KDoc 里（本仓先例：`SchemaVersionTest`/`PreviewSourceTest` 读源码，也是「运行期测不到就退到静态断言」）。**规矩：任何回归用例，先做反向验证；变不红的用例是负债，不是资产。**

69. **「兜底逻辑」与「上限/计数」是这类缺陷的两个固定形状，改的时候要一起看**。同一轮里清掉的三处，形状完全一样——都在「看起来已经处理了、其实没覆盖到」的地方：
    - `relay/src/changes.ts` 的 `QUEUE_LIMIT = 256` 分支**不可达**：`sink()` 是同步调用，`pending++/--` 在一帧之内闭合，永远是 0，慢客户端一条都拦不住（真正能说明堵住的是 `streamChanges` 里 `res.write()` 返回 false + 此后没 drain）。同时 `publish()` 里 `sub.write(change); delivered++;` 是**无条件自增**，返回的送达数会虚高。改成 `write` 返回布尔（背压/已关闭 → false），`publish` 只数真收下的；那个 256 保留但改名为 `REENTRANCY_LIMIT`，它拦的是 `sink` 内部同步回头再 `publish` 的递归扇出。
    - `relay/src/proxy.ts` 的 `unauthIp: Map<string,Budget>` **只增不删**（键是公网 IP，扫描器换地址就来一条；768MB 机器跑几天就是几十万条常驻），且 `take()` 抛错时已经 `set` 了一条 `open=0` 的空壳、没人会再来清它。抽成 `UnauthSlots`：按 IP 记引用计数，降到 0 就 `delete`；**失败路径也要回滚**。`Budget.take()` 返回的释放函数必须真的调（原来写的是 `slot.budget.used-=1`，绕过了 `held` 幂等守卫）。
    - `data/Projector.kt` 的 `settleCard` 兜底原来无条件 `indexOfLast`：两张同类卡并发挂着（一轮里连着问两次，或上一条结算帧被 2000 条窗口截掉）时，它撤的是**还在等的那张**，真正已结算的那张继续留着——用户看到「批过的又冒出来、没批的消失了」。改成**只有同类卡恰好一张时才撤**；不止一张就什么都不做（留着的卡还有账本层兜底，撤错了没有）。
    另一条同源：`plugin/src/control.ts` 的 `focus()` 原来要求 `sinceSeq > 0` 才报 `hasMore`，而 `sinceSeq === 0`（装机后第一次进会话）恰恰是最需要它的那一档——hub 只留尾部窗口，更老的部分只能在归档里。这条此前靠 actor 启动时无条件 `dirty = true` 兜住，**是巧合不是契约**。
    **判据**：凡是「这里加个上限/兜底就安全了」的写法，都要问三句——①这个上限真的会命中吗（能不能构造出让分支执行的反例）；②计数/返回值的每个自增点，是不是都只在「真的发生了」时执行；③兜底在**有多个候选**时撤错了，代价是不是比不做更大。

70. **`ASSISTANT_TEXT` 这个 kind 名不副实——一回合里 70% 的「助手正文」记录根本没有正文，任何按它计数的分页都是假的**。`packages/core/src/classify.ts:95-96` 对宿主每次 `assistant/message` 无条件归类，而一回合里助手会发很多条、其中大量只含 `type=reasoning`。生产库（88 会话 / 58736 行 / 150MB）实测：`ASSISTANT_TEXT` 7774 条里 **5763 条没有正文、合计 67.0MB**；全库正文 473K 字符 vs 思考 13308K 字符（**28×**）。
    症状三连，全是这一条的下游：①列表预览偶发整行空白（最新那条恰好没正文）；②点一次「载入更早」只多 1~6 段正文（`EARLIER_PAGE=30` 按 `kind` 计数，实测 `session-8ac8cd`=1、`9f99c5`=5、`03ca06`=6）；③进会话第一屏几乎全是思考与工具（`TAIL_LIMIT=200` 按**条数**取，实测 `session-37ab11` 尾部 200 条里正文 0 段、元数据 42 条）。
    **正解不是「多扫几条」**：第一版用倒扫 + 上限 64 条兜底，模拟器注入「50 条正文 + 200 条纯思考」后预览**整行消失**——上限只是把概率调小，不是修复。改成写入时算好一列 `has_body`（`TimelineRecord.hasBody()`，真解析 `previewHead()` 不用 payload 字面量，否则思考正文里恰好出现 `"type":"text"` 会假阳性、后果就是预览又变空白），读侧 `bodyPage(has_body=1)` 索引点查，O(倒扫) 变 O(1)。
    老库迁移允许用字面量近似（`payload_json LIKE '%"type":"text"%'`，一次性、跑完不再看），对生产库 7786 条比对过：`bodyReal=1980 / bodyFast=1980 / 漏 0 / 误 0`。**但运行期一律用真解析。**

71. **`LaunchedEffect` 的 key 里含自己写的那个 state，赋值就会把当前协程当场取消——后面的挂起点一个都不会执行**。2026-11-03「载入更早」保位失效，根因之一就是这段：
    ```kotlin
    LaunchedEffect(pendingAnchor, rows, state.hasMore) {
        val target = anchorTargetOf(...) ?: return@LaunchedEffect
        pendingAnchor = null          // ← 改掉了自己的 key
        withFrameNanos { }            // ← 从此永不执行
        listState.scrollToItem(target, anchor.offset)
    }
    ```
    `pendingAnchor = null` 触发重组、key 变化、协程取消，`withFrameNanos`/`scrollToItem` 全被吞掉。**判据很硬**：加一条「滚动之后」的日志，如果它从不打印，就是协程被取消了，而不是滚动被别的东西覆盖。修法是把滚动移进 `val scope = rememberCoroutineScope()` 的 `scope.launch { }`（`scope` 必须声明在 `LaunchedEffect` **之前**）。
    同一处还有第二个坑：**锚点判据不能用「行数变多」**。流式输出每帧都往尾部追加，加载还没回来判据就先满足，于是用「尾部新增行数」冒充「前插行数」算出错目标、`pendingAnchor` 随即清空，真正的前插到达时已无锚点。必须按**身份**（`timelineAnchorOf`）+ `seq` 兜底判「这一行有没有往前挪」。
    第三个坑：`mergeTurnPanels` 会把同一 turn 的相邻面板并成一块、合并体沿用 `run.first()` 的身份 ⇒ 用户正看着的 `#seg3` 加载后变成 `#seg0`，`panelId` 身份失效（日志 `found=-1`）。所以 `TimelineAnchor` 必须带 `seq`，`bySeq` 找第一条 `seq >= anchor.seq`。
    第四个坑：`firstVisibleItemIndex` 命中的可能是 leading 行（「载入更早」按钮 / 快照占位），要取 `layoutInfo.visibleItemsInfo.firstOrNull { it.index >= leading }`。

72. **内存投影窗口被「重读尾部」一把换掉，用户翻出来的历史就随下方输出一起蒸发**。`ChatSyncActor` 的 `records` 是**内存窗口**（不是数据库视图），`loadEarlier()` 把更早的记录并进来，而每次落盘（`commitPage`）末尾那句 `records = store.tail(sessionId, TAIL_LIMIT)` 会把整个窗口换回「最后 200 条」⇒ 用户正在看历史、对面还在打字，翻出来的前部瞬间消失。2026-11-03 用户报的就是这个。
    **不能靠「过滤内存里的旧 records」修**：中间那段完全可能落进了 Room 却从没进过内存（上一轮同步写进去、之后窗口被重置过），过滤只会保住「碰巧在内存里」的那些，时间线上凭空多出洞。**数据库是权威**——新增 `ChatStore.between(sessionId, sinceSeq, beforeSeq)`，把 `[已翻到的最老, 新尾部起点)` 连续读回来再拼新尾部（`refreshTail()`）。
    代价是内存窗口可能长于 `TAIL_LIMIT`，这是刻意的（用户翻过的历史本来就该留着），上限由 `PER_SESSION_LIMIT=2000` 兜住。实测 `project()` 成本：200 条 1.01ms / 500 条 1.57ms / 1000 条 1.70ms / 2000 条 3.27ms（JVM），手机上按 3-5 倍算 ⇒ **一开始就把 2000 条全读进内存不值得，但「懒加载窗口 + 刷新时连续读回」是对的**。
    护栏：`ChatSyncActorTest` 的 `history loaded by the user survives the next sync landing`（翻过历史后落盘，最老仍是 71）与 `the kept history is re-read from the store so it has no holes`（断言 `betweenCalls` 区间是 `71L to 103L`、`records.map{seq}` 连续无洞；没翻过历史时 `betweenCalls` 必须为空，不能白读）。

73. **贴底 effect 会吃掉任何一次「往上跳」的程序化滚动；冻顶气泡的复现前提是「尾部超过一屏」**。两条独立的坑挤在同一个交互里。
    - **`followTail` 只在「滚动停止」边沿更新**（`if (!scrolling && wasScrolling) followTail = layout.totalItemsCount - last <= 1`），也只有 `DragInteraction.Start` 会把它置 false。用户停在底部时它一直是 true，而 `LaunchedEffect(state.revision)` 与 `StickToBottomOnGrowth` 都拿 `autoScrollOnNewContent(followTail, userDragging)` 当闸门——程序化滚动（`requestScrollToItem`）不产生 `DragInteraction`，还没走完就被下一帧的流式输出拽回底部，**观感就是「点了没反应」**（2026-11-03 用户报「屏幕滑到最低端，点击冻结气泡，没有发生跳转」；截图对比：视口顶部先变靠后又回落）。规则：**任何「我要上去读那一条」的跳转，必须在发起滚动前当场交出 `followTail`**（冻顶气泡与「载入更早」同一口径；「跳转到最新」那一侧才是 `followTail = true`）。
    - **冻顶气泡只在「最后一条用户消息之后的尾部超过一屏」时才存在**：`stickyUserBubble` 第一步就在可见区里找 `role == USER`，找到即返回 null ⇒ 底部视口里还看得见那条用户消息时，气泡**根本不渲染**，全尺寸模拟器上复现不了（本机各会话尾部都不足一屏）。**`adb shell wm size 1100x1400` 把逻辑屏压小**（用完 `wm size reset`）就能稳定造出：同一会话同一动作，旧实现点完 `uiautomator` 层级与点击前逐字相同，修复版用户消息落进时间线 `[222,280]` 且「跳转到最新消息」胶囊出现。
    - `stickyBubbleTarget` **不能只按 `timelineAnchorOf` 比**——它对本地回声（`seq<=0` 且无 `panelId`）返回 null，`indexOfFirst` 会命中第一条拿不到身份的行（点 A 跳 B）。先按 `===` 对象身份找，再回落稳定身份，找不到返 -1。
    - 工具事实：模拟器不是「装上就能用」——`dumpsys window` 报 `Application Not Responding: com.dsh.mobile.v2`，ANR 报告 `Load: 38.66 / 21.8 / 9.02`、CPU `some avg10=92.79`、`96% TOTAL … 47% irq`，是 guest 被压死（反复 `snapshot`/`screencap`/`uiautomator dump` 也在压）。`pkill -f qemu-system` 后冷启动并**静置 3 分钟**（load 25→2.4）再操作。`adb shell uiautomator dump` 能拿到**带 `bounds`** 的层级（mobilewright 的 `snapshot` 没有），是这类点位取证的唯一可靠手段；`wm size reset` 后物理屏是 **1080x2400**。

74. **`LazyColumn` 迭代的是 `mergeTurnPanels` 之后的 `rows`，不是 `state.messages`——任何拿 `messages.size` 算列表下标的算式都是错的，而错得「看不出来」**。`ui/ChatScreen.kt` 的 `itemsIndexed(rows, key = { i, m -> timelineKey(i, m) })` 用的是合并后的列表，而 `tailItemIndex(state)` 一直用 `state.messages.size` 算尾部下标。同一回合的相邻面板会被并成一块，于是 `rows` 比 `messages` 短得多：一轮 4 块相邻面板 + 1 条提问时 `messages=5`、`rows=2`，旧算式给出 4，而列表最后一项是 1（临时探针实测 `PROBE messages=5 rows=2 tailItemIndex=4 lastRendered=1`）。
    **为什么线上没炸**：`LazyColumn.scrollToItem` 对越界下标会**夹到末项**，所以「贴底」看着是对的。但这是靠兜底掩盖的错误下标——`itemHeightOf(tail)`（在 `visibleItemsInfo` 里找 `it.index == index`）查不到可见项、恒返回 0，`tailScrollOffset(0)` 退回那个 `1_000_000` 哨兵值，「盖过末项高度」的真实语义退化成「赌一个足够大的数」。一旦有人让这个下标参与别的判断（比如和 `visibleItemsInfo` 比大小），立刻错。
    **修法**：`tailItemIndex(state, rowCount)` 显式吃第二个参数，四处调用点全传 `rows.size`；**刻意不给默认值**——给一个 `= state.messages.size` 的默认值等于让每个新调用点都默默踩同一个坑，传错的那天不会有编译错误。护栏用例 `tailItemIndexUsesTheRenderedRowCountNotTheMessageCount` 把「正确口径 1 / 错误口径 4」两条算式都钉住。
    **同轮的方法论教训**：我先前拍脑袋列了「冻顶气泡三条隐患」（部分可见 vs 完整可见、`lastVisibleIndex=-1` 首帧、死常量），逐条核实后**只有第三条成立**——前两条一条是把产品意图读反了（露出一点用户气泡就不冻是**合理设计**，说明用户已经在读那段），另一条在第一帧 `rows` 还是空、`stickyUserBubble` 第一句就 `return null`，根本不会闪。而「改成完整可见」的提议在任何输入上都不改变返回值，属于无动机改动。**规矩：报隐患前先构造一个能证伪它的输入；构造不出来就说明它不是缺陷。**

75. **一轮的「位置基准」是 `anchorSeq`（本轮第一条记录的 seq），而真宿主的第一条是 `turn/start`——它比 `user/message` 更早；`trim` 口径不一致又让「流式去重」永远判不出来。两个缺陷一起把同一条正文劈成两半。**
    2026-11-04 用户截图（botw 工作空间）：一条用户气泡「动手！」的**上方**挂着它自己那一轮的回答折叠栏，下方才是这一轮的正文。生产库取证（2148 条记录全量搜原文）证明：截图里上下两段正文**是同一条记录 `seq=10388`（t24）的首尾**（开头 `@4`「验证完成…」、结尾 `@3568`「…同一套骨架…」），而中间那块折叠行的思考预览来自 **t25**（`seq=10400` 起）。也就是说 t24 的一条正文被劈成两半，中间插进了 t25 的折叠行与「动手！」气泡。
    - **缺陷一（位置）**：`TurnUiModel.panelOrder` 在活面板（`panelSeq == 0`）时以 `anchorSeq` 为基准算 `anchorSeq + 0.25*(index+1)`，而 `anchorSeq` = `Projector` 用 `index.getOrPut(turnId) { TurnBuilder(turnId, record.seq) }` 取的**本轮首见记录 seq**；真宿主 t25 的 `anchorSeq=10394`（`turn/start`）、用户消息 `seq=10397`，差 3。段序累加 9 段才 +2.25，**永远追不上**。修法：加 `lastSeq` 参数（本轮最后一条记录的 seq），基准换成 `lastSeq`，没有才退 `anchorSeq`；单参重载一并删（同一错基准）。
    - **缺陷二（长度口径）**：`Projector.applyStreaming` 的去重判据是「末段正文以流式文本开头且不短于它」，但 durable 侧在 `assistantText()` 末尾 `.trim()` 过、流式侧来自 `payloadTextOf` 原样取出。宿主正文常以 `\n\n` 开头（实测 3670 → 3668）⇒ `length >=` 与 `startsWith` **恒假** ⇒ 去重永不生效，流式副本以 `seq=0` 挂在末段，被 `position(m) = if (order>0) order else seq` 算成 **0**，排到整条时间线**最前**。修法：流式侧同样 `.trim()`，并在 trim 后为空时早退。
    - **护栏**（回退两处修复会各自失败，其余 53 条仍绿）：`a live panel's order is greater than its own user message seq`、`a durable body is not drawn twice when the stream still holds it`。
    - **方法论教训**：`ProjectorTest` 里早有 `a panel never appears above the user message even when turn start precedes it`，但它只断言「`rows[1]` 是面板」，**没有断言 `order` 与用户 `seq` 的大小关系** ⇒ 这个 bug 一直活着。**槽位顺序正确 ≠ 位置数值正确**；凡是用「小数 order」参与排序的，护栏必须断言数值大小而不是数组下标。
    - 另一个反复踩的坑：**探针读错层级就会得到彻底相反的证据**。第一次用 `payload.data.content` 取正文（真实路径是 `payload.data.message.content`），于是 10290–10760 的记录**全部**被判成「无正文」，差一点下「正文没进归档」的错结论。

**中继部署（Docker）**

76. **「一行命令起服务」有三个各不相同的失败面：起不来、起得来但打不开、打得开但登不进。** 三者在 `docker logs` 里长得完全不一样，必须分开验。
    - **打不开**：`target()` 的「只信任反代」护栏在**没声明反代**时也会拒绝一切非 loopback 来源。本地 `npm start` 看不出来（127.0.0.1 放行），容器里 `remoteAddress` 是 Docker 网桥网关，于是每个请求都是 `403 · untrusted-proxy`——服务健康、端口通、页面全 403。修法：抽出纯函数 `trustedPeer(remote,trustedProxy,testMode,trustAnyPeer)`，**没声明反代就是直连部署，不做这层拦截**（直连时 `clientIp` 本来就不采信 XFF，限流键是真实 socket 地址，拦下来只把可用部署判成故障）；写了反代地址才只认它。`RELAY_TRUSTED_PROXY` 的 compose 默认值因此从 `*` 改回**空**：`*` 的真实含义是「我承认 XFF 可被伪造」，不该是默认。
    - **登不进**：`store.login` 的 TOTP 判据原来是「账号里有 secret 就必须给码」，而 `provision` 又总是建带 secret 的账号 ⇒ 单密码模式的容器一边打印 `TOTP=OFF`，一边要求一个从未展示用途的验证码（`401 · login-failed`）。修法：TOTP **只看配置**（`requireTotp`），`provision`/`create-account` 只在需要时才塞 secret；已有账号后来打开开关时用 `Store.ensureTotp()` 补一把并打印一次——仓库里没有「给已登录账号补注册验证器」的入口，不补就是把人锁在门外。护栏：`tests/relay-bootstrap.test.ts` 的「TOTP 只看配置：关着时休眠 secret 不拦人，开着时缺码就是 401」（关/开/后开关三段）。
    - **方法论**：这三条都是**容器里才暴露**的。单测走 loopback、走 `testMode`，把两个「来源信任」分支全短路了；`scripts/docker-test.ts` 之前断的是「起得来」（health、non-root、卷持久化），所以前两条一直没被发现。

77. **删除一条路由时，`grep -rn` 要连 `scripts/` 一起搜**。`scripts/docker-test.ts` 里那条 `/native/v2/hosts/:id/stream` 往返断言在 v2 删路由后变成 404，`ws` 的 `'error'` 未捕获直接崩脚本，看起来像中继坏了；容器 e2e 只证「容器里这一跳」，协议行为交给 `tests/integration-v2.test.ts`。

78. **中继的容量参数只收窄「本端接纳能力」，不改变线格式**。`packages/relay/src/limits.ts` 的 `capacityFromEnv` 只读 `RELAY_MEMORY_MB` / `RELAY_MAX_HOSTS` / `RELAY_MAX_STREAMS` / `RELAY_MAX_BODY_MB`，`streams` 硬上限是 `LIMITS.streams`（64，线格式常量），写更大**直接报错**而不是悄悄钳位——真正的对端是手机与插件，放宽本端限制只会让它们在 4422 `unsupported-window` 上断连。另有一条 Python/TS 语法的坑：`Capacity` 一旦要注入 `Proxy`，`new Budget(...)` **不能留在字段初始化器里**（初始化器早于构造函数体执行，拿不到构造参数），必须搬进构造函数体。

79. **裸 IP 部署时「端口打不开」的三种真身，`nginx -t` 只会对其中一种吭声**。
    2026-11-04 在 `self-hosted`（云主机 203.0.113.10）实测：容器在 `127.0.0.1:8080` 起得好好的、`docker logs` 干净、`docker inspect` 是 `running/healthy`，但外网 `http://203.0.113.10:8080/healthz` 一律 `Operation timed out`。
    - **① 安全组只开 22/80/443**：`nc -z` 对 `8080`/`8090`/`8000`/`8081`/`8443`/`8888`/`9000`/`3000`/`18080` 全是 timeout，只有 80 是 `Connection succeeded`。判据就是**用 `nc -vz` 看是 `Connection refused`（端口通、只是没进程听）还是 `Operation timed out`（包被丢了）**——前者说明端口开放、绑 loopback 也能从外网测到；后者说明安全组没放通，再怎么改容器都没用。云主机上「起得来但外面连不上」先把这一条排掉。
    - **② `server_name` 重名只认先加载的那个**：`nginx -t` 只打一行 `[warn] conflicting server name "203.0.113.10" on 0.0.0.0:80, ignored`，然后就 `syntax is ok`。被 ignore 的是**后加载的文件**（`sites-enabled/` 按文件名排序），而这意味着「你以为加了个新站点」或「你以为旧站点还在」二者之一会静默失效。本例里 `ybs-api` 早就把 `203.0.113.10 downloads.example.test www.downloads.example.test` 写进 `server_name`，而 `relay` 排序在前 ⇒ **ybs-api 在裸 IP 上的 `/api/ybs/**`、`/api/auto-sign/**`、`/.well-known/acme-challenge/` 全被中继接走**（中继对这些路径回 404）。修法不是在两个文件里来回挪，而是**把被顶掉站点在该 IP 上的 location 原样 `include` 进新块**（`include snippets/autosign-ybs-routes.conf;` + acme 那段），并在注释里写清「为什么这两行不能省」。判据：`grep -rn '<IP>' /etc/nginx/` 找既有声明 + `nginx -T | grep -n 'server_name'` 看重复；验证要**两条路径各打一遍**（中继的 `/healthz` 与旧站点的 `/api/ybs/...`），只看自己那条等于没验。
    - **③ 反向代理的三个必配项**（配置本身没错，漏了就是「能登进去但用着用着断」）：`proxy_http_version 1.1` + `Upgrade`/`Connection`（插件隧道与手机控制流都是 WS，缺了握手直接失败）、`proxy_read_timeout` 拉到小时级（隧道与 SSE 是长连接，nginx 默认 60s 会把它们「正常」关掉，两端都只看到 close）、`proxy_buffering off` + `proxy_request_buffering off`（附件与归档要流式过，别先落到 nginx 的临时盘）。
    - **顺带两条长度门槛**（都在 provision 那一刻炸，报错只有一行，不给上下文）：`RELAY_PASSWORD` 走 `passwordHash`，**最短 12 字符**（`Error: password-length`，容器起来就退出——`relay@test` 这种 10 字符的直接不行）；`RELAY_METRICS_TOKEN` 走 `^Bearer (\S{16,128})$`，**最短 16 字符**、但不许含空白（2026-11-04 之前还限死 base64url，填个带 `@` 的密码当 token 会得到「token 明明对却 401」）。

**App 与中继的身份（裸 IP / 换中继）**

80. **手机侧有**两道**「只认 https」的门，裸 IP 部署时互相打脸：扫码补 `http://`，归一化又把它拒掉**。
    2026-11-04 在 `self-hosted` 上实测：插件隧道已经 `online:true`、中继日志干净，但 `dsh://pair?relay=http://203.0.113.10&login=…&code=…` 深链**连续三次（热启动、冷启动都试）界面毫无反应**，中继的 `devices` 表一条新行都没有——登录请求根本没发出去。
    - **门一（地址归一化）**：`ScanScreen.parsePairing`/`normalizePairing` 给 IP 字面量补的是 `http://`（注释写着「补 https 只会让扫码后直接连不上」），而 `RelayApi.normalize` 的判据是 `if (!url.isHttps) throw("请输入 HTTPS 中继根域名")`。同一条链路两头各自「正确」，合起来就永远登不上。修法：**只有域名必须 HTTPS**（否则任何人能把你引到假中继），**IP 字面量允许明文**——这跟 `network_security_config` 的 `cleartextTrafficPermitted` 是同一个口径。错误文案也要改，原来那句只说「HTTPS 根域名」，用户拿着 IP 完全不知道该填什么。
    - **门二（长连接 scheme）**：`RelayApiV2` 的控制流地址是 `api.origin.replaceFirst("^https:", "wss:") + "/control"`，明文 origin 替换不到 ⇒ 拼出 `http://…/control`，OkHttp 当场拒（`Expected URL scheme 'http' or 'https'`）。**同包里早就有正确的 `wsUrl(origin)`（http→ws / https→wss），但全仓零处调用**——死代码比没有更坏：读代码的人以为这条路径已经支持了。
    - 教训：**「支持明文裸 IP」是一个跨三处的约定**（扫码补协议、归一化放行、长连接换 scheme），只改一处等于没改。实测判据不是看界面报什么错，而是**去中继库里看 `devices` 有没有新行**——一行都没有就说明请求没出门，别在 UI 层猜。

81. **换中继（或重新配对）时，上一台的在途答复与本地记账都会把界面拽回去**。
    现场：从生产中继切到 `self-hosted` 后，界面顶栏一直是旧主机的别名，并且**每 3 秒对旧主机发一次 `GET /native/v2/hosts/<旧id>/changes` 得 404**，一直不停（中继 access log 里 51 次）。
    - **成因一**：`boot()` 的 `loadHosts(false)` 在登录完成前就发给了上一台中继，那条答复迟 ~2.3 秒回来，正好压在 `switchToJustPaired()` 之后 ⇒ 剪枝逻辑拿**旧列表**判断「当前主机不在其中」，于是 `selectHost(null)` 再 `firstOrNull{authorized}` 选中旧主机。**`/native/v2/hosts` 的答复没有「属于哪台中继」这个概念**，origin 换掉后它照样进 `update{ hosts = value }`。修法：`RelayApi` 里加一个 `relayEpoch`（`origin` 的 setter 里 `rotate()`），`listHosts()` 在发起时抓代次、回调里 `accepts(epoch)` 才采用，过期就**静默丢弃**（不报错，用户不该看到一条早已无意义的失败）。
    - **成因二**：`SessionCache` 里的 `hosts`/`lastHost`/`lastHostAlias` 只对上一台有效（主机 id 是中继局部的），换中继必须 `clearRelayState()`；会话与游标按 hostId 分键、可以留着。
    - **成因三**：`openStreams()` 原来只用 `gateway.currentHost()`，该主机可能已被换掉。加一道「中继这次答复里确认存在」的守卫，别对着旧 id 重试。
    - 判据：**新旧两台的身份不能挂在同一个可变字段上**。凡是「问的是 A、答的也是 A，但此刻已经在跟 B 说话」的地方，都要么带代次、要么在换值那一刻清空——这两条只能选一条，不能都不做。

82. **会话 id 全以 `session-` 开头，日志里 `take(8)` 打出来逐字相同**。
    `ChatSyncActor` 的脉搏日志原本是 `pulse ${pulse.sessionId.take(8)}`，而 DSH 的会话 id 形如 `session-70afe04c-…` ⇒ 每条都打印 `pulse session-`，**看不出是哪个会话**。2026-11-04 排查「脉搏明明在到、看门狗却每 12 秒判一次死」时，这一点让「脉搏到底属于当前会话还是隔壁那个后台会话」完全无法判断，白绕一圈。
    修法：id 取 `takeLast(8)`（末尾才是随机段），再加一个实例标识 `${sessionId.takeLast(8)}@${System.identityHashCode(this)}`，并在 `recover()` 里把判死那一刻的两条时钟（`armed`、`pulseAge`）一起落盘。**一个 actor 抢了别人的控制流**这种事故，只有这两样东西能一眼看出来。

83. **中继自管 APK 分发：三处「只有真链路才会遇到」的门**（2026-11-04 首次发布实测）。
    背景：`POST /control/release`（主机凭据）+ 公开的 `GET /api/dsh-mobile-sh-apk/downloads/{version.json,<apk>,latest.apk}`，本机构建后一条命令推上去，App 优先问当前中继、失败回落旧地址。
    - **① nginx 的 `location /api/ { return 404; }` 会把新前缀一起吃掉**。`deploy/nginx.conf.example` 与 self-hosted 上的 `snippets/autosign-ybs-routes.conf` 都有一条 `/api/**` 兜底 404，于是 `/api/dsh-mobile-sh-apk/…` 落到它手上——**中继日志里连请求都没有**，手机看到的是一张 nginx 404 页，症状像「这台中继不提供更新」。修法：在 server 块里加更长的 **`location ^~ /api/dsh-mobile-sh-apk/ { proxy_pass …; }`**（前缀匹配最长优先；`^~` 让这条不进正则）。判据：出问题时先看**中继自己有没有收到请求**，别在 App 侧猜。
    - **② macOS 自带的 bash 3.2：`$( )` 里套 heredoc 会把 body 当代码解析**，报 `unexpected EOF while looking for matching ''`（我的解析器里到处是单引号，于是整段被当成代码）。修法：**顶层 heredoc 先落一个临时脚本**（`mktemp -d` 里写 `creds.py`），再 `found="$(python3 "$SCRATCH/creds.py" …)"`。
      同一处还有第二个坑：`echo "目标 $RELAY_URL（中继托管）"` 报 `RELAY_URL\ufffd: unbound variable` —— **全角括号紧跟变量名会被当成变量名的一部分**（`od -c` 能看到 `$ R E L A Y _ U R L 357 274 210`）。规矩：**非 ASCII 字符前面一律写 `${VAR}`**，本机 grep 一遍 `\$[A-Za-z_][A-Za-z0-9_]*[^\x00-\x7f]` 就能全部找出来。
    - **③ 版本号会拼进落盘文件名，必须白名单**。服务端用 `^[0-9][0-9A-Za-z.+-]{0,31}$` 校验（放过 `../..` 就是任意写文件），配套三件：`rawBody` 有上限、`x-content-sha256` 与自算摘要比对不符直接 400、**先写 `.tmp` 再 `renameSync`**（传一半断连不会留下一个「能下载但装不上」的包）。发布脚本侧再补一次交叉校验：上传成功后 GET 一次清单，断言 `latest` 与 `sha256` 就是刚发的这个——防「HTTP 200 但清单没变」这种静默失败（那种情况下 App 会永远提示「已是最新」）。
    - 另外两条实现细节：`releases` 表**只存元数据、不存二进制**（十几 MB 的 blob 进 SQLite 会让每次 checkpoint 都搬它）；`releaseTrim(keep)` 返回被裁掉的版本号由调用方删文件，否则台账清了、卷上还在长。

84. **中继的主机身份是「这台机器 + 这台中继」的二元组，换中继不等于换机器**（2026-11-05 生产事故）。
    现场：切到自建中继（`self-hosted`）试了一天再切回 `relay.example.test`，插件**重新 enroll 了一个新 hostId**（`5c23cdad`），而手机的每条授权都挂在旧 hostId（`d2c9fab5`）上 ⇒ 手机上 `/native/v2/hosts` 里这台主机 `authorized:false`，实时数据一条也收不到，用户看到的是「电脑明明在发、手机什么都没有」。连带把 self-hosted 那边的真机配对也弄丢了。
    - 根因：凭据只存**一条**记录（`mobile-sh/tunnel`），记的是「当前 relayOrigin + hostId」；`lifecycle.ts` 的判据是 `identity.relayOrigin !== relay.origin` 就整份丢弃重 enroll。于是「换一个入口」被实现成了「这台机器换了新身份」。
    - 修法：记录改成 `{kind, payload:{…当前身份…, history:{[relayOrigin]: 身份}}}`；换中继时先查 `history`，命中就**直接沿用**（不 enroll、顺手把当前身份挪进 history），没命中才 enroll 并把新身份记进去；history 最多留 5 条（整份凭据受 4 KiB 上限约束，一条十几字节的 token 恰好是「因为可以才存」）。旧的单条信封仍然照收（迁移不需要 `schema_version`）。
    - 教训：**凡是按「远端地址」区分的事实，本地记账必须按地址分键**。「我上次连的就是它」和「我就是它」是两件事；把后者写死在前者上，换地址就等于掉授权。判据：换一次中继再换回来，`devices`/`hosts` 表不该多出新行。

85. **宿主那边没了，手机不能靠记录里的 `running=true` 自己转圈**（2026-11-05 同上现场）。
    症状：桌面会话链停在 `t45`（`ASSISTANT_TEXT` 之后**没有 `TURN_END`**），手机端永远显示「运行中」、发送钮永远是「停止」，用户完全不知道发生了什么。
    - 成因：`running` 是**记录里**的状态，不是此刻的事实。宿主进程被杀/断电/断网时，`turn/end` 永远不会来；手机重连后按记录算，自然一直「在跑」。而 `ChatSyncActor` 收到 `state(connected=false)` 时只写了 `note`，**脉搏带来的运行态（`lastPulse`/`hostRunning`）原样留着**——断流那一刻它就不再代表任何事。
    - 修法两条：①断流即收回凭据（`lastPulse=null; lastPulseAt=0; hostRunning=false`），重新连上时由新脉搏重建；②渲染层区分「记录里开着」与「会话真在跑」：`stranded = message.running && !sessionRunning`，折叠行由 `foldSummary(..., interrupted = stranded)` 显示「已中断 / 已处理 N 个操作 · 已中断」，不转圈、不贴活思考、不报「此刻在干什么」。**「已中断」不等于「运行中」，也不等于「已完成」**——后两者都是在替宿主圆谎。
    - 判据：界面上的任何「进行中」，都必须能指向一个**当前还活着**的证据（脉搏、未断的流）。没有证据的旋转，就是在把「不知道」画成「在忙」。

86. **Compose 里画线要三件事同时满足，少一件就是一段编译错误**（2026-11-05 抽屉导轨改版）。
    需求：「一级抽屉的纵向时间线不要穿过图标」。做法是把容器级一条贯通线，改成每行自绘、在图标处留缺口。踩了三层：
    - **局部 `fun` 拿不到 `drawBehind` 的接收者**：`fun segment(...) { drawLine(...) }` 写在 `drawBehind { }` 里，编译报 `Functions which invoke @Composable functions must be marked with the @Composable annotation`。改成普通 lambda **也一样**（嵌套 lambda 同样没有接收者）——必须先把接收者存成局部值 `val scope = this`，再 `scope.drawLine(...)`。
    - **主题 token 只能在组合里读**：`Ds.borderStrong` 是 `@Composable` 作用域里的字段，写进 `drawBehind` 的 lambda 会报 `@Composable invocations can only happen from the context of a @Composable function`。修法是把颜色当参数传进 `Modifier.timelineRail(color: Color, …)`，由调用点（组合作用域）取值。
    - **留缺口要按行高算，不能写死偏移**：`TimelineRow` 是 `padding(vertical = 9.dp)` + `CenterVertically`，图标中心在 `size.height / 2f`。`timelineRail` 画 `0 → center-half` 与 `center+half → height` 两段，`half` 取图标半径 8dp；旁白行（没有图标）用 `through = true` 画整段，把上下两段接起来。写死 `9.dp` 的偏移在字体变大/行高变化时会切到图标。
    - 判据：放大截图看**图标上下有没有缺口**，而不是看「线是连着的」——线连着的时候图标也被穿过去了。

87. **用户气泡的复制按钮：视觉 18dp、触控 40dp，而且别用 Toast 当唯一反馈**（2026-11-05 用户要求「用户的消息气泡左侧加 1 个复制图标」）。
    - 18dp 的点击区实测点不中：图标只有拇指三分之一宽，按像素点偏 10px 就落到气泡文本上，弹出的是系统的文字选择放大镜。给 40dp 的透明触控区（图标仍 18dp），`snapshot` 里才会立起 `view "复制这条消息"` 这个可点节点。
    - **Toast 不可靠**：模拟器（Android 15 AOSP）上点击后没有任何可见变化，容易误判「按钮没生效」。改成图标就地变对勾 1.5 秒（`LaunchedEffect(copied) { delay(1500); copied = false }`），Toast 只当补充。
    - **验证手段**：`adb shell input tap` 对这个 App 时灵时不灵（快速 tap 会被 `LazyColumn` 当成 fling 开头吞掉）；用 `input swipe x y x y 700`（原地长按）更稳，复制成功时 Android 13+ 会在屏幕左下弹出「<被复制的文本> / 已复制」的系统预览卡片——那张卡片就是复制真的发生了的铁证。

88. **`ModalBottomSheet` 的「滑动收起」是指针级手势，`nestedScroll` 挡不住**（2026-11-06 用户：「抽屉内容区域现在上下滑动可能会造成收起手提， 收抽屉动作放到顶部空白区或者全面手势返回」）。
    - 第一版给两级抽屉的滚动容器加了 `NestedScrollConnection.onPostScroll { …consume y… }`，装机实测**照样收起**：`adb shell input swipe 540 1200 540 2000 600` 之后截图直接回到聊天页。原因是 M3 1.3.1 的 `ModalBottomSheet` 把 `anchoredDraggable` 挂在抽屉 Surface 上，那是**指针级**手势；滚动容器滚到边界后压根不消费那一笔，事件顺着 Main pass 上浮给抽屉，而指针层没有「消费剩多少」这个概念，`onPostScroll` 返回什么都影响不到它。
    - 正解是在滚动区**外面**包一层 `pointerInput`，在 `PointerEventPass.Main` 上把「没被里面消费掉的位移」就地消费：`if (!change.isConsumed && change.positionChanged()) change.consume()`。里面能滚时它先消费（外层什么都不做），滚到边界才由外层兜住；`positionChanged()` 判据保证点击、长按进二级抽屉不受影响。标题条与 drag handle 在滚动区之外，仍走 M3 默认手势；系统返回键/返回手势与点遮罩也不受影响。
    - 顺序不能反：`.sheetScrollIsContent().verticalScroll(scroll)` 才对。反过来把 `pointerInput` 放到 `verticalScroll` 之后（更内层），它会在 Main pass 里抢先消费，滚动就死了。
    - 实测（模拟器 `test_emulator`，0.2.54 之后的新构建）：一级抽屉正文下滑 900px 截图**逐字节相同**（没收起），上滑再下滑能正常来回滚且抽屉一直在；从 `y≈205` 的 drag handle 下滑 → 立刻回聊天页；二级抽屉正文下滑同样逐字节相同、从 handle 下滑则收起；`adb shell input keyevent 4` 也能收起。
    - 取证坑：mobilewright 的 `swipe` **只吃一个方向参数**（`swipe up/down/left/right`），写 `swipe 540 1200 540 2000 600` 会直接报错、屏幕一个像素都不动——拿「截图相同」当「修好了」的证据是假绿。坐标拖动要用 `adb shell input swipe x1 y1 x2 y2 duration`，并**先确认拖动真的发生了**（内容滚动的截图要变）。
    - **2026-11-06 复查：这一版是错的，已整段删除**。①消费指针**挡不住**抽屉，实测同一次手势里抽屉照样跟着手指走到半高；②它把正文自己的滚动手势掐死了——滑到顶/底再往回滑，同一次触摸里后续位移全部失效（用户原话「卡住，像是失灵了一样，过几秒又会自动恢复」）。真身与正解见坑 89。

89. **`ModalBottomSheet` 的拖拽手势在 1.3.x 里关不掉，只能抬到 material3 1.4 用 `sheetGesturesEnabled = false`**（2026-11-06 用户：「实测抽屉滑到最顶端或者最底端的时候再次滑动，就会卡住，像是失灵了一样，过几秒又会自动恢复」）。
    - 表面现象是「卡住」，实际是两个独立故障叠在一起：**抽屉会被正文里的拖动带走**（坑 88 那一版没治好），而且**正文滚动在同一次触摸里被掐死**。判据要分开测：同一次 `input motionevent` 序列里先滑到底、再反向滑回来，看内容回不回来、抽屉动没动。
    - 为什么指针层消费一定失败：`draggable`（`anchoredDraggable` 的底层）**不看 `isConsumed`**。被消费的只是「别人要不要处理这一笔」，不是「这个手势作废」。于是在 Main pass 上消费的结果是：抽屉照走；而滚动容器的那条 `awaitDragOrCancellation` 看到被消费就结束本次拖拽，后续位移再没人接手 —— 这就是「卡住」。
    - 正解：material3 **1.4.0** 给 `ModalBottomSheet` 加了 `sheetGesturesEnabled: Boolean`。取值前先验，别信文档版本号：解 `material3-android-<v>.aar` 的 `classes.jar`，`grep -rla sheetGesturesEnabled` —— `1.3.2` **零命中**，`1.4.0` 命中 `ModalBottomSheetKt`；再用 `javap -p` 看签名确认它是 `ModalBottomSheet` 的第 5 个参数（`…, float sheetMaxWidth, boolean sheetGesturesEnabled, Shape, …`）。
    - 拿到开关就得抬 BOM：`2024.12.01`（ui 1.7.5）→ `2025.10.01`（ui 1.9.4 / material3 1.4.0）。material3 1.4.0 的 pom 本身就要求 ui 1.8.x / runtime 1.9.0，只钉 material3 会由 Gradle 冲突解析把 ui/foundation 一起顶上去，不如整包对齐。编译只多出四条既有 deprecated 警告，无 API 断裂。
    - 代价要写清楚：抽屉**只能靠点遮罩 / 系统返回关闭**，drag handle 还在但不再响应拖动。这是明确的取舍（用户当时就说过「收抽屉动作放到顶部空白区或者全面手势返回」），不是漏改。
    - 实测（模拟器 `emulator-5556`，一步 10 个工具把抽屉撑到 21 个操作）：同一次手势里从 `y=1900` 上行到 `y=20` 内容滑到底 → 不抬手直接下行回 `y=1900`，**内容回到顶部、抽屉全程不动**；顶部下行 1600px，抽屉不动、内容也不动（已到顶）；`keyevent 4` 正常收起。修前同一条序列：抽屉被拖到半高、内容跳回顶部。
    - 护栏：`android/app/src/test/java/com/dsh/mobile/v2/ui/SheetGestureTest.kt` 扫 `ChatScreen.kt` 源码，钉住「`ModalBottomSheet(` 的个数 == `sheetGesturesEnabled = false` 的个数」并禁止 `sheetScrollIsContent` 复活。去掉任一处标记即红。

90. **`_state` 里躺的是 `snapshot()` 副本，用 `===` 找消息一定找不到，而且失败是静默的**（2026-11-07 用户：「这两条消息为什么不能删除，这是之前排队已经发送的消息了」——截图里两条排队中的气泡一直挂着「等待发送」）。
    - 现象先被拆成两半，只有一半是真 bug。**长按删除是好的**：模拟器上对一个「宿主已 ack、但一条记录都没回」的挂起回声长按，菜单正常弹出「删除」，点掉之后气泡消失且 6 秒后仍未复活（`ChatScreen.kt:1502` 的 `canDelete = true`，早期版本起就在，0.2.33 以后的包都有）。**「等待发送」清不掉是真 bug**。
    - 根因：`ChatViewModel.replaceEcho()` 用 `list.indexOfFirst { it === old }` 定位那行回声，而 `_state.value.messages` 里是 `timeline()` 产出的 `.map { it.snapshot() }` **副本** —— 身份永远不相等，`idx` 恒为 `-1`，函数一声不响地什么都不换。于是 ack 回调里那句 `replaceEcho(echo, echo.copy(sending = false))` 是空操作，`message.sending` 永远是 `true`，气泡永远写着「等待发送」。
    - 为什么平时看不出来：真记录（`user/message`）一到，`adoptPendingEchoes` 就把回声整条换成有 seq 的那份，`sending` 跟着消失 —— 这个窗口通常不到一秒。**只有「宿主已经收下（进了 inbox）但还没开跑」的排队消息窗口很长**，用户看到的就是一条明明发出去了、却一直标着「等待发送」的气泡。原话「这是之前排队已经发送的消息了」说的就是这件事。
    - 复现方式（不需要真机）：给假宿主加一个「只回 ack、一条记录都不发」的分支，就从 UI 发一句。修前气泡停在「等待发送」；修后同一个流程里气泡是正常的紫色气泡。diag 里 `chat submit len=11` 之后**没有任何失败日志**，说明 ack 是成功的 —— 这条是「不是发送失败、是本地状态没更新」的判据。
    - 正解：匹配不能只比身份。抽成纯函数 `sameLocalEcho(a, b)` / `echoSlotOf(messages, old)`：**先按身份、再按文本**（`seq <= 0` 才算回声，`seq > 0` 的真记录不参与，否则两条内容相同的消息会互相冒充）。同一份代码里 `dropLocalEcho` 早就是这么比的（`it === message || (it.seq <= 0 && it.text == message.text)`），`replaceEcho` 当时漏了 —— 同一个 bug 修了一半。
    - 护栏：`UiLogicTest` 三条 —— 「副本也要能认出来」（显式 `assertNotSame` 钉住前提）、「`seq > 0` 的真记录不参与文本匹配」、「身份优先于文本」（连发两句一样的话时，手里握着原实例的那条不会被认成另一条）。

91. **按 kind 名整批删的清理会把「新加的同类事件」一起清掉：分类名既是筛子又是容器**（2026-11-08，给「后台任务」胶囊开数据通路时才暴露）。
    - 表面现象：数据层、投影、UI 全绿，装机后「后台任务」胶囊**一颗都不出现**，但同一份代码里「任务」胶囊好好的。直查设备库（`run-as … sqlite3 databases/dsh-timeline.db`）发现注入的那行 `kind='META_PATCH'` / `job/registered` **在重启后 5 秒内消失了**，而同一条 SQL 注入的 `TASK_LIST` 活得好好的 —— 差别只在 kind 名。
    - 根因：`META_PATCH` 这个名字同时承担两个角色 —— ①「结构噪声，落盘时丢掉」②「任何还没被分类的事件」。于是 `TimelineStore` 的 `DROPPED_KINDS` 过滤（`commit()`）与启动时的一次性老数据清理 `cleanupDroppedKinds()`（`DELETE … WHERE kind IN (:kinds) LIMIT :limit`，`CLEANUP_CHUNK = 500`，冷启动 5 秒后跑）**都是只按 kind 名删的**。只在 `commit()` 里开一个口子（`it.kind in DROPPED_KINDS && !it.isJobLifecycle()`）不够 —— 启动清理照旧把 `META_PATCH` 那一整批删干净。
    - 正解：别让新事件继续姓 `META_PATCH`。新增 `RecordKind.JOB_EVENT`，在**写实体那一刻**就改名 —— `TimelineRecordEntity.of()` 里判 `record.kind == META_PATCH && record.eventType() in {"job/registered","job/settled"}` 就落成 `JOB_EVENT`；`isJobLifecycle()` 同时认 `JOB_EVENT` 与老的 `META_PATCH + type`（旧行还能读）；`Projector` 的 `when` 补一条 `JOB_EVENT -> Unit`（它本来就读不到，只是必须穷尽）。这样「按 kind 名删」的那些地方天然不会碰到它。
    - 护栏：`android/app/src/test/java/com/dsh/mobile/v2/data/JobRegistryTest.kt`（8 例，含 `isJobLifecycle` 认两种形态但不认 `step/start`）。
    - 推广：任何「按分类名整批删/整批丢」的机制，在新增一个「同类但语义不同」的事件时都要回头看一遍。**容器名不能同时当筛子用。**

92. **注入的桩数据会被宿主的真实记录顶掉：判据是「seq 最大」，不是你注入的顺序**（2026-11-08，三胶囊验收中途反复「胶囊自己消失」）。
    - 现象：注入一行 `TASK_LIST`（`todo/write`）⇒ 任务胶囊出现；假宿主紧接着推了一批新记录 ⇒ 再过一会儿进会话，胶囊**又没了**，而同一份桩数据还躺在库里。
    - 根因：会话视图取的是「最近一轮」，`newest = turns.maxByOrNull { it.lastSeq }`（按 `lastSeq` 最大，不是按记录进来的先后）。假宿主的 seq 底数是 `Date.now() * 1000`（约 `1.794e15`），它**新推**的记录 seq 天然比你几分钟前算出来的桩数据大 ⇒ 最近一轮换成了宿主那一轮，`todos` 自然读成宿主那份（空）。
    - 定位手段（一针见血）：在 `ChatViewModel` 投影现场临时加一行 `Diag.log("chat", "PROBE todos=… jobs=… turn=… records=… subs=…")`，装机后 `run-as … cat files/diag.log`。这次读到的原话是 `PROBE todos=0 jobs=1 turn=smoke-session-1:t0 records=220` —— `todos=0` 和 `turn=t0` 一起说明「判定用的那一轮不是我以为的那一轮」。
    - 正解：注入前先 `select max(seq)`，用 `max(seq) + 100` 当桩的 seq；并且每轮验收前**重新查一次** max(seq)（宿主的 seq 只能变大）。验收完把临时探针删掉 —— 它只该活一轮。
    - 顺带两条验收口径：①`uiautomator dump` 前必须 `adb shell rm -f /sdcard/d.xml`，否则 `pull` 回来的是**上一棵树**（重取后节点数从 61 变、能搜到「任务清单」）；②`uiautomator dump` 的 `text`/`content-desc` 属性能区分「有文字」与「只有无障碍标签」—— 纯图标胶囊只能靠 `content-desc` 找（本例 `[421,1972][460,2011] 任务清单` / `[521,1972][560,2011] 后台任务` / `[621,1972][660,2011] 子智能体`）。

93. **「悬浮」改成「独占一行」必须两头都量：出现时真占一行，缺席时把那一行还回去**（2026-11-10，用户口径「这些胶囊出现时，独占一行，不要悬浮，未出现时回收空白行」）。
    - 位置沿革：消息区右上角 `Alignment.TopEnd`（跟输入框隔着整片滚动区，长任务里没人会注意到）→ 消息区底部 `Alignment.BottomCenter` 悬浮（**压在最后几行正文上**，截图上「已完成分析」被胶囊盖住半行）→ 消息区与 `Composer` 之间的布局流。
    - 正解：把它放进外层 `Column`（`Box(Modifier.weight(1f))` 与 `Composer` 之间），`if (capsules.isNotEmpty())` 才发射 `CapsuleRow`。缺席时整行根本不存在 ⇒ 一行空白都不占；出现时从消息区（`weight(1f)`）扣走那一行高度，输入框钉在底部不动。
    - **两侧都要量**（只量一侧必然放过一半）：`uiautomator dump` 取 `content-desc` 的 bounds。有胶囊时 `任务清单 [471,1982][510,2021]` / `后台任务 [571,1982][610,2021]`，无胶囊时同一屏搜不到这三个 `content-desc`；**侧证是输入框的 y 不变**（两种情况都是 `发送消息或输入指令… [58,2089][435,2145]`）—— 它证明吃掉的高度来自消息区，而不是把输入框往上顶。
    - 视觉判据只有一条：**胶囊那一带不许压着字**。裁 `(0,1900,1080,2200)` 对比两张截图 —— 修前最后一行的字从胶囊底下穿过去，修后列表在胶囊行上沿被裁断（最后一行只剩上半）。
    - 顺手踩到的小坑：`CapsuleRow` 原来靠父 `Column` 的 `horizontalAlignment = CenterHorizontally` 居中，搬出后父 `Column` 是默认 `Start` ⇒ 必须自己 `fillMaxWidth()` + `Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)`（`spacedBy` 的 alignment 只在「行内有富余」时生效，对「刚好裹住内容」的旧用法一样安全）。
    - 连带的顺序变化：待答卡片**仍**悬浮在消息区底部（它跟的是某条消息 / 某一步，贴滚动内容下沿才对得上出处），所以两者同时出现时卡片在**上面**、胶囊行贴着输入框 —— 这翻转了 2026-11-08「卡片在下」的排法，是「胶囊退出悬浮」的直接后果。

94. **「子代理此刻在不在跑」不在 `subagentCatalog` 里，在它自己那条会话的 `running` 上**（2026-11-10，用户报「实测有很多已经结束的子代理导致子代理胶囊在显示」）。
    - 现象：真机上子智能体胶囊**只要派过一次子代理就永远亮着**，弹层里列出一串早就结束的子代理。三处代码都没错 —— 错的是一条从来没存在过的字段。
    - 第一步要接受的坏消息：条目里**根本没有状态**。宿主 schema 原文是 `SubagentCatalogEntry = { readonly id: SessionId; readonly createdAt: number } & ({ mode: 'one-shot'; label? } | { mode: 'external'; label? } | { mode: 'continuable'; label: string } | { mode: 'unknown'; label? })`。`subagentTiming` 也不是它 —— 那份 zod 描述是「the child's own descriptor」口径，讲的是**这条会话自己作为子代理**的计时，不是它那些子代理的状态；`agentTeam` 是 team 功能；`kind: 'subagent'` 命中的是 `SessionAddress`，**不是** job 的 kind（子代理不走 `job/registered`/`job/settled`）。
    - 正解来自桌面端自己的列表构建器（asar offset 22435640）：`for (const child of projection.values.subagentCatalog ?? []) { if (child.mode === 'external') continue; const childId = child.id; … running: this.scopes.get(childId)?.session.getSnapshot().running ?? false }` —— 活体状态取「**子代理自己那条会话**的 `running`」，拿不到就 `false`；`external` 整条跳过。
    - 关键是**取数时机**：子代理会话确实就在同一个 `/api/session/list` 响应里（`parentSessionId` 非空 / `origin === 'subagent'`），但插件 `packages/plugin/src/collector.ts:132` 是 `items.filter(item => !isInternalSession(item)).map(slimSession)` —— **先过滤再 slim**，等 `slimSession` 拿到手时那些行已经被删光了。所以活体表必须在**过滤之前**建：`runningSessions(items)` 扫原始条目得到 `Map<sessionId, boolean>`，再作为第二参传给 `slimSession(item, running?)`，由它给每条 catalog 条目盖 `running`（`mode === 'external'` 直接 `false`，它没有本机会话）。
    - 手机侧要**三态**：`running: Boolean? = null`，且 KDoc 写明「null = 不知道，不是「跑完了」」。判据 `liveSubagents(subagents) = subagents.filter { it.running != false }` —— 老插件不发这个字段时不能让胶囊消失（fail-open），只有**明确 `false`** 才算结束。胶囊与弹层用同一个判据。
    - 第二个坑（这次把胶囊直接弄没了）：`ChatViewModel.refreshProjections` 里 `subagents = value.copy(…)` 会把「响应里压根没有这个键」读成空目录，把会话列表那份（带活体标记的）抹掉。正解是**保留列表那一份**（`subagents = local.subagents`），并且**单一来源** —— `RelayGateway.sessionProjections` 不再读 catalog，否则两条路各写一份、谁后到谁赢。
    - 验证手法（本地能跑，因为假宿主就是一个真插件）：给它加 `SMOKE_SUBAGENT_LIVE` 开关，`/api/session/list` 里放两条 catalog 条目 + 对应的两条子代理会话行（`running` 跟着开关走）。解密中继库的 `session_meta.item` 能直接看到插件有没有把 `running` 盖上；手机侧 `running:true` ⇒ `后台任务 [471,1454]` + `子智能体 [571,1454]`、弹层「共 1 个在跑 / 审查投影器」（已结束那条被过滤）；`SMOKE_SUBAGENT_LIVE=0` ⇒ 子智能体胶囊消失、只剩 `后台任务 [521,1454]`（整行重新居中）。
    - 推广：**「列出子项」与「子项此刻在不在」往往不在同一个投影里。** 遇到这种缺口，先去找「子项自己那条最原始的记录」（这里是会话行的 `running`），并且确认它在链路上**任何按身份过滤之前**都还在。
