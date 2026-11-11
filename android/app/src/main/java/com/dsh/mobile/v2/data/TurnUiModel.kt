package com.dsh.mobile.v2.data

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/**
 * 一个回合渲染出来的一切（v2 §二）。
 *
 * 这是**投影结果**，不是存储形态：存储只有 [TimelineRecord] 一条记录一层皮，
 * 而界面上的一轮对话是有形状的 —— 一句用户输入、一块过程面板、若干段正文、
 * 若干张待答卡片。把形状固化成一个不可变对象，Compose 侧才能：
 *
 * - 用 `turn_${turnId}` 当 LazyColumn 的稳定 key（不随流式增长而变）；
 * - 只在真正变化的那一层重组（[assistantBlocks] 与 [steps] 是 PersistentList，
 *   追加一段正文只新建一个节点，前面几十段的结构被共享）；
 * - 组件里按**槽位**摆位置，而不是靠 `seq - 0.25` 这类浮点偏移去猜。
 *
 * 旧的 `order = seq - 0.25` 方案有三个必须停掉的理由：面板与正文共用 seq 时
 * 键必须再分叉（`timelineKey` 就是为此写的）；合并不知不觉把顺序交给排序稳定性；
 * 而浮点数在"同一 seq 上挂多个面板"时根本无法表达顺序。
 *
 * **分段（2026-10-31 改造）**：一轮不再只有「一块面板 + 一串正文」，而是若干个
 * [TurnSegment]，每段是「这期间累积的思考/工具 + 它产出的那段正文」。
 *
 * 旧模型（`processPanel` + `assistantBlocks` 两个平行槽位）把顺序写死成
 * 「用户 → 折叠栏 → 全部正文」，于是一轮里「边说边干」的几段正文全挤在折叠栏
 * 下面 —— 而真宿主一轮能跑 24 个 step，每步都是「思考 + 正文 + 工具」，用户看到
 * 的是一条折叠栏加上一坨正文，跟桌面端「折叠栏 / 正文 / 折叠栏 / 正文」的交叉
 * 排布完全对不上（2026-10-31 用户按桌面端截图提需求：「每输出一段正文，就开启
 * 一个新的折叠栏」）。
 */
@androidx.compose.runtime.Immutable
data class TurnUiModel(
    /** 稳定身份，同时是 LazyColumn 的 key：`turn_$turnId`。 */
    val turnId: String,
    /** 这一轮的首个 seq，用于向前翻页与「回到某轮」。 */
    val anchorSeq: Long,
    /**
     * 这一轮里出现过的最大 seq。
     *
     * <p>会话级状态（[queue]/[todos]）取的是「最后被写入的那一轮」上的副本，判据就是
     * 这个字段 —— 不能用列表尾：轮次之间会交错，列表尾不是最新那一轮
     * （2026-10-31 实测：队列与任务已落库，界面却是空的）。
     */
    val lastSeq: Long = 0L,
    /** 用户那句话（本地回声也在这里，`seq == 0`）。 */
    val userMessage: Model.Message? = null,
    /**
     * 这一轮按时间顺序切出的段，每段自带自己的面板与正文。
     *
     * 段由「一段正文」闭合：正文到达时，此前累积的思考/工具归入同一个段的面板，
     * 这段正文则挂在它后面 —— 于是一段的渲染顺序是「折叠栏 → 它产出的正文」。
     * 最后一段通常没有正文（正在进行的那一步），它就是时间线尾部那块在跑的面板。
     */
    val segments: ImmutableList<TurnSegment> = persistentListOf(),
    /** 这一轮里还没结算的审批与提问。 */
    val interactions: ImmutableList<InteractionCard> = persistentListOf(),
    /**
     * 截到这一轮为止的队列状态。
     *
     * 队列是**会话级**的：它跨轮存在，不属于某一轮。放在这里是为了让投影
     * 结果自带「到这一轮为止队列长什么样」，界面取 [lastSeq] 最大的那一轮。
     */
    val queue: ImmutableList<Model.QueuedMessage> = persistentListOf(),
    /** 截到这一轮为止的任务清单，同上。 */
    val todos: ImmutableList<Model.Todo> = persistentListOf(),
    val turnState: TurnState = TurnState.COMPLETED,
) {
    /**
     * 最后一个带面板的段 —— 折叠行、抽屉、流式覆盖层都盯这一块。
     *
     * 保留这个名字（而不是让调用方自己 `segments.lastOrNull { it.panel != null }`）
     * 是因为「当前这块面板」这个概念在投影、覆盖层、测试里都反复出现。
     */
    val processPanel: ProcessPanelNode?
        get() = segments.lastOrNull { it.panel != null }?.panel

    /** 全部正文，按段序摊平（首行预览、搜索、导出这类「只要文本」的消费方用）。 */
    val assistantBlocks: ImmutableList<Model.Message>
        get() = segments.flatMap { it.blocks }.toImmutableList()

    /** LazyColumn 的稳定 key。 */
    fun key(): String = "turn_$turnId"

    /** 这一轮是否仍在跑（面板转圈、折叠行报数靠它）。 */
    val running: Boolean get() = turnState == TurnState.RUNNING

    /** 是否有待答卡片（时间线不自动跟到底，要让用户看见）。 */
    val waiting: Boolean get() = turnState == TurnState.WAITING_INTERACTION
}

