package com.dsh.mobile.v2.ui

import com.dsh.mobile.v2.data.AttachmentLoader
import com.dsh.mobile.v2.data.Diag
import com.dsh.mobile.v2.data.Model
import com.dsh.mobile.v2.data.MessageTrace
import com.dsh.mobile.v2.data.Overlay
import com.dsh.mobile.v2.data.TimelineRecord
import com.dsh.mobile.v2.data.jobsOf
import com.dsh.mobile.v2.data.mergeRecords
import com.dsh.mobile.v2.data.project
import com.dsh.mobile.v2.data.rows
import com.dsh.mobile.v2.net.ChatSyncActor
import com.dsh.mobile.v2.net.RelayGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * 聊天屏状态机：一条会话（v2 归档流 + Room 投影）的 UI 侧桥。
 *
 * v1 把这套状态摊在 ChatScreen.java 的 1500 行里；v2 收进一个普通类，
 * 由 AppViewModel 在打开会话时创建、离开时销毁。Stream 回调只改 State，
 * 渲染交给 Compose。
 */
class ChatViewModel(private val appVm: AppViewModel) {

    /**
     * 待发区里的一枚附件。
     *
     * [attachment] 是已经压缩好的字节；[preview] 只用于显示缩略图，
     * 文件类附件没有预览图。
     */
    class Pending(
        val attachment: Model.Attachment,
        val preview: AttachmentLoader.Preview?,
        /** 读这张图/这个文件时出的错；非空时这一枚不会被发出去。 */
        val error: String = "",
    )

    data class ChatState(
        /** 当前会话投影（标题/模型/权限随宿主投影刷新）。 */
        val session: Model.Session = Model.Session(),
        /** 完整消息时间轴（面板在前、正文在后、本地回声垫尾）。 */
        val messages: List<Model.Message> = emptyList(),
        /** 快照已到位。 */
        val snapshotReady: Boolean = false,
        /** 还能向前翻页。 */
        val hasMore: Boolean = false,
        /** 正在向前拉历史。 */
        val loadingEarlier: Boolean = false,
        /** 宿主回合进行中（composer 显示停止钮）。 */
        val running: Boolean = false,
        /** 流式叙述增量。 */
        val streamingText: String = "",
        /** 快照多帧进度（done/total）。 */
        val snapshotDone: Int = 0,
        val snapshotTotal: Int = 0,
        /** 连接状态行。 */
        val stateLabel: String = "",
        /**
         * 长推理进度行：「正在深度思考 (45s)…」。
         *
         * <p>**已死字段（2026-10-31）**：显示按用户要求移除后全仓库再无读方；
         * renderActor 曾在 THINKING_SILENT 时每秒写它 → revision+1 全屏重组，
         * 是真机 GPU 96% 满帧空转的直接推手之一。现在静默时长只走诊断日志
         * （`lastNotedThinkingLabel`）。字段保留仅为兼容旧缓存反序列化与避免
         * 无谓的调用方改动；写方已全部删除。
         */
        val thinkingLabel: String = "",
        /** 待答审批卡片。 */
        val approvals: List<Model.Approval> = emptyList(),
        /** 待答提问卡片。 */
        val questions: List<Model.QuestionCard> = emptyList(),
        /**
         * 此刻还在跑的后台任务（`job/registered` 尚未被 `job/settled` 结算的那些）。
         *
         * <p>**不落盘**：它是瞬时视图，由 [ChatViewModel] 每帧从记录现算
         * （见 `Projector.jobsOf`）。落盘的那份一旦过夜就是错的。
         */
        val jobs: List<Model.Job> = emptyList(),
        /** 发送中（本地回声转圈）。 */
        val sending: Boolean = false,
        /**
         * 已选好、还没发出去的附件。
         *
         * 文件类附件此刻还没换 receiptId——草稿会话连 id 都没有，上传无从谈起；
         * 真正上传发生在发送那一刻（见 [uploadPending]）。
         */
        val pending: List<Pending> = emptyList(),
        /** 正在读/压缩附件，composer 里显示一个小转圈。 */
        val attaching: Boolean = false,
        /**
         * 正在等服务端确认的排队操作，键是 `QueuedMessage.id`。
         *
         * <p>删除与「立即插话」都是**同步写操作**：点下去之后要等宿主回
         * `session/updateQueue` 的结果。不给等待态的话，卡片会在结果回来之前
         * 一直停在那里，用户以为没点上，于是连点几次（2026-10-30 反馈）。
         * 记 id 而不是布尔，是因为队列里可能有多条，只有被点的那条该转圈。
         */
        val queuedBusy: Set<String> = emptySet(),
        /**
         * 版本号：每次状态变更都 +1。
         *
         * 累加器会把面板消息当同一个实例反复原地改，而 MutableStateFlow 按 equals
         * 去重——新旧列表结构相等时不会发射，界面就冻结在最后帧。靠版本数强制发射。
         */
        val revision: Long = 0,
    )

    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state

    /** 本地回声：发出后立刻显示，宿主回显时按文本被替换。 */
    private var pendingEcho: Model.Message? = null
    private var sessionId: String = ""

    /**
     * v2 的时间线通道：归档补差 + 实时控制流 + 脉搏看门狗。
     *
     * 与 [stream] 并存是这一棒的现实：正文/思考/工具步骤已经走 v2（Room + 投影器），
     * 而审批卡片、提问卡片、任务胶囊、排队队列仍走 `$events`（它们不是时间线的一行）。
     * 两边的状态在 [publish] 处汇合，UI 只读一份 `messages`。
     */
    private var actor: ChatSyncActor? = null
    private var actorJob: Job? = null
    private var tickJob: Job? = null
    private val actorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * v2 已经接管时间线。
     *
     * 一旦为 true，老链路的快照/事件就不再往 `messages` 里写——否则两边各画一份
     * 同一轮对话，用户看到的是每条回复都出现两遍（一边 seq 来自宿主、一边来自归档）。
     * 审批、提问、任务、队列**不在**时间线里，它们照旧走老链路。
     */
    private var v2Active = false

    /** 上一帧投影的指纹：内容没变就不重新投影，避免每秒把整屏对象换一遍。 */
    /** 上一次渲染的内容指纹。`tickJob`（IO）与主线程都会读写，见 [publish]。 */
    @Volatile
    private var renderMark: String = ""
    /** 最近一次写进诊断日志的 thinkingLabel；只在秒数变化时落一条，避免每秒刷屏。 */
    @Volatile
    private var lastNotedThinkingLabel: String = ""

    /** 草稿会话：还没在宿主上创建（id 为空），订阅、投影、缓存一律跳过。 */
    private fun isDraft() = sessionId.isEmpty()

    /** 给 [AppViewModel.replayInteractions] 用：本页正在看哪个会话。 */
    fun currentSessionId(): String = sessionId

    /**
     * 输入草稿的落盘键。
     *
     * 草稿会话没有 id，用一个固定键：用户点「新建」敲了一半又退出去，回来还能接着写。
     */
    private fun draftKey(): String = sessionId.ifEmpty { NEW_DRAFT_KEY }

    /** 统一发射点：换状态 + 版本自增，保证每次回调都触发重组。
     *
     * <p>**读-改-写必须原子**：`tickJob` 跑在 `Dispatchers.IO`（见 [actorScope]），
     * `renderActor()` 会从这里整表替换消息列表；与此同时主线程的 `onChange`、
     * 用户输入、滚动跟随也在改同一个 `_state`。裸的 `_state.value = f(_state.value)`
     * 是经典的 lost update —— 一次并发就能把刚投影出来的流式正文覆盖回旧快照，
     * 用户看到的是「字闪一下就退回去」。
     *
     * <p>`update {}` 是 `MutableStateFlow` 提供的 CAS 循环，天然原子；CAS 失败会重跑
     * 一次 `transform`，所以 transform 必须是纯的（这里全是 `copy`）。
     */
    private fun publish(transform: (ChatState) -> ChatState) {
        _state.update { transform(it).let { next -> next.copy(revision = next.revision + 1) } }
    }

    /**
     * 时间轴排序见顶层 [timelineOf]：真实 seq 升序，本地行（seq≤0）垫在尾部。
     */
    private fun timeline(messages: List<Model.Message>): List<Model.Message> =
        timelineOf(messages).map { it.snapshot() }

    private val stream = object : RelayGateway.Stream {
        override fun onSnapshot(
            session: Model.Session,
            messages: List<Model.Message>,
            hasMore: Boolean,
        ) {
            // 标题照收（v2 的时间线里没有会话标题），但**正文不再往 messages 里写**：
            // v2 接管后两边各画一份，每条回复就会显示两遍。
            if (v2Active) {
                if (session.title.isNotBlank() && session.title != _state.value.session.title) {
                    publish { it.copy(session = it.session.copy(title = session.title)) }
                }
                return
            }
            publish {
                it.copy(
                    // 快照头带着宿主给的标题：刚建好的会话此时还叫「新对话」，
                    // 不接这个字段，顶栏要等下一次投影刷新才改名（首轮回复早就到了）。
                    session = if (session.title.isNotBlank()) {
                        it.session.copy(title = session.title)
                    } else {
                        it.session
                    },
                    // 增量快照只带 durable 事件：刚发出去还没回显的那条（seq=0 的本地
                    // 回声）不在里面。整表替换会把气泡从界面上抹掉，直到回显事件到达
                    // 才回来——用户看到的就是「刚才发的消息不见了」（2026-10-28 真机
                    // 复现）。把尚未顶替的本地回声拼回尾部：回显到达时 onMessage 的
                    // 顶替逻辑（seq=0 + 同文本）会把它收掉，不会重复。
                    messages = timeline(messages) + localPendingEcho(it),
                    hasMore = hasMore,
                    snapshotReady = true,
                    snapshotDone = 0,
                    snapshotTotal = 0,
                )
            }
            persist()
        }

        override fun onSnapshotProgress(done: Int, total: Int) {
            publish { it.copy(snapshotDone = done, snapshotTotal = total) }
        }

        override fun onMessage(message: Model.Message) {
            if (v2Active) return
            val current = _state.value.messages.toMutableList()
            // 宿主回显的用户消息顶掉同文本的本地回声（回声 seq=0，回显带真实 seq）。
            //
            // 必须按「seq=0 + 同文本」找，不能按对象身份：_state 里躺的是 snapshot()
            // 副本，不是 pendingEcho 那个实例，remove(echo) 会一声不响地什么都不删，
            // 界面上同一句话出现两遍（2026-10-27 真机复现）。
            if (message.role == Model.Role.USER && pendingEcho != null) {
                val echo = pendingEcho
                if (echo != null && echo.text == message.text) {
                    pendingEcho = null
                    current.removeAll { it.seq <= 0 && it.role == Model.Role.USER && it.text == message.text }
                }
            }
            dropSupersededSameSeq(current, message)
            current.add(message)
            publish {
                it.copy(
                    messages = timeline(current),
                    streamingText = "",
                    sending = if (pendingEcho == null) false else it.sending,
                )
            }
            persist()
        }

        override fun onStreaming(partialText: String) {
            // v2 的流式正文来自控制流的 STREAM_DELTA（按 tick 刷新），
            // 老链路的 assistant-stream 只负责让 LazyColumn 的转圈动起来。
            if (v2Active) return
            publish { it.copy(streamingText = partialText) }
        }

        override fun onProcess(process: Model.Message) {
            if (v2Active) return
            // 面板按 panelId 认人，不能按 seq。
            //
            // 面板的 seq 是会变的：实时增量先给它 seq=-1（排到末尾），durable 事件到了
            // 又收编成该回合首个事件的 seq。UI 里那份睡的是旧 seq，拿 seq 去 indexOfFirst
            // 就换成另一个对象，收尾事件落空 → 留下永不熄灭的「正在分析请求」（2026-10-27 真机复现）。
            val current = _state.value.messages.toMutableList()
            val id = process.panelId
            val idx = if (id.isNotEmpty()) {
                current.indexOfFirst { it.panelId.isNotEmpty() && it.panelId == id }
            } else {
                -1
            }
            // 收尾后什么都没留下的面板直接撤掉，别留一行「已完成分析」。
            //
            // 累加器那边由 retire() 在 dedupeTimeline 里删除，但那只改 bySeq，
            // 界面这份是这里塞进去的，没有第二个入口能把它拿掉——不在这里同步删除，
            // 每个回合都会在时间线上多留一块熄了灯的空面板（2026-10-27 真机复现）。
            val barren = !process.running && process.steps.isEmpty() &&
                process.actions.isEmpty() && process.reasoning.isEmpty()
            if (idx >= 0) {
                if (barren) current.removeAt(idx) else current[idx] = process
            } else if (!barren) {
                current.add(process)
            }
            publish { it.copy(messages = timeline(current)) }
            // 面板的更新只走这里，而 persist() 原先只在 onSnapshot/onMessage 里被调用——
            // 面板因此从不落盘，重进会话就只剩本站正文（F-09 的另一半成因）。
            // 流式期间每条增量都会进来（高频），全落盘会把持久化打成写风暴，
            // 所以只在回合收尾（running=false）后落一次。
            if (!process.running) persist()
        }

        override fun onEarlier(messages: List<Model.Message>?, hasMore: Boolean) {
            if (v2Active) {
                // v2 的历史在本地 SQLite，翻页走 ChatSyncActor.loadEarlier()。这条
                // 老通道的答复在这里没有意义——它只会把「还有更早的」误报成 false，
                // 于是用户往上滑一次之后就再也翻不动了。
                publish { it.copy(loadingEarlier = false, hasMore = actor?.hasEarlier == true) }
                return
            }
            if (messages == null) {
                publish { it.copy(loadingEarlier = false) }
                return
            }
            val merged = (_state.value.messages + messages)
                .distinctBy { if (it.seq > 0) it.seq else it.hashCode().toLong() }
            publish {
                it.copy(
                    messages = timeline(merged),
                    hasMore = hasMore,
                    loadingEarlier = false,
                )
            }
        }

        override fun onState(label: String, connected: Boolean) {
            publish { it.copy(stateLabel = label) }
        }

        /**
         * 任务清单变了（`todo/write`，整表覆盖）。
         *
         * 只换 `session.todos`，不碰时间线：清单不是消息，任务胶囊读的是这一个状态。
         * 宿主发的是全量，所以直接替换；本地不用做增删比对。
         */
        override fun onTodos(todos: List<Model.Todo>) {
            // v2 下队列与任务走归档流（`todo/write` 是带 seq 的真事件），
            // 两条路都写同一个字段会互相覆盖。此时老通道只剩降级用途。
            if (v2Active) return
            if (todos == _state.value.session.todos) return
            publish { it.copy(session = it.session.copy(todos = todos)) }
            appVm.syncTodos(sessionId, todos)
            // 任务进度是极低频事件（一轮里几次），顺手落一次盘：
            // 重进会话时胶囊立刻显示上次的进度，不必等投影回来。
            persist()
        }

        /**
         * 排队队列变了（`agent/inbox/spliced`，增量 splice）。
         *
         * 宿主只发「哪个桶、从第几位、删几条、插几条」，本地照做即可——不用重拉投影，
         * 也不该把队列塞进时间线。回合开始时宿主会领走队首（`claim`），
         * 于是同一条 splice 也负责「卡片消失」这件事。
         */
        override fun onInbox(
            target: String,
            start: Int,
            removedCount: Int,
            inserted: List<Model.QueuedMessage>,
        ) {
            if (v2Active) return
            val next = applyInboxSplice(_state.value.session.queue, target, start, removedCount, inserted)
            if (next == _state.value.session.queue) return
            publish { it.copy(session = it.session.copy(queue = next)) }
        }
    }

