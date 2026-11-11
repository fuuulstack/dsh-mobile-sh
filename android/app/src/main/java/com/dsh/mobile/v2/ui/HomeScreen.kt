package com.dsh.mobile.v2.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.mobile.v2.R
import com.dsh.mobile.v2.data.Model

/**
 * 屏幕一：全部会话。
 *
 * 顶栏 = 延迟胶囊（左）+ 当前电脑名（点击切换主机）+ 加载圈（右）；
 * 列表按工作区分组（每组默认 5 条，展开看全量）；底部两段导航 + 中央新建。
 */
object HomeUiDefaults {
    /** 分组默认展示条数；展开后全量。 */
    const val GROUP_PREVIEW = 5
}

@Composable
fun HomeScreen(
    state: AppViewModel.UiState,
    currentHostAlias: String,
    currentHostPlatform: String,
    hasAuthorizedHost: Boolean,
    hosts: List<Model.Host> = emptyList(),
    currentHostId: String = "",
    onSelectHost: (Model.Host) -> Unit = {},
    onHostClick: () -> Unit,
    /** 打开主机下拉时强制刷一次主机列表：绿点只能来自中继的实时答复，不能吃缓存。 */
    onRefreshHosts: () -> Unit = {},
    onNewSession: () -> Unit,
    onOpenSession: (Model.Session) -> Unit,
    onDeleteSession: (Model.Session) -> Unit,
    onPair: () -> Unit,
    onScan: () -> Unit = {},
    /** 下拉刷新：真的去刷会话列表（[AppViewModel.refreshSessions]）。 */
    onRefresh: () -> Unit,
    /** 顶栏延迟胶囊的点击：只探一次延迟，不动列表。 */
    onRefreshLatency: () -> Unit = {},
    onSearch: () -> Unit = {},
    onMore: () -> Unit = {},
) {
    // 长按删除确认（v1 deleteSession 文案）：只从本机列表删除，电脑端不受影响。
    var pendingDelete by remember { mutableStateOf<Model.Session?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(Ds.bgPage)) {
        HomeHeader(
            alias = currentHostAlias,
            platform = currentHostPlatform,
            latencyMs = state.latencyMs,
            loading = state.loadingSessions,
            note = state.note,
            info = state.info,
            hosts = hosts,
            currentHostId = currentHostId,
            onSelectHost = onSelectHost,
            onScan = onScan,
            onHostClick = onHostClick,
            onRefreshHosts = onRefreshHosts,
            onRefreshLatency = onRefreshLatency,
            onSearch = onSearch,
            onMore = onMore,
        )

        Box(modifier = Modifier.weight(1f)) {
            when {
                !hasAuthorizedHost -> EmptyPair(onPair, onScan)
                state.sessions.isEmpty() && state.loadingSessions -> LoadingPlaceholder()
                state.sessions.isEmpty() -> EmptySessions(onNewSession)
                else -> SessionList(
                    sessions = state.sessions,
                    sortMode = state.sortMode,
                    loading = state.loadingSessions,
                    onRefresh = { onRefresh() },
                    onOpen = onOpenSession,
                    onDelete = { pendingDelete = it },
                )
            }
        }
        // 底部导航由 AppRoot 统一渲染（tab 壳），这里不再自带一条。
    }

    pendingDelete?.let { target ->
        ConfirmSheet(
            title = "删除会话？",
            message = "「${target.title.ifBlank { "未命名会话" }}」\n\n" +
                "仅从本机列表删除，电脑端不受影响；该会话有新消息时会重新出现。",
            confirmLabel = "删除",
            danger = true,
            onConfirm = {
                onDeleteSession(target)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/**
 * 确认弹层（v1 Widgets.sheetConfirm 的 Compose 版）：底部 sheet + 标题 + 说明 + 主/次按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmSheet(
    title: String,
    message: String,
    confirmLabel: String,
    danger: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
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
            Spacer(Modifier.height(8.dp))
            Text(message, fontSize = 14.sp, color = Ds.textSecondary, lineHeight = 20.sp)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(
                    label = "取消",
                    modifier = Modifier.weight(1f).height(46.dp),
                ) { onDismiss() }
                Box(modifier = Modifier.weight(1f).height(46.dp)) {
                    if (danger) {
                        // 危险动作：红底白字（v1 danger=true）
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Ds.statusError, RoundedCornerShape(14.dp))
                                .dsClickable { onConfirm() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                confirmLabel,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    } else {
                        PrimaryButton(
                            label = confirmLabel,
                            modifier = Modifier.fillMaxSize(),
                        ) { onConfirm() }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ============================================================
/**
 * 锚定菜单的固定宽度：宽度不随内容变，位置才不会每次打开都跳。
 *
 * 全 App 的锚定菜单（主机下拉、顶栏「更多」）都用它 —— 只写 `widthIn(min =)` 会被
 * `Popup` 按可用宽度测量、拉成整屏宽（2026-10-27 实测：白卡横跨 0..1078）。
 */
internal val MENU_WIDTH = 232.dp

// 顶栏
// ============================================================

@Composable
private fun HomeHeader(
    alias: String,
    platform: String,
    latencyMs: Int,
    loading: Boolean,
    note: String,
    info: String,
    hosts: List<Model.Host>,
    currentHostId: String,
    onSelectHost: (Model.Host) -> Unit,
    onScan: () -> Unit,
    onHostClick: () -> Unit,
    onRefreshHosts: () -> Unit,
    /** 延迟胶囊的点击：只探延迟，不刷会话列表。 */
    onRefreshLatency: () -> Unit,
    onSearch: () -> Unit,
    onMore: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // v1 listHeader 用的是 FrameLayout 三栏：左延迟、中电脑名、右操作钮。
        // 中间必须真正落在屏幕中线上，而不是「左边剩多少放多少」——v1 源码里这条是
        // 写进注释的硬约束，用 Row + weight 会让电脑名随左侧胶囊宽度左右飘。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        ) {
            LatencyChip(
                latencyMs = latencyMs,
                modifier = Modifier.align(Alignment.CenterStart),
                onClick = onRefreshLatency,
            )
            // 中：电脑名 + 下拉箭头，点开是锚在这里的下拉菜单（不是从底部升起的抽屉）。
            var menuOpen by remember { mutableStateOf(false) }
            // 菜单要居中到「电脑名」这一块，而不是居左对齐：DropdownMenu 默认从锚点左缘
            // 展开，锚点（名字 + 箭头）宽度由主机名长度决定，所以得先量出来再反推偏移。
            var anchorWidth by remember { mutableStateOf(0) }
            val density = LocalDensity.current
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    // 上限挡住左右两侧的胶囊/圆钮：主机名再长也只会省略，
                    // 不会把这一组撑到压住搜索按钮。
                    .widthIn(max = 196.dp)
                    .onGloballyPositioned { anchorWidth = it.size.width },
            ) {
            // 整组按内容宽度、整体居中：定宽 + 名字 weight 会让名字和箭头之间
            // 拉出一道空白，短名字看起来像「名字和箭头分家了」。名字超过上限才省略。
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .dsClickable {
                        menuOpen = true
                        // 每次开菜单都问一次中继：否则刚关掉的电脑还挂着绿点，
                        // 「（当前）」也还指着那台已经不在的机器。
                        onRefreshHosts()
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 平台图标是主机的身份标识，任何状态下都不许被占用：加载时它原地
                // 淡到半透明即可，宽度不变、语义不丢，也不会在顶栏里多出第二个图标。
                if (platform.isNotEmpty()) {
                    Icon(
                        painter = painterResource(platformIcon(platform)),
                        contentDescription = null,
                        tint = Ds.textSecondary,
                        modifier = Modifier
                            .size(15.dp)
                            .alpha(if (loading) 0.45f else 1f),
                    )
                }
                Spacer(Modifier.size(5.dp))
                // v1 把电脑名固定成 112dp：它是顶栏里唯一长度不可控的文本，
                // wrap_content 时名字一长就把下拉箭头挤成 0 宽，看起来像「下拉没了」。
                Text(
                    alias.ifBlank { "全部会话" },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.01).sp,
                    color = Ds.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 140.dp),
                )
                // 右侧留 6dp 而不是 3dp：和左边「图标 15 + 间距 5」配平，
                // 否则整组视觉重心比屏幕中线偏右约 3dp。
                Spacer(Modifier.size(6.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_chevron_down),
                    contentDescription = "切换主机",
                    tint = Ds.textSecondary,
                    modifier = Modifier.size(14.dp),
                )
            }
            // 下拉内容：只列**已配对**的主机，与「设置 → 设备管理」同一个口径
            // （`pairedHosts`）。未配对的电脑列在这里既切不过去、点一下还会跳去配对，
            // 看着像配对没生效（2026-10-29 用户直报「下拉框残留未配对主机」）。
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = Ds.bgPopup,
                shape = RoundedCornerShape(14.dp),
                offset = DpOffset(
                    x = with(density) { (anchorWidth.toDp() - MENU_WIDTH) / 2 },
                    y = 4.dp,
                ),
                // 定宽：菜单宽度别跟着第一项文字长短伸缩，「未命名主机」和带
                // 「（当前）· 离线」的长名字都要一样宽，否则每开一次位置都在跳。
                modifier = Modifier.width(MENU_WIDTH),
            ) {
                val paired = pairedHosts(hosts)
                paired.forEach { host ->
                    val isCurrent = host.id == currentHostId
                    DropdownMenuItem(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        modifier = Modifier.height(44.dp),
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // 名字占满剩余宽度、后缀不换行：主机名很长时让名字省略，
                                // 而不是把「（当前）/ · 离线」挤出可视区。
                                Text(
                                    host.alias.ifBlank { "未命名主机" },
                                    fontSize = 15.sp,
                                    color = Ds.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                // 「未配对」后缀已经不需要了：未授权的主机根本不进这个列表。
                                val suffix = when {
                                    isCurrent -> "（当前）"
                                    !host.online -> " · 离线"
                                    else -> ""
                                }
                                if (suffix.isNotEmpty()) {
                                    Text(
                                        suffix,
                                        fontSize = 12.sp,
                                        color = Ds.textMuted,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                            }
                        },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(platformIcon(host.platform)),
                                contentDescription = null,
                                tint = Ds.textSecondary,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onSelectHost(host)
                        },
                    )
                }
                if (paired.isEmpty()) {
                    DropdownMenuItem(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        modifier = Modifier.height(44.dp),
                        text = { Text("还没有已授权的电脑", fontSize = 15.sp, color = Ds.textMuted) },
                        onClick = { },
                    )
                }
                HorizontalDivider(color = Ds.borderSubtle)
                // 扫一扫固定压尾：它是「换一台电脑」的另一种输入方式，位置必须稳定，
                // 不能随主机数量上下浮动——否则每次开菜单都要重新找。
                DropdownMenuItem(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                    modifier = Modifier.height(44.dp),
                    text = { Text("扫一扫", fontSize = 15.sp, color = Ds.primary) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_lucide_scan),
                            contentDescription = null,
                            tint = Ds.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onScan()
                    },
                )
            }
            }
            // 右：搜索 / 更多（v1 两个都是 34dp 圆钮）。
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleIconButton(
                    icon = R.drawable.ic_lucide_search,
                    contentDescription = "搜索",
                    size = 34.dp,
                ) { onSearch() }
                Spacer(Modifier.size(6.dp))
                CircleIconButton(
                    icon = R.drawable.ic_lucide_more_horizontal,
                    contentDescription = "更多",
                    size = 34.dp,
                ) { onMore() }
            }
        }
        // 通道异常（令牌被拒/被撤销）时才出现的一行原因；正常不存在。
        if (note.isNotEmpty()) {
            Text(
                note,
                fontSize = 12.sp,
                color = Ds.statusError,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }
        // 刚做完一件事的结果（本机删除等）：不是错误，用次要色，别长得像报错。
        if (info.isNotEmpty()) {
            Text(
                info,
                fontSize = 12.sp,
                color = Ds.textMuted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }
    }
}