enum class TurnState {
    /** 宿主还在跑这一轮。 */
    RUNNING,

    /** 卡在等用户回答（审批/提问）。整轮停在这里，不是 bug。 */
    WAITING_INTERACTION,

    /** 正常收尾。 */
    COMPLETED,

    /** 宿主报错或被打断收尾。 */
    INTERRUPTED,
}

/**
 * 一轮里的一段：**一块过程面板 + 它产出的那几段正文**。
 *
 * 分段单位的选取（2026-10-31 改造）：
 * - 一段正文到达，就把「到此刻为止累积的思考/工具」封进一个段，正文挂在它后面；
 * - 下一段思考/工具再开一个**新**段，于是界面上是「折叠栏 → 正文 → 折叠栏 → 正文」；
 * - 跨 turn 也一样：切段只看「有没有正文插进来」，不看 turn 边界。
 *
 * 为什么不是「一段正文一个段」：真宿主一步里可以有多段正文（同一条
 * `assistant/message` 的多个 text 块会被拼成一条），而再切细会把同一段思考拆到
 * 两个折叠栏里。
 */
@androidx.compose.runtime.Immutable
data class TurnSegment(
    /** 这一段的面板；只在「这一段还没有任何操作」时为 null。 */
    val panel: ProcessPanelNode? = null,
    /** 这一段产出的正文，按到达顺序。一般是 1 条。 */
    val blocks: ImmutableList<Model.Message> = persistentListOf(),
) {
    /** 这一段有没有东西可画（空段不该占位）。 */
    val visible: Boolean
        get() = blocks.isNotEmpty() || (panel != null && (panel.expandable || panel.isRunning))

    /**
     * 一屏里这一段面板的身份 —— 与 [ProcessPanelNode.panelId] 的区别只在流式路径。
     *
     * 流式过程中（思考增量到了、durable 记录还没落）面板没有 `panelSeq`，一轮里
     * 可能出现**多段这样的活面板**（思考 → 正文 → 又一段思考）。它们如果都退回
     * `turnId`，LazyColumn 的 key 就会撞（`pos-<order>` 重复 ⇒ 直接崩），抽屉也会
     * 「点哪块都打开最后一块」。所以这里用**段序号**兜底：durable 面板用自己的
     * `panelId`，活面板用 `<turnId>#live<段序>`。
     */
    fun identity(turnId: String, index: Int): String = "$turnId#seg$index"

    /**
     * 这一段面板在这一轮里的位置。
     *
     * 面板的锚点是这段里第一条思考/工具的 seq，排在它产出正文之前：
     * `panelSeq - 0.25`（与 v1 的 `panelOrder(seq) = seq - 0.25` 同义）。
     *
     * 没有锚点时（只有活思考、durable 记录一条都还没到）基准必须是
     * **本轮最后一条已见记录的 seq**（[lastSeq]），不能是本轮的 [anchorSeq]。
     *
     * <p>踩过的坑：`anchorSeq` 是「这一轮第一条记录的 seq」，而真宿主的第一条
     * 记录是 `turn/start`，它比 `user/message` **还早**（生产库实测 botw 会话
     * `t25`：`anchorSeq=10394`、用户消息 `seq=10397`）。拿它当基准时活面板算出
     * `10394 + 0.25*(0+1) = 10394.25`，排在用户消息 `10397` 之前 —— 真机现象
     * 就是「『动手！』气泡**上方**出现它自己那一轮的回答折叠栏」。段序累加
     * （9 段才 +2.25）也永远追不上那 3 个 seq 的差距，所以越跑越明显。
     *
     * 按段序累加 0.25 这个设计保留：都退回同一个值会让多段活面板的 `order`
     * 相等 ⇒ LazyColumn 键撞、排序不稳（`LinkedHashMap<Double, Message>` 按位置
     * 去重还会吞掉一块）。
     */
    fun panelOrder(anchorSeq: Long, lastSeq: Long, index: Int = 0): Double {
        val anchor = panel?.panelSeq ?: 0L
        if (anchor > 0) return anchor.toDouble() - 0.25
        // `lastSeq` 为 0 说明这一轮还没有任何真实记录（流式新建轮把 anchorSeq
        // 设成 Long.MAX_VALUE 兜底），此时沿用它，活面板照样排在时间线最末。
        val base = if (lastSeq > 0) lastSeq.toDouble() else if (anchorSeq > 0) anchorSeq.toDouble() else 0.0
        return base + 0.25 * (index + 1)
    }
}

