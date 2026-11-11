package com.dsh.mobile.v2.data

import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 投影器回归：这些用例一对一钉住 v2 文档里点名的历史坑。
 *
 * 每个用例名都写明它在防什么，失败信息也写成「现象 + 为什么错」，因为这类 bug
 * 在真机上表现为「渲染得难看」，很难从堆栈反推。
 */
class ProjectorTest {

    // ---------- 构造工具 ----------

    private fun record(
        seq: Long,
        kind: RecordKind,
        turnId: String = "s1:t1",
        prevSeq: Long = seq - 1,
        at: Long = 1_000L * seq,
        payload: String = "",
        sessionId: String = "s1",
        clientMsgId: String? = null,
    ) = TimelineRecord(
        sessionId = sessionId,
        seq = seq,
        prevSeq = prevSeq,
        turnId = turnId,
        kind = kind,
        timestampMs = at,
        clientMsgId = clientMsgId,
        payloadJson = payload,
    )

    private fun user(seq: Long, text: String, turnId: String = "s1:t1") = record(
        seq, RecordKind.USER_INPUT, turnId, payload = """
            {"type":"user/message","seq":$seq,"data":{"message":{"role":"user","content":[{"type":"text","text":"$text"}]}}}
        """.trimIndent()
    )

    private fun assistant(seq: Long, text: String, reasoning: String = "", turnId: String = "s1:t1"): TimelineRecord {
        val parts = buildString {
            append("""{"type":"text","text":"$text"}""")
            if (reasoning.isNotEmpty()) append(""",{"type":"reasoning","text":"$reasoning"}""")
        }
        return record(
            seq, RecordKind.ASSISTANT_TEXT, turnId, payload = """
                {"type":"assistant/message","seq":$seq,"data":{"message":{"role":"assistant","content":[$parts]}}}
            """.trimIndent()
        )
    }

    private fun toolCall(seq: Long, callId: String, name: String, command: String, turnId: String = "s1:t1") = record(
        seq, RecordKind.STEP_TOOL, turnId, payload = """
            {"type":"tool/call","seq":$seq,"data":{"callId":"$callId","name":"$name","arguments":"{\"command\":\"$command\"}"}}
        """.trimIndent()
    )

    private fun toolResult(seq: Long, callId: String, output: String, turnId: String = "s1:t1") = record(
        seq, RecordKind.STEP_TOOL, turnId, payload = """
            {"type":"tool/result","seq":$seq,"data":{"callId":"$callId","output":"$output"}}
        """.trimIndent()
    )

    private fun turnEnd(seq: Long, turnId: String = "s1:t1") =
        record(seq, RecordKind.TURN_END, turnId, payload = """{"type":"turn/end","seq":$seq,"data":{}}""")

    /** 投影器一字不读、但会推进 `anchorSeq` / `lastSeq` 的记录（`turn/start` 等）。 */
    private fun metaPatch(seq: Long, payload: String, turnId: String = "s1:t1") =
        record(seq, RecordKind.META_PATCH, turnId, payload = payload)

    /** 宿主在 catch 分支写的失败回合：`{kind:"error", error:{message, code}}`。 */
    private fun turnEndFailed(seq: Long, code: String, message: String, turnId: String = "s1:t1") =
        record(
            seq, RecordKind.TURN_END, turnId,
            payload = """{"type":"turn/end","seq":$seq,"data":{"reason":{"kind":"error","error":{"message":"$message","code":"$code"}}}}""",
        )

    // ---------- 分段（治「一轮压成一个折叠栏」，2026-10-31 用户按桌面端提需求） ----------

    @Test
    fun `every stretch of assistant text opens a new folded row after it`() {
        // 真宿主一轮能跑 24 个 step，每步都是「思考 + 正文 + 工具」。桌面端把它们
        // 交叉铺开：「折叠栏 / 正文 / 折叠栏 / 正文」。旧模型把一轮压成一个面板 +
        // 一坨正文，手机上就是一条折叠栏下面堆着所有正文（用户 2026-10-31 反馈）。
        val turns = projectTimeline(
            listOf(
                user(1, "干活"),
                toolCall(2, "c1", "ops_bash", "npm test"),
                assistant(3, "第一段结论", reasoning = "先跑测试"),
                toolCall(4, "c2", "ops_bash", "npm build"),
                assistant(5, "第二段结论", reasoning = "再构建"),
            )
        )
        val rows = turns.single().toMessages()
        assertEquals(
            "顺序必须是「用户 → 面板 → 正文 → 面板 → 正文」，把正文全堆在末尾就是没分段",
            listOf(
                Model.Role.USER to false,
                Model.Role.ASSISTANT to true,
                Model.Role.ASSISTANT to false,
                Model.Role.ASSISTANT to true,
                Model.Role.ASSISTANT to false,
            ),
            rows.map { it.role to it.panelId.isNotEmpty() },
        )
        val texts = rows.filter { it.role == Model.Role.ASSISTANT && it.text.isNotEmpty() }.map { it.text }
        assertEquals(listOf("第一段结论", "第二段结论"), texts)
    }

    @Test
    fun `each folded row has its own identity and position`() {
        // 一轮多块面板之后，panelId 与 order 都不能再共用同一个值：共用了 LazyColumn
        // 的 key 会撞（直接崩），抽屉也会「点哪块都打开最后一块」。
        val turn = projectTimeline(
            listOf(
                user(1, "干活"),
                toolCall(2, "c1", "ops_bash", "a"),
                assistant(3, "第一段"),
                toolCall(4, "c2", "ops_bash", "b"),
                assistant(5, "第二段"),
            )
        ).single()
        val panels = turn.toMessages().filter { it.panelId.isNotEmpty() }
        assertEquals("每段正文之前都应该有一块自己的折叠栏", 2, panels.size)
        assertEquals("面板身份必须互不相同", 2, panels.map { it.panelId }.distinct().size)
        assertEquals("面板位置必须互不相同（LazyColumn 的 key 由它派生）", 2, panels.map { it.order }.distinct().size)
        assertTrue(
            "第一块面板要排在第二块之前",
            panels[0].order < panels[1].order,
        )
    }

    @Test
    fun `a turn whose text never arrives still folds into a single running row`() {
        // 反面：整轮一句正文都没有（长工具链/失败中断）时不能切成一堆空段。
        val turn = projectTimeline(
            listOf(user(1, "跑"), toolCall(2, "c1", "ops_bash", "npm test"), turnEnd(3))
        ).single()
        val rows = turn.toMessages()
        assertEquals("只有用户消息 + 一块面板，不该凭空多出第二块", 2, rows.size)
        assertTrue("那块面板必须是折叠行", rows[1].panelId.isNotEmpty())
    }

