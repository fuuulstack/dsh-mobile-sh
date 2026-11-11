package com.dsh.mobile.v2.data

import com.dsh.mobile.v2.net.arr
import com.dsh.mobile.v2.net.int
import com.dsh.mobile.v2.net.bool
import com.dsh.mobile.v2.net.clipText
import com.dsh.mobile.v2.net.long
import com.dsh.mobile.v2.net.contentOf
import com.dsh.mobile.v2.net.obj
import com.dsh.mobile.v2.net.queuedOf
import com.dsh.mobile.v2.net.str
import com.dsh.mobile.v2.net.toolActionOfRecord
import com.dsh.mobile.v2.net.toolArgumentsOf
import com.dsh.mobile.v2.net.todosOf
import com.dsh.mobile.v2.net.toolResultOf
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 投影器：`List<TimelineRecord>` → `List<TurnUiModel>`。
 *
 * **纯函数**，没有任何 Android / 协程 / 网络依赖，所以能用 JVM 单测把坑 12/27/37
 * 那类「一轮渲染成上下两截」「面板跑到气泡上方」的错误全部钉住 —— 这些 bug 在
 * 真机上要复现几分钟，在这里是一条 5 行的用例。
 *
 * 与旧 `SessionAccumulator` 的根本差别：它不再需要维护「当前面板是哪个对象」。
 * 旧的实现把状态放在可变对象上（`process`、`processTurn`、`bySeq`），于是
 * `turn/end` 必须先置空再返回，身份比对必然失败（bug 12）；而且面板的 `order`
 * 要靠 `seq - 0.25` 去挤位置（bug 10/27）。这里换个做法：**按 turnId 分组，
 * 槽位固定**，位置由渲染顺序决定，不再有小数、不再有共享 seq 的键冲突。
 */

/** 一轮在构建过程中的可变累积形态（只在投影期间存在，不出函数）。 */
private class TurnBuilder(val turnId: String, val anchorSeq: Long) {
    var user: Model.Message? = null
    /** 已封口的段（每个都带自己的面板与正文）。 */
    val segments = ArrayList<TurnSegment>()
    /** 当前正在攒的那段的面板步骤/动作。 */
    val panelSteps = ArrayList<Model.Step>()
    val panelActions = ArrayList<Model.ToolAction>()
    /** 当前段的正文 —— 只装还没封口的那几条。 */
    val pendingBlocks = ArrayList<Model.Message>()
    val cards = ArrayList<InteractionCard>()
    var panelRunning = false
    var panelFailure = ""
    /** 用户主动停止（`turn/end` 的 `reason.kind == "aborted"`）。 */
    var panelAborted = false
    /**
     * 当前这块面板的锚点 seq：这一段里**第一条**思考/工具记录的 seq。
     *
     * 一轮现在会有多块面板，每块必须有自己的锚点 —— 否则所有面板的
     * `panelId`/`order` 都一样（LazyColumn 撞 key、抽屉点哪块都开最后一块）。
     */
    var panelStartSeq = 0L
    /**
     * 这一轮里出现过的最大 seq。
     *
     * <p>会话级状态（队列/任务）的权威副本在**最后被写入的那一轮**上，而「最后写入」
     * 只能按 seq 判 —— `turns` 是按 turnId 首次出现的顺序追加的，轮次之间会交错
     * （t0 写了一轮、t1 插进来，之后 t0 又收到 splice）。
     */
    var lastSeq = 0L
    var startedAt = 0L
    var endedAt = 0L
    var currentAction = ""
    var liveThinking = ""
    /**
     * 队列的当前形态。
     *
     * `agent/inbox/spliced` 是**增量**（splice），它描述的是整个会话 inbox 的
     * 片段，不是某一轮的。所以这份列表必须跟着 `projectTimeline`
     * 的循环一路传下去，不能每个轮各拿一份空白重来。
     */
    var queue: List<Model.QueuedMessage> = emptyList()
    /** 任务清单。`todo/write` 是**整表覆盖**，同样是会话级状态。 */
    var todos: List<Model.Todo> = emptyList()
    /** 已见过的 tool callId → actions 下标，用于 tool/result 回填。 */
    val byCallId = HashMap<String, Int>()

    /**
     * 这一轮里**已经结算过**的审批/提问 callId。
     *
     * <p>用于挡「先 decided、后 asked」和「跨轮回放时 decided 在另一轮」两种顺序：
     * 归档是按 seq 升序回放的，`asked` 一定在 `decided` 之前，理论上用不到这个集合；
     * 但**中继的滚动窗口会把旧的 asked 滚掉、留下新的 decided**（2000 条窗口按 seq
     * 截断，边界正好切在两半中间时就是这个形态）。只靠 `settleCard` 的顺序移除会漏掉
     * 那种「decided 到达时卡片还不存在」的情形。
     */
    val settledCalls = HashSet<String>()
    var lastThinkingStep: Model.Step? = null

    /**
     * 封口：把「此刻累积的思考/工具 + 待挂正文」落成一个段。
     *
     * 正文到达时调用它，于是这段正文之后来的思考/工具自然归到下一段 ——
     * 「每输出一段正文，就开启一个新的折叠栏」（2026-10-31 用户要求）。
     *
     * @param closed 这一段是否已经收尾（中间的段一律已收尾：正文都出来了，
     *   产出它的那段过程自然结束）。收尾的段不转圈、不报「正在分析」。
     * @param allowEmptyPanel 是否允许「只有 running、没有任何步骤」也占一个段。
     *   只有**第一段**需要（用户刚发消息、思考还没到，折叠行得先占位），
     *   中间的段不行——否则每段正文后面都会多出一条空的「正在分析」。
     */
    fun sealSegment(closed: Boolean, allowEmptyPanel: Boolean = false) {
        val hasContent = panelSteps.isNotEmpty() || panelActions.isNotEmpty() ||
            panelFailure.isNotEmpty() || (panelRunning && allowEmptyPanel)
        if (!hasContent && pendingBlocks.isEmpty()) return
        segments.add(
            TurnSegment(
                panel = if (hasContent) buildPanel(closed) else null,
                blocks = pendingBlocks.toList().toPersistentList(),
            )
        )
        pendingBlocks.clear()
        panelSteps.clear()
        panelActions.clear()
        byCallId.clear()
        lastThinkingStep = null
        liveThinking = ""
        panelFailure = ""
        panelStartSeq = 0L
    }
}