/**
 * 过程面板 —— 界面上折成一行的那块。
 *
 * 拆块的粒度是**正文**，不是 step：一段正文之前的所有思考/工具并成一块，正文
 * 之后重新开一块（[TurnSegment]）。按 step 拆块是 bug 34/37 的根源 —— 一轮
 * 7~9 步渲染成 7~9 行「已处理 1 个操作」，正文被挤出屏幕；而整轮并成一块又会把
 * 「思考 → 说话 → 再思考 → 再说」压成一条折叠栏 + 一坨正文（2026-10-31 用户按
 * 桌面端提的形态）。步骤细节仍逐条保留在 [steps] 里，供底部抽屉展示。
 */
@androidx.compose.runtime.Immutable
data class ProcessPanelNode(
    val turnId: String,
    val isRunning: Boolean = false,
    /** 折叠行右侧的当前动作名（`执行 npm test` / `思考中` / 技能名）。 */
    val currentActionLabel: String = "",
    /**
     * 折叠行尾部的思考预览，**已经取尾部**。
     *
     * 生成时就 `.replace('\n', ' ').takeLast(60)`：UI 里 `maxLines = 1` +
     * `overflow = Clip` 从末尾省略，若传全文则可见的永远是开头，每个新字只把
     * 窗口整体挪一位，看起来"刷一下就再没变过"（bug 26）。取尾部 + 足够短的
     * 额度，尾巴才真的可见。
     */
    val tailThinkingPreview: String = "",
    /**
     * 流式思考的**全文**（不压平、不截断）。
     *
     * [tailThinkingPreview] 是折叠行专用的 60 字尾窗（bug 26），但二级抽屉的
     * 「深度思考」详情页渲染的是 `effectiveSteps` 里那条 THINKING 步骤的
     * `text`——早期它直接吃 60 字窗口，于是用户看到「抽屉里只有几行、后续的
     * 字把前面的字挤掉」（2026-10-31 真机反馈）：思考每长过 60 字，开头就被
     * 挤出去，抽屉里永远只有最后 60 字。窗口是给单行折叠行的，不该漏进详情页。
     *
     * 折叠行不受影响：`thinkingPreview(streaming=true, limit=INLINE_THINKING_LIMIT)`
     * 在它之上还有一层 12 字尾窗（ChatViewModel.kt），全文进 steps 后折叠行
     * 依旧只露尾巴。
     */
    val liveThinkingFull: String = "",
    /** 工具调用条数：折叠行、抽屉标题、面板页脚三处同源（bug 36）。 */
    val totalOpsCount: Int = 0,
    /** 收尾后的用时（秒）；运行中为 0。 */
    val durationSec: Long = 0,
    val steps: ImmutableList<Model.Step> = persistentListOf(),
    /** 工具动作明细，`steps` 里的 TOOL 步骤用 `actionIndex` 指向它。 */
    val actions: ImmutableList<Model.ToolAction> = persistentListOf(),
    /** 非空表示这一轮以失败/中断收尾。 */
    val failure: String = "",
    /**
     * 整轮是否已经收尾。
     *
     * 折叠行的「用时 X 秒」只挂给收尾那一块（bug 37 第 ③ 条），而"这一轮是否还在跑"
     * 必须看回合级状态 —— 宿主每步都发 `step/end`，`isRunning` 走完一步就 false，
     * 但整轮可能还在跑。两者分开正是不再中途误报「已处理 N 个操作」的原因。
     */
    val turnClosed: Boolean = true,
    /**
     * 本块面板在这一段里的锚点 seq（这段里第一条思考/工具记录的 seq）。
     *
     * 一轮多块面板之后，面板身份不能共用一个 turnId：抽屉会「点哪块都打开最后
     * 一块」，合成的活思考步骤 id 也会撞。见 [panelId]。
     */
    val panelSeq: Long = 0L,
) {
    /**
     * 这一块面板的稳定身份。
     *
     * 一轮只有一块面板时它就是 turnId（历史行为与缓存里的旧 panelId 兼容）；
     * 多块时加 `#<panelSeq>` 后缀逐块唯一。
     */
    val panelId: String get() = if (panelSeq > 0) "$turnId#$panelSeq" else turnId
    /** 有可展开的内容吗（决定折叠行能不能点开抽屉）。 */
    val expandable: Boolean get() = steps.isNotEmpty() || actions.isNotEmpty() || failure.isNotEmpty()

    /**
     * 交给 UI 的步骤：把流式思考（[tailThinkingPreview]）并进 `steps`。
     *
     * <p>为什么要并：折叠行的尾部预览 `foldedThinkingText` 只认 `steps` 里的
     * THINKING 项，单存一个 `tailThinkingPreview` 字段没人读得到（2026-10-30
     * 反馈「折叠栏右侧的思考流式预览怎么没有了」）。合并规则与 durable 思考一致：
     * 末条已经是 THINKING 就地补全（更长才覆盖），否则另起一块 —— 叠成两条会让
     * 预览读到较短的那条，看起来「思考卡住了」。
     */
    val effectiveSteps: ImmutableList<Model.Step>
        get() {
            if (tailThinkingPreview.isBlank() && liveThinkingFull.isBlank()) return steps
            // 抽屉/详情页吃全文；60 字尾窗只是折叠行的显示额度。全文为空时
            // （旧缓存、只有 durable 思考的场景）退回尾窗，两不误。
            val display = liveThinkingFull.ifBlank { tailThinkingPreview }
            val last = steps.lastOrNull()
            if (last != null && last.kind == Model.Step.THINKING) {
                if (display.length <= last.text.length) return steps
                val list = steps.toMutableList()
                list[list.lastIndex] = last.copy(text = display)
                return list.toImmutableList()
            }
            // 合成出来的那条思考步骤**必须**用确定性 id，不能走 `Step.nextId()`：
            // `effectiveSteps` 是个 getter，每被访问一次就重新拼一次列表，而
            // `nextId()` 每调一次给一个新号。二级抽屉记的是 `step.id`
            // （见 ChatScreen 的 ProcessTimelineSheet），流式思考每长一个字、
            // 界面每重组一次，那个 id 就换一个，抽屉里的 `firstOrNull { it.id == detailStepId }`
            // 立刻落空 —— 表现为「点开二级详情，内容一加载就自己关掉」
            // （2026-10-30 真机反馈）。用 panelId 派生一个稳定号，同一块面板里
            // 这条合成步骤的身份始终不变，而 durable 步骤本来就是稳定 id。
            //
            // 一轮多块面板之后必须带上 panelSeq：共用 `live-think:<turnId>` 会让
            // 每块面板里那条合成步骤同号，抽屉按 id 找永远命中第一块。
            val list = steps.toMutableList()
            list.add(
                Model.Step(
                    kind = Model.Step.THINKING,
                    text = display,
                    id = "live-think:$panelId",
                )
            )
            return list.toImmutableList()
        }
}

