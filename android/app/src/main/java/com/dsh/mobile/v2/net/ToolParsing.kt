package com.dsh.mobile.v2.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 工具事件 → 展示用的三元组（call / result / 命令识别）。
 *
 * 为什么单独一个文件：宿主的 `tool/call` 与 `tool/result` 载荷形状随版本漂过好几次
 * （`name` 在顶层、在 `tool.name`、只在 `toolCall.name` 里；参数在 `arguments` 或
 * `input`；输出在 `result`、`message.content`、`output`），而 [Projector] 是纯函数，
 * 不该把「这个字段可能在四个地方」的补丁逻辑铺一屏。这里一次性收干净，投影器只吃
 * 已归一的结构。所有函数都对畸形输入返回中性值 —— 投影路径上不许抛异常。
 */

/** `tool/call` 归一后的结果。`label` 是折叠行上「此刻在干什么」的文案。 */
internal data class ParsedToolCall(
    val callId: String,
    val name: String,
    /** 参数的一句话摘要（命令/路径/查询），给抽屉里的副标题用。 */
    val detail: String,
    /**
     * 模型写的小标题（工具参数里的 `description`）。
     *
     * 桌面端折叠行 `工具调用 · ops_bash · 改成十秒桶` 的最后一段就是它——`toolRowModel`
     * 拼 `[工具名, base].join(" · ")`，`base` 取 `SUMMARY_KEYS`，bash 变体是
     * `["description", "command"]`，也就是**描述优先、命令兜底**。手机端要显示同一个
     * 小标题，就得把 `description` 单独读出来，而不是像以前那样只留命令。
     *
     * 工具没给描述时是空串（不是命令）：命令另有 [command]，折叠行第二行负责它。
     */
    val subtitle: String,
    /** 原始命令（bash/pwsh/shell 类工具），没有就是空串。 */
    val command: String,
    /** 折叠行/抽屉里显示的完整文案，例如「执行 npm test」。 */
    val label: String,
    /** 技能类工具的技能名。 */
    val skill: String,
    /** 抽屉里展示的命令正文（可能比 [command] 长）。 */
    val commandText: String,
)

/** `tool/result` 归一后的结果。 */
internal data class ParsedToolResult(
    val callId: String,
    val output: String,
)

/**
 * 读一条 `tool/call`（也兼容流式 `tool-call-delta`）。
 *
 * 判据全部是「有就用，没有就下一个候选」，绝不因为某个字段缺失就整条丢掉：
 * 宿主漏发 `callId` 时仍要能在时间线上显示这一行，否则用户会看到"助手凭空停了"。
 */
internal fun toolActionOfRecord(data: JsonObject): ParsedToolCall {
    // 有些版本把整个调用塞在 tool / toolCall 子对象里。
    val nested = data.obj("tool") ?: data.obj("toolCall")
    val callId = firstOf(
        data.str("callId"),
        data.str("toolCallId"),
        data.str("id"),
        nested?.str("callId") ?: "",
        nested?.str("id") ?: "",
    )
    val name = firstOf(
        data.str("name"),
        data.str("toolName"),
        nested?.str("name") ?: "",
        data.str("tool") ?: "",
    )
    val arguments = toolArgumentsOf(data)
    val command = firstOf(
        arguments.str("command"),
        arguments.str("cmd"),
        arguments.str("script") ?: "",
        arguments.str("shell") ?: "",
    )
    val skill = firstOf(arguments.str("skill"), arguments.str("skillName"), data.str("skill"))
    val path = firstOf(
        arguments.str("path"),
        arguments.str("file_path") ?: "",
        arguments.str("filePath") ?: "",
        arguments.str("target") ?: "",
    )
    val query = firstOf(arguments.str("query"), arguments.str("pattern"), arguments.str("url"))
    // 小标题：模型写在参数里的 `description`，就是桌面端折叠行
    // 「工具调用 · ops_bash · 改成十秒桶」最后那一段。
    //
    // 桌面端 `SUMMARY_KEYS.bash = ["description", "command"]` 是「描述优先、命令兜底」，
    // 但兜底那一段在手机端已经有 [detail] 承担，所以这里只取描述本身：没有就留空，
    // 折叠行便只显示操作名，不拿命令冒充小标题（同一句话在一行里出现两次最难看）。
    // 两种载荷形状都认：`{description:"…"}` 与 `{description:{text:"…"}}`。
    val subtitle = firstOf(
        arguments.str("description"),
        arguments.obj("description")?.str("text") ?: "",
        data.str("description"),
    ).trim()
    // 摘要：优先命令（最有信息量），其次技能名，再次路径/查询，最后空。
    val detail = firstOf(command, skill, path, query)
    val label = when {
        command.isNotEmpty() -> "执行 " + clipText(command, 48)
        skill.isNotEmpty() -> "技能 " + skill
        name.isNotEmpty() -> prettyToolName(name)
        else -> "调用工具"
    }
    return ParsedToolCall(
        callId = callId,
        name = name,
        detail = detail,
        subtitle = subtitle,
        command = command,
        label = label,
        skill = skill,
        commandText = if (command.isNotEmpty()) command else detail,
    )
}

