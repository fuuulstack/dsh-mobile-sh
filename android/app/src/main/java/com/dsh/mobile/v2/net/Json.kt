package com.dsh.mobile.v2.net

import com.dsh.mobile.v2.data.Model
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * JSON 访问工具（顶层扩展）：把「字段可能缺、类型可能不对」的宿主 JSON 一次性兜住。
 * 所有函数失败都返回中性值（空串/0/false/null），绝不在解析路径上抛异常。
 *
 * 用法：`import com.dsh.mobile.v2.net.*` 后 `frame.str("type")`、`data.long("seq")` 直用。
 */

/** 字符串字段；JsonNull / 非原始类型都按空串处理。 */
internal fun JsonObject?.str(name: String): String {
    val v = this?.get(name) ?: return ""
    return (v as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: ""
}

/** 长整型字段；解析失败返回 0。 */
internal fun JsonObject?.long(name: String): Long {
    val v = this?.get(name) ?: return 0L
    return (v as? JsonPrimitive)?.longOrNull ?: 0L
}

/** 整型字段；解析失败返回 0。 */
internal fun JsonObject?.int(name: String): Int {
    val v = this?.get(name) ?: return 0
    return (v as? JsonPrimitive)?.intOrNull ?: 0
}

/** 布尔字段；缺省取 fallback。 */
internal fun JsonObject?.bool(name: String, fallback: Boolean = false): Boolean {
    val v = this?.get(name) ?: return fallback
    return (v as? JsonPrimitive)?.booleanOrNull ?: fallback
}

/** 可空布尔字段：字段缺失 / JsonNull / 解析不出来都返回 null。
 *
 *  与 [bool] 的区别是「不知道」和「false」不是一回事：老插件根本不发这个字段，
 *  把它读成 false 等于凭空宣布子代理已经跑完（胶囊会莫名其妙消失）。 */
internal fun JsonObject?.boolOrNull(name: String): Boolean? {
    val v = this?.get(name) ?: return null
    if (v is JsonNull) return null
    return (v as? JsonPrimitive)?.booleanOrNull
}

/** 对象字段。 */
internal fun JsonObject?.obj(name: String): JsonObject? = this?.get(name) as? JsonObject

/** 数组字段。 */
internal fun JsonObject?.arr(name: String): JsonArray? = this?.get(name) as? JsonArray

/** 原始值转字符串（数字/布尔也能读）；对象/数组返回空。 */
internal fun contentOf(value: JsonElement?): String =
    (value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: ""

/** content parts 的宽松取文：{type:"text"}、{type:"json",text}、字符串片段都收。 */
internal fun looseContent(value: JsonElement?): String {
    if (value == null) return ""
    if (value is JsonPrimitive) return value.content
    if (value !is JsonArray) return ""
    val sb = StringBuilder()
    for (part in value) {
        if (part is JsonPrimitive) { sb.append(part.content); continue }
        if (part !is JsonObject) continue
        var text = part.str("text")
        if (text.isEmpty()) text = part.str("content")
        if (text.isEmpty()) {
            val data = part["data"]
            if (data != null) text = contentOf(data)
        }
        if (text.isEmpty()) continue
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append(text)
    }
    return sb.toString()
}

/** content parts 里 type=text 的拼接（与 v1 ChatState.content 同口径）。 */
internal fun textContent(value: JsonElement?): String {
    if (value !is JsonArray) return ""
    val sb = StringBuilder()
    for (part in value) {
        if (part is JsonObject && part.str("type") == "text") sb.append(part.str("text"))
    }
    return sb.toString()
}

/** content parts 里的 image 引用（attachmentId + name），兼容嵌套与扁平两种形态。 */
internal fun imageRefs(value: JsonElement?): List<Model.ImageRef> {
    if (value !is JsonArray) return emptyList()
    val refs = ArrayList<Model.ImageRef>()
    for (part in value) {
        if (part !is JsonObject || part.str("type") != "image") continue
        val source = part.obj("attachment") ?: part
        val id = source.str("attachmentId").ifEmpty { source.str("id") }
        if (id.isEmpty()) continue
        refs.add(Model.ImageRef(attachmentId = id, name = source.str("name").ifEmpty { part.str("name") }))
    }
    return refs
}

/**
 * 回放记录里「确实带助手内容」的回合集合。
 *
 * 面板的思考与步骤唯一来源是 `assistant/message`：只有这类事件落在回放范围内，
 * 该回合的面板才会被真正重建。若只凭 `turn/start`、`turn/end` 这类事件就认定
 * 「回放会重建」，增量快照（since>0，常常只带一条 `turn/end`）一回来，缓存里那些
 * 完整的旧面板就会被整块跳过丢掉 —— 真机现象是「重进会话，深度思考与处理详情静默消失」。
 */
internal fun turnsWithAssistantContent(records: JsonArray?): Set<Long> {
    val turns = HashSet<Long>()
    if (records == null) return turns
    for (i in 0 until records.size) {
        val r = records[i] as? JsonObject ?: continue
        val event = r.obj("event") ?: continue
        if (event.str("type") != "assistant/message") continue
        val data = event.obj("data") ?: continue
        val turn = data.long("turn").let { if (it == 0L && !data.containsKey("turn")) -1L else it }
        if (turn >= 0) turns.add(turn)
    }
    return turns
}

/**
 * 解析宿主的任务清单（`TodoItem[]`）。
 *
 * 形状：`[{content, status}]`，status ∈ pending / in_progress / completed。
 * 宿主是整表覆盖语义；这里只做形状容错（字符串条目按 pending 收），
 * 状态字段缺省填 pending，不认识的词也留在原文里由 `Model.Todo` 判。
 */
internal fun todosOf(value: JsonElement?): List<Model.Todo> {
    val array = value as? JsonArray ?: return emptyList()
    val out = ArrayList<Model.Todo>(array.size)
    for (item in array) {
        // 容错：老宿主/第三方适配器可能只下发 content 字符串。
        if (item is JsonPrimitive) {
            val text = contentOf(item)
            if (text.isNotEmpty()) out.add(Model.Todo(content = text))
            continue
        }
        val o = item as? JsonObject ?: continue
        val text = o.str("content")
        if (text.isEmpty()) continue
        out.add(Model.Todo(content = text, status = o.str("status").ifEmpty { "pending" }))
    }
    return out
}

/**
 * 解析宿主的子智能体目录（`session/projections` 的 `values.subagentCatalog`）。
 *
 * <p>宿主侧形状是
 * `{id, createdAt} & ({mode:'one-shot'|'external'|'continuable'|'unknown', label?})`。
 * 端侧只留「哪一条、叫什么、什么模式、什么时候起的」四样：
 * - `label` 只在 `continuable` 上一定存在，其余模式可选——缺了就留空串，
 *   界面按「没有标签」画，不能画成字面的 undefined；
 * - 没有 `id` 的条目直接丢掉：胶囊点开的清单拿它当 key，留一个幽灵条目
 *   只会让列表崩或者点开一片空白。
 */
internal fun subagentsOf(value: JsonElement?): List<Model.Subagent> {
    val array = value as? JsonArray ?: return emptyList()
    val out = ArrayList<Model.Subagent>(array.size)
    for (item in array) {
        val o = item as? JsonObject ?: continue
        val id = o.str("id")
        if (id.isEmpty()) continue
        out.add(
            Model.Subagent(
                id = id,
                label = o.str("label"),
                mode = o.str("mode"),
                createdAt = o.long("createdAt"),
                running = o.boolOrNull("running"),
            ),
        )
    }
    return out
}

/**
 * 解析宿主 inbox（`session/projections` 的 `values.inbox`）。
 *
 * 形状是 `{"next-turn": <message 列表>, "next-step": <message 列表>}`，其中
 * message = `{id, role:"user", content:<parts>, source}`。
 * 端侧只关心「哪条、什么文本、几张图、是整轮还是插步」，其余字段丢掉。
 * 两个桶按 next-turn → next-step 的顺序拼成一个列表：宿主先领 next-turn，
 * 界面上也就按这个顺序排队展示。
 */
internal fun queueOf(value: JsonElement?): List<Model.QueuedMessage> {
    val inbox = value as? JsonObject ?: return emptyList()
    val out = ArrayList<Model.QueuedMessage>()
    for (target in QUEUE_TARGETS) {
        val bucket = inbox.arr(target) ?: continue
        for (item in bucket) {
            val o = item as? JsonObject ?: continue
            queuedOf(o, target)?.let { out.add(it) }
        }
    }
    return out
}

/**
 * 解析一条 inbox 消息（队列投影与 splice 事件共用同一口径）。
 *
 * 没有 id 的条目直接丢掉：编辑/删除/插话全靠 id 定位，留着一个没有 id 的
 * 幽灵条目只会让用户点了没反应。
 */
internal fun queuedOf(o: JsonObject, target: String): Model.QueuedMessage? {
    val id = o.str("id")
    if (id.isEmpty()) return null
    val content = o["content"]
    var images = 0
    if (content is JsonArray) {
        for (part in content) {
            if (part is JsonObject && part.str("type") == "image") images++
        }
    }
    return Model.QueuedMessage(
        id = id,
        text = textContent(content).trim(),
        target = target,
        imageCount = images,
    )
}

/** 宿主 inbox 的两个桶，顺序即展示顺序。 */
internal val QUEUE_TARGETS = listOf("next-turn", "next-step")

/** 取本地化文案：字符串原样，{en,zh} 结构取 zh → en；其它类型退化为空串。 */
internal fun localizedText(value: JsonElement?): String {
    if (value == null) return ""
    if (value is JsonPrimitive) return value.content
    if (value is JsonObject) {
        val zh = value.str("zh")
        if (zh.isNotEmpty()) return zh
        return value.str("en")
    }
    return ""
}

/** 裁剪到 max 字符（超长补省略号）。 */
internal fun clipText(value: String?, max: Int): String {
    if (value == null) return ""
    val text = value.trim()
    if (text.length <= max) return text
    return text.substring(0, max) + "…"
}

/** 解析一个 JSON 字符串为对象；失败返回 null。 */
internal fun parseJson(text: String): JsonObject? = try {
    Json.parseToJsonElement(text) as? JsonObject
} catch (ignored: Exception) {
    null
}
