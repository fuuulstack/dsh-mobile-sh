package com.dsh.mobile.v2.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.dsh.mobile.v2.data.Diag
import com.dsh.mobile.v2.data.Model
import com.dsh.mobile.v2.ui.LocalSearch
import com.dsh.mobile.v2.data.TokenVault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.regex.Pattern

/**
 * 中继网关（v2，Kotlin 版）：JSON ↔ Model 的翻译与回调分发，不含 UI 逻辑。
 *
 * 与 v1 HttpRelayGateway 的结构对应：
 * - 认证：device-code 登录 / 登出 / 令牌轮换落盘；
 * - 主机与会话：listHosts / pair / unpair / list / create / rename / projections；
 * - 聊天：正文与历史由 v2 归档流承担（ChatSyncActor + Room 投影）；
 *   本类只剩发送/取消/队列这些直达宿主的 v1 RPC。
 * - 事件：openEvents / watchChanges / waterfall 回答 / emit 分发；
 * - 偏好与状态：omit 头、RTT 探测、顶栏状态。
 *
 * 任务（Task）接口 v2 暂不移植：v1 也全部返回「接口尚未实现」。
 */
class RelayGateway(
    private val api: RelayApi,
    private val vault: TokenVault,
) {

    /** 主线程出站口：所有回调经它投递（v1 用 Handler main 字段，此处构造时固定）。 */
    private val main = Handler(Looper.getMainLooper())

    private var current: Model.Host? = null

    // ============================================================
    // 回调契约
    // ============================================================

    fun interface Reply<T> {
        fun done(value: T?, error: String?)
    }

    interface Stream {
        /** @param hasMore 快照之外是否还有更早的历史，决定要不要显示「加载更早」。 */
        fun onSnapshot(session: Model.Session, messages: List<Model.Message>, hasMore: Boolean)
        fun onSnapshotProgress(done: Int, total: Int)
        fun onMessage(message: Model.Message)
        fun onStreaming(partialText: String)
        /** 助手回合处理过程更新（同一个可变实例，UI upsert + 重绘即可）。 */
        fun onProcess(process: Model.Message)
        fun onEarlier(messages: List<Model.Message>?, hasMore: Boolean)
        fun onState(label: String, connected: Boolean)

        /**
         * 宿主的任务清单变了（`todo/write` 事件，整表覆盖）。
         *
         * 单开一路而不是塞进 onMessage：任务清单不是时间线上的消息，它没有 seq、
         * 也不该出现在时间线里；胶囊要的是「当前进度」这一个状态。
         * 默认空实现，这样既有的 Stream 实现（含测试替身）不必跟着改。
         */
        fun onTodos(todos: List<Model.Todo>) {}

        /**
         * 宿主的 inbox 变了（`agent/inbox/spliced`，增量 splice）。
         *
         * 队列不重放全量，只发「在 [target] 桶的第 [start] 位删 [removedCount] 条、
         * 再插入 [inserted]」——本地照做即可，不用重新拉投影。
         * 之所以单开一路：队列不是时间线内容，没有 seq，也不该出现在气泡里。
         * 默认空实现，既有实现（含测试替身）不必跟着改。
         */
        fun onInbox(target: String, start: Int, removedCount: Int, inserted: List<Model.QueuedMessage>) {}
    }

    /** app 级事件通道（$events 流）的监听者；不随会话开关。 */
    interface EventsListener {
        fun onApproval(approval: Model.Approval)
        fun onQuestion(card: Model.QuestionCard)
        fun onInteractionCancel(eventId: String)
        fun onSessionStatus(sessionId: String, running: Boolean) {}
        fun onSessionAdded(session: Model.Session) {}
        fun onSessionRemoved(sessionId: String) {}
        fun onSessionActivity(sessionId: String, at: Long) {}
        fun onSessionError(sessionId: String, message: String) {}
        fun onAdaptersUpdated() {}
        fun onPermissionCatalogChanged() {}
        /** 事件流断过之后又接回来了：宿主只重投未结算 waterfall，历史要主动拉一次。 */
        fun onReconnected() {}
    }

    init {
        api.attachMainThread(main)
        // 流被 401 打回时 RelayApi 自行轮换一次；换出来的新令牌必须落盘，
        // 否则进程一死又回到那份被中继拒绝的旧令牌。
        api.onRotated = { fresh ->
            api.token = fresh
            try {
                vault.save(api.origin, fresh)
            } catch (ignored: Exception) {
            }
            // 换到新令牌就是凭据问题解决了；不清 note 首页会继续说「登录已过期」。
            authNote = ""
            onHealthChanged()
        }
    }

    // ============================================================
    // 认证
    // ============================================================

    /**
     * 用设备配对码登录：不需要邮箱、密码、TOTP。
     * 配对码在中继网页 /device-codes 生成，15 分钟内有效、用一次即废；
     * 换回来的 native 令牌与账号登录完全同权（30 天滑动续期）。
     */
    fun login(endpoint: String, code: String?, label: String?, reply: Reply<String>) {
        try {
            api.origin = RelayApi.normalize(endpoint)
        } catch (e: Exception) {
            reply.done(null, e.message ?: RelayApi.ENDPOINT_INVALID)
            return
        }
        val trimmed = code?.trim() ?: ""
        if (trimmed.isEmpty()) {
            reply.done(null, "请输入配对码")
            return
        }
        val body = buildJsonObject {
            put("code", trimmed)
            put("label", label?.trim()?.takeIf { it?.isNotEmpty() == true } ?: Build.MODEL)
        }
        api.request("/native/v2/device-code", body, false) { value, error ->
            if (error != null) { reply.done(null, error); return@request }
            val token = value?.str("token") ?: ""
            if (token.isEmpty()) { reply.done(null, "中继未返回登录凭据"); return@request }
            api.token = token
            try {
                vault.save(api.origin, token)
            } catch (e: Exception) {
                api.token = ""
                reply.done(null, "无法安全保存登录状态")
                return@request
            }
            reply.done(token, null)
        }
    }

    fun logout(reply: Reply<Void>?) {
        api.request("/native/v2/logout", buildJsonObject { }, true) { _, _ ->
            vault.clear()
            api.token = ""
            api.closeStream()
            reply?.done(null, null)
        }
    }

    fun hasToken(): Boolean = api.token.isNotEmpty()

    fun endpoint(): String = api.origin

    fun setEndpoint(endpoint: String) {
        try {
            api.origin = RelayApi.normalize(endpoint)
        } catch (ignored: Exception) {
        }
    }

    // ============================================================
    // 主机
    // ============================================================

    fun listHosts(reply: Reply<List<Model.Host>>) {
        // 记下发请求时是哪一台中继：换过中继之后回来的答复属于上一台，采用它就会
        // 把界面指到一台在新中继上不存在的电脑（旧 hostId 的 /changes 永远 404）。
        val epoch = api.relayEpoch.current()
        api.request("/native/v2/hosts", null, true) { value, error ->
            if (!api.relayEpoch.accepts(epoch)) { reply.done(null, null); return@request }
            if (error != null) { reply.done(null, error); return@request }
            val hosts = ArrayList<Model.Host>()
            val items = value?.arr("items")
            if (items != null) {
                for (i in 0 until items.size) {
                    val h = items[i] as? JsonObject ?: continue
                    hosts.add(
                        Model.Host(
                            id = h.str("hostId"),
                            alias = h.str("alias").ifEmpty { "DSH 主机" },
                            online = h.bool("online", false),
                            authorized = h.bool("authorized", false),
                            version = h.str("pluginVersion"),
                            platform = h.str("platform"),
                        )
                    )
                }
            }
            reply.done(hosts, null)
        }
    }

    fun pair(code: String?, reply: Reply<Void>) {
        val body = buildJsonObject { put("code", code?.trim() ?: "") }
        api.request("/native/v2/pair", body, true) { _, error ->
            if (error != null) reply.done(null, error) else reply.done(null, null)
        }
    }

    /** 解除本机对某台主机的授权（撤 grant，不撤设备）。 */
    fun unpair(hostId: String?, reply: Reply<Void>) {
        val body = buildJsonObject { put("hostId", hostId ?: "") }
        api.request("/native/v2/unpair", body, true) { _, error ->
            if (error != null) reply.done(null, error) else reply.done(null, null)
        }
    }

    fun selectHost(host: Model.Host?) {
        current = host
        // 换主机必须关掉旧主机的跟随流：留着不但白耗电量，事件还会投到已无人消费的
        // 出站口上。
        api.closeStream()
        // 换主机会重开事件流；旧主机上留下的凭据结论不该继续挂在顶栏上。
        authNote = ""
    }

    fun currentHost(): Model.Host? = current

    /** 当前中继根地址；用来判断这次登录是不是换了另一台中继。 */
    fun relayOrigin(): String = api.origin

    // ============================================================
    // 会话
    // ============================================================

    /**
     * 会话清单。
     *
     * <p>数据源是**中继存档的会话元数据**（`GET /native/v2/hosts/:id/sessions`），
     * 不再经过宿主的 `session/list` RPC：那条路要求手机与电脑同时在位，人不在电脑
     * 前就只看到一台空列表。中继侧的元数据由插件在 `refreshSessions()` 里对**全部**
     * 会话 `putMeta`（正文才限 `topK` 个），字段与宿主 `session/list` 同构，所以
     * [sessionOf] 不用动。
     */
    fun listSessions(reply: Reply<List<Model.Session>>) {
        val host = current ?: run { reply.done(null, "尚未选择主机"); return }
        api.get("/native/v2/hosts/${host.id}/sessions", true) { value, error ->
            if (error != null) { reply.done(null, error); return@get }
            val sessions = ArrayList<Model.Session>()
            val rows = value?.arr("items")
            if (rows != null) {
                for (i in 0 until rows.size) {
                    val item = rows[i] as? JsonObject ?: continue
                    val s = sessionOf(item, host.alias)
                    if (s != null) sessions.add(s)
                }
            }
            reply.done(sessions, null)
        }
    }

    fun createSession(reply: Reply<Model.Session>) {
        val host = current ?: run { reply.done(null, "尚未选择主机"); return }
        api.rpc(host.id, "create", buildJsonObject { }) { value, error ->
            if (error != null) { reply.done(null, error); return@rpc }
            val id = value?.str("sessionId") ?: ""
            if (id.isEmpty()) { reply.done(null, "创建会话未返回标识"); return@rpc }
            reply.done(
                Model.Session(id = id, title = "新对话", subtitle = host.alias, updatedAt = System.currentTimeMillis()),
                null
            )
        }
    }

    fun renameSession(sessionId: String, title: String, reply: Reply<Void>) {
        val host = current ?: run { reply.done(null, "尚未选择主机"); return }
        val body = buildJsonObject {
            put("sessionId", sessionId)
            put("title", title)
        }
        api.rpc(host.id, "rename", body) { _, error ->
            if (error != null) reply.done(null, error) else reply.done(null, null)
        }
    }

    // 注：宿主没有 session/delete 端点（v1 也没有），「删除会话」一律是本机墓碑，
    // 由 AppViewModel.deleteSession 处理；这里不再放一个必然失败的桩。

    // ============================================================
    // 通道健康
    // ============================================================

    /**
     * 通道坏掉的真实原因（空 = 一切正常）。由两条流的 state 回调维护：
     * 连上就清空，令牌被拒就记下那句中文。首页顶栏用它，不用它的话
     * 401 期间照样会显示绿点。
     */
    @Volatile
    private var authNote = ""

    /** 最近一次有流真的连上的时刻（0 = 从未），「最后同步」用它，不用当前时间。 */
    @Volatile
    private var lastLiveAt = 0L

    /** 最近一次 RTT 探测结果（毫秒，-1 = 还没测到）；顶栏红黄绿点必须来自真实往返。 */
    @Volatile
    private var latencyMs = -1

    /** 健康状态翻转后的重刷钩子，由界面注入。 */
    var onHealthChanged: () -> Unit = {}

    /** 当前缓存的 RTT（毫秒，-1 = 未知）。 */
    fun latency(): Int = latencyMs

    /**
     * 打一次往返探测。用 /native/v2/hosts：一定存在、带鉴权、返回体只有主机列表；
     * 401 也算「链路通」——服务器答了话，只是凭据过期，那属于另一种问题。
     */
    fun probeLatency(done: (Int) -> Unit) {
        api.probe("/native/v2/hosts") { ms ->
            if (ms >= 0) latencyMs = ms
            else if (latencyMs < 0) latencyMs = -1
            done(ms)
        }
    }

    /**
     * 记录通道的真实健康状况。
     *
     * 只认凭据级失败：那是「这份凭据不被接受」，值得让首页也闭嘴。普通断线重连
     * 由那条流自己显示，不升级到全局顶栏——否则一条流的瞬时抖动会把整个 app
     * 说成坏了。
     */
    private fun trackStream(message: String, connected: Boolean) {
        val before = authNote
        if (connected) {
            authNote = ""
            lastLiveAt = System.currentTimeMillis()
        } else if (RelayApi.credentialFailure(message)) {
            authNote = message
        }
        // 顶栏只在启动时取一次状态；不推一下就重刷，401 之后它会一直挂着「已连接」。
        if (before.isEmpty() != authNote.isEmpty()) onHealthChanged()
    }

    /**
     * 发一条 prompt。
     *
     * @param mode 宿主的投递方式：`"queue"` = 排在当前这轮之后（默认）；
     *   `"steer"` = 抢先插进正在跑的这轮（用户长按发送按钮）。
     *   宿主只在轮次运行中认 `steer`，空闲时两者等价。
     */
    fun sendPrompt(
        sessionId: String,
        text: String?,
        attachments: List<Model.Attachment>?,
        mode: String = "queue",
        /**
         * 宿主侧幂等键。默认现造一个，但**调用方必须显式传**的场景很关键：
         * v2 控制流失败回落这条路时，同一个 requestId 才能让宿主认出
         * 「这就是刚才那条」，而不是把它当成第二条新消息收进 inbox
         * （2026-10-30 真机：发一次消息，手机端显示排队 2 条）。
         */
        requestId: String = UUID.randomUUID().toString(),
        reply: Reply<Void>,
    ) {
        val host = current ?: run { reply.done(null, "尚未选择主机"); return }
        val body = buildJsonObject {
            val parts = buildJsonArray {
                if (!text.isNullOrBlank()) {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    })
                }
                // 图片：{type:"image", mediaType, data(base64), name?}（已压缩到限额内）。
                // 文件：{type:"file", receiptId}——字节早在上传那一步交给宿主了。
                attachments?.forEach { a ->
                    if (a.isFile) {
                        add(buildJsonObject {
                            put("type", "file")
                            put("receiptId", a.receiptId)
                        })
                        return@forEach
                    }
                    if (a.data.isEmpty()) return@forEach
                    add(buildJsonObject {
                        put("type", "image")
                        put("mediaType", a.mediaType)
                        put("data", Base64.encodeToString(a.data, Base64.NO_WRAP))
                        if (a.name.isNotEmpty()) put("name", a.name)
                    })
                }
            }
            if (parts.size == 0) { reply.done(null, "没有可发送的内容"); return@buildJsonObject }
            put("sessionId", sessionId)
            put("requestId", requestId)
            put("mode", mode)
            put("content", parts)
            put("clientTimeZone", clientTimeZoneId())
        }
        api.rpc(host.id, "prompt", body) { _, error ->
            if (error != null) {
                reply.done(null, error)
            } else {
                reply.done(null, null)
            }
        }
    }

    /**
     * 改一条排队消息：编辑正文 / 删除 / 提前插话。
     *
     * 对应宿主的 `session/updateQueue`，`action.kind` 三选一：
     * - `"edit"`：只收纯文本，宿主会把这条的 content 整块换成新文本
     * - `"remove"`：从 inbox 里摘掉（宿主同时退掉上传回执）
     * - `"steer"`：从 next-turn 挪进当前这轮，等价于桌面端排队卡片上的「↑」
     *
     * 失败常见两种：`session/queue-item-not-found`（这条已经被领走了）与
     * `session/steer-unavailable`（当前轮已经不收插话了），文案在 HostErrors 里。
     */
    fun updateQueue(
        sessionId: String,
        itemId: String,
        kind: String,
        text: String? = null,
        reply: Reply<Void>,
    ) {
        val host = current ?: run { reply.done(null, "尚未选择主机"); return }
        val body = buildJsonObject {
            put("sessionId", sessionId)
            put("itemId", itemId)
            put("action", buildJsonObject {
                put("kind", kind)
                if (kind == "edit") {
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", text ?: "")
                        })
                    })
                }
            })
        }
        api.rpc(host.id, "updateQueue", body) { _, error ->
            if (error != null) reply.done(null, error) else reply.done(null, null)
        }
    }

    /** DSH 只接受 `UTC` 或 IANA Area/Location 形状的时区名。 */
    private val ianaZone = Pattern.compile("^[A-Za-z][A-Za-z0-9_+.-]*(?:/[A-Za-z0-9_+.-]+)+$")

    /**
     * 折算成宿主能接受的时区名。
     *
     * Android 的 TimeZone.getDefault().getID() 常见返回 GMT、GMT+08:00 这类旧式 ID，
     * 不含 Area/Location 的斜杠，会被宿主以 session/invalid-time-zone 拒收整条 prompt。
     * 在同偏移量的 IANA 区域名里折算，取不到再退化为 UTC。
     */
    fun clientTimeZoneId(): String {
        return try {
            val zone = TimeZone.getDefault()
            val id = zone?.id
            if (id != null && (id == "UTC" || ianaZone.matcher(id).matches())) return id
            val offset = zone?.getOffset(System.currentTimeMillis()) ?: 0
            if (offset == 0) return "UTC"
            var etcFallback: String? = null
            for (candidate in TimeZone.getAvailableIDs(offset)) {
                if (!ianaZone.matcher(candidate).matches()) continue
                if (TimeZone.getTimeZone(candidate).useDaylightTime()) continue
                if (!candidate.startsWith("Etc/")) return candidate
                if (etcFallback == null) etcFallback = candidate
            }
            if (etcFallback != null) return etcFallback
            if (offset % 3600000 == 0) {
                val hours = offset / 3600000
                // Etc/GMT 的符号与 UTC 偏移相反：Etc/GMT-8 表示 UTC+8。
                if (hours in -12..14) return "Etc/GMT" + (if (hours > 0) "-" else "+") + Math.abs(hours)
            }
            "UTC"
        } catch (e: Exception) {
            "UTC"
        }
    }

    fun executeCommand(sessionId: String, line: String, reply: Reply<Void>) {
        val host = current ?: run { reply.done(null, "尚未选择主机"); return }
        api.command(host.id, sessionId, line) { value, error ->
            if (error != null) { reply.done(null, error); return@command }
            // 命令处理器可能自己返回失败（例如预设名不存在），这时别对用户报成功。
            val r = value?.obj("result")
            val kind = r?.str("kind") ?: ""
            if (kind.isNotEmpty() && kind != "success") {
                val text = r?.str("text") ?: ""
                reply.done(null, text.ifEmpty { "命令执行失败" })
                return@command
            }
            reply.done(null, null)
        }
    }

    fun cancel(sessionId: String, reply: Reply<Void>) {
        val host = current ?: run { reply.done(null, "尚未选择主机"); return }
        val body = buildJsonObject { put("sessionId", sessionId) }
        api.rpc(host.id, "cancel", body) { _, error ->
            if (error != null) reply.done(null, error) else reply.done(null, null)
        }
    }

    // ============================================================
    // 待答交互（审批 / 提问卡片）
    // ============================================================

    private var eventsListener: EventsListener? = null

    /**
     * 接上 app 级交互监听者（审批 / 提问 / 归档变更）。
     *
     * <p>v1 时代这里会另开一条 `$events` WS 常驻收瀑布。那条路由已随 v1 一起删除
     * （中继对 `/native/v1/…` 直接 410），改由**每个会话的控制流**承载：插件自己
     * 开着宿主的 `$events`，把瀑布转成 `INTERACTION` 帧发过来，并在每次订阅时把
     * 未结算的那些重投一遍（瀑布不落盘，这是它唯一的持久性来源）。
     *
     * <p>代价说清楚：**人停在会话列表页时**没有任何控制流在位，那一刻到达的卡片
     * 手机是收不到的。进会话时插件会重投，所以卡片不会丢；丢的是「不在会话里也
     * 能收到审批」这条。v1 那条 app 级流已经不存在，这不是取舍而是既成事实。
     */
    fun openEvents(listener: EventsListener) {
        eventsListener = listener
    }

    /**
     * 订阅会话变更（SSE）。
     *
     * <p>顺带把这条流的死活报给 [trackStream]：v1 那条 app 级 `$events` 删掉之后，
     * 变更流就是**唯一**常驻的 app 级长连接，凭据失效的信号只有从它这里能看到。
     */
    fun watchChanges(feed: RelayApi.ChangeFeed) {
        val host = current ?: return
        api.watchChanges(host.id, object : RelayApi.ChangeFeed {
            override fun change(
                sessionId: String,
                latestSeq: Long,
                title: String,
                records: JsonArray?,
                truncated: Boolean,
            ) = feed.change(sessionId, latestSeq, title, records, truncated)

            override fun state(note: String) {
                trackStream(note, note.isEmpty())
                feed.state(note)
            }
        })
    }

    fun closeChanges() { api.closeChanges() }

    /**
     * 控制流送到的交互帧 → app 级监听者。
     *
     * <p>`kind` 就是宿主 `$events` 帧的 `type`（插件原样转的）：`waterfall` 带
     * `event`/`eventId`/`agentId`/`request`，`cancel` 只带 `eventId`。
     *
     * <p>字段缺失时**不抛**：一条形状不对的帧不该把调用它的 IO 线程打断，那会连
     * 带把同一批 RECORDS 的处理也丢掉。
     */
    fun deliverInteraction(kind: String, payload: JsonObject?) {
        val listener = eventsListener ?: return
        if (payload == null) return
        when (kind) {
            "waterfall" -> {
                val event = payload.str("event")
                val eventId = payload.str("eventId")
                val agentId = payload.str("agentId")
                val request = payload.obj("request")
                when (event) {
                    "approval/request" -> parseApproval(eventId, agentId, request)?.let { listener.onApproval(it) }
                    "user-questions/request" -> parseQuestionCard(eventId, agentId, request)?.let { listener.onQuestion(it) }
                }
            }
            "cancel" -> {
                val eventId = payload.str("eventId")
                if (eventId.isNotEmpty()) listener.onInteractionCancel(eventId)
            }
        }
    }

    /** 宿主错误对象是 {message,...} 或字符串；只取可展示的一句话。 */
    private fun errorText(raw: JsonElement?): String = when (raw) {
        is JsonObject -> raw.str("message").ifEmpty { "会话出错" }
        is JsonPrimitive -> raw.content
        else -> "会话出错"
    }

    /**
     * 摘下 app 级交互监听者（切主机 / 退出登录）。
     *
     * <p>没有 socket 要关：瀑布现在由每个会话的控制流承载，那些流由各自的
     * `ChatSyncActor` 自己收尾，调用方只管在切主机时把 `eventsListener` 摘掉。
     */
    fun closeEvents() {
        eventsListener = null
    }


    /** approval/request 的 request → Model；字段对齐桌面端 PendingApproval 的投影。 */
    fun parseApproval(eventId: String?, agentId: String?, request: JsonObject?): Model.Approval? {
        if (request == null) return null
        val approval = Model.Approval()
        approval.eventId = eventId ?: ""
        // 来自 `$events` 的 waterfall：这个 eventId 是宿主的连接级标识，答得回去。
        // 归档重建的卡不走这里（`Projector.approvalCard` 那条），那边默认为 false。
        approval.answerable = !eventId.isNullOrEmpty()
        approval.agentId = agentId ?: ""
        approval.toolName = request.str("toolName")
        approval.callId = request.str("callId")
        approval.reason = request.str("reason")
        // displayReason 在宿主侧是可本地化的结构（{en,zh}），直接当字符串取会得到空串。
        approval.displayReason = localizedText(request["displayReason"])
        return approval
    }

    /** user-questions/request 的 request → Model；questions 数组逐题解析，缺 id 的题跳过。 */
    fun parseQuestionCard(eventId: String?, agentId: String?, request: JsonObject?): Model.QuestionCard? {
        if (request == null) return null
        val questions = request.arr("questions") ?: return null
        if (questions.isEmpty()) return null
        val card = Model.QuestionCard()
        card.eventId = eventId ?: ""
        card.answerable = !eventId.isNullOrEmpty()
        card.agentId = agentId ?: ""
        card.callId = request.str("callId")
        if (card.callId.isEmpty()) {
            card.callId = request.obj("wait")?.str("callId") ?: ""
        }
        for (i in 0 until questions.size) {
            val q = questions[i] as? JsonObject ?: continue
            val question = Model.Question()
            question.id = q.str("id")
            if (question.id.isEmpty()) continue
            question.question = q.str("question")
            question.detail = q.str("detail")
            question.header = q.str("header")
            question.multiSelect = q.bool("multiSelect", false)
            val options = q.arr("options")
            if (options != null) {
                for (j in 0 until options.size) {
                    val o = options[j] as? JsonObject ?: continue
                    val option = Model.QuestionOption()
                    option.label = o.str("label")
                    option.description = o.str("description")
                    if (option.label.isNotEmpty()) question.options.add(option)
                }
            }
            card.questions.add(question)
        }
        return if (card.questions.isEmpty()) null else card
    }

    // ============================================================
    // 附件与搜索
    // ============================================================

    /**
     * 上传一个非图片附件，换回 receiptId。
     *
     * 宿主对图片与文件走两条不同的路：图片直接 base64 内联进 prompt，文件必须先
     * 换 receiptId，prompt 里只留 {type:"file", receiptId}。没有 receiptId 的 file
     * 类型会被宿主以 session/attachment-invalid 拒掉。
     */
    fun uploadAttachment(sessionId: String, attachment: Model.Attachment, reply: Reply<String>) {
        if (attachment.data.isEmpty()) { reply.done(null, "附件内容为空"); return }
        val host = current ?: run { reply.done(null, "尚未选择主机"); return }
        api.upload(host.id, sessionId, attachment.name, attachment.data) { value, error ->
            if (error != null) { reply.done(null, error); return@upload }
            val receipt = value?.str("receiptId") ?: ""
            if (receipt.isEmpty()) reply.done(null, "宿主未返回 receiptId") else reply.done(receipt, null)
        }
    }

    fun loadAttachment(sessionId: String, image: Model.ImageRef?, reply: Reply<Bitmap>) {
        if (image == null || image.attachmentId.isEmpty()) { reply.done(null, "图片引用缺失"); return }
        val host = current ?: run { reply.done(null, "尚未选择主机"); return }
        val body = buildJsonObject {
            put("sessionId", sessionId)
            put("attachmentId", image.attachmentId)
        }
        api.rpc(host.id, "attachment", body) { value, error ->
            if (error != null) { reply.done(null, error); return@rpc }
            val data = value?.str("data") ?: ""
            if (data.isEmpty()) { reply.done(null, "宿主未返回图片数据"); return@rpc }
            // base64 解码与 BitmapFactory 都在共享单线程；成功后回主线程。
            imageDecode.execute {
                try {
                    val bytes = Base64.decode(data, Base64.NO_WRAP)
                    val bitmap = decodeSampled(bytes)
                    main.post {
                        if (bitmap != null) reply.done(bitmap, null) else reply.done(null, "图片解码失败")
                    }
                } catch (e: Exception) {
                    main.post { reply.done(null, "图片解码失败") }
                }
            }
        }
    }

    /** 图片解码共享单线程：连续点开多张附件不再每次裸起一个 Thread。 */
    private val imageDecode: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dsh-v2-image-decode").apply { isDaemon = true }
    }

    /**
     * 按屏幕量级采样解码：4000×3000 的原图全尺寸解码要 ~48MB 堆，低内存机上
     * 连点几张就 OOM。先量尺寸，按最长边 2048 取 2 的幂采样。
     */
    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (longest / sample > 2048) sample *= 2
            if (sample > 1) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            } else {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (ignored: Exception) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    fun searchSessions(query: String?, reply: Reply<List<Model.SearchHit>>) {
        val host = current ?: run { reply.done(null, "尚未选择主机"); return }
        val body = buildJsonObject { put("query", query ?: "") }
        api.rpc(host.id, "search", body) { value, error ->
            if (error != null) { reply.done(null, error); return@rpc }
            val hits = ArrayList<Model.SearchHit>()
            val items = value?.arr("items")
            if (items != null) {
                for (i in 0 until items.size) {
                    val item = items[i] as? JsonObject ?: continue
                    val hit = Model.SearchHit(
                        sessionId = item.str("sessionId"),
                        title = item.str("title"),
                        snippet = item.str("snippet"),
                    )
                    if (hit.sessionId.isNotEmpty()) hits.add(hit)
                }
            }
            reply.done(hits, null)
        }
    }

    fun rotateToken(reply: Reply<Void>) {
        api.rotate { value, error ->
            if (error != null) { reply.done(null, error); return@rotate }
            val token = value?.str("token") ?: ""
            if (token.isEmpty()) { reply.done(null, "中继未返回新令牌"); return@rotate }
            try {
                vault.save(api.origin, token)
            } catch (e: Exception) {
                reply.done(null, "无法安全保存新令牌")
                return@rotate
            }
            reply.done(null, null)
        }
    }

    fun modelCatalog(reply: Reply<Model.ModelCatalog>) {
        val host = current ?: run { reply.done(null, "尚未选择主机"); return }
        api.rpc(host.id, "modelCatalog", null) { value, error ->
            if (error != null) { reply.done(null, error); return@rpc }
            reply.done(parseCatalog(value), null)
        }
    }

    /**
     * 宿主声明的思考等级列表。
     *
     * 形状是 `reasoning: { efforts: [{id, name, description?}], defaultEffort }`；有的 provider
     * 只在 reasoning 里给 defaultEffort 不给 efforts（比如不支持切换思考等级的那些），
     * 这时返回空表——界面据此不显示切换入口，而不是编一个 off/low/high 出来。
     */
    private fun parseEfforts(reasoning: JsonObject?): List<Model.ReasoningEffort> {
        val arr = reasoning?.arr("efforts") ?: return emptyList()
        val out = ArrayList<Model.ReasoningEffort>(arr.size)
        for (i in 0 until arr.size) {
            val item = arr[i] as? JsonObject ?: continue
            val id = item.str("id")
            if (id.isEmpty()) continue
            out.add(
                Model.ReasoningEffort(
                    id = id,
                    name = item.str("name").ifEmpty { id },
                    description = item.str("description"),
                )
            )
        }
        return out
    }

    /** `session/modelCatalog` → 手机端的扁平选项；provider 展示名来自分组。 */
    fun parseCatalog(value: JsonObject?): Model.ModelCatalog {
        val catalog = Model.ModelCatalog()
        if (value == null) return catalog
        val def = value.obj("default")
        if (def != null) {
            catalog.defaultChoice = Model.ModelChoice(
                provider = def.str("provider"),
                model = def.str("model"),
                reasoningEffort = def.str("reasoningEffort"),
            ).also { it.name = it.model }
        }
        val groups = value.arr("groups")
        if (groups != null) {
            for (i in 0 until groups.size) {
                val group = groups[i] as? JsonObject ?: continue
                val provider = group.str("id")
                val providerName = group.str("name").ifEmpty { provider }
                val models = group.arr("models") ?: continue
                for (j in 0 until models.size) {
                    val model = models[j] as? JsonObject ?: continue
                    val reasoning = model.obj("reasoning")
                    val choice = Model.ModelChoice(
                        provider = provider,
                        providerName = providerName,
                        model = model.str("id"),
                        name = model.str("name").ifEmpty { model.str("id") },
                        reasoningEffort = reasoning?.str("defaultEffort") ?: "",
                        efforts = parseEfforts(reasoning),
                    )
                    if (choice.model.isNotEmpty()) catalog.choices.add(choice)
                }
            }
        }
        val failures = value.arr("failures")
        if (failures != null) {
            for (i in 0 until failures.size) {
                val failure = failures[i] as? JsonObject ?: continue
                val name = failure.str("name").ifEmpty { failure.str("id") }
                val message = failure.str("message")
                // 本地缓存的失败项没有 message（整句已存在 name 里），别补出空的「：」。
                catalog.failures.add(if (message.isEmpty()) name else "$name：$message")
            }
        }
        return catalog
    }

    fun selectModel(sessionId: String, choice: Model.ModelChoice, reply: Reply<Void>) {
        val host = current ?: run { reply.done(null, "尚未选择主机"); return }
        val body = buildJsonObject {
            put("sessionId", sessionId)
            put("provider", choice.provider)
            put("model", choice.model)
            if (choice.reasoningEffort.isNotEmpty()) put("reasoningEffort", choice.reasoningEffort)
        }
        api.rpc(host.id, "selectModel", body) { _, error ->
            if (error != null) reply.done(null, error) else reply.done(null, null)
        }
    }

    /**
     * 单个会话的当前投影。
     *
     * session/list 的 projections.values 只折叠了标题，modelSelection / permissions
     * 都不在里面——进会话时问一次 session/projections，把真实值填回去。
     */
    fun sessionProjections(sessionId: String, reply: Reply<Model.Session>) {
        val host = current ?: run { reply.done(null, "尚未选择主机"); return }
        val body = buildJsonObject { put("sessionId", sessionId) }
        api.rpc(host.id, "projections", body) { value, error ->
            if (error != null) { reply.done(null, error); return@rpc }
            if (value == null) { reply.done(null, "没有投影"); return@rpc }
            // 形状是 {asOfSeq, values:{...}}；也兼容直接把 values 摊平的情况。
            val values = value.obj("values") ?: value
            val s = Model.Session(id = sessionId)
            // 标题也在投影里。这里不填的话，Model.Session 的默认值「新对话」会被当成
            // 真实标题回传，上层的 ifBlank 兜底就再也不会生效（实测顶栏永远显示「新对话」）。
            val projectedTitle = values.str("title")
            s.title = if (projectedTitle == "null") "" else projectedTitle
            // 任务清单也是投影（SessionProjectionMap.todos，整表覆盖下发）。
            // 字段缺失 ≠ 空数组：缺了说明这次没带，交给上层的合并策略保留旧值；
            // 明确给了 [] 才是宿主那边真的清空了。
            if (values.containsKey("todos")) s.todos = todosOf(values["todos"])
            // 子智能体目录**刻意不从这里取**：投影是宿主原样下发的，目录条目没有
            // 状态字段，而「谁还在跑」只有会话列表那条路带（插件 `slimSession` 会拿
            // 子代理自己那条会话的 `running` 盖章）。两个来源给同一个字段、
            // 保真度还不同，就是互相覆盖的经典配方（见 [ChatViewModel] 的
            // `refreshProjections`，那里以本地那份为准）。
            // 排队消息同理：缺字段 ≠ 队列空（保留旧值），明确给了才覆盖。
            if (values.containsKey("inbox")) s.queue = queueOf(values["inbox"])
            applyModelSelection(s, wrapValues(values))
            Diag.log("model", "projections $sessionId -> ${s.modelName.ifEmpty { "(empty)" }}")
            if (s.modelName.isEmpty()) {
                // 新会话没有 durable selection：退回模型目录里的部署默认值。
                api.rpc(host.id, "modelCatalog", null) { catalog, _ ->
                    val def = catalog?.obj("default")
                    if (def != null) {
                        s.modelProvider = def.str("provider")
                        s.modelName = def.str("model")
                    }
                    reply.done(s, null)
                }
                return@rpc
            }
            reply.done(s, null)
        }
    }

    /** applyModelSelection 吃的是 item.projections.values；这里把已取到的 values 包成同一形状。 */
    private fun wrapValues(values: JsonObject): JsonObject = buildJsonObject {
        put("projections", buildJsonObject { put("values", values) })
    }

    // ============================================================
    // 中继状态
    // ============================================================

    fun relayStatus(reply: Reply<Model.RelayStatus>) {
        val status = Model.RelayStatus()
        status.endpoint = stripScheme(api.origin)
        // 存过令牌不等于连着：顶栏说的是通道事实，不是配置事实。
        status.note = authNote
        status.state = if (authNote.isEmpty() && hasToken()) Model.RelayState.CONNECTED else Model.RelayState.DISCONNECTED
        status.cloudSynced = hasToken() && lastLiveAt > 0L
        status.latencyMs = latencyMs
        // 上行/下行取自网络层那份账（HTTP 拦截器 + WS 帧累加），不是估的；账在 RelayApi 的
        // companion 上，进程内只有一本，不随实例重建清零。累计值留作诊断；hero 显示的是
        // sampleRates 的窗口平均速率（字节/秒）。
        status.upstreamBytes = RelayApi.trafficUp.get()
        status.downstreamBytes = RelayApi.trafficDown.get()
        RelayApi.sampleRates()?.let { (up, down) ->
            status.upstreamBps = up
            status.downstreamBps = down
        }
        status.autoReconnect = preferences().autoReconnect
        val host = current
        if (host != null) {
            status.hostAlias = host.alias
            status.hostPlatform = host.platform
            status.syncedSessions = 0
        }
        reply.done(status, null)
    }

    private fun stripScheme(origin: String): String = origin.replaceFirst("^https?://".toRegex(), "")

    // ============================================================
    // 版本管理（v1 HttpRelayGateway.fetchRelease 一比一移植）
    // ============================================================

    /**
     * 拉取版本清单（静态托管的 version.json），解析成 [Model.Release]。
     *
     * 缺字段时留空而不是失败——`Release.valid` 由调用方判定；解析出的结构不完整
     * （缺版本号或下载地址）才报「版本信息不完整」。
     */
    fun fetchRelease(url: String, reply: Reply<Model.Release>) {
        api.fetch(url) { value, error ->
            if (error != null) { reply.done(null, error); return@fetch }
            if (value == null) { reply.done(null, "没有拿到版本信息"); return@fetch }
            val release = Model.Release(
                latest = value.str("latest"),
                minSupported = value.str("minSupported"),
                url = value.str("url"),
                latestUrl = value.str("latestUrl"),
                size = value.long("size"),
                sha256 = value.str("sha256"),
                notes = value.str("notes"),
                publishedAt = value.str("publishedAt"),
            )
            if (!release.valid) {
                reply.done(null, "版本信息不完整：缺版本号或下载地址")
                return@fetch
            }
            reply.done(release, null)
        }
    }

    // ============================================================
    // 偏好
    // ============================================================

    fun preferences(): Model.Preferences {
        val omit = api.omit
        return Model.Preferences(
            showReasoning = !omit.contains("reasoning"),
            editContent = vaultEditContent(),
            autoReconnect = vaultAutoReconnect(),
            // 中继侧的权威值在服务端；这里的本地缓存只是「上次问到的答案」，
            // 真值由 [loadAttachments] 拉回来覆盖（见那个方法的注释）。
            transferAttachments = vaultTransferAttachments(),
            omitHeader = omit,
        )
    }

    // vault 的偏好读写走 setter 注入（避免网关直接持有 Context 语义）：
    private var vaultEditContent: () -> Boolean = { false }
    private var vaultAutoReconnect: () -> Boolean = { true }
    private var vaultTransferAttachments: () -> Boolean = { true }
    private var vaultSaveEditContent: (Boolean) -> Unit = {}
    private var vaultSaveAutoReconnect: (Boolean) -> Unit = {}
    private var vaultSaveTransferAttachments: (Boolean) -> Unit = {}

    /** 把 vault 的偏好读写挂进来（MainActivity 组装时调用）。 */
    fun bindPreferences(
        editContent: () -> Boolean,
        autoReconnect: () -> Boolean,
        transferAttachments: () -> Boolean,
        saveEditContent: (Boolean) -> Unit,
        saveAutoReconnect: (Boolean) -> Unit,
        saveTransferAttachments: (Boolean) -> Unit,
    ) {
        vaultEditContent = editContent
        vaultAutoReconnect = autoReconnect
        vaultTransferAttachments = transferAttachments
        vaultSaveEditContent = saveEditContent
        vaultSaveAutoReconnect = saveAutoReconnect
        vaultSaveTransferAttachments = saveTransferAttachments
    }

    fun savePreferences(prefs: Model.Preferences) {
        // omit 语义与宿主插件 packages/core/src/filter.ts 对齐：
        //   reasoning = 不下发思考过程；tool = 不下发工具载荷；context = 不下发宿主上下文记录。
        // 手机的「模型处理详情」要展开思考与每一步操作，所以 context 恒定丢弃，其余按开关决定。
        val value = if (prefs.showReasoning) "context" else "reasoning,context"
        api.omit = value
        vault.omit(value)
        vaultSaveEditContent(prefs.editContent)
        vaultSaveAutoReconnect(prefs.autoReconnect)
        vaultSaveTransferAttachments(prefs.transferAttachments)
    }

    /**
     * 读中继侧的「本设备是否传输附件」。
     *
     * 真值在服务端（`devices.attachments`）：这个开关控制的是中继要不要替这台设备
     * 搬运附件字节，所以重装 App、清数据都不会把它忘了，换一台设备也各记各的。
     * 本地那份只是缓存，拉回来才作数——读失败就保持原值，不把开关翻成「关」
     * （翻错会让人以为附件功能坏了）。
     */
    fun loadAttachments(reply: Reply<Boolean>) {
        api.request("/native/v2/attachments", null, true) { value, error ->
            if (error != null) { reply.done(null, error); return@request }
            val enabled = value?.bool("enabled", true) ?: true
            vaultSaveTransferAttachments(enabled)
            reply.done(enabled, null)
        }
    }

    /** 写中继侧的开关；以服务端回显为准，不假设本地先改就成功。 */
    fun setAttachments(enabled: Boolean, reply: Reply<Boolean>) {
        val body = buildJsonObject { put("enabled", enabled) }
        api.request("/native/v2/attachments", body, true) { value, error ->
            if (error != null) { reply.done(null, error); return@request }
            val applied = value?.bool("enabled", enabled) ?: enabled
            vaultSaveTransferAttachments(applied)
            reply.done(applied, null)
        }
    }

    // ============================================================
    // 清理
    // ============================================================

    fun close() {
        api.close()
    }

    // ============================================================
    // 会话摘要解析（session/list 与 api-session/added 共用）
    // ============================================================

    /**
     * 一条宿主会话摘要 → Model.Session；子代理会话与空 id 返回 null。
     *
     * session/list 与 api-session/added 的同一种摘要形状共用它：两处各写一遍
     * 迟早会漂移。归档元数据（/sync 的 items）也是同一形状。
     */
    fun sessionOf(item: JsonObject?, hostAlias: String?): Model.Session? {
        if (item == null) return null
        // 子代理（mnemon 内存审查、委托工具等）由父会话驱动，不能作为顶层会话 follow。
        // 插件端已先过滤一遍并剥掉这两个字段，这里是「旧插件 + 新 App」的兜底。
        if (item["parentSessionId"] != null && item["parentSessionId"] !is kotlinx.serialization.json.JsonNull) return null
        if (item.str("origin") == "subagent") return null
        val s = Model.Session()
        s.id = item.str("sessionId")
        if (s.id.isEmpty()) return null
        s.title = titleOf(item).ifEmpty { fallbackTitleOf(item) }
        if (s.title.contains("dsh-mnemon:delegated") || s.title.startsWith("[dsh-mnemon:") || s.title.contains("delegated review")) return null
        s.running = item.bool("running", false)
        s.blank = item.bool("blank", false)
        s.workspace = workspaceOf(item)
        s.updatedAt = item.long("updatedAt")
        s.messageCount = item.int("messageCount")
        // 首行预览不在这里取：宿主 session/list 的 snippet 已经不转发，预览由
        // AppViewModel 从本地已落盘的消息现算（列表与正文同源，见 Preview.kt）。
        s.subtitle = hostAlias ?: ""
        // 子智能体目录（SessionProjectionMap.subagentCatalog）：宿主只在投影里整表下发。
        // 列表项里带就顺手带上——`/rpc/projections` 那条路在生产日志里 4/4 ECONNRESET，
        // 只靠它的话这颗胶囊会三天两头不显示。缺字段 ≠ 没有子智能体（保留旧值）。
        val projectionValues = item.obj("projections")?.let { it.obj("values") ?: it }
        if (projectionValues != null && projectionValues.containsKey("subagentCatalog")) {
            s.subagents = subagentsOf(projectionValues["subagentCatalog"])
        }
        applyModelSelection(s, item)
        Diag.log("model", "${s.id} -> ${s.modelName.ifEmpty { "(empty)" }}")
        return s
    }

    private fun titleOf(item: JsonObject): String {
        // 这里**不伪造**标题：拿不到真标题就返回空串，由调用方决定是回退到缓存里
        // 已知的标题，还是显示占位。以前这里会退到 cwd 目录名、再退到字面量
        // 「新对话」，于是「有没有标题」这个信息被抹平了——上层拿到的永远是
        // 非空字符串，`ifBlank` 兜底全线失效，顶栏只能先闪一下「新对话」
        // （2026-10-30 实测：打开会话先显示「新会话」，随后才变成真标题）。
        val projections = item.obj("projections") ?: return ""
        val values = projections.obj("values")
        val title = (values ?: projections).str("title")
        if (title.isEmpty() || title == "null") return ""
        return title
    }

    /**
     * 会话列表里没有携带标题时的展示回退：工作区目录名。
     *
     * 只在**没有其他来源**时才用（列表占位、本地草稿）。会话页顶栏宁可先空着等
     * 真标题，也不要先显示一个可能错的目录名然后跳变。
     */
    fun fallbackTitleOf(item: JsonObject?): String {
        if (item == null) return ""
        val cwd = item.str("cwd")
        return if (cwd.isEmpty()) "" else cwd.substringAfterLast('/')
    }

    private fun workspaceOf(item: JsonObject): String {
        val cwd = item.str("cwd")
        if (cwd.isEmpty()) return ""
        val parts = cwd.split(Regex("[/\\\\]"))
        return parts.lastOrNull() ?: ""
    }

    /**
     * 把宿主投影里的模型选择落到会话上。取值优先 next（下一回合将使用的模型），
     * 退回 lastUsed；两者都没有就留空，由 Model.Session.modelLabel 给中性占位。
     */
    private fun applyModelSelection(session: Model.Session, item: JsonObject) {
        val projections = item.obj("projections") ?: return
        val values = projections.obj("values") ?: return
        session.permissionPreset = permissionPresetOf(values["permissions"])
        val selection = values.obj("modelSelection") ?: return
        var chosen = selection.obj("next")
        if (chosen == null) chosen = selection.obj("lastUsed") ?: return
        session.modelProvider = chosen.str("provider")
        session.modelName = chosen.str("model")
        session.reasoningEffort = chosen.str("reasoningEffort")
    }

    /**
     * 权限投影 → 预设名。
     *
     * 宿主投射出来的形状是 {currentValue:"default"}（已归一到某个预设键）。
     * 兼容内核状态形状 {preset,sandbox,approval}。
     */
    private fun permissionPresetOf(raw: JsonElement?): String {
        if (raw !is JsonObject) return ""
        val current = raw.str("currentValue")
        if (current.isNotEmpty()) return current
        val preset = raw.str("preset")
        if (preset.isNotEmpty()) return preset
        val approval = raw.str("approval")
        val sandbox = raw.str("sandbox")
        if (approval == "never") return "danger-full-access"
        if (approval == "ask") return if (sandbox == "read-only") "read-only" else "default"
        return ""
    }


}

