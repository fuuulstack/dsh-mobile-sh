package com.dsh.mobile.v2.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 附件缩略图的两块纯逻辑：缓存键、淘汰顺序，以及「那一格写什么」。
 *
 * 真正的字节加载要 Bitmap 与网络，不在单测里；这里钉的是**出错时用户看到什么**——
 * 「附件传输已关闭」和「图片加载失败」必须分得开，因为前者要去设置页打开开关，
 * 后者只能重试，混成一句话就会把人引到错的方向。
 */
class AttachmentThumbsTest {

    @Test
    fun `缓存键必须带上会话，否则两个会话的同一张图会串`() {
        // attachmentId 只在会话内唯一。只用 id 做键的话，A 会话先加载的图会被
        // B 会话直接命中——用户看到的是「另一段对话里的图出现在这里」。
        assertNotEquals(thumbKey("s1", "att-1"), thumbKey("s2", "att-1"))
        assertEquals(thumbKey("s1", "att-1"), thumbKey("s1", "att-1"))
    }

    @Test
    fun `中继关掉附件传输时那一格说的是开关，不是加载失败`() {
        // 用户能做的事不同：这一句指向设置页的开关；「加载失败」只会让人重试。
        assertEquals("附件传输已关闭", thumbPlaceholder(enabled = false, failed = false, name = "a.png"))
        // 关着的时候哪怕之前失败过，也要先说开关——否则用户以为修好开关也没用。
        assertEquals("附件传输已关闭", thumbPlaceholder(enabled = false, failed = true, name = "a.png"))
    }

    @Test
    fun `开着但取不到字节时说的是加载失败`() {
        assertEquals("图片加载失败", thumbPlaceholder(enabled = true, failed = true, name = "a.png"))
    }

    @Test
    fun `还没开始加载时用文件名占位，没名字就写图片`() {
        assertEquals("shot.png", thumbPlaceholder(enabled = true, failed = false, name = "shot.png"))
        // 空名字不能留白：那一格会变成一个没有信息的灰块。
        assertEquals("图片", thumbPlaceholder(enabled = true, failed = false, name = ""))
    }

    @Test
    fun `缓存超出上限时丢最久没用的那一项`() {
        // 时间线是 LazyColumn，往上翻再翻回来会重新组合；不缓存就每次都重新
        // 走 session/attachment（宿主现读现编码 base64），滑动会明显卡。
        // 但缓存必须有上限：Bitmap 很大，无界 Map 翻几十屏就能把内存吃干净。
        val cache = LruCache<Int>(limit = 3)
        cache.put("a", 1)
        cache.put("b", 2)
        cache.put("c", 3)
        // 读一次 a，让它在 LRU 里变成「刚用过」：接下来淘汰的应当是 b 而不是 a。
        // 这正是 accessOrder 与插入序的区别，写错了单看条数根本发现不了。
        assertEquals(1, cache.get("a"))
        cache.put("d", 4)
        assertNull("最久没用的 b 应当被淘汰", cache.get("b"))
        assertEquals(1, cache.get("a"))
        assertEquals(4, cache.get("d"))
    }

    @Test
    fun `清空缓存之后取不到旧项`() {
        // 退出登录 / 切主机时必须清：换一台电脑后同一个 attachmentId
        // 可能指向完全不同的内容，命中旧缓存等于给用户看别人的图。
        val cache = LruCache<String>(limit = 8)
        cache.put("s1/att-1", "old")
        assertTrue(cache.get("s1/att-1") != null)
        cache.clear()
        assertNull(cache.get("s1/att-1"))
    }
}
