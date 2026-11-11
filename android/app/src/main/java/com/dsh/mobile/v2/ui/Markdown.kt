package com.dsh.mobile.v2.ui

/**
 * 助手消息的 Markdown 解析（v1 Markdown.java 的 Kotlin 移植，只留解析部分）。
 *
 * <p>覆盖对话里实际出现的子集：段落、标题、有序/无序列表、引用、分割线、
 * 围栏代码块、GFM 管道表格、DSH 自己的 dsh-ui JSON 卡片。
 * 未知语法按原文显示，不追求 CommonMark 全覆盖——与 v1 的取舍一致。
 *
 * <p>纯函数、无 Android 依赖，JVM 单测可直接覆盖。
 */
object Markdown {

    const val PARAGRAPH = 0
    const val HEADING = 1
    const val CODE = 2
    const val LIST = 3
    const val QUOTE = 4
    const val HR = 5
    const val DSUI = 6
    const val TABLE = 7

    data class Block(
        val type: Int,
        /** 段落/标题/引用/代码正文。 */
        val text: String = "",
        /** 代码语言。 */
        val lang: String = "",
        /** 标题层级 1..6。 */
        val level: Int = 0,
        /** 列表项；ordered 决定用 1. 还是 •。 */
        val items: List<String> = emptyList(),
        val ordered: Boolean = false,
        /** 表格表头与数据行。 */
        val header: List<String> = emptyList(),
        val rows: List<List<String>> = emptyList(),
        /** dsh-ui 卡片原文（JSON）。 */
        val json: String = "",
    )

    /** 把一段 Markdown 切成块。 */
    fun parse(source: String?): List<Block> {
        if (source.isNullOrEmpty()) return emptyList()
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = ArrayList<Block>()
        var i = 0
        val paragraph = StringBuilder()
        val list = ArrayList<String>()
        var listOrdered = false

        fun flushParagraph() {
            val text = paragraph.toString().trim()
            paragraph.setLength(0)
            if (text.isNotEmpty()) blocks.add(Block(PARAGRAPH, text = text))
        }

        fun flushList() {
            if (list.isEmpty()) return
            blocks.add(Block(LIST, items = ArrayList(list), ordered = listOrdered))
            list.clear()
        }

        fun flushAll() { flushParagraph(); flushList() }

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // 围栏代码块
            if (trimmed.startsWith("```")) {
                flushAll()
                val lang = trimmed.removePrefix("```").trim()
                val body = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    body.append(lines[i]).append('\n')
                    i++
                }
                i++ // 跳过结束围栏
                val code = body.toString().trimEnd('\n')
                blocks.add(
                    if (lang.equals("dsh-ui", ignoreCase = true) || lang.equals("dshui", ignoreCase = true)) {
                        Block(DSUI, json = code)
                    } else {
                        Block(CODE, text = code, lang = lang)
                    }
                )
                continue
            }

            // 空行：段落与列表的界
            if (trimmed.isEmpty()) {
                flushAll()
                i++
                continue
            }

            // 分割线
            if (isHr(trimmed)) {
                flushAll()
                blocks.add(Block(HR))
                i++
                continue
            }

            // 标题
            val level = headingLevel(trimmed)
            if (level > 0) {
                flushAll()
                blocks.add(Block(HEADING, text = trimmed.trimStart('#').trim(), level = level))
                i++
                continue
            }