/** 主机平台 → 图标；未知退回通用服务器图标（与 v1 Icons.platformIcon 一致）。 */
fun platformIcon(platform: String): Int = when (platform) {
    "macos" -> R.drawable.ic_lucide_apple
    "windows" -> R.drawable.ic_lucide_windows
    "linux" -> R.drawable.ic_lucide_terminal
    else -> R.drawable.ic_lucide_server
}

/**
 * 主机平台 → 设置页 hero 卡的头像；没有对应头像时返回 0（调用方就别画）。
 *
 * 取值只有 `macos` / `windows` / `linux` 三种：中继的 `normalizePlatform` 已经把
 * Node 的 `darwin` / `win32` 这些同义词归一化过了，客户端不再自己认一遍。
 */
fun platformAvatar(platform: String): Int = when (platform) {
    "macos" -> R.drawable.platform_avatar_mac
    "windows" -> R.drawable.platform_avatar_windows
    else -> 0
}

// ============================================================
// 列表
// ============================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionList(
    sessions: List<Model.Session>,
    sortMode: Model.SortMode,
    loading: Boolean,
    onRefresh: () -> Unit,
    onOpen: (Model.Session) -> Unit,
    onDelete: (Model.Session) -> Unit,
) {
    // 三种模式（v1 HomeScreen.setSortMode / bindSessions 对齐）：
    //  BY_WORKSPACE 按工作区分组（默认）、BY_DATE 按日期分段、RUNNING 只列运行中。
    // 先按 id 去重：宿主 session/list、事件流 onSessionAdded、缓存回填三条来源
    // 可能短暂给出同一会话两份，LazyColumn 的 key 一旦重复就直接崩
    // （2026-10-27 真机实测：java.lang.IllegalArgumentException: Key "7277" was already used）。
    val ordered = remember(sessions, sortMode) {
        val unique = sessions.distinctBy { it.id }
        when (sortMode) {
            Model.SortMode.RUNNING -> unique.filter { it.running }.sortedByDescending { it.updatedAt }
            else -> unique.sortedByDescending { it.updatedAt }
        }
    }
    val grouped = remember(ordered, sortMode) {
        when (sortMode) {
            Model.SortMode.BY_DATE -> ordered.groupBy { Model.dateGroupOf(it.updatedAt) }
            Model.SortMode.RUNNING -> if (ordered.isEmpty()) emptyMap() else mapOf("正在运行" to ordered)
            else -> ordered.groupBy { it.workspace.ifBlank { "未分组" } }
        }
    }
    // 展开的分组；「加载更多」点击后全量展示。
    var expanded by remember { mutableStateOf(setOf<String>()) }

    if (ordered.isEmpty() && sortMode == Model.SortMode.RUNNING) {
        EmptyHint("当前没有正在运行的会话", modifier = Modifier.fillMaxWidth())
        return
    }

    // v1 页 2「下拉刷新」：Compose 用 PullToRefreshBox（material3 1.3），但**自带指示器
    // 必须换掉**。默认那份在 `isRefreshing = true` 时走 Material3 的
    // `CircularProgressIndicator`，它内部有四组无限 `animateFloat`（起止角各一组、
    // 2000ms 的 baseRotation、currentRotation），一屏一个就能把 GPU 拉满 —— 2026-10-31
    // 真机实测：列表页 `dumpsys gfxinfo` 10 秒 614 帧（61fps 空转）、
    // GpuWorkeBPF 报 96%；同一个 App 切到聊天页/设置页都是 0 帧。
    //
    // 换成我们自己的 Spinner（定值 270° 弧 + graphicsLayer.rotationZ，走 RenderNode
    // 矩阵不重画）。它同样只在 `loading` 为真时出现，拖动过程的位移由
    // `PullToRefreshBox` 自己给的 `state.distanceFraction` 控制，不额外起动画。
    val refreshState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = loading,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
        state = refreshState,
        indicator = {
            if (loading) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp),
                ) {
                    Spinner(size = 20.dp, color = Ds.primary, strokeWidth = 2.dp, stepsPerSecond = 60)
                }
            }
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp),
        ) {
            grouped.forEach { (group, all) ->
                item(key = "header-$group") {
                    WorkspaceHeader(group, all.size)
                }
                val showAll = group in expanded || all.size <= HomeUiDefaults.GROUP_PREVIEW
                val shown = if (showAll) all else all.take(HomeUiDefaults.GROUP_PREVIEW - 1)
                items(shown, key = { "${group}::${it.id}" }) { s ->
                    SessionRow(s, onOpen = { onOpen(s) }, onDelete = { onDelete(s) })
                }
                if (!showAll) {
                    item(key = "more-$group") {
                        ExpandMoreRow(
                            remaining = all.size - (HomeUiDefaults.GROUP_PREVIEW - 1),
                            onExpand = { expanded = expanded + group },
                        )
                    }
                } else if (all.size > HomeUiDefaults.GROUP_PREVIEW) {
                    item(key = "collapse-$group") {
                        CollapseRow(onCollapse = { expanded = expanded - group })
                    }
                }
            }
        }
    }
}