// ============================================================
// 流活性看门狗参数（v1 同口径，见 MainActivity.streamWatchdogTick）
// ============================================================

/**
 * 日志里认得出是哪条会话的短 id。
 *
 * 取**尾段**而不是头段：会话 id 一律 `session-` 开头，`take(8)` 每条都长一样，
 * 串号问题反而查不出来（v1 踩过一次，见 `MainActivity.shortId`）。
 */
internal fun shortId(sessionId: String): String {
    val tail = sessionId.substringAfterLast('-')
    return if (tail.length > 8) tail.substring(0, 8) else tail
}

/**
 * 「该有帧」却连续这么久**没有一个有用帧**，就当这条连接半死。
 *
 * <p>45s 是 v1 的值，v2 先照搬；真链路上一次完整回合的帧间隔远小于它，所以按
 * 评审 §7.4 的建议收到 30s——恢复时间直接减掉 15s，而工具执行这类合法静默本就
 * 不在 `busy()` 里计数，不会被误判。要再调就改这一个常量。
 */
internal const val STREAM_SILENCE_MS = 30_000L

/** 看门狗自身轮询间隔。 */
private const val STREAM_WATCHDOG_MS = 10_000L

/** 一次提交/一段流最多自动重开几次；再多就是徒劳重下快照，交给用户手动重进。 */
private const val MAX_AUTO_RESYNCS = 3