/**
 * 主入口。
 *
 * [records] 必须按 `seq` 升序（调用方从 Room 顺读即得）。乱序也能work，因为分组
 * 靠 turnId 且追加有序；但有序能让「最后一段正文」这类判断是 O(1)。
 */
fun projectTimeline(records: List<TimelineRecord>): List<TurnUiModel> {
    if (records.isEmpty()) return emptyList()
    val turns = ArrayList<TurnBuilder>()
    val index = HashMap<String, TurnBuilder>()
    // 没有 turnId 的旧记录（迁移前归档）要归到某一轮里，否则它们会连成一条
    // 无边界的巨轮，面板永远在跑了。策略：挂到最近一个 USER_INPUT 所在的轮；
    // 一个都还没有时合成一条隐式轮（v2 §九 Top3 风险 ② 的验收要求）。
    var current: TurnBuilder? = null

    for (record in records) {
        val turnId = record.turnId.ifEmpty {
            // 孤儿记录：优先并进"当前轮"，没有就新开一轮并标记成隐式。
            current?.turnId ?: "implicit:${record.sessionId}:${record.seq}"
        }
        val turn = index.getOrPut(turnId) {
            // 新轮要继承会话级状态（队列/任务）：两者都不属于某一轮，
            // 而是跟着会话一路变下去的。新轮从零开始的话，列表会在
            // 下一次 splice 之前显示为空——那是真的清空了，不是忽略了上一轮。
            TurnBuilder(turnId, record.seq).also {
                it.queue = current?.queue.orEmpty()
                it.todos = current?.todos.orEmpty()
                turns.add(it)
            }
        }
        current = turn
        if (record.seq > turn.lastSeq) turn.lastSeq = record.seq
        // 存根：正文还不在本地（要么正在补、要么补不下来）。必须给出一条看得见的
        // 占位，而不是把它当成一条空正文默默跳过 —— 「回复丢了」比「这条内容很大，
        // 正在取回」糟得多。
        //
        // 判据是 `isOversizedStub` 而不是 kind：存根保留原记录的类型
        // （ASSISTANT_TEXT / USER_INPUT…），只是 payload 换成了 `{type:"stub/payload"}`。
        // 所以这一步要走在 kind 分发**之前**。
        if (record.isOversizedStub) {
            turn.pendingBlocks.add(
                Model.Message(
                    role = Model.Role.SYSTEM,
                    text = stubNotice(record),
                    seq = record.seq,
                    time = record.timestampMs,
                )
            )
            continue
        }
        when (record.kind) {
            RecordKind.USER_INPUT -> {
                val text = userText(record)
                turn.user = Model.Message(
                    role = Model.Role.USER,
                    text = text,
                    // seq 保留真实值：向前翻页与「这条消息在第几条」仍以它为准。
                    seq = record.seq,
                    time = record.timestampMs,
                    // 用户自己发的图：durable 日志里只有 attachmentId，字节要单独取。
                    // 不带上这一段，重进会话后自己发的图就只剩一行文件名
                    // （2026-11-01 用户直报「历史里的图片不显示」）。
                    images = imagesOf(record).toMutableList(),
                )
                turn.startedAt = record.timestampMs
                turn.panelRunning = true
            }

            RecordKind.ASSISTANT_TEXT -> {
                val text = assistantText(record)
                val reasoning = reasoningOf(record)
                appendThinking(turn, reasoning, record.timestampMs, record.seq)
                if (text.isNotEmpty()) {
                    // 正文到达 ⇒ 产出它的那段过程到此结束，先封口，正文挂在这一段后面。
                    // 此后到达的思考/工具会落进**下一段**的面板 —— 这就是「每输出一段
                    // 正文，就开启一个新的折叠栏」（2026-10-31 用户要求）。
                    turn.sealSegment(closed = true)
                    turn.pendingBlocks.add(
                        Model.Message(
                            role = Model.Role.ASSISTANT,
                            text = text,
                            seq = record.seq,
                            time = record.timestampMs,
                            images = imagesOf(record).toMutableList(),
                        )
                    )
                    // 正文自己就算这个段的全部内容时也要落地：立刻再封一次，
                    // 否则「只有正文、没有任何工具」的回合会攒到最后才出段。
                    turn.sealSegment(closed = false)
                }
            }

            RecordKind.STEP_THINKING -> {
                // 只有实时增量会以这个 kind 直接过来；归档里思考都并进
                // ASSISTANT_TEXT 的 reasoning part（见 classify.ts）。
                val text = record.payloadOf().obj("data").str("text")
                if (text.isNotEmpty()) appendThinking(turn, text, record.timestampMs, record.seq)
            }

            RecordKind.STEP_TOOL -> applyTool(turn, record)

            RecordKind.TURN_END -> {
                turn.panelRunning = false
                turn.endedAt = record.timestampMs
                // 供应商/模型层报错走 `turn/end` 的 `data.reason`：
                // `{kind:"error", error:{message, code}}`（`dsh-agent-loop/lib/index.js`
                // 的 catch 分支）。桌面端会把整条原文摊开给人看，手机端以前**整段丢掉**
                // ——`panelFailure` 字段一直存在却没人写，所以 429 限流、上下文超长、
                // 鉴权失败这些在手机上都表现为「回合莫名其妙结束了」。
                //
                // 这里只落一段人话 + 原始 code：原文可能很长（截图那条 300+ 字），
                // 折叠行放不下，展开抽屉才看全。
                // `turn/end` 的 `reason.kind` 共五种（`dsh-agent-loop/lib/index.js`）：
                // `completed` / `error` / `aborted` / `blocked` / `max-tokens`。
                //
                // 以前只认 `error`，其余三种**静默丢掉**——其中 `max-tokens` 是
                // 桌面端会显著提示的那种（「已达到输出 token 上限，回答被截断，
                // 发送"继续"可让模型接着输出」），手机上一个字都没有，用户只看到
                // 回答突然断了（2026-11-01 用户实测）。
                val reason = record.payloadOf().obj("data")?.obj("reason")
                val kind = reason?.str("kind").orEmpty()
                when (kind) {
                    "error" -> {
                        val err = reason?.obj("error")
                        val msg = err?.str("message").orEmpty().ifEmpty { "本轮运行失败" }
                        val code = err?.str("code").orEmpty()
                        turn.panelFailure = if (code.isEmpty() || code == "UNKNOWN") msg else "$code：$msg"
                    }
                    // 桌面端原文：「已达到输出 token 上限 回答被截断，已有输出保留在
                    // 对话中。发送"继续"可让模型接着输出。」——它**不是错误**，是
                    // 一次正常的提前收尾，但用户必须知道「可以继续」，否则会以为
                    // 模型答不下去了。所以照抄这段语义，不写成「失败」。
                    "max-tokens" -> turn.panelFailure =
                        "已达到输出 token 上限，回答被截断。" +
                            "已有输出保留在对话中，发送「继续」可让模型接着输出。"
                    // 用户主动停止：不需要醒目提示，但也不能让这一段看起来「已完成」。
                    "aborted" -> turn.panelAborted = true
                    // 被安全/额度策略拦下：桌面端有专门文案，这里用中性说法。
                    "blocked" -> turn.panelFailure = "这一轮被宿主拦下，没有继续执行"
                    else -> Unit
                }
                if (turn.panelFailure.isNotEmpty() || kind == "aborted") {
                    // **位置锚点**：这类收尾段通常没有任何步骤，`panelStartSeq` 会是 0，
                    // 于是 `panelOrder` 退回「本轮最后一条记录的 seq + 0.25」——
                    // 没有这个锚点，收尾段会跟同轮其它活面板挤在同一批位置上。
                    // 用 `turn/end` 自己的 seq 当锚点，它就稳稳落在这一轮的最后。
                    if (turn.panelStartSeq <= 0) turn.panelStartSeq = record.seq
                }
            }

            // 已经结算过的审批不再产卡：它不是「现在必须做的一件事」，是历史。
            //
            // 2026-11-01 用户质问「归档里的审批卡为什么还需要点击」——问得对。原实现
            // 让它躺在悬浮层里、按钮置灰、还写一行「需要在电脑端重新发起」，那是把
            // **设计错误包装成了提示**：一张已结算的审批根本不该占据任何屏幕。
            RecordKind.APPROVAL_REQ -> approvalCard(record)?.let {
                if (it.approval?.callId?.let { c -> c.isNotEmpty() && c in turn.settledCalls } != true) {
                    turn.cards.add(it)
                }
            }
            RecordKind.QUESTION_REQ -> questionCard(record)?.let {
                if (it.question?.callId?.let { c -> c.isNotEmpty() && c in turn.settledCalls } != true) {
                    turn.cards.add(it)
                }
            }
            // 结算与撤回：把对应卡片撤下（账本层另有过滤，这里只保证投影自洽）。
            RecordKind.APPROVAL_RES, RecordKind.QUESTION_RES -> settleCard(turn, record)

            RecordKind.ERROR_OVERSIZED_TRUNCATED -> {
                // 明确报错，不许静默丢弃（不变量 5）。
                turn.pendingBlocks.add(
                    Model.Message(
                        role = Model.Role.SYSTEM,
                        text = "这条内容超过了体积上限，只保留了首尾各 32 KiB。",
                        seq = record.seq,
                        time = record.timestampMs,
                    )
                )
            }

            // 队列与任务：两者都是带 seq 的真会话日志事件，不是"变更通知"。
            // v1 只能从 `/rpc/projections` 读它们，而那条路在生产日志里 4/4 都是
            // ECONNRESET，所以卡片会静默冻住。改走归档后它们与正文同源。
            RecordKind.INBOX_SPLICE -> applyInboxSplice(turn, record)
            RecordKind.TASK_LIST -> {
                val list = record.payloadOf().obj("data")?.arr("todos")
                if (list != null) turn.todos = todosOf(list)
            }

            // STREAM_DELTA 走 overlay，不进 SQLite 投影；META_PATCH 不影响时间线。
            // 后台任务不参与时间线：它是「此刻在跑什么」的会话级快照，
            // 由 `jobsOf(...)` 单独算，塞进轮次里只会平白多出一堆空段。
            RecordKind.JOB_EVENT,
            RecordKind.STREAM_DELTA, RecordKind.META_PATCH -> Unit
        }
    }

    return turns.map { it.build(live = false) }
}