    /**
     * 起一条 v2 时间线：本地先出 → 控制流实时回放 → `/v2/sync` 补差。
     *
     * 与 [open] 里那条老链路并行跑。老中继不认 `/native/v2/sync` 时
     * [ChatSyncActor.unsupported] 会翻成 true，这里就不再渲染它的结果——
     * 否则会出现「v2 空了一屏、把老快照盖掉」的空白页。
     */
    private fun startActor(id: String) {
        val host = appVm.hostId()
        if (id.isEmpty() || host.isEmpty()) return
        stopActor()
        val transport = appVm.chatTransport ?: return
        val created = ChatSyncActor(
            sessionId = id,
            hostId = host,
            store = appVm.timeline,
            transport = transport,
            // 回调在 IO 线程上触发，所以必须切回主线程再动 StateFlow：
            // `_state` 只有一个写者线程，跨线程写会让 Compose 读到撕裂的快照。
            onChange = { appVm.runOnMain { renderActor() } },
            // 审批/提问瀑布：v1 那条 app 级 `$events` WS 已删，改由本条控制流转上来。
            // 仍然走 app 级监听者（而不是本页直接渲染）：账本要记，列表页的通知也要发。
            onInteraction = { kind, payload -> appVm.deliverInteraction(kind, payload) },
        )
        actor = created
        actorJob = actorScope.launch {
            // `start()` 里有网络往返（openControl + 首轮补差），任何一次抛出都会让
            // 这个 job 直接结束且不留痕迹：会话看起来「已连接」但永远停在尾部。
            runCatching { created.start() }
                .onFailure {
                    Diag.log("chat", "actor start failed: ${it.javaClass.simpleName} ${it.message}")
                    appVm.runOnMain { publish { it.copy(stateLabel = "同步失败，下拉刷新") } }
                }
            // 中止的会话不该继续在后台补差：用户已经走了。
            if (!isActive) created.stop()
        }
        tickJob = actorScope.launch {
            while (isActive) {
                delay(TICK_MS)
                val live = actor ?: break
                if (live.unsupported) break
                // **心跳循环本身绝不能因为一次异常就死掉。**
                //
                // <p>实测事故：聊天页停在助手中途，插件与中继两端都健康，手机却
                // 再也没写过一个字节——因为 `tick()` 或 `renderActor()` 抛出一次
                // 异常就终结了这条协程，之后不再有 tick、不再有半死判定、不再补差，
                // 界面上也没有任何报错。看门狗死掉时没有任何东西会来救它。
                // 吞掉并记录，让下一个 tick 继续值班。
                runCatching {
                    live.tick()
                    renderActor()
                }.onFailure {
                    Diag.log("chat", "tick failed: ${it.javaClass.simpleName} ${it.message}")
                }
            }
        }
    }

    private fun stopActor() {
        tickJob?.cancel()
        tickJob = null
        actorJob?.cancel()
        actorJob = null
        actor?.let { runCatching { actorScope.launch { it.stop() } } }
        actor = null
        // 离开会话时清掉推理进度行：它属于这个会话的脉搏，带到下一个会话就是假消息。
        // （显示已移除，只剩这里的诊断去重标记。）
        lastNotedThinkingLabel = ""
    }

    /**
     * 把 actor 的时间线铺到界面上。
     *
     * 只有「本地一条都没有、v2 也还没到」时才会铺出空列表，所以调用点集中在
     * 时间线真的变了的时候（sync 落盘、实时帧到达），不是每帧都跑。
     */
    private fun renderActor() {
        val live = actor ?: return
        if (live.unsupported) return
        // 「推理进度行」已于 2026-10-30 按用户要求从 UI 移除，这里不再把 thinkingLabel
        // 写进 ChatState：THINKING_SILENT 时它每秒变一个字，写状态就是每秒一次
        // revision+1 全屏重组——真机 GPU 96% 满帧空转的直接推手之一（2026-10-31
        // 对照实验：切后台 GPU 归零、回前台复燃 96%，屏幕 3/4 空白）。静默时长仍由
        // 诊断日志承担（tick 失败那条之外，这里补一条低频采样即可）。
        // 静默时长只按**整 10 秒**打点，不按秒。
        //
        // `thinkingLabel()` 形如「正在深度思考 (118s)…」，秒数每秒变 ⇒ 早先直接比
        // 原串的去重永远不成立，长时间静默会每秒往 diag.log 写一行，把 256 KiB 的
        // 环形日志刷掉（2026-11-01 实测：假宿主卡在 THINKING_SILENT 时每秒一条）。
        // 键取「十秒桶」既保留「还在思考、已经多久」的观测价值，写入量又降到十分之一。
        val thinking = live.thinkingLabel()
        val thinkingKey = if (thinking.isEmpty()) "" else
            thinking.filter { it.isDigit() }.toLongOrNull()?.div(10L)?.toString().orEmpty()
        if (thinkingKey.isNotEmpty() && thinkingKey != lastNotedThinkingLabel) {
            Diag.log("chat", "thinking silent: $thinking")
            lastNotedThinkingLabel = thinkingKey
        }
        if (lastNotedThinkingLabel.isNotEmpty() && thinkingKey.isEmpty()) lastNotedThinkingLabel = ""
        // 本地时间线还没有任何东西时不要抢屏：老链路的缓存/快照先铺着，
        // 等 v2 真的有了内容再接手（否则进会话会闪一屏空白）。
        if (live.records.isEmpty() && live.overlay.isEmpty()) return
        // 内容没变就别动：每秒重建一遍 Model.Message 会让 LazyColumn 里所有
        // 正在跑的转圈动画每帧重启，看着像卡住了。
        // 指纹必须含**流式思考**长度：漏了它，思考在长而正文没动时界面不刷新，
        // 折叠行尾部预览就冻在第一个字上（2026-10-30）。
        val mark = "${live.records.size}:${live.records.lastOrNull()?.seq}:${live.overlay.size}:" +
            "${live.streamingText.length}:${live.liveReasoning.length}:${live.hostRunning}"
        if (mark == renderMark && v2Active) return
        val turns = project(live.records, live.overlay())
        val messages = turns.rows()
        // 队列与任务是会话级的，投影把它们挂在每一轮上；取最后
        // 一轮就是「截到目前为止」的最新形态。以前这两个只能靠 v1 的
        // `/rpc/projections` 拉，而那条路在生产日志里 4/4 都是 ECONNRESET。
        // 队列/任务的权威来源是**最后被写入的那一轮**，不是 turns 列表尾。
        //
        // `turns` 是按「某个 turnId 第一次出现」的顺序追加的，而轮次之间会交错：
        // 归档按 seq 升序回放时，t0 写完一轮、t1..t21 依次插队，之后 t0 又被追加
        // （同一轮里宿主可以继续写 splice / todo）。于是「列表尾」常常不是「最新
        // 那一轮」，从它身上读会话级状态就会取到一份陈旧（甚至空）的副本 ——
        // 2026-10-31 实测：队列与任务明明已经落库，界面上一片空白。
        val newest = turns.maxByOrNull { it.lastSeq }
        // 后台任务：**不在宿主的投影表里**（SessionProjectionMap 没有 jobs），
        // 所以它跟队列/任务不同源，只能从 `job/registered` / `job/settled` 记录自己聚合。
        // 输入必须是 `mergeRecords` 那一份：刚起的任务先落在 overlay 里，
        // 只扫已落盘的 records 会把这最该看到的一条漏掉。
        val jobs = jobsOf(mergeRecords(live.records, live.overlay()))
        // 审批/提问卡片：归档里的 `approval/asked` 是持久事件，所以进会话能重建出来。
        // 但它的 id 是 `sessionId:seq`，回答要用 `$events` 那个 `eventId`——
        // 两者靠 `callId` 对账（见 `AppViewModel.eventIdOfCall`）。对不上时卡片照样显示，
        // 只是点不了——那也比完全看不到好（用户至少知道宿主在等他）。
        val restored = turns.flatMap { it.interactions }
        if (messages.isEmpty()) return
        renderMark = mark
        v2Active = true
        // 本地回声还没被回显顶掉时，别让 v2 的整表替换把它抹掉；已经被时间线认领的
        // 那些要收口，否则同一句话显示两遍（上面那条还是本地那份没 seq 的）。
        val merged = messages + adoptPendingEchoes(localPendingEcho(_state.value), messages)
        publish {
            it.copy(
                messages = timeline(merged),
                streamingText = "",
                snapshotReady = true,
                // v2 没有 `api-session/status` 那条 SSE，`running` 只能从脉搏来。
                // 不同步它，`foldLive` 永远 false，折叠行的活思考永远不显示。
                running = live.hostRunning,
                // 翻历史的能力在 v2 里由本地表决定，不在老通道的 hasMore 上。
                hasMore = live.hasEarlier,
                // 队列与任务与正文同源：两者都来自同一条归档流，
                // 不再依赖那条一直失败的 `/rpc/projections`。
                session = it.session.copy(
                    queue = newest?.queue?.toMutableList() ?: it.session.queue,
                    todos = newest?.todos?.toMutableList() ?: it.session.todos,
                ),
                // 后台任务是瞬时视图：由这一帧的记录现算，不落盘、不累积。
                jobs = jobs,
                // 归档里的 `approval/asked` 重建出来的卡。已经在界面上的那份
                // （带着 `$events` 的 eventId，可点）优先，所以走 mergeRestored。
                approvals = mergeRestored(it.approvals, restored.filterIsInstance<Model.Approval>()),
                questions = mergeRestored(it.questions, restored.filterIsInstance<Model.QuestionCard>()),
            )
        }
        // T6：投影完成、交给渲染。只有真的画出来才算「上屏」——上面几个早退分支
        // （空时间线、内容没变、投影出 0 条消息）都不算。
        MessageTrace.noteOnScreen(sessionId)
        // 首行预览同源：这一轮可能刚补进历史中段，Room 里已经能看到第一条助手正文
        // 了，但列表那一行还是空的（旧 prefs 缓存里没有）。清掉这个会话的内存预览
        // 缓存，下次刷新列表会重新取（走 `AppViewModel.previewOf` 的预热路径）。
        appVm.invalidatePreview(sessionId)
    }

    fun open(session: Model.Session) {
        sessionId = session.id
        // 标题必须当场就有。传进来的那份通常来自会话列表（宿主 projections 里
        // 本来就带 title），但它也可能来自深链/通知（`openSessionById` 只给 id 与
        // 一个可能为空的标题）。这时先用本地已知的那条补上，别让顶栏先飘一个
        // 占位、等 `/rpc/projections` 回来再跳变（2026-10-30 用户实测）。
        val knownTitle = session.title.ifBlank { appVm.titleOf(session.id) }
        // 工作区同理：深链/通知进来的壳只有 id，顶栏副标题会先空一段才冒出来。
        val knownWorkspace = session.workspace.ifBlank { appVm.workspaceOf(session.id) }
        _state.value = ChatState(
            session = session.copy(
                title = knownTitle.ifBlank { session.title },
                workspace = knownWorkspace.ifBlank { session.workspace },
            ),
        )
        // 草稿：没有宿主会话，别去拉流/投影，直接铺一屏空的输入页。
        if (isDraft()) {
            _state.value = _state.value.copy(snapshotReady = true)
            refreshCatalog()
            return
        }
        // 冷启动先铺缓存（离线也看得到历史），流式快照到了再覆盖。
        // 缓存也要过 timeline：落盘的历史里可能残留重复 seq（旧版本写入/宿主重投），
        // 直出会让 LazyColumn 的 key 撞车直接崩（2026-10-27 真机实测 seq-3415）。
        // 缓存里的「运行中」状态不可信：进程被杀/断线时写下的 running 没有对应的
        // turn/end 会到，原样铺出来就是一条永不熄灭的「正在分析请求」。
        // 冷启动一律按已结束读，真实运行态由随后的快照/事件重建。
        val cached = timeline(appVm.cache.loadMessages(appVm.hostId(), session.id)).map {
            if (it.running) it.copy(running = false) else it
        }
        if (cached.isNotEmpty()) {
            _state.value = _state.value.copy(messages = cached, snapshotReady = true)
        }
        // 正文与历史只有 v2 一条路（ChatSyncActor + Room 投影）。老链路的
        // openSession/累加器已经删除：它和 v2 会同时往 `messages` 里写，
        // 而 v1 那份的 seq 语义与 v2 不同，混在一起就是重复的正文。
        startActor(session.id)
        refreshProjections()
        refreshCatalog()
        // 人不在聊天页时到达的待答卡片只发了通知：从本地记账里回放，不再重开 $events。
        appVm.replayInteractions(this)
    }

    /** 会话投影（模型/权限/标题）：打开时拉一次，权限或模型变更后也要拉。 */
    fun refreshProjections() {
        refreshProjections(null)
    }

    /**
     * 拉一次会话投影。
     *
     * @param onValue 拿到投影后的额外动作（例如「这条消息是不是进了队列」）。
     *   投影失败或为空时不回调——调用方不能把它当成「已确认」。
     */
    private fun refreshProjections(onValue: ((Model.Session) -> Unit)?) {
        if (sessionId.isEmpty()) return
        appVm.gateway.sessionProjections(sessionId) { value, _ ->
            if (value != null) {
                val local = _state.value.session
                val ident = mergeProjectionIdentity(
                    projectedTitle = value.title,
                    projectedWorkspace = value.workspace,
                    localTitle = local.title,
                    localWorkspace = local.workspace,
                    cachedWorkspace = { appVm.workspaceOf(sessionId) },
                )
                val merged = value.copy(
                    title = ident.first,
                    workspace = ident.second,
                    // 队列与任务在 v2 路径下由归档流算出来（`agent/inbox/spliced` /
                    // `todo/write`）。这里必须**显式保留本地的**：`value` 是投影回来
                    // 的一整份 Session，`copy` 会把它的 queue/todos 一并带过来，
                    // 而 `/api/session/projections` 的 values 里通常没有 inbox 键 ⇒
                    // 拿到的是空列表。于是「点一次立即发送 → 成功 → refreshProjections
                    // → 整份覆盖」就把界面上所有排队卡片清空了，而宿主那边一条都没动
                    // （2026-11-01 真机复现：点完队列全消失，假宿主日志里一次 inbox
                    // 操作都没有）。v2 未激活时（没有归档记录）才采信投影那份。
                    queue = if (v2Active) local.queue else value.queue,
                    todos = if (v2Active) local.todos else value.todos,
                    // 子智能体目录**一律以本地那份为准**，不看 v2 有没有激活。
                    // 目录只有会话列表那条路带活体标记（插件在 `slimSession` 里把子代理
                    // 自己那条会话的 `running` 盖上去），而 `/api/session/projections`
                    // 是宿主原样下发、条目里没有状态；更糟的是投影响应**可能压根没有
                    // 这个键**，`value.copy` 会把缺键读成空目录，把刚拿到的那份抹掉
                    // （2026-11-10 本地实测：列表里明明带着 catalog，进会话后胶囊就是不出现）。
                    subagents = local.subagents,
                    // 队列与任务在 v2 路径上已经与正文同源（归档里的
                    // `agent/inbox/spliced` / `todo/write`）。这里若照旧把投影值接过来，
                    // 就会和 v2 算出的那份**互相覆盖**：两条路的时序不同，
                    // 界面上就是队列卡片一会儿有一会儿没。所以这两个字段
                    // 在 v2 预览路径下不再采用。
                    // 投影里没带 todos 时保留手上这份：`session/projections` 每次都会
                    // 回整份 values，但老宿主/适配器可能压根没有 todos 这个键。
                    // 真的被清空会走 `todo/write` 的 []，那条路照常覆盖。
                )
                publish { it.copy(session = merged) }
                onValue?.invoke(merged)
            }
        }
    }

    /** 模型目录：落缓存后设置页与选择器都能离线读。 */
    fun refreshCatalog() {
        appVm.gateway.modelCatalog { value, _ ->
            if (value != null) appVm.cache.saveCatalog(value)
        }
    }

    /** 读缓存的模型目录（离线也拿得到上次结果）。 */
    fun catalogSnapshot(): Model.ModelCatalog? = appVm.cache.loadCatalog()

    fun selectModel(choice: Model.ModelChoice) {
        if (sessionId.isEmpty()) return
        appVm.gateway.selectModel(sessionId, choice) { _, error ->
            if (error != null) Diag.log("chat", "selectModel failed: $error")
            refreshProjections()
        }
    }