    // ---------- 槽位顺序（治 bug 10 / 27：面板跑到气泡上方） ----------

    @Test
    fun `a turn renders user message then panel then assistant text`() {
        val turns = projectTimeline(
            listOf(
                user(1, "跑一下测试"),
                toolCall(2, "c1", "ops_bash", "npm test"),
                assistant(3, "好了"),
            )
        )
        assertEquals(1, turns.size)
        val turn = turns.single()
        assertEquals("跑一下测试", turn.userMessage?.text)
        assertNotNull("这一轮有工具调用，必须出现过程面板", turn.processPanel)
        assertEquals(listOf("好了"), turn.assistantBlocks.map { it.text })

        // 槽位顺序由 `toMessages()` 决定，不是由 seq 小数决定。
        val roles = turn.toMessages().map { it.role to it.panelId.isNotEmpty() }
        assertEquals(
            "顺序必须是「用户 → 面板 → 正文」；面板跑到气泡上方就是这里错了",
            listOf(Model.Role.USER to false, Model.Role.ASSISTANT to true, Model.Role.ASSISTANT to false),
            roles,
        )
    }

    @Test
    fun `a panel never appears above the user message even when turn start precedes it`() {
        // 真宿主 `turn/start` 比 `user/message` 早：面板的 anchorSeq 会小于用户消息。
        // 槽位硬编码顺序就是为这个情形准备的。
        val turns = projectTimeline(
            listOf(
                toolCall(1, "c1", "ops_bash", "ls"),
                user(2, "看看目录"),
                assistant(3, "看完了"),
            )
        )
        val rows = turns.single().toMessages()
        assertEquals(Model.Role.USER, rows.first().role)
        assertTrue("面板必须排在用户消息之后", rows[1].panelId.isNotEmpty())
    }

    @Test
    fun `a live panel's order is greater than its own user message seq`() {
        // 治 2026-11-04 「动手！」那条：真宿主一轮的第一条记录是 `turn/start`（META_PATCH），
        // 比 `user/message` 早若干 seq。活面板没有 durable 锚点（panelSeq==0），旧算式用
        // `anchorSeq + 0.25*(index+1)` 作位置 —— 段序要累加到第 9 段才 +2.25，永远追不上
        // 那 3 个 seq 的差距，于是折叠行排到了用户气泡**上方**。
        //
        // 生产实测（botw `session-a57cda03`）：t25 的 anchorSeq=10394、用户消息 seq=10397。
        // 这里复刻同样的形状：turn/start 在 1，用户消息在 5。
        val records = listOf(
            metaPatch(1, """{"type":"turn/start","seq":1,"data":{"turn":1}}"""),
            user(5, "动手！"),
            assistant(6, "上一段正文"),
        )
        val overlay = Overlay(
            uncommitted = emptyMap(),
            liveReasoning = "正在分析中",
            reasoningTurnId = "s1:t1",
        )
        val rows = project(records, overlay).single().toMessages()
        val userSeq = rows.first { it.role == Model.Role.USER }.seq
        val livePanel = rows.last { it.panelId.isNotEmpty() }
        assertTrue(
            "活面板的位置（${livePanel.order}）必须晚于这一轮用户消息的 seq（$userSeq），" +
                "否则折叠行会跑到气泡上方",
            livePanel.order > userSeq.toDouble(),
        )
    }

    @Test
    fun `a durable body is not drawn twice when the stream still holds it`() {
        // 治 2026-11-04 「同一条正文上下各一份」：durable 正文在 `assistantText()` 末尾
        // 被 `.trim()`，宿主原文常以 `\n\n` 开头（生产实测 3670 字 → trim 后 3668）。
        // 旧去重判据拿未 trim 的流式文本去 `startsWith` 已 trim 的 durable，恒假 ⇒
        // 流式副本以 `seq=0` 挂在末段，被 `position()` 算成 0 排到时间线最前。
        val body = "验证完成，结论：这条路走得通。"
        val records = listOf(user(1, "动手"), assistant(2, body))
        val overlay = Overlay(
            uncommitted = emptyMap(),
            streamingText = "\n\n$body",     // 宿主原文带前导换行
            streamingTurnId = "s1:t1",
        )
        val rows = project(records, overlay).flatMap { it.toMessages() }
            .filter { it.role == Model.Role.ASSISTANT && it.text.contains("这条路走得通") }
        assertEquals(
            "同一条正文只能出现一次；出现两次就是流式副本没被去重",
            1,
            rows.size,
        )
        assertTrue(
            "留下的必须是 durable 那份（seq>0），流式副本（seq=0）会被排到时间线最前",
            rows.single().seq > 0,
        )
    }

    // ---------- 折叠行摘要（治 bug 37） ----------

    @Test
    fun `tail preview keeps the newest characters so a live line visibly moves`() {
        val long = "第一段很长的思考内容".repeat(6) + "最新的结论在这里"
        val turn = projectTimeline(listOf(user(1, "问"), assistant(2, "答", reasoning = long))).single()
        val preview = turn.processPanel!!.tailThinkingPreview
        assertTrue("预览必须取尾部，取头部等于永远不更新", preview.endsWith("最新的结论在这里"))
        assertEquals("预览必须压平成单行（UI 上是 maxLines=1）", -1, preview.indexOf('\n'))
        assertTrue("预览不能超过 60 字，否则一行放不下", preview.length <= 60)
    }

    @Test
    fun `ops count reports while running and stops counting after the turn ends`() {
        val running = projectTimeline(
            listOf(user(1, "跑"), toolCall(2, "c1", "ops_bash", "a"), toolCall(3, "c2", "ops_bash", "b"))
        ).single()
        assertEquals(TurnState.RUNNING, running.turnState)
        assertTrue(running.processPanel!!.isRunning)
        assertEquals("运行中就要报操作数，不能等收尾（bug 37 第二次反馈）", 2, running.processPanel!!.totalOpsCount)

        val done = projectTimeline(
            listOf(
                user(1, "跑"), toolCall(2, "c1", "ops_bash", "a"), toolCall(3, "c2", "ops_bash", "b"),
                turnEnd(4),
            )
        ).single()
        assertEquals(TurnState.COMPLETED, done.turnState)
        assertEquals(2, done.processPanel!!.totalOpsCount)
        assertTrue("收尾后要给用时", done.processPanel!!.durationSec >= 1)
    }

    @Test
    fun `a turn with no tool calls says thinking rather than inventing an ops count`() {
        val turn = projectTimeline(listOf(user(1, "问"), assistant(2, "答", reasoning = "想一想"))).single()
        assertEquals(0, turn.processPanel!!.totalOpsCount)
        // 文案口径与 `ChatViewModel.currentActivity()` 一致：「思考中」。
        assertEquals("思考中", turn.processPanel!!.currentActionLabel)
    }