/**
 * 一次投影的现场覆盖层（v2 §4.3 双层隔离）。
 *
 * 只存**还没落盘**的东西：`FOCUS_SESSION` 回放帧、`STREAM_DELTA` 的流式正文。
 * 同 seq 以 SQLite 为准，所以覆盖层在落盘后要能逐条让位 —— 这让「实时到的确定性
 * 事件因 prevSeq 大于本地水位而先进 buffer」不会污染水位，也不会在落盘后重复。
 */
data class Overlay(
    /** 按 seq 排的重放/未落盘记录；同 seq 覆盖。 */
    val uncommitted: Map<Long, TimelineRecord> = emptyMap(),
    /** 流式正文（会随 STREAM_DELTA 增长，落定后由 ASSISTANT_TEXT 取代）。 */
    val streamingText: String = "",
    val streamingTurnId: String = "",
    /** 流式思考：进折叠行尾部预览，**绝不**进正文气泡。 */
    val liveReasoning: String = "",
    val reasoningTurnId: String = "",
    /** 这一轮是否在跑（来自 pulse / turn 状态，不来自 SQLite）。 */
    val liveTurnId: String = "",
) {
    fun withStreaming(turnId: String, text: String): Overlay =
        copy(streamingText = text, streamingTurnId = turnId)

    fun clearStreaming(): Overlay = copy(streamingText = "", streamingTurnId = "")

    fun withReasoning(turnId: String, text: String): Overlay =
        copy(liveReasoning = text, reasoningTurnId = turnId)

    fun clearReasoning(): Overlay = copy(liveReasoning = "", reasoningTurnId = "")

    /** local_acked_seq 推进后清掉已落盘的条目（它们是 shadow 而非来源）。 */
    fun compactedTo(localAcked: Long): Overlay =
        if (uncommitted.keys.none { it <= localAcked }) this
        else copy(uncommitted = uncommitted.filterKeys { it > localAcked })

    companion object {
        val EMPTY = Overlay()
    }
}

/**
 * 合并 SQLite 记录与覆盖层，再投影。
 *
 * 同 seq **以 SQLite 为准**（它已经落盘、经过事务校验）；覆盖层只填空缺的高位。
 * 流式正文作为一条临时 ASSISTANT_TEXT 参与投影，因而绘制路径与落定后完全一致。
 */