    /** 权限预设：宿主没有独立 RPC，桌面端也发 /permission 斜杠命令，必须走 commands/execute。 */
    fun setPermission(preset: String) {
        if (sessionId.isEmpty()) return
        appVm.gateway.executeCommand(sessionId, "/permission $preset") { _, error ->
            if (error != null) Diag.log("perm", "setPermission failed: $error")
            // 不等宿主下一次 push，就地刷新投影。
            refreshProjections()
        }
    }

    /** 输入草稿：按会话存本地缓存。 */
    fun draft(): String = appVm.cache.draft(appVm.hostId(), draftKey())

    // ============================================================
    // 待发附件
    // ============================================================

    /**
     * 读一个刚选中的 Uri 进待发区。
     *
     * 解码与压缩在 [AttachmentLoader] 的单线程里做，这里只负责把结果并进状态。
     * @param asImage 相册/拍照传 true（强制按图片处理）；文件入口传 false，按 MIME 判断。
     */
    fun attach(context: android.content.Context, uri: android.net.Uri, asImage: Boolean) {
        publish { it.copy(attaching = true) }
        AttachmentLoader.load(context, uri, asImage) { result ->
            publish { current ->
                val error = result.error
                if (error != null || result.attachment == null) {
                    current.copy(attaching = false)
                        .also { appVm.reportNote(error ?: "附件读取失败") }
                } else {
                    current.copy(
                        pending = current.pending + Pending(result.attachment, result.preview),
                        attaching = false,
                    )
                }
            }
        }
    }

    fun removePending(index: Int) {
        publish { current ->
            if (index !in current.pending.indices) current
            else current.copy(pending = current.pending.filterIndexed { i, _ -> i != index })
        }
    }

    /**
     * 文件类附件在发送前换 receiptId。
     *
     * 图片不需要这一步（直接 base64 内联），只有非图片附件要先落到宿主的暂存区。
     * 一串附件逐个串行上传：并发上传在弱网下只会互相拖慢，而且宿主暂存区
     * 本来就是按会话排队的。
     */
    private fun uploadPending(
        list: List<Pending>,
        index: Int,
        done: (attachments: List<Model.Attachment>?, error: String?) -> Unit,
    ) {
        if (index >= list.size) {
            done(list.map { it.attachment }, null)
            return
        }
        val item = list[index]
        if (!needsUpload(item.attachment)) {
            uploadPending(list, index + 1, done)
            return
        }
        publish { it.copy(attaching = true) }
        appVm.gateway.uploadAttachment(sessionId, item.attachment) { receipt, error ->
            if (error != null || receipt == null) {
                publish { it.copy(attaching = false) }
                done(null, error ?: "附件上传失败")
                return@uploadAttachment
            }
            item.attachment.receiptId = receipt
            uploadPending(list, index + 1, done)
        }
    }

    /** 图片内联，其余都要先换 receiptId。 */
    private fun needsUpload(attachment: Model.Attachment): Boolean {
        if (attachment.receiptId.isNotEmpty()) return false
        return attachment.mediaType !in setOf("image/png", "image/jpeg", "image/webp", "image/gif")
    }

    fun saveDraft(text: String) {
        val key = draftKey()
        if (key.isEmpty()) return
        appVm.cache.saveDraft(appVm.hostId(), key, text)
    }

    fun loadEarlier() {
        if (_state.value.loadingEarlier) return
        val live = actor
        if (v2Active && live != null) {
            // v2：历史在本地 SQLite（与中继 1:1，每会话 2000 条），翻历史就是换个
            // 窗口读同一张表。走网络不但慢，还会让「往上滑」悄悄消耗流量与额度。
            if (!live.hasEarlier) {
                publish { it.copy(loadingEarlier = false, hasMore = false) }
                return
            }
            publish { it.copy(loadingEarlier = true) }
            actorScope.launch {
                val added = runCatching { live.loadEarlier() }.getOrDefault(0)
                appVm.runOnMain {
                    renderActor()
                    publish { it.copy(loadingEarlier = false, hasMore = added > 0 || live.hasEarlier) }
                }
            }
            return
        }
        // 没有 v2 actor（草稿、或还没连上）：本地没有别的正文来源，到此为止。
        // v1 的 /page 通道已随 SessionAccumulator 一起删除。
        publish { it.copy(loadingEarlier = false) }
    }

    /**
     * 发送一条 prompt：把待发区的附件一起带上，先挂本地回声。草稿会话在这一步才真正创建。
     *
     * @param steer 长按发送按钮时为 true：让宿主把这句话**插进正在跑的这一轮**，
     *   而不是排在它后面。空闲时宿主两种模式等价，所以不需要调用方先判 running。
     */
    fun send(text: String, steer: Boolean = false) {
        val pending = _state.value.pending.filter { it.error.isEmpty() }
        // 发出去就从待发区清掉：留着让人以为还没发，而实际上链接已经走了。
        if (pending.isNotEmpty()) publish { it.copy(pending = emptyList()) }
        submit(text, pending, null, steer)
    }

    /**
     * 发送的统一入口。
     *
     * @param pending 待发附件。文件类要先换 receiptId，所以真正发出去之前多一步。
     * @param existing 已经在时间线上的回声（重发时用）。为空表示要新建一条回声。
     * @param steer 立即插话（见 [send]）；重发永远走默认的排队。
     */
    private fun submit(
        text: String,
        pending: List<Pending>,
        existing: Model.Message?,
        steer: Boolean = false,
    ) {
        if (text.length > 32000) return
        val trimmed = text.trim()
        if (trimmed.isEmpty() && pending.isEmpty()) return
        if (!isDraft()) {
            deliver(trimmed, pending, existing, steer)
            return
        }
        // 草稿：先让宿主建会话，拿到 id 再订阅、再发。
        // 建不出来就挂一条失败回声——输入框已经被 Composer 清空了，只写一条
        // note 用户看不见（聊天页不显示 note），得让这句话留在时间线上可重发。
        Diag.log("chat", "draft create len=${trimmed.length}")
        publish { it.copy(sending = true) }
        appVm.gateway.createSession { created, error ->
            if (created == null) {
                if (existing != null) {
                    replaceEcho(existing, existing.copy(sending = false, failed = true))
                } else {
                    val failed = Model.Message.user(trimmed).apply { failed = true }
                    failed.images.addAll(
                        pending.map { Model.ImageRef(name = it.attachment.name, local = it.preview?.bitmap) }
                    )
                    publish {
                        it.copy(
                            messages = timeline(it.messages + failed),
                            sending = false,
                            running = false,
                        )
                    }
                }
                publish { it.copy(sending = false, running = false) }
                appVm.reportNote(error ?: "创建会话失败")
                return@createSession
            }
            bindCreated(created)
            deliver(trimmed, pending, existing, steer)
        }
    }

    /**
     * 上传还在等 receiptId 的附件，齐了再真正发 prompt。
     *
     * 没有附件、或者全是图片时 [uploadPending] 会立刻回调，这一层就只是一次直通。
     */
    private fun deliver(
        trimmed: String,
        pending: List<Pending>,
        existing: Model.Message?,
        steer: Boolean = false,
    ) {
        if (pending.isEmpty()) {
            dispatch(trimmed, emptyList(), existing, steer)
            return
        }
        uploadPending(pending, 0) { list, error ->
            if (error != null || list == null) {
                // 上传失败不该把已经打好的字丢掉：退回待发区，让用户再点一次发送。
                publish { it.copy(pending = pending, sending = false, running = false) }
                appVm.reportNote(error ?: "附件上传失败")
                return@uploadPending
            }
            // 把本地缩略图带过去：图片的 attachmentId 要等宿主回显才有，
            // 而字节此刻就在手里（见 Model.ImageRef.local）。
            val previews = pending.mapNotNull { p ->
                p.preview?.bitmap?.let { bmp -> p.attachment.name to bmp }
            }.toMap()
            dispatch(trimmed, list, existing, steer, previews)
        }
    }

    /** 草稿拿到真实 id 之后的接续：补 session、订阅流、进列表。 */
    private fun bindCreated(created: Model.Session) {
        sessionId = created.id
        publish { it.copy(session = created, snapshotReady = true) }
        appVm.onDraftCreated(created)
        startActor(created.id)
        refreshProjections()
        refreshCatalog()
        appVm.replayInteractions(this)
    }

    /** 真正发出去：挂回声（或复用重发的那条）+ 调 prompt。 */
    private fun dispatch(
        trimmed: String,
        attachments: List<Model.Attachment>,
        existing: Model.Message?,
        steer: Boolean = false,
        /** 附件名 → 本地缩略图。只有刚发出的这条回声用得上，见 [Model.ImageRef.local]。 */
        previews: Map<String, android.graphics.Bitmap> = emptyMap(),
    ) {
        Diag.log("chat", "submit len=${trimmed.length} attachments=${attachments.size} steer=$steer")
        if (existing != null) {
            pendingEcho = existing
            publish { it.copy(sending = true, running = true) }
            transmit(existing, trimmed, attachments, steer)
            return
        }
        val echo = Model.Message.user(trimmed).apply { sending = true }
        // 本地回声带上刚读出来的缩略图字节：`attachmentId` 此刻还是空串（要等宿主
        // 回显 durable 记录才有），拿空 id 去请求只会得到「图片加载失败」
        // （2026-11-01 用户实测）。字节就在手里，直接画。
        echo.images.addAll(
            attachments.map { att ->
                Model.ImageRef(
                    name = att.name,
                    local = previews[att.name],
                )
            }
        )
        pendingEcho = echo
        publish {
            it.copy(
                messages = timeline(it.messages + echo),
                sending = true,
                running = true,
            )
        }
        transmit(echo, trimmed, attachments, steer)
    }

    /**
     * 把一条已在时间线上的回声真正发出去。
     *
     * 与 [dispatch] 的分工：dispatch 负责新建回声，这里只发送并回填成败——
     * 重发时回声已经在列表里，不能再加一条。
     *
     * @param steer true = 插进正在跑的这一轮（长按发送）；false = 排队。
     */
    private fun transmit(
        echo: Model.Message,
        text: String,
        attachments: List<Model.Attachment>?,
        steer: Boolean = false,
    ) {
        // T1：手指按下到真正调用之间的时间也算进去——用户感知的是「点了之后多久
        // 有反应」，不是「请求发出之后多久」。
        // `Model.Message` 没有 clientMsgId 字段（v2 记录里有，但本地回声没有），
        // 所以这里现造一个只用一次的 id：它唯一的用途是把同一句话的几跳串起来。
        val traceId = MessageTrace.begin(
            clientMsgId = UUID.randomUUID().toString(),
            sessionId = sessionId,
            now = echo.time,
        ).clientMsgId
        // v2 优先：控制流上的 CMD 由插件落到宿主，插件按 cmdId 缓存 ack，
        // 同一条命令重发只是重放结果。命令通了就不再走 v1 的 rpc——那条路中继
        // 直打宿主，插件不知道它存在，「只执行一次」没有任何地方保证。
        val actorNow = actor
        if (actorNow != null) {
            sendViaControl(actorNow, echo, text, attachments, steer, traceId)
            return
        }
        appVm.gateway.sendPrompt(
            sessionId = sessionId,
            text = text,
            attachments = attachments,
            mode = if (steer) "steer" else "queue",
        ) { _, error ->
            // T4：宿主答复了。v1 这条路不带插件耗时，所以 T3 会缺——那正是
            // 「这句话走的是还没搬上控制流的旧接口」的可观测痕迹。
            if (error == null) MessageTrace.hostAccepted(traceId, pluginMs = null)
            else MessageTrace.noteFailed(traceId, "send-failed")
            // 回声的成败要换出新实例才能触发重组（可变 data class 就地改字段看不见）。
            replaceEcho(echo, echo.copy(sending = false, failed = error != null))
            publish { it.copy(sending = false) }
            // 宿主在跑时这句话进了 inbox，桌面端把它画成输入框上方的排队卡片。
            // 卡片由 `agent/inbox/spliced` 增量维护，但那个事件要新插件才会透传
            // （老插件的 omit 会把 payload 抹成 omitted），所以成功后再拉一次投影兜底：
            // 一条 RPC 换「排队卡片一定出现」，比让用户以为消息发丢了划算。
            if (error == null) refreshProjections()
        }
    }