/** 工作区分组标题：文件夹图标 + 名称 + 计数。 */
@Composable
private fun WorkspaceHeader(name: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 16.dp, end = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_lucide_folder),
            contentDescription = null,
            tint = Ds.textTertiary,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.size(7.dp))
        Text(
            name,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Ds.textTertiary,
            // 同 SectionLabel：中文标题不套拉丁字距（W-03）。
            letterSpacing = 0.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            "$count",
            fontSize = 11.sp,
            letterSpacing = 0.02.sp,
            color = Ds.textMuted,
        )
    }
}

/** 会话行：标题 + 预览 + 时间/状态位（运行中转圈）。 */
@Composable
private fun SessionRow(
    session: Model.Session,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val interaction = androidx.compose.foundation.interaction.MutableInteractionSource()
    val pressed by interaction.collectIsPressedAsState()
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (pressed) Modifier.background(Ds.bgPressed)
                else Modifier
            )
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onOpen,
                onLongClick = onDelete,
            )
            .padding(horizontal = 10.dp, vertical = Dimens.sessionRowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 三态状态槽（与 v1 一致）：在跑转圈 > 未读绿点 > 灰点；转圈优先，
        // 同一行同时「在跑」和「未读」时，用户关心的首先是它在跑。
        // 槽固定 14dp（v1 sessionRow 的 slotSize），点本身仍是 6dp。
        Box(modifier = Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            when {
                // 列表里运行中会话：30fps 转圈。
                //
                // 2026-10-31 真机实测（Android / ColorOS）：成本只跟「每秒变化几次」
                // 有关，跟画多大、几个、什么形状都无关。同一页面只改这一处：
                // 静态 0%、8fps 10%、30fps 30%、60fps 50% 单核 CPU。齿轮/菊花
                // 换形状没用，共享同一时钟也没用（都在同一 vsync 内合并）。
                // 30fps 是「看得出在转」与「别把半个核烧掉」之间的折中：
                // 分 60 次推进、每次 6°，肉眼看就是匀速旋转，而成本只有满帧的六成。
                session.running -> Spinner(size = 14.dp, color = Ds.statusRunning, stepsPerSecond = 30)
                session.unread -> StatusDot(Ds.statusUnreadDot, Dimens.statusDotSm)
                else -> StatusDot(Ds.statusOfflineDot, Dimens.statusDotSm)
            }
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                // 与聊天页顶栏同一套回退（真标题 → 工作区目录名 → 占位）：
                // 两处显示不一致的话，点进去会「换个名字」，看着像另一条会话。
                chatHeaderTitle(session.title, session.workspace),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.01).sp,
                color = Ds.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val preview = session.preview
            if (preview.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    preview,
                    fontSize = 12.sp,
                    color = Ds.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        val stamp = Model.relativeTime(session.updatedAt)
        Text(
            stamp,
            fontSize = 12.sp,
            // 等宽只留给纯 ASCII：中文相对时间（「刚刚」「3 分钟前」）落到等宽字体的
            // 汉字回退字形上，字距忽宽忽窄、比旁边正文还显眼；「12ms」这类纯数字
            // 才真需要等宽对齐（W-04）。
            fontFamily = if (stamp.all { it.code < 128 }) {
                androidx.compose.ui.text.font.FontFamily.Monospace
            } else {
                androidx.compose.ui.text.font.FontFamily.Default
            },
            color = Ds.textMuted,
        )
    }
}