    // ---------- 工具配对 ----------

    @Test
    fun `a tool result attaches to its own call instead of the last one`() {
        val turn = projectTimeline(
            listOf(
                user(1, "跑两个"),
                toolCall(2, "c1", "ops_bash", "first"),
                toolCall(3, "c2", "ops_bash", "second"),
                toolResult(4, "c1", "first-output"),
                toolResult(5, "c2", "second-output"),
            )
        ).single()
        val actions = turn.processPanel!!.actions
        assertEquals(2, actions.size)
        assertEquals("c1", actions[0].callId)
        assertEquals("first-output", actions[0].output)
        assertEquals("second-output", actions[1].output)
        assertEquals(Model.ToolAction.Status.DONE, actions[0].status)
    }

    @Test
    fun `a result without a call id falls back to the last running row instead of vanishing`() {
        // 宿主某些版本漏发 callId；宁可归到最近一行，也不能让输出凭空消失。
        val turn = projectTimeline(
            listOf(
                user(1, "跑"),
                toolCall(2, "c1", "ops_bash", "cmd"),
                record(3, RecordKind.STEP_TOOL, payload = """{"type":"tool/result","seq":3,"data":{"output":"出结果了"}}"""),
            )
        ).single()
        val actions = turn.processPanel!!.actions
        assertEquals(1, actions.size)
        assertEquals("出结果了", actions[0].output)
        assertEquals(Model.ToolAction.Status.DONE, actions[0].status)
    }

    @Test
    fun `command tools are recognised so the fold line can say what is running`() {
        val turn = projectTimeline(
            listOf(user(1, "跑"), toolCall(2, "c1", "ops_bash", "npm test"))
        ).single()
        val action = turn.processPanel!!.actions.single()
        assertTrue("命令类工具要打上标记（决定图标与措辞）", action.command)
        assertEquals("执行 npm test", turn.processPanel!!.currentActionLabel)
    }

    // ---------- 操作小标题（模型写的 `description`，对齐桌面端折叠行） ----------

    /** 与 [toolCall] 同形，但参数里带上了模型写的小标题。 */
    private fun toolCallWithDescription(
        seq: Long,
        callId: String,
        name: String,
        command: String,
        description: String,
        turnId: String = "s1:t1",
    ) = record(
        seq, RecordKind.STEP_TOOL, turnId, payload = """
            {"type":"tool/call","seq":$seq,"data":{"callId":"$callId","name":"$name",
            "arguments":"{\"command\":\"$command\",\"description\":\"$description\"}"}}
        """.trimIndent().replace("\n", "")
    )

    @Test
    fun `a tool description becomes the subtitle the fold line shows`() {
        // 桌面端折叠行：`工具调用 · ops_bash · 改成十秒桶`。最后那段是模型写在参数
        // `description` 里的那句话；手机端原先完全不读它，于是同一批调用在电脑上有
        // 小标题、在手机上只剩一条裸命令（2026-11-01 用户要求同步）。
        val turn = projectTimeline(
            listOf(
                user(1, "改成十秒桶"),
                toolCallWithDescription(2, "c1", "ops_bash", "node scripts/build.mjs", "改成十秒桶"),
            )
        ).single()
        val action = turn.processPanel!!.actions.single()
        assertEquals("改成十秒桶", action.subtitle)
        // 命令仍在 detail 里，两段各归各位：小标题进折叠行第一行，命令进第二行。
        assertEquals("node scripts/build.mjs", action.detail)
    }

    @Test
    fun `a tool without a description keeps an empty subtitle instead of borrowing the command`() {
        // 没写描述的工具**不能**拿命令冒充小标题：那样折叠行第一行会变成
        // 「已处理 N 个操作 · 执行命令 · npm test」，第二行又写一遍 npm test。
        val turn = projectTimeline(
            listOf(user(1, "跑"), toolCall(2, "c1", "ops_bash", "npm test"))
        ).single()
        assertEquals("", turn.processPanel!!.actions.single().subtitle)
    }

    @Test
    fun `a description object payload is read as text too`() {
        // 少数宿主把描述写成 `{description:{text:"…"}}`（与 content part 同形）。
        val turn = projectTimeline(
            listOf(
                user(1, "跑"),
                record(
                    2, RecordKind.STEP_TOOL, payload = """
                        {"type":"tool/call","seq":2,"data":{"callId":"c1","name":"ops_bash",
                        "arguments":"{\"command\":\"ls\",\"description\":{\"text\":\"看看目录\"}}"}}
                    """.trimIndent().replace("\n", "")
                ),
            )
        ).single()
        assertEquals("看看目录", turn.processPanel!!.actions.single().subtitle)
    }

    @Test
    fun `a later call record fills in a subtitle the delta missed`() {
        // 流式增量常常只带得出命令，描述要等完整参数才看得见。同一条调用被覆盖时
        // 必须把描述补上，否则折叠行永远等不到小标题。
        val turn = projectTimeline(
            listOf(
                user(1, "跑"),
                toolCall(2, "c1", "ops_bash", "npm test"),
                toolCallWithDescription(3, "c1", "ops_bash", "npm test", "跑测试"),
            )
        ).single()
        val action = turn.processPanel!!.actions.single()
        assertEquals("跑测试", action.subtitle)
        assertEquals("同一条调用不能被拆成两行", 1, turn.processPanel!!.actions.size)
    }

    // ---------- 多轮与孤儿记录 ----------

    @Test
    fun `separate turns stay separate and keep their own panels`() {
        val turns = projectTimeline(
            listOf(
                user(1, "第一句", "s1:t1"), assistant(2, "第一答", turnId = "s1:t1"), turnEnd(3, "s1:t1"),
                user(4, "第二句", "s1:t2"), assistant(5, "第二答", turnId = "s1:t2"),
            )
        )
        assertEquals(2, turns.size)
        assertEquals("第一句", turns[0].userMessage?.text)
        assertEquals("第二句", turns[1].userMessage?.text)
        assertEquals(TurnState.COMPLETED, turns[0].turnState)
        assertEquals(TurnState.RUNNING, turns[1].turnState)
        assertEquals("每一轮只带自己的正文", listOf("第一答"), turns[0].assistantBlocks.map { it.text })
    }