    /**
     * 走 v2 控制流发送（`CMD op=prompt`）。
     *
     * <p>载荷形状与 v1 的 `/api/session/prompt` 完全一致——插件把它原样转给宿主，
     * 所以两条路对宿主是同一件事，切换不需要改宿主侧的期望。
     *
     * <p>控制流不可写（还没连上）时**回落到 v1**：这里的目标是「消息能发出去」，
     * 而不是「证明 v2 通了」。真正的可观测痕迹留在六跳打点上——走 v1 的那条
     * 没有 `T3_PLUGIN`。
     */
    private fun sendViaControl(
        live: ChatSyncActor,
        echo: Model.Message,
        text: String,
        attachments: List<Model.Attachment>?,
        steer: Boolean,
        traceId: String,
    ) {
        // 这个 requestId 是**宿主侧幂等键**，必须同时用在 v2 的 CMD 载荷和 v1 的
        // 回落 RPC 上。两条路各造一个 UUID 的后果不是理论问题：控制流把命令发出去了、
        // 但 ack 在 3 秒内没回来（隧道忙 / 插件正卡在别的活上），回落再发一遍，
        // 宿主按两个不同的 requestId 收成**两条**排队消息——用户在手机上看到
        // 「发一次，排队 2 条」（2026-10-30 真机）。
        // 复用同一个 id，宿主自己就能认出是重复投递。
        val requestId = UUID.randomUUID().toString()
        val payload = buildJsonObject {
            val parts = buildJsonArray {
                if (text.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", text) })
                attachments?.forEach { a ->
                    if (a.isFile) {
                        add(buildJsonObject { put("type", "file"); put("receiptId", a.receiptId) })
                        return@forEach
                    }
                    if (a.data.isEmpty()) return@forEach
                    add(buildJsonObject {
                        put("type", "image")
                        put("mediaType", a.mediaType)
                        put("data", android.util.Base64.encodeToString(a.data, android.util.Base64.NO_WRAP))
                        if (a.name.isNotEmpty()) put("name", a.name)
                    })
                }
            }
            put("sessionId", sessionId)
            put("requestId", requestId)
            put("mode", if (steer) "steer" else "queue")
            put("content", parts)
        }
        live.command("prompt", payload, raceMs = ChatSyncActor.PROMPT_ACK_RACE_MS) { ok, error ->
            appVm.runOnMain {
                if (ok) {
                    MessageTrace.hostAccepted(traceId, pluginMs = null)
                } else if (error == "not-connected") {
                    // 控制流还没连上：宿主必然一个字节都没收到，回落 v1 重发同一条
                    // 回声（requestId 原样带过去）是安全的。
                    appVm.gateway.sendPrompt(
                        sessionId = sessionId,
                        text = text,
                        attachments = attachments,
                        mode = if (steer) "steer" else "queue",
                        requestId = requestId,
                    ) { _, e2 ->
                        if (e2 == null) MessageTrace.hostAccepted(traceId, pluginMs = null)
                        else MessageTrace.noteFailed(traceId, "fallback-failed")
                        replaceEcho(echo, echo.copy(sending = false, failed = e2 != null))
                        publish { it.copy(sending = false) }
                        if (e2 == null) refreshProjections()
                    }
                    return@runOnMain
                } else {
                    Diag.log("chat", "v2 prompt failed: $error")
                    MessageTrace.noteFailed(traceId, "v2-$error")
                }
                replaceEcho(echo, echo.copy(sending = false, failed = !ok))
                publish { it.copy(sending = false) }
                if (ok) refreshProjections()
            }
        }
    }

    /**
     * 用新实例原位替换一条本地回声，并同步 pendingEcho 的身份。
     *
     * <p>匹配规则见 [sameLocalEcho]：**不能只比对象身份** —— `_state.value.messages`
     * 里是 `snapshot()` 副本，只比 `===` 的话 `sending` 永远清不掉，界面会一直
     * 挂「等待发送」。
     */
    private fun replaceEcho(old: Model.Message, new: Model.Message) {
        val list = _state.value.messages.toMutableList()
        val idx = echoSlotOf(list, old)
        if (idx >= 0) list[idx] = new
        pendingEcho?.takeIf { sameLocalEcho(it, old) }?.let { pendingEcho = new }
        publish { it.copy(messages = list) }
    }

    /**
     * 失败回声的原样重发。
     *
     * 草稿态（创建会话那一步就失败了）不能直接 `sendPrompt("")`：得先把会话建出来
     * 再发，所以整条走 [submit] 的草稿分支，并把这条回声交给它复用——不能再挂一条。
     */
    /**
     * 长按气泡「本地删除」：只在**这台手机上**抹掉这条。
     *
     * <p>为什么是本地而不是让宿主也删：宿主的 session log 是 append-only 的追加日志，
     * 没有删除接口；而且别的设备、电脑端的界面都还该看见它。手机上删掉的是「我这儿
     * 记着的那一份副本」，与中继归档、宿主原文都无关。
     *
     * <p>不能删的只有一种：`seq <= 0` 的本地回声 —— 它还没被宿主确认、根本不在归档里，
     * 等它落定自己会变成真记录；这时删掉只是把它从界面上藏起来，反而更难排查发没发出去。
     *
     * <p>删掉用户提问时，那一轮的助手回复会变成「没有提问的回答」—— 这是可以接受的：
     * 时间线本来就允许中间缺几条（压缩、离载存根都会造成同样的空缺），投影器按 seq
     * 排位置，不按「问答必须成对」做校验。用户想清掉哪条就清掉哪条。
     *
     * 落盘由 [ChatSyncActor.deleteLocal] 负责，它删完会重读尾部并触发重投影 ——
     * 手工从 `messages` 里摘一条是没用的，下次任何重读都会把它捡回来。
     */
    fun deleteLocal(message: Model.Message) {
        val seq = message.seq
        // `seq <= 0` = 本地回声：排队中的消息还没被宿主写成 `user/message`，
        // 界面这条只是「我刚发出去的那句话」的影子，Room 里没有对应行。
        //
        // 早先这里是直接拒绝（「等它落定后再删」），代价是**排队中的消息删不掉** ——
        // 而用户恰恰最想删的就是它们（2026-11-01 真机反馈：两条排队的消息长按没有
        // 删除菜单）。现在改成两件事一起做：①把它从 `messages` 里摘掉（界面立刻消失）；
        // ②如果宿主 inbox 里还挂着对应的那条，一并撤下来，否则开跑那一轮它又会冒回来。
        if (seq <= 0) {
            dropLocalEcho(message)
            return
        }
        val live = actor ?: return
        actorScope.launch {
            val ok = try {
                live.deleteLocal(seq)
            } catch (e: Exception) {
                false
            }
            if (!ok) appVm.reportNote("这条已经不在本地了")
        }
    }

    /**
     * 摘掉一条还没落定的本地回声。
     *
     * <p>两处都要清，否则「删了又回来」：
     * 1. `_state.messages` —— 界面这一份；
     * 2. `pendingEcho` —— [renderActor] 每次投影完都会用
     *    [adoptPendingEchoes] 把还没被时间线认领的回声补回列表尾部；
     *    只改 `messages` 的话，下一帧它就从 `pendingEcho` 里复活。
     *
     * <p>**宿主 inbox 里那条也要撤**：本地回声只有文本，没有宿主分配的 itemId，
     * 但队列卡片里有（`QueuedMessage.id`）。所以按文本在队列里找同一条 —— 找到就
     * 走 [removeQueued] 的正规通路把它从 inbox 撤下来。找不到说明它已经开始跑了
     * （或者压根没进过队列），这时只能提示用户去电脑端取消，不能假装删干净了。
     */
    private fun dropLocalEcho(message: Model.Message) {
        if (pendingEcho === message || pendingEcho?.text == message.text) pendingEcho = null
        publish { state -> state.copy(messages = state.messages.filterNot { it === message || (it.seq <= 0 && it.text == message.text) }) }
        val queued = _state.value.session.queue.firstOrNull { it.text == message.text }
        if (queued != null && queued.id.isNotEmpty()) {
            // 队列里还挂着：撤掉它，宿主那边就不会再跑了。
            removeQueued(queued)
            appVm.reportNote("已从队列移除")
        } else {
            appVm.reportNote("已从列表移除。它可能已经开跑，电脑端那边仍需自行取消")
        }
    }

    fun retry(echo: Model.Message) {
        val resent = echo.copy(sending = true, failed = false)
        replaceEcho(echo, resent)
        pendingEcho = resent
        publish { it.copy(sending = true) }
        submit(resent.text, emptyList(), resent)
    }

    fun cancel() {
        if (sessionId.isEmpty()) return
        appVm.gateway.cancel(sessionId) { _, _ -> }
    }

    // ============================================================
    // 排队消息（宿主 inbox）
    // ============================================================

    /** 当前排队中的消息，顺序即宿主领取顺序（next-turn 在前）。 */
    fun queue(): List<Model.QueuedMessage> = _state.value.session.queue

    /**
     * 编辑一条排队消息的正文。
     *
     * 宿主只收纯文本（`edit` 的 content 里出现图片会被 `session/attachment-invalid` 拒），
     * 所以这里也只发文本；原消息带的图片宿主会一并丢掉，这与桌面端一致。
     */
    /**
     * 走 v2 控制流改队列（edit / remove / steer）。
     *
     * <p>控制流没连上时回落到 v1 [RelayGateway.updateQueue]。两条路的失败语义一样：
     * 报错之后**必须重读队列**——失败往往说明这条已经不是待办了（被领走 / 这轮已
     * 跑完），只报错不重读会让卡片一直挂在那里。
     */
    private fun queueViaControl(itemId: String, kind: String, text: String?, fallback: () -> Unit) {
        val live = actor
        if (live == null) {
            fallback()
            return
        }
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("itemId", itemId)
            put("action", buildJsonObject {
                put("kind", kind)
                if (kind == "edit" && text != null) put("text", text)
                // steer 带上正文：插件要用它判断宿主在不在跑。
                //
                // <p>宿主空闲时这条消息本来就会被立刻领走，`updateQueue` 的 steer 却
                // 只认 `agent.status === "running"`，会直接抛 `session/steer-unavailable`。
                // 用户点「立即发送」要的是「现在就发」，不是「报个错」——所以由插件
                // 决定：在跑就 steer，没跑就拿这段正文直接 prompt。
                if (kind == "steer" && text != null) put("text", text)
            })
        }
        // 等待态在**发出之前**就置位：宿主可能几百毫秒才回，先转圈再等。
        // 失败/成功两条路都必须把它清掉，否则卡片会永久转圈（比没有转圈更糟）。
        markQueuedBusy(itemId, true)
        live.command("queue", payload) { ok, error ->
            appVm.runOnMain {
                if (ok) {
                    // **插件确认成功后才从 UI 上删卡片**，并且当场删。
                    //
                    // 2026-11-02 用户口径：「点击立即发送后，加载图标需要等到插件响应
                    // 确实 steer 成功，手机端再删除排队消息 UI」。
                    //
                    // 原实现是「ack 一到就收转圈，卡片等宿主的 inbox/spliced 归档帧」——
                    // 于是中间有一段空档：圈没了、卡片还在，用户以为没点上又点一次。
                    // 现在两件事绑在一起：ack 成功 ⇒ 清掉这一条 + 收圈，一步到位。
                    //
                    // 只对 steer/remove 这么做：edit 是改内容、卡片要留着。
                    if (kind == "steer" || kind == "remove") dropQueuedLocally(itemId)
                    markQueuedBusy(itemId, false)
                    refreshProjections()
                    return@runOnMain
                }
                markQueuedBusy(itemId, false)
                if (error == "not-connected" || error == "ack-timeout") {
                    fallback()
                    return@runOnMain
                }
                appVm.reportNote(error ?: "队列操作没送到电脑")
                // 失败恰恰最需要重读：`queue-item-not-found` 意味着它已经被领走，
                // `steer-unavailable` 意味着这轮已经跑完、消息落到了 next-turn。
                refreshProjections()
            }
        }
    }

    /**
     * 从队列里摘掉一条（插件已确认）。
     *
     * <p>与「等宿主的 `inbox/spliced` 归档帧」不同：那条路要等插件落库 → 中继归档 →
     * 手机同步，几百毫秒到几秒。用户点了「立即发送 / 删除」之后，插件回了 ack 就说明
     * 宿主那边已经执行了，界面没有理由再拖。
     */
    private fun dropQueuedLocally(itemId: String) {
        if (itemId.isEmpty()) return
        publish { st ->
            val next = st.session.queue.filterNot { it.id == itemId }
            if (next.size == st.session.queue.size) st
            else st.copy(session = st.session.copy(queue = next.toMutableList()))
        }
    }

    /**
     * 置/清一条排队消息的等待态。
     *
     * <p>用 `Set<String>` 而不是单个 id：两条卡片的操作可能同时在飞（用户手快）。
     * 集合没变就不 publish —— 这条路径每次点击会走两遍（置位 + 清除），
     * 无条件 publish 会让整个 composer 白重组一次。
     */
    private fun markQueuedBusy(itemId: String, busy: Boolean) {
        if (itemId.isEmpty()) return
        val current = _state.value.queuedBusy
        val next = if (busy) current + itemId else current - itemId
        if (next == current) return
        publish { it.copy(queuedBusy = next) }
    }

    fun editQueued(item: Model.QueuedMessage, text: String) {
        val trimmed = text.trim()
        if (sessionId.isEmpty() || item.id.isEmpty() || trimmed.isEmpty()) return
        if (trimmed == item.text) return
        queueViaControl(item.id, "edit", trimmed) {
        markQueuedBusy(item.id, true)
        appVm.gateway.updateQueue(sessionId, item.id, "edit", trimmed) { _, error ->
            markQueuedBusy(item.id, false)
            // 失败也要重读队列。宿主回 `session/queue-item-not-found` 的意思是
            // 「这条已经不是待办」——多半它刚被领走开始跑了。只报错不重读，
            // 卡片会一直挂在那里，用户点删除也只得到同一句错误（2026-10-30
            // 模拟器实测：卡片挂了两分钟，宿主那边这一轮早就跑完了）。
            if (error != null) appVm.reportNote(error)
            refreshProjections()
        }
        }
    }

    /** 删掉一条排队消息。 */
    fun removeQueued(item: Model.QueuedMessage) {
        if (sessionId.isEmpty() || item.id.isEmpty()) return
        queueViaControl(item.id, "remove", null) {
            markQueuedBusy(item.id, true)
            appVm.gateway.updateQueue(sessionId, item.id, "remove") { _, error ->
                if (error == null) dropQueuedLocally(item.id)
                markQueuedBusy(item.id, false)
                // 同 [editQueued]：删不掉恰恰最需要重读——说明它已经不在队列里了。
                if (error != null) appVm.reportNote(error)
                refreshProjections()
            }
        }
    }

    /**
     * 把一条排队消息提前插进正在跑的这轮（桌面端排队卡片上的「↑」）。
     *
     * 只有 `next-turn` 能被插话，且宿主当前必须真的在跑——否则会回
     * `session/steer-unavailable`，文案在 HostErrors 里已经写好了。
     */
    fun steerQueued(item: Model.QueuedMessage) {
        if (sessionId.isEmpty() || item.id.isEmpty()) return
        queueViaControl(item.id, "steer", item.text) {
            markQueuedBusy(item.id, true)
            appVm.gateway.updateQueue(sessionId, item.id, "steer") { _, error ->
                if (error == null) dropQueuedLocally(item.id)
                markQueuedBusy(item.id, false)
                // `session/steer-unavailable` 表示这一轮已经跑完、没有可插的地方，
                // 那条消息这时通常已经落到 next-turn 了。重读才看得见它的新位置。
                if (error != null) appVm.reportNote(error)
                refreshProjections()
            }
        }
    }

    /**
     * 回答审批。
     *
     * 这里**不重开 `$events`**。以前回答完会调 `resyncInteractions()`「验真」，可宿主
     * 收到 `kind:"result"` 就当场结算，重开只会让还没结算的卡被重投一遍；而重投出来的
     * 同一张卡又被当成新卡片画出来，用户点一次卡就复活一次——就是「一直在弹审批」。
     * 现在改由 [AppViewModel.markAnswering] 记账，重投的陈旧帧一律丢弃。
     */
    fun answerApproval(approval: Model.Approval, allowOnce: Boolean) {
        // 归档重建的卡：手上这个 eventId 是 `sessionId:seq`，宿主的 `$events`
        // 根本不认。按 callId 找回 `$events` 那条连接发下来的真 id——两边唯一
        // 共有的键就是 callId（见 AppViewModel.eventIdByCallId 的 KDoc）。
        val usable = appVm.eventIdOfCall(approval.callId).ifEmpty { approval.eventId }
        if (usable.isEmpty() || usable == approval.eventId && !approval.answerable) {
            // 找不回来就别装成功：这张卡只可能来自历史归档，宿主那次 waterfall
            // 早就过期了（或手机重装过）。点下去只会让用户以为批过了、那一轮却还卡着。
            appVm.reportNote("这条审批来自历史记录，需要电脑端重新发起")
            return
        }
        appVm.markAnswering(usable)
        publish { it.copy(approvals = it.approvals.filterNot { a -> a.eventId == approval.eventId }) }
        val outcome = if (allowOnce) "allowed-once" else "rejected"
        answerViaControl(usable, outcome) {
            // v1 的 `$events/result` 旁路已随 `/native/v1/*` 一起删除，没有第二条路可退。
            // 控制流不可用时只能如实告诉用户，并把卡放回去让他还能再点。
            appVm.forgetAnswering(usable)
            publish {
                it.copy(approvals = it.approvals.filterNot { a -> a.eventId == approval.eventId } + approval)
            }
            appVm.reportNote("控制流没连上电脑，审批没有送出，请稍后重试")
        }
    }

    /**
     * 走 v2 控制流回答一张 waterfall（审批或提问）。
     *
     * <p>`clientId` **不由手机填写**：插件用它自己 `$events` 连接的 id 覆盖。宿主
     * 的规则是「回答者必须是收到这条 waterfall 的那个连接」，手机填自己的 id 会被
     * 静默丢弃——卡片下次重连又回来，看上去像「点了没用」。
     *
     * <p>控制流没连上时回落到 [fallback]（v1 的 `$events/result`）：卡片正等着有人
     * 回答，这时候在意「走的是哪条路」比在意「用户点的能不能生效」更没意义。
     */
    private fun answerViaControl(
        eventId: String,
        outcome: Any,
        fallback: () -> Unit,
    ) {
        val live = actor
        if (live == null) {
            fallback()
            return
        }
        val payload = buildJsonObject {
            put("eventId", eventId)
            put("outcome", buildJsonObject {
                put("kind", "result")
                put("value", when (outcome) {
                    is String -> JsonPrimitive(outcome)
                    is JsonObject -> outcome
                    else -> JsonPrimitive(outcome.toString())
                })
            })
        }
        live.command("interaction", payload) { ok, error ->
            appVm.runOnMain {
                if (ok) return@runOnMain
                if (error == "not-connected" || error == "ack-timeout") {
                    fallback()
                    return@runOnMain
                }
                Diag.log("chat", "v2 interaction failed: $error")
                // 没送达就得让这张卡能再出现，否则用户以为答过了、那一轮还卡着。
                appVm.forgetAnswering(eventId)
                appVm.reportNote("没有送到电脑：$error")
            }
        }
    }

    /** 提交提问卡片：selected/custom 已经直接记在 Question 实例上。顺序理由同上。 */
    fun answerQuestions(card: Model.QuestionCard) {
        appVm.markAnswering(card.eventId)
        publish { it.copy(questions = it.questions.filterNot { q -> q.eventId == card.eventId }) }
        val answers = buildJsonObject {
            put("answers", buildJsonArray {
                for (q in card.questions) {
                    add(buildJsonObject {
                        put("id", q.id)
                        put("selected", buildJsonArray {
                            for (label in q.selected) add(JsonPrimitive(label))
                        })
                        if (q.custom.isNotBlank()) put("custom", q.custom.trim())
                    })
                }
            })
        }
        answerViaControl(card.eventId, answers) {
            // 同 answerApproval：v1 旁路已删，控制流不可用只能如实告知并放回卡片。
            appVm.forgetAnswering(card.eventId)
            publish {
                it.copy(questions = it.questions.filterNot { q -> q.eventId == card.eventId } + card)
            }
            appVm.reportNote("控制流没连上电脑，回答没有送出，请稍后重试")
        }
    }

