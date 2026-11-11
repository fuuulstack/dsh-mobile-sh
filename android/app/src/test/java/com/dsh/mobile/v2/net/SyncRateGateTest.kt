package com.dsh.mobile.v2.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/sync` 客户端限流闸门（服务端是每登录会话 30 次 / 60 秒）。
 *
 * 这一组的价值在真机上很难复现：一冷启动几十个会话同时补页才会暴露出「一拥而上
 * → 大面积 429 → 退避重试又继续消耗」的正反馈，现场一次能打出三百多次 429、
 * 拖好几分钟。把窗口行为抽成可注入时钟的纯逻辑之后，边界就能直接断言。
 */
class SyncRateGateTest {

    private fun gate(max: Int = 120, windowMs: Long = 60_000L) = SyncRateGate(windowMs = windowMs, max = max)

    @Test
    fun `窗口内不超过上限 超出就挡住`() {
        val g = gate(max = 3)
        assertTrue(g.tryAcquire(1_000))
        assertTrue(g.tryAcquire(1_100))
        assertTrue(g.tryAcquire(1_200))
        assertFalse("第 4 次要被挡住", g.tryAcquire(1_300))
        assertEquals(3, g.used(1_300))
    }

    @Test
    fun `挡住的时候能算出还要等多久`() {
        val g = gate(max = 2, windowMs = 10_000)
        assertTrue(g.tryAcquire(0))
        assertTrue(g.tryAcquire(2_000))
        // 两笔都发出去了，闸门已满：最早那笔在 0ms，窗口 10s → 到 10s 才滑出去。
        assertFalse(g.tryAcquire(3_000))
        assertEquals(7_000L, g.waitMs(3_000))
        // 还没满的时候不用等。
        val roomy = gate(max = 3, windowMs = 10_000)
        assertTrue(roomy.tryAcquire(0))
        assertEquals("还有额度时不必等", 0L, roomy.waitMs(0))
    }

    @Test
    fun `窗口滑过去之后额度自动回来`() {
        val g = gate(max = 2, windowMs = 1_000)
        assertTrue(g.tryAcquire(0))
        assertTrue(g.tryAcquire(100))
        assertFalse(g.tryAcquire(200))
        // 到 1000ms，第一笔滑出窗口，可以再发一次。
        assertTrue(g.tryAcquire(1_000))
        assertFalse("第二笔还在窗口里", g.tryAcquire(1_001))
        assertTrue(g.tryAcquire(1_100))
    }

    @Test
    fun `空窗口不用等 也不占额度`() {
        val g = gate(max = 5)
        assertEquals(0L, g.waitMs(9_999))
        assertEquals(0, g.used(9_999))
        assertTrue(g.tryAcquire(9_999))
    }

    @Test
    fun `reset 之后额度清零`() {
        val g = gate(max = 1)
        assertTrue(g.tryAcquire(0))
        assertFalse(g.tryAcquire(0))
        g.reset()
        assertTrue("换主机/退出登录后要能立刻重新开始", g.tryAcquire(0))
    }

    @Test
    fun `时钟回拨不会把额度锁死`() {
        val g = gate(max = 2, windowMs = 1_000)
        assertTrue(g.tryAcquire(5_000))
        assertTrue(g.tryAcquire(5_100))
        // 时间倒退（系统校时）：waitMs 不能返回负数把调用方卡住。
        assertFalse(g.tryAcquire(1_000))
        assertTrue("等待时长必须是非负的", g.waitMs(1_000) >= 0L)
    }

    @Test
    fun `默认额度留出余量`() {
        // 服务端 600 次/分钟（自托管放宽后），客户端 120：中间还有重连、其它设备。
        val g = SyncRateGate()
        var granted = 0
        repeat(200) { if (g.tryAcquire(it.toLong())) granted++ }
        assertEquals(120, granted)
    }
}
