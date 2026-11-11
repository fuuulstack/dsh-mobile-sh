package com.dsh.mobile.v2.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打字机与「贴底跟随」的纯函数口径。
 *
 * <p>这两块都在 Compose 里跑，但真正容易写错的判断全是纯逻辑：积压多少吐多少、
 * 什么时候算「有积压」、什么时候该滚。抽出来钉住，比在模拟器上盯着屏幕猜靠谱。
 */
class TypewriterTest {

    // ---------- typewriterChunk ----------

    @Test
    fun `没有积压时一个字符都不吐`() {
        assertEquals("没积压就别动，否则会把已显示的又算一遍", 0, typewriterChunk(0))
        assertEquals("负数是脏数据，按没积压处理", 0, typewriterChunk(-5))
    }

    @Test
    fun `积压很少时一个 tick 吐一个字`() {
        // 模型一字一字地吐时，视觉上就该是「正在写」——一个 tick 一个字。
        assertEquals(1, typewriterChunk(1))
        assertEquals(1, typewriterChunk(TYPEWRITER_CATCHUP_TICKS))
    }

    @Test
    fun `积压越多每个 tick 吐得越多`() {
        // 一次灌进来 240 个字：按固定速率（1 字/16ms）要追四秒，用户盯着半截话更难受。
        // 期望 240/12 = 20 字/tick → 12 个 tick（≈200ms）追平。
        assertEquals(TYPEWRITER_CATCHUP_TICKS, typewriterChunk(TYPEWRITER_CATCHUP_TICKS * TYPEWRITER_CATCHUP_TICKS))
        val chunk = typewriterChunk(240)
        assertEquals("240 字应在 12 个 tick 内追平", 20, chunk)
        assertTrue(
            "按这个速度必须真的能在 CATCHUP_TICKS 个 tick 内追平，否则「自适应」是假的",
            chunk * TYPEWRITER_CATCHUP_TICKS >= 240,
        )
    }

    @Test
    fun `积压刚好比一个 tick 多一点点也要吐两个`() {
        // 向上取整：13 个字不能每个 tick 只吐 1 个（那要 13 个 tick，超出预算）。
        assertEquals(2, typewriterChunk(TYPEWRITER_CATCHUP_TICKS + 1))
    }

    @Test
    fun `任何输入速率下积压都收敛在固定窗口内`() {
        // 回归：曾经用 pending^0.7/8 的次线性公式，积压会随输入速率线性累积且永不收敛
        // （宿主 1000 字/秒 → 屏幕稳定落后 1 秒；3750 字/秒 → 落后 1.8 秒）。
        // 判据：稳态积压（字数）不超过「每个 tick 灌进来的字数 × CATCHUP_TICKS」。
        for (perTick in listOf(1, 3, 6, 16, 30, 60)) {
            var pending = 0
            repeat(5_000) {
                pending += perTick
                pending -= typewriterChunk(pending)
            }
            val budget = perTick * TYPEWRITER_CATCHUP_TICKS
            assertTrue(
                "灌入 $perTick 字/tick 时稳态积压 $pending 字，超过 ${budget} 字的预算",
                pending <= budget,
            )
        }
    }

    // ---------- typewriterNext ----------

    @Test
    fun `按给定步长推进下标`() {
        assertEquals(5, typewriterNext("0123456789", shown = 0, chunk = 5))
        assertEquals(10, typewriterNext("0123456789", shown = 5, chunk = 5))
    }

    @Test
    fun `推进不会越过文本末尾`() {
        assertEquals("追到末尾就停在末尾", 10, typewriterNext("0123456789", shown = 8, chunk = 99))
        assertEquals("已经到末尾就原地不动", 10, typewriterNext("0123456789", shown = 10, chunk = 3))
    }

    @Test
    fun `空文本与非法下标都不抛`() {
        assertEquals(0, typewriterNext("", shown = 0, chunk = 3))
        assertEquals("负数下标夹回 0", 0, typewriterNext("abc", shown = -2, chunk = 0))
        assertEquals("越界下标夹到末尾", 3, typewriterNext("abc", shown = 99, chunk = 1))
    }

    @Test
    fun `不会把代理对切成半个字符`() {
        // emoji 与扩展区汉字是代理对：按 UTF-16 单元切会切出孤立的高位代理，
        // 渲染出来是一个方块（或直接崩）。必须停在 code point 边界上。
        val text = "a\uD83D\uDE00b" // a + 😀 + b，长度 4 个 UTF-16 单元、3 个字符
        val afterOne = typewriterNext(text, shown = 0, chunk = 1)
        assertEquals("一个字符 = 'a'", 1, afterOne)
        val afterTwo = typewriterNext(text, shown = afterOne, chunk = 1)
        assertEquals("😀 占两个单元，必须整块吐出来", 3, afterTwo)
        assertEquals("再一个就是 b", 4, typewriterNext(text, shown = afterTwo, chunk = 1))
    }