/**
 * 分组折叠分隔行（v1 maskedSessionRow 下半部分 / collapseActionRow）：
 * 「──── 展开其余 N 个会话 ▾ ────」，两侧是 1dp 分隔线，中间灰字，不是蓝字按钮。
 */
@Composable
private fun SectionDividerAction(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .dsClickableNeutral { onClick() }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(Ds.divider),
        )
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.02.sp,
            color = Ds.textMuted,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(Ds.divider),
        )
    }
}

/** 「展开其余 N 个会话 ▾」。 */
@Composable
private fun ExpandMoreRow(remaining: Int, onExpand: () -> Unit) =
    SectionDividerAction("展开其余 $remaining 个会话 ▾", onExpand)

/** 全部展开后的「收起会话 ▴」。 */
@Composable
private fun CollapseRow(onCollapse: () -> Unit) =
    SectionDividerAction("收起会话 ▴", onCollapse)

// ============================================================
// 空态 / 加载态
// ============================================================

@Composable
private fun LoadingPlaceholder() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(90.dp))
        Spinner(size = 28.dp, color = Ds.primary, strokeWidth = 2.4.dp)
        Spacer(Modifier.height(14.dp))
        Text("正在加载会话…", fontSize = 15.sp, color = Ds.textMuted)
    }
}

