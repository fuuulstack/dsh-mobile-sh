package com.dsh.mobile.v2.data

import kotlinx.serialization.Serializable
import java.util.Calendar
import java.util.Locale

/**
 * 应用内领域模型（Kotlin 版）。
 *
 * UI 层只依赖这里的类型，不直接接触 JSON。字段按历史版本的信息设计，
 * 由网关层在缺失时置空，UI 渲染占位而不是崩溃。
 *
 * 与历史 Java 版的差异：数据类直接标注 [Serializable]，缓存编解码走 kotlinx-serialization，
 * 不再手写 org.json 的 encode/decode 双份映射。
 */
object Model {

    /** 会话列表排序方式（sort menu）。 */
    enum class SortMode { BY_DATE, BY_WORKSPACE, RECENT, RUNNING }

    enum class RelayState { DISCONNECTED, CONNECTING, CONNECTED, DEGRADED }

    @Serializable
    data class RelayStatus(
        var state: RelayState = RelayState.DISCONNECTED,
        var endpoint: String = "",
        var latencyMs: Int = -1,
        var cloudSynced: Boolean = false,
        var upstreamBytes: Long = 0,
        var downstreamBytes: Long = 0,
        /** 实时速率（字节/秒）：最近几秒的滑动窗口平均；设置页 hero 显示它而不是累计值。 */
        var upstreamBps: Long = 0,
        var downstreamBps: Long = 0,
        var syncedSessions: Int = 0,
        var hostAlias: String = "",
        /** 当前主机的平台（"macos"/"windows"/"linux"）：设置页 hero 卡按它选头像。 */
        var hostPlatform: String = "",
        var autoReconnect: Boolean = true,
        /** 通道已经不工作的原因（例如令牌被拒）。空 = 没有需要用户处理的问题；优先于所有绿灯文案。 */
        var note: String = "",
    ) {
        val isConnected: Boolean
            get() = note.isEmpty() && (state == RelayState.CONNECTED || state == RelayState.DEGRADED)

        /** 顶栏胶囊文案；note 非空时它就是唯一该说的话。 */
        fun pillText(): String {
            if (note.isNotEmpty()) return note
            if (state == RelayState.CONNECTING) return "正在连接中继…"
            if (state == RelayState.DEGRADED) return "中继连接不稳定"
            if (state != RelayState.CONNECTED) return "中继未连接"
            val sb = StringBuilder("中继已连接")
            if (latencyMs >= 0) sb.append(" · ").append(latencyMs).append("ms")
            return sb.toString()
        }
    }

    // ============================================================
    // 会话
    // ============================================================

    @Serializable
    data class Session(
        var id: String = "",
        var title: String = "新对话",
        var workspace: String = "",
        var subtitle: String = "",
        var running: Boolean = false,
        var updatedAt: Long = 0,
        var preview: String = "",
        var messageCount: Int = 0,
        /** 中继摘要给出的最后一条消息 seq；本机删除会话时用它记录「删除到哪」。 */
        var lastSeq: Long = 0,
        var hasPendingApproval: Boolean = false,
        /** 宿主标记的空会话（只有 workspace/context、没有对话）；summary 里直接给。 */
        var blank: Boolean = false,
        var modelProvider: String = "",
        var modelName: String = "",
        var reasoningEffort: String = "",
        var permissionPreset: String = "",
        /** 本地读过这条会话的时间（epoch millis，0 = 从未读过）。未读是纯客户端概念。 */
        var readAt: Long = 0,
        /**
         * 宿主的任务清单（`todo_write` 工具的产物）。
         *
         * 它在宿主那里就是一个**会话投影**（SessionProjectionMap.todos），所以跟着
         * [Model.Session] 走：进会话时由 `session/projections` 拉回，之后由流里的
         * `todo/write` 事件推增量。空列表 = 这条会话没写过任务，界面不显示胶囊。
         */
        var todos: List<Todo> = emptyList(),
        /**
         * 宿主给的子智能体目录（`session/projections` 的 `values.subagentCatalog`）。
         *
         * <p>与 [todos] 不同，它**不是**逐条推的：宿主只在投影里给整表，所以进会话时
         * 随 `session/projections` 拿一次；实时变化（新起一个子智能体）要等下一次
         * 投影拉取。空列表 = 这条会话没有子智能体，界面不显示那颗胶囊。
         */
        var subagents: List<Subagent> = emptyList(),
        /**
         * 宿主 inbox 里还没被领走的排队消息（`session/projections` 的 `values.inbox`）。
         *
         * 桌面端的「排队消息卡片」就是这块数据：宿主回合运行中发出的消息按
         * `mode:"queue"` 落进 `next-turn`，下一个回合开始时被领走一条。
         * 空列表 = 队列空，输入区不显示排队卡片。
         */
        var queue: List<QueuedMessage> = emptyList(),
    ) {
        /** 是否有宿主那边的新活动还没被看过；正在跑的会话不算未读（已经在转圈了）。 */
        val unread: Boolean
            get() = !running && updatedAt > 0 && updatedAt > readAt

        /** composer 上的模型胶囊文案；宿主没给投影时返回中性占位而不是编造型号。 */
        fun modelLabel(): String {
            if (modelName.isEmpty()) return "默认模型"
            val effort = if (reasoningEffort.isEmpty()) "" else " · ${reasoningEffort.replaceFirstChar { it.uppercase() }}"
            return modelName + effort
        }

        fun permissionLabel(): String = permissionLabelFor(permissionPreset)

        /** 任务胶囊的数据；宿主没给清单时 total=0，胶囊不渲染。 */
        val taskProgress: TaskProgress
            get() = taskProgressOf(todos)

        /** composer 上的权限胶囊文案；宿主没给投影时退回中性「权限」。 */
        fun toolLabel(): String = if (modelProvider.isEmpty()) "工具" else modelProvider

        companion object {
            /** 预设 id → 中文文案，用词跟桌面端一致。 */
            fun permissionLabelFor(preset: String): String = when (preset) {
                "read-only" -> "仅可查看"
                "workspace-write" -> "工作区内修改"
                "danger-full-access" -> "完全权限"
                "auto" -> "自动审查"
                "default" -> "默认权限"
                "custom" -> "自定义权限"
                else -> "权限"
            }
        }
    }