/** 看门狗每次 tick 的裁决。 */
internal enum class WatchdogVerdict {
    WAIT,
    RESYNC,
    GIVE_UP,
}

/**
 * 看门狗的一次裁决（纯函数，JVM 单测直接驱动）。
 *
 * @param idleMs 距上一个**有用帧**过去了多久（控制帧不算，见 `Diag.isUsefulFrame`）。
 * @param sinceResyncMs 距上一次自动重开过去了多久。
 * @param attempts 本轮已经重开过几次。
 * @param silenceMs 静默阈值；单测可以传小值，生产用 [STREAM_SILENCE_MS]。
 *
 * 两条间隔判据都必要：`idleMs` 是「真的没帧」，`sinceResyncMs` 防的是「刚重开、
 * 快照正在路上」——没有它会在每 10s tick 里连着重开三次，白下三遍快照。
 *
 * <p>`sinceResyncMs` 的下限随重开次数**线性加长**（30s / 60s / 90s）：这就是评审
 * §7.4 要的「有界退避」——链路真的坏了的时候，每隔 30 秒重下一份几百 KB 的快照
 * 本身就是二次伤害，而总次数上限保证它不会无限拖下去。
 */
internal fun watchdogVerdict(
    idleMs: Long,
    sinceResyncMs: Long,
    attempts: Int,
    silenceMs: Long = STREAM_SILENCE_MS,
): WatchdogVerdict {
    // 已到上限先判：否则 attempts 涨到上限后还要再白等一个退避窗口才认输。
    if (attempts >= MAX_AUTO_RESYNCS) return WatchdogVerdict.GIVE_UP
    if (idleMs < silenceMs) return WatchdogVerdict.WAIT
    val backoff = silenceMs * (attempts.coerceAtLeast(0) + 1)
    if (sinceResyncMs < backoff) return WatchdogVerdict.WAIT
    return WatchdogVerdict.RESYNC
}
