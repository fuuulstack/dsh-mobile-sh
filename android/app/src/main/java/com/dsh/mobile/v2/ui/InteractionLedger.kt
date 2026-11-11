package com.dsh.mobile.v2.ui

import com.dsh.mobile.v2.data.Model

/**
 * 待答审批/提问卡片的本地账本。
 *
 * 为什么需要它：`$events` 是**开机就建、一直不断**的一条流，而宿主在**每次建立新连接**
 * 时会把所有仍未结算的 waterfall 重投一遍（`startRemoteEvent` 末尾遍历
 * `pendingRemoteEvents` 逐个 deliver）。旧实现每进一次会话就重开那条流，于是同一张
 * 没结算的请批会跟着重来一次，用户看到的就是「一直在弹审批」。
 *
 * 现在 `$events` 只在开机时建一条，卡片落在本账本里，打开会话时 [replay] 回放：
 * - [note] 记下新卡片（同一 eventId 只留一份）；
 * - [markAnswering] 用户点过之后就静默丢弃重投的陈旧帧；
 * - [forget] 回答没送达时撤销，卡片还能重新出现；
 * - [cancel] 宿主发来 cancel 帧时清掉。
 *
 * 纯 Kotlin，无 Android 依赖，可直接单测。
 */
class InteractionLedger {
    private val approvals = LinkedHashMap<String, Model.Approval>()
    private val questions = LinkedHashMap<String, Model.QuestionCard>()

    /** 点过或已结算过的 eventId。重投回来的同一张卡不再画、也不再发通知。 */
    private val answered = HashSet<String>()

    fun note(approval: Model.Approval) {
        approvals[approval.eventId] = approval
    }

    fun note(card: Model.QuestionCard) {
        questions[card.eventId] = card
    }

    /** 已经被点过（或已结算）：这张卡再来就是陈旧重投。 */
    fun isAnswering(eventId: String): Boolean =
        eventId.isNotEmpty() && answered.contains(eventId)

    fun markAnswering(eventId: String) {
        if (eventId.isNotEmpty()) answered.add(eventId)
    }

    /** 回答没送达：撤销记账，让这张卡还能重新出现。 */
    fun forget(eventId: String) {
        answered.remove(eventId)
    }

    /** 宿主发来 cancel 帧：卡片作废，不必再记着它。 */
    fun cancel(eventId: String) {
        markAnswering(eventId)
        approvals.remove(eventId)
        questions.remove(eventId)
    }

    /** 清空（退出登录/换主机）。 */
    fun clear() {
        approvals.clear()
        questions.clear()
        answered.clear()
    }

    /** 属于 [sessionId] 且仍在等的审批卡，按到达顺序。 */
    fun approvalsFor(sessionId: String): List<Model.Approval> =
        approvals.values.filter { interactionBelongs(it.agentId, sessionId) && !isAnswering(it.eventId) }

    /** 属于 [sessionId] 且仍在等的提问卡，按到达顺序。 */
    fun questionsFor(sessionId: String): List<Model.QuestionCard> =
        questions.values.filter { interactionBelongs(it.agentId, sessionId) && !isAnswering(it.eventId) }
}