fun project(records: List<TimelineRecord>, overlay: Overlay = Overlay.EMPTY): List<TurnUiModel> {
    val merged = mergeRecords(records, overlay)
    val projected = projectTimeline(merged).toMutableList()
    applyStreaming(projected, overlay, records)
    return projected
}

/**
 * `project` 用的那份记录合并：已落盘的一批 + overlay 里还没落盘的一批（按 seq 去重升序）。
 *
 * <p>抽出来是因为**会话级状态要吃同一份输入**。后台任务（见 [jobsOf]）不在宿主的投影表里、
 * 只能靠记录自己聚合，而刚起的那条任务**先落在 overlay 里**：只扫已落盘的那批，
 * 界面上会漏掉「正跑着、还没同步」的任务 —— 那恰好是用户最想看到的那条。
 */
fun mergeRecords(records: List<TimelineRecord>, overlay: Overlay): List<TimelineRecord> =
    if (overlay.uncommitted.isEmpty()) {
        records
    } else {
        val seen = records.mapTo(HashSet(records.size)) { it.seq }
        val extras = overlay.uncommitted.values.filter { it.seq !in seen }.sortedBy { it.seq }
        if (extras.isEmpty()) records else (records + extras).sortedBy { it.seq }
    }

/**
 * 把覆盖层里的流式正文与流式思考并入投影结果。
 *
 * <p>两者**必须分开**：正文进 `assistantBlocks`（聊天气泡），思考进
 * `processPanel.tailThinkingPreview`（折叠行尾部预览）。早先共用一条通道，
 * 手机上表现为「思考过程被当正文铺在聊天页，折叠行里反而什么都没有」。
 */
private fun applyStreaming(
    projected: MutableList<TurnUiModel>,
    overlay: Overlay,
    records: List<TimelineRecord>,
) {
    if (overlay.liveReasoning.isNotEmpty()) {
        // 精确匹配 turnId；匹配不到时退到**最后一轮**。
        //
        // 为什么要退：思考增量没有 seq，它在 durable 记录之前就到了。宿主的
        // `turn/start` 往往比第一片 reasoning 晚几十到几百毫秒，这段窗口里
        // `projected` 里根本没有 `overlay.reasoningTurnId` 那一轮 —— 精确匹配
        // 落空，折叠行一直空着，直到第一个 durable 事件把轮建出来。用户看到的就是
        // 「开头一段思考没有预览」（2026-10-30 实测：reasoning 帧已到手机、
        // 折叠行仍是空的）。退到最后一轮是安全的：思考增量永远属于正在进行的那一轮。
        val exact = projected.indexOfLast { it.turnId == overlay.reasoningTurnId }
        // 退到最后一轮**必须**限定「这一轮还没收尾」：宿主的新一轮 turn/start
        // 比第一片思考晚到时，最后一轮是上一轮（已经 footnote 收尾）。无条件退过去
        // 会把新思考贴到旧面板上，旧轮凭空复活成「正在跑」，比不显示更糟。
        val fallback = projected.indexOfLast {
            it.processPanel?.turnClosed != true && it.turnState != TurnState.COMPLETED
        }
        val at = if (exact >= 0) exact else fallback
        if (at >= 0) {
            projected[at] = projected[at].withLiveReasoning(overlay.liveReasoning)
        } else {
            // 连一轮都没有（会话刚开、一条 durable 记录还没到）：造一轮，
            // 否则折叠行要等到 turn/start 才出现，中间那段思考谁也看不见。
            projected.add(
                TurnUiModel(
                    turnId = overlay.reasoningTurnId.ifEmpty { "live-reasoning" },
                    anchorSeq = Long.MAX_VALUE,
                    segments = persistentListOf(TurnSegment(panel = livePanel(overlay.reasoningTurnId, overlay.liveReasoning))),
                    turnState = TurnState.RUNNING,
                )
            )
        }
    }
    val live = overlay.streamingText.trim()
    if (live.isEmpty()) return
    val liveTurn = overlay.streamingTurnId
    val at = projected.indexOfLast { it.turnId == liveTurn }
    if (at < 0) {
        // 流式正文先于任何 durable 记录到达：给它一个临时轮，落定后自然被替换。
        projected.add(
            TurnUiModel(
                turnId = liveTurn.ifEmpty { "live:" + (records.lastOrNull()?.turnId ?: "") },
                anchorSeq = Long.MAX_VALUE,
                segments = persistentListOf(
                    TurnSegment(blocks = persistentListOf(streamingMessage(live)))
                ),
                turnState = TurnState.RUNNING,
            )
        )
        return
    }
    val turn = projected[at]
    // 流式正文已经落定成 durable 记录时不要重复画：判据是「**最后一段**的正文以
    // 流式文本开头且不短于它」—— 也就是说流式只是它的前缀。
    //
    // 两个条件缺一不可，早先只有长度比较，且遍历**所有**段：
    // - 只看最后一段：一轮里可以有多段正文（思考 → 正文 A → 工具 → 正文 B）。
    //   正文 A 落定后长度 100，正文 B 正在流式、才 20 个字 —— 旧判据
    //   `any { it.text.length >= streaming.length }` 命中 A 就整段抑制，
    //   用户看到「卡住 → 突然整段跳现」。
    // - 要求前缀关系：长度够长但内容完全不同的另一段不该压掉正在流式的新段。
    //
    // 比较的两侧必须同一口径：durable 正文由 [assistantText] 末尾 `.trim()` 过，而
    // 流式文本来自 `payloadTextOf` 原样取出。宿主正文常以 `\n\n` 开头（实测 botw 会话
    // `seq=10388`：原始 3670 字，trim 后 3668），于是 `length >=` 恒假、`startsWith`
    // 恒假 —— 去重永不生效，同一条正文被画两份（流式那份 `seq=0` 还会被
    // `ChatViewModel.position()` 算成 0 排到时间线最前）。这里对流式侧同样 trim。
    val lastBlock = turn.assistantBlocks.lastOrNull()
    if (lastBlock != null && lastBlock.text.length >= live.length && lastBlock.text.startsWith(live)) return
    projected[at] = turn.withStreamingBlock(streamingMessage(live))
}

