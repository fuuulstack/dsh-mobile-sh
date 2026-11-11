package com.dsh.mobile.v2.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 临时诊断日志（v2）：写进应用私有目录的 files/diag.log。
 *
 * logcat 缓冲区留不住应用日志，实时推流问题必须看到「应用到底收到了哪些帧」，
 * 落到文件里用 `adb shell run-as com.dsh.mobile.v2 cat files/diag.log` 读。
 *
 * 时间戳在调用线程格式化（纯内存、微秒级），磁盘写全部进单线程队列：
 * 这个日志挂在每条会话解析和流帧回调上，任何一次主线程写盘都是可感知的卡顿。
 *
 * 格式化器必须无状态：调用点横跨主线程与 `Dispatchers.IO`（`ChatViewModel` 的 actorScope），
 * `SimpleDateFormat` 是可变对象，并发 `format` 会互相踩 calendar，吐出串行的时间戳甚至
 * `ArrayIndexOutOfBoundsException`。`DateTimeFormatter` 不可变，天然可共享。
 */
object Diag {
    private const val MAX_BYTES = 256 * 1024
    private val fmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)
    private var file: File? = null

    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dsh-v2-diag").apply { isDaemon = true }
    }

    fun init(context: android.content.Context) {
        file = File(context.filesDir, "diag.log")
    }

    /** 帧字段读取工具（失败返回 null，诊断日志绝不能因坏帧崩溃）。 */
    private fun JsonObject.str(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

    private fun JsonObject.num(name: String): Long? =
        (this[name] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.let { runCatching { it.long }.getOrNull() }

    private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

    /** 一行摘要：流帧的类型与关键字段，够判断「收到了什么、有没有断」。 */
    /**
     * 这一帧算不算「有用的」帧。
     *
     * <p>看门狗的判据必须是**业务帧**，不能是「收到任何 WS 消息」。`snapshot-begin`、
     * `snapshot-end`、`ack` 这些控制帧会周期性地自己冒出来（尤其重放时每帧都带），
     * 拿它们当活性证据就等于看门狗自己被喂饱——连接半死时照样每 10 秒清零计时，
     * 永远等不到 45 秒。
     *
     * <p>反过来 `assistant-stream` 的每个 chunk、`event`、`snapshot-records` 里的真实
     * 记录都是内容推进，算。
     */
    fun isUsefulFrame(frame: JsonObject): Boolean = when (frame.str("type")) {
        "event" -> true
        "assistant-stream" -> true
        "snapshot-records" -> (frame["records"] as? JsonArray)?.isNotEmpty() == true
        "messages", "applied" -> true
        else -> false
    }

    fun describe(frame: JsonObject): String {
        val type = frame.str("type") ?: "?"
        if (type == "event") {
            val event = frame.obj("event") ?: return "event(null)"
            return "event " + (event.str("type") ?: "?") + " seq=" + (event.num("seq") ?: 0) +
                " data=" + (if (event["data"] is JsonPrimitive) "omitted" else "obj")
        }
        if (type == "assistant-stream") {
            val f = frame.obj("frame") ?: return "assistant-stream(null)"
            val chunk = f.obj("chunk")
            val chunkType = chunk?.str("type") ?: "-"
            val chunkText = chunk?.str("delta") ?: chunk?.str("text") ?: ""
            return "assistant-stream " + (f.str("type") ?: "?") +
                " idx=" + (f["index"]?.let { (it as? JsonPrimitive)?.let { p -> runCatching { p.int }.getOrNull() } } ?: -1) +
                " chunk=$chunkType len=${chunkText.length}"
        }
        if (type == "snapshot") {
            val records = (frame["records"] as? JsonArray)?.size ?: 0
            return "snapshot records=$records cursor=" + (frame.num("cursor") ?: -1)
        }
        if (type == "waterfall") return "waterfall event=" + (frame.str("event") ?: "") +
            " eventId=" + (frame.str("eventId") ?: "")
        if (type == "ready") return "ready clientId=" + (frame.str("clientId") ?: "")
        return type
    }

    fun log(tag: String, message: String) {
        val f = file ?: return
        val line = fmt.format(LocalTime.now()) + " " + tag + " " + message + System.lineSeparator()
        writer.execute {
            try {
                if (f.exists() && f.length() > MAX_BYTES) {
                    val old = File(f.parentFile, "diag.old.log")
                    if (old.exists()) old.delete()
                    if (!f.renameTo(old)) return@execute
                }
                f.appendText(line)
            } catch (ignored: Exception) {
                // 诊断日志永远不能影响主流程
            }
        }
    }
}