@Composable
private fun EmptySessions(onNewSession: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(72.dp))
        Image(
            painter = painterResource(R.drawable.dafeiyu_logo),
            contentDescription = null,
            modifier = Modifier.size(96.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("还没有会话", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ds.textPrimary)
        Spacer(Modifier.height(20.dp))
        PrimaryButton(
            label = "新建对话",
            modifier = Modifier
                .fillMaxWidth(0.46f)
                .height(46.dp),
        ) { onNewSession() }
    }
}

/**
 * 还没有授权任何电脑：主行动点是配对，不是新建（新建会因「尚未选择主机」失败）。
 *
 * 两个入口并排——桌面已经显示二维码时用「扫一扫」，只给了 8 位数字就用「输入配对码」。
 * 只留一个实心钮，第二个用描边款，一屏里只有一个主行动点。
 */
@Composable
private fun EmptyPair(onPair: () -> Unit, onScan: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(72.dp))
        Image(
            painter = painterResource(R.drawable.dafeiyu_logo),
            contentDescription = null,
            modifier = Modifier.size(96.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("先配对一台电脑", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ds.textPrimary)
        Spacer(Modifier.height(20.dp))
        PrimaryButton(
            label = "输入配对码",
            modifier = Modifier
                .fillMaxWidth(0.46f)
                .height(46.dp),
        ) { onPair() }
        Spacer(Modifier.height(10.dp))
        SecondaryButton(
            label = "扫一扫",
            modifier = Modifier
                .fillMaxWidth(0.46f)
                .height(46.dp),
        ) { onScan() }
        Spacer(Modifier.height(12.dp))
        Text(
            "配对码在中继网页的「你的主机」里生成",
            fontSize = 12.sp,
            color = Ds.textMuted,
        )
    }
}

