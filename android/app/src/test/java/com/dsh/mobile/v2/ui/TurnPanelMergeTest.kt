package com.dsh.mobile.v2.ui

import com.dsh.mobile.v2.data.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回合合并（[mergeTurnPanels]）的单测。
 *
 * 规则来自 2026-10-28 的真机反馈：一轮回复里的思考与工具步骤应当只占**一行**
 * 「正在分析请求 / 已完成 N 个操作」，点开才看逐条；而不是每个 step 各占一行
 * 「已处理 1 个操作」。真宿主一轮 7~9 个 step，所以这条不是锦上添花。
 *
 * 合并只在渲染层做（服务端与本机状态都保持每 step 一块），所以这里只验纯函数：
 * 谁的步骤并到谁、工具下标有没有顺移、进行中与页脚取哪一块。
 */
class TurnPanelMergeTest {

    private fun panel(
        panelId: String,
        turn: Long,
        order: Double,
        actions: Int = 1,
        running: Boolean = false,
        footnote: Boolean = false,
        thinking: String = "想一想",
    ): Model.Message {
        val message = Model.Message(role = Model.Role.ASSISTANT).apply {
            this.panelId = panelId
            this.turn = turn
            this.order = order
            this.running = running
            this.footnote = footnote
            this.startedAt = 1000
            this.endedAt = 2000
        }
        repeat(actions) { i ->
            message.actions.add(Model.ToolAction(name = "tool-$i", detail = "d$i"))
            message.steps.add(Model.Step(kind = Model.Step.TOOL, actionIndex = i))
            message.steps.add(Model.Step.thinking(thinking))
        }
        return message
    }

    private fun assistantText(seq: Long, text: String) = Model.Message.assistant(text).apply { this.seq = seq }

    @Test
    fun `同一回合的相邻面板并成一块`() {
        val rows = mergeTurnPanels(
            listOf(
                panel("p1", turn = 1, order = 5.75),
                panel("p2", turn = 1, order = 6.75),
                panel("p3", turn = 1, order = 7.75),
            ),
        )
        assertEquals(1, rows.size)
        assertEquals(3, rows[0].actions.size)
        // 抽屉里要能逐条展开：3 个工具 + 3 段思考。
        assertEquals(6, rows[0].steps.size)
    }

    @Test
    fun `工具步骤的下标合并后仍然指向自己的动作`() {
        val rows = mergeTurnPanels(
            listOf(panel("p1", 1, 5.75), panel("p2", 1, 6.75)),
        )
        val toolSteps = rows[0].steps.filter { it.kind == Model.Step.TOOL }
        assertEquals(listOf(0, 1), toolSteps.map { it.actionIndex })
        assertEquals("tool-0", rows[0].actions[toolSteps[0].actionIndex].name)
        assertEquals("tool-0", rows[0].actions[toolSteps[1].actionIndex].name)
    }

    @Test
    fun `中间夹着正文就不合并`() {
        val rows = mergeTurnPanels(
            listOf(
                panel("p1", 1, 5.75),
                assistantText(6, "先说一句"),
                panel("p2", 1, 6.75),
            ),
        )
        // 「思考 → 回答 → 继续思考」是真实节奏，跨过正文合并会把那句回答挪到最后。
        assertEquals(3, rows.size)
        assertEquals("p1", rows[0].panelId)
        assertEquals("先说一句", rows[1].text)
        assertEquals("p2", rows[2].panelId)
    }

    @Test
    fun `不同回合的面板不合并`() {
        val rows = mergeTurnPanels(
            listOf(panel("p1", 1, 5.75), panel("p2", 2, 9.75)),
        )
        assertEquals(2, rows.size)
    }

    @Test
    fun `进行中取最后一块的状态`() {
        val rows = mergeTurnPanels(
            listOf(
                panel("p1", 1, 5.75, running = false).apply { status = "正在调用工具" },
                panel("p2", 1, 6.75, running = true).apply { status = "正在分析请求" },
            ),
        )
        assertTrue(rows[0].running)
        assertEquals("正在分析请求", rows[0].status)
    }

    @Test
    fun `全部收尾时不算进行中`() {
        val rows = mergeTurnPanels(
            listOf(
                panel("p1", 1, 5.75, running = false),
                panel("p2", 1, 6.75, running = false),
            ),
        )
        assertFalse(rows[0].running)
    }

    @Test
    fun `页脚只要有一块带就保留`() {
        val rows = mergeTurnPanels(
            listOf(
                panel("p1", 1, 5.75, footnote = false),
                panel("p2", 1, 6.75, footnote = true),
            ),
        )
        assertTrue(rows[0].footnote)
    }

    @Test
    fun `单块面板原样返回`() {
        val only = panel("p1", 1, 5.75)
        val rows = mergeTurnPanels(listOf(only))
        assertEquals(1, rows.size)
        assertTrue(only === rows[0])
    }

    @Test
    fun `没有回合归属的旧面板不参与合并`() {
        // turn = -1 是改造前落盘的面板，位置还在但不知道属于哪一轮。
        val rows = mergeTurnPanels(
            listOf(panel("p1", -1, 5.75), panel("p2", -1, 6.75)),
        )
        assertEquals(2, rows.size)
    }

    @Test
    fun `普通消息不合并`() {
        val rows = mergeTurnPanels(listOf(assistantText(1, "a"), assistantText(2, "b")))
        assertEquals(2, rows.size)
        assertEquals(listOf("a", "b"), rows.map { it.text })
    }
}