/**
 * 时间线上的一张待答卡片。
 *
 * [interactionId] 必须是 `"<sessionId>:<seq>"`：宿主对未结算的 waterfall 会在每次
 * `$events` 新连接时重投，用时间戳做 id 会让同一张卡每重连一次多堆一份（bug 22/23）。
 */
@androidx.compose.runtime.Immutable
data class InteractionCard(
    val interactionId: String,
    val sessionId: String,
    val kind: Kind,
    val approval: Model.Approval? = null,
    val question: Model.QuestionCard? = null,
) {
    enum class Kind { APPROVAL, QUESTION }
}

/** 把一轮的槽位摊平成渲染行 —— UI 只需按顺序画，不需要自己判断谁在谁前面。 */
fun TurnUiModel.toMessages(): List<Model.Message> = buildList {
    userMessage?.let { add(it) }
    // 段内顺序是「面板 → 正文」：那块折叠栏是这段正文的**产出过程**，
    // 排在正文前面读起来才是「先做了什么、再说什么」。
    for ((index, segment) in segments.withIndex()) {
        if (!segment.visible) continue
        segment.panel?.let { panel ->
            add(
                panel.toMessage(
                    panelSeq = panel.panelSeq,
                    panelId = segment.identity(turnId, index),
                    order = segment.panelOrder(anchorSeq, lastSeq, index),
                )
            )
        }
        addAll(segment.blocks)
    }
}