    /**
     * 任务清单里的一条（宿主 `todo_write` 工具的条目）。
     *
     * 形状与宿主协议一一对应：`{ content, status }`，status 三态。
     * **不加 `@Serializable` 之外的花样**：宿主整表覆盖下发，端侧只读。
     */
    @Serializable
    data class Todo(
        var content: String = "",
        var status: String = "pending",
    ) {
        val completed: Boolean
            get() = status == "completed"

        val inProgress: Boolean
            get() = status == "in_progress"
    }

    /**
     * 任务胶囊的统计：`TaskProgress(done, total)`。
     *
     * 全完成由 ChatScreen 的 TodoPill 自己换配色，所以这里只备数，
     * 不在这里决定配色。
     */
    data class TaskProgress(val done: Int, val total: Int) {
        val allDone: Boolean get() = total > 0 && done == total

        /** 胶囊文案：`任务: 3/7`。宿主没给清单时返回空串，界面据此不渲染。 */
        fun label(): String = if (total <= 0) "" else "任务: $done/$total"
    }

    /**
     * 宿主的一个子智能体（`session/projections` 的 `values.subagentCatalog` 一条）。
     *
     * <p>宿主侧的形状是
     * `{ id, createdAt } & ({mode:'one-shot'|'external'|'continuable'|'unknown', label?})`，
     * 所以 [label] 只有 `continuable` 一定非空 —— 其余模式的 `label` 是可选的，
     * 界面不能假设它有值（拿空串当「没有标签」画，别画成 undefined）。
     */
    @Serializable
    data class Subagent(
        var id: String = "",
        var label: String = "",
        /** `one-shot` / `external` / `continuable` / `unknown`；未知值原样保留。 */
        var mode: String = "",
        var createdAt: Long = 0,
        /**
         * 它此刻还在不在跑 —— **三态**，`null` 是「不知道」，不是「跑完了」。
         *
         * 目录条目本身不带状态（宿主只发 `{id, createdAt, mode, label?}`），所以
         * 插件会把子代理**自己那条会话**的 `running` 盖上来。老插件不发这个字段，
         * 读成 `false` 等于凭空宣布它跑完了、胶囊会莫名其妙消失；因此只有明确
         * `false` 才算结束，`null` 一律按「还在」（跟没这个功能之前一样）。
         */
        var running: Boolean? = null,
    )

    /**
     * 宿主的一个后台任务（`job/registered` 尚未被 `job/settled` 结算的那条）。
     *
     * <p>**不落盘**：它是「此刻在跑」的瞬时视图，由手机从记录流里现算
     * （见 `Projector.jobsOf`）。落盘的那份一旦过夜就是错的。
     *
     * <p>字段照抄宿主 `JobRegisteredData`：`jobId` / `owner` / `kind` / `label` /
     * `startedAt`；`owner` 不进模型（胶囊只显示当前会话看得到的任务）。
     */
    data class Job(
        var id: String = "",
        var label: String = "",
        /** 宿主自己分类用的字符串（如 `shell`），只做展示兜底，不做判据。 */
        var kind: String = "",
        var startedAt: Long = 0,
    ) {
        /** `已运行 1 分 20 秒`；宿主没给开始时间时返回空串。 */
        fun elapsedLabel(): String {
            if (startedAt <= 0) return ""
            val totalSeconds = maxOf(0L, (System.currentTimeMillis() - startedAt) / 1000)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            if (minutes <= 0L) return "已运行 $seconds 秒"
            return "已运行 $minutes 分 $seconds 秒"
        }
    }

