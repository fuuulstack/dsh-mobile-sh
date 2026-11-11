package com.dsh.mobile.v2.net

import java.io.ByteArrayOutputStream
import com.dsh.mobile.v2.BuildConfig
import com.dsh.mobile.v2.data.Diag
import com.dsh.mobile.v2.data.LocalAckedSeq
import com.dsh.mobile.v2.data.RecordKind
import com.dsh.mobile.v2.data.TimelineRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * v2 协议面（Android 侧）：`/v2/sync` 分页 + `/v2/control` 实时控制流。
 *
 * 与 v1 的根本区别：手机不再解析 DSH 自己的事件信封。两条通路（归档分页与实时
 * 流）下发的都是同一种 [TimelineRecord]，所以手机只有一个投影器（`Projector.kt`），
 * 不存在「实时路径与归档路径形状不一致」这一类 bug——v1 里那是有两张解析器的。
 *
 * 本类只做编解码与 socket 管理，不碰 Room、不看水位：水位推进由 `ChatSyncActor`
 * 用 `@Transaction` 完成（先落盘后推进，设计 §4.3）。这样单飞、退避、半死判定
 * 都能在纯 Kotlin 里用假时钟测。
 */
class RelayApiV2(private val api: RelayApi) {

    // ============================================================
    // 回调契约
    // ============================================================

    /** 一页归档正文。 */
    fun interface SyncResult {
        fun done(page: SyncPage?, error: String?, retryAfterMs: Long)
    }

    /** 控制流（`/v2/control`）的帧回调。 */
    interface ControlFeed {
        /** 插件报的 build_hash / 能力 / worker epoch，用于设置页显示三端是否同批。 */
        fun hello(ack: HelloAck) {}

        /** 一批实时记录（与归档记录逐字节同构）。 */
        fun records(frame: RecordsFrame)

        /** 5 秒一次的脉搏。**这是唯一的「链路还活着」证据**，不是心跳。 */
        fun pulse(pulse: SessionPulse)

        /** 手机发的写操作被插件确认（或失败）。 */
        fun ack(ack: CommandAck) {}

        /** 插件主动报错（例如 `focus` 了一个不存在的会话）。 */
        fun error(code: String, message: String) {}

        /**
         * 审批 / 提问瀑布（宿主 `$events` 的 `waterfall` / `cancel`），`payload` 是宿主原样
         * 的帧值。
         *
         * <p>为什么由控制流承载而不是自己再开一条：v1 有一条 app 级的 `$events` WS 专门
         * 收它，那条路由已随 v1 一起删除。插件是唯一同时握着宿主 `$events` 与手机控制
         * 通道的进程，所以由它转——并且它会在每次订阅时把**未结算**的瀑布重投一遍
         * （`packages/plugin/src/hub.ts` 的尾部窗口），因为瀑布不落盘，断线后没有任何
         * 别的东西能把它补回来。
         */
        fun interaction(kind: String, payload: JsonObject?) {}

        /** socket 状态变化。`connected=false` 时不必自己重连——本类会退避重连。 */
        fun state(note: String, connected: Boolean)
    }

    // ============================================================
    // 线上形状
    // ============================================================

    /**
     * 一页 `/v2/sync` 响应。
     *
     * `records` 在中继里是**字符串原文**（解密后不 parse、不 stringify），到这里才
     * 第一次解析。单条解析失败只跳过那一行并计入 [skipped]：一条坏行不该让整页
     * 作废，更不该让水位停住——那会把一次版本不匹配变成永久的同步卡死。
     */
    data class SyncPage(
        val sessionId: String,
        val reqAfterSeq: Long,
        val nextCursorSeq: Long,
        val archiveCoveredSeq: Long,
        val hasMore: Boolean,
        val stopReason: String,
        val records: List<TimelineRecord>,
        val bytes: Long,
        val skipped: Int,
    )

    data class HelloAck(val buildHash: String, val capabilities: List<String>, val workerEpoch: Long)

    data class RecordsFrame(
        val sessionId: String,
        val records: List<TimelineRecord>,
        val hasMore: Boolean,
        val stopReason: String,
    )

