package com.dsh.mobile.v2.ui

import com.dsh.mobile.v2.data.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 待答卡片账本。
 *
 * 背景：宿主在每次建立 `$events` 新连接时会把所有没结算的 waterfall 重投一遍。
 * 旧实现每进一次会话就重开那条流，于是同一张卡跟着重来一次——用户看到的是
 * 「一直在弹审批」。这里的用例锁住三条：重投不重复、点过就静默、失败能回来。
 */
class InteractionLedgerTest {

    private fun approval(eventId: String, agentId: String = "s1") = Model.Approval().apply {
        this.eventId = eventId
        this.agentId = agentId
        this.toolName = "bash"
        this.callId = "call-$eventId"
        this.reason = "需要写工作区外的目录"
    }

    private fun question(eventId: String, agentId: String = "s1") = Model.QuestionCard().apply {
        this.eventId = eventId
        this.agentId = agentId
    }

    @Test
    fun repeatedDeliveryKeepsOneCard() {
        val ledger = InteractionLedger()
        ledger.note(approval("e1"))
        ledger.note(approval("e1"))
        ledger.note(approval("e1"))
        assertEquals(1, ledger.approvalsFor("s1").size)
    }

    @Test
    fun answeredCardIsNotReplayedOnReconnect() {
        val ledger = InteractionLedger()
        ledger.note(approval("e1"))
        assertTrue(ledger.isAnswering("e1").not())

        ledger.markAnswering("e1")

        assertTrue(ledger.isAnswering("e1"))
        assertEquals(0, ledger.approvalsFor("s1").size)
    }

    @Test
    fun failedAnswerLetsTheCardComeBack() {
        val ledger = InteractionLedger()
        ledger.note(approval("e1"))
        ledger.markAnswering("e1")
        assertEquals(0, ledger.approvalsFor("s1").size)

        // 回答没送到：撤销记账，下一次重投还得能看见它，否则用户以为答过了。
        ledger.forget("e1")

        assertFalse(ledger.isAnswering("e1"))
        assertEquals(1, ledger.approvalsFor("s1").size)
    }

    @Test
    fun cancelDropsTheCardForGood() {
        val ledger = InteractionLedger()
        ledger.note(approval("e1"))
        ledger.cancel("e1")
        assertEquals(0, ledger.approvalsFor("s1").size)
        // 结算过的 eventId 再被重投也只是陈旧帧。
        assertTrue(ledger.isAnswering("e1"))
    }

    @Test
    fun replayOnlyHandsOutItsOwnSessionCards() {
        val ledger = InteractionLedger()
        ledger.note(approval("e1", agentId = "s1"))
        ledger.note(approval("e2", agentId = "s2"))

        assertEquals(listOf("e1"), ledger.approvalsFor("s1").map { it.eventId })
        assertEquals(listOf("e2"), ledger.approvalsFor("s2").map { it.eventId })
        assertEquals(0, ledger.approvalsFor("s3").size)
    }

    @Test
    fun questionCardsFollowTheSameRules() {
        val ledger = InteractionLedger()
        ledger.note(question("q1"))
        ledger.note(question("q1"))
        assertEquals(1, ledger.questionsFor("s1").size)

        ledger.markAnswering("q1")
        assertEquals(0, ledger.questionsFor("s1").size)

        ledger.forget("q1")
        assertEquals(1, ledger.questionsFor("s1").size)
    }

    @Test
    fun emptyEventIdIsNeverConsideredAnswering() {
        val ledger = InteractionLedger()
        // 宿主没给 eventId 时不能因为空串相等就把所有卡都当成已答。
        assertFalse(ledger.isAnswering(""))
        ledger.markAnswering("")
        assertFalse(ledger.isAnswering(""))
    }

    @Test
    fun clearDropsEverything() {
        val ledger = InteractionLedger()
        ledger.note(approval("e1"))
        ledger.note(question("q1"))
        ledger.markAnswering("e2")
        ledger.clear()
        assertEquals(0, ledger.approvalsFor("s1").size)
        assertEquals(0, ledger.questionsFor("s1").size)
        assertFalse(ledger.isAnswering("e2"))
    }
}
