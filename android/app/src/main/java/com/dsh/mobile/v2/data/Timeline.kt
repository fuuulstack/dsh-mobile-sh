package com.dsh.mobile.v2.data

/**
 * 会话时间线上的一条记录 —— 三端共用的唯一存储单元（v2 §二 数据模型）。
 *
 * 重构前每一跳都自创形状：插件推 `{sessionId, seq, record}`，中继存不透明密文，
 * 手机在渲染时再从原始事件名现猜「这是什么」。三个解析器、三套 bug、一个字段都
 * 建不了索引。
 *
 * [kind] 是**插件**给出的粗分类（它才看得见宿主流），[payloadJson] 是宿主事件的
 * 原文，一字不改。于是新事件类型既不需要中继改 schema，也不需要手机改协议 ——
 * 这正是 `filter.ts` 能彻底放弃「值得转发的事件白名单」的原因：已经没有任何一跳
 * 必须读懂 payload 才能搬运它。
 *
 * [seq] 由插件分配：会话内严格递增，但**从不假设连续**。[prevSeq] 是「存在缺口」
 * 的物证，手机的水位只允许跨过连续事务推进（v2 §4.3）。
 */
data class TimelineRecord(
    val sessionId: String,
    val seq: Long,
    /** 前驱 seq；0 表示「这是我看得见的最早一条」。 */
    val prevSeq: Long,
    /** 形如 `<sessionId>:t<turn>`；无回合归属时为空串。 */
    val turnId: String,
    /** 形如 `<turnId>:s<step>`；非步骤记录为 null。 */
    val stepId: String? = null,
    val kind: RecordKind,
    val timestampMs: Long = 0,
    /** 手机发送时带的幂等键：本地回声靠它认领自己的宿主副本。 */
    val clientMsgId: String? = null,
    /** 宿主事件原文：`{"type":…,"seq":…,"data":…}`。 */
    val payloadJson: String = "",
    /** payload 超阈值后被离载成存根（走 `GET /v2/blobs/:id` 取回）。 */
    val isOversizedStub: Boolean = false,
    val blobId: String? = null,
    val blobSize: Long = 0,
    /** 非 0 = 原件超过硬上限，被截成首尾各 32 KiB；界面要显示成错误行。 */
    val truncatedFrom: Long = 0,
    /**
     * 生产者离载时留下的正文头部（wire 上的 `stub.previewHead`）。
     *
     * <p>存根自己的 `payloadJson` 只是 `{"type":"stub/payload","blobId":…}` ——
     * 里面**没有正文**。列表首行预览要么等 blob 补回来，要么用这个字段。
     * 早先这里直接 `payloadJson.take(2048)`，于是「最后一条回复超过 256 KiB」的
     * 会话在列表里显示的是那段 JSON 信封原文。
     */
    val stubPreviewHead: String = "",
) {
    /**
     * 从这条记录里现解出一段可读文本 —— 列表首行预览用。
     *
     * **不要**把它写成「只有存根才有内容」。`isOversizedStub` 是「正文太大被离载」
     * 的标志，正常记录恰恰是内容都在的那种。2026-10-31 真机实测：冷启动后列表所有
     * 会话的 snippet 一起消失，`firstAssistant(...)?.previewHead()` 对 89 条正常
     * ASSISTANT_TEXT 全部返回空串，预热队列（`AppViewModel.warmPreviews` 里
     * `if (!head.isNullOrEmpty())`）永远补不上。
     *
     * 存根优先用 [stubPreviewHead]（生产者离载时留的正文头部）；老记录没有这个字段时
     * 退回 `payloadJson` —— 那是 JSON 信封，不好看，但比空白强，而且一旦 blob 补回来
     * 就会被 [TimelineStore.fillStub] 整条替换掉。
     */
    fun previewHead(): String {
        if (isOversizedStub) {
            return stubPreviewHead.ifEmpty { payloadJson.take(2048) }
        }
        val data = com.dsh.mobile.v2.net.parseJson(payloadJson)?.get("data")
            as? kotlinx.serialization.json.JsonObject ?: return ""
        val message = data["message"] as? kotlinx.serialization.json.JsonObject
        return listOf(
            com.dsh.mobile.v2.net.textContent(data["content"]),
            message?.let { com.dsh.mobile.v2.net.textContent(it["content"]) } ?: "",
            com.dsh.mobile.v2.net.contentOf(data["text"]),
            message?.let { com.dsh.mobile.v2.net.contentOf(it["text"]) } ?: "",
        ).firstOrNull { it.isNotEmpty() }?.trim() ?: ""
    }

    /**
     * 这条记录**有没有正文**（写库时算好，落成 `timeline_records.has_body`）。
     *
     * <p>为什么需要它：`ASSISTANT_TEXT` 名不副实 —— 宿主每次 `assistant/message`
     * 都记成它，而一回合里大量只含 `type=reasoning`。生产库实测 7786 条里
     * **5806 条没有正文（75%）**。而「最近一条带正文的助手消息」是列表首行预览
     * 与翻页边界的唯一依据。
     *
     * <p>没有这一列时只能倒扫逐条 [previewHead] 试探，靠一个扫描上限兜底；
     * 一旦连续无正文的记录超过上限，预览直接变空白。2026-11-03 模拟器实测：
     * 造 200 条纯思考就把 `scanAssistantBody` 的 64 条上限打穿，列表预览整行消失。
     *
     * <p>**判据用真的解析结果，不用字面量猜**：曾经想用
     * `payloadJson.contains("\"type\":\"text\"")` 这种便宜办法（对生产库 7786 条
     * 比对过，零漏零误），但思考正文里恰好出现 `"type":"text"` 字面量就会变成
     * 假阳性 —— 假阳性的后果是预览又变空白，正是这次要修的 bug。
     * 写入时每条只算一次，读侧永远是索引点查，这个解析代价买的是正确性。
     *
     * <p>存根（[isOversizedStub]）不看 payload：它的 payload 只有 blobId，
     * 正文头部在 [stubPreviewHead] 里。
     */
    fun hasBody(): Boolean = when {
        isOversizedStub -> stubPreviewHead.isNotEmpty()
        kind != RecordKind.ASSISTANT_TEXT -> false
        else -> previewHead().isNotEmpty()
    }

    /**
     * 这条记录是不是**后台任务的生命周期事件**（`job/registered` / `job/settled`）。
     *
     * <p>两种形态都要认：
     *  - [RecordKind.JOB_EVENT] —— 手机自己落盘时改写过的名字（见
     *    `TimelineRecordEntity.of`）。改名是为了让「按 kind 整批删 META_PATCH」的
     *    落盘过滤与启动清理**碰不到**它们。
     *  - [RecordKind.META_PATCH] —— 中继归档里历史记录带的仍是插件当年的分类。
     *    归档的 kind 是写进去就定型的，改不了，所以进来时还得靠 payload 的 `type`
     *    认一遍；认出来之后同样按 JOB_EVENT 落盘。
     */
    fun isJobLifecycle(): Boolean = when (kind) {
        RecordKind.JOB_EVENT -> true
        RecordKind.META_PATCH -> eventType() in JOB_EVENT_TYPES
        else -> false
    }

    /**
     * payload 顶层的 `type`（宿主的事件类型）。
     *
     * <p>刻意**不用** `RecordKind`：那一层是有损的粗分类，`META_PATCH` 一个桶里装着
     * 十几种事件，判断具体是哪种只能回 payload 里读。读不到就给空串 —— 一切按
     * 「不是我要的」处理，绝不因为 payload 形状变了就崩。
     */
    fun eventType(): String {
        val obj = com.dsh.mobile.v2.net.parseJson(payloadJson) as? kotlinx.serialization.json.JsonObject
            ?: return ""
        val prim = obj["type"] as? kotlinx.serialization.json.JsonPrimitive ?: return ""
        return prim.content
    }
}

