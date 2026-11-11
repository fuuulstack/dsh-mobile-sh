package com.dsh.mobile.v2.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dsh.mobile.v2.ApkInstaller
import com.dsh.mobile.v2.BuildConfig
import com.dsh.mobile.v2.R
import com.dsh.mobile.v2.data.CrashLog
import com.dsh.mobile.v2.data.Diag
import com.dsh.mobile.v2.data.ArchiveCoveredSeq
import com.dsh.mobile.v2.data.LocalAckedSeq
import com.dsh.mobile.v2.data.Model
import com.dsh.mobile.v2.data.SessionCache
import com.dsh.mobile.v2.data.SessionWatermarks
import com.dsh.mobile.v2.data.TokenVault
import com.dsh.mobile.v2.data.firstLinePreview
import com.dsh.mobile.v2.data.TimelineStore
import com.dsh.mobile.v2.net.RelayApi
import com.dsh.mobile.v2.net.RelayApiV2
import com.dsh.mobile.v2.net.RelayChatTransport
import com.dsh.mobile.v2.net.RelayGateway
import com.dsh.mobile.v2.net.SyncCoordinator
import com.dsh.mobile.v2.net.SyncPlanner
import com.dsh.mobile.v2.net.SyncRateGate
import com.dsh.mobile.v2.net.long
import com.dsh.mobile.v2.net.obj
import com.dsh.mobile.v2.net.shortId
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.resume