    /**
     * 宿主 inbox 里的一条排队消息（桌面端「排队消息卡片」的对应物）。
     *
     * 来源是 `session/projections` 的 `values.inbox`：
     * - `next-turn`：等这一轮跑完再发出去（桌面端点「发送」且回合在跑时落这里）
     * - `next-step`：插到下一个 step 边界（桌面端「插话」落这里）
     *
     * [id] 是宿主 message 的 id（随机 uuid，**不是** requestId），
     * 编辑/删除/插话都靠它定位，见 `session/updateQueue` 的 `itemId`。
     */
    @Serializable
    data class QueuedMessage(
        var id: String = "",
        var text: String = "",
        /** `next-turn` 或 `next-step`。只有 `next-turn` 能被「插话」提前发出去。 */
        var target: String = "next-turn",
        /** 这条消息带几张图。图片内容内联在宿主 inbox 里，端侧只用来出文案。 */
        var imageCount: Int = 0,
    ) {
        val steerable: Boolean get() = target == "next-turn"

        /** 卡片上的一行文本：图片消息没有正文时退化成「[图片]」。 */
        fun label(): String = text.ifBlank { if (imageCount <= 0) "" else "[图片]" }
    }

    /** 会话列表按工作区分组。 */
    data class Workspace(val name: String) {
        val sessions: MutableList<Session> = mutableListOf()
    }

    // ============================================================
    // 模型目录
    // ============================================================

    /**
     * 一个可选思考等级。
     *
     * `id` 是发给宿主的协议值（off/low/high/max），`name` 是给人看的（Off/Low/...）。
     * 两者都由宿主下发——手机端不自己造等级，也不假设等级集合固定。
     */
    @Serializable
    data class ReasoningEffort(
        var id: String = "",
        var name: String = "",
        var description: String = "",
    )

    @Serializable
    data class ModelChoice(
        var provider: String = "",
        var providerName: String = "",
        var model: String = "",
        var name: String = "",
        var reasoningEffort: String = "",
        /** 该模型支持的思考等级；空表示宿主没声明，界面上就不给换。 */
        var efforts: List<ReasoningEffort> = emptyList(),
    ) {
        /** 宿主给的等级 id → 展示名；找不到就原样返回，别把 high 显示成空白。 */
        fun effortLabel(id: String): String {
            if (id.isEmpty()) return ""
            return efforts.firstOrNull { it.id == id }?.name?.ifEmpty { id } ?: id
        }

        /** 只有「供应商 → 模型」之间的分隔用“/”，模型名与推理等级保持原来的“·”。 */
        fun label(): String {
            val effort = effortLabel(reasoningEffort).let { if (it.isEmpty()) "" else " · $it" }
            return (if (providerName.isEmpty()) provider else providerName) + " / " + name + effort
        }
    }

    @Serializable
    data class ModelCatalog(
        var defaultChoice: ModelChoice? = null,
        val choices: MutableList<ModelChoice> = mutableListOf(),
        val failures: MutableList<String> = mutableListOf(),
    )

    // ============================================================
    // 聊天消息
    // ============================================================

    enum class Role { USER, ASSISTANT, SYSTEM }