/**
 * 把活思考贴到这一轮**正在攒的那一段**上。
 *
 * 段是「面板 + 它产出的正文」，所以活思考属于**最后一段**，且只在它还没有正文时
 * 才是它的（有正文说明那段已经封口，正文刚出来，接下来的思考属于**新的一段**）。
 * 这个判断就是「每输出一段正文，就开启一个新的折叠栏」在覆盖层上的落点：
 * durable 路径由 `sealSegment` 切段，流式路径必须切得一模一样，否则用户会看到
 * 折叠栏在正文冒出来的那一刻忽然把下面的正文包进自己。
 */
private fun TurnUiModel.withLiveReasoning(full: String): TurnUiModel {
    val last = segments.lastOrNull()?.takeIf { it.blocks.isEmpty() }
    val panel = (last?.panel ?: ProcessPanelNode(turnId = turnId))
        .copy(
            tailThinkingPreview = full.replace('\n', ' ').trim().takeLast(60),
            liveThinkingFull = full,
            isRunning = true,
            turnClosed = false,
        )
    val updated = if (last != null) {
        (segments.dropLast(1) + last.copy(panel = panel)).toPersistentList()
    } else {
        (segments + TurnSegment(panel = panel)).toPersistentList()
    }
    return copy(segments = updated, turnState = TurnState.RUNNING)
}

/** 流式正文：挂到最后一段（没有就造一段），保持「面板 → 正文」的段内顺序。 */
private fun TurnUiModel.withStreamingBlock(block: Model.Message): TurnUiModel {
    val last = segments.lastOrNull()
    val updated = if (last != null) {
        (segments.dropLast(1) + last.copy(blocks = (last.blocks + block).toPersistentList())).toPersistentList()
    } else {
        (segments + TurnSegment(blocks = persistentListOf(block))).toPersistentList()
    }
    return copy(segments = updated, turnState = TurnState.RUNNING)
}

/** 只有活思考、durable 一条都没到时用的空面板（没有锚点，位置由轮锚点兜底）。 */
private fun livePanel(turnId: String, full: String) = ProcessPanelNode(
    turnId = turnId,
    isRunning = true,
    tailThinkingPreview = full.replace('\n', ' ').trim().takeLast(60),
    liveThinkingFull = full,
    turnClosed = false,
)

private fun streamingMessage(text: String) = Model.Message(
    role = Model.Role.ASSISTANT,
    text = text,
    seq = 0,
    streaming = true,
    time = System.currentTimeMillis(),
)

// ============================================================
// 分组的收尾：把累积形态变成不可变模型
// ============================================================

private fun TurnBuilder.build(live: Boolean): TurnUiModel {
    // 收尾：把还没落成段的东西（最后一段正在跑的面板、或末尾没有面板的正文）封口。
    //
    // 这里**不能**无条件补一块空面板：一轮里的步骤早就被正文封口带走了，无条件补
    // 只会在每轮末尾多出一条永远转圈、点开什么都没有的「正在分析」（实测：一轮
    // 3 条消息渲染成 4 行，最后一行是空面板）。只有整轮一条操作都还没有时才补 ——
    // 那时折叠行是「它正在干活」的唯一信号，得先占位。
    // 先记下「这一轮是不是失败收场」再封口：`sealSegment` 会把 `panelFailure` 清空
    // （它已经交进那一段的面板里了），清完之后再判状态就永远是 COMPLETED。
    // 2026-11-01 补失败原因时踩到：文本进了面板、状态却是「已完成」。
    val failed = panelFailure.isNotEmpty() || panelAborted
    val failureText = panelFailure
    sealSegment(
        closed = !(live || panelRunning),
        allowEmptyPanel = segments.isEmpty() && !live && panelRunning,
    )
    // 失败原因**可能比它属于的那一段晚到**：正文一到就 `sealSegment` 封口了，
    // 而 `turn/end` 的 reason 是最后一条记录。这时 `panelFailure` 既没进刚封的段
    // （那一段早就建好了），也没法再开一段（没有步骤、没有待挂正文，
    // `sealSegment` 会直接 return）——整条失败就这么消失。
    //
    // 所以失败要**补到最后一段的面板上**：它就是这一轮的结局，不属于任何单个步骤。
    if (failureText.isNotEmpty() && segments.isNotEmpty() &&
        segments.last().panel?.failure.isNullOrEmpty()
    ) {
        val last = segments.last()
        segments[segments.lastIndex] = last.copy(
            panel = (last.panel ?: ProcessPanelNode(turnId = turnId)).copy(
                failure = failureText,
                turnClosed = true,
            )
        )
    }
    // 空的、什么都没有的段不占位置。
    val sealed = segments.filter { it.visible }.toPersistentList()
    // 整轮是否在跑由回合级状态决定：宿主每步都发 step/end，isRunning 走完一步就
    // false，但整轮可能还在继续（下一步的思考、或者正在往外吐正文）。
    val running = live || panelRunning
    val state = when {
        cards.isNotEmpty() -> TurnState.WAITING_INTERACTION
        running -> TurnState.RUNNING
        failed -> TurnState.INTERRUPTED
        else -> TurnState.COMPLETED
    }
    return TurnUiModel(
        turnId = turnId,
        anchorSeq = anchorSeq,
        lastSeq = lastSeq,
        userMessage = user,
        segments = sealed,
        interactions = cards.toPersistentList(),
        queue = queue.toPersistentList(),
        todos = todos.toPersistentList(),
        turnState = state,
    )
}

/**
 * 把当前累积的思考/工具做成一块面板。
 *
 * @param closed 这一段已经收尾（后面紧跟着它产出的正文）。收尾的段不转圈。
 */
private fun TurnBuilder.buildPanel(closed: Boolean): ProcessPanelNode {
    val running = !closed && (panelRunning || panelSteps.isNotEmpty())
    // 尾部预览：先压平换行，再取末尾 60 字（bug 26 —— 取头部等于永远不更新）。
    // 全文另存 liveThinkingFull：折叠行吃尾窗，二级抽屉的思考详情吃全文。
    val tail = liveThinking.replace('\n', ' ').trim().takeLast(60)
    return ProcessPanelNode(
        turnId = turnId,
        isRunning = running,
        // 动作名照实留下（收尾的段不转圈，UI 也不会画它）：它是这块面板「最后
        // 在干什么」的事实记录，折叠行摘要与抽屉标题都还要靠它。
        currentActionLabel = currentAction,
        tailThinkingPreview = tail,
        liveThinkingFull = liveThinking,
        totalOpsCount = panelActions.size,
        durationSec = if (!running && startedAt > 0 && endedAt > startedAt) {
            maxOf(1, Math.round((endedAt - startedAt) / 1000.0))
        } else {
            0
        },
        steps = panelSteps.toPersistentList(),
        actions = panelActions.toPersistentList(),
        failure = panelFailure,
        turnClosed = closed,
        // 这一块面板的锚点：这一段里第一条思考/工具的 seq。0 表示还没有 durable
        // 记录（只有活思考），此时面板身份退回 turnId、位置退回轮锚点之后。
        panelSeq = panelStartSeq,
    )
}