    @Test
    fun `records without a turn id are grouped instead of thrown away`() {
        // 迁移前的归档没有 turnId。丢掉它们等于历史凭空变短；放进一条无边界巨轮
        // 又会让面板永远在转。做法是把它们并进最近一个用户消息所在的轮。
        val turns = projectTimeline(
            listOf(
                user(1, "问"),
                record(2, RecordKind.ASSISTANT_TEXT, turnId = "", payload = """{"type":"assistant/message","data":{"content":[{"type":"text","text":"旧回答"}]}}"""),
            )
        )
        assertEquals(1, turns.size)
        assertEquals("问", turns.single().userMessage?.text)
        assertTrue(turns.single().assistantBlocks.map { it.text }.contains("旧回答"))
    }

    // ---------- 覆盖层（实时与归档合并） ----------

    @Test
    fun `committed rows win over the same seq in the overlay`() {
        val durable = listOf(user(1, "问"), assistant(2, "落盘的答案"))
        val overlay = Overlay(uncommitted = mapOf(2L to assistant(2, "覆盖层的答案")))
        val turn = project(durable, overlay).single()
        assertEquals("同 seq 必须以 SQLite 为准", listOf("落盘的答案"), turn.assistantBlocks.map { it.text })
    }

    @Test
    fun `overlay fills seqs the database does not have yet`() {
        val durable = listOf(user(1, "问"))
        val overlay = Overlay(uncommitted = mapOf(2L to assistant(2, "还没落盘")))
        val turn = project(durable, overlay).single()
        assertTrue(turn.assistantBlocks.map { it.text }.contains("还没落盘"))
    }

    @Test
    fun `compacting the overlay drops rows at or below the acknowledged watermark`() {
        val overlay = Overlay(uncommitted = mapOf(3L to assistant(3, "a"), 7L to assistant(7, "b")))
        val compacted = overlay.compactedTo(5L)
        assertEquals(setOf(7L), compacted.uncommitted.keys)
        assertEquals("水位之下全清空时应原样返回同一对象", overlay, overlay.compactedTo(2L).takeIf { it !== overlay } ?: overlay)
    }

    @Test
    fun `streaming text is shown as an assistant block while it grows`() {
        val durable = listOf(user(1, "问"))
        val live = project(durable, Overlay.EMPTY.withStreaming("s1:t1", "正在"))
        assertTrue(live.single().assistantBlocks.any { it.text == "正在" && it.streaming })

        // 落盘后 durable 更长：流式不再覆盖它。
        val settled = project(
            listOf(user(1, "问"), assistant(2, "正在输出完整回答")),
            Overlay.EMPTY.withStreaming("s1:t1", "正在"),
        )
        assertEquals(listOf("正在输出完整回答"), settled.single().assistantBlocks.map { it.text })
    }

    // ---------- 审批与交互 ----------

    @Test
    fun `an approval card carries the session-scoped interaction id`() {
        val seq = 7L
        val turn = projectTimeline(
            listOf(
                user(1, "跑"),
                record(
                    seq, RecordKind.APPROVAL_REQ,
                    payload = """{"type":"approval/request","seq":$seq,"data":{"toolName":"ops_bash","callId":"c1","reason":"要执行命令"}}"""
                ),
            )
        ).single()
        val card = turn.interactions.single()
        assertEquals("interaction_id 必须是 sessionId:seq，严禁时间戳", "s1:7", card.interactionId)
        assertEquals(Model.Approval::class.java, card.approval!!::class.java)
        assertEquals("要执行命令", card.approval!!.headline())
        assertEquals("审批未结算时整轮处于等待状态", TurnState.WAITING_INTERACTION, turn.turnState)
        // 归档重建的卡**不可回答**：它带的 id 是 sessionId:seq，宿主的 $events
        // 不认这个 id。界面据此灰掉按钮并给出提示 —— 宁可点不动，也不要点了
        // 像成功、那一轮却还卡在电脑上（2026-10-31 用户报「审批卡点不动」）。
        assertEquals(false, card.approval!!.answerable)
    }

    @Test
    fun `a resolution record removes the card it settles`() {
        val turn = projectTimeline(
            listOf(
                user(1, "跑"),
                record(2, RecordKind.APPROVAL_REQ, payload = """{"type":"approval/request","seq":2,"data":{"toolName":"t","reason":"r"}}"""),
                record(3, RecordKind.APPROVAL_RES, payload = """{"type":"approval/decided","seq":3,"data":{"forSeq":2}}"""),
            )
        ).single()
        assertTrue("结算后卡片必须消失；留着就是「点完又冒出来」", turn.interactions.isEmpty())
    }

    @Test
    fun `an ambiguous settlement never removes the card that is still waiting`() {
        // 两张同类卡并发挂着（一轮里连着问两次审批），而结算帧什么关联键都没带。
        // 旧兜底无条件 indexOfLast：它会把**还在等的那张**撤掉、真正结算的那张留着，
        // 用户看到「批过的又冒出来、没批的消失了」。
        val turn = projectTimeline(
            listOf(
                user(1, "跑"),
                record(2, RecordKind.APPROVAL_REQ, payload = """{"type":"approval/request","seq":2,"data":{"toolName":"t","reason":"第一条"}}"""),
                record(3, RecordKind.APPROVAL_REQ, payload = """{"type":"approval/request","seq":3,"data":{"toolName":"t","reason":"第二条"}}"""),
                record(4, RecordKind.APPROVAL_RES, payload = """{"type":"approval/decided","seq":4,"data":{}}"""),
            )
        ).single()
        assertEquals(
            "关联键缺失且同类卡不止一张时必须一张都不撤",
            listOf("s1:2", "s1:3"),
            turn.interactions.map { it.interactionId },
        )
    }

    @Test
    fun `a lone card is still settled by an ambiguous resolution record`() {
        // 反面：同类卡只有一张时兜底照旧生效——这是「字段名变了别让卡片永远挂着」的保命路。
        val turn = projectTimeline(
            listOf(
                user(1, "跑"),
                record(2, RecordKind.APPROVAL_REQ, payload = """{"type":"approval/request","seq":2,"data":{"toolName":"t","reason":"r"}}"""),
                record(3, RecordKind.APPROVAL_RES, payload = """{"type":"approval/decided","seq":3,"data":{}}"""),
            )
        ).single()
        assertTrue("唯一的同类卡该被撤下", turn.interactions.isEmpty())
    }

    // ---------- 体积上限与错误可见性 ----------

    @Test
    fun `an oversized record shows a visible notice instead of silence`() {
        val turn = projectTimeline(
            listOf(
                user(1, "跑"),
                record(2, RecordKind.ERROR_OVERSIZED_TRUNCATED, payload = """{"type":"error/oversized-truncated","data":{}}"""),
            )
        ).single()
        val notice = turn.assistantBlocks.single()
        assertEquals(Model.Role.SYSTEM, notice.role)
        assertTrue(notice.text.contains("体积上限"))
    }

    // ---------- 空输入 ----------