/**
 * 读 `tool/result`。输出可能是字符串、content 数组、或 `{stdout,stderr}` 一类结构。
 *
 * 顺带把 `isError` 之类留在这里判断（投影器只关心"有没有内容"与"成不成功"），
 * 所以 [Projector] 里不再重复解析。
 */
internal fun toolResultOf(data: JsonObject): ParsedToolResult {
    val callId = firstOf(
        data.str("callId"),
        data.str("toolCallId"),
        data.str("id"),
        data.obj("toolCall")?.str("callId") ?: "",
    )
    val message = data.obj("message")
    val output = firstNonBlank(
        textContent(data["content"]),
        textContent(message?.let { it["content"] }),
        data.str("output"),
        data.str("text"),
        data.str("result") ?: "",
        data.obj("result")?.let { textContent(it["content"]) } ?: "",
        data.obj("result")?.str("output") ?: "",
        data.obj("result")?.str("stdout") ?: "",
    )
    return ParsedToolResult(callId = callId, output = trimToLimit(output, 20_000))
}

/**
 * 参数对象：`arguments`/`input`/`args`，三处里第一篇 JSON 对象。
 *
 * 宿主的参数是 JSON **字符串**（`"{\"command\":\"npm test\"}"`）而不是对象，
 * 这是最常见的形状；也兼容已经是对象的版本。解析失败返回空对象（不是 null），
 * 让调用方无需判空。
 */
internal fun toolArgumentsOf(data: JsonObject): JsonObject {
    for (key in ARGUMENT_KEYS) {
        val raw = data[key] ?: continue
        when (raw) {
            is JsonObject -> return raw
            is JsonPrimitive -> {
                if (raw is JsonNull) continue
                val parsed = parseJson(raw.content)
                if (parsed != null) return parsed
                // 不是 JSON 但有内容：当作只有一行参数的退化形态。
                val text = raw.content.trim()
                if (text.isNotEmpty()) return JsonObject(mapOf("command" to JsonPrimitive(text)))
            }
            is JsonArray -> {
                // 少数版本给的是位置参数数组。
                val list = raw.map { contentOf(it) }.filter { it.isNotEmpty() }
                if (list.isNotEmpty()) {
                    return JsonObject(mapOf("command" to JsonPrimitive(list.joinToString(" "))))
                }
            }
        }
    }
    return JsonObject(emptyMap())
}

private val ARGUMENT_KEYS = arrayOf("arguments", "input", "args", "params", "parameters")

/**
 * 工具名的展示形态：`ops_bash` → `ops_bash`（原样保留），但把明显的 MCP 长名截短。
 * 只裁长度，不做语义猜测 —— 名字是用户认工具的唯一标识，改写反而会误导。
 */
private fun prettyToolName(name: String): String = clipText(name, 40)

private fun firstOf(vararg values: String): String = values.firstOrNull { it.isNotEmpty() } ?: ""

private fun firstNonBlank(vararg values: String): String =
    values.firstOrNull { it.isNotBlank() }?.trim() ?: ""

private fun trimToLimit(text: String, max: Int): String =
    if (text.length <= max) text else text.substring(0, max) + "\n…（已截断）"

/** 工具事件的类型判据：`tool/result` 与 `tool/call` 走同一个 kind，靠这里分流。 */
internal fun JsonElement?.isResultType(): Boolean =
    contentOf(this).let { it.equals("tool/result", ignoreCase = true) }