    @Serializable
    data class ToolAction(
        var name: String = "",
        /** 宿主给这次工具调用的唯一 id（tool/call 与 tool/result 靠它一对一配对）。 */
        var callId: String = "",
        var detail: String = "",
        /**
         * 模型给这次调用的**小标题**（工具参数里的 `description`）。
         *
         * 桌面端折叠行是 `工具调用 · ops_bash · 改成十秒桶`——最后那一段既不是工具名
         * 也不是命令，而是模型自己写的一句话说明（`toolRowModel` 里
         * `summary = [工具名, base].join(" · ")`，`base` 优先取 `description`）。
         * 手机端原先只认 `command`，于是同一批调用在桌面上有「改成十秒桶」、在手机上
         * 只剩一条裸命令，看不出这一步到底想干什么（2026-11-01 用户要求同步）。
         *
         * 没有 `description` 的工具留空串——折叠行此时只显示操作名，不编一个假标题。
         * 有默认值，旧缓存反序列化不会崩（`SessionCache` 走 kotlinx 深拷贝）。
         */
        var subtitle: String = "",
        var durationMs: Long = 0,
        var status: Status = Status.DONE,
        /** tool/result 的输出摘要；展开这一行时显示。 */
        var output: String = "",
        /** 仅由 tool-call-delta 增量拼出来的临时条目；durable tool/call 到达时原地补全。 */
        var provisional: Boolean = false,
        var startedAt: Long = 0,
        /**
         * 这是一次**技能加载**（宿主的 `skill` 工具）。
         *
         * 技能跟普通工具在界面上要分开：它有自己的图标与措辞（「加载技能 xxx」），
         * 折进「已处理 N 个操作」里用户就分不清刚才是在跑命令还是在换技能
         * （2026-10-28 反馈：加载/使用技能要带技能图标 + 技能名）。
         */
        var skill: Boolean = false,
        /**
         * 这是一次**命令执行**（bash/shell/terminal…）。
         *
         * 折叠行的「当前操作」要把正在跑的那条命令原样带出来（「执行 npm test」），
         * 只写「正在调用工具」看不出它在干什么。
         */
        var command: Boolean = false,
    ) {
        enum class Status { DONE, RUNNING, FAILED }

        fun durationLabel(): String {
            if (durationMs <= 0) return ""
            if (durationMs < 1000) return "${durationMs}ms"
            return String.format(Locale.getDefault(), "%.1fs", durationMs / 1000.0)
        }
    }

    /** 处理详情时间线上的一项。 */
    @Serializable
    data class Step(
        var kind: Int = THINKING,
        /** THINKING 的思考正文 / TEXT 的旁白文本。 */
        var text: String = "",
        /** TOOL 步骤对应的工具调用（缓存编解码时用 actionIndex 存下标，载入后修复）。 */
        var actionIndex: Int = -1,
        /**
         * 步骤的稳定身份。
         *
         * 二级抽屉（单步详情）原来记的是**下标**，而 `mergeTurnPanels` 会把同回合
         * 相邻面板拼成一块、`steps` 长度与顺序随之变化：下标于是指向另一条（「自动
         * 切换到另一个过程的详情」），或越界（抽屉凭空消失）。改成按下标创建时发号、
         * 合并时原样保留（`step.copy()` 带过去），抽屉只认这个号。
         *
         * 旧缓存没有这个字段 ⇒ 反序列化得到空串，此时调用方退回按下标定位。
         * 序号只在进程内唯一，不参与任何跨端协议。
         */
        var id: String = "",
    ) {
        companion object {
            const val THINKING = 0
            const val TOOL = 1
            const val TEXT = 2

            /** 进程内自增序号：生成 [Step.id] 用。 */
            private val seq = java.util.concurrent.atomic.AtomicLong()

            fun nextId(): String = "s" + seq.incrementAndGet()

            fun thinking(text: String?) = Step(kind = THINKING, text = text ?: "", id = nextId())
            fun text(text: String?) = Step(kind = TEXT, text = text ?: "", id = nextId())
        }
    }