// ============================================================
// 底部导航
// ============================================================

/**
 * 底部两段导航 + 中央新建。
 * 选中态只切图标描边/实心，不垫底色（克制标记）。
 */
@Composable
fun BottomNav(
    current: Int,
    onHome: () -> Unit,
    onNewSession: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Ds.bgSurface)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavItem(
            label = "会话",
            icon = R.drawable.ic_lucide_message_square,
            iconActive = R.drawable.ic_lucide_message_square_filled,
            active = current == 0,
            onClick = onHome,
            modifier = Modifier.weight(1f),
        )
        // 中央新建：动作不是 tab，用中性灰。
        Column(
            modifier = Modifier
                .dsClickableNeutral { onNewSession() }
                .padding(horizontal = 18.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_plus),
                contentDescription = "新建对话",
                tint = Ds.navInactiveFg,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "新建",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Ds.navInactiveFg,
            )
        }
        NavItem(
            label = "设置",
            icon = R.drawable.ic_lucide_settings,
            iconActive = R.drawable.ic_lucide_settings_filled,
            active = current == 1,
            onClick = onSettings,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun NavItem(
    label: String,
    icon: Int,
    iconActive: Int,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .dsClickableNeutral(enabled = !active, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(if (active) iconActive else icon),
            contentDescription = label,
            tint = if (active) Ds.navActiveFg else Ds.navInactiveFg,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = if (active) Ds.navActiveFg else Ds.navInactiveFg,
        )
    }
}
