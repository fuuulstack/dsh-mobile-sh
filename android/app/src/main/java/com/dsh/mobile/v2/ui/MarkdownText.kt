package com.dsh.mobile.v2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Markdown 渲染（Compose 版，v1 Markdown.java 的视觉一比一）。
 *
 * 段落 / 标题 / 列表 / 引用 / 分割线 / 围栏代码块 / GFM 管道表格 / dsh-ui 卡片降级为代码块。
 * 正文 21sp（沿用 v1 偏大的可读性选择）。
 */
@Composable
fun MarkdownText(source: String, modifier: Modifier = Modifier) {
    val blocks = androidx.compose.runtime.remember(source) { Markdown.parse(source) }
    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEach { block ->
            when (block.type) {
                Markdown.HEADING -> {
                    Text(
                        annotated(block.text),
                        // v1：1~2 级用 text_body_lg(16sp)、其余 text_body(15sp)，都加粗。
                        fontSize = if (block.level <= 2) 16.sp else 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Ds.textPrimary,
                        lineHeight = if (block.level <= 2) 22.sp else 21.sp,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                    )
                }

                Markdown.LIST -> Column(modifier = Modifier.padding(vertical = 2.dp)) {
                    block.items.forEachIndexed { index, item ->
                        Row(modifier = Modifier.padding(vertical = 3.dp)) {
                            Text(
                                if (block.ordered) "${index + 1}." else "•",
                                fontSize = 15.sp,
                                color = Ds.textSecondary,
                                lineHeight = 22.sp,
                                modifier = Modifier.width(22.dp),
                            )
                            Text(
                                annotated(item),
                                fontSize = 15.sp,
                                color = Ds.textPrimary,
                                lineHeight = 22.sp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                Markdown.QUOTE -> Row(modifier = Modifier.padding(vertical = 6.dp)) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .heightInSelf()
                            .background(Ds.borderStrong),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        block.text,
                        fontSize = 15.sp,
                        color = Ds.textMuted,
                        lineHeight = 22.sp,
                        modifier = Modifier.weight(1f),
                    )
                }

                Markdown.HR -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp)
                        .height(1.dp)
                        .background(Ds.divider),
                )

                Markdown.CODE -> CodeBlock(block.text)

                // dsh-ui 卡片暂按代码块降级（v1 也是「解析失败就回退代码块」）。
                Markdown.DSUI -> CodeBlock(block.json)

                Markdown.TABLE -> TableBlock(block)

                else -> Text(
                    annotated(block.text),
                    fontSize = 15.sp,
                    color = Ds.textPrimary,
                    lineHeight = 22.sp,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }
}

/** 行内样式（粗体/斜体/行内代码/删除线）→ AnnotatedString。 */
@Composable
@androidx.compose.runtime.ReadOnlyComposable
private fun annotated(text: String): AnnotatedString = buildAnnotatedString {
    Markdown.inlineSpans(text).forEach { span ->
        val start = length
        append(span.text)
        val end = length
        if (end > start) {
            if (span.bold) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
            if (span.italic) addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
            if (span.strike) addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, end)
            if (span.code) {
                addStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Ds.bgSurfaceVariant,
                        fontSize = 15.sp,
                    ),
                    start,
                    end,
                )
            }
        }
    }
}

/** 围栏代码块：深色底 + 等宽字 + 横向滚动（v1 codeBlock 同款）。 */
@Composable
private fun CodeBlock(code: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(Ds.codeBg, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .horizontalScroll(rememberScrollState()),
    ) {
        Text(
            code,
            fontSize = 12.sp,
            color = Ds.codeText,
            fontFamily = FontFamily.Monospace,
            lineHeight = 17.sp,
        )
    }
}

/** GFM 管道表格：圆角描边卡片；列数 >3 时横向滚动（v1 同款）。 */
@Composable
private fun TableBlock(block: Markdown.Block) {
    var columns = block.header.size
    block.rows.forEach { columns = maxOf(columns, it.size) }
    if (columns == 0) return
    val wide = columns > 3

    val grid = @Composable {
        Column(
            modifier = Modifier
                .background(Ds.bgSurface, RoundedCornerShape(10.dp))
                .border(1.dp, Ds.borderDefault, RoundedCornerShape(10.dp)),
        ) {
            if (block.header.isNotEmpty()) {
                TableRow(block.header, columns, wide, header = true)
            }
            block.rows.take(40).forEach { row ->
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Ds.divider))
                TableRow(row, columns, wide, header = false)
            }
        }
    }

    Box(modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp)) {
        if (wide) {
            Box(modifier = Modifier.horizontalScroll(rememberScrollState())) { grid() }
        } else {
            Box(modifier = Modifier.fillMaxWidth()) { grid() }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, columns: Int, wide: Boolean, header: Boolean) {
    Row(
        modifier = Modifier
            .then(
                if (header) Modifier.background(Ds.bgSurfaceVariant) else Modifier
            ),
        verticalAlignment = Alignment.Top,
    ) {
        for (c in 0 until columns) {
            val value = cells.getOrElse(c) { "" }
            val cellModifier = if (wide) Modifier.width(116.dp) else Modifier.weight(1f)
            Text(
                annotated(value),
                fontSize = 12.sp,
                color = if (header) Ds.textPrimary else Ds.textSecondary,
                fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                lineHeight = 17.sp,
                modifier = cellModifier.padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

/** 让引用竖条跟随文字高度。 */
private fun Modifier.heightInSelf(): Modifier = this
