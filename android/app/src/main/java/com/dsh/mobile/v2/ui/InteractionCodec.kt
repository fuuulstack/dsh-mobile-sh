package com.dsh.mobile.v2.ui

import com.dsh.mobile.v2.data.InteractionLedgerEntry
import com.dsh.mobile.v2.data.LedgerState
import com.dsh.mobile.v2.data.Model
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 审批/提问卡片的跨重启编码。
 *
 * <p>卡片本身只是几个字符串字段，用 `kotlinx.serialization` 给它加 `@Serializable`
 * 会连带要求 `Model.Interaction` 整条继承链可序列化，而那张卡片是要被 UI 直接持有、
 * 随手改草稿状态的普通类。所以手写一对纯函数：编码成一条 JSON 字符串存进
 * `interaction_ledger.payload_json`，读回来重建一个全新的卡片对象。
 *
 * <p>`interactionId` 一律 `"${sessionId}:${seq}"`（与投影器、插件侧同一口径）。
 * 严禁时间戳：宿主在新连接上会重投未结算的 waterfall，用时间戳做 id 会让每次重投
 * 都变成一张新卡。
 */
object InteractionCodec {

    fun encode(
        sessionId: String,
        card: Model.Interaction,
        seq: Long,
        now: Long,
    ): InteractionLedgerEntry? {
        if (sessionId.isEmpty() || card.eventId.isEmpty()) return null
        val id = interactionIdOf(sessionId, seq, card.eventId)
        val kind: String
        val payload: JsonObject
        when (card) {
            is Model.Approval -> {
                kind = KIND_APPROVAL
                payload = buildJsonObject {
                    put("eventId", card.eventId)
                    put("agentId", card.agentId)
                    put("toolName", card.toolName)
                    put("callId", card.callId)
                    put("reason", card.reason)
                    put("displayReason", card.displayReason)
                    put("answered", card.answered)
                    put("submitted", card.submitted)
                }
            }

            is Model.QuestionCard -> {
                kind = KIND_QUESTION
                payload = buildJsonObject {
                    put("eventId", card.eventId)
                    put("agentId", card.agentId)
                    put("callId", card.callId)
                    put("answered", card.answered)
                    put("submitted", card.submitted)
                    putJsonArray("questions") {
                        for (q in card.questions) {
                            add(
                                buildJsonObject {
                                    put("id", q.id)
                                    put("question", q.question)
                                    put("detail", q.detail)
                                    put("header", q.header)
                                    put("multiSelect", q.multiSelect)
                                    putJsonArray("options") {
                                        for (o in q.options) {
                                            add(
                                                buildJsonObject {
                                                    put("label", o.label)
                                                    put("description", o.description)
                                                }
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }

            else -> return null
        }
        return InteractionLedgerEntry(
            sessionId = sessionId,
            interactionId = id,
            kind = kind,
            state = LedgerState.PENDING,
            payloadJson = payload.toString(),
            updatedAt = now,
        )
    }

    /**
     * 读回一张卡。
     *
     * <p>已经点过或已结算的条目由调用方按 [InteractionLedgerEntry.acceptsReplay] 过滤，
     * 这里只管解码。字段缺失一律退默认值——历史行是上一个版本写的，格式只会更少。
     */
    fun decode(entry: InteractionLedgerEntry): Model.Interaction? {
        val json = runCatching { Json.parseToJsonElement(entry.payloadJson).jsonObject }.getOrNull() ?: return null
        return when (entry.kind) {
            KIND_APPROVAL -> Model.Approval().apply {
                eventId = json.str("eventId")
                agentId = json.str("agentId")
                toolName = json.str("toolName")
                callId = json.str("callId")
                reason = json.str("reason")
                displayReason = json.str("displayReason")
                answered = json.flag("answered")
                submitted = json.flag("submitted")
            }

            KIND_QUESTION -> Model.QuestionCard().apply {
                eventId = json.str("eventId")
                agentId = json.str("agentId")
                callId = json.str("callId")
                answered = json.flag("answered")
                submitted = json.flag("submitted")
                val list = json["questions"] as? kotlinx.serialization.json.JsonArray ?: return@apply
                for (raw in list) {
                    val o = raw as? JsonObject ?: continue
                    questions.add(
                        Model.Question().apply {
                            id = o.str("id")
                            question = o.str("question")
                            detail = o.str("detail")
                            header = o.str("header")
                            multiSelect = o.flag("multiSelect")
                            val options = o["options"] as? kotlinx.serialization.json.JsonArray ?: return@apply
                            for (item in options) {
                                val opt = item as? JsonObject ?: continue
                                this.options.add(
                                    Model.QuestionOption().apply {
                                        label = opt.str("label")
                                        description = opt.str("description")
                                    }
                                )
                            }
                        }
                    )
                }
            }

            else -> null
        }
    }

    /** 与投影器、插件同一口径：会话 + seq。 */
    fun interactionIdOf(sessionId: String, seq: Long, eventId: String): String =
        if (seq > 0) "$sessionId:$seq" else "$sessionId:$eventId"

    private const val KIND_APPROVAL = "APPROVAL"
    private const val KIND_QUESTION = "QUESTION"

    private fun JsonObject.str(name: String): String =
        runCatching { (this[name] as? JsonPrimitive)?.jsonPrimitive?.content ?: "" }.getOrDefault("")

    private fun JsonObject.flag(name: String): Boolean =
        runCatching { this[name]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false }.getOrDefault(false)
}