    @Test
    fun `an empty timeline projects to nothing rather than a phantom turn`() {
        assertTrue(projectTimeline(emptyList()).isEmpty())
        assertTrue(project(emptyList(), Overlay.EMPTY).isEmpty())
    }

    @Test
    fun `malformed payloads degrade to empty text without throwing`() {
        // 投影路径上不许抛异常：一条坏记录不该让整页空白。
        val turn = projectTimeline(
            listOf(
                user(1, "问"),
                record(2, RecordKind.ASSISTANT_TEXT, payload = "not json at all"),
            )
        ).single()
        assertEquals("问", turn.userMessage?.text)
        assertNull("解析不出来的正文不该合成一条空消息", turn.assistantBlocks.firstOrNull())
    }

    @Test
    fun `an oversized stub shows a placeholder with its size and blocks no later record`() {
        // 存根保留原记录的 kind（这里是 ASSISTANT_TEXT），payload 换成 stub/payload。
        // 如果投影器只看 kind，这一条会被当成「空正文」静默跳过 —— 用户于是看不到
        // 那轮回复存在过，只看到后面那句「好的。」凭空接上来。
        val stub = record(
            seq = 3,
            kind = RecordKind.ASSISTANT_TEXT,
            payload = """{"type":"stub/payload","blobId":"s1:3","blobSize":524288}""",
        ).copy(isOversizedStub = true, blobId = "s1:3", blobSize = 524288)
        val later = record(
            seq = 4,
            kind = RecordKind.ASSISTANT_TEXT,
            payload = """{"type":"assistant/message","data":{"text":"后面这句还在"}}""",
        )

        val turns = projectTimeline(listOf(user(1, "看看这个文件"), stub, later))
        val all = turns.flatMap { it.assistantBlocks + listOfNotNull(it.userMessage) }

        val notice = all.firstOrNull { it.role == Model.Role.SYSTEM && it.seq == 3L }
        assertNotNull("存根必须留下一条可见占位", notice)
        assertTrue("占位要说清有多大：${notice!!.text}", notice.text.contains("512 KB"))
        assertTrue("后面的正文不能被存根吞掉", all.any { it.text.contains("后面这句还在") })
    }

    // ---------- 思考与正文必须分道（2026-10-30 反馈） ----------

    @Test
    fun `live reasoning never becomes an assistant bubble`() {
        // 现象：手机上「思考过程直接铺在聊天页」，而折叠行里什么都没有。
        // 成因是插件把 `reasoning-delta` 也塞进了正文缓冲，以 assistant-stream 下发。
        // 这一层守住的是下游：即使 reasoning 走了自己的通道，也不能被画成气泡。
        val records = listOf(user(1, "算一下"), assistant(2, "答案是 4"))
        val projected = project(
            records,
            Overlay(liveReasoning = "先看看题目里有什么…", reasoningTurnId = "s1:t1"),
        )
        val texts = projected.flatMap { it.assistantBlocks }.map { it.text }
        assertEquals("思考不该进正文气泡", listOf("答案是 4"), texts)
        val panel = projected.single().processPanel
        assertNotNull("思考必须落在折叠行的步骤里", panel)
        assertEquals(
            "思考必须能被折叠行的尾部预览取到",
            "先看看题目里有什么…",
            panel!!.effectiveSteps.last { it.kind == Model.Step.THINKING }.text,
        )
    }

    @Test
    fun `two live segments in one turn never collide on identity or position`() {
        // 流式过程中一轮会长出多段活面板：「思考 → 正文 → 又一段思考」。这些面板
        // 都还没有 panelSeq，如果身份退回 turnId、位置退回同一个值，LazyColumn 的
        // key 就会撞（`pos-<order>` 重复 ⇒ 直接崩），抽屉也会点哪块都开最后一块。
        val turn = TurnUiModel(
            turnId = "s1:t1",
            anchorSeq = 100,
            segments = persistentListOf(
                TurnSegment(panel = ProcessPanelNode(turnId = "s1:t1", isRunning = true, tailThinkingPreview = "先想")),
                TurnSegment(blocks = persistentListOf(Model.Message.assistant("第一段"))),
                TurnSegment(panel = ProcessPanelNode(turnId = "s1:t1", isRunning = true, tailThinkingPreview = "又想")),
                TurnSegment(blocks = persistentListOf(Model.Message.assistant("第二段"))),
            ),
        )
        val panels = turn.toMessages().filter { it.panelId.isNotEmpty() }
        assertEquals("两段活面板都要出现", 2, panels.size)
        assertEquals("身份必须互不相同", 2, panels.map { it.panelId }.distinct().size)
        assertEquals("位置必须互不相同", 2, panels.map { it.order }.distinct().size)
        assertTrue("位置必须自小到大，否则顺序会抖", panels[0].order < panels[1].order)
        assertTrue("活面板的位置不能退到 0（会被排到时间线最前面）", panels.all { it.order > 0 })
    }

    @Test
    fun `live reasoning starts a folded panel before any durable event arrives`() {
        // 一轮刚起步、只有思考增量时，界面上必须立刻出现折叠行（带尾部预览）。
        // 否则要等第一个 durable 记录才有面板，那几秒里界面是静止的。
        val projected = project(
            listOf(user(1, "写个测试")),
            Overlay(liveReasoning = "先想想要断言什么", reasoningTurnId = "s1:t1"),
        )
        val panel = projected.single().processPanel
        assertNotNull("思考一开始就该有折叠行", panel)
        assertTrue("运行中的折叠行要转圈", panel!!.isRunning)
        assertEquals(
            "先想想要断言什么",
            panel.effectiveSteps.single { it.kind == Model.Step.THINKING }.text,
        )
    }

    @Test
    fun `a longer live reasoning replaces the durable one instead of stacking`() {
        // 落定的 reasoning 与流式 reasoning 是同一条思路的两份快照，不是两段。
        // 叠成两条会让折叠行尾部预览读到较短的那条，看起来「思考卡住了」。
        val durable = assistant(2, "答案是 4", reasoning = "先算")
        val projected = project(
            listOf(user(1, "算一下"), durable),
            Overlay(liveReasoning = "先算一下两数之和再取模", reasoningTurnId = "s1:t1"),
        )
        val thinking = projected.single().processPanel!!.effectiveSteps.filter { it.kind == Model.Step.THINKING }
        assertEquals("不能叠成两条", 1, thinking.size)
        assertEquals("先算一下两数之和再取模", thinking.single().text)
    }