    @Serializable
    data class Message(
        var role: Role = Role.ASSISTANT,
        var text: String = "",
        var time: Long = 0,
        /** 宿主会话日志里的 seq，消息的唯一身份：快照判重、x-dsh-since 边界都靠它。0 = 本地回声/占位。 */
        var seq: Long = 0,
        /**
         * 处理详情面板的本地身份（非面板为空）。
         *
         * 面板在宿主日志里没有 seq：它挂在某个事件之前，靠 `seq - 0.25` 占位，
         * 随后又会被「收编」成该回合首个事件的 seq——同一个面板的 seq 是会变的，
         * 拿它当身份去 UI 里找实例必然找错，收尾事件就落到了另一个对象上，
         * 界面留下一条永不熄灭的「正在分析请求」（2026-10-27 真机复现）。
         */
        var panelId: String = "",
        /**
         * 时间线排序键（0 = 按 seq 排）。
         *
         * 面板在宿主日志里没有自己的 seq，却要排在「用户气泡之后、回答正文之前」，
         * 整数序列装不下这个位置——只能带小数。累加器此前只把带小数的位置写进
         * bySeq 的键里，而界面是按 seq 字段重排的，键里的信息到 UI 就丢了，
         * 面板会落回它自己的整数 seq（= turn/start），排到用户气泡上方
         * （2026-10-27 真机截图确认）。所以把位置显式带在消息上。
         */
        var order: Double = 0.0,
        /** 处理详情面板所属回合；-1 表示未知。 */
        var turn: Long = -1,
        /**
         * 面板所属的 step（宿主 `data.step`）；-1 表示还不属于任何一步。
         *
         * 宿主每一步（step）自带 reasoning + tool-call + text，正确形态是
         * 「思考 → 工具 → 思考 → 正文」交叉出现。整轮合成一个面板就只剩
         * 「全部步骤在上、正文在下」（2026-10-27 真机反馈）。所以面板按
         * (turn, step) 一分为多，各自带自己的步骤，位置落在这一步产出的事件之前。
         */
        var step: Long = -1,
        val actions: MutableList<ToolAction> = mutableListOf(),
        val steps: MutableList<Step> = mutableListOf(),
        /** 消息里的图片引用：durable 日志只存 attachmentId，点开时用 session/attachment 取字节。 */
        val images: MutableList<ImageRef> = mutableListOf(),
        var streaming: Boolean = false,
        /** 已发出但宿主还没 ack：气泡左侧显示转圈。 */
        var sending: Boolean = false,
        /** 发送失败：气泡左侧显示红色重试图标，点它原样重发。 */
        var failed: Boolean = false,
        /** 「模型处理详情」里的思考全文。 */
        var reasoning: String = "",
        /** 处理详情的实时状态（正在分析请求 / 正在调用工具 …）。 */
        var status: String = "",
        /** 该回合是否仍在进行；决定详情面板默认展开。 */
        var running: Boolean = false,
        var startedAt: Long = 0,
        var endedAt: Long = 0,
        /** 非空表示这一回合以失败/中断收尾。 */
        var failure: String = "",
        /**
         * 是否显示「已完成分析 · 已处理 N 个操作 · 用时X」页脚。
         *
         * 一轮会拆成多个 step 面板，页脚只挂在该轮最后一块上；每块都挂会让
         * 一轮看起来像好几轮（2026-10-28 改造时定）。
         */
        var footnote: Boolean = false,
    ) {
        companion object {
            fun user(text: String) = Message(role = Role.USER, text = text, time = System.currentTimeMillis())

            fun assistant(text: String) = Message(role = Role.ASSISTANT, text = text, time = System.currentTimeMillis())

            /** 宿主的 durable assistant/message 还没到时，先承载思考与工具步骤的占位回合。 */
            fun pending() = Message(
                role = Role.ASSISTANT, running = true,
                startedAt = System.currentTimeMillis(), status = "正在分析请求",
            )
        }

        /**
         * 详情面板的折叠标题：**只在回合结束后**给「已完成分析 · 已处理 N 个操作 · 用时 X」。
         *
         * 运行中返回空串——用户明确要求删掉「正在分析请求」那一行（2026-10-29）：
         * 折叠行自己会用旋转图标 + 当前操作名说明「在跑」，再挂一句状态词是多余的。
         */
        fun processTitle(): String {
            if (running) return ""
            if (failure.isNotEmpty()) return failure
            if (reasoning.isEmpty() && actions.isEmpty()) return ""
            val count = toolCount()
            val sb = StringBuilder("已完成分析")
            if (count > 0) sb.append(" · 已处理 ").append(count).append(" 个操作")
            val took = tookLabel()
            if (took.isNotEmpty()) sb.append(" · ").append(took)
            return sb.toString()
        }

        /** “用时 4 秒” / “用时 1 分 12 秒”；拿不到起止时间就返回空串。 */
        fun tookLabel(): String {
            if (startedAt <= 0) return ""
            val end = if (endedAt > 0) endedAt else System.currentTimeMillis()
            val seconds = maxOf(1, Math.round((end - startedAt) / 1000.0))
            if (seconds < 60) return "用时 $seconds 秒"
            return "用时 ${seconds / 60} 分 ${seconds % 60} 秒"
        }

        /** 是否有可展开的处理详情。 */
        val hasProcess: Boolean
            get() = running || failure.isNotEmpty() || reasoning.isNotEmpty() || actions.isNotEmpty()

        /**
         * 交给 UI 的不可变快照。
         *
         * 累加器是**原地改**消息对象的（turn/end 直接把 running 置 false）。若把对象本身
         * 交给 Compose，界面持有的那一份和被改的是同一个实例：data class 的 equals 看到
         * 两边字段全等（本来就是同一个对象），LazyColumn 判定「item 没变」直接跳过重组，
         * 屏幕上就留下一条永不熄灭的「正在分析请求」（2026-10-27 真机复现）。
         *
         * 所以每次发布都复制一份：之后的原地修改只动累加器那份，界面这份保持不变，
         * 下次发布时新旧两份字段不同，重组才会真的发生。列表也要深拷贝——浅拷贝会让
         * 两边共用一个 MutableList，往里加步骤同样绕过去重。
         */
        fun snapshot(): Message = copy(
            actions = actions.map { it.copy() }.toMutableList(),
            steps = steps.map { it.copy() }.toMutableList(),
            images = images.map { it.copy() }.toMutableList(),
        )

        /**
         * 抽屉标题「已处理 N 个操作」的数字——**全 App 唯一口径**。
         *
         * 只数点得开的那几条：`actionIndex` 越界的工具步骤（合并面板时被置 -1、或旧
         * 缓存里对不上号）在抽屉里渲染不出来，计进去就成了「写着 7 个操作、点开只有
         * 5 行」。抽屉的渲染条件就是这一句，两边同源，数字不可能再对不上
         * （2026-10-29 用户反复反馈「报的数和点开的条数对不上」）。
         */
        fun toolCount(): Int =
            steps.count { it.kind == Step.TOOL && it.actionIndex in actions.indices }
    }

