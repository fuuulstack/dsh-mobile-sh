package com.dsh.mobile.v2.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同步侧纯逻辑的 JVM 单测。
 *
 * <p>v1 那套「归档游标 / nextCursor / recordTooLarge」的补页决策已随
 * `/native/v1/…` 路由一起删除，对应的断言也一并去掉。留下的是两件与协议版本
 * 无关、出错却极难在真机上看出来的事：**同一会话的请求串行化**，以及
 * **旧游标迁移的保守口径**。
 */
class SyncPlannerTest {

    // ---- single-flight ----

    @Test
    fun `同会话连续二十个触发只有一个在途 dirty 最后对账`() {
        val tracker = SyncPlanner.Tracker()
        var started = 0
        var rerun = false
        repeat(20) {
            if (tracker.begin("s1")) started++ else rerun = true
        }
        assertEquals("二十个触发只能开一个请求", 1, started)
        assertTrue(rerun)
        assertTrue(tracker.isRunning("s1"))
        // 一轮结束：因为期间有变更，必须再来一轮。
        assertTrue(tracker.finish("s1"))
        assertFalse("状态被清掉了，下一次触发重新开始", tracker.isRunning("s1"))
        assertTrue(tracker.begin("s1"))
        // 没有新变更就到此为止。
        assertFalse(tracker.finish("s1"))
    }

    @Test
    fun `不同会话各自串行 主机切换后旧状态被丢弃`() {
        val tracker = SyncPlanner.Tracker()
        assertTrue(tracker.begin("a"))
        assertTrue("不同会话不该互相阻塞", tracker.begin("b"))
        assertFalse(tracker.begin("a"))
        tracker.reset()
        assertFalse(tracker.isRunning("a"))
        // 切走再切回来能正常开始，旧回调不会把它一直卡在 running。
        assertTrue(tracker.begin("a"))
    }

    @Test
    fun `空会话 id 不占调度槽`() {
        val tracker = SyncPlanner.Tracker()
        assertFalse(tracker.begin(""))
        assertFalse(tracker.finish(""))
    }

    // ---- 已污染游标迁移 ----

    @Test
    fun `污染的旧游标只会回落到本地真正拥有的位置`() {
        // 现场形态：游标被 latestSeq 顶到 908，本地正文只到 59。
        val legacy = mapOf("a" to 908L, "b" to 30L, "c" to 0L, "d" to 12L, "" to 5L)
        val local = mapOf("a" to 59L, "b" to 42L, "c" to 100L, "d" to 0L)
        val migrated = SyncPlanner.migrateCursors(legacy) { local[it] ?: 0L }
        assertEquals("被顶高的回落到真实位置", 59L, migrated["a"])
        assertEquals("本来就诚实的原样保留", 30L, migrated["b"])
        assertEquals("本地没有消息的从归档头重拉", null, migrated["d"])
        assertEquals("零值不迁移", null, migrated["c"])
        assertFalse("空 id 不该进表", migrated.containsKey(""))
        assertEquals(2, migrated.size)
    }

    @Test
    fun `迁移不会凭空造出游标`() {
        assertTrue(SyncPlanner.migrateCursors(emptyMap()) { 100L }.isEmpty())
        assertTrue(SyncPlanner.migrateCursors(mapOf("x" to 0L)) { 100L }.isEmpty())
    }

    // ---- 退避要有界 ----

    @Test
    fun `零进展退避有界`() {
        assertEquals(0L, SyncPlanner.backoffMs(0))
        assertEquals(1_000L, SyncPlanner.backoffMs(1))
        assertEquals(2_000L, SyncPlanner.backoffMs(2))
        assertEquals(4_000L, SyncPlanner.backoffMs(3))
        assertEquals(8_000L, SyncPlanner.backoffMs(4))
        assertEquals("再多次也不无限增长", 8_000L, SyncPlanner.backoffMs(99))
    }
}
