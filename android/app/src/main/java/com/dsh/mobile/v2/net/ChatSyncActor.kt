package com.dsh.mobile.v2.net

import com.dsh.mobile.v2.data.Diag
import com.dsh.mobile.v2.data.LocalAckedSeq
import com.dsh.mobile.v2.data.MessageTrace
import com.dsh.mobile.v2.data.Overlay
import com.dsh.mobile.v2.data.SessionWatermarks
import com.dsh.mobile.v2.data.TimelineRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 一个会话的同步与实时流协调器 —— v2 里链路 ③（看聊天）的全部状态机。
 *
 * <h3>它负责什么</h3>
 *
 * 进会话时的正确顺序（设计 §4.2 时序图）：
 * 1. **立刻**用本地 Room 渲染（调用方负责，这里不阻塞首屏）。
 * 2. 打开 `/v2/control` 并 `FOCUS_SESSION(sinceSeq=local_acked_seq)`：插件从自己的
 *    尾部窗口增量重放，手机端**不必**等宿主。
 * 3. 并行发 `/v2/sync(afterSeq=local_acked_seq)`：中继侧的加密归档是同一条记录的
 *    另一个副本，两条通路会重叠，靠 `(sessionId, seq)` 主键天然去重。
 *
 * <h3>它刻意不负责什么</h3>
 *
 * 不渲染、不持有 `Model.Message`、不碰 UI 线程。唯一对外的东西是 [records] 与
 * [watermarks] 两个 State，UI 层用 `Projector.project()` 消费它们。
 *
 * <h3>三条不变量在这里落地的样子</h3>
 *
 * <ul>
 *   <li><b>单飞</b>（§6）：同一个 sessionId 同一时刻只有一个 `/v2/sync` 在途。飞行中
 *       的新触发只置脏标记，落地后再跑一轮——不是排队十条请求。</li>
 *   <li><b>先落盘后推进</b>（§4）：`commit()` 在一个 `@Transaction` 里先 insertAll
 *       再 saveMeta。这里的水位只在 `commit()` 成功返回之后才更新。</li>
 *   <li><b>只能增量</b>（§1）：所有 `afterSeq` 取自 `local_acked_seq`。脉搏发现半死
 *       时重放的是「本地已确认之后」那一段，绝不重放整段历史——v1 就是在这里把
 *       一次长推理重放成了一整段历史。</li>
 * </ul>
 */
/**
 * 一条会话的同步执行器：补差、看门狗、存根补齐、写操作竞速。
 *
 * <h3>并发契约（读代码前先看这条）</h3>
 *
 * <p>这个类有**两个写者线程**，这是设计而非疏忽：
 * <ul>
 *   <li><b>主线程</b>：控制流帧经 `api.post{}`（`RelayApi.post` 走 `mainHandler`）进来，
 *       落到 `feed.records/ack/pulse/state`，因此 `overlay`、`writes`、`streamingText`
 *       等都在主线程被写。</li>
 *   <li><b>IO 线程</b>：`ChatViewModel.actorScope` 是 `Dispatchers.IO`，`tick()` 在它上面
 *       跑 —— 会遍历/删除 `overlay`、`sweepAcks` 遍历 `writes`、`renderActor` 取快照。</li>
 * </ul>
 *
 * <p>所以：**跨线程的容器一律用 `ConcurrentHashMap`，跨线程的旗标一律 `@Volatile`，
 * 交给外部消费的集合一律给真快照（`toMap()`），不给活引用。** 违反其中任何一条的
 * 后果都不是「偶尔慢一点」，而是 `ConcurrentModificationException` 落在渲染协程里
 * 把整条 tick 打死（坑 13 的同类形态：一次异常 = 永久静默）。
 */
