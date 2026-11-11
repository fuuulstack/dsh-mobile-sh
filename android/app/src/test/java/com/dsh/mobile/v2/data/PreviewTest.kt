package com.dsh.mobile.v2.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话列表首行预览的单测。
 *
 * 这条规则来自 2026-10-28 的用户要求：首行要展示**实际拉取到的消息**，思考、
 * 工具调用、用户自己说的话都不算。曾经那条「插件算 snippet → 中继存一份 →
 * 手机收一次」的旁路已经拆掉，所以这里只需验证「从本地消息里挑哪一条」。
 */
class PreviewTest {

    private fun assistant(text: String, panelId: String = "") =
        Model.Message.assistant(text).apply { this.panelId = panelId }

    private fun user(text: String) = Model.Message.user(text)

    @Test
    fun `没有消息时预览为空`() {
        assertEquals("", firstLinePreview(emptyList()))
    }

    @Test
    fun `取最后一条助手正文的首行`() {
        val messages = listOf(
            assistant("第一轮回答"),
            user("再来一次"),
            assistant("第二轮第一行\n第二轮第二行"),
        )
        assertEquals("第二轮第一行", firstLinePreview(messages))
    }

    @Test
    fun `用户说的话不算`() {
        assertEquals("助手说的", firstLinePreview(listOf(assistant("助手说的"), user("用户说的"))))
    }

    @Test
    fun `只有思考步骤没有正文的消息不算`() {
        val thinkingOnly = Model.Message.pending().apply {
            running = false
            steps.add(Model.Step.thinking("让我想想"))
        }
        assertEquals("真正的回复", firstLinePreview(listOf(assistant("真正的回复"), thinkingOnly)))
    }

    @Test
    fun `处理详情面板不算`() {
        assertEquals("真正的回复", firstLinePreview(listOf(assistant("真正的回复"), assistant("", panelId = "p1"))))
    }

    @Test
    fun `首行是空行时往下找第一行有内容的`() {
        assertEquals("正文", firstLinePreview(listOf(assistant("\n\n  正文  \n第二行"))))
    }

    @Test
    fun `长句子原样返回——省略交给界面`() {
        val long = "很".repeat(200)
        assertEquals(long, firstLinePreview(listOf(assistant(long))))
    }

    @Test
    fun `只有用户消息时预览为空`() {
        assertEquals("", firstLinePreview(listOf(user("你好"))))
    }

    // ============================================================
    // Room 里的预览头（TimelineRecord.previewHead）
    // ============================================================

    private fun assistantRecord(payload: String, stub: Boolean = false) = TimelineRecord(
        sessionId = "s1",
        seq = 1,
        prevSeq = 0,
        turnId = "s1:t0",
        kind = RecordKind.ASSISTANT_TEXT,
        payloadJson = payload,
        isOversizedStub = stub,
    )

    /**
     * 2026-10-31 真机实测回归：原实现写成 `if (!isOversizedStub) "" else ...`，
     * 于是正常记录一律返回空串。现场表现就是“每次重开 App，所有会话的
     * snippet 一起消失”——因为列表在冷启动时只能靠这条路径重算。
     */
    @Test
    fun `存在正常的助手正文时预览头要有内容`() {
        val payload = """{"type":"assistant/message","seq":1,"data":{"turn":0,"message":{"role":"assistant","content":[{"type":"reasoning","text":"想想"},{"type":"text","text":"第一行
第二行"}]}}}"""
        val head = assistantRecord(payload).previewHead()
        assertTrue("存在正常副本时不能返回空串", head.isNotEmpty())
        assertTrue("思考块不能冒充正文", head.contains("第一行"))
    }

    @Test
    fun `存根优先用生产者留下的 previewHead，而不是把 JSON 信封当预览`() {
        // 事故形态：存根的 payloadJson 是 `{"type":"stub/payload","blobId":…}` ——
        // 里面**没有正文**。旧实现直接 `payloadJson.take(2048)`，于是「最后一条回复
        // 超过 256 KiB」的会话在列表首行显示的是这段 JSON 原文。
        val stubPayload = """{"type":"stub/payload","blobId":"s1:1","blobSize":524288}"""
        val head = assistantRecord(stubPayload, stub = true)
            .copy(stubPreviewHead = """{"type":"tool/result","data":{"output":"真正的正文开头""")
            .previewHead()
        assertTrue("必须用 previewHead", head.contains("真正的正文开头"))
        assertFalse("绝不能把 stub 信封当预览", head.contains("stub/payload"))
    }

    @Test
    fun `老存根没有 previewHead 时退回 payload 而不是空白`() {
        // 兼容：升级前落盘的存根没有这一列（默认空串）。空串会让列表彻底没有预览，
        // 退回 payload 至少还能看出「这条内容很大」。
        val stubPayload = """{"type":"stub/payload","blobId":"s1:1"}"""
        val head = assistantRecord(stubPayload, stub = true).previewHead()
        assertTrue("不能返回空串", head.isNotEmpty())
    }

    @Test
    fun `补回正文后预览改用正文`() {
        // `fillStub` 会把 payload 换成真正文并清掉 stub 标志；此时 previewHead 走
        // 正常解析路径，不再看 stubPreviewHead（它只对存根生效）。
        val filled = assistantRecord(
            """{"type":"assistant/message","data":{"message":{"content":[{"type":"text","text":"补回来的正文"}]}}}""",
            stub = false,
        ).copy(stubPreviewHead = "过期的头部")
        assertEquals("补回来的正文", filled.previewHead())
    }

    @Test
    fun `payload 不可读时不崩、返回空串`() {
        assertEquals("", assistantRecord("这不是 JSON").previewHead())
        assertEquals("", assistantRecord("").previewHead())
    }
}