    // ============================================================
    // 待答交互（审批 / 提问卡片）
    // ============================================================

    /** 一张待答卡片。eventId 是回传 $events/result 的关联键。 */
    @Serializable
    open class Interaction {
        var eventId: String = ""
        var agentId: String = ""
        /** 已提交回答或已被宿主撤销；UI 据此撤下卡片。 */
        var answered: Boolean = false
        /** 回答已回传、但宿主还没推 cancel 帧：不能凭 RPC 成功就撤卡，由 cancel 帧撤下。 */
        var submitted: Boolean = false

        /**
         * 这张卡能不能真的回答。
         *
         * 只有从 `$events` 的 waterfall 帧来的卡才有宿主认的 `eventId`；从归档
         * （`approval/asked` 持久事件）重建出来的卡 id 是 `sessionId:seq`，宿主那边
         * 早没有这条挂起的 waterfall 了。默认 false —— 宁可按钮灰着，也不要让用户
         * 以为批过了、那一轮却还卡在电脑上。
         */
        var answerable: Boolean = false
    }

    /** 工具审批卡片：允许一次 / 拒绝。 */
    class Approval : Interaction() {
        var toolName: String = ""
        var callId: String = ""
        /** 人类可读的审批原因（asker 给的 reason）。 */
        var reason: String = ""
        /** 展示文案；可能带本地化结构，取不到时退回 reason。 */
        var displayReason: String = ""

        /** 卡片标题；与桌面端同一口径：优先 reason，否则「工具 X 请求越权执行」。 */
        fun headline(): String {
            if (reason.isNotBlank()) return reason.trim()
            val tool = toolName.ifEmpty { "工具" }
            return "$tool 请求越权执行"
        }
    }

    /** 提问卡片：一批问题一次提交。 */
    class QuestionCard : Interaction() {
        var callId: String = ""
        val questions: MutableList<Question> = mutableListOf()

        /** 是否所有问题都有回答（选中了选项或填了自定义文本）。 */
        val complete: Boolean
            get() = questions.isNotEmpty() && questions.all { it.answered }
    }

    /** 提问卡片里的一道题；UI 侧草稿（selected/custom）就记在实例上。 */
    class Question {
        var id: String = ""
        var question: String = ""
        var detail: String = ""
        var header: String = ""
        var multiSelect: Boolean = false
        val options: MutableList<QuestionOption> = mutableListOf()
        /** 草稿：选中的 option label。单选时最多一个。 */
        val selected: MutableList<String> = mutableListOf()
        /** 草稿：自定义「其他」回答。 */
        var custom: String = ""

        val answered: Boolean
            get() = selected.isNotEmpty() || custom.isNotBlank()
    }

    class QuestionOption {
        var label: String = ""
        var description: String = ""
    }

    // ============================================================
    // 附件
    // ============================================================

    /** 一张待发送的附件：图片已压缩，data 是最终 bytes；非图片保留原字节去换 receiptId。 */
    class Attachment {
        var name: String = ""
        var mediaType: String = ""
        var data: ByteArray = ByteArray(0)

        /**
         * 非图片附件必须先上传换到这个 receiptId，宿主才认。
         * 图片为空——图片是直接 base64 内联进 prompt 的。
         */
        var receiptId: String = ""

        /** 上传过的是文件，没上传的是图片。 */
        val isFile: Boolean get() = receiptId.isNotEmpty()
    }

    /** 历史消息里引用的图片（durable 日志只存 attachmentId）。 */
    @Serializable
    data class ImageRef(
        var attachmentId: String = "",
        var name: String = "",
        /**
         * **本地刚发出去的这张图**的缩略图字节。
         *
         * <p>`@Transient`：它只活在内存里，不参与序列化——落盘/上行的是
         * `attachmentId`，宿主那边有权威副本。
         *
         * <p>为什么需要它（2026-11-01 用户实测）：本地回声的 `ImageRef` 只有
         * `name`、`attachmentId` 还是空串（要等宿主把 durable 记录回显下来才有
         * id），而缩略图组件看见非空 `attachmentId` 才会去取——于是它拿一个空 id
         * 发 `session/attachment`，必然失败，界面上就是「图片加载失败」，等发送
         * 成功、id 回填之后又自己好了。可字节本来就在手机里，压根不该走网络。
         */
        @kotlinx.serialization.Transient
        var local: android.graphics.Bitmap? = null,
    )

