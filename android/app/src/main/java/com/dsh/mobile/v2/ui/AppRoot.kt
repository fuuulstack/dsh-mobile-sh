package com.dsh.mobile.v2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dsh.mobile.v2.R
import com.dsh.mobile.v2.data.Model

/**
 * 根导航：状态机驱动，没有独立 router。
 *
 * route 存在 AppViewModel.UiState 里（LOGIN/HOME/CHAT/SETTINGS），
 * 底部导航是 HOME 与 SETTINGS 两段；CHAT 是推上去的详情页。
 * 聊天页的 ChatViewModel 随会话打开创建、离开销毁（挂 vm.chatBridge，事件流转发用）。
 */
@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var showHostMenu by remember { mutableStateOf(false) }
    var showPairing by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showChatMore by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showChatDelete by remember { mutableStateOf(false) }
    var showCrash by remember { mutableStateOf(state.crashLog.isNotEmpty()) }

    val route = state.route
    val inTab = route == AppViewModel.Route.HOME || route == AppViewModel.Route.SETTINGS

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Ds.bgPage,
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets),
        ) {
            when (route) {
                AppViewModel.Route.LOGIN -> LoginScreen(
                    busy = state.loginBusy,
                    error = state.loginError,
                    prefillEndpoint = state.prefillEndpoint,
                    prefillCode = state.prefillCode,
                    onSubmit = vm::submitLogin,
                    onClearError = vm::clearLoginError,
                    onScan = vm::openScan,
                    // 已登录的人被「返回」送到这一页时，左上角要有一条回去的路。
                    onBack = if (vm.canReturnHome()) vm::backToHome else null,
                )

                AppViewModel.Route.HOME -> {
                    val host = vm.currentHost()
                    // 「有主机」不等于「能用」：设备登录后 hosts 里会出现账号下所有电脑，
                    // 但没配对过的那几台 authorized=false，点了也发不出请求。空态因此要按
                    // 已授权来判断，否则新设备登录后看到的是「还没有会话 + 新建对话」，
                    // 而正确的下一步是「先配对一台电脑」。
                    val hasHost = state.hosts.any { it.authorized } || host != null
                    Box(modifier = Modifier.weight(1f)) {
                        HomeScreen(
                            state = state,
                            currentHostAlias = host?.alias ?: "",
                            currentHostPlatform = host?.platform ?: "",
                            hasAuthorizedHost = hasHost,
                            hosts = state.hosts,
                            currentHostId = host?.id ?: "",
                            onSelectHost = { vm.selectHost(it) },
                            onHostClick = {
                                showHostMenu = true
                                // 抽屉里的在线状态同样只能来自中继的实时答复。
                                vm.loadHosts(true)
                            },
                            onRefreshHosts = { vm.loadHosts(true) },
                            onNewSession = {
                                vm.createSession { s -> if (s != null) vm.openSession(s) }
                            },
                            onOpenSession = { vm.openSession(it) },
                            onDeleteSession = vm::deleteSession,
                            onPair = { showPairing = true },
                            onScan = vm::openScan,
                            // 下拉刷新只刷会话列表；顶栏延迟胶囊点一下才探延迟。
                            // 这两件事以前共用一个回调，于是下拉只探了延迟、列表纹丝不动，
                            // 指示器还因为没人复位而一直转（2026-10-30 用户实测）。
                            onRefresh = vm::refreshSessions,
                            onRefreshLatency = vm::refreshLatency,
                            onSearch = { showSearch = true },
                            onMore = { showMoreMenu = true },
                        )
                    }
                }

                AppViewModel.Route.CHAT -> {
                    val session = vm.activeSession
                    if (session == null) {
                        vm.backToHome()
                    } else {
                        val chat = remember(session.id) {
                            ChatViewModel(vm).also { bridge ->
                                vm.chatBridge = bridge
                                bridge.open(session)
                            }
                        }
                        ChatScreen(
                            vm = vm,
                            chat = chat,
                            session = session,
                            onMore = { showChatMore = true },
                        )
                    }
                }

                AppViewModel.Route.SETTINGS -> Box(modifier = Modifier.weight(1f)) {
                    // 进设置页就把中继侧的附件策略拉一遍：这个开关按设备记在服务端，
                    // 本地那份只是缓存，另一台设备改过之后这里显示的会是旧值。
                    LaunchedEffect(Unit) { vm.refreshAttachmentPolicy() }
                    SettingsScreen(
                        state = state,
                        onTogglePref = vm::savePreferences,
                        onRotate = { vm.rotateToken() },
                        onUnpair = vm::unpair,
                        onLogout = vm::logout,
                        onForceSync = vm::forceSync,
                        onRefresh = vm::refreshRelayState,
                        onCheckUpdate = { vm.checkForUpdate() },
                        onDownloadUpdate = { vm.downloadAndInstall() },
                        onOpenInstallSettings = { vm.openInstallSettings() },
                    )
                }

                AppViewModel.Route.SCAN -> ScanScreen(
                    grantTick = state.cameraGrantTick,
                    onScanned = { vm.onScanned(it) },
                    onFailed = { /* 失败文案显示在扫码页内，无需路由动作 */ },
                    onClose = vm::closeScan,
                )

                AppViewModel.Route.TASKS -> TasksScreen(onBack = vm::leaveTasks)
            }

            // 底部导航只在两个 tab 页出现（聊天页有自己的返回顶栏）。
            if (inTab) {
                // 只认已配对：未配对的主机发不出任何 RPC，「新建」必然失败。
                val hasHost = state.hosts.any { it.authorized } || vm.currentHost() != null
                BottomNav(
                    current = if (route == AppViewModel.Route.SETTINGS) 1 else 0,
                    onHome = vm::backToHome,
                    onNewSession = {
                        // 没授权主机时「新建」必然失败（网关返回「尚未选择主机」），
                        // 直接把用户带到配对入口。
                        if (!hasHost) {
                            showPairing = true
                        } else {
                            vm.createSession { s -> if (s != null) vm.openSession(s) }
                        }
                    },
                    onSettings = vm::openSettings,
                )
            }
        }
    }

    // 系统返回键：详情页回上层，tab 页交给系统（退出应用）。
    androidx.activity.compose.BackHandler(
        enabled = route == AppViewModel.Route.CHAT || route == AppViewModel.Route.SETTINGS
            || route == AppViewModel.Route.SCAN || route == AppViewModel.Route.TASKS,
    ) {
        when (route) {
            AppViewModel.Route.CHAT -> vm.leaveChat()
            AppViewModel.Route.SCAN -> vm.closeScan()
            // 后台任务页是从聊天页点进来的，返回要回到那个会话而不是首页。
            AppViewModel.Route.TASKS -> vm.leaveTasks()
            else -> vm.backToHome()
        }
    }

    // 一次性结论提示（如「已是最新版本」）：弹完立刻清空，重组不会再弹一次。
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(state.toast) {
        if (state.toast.isNotEmpty()) {
            android.widget.Toast.makeText(context, state.toast, android.widget.Toast.LENGTH_SHORT).show()
            vm.consumeToast()
        }
    }

    if (showHostMenu) {
        HostMenuSheet(
            vm = vm,
            // 与顶栏下拉、设备管理同一个口径：只列已配对的主机。
            hosts = pairedHosts(state.hosts),
            onDismiss = { showHostMenu = false },
        )
    }

    if (showPairing) {
        PairingSheet(
            error = state.loginError,
            busy = state.loginBusy,
            onDismiss = { if (!state.loginBusy) showPairing = false },
            onSubmit = { vm.submitPair(it) },
            onDismissedBySuccess = { showPairing = false },
        )
    }

    // 上次崩溃的堆栈：首帧之后弹一次，截图即可定位（真机没有 adb 也能排障）。
    if (showCrash && state.crashLog.isNotEmpty()) {
        CrashSheet(text = state.crashLog, onDismiss = { showCrash = false })
    }

    // v1 6.4：搜索 sheet（标题 + 输入 + 取消/搜索双按钮 + 结果列表）
    if (showSearch) {
        SearchSheet(
            hits = state.searchHitList,
            note = state.searchNote,
            done = state.searchDone,
            onSearch = { vm.runSearch(it) },
            onOpen = { hit ->
                showSearch = false
                vm.openSessionById(hit.sessionId, hit.title)
            },
            onDismiss = { showSearch = false },
        )
    }

    // v1 6.1：首页「更多」菜单（排序/输入配对码/刷新/设置）
    if (showMoreMenu) {
        MenuPopup(
            items = listOf(
                MenuItem("按工作区", R.drawable.ic_lucide_folder, state.sortMode == Model.SortMode.BY_WORKSPACE),
                MenuItem("按日期", R.drawable.ic_lucide_clock, state.sortMode == Model.SortMode.BY_DATE),
                MenuItem("正在运行", R.drawable.ic_lucide_circle_dashed, state.sortMode == Model.SortMode.RUNNING),
                MenuItem("输入配对码", R.drawable.ic_lucide_shield),
                MenuItem("刷新会话", R.drawable.ic_lucide_refresh),
                MenuItem("设置", R.drawable.ic_lucide_settings),
            ),
            onPick = { index ->
                showMoreMenu = false
                when (index) {
                    0 -> vm.setSortMode(Model.SortMode.BY_WORKSPACE)
                    1 -> vm.setSortMode(Model.SortMode.BY_DATE)
                    2 -> vm.setSortMode(Model.SortMode.RUNNING)
                    3 -> showPairing = true
                    4 -> vm.loadSessions()
                    else -> vm.openSettings()
                }
            },
            onDismiss = { showMoreMenu = false },
        )
    }

    // v1 6.3：会话页「更多」3 项（后台任务/重命名/删除）
    if (showChatMore) {
        val target = vm.activeSession
        MenuPopup(
            items = listOf(
                MenuItem("后台任务", R.drawable.ic_lucide_clock),
                MenuItem("重命名", R.drawable.ic_lucide_pencil),
                MenuItem("删除", R.drawable.ic_lucide_trash, danger = true),
            ),
            onPick = { index ->
                showChatMore = false
                if (target != null) {
                    when (index) {
                        0 -> vm.openBackgroundTasks()
                        1 -> showRename = true
                        else -> showChatDelete = true
                    }
                }
            },
            onDismiss = { showChatMore = false },
        )
    }

    if (showRename) {
        val target = vm.activeSession
        if (target != null) {
            InputSheet(
                title = "重命名会话",
                initial = target.title,
                hint = "会话名称",
                confirmLabel = "保存",
                onConfirm = { name ->
                    vm.renameSession(target, name) { showRename = false }
                },
                onDismiss = { showRename = false },
            )
        }
    }

    if (showChatDelete) {
        val target = vm.activeSession
        if (target != null) {
            ConfirmSheet(
                title = "删除会话？",
                message = "仅从本机列表删除，电脑端不受影响；该会话有新消息时会重新出现。",
                confirmLabel = "删除",
                danger = true,
                onConfirm = {
                    showChatDelete = false
                    vm.deleteSessionOnHost(target) { vm.backToHome() }
                },
                onDismiss = { showChatDelete = false },
            )
        }
    }
}

