package com.dsh.mobile.v2.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这条记录有没有正文」的判据（[TimelineRecord.hasBody]）。
 *
 * <p>2026-11-02 用户实测：「preview 只保留正文作为预览，思考过程现在会导致预览变空白」。
 * 根因是 `ASSISTANT_TEXT` 名不副实 —— 只含 `type=reasoning` 的 `assistant/message`
 * 也记成它。生产库实测 75% 的 `ASSISTANT_TEXT` 没有正文，27 个会话里 7 个的
 * 「最新一条」正是这种。
 *
 * <p>**为什么不再测倒扫**：上一版靠 `scanAssistantBody` 分批倒扫 + 64 条上限兜底，
 * 2026-11-03 模拟器实测造 200 条纯思考就把上限打穿、列表预览整行消失。
 * 现在改成写入时算好 `has_body` 落列，读侧是 `dao.latestBody` 索引点查 ——
 * 没有上限、没有 N 次查询。所以这里测的是**判据本身**，而判据错了整个机制就错了。
 *
 * <p>[FakeStore.latestAssistant] 那种「取最后一条 ASSISTANT_TEXT」的假实现按老语义
 * 写，**用它测这条会假绿** —— 所以这里直接测纯函数，自己喂记录。
 */
class PreviewScanTest {

    private fun body(seq: Long, text: String) = TimelineRecord(
        sessionId = "s1",
        seq = seq,
        prevSeq = seq - 1,
        turnId = "s1:t1",
        kind = RecordKind.ASSISTANT_TEXT,
        payloadJson = """{"type":"assistant/message","data":{"message":{"content":[{"type":"text","text":"$text"}]}}}""",
    )

    /** 宿主真实形状：只有 reasoning，没有 text。 */
    private fun reasoning(seq: Long, text: String = "想一下") = TimelineRecord(
        sessionId = "s1",
        seq = seq,
        prevSeq = seq - 1,
        turnId = "s1:t1",
        kind = RecordKind.ASSISTANT_TEXT,
        payloadJson = """{"type":"assistant/message","data":{"message":{"content":[{"type":"reasoning","text":"$text"}]}}}""",
    )

    @Test
    fun `只有思考的记录没有正文`() {
        assertFalse(reasoning(2).hasBody())
    }

    @Test
    fun `带 text 部分的记录有正文`() {
        assertTrue(body(1, "答一句").hasBody())
    }

    @Test
    fun `思考与正文混在同一条时算有正文`() {
        val mixed = TimelineRecord(
            sessionId = "s1",
            seq = 3,
            prevSeq = 2,
            turnId = "s1:t1",
            kind = RecordKind.ASSISTANT_TEXT,
            payloadJson = """{"type":"assistant/message","data":{"message":{"content":[""" +
                """{"type":"reasoning","text":"想一想"},""" +
                """{"type":"text","text":"答案"}]}}}""",
        )
        assertTrue(mixed.hasBody())
    }

    @Test
    fun `思考正文里出现 type text 字面量也不会被误判成正文`() {
        // 这条是「用 `payloadJson.contains("\"type\":\"text\"")` 做快判」的假阳性样本：
        // 字面量出现在 reasoning 的**文本内容**里，不是结构字段。假阳性的后果是
        // 预览又变空白（挑中一条其实没有正文的记录）—— 正是这次要修的 bug。
        val tricky = reasoning(4, text = """代码里要写 {"type":"text"} 才能拿到正文""")
        assertFalse("字面量不能当结构判据", tricky.hasBody())
        assertEquals("", tricky.previewHead())
    }

    @Test
    fun `存根记录不会被当成无正文跳过`() {
        // 存根（正文被离载）的 payloadJson 里没有正文，但 stubPreviewHead 有。
        // 若按 payloadJson 判空，会话会显示不了预览；这也是历史事故之一。
        val stub = TimelineRecord(
            sessionId = "s1",
            seq = 9,
            prevSeq = 8,
            turnId = "s1:t1",
            kind = RecordKind.ASSISTANT_TEXT,
            payloadJson = """{"type":"stub/payload","blobId":"b1"}""",
            isOversizedStub = true,
            blobId = "b1",
            blobSize = 999_999,
            stubPreviewHead = "很长的那段正文开头",
        )
        assertTrue(stub.hasBody())
        assertEquals("很长的那段正文开头", stub.previewHead())
    }

    @Test
    fun `没有预览头的存根算无正文`() {
        val stub = TimelineRecord(
            sessionId = "s1",
            seq = 9,
            prevSeq = 8,
            turnId = "s1:t1",
            kind = RecordKind.ASSISTANT_TEXT,
            payloadJson = """{"type":"stub/payload","blobId":"b1"}""",
            isOversizedStub = true,
            blobId = "b1",
            blobSize = 999_999,
        )
        assertFalse(stub.hasBody())
    }

    @Test
    fun `非助手正文的 kind 一律不算正文`() {
        // `has_body` 只对 ASSISTANT_TEXT 有意义。用户消息、工具调用、思考步骤
        // 即使 payload 里带 `"type":"text"` 也不能进预览与翻页边界。
        val user = TimelineRecord(
            sessionId = "s1",
            seq = 5,
            prevSeq = 4,
            turnId = "s1:t1",
            kind = RecordKind.USER_INPUT,
            payloadJson = """{"type":"user/message","data":{"content":[{"type":"text","text":"我问"}]}}""",
        )
        assertFalse(user.hasBody())
    }

    @Test
    fun `空 payload 不会崩也不会有正文`() {
        val empty = TimelineRecord(
            sessionId = "s1",
            seq = 6,
            prevSeq = 5,
            turnId = "s1:t1",
            kind = RecordKind.ASSISTANT_TEXT,
            payloadJson = "",
        )
        assertFalse(empty.hasBody())
        assertEquals("", empty.previewHead())
    }

    @Test
    fun `连续 200 条纯思考之后再早的正文仍能被选中`() {
        // 这就是打穿旧 64 条上限的形状。`has_body` 是索引条件，条数不影响结果。
        val rows = ArrayList<TimelineRecord>()
        rows.add(body(1, "很久以前的正文"))
        for (seq in 2L..201L) rows.add(reasoning(seq))
        val bodies = rows.filter { it.hasBody() }
        assertEquals("只有一条记录算正文", 1, bodies.size)
        assertEquals(1L, bodies.single().seq)
        assertEquals("很久以前的正文", bodies.single().previewHead())
    }
}