    /** EventsListener 转发：新审批/提问卡片到达。返回 false 表示这张卡不属于本会话。 */
    fun onApproval(approval: Model.Approval): Boolean {
        if (!interactionBelongs(approval.agentId, sessionId)) {
            Diag.log("events", "approval skip agentId=${approval.agentId} session=$sessionId")
            return false
        }
        // 刚点过、还没拿到回执的卡被宿主重投：丢掉，别让它在界面上复活。
        if (appVm.isAnswering(approval.eventId)) return true
        publish {
            it.copy(
                approvals = it.approvals.filterNot { a -> a.eventId == approval.eventId } + approval,
            )
        }
        return true
    }

    fun onQuestion(card: Model.QuestionCard): Boolean {
        if (!interactionBelongs(card.agentId, sessionId)) {
            Diag.log("events", "question skip agentId=${card.agentId} session=$sessionId")
            return false
        }
        if (appVm.isAnswering(card.eventId)) return true
        // 宿主重投是重新序列化一遍 request，解析出的是一批全新的 Question 实例
        // （selected 空、custom 空）。同一张卡已经在屏上时把草稿搬过去，别让一次
        // 重连把用户填了一半的选择清空——审批卡没有草稿字段，所以只有提问卡要。
        val onScreen = _state.value.questions.firstOrNull { it.eventId == card.eventId }
        if (onScreen != null) carryQuestionDrafts(onScreen, card)
        publish {
            it.copy(
                questions = it.questions.filterNot { q -> q.eventId == card.eventId } + card,
            )
        }
        return true
    }

    /**
     * 从 Room 账本里恢复一张卡（跨进程）。
     *
     * <p>与 [onApproval]/[onQuestion] 分开是因为恢复的语义不同：那张卡上一轮就在等，
     * 用户可能已经看过、甚至填了一半草稿。走同一去重口径（同 eventId 只留一份），
     * 但不发通知——通知对「重新打开 App 看到还在等的东西」是噪音。
     */
    fun onRestored(card: Model.Interaction) {
        if (!interactionBelongs(card.agentId, sessionId)) return
        when (card) {
            is Model.Approval -> publish {
                it.copy(approvals = it.approvals.filterNot { a -> a.eventId == card.eventId } + card)
            }

            is Model.QuestionCard -> publish {
                it.copy(questions = it.questions.filterNot { q -> q.eventId == card.eventId } + card)
            }
        }
    }

    fun onInteractionCancel(eventId: String) {
        publish {
            it.copy(
                approvals = it.approvals.filterNot { a -> a.eventId == eventId },
                questions = it.questions.filterNot { q -> q.eventId == eventId },
            )
        }
    }

    /** 会话被改名：顶栏跟着换，别等下次重开这一页。 */
    fun onTitleChanged(sessionId: String, title: String) {
        if (sessionId != this.sessionId) return
        publish { it.copy(session = it.session.copy(title = title)) }
    }

    fun onSessionStatus(running: Boolean) {
        publish { it.copy(running = running, streamingText = if (running) it.streamingText else "") }
    }

    fun close() {
        // 落一次盘再走：发送后立刻返回列表时，宿主回显（durable user/message）往往
        // 还没从流上到。不关流：留给网络到达后由 SSE/差量同步补进缓存。流的生命
        // 周期由下一次 openSession 的 follow() 收（它首行就 closeStream），或切主机/
        // 退出登录时统一关——提前关会把「回显还漂在路上」的那条消息永远丢在门外
        // （2026-10-28 真机复现：重新进会话，刚才发的气泡不见了）。
        persist()
        stopActor()
        pendingEcho = null
        // 看门狗**不停**：人离开聊天页，流照样留着（上面那段），那这条流半死时照样
        // 需要一个东西发现它。以前这里 pauseWatchdog()，等于「人一走就没人管这条
        // 连接了」——回到会话看到的还是离开那一刻的内容，直到下一次发消息才暴露。
        // 用户明确接受后台持续拉取（2026-10-30），代价是半死时会在后台重放一次快照；
        // 重沿用旧累加器的时间线铺底（见 RelayGateway.reopen），不会退回旧版本。
        // 生命周期不变：切主机 / 退出登录 / 换会话时 RelayGateway 会停掉它。
    }

    private fun persist() {
        if (sessionId.isEmpty()) return
        // v1 累加器的「快照分批期间不落盘」闸门随它一起删除：v2 的落盘是
        // `ChatSyncActor` 按记录逐条 `timeline.commit`，不经过这里。
        // 这里只剩列表预览要跟着正文走。
        val messages = _state.value.messages
        appVm.cache.saveMessages(appVm.hostId(), sessionId, messages)
        // 首页那一行的首行预览就是从这里来的（列表与正文同源），顺手刷新，
        // 免得返回列表时还显示上一轮的句子。
        appVm.syncPreview(appVm.hostId(), sessionId, messages)
    }

    /**
     * 快照整表替换时，把还没被宿主回显顶替的本地回声（seq=0 的用户行）挑出来：
     * 它们不在快照里，不拼回去气泡就从界面消失。
     */
    private fun localPendingEcho(state: ChatState): List<Model.Message> =
        state.messages.filter { it.seq <= 0 && it.role == Model.Role.USER && it.text.isNotEmpty() }
}

/**
 * 从「还没被回显顶掉的本地回声」里剔掉那些已经出现在投影结果里的。
 *
 * <p>v2 模式下同一条用户消息有两个来源：拖进时间线的那条 `USER_INPUT` 记录（有真实
 * seq），以及发送时立刻画的本地回声（seq=0）。v1 靠 `onMessage` 里的「同文本顶掉」
 * 收口，v2 把 `renderActor()` 的整表替换交给投影器之后，那段逻辑再也走不到——于是
 * 一句话在界面上出现两遍，而且上面那条永远停在「正在发送」。
 *
 * <p>按**文字计数**匹配而不是「出现即删」：用户完全可能连发两句一样的话，第二句
 * 还没落盘时不该被第一句的记录顺手删掉。顺序也重要——回声按发出先后排列，先匹配
 * 到的先收口。
 */
/**
 * 两条消息是不是「同一条本地回声」。
 *
 * <p>为什么不只比对象身份：`ChatViewModel` 的 `_state.value.messages` 里躺的是
 * `timeline()` 产出的 `snapshot()` **副本**，不是发送时那个实例。只比 `===` 会一声不响
 * 地什么都不换 —— `sending` 永远清不掉，宿主明明已经 ack 收下的消息在界面上一直挂
 * 「等待发送」（2026-11-07 模拟器实测：`chat submit` 之后 diag 里没有任何失败日志、
 * 说明 ack 是成功的，气泡却始终停在「等待发送」；真机上用户的原话是「这是之前排队
 * 已经发送的消息了」）。
 *
 * <p>本地回声的 `seq` 恒为 0，所以「按文本匹配」是安全的：真记录（`seq > 0`）不参与
 * 匹配，两条内容相同的消息不会互相冒充。[ChatViewModel.dropLocalEcho] 早就是这么比的，
 * `replaceEcho` 当时漏了。
 */
fun sameLocalEcho(a: Model.Message, b: Model.Message): Boolean =
    a === b || (a.seq <= 0 && b.seq <= 0 && a.text == b.text)

/** [sameLocalEcho] 的列表版：**先按身份、再按文本**，找不到返回 -1。 */
fun echoSlotOf(messages: List<Model.Message>, old: Model.Message): Int {
    val exact = messages.indexOfFirst { it === old }
    if (exact >= 0) return exact
    // 身份找不到（`_state` 里是副本）才退到文本。`seq > 0` 的真记录不参与。
    if (old.seq > 0) return -1
    return messages.indexOfFirst { it.seq <= 0 && it.text == old.text }
}

fun adoptPendingEchoes(
    pending: List<Model.Message>,
    projected: List<Model.Message>,
): List<Model.Message> {
    if (pending.isEmpty()) return pending
    val remaining = HashMap<String, Int>()
    for (message in projected) {
        if (message.role != Model.Role.USER || message.seq <= 0) continue
        remaining[message.text] = (remaining[message.text] ?: 0) + 1
    }
    val kept = ArrayList<Model.Message>(pending.size)
    for (echo in pending) {
        val left = remaining[echo.text] ?: 0
        if (left > 0) {
            remaining[echo.text] = left - 1
            continue
        }
        kept.add(echo)
    }
    return kept
}

/**
 * 时间轴排序：真实 seq 升序，本地行（seq≤0：回声/占位）保持插入顺序垫在尾部。
 *
 * 直接用 sortedBy { seq } 会把 seq=0 的本地回声甩到最前面（比所有历史都早）。
 */
/**
 * 同 seq 的旧投影替换：后到的那份顶掉先到的。
 *
 * **处理面板不参与**：面板与同回合的助手正文共用 seq（面板的真身份是
 * [Model.Message.panelId]，位置是 `order = seq - 0.25`），原来那句无差别
 * `removeAll { it.seq == ... }` 会把面板一起清掉，而清掉它的那次 persist 正好是
 * 唯一会落盘的路径 ⇒「深度思考 / 处理详情」永远进不了缓存（F-09：重进会话内容静默消失）。
 * seq<=0 是本地回声/占位，本来就没有「同 seq 顶掉」的语义，直接跳过。
 */
fun dropSupersededSameSeq(messages: MutableList<Model.Message>, incoming: Model.Message) {
    if (incoming.seq <= 0) return
    messages.removeAll { it.seq == incoming.seq && it.panelId.isEmpty() }
}

/** 消息在时间线上的位置：面板用 order，其余用 seq。 */
private fun position(m: Model.Message): Double = if (m.order > 0) m.order else m.seq.toDouble()

/**
 * 把一条 inbox splice 应用到本地排队队列上。
 *
 * 纯函数（可单测）：[queue] 是 `next-turn` 在前、`next-step` 在后的拼接结果，
 * 所以要先把目标桶在整体里的区间算出来，再在桶内做 `toSpliced(start, removed, inserted)`。
 *
 * 下标越界一律钳到合法区间：宿主与本地的队列若因为丢帧短暂不一致，
 * 宁可插到末尾/什么都不删，也不能让整条队列因为一次 splice 变空。
 */
fun applyInboxSplice(
    queue: List<Model.QueuedMessage>,
    target: String,
    start: Int,
    removedCount: Int,
    inserted: List<Model.QueuedMessage>,
): List<Model.QueuedMessage> {
    if (target != "next-turn" && target != "next-step") return queue
    val head = if (target == "next-turn") 0 else queue.count { it.target == "next-turn" }
    val size = queue.count { it.target == target }
    val at = head + start.coerceIn(0, size)
    val drop = removedCount.coerceIn(0, size - (at - head))
    val next = queue.toMutableList()
    repeat(drop) { if (at < next.size) next.removeAt(at) }
    next.addAll(at.coerceAtMost(next.size), inserted)
    return next
}

/**
 * 内联步骤流里的一段：要么是一条旁白原样渲染，要么是整轮的操作折成一行摘要。
 *
 * 合并规则（2026-10-29 用户定「一轮一行」）：**整轮所有操作只折一行**，不管几条。
 * 早先是「连续 ≥2 条才折、单条原样」，于是运行中经常出现「已处理 3 个操作」紧挨着
 * 一行「✦ 深度思考」——思考也是操作，它不该单独占一行；更糟的是一轮被切成几段，
 * 同一条时间线上并排冒出好几个「已处理 N 个操作」。
 *
 * 旁白（[Model.Step.TEXT]）仍是独立段、不参与合并：它是给人读的正文，
 * 折进「已处理 N 个操作」会直接丢字。新数据不再产生旁白，只有旧缓存里有。
 */
sealed interface StepGroup {
    /** 单条旁白原样渲染。 */
    data class Single(val index: Int) : StepGroup

    /** 整轮操作（思考 + 工具 + 技能）合并成的一行摘要。 */
    data class Fold(val indices: List<Int>) : StepGroup
}

/**
 * 把面板的 steps 切成渲染段：**所有**操作合成一个折叠段，旁白各自成段。
 *
 * 折叠段插在第一条操作所在的位置，旁白按原顺序留在前后——这样「旁白 → 操作 → 旁白」
 * 的老面板读起来顺序不乱，而操作永远只占一行。
 *
 * [visible] 用来屏蔽不该显示的步骤（设置里关掉思考时不显示思考行）；
 * 被屏蔽的步骤既不渲染也不参与计数——否则会出现「已处理 2 个操作」里混进两段思考。
 */
fun groupSteps(
    steps: List<Model.Step>,
    visible: (index: Int, step: Model.Step) -> Boolean = { _, _ -> true },
): List<StepGroup> {
    val out = ArrayList<StepGroup>()
    val ops = ArrayList<Int>()
    for (i in steps.indices) {
        val step = steps[i]
        if (!visible(i, step)) continue
        if (step.kind == Model.Step.TEXT) out.add(StepGroup.Single(i)) else ops.add(i)
    }
    if (ops.isNotEmpty()) {
        // 折叠段落在第一条操作的位置：前面是旁白就排在它后面，否则排最前。
        val at = out.indexOfFirst { it is StepGroup.Single && it.index > ops.first() }
        val fold = StepGroup.Fold(ops)
        if (at < 0) out.add(fold) else out.add(at, fold)
    }
    return out
}

/**
 * 折叠行的文案：整轮压成一行，**始终**报「已处理 N 个操作」。
 *
 * 三条硬规则（2026-10-29 用户两次反馈后定稿）：
 * 1. **运行中也报数**。早先「整轮没结束就什么都不报」，真机上折叠行只剩一个旋转图标，
 *    用户看不出这轮已经干了多少（2026-10-29 反馈「『已处理X个操作』也没有出现」）。
 *    运行中唯一例外是**一个操作都还没有**：那时报「正在分析」，不编一个
 *    「已处理 0 个操作」这种假数字。
 * 2. **只报工具数**：思考了几段对用户没有信息量（2026-10-28「不要『深度思考 ×3』」）。
 * 3. 用时只给整轮收尾那一组（`took` 非空，由调用方按 footnote 判），一次轮回报一次。
 *
 * 计数口径由调用方传整块面板的 [Model.Message.toolCount]，不是本组数出来的——
 * 折叠行数本组、抽屉数整块，两边必然对不上（真机反复反馈的就是这个）。
 *
 * @param running 这一轮还没结束（见 [foldLive]）；只影响「还没有任何操作」时的措辞。
 * @param took 形如「用时 4 秒」；空串表示这轮还没收尾，只报操作数。
 * @param totalTools 整块面板的可展开操作数；负数表示调用方没给，退回本组的工具数。
 * @param interrupted 记录里这一轮还开着，但会话早就不在跑了——宿主那轮没等到 `turn/end`
 *   就没了（进程被杀、断网、换中继）。这时既不能报「运行中」也不能报「已完成」：
 *   前者是骗（它根本不会动了），后者也是骗（这轮确实没跑完）。
 */
fun foldSummary(
    steps: List<Model.Step>,
    indices: List<Int>,
    running: Boolean,
    took: String = "",
    totalTools: Int = -1,
    failure: String = "",
    interrupted: Boolean = false,
): String {
    val picked = indices.mapNotNull { steps.getOrNull(it) }
    val tools = if (totalTools >= 0) totalTools else picked.count { it.kind == Model.Step.TOOL }
    val head = when {
        // 有失败原因时**它才是这一行的重点**：整轮跑挂了，「已处理 3 个操作」是废话。
        // 供应商 429、上下文超长、鉴权失败都走这条（`turn/end` 的 `data.reason`）。
        failure.isNotEmpty() -> "处理失败"
        tools > 0 -> "已处理 $tools 个操作"
        // 还没调过工具：报「正在分析」，别写「已处理 0 个操作」这种假数字。
        interrupted -> "已中断"
        running -> "正在分析"
        else -> "已完成分析"
    }
    val tail = when {
        failure.isNotEmpty() -> failure
        // 已经有操作数时把它挂在后面；纯中断的情况头里已经写过了，不必重复。
        interrupted && tools > 0 -> "已中断"
        else -> took
    }
    return if (tail.isEmpty()) head else "$head · $tail"
}

