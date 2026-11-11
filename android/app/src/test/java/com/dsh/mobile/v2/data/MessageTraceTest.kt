package com.dsh.mobile.v2.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 六跳打点的回归测试。
 *
 * <p>这套东西的价值全在「缺哪一跳」上，所以断言的重点不是时间数字好看，
 * 而是：**该有的一跳不丢、重复的跳不刷新、没有 T2 也没人被误导**。
 */
class MessageTraceTest {

    @Before
    fun setUp() = MessageTrace.clear()

    @Test
    fun `begin starts a trace at T1 and repeated calls keep the first hop`() {
        val entry = MessageTrace.begin("m1", "s1", now = 1000)
        assertEquals(1000L, entry.at[MessageTrace.T1_SEND])
        MessageTrace.begin("m1", "s1", now = 5000)
        assertEquals("重复 begin 不能把 T1 推后——那会把耗时算短", 1000L, entry.at[MessageTrace.T1_SEND])
    }

    @Test
    fun `hostAccepted derives T3 from the plugin duration so no clock comparison is needed`() {
        MessageTrace.begin("m1", "s1", now = 1000)
        MessageTrace.hostAccepted("m1", pluginMs = 250, now = 1600)
        val entry = MessageTrace.find("m1")!!
        assertEquals(1350L, entry.at[MessageTrace.T3_PLUGIN])
        assertEquals(1600L, entry.at[MessageTrace.T4_HOST])
    }

    @Test
    fun `a missing plugin duration leaves T3 absent instead of inventing a time`() {
        MessageTrace.begin("m1", "s1", now = 1000)
        MessageTrace.hostAccepted("m1", pluginMs = null, now = 1600)
        val entry = MessageTrace.find("m1")!!
        assertNull(entry.at[MessageTrace.T3_PLUGIN])
        assertEquals(1600L, entry.at[MessageTrace.T4_HOST])
        assertEquals(MessageTrace.T3_PLUGIN, entry.missing())
    }

    @Test
    fun `an absurd plugin duration is dropped rather than pushing T3 before T1`() {
        MessageTrace.begin("m1", "s1", now = 10_000)
        MessageTrace.hostAccepted("m1", pluginMs = 5_000_000, now = 12_000)
        val entry = MessageTrace.find("m1")!!
        assertNull(entry.at[MessageTrace.T3_PLUGIN])
        assertEquals(12_000L, entry.at[MessageTrace.T4_HOST])
    }

    @Test
    fun `the first user message of a session marks T5 once and only once`() {
        MessageTrace.begin("m1", "s1", now = 1000)
        assertEquals("m1", MessageTrace.noteUserMessage("s1", now = 1300))
        assertNull("第二条用户消息不该顶替第一条的 T5", MessageTrace.noteUserMessage("s1", now = 1400))
        assertEquals(1300L, MessageTrace.find("m1")!!.at[MessageTrace.T5_FRAME])
    }

    @Test
    fun `a message for another session never steals the arrival mark`() {
        MessageTrace.begin("m1", "s1", now = 1000)
        assertNull(MessageTrace.noteUserMessage("s2", now = 1300))
        assertNull(MessageTrace.find("m1")!!.at[MessageTrace.T5_FRAME])
    }

    @Test
    fun `T6 is only stamped after T5 and only once`() {
        MessageTrace.begin("m1", "s1", now = 1000)
        assertFalse("还没到 T5 就不算上屏", MessageTrace.noteOnScreen("s1", now = 1100))
        MessageTrace.noteUserMessage("s1", now = 1300)
        assertTrue(MessageTrace.noteOnScreen("s1", now = 1500))
        assertFalse("重复渲染不该改写上屏时刻", MessageTrace.noteOnScreen("s1", now = 9000))
        assertEquals(1500L, MessageTrace.find("m1")!!.at[MessageTrace.T6_ONSCREEN])
    }

    @Test
    fun `a frame that arrives before the send still records its later hops`() {
        // 回放记录可能比 T1 先到；hop() 会自动开一条并保留后续跳点。
        MessageTrace.hop("orphan", MessageTrace.T5_FRAME, now = 500)
        val entry = MessageTrace.find("orphan")
        assertNotNull(entry)
        assertEquals(500L, entry!!.at[MessageTrace.T5_FRAME])
    }