/**
 * 追加一段思考。
 *
 * 连续到达的思考并进同一个 THINKING 步骤（界面上一块），工具调用之后再来的思考
 * 另起一块。这样抽屉里是「思考 → 工具 → 思考」的真实交替，而不是"全部步骤在上、
 * 正文在下"（bug 27 里那个错误形态）。
 */
private fun appendThinking(turn: TurnBuilder, text: String, at: Long, seq: Long) {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return
    // 这一段的第一条思考就是这块面板的锚点（身份与位置都从它来）。
    if (turn.panelStartSeq <= 0 && seq > 0) turn.panelStartSeq = seq
    val last = turn.panelSteps.lastOrNull()
    if (last != null && last.kind == Model.Step.THINKING) {
        // durable 全文比增量更长时补全（相等就不动，免得每帧都改对象）。
        if (trimmed.length > last.text.length) {
            last.text = trimmed
            turn.liveThinking = trimmed
        }
    } else {
        val step = Model.Step(kind = Model.Step.THINKING, text = trimmed, id = Model.Step.nextId())
        turn.panelSteps.add(step)
        turn.lastThinkingStep = step
        turn.liveThinking = trimmed
    }
    turn.currentAction = "思考中"
    if (turn.startedAt <= 0) turn.startedAt = at
}

/**
 * 应用一条工具事件。
 *
 * `tool/call` 与 `tool/result` 都归到 [RecordKind.STEP_TOOL]，靠 `callId` 一对一认人：
 * 一个 call 一行，结果回填到同一行。找不到 call 时退到「最后一条仍在跑的行」，
 * 宁可归错也不让结果凭空消失（宿主不同版本会漏 callId）。
 */
private fun applyTool(turn: TurnBuilder, record: TimelineRecord) {
    val payload = record.payloadOf()
    val data = payload.obj("data") ?: return
    val type = payload.str("type")
    val at = if (record.timestampMs > 0) record.timestampMs else System.currentTimeMillis()
    if (type == "tool/result") {
        val callId = toolResultOf(data).callId
        val index = turn.byCallId[callId]
            ?: turn.panelActions.indexOfLast { it.status == Model.ToolAction.Status.RUNNING }
        if (index >= 0) {
            val action = turn.panelActions[index]
            val failed = data["error"] != null && data["error"] !is kotlinx.serialization.json.JsonNull ||
                data.bool("isError", false) ||
                (data.obj("message")?.bool("isError", false) ?: false)
            action.status = if (failed) Model.ToolAction.Status.FAILED else Model.ToolAction.Status.DONE
            action.provisional = false
            action.durationMs = if (action.startedAt > 0) maxOf(0L, at - action.startedAt) else 0
            val output = toolResultOf(data).output
            if (output.isNotEmpty()) action.output = output
            turn.currentAction = "正在分析请求"
        }
        return
    }
    // tool/call（以及流式 tool-call-delta，若插件把它归到这里）
    // 这一段的第一条工具记录同样可以当锚点（有的 step 只有工具、没有新思考）。
    if (turn.panelStartSeq <= 0 && record.seq > 0) turn.panelStartSeq = record.seq
    val parsed = toolActionOfRecord(data)
    val existing = turn.byCallId[parsed.callId]
    if (existing != null) {
        val action = turn.panelActions[existing]
        if (parsed.name.isNotEmpty()) action.name = parsed.name
        if (parsed.detail.isNotEmpty()) action.detail = parsed.detail
        // 流式增量先到、`tool/call` 后到时靠这一句补上小标题（增量那条往往只带得出命令，
        // 描述要等完整参数才看得见）。已经有值就不覆盖，免得残缺增量盖掉全文。
        if (parsed.subtitle.isNotEmpty() && action.subtitle.isEmpty()) action.subtitle = parsed.subtitle
        action.provisional = false
        action.status = Model.ToolAction.Status.RUNNING
    } else {
        val action = Model.ToolAction(
            name = parsed.name,
            callId = parsed.callId,
            detail = parsed.detail,
            subtitle = parsed.subtitle,
            status = Model.ToolAction.Status.RUNNING,
            startedAt = at,
            // 这两个字段是"是不是技能/命令"的标记（决定图标与措辞），不是名字本身；
            // 名字与参数在 detail / Step.text 里。
            skill = parsed.skill.isNotEmpty(),
            command = parsed.command.isNotEmpty(),
        )
        turn.panelActions.add(action)
        turn.panelSteps.add(
            Model.Step(
                kind = Model.Step.TOOL,
                actionIndex = turn.panelActions.size - 1,
                text = parsed.commandText,
                id = Model.Step.nextId(),
            )
        )
        if (parsed.callId.isNotEmpty()) turn.byCallId[parsed.callId] = turn.panelActions.size - 1
    }
    turn.currentAction = parsed.label
    if (turn.startedAt <= 0) turn.startedAt = at
}

/**
 * 从一批记录里算出**此刻还在跑的后台任务**。
 *
 * <p>宿主把任务生命周期当普通事件推（`job/registered` 开跑、`job/settled` 结算），
 * 所以这里只是把已经收到的东西理一遍：注册进表、结算出表。
 *
 * <p>**为什么不在手机上问宿主**：宿主的 `job/list` 是 `mode: 'stream'`
 * （open 时一整帧、之后每次生命周期提交再一帧、没有自然结束），为一个胶囊常驻一条
 * 流不划算；而这两个事件本来就随记录流一路推到手机。
 *
 * <p>两条顺序约束：
 *  - [records] **必须按 seq 升序**（调用方都是从 Room 或 overlay 里按序取出来的）。
 *    顺序反了会让「先结算后注册」看起来像一条在跑的任务。
 *  - 没见过 `registered` 的 `settled` 直接忽略 —— 它的注册记录在更早的窗口外，
 *    不是我们要显示的东西。
 *
 * <p>返回顺序是**注册顺序**（`LinkedHashMap`）：胶囊点开的清单里，先起的排前面。
 */