class ChatSyncActor(
    val sessionId: String,
    val hostId: String,
    private val store: ChatStore,
    private val transport: ChatTransport,
    private val priority: Int = PRIORITY_FOREGROUND,
    private val pageByteBudget: Int = PAGE_BYTE_BUDGET,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * 时间线变了（落盘一批、或来了一帧实时记录）。
     *
     * 在调用方的 IO 线程上触发，实现方自己负责切回主线程。**不**给流式增量的每一
     * 帧都回调：那会把主线程打成一秒几十次重组，而增量由 `tick()` 的节拍刷新
     * 就够了（人是看不清更快的）。
     */
    private val onChange: (() -> Unit)? = null,
    /**
     * 控制流送来的交互帧（审批 / 提问瀑布、cancel）。`kind` 是宿主 `$events` 帧的
     * `type`，`payload` 是它原样的值。
     *
     * <p>为什么由**会话级**控制流承载 app 级的事件：v1 那条常驻的 `$events` WS 已随
     * `/native/v1/…` 一起删除，而插件本来就同时握着宿主的 `$events` 与这条控制通道，
     * 由它转是最短的路径。插件在每次订阅时会把**未结算**的瀑布重投一遍，所以
     * 「进会话时卡片一定在」不依赖这条回调的实时性。
     */
    private val onInteraction: ((kind: String, payload: kotlinx.serialization.json.JsonObject?) -> Unit)? = null,
) {

    // ============================================================
    // 依赖接口（Room 与 OkHttp 只是两个实现，测试用内存实现）
    // ============================================================

    /** 底层时间线存储。实现必须保证 [commit] 是「先落盘、后推进水位」的一个事务。 */
    interface ChatStore {
        suspend fun watermark(sessionId: String): SessionWatermarks

        /**
         * 把一批记录写进 SQLite 并推进水位，同一个事务。
         *
         * 返回实际写入的新行数（主键冲突的行不算）。调用方用它的**最大 seq** 决定
         * 水位能推进到哪——服务端说覆盖到 200 而本地只写进 104 时，水位只能到 104。
         */
        suspend fun commit(sessionId: String, records: List<TimelineRecord>, watermarks: SessionWatermarks): Int

        /** 本地已落盘记录的尾部，用于合并投影。 */
        suspend fun tail(sessionId: String, limit: Int): List<TimelineRecord>

        /**
         * 读 [sinceSeq, beforeSeq) 这一整段（seq 升序），**不限条数**。
         *
         * <p>给「保留已翻出来的历史」用：落盘后要把内存窗口重新接成一段连续的区间，
         * 只有数据库能保证中间没有洞。默认实现退回空表，老夹具不必改。
         */
        suspend fun between(sessionId: String, sinceSeq: Long, beforeSeq: Long): List<TimelineRecord> = emptyList()

        /**
         * 尾部窗口再往前读一页（翻历史）。
         *
         * <p>默认实现返回空：这样 v2 之前的测试夹具不必改，而生产实现必须给真的。
         * 返回空同时意味着「没有更早的了」——与真实但已到底的情况无法区分，这是
         * 有意的：两者对 UI 的结论相同（别让人一直下拉）。
         */
        suspend fun earlier(sessionId: String, beforeSeq: Long, limit: Int): List<TimelineRecord> = emptyList()

        /**
         * 往前取 [turns] **段助手正文**，连同它们之间的全部记录（步骤、工具、收尾）。
         *
         * <p>翻页单位是正文而不是记录：一轮在库里是十几二十条记录，按记录数翻
         * 「50 条」可能只覆盖一轮，用户滑一次几乎没变化，还会把同一轮切在中间。
         * 默认实现退回「按记录数取一页」，老夹具不必改。
         *
         * @return 取到的记录（seq 升序）；空表示到底了。
         */
        suspend fun earlierByTurns(sessionId: String, beforeSeq: Long, turns: Int): List<TimelineRecord> =
            earlier(sessionId, beforeSeq, turns)

        /** 读本地预览（该会话**最后一条** ASSISTANT_TEXT）。 */
        suspend fun latestAssistant(sessionId: String): TimelineRecord?

        /** 还没取回正文的存根，按 seq 升序。 */
        suspend fun pendingStubs(sessionId: String, limit: Int): List<TimelineRecord> = emptyList()

        /**
         * 用真正的正文替换一条存根，返回受影响行数（0 = 那条已经不是存根了）。
         *
         * <p>默认实现返回 0：老夹具不关心这条通路；生产实现是 Room 的 `UPDATE`。
         */
        suspend fun fillStub(sessionId: String, seq: Long, payload: String): Int = 0

        /**
         * 删掉本地的一条记录（长按气泡「本地删除」）。
         *
         * <p>**必须只删行、不退水位**：`local_acked_seq` 是「拉到哪儿」的记账，退回去会让
         * 下一次同步把这条重新拉下来 —— 用户删了又自己冒出来，比不能删更糟。
         * 默认实现返回 false：老夹具不关心这条通路。
         */
        suspend fun deleteRecord(sessionId: String, seq: Long): Boolean = false
    }

    /** 网络。`sync` 成功时回调一页；失败回调 error 与建议的等待毫秒。 */
    interface ChatTransport {
        suspend fun sync(
            hostId: String,
            sessionId: String,
            afterSeq: LocalAckedSeq,
            priority: Int,
            pageByteBudget: Int,
        ): SyncOutcome

        /**
         * 取回一条被离载的正文（存根指向的 blob）。拿不到时返回 `null`，存根留在原地。
         *
         * <p>默认实现返回 null：老测试夹具不关心这条通路，但生产实现必须给真的。
         */
        suspend fun blob(hostId: String, blobId: String): String? = null

        fun openControl(hostId: String, sessionId: String, sinceSeq: LocalAckedSeq, feed: RelayApiV2.ControlFeed)

        fun focus(sessionId: String, sinceSeq: LocalAckedSeq): Boolean

        /**
         * 发一条写操作（prompt / 审批回答 / 排队变更 / cancel）到控制流。
         *
         * <p>幂等键 [cmdId] 由这里生成并**在整条重试链路上复用**：插件按它缓存
         * ack，`/v2/control` 可能重发、HTTPS 旁路也可能重发，同一个 id 才是
         * 「这条命令最多执行一次」的唯一保证。
         *
         * <p>返回 false 表示控制流当前不可写（还没连上）。调用方据此决定是等
         * 还是直接走旁路。
         */
        fun command(cmdId: String, op: String, payload: JsonObject): Boolean = false

        fun closeControl()

        /**
         * 控制流现在是不是还连着。
         *
         * <p>`recover()` 用它决定「重开 socket」还是「只重挂聚焦」。默认 `false`
         * 是安全的一侧：老夹具不实现它，就退回原来的「无条件重开」行为，
         * 不会因为一次读不到状态而漏掉自救。
         */
        fun controlAlive(): Boolean = false
    }

    /**
     * 重读尾部窗口，**保留用户已经翻出来的更早内容**。
     *
     * <p>以前这里是 `records = store.tail(sessionId, TAIL_LIMIT)`，一处赋值把整个
     * 内存窗口换掉。`loadEarlier()` 好不容易翻出来的前部就此蒸发 —— 而「下方有
     * 输出」每次都会触发一次落盘（`commitPage`），于是用户正在看历史时，只要对面
     * 还在打字，翻出来的内容就立刻消失。2026-11-03 用户报的就是这个。
     *
     * <p>现在的做法：把「用户已经翻到的最老一条」到「新尾部窗口起点」之间的记录
     * **从 SQLite 连续读回来**，再拼上新尾部。
     *
     * <p>**为什么不直接过滤内存里的旧 [records]**：那样会漏。中间那段完全可能有些
     * 记录落进了 Room 却从没进过内存（比如上一轮同步写进去、之后窗口被重置过），
     * 过滤内存只会保住「碰巧在内存里」的那些，时间线上凭空多出洞。数据库是权威，
     * 读它。
     *
     * <p>代价是内存窗口可能长于 [TAIL_LIMIT]。这是刻意的：用户翻过的历史本来就该
     * 留着。上限由每会话 2000 条（[TimelineDatabase.PER_SESSION_LIMIT]）兜住，
     * 实测 2000 条投影 3.27ms（JVM），不会失控；退出会话时随 actor 一起释放。
     */
    private suspend fun refreshTail() {
        val fresh = store.tail(sessionId, TAIL_LIMIT)
        if (fresh.isEmpty()) return
        val oldest = fresh.first().seq
        val windowStart = records.firstOrNull()?.seq
        if (windowStart == null || windowStart >= oldest) {
            // 没翻过历史，或者新尾部已经完全覆盖了原窗口 ⇒ 直接用新的。
            records = fresh
            return
        }
        // 从数据库把 [windowStart, oldest) 这一段连续读回来，保证没有洞。
        val older = store.between(sessionId, windowStart, oldest)
        records = (older + fresh).sortedBy { it.seq }
    }

    /** 一页同步的结果：成功页 / 失败文案 / 建议等待。三选一，绝不「成功但空」。 */
    sealed interface SyncOutcome {
        data class Page(val page: RelayApiV2.SyncPage) : SyncOutcome
        data class Failed(val message: String, val retryAfterMs: Long) : SyncOutcome
    }

    // ============================================================
    // 对外状态
    // ============================================================

    /** 本地已落盘的时间线（不含 overlay）。UI 读它跑投影器。 */
    var records: List<TimelineRecord> = emptyList()
        private set

    /** 三水位快照。`localAcked` 是唯一允许喂给 `/v2/sync` 的 afterSeq。 */
    var watermarks: SessionWatermarks = SessionWatermarks()
        private set

    /**
     * 内存态 overlay：实时推来但还没被归档确认的记录，按 seq 索引。
     *
     * 为什么不直接写 Room：实时记录到达时前面的 seq 可能还缺（断流、慢归档）。
     * 直接落盘会让「本地最大 seq」跳到一个空洞之后，水位一旦越过空洞，那几条
     * 中间记录**永远不会**再被拉回来（§4.3）。
     *
     * <p>**并发**：写入发生在主线程（`api.post{}` → `mainHandler.post` → `feed.records`），
     * 读取与删除发生在 `Dispatchers.IO`（`tick()` 的 `dropOverlayUpTo`、`renderActor`
     * 取快照）。流式与补差本来就是常态并行，`LinkedHashMap` 在这条路径上会抛
     * `ConcurrentModificationException`（渲染线程无兜底 → 崩溃）或结构损坏丢条目。
     * `ConcurrentHashMap` 的迭代器是弱一致的：并发修改下既不抛也不失效，正是这里要的。
     *
     * <p>顺序不重要：消费方（`Projector`）会按 `seq` 重新排序（`sortedBy { it.seq }`），
     * 所以换成无序 Map 不改变投影结果。
     */
    val overlay: MutableMap<Long, TimelineRecord> = ConcurrentHashMap()

    /** 投影器要的 overlay 快照。每次投影都现取，绝不 `remember` 住它——
     *  那是「流式内容冻住不动」的老坑（AGENTS.md §28）。
     *
     *  <p>这里返回的是**真快照**（`toMap()`）。交给投影器一个活引用等于把并发窗口
     *  一直开到渲染结束：投影期间主线程还在往里写，`Projector` 遍历时就会撞上
     *  半成品状态。拷贝的代价是每次渲染一次 O(overlay 条数)，而 overlay 只装
     *  「还没被归档确认的尾巴」，通常是几条。 */
    fun overlay(): Overlay = Overlay(
        uncommitted = HashMap(overlay),
        streamingText = streamingText,
        streamingTurnId = streamingTurnId,
        liveReasoning = liveReasoning,
        reasoningTurnId = reasoningTurnId,
    )

    /** 流式正文（`STREAM_DELTA`），只活在这一层：它是不落盘的临时文本。 */
    @Volatile
    var streamingText: String = ""
        private set
    @Volatile
    var streamingTurnId: String = ""
        private set

    /** 流式思考（`assistant-reasoning`），与正文分开存：它进折叠行尾部预览，
     *  绝不进正文气泡（2026-10-30 反馈「思考被当正文输出」）。 */
    @Volatile
    var liveReasoning: String = ""
        private set
    @Volatile
    var reasoningTurnId: String = ""
        private set

    /** 宿主此刻是否还在跑这一轮（来自脉搏的 `hostState`）。
     *
     *  <p>为什么必须暴露：折叠行要不要贴「活思考」由
     *  `foldLive(message, sessionRunning)` 决定，而 `sessionRunning` 一路来自
     *  `ChatState.running`。v2 路径下没有 `api-session/status` 那条 SSE，
     *  `ChatState.running` 只有 `onSessionStatus` 一个写入口 —— 没人调它，
     *  于是它永远是 false，`live` 永远 false，思考预览一辈子不显示
     *  （2026-10-30 实测：投影层 `withThinking=7`、`liveReasoning=609`，
     *  界面上折叠行仍然只有「已处理 3 个操作」）。
     */
    @Volatile
    var hostRunning: Boolean = false
        private set

    /** 最近一次脉搏时刻；0 = 本次聚焦内还没收到过。 */
    @Volatile
    var lastPulseAt: Long = 0
        private set

    /**
     * 诊断标识：会话尾 8 位 + 实例哈希。
     *
     * <p>2026-11-04 在 self-hosted 裸 IP 链路上看到的怪现象是「脉搏每 5 秒一条，看门狗却每 12 秒
     * 判一次死、还一路涨到 recover #2」。日志里所有会话 id 都以 `session-` 开头，
     * `take(8)` 打印出来逐字相同，根本分不清「这条脉搏是哪个会话的」「这条 recover 是
     * 哪个实例的」。会话 id 取尾 8 位才有区分度，再加上实例哈希，两个 actor 抢同一条
     * 控制流时一眼就能看出来。
     */
    private val actorTag = "${sessionId.takeLast(8)}@${System.identityHashCode(this)}"

    /** 判死那一刻「距上一条脉搏多久」；-1 = 本次聚焦内从没收到过。 */
    private var pulseAgeBeforeRecover: Long = -1

    /**
     * 最近一次从控制流**收到任何业务帧**的时刻（records 或脉搏）。
     *
     * <p>存在的理由是 2026-10-30 生产日志里那条自我维持的循环：`focus … replay=200`
     * （约 500 KiB / 32 帧）之后紧跟 `tunnel credit stall 10s pending=12KiB
     * buffered=0KiB`。插件的 `MessageBridge` 用 `tail.then()` 把 records 与脉搏严格
     * 串行在同一条 channel、同一份流控信用上，32 帧重放足以把脉搏压在队尾十几秒。
     *
     * <p>原来判活只认 [lastPulseAt]，于是「正在被 records 灌满」这条最健康的连接
     * 反而被判死：手机重连 → 插件重放 → 再堵一次。records 帧带 seq 且逐条校验，
     * 伪造不了活性，它和脉搏一样是「这条流还活着」的铁证。
     */
    @Volatile
    var lastDataAt: Long = 0
        private set

    /**
     * 看门狗开机时刻（= 控制流刚连上那一刻）。
     *
     * <p>存在的理由是实测事故：控制流的 socket 建起来了（`ws open`），可一条脉搏
     * 都没到达（插件侧的 `focus` 没生效 / 半路丢帧 / 手机端解帧失败都会这样）。
     * 原来判定只认 [lastPulseAt]，而 `lastPulseAt == 0` 被当成「还没开始计时」，
     * 于是**没有任何东西会判死**：界面永远停在最后一帧，插件和中继却都健康。
     * 看门狗必须有一个不依赖对端行为的起点。
     */
    @Volatile
    private var armedAt: Long = 0
        private set

    /** 本次聚焦内已经自救过几次（仅用于诊断与限频，不参与判死）。 */
    @Volatile
    var recoveries: Int = 0
        private set

    /** 连续「成功但一页都没往前走」的轮数（测试可见）。 */
    internal fun zeroProgressRounds(): Int = failures.stallRounds(sessionId)

    /** 最近一次脉搏内容，UI 用它显示「正在深度思考 (Ns)」。 */
    var lastPulse: RelayApiV2.SessionPulse? = null
        private set

    /** 已判过半死：防止每个 tick 都重连，那会把自己打成掉线循环。 */
    @Volatile
    private var halfDeadDeclared = false

    @Volatile
    private var inFlight = false
    @Volatile
    private var dirty = false
    private val failures = SyncCoordinator.FailureTracker(clock)

    /** 供 UI 显示同步状态（空串 = 正常）。 */
    @Volatile
    var note: String = ""
        private set

    /**
     * 本地还有没有更早的记录没读出来。
     *
     * <p>判据是「翻到的最老一条 seq 是否大于 1」：本地容量与中继 1:1（每会话
     * 2000 条），所以 SQLite 里有更早的就是能翻的。这里**不**问中继——中继那边
     * 已经滚出窗口的记录本来就取不回来，问了只会多一次往返。
     */
    @Volatile
    var hasEarlier: Boolean = false
        private set

    /**
     * 中继不认 `/native/v2/sync`（老版本，或自建的另一份中继）。
     *
     * 一旦为 true 就该退回旧链路，而不是每隔两秒重试一次注定 404 的请求——
     * 那会让界面一直显示「同步中」，用户以为在补历史，其实什么都没发生。
     */
    @Volatile
    var unsupported: Boolean = false
        private set

    // ============================================================
    // 生命周期
    // ============================================================

    /**
     * 进会话：读本地 → 开控制流 → 增量补差。
     *
     * 顺序不能反：先开控制流能立刻拿到插件回放的尾部（用户马上看到内容），
     * 再补差把中间那段空洞填上。反过来的话用户会先看到「空的历史 + 新的正文」。
     */
    suspend fun start() {
        watermarks = store.watermark(sessionId)
        records = store.tail(sessionId, TAIL_LIMIT)
        // 探一下「尾部窗口下面还有没有」：本地记录的最老 seq 未必是 1（中继侧
        // 2000 条滚动窗口可能已经把更早的滚掉了），所以不能拿 seq>1 当判据。
        hasEarlier = records.isNotEmpty() &&
            store.earlier(sessionId, records.first().seq, 1).isNotEmpty()
        armedAt = clock()
        // 每个 actor 抢占控制流都要留痕：两个 actor 抢同一条 socket 时，
        // 「谁在什么时刻把别人的连接关了」只有这张表说得清（见 actorTag 的说明）。
        Diag.log("control", "actor start tag=$actorTag from=${watermarks.localAcked.value}")
        transport.openControl(hostId, sessionId, LocalAckedSeq(watermarks.localAcked.value), feed)
        // 首轮补差：前台单会话，独占预算。
        syncUntilCaughtUp()
    }

    fun stop() {
        Diag.log("control", "actor stop tag=$actorTag")
        transport.closeControl()
    }

    /**
     * 本地删掉一条记录，然后重读尾部并通知 UI 重投影。
     *
     * <p>为什么不直接改内存里的 `records`：投影器吃的是**归档原文**（`TimelineRecord`），
     * 而内存外那条已经落进 Room 了。删库之后再按老办法 `store.tail()` 重读一次，
     * 内存与磁盘就是同一份 —— 手工从列表里摘掉那一条的话，下次任何重读都会把它捡回来。
     *
     * <p>**只影响这台手机。** 中继那边照旧保留（14 天 TTL），换设备或重装会再看到它；
     * 这是「本地删除」这个词的边界，不是 bug。
     *
     * @return 真的删掉了才 true（那条不在了 —— 比如已被压缩掉 —— 就是 false）。
     */
    suspend fun deleteLocal(seq: Long): Boolean {
        val removed = try {
            store.deleteRecord(sessionId, seq)
        } catch (e: Exception) {
            false
        }
        if (!removed) return false
        // 先从内存窗口里摘掉这一条，再重读尾部 —— 否则用户翻出来的那一段里
        // 还留着它（`refreshTail()` 会保留更早的部分）。
        records = records.filterNot { it.seq == seq }
        refreshTail()
        onChange?.invoke()
        return true
    }

    /**
     * 离开会话时调用：让插件停掉这路的订阅，省它的 CPU 与流量。
     *
     * <p>**当前没有任何调用点**，而 `closeControl()` 本身就够用了：插件侧
     * `ControlSession.close()` 会 `focused.clear()` + 逐个退订，隧道一断订阅自然
     * 就没了。所以这里原来那句 `transport.focus(...)` 不但多余，还恰好是反的——
     * 在离开会话时重新聚焦一次。留着这个方法是因为将来若要做「一条隧道盯多个
     * 会话」，退订就得显式发 `UNFOCUS_SESSION`（插件已经支持，见
     * `control.ts` 的 `UNFOCUS_SESSION` 分支）。
     */
    fun unfocus() {
        transport.closeControl()
    }

    /**
     * 往前翻一页历史（用户上拉）。
     *
     * <p>只读本地 SQLite，不发请求：v2 的容量口径是「本地与中继 1:1，每会话 2000
     * 条」，翻历史就是换个窗口读同一张表。走网络不但慢，还会把「翻历史」变成
     * 一条悄悄消耗流量与额度的通路。
     *
     * <p>返回值是**新增到 [records] 里的条数**：0 表示到底了，UI 据此停掉下拉。
     */
    suspend fun loadEarlier(pageSize: Int = EARLIER_PAGE): Int {
        val oldest = records.firstOrNull()?.seq ?: return 0
        // 单位是**助手正文**，不是记录。一轮在库里十几二十条记录，按记录数翻
        // 「50 条」常常只覆盖一轮，用户滑一次几乎没变化，还会把同一轮切在中间。
        val page = store.earlierByTurns(sessionId, oldest, pageSize)
        if (page.isEmpty()) {
            // 问不到了就是真到底了，别再让 UI 显示「还能翻」。
            hasEarlier = false
            return 0
        }
        // 同 seq 以已在内存的那份为准：翻页不该把刚被补过正文的存根顶回半截。
        val known = records.mapTo(HashSet()) { it.seq }
        val fresh = page.filter { it.seq !in known }
        records = (fresh + records).sortedBy { it.seq }
        // 还能不能继续翻：直接问「再往前一段正文有没有」。
        //
        // 不能用 `page.size >= pageSize` 判 —— 那是按记录数的口径，而一页正文
        // 可能对应几十条记录（也可能只有几条），拿条数比一定会误判。
        hasEarlier = store.earlierByTurns(sessionId, records.first().seq, 1).isNotEmpty()
        if (fresh.isNotEmpty()) onChange?.invoke()
        return fresh.size
    }

    // ============================================================
    // 同步主循环
    // ============================================================

    /**
     * 一直拉到底（或在预算耗尽/零进展时停下）。
     *
     * 「一直」是有限度的：`continueAfter` 要求 `hasMore` 且游标真的动了。零进展
     * 还继续拉就是 tight loop——v1 的「补页 26/29 次空响应」正是这么烧出来的。
     */
    /**
     * 从「连接看着还在、却一个字节都不来」里恢复。
     *
     * <p>三件事都要做，缺一条就救不回来：
     * 1. **重开控制流** —— socket 层活着不代表隧道那一段活着；`focus` 发进一条
     *    半死的连接等于没发。只重挂订阅不重开 socket，正是这次事故里反复失败的
     *    那个「恢复」。
     * 2. **重挂聚焦** —— 插件侧的 `focused` 集合跟着旧连接一起没了。
     * 3. **增量补差** —— 归档那份比回放更权威，也覆盖断流期间漏掉的 `TURN_END`。
     *
     * <p>一律从 `local_acked` 起步，**绝不 since=0**：全量重放会在弱网上把同一次
     * 故障放大成几十页下载。
     */
    private suspend fun recover(now: Long) {
        halfDeadDeclared = true
        recoveries++
        pulseAgeBeforeRecover = if (lastPulseAt > 0) now - lastPulseAt else -1
        lastPulseAt = 0
        lastDataAt = 0
        armedAt = now
        val after = LocalAckedSeq(watermarks.localAcked.value)
        // 判死那一刻的两条时钟必须落盘：否则「脉搏明明在到，为什么还判死」只能在源码里猜。
        Diag.log(
            "control",
            "recover #$recoveries from=${after.value} tag=$actorTag armed=$armedAt " +
                "pulseAge=$pulseAgeBeforeRecover",
        )
        // 1 + 2：重开 socket，再在新连接上聚焦。
        //
        // 但**只在控制流确实已经断了的时候**才关。坑 54 的现场是「socket 活得好
        // 好的、records 照常到达、只是没有脉搏」——那种情况下 `closeControl()`
        // 会把一条能用的连接掐掉，插件立刻重新 focus 并重放整个尾部窗口
        // （实测 200 条约 500 KiB / 30 多帧），紧接着撞上隧道流控。每 12 秒一次的
        // 自我重连就是这样把自己打成流量风暴的。
        //
        // 判据用 `controlAlive()`：连接还在就只补一次 focus（幂等，插件侧会
        // 重新订阅并回放增量），不碰 socket。
        val alive = runCatching { transport.controlAlive() }.getOrDefault(false)
        // 「连接仍在」这一支必须**验一下 focus 真的发出去了**。
        //
        // 2026-11-01 真机实测（0.2.34）：聊天页卡住不再出新内容，返回列表再进会话
        // 立刻恢复。抓到的现场是手机侧**没有任何一条到中继的 TCP 连接**，而
        // `syncV2.requests` 停在 10、界面 30 秒不动 —— 也就是说 `recover()` 一直在走
        // 这条 `alive` 分支、一次都没重开 socket。
        //
        // 为什么会误判：`controlAlive()` 只看 `open && socket != null`，而这两个状态
        // 只在 `onFailure`/`onClosed` 里翻转。OkHttp 的底层 socket 被 NAT 静默丢弃时
        // 回调不一定及时到，`open` 就一直是 true；再加上 `WebSocket.send()` 在死连接上
        // **不抛异常**（排进队列后丢掉），`focus()` 照样返回 true —— 于是「重挂聚焦」
        // 每 12 秒"成功"一次，实际一个字节都没出去，永远修不好。
        //
        // 判据改成「focus 真的返回成功」：`focus()` 在 `socket == null || !open` 时
        // 返回 false，那时就该老老实实重开。返回值早先被丢弃了，是这条死循环的最后一环。
        // 第二道闸：`focus()` 的返回值**不够**。
        //
        // OkHttp 的 `WebSocket.send()` 只把帧排进发送队列，连接已死也不抛——所以
        // 「自称在线 + send 不抛」可以同时为真，返回值照样是 true，于是永远走
        // 「只重挂聚焦」，一次都不重开。连着自己救了几轮、脉搏还是没回来，就说明
        // 「在线」这个判断根本不可信：这时候必须**强制**重开，不能再信它一次。
        //
        // 一次就够：`recover()` 只有在脉搏回来时才把 `recoveries` 归零（见 `onPulse`），
        // 所以 `recoveries >= 2` 的含义正是「上一轮自救之后，脉搏一个都没到」。
        val suspectedZombie = alive && recoveries >= 2
        val focused = !suspectedZombie && alive &&
            runCatching { transport.focus(sessionId, after) }.getOrDefault(false)
        if (focused) {
            Diag.log("control", "recover #$recoveries 连接仍在，聚焦已重挂")
        } else {
            if (suspectedZombie) {
                Diag.log("control", "recover #$recoveries 连续自救无脉搏，判定连接为僵尸，强制重开")
            }
            if (alive) {
                // 「自称活着但聚焦发不出去」＝死连接。必须记下来，否则下次现场
                // 还是只能靠抓包才知道。
                Diag.log("control", "recover #$recoveries 自称在线但聚焦失败，重开 socket")
            }
            runCatching { transport.closeControl() }
            transport.openControl(hostId, sessionId, after, feed)
            transport.focus(sessionId, after)
        }
        // 3：补数据。dirty 置真保证即使本轮补差被冷却挡住，下一 tick 还会再来。
        dirty = true
        syncUntilCaughtUp()
        note = ""
    }

    suspend fun syncUntilCaughtUp() {
        val slot = SyncCoordinator.decideRun(
            inFlight = inFlight,
            pending = dirty,
            cooldownUntil = failures.cooldownUntil(sessionId),
            now = clock(),
        )
        if (!slot.run) {
            // 在途时把触发折叠成脏标记；冷却期内则直接放弃这一轮。
            dirty = dirty || slot.dirty
            return
        }
        inFlight = true
        dirty = false
        try {
            var rounds = 0
            while (rounds++ < MAX_ROUNDS) {
                val after = LocalAckedSeq(watermarks.localAcked.value)
                val outcome = transport.sync(hostId, sessionId, after, priority, pageByteBudget)
                when (outcome) {
                    is SyncOutcome.Failed -> {
                        // 老中继不认 /native/v2/sync：记下来，让上层退回旧链路。
                        // 继续重试只会把「同步中」这个状态永远挂在界面上。
                        if (outcome.message == "sync-unsupported") {
                            unsupported = true
                            note = ""
                            return
                        }
                        failures.noteFailure(sessionId)
                        note = outcome.message
                        return
                    }
                    is SyncOutcome.Page -> {
                        val page = outcome.page
                        if (page.skipped > 0) {
                            // 有行解析不了：说明别的端版本不匹配。说出来，别装作同步成功。
                            note = "有 ${page.skipped} 条记录格式不兼容，已跳过"
                        }
                        failures.noteSuccess(sessionId)
                        if (page.records.isEmpty() && page.nextCursorSeq <= after.value) {
                            // 空页且游标不动：中继手里没有更新的东西，收工。
                            note = ""
                            break
                        }
                        val landed = commitPage(page)
                        // 「成功但没往前走」与「请求失败」是两件事，分别记账：
                        // 前者连续几次就退避，否则脉搏每 5 秒把 dirty 置真、每 tick 发一次
                        // 注定无进展的请求 = 客户端 hot loop（中继的 SyncLoopGuard 是护栏，
                        // 不该指望对端替自己刹车）。
                        if (page.nextCursorSeq <= after.value) {
                            failures.noteStall(sessionId)
                        } else {
                            failures.noteProgress(sessionId)
                        }
                        if (!SyncCoordinator.continueAfter(page, after.value)) break
                        if (landed == 0L && page.nextCursorSeq <= after.value) break
                    }
                }
            }
        } finally {
            inFlight = false
            // 飞行期间又被触发过：落地后补跑一轮，不该丢掉那次触发。
            if (dirty) {
                dirty = false
                syncUntilCaughtUp()
            }
        }
    }

    /**
     * 落盘一页并推进水位。
     *
     * 返回值是实际写进 SQLite 的最大 seq（**不是**中继说的 nextCursor）。这两个值
     * 分开正是「先落盘后推进」的落点：矛盾时以本地为准。
     */
    private suspend fun commitPage(page: RelayApiV2.SyncPage): Long {
        val before = watermarks.localAcked.value
        val next = SyncCoordinator.advance(
            requestAfter = before,
            nextCursor = page.nextCursorSeq,
            landed = page.records.maxOfOrNull { it.seq } ?: 0,
        )
        val updated = watermarks.copy(
            localAcked = next,
            archiveCovered = com.dsh.mobile.v2.data.ArchiveCoveredSeq(
                maxOf(watermarks.archiveCovered.value, page.archiveCoveredSeq)
            ),
        )
        store.commit(sessionId, page.records, updated)
        watermarks = updated
        // 水位越过的那部分 overlay 已经不新鲜了（Room 里有了权威副本）。
        dropOverlayUpTo(next.value)
        // 只重读尾部：整表重读在大会话上会让 UI 卡顿，而用户看的就在尾部。
        // 但**必须保住已翻出来的更早部分**，否则下方一出输出就把历史冲掉。
        refreshTail()
        if (page.records.isNotEmpty()) onChange?.invoke()
        return page.records.maxOfOrNull { it.seq } ?: 0
    }

    // ============================================================
    // 实时流回调
    // ============================================================

    private val feed = object : RelayApiV2.ControlFeed {
        override fun interaction(kind: String, payload: kotlinx.serialization.json.JsonObject?) {
            onInteraction?.invoke(kind, payload)
        }

        override fun records(frame: RelayApiV2.RecordsFrame) {
            if (frame.sessionId.isNotEmpty() && frame.sessionId != sessionId) {
                // 插件按会话推，但别的会话的记录绝不能混进当前页面（v1 的审批串会话
                // 就是这么来的）。丢掉，并留一条诊断。
                return
            }
            var newest = 0L
            for (record in frame.records) {
                // 双重过滤：帧级（插件按会话推）之外再逐条挡一次。一条混进来的
                // 外会话记录会把别的会话的正文画进当前页面——v1 的审批串会话
                // 就是这么发生的，代价是「点了允许等于给别的会话放权」。
                if (record.sessionId.isNotEmpty() && record.sessionId != sessionId) continue
                when (record.kind) {
                    com.dsh.mobile.v2.data.RecordKind.STREAM_DELTA -> applyDelta(record)
                    // 确定性记录先进 overlay：前面的 seq 可能还缺，直接落盘会留下
                    // 一个水位跨不过去的空洞（§4.3）。
                    else -> {
                        overlay[record.seq] = record
                        newest = maxOf(newest, record.seq)
                    }
                }
            }
            // 收到任何一条就证明这条控制流在干活：records 与脉搏共用同一条串行
            // 队列，重放 500 KiB 时脉搏会被压在队尾十几秒，只认脉搏会把最健康的
            // 那条连接判死（见 [lastDataAt]）。
            if (frame.records.isNotEmpty()) lastDataAt = clock()
            if (frame.hasMore) {
                // 插件还有更多可回放：靠 /v2/sync 拿归档那份，比再要一轮回放便宜。
                dirty = true
            }
            if (newest > 0) {
                note = ""
                // T5：这句话的到达点。按「本会话第一条还没打 T5 的待追踪消息」匹配
                // ——宿主事件里没有 requestId 的回声（见 MessageTrace 的说明）。
                if (frame.records.any { it.kind == com.dsh.mobile.v2.data.RecordKind.USER_INPUT }) {
                    MessageTrace.noteUserMessage(sessionId)
                }
                onChange?.invoke()
            }
        }

        override fun pulse(pulse: RelayApiV2.SessionPulse) {
            if (pulse.sessionId.isNotEmpty() && pulse.sessionId != sessionId) return
            // 每一次脉搏都记一行：这次事故里「脉搏到底有没有到」只能靠两端日志反推，
            // 手机侧一个字都没有，白白多查了半天。
            Diag.log(
                "control",
                "pulse tag=$actorTag id=${pulse.sessionId.takeLast(8)} latest=${pulse.latestSeq} " +
                    "covered=${pulse.archiveCoveredSeq} state=${pulse.hostState} " +
                    "silent=${pulse.silentDurationMs}ms epoch=${pulse.workerEpoch}",
            )
            lastPulseAt = clock()
            lastPulse = pulse
            // 脉搏本身就是「有东西到达」的一个特例，两条时钟一起刷新。
            lastDataAt = lastPulseAt
            // 真的有脉搏回来了才算健康：`recover()` 里的重置会让「重连也没用」
            // 变成每 12 秒一次的静默死循环。
            halfDeadDeclared = false
            recoveries = 0
            val act = SyncCoordinator.onPulse(
                localAcked = watermarks.localAcked.value,
                pulseLatestSeq = pulse.latestSeq,
                pulseCovered = pulse.archiveCoveredSeq,
                halfDeadAlready = false,
            )
            if (act.replay) dirty = true
            // 宿主在推理但没字节：这是正常的长推理，界面要显示进度而不是装死。
            if (pulse.thinkingSilent) note = ""
        }

        override fun ack(ack: RelayApiV2.CommandAck) {
            // 写操作是「命令-确认」语义，不是状态量：解到就立刻收口，不留缓存，
            // 否则同一个 cmdId 的第二次到达会让回调执行两遍。
            //
            // 这里**不**保留 ack 副本。早先有个 `pendingAcks` 映射，只在 `ack()` 里
            // 写入、从不清除，唯一的读者 `ackRaceExpired()` 全仓没有调用点 ——
            // 于是它是一条只增不减的泄漏：每发一条写操作就永久多一个条目，
            // 进程活得越久占得越多。
            val pending = writes.remove(ack.cmdId) ?: return
            // 成功时插件给的是空串，而回调的契约是「错才有错」——让上层拿到的
            // 要么是 null 要么是一句能显示的话，不必到处判空串。
            runCatching { pending.onResult(ack.ok, ack.error.ifEmpty { null }) }
        }

        override fun error(code: String, message: String) {
            note = "$code: $message"
        }

        override fun state(note: String, connected: Boolean) {
            if (connected) return
            this@ChatSyncActor.note = note
            // 断流就把「在跑」的依据一起收回（2026-11-05 现场：主机昨天切走，手机这一侧
            // 还在按最后一条脉搏的 STREAMING 转圈，界面永远是「运行中」、发送钮永远是「停止」）。
            // 脉搏是唯一的活证；流没了它就不再代表任何事。
            lastPulse = null
            lastPulseAt = 0
            hostRunning = false
        }
    }

    /** 在途的写操作，等 ack 回来时按 cmdId 找它的回调。 */
    private class Pending(
        val cmdId: String,
        val op: String,
        val payload: JsonObject,
        val sentAt: Long,
        /** 这条命令的 ack 竞速窗口。prompt 用长窗（见 [PROMPT_ACK_RACE_MS]），其余默认 [ACK_RACE_MS]。 */
        val raceMs: Long,
        val onResult: (ok: Boolean, error: String?) -> Unit,
    )

    /**
     * 还没收到 ack 的写操作。
     *
     * <p>同样是跨线程的：`feed.ack()` 在主线程 `remove`，`sweepAcks()` 在 `tick()` 的
     * IO 线程上遍历 + `remove`。用 `LinkedHashMap` 时这两条路径会互相踩
     * （`ConcurrentModificationException` 落在渲染/同步协程里，整条 tick 就此终结）。
     * 迭代器弱一致即可——`sweepAcks` 只关心「到点的那些」，漏掉一条下一轮还会看到。
     */
    private val writes: MutableMap<String, Pending> = ConcurrentHashMap()

    /**
     * 发一条写操作（prompt / 审批回答 / 排队变更 / cancel）。
     *
     * <p>为什么写操作走控制流而不是继续用 v1 的 `/native/v2/.../rpc`：v1 那条路是
     * 中继直接打到宿主的，插件根本不知道这条命令存在，于是「同一条命令只执行一次」
     * 没有任何地方保证。走 `CMD` 之后插件按 [cmdId] 缓存 ack，重发只是重放同一个
     * 结果。手机侧的超时（[sweepAcks]）**只把结果收口成 `ack-timeout`，不重发**：
     * 「不确定」与「肯定失败」必须分开，重发与否由调用方按 op 决定（prompt 有宿主
     * `requestId` 幂等兜底，queue/审批回答幂等无间隙）。
     *
     * <p>控制流当前不可写（还没连上）时 [onResult] 立刻以 `not-connected` 失败返回，
     * 让调用方决定是退回老接口还是提示用户——这里绝不悄悄吞掉。
     *
     * @param raceMs 这条命令的 ack 竞速窗口。**prompt 必须传长窗**
     * [PROMPT_ACK_RACE_MS]：插件打宿主走 loopback（20 秒超时），3 秒就把 prompt
     * 收口成 `ack-timeout` 会让调用方回落 v1 重发——而宿主的 requestId 幂等
     * （hasPromptRequest）在「查重」与「写入 inbox」之间有 await 间隙，回落的那份
     * 恰好落进间隙里就会**真正执行两次**，用户看到「发一次，排队 2 条」
     * （2026-10-31 定案）。长窗让 prompt 等到插件最终结果再收口，绕开间隙。
     */
    fun command(
        op: String,
        payload: JsonObject,
        raceMs: Long = ACK_RACE_MS,
        onResult: (ok: Boolean, error: String?) -> Unit,
    ) {
        val cmdId = UUID.randomUUID().toString()
        val entry = Pending(cmdId, op, payload, clock(), raceMs, onResult)
        // 先登记再发送：ack 可能在 command() 返回之前就通过同一个事件循环到达，
        // 那时若还没登记就会被当成没有归属的帧丢掉，调用方永远等不到结果。
        writes[cmdId] = entry
        val sent = runCatching { transport.command(cmdId, op, payload) }.getOrDefault(false)
        if (!sent) {
            writes.remove(cmdId)
            onResult(false, "not-connected")
        }
    }

    /** 有没有一条写操作还没收到 ack（用于判断「现在还能不能发」）。 */
    fun awaitingAck(): Boolean = writes.isNotEmpty()

    /** 超时未确认的写操作数——超过 0 说明控制流的 ack 回路断了。 */
    fun overdueAcks(now: Long = clock()): Int = writes.values.count { now - it.sentAt >= it.raceMs }

    /**
     * 把超时仍没等到 ack 的写操作收口成失败。
     *
     * <p>**这条通路原来不存在，于是「命令丢了」看起来像「命令在跑」**：`command()`
     * 把条目放进 [writes]，只有 `feed.ack()` 会取走它；插件不回 ack（它的
     * `command()` 抛错、或者帧根本没到插件）时 `onResult` 永不执行，调用方的
     * `sending` 永远是 true，界面一直显示「停止」，而宿主一个字节都没收到——
     * 2026-10-30 的事故正是这样被用户发现的（本地看到会话里根本没有这条消息）。
     *
     * <p>语义是「不确定」而不是「肯定失败」：ack 可能只是慢或者丢在路上。所以
     * 错误串固定为 `ack-timeout`，让调用方据此决定重发或等待，而不是当成一条
     * 确定的业务错误去提示用户。每条命令有**自己的**竞速窗（[Pending.raceMs]）：
     * queue / 审批回答仍是 3 秒（宿主侧 updateQueue / $events-result 幂等无间隙，
     * 回落安全）；prompt 是 25 秒（见 [PROMPT_ACK_RACE_MS]——它的宿主幂等有
     * 「查重-写入」间隙，3 秒回落即双发）。
     */
    fun sweepAcks(now: Long = clock()): Int {
        if (writes.isEmpty()) return 0
        val expired = writes.values.filter { now - it.sentAt >= it.raceMs }
        if (expired.isEmpty()) return 0
        for (entry in expired) {
            writes.remove(entry.cmdId)
            Diag.log("control", "ack timeout op=${entry.op} cmd=${entry.cmdId.take(8)} after=${now - entry.sentAt}ms")
            runCatching { entry.onResult(false, "ack-timeout") }
        }
        return expired.size
    }

    /** 上一次补存根的时刻（毫秒）。见 [fillStubs] 的限速说明。 */
    private var lastBlobAt = 0L

    /** 相邻两次补存根的最小间隔。
     *
     *  3s → 1s：一轮对话常有十几条存根，3 秒一批要等将近一分钟才看得全。
     *  1 秒一批是**有意的上限**，不是保守：单条存根动辄 256 KiB 以上，再快就会在
     *  蜂窝网上把用户正在读的那屏挤掉（见 [fillStubs] 的限速说明）。 */
    private var blobGapMs = 1_000L

    /** 一次 tick 最多补几条存根。
     *
     *  2 → 3，不是 10：`fillStubs` 是**串行 await**，一批十条 = 一次 tick 里连打
     *  十个 blob 请求，峰值能到几 MB，而且期间 tick 被占住，脉搏判死与落盘都跟着延后。 */
    private var blobBatch = 3



    private fun applyDelta(record: TimelineRecord) {
        // 流式增量是「当前这一步」的累计文本，不是可追加的碎片：直接替换。
        // 一帧丢了也能自愈，下一帧就是完整文本。
        val kind = record.deltaKind()
        if (kind == "assistant-reasoning") {
            // 思考只进折叠行的尾部预览，不进正文。两条通道共用一个缓冲
            // 就是「思考过程被当正文铺在聊天页」的成因（2026-10-30）。
            Diag.log("control", "reasoning delta ${record.deltaText().length} chars turn='${record.turnId}'")
            liveReasoning = record.deltaText()
            reasoningTurnId = record.turnId
            return
        }
        // 正文增量也要打点：它和思考增量是两条独立路径，只记思考的话
        // 「打字机不动」时分不清是没收到、还是收到了没渲染
        // （2026-11-01 模拟器排查正卡在这一步：思考 98 条、正文一条都看不到）。
        Diag.log("control", "answer ${record.deltaText().length} chars turn='${record.turnId}'")
        streamingText = record.deltaText()
        streamingTurnId = record.turnId
        // 刻意**不进** overlay：overlay 是按 seq 索引的落盘候选，而增量永远不是
        // 时间线的一行。放进去会被投影器当成一条正文画出来，落盘之后又凭空消失，
        // 用户看到的就是「回复闪了一下又没了」。
    }

    private fun dropOverlayUpTo(seq: Long) {
        if (overlay.isEmpty()) return
        val iterator = overlay.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().key <= seq) iterator.remove()
        }
    }

    // ============================================================
    // 半死自愈（由外部 ticker 每 1~5 秒调用）
    // ============================================================

    /**
     * 看门狗一次 tick。
     *
     * 判据见 [SyncCoordinator.halfDead]：12 秒无脉搏且「在跑或有欠账」才算半死。
     * 恢复动作是**增量**的：先补一次 `/v2/sync(afterSeq=local_acked)`，再在新连接上
     * 重发 `FOCUS_SESSION`。绝不重放整段历史。
     */
    /**
     * 长推理时给界面的一句话：「正在深度思考 (45s)…」。
     *
     * <p>为什么需要它：V2 把「连接活着」与「有数据在动」分开之后，一段纯推理
     * 六十秒里一个记录都不会到。如果界面据此保持静态，用户看到的是卡死——v1 的
     * 十六分钟静默就是这么来的。脉搏的 `silentDurationMs` 是宿主自己报的静默时长，
     * 用它显示秒数是最诚实的做法：既没有编造进度，又证明流是活的。
     *
     * <p>只在 `THINKING_SILENT` 时返回非空：`STREAMING` 有字节在动，界面自己会更新。
     */
    fun thinkingLabel(now: Long = clock()): String {
        val pulse = lastPulse ?: return ""
        if (pulse.hostState != "THINKING_SILENT") return ""
        // 以脉搏自报的静默时长为准，但也要叠上「距离上一次脉搏」的时间：插件每 5 秒
        // 才报一次，中间的 4 秒若一直显示 40s 会显得没在走。
        val since = (now - lastPulseAt).coerceAtLeast(0)
        val seconds = ((pulse.silentDurationMs + since) / 1000).coerceAtLeast(1)
        return "正在深度思考 (${seconds}s)…"
    }

    suspend fun tick() {
        val now = clock()
        // `start()` 可能在任何一步抛（网络往返、Room 读盘）。它抛了就等于看门狗
        // 从没启动过——但界面此刻正停在一个「看起来已连接」的空壳上。所以第一次
        // tick 无论如何都上发条：宁可多判一次死，也不要再出现「永远不判」。
        if (armedAt <= 0) armedAt = now
        // 先收口超时的写操作：它不能等网络、不能挂在任何 suspend 点上，否则
        // 「命令丢了」会被永远显示成「命令在跑」。
        sweepAcks(now)
        val running = lastPulse?.let { it.hostState != "IDLE" } ?: false
        hostRunning = running
        // 一条脉搏都没收到过，且已经超过判死窗口：这条控制流**无法证明自己是活的**。
        //
        // 这一支必须在 `halfDead` 之前判，因为那个函数在没有脉搏时拿不到
        // `running`/`latestSeq`（都来自脉搏），只能返回 false——实测事故里界面
        // 就该被这一支救回来，却因为「还没开始计时」永久停摆。
        //
        // 坑 54（2026-10-30 实测）：这条分支原来拿**固定**的 `PULSE_DEAD_MS` 当门限，
        // 而 `recover()` 会把 `armedAt` 重置成「此刻」、把 `lastPulseAt` 归零。于是
        // 每一次自救都把发条重新上满 12 秒，12 秒一到又自救——`recoveries` 只增不减，
        // 却没有任何地方用它限流。插件日志里就是每 12 秒一条
        // `focus … replay=200`（约 500 KiB / 30 多帧），紧跟 `tunnel credit stall 10s`。
        // 旁边那条 `halfDead` 分支有 `!halfDeadDeclared` 守卫，这条没有，就是漏了。
        //
        // 修法用**渐进宽限**而不是简单布尔：真没脉搏时重连本身可能是有效的（新
        // socket 上脉搏会来），一次性封死会把真故障变成永久不救。见
        // [SyncCoordinator.noPulseGraceMs]。
        // 僵尸连接那一支（`recoveries == 1`，即上一次自救之后脉搏一个都没回来）
        // **不跟着指数宽限**：指数退避是为了别在「重开也失败」时打成流量风暴，而这里
        // 连重开都还没试过 —— 上一次自救走的是「自称在线、只重挂聚焦」那条路。
        //
        // 2026-11-01 实测（0.2.34 真机卡住）：默认宽限下第 1 次自救在 12 秒、第 2 次
        // 要等 24 秒，用户得盯着不动的屏幕 36 秒才恢复。僵尸判定只用一个固定窗口，
        // 恢复时间回到 24 秒；再第 3 次之后才走指数退避（那时是真的在反复重开）。
        val grace = if (recoveries == 1) {
            SyncCoordinator.PULSE_DEAD_MS
        } else {
            SyncCoordinator.noPulseGraceMs(recoveries)
        }
        // 这一支也要看 `lastDataAt`：重放 500 KiB 时脉搏被压在队尾，但 records
        // 一直在到。只认脉搏会在「最忙的时候」把连接判死，那正是最不该重连的时刻。
        //
        // 但判据必须是**新鲜度**，不能是「有没有过」。原来写的是
        // `maxOf(lastPulseAt, lastDataAt) <= 0`，只要收到过一条 records 就永久为假，
        // 于是「records 一直在到、脉搏一条不来」（插件侧探针 abort，见坑 54）再也
        // 没有任何东西会判死：内容还在更新，`hostState`/`running` 冻在最后一帧旧脉搏，
        // 长推理进度行不再走。见 [SyncCoordinator.pulseEvidenceLost]。
        val lost = SyncCoordinator.pulseEvidenceLost(
            now = now,
            armedAt = armedAt,
            lastPulseAt = lastPulseAt,
            lastDataAt = lastDataAt,
        )
        if (lost && armedAt > 0 && now - armedAt >= grace) {
            note = if (recoveries <= 0) {
                "控制流没有脉搏，正在重连…"
            } else {
                "控制流仍然没有脉搏（已自救 $recoveries 次），${grace / 1000} 秒后再试…"
            }
            recover(now)
            return
        }
        val dead = SyncCoordinator.halfDead(
            now = now,
            lastPulseAt = lastPulseAt,
            running = running,
            latestSeq = lastPulse?.latestSeq ?: 0,
            localAcked = watermarks.localAcked.value,
            lastDataAt = lastDataAt,
        )
        if (dead && !halfDeadDeclared) {
            note = "连接半死，正在增量恢复…"
            recover(now)
            return
        }
        // 平时也顺手把欠账补上（脉搏说中继已经收下了）。
        if (dirty) syncUntilCaughtUp()
        // 存根补齐是独立的一件事：它不影响水位，也不该拖慢补差，但用户看到的是
        // 「这条内容很大」而不是正文（验收 #6 要求要么能下、要么明确报错）。
        fillStubs(now)
    }

    /**
     * 把还没取回正文的存根一条条补上。
     *
     * <p>三处刻意设计：
     * 1. **限速**：一次 tick 最多补 [blobBatch] 条、且相邻两次至少隔 [blobGapMs]。
     *    存根动辄 256 KiB 以上，一口气补十条会在蜂窝网上打出几 MB，用户正在读的
     *    那屏反而变卡。
     * 2. **失败不清存根**：拿不到就留在原地，下次 tick 再试。存根至少还显示
     *    「这条内容很大」，清掉就彻底没了。
     * 3. **不推进水位**：补正文是就地 UPDATE，与 `local_acked_seq` 无关。把它也算进
     *    同步进度会让「正文补齐了但新的还没拉」被误判成已经追平。
     */
    private suspend fun fillStubs(now: Long) {
        if (now - lastBlobAt < blobGapMs) return
        val pending = try {
            store.pendingStubs(sessionId, blobBatch)
        } catch (e: Exception) {
            return
        }
        if (pending.isEmpty()) return
        lastBlobAt = now
        var filled = 0
        for (record in pending) {
            val id = record.blobId ?: continue
            val text = try {
                transport.blob(hostId, id)
            } catch (e: Exception) {
                null
            } ?: continue
            if (text.isEmpty()) continue
            val rows = try {
                store.fillStub(sessionId, record.seq, text)
            } catch (e: Exception) {
                0
            }
            if (rows > 0) filled++
        }
        if (filled > 0) {
            refreshTail()
            onChange?.invoke()
        }
    }

    /** Reasoning text carried by a live delta. The plugin sends
     *  `{type:'assistant-stream', data:{text}}` for deltas and the raw host event
     *  for everything else, so both shapes have to resolve here. */
    private fun TimelineRecord.deltaText(): String = payloadTextOf(payloadJson)

    /** `payload.type` —— 区分流式正文与流式思考的唯一判据。
     *
     *  <p>两种增量都带 `text` 字段，只靠字段在不在分不开；早先正是因为没看 type，
     *  插件把 reasoning 也塞进正文缓冲，手机上思考过程直接铺进了聊天页。 */
    private fun TimelineRecord.deltaKind(): String = payloadTypeOf(payloadJson)

    companion object {
        /** 写操作发出后多久没有 ack 就认为竞速失败（设计 §风险 #3 的 5s SLA 里留 2s 余量）。 */
        const val ACK_RACE_MS = 3_000L

        /**
         * prompt 专用的长竞速窗：插件打宿主的 loopback 有 20 秒超时，宿主忙时
         * prompt 的 ack 会晚到十几秒。3 秒就收口成 `ack-timeout` 会让调用方回落
         * v1 重发，而宿主的 requestId 幂等（hasPromptRequest）在查重与写入 inbox
         * 之间有 await 间隙——回落的那份落进间隙就真正执行两次，手机上「发一次，
         * 排队 2 条」（2026-10-31 定案，生产日志 16:57:24/16:58:36 两条 cmd prompt
         * + 三条 cmd queue 的手动删重复痕迹）。25 秒 = 20 秒 loopback + 5 秒余量，
         * 让 prompt 等插件最终结果再收口。
         */
        const val PROMPT_ACK_RACE_MS = 25_000L

        /** tail 只读这么多：用户看的是尾部，整表重读大不该出现在这条路径上。 */
        const val TAIL_LIMIT = 200

        /**
         * 每次上拉翻多少条。
         *
         * <p>与 `TimelineDatabase.PAGE_SIZE`（50）同量级：一屏约 8~12 行气泡，50 条
         * 够滑三四屏，而单次投影 50 条的开销可以忽略。取太大（比如一次 500）会让
         * 上拉一次就卡一下，因为投影器是纯函数、每次都从头分组。
         */
        const val EARLIER_PAGE = 30

        /** 一次 `syncUntilCaughtUp` 最多几轮：无限循环会把一次堵车变成 CPU 风暴。 */
        const val MAX_ROUNDS = 20

        /** 前台单会话：优先级压过后台批量补差。 */
        const val PRIORITY_FOREGROUND = 100

        /** 与 `SYNC_V2.pageByteBudget` 一致（512 KiB）。 */
        const val PAGE_BYTE_BUDGET = 512 * 1024
    }
}