/**
 * 面板 → 渲染用的消息对象。
 *
 * 既有 `MessageRow` / `ProcessTimelineSheet` 吃的就是这个形状：折叠行要的
 * 「已处理 N 个操作」「用时 X 秒」「活思考尾部」全部由 `foldSummary` /
 * `currentActivity` / `foldedThinkingText` 从 `steps` 与 `actions` 现算，
 * 所以这里**不**把摘要字段塞进 `Model.Message` —— 那会为了显示一行字去改缓存
 * 格式（加字段就得给默认值，否则旧缓存反序列化直接崩，见 bug 15）。
 *
 * 唯一的例外是 `panelId`：抽屉靠它认「我现在展开的是哪一块」。一轮多块面板之后
 * 必须逐段唯一，由 [ProcessPanelNode.panelId] 给（`<turnId>#<panelSeq>`）。
 *
 * <p>**`order` 必须由这段内容的锚点算出来，不能写死 0**（2026-10-30 实测事故）：
 * `position(m) = if (order > 0) order else seq`，面板写死 `order=0.0` + `seq=0`
 * ⇒ 位置恒为 `0.0` ⇒ 所有 v2 面板被排到时间线**最前面**。落定的面板因为
 * `running=false` 走 `timelineOf` 的 local 段、被 `addAll` 垫到尾部，看着还算
 * 正常；**正在跑的那块 `running=true` 走 remote 段，位置 0.0 直接被排到聊天页
 * 顶端之外**，用户滚在底部永远看不到它 —— 表现就是「折叠行右侧的思考预览没了」、
 * 「流式期间什么都不显示，跑完才一次性冒出来」。
 */
fun ProcessPanelNode.toMessage(
    panelSeq: Long = this.panelSeq,
    panelId: String = this.panelId,
    order: Double = if (panelSeq > 0) panelSeq.toDouble() - 0.25 else 0.0,
): Model.Message = Model.Message(
    role = Model.Role.ASSISTANT,
    text = "",
    // seq 也必须是真实值：`timelineOf` 用 `m.seq > 0` 判「这是远端历史」，
    // 写死 0 会被当成「本地回声」，整块面板被 `addAll(local)` 垫到时间线最尾，
    // 历史面板因此全部堆在聊天页底部、把正文挤走（2026-10-30 实测：整屏 9 行
    // 「已处理 3 个操作」，一条正文都没有）。
    seq = if (panelSeq in 1 until Long.MAX_VALUE) panelSeq else 0,
    panelId = panelId,
    order = order,
    steps = effectiveSteps.toMutableList(),
    actions = actions.toMutableList(),
    running = isRunning,
    status = currentActionLabel,
    failure = failure,
    footnote = turnClosed,
)

/** 按顺序把所有回合摊平成渲染行。 */
fun List<TurnUiModel>.rows(): List<Model.Message> = flatMap { it.toMessages() }

/** 便于测试与投影器内部构造。 */
fun List<TimelineRecord>.toImmutable(): ImmutableList<TimelineRecord> = toImmutableList()