    @Test
    fun `the synthesised live thinking step keeps one id across reads`() {
        // `effectiveSteps` 是 getter，每读一次都会重新拼列表。合成出来的那条思考
        // 步骤如果走 `Step.nextId()`，每读一次就换一个号，而二级抽屉记的正是
        // `step.id` —— 流式思考每长一个字、界面每重组一次，抽屉里那条就查不到，
        // 表现为「点开二级详情，内容一加载就自己关掉」（2026-10-30 真机反馈）。
        val projected = project(
            listOf(user(1, "算一下")),
            Overlay(liveReasoning = "正在想", reasoningTurnId = "s1:t1"),
        )
        val panel = projected.single().processPanel!!
        val first = panel.effectiveSteps.single { it.kind == Model.Step.THINKING }.id
        val second = panel.effectiveSteps.single { it.kind == Model.Step.THINKING }.id
        assertTrue("合成的思考步骤必须有非空 id", first.isNotEmpty())
        assertEquals("同一条合成步骤两次读取必须是同一个 id", first, second)
    }

    @Test
    fun `the synthesised live thinking step id survives the text growing`() {
        // 思考在长的时候 `tailThinkingPreview` 会变，但 id 不能跟着变：
        // 抽屉开着的时候思考还在流，那正是最容易掉身份的时刻。
        val base = project(
            listOf(user(1, "算一下")),
            Overlay(liveReasoning = "想", reasoningTurnId = "s1:t1"),
        ).single().processPanel!!
        val grown = base.copy(tailThinkingPreview = "想了很久，还在想")
        val before = base.effectiveSteps.single { it.kind == Model.Step.THINKING }.id
        val after = grown.effectiveSteps.single { it.kind == Model.Step.THINKING }.id
        assertEquals("思考变长不该换 id", before, after)
    }

    @Test
    fun `a long live reasoning keeps its full text in the thinking step`() {
        // 事故形状：`tailThinkingPreview` 生成就 `.takeLast(60)`——那是折叠行的
        // 单行尾窗（bug 26），却直接漏进了二级抽屉的「深度思考」详情页
        // （MarkdownText(step.text)）。思考超过 60 字后，开头被持续挤掉，抽屉里
        // 永远只有最后 60 字，用户描述成「显示区域只有几行，后面的字挤掉前面的字」
        // （2026-10-31 真机反馈）。修法：面板另存全文 `liveThinkingFull`，
        // `effectiveSteps` 的 THINKING 步骤吃全文；尾窗字段照旧给折叠行。
        val long = "先看清楚题目在问什么。".repeat(3) + "然后逐条推演：" + (1..12).joinToString("") { "第$it 步核对前提并计算。" }
        assertTrue("测试前提：思考必须长过 60 字尾窗", long.length > 60)

        val projected = project(
            listOf(user(1, "推演一下")),
            Overlay(liveReasoning = long, reasoningTurnId = "s1:t1"),
        )
        val panel = projected.single().processPanel!!
        assertEquals(
            "抽屉里的思考步骤必须是全文，不能被 60 字窗口截断",
            long,
            panel.effectiveSteps.single { it.kind == Model.Step.THINKING }.text,
        )
        assertEquals(
            "折叠行尾窗字段照旧是 60 字压平（它有自己的显示额度）",
            long.replace('\n', ' ').trim().takeLast(60),
            panel.tailThinkingPreview,
        )
    }

    @Test
    fun `the durable thinking step keeps its full text from the turn builder`() {
        // durable 路径（TurnBuilder.build）也赋同一份全文：落定后的面板，
        // 抽屉里的思考步骤一样不能只剩 60 字。
        val durableReasoning = "先看约束。".repeat(3) + (1..12).joinToString("") { "方案$it 的代价要算清。" }
        assertTrue(durableReasoning.length > 60)
        val projected = projectTimeline(
            listOf(user(1, "设计方案"), assistant(2, "选方案 3", reasoning = durableReasoning)),
        )
        val panel = projected.single().processPanel!!
        assertEquals(
            durableReasoning,
            panel.effectiveSteps.single { it.kind == Model.Step.THINKING }.text,
        )
    }

    // ---------- 队列与任务：原本被当 META_PATCH 丢掉的持久事件 ----------

    private fun splice(
        seq: Long,
        target: String = "next-turn",
        start: Int = 0,
        removed: Int = 0,
        ids: List<String> = emptyList(),
        turnId: String = "s1:t1",
    ): TimelineRecord {
        val body = ids.joinToString(",") {
            "{\"id\":\"$it\",\"content\":[{\"type\":\"text\",\"text\":\"$it 的话\"}]}"
        }
        return record(
            seq, RecordKind.INBOX_SPLICE, turnId, payload = """
            {"type":"agent/inbox/spliced","seq":$seq,"data":{"target":"$target","start":$start,"removedCount":$removed,"inserted":[$body]}}
            """.trimIndent()
        )
    }

    @Test
    fun `排队的 splice 按桶与下标生效，而不是整表覆盖`() {
        // 宿主发的是增量：「哪个桶、从第几位、删几条、插几条」。
        // 把它当整表或当追加，都会让队列和宿主对不上。
        val turns = projectTimeline(
            listOf(
                user(1, "开始"),
                splice(2, ids = listOf("a", "b")),
                splice(3, ids = listOf("c"), start = 2),
            )
        )
        assertEquals(listOf("a", "b", "c"), turns.last().queue.map { it.id })
    }

    @Test
    fun `队列是会话级的，跨轮不丢`() {
        // 队列不属于某一轮。第二轮上没有 splice 时，它必须继承第一轮的值。
        val turns = projectTimeline(
            listOf(
                user(1, "第一轮", turnId = "s1:t1"),
                splice(2, ids = listOf("a"), turnId = "s1:t1"),
                user(3, "第二轮", turnId = "s1:t2"),
            )
        )
        assertEquals(listOf("a"), turns.last().queue.map { it.id })
    }

    @Test
    fun `任务清单是整表覆盖`() {
        fun todo(seq: Long, items: String) = record(
            seq, RecordKind.TASK_LIST, payload = """
            {"type":"todo/write","seq":$seq,"data":{"todos":[$items]}}
            """.trimIndent()
        )
        val turns = projectTimeline(
            listOf(
                user(1, "开始"),
                todo(2, "{\"content\":\"一\",\"status\":\"completed\"},{\"content\":\"二\",\"status\":\"in_progress\"}"),
                // 第二次是完整列表而非增量：「一」没了就是真的删了。
                todo(3, "{\"content\":\"二\",\"status\":\"completed\"}"),
            )
        )
        assertEquals(listOf("二"), turns.last().todos.map { it.content })
        assertTrue(turns.last().todos.single().completed)
    }