    @Test
    fun `totalMs spans T1 to T6 and reports -1 when the trail is incomplete`() {
        MessageTrace.begin("m1", "s1", now = 1000)
        assertEquals(-1L, MessageTrace.find("m1")!!.totalMs())
        MessageTrace.noteUserMessage("s1", now = 1300)
        assertEquals("没有 T6 时按 T5 收口", 300L, MessageTrace.find("m1")!!.totalMs())
        MessageTrace.noteOnScreen("s1", now = 1800)
        assertEquals(800L, MessageTrace.find("m1")!!.totalMs())
    }

    @Test
    fun `re-beginning the same id does not enqueue a second copy`() {
        repeat(MessageTrace.CAPACITY) { MessageTrace.begin("m$it", "s1", now = it.toLong()) }
        repeat(MessageTrace.CAPACITY) { MessageTrace.begin("m$it", "s1", now = 99_999) }
        assertEquals(
            "重复 begin 只是回到同一条记录，不能把环撑满两倍然后挤掉别人",
            MessageTrace.CAPACITY,
            MessageTrace.report().lines().size,
        )
    }

    @Test
    fun `evicting the oldest keeps the ring and the index in step`() {
        val total = MessageTrace.CAPACITY + 40
        repeat(total) { MessageTrace.begin("m$it", "s1", now = it.toLong()) }
        // 环里只剩最近 CAPACITY 条，且每一条都能在索引里找到——没有悬空。
        val lines = MessageTrace.report().lines()
        assertEquals(MessageTrace.CAPACITY, lines.size)
        for (line in lines) {
            val id = line.substringBefore(' ')
            assertNotNull("环里有、索引里没有 = 悬空条目: $id", MessageTrace.find(id))
        }
        // 被挤掉的那几条必须真的消失，否则索引只增不减。
        assertNull(MessageTrace.find("m0"))
        assertNull(MessageTrace.find("m${total - MessageTrace.CAPACITY - 1}"))
        assertNotNull(MessageTrace.find("m${total - 1}"))
    }

    @Test
    fun `render marks missing hops and shows offsets from T1`() {
        MessageTrace.begin("abcdefghijklmnop", "s1", now = 1000)
        MessageTrace.noteUserMessage("s1", now = 1250)
        val text = MessageTrace.find("abcdefghijklmnop")!!.render()
        assertTrue(text, text.contains("T1_SEND=+0"))
        assertTrue(text, text.contains("T5_FRAME=+250"))
        assertTrue("缺的跳要画成 -，不是 0", text.contains("T3_PLUGIN=-"))
        assertTrue("id 要截短，不然一行贴不下", text.contains("abcdefghijkl"))
        assertFalse(text.contains("abcdefghijklmnop"))
    }

    @Test
    fun `a failure is recorded without pretending the sentence arrived`() {
        MessageTrace.clear()
        MessageTrace.begin("m-fail", "s1", now = 1_000)
        MessageTrace.hostAccepted("m-fail", pluginMs = 250, now = 1_600)

        MessageTrace.noteFailed("m-fail", "ack-timeout", now = 9_999)

        val entry = MessageTrace.find("m-fail")!!
        // 断在 T5 之前：宿主答复了，但这一轮的第一条用户消息从没回传。
        assertEquals(MessageTrace.T5_FRAME, entry.missing())
        assertEquals(-1, entry.totalMs())
    }

    @Test
    fun `a failure marker never counts as a completed hop`() {
        MessageTrace.clear()
        MessageTrace.begin("m-done", "s1", now = 1_000)
        MessageTrace.noteUserMessage("s1", now = 1_100)
        MessageTrace.noteOnScreen("s1", now = 1_200)

        MessageTrace.noteFailed("m-done", "late-error", now = 9_999)

        val entry = MessageTrace.find("m-done")!!
        // 已经闭合的句子不该被后来的失败标脏；也没有多跳出来。
        assertEquals(listOf(
            MessageTrace.T1_SEND,
            MessageTrace.T3_PLUGIN,
            MessageTrace.T4_HOST,
            MessageTrace.T5_FRAME,
            MessageTrace.T6_ONSCREEN,
        ), MessageTrace.HOPS)
        assertEquals(200L, entry.totalMs())
        assertFalse(entry.at.containsKey("FAILED"))
    }

    @Test
    fun `a failure for an unknown id is ignored instead of inventing a record`() {
        MessageTrace.clear()
        MessageTrace.noteFailed("never-seen", "ack-timeout")
        assertNull(MessageTrace.find("never-seen"))
        assertEquals("", MessageTrace.report())
    }
}
