package com.dsh.mobile.v2.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown 解析单测（v1 Markdown.java 的子集契约）。
 *
 * 只测解析结果，不测 Compose 渲染——渲染层是纯映射，解析对了视觉才可能对。
 */
class MarkdownTest {

    @Test
    fun `段落与空行分段`() {
        val blocks = Markdown.parse("第一段\n还是第一段\n\n第二段")
        assertEquals(2, blocks.size)
        assertEquals(Markdown.PARAGRAPH, blocks[0].type)
        assertEquals("第一段\n还是第一段", blocks[0].text)
        assertEquals("第二段", blocks[1].text)
    }

    @Test
    fun `标题按井号数量定级，井号后无空格不算标题`() {
        val blocks = Markdown.parse("# 一级\n### 三级\n#标签")
        assertEquals(Markdown.HEADING, blocks[0].type)
        assertEquals(1, blocks[0].level)
        assertEquals("一级", blocks[0].text)
        assertEquals(3, blocks[1].level)
        // #标签 没有空格：不是标题，落成段落
        assertEquals(Markdown.PARAGRAPH, blocks[2].type)
        assertEquals("#标签", blocks[2].text)
    }

    @Test
    fun `围栏代码块保留原文与语言`() {
        val src = "```kotlin\nval a = 1\nval b = 2\n```"
        val blocks = Markdown.parse(src)
        assertEquals(1, blocks.size)
        assertEquals(Markdown.CODE, blocks[0].type)
        assertEquals("kotlin", blocks[0].lang)
        assertEquals("val a = 1\nval b = 2", blocks[0].text)
    }

    @Test
    fun `dsh-ui 围栏识别为 DSUI 块`() {
        val blocks = Markdown.parse("```dsh-ui\n{\"type\":\"callout\"}\n```")
        assertEquals(Markdown.DSUI, blocks[0].type)
        assertEquals("{\"type\":\"callout\"}", blocks[0].json)
    }

    @Test
    fun `无序与有序列表分别成块`() {
        val blocks = Markdown.parse("- 甲\n- 乙\n\n1. 一\n2. 二")
        assertEquals(2, blocks.size)
        assertEquals(Markdown.LIST, blocks[0].type)
        assertFalse(blocks[0].ordered)
        assertEquals(listOf("甲", "乙"), blocks[0].items)
        assertTrue(blocks[1].ordered)
        assertEquals(listOf("一", "二"), blocks[1].items)
    }

    @Test
    fun `引用可跨多行`() {
        val blocks = Markdown.parse("> 第一行\n> 第二行")
        assertEquals(Markdown.QUOTE, blocks[0].type)
        assertEquals("第一行\n第二行", blocks[0].text)
    }

    @Test
    fun `分割线三种写法`() {
        listOf("---", "***", "___").forEach { mark ->
            val blocks = Markdown.parse("上文\n\n$mark\n\n下文")
            assertTrue("$mark 应识别为 HR", blocks.any { it.type == Markdown.HR })
        }
    }

    @Test
    fun `GFM 表格需要下一行是分隔行`() {
        val src = "| 列A | 列B |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |"
        val blocks = Markdown.parse(src)
        assertEquals(Markdown.TABLE, blocks[0].type)
        assertEquals(listOf("列A", "列B"), blocks[0].header)
        assertEquals(2, blocks[0].rows.size)
        assertEquals(listOf("1", "2"), blocks[0].rows[0])
    }

    @Test
    fun `正文里的竖线不误判为表格`() {
        val blocks = Markdown.parse("a | b 只是正文")
        assertEquals(Markdown.PARAGRAPH, blocks[0].type)
    }

    @Test
    fun `表格行支持转义竖线与对齐分隔行`() {
        val src = "| a\\|b | c |\n|:--|--:|\n| 1 | 2 |"
        val blocks = Markdown.parse(src)
        assertEquals(listOf("a|b", "c"), blocks[0].header)
        assertTrue(Markdown.isTableDelimiter("|:--|--:|"))
    }

    @Test
    fun `行内样式切分：粗体 斜体 代码 删除线`() {
        val spans = Markdown.inlineSpans("普通 **粗** 和 *斜* 与 `code` 还有 ~~删~~")
        assertTrue(spans.any { it.text == "粗" && it.bold })
        assertTrue(spans.any { it.text == "斜" && it.italic })
        assertTrue(spans.any { it.text == "code" && it.code })
        assertTrue(spans.any { it.text == "删" && it.strike })
    }

    @Test
    fun `行内代码里的星号不被当作粗体`() {
        val spans = Markdown.inlineSpans("看 `a ** b` 这段")
        val code = spans.first { it.code }
        assertEquals("a ** b", code.text)
        assertFalse(spans.any { it.bold })
    }

    @Test
    fun `空输入返回空块列表`() {
        assertTrue(Markdown.parse(null).isEmpty())
        assertTrue(Markdown.parse("").isEmpty())
    }
}