/**
 * 一帧增量 payload 里的 `type`。
 *
 * <p>**这是区分流式正文与流式思考的唯一判据。** 两种增量都带 `text` 字段，
 * 只靠字段在不在分不开 —— 早先插件正是没看 type，把 `reasoning-delta` 的思考
 * 塞进了正文缓冲，手机上表现为「思考过程直接铺在聊天页、折叠行里反而什么都没有」
 * （2026-10-30 反馈）。插件侧已同步修好，但手机端必须自己也能分辨：
 * 一条老版本插件推来的帧不该把整屏正文换成思考。
 */
internal fun payloadTypeOf(payloadJson: String): String {
    if (payloadJson.isEmpty()) return ""
    return try {
        val obj = Json.parseToJsonElement(payloadJson) as? JsonObject ?: return ""
        (obj.get("type") as? JsonPrimitive)?.contentOrNull ?: ""
    } catch (e: Exception) {
        // 坏 payload 不该让流断掉：宁可这一帧没有类型。
        ""
    }
}

/** 一帧增量 payload 里的文本（`data.text`，退化时认裸字符串）。 */
internal fun payloadTextOf(payloadJson: String): String {
    if (payloadJson.isEmpty()) return ""
    return try {
        val payload = Json.parseToJsonElement(payloadJson)
        val obj = payload as? JsonObject
        val data = obj?.get("data") as? JsonObject
        val text = (data?.get("text") as? JsonPrimitive)?.contentOrNull
        text ?: if (payload is JsonPrimitive) payload.contentOrNull ?: "" else ""
    } catch (e: Exception) {
        // 坏 payload 不该让流断掉：宁可这一帧没有文本。
        ""
    }
}
