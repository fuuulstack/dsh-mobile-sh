package com.dsh.mobile.v2.ui

import com.dsh.mobile.v2.data.Model
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 输入框上方那排图标胶囊：**显示哪几颗**、以及子智能体模式怎么显示。
 *
 * 用户口径（2026-11-08）：「如果同时有任务列表和子智能体以及后台任务就并排显示
 * 多个胶囊，胶囊只要图标，不要文字，点开可以查看」。顺序与「有没有内容才显示」
 * 这两条都是产品契约，不是实现细节，所以脱离 Compose 钉在这里。
 */
class CapsuleRowTest {

    private fun todo(text: String) = Model.Todo(content = text)
    private fun job(id: String) = Model.Job(id = id, label = "跑测试")
    private fun subagent(id: String) = Model.Subagent(id = id, label = "审查")

    @Test
    fun `nothing to show means no capsules at all`() {
        assertEquals(
            "三份数据都空时必须一颗都不画 —— 画一排点开什么都没有的图标比不画更糟",
            emptyList<CapsuleKind>(),
            capsuleKinds(emptyList(), emptyList(), emptyList()),
        )
    }

    @Test
    fun `capsules come out in a fixed order`() {
        assertEquals(
            "三样都有时按 任务 → 后台任务 → 子智能体 并排",
            listOf(CapsuleKind.TASKS, CapsuleKind.JOBS, CapsuleKind.SUBAGENTS),
            capsuleKinds(listOf(todo("a")), listOf(job("j1")), listOf(subagent("s1"))),
        )
        assertEquals(
            "只有后台任务时不能让它的位置跟着别的空槽漂移",
            listOf(CapsuleKind.JOBS),
            capsuleKinds(emptyList(), listOf(job("j1")), emptyList()),
        )
        assertEquals(
            "只有子智能体时同理",
            listOf(CapsuleKind.SUBAGENTS),
            capsuleKinds(emptyList(), emptyList(), listOf(subagent("s1"))),
        )
    }

    @Test
    fun `subagent modes are named for humans and unknown ones survive`() {
        assertEquals("一次性", subagentModeLabel("one-shot"))
        assertEquals("外部", subagentModeLabel("external"))
        assertEquals("可续", subagentModeLabel("continuable"))
        assertEquals("未知", subagentModeLabel("unknown"))
        assertEquals("字段缺失不是「未知模式」，别把两种情况混成一句", "模式未知", subagentModeLabel(""))
        assertEquals(
            "认不出的模式要原样显示：折叠成「其他」等于对用户说谎",
            "team-lead",
            subagentModeLabel("team-lead"),
        )
    }

    @Test
    fun `a finished subagent no longer holds the capsule up`() {
        // 用户 2026-11-10：「实测有很多已经结束的子代理导致子代理胶囊在显示」。
        // subagentCatalog 是**派过的名单**，不是「在跑的名单」；一条长会话派过十几个
        // 之后，它们全都结算了，名单却还在长。
        val live = subagent("s-live").apply { running = true }
        val done = subagent("s-done").apply { running = false }
        assertEquals(
            "只要还有一个在跑，胶囊就得留着",
            listOf(CapsuleKind.SUBAGENTS),
            capsuleKinds(emptyList(), emptyList(), listOf(live, done)),
        )
        assertEquals(
            "全跑完了，胶囊跟着消失 —— 不能因为名单非空就亮着",
            emptyList<CapsuleKind>(),
            capsuleKinds(emptyList(), emptyList(), listOf(done)),
        )
        assertEquals("清单也只列在跑的那些", listOf(live), liveSubagents(listOf(live, done)))
    }

    @Test
    fun `a subagent with no known state is not treated as finished`() {
        // 老插件不发 running（字段缺失）。读成 false 等于凭空宣布它跑完了，
        // 升级 App 的人会静默少一块功能 —— 缺席按「还在」处理，跟没这功能之前一样。
        val unknown = subagent("s-1")
        assertEquals("缺席不是 false", null, unknown.running)
        assertEquals(listOf(unknown), liveSubagents(listOf(unknown)))
        assertEquals(
            "因此只凭缺席不能把胶囊藏掉",
            listOf(CapsuleKind.SUBAGENTS),
            capsuleKinds(emptyList(), emptyList(), listOf(unknown)),
        )
    }
}
