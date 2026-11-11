package com.dsh.mobile.v2.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 变更流重连后的补差判据。
 *
 * <p>回归的是这个缺口：中继的变更总线**不做重放**（`changes.ts`：「断线期间的变更由
 * 手机重连后走一次 /sync 按游标补齐」），而手机侧没有任何代码在 SSE 恢复后叫醒补差。
 * 于是「停在列表页、SSE 断了几分钟又自己连上」这段时间里变过的会话不会更新，
 * 要等用户下拉刷新或重新进列表页。
 *
 * <p>补的成本可以接受（起点是每条会话各自的 `localAckedSeq`，追平的会话返回空页，
 * 实测 38 会话一轮 0.38 ms），所以真正的设计点只有一个：**别在冷启动时补两遍**。
 */
class ChangesReconnectTest {

    @Test
    fun `a cold start that connects on the first try does not trigger a catch-up`() {
        // `selectHost` 已经走过一次 loadSessions()（内部 fillPreviews → 批量补差）。
        // 无条件把「连上了」当触发点，冷启动就是白白翻倍。
        val r = ChangesReconnect()
        assertFalse("首次就成功连上，不该再补一次", r.onConnected())
    }

    @Test
    fun `connecting after a drop triggers exactly one catch-up`() {
        val r = ChangesReconnect()
        r.onConnected()          // 冷启动那次，不补
        r.onDropped()            // 断了
        assertTrue("掉线之后连上必须补", r.onConnected())
        assertFalse("补过就该复位，不能连着补第二次", r.onConnected())
    }

    @Test
    fun `a first connection that fails still counts as a drop`() {
        // 冷启动时第一次连接就失败（弱网、中继刚重启），随后重连成功——这段空窗里
        // 同样可能有变更，必须补。把「掉过」只定义成「连上之后再断」会漏掉它。
        val r = ChangesReconnect()
        r.onDropped()
        assertTrue("首次连接失败后重连成功，也要补", r.onConnected())
    }

    @Test
    fun `repeated drops collapse into one catch-up`() {
        // 退避重连会连着报好几次失败；只该补一次。
        val r = ChangesReconnect()
        r.onDropped()
        r.onDropped()
        r.onDropped()
        assertTrue(r.onConnected())
        assertFalse(r.onConnected())
    }

    @Test
    fun `the pending flag tracks whether a catch-up is owed`() {
        val r = ChangesReconnect()
        assertFalse(r.pending())
        r.onDropped()
        assertTrue("掉过还没补", r.pending())
        r.onConnected()
        assertFalse("补过了", r.pending())
    }
}
