package com.dsh.mobile.v2.net

import com.dsh.mobile.v2.data.Diag
import com.dsh.mobile.v2.data.TokenVault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 中继 HTTP/WS 客户端（v2，Kotlin 版）：照 v1 RelayApi.java 的协议面重写。
 *
 * 覆盖：RPC 轮询（session 系 rpc）、SSE 会话清单（`/sessions`）、SSE 变更流
 * （`/changes`）、v2 控制流之外的 HTTPS 旁路（上传 / 附件 / blob / 版本）。
 *
 * v1 的 `/sync` 差量与 `$events` 事件流两条通路已删除（中继对 `/native/v1/…`
 * 一律 410）：补差走 `RelayApiV2.sync`，审批/提问走控制流的 `INTERACTION` 帧。
 *
 * 与 v1 的差异：
 * - 回调改 Kotlin 高阶函数（Result/Feed/ChangeFeed），主线程投递由
 *   调用方（RelayGateway）用 Handler 自己处理；本类保持纯网络层。
 * - JSON 走 kotlinx-serialization（JsonObject），与数据层一致。
 */
class RelayApi {

    // ============================================================
    // 回调契约
    // ============================================================

    /** 单次往返的结果：value 或 error 二选一。 */
    fun interface Result {
        fun done(value: JsonObject?, error: String?)
    }

    /** 变更流（SSE /changes）的回调。 */
    interface ChangeFeed {
        /**
         * 一条会话变更。中继已经把这次归档的正文一起推下来了（[records]，形状与
         * `/sync` 的 `deltas[].records` 一致），所以拿到就能直接并进本地缓存，
         * 不必再发一次公网 `/sync`。
         *
         * [truncated] 为真表示中继的字节预算没装下全部记录（或手机游标落后太多，
         * 推下来的尾部接不上），此时手机仍要按游标补一次 `/sync`。
         *
         * 没有首行预览：预览由 AppViewModel 从本地已落盘的消息现算（曾经有过，
         * 2026-10-28 下线——它和本地正文是两条水位）。
         */
        fun change(sessionId: String, latestSeq: Long, title: String, records: JsonArray?, truncated: Boolean)
        /** 连接状态（空串表示正常）；只用于日志与顶栏提示。 */
        fun state(note: String)
    }

    // ============================================================
    // 客户端与状态
    // ============================================================

    /** 文本帧按 UTF-8 真实字节数记账：中文按 3 字节算，不按字符数近似。 */
    private fun bytes(text: String): Long = text.toByteArray(Charsets.UTF_8).size.toLong()

