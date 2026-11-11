package com.dsh.mobile.v2.ui

import com.dsh.mobile.v2.data.InteractionLedgerEntry
import com.dsh.mobile.v2.data.LedgerState
import com.dsh.mobile.v2.data.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审批/提问卡片的跨重启编解码。
 *
 * <p>为什么值得单测：这张卡片是**唯一**跨进程活着的交互状态，而它的身份键
 * （`interactionId`）一旦不稳，用户就会看到「点过的审批又回来了」—— 这正是 v1 被
 * 报了很多次的那个 bug。所以这里的断言集中在两件事：身份键必须由 `sessionId` 与
 * 稳定的东西（seq 或 eventId）决定，以及一张卡解码回来之后每个字段都还在。
 */
class InteractionCodecTest {

    private fun approval(eventId: String = "evt-1") = Model.Approval().apply {
        this.eventId = eventId
        agentId = "sess-1"
        toolName = "ops_bash"
        callId = "call-9"
        reason = "需要写入工作区"
        displayReason = "需要写入工作区"
    }

    @Test
    fun `an approval round-trips through the ledger payload`() {
        val entry = InteractionCodec.encode("sess-1", approval(), seq = 7, now = 1000)!!
        assertEquals("APPROVAL", entry.kind)
        assertEquals("sess-1:7", entry.interactionId)
        assertEquals(LedgerState.PENDING, entry.state)

        val back = InteractionCodec.decode(entry) as Model.Approval
        assertEquals("evt-1", back.eventId)
        assertEquals("sess-1", back.agentId)
        assertEquals("ops_bash", back.toolName)
        assertEquals("call-9", back.callId)
        assertEquals("需要写入工作区", back.reason)
        assertEquals("需要写入工作区", back.displayReason)
        assertFalse(back.answered)
    }

    @Test
    fun `questions keep every option and its draft flags`() {
        val card = Model.QuestionCard().apply {
            eventId = "evt-2"
            agentId = "sess-1"
            callId = "call-1"
            questions.add(
                Model.Question().apply {
                    id = "q1"
                    question = "选哪个？"
                    detail = "细节"
                    header = "部署"
                    multiSelect = true
                    options.add(Model.QuestionOption().apply { label = "A"; description = "第一个" })
                    options.add(Model.QuestionOption().apply { label = "B"; description = "第二个" })
                }
            )
        }
        val entry = InteractionCodec.encode("sess-1", card, seq = 12, now = 2000)!!
        assertEquals("QUESTION", entry.kind)
        assertEquals("sess-1:12", entry.interactionId)

        val back = InteractionCodec.decode(entry) as Model.QuestionCard
        assertEquals("evt-2", back.eventId)
        assertEquals(1, back.questions.size)
        val q = back.questions[0]
        assertEquals("q1", q.id)
        assertEquals("选哪个？", q.question)
        assertEquals("细节", q.detail)
        assertEquals("部署", q.header)
        assertTrue(q.multiSelect)
        assertEquals(listOf("A", "B"), q.options.map { it.label })
        assertEquals(listOf("第一个", "第二个"), q.options.map { it.description })
    }

    @Test
    fun `a card with no seq falls back to the event id and never to a timestamp`() {
        // `$events` 的 waterfall 不带时间线 seq。此时身份键必须退到 eventId：
        // 宿主重投同一张卡时 eventId 不变，用时间戳就会每次都变成新卡。
        val a = InteractionCodec.encode("sess-1", approval(), seq = 0, now = 1_000)!!
        val b = InteractionCodec.encode("sess-1", approval(), seq = 0, now = 9_999)!!
        assertEquals("sess-1:evt-1", a.interactionId)
        assertEquals(a.interactionId, b.interactionId)
        assertFalse(a.interactionId.contains("1000"))
        assertFalse(a.interactionId.contains("9999"))
    }

    @Test
    fun `the same interaction keeps one identity across retransmissions`() {
        // 同样是 seq=7 的重投：id 必须一致，否则 Room 里会堆出两行「同一张卡」，
        // 点掉其中一行之后另一行还会被当作待答条目回放出来。
        val first = InteractionCodec.encode("sess-1", approval(), seq = 7, now = 1)!!
        val again = InteractionCodec.encode("sess-1", approval(), seq = 7, now = 500)!!
        assertEquals(first.interactionId, again.interactionId)
    }

    @Test
    fun `an empty event id is refused instead of stored under a blank key`() {
        assertNull(InteractionCodec.encode("sess-1", approval(eventId = ""), seq = 1, now = 1))
        assertNull(InteractionCodec.encode("", approval(), seq = 1, now = 1))
    }

    @Test
    fun `a truncated or malformed payload decodes to null rather than throwing`() {
        val broken = InteractionLedgerEntry(
            sessionId = "sess-1",
            interactionId = "sess-1:1",
            kind = "APPROVAL",
            state = LedgerState.PENDING,
            payloadJson = "{\"eventId\":",
            updatedAt = 1,
        )
        assertNull(InteractionCodec.decode(broken))
        assertNull(InteractionCodec.decode(broken.copy(kind = "SOMETHING_NEW")))
    }

    @Test
    fun `a settlement already recorded is not overwritten by a retransmission`() {
        // 这条断言保护的是 `TimelineStore.noteInteraction` 的判据本身：
        // 已结算的条目不能再被一条 PENDING 覆盖回去。
        val settled = InteractionLedgerEntry(
            sessionId = "sess-1",
            interactionId = "sess-1:7",
            kind = "APPROVAL",
            state = LedgerState.RESOLVED,
            payloadJson = "{}",
            updatedAt = 1,
        )
        assertFalse(settled.acceptsReplay())
        assertTrue(settled.copy(state = LedgerState.PENDING).acceptsReplay())
    }
}