/**
 * 应用状态机：登录态、主机选择、会话列表、延迟探测、事件流。
 *
 * v1 把这些摊在 MainActivity 的两千行里；v2 收进一个 ViewModel，
 * Compose 只负责按状态渲染，路由切换 = 状态字段变化。
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    enum class Route { LOGIN, HOME, CHAT, SETTINGS, SCAN, TASKS }

    data class UiState(
        val route: Route = Route.LOGIN,
        val booting: Boolean = true,
        val loginError: String = "",
        val loginBusy: Boolean = false,
        /** 已授权主机列表（冷启动先用落盘快照）。 */
        val hosts: List<Model.Host> = emptyList(),
        val sessions: List<Model.Session> = emptyList(),
        val loadingSessions: Boolean = false,
        val latencyMs: Int = -1,
        /**
         * 首页顶部的一行提示。
         *
         * 有错误与结果两种语义：`note` 是通道异常的原因（红字），`info` 是刚做完
         * 一件事的结果（灰字）。曾经两者共用 `note`，于是「已从本机删除」被渲染成
         * 报错红字（2026-10-27 验收）。
         */
        val note: String = "",
        val info: String = "",
        /**
         * 一次性 toast 文案（`AppRoot` 弹出后立刻清空）。
         *
         * 只给「看完就没用」的结论用，例如「已是最新版本」：这类结论写进卡片会
         * 留下一块常驻文案，而它本身没有后续动作可做（v1 同样走 Toast）。
         */
        val toast: String = "",
        val crashLog: String = "",
        /** 设置页：中继状态与偏好快照。 */
        val relay: Model.RelayStatus = Model.RelayStatus(),
        val prefs: Model.Preferences = Model.Preferences(),
        /** dsh://pair/ 深链预填到登录页的中继地址（系统相机扫中继网页二维码进来）。 */
        val prefillEndpoint: String = "",
        val prefillCode: String = "",
        /** 当前排序/筛选模式（v1 首页三点菜单切换，落盘记住）。 */
        val sortMode: Model.SortMode = Model.SortMode.BY_WORKSPACE,
        /** 搜索 sheet 状态（v1 showSearch → sheetInput + 结果 sheet）。 */
        val searchOpen: Boolean = false,
        val searchQuery: String = "",
        val searchHitList: List<Model.SearchHit> = emptyList(),
        val searchDone: Boolean = false,
        val searchNote: String = "",
        /**
         * 相机权限被重新授予的次数。
         *
         * 扫码页先切路由再弹权限框，弹窗关掉时页面已经建好、TextureView 已就绪，
         * 但那次 start() 因为没权限失败了。这个计数一变，扫码页就自动重试一次，
         * 用户不用再去点「重试」。
         */
        val cameraGrantTick: Int = 0,
        /** 版本管理：检查/下载的状态机（设置页「检查更新」分组）。 */
        val releaseChecking: Boolean = false,
        val pendingRelease: Model.Release? = null,
        val releaseNote: String = "",
        val releaseError: String = "",
        /**
         * 下载被「安装未知应用」权限卡住。
         *
         * 单独一个布尔而不是往 `releaseNote` 里塞「去开启安装权限」：那句文案还要被
         * 拿去比对来决定显示哪个按钮，用户从系统设置页回来后权限已变、文案却还是
         * 旧的，界面就停在「去开启安装权限」不动（2026-10-28 反馈）。有了这个字段，
         * 权限一刷新就能把它翻回 false。
         */
        val installPermissionMissing: Boolean = false,
        val downloadPercent: Int = -2, // -2=未开始, -1=进行中(未知总大小), >=0=百分比
    )

    private val vault = TokenVault(app)
    val cache = SessionCache(app)
    private val api = RelayApi()

    /**
     * v2 的同步与实时通道。
     *
     * 与 [gateway] 并存是过渡期的现实：`gateway` 还担着会话列表、模型目录、
     * 审批问答这些**非时间线**的活儿，而时间线（正文、思考、工具步骤）已经整条
     * 搬到「中继归档 + 插件控制流」上。两者共用同一个 [RelayApi]（同一个令牌、
     * 同一条主线程投递口），所以不存在两份登录态。
     */
    private val v2api = RelayApiV2(api)
    val timeline: TimelineStore = TimelineStore.store(app)

    /** v2 时间线的传输实现。做成字段是为了测试能换掉它，也是为了在
     *  `RelayApi` 的令牌轮换时只有一处需要重建。 */
    val chatTransport: RelayChatTransport by lazy { RelayChatTransport(v2api) }

    val gateway = RelayGateway(api, vault)

    private val _state = MutableStateFlow(UiState())
    /** 相机的来路：`openScan()` 记、`closeScan()` 原路返回（见那边的注释）。 */
    private var scanReturnRoute: Route = Route.HOME
    val state: StateFlow<UiState> = _state

    /** 当前打开的会话。 */
    var activeSession: Model.Session? = null
        private set

    /** 聊天页桥：事件流上的审批/提问/状态转发给当前打开的 ChatViewModel。 */
    var chatBridge: ChatViewModel? = null

    private var latencyJob: Job? = null
    private var hostsLoadedAt = 0L
    private var lastHostId: String = ""

    /** 下拉刷新的超时兜底（见 [refreshSessions]）：请求回来或超时后都要撤掉。 */
    private var refreshGuard: Job? = null

    /**
     * 下拉刷新指示器最早可以收起的时刻（`0` = 没有强制停留）。
     *
     * 见 [settleSessionsLoading]：本机回包太快时 `true`/`false` 会被 StateFlow 合并成一帧
     * `false`，指示器一次都没转起来。
     */
    private var refreshMinUntil: Long = 0L

    /** 每会话的补页调度（同会话最多一个 /sync 在途，期间的变更只标 dirty）。 */
    private val syncs = SyncPlanner.Tracker()

    /** 连续零进展次数：只影响退避长度，一旦有进展就清掉。 */
    private val zeroProgress = HashMap<String, Int>()
    /** `/sync` 的客户端限流闸门（服务端已放宽到 600 次/分钟，见 relay 的 `RATE.sync`）。 */
    private val syncGate = SyncRateGate()

    /**
     * v2 游标是否已经取代 v1（一次性日志，避免每次刷列表都重复宣告）。
     *
     * <p>语义上是「旧游标一律不信、重拉」的记账点，不是功能开关。
     */
    private var v2CursorsTrusted = false

    /** 正在跑的补页请求数 / 排队等待的会话（见 [launchSessionSync]）。 */
    private var syncRunning = 0
    private val syncQueue = ArrayDeque<Pair<String, Int>>()
    /** 最近一次本地搜索命中（v1 里搜索结果直接来自 LocalSearch 返回值）。 */
    private var searchHits: List<Model.SearchHit> = emptyList()

    init {
        // 崩溃堆栈要赶在一切业务之前落盘初始化。
        Diag.init(app)
        CrashLog.init(app)
        CrashLog.install()
        api.origin = vault.origin()
        api.token = vault.load()
        api.omit = vault.omit()
        gateway.bindPreferences(
            editContent = { vault.editContent() },
            autoReconnect = { vault.autoReconnect() },
            transferAttachments = { vault.transferAttachments() },
            saveEditContent = { vault.editContent(it) },
            saveAutoReconnect = { vault.autoReconnect(it) },
            saveTransferAttachments = { vault.transferAttachments(it) },
        )
        _state.value = _state.value.copy(crashLog = CrashLog.read())
        CrashLog.clear()
        // 注意：boot() 不在这里调！Kotlin 属性与 init 块按声明顺序执行，
        // eventsListener（文件后部）此时还是 null——冷启动有 lastHost 缓存时
        // boot→selectHost→openStreams→openEvents(eventsListener) 会传 null 崩溃
        // （2026-10-26 真机闪退实证）。boot 挪到类末尾的 init 块（所有属性就绪后）。
    }

    private fun update(transform: (UiState) -> UiState) {
        _state.value = transform(_state.value)
    }

    fun hostId(): String = lastHostId

    /**
     * 切回主线程执行。
     *
     * `RelayApi` 的回调都在主线程上，但 v2 的同步是挂起函数、跑在 IO 上，
     * 它拿到的回调也在 IO 上。改 `StateFlow` 的写者必须是同一个线程，
     * 否则 Compose 会读到撕裂的快照。
     */
    fun runOnMain(block: () -> Unit) {
        handler.post(block)
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 聊天页落盘后同步刷新首页那一行的首行预览。
     *
     * 首页的预览是从本地消息算的，聊天页新收到的回复不会自己出现在列表里；
     * 返回列表时若还靠「进页面才重算」，用户会先看到上一轮的旧句子。
     */
    fun syncPreview(hostId: String, sessionId: String, messages: List<Model.Message>) {
        onMessagesChanged(hostId, sessionId, messages)
    }

    /** 冷启动：有令牌直接进列表页，没有则留在登录页。 */
    private fun boot() {
        if (!gateway.hasToken()) {
            update { it.copy(booting = false, route = Route.LOGIN) }
            return
        }
        // 冷启动先用落盘的主机快照：顶栏立刻显示电脑名，切换菜单也能离线点开。
        val known = cache.loadHosts()
        val saved = cache.lastHost()
        if (saved.isNotEmpty()) {
            var cachedHost = known.firstOrNull { it.id == saved }
            if (cachedHost == null) {
                // online 不置 true：冷启动根本没有在线证据，绿点必须等 listHosts 回话。
                cachedHost = Model.Host(alias = cache.lastHostAlias())
                cachedHost.id = saved
            }
            cachedHost.authorized = true
            gateway.selectHost(cachedHost)
        }
        lastHostId = gateway.currentHost()?.id ?: ""
        update {
            it.copy(
                booting = false,
                route = Route.HOME,
                hosts = known,
                sessions = filterVisible(cache.loadSessions(lastHostId)),
            )
        }
        if (lastHostId.isNotEmpty()) {
            loadHosts(false)
            loadSessions()
            openStreams()
            startLatencyLoop()
        } else {
            // 从未选过主机（配对后杀进程重开、或多设备新装）：没有 lastHostId 也要
            // 拉一次 hosts——loadHosts 回调里的「首次出现已授权主机自动选中」是
            // 这条路径唯一的救星，否则空态配对引导永远出不去（2026-10-26 真机实测：
            // 中继 grants 已建立，App 却停在「提示配对」，就是因为 boot 里这次
            // loadHosts 被跳过了）。
            loadHosts(false)
        }
    }

    // ============================================================
    // 登录 / 配对
    // ============================================================

    fun submitLogin(endpoint: String, code: String, label: String) {
        if (_state.value.loginBusy) return
        update { it.copy(loginBusy = true, loginError = "") }
        val previousRelay = gateway.relayOrigin()
        gateway.login(endpoint, code.ifBlank { null }, label.ifBlank { null }) { _, error ->
            if (error != null) {
                update { it.copy(loginBusy = false, loginError = error) }
                return@login
            }
            if (previousRelay.isNotEmpty() && previousRelay != gateway.relayOrigin()) forgetStaleRelay()
            afterLogin()
        }
    }

    /** 登录成功（含深链进入）后的共同落点。 */
    private fun afterLogin() {
        lastHostId = gateway.currentHost()?.id ?: ""
        update { it.copy(loginBusy = false, loginError = "", route = Route.HOME) }
        loadHosts(true)
        loadSessions()
        openStreams()
        startLatencyLoop()
        // 附件策略按设备记在中继上：登录后先把真值拉回来，别让本地缓存冒充它。
        refreshAttachmentPolicy()
    }

    /**
     * 换了一台中继：缓存里只对上一台有效的东西（主机 id、当前主机）立刻作废。
     *
     * <p>主机 id 是**中继局部**的——同一台电脑在另一台中继上是另一个 id。不清理的话，
     * 冷启动的 `boot()` 恢复与迟到的 `loadHosts` 答复都会把界面指到一台在新中继上
     * 不存在的电脑：顶栏挂旧机器名、`/changes` 每 3 秒换一个 404（2026-11-04 在 self-hosted
     * 裸 IP 上实测，生产中继的 `/hosts` 迟了 2.3 秒，正好压在配对完成之后）。
     *
     * <p>只清主机记账，不碰会话缓存：后者一律按 hostId 分键，换了中继再也读不到。
     */
    private fun forgetStaleRelay() {
        gateway.selectHost(null)
        lastHostId = ""
        cache.clearRelayState()
        update { it.copy(hosts = emptyList(), sessions = emptyList(), note = "") }
    }

    /**
     * dsh://pair 深链：与扫码同一条「一码配齐」路径。
     *
     * 契约：`dsh://pair?relay=https://relay.example.com&login=12345678&code=87654321`。
     * 带 `login` → 全自动登录 + 配对（见 [onScanned]）；不带 → 旧的两步路径。
     */
    fun handlePairLink(uri: android.net.Uri) {
        if (uri.scheme != "dsh" || uri.host != "pair") return
        val code = uri.getQueryParameter("code") ?: uri.fragment?.takeIf { it.matches(Regex("\\d{4,10}")) }
        if (code.isNullOrBlank()) return
        val relay = uri.getQueryParameter("relay") ?: ""
        val login = uri.getQueryParameter("login")?.takeIf { it.matches(Regex("\\d{8}")) }
        if (login != null && relay.isNotBlank()) {
            onScanned(ScanPairing(relay, code, login))
            return
        }
        if (gateway.hasToken()) {
            gateway.pair(code) { _, error ->
                if (error != null) {
                    update { it.copy(note = error) }
                } else {
                    loadHosts(true)
                }
            }
            return
        }
        update {
            it.copy(
                route = Route.LOGIN,
                booting = false,
                prefillEndpoint = relay.ifBlank { it.prefillEndpoint },
                prefillCode = code,
            )
        }
    }

    fun submitPair(code: String) {
        if (_state.value.loginBusy) return
        update { it.copy(loginBusy = true, loginError = "") }
        gateway.pair(code) { _, error ->
            if (error != null) {
                update { it.copy(loginBusy = false, loginError = error) }
                return@pair
            }
            // v1 对齐（MainActivity.openPairing.ok：toast「配对成功」+ show(HOME)
            // + refreshHostsAndSessions）。原实现成功路径什么都不做——抽屉被调用方
            // 一关，界面毫无反馈，真机表现就是「按钮变灰卡住」（2026-10-26 踩坑）。
            //
            // 注意不能只依赖 loadHosts 回调里的「首次出现已授权主机自动选中」：那个
            // 分支只在 currentHost()==null 时走，而配第二台电脑时旧主机还挂着，于是
            // 新配的主机永远选不上，后续 RPC 继续发给那台可能已经离线的旧机
            // （2026-10-27 实测：配对成功但发送返回 503 host-offline）。
            update { it.copy(loginBusy = false, loginError = "", note = "", info = "") }
            switchToJustPaired()
        }
    }

    /**
     * 配对成功后切到「刚配好的那台」。
     *
     * 中继不告诉我们是哪一台，所以重新拉一次主机列表，挑最新出现的已授权主机。
     * 用 id 集合做差：配对前不在、配对后在的那条就是它；差集为空（重复配对已有主机）
     * 时保持当前选择不动。
     */
    private fun switchToJustPaired() {
        val before = _state.value.hosts.map { it.id }.toSet()
        gateway.listHosts { value, error ->
            if (value == null) {
                update { it.copy(note = error ?: "") }
                return@listHosts
            }
            cache.saveHosts(value)
            update { it.copy(hosts = value) }
            val fresh = value.firstOrNull { it.authorized && it.id !in before }
                ?: value.firstOrNull { it.authorized }
            if (fresh != null) selectHost(fresh) else loadSessions()
        }
    }

    /**
     * 扫码页入口：先索要相机权限（异步申请，扫码引擎内还会自查兜底），
     * 路由切到 SCAN——仅登录态可达，已登录时从配对抽屉进也一样退回登录页预填。
     */
    fun openScan() {
        // 记住来路：相机页退出要回到**进来时那一页**，不再一律回登录页。
        //
        // 原来 `closeScan()` 写死 `Route.LOGIN`，于是「会话列表 → 扫一扫 → 返回」会落到
        // 登录/扫码引导页（一个已登录的人被扔回「扫一下即可连接」），反过来在未登录的
        // 引导页上按返回又看不出区别。2026-11-05 用户口径：「返回到扫码页，不是会话列表」——
        // 两种来路都要能原路返回，所以记下进入前的那一页。
        scanReturnRoute = _state.value.route
        requestCameraPermission()
        update { it.copy(route = Route.SCAN) }
    }

    fun closeScan() {
        update { it.copy(route = scanReturnRoute) }
    }

    /** 已登录（令牌还在）时，登录/扫码引导页要留一个「回到会话列表」的出口。 */
    fun canReturnHome(): Boolean = gateway.hasToken()

    /**
     * 扫到配对码：一码配齐，全自动，不再回登录页预填。
     *
     * 2026-10-27 按用户要求改：旧流程「扫码 → 回登录页预填 → 用户确认 → 提交」，
     * 而且未登录时要先在中继网页生成登录码，等于扫两个码、填三样东西。现在：
     * - 码里带 `login`（插件「生成二维码」下发的新格式）→ 自动登录 + 自动配对，
     *   登录码全程不进用户视线，扫完直接进首页；
     * - 码里没有 `login`（老中继 / 中继网页的旧格式）→ 退回旧的两步路径，
     *   带着地址与配对码回登录页让用户补登录码。
     * - 已登录（可能登录在另一个中继上）→ 切到码里的中继（多中继分槽）再配对；
     *   那个中继没有存的令牌时才要求带 login 的码或走旧路径。
     */
    fun onScanned(pairing: ScanPairing) {
        val login = pairing.login
        if (login != null) {
            // 全自动路径：登录码换 30 天令牌 → 配对码换主机授权 → 进首页。
            update { it.copy(loginBusy = true, loginError = "") }
            // label 传 null，由网关统一填 Build.MODEL：中继按 label 认设备（同名的复用同一行
            // 设备、保留它的主机授权）。扫码链路若自报「手机扫码」而手动登录报机型，同一台手机
            // 会在中继里变成两台设备，之前配好的主机就全成了未配对。
            val previousRelay = gateway.relayOrigin()
            gateway.login(pairing.relay, login, null) { _, loginError ->
                if (loginError != null) {
                    update { it.copy(loginBusy = false, loginError = loginError) }
                    return@login
                }
                if (previousRelay.isNotEmpty() && previousRelay != gateway.relayOrigin()) forgetStaleRelay()
                gateway.pair(pairing.code) { _, pairError ->
                    if (pairError != null) {
                        // 登录已成功，只有配对失败：回登录页报错但保住令牌，
                        // 用户重扫一个码即可（不必重新登录）。
                        update { it.copy(loginBusy = false, loginError = pairError) }
                        return@pair
                    }
                    update { it.copy(loginBusy = false, loginError = "", note = "", info = "") }
                    afterLogin()
                    switchToJustPaired()
                }
            }
            return
        }
        // 旧格式兜底：没有登录码就维持旧的两步体验（预填 + 用户补码确认）。
        update {
            it.copy(
                route = Route.LOGIN,
                prefillEndpoint = pairing.relay,
                prefillCode = pairing.code,
                loginError = "",
            )
        }
    }

    fun logout() {
        interactions.clear()
        // 换账号/换主机之后 attachmentId 可能指向完全不同的内容，缓存必须清。
        ThumbCache.clear()
        gateway.logout { _, _ ->
            cache.clear()
            previewCache.clear()
            update { it.copy(route = Route.LOGIN, sessions = emptyList(), hosts = emptyList(), note = "", info = "") }
            latencyJob?.cancel()
        }
    }

    /** 登录屏清掉错误（输入变化时）。 */
    fun clearLoginError() {
        if (_state.value.loginError.isNotEmpty()) update { it.copy(loginError = "") }
    }

    // ============================================================
    // 主机
    // ============================================================

    fun loadHosts(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - hostsLoadedAt < HOSTS_TTL_MS) return
        hostsLoadedAt = now
        gateway.listHosts { value, error ->
            if (value != null) {
                cache.saveHosts(value)
                // 这里保留中继给的全量（含未配对），由 UI 层的 pairedHosts 统一收口：
                // 数据层留着它才能在「当前主机的授权被撤销」时认出这台机器已经不可用。
                update { it.copy(hosts = value) }
                // 当前这台在新列表里已经不可用了（换了设备重新登录、绑定被撤销、
                // 电脑被移除），必须立刻放手：否则后续 rpc/与 /changes 全打在一台
                // 没有 grant 的主机上，中继回 404，界面只剩一句「请求失败（404）」，
                // 而用户真正该看到的是「先配对一台电脑」。
                val current = gateway.currentHost()
                if (current != null && value.none { it.id == current.id && it.authorized }) {
                    // 顺着清掉那台主机留下的报错：它已经不再是目标，继续挂着只
                    // 会在空态上方顶一条与当前操作无关的 404。
                    update { it.copy(note = "") }
                    selectHost(null)
                }
                // 首次出现已授权主机时自动选中（配对成功后的落地：v1 的
                // refreshHostsAndSessions 等价路径——否则配对后 hasAuthorizedHost
                // 变 true 但没有当前主机，会话列表仍拉不到东西）。
                if (gateway.currentHost() == null) {
                    value.firstOrNull { it.authorized }?.let { selectHost(it) }
                }
            }
            if (error != null && value == null) {
                update { it.copy(note = error) }
            }
        }
    }

    /** 切换当前主机；会话列表随主机走。 */
    fun selectHost(host: Model.Host?) {
        gateway.selectHost(host)
        lastHostId = host?.id ?: ""
        cache.setLastHost(lastHostId, host?.alias ?: "")
        _state.value = _state.value.copy(
            sessions = filterVisible(cache.loadSessions(lastHostId)),
        )
        loadSessions()
        openStreams()
    }

    fun currentHost(): Model.Host? = gateway.currentHost()

    // ============================================================
    // 会话列表
    // ============================================================

    /**
     * 拉会话列表。
     *
     * @param silent 静默刷新：不亮 loadingSessions——列表已有数据时的后台刷新
     * （leaveChat 返回列表）不该弹下拉刷新指示器（2026-10-28 用户反馈）。
     */
    fun loadSessions(silent: Boolean = false) {
        if (gateway.currentHost() == null) {
            // 没有主机时也要收指示器：交给这里的人可能是下拉手势（见 refreshSessions）。
            if (!silent) update { it.copy(loadingSessions = false) }
            return
        }
        if (!silent) update { it.copy(loadingSessions = true) }
        gateway.listSessions { value, error ->
            refreshGuard?.cancel()
            refreshGuard = null
            if (value != null) {
                cache.saveSessions(lastHostId, value)
                update {
                    it.copy(
                        sessions = filterVisible(value),
                        note = "",
                        info = "",
                    )
                }
                settleSessionsLoading()
                fillPreviews(value)
            } else {
                update { it.copy(note = error ?: it.note) }
                settleSessionsLoading()
            }
        }
    }

    /**
     * 收掉下拉刷新指示器，但不早于 [refreshMinUntil]。
     *
     * 本机中继 1ms 就回包时，`true` 与 `false` 在同一个主线程切片里写完，StateFlow 会
     * 合并掉中间那一帧 —— `PullToRefreshBox` 的 `isRefreshing` **一次都没变成过 true**，
     * 指示器停在松手时的位置不动（2026-10-30 实测：转了两分钟还在转，`uiautomator`
     * 里 `ProgressBar` 节点一直在）。所以置位后必须撑满一个可见时长。
     */
    private fun settleSessionsLoading() {
        val remaining = refreshMinUntil - System.currentTimeMillis()
        if (remaining <= 0) {
            update { it.copy(loadingSessions = false) }
            return
        }
        viewModelScope.launch {
            delay(remaining)
            update { it.copy(loadingSessions = false) }
        }
    }

    /**
     * 下拉刷新手势。
     *
     * `PullToRefreshBox` 的 `isRefreshing` 绑的是 [UiState.loadingSessions]，
     * 指示器**只认这一个开关**：回调里不置位就转不起来，置位后没人复位就一直
     * 转下去（2026-10-30 用户实测「下拉刷新会卡住图标」）。
     *
     * 所以这里必须自己做三件事：①真的去刷会话列表；②让 `true` 撑满
     * [REFRESH_MIN_VISIBLE_MS]（见 [settleSessionsLoading]）；③给一次请求兜一个超时——
     * 弱网或隧道抖动时 `listSessions` 的回调可能长时间不回来，没有兜底就永远转。
     */
    fun refreshSessions() {
        refreshMinUntil = System.currentTimeMillis() + REFRESH_MIN_VISIBLE_MS
        if (!_state.value.loadingSessions) update { it.copy(loadingSessions = true) }
        refreshGuard?.cancel()
        refreshGuard = viewModelScope.launch {
            delay(REFRESH_TIMEOUT_MS)
            // 超时兜底：宁可少转一会儿，也不能让指示器挂死。
            if (_state.value.loadingSessions) {
                refreshMinUntil = 0L
                update { it.copy(loadingSessions = false) }
            }
        }
        loadSessions()
    }

    /**
     * 把会话内容预拉进缓存——列表的首行预览就靠它。
     *
     * <p>首行不再由宿主/中继给（那条 snippet 旁路 2026-10-28 已拆），改为从本地
     * 已落盘的消息现算，所以**必须真的把正文拉到本地**，否则列表只有标题。
     *
     * <p>请求带上去的是**归档游标**（[SessionCache.archiveCursors]），不是会话列表
     * 的 `lastSeq`、也不是本地消息的最大 seq。这两个曾经都被当成游标用过，结果是
     * 游标虚高到元数据水位，服务端认为「你都齐了」从此一个字节不下发。
     *
     * <p>逐会话差量走 [SyncPlanner.Tracker] 串行化：同一会话同时只有一个 `/v2/sync` 在途，
     * 期间的变更只标 dirty，等当前这轮结束再对账，不会几十条并发打同一份数据。
     */
    private fun fillPreviews(sessions: List<Model.Session>) {
        val hostId = lastHostId
        if (hostId.isEmpty() || sessions.isEmpty()) return
        // v2 有了自己的三水位（Room `session_metadata`），v1 的 prefs 游标整体作废：
        // 它是从乱猜的语义（元数据水位）演化来的，可信度只能靠「重新拉一遍」恢复。
        // 迁移只跑一次，之后一直走 v2。这条迁移只对新装的 App 有意义，老用户的
        // 迁移标记早就在 prefs 里了。
        if (!v2CursorsTrusted) {
            v2CursorsTrusted = true
            Diag.log("sync", "v2 cursors reset for ${shortId(hostId)}, re-syncing from scratch")
        }
        // 旧版游标只在这里迁一次：min(旧游标, 本地正文最大 seq)，理由见 SessionCache。
        val migrated = cache.migrateArchiveCursors(hostId)
        if (migrated > 0) Diag.log("sync", "migrated $migrated archive cursors host=${shortId(hostId)}")
        // 冷启动时落后几十个会话是常态。**必须走批量轮转**：一次 /sync 能服务
        // sessionCap 个会话，而中继对 /sync 的限流是每账号 30 次/分钟——逐个会话
        // 单发的话第一分钟就打出上百次 429（实测 160 次），全靠退避重试慢慢收敛。
        // 批量轮转把「31 个会话」变成「4 次请求」。
        batchSyncRound(hostId, sessions, round = 1, pulled = 0)
    }

    /**
     * 一轮批量补拉。跑完若还有会话落后就再来一轮（有次数上限）。
     *
     * @param round 当前轮次（从 1 开始）。
     * @param pulled 本次冷启动累计已合并的会话数，用于日志。
     */
    private fun batchSyncRound(
        hostId: String,
        sessions: List<Model.Session>,
        round: Int,
        pulled: Int,
        subset: Boolean = false,
    ) {
        if (hostId != lastHostId) return
        // 批量预拉也要走同一个调度器：不然它和某个会话的单飞补页会同时在途，
        // 两边各读一份 cached、各自 merge、先后落盘——后落的那份会把先落的记录写没。
        // 认领失败的会话（已经有一个在途）本轮跳过，它有 dirty 会在收尾时自己续上。
        val claimed = ArrayList<String>()
        val cursors = HashMap<String, Long>()
        for (s in sessions) {
            if (s.id.isEmpty() || !syncs.begin(s.id)) continue
            claimed.add(s.id)
            cursors[s.id] = cache.archiveCursor(hostId, s.id)
        }
        if (cursors.isEmpty()) return
        // 批量轮转同样受额度约束：额度不足就把认领放回去，等窗口滑过去再来，
        // 而不是硬发出去换一个 429（那会连累后面所有请求）。
        val now = System.currentTimeMillis()
        if (!syncGate.tryAcquire(now)) {
            for (id in claimed) syncs.finish(id)
            val wait = syncGate.waitMs(now)
            Diag.log("sync", "batch round $round 额度用完，${wait}ms 后再来")
            viewModelScope.launch {
                delay(wait.coerceAtLeast(1_000L))
                if (hostId != lastHostId) return@launch
                batchSyncRound(hostId, sessions, round, pulled, subset)
            }
            return
        }
        batchSyncV2(hostId, sessions, claimed, cursors, round, pulled, subset)
    }

    /**
     * v2 的批量补差：一次 `/v2/sync` 带多个会话，回来直接写 Room。
     *
     * <p>落的是 `TimelineRecord` 加三水位，游标由 `SyncCoordinator.advance` 侧算
     * （只进不退、且绝不越过真正落盘的 seq），首行预览从 Room 现取。
     *
     * <p>v1 那条归档补差（`gateway.syncArchive` + `cache.saveMessagesNow`）已随
     * `SessionAccumulator` 一起删除：老中继不再兼容。
     */
    private fun batchSyncV2(
        hostId: String,
        sessions: List<Model.Session>,
        claimed: List<String>,
        cursors: Map<String, Long>,
        round: Int,
        pulled: Int,
        subset: Boolean,
    ) {
        // 游标只能来自 Room 的 `local_acked_seq`，不能来自 prefs 里那份 v1 归档游标。
        // 实测症状：批量补拉每 2.5 秒发一次 `from=0`，中继照发同一页 112 条，而
        // `advance()` 按真正落盘的 seq 把水位推到 5350；下一轮又读回 0，于是永远
        // 重放同一段、`behind` 永远非空、轮次烧到上限为止（diag 里连续 4 行
        // `from=0 records=112 landed=5350`）。三水位分离的意义正在于此。
        viewModelScope.launch {
            val roomCursors = withContext(Dispatchers.IO) {
                runCatching { timeline.allMeta().associate { it.sessionId to it.localAckedSeq } }
                    .getOrDefault(emptyMap())
            }
            if (hostId != lastHostId) {
                for (id in claimed) syncs.finish(id)
                return@launch
            }
            sendBatch(hostId, sessions, claimed, roomCursors, cursors, round, pulled, subset)
        }
    }

    private fun sendBatch(
        hostId: String,
        sessions: List<Model.Session>,
        claimed: List<String>,
        roomCursors: Map<String, Long>,
        cursors: Map<String, Long>,
        round: Int,
        pulled: Int,
        subset: Boolean,
    ) {
        val requests = sessions.map { session ->
            RelayApiV2.BatchSession(
                sessionId = session.id,
                // v2 有 Room 水位就用它；只有从未被 v2 同步过的会话才回落到
                // 老游标（通常是 0，等于「从头拉」——这正是正确行为）。
                afterSeq = LocalAckedSeq(roomCursors[session.id] ?: cursors[session.id] ?: 0L),
                priority = if (session.id == chatBridge?.currentSessionId()) 100 else 10,
            )
        }
        v2api.syncBatch(hostId, requests) { pages, error, _ ->
            val rerun = ArrayList<String>()
            for (id in claimed) if (syncs.finish(id)) rerun.add(id)
            if (hostId != lastHostId) {
                syncs.reset()
                return@syncBatch
            }
            if (error != null) {
                when (error) {
                    "sync-unsupported" -> {
                        // 中继太老，没有 /v2/sync。这条路已经没有 v1 兜底：
                        // 记一笔就够，重跑只会原地打转并把「同步中」挂在界面上。
                        Diag.log("sync", "relay has no /v2/sync; this build needs a newer relay")
                    }
                    "sync-loop" -> Diag.log("sync", "v2 batch loop guard hit, backing off")
                    else -> Diag.log("preview", "v2 sync failed: $error")
                }
                return@syncBatch
            }
            if (pages.isEmpty()) {
                for (id in rerun) requestSessionSync(hostId, id)
                return@syncBatch
            }
            viewModelScope.launch {
                val progressed = withContext(Dispatchers.IO) {
                    var landedSessions = 0
                    for (page in pages) {
                        val before = page.reqAfterSeq
                        val landed = page.records.maxOfOrNull { it.seq } ?: 0L
                        // 只传裸 Long：`advance` 收的是三个 seq 而不是三个水位，
                        // 让它返回 `LocalAckedSeq` 是编译期就挡住「把元数据水位当游标」。
                        val next = SyncCoordinator.advance(before, page.nextCursorSeq, landed)
                        // 两水位各算各的，**绝不能互相顶替**：
                        //  - archiveCovered 来自中继（「我归档到这么多」），
                        //  - localAcked 只由 `advance` 按真正落盘的 seq 决定。
                        // serverMeta（宿主说「我这有这么多」）已废弃：推进条件与另外两个完全不同，
                        // 且从未被实际使用（未读红点从会话列表的 `lastSeq` 现算）。
                        val meta = runCatching { timeline.watermark(page.sessionId) }.getOrNull()
                        val watermarks = SessionWatermarks(
                            archiveCovered = ArchiveCoveredSeq(
                                maxOf(meta?.archiveCovered?.value ?: 0L, page.archiveCoveredSeq),
                            ),
                            localAcked = next,
                        )
                        val wrote = runCatching {
                            timeline.commit(page.sessionId, page.records, watermarks)
                        }.getOrDefault(0)
                        if (wrote > 0 || page.records.isNotEmpty()) landedSessions++
                        Diag.log(
                            "sync",
                            "v2 ${shortId(page.sessionId)} from=$before records=${page.records.size} " +
                                "landed=$landed next=${next.value} hasMore=${page.hasMore} " +
                                "stop=${page.stopReason}",
                        )
                    }
                    landedSessions
                }
                if (hostId != lastHostId) return@launch
                if (progressed > 0) {
                    // 首行预览从 Room 现算：v2 的正文只写在这里，prefs 里那份还是旧的。
                    for (page in pages) invalidatePreview(page.sessionId)
                    update { state -> state.copy(sessions = filterVisible(cache.loadSessions(hostId))) }
                    // `requeue` 而不是 `enqueue`：这一批是「缓存整体作废，重算一遍」，
                    // 不管它当前闩没闩住都要再算一次。
                    for (page in pages) warmQueue.requeue(hostId, page.sessionId)
                    scheduleWarm()
                }
                val behind = pages.filter { it.hasMore && it.nextCursorSeq > it.reqAfterSeq }
                if (behind.isNotEmpty() && round < MAX_BATCH_ROUNDS) {
                    val next = behind.map { page ->
                        sessions.firstOrNull { it.id == page.sessionId } ?: return@launch
                    }
                    batchSyncRound(hostId, next, round + 1, pulled + progressed, subset = true)
                    return@launch
                }
                for (id in rerun) requestSessionSync(hostId, id)
            }
        }
    }

    /**
     * 会话补页的统一入口：**同一会话同一时刻只允许一个 `/sync` 在途**。
     *
     * <p>一次流式回复会连发几十个变更事件。若每个都直接发一次 `/sync`，就是几十条
     * 并发公网请求，而且它们拿同一个游标、下载同一批记录——评审现场「同一批
     * 3/28/21 条记录反复重下」正是这么来的。串行化之后，期间的变更只标 dirty，
     * 等当前这轮结束再对一次账。
     */
    private fun requestSessionSync(hostId: String, sessionId: String, attempt: Int = 1) {
        if (hostId.isEmpty() || sessionId.isEmpty()) return
        if (hostId != lastHostId) return
        if (!syncs.begin(sessionId)) return
        launchSessionSync(hostId, sessionId, attempt)
    }

    /**
     * 给「每个会话一轮补页」加一道全局闸门。
     *
     * <p>中继对 `/sync` 的限流是**每账号 30 次/分钟**（`server.ts` 的
     * `store.rate('sync:'+id_hash,30,60000)`）。冷启动时二十几个会话同时要补页、
     * 每个还要跑好几轮，一拥而上就会被大面积 429——实测一轮 397 次 429，
     * 全靠退避重试才慢慢收敛（多花了两三分钟）。
     *
     * <p>这里限制同时在途的补页数，超出的排队（同一会话不重复排）。队列长度也封顶：
     * 真落了太多，下一次列表刷新/变更事件还会再要一遍，宁可丢排队位置也不无限堆。
     */
    private fun launchSessionSync(hostId: String, sessionId: String, attempt: Int) {
        synchronized(syncQueue) {
            if (syncQueue.any { it.first == sessionId }) return
            if (syncRunning >= SYNC_CONCURRENCY || !syncGate.tryAcquire(System.currentTimeMillis())) {
                if (syncQueue.size < SYNC_QUEUE_MAX) syncQueue.add(sessionId to attempt)
                return
            }
            syncRunning++
        }
        dispatchSessionSync(hostId, sessionId, attempt)
    }

    private fun dispatchSessionSync(hostId: String, sessionId: String, attempt: Int) {
        runSessionSync(hostId, sessionId, attempt) {
            val stale = hostId != lastHostId
            val now = System.currentTimeMillis()
            val wait = syncGate.waitMs(now)
            val next: Pair<String, Int>? = synchronized(syncQueue) {
                syncRunning--
                // 主机切走时队列里的账已经作废，一并清掉。
                if (stale) {
                    syncQueue.clear()
                    null
                } else if (wait > 0) {
                    // 额度用完：队列留着，等窗口滑过去再整体放行（不是逐个撞墙）。
                    null
                } else {
                    syncQueue.removeFirstOrNull()
                }
            }
            if (!stale && next != null) {
                launchSessionSync(hostId, next.first, next.second)
            } else if (!stale && wait > 0 && syncQueue.isNotEmpty()) {
                Diag.log("sync", "额度用完，${wait}ms 后继续排队的 ${syncQueue.size} 个会话")
                viewModelScope.launch {
                    delay(wait.coerceAtLeast(1_000L))
                    drainSyncQueue(hostId)
                }
            }
        }
    }

    /** 把队列里等着的会话按额度放出去（额度用完时由定时器调用）。 */
    private fun drainSyncQueue(hostId: String) {
        if (hostId != lastHostId) return
        while (true) {
            val next = synchronized(syncQueue) {
                if (syncRunning >= SYNC_CONCURRENCY || !syncGate.tryAcquire(System.currentTimeMillis())) {
                    null
                } else {
                    syncRunning++
                    syncQueue.removeFirstOrNull()
                }
            } ?: return
            dispatchSessionSync(hostId, next.first, next.second)
        }
    }

    /**
     * 跑一轮会话补页；结束后按 dirty 决定再来一轮，按零进展决定退避。
     *
     * @param attempt 连续失败次数（用于退避长度），正常推进就回到 1。
     */
    private fun runSessionSync(hostId: String, sessionId: String, attempt: Int, onDone: () -> Unit) {
        runSessionSyncV2(hostId, sessionId, attempt, onDone)
    }

    /**
     * v2 的单会话补页。
     *
     * <p>与批量版共用同一套落盘与游标规则，区别只在于它是「一个会话磨到底」：只要
     * `hasMore` 就接着拉，直到追平或零进展。单飞由调用方（[requestSessionSync] 的
     * `syncs.begin`）保证，这里不再自己加锁。
     */
    private fun runSessionSyncV2(hostId: String, sessionId: String, attempt: Int, onDone: () -> Unit) {
        viewModelScope.launch {
            val before = withContext(Dispatchers.IO) {
                runCatching { timeline.watermark(sessionId).localAcked }
                    .onFailure {
                        // 读水位失败**必须留痕**：静默回落 0 会让这一轮退化成
                        // `from=0`，中继判定「同一个 afterSeq 反复问」直接 429，
                        // 手机侧只看到 sync-loop，真因被整个吞掉。
                        Diag.log("sync", "watermark ${shortId(sessionId)} 读取失败: ${it.javaClass.simpleName}: ${it.message}")
                    }
                    .getOrDefault(LocalAckedSeq(0))
            }
            v2api.sync(
                hostId = hostId,
                sessionId = sessionId,
                afterSeq = before,
                mode = "BACKGROUND_BATCH",
                priority = 10,
                pageByteBudget = if (attempt <= 1) RelayApiV2.DEFAULT_PAGE_BUDGET else RelayApiV2.DEFAULT_PAGE_BUDGET / 4,
            ) { page, error, _ ->
                if (hostId != lastHostId) {
                    syncs.reset()
                    onDone()
                    return@sync
                }
                if (error != null) {
                    Diag.log("sync", "v2 session ${shortId(sessionId)} failed: $error")
                    syncs.finish(sessionId)
                    when (error) {
                        "sync-unsupported" -> {
                            // 中继太老，没有 /v2/sync。没有 v1 兜底了：记一笔就停，
                            // 反复重试只会把「同步中」永远挂在界面上。
                            Diag.log("sync", "relay has no /v2/sync; this build needs a newer relay")
                            syncs.finish(sessionId)
                            onDone()
                        }
                        else -> {
                            backoffRetry(hostId, sessionId, attempt)
                            onDone()
                        }
                    }
                    return@sync
                }
                if (page == null) {
                    syncs.finish(sessionId)
                    backoffRetry(hostId, sessionId, attempt)
                    onDone()
                    return@sync
                }
                viewModelScope.launch {
                    // 第二个返回值是「这批真的落盘了」。游标是**算**出来的，写没写成
                    // 与它无关：把落盘失败也当成推进，会让调用方以为有进展而不退避，
                    // 于是同一条 from 反复问下去（中继 429、手机 sync-loop）。
                    val (landed, committed) = withContext(Dispatchers.IO) {
                        val landedMax = page.records.maxOfOrNull { it.seq } ?: 0L
                        val next = SyncCoordinator.advance(before.value, page.nextCursorSeq, landedMax)
                        val old = runCatching { timeline.watermark(sessionId) }.getOrNull()
                        val watermarks = SessionWatermarks(
                            archiveCovered = ArchiveCoveredSeq(
                                maxOf(old?.archiveCovered?.value ?: 0L, page.archiveCoveredSeq),
                            ),
                            localAcked = next,
                        )
                        var failed = false
                        val wrote = runCatching {
                            timeline.commit(sessionId, page.records, watermarks)
                        }.onFailure {
                            failed = true
                            // 落盘失败同样不能静默：`wrote=0` 与「服务端一条都没给」
                            // 在日志里长得一模一样，只有把异常打出来才分得清。
                            Diag.log("sync", "commit ${shortId(sessionId)} 失败: ${it.javaClass.simpleName}: ${it.message}")
                        }.getOrDefault(0)
                        Diag.log(
                            "sync",
                            "v2 session ${shortId(sessionId)} from=${before.value} records=${page.records.size} " +
                                "landed=$landedMax next=${next.value} wrote=$wrote hasMore=${page.hasMore} " +
                                "stop=${page.stopReason}",
                        )
                        next to !failed
                    }
                    if (hostId != lastHostId) {
                        onDone()
                        return@launch
                    }
                    val progressed = committed && landed.value > before.value
                    if (!committed) {
                        // 落盘抛了：游标算得再大也没意义，这一轮按零进展退避。
                        Diag.log("sync", "session ${shortId(sessionId)} 落盘失败，按零进展退避")
                    }
                    if (progressed) {
                        zeroProgress.remove(sessionId)
                        // `invalidatePreview` 顺带松开闩锁，所以紧接着的 `enqueue` 一定
                        // 成功；写严格条件而不是无条件排队，是为了将来万一有人把释放
                        // 那句删掉时不至于变成「每次进度都排一次、每次都排不进去」。
                        invalidatePreview(sessionId)
                        if (warmQueue.enqueue(hostId, sessionId)) scheduleWarm()
                    }
                    val dirty = syncs.finish(sessionId)
                    when {
                        // 零进展：服务端说还有，但一条也没落盘。退避，别原地打转。
                        page.hasMore && !progressed -> backoffSessionSync(hostId, sessionId, landed.value)
                        page.hasMore && syncs.begin(sessionId) -> launchSessionSync(hostId, sessionId, attempt = 1)
                        dirty && syncs.begin(sessionId) -> launchSessionSync(hostId, sessionId, attempt = 1)
                    }
                    onDone()
                }
            }
        }
    }

    /** 零进展：把重试推到几秒之后，别把电量全烧在一个确定要不到的东西上。 */
    private fun backoffSessionSync(hostId: String, sessionId: String, cursor: Long) {
        val next = (zeroProgress[sessionId] ?: 0) + 1
        zeroProgress[sessionId] = next
        val wait = SyncPlanner.backoffMs(next)
        Diag.log("sync", "session ${shortId(sessionId)} zero progress (cursor=$cursor) retry in ${wait}ms")
        viewModelScope.launch {
            delay(wait)
            if (hostId != lastHostId) return@launch
            requestSessionSync(hostId, sessionId)
        }
    }

    /** 请求失败的有界退避（1/2/4/8 秒封顶）。 */
    /**
     * 失败重试。
     *
     * <p>**必须把 attempt 传下去**：早先这里把 attempt 丢掉、重新从 1 开始，
     * 退避窗口永远是 1 秒，一次 429 风暴就变成「每秒重试一次、永远不停」——
     * 实测一口气打出 337 次 429，而中继的限流窗口是 60 秒，越重试恢复得越慢。
     * 现在重试次数本身也封顶：真到上限就放弃，等下一次列表刷新/变更事件再来。
     */
    private fun backoffRetry(hostId: String, sessionId: String, attempt: Int) {
        if (attempt >= SYNC_MAX_ATTEMPTS) {
            Diag.log("sync", "session ${shortId(sessionId)} 连续失败 $attempt 次，交给下一次触发")
            return
        }
        val wait = SyncPlanner.backoffMs(attempt)
        viewModelScope.launch {
            delay(wait)
            if (hostId != lastHostId) return@launch
            requestSessionSync(hostId, sessionId, attempt + 1)
        }
    }

    /**
     * 会话「当前进行到哪」。
     *
     * `Model.Session.lastSeq` 只在 SSE 变更回调里被填过，从会话列表（rpc/list）
     * 或归档条目（/sync 的 items）构造出来的会话它一直是 0。而隐藏墓碑的比较
     * 用的就是它——于是 `isHidden(currentSeq = 0)` 永远判「没新消息」，本机删除
     * 过的会话再也不回来（2026-10-27 验收实测：删掉后连发几轮消息，列表始终
     * 「还没有会话」）。同步游标记的正是最新 seq，两者取大才是真实水位。
     */
    private fun currentSeq(session: Model.Session): Long =
        maxOf(session.lastSeq, cache.archiveCursor(lastHostId, session.id))

    /** 本机过滤：隐藏（仅本机删除）、空会话都不进列表；并套上已读时刻与首行预览。 */
    private fun filterVisible(sessions: List<Model.Session>): List<Model.Session> {
        if (lastHostId.isEmpty()) return withReadAt(sessions, applyPreview = false)
        return withReadAt(
            sessions.filter { s ->
                if (cache.isHidden(lastHostId, s.id, currentSeq(s))) return@filter false
                if (isSessionEmpty(s)) return@filter false
                true
            },
            applyPreview = true,
        )
    }

    /**
     * 会话列表这一行的首行预览：**用本地已落盘的消息现算**。
     *
     * 2026-10-28 之前这里是宿主/插件算好、经中继 snippet 发下来的。那条旁路和正文
     * 是两条独立水位，于是出现过「列表里已经看到最新一行，点进会话却没有这条回复」。
     * 现在列表与正文同源：正文到哪，预览就到哪；没拉到过正文就没有预览（宁缺勿错）。
     *
     * 内存里存一份算好的结果：`filterVisible` 每次刷新都要给每行取一次，而
     * `loadMessages` 要把整段消息 JSON 解出来，十几个会话乘几百条消息扛不住。
     * 消息一有变化就由 [onMessagesChanged] 用内存里的最新列表覆盖（`saveMessages`
     * 是异步落盘的，不能「写完再读盘」——那样读到的是上一版）。
     */
    private val previewCache = HashMap<String, String>()

    /** 首行预览预热队列；闩锁由数据变化释放、出队不解锁，见 [WarmQueue]。 */
    private val warmQueue = WarmQueue()

    private var warmJob: Job? = null

    private fun previewOf(hostId: String, sessionId: String): String {
        if (hostId.isEmpty() || sessionId.isEmpty()) return ""
        val key = "$hostId/$sessionId"
        // 内存里有就直接用：这是最近一次真正流到界面的那段正文算出来的。
        previewCache[key]?.let { return it }
        // 只有两个来源能进 `previewCache`：预热（Room 里的最新助手正文）和
        // `onMessagesChanged`（活跃聊天页内存里的正文）。
        //
        // **这里曾经还有一级：`cache.loadMessages()` 读 `files/messages/*.json`。**
        // 那是 v1 时代的整份消息文件，唯一写入点是 `ChatViewModel.persist()`，也就
        // 是说它只在「进过聊天页」时才更新。它有两个致命后果（2026-11-02 用户报
        // 「列表页预览一直停在旧文案」）：
        //   1. 命中就 `return`，**永远走不到下面的 Room 预热**——会话在后台跑完一
        //      整轮，只要没再进过聊天页，预览就一直是上次退出时的那句话；
        //   2. 它还把旧文本回填进 `previewCache`，于是 `invalidatePreview()` 清掉
        //      缓存后，下一帧又从文件读回同一份旧文本重新缓存 —— **自锁**，连
        //      「数据变了就重算」这条通路都被堵死。
        // 顺带它是**整文件读 + 全量 JSON 反序列化**，而 `previewOf` 在 `withReadAt`
        // 里对每一行都调一次：冷启动 50 个会话就是主线程上 50 次全量反序列化。
        //
        // 删掉这一级不会让列表变白：`withReadAt` 取到空串时**保留 prefs 里的旧
        // preview**（`fresh.ifEmpty { s.preview }`），所以第一帧显示的是上次落盘的
        // 值，预热回来再换成最新——用户看到的只是「过一会儿自己更新了」。
        //
        // 预热本身**绝不同步查 Room**：`previewOf` 在列表刷新时对每一行都调一次，
        // 主线程上串 50 次数据库往返就是一次可见的卡顿，弱机上直接 ANR。这里只
        // 排队，`warmPreviews()` 在 IO 线程一次查完再统一刷新。
        if (warmQueue.enqueue(hostId, sessionId)) scheduleWarm()
        return ""
    }

    /**
     * 预热队列：把「缓存里没有预览」的会话攒起来，在 IO 线程一次查完。
     *
     * <p>攒批而不是每行一个协程：冷启动 50 个会话就是 50 个协程抢同一个 Room 连接，
     * 反而更慢；而 `timeline.latestAssistant` 是索引点查，一次循环串行跑完总共也就
     * 几十毫秒。
     */
    private fun scheduleWarm() {
        if (warmJob?.isActive == true) return
        warmJob = viewModelScope.launch {
            // 让本帧的列表先画出来。预热只是为了补内容，不该跟首帧抢主线程。
            delay(120)
            // 循环到队列空为止，而不是「一次取一批就退出」：预热期间随时可能有新的
            // 变更帧进来排队，一次性快照会把它们留在队列里而没人再来取
            // （`warmJob.isActive` 仍是 true，`scheduleWarm()` 当场返回），那一批预览
            // 就永久停在旧值上。
            while (true) {
                val host = lastHostId
                if (host.isEmpty()) return@launch
                // 出队只取待办、不解锁（闩锁由数据变化释放，见 [WarmQueue] 的 KDoc）。
                val pending = warmQueue.dequeue(host)
                if (pending.isEmpty()) return@launch
                val found = HashMap<String, String>(pending.size)
                withContext(Dispatchers.IO) {
                    for (id in pending) {
                        val head = runCatching { timeline.latestAssistant(id)?.previewHead() }.getOrNull()
                        if (!head.isNullOrEmpty()) found[id] = head
                    }
                }
                if (found.isEmpty()) continue
                found.forEach { (id, head) -> previewCache["$host/$id"] = head }
                update { state ->
                    var touched = false
                    val next = state.sessions.map {
                        val head = found[it.id] ?: return@map it
                        if (it.preview == head) return@map it
                        touched = true
                        it.copy(preview = head)
                    }
                    if (touched) {
                        // 留一条可核对的证据：「列表页预览到底算没算过」。没有它，
                        // 这一类问题只能靠截图猜（2026-11-02 先猜了半天的假宿主）。
                        Diag.log("preview", "warm host=${shortId(host)} refreshed=${found.size}")
                        state.copy(sessions = next)
                    } else {
                        state
                    }
                }
            }
        }
    }

    /** 内存里记下这一行刚算出的预览（落盘是异步的，不能写完再读盘）。 */
    private fun rememberPreview(hostId: String, sessionId: String, messages: List<Model.Message>): String {
        val text = firstLinePreview(messages)
        if (hostId.isNotEmpty() && sessionId.isNotEmpty()) previewCache["$hostId/$sessionId"] = text
        return text
    }

    /**
     * v2 通路落盘了新记录：该会话的内存预览标记为失效，下次刷新列表重新取。
     *
     * <p>只在 `v2Active` 的聊天页会调到这里。清掉而不是直接算：新落盘的是历史中段
     * 也可能，而首行预览只看「最后一条助手正文」，重新算比猜便宜。
     */
    fun invalidatePreview(sessionId: String) {
        if (sessionId.isEmpty()) return
        previewCache.remove("$lastHostId/$sessionId")
        // 顺带松开预热闩锁：这一行有了新数据，必须允许再排一次队。漏掉这一句就是
        // 2026-11-02 那个「列表页预览永远不刷新」——闩锁只在批量同步那条路上被解开，
        // 单会话同步只 `add` 从不 `remove`，于是每个会话一辈子只可能被预热一次。
        warmQueue.release(lastHostId, sessionId)
    }

    /** 消息列表有变化（拉回差量、流式落盘）后重算这一行的预览并刷新列表。 */
    private fun onMessagesChanged(hostId: String, sessionId: String, messages: List<Model.Message>) {
        if (hostId.isEmpty() || sessionId.isEmpty()) return
        val text = rememberPreview(hostId, sessionId, messages)
        if (hostId != lastHostId) return
        update { state ->
            state.copy(
                sessions = state.sessions.map {
                    if (it.id == sessionId && it.preview != text) it.copy(preview = text) else it
                },
            )
        }
    }

    /**
     * 空会话判定：只有 workspace/context、没有真正对话的会话。
     *
     * 宿主 session/list **不给 messageCount**（v1 注释实测：全是 0）——原实现用
     * `messageCount<=0 && preview空` 判空会把全部会话过滤光，真机表现就是
     * 「还没有会话」（2026-10-26 踩坑）。可靠的信号只有两个：宿主给的 `blank`
     * 字段、以及本地是否已有已落盘的 user/assistant 消息。
     *
     * 2026-11-02：原先还参考过「归档集合」（`archivedSessionIds`），但那份数据
     * 来自 v1 `/sync` 的归档元数据；v1 路由整体删除后写入端已经不存在。归档会话
     * 目前在 v2 里没有数据来源，属于已知缺口（见 docs/pitfalls.md）。
     */
    private fun isSessionEmpty(s: Model.Session): Boolean {
        if (s.running) return false
        // 宿主直接标记的空会话（blank 字段）优先；v1 没有它只能靠多信号推断，
        // v2 的中继转发带着这个字段，直接用更可靠。
        if (!s.blank) return false
        // 新建会话给 10 分钟宽限，避免刚建出来就消失。
        if (s.updatedAt > 0 && System.currentTimeMillis() - s.updatedAt < 10 * 60 * 1000L) return false
        // 本地已有 user/assistant 消息 = 有真正对话。（预览是本地消息算出来的，
        // 不能反过来再当判空信号——那会变成同一个事实自己证明自己。）
        if (s.preview.isNotBlank()) return false
        for (m in cache.loadMessages(lastHostId, s.id)) {
            if (m.role == Model.Role.USER || m.role == Model.Role.ASSISTANT) return false
        }
        // 全程没有任何对话迹象，且宿主自己就标了 blank：空会话。
        return true
    }

    /**
     * 给一批会话套上已读时刻，让 unread 能判断。
     *
     * 首次安装先渲染的是缓存列表，这里同样要落基线；否则冷启动会先闪一屏绿点，
     * 等网络列表回来才变灰——用户看到的是「明明都读过了却全亮」。
     */
    private fun withReadAt(
        sessions: List<Model.Session>,
        applyPreview: Boolean,
    ): List<Model.Session> {
        if (sessions.isEmpty()) return sessions
        if (!cache.hasReadBaseline()) cache.seedReadBaseline(sessions)
        val map = cache.readAtMap()
        return sessions.map { s ->
            val read = map[s.id] ?: 0L
            if (!applyPreview) {
                s.copy(readAt = read)
            } else {
                // 取不到就**保留旧值**，不要覆盖成空串。
                //
                // 用户口径（2026-11-01）：「预览直接用上次拉取到的最新信息作为预览
                // 不行吗？预热再更新就好了」——原来这里无条件覆盖，而 `previewOf`
                // 在「内存没有 / v1 JSON 缓存没有 / Room 还没预热」三级全落空时返回
                // **空串**，于是 prefs 里存着的预览被当场抹白，等预热（delay 120ms +
                // Room 查询）回来才补。冷启动那几秒列表一片没有预览，看起来就像
                // snippet 又丢了。
                //
                // 旧值最多是上一轮的正文，仍是有用信息；空串什么都不是。
                val fresh = previewOf(lastHostId, s.id)
                s.copy(readAt = read, preview = fresh.ifEmpty { s.preview })
            }
        }
    }

    /**
     * 点「新建」：只开一屏本地草稿，**不碰宿主**。
     *
     * 以前这里立刻 `rpc/create`，结果是点一下就在电脑上多出一条空会话，还会被
     * 插件报进中继、推到别的设备上——用户根本没打算聊，列表却先脏了；顺带还要
     * 等一次网络往返才看得到输入框。改成延迟创建：草稿的 id 为空，发第一条消息
     * 时才真正 create 并补上 id（见 ChatViewModel.send）。
     */
    fun createSession(onReady: (Model.Session?) -> Unit) {
        val host = currentHost()
        if (host == null) {
            update { it.copy(note = "尚未选择主机") }
            onReady(null)
            return
        }
        onReady(
            Model.Session(
                id = "",
                title = "新对话",
                subtitle = host.alias,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /**
     * 草稿刚在宿主上落地：把真会话接进来。
     *
     * 换掉 activeSession 的 id 是不够的——首页列表来自缓存，不补一条进去，用户
     * 发完第一条消息返回列表时看不到自己刚聊的那一轮（归档还没推过来）。
     */
    fun onDraftCreated(created: Model.Session) {
        activeSession = created
        if (lastHostId.isEmpty()) return
        val merged = listOf(created) + cache.loadSessions(lastHostId).filterNot { it.id == created.id }
        cache.saveSessions(lastHostId, merged)
        update { it.copy(sessions = filterVisible(merged)) }
    }

    /** 让 note 在别处（ChatViewModel）也能上报；首页顶部会显示它。 */
    fun reportNote(text: String) {
        update { it.copy(note = text) }
    }

    /**
     * 本地已知的会话标题：内存列表优先，其次磁盘缓存。
     *
     * 聊天页顶栏必须**当场**就有标题——标题靠 `/rpc/projections` 拿的话，弱网下要等
     * 一整趟往返，用户先看到的是占位（2026-10-30 实测「打开会话先显示『新会话』，
     * 随后才变真标题」）。会话列表本来就有这条标题，没理由再去要一次。
     */
    fun titleOf(sessionId: String): String {
        if (sessionId.isEmpty()) return ""
        _state.value.sessions.firstOrNull { it.id == sessionId }?.let {
            if (it.title.isNotBlank()) return it.title
        }
        if (lastHostId.isEmpty()) return ""
        return cache.loadSessions(lastHostId).firstOrNull { it.id == sessionId }?.title ?: ""
    }

    /**
     * 工作区路径（顶栏副标题）。
     *
     * <p>**必须与 [titleOf] 一样有本地兜底**：顶栏副标题直接用 `session.workspace`，
     * 而它只有在 `/rpc/projections` 回来之后才有值。深链/通知进入会话时传进来的是
     * 一份只有 id 的壳，于是先空一段、几百毫秒后才冒出来——用户看到的就是
     * 「标题下方的 workspace 名加载一小会才显示」（2026-11-02 反馈）。
     *
     * <p>数据源与标题同源：内存列表 → prefs 里缓存的会话表。
     */
    fun workspaceOf(sessionId: String): String {
        if (sessionId.isEmpty()) return ""
        _state.value.sessions.firstOrNull { it.id == sessionId }?.let {
            if (it.workspace.isNotBlank()) return it.workspace
        }
        if (lastHostId.isEmpty()) return ""
        return cache.loadSessions(lastHostId).firstOrNull { it.id == sessionId }?.workspace ?: ""
    }

    // ============================================================
    // 待答卡片的本地账本
    //
    // 宿主在**每次建立 $events 新连接**时，会把所有仍挂着的 waterfall 重投一遍
    // （startRemoteEvent 末尾遍历 pendingRemoteEvents 逐个 deliver）。旧实现每进一次
    // 会话就重开那条流，于是没结算的请批跟着重来一次，看起来就是「一直在弹审批」。
    //
    // 现在 $events 只在开机时建一条，不再重开；卡片落在下面两个 map 里，打开会话
    // 时用 [replayInteractions] 回放。点过但没拿到回执的记在 [answeredEvents]，其
    // 期间的重投静默丢弃。
    // ============================================================

    private val interactions = InteractionLedger()

    /**
     * `eventId → interactionId`（`sessionId:seq`）。
     *
     * <p>回答一张卡的时候（`answerApproval`/`answerQuestions`）手上只有 `eventId`，
     * 而 Room 账本的主键是 `interactionId`。这张表把两者接起来，`forget`/`markResolved`
     * 才找得到那一行。来源只有两处：[noteInteraction]（新卡到达）与
     * [replayInteractions]（跨重启恢复）。
     */
    private val interactionIds = HashMap<String, String>()
    /**
     * 反向索引：`callId → eventId`。
     *
     * 归档里的 `approval/asked` 带 `callId` 但**不带** `$events` 那个 `eventId`（后者是网关
     * 在 `startRemoteEvent` 里现生成的另一个随机 UUID，与 `approval/asked` 的 `id` 无关）。
     * 两边都带 `callId`，所以只能靠它对账。没有这张对照表，从归档重建出来的
     * 卡片就只能看不能点。
     */
    private val eventIdByCallId = HashMap<String, String>()

    /**
     * 打开会话时把仍在等的卡片交给它（只交属于这个会话的）。
     *
     * <p>内存账本 + Room 账本各回放一次：内存那份是本次进程里刚收到的（最新，
     * 带 UI 侧还没提交的草稿状态），Room 那份是上个进程留下的（跨重启还在等）。
     * 两边同一 eventId 的卡会被 [ChatViewModel.onApproval] 去重，所以顺序只影响
     * 「谁的内容更旧」，不影响正确性。
     */
    fun replayInteractions(bridge: ChatViewModel) {
        val sid = bridge.currentSessionId()
        for (approval in interactions.approvalsFor(sid)) bridge.onApproval(approval)
        for (card in interactions.questionsFor(sid)) bridge.onQuestion(card)
        viewModelScope.launch {
            for (entry in runCatching { timeline.pendingInteractions(sid) }.getOrDefault(emptyList())) {
                val restored = InteractionCodec.decode(entry) ?: continue
                // 账本里的卡一律是「不能答」的：`InteractionCodec` 不持久化可回答性，
                // 而宿主那边挂起的 waterfall 只活在内存 `pendingRemoteEvents` 里，
                // 进程一换（或那轮已收尾）就没了。于是：
                //
                // 早先这里无条件回放，结果是**死锁**——卡显示出来但按钮全灰（不可答），
                // 用户点不动 ⇒ 永远走不到 markResolved ⇒ 账本里那条永远 PENDING ⇒
                // 每次进会话都再回放一次，永久占着悬浮层的 420dp，把下面的消息全挡住
                // （2026-11-01 用户实测）。
                //
                // 现在的规矩：**只有能答的卡才进界面**。不能答的直接把账本标结算，
                // 让它彻底退场——已经过去的事不该占屏幕。
                if (restored.eventId.isNotEmpty()) rememberInteractionId(restored.eventId, entry.interactionId)
                runCatching { timeline.markResolved(sid, entry.interactionId) }
            }
        }
    }

    /**
     * 落盘一张卡（跨重启的审批账本，v2 §8）。
     *
     * <p>写在 Room 里而不是只留内存：宿主在**每次新连接**上都会重投未结算的
     * waterfall，但如果手机进程被杀、重连前那段窗口里用户正好点过一张卡，纯内存的
     * 账本就丢了「点过」这件事，卡片会复活。`interactionId` 用 `sessionId:seq`
     * （严禁时间戳——重投时必须是同一个 id）。
     */
    fun noteInteraction(sessionId: String, card: Model.Interaction, seq: Long) {
        if (sessionId.isEmpty() || card.eventId.isEmpty()) return
        val entry = InteractionCodec.encode(sessionId, card, seq, System.currentTimeMillis()) ?: return
        rememberInteractionId(card.eventId, entry.interactionId)
        rememberEventIdOfCall(card)
        viewModelScope.launch { runCatching { timeline.noteInteraction(entry) } }
    }

    /**
     * 记下 `callId → eventId`，让归档重建出来的卡片能找回可回答的那个 id。
     *
     * 只有 `$events` 那条路径上的 `eventId` 能被宿主认（`receiveRemoteEventResult` 按
     * 它查 `pendingRemoteEvents`），归档里的 `sessionId:seq` 不行。
     */
    private fun rememberEventIdOfCall(card: Model.Interaction) {
        val callId = when (card) {
            is Model.Approval -> card.callId
            is Model.QuestionCard -> card.callId
            else -> ""
        }
        if (callId.isNotEmpty() && card.eventId.isNotEmpty()) eventIdByCallId[callId] = card.eventId
    }

    /** 归档卡片要回答时用：按 `callId` 取回可回答的 `eventId`（取不到就是空串）。 */
    fun eventIdOfCall(callId: String): String =
        if (callId.isEmpty()) "" else eventIdByCallId[callId] ?: ""

    /** 记下 `eventId → interactionId`，让按 `eventId` 触发的动作用得到 Room 那一行。 */
    private fun rememberInteractionId(eventId: String, interactionId: String) {
        if (eventId.isNotEmpty() && interactionId.isNotEmpty()) interactionIds[eventId] = interactionId
    }

    /** 点过或已结算过的 eventId：重投回来的同一张卡不再画、也不再发通知。 */
    fun isAnswering(eventId: String): Boolean = interactions.isAnswering(eventId)

    /**
     * 用户点了这张卡。
     *
     * <p>两处一起记：内存账本挡本次进程里的重投，Room 账本挡**下一次启动**之后的重投。
     * 只做内存那份的话，进程正好在「点了、还没收到回执」之间被杀，宿主重连就会把这张
     * 卡再推一遍，用户看到自己刚点掉的审批又冒出来了。
     */
    fun markAnswering(eventId: String) {
        interactions.markAnswering(eventId)
        val interactionId = interactionIds[eventId] ?: return
        viewModelScope.launch { runCatching { timeline.markClicked(sessionIdOf(interactionId), interactionId) } }
    }

    /** 宿主确认结算：Room 账本转 RESOLVED，之后的重投一律静默。 */
    fun markInteractionResolved(eventId: String) {
        val interactionId = interactionIds[eventId] ?: return
        viewModelScope.launch { runCatching { timeline.markResolved(sessionIdOf(interactionId), interactionId) } }
    }

    /** 回答没送达：撤销记账，让这张卡还能重新出现（内存与 Room 都要撤）。 */
    fun forgetAnswering(eventId: String) {
        interactions.forget(eventId)
        val interactionId = interactionIds[eventId] ?: return
        viewModelScope.launch { runCatching { timeline.forgetInteraction(sessionIdOf(interactionId), interactionId) } }
    }

    /** 宿主 cancel 帧：卡片作废，Room 账本转 CANCELLED。 */
    fun cancelInteraction(eventId: String) {
        interactions.cancel(eventId)
        val interactionId = interactionIds[eventId] ?: return
        viewModelScope.launch { runCatching { timeline.forgetInteraction(sessionIdOf(interactionId), interactionId, cancelled = true) } }
    }

    /** `interactionId` 是 `"${sessionId}:${seq}"`；会话 id 自身不含冒号，取第一段即可。 */
    private fun sessionIdOf(interactionId: String): String = interactionId.substringBefore(':')

    /** 上报一条「刚做完某事」的结果，首页灰字显示（不是错误）。 */
    fun reportInfo(text: String) {
        update { it.copy(info = text, note = "") }
    }

    /** 弹一条 toast（一次性结论，如「已是最新版本」）；[AppRoot] 弹完就清空。 */
    fun showToast(text: String) {
        update { it.copy(toast = text) }
    }

    /** Toast 已弹出：清掉，避免重组时重复弹。 */
    fun consumeToast() {
        if (_state.value.toast.isEmpty()) return
        update { it.copy(toast = "") }
    }

    /**
     * 首页长按删除（v1 MainActivity.deleteSession 一比一）：**只从本机列表删除**，
     * 不调用宿主、不删电脑端任何数据；只记一条「隐藏到 seq N」的墓碑，宿主的
     * 新消息把 seq 推进后会话会自动重新出现。
     *
     * 调用方负责先弹确认（v1 文案见 HomeScreen 的长按确认弹层）。
     */
    fun deleteSession(session: Model.Session) {
        // 本机隐藏：有新消息时重新出现（不调宿主——宿主侧删除由聊天页「更多→删除」负责）。
        cache.hideSession(lastHostId, session.id, currentSeq(session))
        update { s ->
            s.copy(
                sessions = s.sessions.filterNot { it.id == session.id },
                info = "已从本机删除",
            )
        }
    }

    /**
     * 聊天页「更多 → 删除」。
     *
     * DSH 至今没有 session/delete 端点（v1 的 gateway.deleteSession 也只是
     * `reply.fail("会话删除接口尚未实现")`），所以这里退化成和首页长按同一语义：
     * **只从本机列表删掉**，电脑端一个字都不动。会话再有新消息会自己回来。
     *
     * 之前这里原样调用那个必然失败的接口、失败又只写进 note——而聊天页不显示
     * note，用户点完「删除」看到的就是弹层关掉、什么都没发生（2026-10-27 验收
     * 实测）。宁可做能力范围内确定的事，也不要留一个假菜单项。
     */
    fun deleteSessionOnHost(session: Model.Session, onDone: () -> Unit) {
        cache.hideSession(lastHostId, session.id, currentSeq(session))
        update { s ->
            s.copy(
                sessions = s.sessions.filterNot { it.id == session.id },
                info = "已从本机删除；电脑端未改动",
            )
        }
        onDone()
    }

    /** 重命名会话（v1 promptRename）。 */
    fun renameSession(session: Model.Session, title: String, onDone: () -> Unit) {
        val name = title.trim()
        if (name.isEmpty()) return
        gateway.renameSession(session.id, name) { _, error ->
            if (error != null) {
                update { it.copy(note = error) }
                return@renameSession
            }
            activeSession = activeSession?.copy(title = name)?.takeIf { it.id == session.id }
                ?: activeSession
            update { s ->
                s.copy(sessions = s.sessions.map { if (it.id == session.id) it.copy(title = name) else it })
            }
            // 聊天页顶栏的标题来自 ChatViewModel 自己那份 session 快照，不会随
            // 列表刷新自动更新——不在这里推一次，改完名字还挂着旧标题，用户会
            // 以为改名没生效。
            chatBridge?.onTitleChanged(session.id, name)
            onDone()
        }
    }

    // ============================================================
    // 排序 / 筛选（v1 SortMode）
    // ============================================================

    /** 切换排序/筛选模式；落盘记住（v1 rememberSortMode）。 */
    fun setSortMode(mode: Model.SortMode) {
        cache.setSortMode(mode)
        update { it.copy(sortMode = mode) }
    }

    /** 恢复上次的排序模式（冷启动调；SessionCache 已带默认值）。 */
    private fun restoreSortMode(): Model.SortMode = cache.sortMode()

    // ============================================================
    // 搜索（v1 LocalSearch 一比一移植 + 宿主兜底）
    // ============================================================

    fun openSearch() {
        update {
            it.copy(
                searchOpen = true, searchQuery = "", searchHitList = emptyList(),
                searchDone = false, searchNote = "",
            )
        }
    }

    fun closeSearch() {
        update {
            it.copy(
                searchOpen = false, searchQuery = "", searchHitList = emptyList(),
                searchDone = false, searchNote = "",
            )
        }
    }

    /**
     * 执行搜索（v1 runSearch）：**先搜本地缓存**，有结果立刻显示；本地没有才问宿主。
     *
     * <p>为什么本地优先：宿主 session/search 依赖 session-query 索引，很多部署里是
     * `openAt: "never"`（直接回 SessionQueryError），把英文报错弹给用户毫无意义。
     */
    fun runSearch(query: String) {
        val text = query.trim()
        if (text.isEmpty()) return
        val scope = _state.value.sessions
        val cached = mutableMapOf<String, List<Model.Message>>()
        for (s in scope) {
            val messages = cache.loadMessages(lastHostId, s.id)
            if (messages.isNotEmpty()) cached[s.id] = messages
        }
        val local = LocalSearch.find(text, scope, cached)
        if (local.isNotEmpty()) {
            searchHits = local
            update {
                it.copy(
                    searchQuery = text,
                    searchHitList = local,
                    searchDone = true,
                    searchNote = "",
                )
            }
            return
        }
        // 本地没有 → 问宿主；宿主未实现/未开启时按原因给用户能读懂的文案。
        gateway.searchSessions(text) { hits, error ->
            if (error != null) {
                update {
                    it.copy(
                        searchQuery = text,
                        searchHitList = emptyList(),
                        searchDone = true,
                        searchNote = LocalSearch.hostSearchHint(error),
                    )
                }
                return@searchSessions
            }
            val found = hits ?: emptyList()
            update {
                it.copy(
                    searchQuery = text,
                    searchHitList = found,
                    searchDone = true,
                    searchNote = if (found.isEmpty()) "没有匹配的会话" else "",
                )
            }
        }
    }

    /** 从搜索结果进入会话（v1 openSessionById）：列表里没有的会话用精简 Session 占位。 */
    fun openSessionById(sessionId: String, title: String) {
        if (sessionId.isEmpty()) return
        val existing = _state.value.sessions.firstOrNull { it.id == sessionId }
        val target = existing ?: Model.Session(id = sessionId, title = title.ifBlank { "对话" })
        openSession(target)
    }

    /**
     * 聊天页「更多 → 后台任务」。
     *
     * 任务接口在宿主侧还没有（v1 同样只有屏没有数据），但入口不能点了什么都不发生：
     * 打开一页说明「接口未就绪、就绪后这里会显示什么」，按返回键退回来。
     */
    fun openBackgroundTasks() {
        update { it.copy(route = Route.TASKS) }
    }

    fun markSessionRead(session: Model.Session) {
        if (session.id.isEmpty()) return
        // 已读时刻至少到这条会话的最后活动：否则刚打开就又被判回未读。
        val now = maxOf(System.currentTimeMillis(), session.updatedAt)
        cache.markRead(session.id, now)
        // 当前正看着的会话副本也要一起改，不然是「关着又打开才发现已读」。
        if (activeSession?.id == session.id) activeSession = session.copy(readAt = now)
        update { s ->
            s.copy(sessions = s.sessions.map {
                if (it.id == session.id) it.copy(readAt = now) else it
            })
        }
    }

    /**
     * 宿主推来新的任务清单：同时更新首页那一份与当前打开的会话副本。
     *
     * 为什么不让胶囊只读 ChatViewModel 的 state：首页不常显任务，但**切走再切回**
     * 时 ChatViewModel 是新建的，`open()` 先铺 `activeSession`，那份要是旧的就
     * 会先闪一下旧进度。两边同源，任何时刻都对。
     *
     * 不落 `cache.saveSessions`：任务清单变化频繁（一轮好几次），而会话列表落盘是
     * 整表写。胶囊的持久化由 ChatViewModel.persist() 随消息一起走。
     */
    fun syncTodos(sessionId: String, todos: List<Model.Todo>) {
        if (sessionId.isEmpty()) return
        if (activeSession?.id == sessionId) {
            activeSession = activeSession?.copy(todos = todos)
        }
        update { s ->
            s.copy(sessions = s.sessions.map {
                if (it.id == sessionId) it.copy(todos = todos) else it
            })
        }
    }

    // ============================================================
    // 会话详情
    // ============================================================

    fun openSession(session: Model.Session) {
        markSessionRead(session)
        activeSession = session
        update { it.copy(route = Route.CHAT) }
    }

    fun leaveChat() {
        activeSession = null
        chatBridge?.close()
        chatBridge = null
        update { it.copy(route = Route.HOME) }
        // 草稿会话从未在宿主上创建：回列表时照样刷一次，它要么已经变成真会话
        // （发过消息），要么本来就不该出现在列表里（没发就走）。
        // 静默：列表已有内容，弹加载图标只会闪一下（2026-10-28 用户反馈）。
        loadSessions(silent = true)
    }

    /**
     * 从后台任务页退回聊天页。
     *
     * 走 leaveChat 会顺带关掉 chatBridge 回首页；这里只换路由，会话与订阅都留着。
     */
    fun leaveTasks() {
        update { it.copy(route = Route.CHAT) }
    }

    // ============================================================
    // 导航
    // ============================================================

    fun openSettings() {
        refreshRelayState()
        // 顺手强制刷一次主机列表：设置页的「设备管理」绿点是在线状态，只能来自中继
        // 这次的实时答复。走 3 分钟 TTL 的话，电脑刚刚关机也会继续显示在线
        // （2026-10-29 用户直报「云端需要探测主机在线状态」）。
        loadHosts(true)
        update { it.copy(route = Route.SETTINGS) }
    }

    fun backToHome() {
        update { it.copy(route = Route.HOME) }
    }

    // ============================================================
    // 设置页：中继状态 / 偏好 / 设备管理
    // ============================================================

    /** 刷新中继状态与偏好快照（两者都从网关本地构造，无网络往返）。 */
    fun refreshRelayState() {
        gateway.relayStatus { value, _ ->
            if (value != null) {
                update { it.copy(relay = value, prefs = gateway.preferences()) }
            }
        }
    }

    fun savePreferences(prefs: Model.Preferences) {
        val previous = _state.value.prefs
        gateway.savePreferences(prefs)
        update { it.copy(prefs = gateway.preferences()) }
        // 「中继为本设备传输附件」的真值在服务端，本地那次只写进了缓存。
        // 真正生效必须走一次中继；失败就把缓存退回旧值——开关停在「已开启」
        // 而中继其实在拦字节，比直接报错更难查。
        if (prefs.transferAttachments != previous.transferAttachments) {
            gateway.setAttachments(prefs.transferAttachments) { applied, error ->
                if (error != null || applied == null) {
                    gateway.savePreferences(previous)
                    update {
                        it.copy(
                            prefs = gateway.preferences(),
                            note = error ?: "附件传输开关未能保存",
                        )
                    }
                } else {
                    update { it.copy(prefs = gateway.preferences()) }
                }
            }
        }
    }

    /**
     * 把中继侧的附件策略拉回来覆盖本地缓存。
     *
     * 进设置页与登录后各拉一次：这个开关按设备记在服务端，本机缓存可能是
     * 另一台设备/上一次会话留下的旧值，不拉就会显示成错的。
     */
    fun refreshAttachmentPolicy() {
        if (!gateway.hasToken()) return
        gateway.loadAttachments { _, _ ->
            update { it.copy(prefs = gateway.preferences()) }
        }
    }

    /** 轮换令牌：配对码换来的长期凭据可以主动换新。 */
    fun rotateToken(onDone: (String?) -> Unit = {}) {
        gateway.rotateToken { _, error ->
            refreshRelayState()
            onDone(error)
        }
    }

    fun unpair(hostId: String) {
        gateway.unpair(hostId) { _, error ->
            if (error != null) {
                update { it.copy(note = error) }
                return@unpair
            }
            cache.forgetHost(hostId)
            if (hostId == lastHostId) {
                val next = cache.loadHosts().firstOrNull { it.authorized }
                gateway.selectHost(next)
                lastHostId = next?.id ?: ""
                if (next != null) cache.setLastHost(next.id, next.alias)
                loadSessions()
            }
            loadHosts(true)
        }
    }

    /** debug：清空同步游标后回首页，直观看到后台重新下载。 */
    fun forceSync() {
        cache.clearArchiveCursors(lastHostId)
        backToHome()
        loadSessions()
    }

    // ============================================================
    // 版本管理（v1 MainActivity 版本组一比一移植）
    // ============================================================

    /** 是否已拿到「安装未知应用」授权（API<26 恒 true）。 */
    fun canInstallPackages(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 26) return true
        return getApplication<Application>().packageManager
            .canRequestPackageInstalls()
    }

    /**
     * 从系统「安装未知应用」设置页回来时刷新权限状态。
     *
     * 那个开关只能用户自己去系统设置里开，开完回来界面不会自己变——不刷新的话，
     * 「去开启安装权限」会一直挂着，用户以为没生效（2026-10-28 反馈）。
     * 权限已开就翻回「下载并安装」；还没开就保持原样，不打扰。
     */
    fun refreshInstallPermission() {
        if (!_state.value.installPermissionMissing) return
        if (!canInstallPackages()) return
        update { it.copy(installPermissionMissing = false) }
    }

    /** 去系统设置开「允许本应用安装未知应用」（那个开关无法弹框代劳）。 */
    fun openInstallSettings() {
        val context = getApplication<Application>()
        try {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:${context.packageName}"),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.w("DSH", "openInstallSettings failed", e)
            update { it.copy(releaseError = "请到系统设置里允许本应用安装未知应用") }
        }
    }

    /**
     * 检查更新：拉版本清单，与当前版本比对。
     *
     * <p>清单现在**优先问当前连着的这台中继**（`/api/dsh-mobile-sh-apk/downloads/`）——
     * 安装包以后就由中继自己托管，发布是「本机构建 + 一条命令推到中继」，不再依赖
     * 一个额外的静态站点。中继没发过任何版本（404 `release-missing`）或这台中继不可达
     * （自建、临时关机、手机上还留着一台已经拆掉的地址）时，回落旧静态地址，所以
     * 「中继挂了」不会变成「更新功能永久失效」。
     *
     * 结果语义：发现新版→pendingRelease（红字+按钮）；已是最新→toast；失败→releaseError。
     * 防重入：checking=true 时直接返回。
     */
    /**
     * 按「当前中继 → 旧静态地址」的顺序取版本清单，第一个成功就收。
     *
     * <p>为什么要回落而不是只问中继：中继是用户自建的，它会关机、会换地址、会还是个
     * 刚起好的空库（404 `release-missing`）。这些都只该让「更新暂时查不到」退化，
     * 不该让「检查更新」这个按钮看起来坏了。
     */
    private fun fetchReleaseWithFallback(consume: (Model.Release?, String?) -> Unit) {
        val candidates = releaseManifestCandidates(gateway.relayOrigin())
        var lastError: String? = null
        fun attempt(index: Int) {
            if (index >= candidates.size) { consume(null, lastError); return }
            gateway.fetchRelease(candidates[index]) { release, error ->
                if (release != null) consume(release, null) else { lastError = error; attempt(index + 1) }
            }
        }
        attempt(0)
    }

    fun checkForUpdate() {
        if (_state.value.releaseChecking) return
        update {
            it.copy(
                releaseChecking = true, releaseNote = "", releaseError = "",
                installPermissionMissing = false,
            )
        }
        fetchReleaseWithFallback { release, error ->
            if (release == null) {
                update { it.copy(releaseChecking = false, releaseError = "检查失败：${error ?: "未知错误"}") }
                return@fetchReleaseWithFallback
            }
            val current = getApplication<Application>()
                .let { ctx -> ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" }
            if (release.forcesUpgrade(current)) {
                // 当前版本低于 minSupported：必须升级，红字强调。
                update {
                    it.copy(
                        releaseChecking = false, pendingRelease = release,
                        releaseNote = "发现新版本 ${release.latest}（当前版本过低，必须升级）",
                    )
                }
                return@fetchReleaseWithFallback
            }
            if (release.newerThan(current)) {
                update {
                    it.copy(
                        releaseChecking = false, pendingRelease = release,
                        releaseNote = buildString {
                            append("发现新版本 ").append(release.latest)
                            release.sizeLabel().takeIf { s -> s.isNotEmpty() }?.let { s -> append(" · ").append(s) }
                        },
                    )
                }
            } else {
                // 已是最新：结论一次性，用 toast；卡片上不留常驻文案（v1 同样做法）。
                update {
                    it.copy(
                        releaseChecking = false, pendingRelease = null,
                        releaseNote = "",
                    )
                }
                showToast("已是最新版本 $current")
            }
        }
    }

    /**
     * 下载并安装：先检查安装权限（无→提示去开），下载到外部缓存的 updates/
     * 目录（ApkProvider 只开这一处），完成后发 ACTION_VIEW 走系统安装器。
     */
    fun downloadAndInstall() {
        val release = _state.value.pendingRelease ?: return
        val context = getApplication<Application>()
        if (!canInstallPackages()) {
            // 不写进度也不下载：装着还会被系统直接拒掉，先把用户送去开权限。
            update { it.copy(installPermissionMissing = true, releaseError = "") }
            return
        }
        update { it.copy(installPermissionMissing = false) }
        val dir = java.io.File(context.getExternalCacheDir(), "updates")
        if (!dir.exists() && !dir.mkdirs()) {
            update { it.copy(releaseError = "无法创建更新目录") }
            return
        }
        // 文件名带上版本号：固定名字会让安装器对同一个 content:// 路径复用上一份
        // 包的解析结果，实测表现为「下的是 0.2.3，安装确认框里还写着 0.2.2」。
        // 换名字即换 URI，顺带保留上一版便于回滚排查。
        val target = java.io.File(dir, apkNameFor(release))
        dir.listFiles()?.forEach { old ->
            if (old.isFile && old.name != target.name && old.name.startsWith("dsh-mobile-")) {
                old.delete()
            }
        }
        update { it.copy(downloadPercent = -1, releaseError = "") }
        api.download(release.downloadUrl(), target, release.sha256, { done, total ->
            update {
                it.copy(
                    downloadPercent = if (total > 0) (done * 100 / total).toInt() else -1
                )
            }
        }) { _, error ->
            if (error != null) {
                update { it.copy(downloadPercent = -2, releaseError = error) }
                return@download
            }
            val problem = verifyDownloadedApk(target, release)
            if (problem != null) {
                target.delete()
                update { it.copy(downloadPercent = -2, releaseError = problem) }
                return@download
            }
            update { it.copy(downloadPercent = -2, releaseNote = "已下载，等待安装确认") }
            installDownloadedApk(target)
        }
    }

    /** 更新包落地文件名：`dsh-mobile-0.2.3.apk`；版本号里不安全字符剔掉。 */
    private fun apkNameFor(release: Model.Release): String {
        val safe = release.latest.trim().map { c ->
            if (c.isLetterOrDigit() || c == '.' || c == '-' || c == '_') c else '_'
        }.joinToString("")
        return "dsh-mobile-${safe.ifEmpty { "update" }}.apk"
    }

    /**
     * 安装前核对包内版本号。
     *
     * 下载链路（Cloudflare、运营商缓存、代理）完全可能给回上一版的文件，而系统
     * 安装器只会照包内信息弹「是否更新」，用户看到版本号没变会以为更新没生效。
     * 这里直接读 APK 的 manifest，版本对不上就不进安装器，并把真实版本报出来。
     */
    private fun verifyDownloadedApk(file: java.io.File, release: Model.Release): String? {
        val context = getApplication<Application>()
        val archive = try {
            context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                android.content.pm.PackageManager.GET_ACTIVITIES,
            )
        } catch (e: Exception) {
            android.util.Log.w("DSH", "getPackageArchiveInfo failed", e)
            null
        } ?: return "安装包已损坏，请重新下载"
        if (archive.packageName != context.packageName) {
            return "安装包不是本应用（${archive.packageName}），已丢弃"
        }
        val got = archive.versionName ?: ""
        if (!got.equals(release.latest, ignoreCase = true)) {
            Diag.log("update", "apk version mismatch want=${release.latest} got=$got")
            return "下载到的安装包是 $got，不是 ${release.latest}，请重试"
        }
        return null
    }

    /**
     * 装下好的 APK —— 走 PackageInstaller 会话，不再发 `ACTION_VIEW + content://`。
     *
     * ColorOS 会把安装 Intent 转手给 `com.oplus.appdetail`，`content://` 的读授权
     * 不跟随转手，provider 又不可导出，结果安装器读完权限校验就静默退出：
     * 下载成功、安装确认一闪、版本号还是旧的。会话安装由系统直接读字节，绕开这一层。
     */
    private fun installDownloadedApk(target: java.io.File) {
        val context = getApplication<Application>()
        ApkInstaller.install(context, target) { error ->
            if (error != null) {
                update { it.copy(downloadPercent = -2, releaseError = error) }
            }
        }
    }

    // 健康状态翻转（令牌被 401 打回等）：顶栏立刻改口，别让状态停在「已连接」。
    init {
        gateway.onHealthChanged = { refreshRelayState() }
        startTimelineGc()
        migrateLegacyCache()
    }

    /**
     * 一次性迁移：把 v1 时代 prefs 里的正文清掉，只留小的键值事实。
     *
     * <p>v2 的时间线在 Room 里，prefs 里那份是**另一个副本**。两份并存不是省空间
     * 而是会产生分歧：列表首行预览可能来自一条聊天页已经没有的消息。所以正文
     * （每会话一个 JSON 文件 + `messageMeta` 水位索引 + 老版本写在 prefs 里的
     * `messages:*` 键）整套删除，**旧游标一律不迁**——`local_acked_seq` 全置 0，
     * 由 `/v2/sync` 重拉（AGENTS.md 坑 2：元数据水位从没证明过正文在盘上）。
     *
     * <p>保留的东西都是没有 v2 等价物、也没有正确性风险的：主机列表、草稿、
     * 排序、模型目录、已读位置、本机隐藏。放在 IO 上跑，冷启动首屏不等它。
     */
    private fun migrateLegacyCache() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val freed = cache.purgeLegacyMessageBodies()
                if (freed > 0) Diag.log("sync", "purged ${freed / 1024} KB of pre-v2 message bodies")
            }
        }
    }

    /**
     * 时间线的过期清理（v2 §七「无锁分批清理」）。
     *
     * <p>每会话 2000 条的上限由 `TimelineDao.commit` 在同一个事务里滚掉（那才是
     * 「本地与中继 1:1」的落点）。这里管的是另一件事：**整条会话没人看了**。
     * 14 天前的行留着只会占磁盘，而中继那边同样只有 14 天。
     *
     * <p>小步删（每次 500 行、一小时一轮）而不是一次清空：一次 `DELETE` 几十万行
     * 会长时间顶着写锁，用户下一次翻页就卡在那里。前面加 `delay` 是因为冷启动那
     * 几秒要留给首屏渲染与首次同步。
     */
    private fun startTimelineGc() {
        viewModelScope.launch {
            delay(TIMELINE_GC_FIRST_DELAY_MS)
            while (true) {
                runCatching { timeline.pruning(System.currentTimeMillis() - TIMELINE_TTL_MS) }
                delay(TIMELINE_GC_INTERVAL_MS)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            // 升级前落盘的 META_PATCH / STREAM_DELTA 清掉一次（见 cleanupDroppedKinds）。
            // 用短的独立延时而不是 TIMELINE_GC_FIRST_DELAY_MS：那个是周期性 GC，
            // 这个是修复本身，等一小时等于没修。
            delay(CLEANUP_FIRST_DELAY_MS)
            runCatching {
                val freed = timeline.cleanupDroppedKinds()
                if (freed > 0) Diag.log("sync", "purged $freed structural records (pre-upgrade)")
            }
        }
    }

    // ============================================================
    // 事件流 / 变更流
    // ============================================================


    private val eventsListener = object : RelayGateway.EventsListener {
        override fun onApproval(approval: Model.Approval) {
            // 已经点过（或结算过）的卡被宿主重投：别再画、也别再通知。
            if (isAnswering(approval.eventId)) return
            interactions.note(approval)
            // 同时落进 Room 账本：宿主每建一条新连接都会重投未结算的 waterfall，
            // 而这条卡片的「点过」状态要能活过进程重启。
            //
            // 这里没有 seq：`$events` 的 waterfall 不带时间线序号。所以账本键退到
            // `sessionId:eventId` —— 宿主重投同一张卡时 eventId 不变，这就是稳定的
            // 身份；绝不能退到时间戳，那样每次重投都成一张新卡。
            noteInteraction(chatBridge?.currentSessionId().orEmpty(), approval, seq = 0)
            val bridge = chatBridge
            // 只交给正在看的那个会话；别的会话的卡不能弹在当前聊天页
            // （点「允许一次」等于给别的会话放权）。被拒收就退回通知路径。
            if (_state.value.route == Route.CHAT && bridge != null && bridge.onApproval(approval)) {
                return
            }
            // 首张卡片到达才请求通知权限：比启动即索要更克制。
            notifyInteraction("DSH 审批请求", approval.headline(), approval.eventId)
        }

        override fun onQuestion(card: Model.QuestionCard) {
            if (isAnswering(card.eventId)) return
            interactions.note(card)
            noteInteraction(chatBridge?.currentSessionId().orEmpty(), card, seq = 0)
            val bridge = chatBridge
            if (_state.value.route == Route.CHAT && bridge != null && bridge.onQuestion(card)) {
                return
            }
            val title = if (card.questions.isEmpty()) "DSH 提问" else card.questions[0].question
            notifyInteraction("DSH 提问", title, card.eventId)
        }

        override fun onInteractionCancel(eventId: String) {
            cancelInteraction(eventId)
            chatBridge?.onInteractionCancel(eventId)
            // 卡片已经不在了，通知也得跟着走。
            cancelInteractionNotification(eventId)
        }

        override fun onSessionStatus(sessionId: String, running: Boolean) {
            chatBridge?.onSessionStatus(running)
            update { s ->
                s.copy(sessions = s.sessions.map {
                    if (it.id == sessionId) it.copy(running = running) else it
                })
            }
        }

        override fun onSessionAdded(session: Model.Session) {
            update { s ->
                val rest = s.sessions.filterNot { it.id == session.id }
                s.copy(sessions = filterVisible(listOf(session) + rest))
            }
        }

        override fun onSessionRemoved(sessionId: String) {
            update { s ->
                s.copy(sessions = s.sessions.filterNot { it.id == sessionId })
            }
        }

        override fun onSessionActivity(sessionId: String, at: Long) {
            update { s ->
                s.copy(sessions = s.sessions.map {
                    if (it.id == sessionId && at > it.updatedAt) {
                        it.copy(updatedAt = at, running = true)
                    } else it
                })
            }
        }

        override fun onReconnected() {
            // 断线重连补拉属于后台对账，同样不该闪加载指示器。
            loadSessions(silent = true)
        }
    }

    /**
     * 会话控制流送上来的交互帧（审批 / 提问 / cancel）→ app 级监听者。
     *
     * <p>在控制流的 IO 线程上被调用；[eventsListener] 里的实现自己负责切主线程
     * （它已经在 `runOnMain` 里动 StateFlow）。
     */
    fun deliverInteraction(kind: String, payload: JsonObject?) {
        gateway.deliverInteraction(kind, payload)
    }

    private fun openStreams() {
        val host = gateway.currentHost() ?: return
        // 只订阅「中继这次答复里确实有」的主机：冷启动从缓存捡起来的旧 id 在换过中继
        // 之后已经不存在，订阅它只会换来 404 与每 3 秒一次的重试（见 forgetStaleRelay）。
        if (_state.value.hosts.none { it.id == host.id }) return
        gateway.openEvents(eventsListener)
        gateway.watchChanges(object : RelayApi.ChangeFeed {
            override fun change(sessionId: String, latestSeq: Long, title: String, records: JsonArray?, truncated: Boolean) {
                // 变更只说明「这条会话有新记录」。去重**不能**拿归档游标比 latestSeq：
                // 那是元数据水位，游标一虚高，整条会话的变更就被永久丢掉。真正的去重
                // 是补拉调度器（同会话一个在途 + dirty 再对账），这里只更新展示水位。
                update { s ->
                    s.copy(sessions = s.sessions.map {
                        when {
                            it.id != sessionId -> it
                            title.isNotBlank() -> it.copy(
                                title = title,
                                lastSeq = maxOf(it.lastSeq, latestSeq),
                            )
                            else -> it.copy(lastSeq = maxOf(it.lastSeq, latestSeq))
                        }
                    })
                }
                // 推送自带正文：中继在 publish 之前已经落库，这批记录就是可直接渲染
                // 的数据。并进本地时间线即可，不必再发一次公网 /sync。只有中继明确
                // 说「没装全」（truncated）或推下来的尾部接不上本地游标时才回退到
                // 拉取——退化路径与推正文之前完全一样。
                // 变更帧只当「有事发生」的信号用。正文由 v2 归档流（Room）负责，
                // 这里补一次差量：覆盖到水位就结束，没覆盖到就继续补。
                requestSessionSync(lastHostId, sessionId)
            }

            override fun state(note: String) {
                if (note.isNotEmpty()) {
                    // 非空 = 断开/失败（「连接失败」「流已结束」「读取中断」…）。
                    Diag.log("preview", "changes state=$note")
                    changesReconnect.onDropped()
                    return
                }
                // 空串 = 连上了。中继的变更总线不做重放，所以断线期间变过的会话只有
                // 靠「连上之后挨个问一遍」才能补回来；不补的话，停在列表页等它自己
                // 恢复是看不见任何更新的（要等下拉刷新或重新进列表）。
                //
                // 判据是「这次连上之前掉过」，不是「连上了」——冷启动那次
                // `selectHost` 已经走过一遍 `loadSessions()`（内部会批量补差），
                // 无条件再补一次就是白白翻倍。
                if (changesReconnect.onConnected()) {
                    Diag.log("preview", "changes reconnected, catching up")
                    loadSessions(silent = true)
                }
            }
        })
    }

    /** 变更流的重连判据：只有「掉过又连上」才值得补一次差量。 */
    private val changesReconnect = ChangesReconnect()

    /** 待答卡片到达通知；app 在前台且正看着聊天页时不打扰。 */
    /**
     * 交互卡片通知。
     *
     * [key] 必须是**事件自己的稳定身份**（eventId），不能用时间戳：宿主对未结算的
     * waterfall 会在每次 `$events` 重开时重投一遍（打开会话、回答完一张卡都会触发
     * 每次重开 `$events` 都会重投），拿时间戳当 id 就等于同一张卡每次多堆一条通知——
     * 用户看到的就是「一直在弹」。
     */
    private fun notifyInteraction(title: String, text: String, key: String = "") {
        try {
            val context = getApplication<Application>()
            // 首张卡片到达才索要权限：本次通知可能不出现，但下一次起就有了。
            requestNotificationPermission()
            val manager = context.getSystemService(
                android.app.NotificationManager::class.java,
            ) ?: return
            val channel = android.app.NotificationChannel(
                CHANNEL_ID, "审批与提问", android.app.NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "电脑上的 DSH 在等待你在手机上回答" }
            manager.createNotificationChannel(channel)
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val intent = android.content.Intent(context, com.dsh.mobile.v2.MainActivity::class.java)
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP) }
            val pi = android.app.PendingIntent.getActivity(
                context, 0, intent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            manager.notify(notificationId(key), notification)
        } catch (e: Exception) {
            Diag.log("notify", "failed: $e")
        }
    }

    /** 同一张卡的重投要落到同一个通知槽位；没给 key 时才退回时间戳（不合并）。 */
    private fun notificationId(key: String): Int =
        if (key.isEmpty()) (System.currentTimeMillis() and 0x7fffffff).toInt()
        else key.hashCode() and 0x7fffffff

    /** 卡片已被答复/撤销：挂着的通知一起收掉，别留一条点开无事发生的死通知。 */
    private fun cancelInteractionNotification(key: String) {
        if (key.isEmpty()) return
        try {
            val manager = getApplication<Application>()
                .getSystemService(android.app.NotificationManager::class.java) ?: return
            manager.cancel(notificationId(key))
        } catch (e: Exception) {
            Diag.log("notify", "cancel failed: $e")
        }
    }

    // ============================================================
    // 延迟探测
    // ============================================================

    private fun startLatencyLoop() {
        latencyJob?.cancel()
        latencyJob = viewModelScope.launch {
            while (true) {
                refreshLatencyOnce()
                delay(LATENCY_INTERVAL_MS)
            }
        }
    }

    fun refreshLatency() {
        viewModelScope.launch { refreshLatencyOnce() }
    }

    private suspend fun refreshLatencyOnce() {
        val ms = suspendCancellableCoroutine { cont ->
            gateway.probeLatency { ms -> cont.resume(ms) }
        }
        // 单次探测失败不该把已知延迟抹成「—」：连接池里那条 socket 常被对端先关，
        // 这边收到 unexpected end of stream，于是顶栏每 8 秒闪一次灰点。
        // 网关保留了上次成功的读数，失败时用它兜底。
        val shown = if (ms >= 0) ms else gateway.latency()
        if (_state.value.route != Route.LOGIN) {
            update { it.copy(latencyMs = shown) }
        }
    }

    companion object {
        private const val LATENCY_INTERVAL_MS = 8000L
        /** 下拉刷新的兜底时长：超过这个时间不管回调有没有回来，都要把指示器收掉。 */
        private const val REFRESH_TIMEOUT_MS = 10000L

        /**
         * 下拉刷新指示器的最短可见时长。
         *
         * 不是为了好看，是为了**让状态真的被观察到一次**：中继 1ms 回包时
         * `loadingSessions = true` 与 `= false` 落在同一个主线程切片里，`StateFlow`
         * 的 conflate 会把中间那帧 `true` 吞掉，`PullToRefreshBox` 的 `isRefreshing`
         * 从头到尾都是 `false`，指示器就停在松手位置一直转（2026-10-30 模拟器实测
         * 转了两分钟，`uiautomator` 的 `ProgressBar` 节点始终在）。
         */
        private const val REFRESH_MIN_VISIBLE_MS = 450L
        private const val HOSTS_TTL_MS = 3 * 60 * 1000L
        private const val CHANNEL_ID = "dsh-v2-interactions"
        /**
         * 单会话补页的字节预算（整页总量，不是单条上限）。
         *
         * 归档记录常见 15~31KB，个别 300KB。给 16KB 时一轮只进得去一条真实回复，
         * 落后几百条就要跑几百轮——现场日志 `from=921 → next=922`、水位 1588 就是
         * 这么来的。补页请求本来就只有一个会话，给到 200KB 也就是一次内网请求的量。
         */
        private const val SYNC_PAGE_BYTES = 640 * 1024

        /**
         * 同时在途的补页请求上限。
         *
         * 中继对 `/sync` 的限流已按「自托管」放宽（600 次/分钟），429 不再是这里的
         * 约束。这条上限现在只为「别把自家连接与线程池占满」——冷启动补页量再大，
         * 首屏与发消息也不该排在它们后面。要给到能让用户干等一秒以上的带宽，八条
         * 是实测的甜点。
         */
        private const val SYNC_CONCURRENCY = 8

        /** 排队上限：真堆过头了，下次列表刷新/变更事件还会再要一遍，不必无限堆。 */
        private const val SYNC_QUEUE_MAX = 64

        /**
         * 一轮列表刷新最多跑几轮批量补拉。
         *
         * 每轮 `/sync` 能服务 up to `sessionCap` 个会话（`recent` tier 是 10）；冷启动
         * 五十来个会话要五轮以上。这里给到 20 是为了让**冷启动一次补完**——轮次太少
         * 就会退化成「一会话一请求」。批量请求的代价是线性带宽，不是配额，所以宁可
         * 多跑几轮。
         */
        private const val MAX_BATCH_ROUNDS = 20

        /**
         * 单会话补页最多连续失败几次。
         *
         * 留三次退避（1+2+4 秒）：失败基本都是网络抖动或中继重启，三次足够覆盖；
         * 再不行就该等下一次触发（列表刷新、SSE 变更），硬重试只会堆垃圾请求。
         */
        private const val SYNC_MAX_ATTEMPTS = 3

        /** 与中继 `ARCHIVE_TTL`（14 天）对齐：两边留一样长，离线时才不会「比云上少」。 */
        private const val TIMELINE_TTL_MS = 14L * 24 * 60 * 60 * 1000

        /** 冷启动先让首屏与首次同步抢资源，一小时后再开始小步删。 */
        private const val TIMELINE_GC_FIRST_DELAY_MS = 60L * 60 * 1000

        /**
         * 一次性清掉老版本写进来的结构记录，启动后多久开始。
         *
         * <p>比 [TIMELINE_GC_FIRST_DELAY_MS] 短得多：那个是周期性 GC、早跑晚跑都
         * 无所谓；这个是**修复本身**，用户升级后等一小时才瘦身等于没修。5 秒足够
         * 让首屏渲染与首次同步先拿到 IO。
         */
        private const val CLEANUP_FIRST_DELAY_MS = 5_000L

        /** 一轮一批，慢一点没关系——14 天 TTL 不需要争分夺秒。 */
        private const val TIMELINE_GC_INTERVAL_MS = 60L * 60 * 1000
    }

    /**
     * 启动入口放这里（所有属性初始化之后）：init 块与属性按声明顺序执行，
     * 类顶部的 init 块里 eventsListener 还是 null。boot → selectHost →
     * openStreams → openEvents(eventsListener) 传 null 即崩（真机闪退实证）。
     */
    init {
        boot()
    }

    /** 首张待答卡片到达时才索要通知权限（比启动即索要更克制）；由 Activity 注入。 */
    var requestNotificationPermission: () -> Unit = {}

    /**
     * 打开扫码页时索要相机权限（Android 6+ 运行时权限）；由 Activity 注入。
     * 申请是异步的，扫码页相机线程里仍会自查一次权限，拒绝时给出失败文案。
     */
    var requestCameraPermission: () -> Unit = {}

    /** 相机权限结果回到 ViewModel：授予了就抬一次 cameraGrantTick 触发扫码页自动重试。 */
    fun onCameraPermissionResult(granted: Boolean) {
        if (!granted) return
        update { it.copy(cameraGrantTick = it.cameraGrantTick + 1) }
    }
}