/**
 * 这一轮是否还没结束——折叠行据此决定「报不报操作数」。
 *
 * 只看 [Model.Message.running] 不够：宿主每步都会发 `step/end`，走完一步就把它置 false，
 * 而这一轮还在继续（下一步的思考、或者正在往外吐正文）。真正「整轮结束」的标记是
 * `turn/end` 打的 [Model.Message.footnote]，所以判据是「还在跑」**或**「会话在跑而这轮没收尾」。
 *
 * 会话已经不在跑（离线读缓存、进程被杀后重进）时一律算结束：那些面板永远等不到
 * `turn/end`，否则它们会连「已处理 N 个操作」都不显示，折叠行只剩一个图标。
 */
fun foldLive(message: Model.Message, sessionRunning: Boolean): Boolean =
    message.running || (sessionRunning && !message.footnote)

/**
 * 抽屉里的「还在进行」必须跟折叠行同源。
 *
 * `message.running` 是归档记录里的历史状态（这一轮没收到 `turn/end`），`stranded`
 * 是「记录开着、却拿不到活体证据」。以前抽屉只看前者，于是外面写「已中断」、
 * 点开里面还在转菊花/逐字吐字，两处判据自相矛盾（2026-11-12 反馈）。
 */
fun timelineLive(messageRunning: Boolean, stranded: Boolean): Boolean =
    messageRunning && !stranded


/**
 * 折叠行下面那行「活思考」的正文：取这一段里**最后一段**非空思考。
 *
 * 折叠行把整段过程压成一行灰字后，运行中就看不出它是不是真的在想——把最新的
 * 思考尾部流式贴在折叠行下面，字在长，人就知道没卡住（2026-10-28 反馈）。
 * 已经落定的面板不展示（回看时那一行只会占地方），正文由抽屉负责。
 */
fun foldedThinkingText(steps: List<Model.Step>, indices: List<Int>): String {
    for (i in indices.indices.reversed()) {
        val step = steps.getOrNull(indices[i]) ?: continue
        if (step.kind != Model.Step.THINKING) continue
        if (step.text.isNotBlank()) return step.text
    }
    return ""
}

/** 折叠行尾部要报的「当前操作」类型；决定前导图标。 */
enum class ActivityKind { THINKING, TOOL, SKILL }

/** 折叠行尾部的「菊花 + 当前操作名」。 */
data class ProcessActivity(
    val kind: ActivityKind,
    /** 前缀用词：「思考」/「执行命令」/「技能」/工具名。 */
    val label: String,
    /** 该操作的具体内容（命令文本、工具名、技能名）；纯思考时为空。 */
    val detail: String = "",
    /**
     * 模型给这次调用写的小标题（工具参数 `description`）。
     *
     * 桌面端折叠行是 `工具调用 · ops_bash · 改成十秒桶`，最后那段就是它。手机端原先
     * 只认命令，于是同一批调用在电脑上有「改成十秒桶」、在手机上只剩一条裸命令
     * （2026-11-01 用户要求同步）。没有描述的工具留空串，折叠行不编假标题。
     */
    val subtitle: String = "",
)

/** 抽屉要盯的那块面板的身份（不存对象，避免拿到旧快照）。 */
data class PanelTarget(
    val panelId: String,
    val turn: Long,
    /**
     * 打开时这块面板的**首个步骤号**（[Model.Step.id]）。
     *
     * `panelId` 是累加器发号的，重新快照（`onSnapshot` 整表替换）会把它们全部重发
     * 一遍，旧 id 当场失效；步骤号在 `mergePanels` 里是 `step.copy()` 带过去的，
     * 同一份快照内认得更准。两者都失效时才退回「同回合最后一块」。
     */
    val stepId: String = "",
)

/** 从一条消息取出抽屉要盯的身份。 */
fun panelTargetOf(message: Model.Message): PanelTarget = PanelTarget(
    panelId = message.panelId,
    turn = message.turn,
    stepId = message.steps.firstOrNull { it.id.isNotEmpty() }?.id ?: "",
)

/**
 * 这一轮**此刻**在干什么：折叠行尾部要跟一个菊花 + 当前操作名（2026-10-28 用户要求）。
 *
 * 折起来之后只剩一行灰字，用户看不出它是「在想」还是「在跑命令」还是「在加载技能」。
 * 取 steps 里**最后一条**思考/工具：思考→「思考中」（正文由流式预览给出），
 * 工具→「执行 <命令>」，技能→技能名。
 *
 * **落定后也照常返回**（2026-10-31 用户要求「其他工具调用/技能等等也需要展示名称」）：
 * 早先这里对已收尾的面板 early-return null，于是折叠行一旦跑完就只剩「已处理 N 个操作」，
 * 回看时完全看不出这一轮到底用了什么工具、加载了什么技能。转不转圈由调用方的
 * [FoldedStepsRow] 按 running 决定，与「报不报名字」是两件事，不该混在一个判据里。
 */
fun currentActivity(message: Model.Message): ProcessActivity? {
    for (i in message.steps.indices.reversed()) {
        val step = message.steps[i]
        when (step.kind) {
            Model.Step.THINKING -> {
                if (step.text.isBlank()) continue
                return ProcessActivity(ActivityKind.THINKING, "思考")
            }
            Model.Step.TOOL -> {
                val action = message.actions.getOrNull(step.actionIndex) ?: continue
                val detail = action.detail.trim().replace('\n', ' ')
                // 小标题（模型写的 `description`）跟着活动一起带出去：折叠行第一行要
                // 「已处理… + 操作名称 + 小标题」，第二行才是命令本身。
                val subtitle = action.subtitle
                if (action.skill) {
                    return ProcessActivity(ActivityKind.SKILL, "技能", detail, subtitle)
                }
                if (action.command && detail.isNotEmpty()) {
                    return ProcessActivity(ActivityKind.TOOL, "执行命令", detail, subtitle)
                }
                if (detail.isNotEmpty()) {
                    return ProcessActivity(ActivityKind.TOOL, action.name.ifBlank { "工具" }, detail, subtitle)
                }
                return ProcessActivity(ActivityKind.TOOL, action.name.ifBlank { "工具调用" }, subtitle = subtitle)
            }
            else -> continue
        }
    }
    return null
}

/**
 * 抽屉要盯的那块面板。
 *
 * 以前抽屉存的是 `Model.Message` 快照，流式增量全落在累加器里另建的对象上，
 * 于是「展开后不刷新」（2026-10-28 用户要求：思考详情也要流式加载）。改成存
 * (panelId, turn) 的身份，每次重组按它回 [rows] 里取最新那份。
 *
 * 合并后的面板沿用首块的 panelId，所以按 id 找不到时退回「同回合最后一块」。
 *
 * 但**「同回合最后一块」不能无条件用**：一轮里可能有多块面板（旁白把回合打断成
 * 两段），退到「最后一块」等于把抽屉悄悄换成另一个过程——用户看到的是「我点开
 * 的是这一段，里面却成了另一段」（2026-10-30 反馈）。所以 [stepId] 命中优先，
 * 只有它也没命中（老缓存没有 id）才退回回合兜底。
 */
fun resolvePanel(
    rows: List<Model.Message>,
    panelId: String,
    turn: Long,
    stepId: String = "",
): Model.Message? {
    if (panelId.isNotEmpty()) {
        rows.firstOrNull { it.panelId.isNotEmpty() && it.panelId == panelId }?.let { return it }
    }
    if (stepId.isNotEmpty()) {
        rows.firstOrNull { m -> m.steps.any { it.id == stepId } }?.let { return it }
    }
    if (turn >= 0) return rows.lastOrNull { it.panelId.isNotEmpty() && it.turn == turn }
    return null
}

/**
 * 渲染前的「回合合并」：把同一回合里**相邻**的多块处理面板并成一块。
 *
 * 宿主是一步一个 step 往下走的——真宿主实测一轮 7~9 个 step，每步各有
 * reasoning + tool/call + assistant/message。累加器按 (turn, step) 一块面板拆分后，
 * 一轮就在时间线上留下 7~9 块面板，每块各折一行「已处理 1 个操作」，正文被挤到
 * 屏幕外（2026-10-28 真机反馈：「连续的思考/工具应该折成正在分析/已完成 X 个操作」）。
 * 这里在装配列表时把它们拼回一块：步骤按原顺序接起来，工具行的 actionIndex 顺移，
 * 起止时间取并集 ⇒ 一行摘要「已处理 N 个操作」，点开抽屉看逐条。
 *
 * **只作用于渲染**：状态里仍是每 step 一块。增量事件按 panelId 认人，一旦把
 * 合并结果写回状态，后面几块的 onProcess 就认不出对应对象了（会多出永不熄灭的
 * 「正在分析请求」），所以落盘与增量都走原列表。
 *
 * **相邻才合并**：两块面板之间夹着一句正文，就是「思考 → 回答 → 继续思考」的真实
 * 节奏；跨过它合并会把那句回答挪到所有步骤之后。
 */
fun mergeTurnPanels(messages: List<Model.Message>): List<Model.Message> {
    val out = ArrayList<Model.Message>(messages.size)
    var i = 0
    while (i < messages.size) {
        val head = messages[i]
        if (head.panelId.isEmpty() || head.turn < 0) {
            out.add(head)
            i++
            continue
        }
        var j = i + 1
        while (j < messages.size) {
            val next = messages[j]
            if (next.panelId.isEmpty() || next.turn != head.turn) break
            // **带失败原因的那块不参与合并**。
            //
            // 合并体会沿用 `run.first()` 的身份（panelId / order / seq），失败文案
            // 虽然被复制过去了，但整块的定位、去重、以及下游 `timelineOf` 的
            // 同位置覆盖都按第一块算 —— 2026-11-01 实测：`seg4`（只有失败、没有步骤）
            // 被并进 `seg2` 后，界面上既没有失败行、摘要也还是「已处理 3 个操作」。
            // 与其在合并里做特例，不如让失败那块**原样独立**：它本来也不该跟
            // 前面的步骤挤成一行。
            if (next.failure.isNotEmpty()) break
            j++
        }
        out.add(if (j - i <= 1) head else mergePanels(messages.subList(i, j)))
        i = j
    }
    return out
}

/** [mergeTurnPanels] 的合并体：接步骤、拼工具行、并时间。 */
private fun mergePanels(run: List<Model.Message>): Model.Message {
    val merged = run.first().snapshot()
    merged.steps.clear()
    merged.actions.clear()
    var startedAt = 0L
    var endedAt = 0L
    for (panel in run) {
        val offset = merged.actions.size
        merged.actions.addAll(panel.actions.map { it.copy() })
        for (step in panel.steps) {
            val copy = step.copy()
            // 工具步骤只记下标：拼完之后下标整体后移了前几块的条数。
            if (step.actionIndex >= 0) {
                copy.actionIndex = if (step.actionIndex < panel.actions.size) offset + step.actionIndex else -1
            }
            merged.steps.add(copy)
        }
        if (panel.startedAt > 0 && (startedAt == 0L || panel.startedAt < startedAt)) startedAt = panel.startedAt
        if (panel.endedAt > endedAt) endedAt = panel.endedAt
        if (panel.footnote) merged.footnote = true
        // 状态、失败文案与「进行中」都取最后一块的：那是这一轮真正停下来的地方，
        // 中间几块必然是已经收尾的。
        if (panel.status.isNotEmpty()) merged.status = panel.status
        if (panel.failure.isNotEmpty()) merged.failure = panel.failure
        merged.running = panel.running
    }
    if (startedAt > 0) merged.startedAt = startedAt
    merged.endedAt = endedAt
    return merged
}

/**
 * LazyColumn 的 item key。
 *
 * seq 是宿主给的稳定身份，但**处理面板与同回合的助手正文共用同一个 seq**
 * （面板的真身份是 [Model.Message.panelId]，位置是 `order = seq - 0.25`）。
 * 只用 seq 取键，面板和正文就会撞键——撞上 LazyColumn 直接崩
 * （历史事故：`Key "3415" already used`）。所以面板用自己的 `panelId` 取键。
 * seq<=0 的本地行（回声/占位）可能多条且 hashCode 会碰撞，退回索引。
 *
 * **不能用 `order` 当键**（2026-10-31 修）：面板的 `order` 在流式期间会变 ——
 * 刚起步时 `panelSeq = 0` 退回 `anchorSeq + 0.25`，durable 记录一到就变成
 * `panelSeq - 0.25`。同一个逻辑面板的 key 从 `pos-100.25` 跳到 `pos-100.75`，
 * LazyColumn 当作「删一项 + 插一项」，滚动锚点丢失并重锚。真宿主一轮 7~9 步、
 * 每步都走一次这个转换，于是流式期间整表反复重锚，表现为「滑动到任意对话都
 * 被顶回去、UI 频繁跳动」。`panelId` 由段序派生（`<turnId>#seg<段序>`），
 * 从第一帧到收尾都不变。
 */
/**
 * 消息的**稳定身份**，用于「加载更早内容后滚回原位」。
 *
 * <p>不能用 [timelineKey]：后者在 `seq<=0` 时退回 `idx-$index`，而插进新内容后
 * 下标整体后移，记下来的键就指到别的消息上。这里只用真正稳定的字段
 * （`panelId` → `seq`），拿不到身份时返回 null（调用方据此不锚定）。
 */
fun timelineAnchorOf(m: Model.Message): String? = when {
    m.panelId.isNotEmpty() -> "panel-${m.panelId}"
    m.seq > 0 -> "seq-${m.seq}"
    else -> null
}

/**
 * 「载入更早」点击那一刻记下的视口锚点。
 *
 * <p>锚点必须取**第一条真正可见的消息行**，不能取 `firstVisibleItemIndex`：
 * 用户滑到最顶时那一项是「载入更早」按钮（leading 行），它不在 `rows` 里、没有身份，
 * 而前插内容恰好发生在它**下面** —— 锚在它身上等于「什么都不做」，一屏新正文把原来
 * 看的那条顶下去（2026-11-03 模拟器实测：点一下 `TAILBODY-41` 从 y=445 掉到 y=1011）。
 *
 * @param key 锚点那条消息的**稳定身份**（[timelineAnchorOf]）；null 表示这一帧没有
 *   可锚定的消息行（内容不足一屏、或它是本地回声），此时只能原地复位。
 * @param rowIndex 点击时该消息在 `rows` 里的下标。
 * @param listIndex 点击时它在列表里的下标（含 leading 偏移）。
 * @param offset 它的顶边到视口顶边的**像素距离**，用于逐像素复位。
 * @param seq 那一行的 `seq`（>0 时才有意义）。[key] 会失效 —— 见 [anchorTargetOf]。
 */
data class TimelineAnchor(
    val key: String?,
    val rowIndex: Int,
    val listIndex: Int,
    val offset: Int,
    val seq: Long = 0L,
)

/**
 * 这一帧该滚到哪个列表下标；null 表示**什么都不做**。
 *
 * <p>**这是「载入更早之后跳到最上面」的根因所在**（2026-11-03 用户第三次反馈）。
 * 上一版判据是「`rows.size` 比点击时大」——但 `rows` 每一帧都可能变大：流式输出
 * 一直在往**尾部**追加。于是加载还没回来，判据就先满足了，锚点用「尾部新增的行数」
 * 冒充「前插的行数」算出目标下标，用户被甩到别处；`pendingAnchor` 随即清空，
 * 真正的前插到达时已经没有锚点可用。
 *
 * <p>现在的判据是**身份位移**：找到那条消息现在在第几行，只有它真的**往后挪了**
 * 才滚。尾部追加不改变它的下标 ⇒ 不滚；前插 30 段正文 ⇒ 下标正好 +30。
 * 这样一来「加载中来了多少流式增量」都不影响结果。
 *
 * @param newLeading 当前 leading 区占几项（[stickyLeadingFor]）。
 * @return 目标列表下标；null = 该消息还没挪位（或已消失），这一帧不动。
 */