    @Test
    fun `步长超过剩余字符数时只吐剩余的那些`() {
        val text = "a\uD83D\uDE00" // a + 😀
        assertEquals("只剩一个字符时不能吐到半个 emoji 里", 3, typewriterNext(text, shown = 1, chunk = 99))
    }

    // ---------- stickOnGrowth ----------

    @Test
    fun `贴底且内容变长时应该滚`() {
        assertTrue(
            "内容长过视口、上次还贴着底 —— 这正是「有输出就该跟着走」",
            stickOnGrowth(canScrollForward = true, atBottom = true, enabled = true),
        )
    }

    @Test
    fun `用户翻上去了就一个动作都不做`() {
        // 这是「看历史时被流式输出拽回底部」的直接成因：只看 canScrollForward
        // 会把翻历史的用户也拽回去。atBottom 是那次反馈之后加的守卫。
        assertFalse(
            "上次观测时就不在底部 → 不许拽人",
            stickOnGrowth(canScrollForward = true, atBottom = false, enabled = true),
        )
    }

    @Test
    fun `内容还没长过视口时不用滚`() {
        assertFalse(
            "canScrollForward=false 说明已经到底了，再滚是空转",
            stickOnGrowth(canScrollForward = false, atBottom = true, enabled = true),
        )
    }

    @Test
    fun `调用方不允许跟随时不滚`() {
        assertFalse(
            "手指正按在列表上（userDragging）时连判断都不该通过",
            stickOnGrowth(canScrollForward = true, atBottom = true, enabled = false),
        )
    }

    // ---------- showScrollToBottom（一级抽屉的「跳到底部」） ----------

    @Test
    fun `抽屉内容没超过一屏时不显示跳到底部`() {
        // 不能滚 = 要么内容还不满一屏、要么已经贴着底，两种都不该有这个入口。
        assertFalse(
            "内容不满一屏（或已在底部）",
            showScrollToBottom(canScrollForward = false, viewportSize = 800),
        )
    }

    @Test
    fun `抽屉视口还没量出来时不显示跳到底部`() {
        // 首帧：视口恒 0，此时 canScrollForward 也是 false。写死 true 会在抽屉
        // 刚打开的一瞬间闪一个按钮出来。
        assertFalse(
            "视口未排版",
            showScrollToBottom(canScrollForward = false, viewportSize = 0),
        )
    }

    @Test
    fun `用户翻上去之后显示跳到底部`() {
        assertTrue(
            "能往下滚 = 视线停在中间",
            showScrollToBottom(canScrollForward = true, viewportSize = 800),
        )
    }

    // ---------- contentHeightOf / stickOnContentGrowth ----------

    @Test
    fun `内容总高等于可滚距离加视口`() {
        assertEquals("不满一屏时 maxValue=0，总高仍是整个视口", 800, contentHeightOf(maxValue = 0, viewportSize = 800))
        assertEquals("撑满一屏之后 maxValue 才是可滚距离", 1600, contentHeightOf(maxValue = 800, viewportSize = 800))
    }

    @Test
    fun `内容从不满一屏长到刚好撑满时必须滚`() {
        // 用户实测反馈的原场景：二级抽屉一打开内容就不满一屏（maxValue 恒 0），
        // 正文流式长到撑满屏幕 —— 这是**第一次**能往下滚，必须跟着走。
        // 老实现拿 maxValue 当判据，这一步是 0 → 0，「没长」，永远不滚。
        val before = contentHeightOf(maxValue = 0, viewportSize = 800)
        val after = contentHeightOf(maxValue = 800, viewportSize = 800)
        assertTrue(
            "从 800 长到 1600，且贴底 —— 必须滚到新的底",
            stickOnContentGrowth(
                height = after,
                lastHeight = before,
                canScrollForward = true,
                atBottom = true,
                dragging = false,
            ),
        )
    }

    @Test
    fun `首帧只记基线不滚`() {
        assertFalse(
            "打开抽屉时该停在开头，lastHeight 为空说明这是第一帧",
            stickOnContentGrowth(height = 2400, lastHeight = null, canScrollForward = true, atBottom = true, dragging = false),
        )
    }

    @Test
    fun `内容没长高时不滚`() {
        assertFalse(
            "同一帧被 snapshotFlow 重放一次不该产生滚动",
            stickOnContentGrowth(height = 1600, lastHeight = 1600, canScrollForward = true, atBottom = true, dragging = false),
        )
    }

    @Test
    fun `抽屉里翻上去看上面的步骤时不许拽人`() {
        assertFalse(
            "上次观测时就不在底部（atBottom=false）→ 不许拽人",
            stickOnContentGrowth(height = 1600, lastHeight = 800, canScrollForward = true, atBottom = false, dragging = false),
        )
    }

    @Test
    fun `手指还在拖时不抢滚动`() {
        assertFalse(
            "拖动中把所有权交给用户，手指抬起后再重新武装",
            stickOnContentGrowth(height = 1600, lastHeight = 800, canScrollForward = true, atBottom = true, dragging = true),
        )
    }
}