/** [TimelineRecord.isJobLifecycle] 认识的事件类型；别处新增后台任务事件时改这里。 */
private val JOB_EVENT_TYPES = setOf("job/registered", "job/settled")

/** 粗分类。刻意做小：细粒度事件类型留在 payload 里，加一种不必迁移中继。 */
enum class RecordKind {
    USER_INPUT,
    STEP_THINKING,
    STEP_TOOL,
    ASSISTANT_TEXT,
    APPROVAL_REQ,
    APPROVAL_RES,
    QUESTION_REQ,
    QUESTION_RES,
    TURN_END,
    /**
     * 宿主 inbox 的一次 splice（`agent/inbox/spliced`）。
     *
     * 增量语义：`target`/`start`/`removedCount`/`inserted` 四个字段描述「在哪个桶的
     * 哪个位置换掉几条」。队列是**会话级**状态，跨轮累积，投影时不能按轮重置。
     */
    INBOX_SPLICE,
    /**
     * 任务清单的整表快照（`todo/write`）。
     *
     * 覆盖语义：每次都是完整列表，不是增量。同样是会话级状态。
     */
    TASK_LIST,
    /**
     * 后台任务的生命周期（`job/registered` / `job/settled`）。
     *
     * <p>宿主侧它们和 `step/start`、`request/header` 一起落在 `META_PATCH` 那个大桶里，
     * 但语义完全不同：那些是**纯结构噪声**，这两种是**会话级状态**（哪个任务在跑）。
     * 混在一起会出事 —— 落盘过滤与 [android.app.Application] 启动时的老数据清理都是
     * **按 kind 名**整批删 `META_PATCH` 的，不分家就会把任务记录一起清掉，胶囊随之时有时无。
     */
    JOB_EVENT,
    META_PATCH,
    /** 仅实时：生产者侧的流式增量，从不归档（落定的记录会取代它）。 */
    STREAM_DELTA,
    /** payload 超硬上限被截断；界面必须显示成可见错误行，不许静默丢弃。 */
    ERROR_OVERSIZED_TRUNCATED,
    ;

