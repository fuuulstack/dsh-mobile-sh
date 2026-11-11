package com.dsh.mobile.v2.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首行预览预热队列的闩锁语义。
 *
 * <p>回归的是 2026-11-02 线上那个现象：**会话列表页的预览永远不刷新，非得进一次
 * 聊天页、再退出来才更新**。
 *
 * <p>链路当时是通的（中继按变更帧推、手机按帧补拉、Room 里确实落了新正文），断在
 * 最后一步：预览要重算得先排队，而原来的闩锁只 `add` 从不 `remove`（唯一的移除点
 * 在批量同步那条路上），所以每个会话一辈子只可能被预热一次；那仅有一次若恰好落在
 * 「最新一条助手消息还只有思考、没有正文」的时刻，查到空串、界面一次都没更新，
 * 闩锁却照样留着，之后再也不会重试。
 *
 * <p>分辨两种「释放」是这类队列最容易写错的地方，所以两条都在这里钉住：
 * **数据变了必须放开**（[WarmQueue.release]，否则就有上面那个 bug），
 * **只是查过一遍不能放开**（[WarmQueue.dequeue]，否则每个空会话每次列表重绘都要重查一次库）。
 */
class WarmQueueTest {

    @Test
    fun `enqueue is idempotent while the key is still latched`() {
        val q = WarmQueue()
        assertTrue(q.enqueue("h1", "s1"))
        assertFalse(q.enqueue("h1", "s1"))
        assertEquals(1, q.size)
    }

    @Test
    fun `new records reopen a session for warming`() {
        val q = WarmQueue()
        assertTrue(q.enqueue("h1", "s1"))
        assertEquals(listOf("s1"), q.dequeue("h1"))

        // 这一条就是那个 bug 的回归：落了新正文（invalidatePreview → release）之后，
        // 同一个会话必须还能重新排队。
        q.release("h1", "s1")
        assertTrue(q.enqueue("h1", "s1"))
        assertEquals(listOf("s1"), q.dequeue("h1"))
    }

    @Test
    fun `a pass that found nothing keeps the latch until data changes`() {
        val q = WarmQueue()
        q.enqueue("h1", "s1")
        // 模拟预热真的跑了但一条正文都没查到：只出队，什么都没查到。
        assertEquals(listOf("s1"), q.dequeue("h1"))
        // 不能因为「查不到」就自己解开闩锁 —— 否则 `previewOf` 每次命中不了缓存都会
        // 重新排队，列表里每多一个空会话就多一次每帧点查。
        assertFalse(q.enqueue("h1", "s1"))
        // 数据真的变了才放开。
        q.release("h1", "s1")
        assertTrue(q.enqueue("h1", "s1"))
    }

    @Test
    fun `dequeue empties the batch so a second pass has nothing to do`() {
        val q = WarmQueue()
        q.enqueue("h1", "s1")
        q.enqueue("h1", "s2")
        assertEquals(listOf("s1", "s2"), q.dequeue("h1"))
        assertTrue(q.dequeue("h1").isEmpty())
        assertEquals(0, q.size)
        // 排队位置没了，但闩锁还在（数据没变，不该再算一遍）。
        assertTrue(q.isQueued("h1", "s1"))
    }

    @Test
    fun `requeue forces a pass even when the key is already latched`() {
        val q = WarmQueue()
        q.enqueue("h1", "s1")
        assertTrue(q.requeue("h1", "s1"))
        // 不能因此出现两条相同会话的待办。
        assertEquals(1, q.size)
        assertTrue(q.isQueued("h1", "s1"))
        assertEquals(listOf("s1"), q.dequeue("h1"))
    }

    @Test
    fun `latches are scoped per host`() {
        val q = WarmQueue()
        assertTrue(q.enqueue("h1", "s1"))
        // 换一台主机之后同名会话不该被上一台的闩锁挡住。
        assertTrue(q.enqueue("h2", "s1"))
        // `h1` 这一批只该取走 `h1` 的待办。
        assertEquals(listOf("s1"), q.dequeue("h1"))
        // 闩锁不受「取走待办」影响，两台主机的都还在。
        assertTrue(q.isQueued("h1", "s1"))
        assertTrue(q.isQueued("h2", "s1"))
        assertEquals(listOf("s1"), q.dequeue("h2"))
    }

    @Test
    fun `dequeue only takes the given host batch`() {
        val q = WarmQueue()
        q.enqueue("h1", "s1")
        q.enqueue("h2", "s2")
        assertEquals(listOf("s1"), q.dequeue("h1"))
        assertEquals(listOf("s2"), q.dequeue("h2"))
        assertTrue(q.dequeue("h1").isEmpty())
    }

    @Test
    fun `release drops both the pending entry and the latch`() {
        val q = WarmQueue()
        q.enqueue("h1", "s1")
        q.release("h1", "s1")
        assertEquals(0, q.size)
        assertFalse(q.isQueued("h1", "s1"))
        assertTrue(q.enqueue("h1", "s1"))
    }

    @Test
    fun `blank identifiers are never queued`() {
        val q = WarmQueue()
        assertFalse(q.enqueue("", "s1"))
        assertFalse(q.enqueue("h1", ""))
        assertEquals(0, q.size)
    }
}