            // 引用（可多行）
            if (trimmed.startsWith(">")) {
                flushAll()
                val quote = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    if (quote.isNotEmpty()) quote.append('\n')
                    quote.append(lines[i].trim().removePrefix(">").trim())
                    i++
                }
                blocks.add(Block(QUOTE, text = quote.toString()))
                continue
            }

            // GFM 管道表格：当前行是 | 开头/结尾，且下一行是分隔行
            if (trimmed.contains('|') && i + 1 < lines.size && isTableDelimiter(lines[i + 1].trim())) {
                flushAll()
                val header = splitRow(trimmed)
                i += 2 // 跳过分隔行
                val rows = ArrayList<List<String>>()
                while (i < lines.size && lines[i].trim().contains('|') && lines[i].trim().isNotEmpty()) {
                    rows.add(splitRow(lines[i].trim()))
                    i++
                }
                blocks.add(Block(TABLE, header = header, rows = rows))
                continue
            }

            // 列表项
            val itemMatch = listItemOf(trimmed)
            if (itemMatch != null) {
                flushParagraph()
                val ordered = itemMatch.first
                if (list.isNotEmpty() && listOrdered != ordered) flushList()
                listOrdered = ordered
                list.add(itemMatch.second)
                i++
                continue
            }

            // 普通段落（同段多行用换行保留，渲染时按段落显示）
            if (list.isNotEmpty()) flushList()
            if (paragraph.isNotEmpty()) paragraph.append('\n')
            paragraph.append(trimmed)
            i++
        }
        flushAll()
        return blocks
    }

    private fun isHr(line: String): Boolean {
        val t = line.replace(" ", "")
        if (t.length < 3) return false
        return t.all { it == '-' } || t.all { it == '*' } || t.all { it == '_' }
    }

    private fun headingLevel(line: String): Int {
        var n = 0
        while (n < line.length && n < 6 && line[n] == '#') n++
        if (n == 0) return 0
        // 必须后面跟空格（否则 #tag 会被当标题）
        if (n < line.length && line[n] != ' ') return 0
        return n
    }

    /** GFM 分隔行：每个单元格只由 -、: 和空白组成，且至少一格。 */
    fun isTableDelimiter(line: String): Boolean {
        if (line.isEmpty() || !line.contains('-')) return false
        val body = line.trim().removePrefix("|").removeSuffix("|")
        val cells = body.split('|')
        if (cells.isEmpty()) return false
        return cells.all { cell ->
            val c = cell.trim()
            c.isNotEmpty() && c.all { it == '-' || it == ':' }
        }
    }

    /** 拆一行管道表格：去掉首尾竖线，按未转义的 | 切分，\| 还原成 |。 */
    fun splitRow(line: String): List<String> {
        var body = line.trim()
        if (body.startsWith("|")) body = body.substring(1)
        if (body.endsWith("|")) body = body.dropLast(1)
        val cells = ArrayList<String>()
        val current = StringBuilder()
        var escaped = false
        for (ch in body) {
            when {
                escaped -> { current.append(ch); escaped = false }
                ch == '\\' -> escaped = true
                ch == '|' -> { cells.add(current.toString().trim()); current.setLength(0) }
                else -> current.append(ch)
            }
        }
        cells.add(current.toString().trim())
        return cells
    }

    /** 列表项：返回 (是否有序, 内容)。 */
    private fun listItemOf(line: String): Pair<Boolean, String>? {
        val trimmed = line.trim()
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
            return false to trimmed.substring(2).trim()
        }
        val m = Regex("^(\\d+)[.)]\\s+(.*)$").find(trimmed) ?: return null
        return true to m.groupValues[2].trim()
    }

    // ============================================================
    // 行内样式切分（渲染层负责套 AnnotatedString）
    // ============================================================

    /** 行内片段：普通文本 / 粗体 / 斜体 / 行内代码 / 删除线。 */
    data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false,
                    val code: Boolean = false, val strike: Boolean = false)

    /**
     * 把一行文本切成带样式的片段。
     *
     * <p>支持 `**粗体**`、`*斜体*`/`_斜体_`、`` `行内代码` ``、`~~删除线~~`。
     * 嵌套（粗体里的斜体）不展开——v1 也只处理单层，正文里极少出现。
     */
    fun inlineSpans(text: String): List<Span> {
        val out = ArrayList<Span>()
        val plain = StringBuilder()
        var i = 0
        fun flushPlain() {
            if (plain.isNotEmpty()) { out.add(Span(plain.toString())); plain.setLength(0) }
        }
        while (i < text.length) {
            val rest = text.substring(i)
            // 行内代码优先：里面的 ** 不该被当粗体
            if (rest.startsWith("`")) {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    flushPlain()
                    out.add(Span(text.substring(i + 1, end), code = true))
                    i = end + 1
                    continue
                }
            }
            if (rest.startsWith("**") || rest.startsWith("__")) {
                val mark = rest.substring(0, 2)
                val end = text.indexOf(mark, i + 2)
                if (end > i + 2) {
                    flushPlain()
                    out.add(Span(text.substring(i + 2, end), bold = true))
                    i = end + 2
                    continue
                }
            }
            if (rest.startsWith("~~")) {
                val end = text.indexOf("~~", i + 2)
                if (end > i + 2) {
                    flushPlain()
                    out.add(Span(text.substring(i + 2, end), strike = true))
                    i = end + 2
                    continue
                }
            }
            if (rest.startsWith("*") || rest.startsWith("_")) {
                val mark = rest.substring(0, 1)
                val end = text.indexOf(mark, i + 1)
                if (end > i + 1) {
                    flushPlain()
                    out.add(Span(text.substring(i + 1, end), italic = true))
                    i = end + 1
                    continue
                }
            }
            plain.append(text[i])
            i++
        }
        flushPlain()
        return out
    }
}