    /** 会话内容搜索的一条命中。 */
    data class SearchHit(
        var sessionId: String = "",
        var title: String = "",
        var snippet: String = "",
    )

    // ============================================================
    // 后台任务
    // ============================================================

    enum class TaskState { RUNNING, QUEUED, DONE, FAILED, CANCELLED }

    data class Task(
        var id: String = "",
        var title: String = "",
        var description: String = "",
        var state: TaskState = TaskState.RUNNING,
        var startedAt: Long = 0,
        var stepsDone: Int = 0,
        var stepsTotal: Int = 0,
        var throughput: String = "",
        var tokensUsed: Long = 0,
        var progress: Float = 0f,
        val steps: MutableList<TaskStep> = mutableListOf(),
        val console: MutableList<String> = mutableListOf(),
        val artifacts: MutableList<Artifact> = mutableListOf(),
    ) {
        fun elapsedLabel(): String {
            if (startedAt <= 0) return ""
            val totalSeconds = maxOf(0L, (System.currentTimeMillis() - startedAt) / 1000)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            if (minutes <= 0L) return "已运行 $seconds 秒"
            return "已运行 $minutes 分 $seconds 秒"
        }
    }

    data class TaskStep(
        var title: String = "",
        var status: Status = Status.PENDING,
        var metric: String = "",
    ) {
        enum class Status { DONE, RUNNING, PENDING, FAILED }
    }

    data class Artifact(
        var name: String = "",
        var size: String = "",
        var state: String = "",
        var uploaded: Boolean = false,
    )

    // ============================================================
    // 版本管理
    // ============================================================

    /** 服务器上的一个发布版本（version.json 的内容）；缺字段时留空而不是崩。 */
    data class Release(
        var latest: String = "",
        var minSupported: String = "",
        var url: String = "",
        var latestUrl: String = "",
        var size: Long = 0,
        var sha256: String = "",
        var notes: String = "",
        var publishedAt: String = "",
    ) {
        /** 下载地址优先用版本专属链接，老服务器没给才退回 latest.apk。 */
        fun downloadUrl(): String = url.ifEmpty { latestUrl }

        val valid: Boolean get() = latest.isNotEmpty() && downloadUrl().isNotEmpty()
        /** "2.2 MB" / "512 KB"；拿不到大小就返回空串。 */
        fun sizeLabel(): String {
            if (size <= 0) return ""
            if (size < 1024) return "$size B"
            if (size < 1024 * 1024) return String.format(Locale.getDefault(), "%.0f KB", size / 1024.0)
            return String.format(Locale.getDefault(), "%.1f MB", size / 1024.0 / 1024.0)
        }

        /** 服务器版本是否比当前版本新。 */
        fun newerThan(current: String): Boolean = Release.compareVersions(latest, current) > 0

        /** 是否卡着最低支持版本：当前版本低于它就非得升级。 */
        fun forcesUpgrade(current: String): Boolean {
            if (minSupported.isEmpty()) return false
            return Release.compareVersions(current, minSupported) < 0
        }

        companion object {
            /**
             * 版本号比较：`0.1.9 < 0.2.0 < 1.0.0`。不能比字符串（"0.1.10" < "0.1.9"）；
             * 逐段按数字比，段数不齐按 0 补；非数字段退化成字符串比较。
             */
            fun compareVersions(a: String?, b: String?): Int {
                val x = (a ?: "").trim().split(Regex("[.\\-+]"))
                val y = (b ?: "").trim().split(Regex("[.\\-+]"))
                val n = maxOf(x.size, y.size)
                for (i in 0 until n) {
                    val px = x.getOrElse(i) { "0" }
                    val py = y.getOrElse(i) { "0" }
                    val nx = px.isNotEmpty() && px.all { it.isDigit() }
                    val ny = py.isNotEmpty() && py.all { it.isDigit() }
                    if (nx && ny) {
                        val d = (px.ifEmpty { "0" }.toLong()).compareTo(py.ifEmpty { "0" }.toLong())
                        if (d != 0) return d
                    } else {
                        val d = px.compareTo(py)
                        if (d != 0) return d
                    }
                }
                return 0
            }
        }
    }

    // ============================================================
    // 设置项
    // ============================================================