fun jobsOf(records: List<TimelineRecord>): List<Model.Job> {
    if (records.isEmpty()) return emptyList()
    val live = LinkedHashMap<String, Model.Job>()
    for (record in records) {
        if (!record.isJobLifecycle()) continue
        val data = record.payloadOf().obj("data") ?: continue
        val id = data.str("jobId")
        if (id.isEmpty()) continue
        if (record.eventType() == "job/settled") {
            live.remove(id)
            continue
        }
        live[id] = Model.Job(
            id = id,
            label = data.str("label"),
            kind = data.str("kind"),
            startedAt = data.long("startedAt"),
        )
    }
    return live.values.toList()
}

// ============================================================
// payload 读取（全部容错：宿主字段形状会随版本变，读不到就给中性值）
// ============================================================

private fun TimelineRecord.payloadOf(): JsonObject =
    com.dsh.mobile.v2.net.parseJson(payloadJson) ?: JsonObject(emptyMap())

/** 用户输入的正文；注入型 user/message（`source.kind != user`）不该出现在时间线上。 */
private fun userText(record: TimelineRecord): String {
    val data = record.payloadOf().obj("data") ?: return ""
    val message = data.obj("message")
    return firstNonEmpty(
        com.dsh.mobile.v2.net.textContent(data["content"]),
        data.str("text"),
        message?.let { com.dsh.mobile.v2.net.textContent(it["content"]) } ?: "",
        message?.str("text") ?: "",
    )
}

/** 助手正文（只取 text part；reasoning 走 [reasoningOf]）。 */
private fun assistantText(record: TimelineRecord): String {
    val data = record.payloadOf().obj("data") ?: return ""
    val message = data.obj("message")
    return firstNonEmpty(
        com.dsh.mobile.v2.net.textContent(data["content"]),
        message?.let { com.dsh.mobile.v2.net.textContent(it["content"]) } ?: "",
        data.str("text"),
        message?.str("text") ?: "",
    ).trim()
}

/** durable 助手消息里 `type=reasoning` 的内容块：历史思考的唯一来源。 */
private fun reasoningOf(record: TimelineRecord): String {
    val data = record.payloadOf().obj("data") ?: return ""
    val sb = StringBuilder()
    collectReasoning(data["content"], sb)
    data.obj("message")?.let { collectReasoning(it["content"], sb) }
    return sb.toString().trim()
}

private fun collectReasoning(value: JsonElement?, sb: StringBuilder) {
    if (value !is JsonArray) return
    for (part in value) {
        val o = part as? JsonObject ?: continue
        if (o.str("type") != "reasoning") continue
        val text = firstNonEmpty(o.str("text"), o.str("reasoning"))
        if (text.isEmpty()) continue
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append(text)
    }
}

/** 正文里引用的图片（durable 日志只存 attachmentId）。 */
private fun imagesOf(record: TimelineRecord): List<Model.ImageRef> {
    val data = record.payloadOf().obj("data") ?: return emptyList()
    val images = ArrayList<Model.ImageRef>()
    val sources = listOfNotNull(data["content"], data.obj("message")?.let { it["content"] })
    for (source in sources) {
        val array = source as? JsonArray ?: continue
        for (part in array) {
            val o = part as? JsonObject ?: continue
            if (o.str("type") != "image") continue
            // 宿主的真实形态是 `{type:"image", attachment:{attachmentId,name}}`；
            // 老日志与部分事件把 attachmentId 直接摊在 part 上，还有的嵌在 `source` 里。
            // 三种都得认：只认扁平那一种时，真机历史里**一张图都显示不出来**
            // （2026-11-01 用户直报「图片附件不显示」，单测也因此抓到了这个缺口）。
            val nested = o.obj("attachment") ?: o.obj("source") ?: o
            val id = firstNonEmpty(nested.str("attachmentId"), o.str("attachmentId"))
            if (id.isEmpty()) continue
            images.add(
                Model.ImageRef(
                    attachmentId = id,
                    name = firstNonEmpty(nested.str("name"), o.str("name")),
                )
            )
        }
    }
    return images
}

/**
 * 应用一次 inbox splice。
 *
 * 宿主的语义（`ReactLoopInbox.mutate`）是「在 `target` 桶的第 `start` 位上，
 * 先删 `removedCount` 条，再插入 `inserted`」。两个桶拼在一个平铺列表里
 * 展示，所以下标要先加上前一个桶的长度。
 *
 * 越界一律钳位而不丢弃：下标错了顶多是排序偏一点，直接跳过这条事件
 * 则会让队列永久停在旧值上。
 */
private fun applyInboxSplice(turn: TurnBuilder, record: TimelineRecord) {
    val data = record.payloadOf().obj("data") ?: return
    val target = data.str("target")
    val raw = data.arr("inserted") ?: return
    val inserted = ArrayList<Model.QueuedMessage>(raw.size)
    for (item in raw) {
        val o = item as? JsonObject ?: continue
        queuedOf(o, target)?.let { inserted.add(it) }
    }
    val current = turn.queue
    if (target != "next-turn" && target != "next-step") return
    val head = if (target == "next-turn") 0 else current.count { it.target == "next-turn" }
    val size = current.count { it.target == target }
    val at = head + data.int("start").coerceIn(0, size)
    val drop = data.int("removedCount").coerceIn(0, size - (at - head))
    val next = current.toMutableList()
    repeat(drop) { if (at < next.size) next.removeAt(at) }
    next.addAll(at.coerceAtMost(next.size), inserted)
    turn.queue = next
}

/** 审批卡：`interaction_id` 必须是 `"<sessionId>:<seq>"`。 */
private fun approvalCard(record: TimelineRecord): InteractionCard? {
    val data = record.payloadOf().obj("data") ?: return null
    val approval = Model.Approval()
    approval.eventId = interactionIdOf(record)
    // 这个 id 是 `sessionId:seq`，宿主不认 —— 归档重建的卡**答不回去**。
    // 真正可回答的 id 只从 `$events` 的 waterfall 来，两边靠 callId 对账
    // （AppViewModel.eventIdByCallId）。这里必须显式置 false，否则界面会把
    // 一张点不动的卡画成"可以点"，用户点完以为批过了、那一轮却还卡着。
    approval.answerable = false
    approval.agentId = record.sessionId
    approval.toolName = firstNonEmpty(data.str("toolName"), data.obj("tool")?.str("name") ?: "")
    approval.callId = data.str("callId")
    approval.reason = data.str("reason")
    approval.displayReason = firstNonEmpty(data.str("displayReason"), approval.reason)
    return InteractionCard(
        interactionId = approval.eventId,
        sessionId = record.sessionId,
        kind = InteractionCard.Kind.APPROVAL,
        approval = approval,
    )
}