fun anchorTargetOf(
    anchor: TimelineAnchor,
    rows: List<Model.Message>,
    newLeading: Int,
): Int? {
    // 没有可锚定的消息行（内容不足一屏）：只能原地复位。
    if (anchor.key == null) return anchor.listIndex
    var rowIndex = rows.indexOfFirst { timelineAnchorOf(it) == anchor.key }
    if (rowIndex < 0) rowIndex = bySeq(anchor.seq, rows)
    if (rowIndex < 0) return null
    // 没挪位 ⇒ 这一帧的 rows 变化来自尾部追加（流式），不是前插。
    if (rowIndex == anchor.rowIndex) return null
    return newLeading + rowIndex
}

/**
 * [anchorTargetOf] 的兜底定位：`seq` 之后的第一行。
 *
 * <p>**为什么 `panelId` 会失效**（2026-11-03 模拟器实证）：`rows` 是
 * [mergeTurnPanels] 的产物，同一 `turn` 的相邻面板会并成一块，合并体沿用
 * `run.first()` 的身份。用户正看着的那块面板如果被前插进来的同 turn 面板并掉，
 * `panelId` 就从 `<turn>#seg3` 变成 `<turn>#seg0` —— 原来记的键在 `rows` 里
 * 再也找不到（实测日志 `found=-1`），锚点当场放弃，界面停在新插内容的开头。
 *
 * <p>`rows` 恒按 `seq` 升序，前插只会插入更小的 `seq`，所以「第一条 `seq >= 锚点 seq`
 * 的行」在加载前后指向同一段内容。合并体会把起点前移到更小的 `seq`，兜底最多落
 * 到合并体之后一行 —— 比整屏跳走好得多。
 */
private fun bySeq(seq: Long, rows: List<Model.Message>): Int {
    if (seq <= 0) return -1
    return rows.indexOfFirst { it.seq >= seq }
}

/**
 * 列表顶部 leading 区占几项（「载入更早」+ 快照占位）。
 *
 * <p>抽成函数是因为它有三个调用方（冻结气泡、锚点换算、跳转），
 * 一旦算法改了却漏掉一处，下标就会整体偏一项。
 */
fun stickyLeadingFor(rows: List<Model.Message>, state: ChatViewModel.ChatState): Int =
    (if (state.hasMore) 1 else 0) + (if (!state.snapshotReady) 1 else 0)

/**
 * 点冻结气泡要滚到哪一行（LazyColumn 的 item 下标）；`-1` 表示找不到、不滚。
 *
 * <p>**不能只按 [timelineAnchorOf] 比**：它对本地回声（`seq<=0` 且没有 `panelId`）
 * 返回 null，而 `rows.indexOfFirst { timelineAnchorOf(it) == null }` 会命中**第一条**
 * 拿不到身份的行——通常正是用户刚发出去、还没被宿主收编的那条回声。于是「点 A 跳到
 * B」，或者目标就是自己而原地不动。所以先按对象身份找，找不到再按稳定身份找。
 *
 * <p>找到之后要**把跟随权交出去**（调用方置 `followTail = false`），否则下一次
 * `state.revision` 变化（流式输出每秒十几次）贴底 effect 立刻把人拽回底部，
 * 锚点算得再准也被覆盖。同源事故见「载入更早」按钮处的事故记录。
 */
fun stickyBubbleTarget(rows: List<Model.Message>, sticky: Model.Message, leadingItems: Int): Int {
    // 先按**对象身份**找：`stickyUserBubble` 返回的就是 `rows` 里的那个元素，
    // 同一帧里 `rows` 与 `sticky` 来自同一次 `remember(state.messages)`，`===` 必然命中。
    // 用 `==`（data class 相等）不行：两条内容完全相同的消息会互相冒充。
    val same = rows.indexOfFirst { it === sticky }
    if (same >= 0) return same + leadingItems
    // 再按稳定身份比（`rows` 被重建、对象换了但身份没换时仍能找回）。
    val anchor = timelineAnchorOf(sticky) ?: return -1
    val at = rows.indexOfFirst { timelineAnchorOf(it) == anchor }
    if (at < 0) return -1
    return at + leadingItems
}

fun timelineKey(index: Int, m: Model.Message): String = when {
    m.panelId.isNotEmpty() -> "panel-${m.panelId}"
    m.seq > 0 -> "seq-${m.seq}"
    else -> "idx-$index"
}

/**
 * 一张待答卡片是不是当前会话的。
 *
 * 宿主 waterfall 帧里的 `agentId` 就是会话 id（宿主侧 `sessionId: agent.id`，
 * 与本端发斜杠命令时 `put("agentId", sessionId)` 同一个东西）。`$events` 是
 * **app 级**一条流：A 会话的审批照样投到手机上，不过滤就会把别的会话的卡片
 * 弹进当前聊天页，用户点「允许一次」等于给别的会话放权。
 *
 * 两种缺省都不拦：宿主没给 agentId（字段缺失/老版本）、或当前还是草稿会话
 * （sessionId 为空，卡片该照常显示在草稿页）。
 */
fun interactionBelongs(agentId: String, sessionId: String): Boolean {
    if (agentId.isEmpty()) return true
    if (sessionId.isEmpty()) return true
    return agentId == sessionId
}

/**
 * 把旧卡上填了一半的草稿搬到重投回来的新卡上。
 *
 * 宿主每次重投都是重新序列化一遍 request，端侧解析出的是一批全新的
 * [Model.Question] 实例（`selected` 空、`custom` 空）。不搬的话，用户正在填的
 * 选择题会在一次重连之后全部清空——审批卡没有草稿字段，所以只有提问卡需要。
 *
 * 只在**新卡没填**时覆盖：宿主若真带了值，以宿主为准。
 */
fun carryQuestionDrafts(from: Model.QuestionCard, to: Model.QuestionCard) {
    for (q in to.questions) {
        val prev = from.questions.firstOrNull { it.id == q.id } ?: continue
        if (q.selected.isEmpty() && prev.selected.isNotEmpty()) q.selected.addAll(prev.selected)
        if (q.custom.isBlank() && prev.custom.isNotBlank()) q.custom = prev.custom
    }
}

fun timelineOf(messages: List<Model.Message>): List<Model.Message> {
    val remote = ArrayList<Model.Message>()
    val local = ArrayList<Model.Message>()
    for (m in messages) {
        // 运行中的面板仍走 remote：它的 order 已经把「不早于最近一条用户消息」算进去了
        // （见累加器的 panelOrder），按位置排才能和这一轮的正文交错。塞进 local 段
        // 会让它永远压在最后，正文一落地就跑到自己产出的那段字下面。
        if (m.seq > 0 || (m.panelId.isNotEmpty() && m.running)) remote.add(m) else local.add(m)
    }
    // 排序用它自己的位置：面板的 order 是「用户消息 seq + 0.25」，其余消息 order=0 走 seq。
    remote.sortBy { position(it) }
    // 按位置去重：快照与增量合并时同一 seq 可能进来两份（内容更新的在后），
    // 重复 key 会让 LazyColumn 直接崩（2026-10-27 真机实测：Key "3415" already used）。
    // 同位置保留最后一份——它携带更完整/更新的投影。
    val deduped = LinkedHashMap<Double, Model.Message>()
    for (m in remote) deduped[position(m)] = m
    val out = ArrayList<Model.Message>(deduped.values)
    // 本地回声一定发生在最新历史之后。
    if (local.isEmpty()) return out
    // 例外：回声就是这一轮的用户消息，属于同一轮的面板得排在它**后面**。
    // 宿主的 turn/start 比 user/message 早一个 seq，面板在这一刻已经建好并排在
    // remote 段，若把回声直接垫到尾部，刚发出去的一瞬间折叠行会闪现在自己气泡上方
    // （2026-10-28 反馈「我的消息上方也有正在分析」）。
    val last = out.lastOrNull()
    if (last != null && last.panelId.isNotEmpty() && last.running) {
        out.removeAt(out.size - 1)
        out.addAll(local)
        out.add(last)
        return out
    }
    out.addAll(local)
    return out
}

/**
 * 时间线尾部在 LazyColumn 里的 item 索引；时间线为空时返回 -1。
 *
 * LazyColumn 的 item 索引 ≠ messages 索引：前面还有「载入更早 / 快照占位」行，
 * 后面挂着流式正文与待答卡片。「跟随底部」的自动滚动和「跳转到最新消息」按钮
 * 都得滚到这一项，算式只能有一份，否则两边迟早走岔。
 *
 * <p>**`rowCount` 必须传渲染层那份 `rows.size`（`mergeTurnPanels` 之后）**，
 * 不能沿用 `state.messages.size`：同一回合的相邻面板会被并成一块，`rows` 比
 * `messages` 短得多。2026-11-03 实测（一轮 4 块相邻面板 + 1 条提问）：
 * `messages=5 / rows=2`，旧算式给出 `tailItemIndex=4`，而列表最后一项是 1。
 *
 * <p>后果被 `LazyColumn` 的越界夹取掩盖了——滚到不存在的下标会被夹到末项，
 * 所以「贴底」看着是对的。但这是**靠兜底掩盖的错误下标**：`itemHeightOf(tail)`
 * 查不到可见项、恒返回 0，`tailScrollOffset(0)` 退回那个 1_000_000 的哨兵值，
 * 于是「贴底」的真实语义从「盖过末项高度」退化成「赌一个足够大的数」。
 *
 * <p>`rowCount` **没有默认值**，刻意如此：给一个 `= state.messages.size` 的默认值
 * 等于让每个新调用点都默默踩同一个坑。传错的那一天不会有编译错误，只会在某次
 * 「和 `visibleItemsInfo` 比大小」的判断里爆掉。
 */
fun tailItemIndex(state: ChatViewModel.ChatState, rowCount: Int): Int {
    // 待答卡片**不算**列表项：它们悬浮在消息区上方，不进 LazyColumn（2026-10-31
    // 用户要求）。曾经把它们算进来，改悬浮之后这里若还加就会多算两格，
    // 「贴底」滚到一个不存在的位置上。
    val tail = rowCount + (if (state.streamingText.isNotEmpty()) 1 else 0)
    if (tail <= 0) return -1
    val leading = (if (state.hasMore) 1 else 0) + (if (!state.snapshotReady) 1 else 0)
    return leading + tail - 1
}

/**
 * 当前要悬浮显示、且还没被回答的卡片。
 *
 * <p>先审批后提问：审批卡挡住的是「电脑那一步正在等你放行」，提问卡挡住的是
 * 「同一轮还没走完」。两者同屏时审批永远在上——它是更紧急的那件事。
 */
fun pendingCards(state: ChatViewModel.ChatState): List<Model.Interaction> {
    val out = ArrayList<Model.Interaction>(state.approvals.size + state.questions.size)
    // **只收能答的卡**。悬浮层的语义是「现在必须做的一件事」；一张答不回去的卡
    // 挂在这里只有一个效果：占住 420dp 把下面的消息全挡住，而按钮还是灰的。
    //
    // 不可答的来源只有一类：从归档重建的历史审批（宿主那边早没这条挂起的
    // waterfall 了）。它们该退成时间线上的一行记录，不该抢这块地方
    // （2026-11-01 用户质问「归档里的审批卡为什么还需要点击」）。
    out.addAll(state.approvals.filterNot { it.answered || it.submitted || !it.answerable })
    out.addAll(state.questions.filterNot { it.answered || it.submitted || !it.answerable })
    return out
}

/**
 * 「跳转到最新消息」悬浮胶囊的显隐。
 *
 * 只看用户的跟随意图：[followTail][ChatViewModel] 由「滚动停止」边沿更新，用户
 * 没滚动过就一直是 true ⇒ 内容从没超过一屏的会话不会冒出这个按钮。
 *
 * **不拿 layoutInfo 反推「离底多远」**：流式期间列表长度先变、布局后到，按可见项
 * 算会在正常跟随时算出「离底很远」，按钮闪一下（2026-10-27 同源事故）。
 */
fun showJumpToLatest(followTail: Boolean, tailIndex: Int): Boolean =
    !followTail && tailIndex >= 0

/**
 * 键盘弹起时是否要主动把时间线贴到最新一条。
 *
 * 与「跳转到最新消息」按钮相反：那个胶囊只在用户**主动离开底部之后**才出现，是补救
 * 入口；这里则是「人正要打字」的信号——键盘吃掉半屏后若还停在历史里，最新那条回复
 * 完全看不见（2026-10-28 用户要求：输入时把最新消息顶到键盘上方）。
 *
 * 判据四条都必要：
 * - 键盘确实弹起了（[keyboardOpen]）：收起键盘的瞬间不该产生任何滚动；
 * - 时间线里有可滚的项（`tailIndex >= 0`）：空会话没有可贴的尾部；
 * - 用户本来就在跟随最新（[followTail]）：**正在翻历史的人不该被键盘拽回底部**。
 *   以前漏了这一条，于是往上翻着旧消息、一点输入框整页跳回最后一条，而且
 *   `followTail` 顺带被置回 true，之后新消息又一路把人往下拽（2026-10-30 实测）。
 * - 手指没按在列表上（[userDragging]）：拖动中途弹键盘（点输入框那一下经常先
 *   落在列表滚动后的松手边沿）不该抢滚动权。
 */
fun pinLatestOnKeyboard(
    keyboardOpen: Boolean,
    tailIndex: Int,
    followTail: Boolean = true,
    userDragging: Boolean = false,
): Boolean =
    keyboardOpen && tailIndex >= 0 && followTail && !userDragging

/**
 * 把归档里重建出来的卡片并进已有列表。
 *
 * 两条路径各自能看到同一张审批，但 id 不同：`$events` 的 `eventId` 是网关现生成的
 * 随机 UUID（只有它能被宿主认），归档里的是 `sessionId:seq`。判同一张卡靠
 * `callId`（两边都带）。同一张卡时**保留已有那份**——它带着可回答的 id。
 *
 * @param existing 已经在界面上的卡（来自 `$events`）
 * @param restored 从归档投影里重建出来的卡
 */
internal fun <T : Model.Interaction> mergeRestored(
    existing: List<T>,
    restored: List<T>,
): List<T> {
    if (restored.isEmpty()) return existing
    val out = existing.toMutableList()
    for (card in restored) {
        val callId = card.callIdOf()
        val hit = out.indexOfFirst {
            it.eventId == card.eventId || (callId.isNotEmpty() && it.callIdOf() == callId)
        }
        if (hit < 0) {
            out.add(card)
        } else if (!out[hit].answerable && card.answerable) {
            // 顺序反了：归档那张先到、`$events` 重投的后到。归档的 id 是
            // `sessionId:seq`，宿主的 `$events` 不认；重投那张才带真 eventId。
            // 把界面上那张原样换成重投的，用户才点得动。
            out[hit] = card
        }
    }
    return out
}

/** 卡片的 `callId`（审批与提问都有）。 */
internal fun Model.Interaction.callIdOf(): String = when (this) {
    is Model.Approval -> callId
    is Model.QuestionCard -> callId
    else -> ""
}

/**
 * 新内容到达时要不要把列表自动滚到尾部。
 *
 * <p>两个输入各自挡住一类「被拽回底部」：
 * - [followTail] == false：用户已经明确离开底部在翻历史——这是上一次修复
 *   （2026-10-30 上午）的判据，但它只在「滚动停止」的边沿更新；
 * - [userDragging] == true：手指**此刻**按在列表上。拖动刚开始时 followTail 还
 *   停留在旧值（多半是 true），流式输出一秒十几次 revision，每一次
 *   `scrollToItem(tail)` 都会在手指未松开时把列表拽回底部（2026-10-30 用户实测
 *   「查看历史消息的时候，如果这个时候有输出，也会往下跳」）。DragInteraction
 *   的边沿比滚动停止早一整段，补的正是这个窗口。
 */
fun autoScrollOnNewContent(followTail: Boolean, userDragging: Boolean): Boolean =
    followTail && !userDragging