    @Test
    fun `会话级状态认最后写入的那一轮，而不是列表尾`() {
        // 归档按 seq 升序回放，但轮次之间会交错：t0 写完一轮、t1 插进来，
        // 之后 t0 又收到 splice。列表尾是 t1，而 t1 建立时 t0 还没有队列——
        // 从它身上读会得到一份空副本，界面上就是「队列与任务明明已落库却一片空白」。
        val turns = projectTimeline(
            listOf(
                user(1, "第一轮", turnId = "s1:t0"),
                user(2, "第二轮", turnId = "s1:t1"),
                splice(3, ids = listOf("a"), turnId = "s1:t0"),
            )
        )
        val newest = turns.maxByOrNull { it.lastSeq }
        assertEquals("s1:t0", newest?.turnId)
        assertEquals(listOf("a"), newest?.queue?.map { it.id })
        // 回归：列表尾那一轮本身确实不带队列，这就是为何不能用 lastOrNull()。
        assertTrue(turns.lastOrNull()?.queue?.isEmpty() != false)
    }

    @Test
    fun `a failed turn carries the vendor error so the phone can show it`() {
        // 2026-11-01：供应商 429 限流在桌面端会整段摊开显示，手机端以前**整段丢掉**
        // ——`panelFailure` 字段一直存在却没人写，用户看到的就是「回合莫名其妙结束了」。
        val turns = projectTimeline(
            listOf(
                user(1, "跑"),
                toolCall(2, "c1", "ops_bash", "npm test"),
                turnEndFailed(3, "RATE_LIMIT", "upstream rate-limited 4 consecutive request(s)"),
            )
        )
        val panel = turns.last().processPanel!!
        assertTrue(panel.failure.contains("RATE_LIMIT"))
        assertTrue(panel.failure.contains("upstream rate-limited"))
        // 有失败就不该再报「已完成分析」：整轮跑挂了，那句是废话。
        assertEquals(TurnState.INTERRUPTED, turns.last().turnState)
    }

    @Test
    fun `a turn that ends normally carries no failure`() {
        val turns = projectTimeline(listOf(user(1, "跑"), turnEnd(2)))
        assertEquals("", turns.last().processPanel?.failure.orEmpty())
    }


    @Test
    fun `a failure that arrives after the body still reaches the last segment`() {
        // 真实顺序：正文先到（那一段当场封口），`turn/end` 的 reason 最后到。
        // 2026-11-01 实测：记录落库了、`panelFailure` 也赋上了，但界面上什么都没有 ——
        // 因为封口之后的 failure 既进不了旧段，也开不了新段（没有步骤、没有待挂正文）。
        val turns = projectTimeline(
            listOf(
                user(1, "跑"),
                assistant(2, "这是正文"),
                turnEndFailed(3, "RATE_LIMIT", "upstream rate-limited 4 consecutive request(s)"),
            )
        )
        val segments = turns.last().segments
        assertTrue(segments.isNotEmpty())
        val lastPanel = segments.last().panel
        assertTrue(
            "失败原因要挂在最后一段上，实际=${lastPanel?.failure}",
            lastPanel?.failure.orEmpty().contains("RATE_LIMIT"),
        )
        // 交给 UI 的那一行也得带上，否则 `MessageRow` 的早退判据会把它整块跳过。
        val rows = turns.rows()
        assertTrue(rows.any { it.failure.contains("RATE_LIMIT") })
    }


    @Test
    fun aFailureOnlySegmentSurvivesMergingAndTimelineDedup() {
        // 端到端：投影 → rows() → mergeTurnPanels → timelineOf。
        // 2026-11-01 实测：投影里 `fails=1`，界面一个字都没有。逐环验证。
        val turns = projectTimeline(
            listOf(
                user(1, "跑"),
                toolCall(2, "c1", "ops_bash", "npm test"),
                assistant(3, "这是正文"),
                turnEndFailed(4, "RATE_LIMIT", "限流了"),
            )
        )
        val rows = turns.rows()
        assertEquals("rows 里要有失败行", 1, rows.count { it.failure.isNotEmpty() })
        val merged = com.dsh.mobile.v2.ui.mergeTurnPanels(rows)
        assertEquals(
            "合并之后失败行还得在（panelId=${merged.map { it.panelId }}）",
            1, merged.count { it.failure.isNotEmpty() },
        )
        val deduped = com.dsh.mobile.v2.ui.timelineOf(merged)
        assertEquals("timelineOf 之后还得在", 1, deduped.count { it.failure.isNotEmpty() })
    }


    @Test
    fun `用户自己发的图在历史里带上 image 引用`() {
        // 宿主把用户消息的图片存成 {type:"image", attachment:{attachmentId}}（durable
        // 日志里只有 id，字节要另外取）。Projector 不把它带出来，重进会话后
        // 自己发的图就只剩一行文件名——用户 2026-11-01 直报「历史里的图片不显示」。
        val turns = projectTimeline(
            listOf(
                record(
                    1, RecordKind.USER_INPUT, payload = """
                        {"type":"user/message","seq":1,"data":{"message":{"role":"user","content":[
                          {"type":"text","text":"看看这张图"},
                          {"type":"image","attachment":{"attachmentId":"att-1","name":"shot.png"}}
                        ]}}}
                    """.trimIndent()
                )
            )
        )
        val images = turns.single().userMessage?.images.orEmpty()
        assertEquals("用户消息必须带上图片引用，否则界面无图可画", 1, images.size)
        assertEquals("att-1", images.single().attachmentId)
        assertEquals("shot.png", images.single().name)
    }

    @Test
    fun `用户消息里的图是扁平 attachmentId 时也认`() {
        // 老版本日志与部分宿主事件把 attachmentId 直接摊在 part 上（不是嵌在
        // attachment 里）。两种形状都得认，否则同一条历史在某些会话里能显示、
        // 在另一些里不能，看起来像「随机丢图」。
        val turns = projectTimeline(
            listOf(
                record(
                    1, RecordKind.USER_INPUT, payload = """
                        {"type":"user/message","seq":1,"data":{"message":{"role":"user","content":[
                          {"type":"text","text":"图"},
                          {"type":"image","attachmentId":"att-flat"}
                        ]}}}
                    """.trimIndent()
                )
            )
        )
        assertEquals(listOf("att-flat"), turns.single().userMessage?.images?.map { it.attachmentId })
    }

    @Test
    fun `纯文字的用户消息不带空的图片引用`() {
        // 反过来的守卫：没有 image part 时 images 必须是空的。若这里凭空多出
        // 一个空 attachmentId，界面上每条普通消息都会多一格「图片加载失败」。
        val turns = projectTimeline(listOf(user(1, "只有文字")))
        assertTrue(turns.single().userMessage?.images?.isEmpty() != false)
    }