    data class Preferences(
        var showReasoning: Boolean = true,
        var editContent: Boolean = false,
        var autoReconnect: Boolean = true,
        /**
         * 中继要不要为**这台设备**搬运附件字节。
         *
         * <p>**不是**本地显示开关：关掉之后中继拒绝转发 `session/attachment`
         * （回读历史图片）与 `uploadFileBinary`（发文件），字节根本不过中继。
         * 真值存在服务端 `devices.attachments`，这里只是它的本地缓存——
         * 界面显示以 [RelayGateway.loadAttachments] 拉回来的为准。
         */
        var transferAttachments: Boolean = true,
        var omitHeader: String = "",
    )

    // ============================================================
    // 主机
    // ============================================================

    @Serializable
    data class Host(
        var id: String = "",
        var alias: String = "",
        var online: Boolean = false,
        var authorized: Boolean = false,
        var version: String = "",
        /** macos / windows / linux；空串表示这台主机没上报过平台（老插件）。 */
        var platform: String = "",
    )

    /** v1 Model.Session.relativeTime 的展示形式：刚刚 / N 分钟前 / 今天 HH:mm / 昨天 / N 天前 / 上周 / N 周前。 */
    /**
     * 按日期分段的分组名（v1 按日期排序用）：今天 / 昨天 / 本周 / 更早。
     * 与 relativeTime 同一套时间口径，避免两处判断漂移。
     */
    fun dateGroupOf(updatedAt: Long, now: Long = System.currentTimeMillis()): String {
        if (updatedAt <= 0) return "更早"
        // 「现在」必须取自入参 now，不能用 Calendar.getInstance()：否则传进来的基准时间
        // 只影响下面的数值差，不影响「今天/昨天」判定，跨零点会算错，也没法写确定性测试。
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val then = Calendar.getInstance().apply { timeInMillis = updatedAt }
        if (isSameDay(cal, then)) return "今天"
        if (isYesterday(cal, then)) return "昨天"
        val days = (now - updatedAt) / 86_400_000L
        if (days <= 6) return "本周"
        return "更早"
    }
    fun relativeTime(updatedAt: Long, now: Long = System.currentTimeMillis()): String {
        if (updatedAt <= 0) return ""
        val minutes = (now - updatedAt) / 60_000L
        // 未来时间（时钟回拨/测试构造）不能算「刚刚」：负数会落进 <1 分支，
        // 让「今天 09:12」这类同一天但更晚的时刻被判成刚刚（2026-10-27 凌晨实测踩到）。
        if (minutes in 0..0) return "刚刚"
        if (minutes in 1..59) return "$minutes 分钟前"
        val hours = minutes / 60
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val then = Calendar.getInstance().apply { timeInMillis = updatedAt }
        if (isSameDay(cal, then)) {
            return String.format(Locale.getDefault(), "今天 %02d:%02d",
                then.get(Calendar.HOUR_OF_DAY), then.get(Calendar.MINUTE))
        }
        if (isYesterday(cal, then)) return "昨天"
        // 未来时间（minutes 为负）已在上面的 isSameDay 分支返回；到这里 minutes 必为正。
        val days = (if (hours > 0) hours else 0) / 24
        if (days <= 6) return "$days 天前"
        if (days <= 13) return "上周"
        return String.format(Locale.getDefault(), "%d 周前", days / 7)
    }

    private fun isSameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    private fun isYesterday(now: Calendar, then: Calendar): Boolean {
        val y = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        return isSameDay(y, then)
    }
}

/**
 * 任务清单 → 胶囊计数。
 *
 * 只数 `completed`：宿主的三态里 `in_progress` 是「正在做」，对用户而言和
 * `pending` 一样都是「还没完成」，分开数会让「任务: 3/7」在同一个任务上跳来跳去。
 */
fun taskProgressOf(todos: List<Model.Todo>): Model.TaskProgress =
    Model.TaskProgress(done = todos.count { it.completed }, total = todos.size)

/**
 * 任务清单里一条的展示形态：胶囊点开后那张清单按这个分档画图标与字色。
 *
 * 与 [taskProgressOf] 的分工要看清：计数只认 `completed`（进行中也算「没完成」，
 * 否则「任务: 3/7」会在同一个任务上跳），但**清单**必须把进行中单独标出来，
 * 不然用户看不出此刻停在哪一条。两处判据不同是故意的，别合并成一个。
 */
enum class TodoMark {
    /** 已完成：实心对勾 + 次要字色。 */
    DONE,

    /** 进行中：转圈 + 主字色，一眼看出「现在在做这条」。 */
    DOING,

    /** 还没轮到（含宿主将来新增的未知状态）：空心方框 + 次要字色。 */
    TODO,
}

/** 一条任务 → 展示形态。未知状态按「还没做」处理，不抛异常。 */
fun todoMarkOf(todo: Model.Todo): TodoMark = when {
    todo.completed -> TodoMark.DONE
    todo.inProgress -> TodoMark.DOING
    else -> TodoMark.TODO
}