    data class SessionPulse(
        val sessionId: String,
        val latestSeq: Long,
        val archiveCoveredSeq: Long,
        val hostState: String,
        val silentDurationMs: Long,
        val tunnelBufferBytes: Long,
        val workerEpoch: Long,
    ) {
        /** 宿主在推理但一个字节都没产出——UI 该显示「正在深度思考 (Ns)」而不是装死。 */
        val thinkingSilent: Boolean get() = hostState == "THINKING_SILENT"
    }

    data class CommandAck(val cmdId: String, val ok: Boolean, val error: String)

    // ============================================================
    // /v2/sync
    // ============================================================

    /**
     * 拉一页归档正文。
     *
     * [afterSeq] 必须是 `local_acked_seq`——`LocalAckedSeq` 这个 value class 的存在
     * 就是为了把「误传 server_meta_seq」变成编译错误（设计 §invariant 2）。
     * 高 [priority] 的会话独占字节预算：前台刚打开的会话不会被后台几十个会话的
     * 冷启动补差挤成空页（bug B9）。
     */
    fun sync(
        hostId: String,
        sessionId: String,
        afterSeq: LocalAckedSeq,
        mode: String,
        priority: Int,
        pageByteBudget: Int,
        result: SyncResult,
    ) {
        try {
            val body = buildJsonObject {
                put("mode", mode)
                put("pageByteBudget", pageByteBudget)
                putJsonArray("sessions") {
                    add(
                        buildJsonObject {
                            put("sessionId", sessionId)
                            put("afterSeq", afterSeq.value)
                            put("priority", priority)
                        }
                    )
                }
            }
            api.request("/native/v2/hosts/$hostId/sync", body, true, RelayApi.Result { value, error ->
                if (error != null) {
                    // 429 是「同一个 afterSeq 反复问」的死循环检测，不是限流：
                    // 调用方要等，立刻重试只会再撞一次。
                    if (error.contains("（429）")) {
                        result.done(null, "sync-loop", SYNC_LOOP_BACKOFF_MS)
                        return@Result
                    }
                    if (error.contains("（404）")) {
                        result.done(null, "sync-unsupported", 0)
                        return@Result
                    }
                    result.done(null, error, 0)
                    return@Result
                }
                val page = try {
                    parseSyncPage(value, sessionId)
                } catch (e: Exception) {
                    Diag.log("sync2", "parse ${e.javaClass.simpleName}: ${e.message}")
                    result.done(null, "中继响应格式不兼容，请检查服务版本", 0)
                    return@Result
                }
                result.done(page, null, 0)
            })
        } catch (e: Exception) {
            result.done(null, "无法构造请求", 0)
        }
    }

    /**
     * 后台批量补差：一次请求带多个会话，让中继按 priority 独占总预算。
     *
     * <p>为什么必须能一次带多个：冷启动时四十多个会话落后是常态，逐个单发就是
     * 四十多次往返，而中继对每个会话都要单独开一次读事务。批量口子让「补 31 个
     * 会话」变成一两次请求，也是 v2 文档里 `BACKGROUND_BATCH` 存在的唯一理由。
     *
     * <p>与单会话版共用同一份响应解析：中继对每个会话各回一条 result，顺序与
     * priority 相关，所以这里按 sessionId 回填，不靠下标对位。
     */
    fun syncBatch(
        hostId: String,
        sessions: List<BatchSession>,
        mode: String = "BACKGROUND_BATCH",
        pageByteBudget: Int = DEFAULT_PAGE_BUDGET,
        result: BatchResult,
    ) {
        if (sessions.isEmpty()) {
            result.done(emptyList(), null, 0)
            return
        }
        try {
            val body = buildJsonObject {
                put("mode", mode)
                put("pageByteBudget", pageByteBudget)
                putJsonArray("sessions") {
                    for (session in sessions) {
                        add(
                            buildJsonObject {
                                put("sessionId", session.sessionId)
                                // afterSeq 只能来自本地落盘水位：用元数据水位会让
                                // 中继认为「你都齐了」，从此一个字节都不下发。
                                put("afterSeq", session.afterSeq.value)
                                put("priority", session.priority)
                            }
                        )
                    }
                }
            }
            api.request("/native/v2/hosts/$hostId/sync", body, true, RelayApi.Result { value, error ->
                if (error != null) {
                    when {
                        error.contains("（429）") -> result.done(emptyList(), "sync-loop", SYNC_LOOP_BACKOFF_MS)
                        error.contains("（404）") -> result.done(emptyList(), "sync-unsupported", 0)
                        else -> result.done(emptyList(), error, 0)
                    }
                    return@Result
                }
                val pages = try {
                    parseSyncPages(value)
                } catch (e: Exception) {
                    Diag.log("sync2", "batch parse ${e.javaClass.simpleName}: ${e.message}")
                    result.done(emptyList(), "中继响应格式不兼容，请检查服务版本", 0)
                    return@Result
                }
                result.done(pages, null, 0)
            })
        } catch (e: Exception) {
            result.done(emptyList(), "无法构造请求", 0)
        }
    }