    @Test
    fun `earlier page boundary counts assistant bodies, not records`() {
        // 2026-11-01 用户口径：「载入更早的消息以正文输出作为单位，每次加载 30 个
        // 正文输出，包含其中的步骤信息」。
        //
        // 2026-11-03 事故：这条用例原先钉的是 SQL `kind='ASSISTANT_TEXT' ... LIMIT 1
        // OFFSET :nth`。但 `ASSISTANT_TEXT` 名不副实 —— 只含 `type=reasoning` 的
        // `assistant/message` 也记成它（生产库实测 75% 没有正文），按它数「30 段正文」
        // 实际只回来 1~6 段，用户看到的就是「翻一页全是思考，没有正文」。
        //
        // 现在的实现：写入时把「有没有正文」算成 `has_body` 列，边界就是
        // `dao.bodyPage(sessionId, beforeSeq, turns)` 的最后一条 —— 一条索引查询，
        // 没有上限、不会因为连续无正文而失效。这里测的就是那条 SQL 的语义。
        val rows = ArrayList<TimelineRecord>()
        var seq = 100L
        for (turn in 1..3) {
            rows += user(seq++, "第 $turn 问")
            val call = seq++
            rows += record(
                call, RecordKind.STEP_TOOL,
                payload = """{"type":"tool/call","seq":$call,"data":{"callId":"c$turn","name":"bash"}}"""
            )
            // 每条正文前都插一条纯思考记录 —— 老实现会把它也数成「一段正文」。
            val think = seq++
            rows += record(
                think, RecordKind.ASSISTANT_TEXT,
                payload = """{"type":"assistant/message","seq":$think,"data":{"message":{"role":"assistant","content":[{"type":"reasoning","text":"想一想"}]}}}"""
            )
            rows += assistant(seq++, "第 $turn 答")
            rows += record(seq++, RecordKind.TURN_END)
        }
        // 三段的正文 seq：103 / 108 / 113（每轮 5 条：用户、工具、纯思考、正文、收尾）。
        // 等价于 `bodyPage(beforeSeq = 9_999, limit = 2)`。
        val bodies = rows
            .filter { it.seq < 9_999 && it.hasBody() }
            .sortedByDescending { it.seq }
            .take(2)
        val boundary = bodies.last().seq
        // 边界必须落在倒数第 2 段**正文**上（108），而不是它前面那条纯思考（107）。
        assertEquals(108L, boundary)
        // 从边界往上取，这一段里必须正好有 2 段真正文、且步骤信息也带进来。
        val page = rows.filter { it.seq >= boundary && it.seq < 9_999 }
        assertEquals(2, page.count { it.kind == RecordKind.ASSISTANT_TEXT && it.hasBody() })
        assertTrue(page.any { it.kind == RecordKind.STEP_TOOL })
    }

    @Test
    fun `翻页边界跳过没有正文的记录`() {
        // 更狠的形状：尾部连续 40 条纯思考，真正的正文在更早。老实现按 kind 数，
        // 第 30 条仍是个空壳 ⇒ 边界停在空壳上，用户翻完一页看不到一行正文。
        //
        // 这也是「倒扫 + 扫描上限」打穿的形状（旧实现 64 条上限）。`has_body`
        // 是索引条件，40 条、400 条都不影响结果。
        val rows = ArrayList<TimelineRecord>()
        rows += assistant(1, "很久以前的正文")
        for (i in 2..41) {
            rows += record(
                i.toLong(), RecordKind.ASSISTANT_TEXT,
                payload = """{"type":"assistant/message","data":{"message":{"content":[{"type":"reasoning","text":"想 $i"}]}}}"""
            )
        }
        // 要 1 段正文：必须一路跳过 40 条空壳，落到 seq=1。
        val one = rows.filter { it.hasBody() }.sortedByDescending { it.seq }.take(1)
        assertEquals(1L, one.last().seq)
        // 要 2 段正文：库里只有 1 段 ⇒ 取回不足 turns 条，调用方回落到 `earlier`。
        val two = rows.filter { it.hasBody() }.sortedByDescending { it.seq }.take(2)
        assertEquals("库里只有 1 段正文，凑不出第 2 段", 1, two.size)
    }

    // ============================================================
    // 流式正文：多段轮次不许被上一段压掉
    // ============================================================

    @Test
    fun `一轮里第二段正文在流式时不会被第一段抑制`() {
        // 事故形态：判据是 `assistantBlocks.any { it.text.length >= streaming.length }`，
        // 遍历**所有**段。多段轮次（思考 → 正文 A → 工具 → 正文 B）里，正文 A 已经
        // 落定且有 100 个字，正文 B 刚开始流式只有 20 个字 —— 命中 A 就整段抑制，
        // 用户看到「卡住 → 突然整段跳现」。
        val records = listOf(
            user(1, "开始"),
            assistant(2, "第一段正文已经落定，长度比正在流式的那段长得多。"),
            toolCall(3, "c1", "bash", "ls"),
            toolResult(4, "c1", "ok"),
        )
        val streaming = "第二段"
        val turns = project(
            records,
            Overlay(streamingText = streaming, streamingTurnId = "s1:t1"),
        )
        val texts = turns.single().assistantBlocks.map { it.text }
        assertTrue(
            "第二段正在流式，必须出现在投影里；实际段落：$texts",
            texts.any { it == streaming },
        )
        assertEquals("第一段不能被覆盖掉", 2, texts.size)
    }

    @Test
    fun `流式文本是最后一段的前缀时不重复画`() {
        // 反向钉子：落定之后 delta 可能还在路上。此时最后一段以流式文本开头且更长，
        // 说明流式只是它的前缀，不能画成两条。
        val records = listOf(
            user(1, "开始"),
            assistant(2, "完整的正文已经落定了。"),
        )
        val turns = project(
            records,
            Overlay(streamingText = "完整的正文", streamingTurnId = "s1:t1"),
        )
        val texts = turns.single().assistantBlocks.map { it.text }
        assertEquals("前缀重复不能画两条；实际：$texts", listOf("完整的正文已经落定了。"), texts)
    }

    @Test
    fun `内容不同但更长的段落不能压掉正在流式的新段`() {
        // 另一个方向：最后一段够长，但和流式文本没有前缀关系 —— 那是两段不同的话。
        val records = listOf(
            user(1, "开始"),
            assistant(2, "完全不相干的另一段话，只是恰巧比较长而已。"),
        )
        val turns = project(
            records,
            Overlay(streamingText = "新的一段", streamingTurnId = "s1:t1"),
        )
        val texts = turns.single().assistantBlocks.map { it.text }
        assertTrue("不是前缀关系就该画出来；实际：$texts", texts.any { it == "新的一段" })
    }

}