/**
 * 贴底滚动时给 `scrollToItem` 的偏移（像素）。
 *
 * `LazyListState.scrollToItem(index)` 的语义是「把这一项对齐到视口**顶部**」，
 * 不是「滚到底」。最后一项短于一屏时列表会把它夹到最底部，看不出问题；可一旦
 * 最后一项（正在流式的那条正文）**高过一屏**，「把它的第一行顶到屏幕上沿」就是
 * 一个合法的滚动位置 —— 于是每来一个字都重算一次、每来一次都停在同一个首行，
 * 用户看到的就是「屏幕顶端与正文第一行对齐、UI 频繁跳动」（2026-10-31 真机实测）。
 *
 * 修法：把偏移给到「这一项的底边」之外。`scrollToItem` 允许越界偏移，列表会自己
 * 夹到 content 末端，等价于「滚到真正的底」——最后一项高过一屏时显示的是它的
 * 尾部，而不是首行。
 *
 * @param itemHeight 目标项的实测高度；后端还没量到（首次组合）时传 0，退回
 *   一个足够大的常量偏移，效果相同（都会被夹到末端）。
 */
fun tailScrollOffset(itemHeight: Int): Int = if (itemHeight > 0) itemHeight else 1_000_000

/**
 * 聊天页顶栏标题。
 *
 * 三档回退，都**必须是本地已有的信息**：真标题 → 工作区目录名 → 「新对话」。
 *
 * 为什么不能直接用 `title.ifBlank { "新对话" }`：`Model.Session.title` 的默认值就是
 * 字面量「新对话」，旧缓存与 `createSession` 也都写着这个串，它同时表示「真的叫这个」
 * 和「还不知道」——直接显示就会在真标题到达时跳变一次（2026-10-30 用户实测「打开
 * 会话时标题是『新会话』，随后才加载成实际标题」）。这里把它当占位处理，退到工作区名：
 * 名称立刻稳定，真标题到了再替换，视觉上不再有「先占位再跳变」。
 */
fun chatHeaderTitle(title: String, workspace: String): String {
    val trimmed = title.trim()
    if (trimmed.isNotEmpty() && trimmed != PLACEHOLDER_TITLE) return trimmed
    val dir = workspace.trim().substringAfterLast('/').substringAfterLast('\\')
    return dir.ifBlank { PLACEHOLDER_TITLE }
}

/** 会话标题的占位值（`Model.Session.title` 的默认值）。 */
const val PLACEHOLDER_TITLE = "新对话"

/**
 * 投影回来时，**身份字段**（标题、工作区）该怎么合并。
 *
 * <p>为什么必须单独处理：`RelayGateway.sessionProjections` 只 `new` 了一个
 * `Model.Session(id = sessionId)`，里面除模型选择外一律是默认值，工作区更是
 * 从头到尾没被填过。而 `refreshProjections` 拿它 `value.copy(...)` 覆盖整份
 * 状态，于是 `open()` 刚兜好的工作区被一个空串顶掉 —— 副标题先正常、过一瞬
 * 就消失（2026-11-02 用户实测）。标题当初写了同样的兜底，工作区漏了。
 *
 * <p>三档顺序与 `open()` 保持一致：**投影值 → 手上这份 → 本地缓存**。
 * 深链/通知进来的壳只有 id，手上这份也可能空，最后一档才落到缓存。
 *
 * @return `first` 是标题，`second` 是工作区。
 */
fun mergeProjectionIdentity(
    projectedTitle: String,
    projectedWorkspace: String,
    localTitle: String,
    localWorkspace: String,
    cachedWorkspace: () -> String,
): Pair<String, String> {
    val title = projectedTitle.ifBlank { localTitle }
    val workspace = projectedWorkspace.ifBlank {
        localWorkspace.ifBlank { cachedWorkspace() }
    }
    return title to workspace
}

/**
 * 「深度思考」折叠行的单行预览文本。
 *
 * 思考增量本身是逐字到端侧的（`relay/core` 的 filter 不拦 `reasoning-delta`，
 * 端侧每个 chunk 都走 `onProcess`），但整段思考只对应**一个** step；早先这里
 * 固定取 `take(120)` 的头部，只要超过 120 字，前 120 字在整段思考期间一个字都
 * 不变——看着就像「思考完了才一次性同步过来」。
 *
 * 所以：还在流式就取尾部（可见光标的推进），落了盘再取头部（回顾时从开头读）。
 *
 * 只取尾部还不够：折叠行把它按 `maxLines = 2` 渲染，**放不下就从末尾省略**，
 * 可见的仍是这 120 字窗口的**开头**，每个新字只把窗口整体挪一位 → 看着「没怎么
 * 变动」（2026-10-28 反馈）。折叠行那处必须用 [INLINE_THINKING_LIMIT] 这种「一定
 * 放得下」的小额度，最新的字才真的落在可见区域里。
 */
fun thinkingPreview(text: String, streaming: Boolean, limit: Int = 120): String {
    val flat = text.trim().replace('\n', ' ')
    if (flat.length <= limit) return flat
    return if (streaming) "…" + flat.takeLast(limit - 1) else flat.take(limit)
}


/**
 * 一个字符在 13sp 下大约占几个汉字宽。
 *
 * **这是「右侧一片空白」的根因（2026-11-01 用户反馈）**：`inlineThinkingBudget`
 * 把「这一行有多少像素」除以「一个汉字的宽度」，得到的单位是**汉字个数**；
 * 而 [thinkingPreview] 的 `limit` 是**字符个数**。英文/ASCII 只有汉字一半宽，
 * 于是同一个像素宽度能放两倍字符，按汉字数截断就只填了一半 —— 中文内容一字一宽，
 * 正好填满，所以「偶尔有中文的时候不是空白」。
 *
 * 按 Unicode 区段粗分三档（全角 / 半角 / 组合符），不追求精确排版，
 * 只求「估算不再系统性偏小一半」。
 */
fun charWidthUnits(cp: Int): Float = when {
    cp == 0x200D || cp in 0x0300..0x036F || cp == 0xFE0F -> 0f
    cp in 0x1100..0x115F || cp in 0x2E80..0xA4CF || cp in 0xAC00..0xD7A3 ||
        cp in 0xF900..0xFAFF || cp in 0xFE30..0xFE4F || cp in 0xFF00..0xFF60 ||
        cp in 0xFFE0..0xFFE6 || cp in 0x1F300..0x1F64F || cp in 0x20000..0x3FFFD -> 1f
    else -> 0.5f
}

/**
 * 按**像素预算**取一段预览，而不是按字符数。
 *
 * 流式取尾部、落定取头部，两端都 O(n) 扫一遍。
 *
 * @param budgetPx 这一行真正有多少像素可用。
 * @param charPx   一个汉字当前的宽度（`13.sp.toPx()`）。
 */
fun thinkingPreviewByWidth(
    text: String,
    streaming: Boolean,
    budgetPx: Int,
    charPx: Float,
): String {
    val flat = text.trim().replace('\n', ' ')
    if (flat.isEmpty()) return ""
    if (charPx <= 0f || budgetPx <= 0) return flat
    // 预留一个字符给省略号。
    val room = (budgetPx / charPx) - 1f
    if (room <= 0f) return "…"

    var used = 0f
    var end = flat.length
    var i = 0
    while (i < flat.length) {
        val cp = flat.codePointAt(i)
        used += charWidthUnits(cp)
        if (used > room) {
            end = i
            break
        }
        i += Character.charCount(cp)
    }
    if (end >= flat.length) return flat
    if (!streaming) return flat.take(end) + "…"

    // 流式取尾部：从后往前扫同样的预算。
    var usedTail = 0f
    var start = 0
    var j = flat.length
    while (j > 0) {
        val cp = flat.codePointBefore(j)
        usedTail += charWidthUnits(cp)
        if (usedTail > room) {
            start = j
            break
        }
        j -= Character.charCount(cp)
    }
    return "…" + flat.substring(start)
}

/**
 * 折叠行**第一行**：`已处理 N 个操作 · <操作名称> · <小标题>`。
 *
 * 用户口径（2026-11-01）：「折叠栏改为 2 行，上方显示已处理… + 操作名称 + 小标题，
 * 下方显示预览内容」。小标题就是桌面端 `工具调用 · ops_bash · 改成十秒桶` 的最后一段
 * ——模型写在工具参数 `description` 里的那句话（见 [Model.ToolAction.subtitle]）。
 *
 * 三条规矩：
 * 1. **落定后照样报工具名与小标题**。桌面端已完成的调用也一直显示小标题，回看时才
 *    知道这一轮每步想干什么；早先手机端一跑完就只剩「已处理 N 个操作」（2026-10-31
 *    已经为此让 [currentActivity] 落定后仍返回值，这里沿用同一口径）。
 * 2. **不编假标题**：没有 `description` 的工具只显示操作名，绝不拿命令冒充小标题
 *    ——命令在第二行，重复一遍既占地方又难看。
 * 3. 思考没有小标题（「思考」两个字已经把话说了），它的内容全在第二行的流式预览里；
 *    而且**落定后连「思考」这个标签也不挂**——那时没有「正在想」这回事，
 *    「已处理 3 个操作 · 用时 12 秒 · 思考」会让人以为还在想（旧版 `foldLine`
 *    落定后只留 title，这里保持同一观感）。
 *
 * @param running 这一轮是否还在跑（见 [foldLive]）；只影响纯思考的标签。
 */
fun foldHead(title: String, activity: ProcessActivity?, running: Boolean = true): String {
    if (activity == null) return title
    val label = activity.label.trim()
    if (label.isEmpty()) return title
    if (activity.kind == ActivityKind.THINKING && !running) return title
    val subtitle = activity.subtitle.trim().replace('\n', ' ')
    val head = "$title · $label"
    return if (subtitle.isEmpty()) head else "$head · $subtitle"
}

/**
 * 折叠行**第二行**：预览内容。
 *
 * - 正在思考 → 思考正文的**尾部**预览（额度按这一行实际宽度算，见 [inlineThinkingBudget]）；
 * - 工具/技能 → 该调用的参数摘要（命令文本、技能名、路径…）；
 * - 没有可预览的内容 → 空串，调用方据此不画第二行（不占一行空白）。
 *
 * 预览独占一行之后额度宽松多了：第一行不再跟它抢宽度，`prefixPx` 给 0 即可。
 *
 * @param usedWidth 这一行 `Text` 实际分到的宽度（0 = 还没量到，退回下限额度）。
 * @param charPx    一个汉字在 13sp 下的宽度。
 */
fun foldPreview(
    running: Boolean,
    activity: ProcessActivity?,
    thinking: String,
    showsThinking: Boolean,
    usedWidth: Int,
    charPx: Float,
): String {
    if (activity == null) return ""
    if (activity.kind == ActivityKind.THINKING) {
        // 用户关掉「显示思考」时不往折叠行塞思考正文，与抽屉口径一致。
        if (!showsThinking || thinking.isBlank()) return ""
        // 还在长的时候取尾部（最新那几个字）；落定后从头取，回看要能看到结论的开头。
        // **按像素算，不按字数算**：见 [charWidthUnits] 的注释（右侧留白的老 bug）。
        return thinkingPreviewByWidth(thinking, streaming = running, budgetPx = usedWidth, charPx = charPx)
    }
    return activity.detail.trim().replace('\n', ' ')
}

/**
 * 折叠行里「思考：<尾部预览>」那一段的**下限**额度。
 *
 * 这个常量现在只是兜底下限：真正的额度由 [inlineThinkingBudget] 按实测可用宽度算。
 *
 * 历史：早先把它当唯额度用，定成 12 —— 一行只露十来个字，右侧空着一大片
 * （2026-11-01 用户反馈「思考内容显示得太少了，像是右侧有一处空白被占位了」）。
 * 直接放大也不行：`Text` 的省略号从**末尾**切，而这一段本来就是取尾部，
 * 切成「窗口开头」之后每来一个新字只把窗口整体挪一位，看着像冻住了
 * （2026-10-28 踩过同一个坑）。所以额度必须跟着可用宽度走。
 */
const val INLINE_THINKING_LIMIT = 12

/**
 * 按「一行实际能放下多少字」算思考预览的额度。
 *
 * @param availablePx 这个 `Text` 实际拿到的宽度（`weight(1f)` 分配之后）。
 * @param prefixPx    `已处理 N 个操作 · 思考：` 那一截的实测宽度。
 * @param charPx      一个汉字在该字号下的宽度（中文近似等宽，用它换算）。
 * @return 能放下的字数；拿不到宽度时退回 [INLINE_THINKING_LIMIT]。
 *
 * 留一个字的余量：刚好卡满时排版会判成溢出，屏幕上反而冒出省略号。
 */
fun inlineThinkingBudget(availablePx: Int, prefixPx: Int, charPx: Int): Int {
    if (charPx <= 0 || availablePx <= 0) return INLINE_THINKING_LIMIT
    val room = availablePx - prefixPx - charPx
    if (room <= 0) return INLINE_THINKING_LIMIT
    return (room / charPx).coerceIn(INLINE_THINKING_LIMIT, 200)
}

/**
 * 顶部冻结条要显示哪一条用户消息。
 *
 * <p>用户口径（2026-11-01）：「冻结用户的上一个会话气泡到顶部……当前页面内如果没有
 * 用户的消息气泡，就把离当前索引最近的上一个用户气泡冻结在顶部，用户点击该消息可
 * 快速跳转」。
 *
 * <p>判定顺序：
 * 1. **屏幕里已经有用户气泡** → 一条都不冻。此时气泡本身就是上下文，再顶一条是重复。
 * 2. 否则取「索引 < 首个可见项」里**最近的那条**用户消息 —— 用户往上翻到哪一段，
 *    顶部就冻那一段的提问。取最近而不是最早：翻得越深，冻结的应该是「当前正在读的
 *    那一段」的开头，不是整个会话的第一句。
 * 3. 再往前一条都没有 → 返回 null（不画）。
 *
 * <p>**可见区必须由调用方给准**（`firstVisible`/`lastVisible`）。早先这里用「往后
 * 扫固定 24 行」猜可见区，结果后面 24 行里只要有一条用户消息就整条不冻 —— 翻到
 * 会话中段时永远不显示（2026-11-01 单测当场发现）。
 *
 * @param firstVisibleIndex 视口里第一项的 item 下标（含 leading 行）。
 * @param lastVisibleIndex  视口里最后一项的 item 下标；`-1` 表示还量不到。
 * @param leadingItems      leading 区占了几项（`hasMore` 的「载入更早」、快照占位行）。
 * @return 要冻结的消息；null 表示不冻。
 */
fun stickyUserBubble(
    rows: List<Model.Message>,
    firstVisibleIndex: Int,
    lastVisibleIndex: Int,
    leadingItems: Int,
): Model.Message? {
    if (rows.isEmpty()) return null
    val firstRow = (firstVisibleIndex - leadingItems).coerceIn(0, rows.lastIndex)
    // 1. 屏幕里已经有用户气泡：不冻。
    if (lastVisibleIndex >= 0) {
        val lastRow = (lastVisibleIndex - leadingItems).coerceIn(0, rows.lastIndex)
        for (i in firstRow..lastRow) {
            if (rows.getOrNull(i)?.role == Model.Role.USER) return null
        }
    }
    // 2. 往上找最近的一条用户消息。
    for (i in (firstRow - 1) downTo 0) {
        val m = rows[i]
        if (m.role == Model.Role.USER && m.text.isNotBlank()) return m
    }
    return null
}

/** 草稿会话（还没在宿主上创建）的输入草稿落盘键。 */
private const val NEW_DRAFT_KEY = "draft-new"

/**
 * 看门狗节拍。
 *
 * 比插件的 5 秒脉搏快一档：判定阈值是 12 秒（[com.dsh.mobile.v2.net.SyncCoordinator.PULSE_DEAD_MS]），
 * 一秒一查才能在 12~13 秒之间就动手，而不是拖到 17 秒。代价只是每秒一次
 * 纯计算（比较两个 Long），不碰网络。
 */
private const val TICK_MS = 1_000L