    /** 一次批量同步里点名的一个会话。 */
    data class BatchSession(val sessionId: String, val afterSeq: LocalAckedSeq, val priority: Int)

    fun interface BatchResult {
        fun done(pages: List<SyncPage>, error: String?, retryAfterMs: Long)
    }

    private fun parseSyncPages(value: JsonObject?): List<SyncPage> {
        val rows = value?.arr("results") ?: return emptyList()
        val pages = ArrayList<SyncPage>(rows.size)
        for (element in rows) {
            val row = element as? JsonObject ?: continue
            val page = syncPageOf(row, row.str("sessionId"))
            if (page != null) pages.add(page)
        }
        return pages
    }

    // ============================================================
    // /v2/control（单条常驻 WSS）
    // ============================================================

    private var socket: WebSocket? = null
    private var generation = 0
    private var hostId = ""
    private var reconnectAttempts = 0

    @Volatile
    private var open = false

    /** 控制流是否真的活着。引用非空不等于活着：onFailure 之后引用还在。 */
    fun alive(): Boolean = open && socket != null

    /**
     * 打开（或重开）控制流。
     *
     * 重开**必须**带 `FOCUS_SESSION(sinceSeq=local_acked_seq)`：断流期间漏掉的
     * 记录只有靠插件的尾部窗口重放补回来，而手机端水位没动过，所以这次重放是
     * 增量的、不重复的。v1 的看门狗在这里做错了——它重放的是整段历史。
     */
    fun openControl(host: String, sessionId: String, sinceSeq: LocalAckedSeq, feed: ControlFeed) {
        hostId = host
        closeControl()
        val epoch = generation
        val builder = Request.Builder()
            .url(wsUrl(api.origin) + "/native/v2/hosts/$host/control")
            .header("Authorization", "Bearer ${api.token}")
        socket = api.controlClient.newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(socket: WebSocket, response: Response) {
                Diag.log("control", "ws open")
                open = true
                reconnectAttempts = 0
                // 握手：报自己的 build_hash，让插件在设置页暴露三端版本是否同批。
                send(socket, buildJsonObject {
                    put("type", "HELLO")
                    put("buildHash", BuildConfig.BUILD_HASH)
                    put("capabilities", JsonArray(listOf(JsonPrimitive("records"), JsonPrimitive("pulse"), JsonPrimitive("cmd"))))
                })
                send(socket, buildJsonObject {
                    put("type", "FOCUS_SESSION")
                    put("sessionId", sessionId)
                    put("sinceSeq", sinceSeq.value)
                })
                api.post { if (epoch == generation) feed.state("已连接", true) }
            }

            override fun onMessage(socket: WebSocket, text: String) {
                if (text.length > CONTROL_MESSAGE_LIMIT) return
                RelayApi.trafficDown.addAndGet(text.toByteArray(Charsets.UTF_8).size.toLong())
                // tag 不是 dsh-v2 的帧一律忽略：滚动升级期间这条路径可能夹带外来帧，
                // 收到不认识的东西就拆 socket 会把一次版本混跑放大成永久掉线。
                val frame = decodeMuxFrame(text) ?: return
                val type = frame.str("type")
                when (type) {
                    "HELLO_ACK" -> {
                        val ack = HelloAck(
                            frame.str("buildHash"),
                            frame.arr("capabilities")?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
                            frame.long("workerEpoch"),
                        )
                        api.post { if (epoch == generation) feed.hello(ack) }
                    }
                    "RECORDS" -> {
                        val parsed = frame.arr("records")?.mapNotNull { recordOf(it) } ?: emptyList()
                        val value = RecordsFrame(
                            frame.str("sessionId"),
                            parsed,
                            frame.bool("hasMore"),
                            frame.str("stopReason"),
                        )
                        api.post { if (epoch == generation) feed.records(value) }
                    }
                    "SESSION_PULSE" -> {
                        val value = SessionPulse(
                            frame.str("sessionId"),
                            frame.long("latestSeq"),
                            frame.long("archiveCoveredSeq"),
                            frame.str("hostState"),
                            frame.long("silentDurationMs"),
                            frame.long("tunnelBufferBytes"),
                            frame.long("workerEpoch"),
                        )
                        api.post { if (epoch == generation) feed.pulse(value) }
                    }
                    "CMD_ACK" -> {
                        val value = CommandAck(frame.str("cmdId"), frame.bool("ok"), frame.str("error"))
                        api.post { if (epoch == generation) feed.ack(value) }
                    }
                    // 审批 / 提问瀑布。插件转的，`payload` 是宿主 `$events` 帧的原样值
                    // （`{type,event,eventId,agentId,request}` 或 `{type:"cancel",eventId}`）。
                    //
                    // **不过滤会话**：`agentId` 就是会话 id，但空值也要收——`handleEvent`
                    // 那边按 `interactionBelongs` 判归属，草稿页/字段缺失都放行。在这一层
                    // 拦会把「卡片通知」也一起拦掉，那是跨会话可见的正经功能。
                    "INTERACTION" -> {
                        val kind = frame.str("kind")
                        val payload = frame.obj("payload")
                        api.post { if (epoch == generation) feed.interaction(kind, payload) }
                    }
                    "ERROR" -> {
                        val code = frame.str("code")
                        val message = frame.str("message")
                        api.post { if (epoch == generation) feed.error(code, message) }
                    }
                }
            }

            override fun onFailure(socket: WebSocket, error: Throwable, response: Response?) {
                open = false
                Diag.log("control", "onFailure $error code=${response?.code ?: -1}")
                api.post { if (epoch != generation) return@post; feed.state("控制流断开：${error.javaClass.simpleName}", false) }
            }

            override fun onClosed(socket: WebSocket, code: Int, reason: String) {
                open = false
                Diag.log("control", "closed $code $reason")
                api.post { if (epoch != generation) return@post; feed.state("控制流已关闭（$code）", false) }
            }
        })
    }

    fun closeControl() {
        generation++
        open = false
        socket?.cancel()
        socket = null
    }

    /** 手机发一条写操作。幂等键 [cmdId] 由调用方生成并在 HTTPS 旁路重试时复用。 */
    /**
     * 取回一条被离载的巨型正文（`GET /v2/blobs/:blobId`）。
     *
     * <p>为什么要有这条通路：单条 payload 超过 256 KiB 时插件只把存根塞进时间线，
     * 真实字节留在中继的加密 blob 表里。没有这一步，手机上那条回复就永远只剩一行
     * 「这条内容很大」——验收 #6 明确要求「5MB 的一条要么能下、要么明确报错」，
     * 只显示占位符属于第三种结果。
     *
     * <p>与 `/v2/sync` 分开是刻意的：分页是「按序推进水位」，取 blob 是「补齐一条
     * 已经落盘但正文缺失的记录」。混在一起会让一条 5MB 记录卡住整个会话的补差。
     */
    fun fetchBlob(hostId: String, blobId: String, result: (text: String?, error: String?) -> Unit) {
        if (hostId.isEmpty() || blobId.isEmpty()) {
            result(null, "blob-unavailable")
            return
        }
        fetchBlobFrom(hostId, blobId, 0, ByteArrayOutputStream(), 0, result)
    }

    /**
     * 一次续传尝试；`offset` 是已经拿到的字节数，也正好是下一段 `Range` 的起点。
     *
     * <p>为什么要循环而不是一次取完：单条存根背后最多 10 MiB，手机在上行弱、切网、
     * 进电梯时读到一半断开是常态。一次失败就从头再来，等于让「5 MB 的回复」在真实
     * 网络里永远补不齐 —— 而存根通路存在的全部理由就是把这条正文补回去。
     *
     * <p>三种响应必须分开处理（中继侧 `parseRange` 的三种结果一一对应）：
     * - `206`：服务端认了断点，追加。
     * - `200`：服务端不支持 Range，给的是**完整**正文，必须 `reset()` 后整体替换；
     *   追加就会拼出一段重复字节。
     * - `416`：越界。若本地已拿到全部（`offset` 等于服务端声明的长度）就算成功，
     *   否则是账算错了，放弃而不是无限重试。
     *
     * <p>重试有上限且退避：中继真的没有这条 blob 时（14 天裁剪过）重试一万次也不会
     * 变出来，那属于「明确报错」而不是「继续尝试」。
     */
    private fun fetchBlobFrom(
        hostId: String,
        blobId: String,
        offset: Int,
        sink: ByteArrayOutputStream,
        attempt: Int,
        result: (text: String?, error: String?) -> Unit,
    ) {
        val builder = Request.Builder()
            .url(api.originFor() + "/native/v2/hosts/$hostId/blobs/" + java.net.URLEncoder.encode(blobId, "UTF-8"))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + api.tokenFor())
            .get()
        if (offset > 0) builder.header("Range", "bytes=$offset-")
        api.rawBytes(builder.build(), BlobPlan.LIMIT) { bytes, error, status ->
            val step = if (error != null) {
                BlobPlan.onFailure(status, offset, attempt, error)
            } else {
                val chunk = bytes ?: ByteArray(0)
                val decision = BlobPlan.onChunk(status, chunk.size, offset)
                // The write happens here, not in the plan: the plan decides *whether*
                // to replace, the transport knows *how much* it just read.
                if (status == 200) { sink.reset(); sink.write(chunk) } else sink.write(chunk)
                decision
            }
            when (step) {
                is BlobPlan.Step.Complete -> result(sink.toString("UTF-8"), null)
                is BlobPlan.Step.Fail -> {
                    Diag.log("sync", "blob $blobId stopped at $offset: ${step.error}")
                    result(null, step.error)
                }
                is BlobPlan.Step.Again -> {
                    if (step.attempt > 0) Diag.log("sync", "blob $blobId retry ${step.attempt} at ${step.offset}")
                    fetchBlobFrom(hostId, blobId, step.offset, sink, step.attempt, result)
                }
            }
        }
    }

    fun command(cmdId: String, op: String, payload: JsonObject): Boolean {
        val s = socket
        if (s == null || !open) {
            // 没连上时返回 false 就够了，但记一行能区分「没连上」与「连上了但写不出去」。
            Diag.log("control", "command $op refused: socket=${if (s == null) "null" else "present"} open=$open")
            return false
        }
        Diag.log("control", "command $op id=${cmdId.take(8)}")
        return send(s, buildJsonObject {
            put("type", "CMD")
            put("cmdId", cmdId)
            put("op", op)
            put("payload", payload)
        })
    }

    fun unfocus(sessionId: String): Boolean {
        val s = socket
        if (s == null || !open) return false
        return send(s, buildJsonObject { put("type", "UNFOCUS_SESSION"); put("sessionId", sessionId) })
    }

    fun focus(sessionId: String, sinceSeq: LocalAckedSeq): Boolean {
        val s = socket
        if (s == null || !open) return false
        return send(s, buildJsonObject {
            put("type", "FOCUS_SESSION")
            put("sessionId", sessionId)
            put("sinceSeq", sinceSeq.value)
        })
    }

    private fun send(socket: WebSocket, frame: JsonObject): Boolean = try {
        val text = buildJsonObject {
            put("tag", MUX_TAG)
            frame.forEach { (key, value) -> put(key, value) }
        }.toString()
        RelayApi.trafficUp.addAndGet(text.toByteArray(Charsets.UTF_8).size.toLong())
        socket.send(text)
    } catch (e: Exception) {
        Diag.log("control", "send ${e.javaClass.simpleName}")
        false
    }

    // ============================================================
    // 解析
    // ============================================================

    private fun parseSyncPage(value: JsonObject?, sessionId: String): SyncPage? {
        val results = value?.arr("results") ?: return null
        val row = results.firstOrNull { (it as? JsonObject)?.str("sessionId") == sessionId } as? JsonObject
            ?: results.firstOrNull() as? JsonObject
            ?: return null
        return syncPageOf(row, sessionId)
    }

    private fun syncPageOf(row: JsonObject, sessionId: String): SyncPage? {
        var skipped = 0
        val records = ArrayList<TimelineRecord>()
        val raw = row.arr("records") ?: JsonArray(emptyList())
        for (element in raw) {
            val record = recordOf(element)
            if (record == null) skipped++ else records.add(record)
        }
        return SyncPage(
            sessionId = row.str("sessionId").ifEmpty { sessionId },
            reqAfterSeq = row.long("reqAfterSeq"),
            nextCursorSeq = row.long("nextCursorSeq"),
            archiveCoveredSeq = row.long("archiveCoveredSeq"),
            hasMore = row.bool("hasMore"),
            stopReason = row.str("stopReason"),
            records = records,
            bytes = row.long("bytes"),
            skipped = skipped,
        )
    }

    private fun decodeMuxFrame(text: String): JsonObject? {
        if (!text.startsWith("{")) return null
        val value = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            return null
        }
        val dict = value as? JsonObject ?: return null
        if (dict.str("tag") != MUX_TAG) return null
        if (dict["type"] !is JsonPrimitive) return null
        return dict
    }

    companion object {
        /** 与 `packages/core/src/mux.ts` 的 `MUX_V2_TAG` 必须一致。 */
        const val MUX_TAG = "dsh-v2"

        /** 一帧控制流的上限：RECORDS 帧最大是一页 512 KiB，留足余量。 */
        private const val CONTROL_MESSAGE_LIMIT = 4 * 1024 * 1024

        /** 单条 blob 的硬上限，与 `SYNC_V2.hardCeiling` 一致（10 MiB）。 */
        private const val BLOB_LIMIT = 10 * 1024 * 1024


        /** 单页字节预算，与 `SYNC_V2.pageByteBudget` 一致（512 KiB）。 */
        const val DEFAULT_PAGE_BUDGET = 512 * 1024

        /** 429 之后等多久（中继侧 SyncLoopGuard 的 blockMs 是 5s）。 */
        const val SYNC_LOOP_BACKOFF_MS = 5_000L

        /**
         * 把线上一条记录解析成 [TimelineRecord]。
         *
         * 字段缺失时给安全默认值而不是抛异常：`kind` 不认识就退 `META_PATCH`
         * （原样透传，投影器会忽略它），`seq` 不是正整数才算真坏行（返回 null）。
         * **绝不编造 seq**——编出来的 seq 会把水位带到错误的位置。
         */
        fun recordOf(element: JsonElement): TimelineRecord? {
            val row = element as? JsonObject ?: return null
            val sessionId = row.str("sessionId")
            val seq = (row["seq"] as? JsonPrimitive)?.let {
                try {
                    it.long
                } catch (e: Exception) {
                    null
                }
            } ?: return null
            // 流式增量的 seq 是「当前这一步」的序号，可以是 0（会话刚开、还没有
            // 任何确定性记录）。其余类型的 seq 必须为正：0 是「没有」的哨兵值。
            val kind = RecordKind.of(row.str("kind"))
            if (seq < 0 || (seq == 0L && kind != RecordKind.STREAM_DELTA)) return null
            val payload = row["payload"]
            val stub = row.obj("stub")
            return TimelineRecord(
                sessionId = sessionId,
                seq = seq,
                prevSeq = row.long("prevSeq"),
                turnId = row.str("turnId"),
                stepId = row.str("stepId").ifEmpty { null },
                kind = kind,
                timestampMs = row.long("timestampMs"),
                clientMsgId = row.str("clientMsgId").ifEmpty { null },
                payloadJson = if (payload == null || payload is JsonNull) "" else payload.toString(),
                isOversizedStub = stub != null,
                blobId = stub?.str("blobId"),
                blobSize = stub?.long("blobSize") ?: 0,
                truncatedFrom = row.obj("truncated")?.long("originalBytes") ?: 0,
                // The wire carries the readable head of an offloaded payload; the stub's
                // own payload is just `{type:'stub/payload',blobId,…}`. Dropping this
                // field (as this parser used to) made every conversation whose last
                // reply was oversized show that JSON envelope as its list preview.
                stubPreviewHead = stub?.str("previewHead") ?: "",
            )
        }
    }
}