/** 中继自带的 APK 分发面（服务端见 `packages/relay/src/release.ts`）。 */
private const val RELEASE_MANIFEST_PATH = "/api/dsh-mobile-sh-apk/downloads/version.json"

/**
 * 旧静态地址，只作回落。
 *
 * 保留它是因为「中继不可达」与「中继还没发过版本」都必须是**可恢复状态**：
 * 前者在自建中继刚上线、或用户改了中继地址时天天发生；后者是每次新部署的默认态。
 * 只该让「更新暂时查不到」退化，不该让整条链路报错。
 *
 * 地址由 `releaseFallbackManifestUrl` 构建属性注入（默认 RFC 2606 占位域），
 * 真实部署不把具体域名写回仓库。
 */
private val FALLBACK_RELEASE_MANIFEST_URL: String = BuildConfig.RELEASE_FALLBACK_MANIFEST_URL

/**
 * 更新清单的候选地址：**先当前中继，再旧静态地址**。
 *
 * 走顶层函数（不是私有成员）是为了能在 JVM 单测里钉住两件事：
 * ①中继地址末尾有没有斜杠都只拼一次；②还没登录/地址为空时**不能**拼出
 * 一个 `/api/…` 这样的相对地址去喂 OkHttp（那会直接抛 IllegalArgumentException）。
 */
internal fun releaseManifestCandidates(relayOrigin: String): List<String> {
    val relay = relayOrigin.trim()
    val list = ArrayList<String>(2)
    if (relay.isNotEmpty() && !relay.startsWith("/")) {
        list.add(relay.trimEnd('/') + RELEASE_MANIFEST_PATH)
    }
    list.add(FALLBACK_RELEASE_MANIFEST_URL)
    return list
}