    /**
     * 只数字节、不缓存也不改写内容的拦截器：HTTP 侧（RPC / SSE / sync / 附件）全走这里。
     * WS 帧不经过 OkHttp 的拦截器链，所以在 socket.send / onMessage 处单独累加。
     */
    private val trafficInterceptor = Interceptor { chain ->
        val request = chain.request()
        // contentLength() 对确定性 body（JSON 串、文件）是精确值；未知长度（-1）不入账。
        request.body?.contentLength()?.let { if (it > 0) trafficUp.addAndGet(it) }
        val response = chain.proceed(request)
        val body = response.body
        if (body == null) {
            response
        } else if (body.contentLength() > 0) {
            // 定长响应：服务端就是发的这么多，直接入账，不必再包一层读计数。
            trafficDown.addAndGet(body.contentLength())
            response
        } else {
            // 不定长（分块 / SSE 长连接）：只有真读完（或关闭）时才知道多少字节，
            // 所以包一层计数 source，读完一截记一截。
            val counting = object : ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val read = super.read(sink, byteCount)
                    if (read > 0) trafficDown.addAndGet(read)
                    return read
                }
            }.buffer()
            response.newBuilder().body(ResponseBody.create(body.contentType(), -1L, counting)).build()
        }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(trafficInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        // Android 可能长保一条早已死掉的 socket；探活否则只有下次写失败才发现。
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    /**
     * v2 控制流专用：**更短的探活间隔**。
     *
     * <p>控制流是「socket 死了不吭声」的唯一受害者——普通请求死了下一次调用就报错，
     * 而它只在写操作时才发现，读侧完全靠脉搏兜。脉搏判死窗口是 12 秒（插件 5 秒
     * 一条，留两次余量），再快就会把网络抖动误判成死连接。
     *
     * <p>WebSocket 的 ping 是协议层空帧，不发业务数据、不占隧道的应用层配额，
     * 代价只有一点点流量。压到 5 秒之后，OkHttp 一个 ping 超时就 `onFailure` →
     * `open = false`，下一秒的 tick 直接走重开分支，**不必再等脉搏窗口**。
     *
     * <p>为什么不把主客户端的 20 秒也改小：那条给所有 HTTP 请求共用，长轮询
     * （`/sync` 一页可能跑好几秒）和 SSE 都挂在上面，压太短会在正常慢请求上误报。
     */
    val controlClient: OkHttpClient by lazy {
        client.newBuilder()
            .pingInterval(5, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 中继根地址。**换值就是换了一台中继**：旧主机 id、旧令牌、旧变更流全部作废。
     *
     * <p>所以这里用自写 setter 而不是普通字段：换值时必须 ①递增 [relayEpoch]，让在途
     * 的旧答复被丢弃（见 [RelayEpoch]）；②关掉旧中继的 `/changes`——它带的是旧令牌，
     * 换完必然 401/404，然后对着一台在新中继上根本不存在的机器无限重试。
     */
    /** 中继代次（见 [RelayEpoch]）。发请求前 `current()`，回调里 `accepts(epoch)`。 */
    internal val relayEpoch = RelayEpoch()

    var origin: String = ""
        set(value) {
            if (field == value) return
            field = value
            relayEpoch.rotate()
            closeChanges()
        }

    var token: String = ""
    var omit: String = TokenVault.DEFAULT_OMIT

    /** 轮换出新令牌后由 Gateway 落盘；不落盘的话进程一死就回到坏令牌。 */
    var onRotated: (String) -> Unit = {}

    /**
     * 上一代 v1 会话跟随流的残留引用。
     *
     * <p>v2 之后手机不再开这条流，字段留着只为 `closeStream()` 能把老连接收掉。
     */
    private var stream: WebSocket? = null

    /** 递增即让所有在途的旧连接 / 重连作废（换主机、退出登录、换会话时用）。 */
    private var generation = 0

    private var rotating = false

    // ============================================================
    // 状态文案（与 v1 同口径，网关层据此区分凭据级失败）
    // ============================================================

    /** 令牌被中继拒绝：退避重连对 401 毫无作用，必须换令牌或重新登录。 */
    /** 4401：中继明确说这台设备的授权被撤了，除了重新配对没有别的路。 */
    private var mainHandler: android.os.Handler? = null

    fun attachMainThread(handler: android.os.Handler) { mainHandler = handler }

    /** 投递到主线程。v2 的 `RelayApiV2` 也复用这条通道，所以是 internal 而非 private。 */
    /** 当前 origin（`https://host` 形式），给同包内的 v2 协议层拼 URL 用。 */
    internal fun originFor(): String = origin

    /** 当前 bearer，同上。 */
    internal fun tokenFor(): String = token

    /**
     * 裸 GET / 原始字节调用，供 v2 的 blob 下载使用。
     *
     * <p>不复用 [request] 是因为它的语义是「先卡 RESPONSE_LIMIT 再整体 `Json.parse`」，
     * 而 blob 有 10 MiB 上限且只需要原文文本，走同一条路会白白多一次解析。
     */
    internal fun rawCall(request: Request, limit: Int, result: (text: String?, error: String?) -> Unit) {
        rawBytes(request, limit) { bytes, error, _ -> result(bytes?.toString(Charsets.UTF_8), error) }
    }

    /**
     * 裸 GET，回调**原始字节**与状态码（供可续传的 blob 下载使用）。
     *
     * <p>与文本版只差一件事：不当 UTF-8 文本解。分块续传时每一段都可能切在多字节
     * 字符中间，逐段解码会得到一串 U+FFFD，拼起来与中继上的原文不同 —— 而这条通路
     * 的全部意义就是把那条 10 MiB 正文一字不差地补回去。
     *
     * <p>状态码一起交出来，是因为续传必须自己判断 206 与 200：服务端支持 Range 时回
     * 206 并从断点开始，不支持时回 200 且给的是**整个**正文 —— 后者必须从头替换，
     * 追加就会拼出一段重复字节。
     */
    internal fun rawBytes(
        request: Request,
        limit: Int,
        result: (bytes: ByteArray?, error: String?, status: Int) -> Unit,
    ) {
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: IOException) {
                Diag.log("http", "raw fail ${request.url.encodedPath} ${error.javaClass.simpleName}: ${error.message}")
                post { result(null, "connection-failed", 0) }
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use { r ->
                    if (!r.isSuccessful) {
                        post { result(null, r.code.toString(), r.code) }
                        return
                    }
                    val bytes = try {
                        r.body?.bytes() ?: ByteArray(0)
                    } catch (e: IOException) {
                        post { result(null, "read-failed", r.code) }
                        return
                    }
                    // Per-response cap, not a total cap: a resumable download is many
                    // responses and the caller owns the running total. Leaving this
                    // out entirely would let one oversized body exhaust the heap.
                    if (bytes.size > limit) {
                        post { result(null, "too-large", r.code) }
                        return
                    }
                    post { result(bytes, null, r.code) }
                }
            }
        })
    }

    internal fun post(block: () -> Unit) {
        val h = mainHandler
        if (h != null) h.post(block) else block()
    }

    companion object {
        const val TOKEN_REJECTED = "登录已过期，请在设置中轮换令牌，或重新登录"
        const val AUTH_REVOKED = "设备授权已撤销"
        const val PAIR_REJECTED = "配对码不对或已过期，请在中继网页重新生成"

        /** 更新包下载的最大尝试次数（含首轮）；续传每次都能推进一段，几轮足够。 */
        private const val MAX_DOWNLOAD_ATTEMPTS = 4

        /**
         * 与中继之间的累计字节：上行 = 请求体 + WS 文本帧，下行 = 响应体 + WS 文本帧。
         * 进程内累计，不清零（App 重启归零）。账是唯一的，速率采样与诊断都读它。
         *
         * 放在 companion 上而不是实例字段上：一个进程里可能有多个 RelayApi
         * （Activity 重建、重连后重建客户端），账只能有一本，否则 hero 卡显示的是
         * 「当前这个实例碰巧经手的那部分」，另一半流量记在别的实例上看不见。
         */
        val trafficUp = AtomicLong(0)
        val trafficDown = AtomicLong(0)

        // ============================================================
        // 速率：滑动窗口采样（只在上游要显示速率时才采）
        // ============================================================

        /** 一次采样：时间戳 + 累计账本快照。 */
        private class TrafficSample(val atMs: Long, val up: Long, val down: Long)

        /** 最近几秒的采样点；只在 relayStatus 被轮询时推进，不另起线程。 */
        private val rateSamples = ArrayDeque<TrafficSample>()

        /** 窗口宽度和断档判定：断档超过它就把旧采样丢掉，防止「离开设置页 10 分钟」摊平速率。 */
        private const val RATE_WINDOW_MS = 8000L
        private const val RATE_GAP_MS = 3000L

        /**
         * 采一点并返回窗口平均速率（字节/秒）。
         * 窗口里不足两点（刚进设置页）返回 null——UI 显示「—」而不是误导性的 0。
         */
        @Synchronized
        fun sampleRates(): Pair<Long, Long>? {
            val now = System.currentTimeMillis()
            val sample = TrafficSample(now, trafficUp.get(), trafficDown.get())
            val last = rateSamples.lastOrNull()
            if (last == null || now - last.atMs > RATE_GAP_MS) rateSamples.clear()
            rateSamples.addLast(sample)
            while (rateSamples.size > 2 && now - rateSamples.first().atMs > RATE_WINDOW_MS) rateSamples.removeFirst()
            val first = rateSamples.first() ?: return null
            val spanMs = now - first.atMs
            if (rateSamples.size < 2 || spanMs < 500) return null
            val up = ((sample.up - first.up) * 1000 / spanMs).coerceAtLeast(0)
            val down = ((sample.down - first.down) * 1000 / spanMs).coerceAtLeast(0)
            return up to down
        }

        /** 401/403 是「这份凭据不被接受」，不是链路抖动。 */
        fun tokenRejected(code: Int): Boolean = code == 401 || code == 403

        /**
         * 非 2xx 的文案。
         *
         * 不能一律把 403 说成「登录已过期」：配对码错、配对码被撤销走的是
         * 同一个 403，用户照那句提示去重新登录完全解决不了问题，反而被引到错的岔路上。
         * 路径能区分的就在这里分开说，其余保持原样。
         */
        fun failureMessage(path: String, code: Int): String = when {
            path == "/native/v2/pair" && code == 403 -> PAIR_REJECTED
            tokenRejected(code) -> TOKEN_REJECTED
            else -> "请求失败（$code）"
        }

        /** 主机已被撤销（410 Gone）：重连没有意义，也不该去轮换令牌。 */
        fun hostGone(code: Int): Boolean = code == 410

        /** 这两句是"凭据级失败"：首页顶栏必须跟着闭嘴，不能继续报绿色健康。 */
        fun credentialFailure(message: String): Boolean =
            TOKEN_REJECTED == message || AUTH_REVOKED == message

        /** Cloud relays bill egress：重连只回买 2 回合 + 消息下限，而不是固定一百条。 */
        private const val MAX_MESSAGES = 40
        private const val MIN_MESSAGES = 16
        private const val MIN_TURNS = 2

        private const val RESPONSE_LIMIT = 16 * 1024 * 1024

        private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

        /**
         * 归一化中继根地址：根路径、不带凭据/查询/片段。
         *
         * <p>**域名必须 HTTPS，IP 字面量允许明文 http**——自建裸 IP 部署拿不到 IP 证书（要单独
         * 签发），中继那边用 `RELAY_ALLOW_INSECURE=true` 明文起服务，App 的
         * `network_security_config` 也放开了 cleartext。两处必须同一口径：
         * `ScanScreen.parsePairing` 给 IP 字面量补的就是 `http://`，这里若只认 HTTPS，
         * 扫码链路会在「地址归一化」这一步就把自己刚拼好的地址否掉——界面上表现为扫码后
         * 毫无反应（错误只落在 loginError，而首页看不到它）。
         */
        fun normalize(value: String): String {
            val url = value.trim().toHttpUrlOrNull()
                ?: throw IllegalArgumentException(ENDPOINT_INVALID)
            val ipLiteral = IPV4.matches(url.host) || url.host.contains(':')
            if (url.username.isNotEmpty() || url.password.isNotEmpty()
                || url.encodedPath != "/" || url.query != null || url.fragment != null
                || (!url.isHttps && !ipLiteral)
            ) throw IllegalArgumentException(ENDPOINT_INVALID)
            return url.toString().trimEnd('/')
        }

        /** 地址不合法的提示语（登录页与深链共用一句）。 */
        const val ENDPOINT_INVALID = "请输入中继地址（域名用 https，IP 可用 http）"
    }

    // ============================================================
    // 令牌自愈
    // ============================================================

    /**
     * 令牌被拒后的自愈：调一次 /native/v2/rotate 换新令牌并重开这条流。
     * 轮换本身失败就不再重试（继续用旧令牌重连是把同一个 401 重播）。
     */
    private fun rotateOnce(reopen: () -> Unit, report: (String) -> Unit) {
        if (rotating) return
        rotating = true
        Diag.log("auth", "token rejected, rotating once")
        rotate { value, error ->
            rotating = false
            val fresh = value?.str("token") ?: ""
            if (error != null || fresh.isEmpty()) {
                Diag.log("auth", "rotate failed ${error ?: "empty token"}")
                post { report(TOKEN_REJECTED) }
                return@rotate
            }
            onRotated(fresh)
            Diag.log("auth", "rotated, reopening")
            post(reopen)
        }
    }

    // ============================================================
    // 单次往返
    // ============================================================

    fun request(path: String, payload: JsonObject?, authenticated: Boolean, result: Result) {
        val builder = try {
            val b = Request.Builder().url(origin + path).header("Accept", "application/json")
            if (authenticated) b.header("Authorization", "Bearer $token")
            // 历史页也走普通 HTTP；同一头部让宿主插件在上传前压缩，不只是流式通道。
            if (authenticated && omit.isNotEmpty()) b.header("x-dsh-omit", omit)
            if (payload != null) b.post(
                payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            )
            b
        } catch (e: Exception) {
            result.done(null, "无法构造请求")
            return
        }
        send(builder, path, result)
    }

    /**
     * 一次带鉴权的 GET。
     *
     * 会话清单现在由中继用自己的 `session_meta` 回答（`GET …/sessions`），不再经隧道
     * 问宿主的 `rpc/list`：电脑不在线也能看列表，而且省掉那跳 loopback。GET 的语义也
     * 更诚实——它不写任何东西，中继侧为此专门拒绝了带 `x-dsh-omit` 之外的 POST 语义。
     */
    fun get(path: String, authenticated: Boolean, result: Result) {
        val builder = try {
            val b = Request.Builder().url(origin + path).header("Accept", "application/json")
            if (authenticated) b.header("Authorization", "Bearer $token")
            b
        } catch (e: Exception) {
            result.done(null, "无法构造请求")
            return
        }
        send(builder, path, result)
    }

    /** [request] 与 [get] 的共同后半段：发出去、换算错误文案、解析 JSON。 */
    private fun send(builder: Request.Builder, path: String, result: Result) {
        client.newCall(builder.build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: IOException) {
                // 真实原因只有这里看得到：TLS 不受信、DNS、超时、连接被拒在这层全被压成同一句文案。
                Diag.log("http", "fail $path ${error.javaClass.simpleName}: ${error.message}")
                post { result.done(null, "连接失败，请检查网络后刷新；消息不会自动重发") }
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use { r ->
                    val value = try {
                        if (!r.isSuccessful) {
                            post { result.done(null, failureMessage(path, r.code)) }
                            return
                        }
                        val bytes = r.body?.bytes() ?: ByteArray(0)
                        if (bytes.size > RESPONSE_LIMIT) throw IOException("response-limit")
                        Json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject
                    } catch (e: Exception) {
                        Diag.log("http", "parse $path ${e.javaClass.simpleName}: ${e.message} code=${r.code} len=${r.body?.contentLength() ?: -1}")
                        post { result.done(null, "中继响应格式不兼容，请检查服务版本") }
                        return
                    }
                    post { result.done(value, null) }
                }
            }
        })
    }

    /**
     * 上传一个非图片附件，换回宿主发的 receiptId。
     *
     * 走宿主的 /api/session/uploadFileBinary：body 是原始字节（不是 JSON），
     * content-type 必须是 application/octet-stream。应答形如
     * `{ok:true, value:{receiptId, file:{attachmentId, name, bytes}}}`，
     * 拿到 receiptId 之后 prompt 里才放 `{type:"file", receiptId}`。
     *
     * 单独一条 OkHttpClient 只为放宽写超时：主客户端的 read 120s 够用，
     * 但大文件在慢网络下写也要占时间。
     */
    fun upload(hostId: String, sessionId: String, name: String, bytes: ByteArray, result: Result) {
        try {
            val base = (origin + "/native/v2/hosts/$hostId/upload").toHttpUrlOrNull()
            if (base == null) { post { result.done(null, "中继地址无效") }; return }
            val url = base.newBuilder().addQueryParameter("sessionId", sessionId).apply {
                if (name.isNotEmpty()) addQueryParameter("name", name)
            }.build()
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .post(bytes.toRequestBody("application/octet-stream".toMediaType()))
                .build()
            uploadClient.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, error: IOException) {
                    Diag.log("upload", "fail $sessionId ${error.javaClass.simpleName}: ${error.message}")
                    post { result.done(null, "上传失败，请检查网络") }
                }

                override fun onResponse(call: okhttp3.Call, response: Response) {
                    response.use { r ->
                        if (!r.isSuccessful) {
                            Diag.log("upload", "$sessionId -> ${r.code}")
                            post { result.done(null, failureMessage("/upload", r.code)) }
                            return
                        }
                        val value = try {
                            Json.parseToJsonElement(r.body?.string() ?: "") as? JsonObject
                        } catch (e: Exception) {
                            post { result.done(null, "中继响应格式不兼容，请检查服务版本") }
                            return
                        }
                        post {
                            val inner = value?.obj("value")
                            if (value?.bool("ok", false) != true || inner == null) {
                                result.done(null, value?.obj("error")?.str("code") ?: "上传被拒绝")
                            } else {
                                result.done(inner, null)
                            }
                        }
                    }
                }
            })
        } catch (e: Exception) {
            result.done(null, "无法构造上传请求")
        }
    }

    /** 附件上传专用：写超时放宽到 5 分钟，其余沿用主客户端。 */
    private val uploadClient: OkHttpClient by lazy {
        client.newBuilder()
            .writeTimeout(5, TimeUnit.MINUTES)
            .callTimeout(6, TimeUnit.MINUTES)
            .build()
    }

    /**
     * 轻量往返探测：GET 一个已存在的端点，只报耗时。
     * 顶栏延迟数字必须是真实 RTT；复用同一个 OkHttpClient（连接池），
     * 测的是已建立连接上的一次往返，不含 TLS 握手。
     */
    fun probe(path: String, done: (Int) -> Unit) {
        if (origin.isEmpty()) { Diag.log("probe", "skip $path origin-empty"); done(-1); return }
        sendProbe(path, attempt = 0, done = done)
    }

    /**
     * 幂等 GET 允许重试一次：连接池里那条 socket 可能已被对端关掉（keep-alive 超时、
     * NAT 表过期、切网），OkHttp 会先把它取出来再发现是死的。这种失败跟延迟无关，
     * 重试一次就能拿到真实读数；真的连不上才会连着两次失败。
     */
    private fun sendProbe(path: String, attempt: Int, done: (Int) -> Unit) {
        val start = System.nanoTime()
        val request = Request.Builder().url(origin + path).get()
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .header("Authorization", "Bearer $token")
            .build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: IOException) {
                Diag.log("probe", "fail $path try=$attempt ${error.javaClass.simpleName}: ${error.message}")
                if (attempt == 0) sendProbe(path, attempt + 1, done) else post { done(-1) }
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use { r ->
                    val code = r.code
                    val ms = maxOf(0L, (System.nanoTime() - start) / 1_000_000L).toInt()
                    Diag.log("probe", "$path -> $code in ${ms}ms try=$attempt")
                    // 4xx 说明服务器答了话（链路是通的）；延迟照样有意义。
                    post { done(if (code >= 500) -1 else ms) }
                }
            }
        })
    }

    // ============================================================
    // RPC（宿主单元 RPC 的 HTTP 外壳）
    // ============================================================

    /**
     * 打一路 session 系 RPC。
     *
     * DSH 的 session/list 把参数命名为 _request（保留占位），其余端点都是 request；
     * modelCatalog 不带参数。method 形如 "list"/"prompt"/"page"…
     */
    fun rpc(hostId: String, method: String, payload: JsonObject?, result: Result) {
        try {
            val rpcId = UUID.randomUUID().toString()
            val args = buildJsonObject {
                if (method == "list") put("_request", payload ?: kotlinx.serialization.json.JsonObject(emptyMap()))
                else if (method != "modelCatalog") put("request", payload ?: kotlinx.serialization.json.JsonObject(emptyMap()))
            }
            val message = buildJsonObject {
                put("type", "client-request")
                put("rpcId", rpcId)
                put("method", "session/$method")
                put("payload", buildJsonObject { put("args", args) })
            }
            request("/native/v2/hosts/$hostId/rpc/$method", message, true, Result { value, error ->
                if (error != null) { result.done(null, error); return@Result }
                val r = value?.obj("result")
                val okRpc = value?.str("rpcId") == rpcId
                if (!okRpc || r == null || !r.bool("ok", false)) {
                    if (r == null) { result.done(null, "DSH 响应格式错误"); return@Result }
                    // 宿主的 error.message 是英文诊断串，只进诊断日志；界面按 code 取中文。
                    val e = r["error"]
                    Diag.log("rpc", "$method failed ${HostErrors.original(e)}")
                    result.done(null, HostErrors.message(e))
                    return@Result
                }
                val v = r["value"] as? JsonObject
                result.done(v ?: kotlinx.serialization.json.JsonObject(emptyMap()), null)
            })
        } catch (e: Exception) {
            result.done(null, "无法构造请求")
        }
    }

    // ============================================================
    // 会话流（session/follow）
    // ============================================================

    /**
     * 关掉 v1 的会话跟随流。
     *
     * <p>v2 之后手机不再开这条流（正文与写操作都走 `/native/v2/.../control`），
     * 所以这里只剩「把上一代可能残留的连接收掉」的作用。保留方法而不是删掉调用点，
     * 是因为 `logout()` / `selectHost()` / `close()` 三处都靠它表达「换主机或退出，
     * 别让旧连接继续跑」。
     */
    fun closeStream() {
        generation++
        stream?.cancel()
        stream = null
    }

    /** One page of older messages. `throughSeq` is the snapshot cursor: DSH will not read past it. */
    fun page(hostId: String, sessionId: String, throughSeq: Long, beforeSeq: Long, result: Result) {
        val payload = buildJsonObject {
            put("address", buildJsonObject { put("kind", "session"); put("sessionId", sessionId) })
            put("throughSeq", throughSeq)
            if (beforeSeq > 0) put("beforeSeq", beforeSeq)
            put("maxMessages", 25)
        }
        rpc(hostId, "page", payload, result)
    }

    // ============================================================
    // 版本管理（公网直连，v1 RelayApi.fetch/download 一比一移植）
    // ============================================================

    /**
     * 打一个公网 URL（版本清单）。
     *
     * 与 [request] 的区别：不带 Authorization、不带 x-dsh-omit、不吃中继的
     * RESPONSE_LIMIT 语义（走独立的 1MB 上限）。中继域名挂了这条路也要能通——
     * 否则用户拿不到升级提示。
     *
     * 这里用 [publicClient] 而不是 [client]：发布服务器在 Cloudflare 后面，DNS 同时
     * 给 A 与 AAAA；主客户端为了不重复发写请求关掉了跨路由重试，落到没有 IPv6 出口
     * 的设备上就是每次「连不上发布服务器」。
     */
    fun fetch(url: String, result: Result) {
        try {
            val request = Request.Builder().url(url).get()
                .header("Accept", "application/json")
                .header("Cache-Control", "no-store")
                .build()
            publicClient.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, error: IOException) {
                    post { result.done(null, "连不上发布服务器") }
                }

                override fun onResponse(call: okhttp3.Call, response: Response) {
                    response.use { r ->
                        val value = try {
                            if (!r.isSuccessful) {
                                post { result.done(null, "服务器返回 ${r.code}") }
                                return
                            }
                            val text = r.body?.string() ?: ""
                            if (text.length > 1024 * 1024) throw IOException("response-limit")
                            Json.parseToJsonElement(text) as? JsonObject ?: throw IOException("not-json")
                        } catch (e: Exception) {
                            // 对外只说「格式不兼容」，真实原因写日志：现场排查要能分清
                            // 是解析炸了、读流炸了，还是被中间设备改了内容。
                            android.util.Log.w("DSH", "release manifest failed: $url", e)
                            post { result.done(null, "版本信息格式不兼容") }
                            return
                        }
                        post { result.done(value, null) }
                    }
                }
            })
        } catch (e: Exception) {
            result.done(null, "无法构造请求")
        }
    }

    /**
     * 公网 GET 专用客户端（版本清单）。
     *
     * 与主客户端唯一的差别是**允许在路由间重试**。主客户端关了它是为了不重复发
     * 写请求；但发布服务器在 Cloudflare 后面，DNS 同时给 A 与 AAAA，设备没有
     * IPv6 出口时（模拟器、部分蜂窝网络、关了 IPv6 的路由器）第一条 IPv6 路由
     * 必然失败，关掉重试就永远停在「连不上发布服务器」。清单是幂等 GET。
     */
    private val publicClient: OkHttpClient by lazy {
        client.newBuilder()
            .retryOnConnectionFailure(true)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 更新包下载专用客户端。
     *
     * 两处与主客户端不同，都是实测踩出来的：
     *
     * 1. **强制 HTTP/1.1**：2026-10-28 在 API 35 模拟器上走 h2 下这个 13MB 的 APK，
     *    稳定在 16%~19% 处收到 `stream was reset: PROTOCOL_ERROR`——同一个地址在
     *    宿主机上用 h1.1 与 h2 都能完整下完，问题出在这条链路上 h2 的流被中间设备
     *    重置。安装包是整份文件，没有多路复用必要，退回 h1.1 最稳。
     *
     * 2. **允许在路由间重试**：理由同 [publicClient]，且下载是幂等的 GET，
     *    换到 IPv4 重试没有任何副作用。关掉重试时界面会停在
     *    `Failed to connect to …/[2606:4700:…]:443`。
     */
    private val downloadClient: OkHttpClient by lazy {
        publicClient.newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 下载一个文件到指定路径（流式写盘，边下边报进度）。
     *
     * 自己下载而不用系统 DownloadManager：那个服务在部分设备/镜像上压根不存在
     * （实测某个 API 35 模拟器 service list 里就没有 download），而 enqueue 还会
     * 返回假 id，功能就这么静默失效。
     *
     * 抗断：失败后按已有字节数发 `Range` 续传重试，最多 [MAX_DOWNLOAD_ATTEMPTS] 次；
     * 只有 `?` 之外的失败才留残包，成功后按 [expectSha256] 校验，不匹配就丢弃。
     *
     * @param progress 已下载字节 / 总字节（总字节未知时给 -1）
     * @param expectSha256 发布清单里的 sha256；为空则跳过校验
     */
    fun download(
        url: String,
        target: java.io.File,
        expectSha256: String,
        progress: (done: Long, total: Long) -> Unit,
        result: Result,
    ) {
        if (url.isEmpty()) { result.done(null, "下载地址为空"); return }
        val part = java.io.File(target.absolutePath + ".part")
        Thread({
            var lastError = "网络中断"
            for (attempt in 1..MAX_DOWNLOAD_ATTEMPTS) {
                if (attempt > 1) {
                    Diag.log("update", "download retry #$attempt part=${part.length()} url=$url")
                    try { Thread.sleep(700L * (attempt - 1)) } catch (e: InterruptedException) { break }
                }
                try {
                    val failure = downloadOnce(url, part, progress)
                    if (failure != null) { lastError = failure; continue }
                    if (target.exists() && !target.delete()) throw IOException("旧安装包删不掉")
                    if (!part.renameTo(target)) throw IOException("无法写入目标文件")
                    val bad = verifySha256(target, expectSha256)
                    if (bad != null) {
                        target.delete(); part.delete()
                        post { result.done(null, bad) }
                        return@Thread
                    }
                    post { result.done(null, null) }
                    return@Thread
                } catch (e: Exception) {
                    lastError = e.message ?: "网络中断"
                    Diag.log("update", "download attempt=$attempt failed: $lastError")
                }
            }
            part.delete()
            post { result.done(null, "下载失败：$lastError") }
        }, "dsh-update-download").start()
    }

    /** 下载一次（带续传）；成功返回 null，失败返回可展示的错误文案。 */
    private fun downloadOnce(
        url: String,
        part: java.io.File,
        progress: (done: Long, total: Long) -> Unit,
    ): String? {
        val already = if (part.exists()) part.length() else 0L
        val builder = Request.Builder().url(url).get()
        if (already > 0) builder.header("Range", "bytes=$already-")
        val response = try {
            downloadClient.newCall(builder.build()).execute()
        } catch (e: IOException) {
            return e.message ?: "网络中断"
        }
        response.use { r ->
            if (r.code == 416) {
                // 本地残包比服务器上的还长（换过版本），这段 Range 无从谈起：清掉重来。
                part.delete()
                return "断点失效，重试"
            }
            val resumed = r.code == 206
            if (!r.isSuccessful && !resumed) return "服务器返回 ${r.code}"
            val body = r.body ?: return "服务器没有返回内容"
            if (!resumed) part.delete()
            val start = if (resumed) already else 0L
            val total = if (body.contentLength() > 0) body.contentLength() + start else -1L
            var done = start
            try {
                java.io.FileOutputStream(part, resumed).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n == -1) break
                            output.write(buffer, 0, n)
                            done += n
                            val d = done
                            val t = total
                            post { progress(d, t) }
                        }
                    }
                    output.flush()
                }
            } catch (e: IOException) {
                // 残包留着，下一轮续传接着下。
                return e.message ?: "连接中断"
            }
            if (total > 0 && done != total) return "连接提前结束（$done/$total）"
            return null
        }
    }

    /** 校验 sha256；不匹配返回错误文案，匹配或清单没给校验值返回 null。 */
    private fun verifySha256(file: java.io.File, expect: String): String? {
        val want = expect.trim().lowercase()
        if (want.isEmpty()) return null
        val actual = try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    digest.update(buffer, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Diag.log("update", "sha256 failed: ${e.message}")
            file.delete()
            return "安装包校验失败，请重试"
        }
        if (actual != want) {
            Diag.log("update", "sha256 mismatch want=$want got=$actual")
            return "安装包不完整，请重试"
        }
        return null
    }

    /**
     * 执行一条斜杠命令：走宿主的 commands/execute 单元 RPC。
     * args 形状是 {agentId, line, submittedAttachments}；line 是带前导斜杠的整行。
     */
    fun command(hostId: String, sessionId: String, line: String, result: Result) {
        try {
            val rpcId = UUID.randomUUID().toString()
            val args = buildJsonObject {
                put("agentId", sessionId)
                put("line", line)
                put("submittedAttachments", kotlinx.serialization.json.JsonArray(emptyList()))
            }
            val message = buildJsonObject {
                put("type", "client-request")
                put("rpcId", rpcId)
                put("method", "commands/execute")
                put("payload", buildJsonObject { put("args", args) })
            }
            request("/native/v2/hosts/$hostId/rpc/command", message, true, Result { value, error ->
                if (error != null) { result.done(null, error); return@Result }
                val r = value?.obj("result")
                val okRpc = value?.str("rpcId") == rpcId
                if (value == null || !okRpc || r == null || !r.bool("ok", false)) {
                    val e = r?.get("error")
                    Diag.log("rpc", "command failed ${HostErrors.original(e)}")
                    result.done(null, HostErrors.message(e))
                    return@Result
                }
                // value = {commandId, result:{kind:"success"|..., text}}；没匹配上也算成功。
                result.done(r.obj("value") ?: kotlinx.serialization.json.JsonObject(emptyMap()), null)
            })
        } catch (e: Exception) {
            result.done(null, "无法构造请求")
        }
    }

    /** 轮换 native bearer：旧令牌立刻作废，新令牌经 reply 返回。 */
    fun rotate(result: Result) {
        request("/native/v2/rotate", kotlinx.serialization.json.JsonObject(emptyMap()), true, Result { value, error ->
            if (error != null) { result.done(null, error); return@Result }
            val fresh = value?.str("token") ?: ""
            if (fresh.isEmpty()) { result.done(null, "中继未返回新令牌"); return@Result }
            token = fresh
            result.done(buildJsonObject { put("token", fresh) }, null)
        })
    }

    // ============================================================
    // 会话变更流（SSE /changes）
    // ============================================================

    /**
     * 订阅中继的会话变更流（写扩散的消费端）。
     *
     * 中继已经知道每个会话的最新 seq 与首行预览，直接把变更推下来——手机拿到
     * 就能画列表行，不必再发一次 /sync。用 SSE 而不是 WebSocket：只下行、帧很小、
     * 自带 retry 语义，也不必和会话流抢 WS 预算。
     */
    /** 会话变更流（SSE）的连接状态；独立计数，退避不受别的流影响。 */
    private var changesCall: okhttp3.Call? = null
    private var changesGeneration = 0
    private var changesFailures = 0
    private var changesHostId = ""
    private var changesRetry: android.os.Handler? = null

    fun watchChanges(hostId: String, feed: ChangeFeed) {
        closeChanges()
        changesHostId = hostId ?: ""
        val epoch = changesGeneration
        Diag.log("preview", "changes open host=${hostId.take(8)} token=${if (token.isEmpty()) "none" else "ok"}")
        val request = Request.Builder()
            .url(origin + "/native/v2/hosts/$hostId/changes")
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-store")
            .header("Authorization", "Bearer $token")
            .build()
        changesCall = client.newCall(request)
        changesCall?.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: IOException) {
                if (epoch != changesGeneration) return
                post { scheduleChangesRetry(hostId, feed, "连接失败") }
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                if (epoch != changesGeneration) { response.close(); return }
                response.use { r ->
                    if (!r.isSuccessful || r.body == null) {
                        val code = r.code
                        if (tokenRejected(code)) {
                            post {
                                rotateOnce({ if (epoch == changesGeneration) watchChanges(hostId, feed) }) { state ->
                                    if (epoch == changesGeneration) feed.state(state)
                                }
                            }
                            return
                        }
                        post { scheduleChangesRetry(hostId, feed, "HTTP $code") }
                        return
                    }
                    post { if (epoch == changesGeneration) feed.state("") }
                    try {
                        val reader = r.body!!.charStream().buffered()
                        var event = ""
                        val data = StringBuilder()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (epoch != changesGeneration) return
                            if (line.isEmpty()) {
                                if (event == "change" && data.isNotEmpty()) deliverChange(data.toString(), feed)
                                event = ""
                                data.setLength(0)
                                continue
                            }
                            when {
                                line.startsWith(":") -> {} // 心跳注释帧
                                line.startsWith("event:") -> event = line.substring(6).trim()
                                line.startsWith("data:") -> data.append(line.substring(5).trim())
                            }
                        }
                        post { scheduleChangesRetry(hostId, feed, "流已结束") }
                    } catch (e: Exception) {
                        if (epoch == changesGeneration) post { scheduleChangesRetry(hostId, feed, "读取中断") }
                    }
                }
            }
        })
    }

    private fun deliverChange(json: String, feed: ChangeFeed) {
        val o = parseJson(json) ?: return
        val sessionId = o.str("sessionId")
        if (sessionId.isEmpty()) return
        // 正文随变更一起到：形状和 /sync 的 deltas[].records 一样（[{event:{…}}]），
        // 直接交给 mergeSyncRecords 就能并入时间线。解析失败按「没有正文」处理，
        // 手机随后仍会按游标补 /sync，不会因此丢消息。
        val records = o.arr("records")
        val truncated = o.bool("truncated")
        post { feed.change(sessionId, o.long("latestSeq"), o.str("title"), records, truncated) }
    }

    private fun scheduleChangesRetry(hostId: String, feed: ChangeFeed, why: String) {
        val epoch = changesGeneration
        if (epoch != changesGeneration) return
        if (hostId != changesHostId) return
        changesRetry?.removeCallbacksAndMessages(null)
        feed.state(why)
        // 退避上限 30 秒：中继重启这类短抖动几秒就恢复。
        val delay = minOf(3000L * (changesFailures + 1), 30000L)
        changesFailures++
        postDelayed(delay) {
            if (epoch == changesGeneration && token.isNotEmpty()) watchChanges(hostId, feed)
        }
    }

    fun closeChanges() {
        changesGeneration++
        changesFailures = 0
        changesRetry?.removeCallbacksAndMessages(null)
        changesCall?.cancel()
        changesCall = null
    }

    fun close() {
        closeStream()
        closeChanges()
        client.dispatcher.cancelAll()
        Thread { client.connectionPool.evictAll() }.start()
    }

    // ============================================================
    // 内部
    // ============================================================

    private fun postDelayed(delay: Long, block: () -> Unit) {
        val h = mainHandler ?: android.os.Handler(android.os.Looper.getMainLooper())
        h.postDelayed(block, delay)
    }
}

/**
 * 把中继 origin 换成同主机同端口的 WS 地址。
 *
 * <p>早先各处写的是 `origin.replaceFirst("^https:", "wss:")`，它只在 https 上成立：
 * 自建裸 IP 部署是 `http://1.2.3.4:8080`，替换不到就得到 `http://…` 的 WS 地址，
 * OkHttp 会直接拒绝（`Expected URL scheme 'http' or 'https'`）。所以改成显式映射，
 * 并保持其余部分（主机、端口、路径前缀）一字不动。
 */
internal fun wsUrl(origin: String): String = when {
    origin.startsWith("https://") -> "wss://" + origin.removePrefix("https://")
    origin.startsWith("http://") -> "ws://" + origin.removePrefix("http://")
    else -> origin
}