/** 菜单项：图标 + 文案 + 是否当前生效 + 是否危险动作。 */
data class MenuItem(
    val label: String,
    val icon: Int,
    val current: Boolean = false,
    val danger: Boolean = false,
)

/**
 * 锚定弹层（v1 `showPopup(anchor, …)` 的 Compose 版）：白底大圆角气泡卡片，
 * 贴在触发按钮**右下方**，背景压暗可点关。
 *
 * v1 采样报告 6.1/6.3 明确：菜单是「锚在按钮左下方/右上角的气泡」，不是底部抽屉。
 * 2026-10-27 真机验收时做成了 ModalBottomSheet，被指出与 v1 不符，已改。
 */
@Composable
private fun MenuPopup(
    items: List<MenuItem>,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Popup(
        // 内容铺满整屏（下面那层压暗），所以**不能**再让 Popup 自己对齐 + 偏移：
        // 那会把压暗层一起挪走，屏幕右侧留 12dp、顶部留 64dp 没被压到。
        // 菜单自己的位置改由内层 padding 决定（原来是 IntOffset(x = -12, y = 64)）。
        alignment = Alignment.TopStart,
        onDismissRequest = onDismiss,
        // usePlatformDefaultWidth = false：Popup 默认按「平台默认宽度」给窗口定尺，
        // 铺满整屏的压暗层会被这个规格夹住。
        properties = PopupProperties(focusable = true, usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Popup 只负责拿焦点，本身不画遮罩；不自己铺一层的话，白卡就直接飘在
                // 页面内容上，看不出「这是浮起来的一层」（F-04）。
                .background(Ds.bgScrim)
                // 压暗层盖住了 Popup 外面的区域，于是「点空白关掉」得自己接一下。
                .dsClickableNeutral { onDismiss() },
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 64.dp, end = 12.dp)
                    // 宽度必须定死：Popup 的内容是按**可用宽度**测量的，只给
                    // widthIn(min) 会被拉成整屏宽（2026-10-27 实测：白卡横跨 0..1078，
                    // 与「不是底部抽屉、锚在按钮右下方的气泡」的注释直接矛盾）。
                    // 用与主机菜单同一个 MENU_WIDTH，全 App 菜单同宽。
                    .width(MENU_WIDTH)
                    .shadow(10.dp, RoundedCornerShape(20.dp))
                    .background(Ds.bgPopup, RoundedCornerShape(20.dp))
                    .padding(vertical = 6.dp),
            ) {
                items.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 菜单行必须自报「这是按钮」：不报的话无障碍树里只剩一段
                            // 裸文本，读屏说不出它可点，手势导航也定位不到（F-07）。
                            .dsClickable(role = Role.Button) { onPick(index) }
                            .padding(horizontal = 18.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(item.icon),
                            contentDescription = null,
                            tint = when {
                                item.danger -> Ds.statusError
                                item.current -> Ds.primary
                                else -> Ds.textSecondary
                            },
                            modifier = Modifier.size(19.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(
                            item.label,
                            fontSize = 16.sp,
                            // 危险项这里**保留**红字：删除是瞬时、不可逆的动作，
                            // 菜单里的红字是平台惯例；与「完全权限」那种常驻状态
                            // 不同类（那里只留红图标，见 Composer，W-06）。
                            color = when {
                                item.danger -> Ds.statusError
                                item.current -> Ds.primary
                                else -> Ds.textPrimary
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (item.current) {
                            Icon(
                                painter = painterResource(R.drawable.ic_lucide_check),
                                contentDescription = null,
                                tint = Ds.primary,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// 主机切换菜单
// ============================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostMenuSheet(
    vm: AppViewModel,
    hosts: List<Model.Host>,
    onDismiss: () -> Unit,
) {
    val current = vm.currentHost()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Ds.bgSurface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                "切换电脑",
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = Ds.textPrimary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (hosts.isEmpty()) {
                EmptyHint("还没有已授权的电脑")
            } else {
                hosts.forEach { host ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .dsClickable { vm.selectHost(host); onDismiss() }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(platformIcon(host.platform)),
                            contentDescription = null,
                            tint = Ds.textSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            host.alias.ifBlank { "未命名主机" },
                            fontSize = 15.sp,
                            color = Ds.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        if (host.id == current?.id) {
                            Icon(
                                painter = painterResource(R.drawable.ic_lucide_check),
                                contentDescription = null,
                                tint = Ds.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        } else if (!host.online) {
                            Text("离线", fontSize = 12.sp, color = Ds.textMuted)
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** 配对码输入抽屉。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairingSheet(
    error: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    onDismissedBySuccess: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    // 成功自动关：busy 上升后回落且无错误 = 配对成功（v1：toast「配对成功」+ show(HOME)）。
    var wasBusy by remember { mutableStateOf(false) }
    LaunchedEffect(busy, error) {
        if (wasBusy && !busy && error.isEmpty()) onDismissedBySuccess()
        wasBusy = busy
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Ds.bgSurface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                "输入配对码",
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = Ds.textPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "在中继网页「你的主机」里点「生成设备配对码」，一台电脑一个码。",
                fontSize = 12.sp,
                color = Ds.textMuted,
            )
            Spacer(Modifier.height(14.dp))
            DshInput(
                value = code,
                onValueChange = { code = it },
                hint = "8 位数字配对码",
                keyboard = androidx.compose.ui.text.input.KeyboardType.Number,
            )
            if (error.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(error, fontSize = 12.sp, color = Ds.statusError)
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                label = "配对",
                busy = busy,
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) { onSubmit(code.trim()) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 崩溃堆栈弹窗：纯文本展示，截图即可定位。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CrashSheet(text: String, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Ds.bgSurface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                "上次崩溃堆栈（请截图）",
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = Ds.statusError,
            )
            Spacer(Modifier.height(10.dp))
            val crashScroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(Ds.bgSurfaceVariant, RoundedCornerShape(10.dp))
                    .verticalScroll(crashScroll)
                    .padding(10.dp),
            ) {
                Text(
                    text,
                    fontSize = 11.sp,
                    color = Ds.textSecondary,
                )
            }
            Spacer(Modifier.height(12.dp))
            SecondaryButton(
                label = "知道了",
                modifier = Modifier.fillMaxWidth().height(44.dp),
            ) { onDismiss() }
            Spacer(Modifier.height(24.dp))
        }
    }
}


/**
 * 搜索 sheet（v1 6.4）：标题「搜索会话内容」+ 输入框 + 取消/搜索双按钮 + 结果列表。
 *
 * 结果条目 v2 改进为两行（标题在上、命中摘要在下）——v1 把两者用「 · 」挤一行，
 * 两行布局避免标题与摘要挤在一行，提升可读性。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchSheet(
    hits: List<com.dsh.mobile.v2.data.Model.SearchHit>,
    note: String,
    done: Boolean,
    onSearch: (String) -> Unit,
    onOpen: (com.dsh.mobile.v2.data.Model.SearchHit) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Ds.bgSurface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text(
                "搜索会话内容",
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = Ds.textPrimary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            DshInput(
                value = query,
                onValueChange = { query = it },
                hint = "搜索关键词",
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(
                    label = "取消",
                    modifier = Modifier.weight(1f).height(46.dp),
                ) { onDismiss() }
                Box(modifier = Modifier.weight(1f).height(46.dp)) {
                    PrimaryButton(
                        label = "搜索",
                        modifier = Modifier.fillMaxSize(),
                    ) { onSearch(query) }
                }
            }
            if (done) {
                Spacer(Modifier.height(16.dp))
                if (note.isNotEmpty()) {
                    Text(note, fontSize = 13.sp, color = Ds.textMuted)
                } else {
                    Text(
                        "搜索结果（${hits.size}）",
                        fontSize = 13.sp,
                        color = Ds.textSecondary,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        hits.forEach { hit ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .dsClickable { onOpen(hit) }
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(
                                    hit.title.ifBlank { "未命名会话" },
                                    fontSize = 15.sp,
                                    color = Ds.textPrimary,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    hit.snippet,
                                    fontSize = 13.sp,
                                    color = Ds.textMuted,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 单行输入 sheet（v1 Widgets.sheetInput：重命名会话等）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputSheet(
    title: String,
    initial: String,
    hint: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Ds.bgSurface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = Ds.textPrimary,
            )
            Spacer(Modifier.height(14.dp))
            DshInput(value = value, onValueChange = { value = it }, hint = hint)
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                label = confirmLabel,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                enabled = value.isNotBlank(),
            ) { onConfirm(value) }
            Spacer(Modifier.height(24.dp))
        }
    }
}
