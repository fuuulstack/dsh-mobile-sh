package com.dsh.mobile.v2.data

/**
 * 会话列表的首行预览：取**最后一条助手正文**的第一行，原样交给 UI。
 *
 * 只做两件事，别的都不做：
 *  1. 挑哪条消息——最后一条助手正文。用户自己说的话不算（列表上一整列都是你刚打的字，
 *     看不出这次答了什么），思考与工具调用不算（那是过程，不是答复），处理详情面板
 *     （`panelId` 非空）也跳过。
 *  2. 挑哪一行——第一行非空文本。
 *
 * **不做** Markdown 清洗、不做字数截断：省略号交给 `Text(maxLines = 1,
 * overflow = Ellipsis)`，多长的句子都会被列表自己裁掉。只在第一行是空行时才往下找，
 * 否则预览会是空白（助手正文偶尔以换行开头）。
 */
fun firstLinePreview(messages: List<Model.Message>): String {
    for (i in messages.indices.reversed()) {
        val m = messages[i]
        if (m.role != Model.Role.ASSISTANT) continue
        if (m.panelId.isNotEmpty()) continue
        if (m.text.isBlank()) continue
        val line = m.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        if (!line.isNullOrEmpty()) return line
    }
    return ""
}