private fun questionCard(record: TimelineRecord): InteractionCard? {
    val data = record.payloadOf().obj("data") ?: return null
    val raw = data.arr("questions") ?: return null
    val card = Model.QuestionCard()
    card.eventId = interactionIdOf(record)
    card.answerable = false
    card.agentId = record.sessionId
    card.callId = data.str("callId")
    for (item in raw) {
        val o = item as? JsonObject ?: continue
        val q = Model.Question()
        q.id = o.str("id")
        q.question = o.str("question")
        q.detail = o.str("detail")
        q.header = o.str("header")
        q.multiSelect = o.bool("multiSelect", false)
        o.arr("options")?.forEach { opt ->
            val oo = opt as? JsonObject ?: return@forEach
            q.options.add(
                Model.QuestionOption().apply {
                    label = oo.str("label")
                    description = oo.str("description")
                }
            )
        }
        card.questions.add(q)
    }
    return InteractionCard(
        interactionId = card.eventId,
        sessionId = record.sessionId,
        kind = InteractionCard.Kind.QUESTION,
        question = card,
    )
}

/**
 * 撤下一张已结算的卡片。
 *
 * 结算帧的关联键**没有统一形状**：`$events` 上的撤销帧只带 `eventId`（那才是权威
 * 路径，账本层会处理）；而落到会话日志里的 `approval/decided` 一类事件可能只带
 * `forSeq`、`requestSeq`、`eventId`，也可能什么都不带。所以按三个层次依次尝试：
 *
 * 1. 显式 id：`eventId` / `interactionId` / `forSeq` / `requestSeq` 折成 `session:seq`；
 * 2. `callId`：审批与提问卡上都记了它；
 * 3. 兜底：**只有一张同类卡时**才撤它——一轮内的审批是顺序发生的，按顺序配对
 *    宁可归到最近一张，也不要因为字段名变了就让卡片永远挂着。
 *
 * 一层都对不上、而同类卡又不止一张时**什么都不做**：撤错卡比留一张卡更糟。原来的
 * 兜底无条件 `indexOfLast`，两张同类卡并发挂起时（一轮里连着问两次，或上一条结算
 * 帧因为窗口截断丢了）会把**还在等的那张**撤掉，而真正已结算的那张继续留着——
 * 用户看到「批过的又冒出来、没批的消失了」。留着的卡还有账本层兜底，撤错了没有。
 */
private fun settleCard(turn: TurnBuilder, record: TimelineRecord) {
    if (turn.cards.isEmpty()) return
    val data = record.payloadOf().obj("data")
    val wantedKind = if (record.kind == RecordKind.APPROVAL_RES) {
        InteractionCard.Kind.APPROVAL
    } else {
        InteractionCard.Kind.QUESTION
    }
    val explicit = linkedSetOf<String>()
    record.payloadOf().str("eventId").let { if (it.isNotEmpty()) explicit.add(it) }
    data?.str("eventId")?.let { if (it.isNotEmpty()) explicit.add(it) }
    data?.str("interactionId")?.let { if (it.isNotEmpty()) explicit.add(it) }
    for (key in SEQ_KEYS) {
        val seq = data?.long(key) ?: 0L
        if (seq > 0) explicit.add("${record.sessionId}:$seq")
    }
    if (explicit.isNotEmpty()) {
        val hit = turn.cards.indexOfFirst { it.interactionId in explicit }
        if (hit >= 0) {
            turn.cards.removeAt(hit)
            return
        }
    }
    val callId = data?.let { firstNonEmpty(it.str("callId"), it.str("toolCallId")) } ?: ""
    if (callId.isNotEmpty()) {
        // 无论这一轮当下有没有那张卡，都记下「这个 callId 已经结算」：
        // 中继的 2000 条滚动窗口可能把 asked 滚掉、只留 decided，那时上面几条
        // 按 id 的匹配全部落空，得靠这张表在**后面**才到达的 asked 上挡一刀。
        turn.settledCalls.add(callId)
        val hit = turn.cards.indexOfFirst {
            it.approval?.callId == callId || it.question?.callId == callId
        }
        if (hit >= 0) {
            turn.cards.removeAt(hit)
            return
        }
    }
    val sameKind = turn.cards.withIndex().filter { it.value.kind == wantedKind }
    if (sameKind.size == 1) turn.cards.removeAt(sameKind[0].index)
}

/** 结算帧里可能承载「结算的是哪一条」的序号字段名。 */
private val SEQ_KEYS = arrayOf("forSeq", "requestSeq", "targetSeq", "seqOfRequest")

/** 与 TS 侧 `interaction_id = "${sessionId}:${seq}"` 完全一致（严禁时间戳）。 */
fun interactionIdOf(record: TimelineRecord): String = "${record.sessionId}:${record.seq}"

private fun firstNonEmpty(vararg values: String): String = values.firstOrNull { it.isNotEmpty() } ?: ""

/** 未用到的 JsonElement 转文本，保留给将来扩展 part 类型。 */
@Suppress("unused")
private fun JsonPrimitive.textOrEmpty(): String = contentOf(this)

/**
 * 一条存根在时间线上该显示什么。
 *
 * <p>「正在取回」与「取不回」共用一条文案不是偷懒：`fillStubs` 会一直重试，而用户
 * 真正需要知道的是「这里本来有内容、但太大，不在手机上」。给出体积让「怎么这么大」
 * 有答案，比一句「内容不可用」有用。
 */
private fun stubNotice(record: TimelineRecord): String {
    val size = record.blobSize
    if (size <= 0) return "这条内容很大，正文还没取回。"
    val kb = size / 1024
    val human = if (kb >= 1024) String.format("%.1f MB", kb / 1024.0) else "$kb KB"
    return "这条内容有 $human，正文正在取回。"
}