    /** 未知名字一律当 [META_PATCH]：新版插件加了新分类，旧 App 也不会崩。 */
    companion object {
        fun of(raw: String): RecordKind = entries.firstOrNull { it.name == raw } ?: META_PATCH
    }
}

// ============================================================
// 三水位（v2 §二 不变量 2）
// ============================================================

/**
 * 「中继归档里已经覆盖到哪一条」。
 *
 * 只用来判断「本地的缺口能不能由中继补上」，**绝不可**当作 `/v2/sync` 的 afterSeq。
 */
@JvmInline
value class ArchiveCoveredSeq(val value: Long)

/**
 * 「本地连续落盘到的最大 seq」。
 *
 * 这是 `/v2/sync` 的**唯一合法** afterSeq 来源（不变量 2）。用 value class 隔离是
 * 为了让「把别的水位传给 sync」变成一个编译错误，而不是一次线上数据丢失。
 */
@JvmInline
value class LocalAckedSeq(val value: Long)

/**
 * 一条会话的两个位置。
 *
 * 为什么没有第三个「宿主元数据」水位：它随列表刷新就走，推进条件与另外两个完全不同，
 * 而它此前唯一的用途（算未读）现在直接由会话列表的 `lastSeq` 现算。留一个永远不被读的
 * 水位只会诱使后来者拿它当游标——那正是「永久跳过中间一段」的成因。
 * - 归档覆盖随插件 ack 前进
 * - 本地落盘只在本事务真正提交后前进
 * 合成一个数字只会让两者互相干扰。用 value class 的目的就是让「拿错水位」
 * 变成编译错误而不是线上数据丢失。
 */
data class SessionWatermarks(
    val archiveCovered: ArchiveCoveredSeq = ArchiveCoveredSeq(0),
    val localAcked: LocalAckedSeq = LocalAckedSeq(0),
)
