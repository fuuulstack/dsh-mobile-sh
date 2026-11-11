package com.dsh.mobile.v2.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.mobile.v2.R
import com.dsh.mobile.v2.data.Model
import com.dsh.mobile.v2.data.TodoMark
import com.dsh.mobile.v2.data.taskProgressOf
import com.dsh.mobile.v2.data.todoMarkOf
import kotlinx.coroutines.launch

/**
 * 屏幕二：会话详情（聊天）。
 *
 * 顶栏 = 返回 + 居中会话标题；消息区（用户靛青气泡右对齐、助手文本左对齐、
 * 流程面板可折叠）；底部胶囊输入框（权限 / 模型 / 发送-停止）。
 */
@Composable
fun ChatScreen(
    vm: AppViewModel,
    chat: ChatViewModel,
    session: Model.Session,
    onMore: () -> Unit = {},
) {
    val state by chat.state.collectAsState()
    val sessionView = state.session
    val listState = rememberLazyListState()
    // 模型处理详情抽屉（两级）：值非空时弹第一级时间线。
    //
    // 存的是面板身份而不是消息对象：面板每来一帧增量就换一个对象，存旧对象等于
    // 把抽屉冻结在打开那一刻（2026-10-28 要求「展开后的思考详情也要流式加载」）。
    var openProcess by remember { mutableStateOf<PanelTarget?>(null) }
    // 抽屉最后一次成功解析到的面板。重新快照会换掉 panelId，届时按身份再也找不到
    // 原来那块；留着这份是为了「宁可冻住也不让抽屉凭空关闭」（见下面 openProcess 处）。
    // 刻意**不用** mutableStateOf：它只在 resolvePanel 返回 null 的兜底路径上被读，
    // 写成可观察状态会让流式每一帧多触发一轮重组。
    val lastPanel = remember { arrayOfNulls<Model.Message>(1) }
    // 任务清单抽屉的开关。清单本身从 state.session.todos 实时读，
    // 这里只存「开着没有」——宿主流式改 todo 时抽屉里的图标要跟着变。
    var showTodos by remember { mutableStateOf(false) }
    // 后台任务 / 子智能体两个清单弹层的开关。三颗胶囊共用同一种交互：
    // 图标胶囊常驻在输入框上方，点开才展开完整清单。
    var showJobs by remember { mutableStateOf(false) }
    var showSubagents by remember { mutableStateOf(false) }
    val showsThinking = vm.state.collectAsState().value.prefs.showReasoning
    // 中继是否为本设备传输附件：关掉之后历史图片不再去取字节，直接说明原因。
    // 读的是服务端策略的本地缓存（见 Model.Preferences.transferAttachments），
    // 进设置页与登录后各同步一次。
    val attachmentsEnabled = vm.state.collectAsState().value.prefs.transferAttachments
    // 装配时间线前先把同一回合的相邻面板并成一块。
    //
    // 宿主一步一个 step，真宿主一轮 7~9 步；累加器按 (turn, step) 一块面板，
    // 不合并就渲染成 7~9 行「已处理 1 个操作」，正文被挤出屏幕。
    // 只在渲染层合：状态里保持每 step 一块，增量事件靠 panelId 认人。
    val rows = remember(state.messages) { mergeTurnPanels(state.messages) }

    // 附件三个入口：相册 / 拍照 / 文件。
    //
    // 相册用系统 Photo Picker（零权限）；拍照走 TakePicture（系统相机 App 把照片写进
    // 我们给的那个 File，读取要过 FileProvider）；文件走 OpenDocument（也零权限，
    // 比老的 GET_CONTENT 在长文件名与云端盘上更稳）。
    val context = androidx.compose.ui.platform.LocalContext.current
    // 拍照产物要到回调里才读，先记住目标文件。
    var captureTarget by remember { mutableStateOf<java.io.File?>(null) }
    val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) chat.attach(context, uri, asImage = true) }
    val pickFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) chat.attach(context, uri, asImage = false) }
    val takePhoto = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture(),
    ) { ok ->
        val target = captureTarget
        captureTarget = null
        if (ok && target != null) {
            chat.attach(context, android.net.Uri.fromFile(target), asImage = true)
        }
    }

    // 跟随底部（v1 followTail 语义）：只有本来就贴着底部才跟随新内容；
    // 用户往上翻历史时，流式增量不再把人拽回底部。
    var followTail by remember { mutableStateOf(true) }
    // 手指是否正按在列表上。
    //
    // <p>**这是「翻历史时一有输出就被拽回底部」的直接成因**：`followTail` 只在
    // 「滚动停止」的边沿更新（见下面那个 snapshotFlow），也就是说手指按住往上拖的
    // **整个过程里它还是 true**——上一次停在底部时设的值。而流式输出每秒要改十几次
    // `state.revision`，每改一次就跑一次 `scrollToItem(tail)`，于是手指还没松开，
    // 列表已经被拽回底部了（2026-10-30 用户实测：「查看历史消息的时候，如果这个
    // 时候有输出，也会往下跳」）。
    //
    // <p>判据取自 `interactionSource` 的拖动交互，而不是 `isScrollInProgress`：
    // 后者被我们自己的 `scrollToItem` 也会置真，用它做守卫会让跟随彻底停摆。
    // 程序化滚动不产生 DragInteraction，所以这个信号只代表「用户的手」。
    var userDragging by remember { mutableStateOf(false) }
    // 只有用户自己拖动才算「离开底部」，内容增长不算。
    //
    // 不能用 layoutInfo 反推：快照一次性把十几条灌进来时，列表长度先变、滚动还没
    // 执行，那个算式当场算出「离底很远」，跟随意图被永久熄掉，界面就停在顶部不动
    // （2026-10-27 真机复现）。也不能直接读 isScrollInProgress 的快照值——它初值
    // false，而 followTail 初值 true，两边一撞就再也跟随不上。所以只在「从滚动中
    // 回到静止」这个边沿更新意图。
    // 首个可见行：冻顶气泡与「已滚到哪一段」共用它。
    //
    // 用 `collect` 而不是在 composition 里读 `layoutInfo`：后者会在每次滚动时
    // 触发重组，而重组又可能改布局，滚动一大就自激掉帧。
    // 加载更早内容后要滚回的锚点。
    //
    // 记的是**消息身份 + 它在 rows 里的下标**（`TimelineAnchor`），不是 LazyColumn
    // 的 item 下标：前者在内容前插后仍能找回同一条消息，后者会整体偏移。
    // 判据看 `anchorTargetOf` —— 只有那条消息真的往后挪了才滚。
    var pendingAnchor by remember { mutableStateOf<TimelineAnchor?>(null) }
    var firstVisibleIndex by remember { mutableStateOf(0) }
    var lastVisibleIndex by remember { mutableStateOf(-1) }
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow {
            listState.firstVisibleItemIndex to
                (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1)
        }.collect { (first, last) ->
            firstVisibleIndex = first
            lastVisibleIndex = last
        }
    }
    LaunchedEffect(listState) {
        var wasScrolling = false
        androidx.compose.runtime.snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling) {
                    if (wasScrolling) {
                        val layout = listState.layoutInfo
                        val last = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
                        if (layout.totalItemsCount > 0) {
                            followTail = layout.totalItemsCount - last <= 1
                        }
                    }
                }
                wasScrolling = scrolling
            }
    }
    // 手一碰列表就交出跟随权，松手后由上面那个「滚动停止」边沿按最终位置判定要不要收回。
    //
    // <p>顺带覆盖了惯性滑行：`DragInteraction.Stop` 之后 fling 还在跑，但那段时间
    // `followTail` 已经是 false，流式增量不会插进来打断滑行。
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is androidx.compose.foundation.interaction.DragInteraction.Start -> {
                    userDragging = true
                    followTail = false
                }
                is androidx.compose.foundation.interaction.DragInteraction.Stop,
                is androidx.compose.foundation.interaction.DragInteraction.Cancel,
                -> userDragging = false
                else -> Unit
            }
        }
    }
    val scope = rememberCoroutineScope()
    // 加载更早的内容落进 `rows` 之后，把当初那条消息滚回视口顶部。
    //
    // 判据是**身份位移**而不是行数变化（`anchorTargetOf` 里有完整事故记录）：
    // 流式输出一直在往尾部追加，用「行数变多」当判据会在加载还没回来时就误触发。
    LaunchedEffect(pendingAnchor, rows, state.hasMore) {
        val anchor = pendingAnchor ?: return@LaunchedEffect
        val leading = stickyLeadingFor(rows, state)
        val target = anchorTargetOf(anchor, rows, leading)
        if (target == null) return@LaunchedEffect
        pendingAnchor = null
        // **必须另起作用域**：这一行 `pendingAnchor = null` 会改掉本 effect 的 key，
        // 当前协程当场被取消，下面任何挂起点（`withFrameNanos` / `scrollToItem`）
        // 都再也不会恢复。2026-11-03 模拟器实测：日志停在 `apply … target=31`，
        // 滚动一次都没执行。
        scope.launch {
            // 等一帧再滚：`rows` 变了不代表 LazyColumn 已经按新数据布局完。
            // 早一帧调用时列表里还是旧项数，目标索引会被夹到末尾
            // （实测偏了 8 项：target=31 落到 23）。
            withFrameNanos { }
            // `scrollOffset` 与 `firstVisibleItemScrollOffset` 同义：**该项已滚出
            // 视口顶边的像素数**。锚点行点击时在视口顶边**下方** 148px，所以要传
            // 负值才能把它推回原位。曾经在这里 `coerceAtLeast(0)`，锚点行每次都被
            // 顶到视口最上沿（2026-11-03 模拟器实测 `LONGBODY-1` 从 y=552 跳到 318）；
            // 用滚动补偿 API 补差也只回退了半程（落到 466）。负值本身是合法的，
            // 只要不超过前一项的高度（这里 148px < 一项 336px）。
            listState.scrollToItem(target, anchor.offset)
        }
    }
    // 输入时把最新消息顶到键盘上方（2026-10-28 用户要求）。
    //
    // 键盘弹起时窗口被 `imePadding()` 压掉一截，列表跟着变矮：用户此前若在翻历史，
    // 缩水后的视口里可能一条最新消息都看不到，打完字还得自己往回滑。这里在「键盘
    // 由无到有」的边沿主动贴尾；`scrollToItem(尾部)` 会被列表夹到底部，所以最新那条
    // 就停在键盘上沿（内容不足一屏时无副作用）。
    //
    // 必须等两帧再滚：第一帧应用 ime inset，第二帧列表才按新高度重新布局；只等一帧
    // 算出来的仍是旧视口，滚动位置会差掉一屏（与「跳转到最新消息」同一个坑）。
    val density = LocalDensity.current
    val keyboardOpen = WindowInsets.ime.getBottom(density) > 0
    // 尾部那一项的实测高度：给 `scrollToItem` 的偏移要盖过整个项，列表才会夹到
    // content 末端（= 真正的底）。还没量到（首次组合）就返回 0，由
    // `tailScrollOffset` 退回一个足够大的常量，效果一样。
    val itemHeightOf: (Int) -> Int = { index ->
        listState.layoutInfo.visibleItemsInfo.lastOrNull { it.index == index }?.size ?: 0
    }
    LaunchedEffect(keyboardOpen) {
        val tail = tailItemIndex(state, rows.size)
        // followTail 作为第三条判据：正在翻历史的人弹键盘不该被拽回底部。
        if (!pinLatestOnKeyboard(
                keyboardOpen = keyboardOpen,
                tailIndex = tail,
                followTail = followTail,
                userDragging = userDragging,
            )
        ) {
            return@LaunchedEffect
        }
        // 贴尾后恢复跟随意图：之后的流式增量继续自动滚到最新。
        followTail = true
        withFrameNanos { }
        withFrameNanos { }
        // 必须带偏移：scrollToItem 的语义是「把该项对齐到顶部」，最后一项高过一屏
        // 时那等于把它的首行钉在屏幕上沿（2026-10-31 真机实测「顶端与正文第一行
        // 对齐」）。偏移给到项高，列表会夹到 content 末端 = 真正的底。
        listState.scrollToItem(tail, tailScrollOffset(itemHeightOf(tail)))
    }
    LaunchedEffect(state.revision) {
        // `userDragging` 是第二道保险：手指按在列表上时，无论 followTail 是什么值
        // 都不许程序化滚动。只靠 followTail 也能挡住（拖动开始就置 false 了），
        // 但那条路要经过一次重组才生效，这里直接读同一个快照更早一帧。
        if (!autoScrollOnNewContent(followTail, userDragging)) return@LaunchedEffect
        // LazyColumn 的 item 索引 ≠ messages 索引：前面有「载入更早/快照占位」行，
        // 后面还有流式与待答卡片行。滚到整个尾部区最后一项（算式与按钮共用一份）。
        val tail = tailItemIndex(state, rows.size)
        if (tail >= 0) {
            // 流式期间回调很密：即时滚动，不动画，避免动画堆积抖动。
            listState.scrollToItem(tail, tailScrollOffset(itemHeightOf(tail)))
        }
    }
    // 打字机让正文在**两次 publish 之间**也在长（一个字一个字冒出来），只跟
    // `revision` 滚会慢半拍：字长出来了、列表没跟上，最新那行被推出视口。
    // 这里补一层「内容长大了就贴回底部」，判据是「上次贴底 + 现在能往下滚了」，
    // 用户往上翻历史时一个动作都不做（详见 AutoScroll.kt）。
    StickToBottomOnGrowth(
        listState = listState,
        enabled = { autoScrollOnNewContent(followTail, userDragging) },
        tailIndex = { tailItemIndex(state, rows.size) },
        tailOffset = { tailScrollOffset(itemHeightOf(tailItemIndex(state, rows.size))) },
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ds.bgPage)
            .imePadding(),
    ) {
        ChatHeader(
            // 标题的回退顺序与列表同源：真标题 → 工作区目录名 → 占位。
            // 会话页顶栏必须**当场**就有一个稳定的名称，不能先给一个占位、
            // 等 `/rpc/projections` 回来再跳变（2026-10-30 用户实测）。
            title = chatHeaderTitle(sessionView.title, sessionView.workspace),
            subtitle = sessionView.workspace.substringAfterLast('/').substringAfterLast('\\'),
            onBack = { vm.leaveChat() },
            onMore = onMore,
        )

        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.hasMore) {
                    item {
                        LoadEarlierRow(
                            busy = state.loadingEarlier,
                            onClick = {
                                // 记下「此刻视口顶部是哪一条」，加载完成后滚回它。
                                //
                                // 不这么做的话，`loadEarlier` 往列表前面插了 30 段正文，
                                // LazyColumn 会重新布局，用户被直接甩到刚加载出来的最前端
                                // —— 他点「载入更早」是想往上接着读，不是想跳到最上面
                                //（2026-11-01 用户实测）。
                                // 锚点必须取**第一条真正可见的消息**，不能用
                                // `firstVisibleItemIndex - leading`。
                                //
                                // 2026-11-01 真机实测：用户滑到最顶时
                                // `firstVisibleItemIndex=0` 命中的是「载入更早」那一行
                                // （leading），`0 - 1` 被 coerce 成 0 ⇒ 锚到了 rows[0]，
                                // 而它的像素偏移被当成 0。加载完把 rows[0] 滚到**顶部**，
                                // 于是原本在 y=507 的那条上移到 y=380 —— 差的就是
                                // leading 行的高度，用户看到「跳了一下」。
                                //
                                // 锚点记**身份**而不是下标：新内容插在前面，下标
                                // 整体后移，记下标就会跳到别处。
                                //
                                // 2026-11-01 真机实测：用户滑到最顶时
                                // `firstVisibleItemIndex=0` 命中的是「载入更早」那一行
                                // （leading），它不在 rows 里、没有身份 ⇒ key 记 null，
                                // 加载后原地不动（它自己也不移位）。
                                //
                                // 2026-11-03 模拟器实测：改成「锚 leading 行就原地不动」
                                // 仍然会跳 —— 用户滑到最顶时视口首项正是「载入更早」按钮，
                                // 新正文插在它**下面**，一屏就把原来读的那条顶下去
                                // （TAILBODY-41 从 y=445 掉到 y=1011，位移 566px）。
                                // 所以锚点必须取**第一条真正可见的消息行**，并记下它的顶边
                                // 到视口顶边的像素距离，加载后按同一距离复位。
                                val leading = stickyLeadingFor(rows, state)
                                val layout = listState.layoutInfo
                                val firstRow = layout.visibleItemsInfo.firstOrNull { it.index >= leading }
                                val idx = firstRow?.index ?: leading
                                val rowIndex = idx - leading
                                pendingAnchor = TimelineAnchor(
                                    key = rows.getOrNull(rowIndex)?.let { timelineAnchorOf(it) },
                                    rowIndex = rowIndex.coerceAtLeast(0),
                                    listIndex = idx,
                                    // `requestScrollToItem` 的 scrollOffset 是「该项顶边越过
                                    // 视口顶边多少像素」，与 `firstVisibleItemScrollOffset`
                                    // 同向；顶边在视口顶边之下时取负值，才能把它推回原位。
                                    offset = firstRow?.let { layout.viewportStartOffset - it.offset } ?: 0,
                                    seq = rows.getOrNull(rowIndex)?.seq ?: 0L,
                                )
                                // 点「载入更早」就是明说「我要往上读」——当场交出跟随权。
                                //
                                // 不交的话，新正文落进 `rows` 会让 `state.revision` 变，
                                // 贴底 effect（`LaunchedEffect(state.revision)` 与
                                // `StickToBottomOnGrowth`）立刻把人拽回尾部，锚点算得再准
                                // 也被覆盖掉（2026-11-03 模拟器实测：锚点 target=31 执行了，
                                // 屏幕上 TAILBODY-41 仍从 y=445 掉到 y=1011）。
                                // 之后用户自己滑回底部，滚动停止边沿会把跟随权收回来。
                                followTail = false
                                chat.loadEarlier()
                            },
                        )
                    }
                }
                if (!state.snapshotReady) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                if (state.stateLabel.isNotEmpty()) state.stateLabel else "正在载入对话…",
                                fontSize = 13.sp,
                                color = Ds.textMuted,
                            )
                            if (state.snapshotTotal > 0) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "已读取 ${state.snapshotDone}/${state.snapshotTotal}",
                                    fontSize = 11.sp,
                                    color = Ds.textMuted,
                                )
                            }
                        }
                    }
                }
                // key：seq 是宿主给的稳定身份；但处理面板与同回合正文共用 seq，
                // 面板得按它自己的位置（order = seq-0.25）取键，否则两者撞键直接崩；
                // seq<=0 的消息（本地回声/占位）可能多条且 hashCode 会碰撞，退回索引。
                itemsIndexed(
                    rows,
                    key = { index, m -> timelineKey(index, m) },
                ) { _, m ->
                    MessageRow(
                        message = m,
                        onRetry = { chat.retry(m) },
                        onDelete = { chat.deleteLocal(m) },
                        showsThinking = showsThinking,
                        // 会话还在跑 = 这一轮可能还没收尾，折叠行不能提前报操作数。
                        sessionRunning = state.running,
                        onOpenProcess = { openProcess = panelTargetOf(it) },
                        sessionId = sessionView.id,
                        attachmentsEnabled = attachmentsEnabled,
                        loadAttachment = { image, done ->
                            vm.gateway.loadAttachment(sessionView.id, image) { bitmap, error ->
                                done(bitmap, error)
                            }
                        },
                    )
                }
                if (state.streamingText.isNotEmpty()) {
                    // 正在吐的那段走打字机：宿主一次灌几十个字时，整段替换看着像卡顿。
                    item { AssistantText(state.streamingText, streaming = true) }
                }
                // 长推理进度行已移除（2026-10-30 用户要求「『正在深度思考』文案去掉」）。
                // 折成一行之后，「还在想」由折叠行的前导图标旋转 + 尾部思考预览表达，
                // 再单独占一行只会和时间轴末尾的面板重复报同一件事。
            }

            // 「跳转到最新消息」：用户往上翻历史后，新内容不再自动把他拽回底部，
            // 由这个胶囊把控制权还给他。点一下 = 恢复跟随 + 滚到尾部。
            val tail = tailItemIndex(state, rows.size)
            if (showJumpToLatest(followTail, tail)) {
                JumpToLatestButton(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 12.dp),
                    onClick = {
                        followTail = true
                        // 同一个「真贴底」口径：不带偏移会把长正文的首行钉在顶部。
                        scope.launch { listState.scrollToItem(tail, tailScrollOffset(itemHeightOf(tail))) }
                    },
                )
            }

            // 「任务: n/n」：宿主 todo_write 的进度，浮在消息区右上角。
            // 放在这里而不是顶栏，是因为顶栏右侧已经被「更多」占了；浮层还能
            // 在滚动时一直可见，不用回头找。没写过任务（total=0）就整块不渲染。
            // 冻顶的用户气泡：**盖在列表之上**，不占布局空间。
            //
            // 2026-11-02 用户实测「还是横向一条白色卡片作底」：之前它被放在
            // 外层 Column 的流里（顶栏与列表之间），于是无论气泡本身多窄，那一行
            // 都会占据一条全宽的区域、透出页面底色，看起来就是一条白带。
            // 真正的悬浮必须放进消息区这个 Box，`align` 叠在列表上。
            //
            // 靠右（`TopEnd`）而不是居中：用户消息在时间轴里本来就是右对齐的
            // （`UserBubble` 是 `fillMaxWidth + Arrangement.End`），冻顶条是它的替身，
            // 位置必须跟原身一致。居中会让人以为「一冻就变成了别的东西」。
            // 右边距取 16dp 与 `LazyColumn` 的 `contentPadding.horizontal` 对齐。
            val stickyLeading = stickyLeadingFor(rows, state)
            val sticky = stickyUserBubble(rows, firstVisibleIndex, lastVisibleIndex, stickyLeading)
            if (sticky != null) {
                StickyUserBubble(
                    text = sticky.text,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 6.dp, end = 16.dp),
                ) {
                    // 按消息身份找它自己；找不到就不动（`requestScrollToItem` 在
                    // 下一帧布局前生效，不会被点按手势刚结束时的滚动抑制吞掉）。
                    val target = stickyBubbleTarget(rows, sticky, stickyLeading)
                    if (target >= 0) {
                        // 点冻顶气泡就是明说「我要上去读那一条」——当场交出跟随权，
                        // 与「载入更早」同一口径。
                        //
                        // 不交的话：`followTail` 只在「滚动停止」边沿更新，而用户
                        // 停在底部时它是 true；点击触发的程序化滚动还没走完，
                        // 下一帧 `state.revision` 一变（流式输出每秒十几次），
                        // `LaunchedEffect(state.revision)` 与 `StickToBottomOnGrowth`
                        // 立刻 `scrollToItem(tail)` 把人拽回底部 —— 观感就是
                        // 「点了没反应」。2026-11-03 用户实测「屏幕滑到最低端，
                        // 点击冻结气泡，没有发生跳转」；模拟器截图对比确认列表
                        // 其实动过一点又被拉回（视口顶部从「没有连接设备…」变成
                        // 更靠后的「模拟器已启动…」再回落）。
                        followTail = false
                        listState.requestScrollToItem(target, 0)
                    }
                }
            }

            // 草稿（`id == ""`：点「新建」只开本地草稿）与空会话在时间轴里一条消息都
            // 没有，整页就只剩顶栏和输入框一片空白，像没加载出来（F-05）。
            // 复刻首页空态的视觉语言（同一张图、同一个 22sp 标题口径）。
            val emptyChat = rows.isEmpty() && state.snapshotReady &&
                state.approvals.isEmpty() && state.questions.isEmpty() &&
                state.streamingText.isBlank()
            if (emptyChat) {
                EmptyChat(draft = sessionView.id.isEmpty(), hostName = sessionView.subtitle)
            }

            // 待答卡片（审批 / 提问）**仍然悬浮**在消息区底部：它跟的是「某条消息 /
            // 某一步」，贴在滚动内容的下沿才对得上出处，而且不该把整条时间轴往上顶。
            //
            // 胶囊行不在这里了 —— 见 Box 外面那一段（2026-11-10 用户口径：
            // 「这些胶囊出现时，独占一行，不要悬浮，未出现时回收空白行」）。
            val pending = pendingCards(state)
            if (pending.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        // 自然高度、贴着输入框上沿；只有真的堆到超高才封顶并
                        // 内部滚动。**不能**用 fillMaxHeight：那会让内层固定占
                        // 掉一大块，卡片被顶到屏幕中间、底下空一片（实测
                        // 2026-10-31 第一版就是浮在半空的）。
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (a in pending.filterIsInstance<Model.Approval>()) {
                        ApprovalCard(
                            approval = a,
                            onAllow = { chat.answerApproval(a, true) },
                            onDeny = { chat.answerApproval(a, false) },
                        )
                    }
                    for (c in pending.filterIsInstance<Model.QuestionCard>()) {
                        QuestionCardView(
                            card = c,
                            onSubmit = { chat.answerQuestions(c) },
                        )
                    }
                }
            }
        }

        // 胶囊行（任务清单 / 后台任务 / 子智能体）：**独占一行、不悬浮**。
        //
        // 2026-11-10 用户口径：「这些胶囊出现时，独占一行，不要悬浮，未出现时回收
        // 空白行」。所以它落在消息区与输入框之间的布局流里 —— 不叠在正文上，也不会
        // 被最后几行文字压住；`capsules.isEmpty()` 时整行根本不发射，一行空白都不占。
        //
        // 位置沿革：更早挂在消息区右上角 `TopEnd`（跟输入框隔着整片滚动区，长任务里
        // 没人会注意到），后来挪到消息区底部 `BottomCenter` 悬浮（压在最后几行正文
        // 上）。两版都栽在「悬浮」这条上。
        //
        // 与待答卡片同时出现时卡片在**上面**（它属于消息区）、胶囊行贴着输入框 ——
        // 这翻转了 2026-11-08「卡片在下」的排法，是本次改动的直接后果：胶囊既然退出
        // 悬浮、进了布局流，就只能排在输入框正上方。
        val capsules = capsuleKinds(sessionView.todos, state.jobs, sessionView.subagents)
        if (capsules.isNotEmpty()) {
            CapsuleRow(
                kinds = capsules,
                onOpen = { kind ->
                    when (kind) {
                        CapsuleKind.TASKS -> showTodos = true
                        CapsuleKind.JOBS -> showJobs = true
                        CapsuleKind.SUBAGENTS -> showSubagents = true
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            )
        }

        Composer(
            chat = chat,
            session = sessionView,
            running = state.running,
            pending = state.pending,
            attaching = state.attaching,
            queuedBusy = state.queuedBusy,
            onPickImage = {
                pickImage.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                    ),
                )
            },
            onTakePhoto = {
                val target = com.dsh.mobile.v2.data.AttachmentLoader.newCaptureTarget(context)
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.files", target,
                )
                captureTarget = target
                takePhoto.launch(uri)
            },
            onPickFile = { pickFile.launch(arrayOf("*/*")) },
            onSend = { text, steer -> chat.send(text, steer) },
            onCancel = { chat.cancel() },
        )
    }

    // 脚注/思考步骤/工具行都指着同一个抽屉：点哪一处都打开这条消息的处理时间线。
    // 不接这里的话 openProcess 只有写入没有读取，点「打开模型处理详情」什么都不会发生。
    openProcess?.let { target ->
        // 按身份回 rows 里取最新那份：流式增量每帧都会替换对象，抽屉跟着一起长。
        //
        // 但**取不到时不能就这么消失**：重新快照（`onSnapshot` 整表替换）会让面板
        // 重新发号，旧 panelId 就再也找不到了；那时 `resolvePanel` 返回 null，抽屉
        // 整块不渲染 —— 用户看到的是「抽屉自己关了」（2026-10-30 反馈）。
        // 所以留住最后一次成功取到的那份，宁可内容冻住也不让它凭空消失。
        val live = resolvePanel(rows, target.panelId, target.turn, target.stepId) ?: lastPanel[0]
        if (live != null) {
            // 身份判等后再记：写 state 会触发重组，不判等的话流式每帧都多跑一轮整合。
            if (live !== lastPanel[0]) SideEffect { lastPanel[0] = live }
            ProcessTimelineSheet(
                message = live,
                onDismiss = {
                    openProcess = null
                    lastPanel[0] = null
                },
            )
        }
    }

    // 任务清单抽屉：传的是此刻的 todos（不是打开那一刻的快照），
    // 于是宿主推到新进度时，抽屉里的图标与底部计数当场跟着变。
    if (showTodos) {
        TaskListSheet(todos = sessionView.todos, onDismiss = { showTodos = false })
    }

    // 后台任务 / 子智能体两张清单：与任务清单同一套口径 —— 传的是**此刻**的数据，
    // 不是打开那一刻的快照，所以宿主推进时清单里的内容当场跟着变。
    if (showJobs) {
        JobListSheet(jobs = state.jobs, onDismiss = { showJobs = false })
    }
    if (showSubagents) {
        // 只列还在跑的：胶囊点开是为了看「现在谁在干活」，把派过的历史名单全倒出来
        // 只会让人以为它们还在跑（与胶囊本身的判据同一口径，见 [liveSubagents]）。
        SubagentListSheet(
            subagents = liveSubagents(sessionView.subagents),
            onDismiss = { showSubagents = false },
        )
    }
}

// ============================================================
// 顶栏
// ============================================================

/**
 * 冻顶的用户气泡：**浮在列表之上**的一枚胶囊。
 *
 * <p>用户口径（2026-11-01/02）：「按现有用户消息气泡样式展示，最多一行、多余省略」
 * ＋「不要背景白色条，要悬浮效果」。
 *
 * <p>关键不在于气泡本身怎么画，而在于**它在哪一层**：早先它作为外层 `Column` 的
 * 一个子项排在顶栏与列表之间，那一行天然占满宽度、透出页面底色，于是无论胶囊多窄，
 * 看起来都是一条白带。现在它由调用方 `align(TopCenter)` 叠在消息区 `Box` 上，
 * 只占自己那点面积，下面直接是滚动的正文 —— 这才是「浮」。
 *
 * <p>胶囊自身：复用 `bubbleUserBg` 底色与 14dp 圆角，`shadow` 造出离页面的高度
 * （用阴影而不是描边：描边在深色底上是一圈生硬的线，阴影在任何底色上都读作「浮起」）。
 */
@Composable
private fun StickyUserBubble(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(14.dp),
                ambientColor = Color.Black.copy(alpha = 0.20f),
                spotColor = Color.Black.copy(alpha = 0.26f),
            )
            .background(Ds.bubbleUserBg, RoundedCornerShape(14.dp))
            .dsClickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_lucide_corner_down_left),
            contentDescription = null,
            tint = Ds.bubbleUserText.copy(alpha = 0.55f),
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text.replace('\n', ' '),
            fontSize = 13.sp,
            color = Ds.bubbleUserText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ChatHeader(title: String, subtitle: String, onBack: () -> Unit, onMore: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .align(Alignment.CenterStart)
                .dsClickableNeutral { onBack() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_chevron_left),
                contentDescription = "返回",
                tint = Ds.textPrimary,
                modifier = Modifier.size(22.dp),
            )
        }
        // v1 4.1：右上「更多」（后台任务 / 重命名 / 删除）
        Box(
            modifier = Modifier
                .size(44.dp)
                .align(Alignment.CenterEnd)
                .dsClickableNeutral { onMore() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_more_horizontal),
                contentDescription = "更多",
                tint = Ds.textPrimary,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = Ds.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = Ds.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ============================================================
// 消息
// ============================================================

/**
 * 「跳转到最新消息」悬浮胶囊（会话详情右下角）。
 *
 * 白底 + 近似全圆头 + 柔和外阴影浮在时间线上；前景走主题靛蓝 [Ds.primary]
 * （对白 4.70，过 AA），不另造一套配色。
 * 高度与顶栏图标按钮同档，右/下留 16/12dp，避开正文与输入框。
 */
@Composable
private fun JumpToLatestButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(50), clip = false)
            .background(Ds.bgElevated, RoundedCornerShape(50))
            .dsClickable(shape = RoundedCornerShape(50)) { onClick() }
            .padding(start = 14.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_scroll_latest),
            contentDescription = null,
            tint = Ds.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "跳转到最新消息",
            fontSize = 13.sp,
            color = Ds.primary,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/**
 * 「跳到底部」悬浮胶囊（一级抽屉内右下角）。
 *
 * 与 [JumpToLatestButton] 是同一个东西的两个位置：抽屉里正文/思考还在往下长，
 * 用户翻上去看前面几步之后，一有新输出就得自己一路拨回来。视觉沿用同一套 token
 * （白底 [Ds.bgElevated] + 全圆头 + 柔和外阴影 + 主题靛蓝前景），但在抽屉里要更轻：
 * 它盖在步骤列表上，尺寸和留白都收一档。
 *
 * 文案从「跳转到最新消息」缩到「跳到底部」：抽屉里没有「消息」，只有步骤。
 */
@Composable
private fun SheetScrollToBottomButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .shadow(elevation = 6.dp, shape = RoundedCornerShape(50), clip = false)
            .background(Ds.bgElevated, RoundedCornerShape(50))
            .dsClickable(shape = RoundedCornerShape(50)) { onClick() }
            .padding(start = 11.dp, end = 13.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_scroll_latest),
            contentDescription = null,
            tint = Ds.primary,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            "跳到底部",
            fontSize = 12.sp,
            color = Ds.primary,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

// ============================================================
// 输入框上方的胶囊行
// ============================================================

/** 一颗胶囊代表什么。枚举顺序就是并排显示的顺序（任务 → 后台任务 → 子智能体）。 */
internal enum class CapsuleKind { TASKS, JOBS, SUBAGENTS }

/**
 * 子智能体名单里「还该出现」的那些。
 *
 * <p>`subagentCatalog` 是**派过的名单**，不是「在跑的名单」：一条长会话派过十几个
 * 子代理之后，它们全都已经结算，名单却还在长（用户 2026-11-10 报的就是这个 ——
 * 胶囊亮着、点开一堆早就结束的）。
 *
 * <p>判据取三态里的「没有明确判死」：`running == false` 才算结束，`true` 和 `null`
 * 都留着。`null` 是「不知道」（老插件不发这个字段），按「还在」处理才不会让升级
 * App 的人凭空丢功能 —— 宁可多留一会儿，也不要静默地少显示东西。
 */
internal fun liveSubagents(subagents: List<Model.Subagent>): List<Model.Subagent> =
    subagents.filter { it.running != false }

/**
 * 这一帧该显示哪几颗胶囊。
 *
 * <p>判据是**有没有内容**、不是「功能开没开」：清单空着就不显示那颗 —— 一排点开
 * 什么都没有的图标比不显示更糟（用户会以为坏了）。所以这个函数只收数据、不看开关，
 * 也因此能脱离 Compose 单测。
 */
internal fun capsuleKinds(
    todos: List<Model.Todo>,
    jobs: List<Model.Job>,
    subagents: List<Model.Subagent>,
): List<CapsuleKind> = buildList {
    if (todos.isNotEmpty()) add(CapsuleKind.TASKS)
    if (jobs.isNotEmpty()) add(CapsuleKind.JOBS)
    if (liveSubagents(subagents).isNotEmpty()) add(CapsuleKind.SUBAGENTS)
}

/**
 * 子智能体模式的人话名字。
 *
 * <p>宿主的全集出自 `SubagentCatalogEntry`：`one-shot` / `external` / `continuable` /
 * `unknown`。认不出的模式**原样显示**、不折叠成「其他」：宿主将来加一种模式时，
 * 用户至少能看见它叫什么，而不是面对一个说谎的「其他」。
 */
internal fun subagentModeLabel(mode: String): String = when (mode) {
    "one-shot" -> "一次性"
    "external" -> "外部"
    "continuable" -> "可续"
    "unknown" -> "未知"
    "" -> "模式未知"
    else -> mode
}

/**
 * 输入框上方那排图标胶囊里的一颗：**只有图标、没有文字**。
 *
 * <p>用户口径（2026-11-08）：「胶囊只要图标，不要文字，点开可以查看」。所以每颗
 * 胶囊必须满足两件事：
 *  - 图标本身能自解释（清单 = `list_checks`、后台任务 = `clock`、子智能体 = `bot`），
 *    并且 `contentDescription` 给读屏一个名字 —— 没有可见文字时它是唯一的无障碍入口；
 *  - **必须可点**：胶囊只说明「有这个东西」，看不到「有几条、卡在哪条」，
 *    那些只有点开才知道（[TaskListSheet] / [JobListSheet] / [SubagentListSheet]）。
 *
 * <p>配色沿用原先那枚任务胶囊：**有东西在动**用品牌靛青（`primarySoft` 底 +
 * `primary` 图标，与待答卡片同一族），**静止/收工**退回中性白底灰图标。
 * 三颗胶囊同一套配色，不引入新色（用户：「主题色不变」）。
 */
@Composable
private fun CapsulePill(kind: CapsuleKind, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val icon = when (kind) {
        CapsuleKind.TASKS -> R.drawable.ic_lucide_list_checks
        CapsuleKind.JOBS -> R.drawable.ic_lucide_clock
        CapsuleKind.SUBAGENTS -> R.drawable.ic_lucide_bot
    }
    val name = when (kind) {
        CapsuleKind.TASKS -> "任务清单"
        CapsuleKind.JOBS -> "后台任务"
        CapsuleKind.SUBAGENTS -> "子智能体"
    }
    // 任务与后台任务都是「有东西在跑」，用品牌色；子智能体目录只是名单，
    // 宿主没有给「这个子智能体还在不在跑」的字段，所以不冒充活跃。
    val live = kind != CapsuleKind.SUBAGENTS
    val fg = if (live) Ds.primary else Ds.textSecondary
    val bg = if (live) Ds.primarySoft else Ds.bgSurface
    val edge = if (live) Ds.primarySoftBorder else Ds.borderStrong
    Box(
        modifier = modifier
            // 阴影先画、clip 后夹：clip=true 会在阴影外面再裁一刀，浮起感就没了。
            .shadow(elevation = 4.dp, shape = RoundedCornerShape(50), clip = false)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .dsClickable(shape = RoundedCornerShape(50), onClick = onOpen)
            .border(1.dp, edge, RoundedCornerShape(50))
            .size(30.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = name,
            tint = fg,
            modifier = Modifier.size(15.dp),
        )
    }
}

/**
 * 有几颗就并排画几颗；间距交给 `spacedBy`，不必自己插 Spacer。
 *
 * <p>整行居中：调用点给的是 `fillMaxWidth()`（它独占消息区与输入框之间的一行），
 * 行宽大于内容，不写对齐就会贴左边。`spacedBy(space, alignment)` 的 alignment 只在
 * 「行内有富余」时才起作用，所以对「窄到刚好裹住内容」的用法同样安全。
 */
@Composable
private fun CapsuleRow(
    kinds: List<CapsuleKind>,
    onOpen: (CapsuleKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (kind in kinds) {
            CapsulePill(kind = kind, onOpen = { onOpen(kind) })
        }
    }
}

/**
 * 后台任务清单：点「后台任务」那颗胶囊打开。
 *
 * <p>数据是 `Projector.jobsOf` 从 `job/registered` / `job/settled` 现算的
 * 瞬时视图（宿主的投影表里**没有** jobs），所以这里只画「此刻在跑」的那些；
 * 已经结算的任务不会留在列表里 —— 那不是遗漏，是它本来就不该在这里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JobListSheet(jobs: List<Model.Job>, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // 与另外两张清单同一口径：内容区自己滚动，抽屉不跟手指走
        // （滑到顶/底再往回滑会卡住，见坑 89）。
        sheetGesturesEnabled = false,
        containerColor = Ds.bgSurface,
    ) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = sheetHeight())) {
            Text(
                "后台任务",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Ds.textPrimary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = if (jobs.isEmpty()) "此刻没有在跑的任务" else "${jobs.size} 个在跑",
                fontSize = 12.sp,
                color = Ds.textMuted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp),
            )
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Ds.divider))
            if (jobs.isEmpty()) {
                Text(
                    "宿主起的后台任务会出现在这里；跑完就自动消失。",
                    fontSize = 13.sp,
                    color = Ds.textMuted,
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                ) {
                    itemsIndexed(jobs, key = { _, job -> job.id }) { index, job ->
                        JobRow(index = index, job = job)
                    }
                }
            }
        }
    }
}

/** 后台任务清单里的一行：转圈 + 标签 + 已运行时长。 */
@Composable
private fun JobRow(index: Int, job: Model.Job) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.width(22.dp).padding(top = 1.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Spinner(size = 13.dp, color = Ds.primary)
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                // 宿主没给 label 时才退到 kind；两个都空就报序号，
                // 免得列表里出现一行完全空白的条目（点了也没内容可看）。
                text = job.label.ifBlank { job.kind.ifBlank { "（第 ${index + 1} 个任务）" } },
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = Ds.textPrimary,
                fontWeight = FontWeight.Medium,
            )
            val elapsed = job.elapsedLabel()
            if (elapsed.isNotEmpty()) {
                Text(elapsed, fontSize = 11.sp, color = Ds.textMuted)
            }
        }
    }
}

/**
 * 子智能体清单：点「子智能体」那颗胶囊打开。
 *
 * <p>数据是宿主的 `subagentCatalog`（`session/projections` 的
 * `values.subagentCatalog`）。它是**目录**，不是运行状态：宿主只给
 * `{id, createdAt, mode, label?}`，没有「还在不在跑」，所以这一屏只报
 * 「这条会话起过哪些子智能体」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubagentListSheet(subagents: List<Model.Subagent>, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        containerColor = Ds.bgSurface,
    ) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = sheetHeight())) {
            Text(
                "子智能体",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Ds.textPrimary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = if (subagents.isEmpty()) "这条会话没有子智能体" else "共 ${subagents.size} 个在跑",
                fontSize = 12.sp,
                color = Ds.textMuted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp),
            )
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Ds.divider))
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            ) {
                itemsIndexed(subagents, key = { _, s -> s.id }) { index, sub ->
                    SubagentRow(index = index, sub = sub)
                }
            }
        }
    }
}

/** 子智能体清单里的一行：机器人图标 + 名字 + 模式。 */
@Composable
private fun SubagentRow(index: Int, sub: Model.Subagent) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.width(22.dp).padding(top = 1.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_bot),
                contentDescription = null,
                tint = Ds.textSecondary,
                modifier = Modifier.size(14.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                // label 只有 `continuable` 一定存在，其余模式是可选的 —— 空就报序号。
                text = sub.label.ifBlank { "（第 ${index + 1} 个子智能体）" },
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = Ds.textPrimary,
            )
            Text(subagentModeLabel(sub.mode), fontSize = 11.sp, color = Ds.textMuted)
        }
    }
}

/**
 * 任务清单抽屉：点「任务: n/n」胶囊打开。
 *
 * 胶囊只报数，看不出「哪几条做完了、现在卡在哪条」——长任务里那正是最想知道的。
 *
 * 三条约束：
 * 1. **跟着 live 数据走**：todos 由调用方每帧从 `state.session.todos` 读，
 *    宿主推新进度时抽屉里的图标与底部计数当场变，不需要关掉重开。
 * 2. **进行中单独标出来**：计数只认 `completed`（见 `taskProgressOf`），但清单里
 *    `in_progress` 必须与待办区分（转圈 vs 空心圈），否则看不出此刻停在哪儿。
 * 3. **配色只走主题 token**：完成用 `statusSuccess`，进行中用品牌靛青，
 *    待办用 `textMuted`。不引入清单自己的新色。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskListSheet(todos: List<Model.Todo>, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val progress = taskProgressOf(todos)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // 抽屉只靠「点遮罩 / 系统返回」关闭，拖拽手势整个关掉。
        //
        // M3 1.3 曾用「在正文里主通道消费指针」来挡这个手势，两轮都不成立：`draggable`
        // 不看 `isConsumed`，所以抽屉照样跟着手指走；反而把正文自己的滚动手势掐死，
        // 滑到顶/底再往回滑就卡住（2026-11-06 用户：「滑到最顶端或者最底端的时候再次
        // 滑动，就会卡住，像是失灵了一样」）。material3 1.4 才给出这个开关。
        sheetGesturesEnabled = false,
        containerColor = Ds.bgSurface,
    ) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = sheetHeight())) {
            Text(
                "任务清单",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Ds.textPrimary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "${progress.done}/${progress.total} 已完成",
                fontSize = 12.sp,
                color = Ds.textMuted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp),
            )
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Ds.divider))
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            ) {
                itemsIndexed(todos) { index, todo ->
                    TodoRow(
                        index = index,
                        todo = todo,
                        // 宿主一轮里同时只会有一条 in_progress；万一有多条，
                        // 也都显示转圈——那是数据本身的问题，不该在这里替它挑一条。
                        mark = todoMarkOf(todo),
                    )
                }
            }
        }
    }
}

/** 清单里的一行：状态图标 + 文案。文案支持换行，长任务标题不该被截断。 */
@Composable
private fun TodoRow(index: Int, todo: Model.Todo, mark: TodoMark) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.width(22.dp).padding(top = 1.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            when (mark) {
                TodoMark.DONE -> Icon(
                    painter = painterResource(R.drawable.ic_lucide_check_circle),
                    contentDescription = "已完成",
                    tint = Ds.statusSuccess,
                    modifier = Modifier.size(14.dp),
                )

                TodoMark.DOING -> Spinner(size = 13.dp, color = Ds.primary)

                TodoMark.TODO -> Icon(
                    painter = painterResource(R.drawable.ic_lucide_circle_dashed),
                    contentDescription = "待办",
                    tint = Ds.textMuted,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = todo.content.ifBlank { "（第 ${index + 1} 条）" },
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = when (mark) {
                TodoMark.DONE -> Ds.textMuted
                TodoMark.DOING -> Ds.textPrimary
                TodoMark.TODO -> Ds.textSecondary
            },
            fontWeight = if (mark == TodoMark.DOING) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LoadEarlierRow(busy: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy) { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            Spinner(size = 16.dp, color = Ds.primary, strokeWidth = 2.dp)
        } else {
            Text("载入更早的消息", fontSize = 13.sp, color = Ds.primary)
        }
    }
}

/**
 * 一条消息 → 视图段。
 *
 * **只有两块**（2026-10-29 用户定）：
 * 1. 操作行：整轮所有思考/工具/技能折成**一行**（[InlineProcess] → [FoldedStepsRow]）；
 * 2. 助手正文。
 *
 * 回合还没产出任何步骤时**什么都不画**——原先这里有个「正在分析请求」的实时状态面板，
 * 用户明确要求删掉：一轮刚开始的那几秒，一条空面板比没有更吵。
 *
 * 「深度思考」不再单独占一行：它只是 steps 里的一个 THINKING 步骤，早先与
 * 「已处理 N 个操作」并列成两块，真机上就是上下各冒一行（2026-10-27 / 10-29 反馈）。
 */
@Composable
private fun MessageRow(
    message: Model.Message,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    showsThinking: Boolean,
    sessionRunning: Boolean,
    onOpenProcess: (Model.Message) -> Unit,
    sessionId: String,
    attachmentsEnabled: Boolean,
    loadAttachment: (Model.ImageRef, (Bitmap?, String?) -> Unit) -> Unit,
) {
    if (message.role == Model.Role.USER) {
        UserBubble(
            message = message,
            onRetry = onRetry,
            onDelete = onDelete,
            sessionId = sessionId,
            attachmentsEnabled = attachmentsEnabled,
            loadAttachment = loadAttachment,
        )
        return
    }
    // 失败面板可能**只有失败原因、没有任何步骤**（供应商在第一步就 429 了）。
    // 漏掉 `failure` 这一项，那一轮就整块不画 —— 用户看到的是「发完就没下文」，
    // 比显示一条错误还糟（2026-11-01 实测：记录已落库、投影也带上了 failure，
    // 界面上什么都没有）。
    if (message.text.isBlank() && message.steps.isEmpty() && message.failure.isBlank()) return
    // 这一轮是否还在跑：宿主每步都发 step/end，`message.running` 走完一步就 false，
    // 但它可能紧接着吐正文或开下一步。整轮结束的标记是 turn/end 打的 footnote。
    val live = foldLive(message, sessionRunning)
    // 记录里这一轮还开着、会话却早就不在跑了：宿主那轮没等到 `turn/end` 就消失了
    // （进程被杀、断网、或者换中继后手机还盯着旧主机）。这种情况下既不能转圈
    // 也不能写「已完成」——2026-11-05 现场就是这样：桌面那轮停在 t45，手机永远「运行中」。
    val stranded = message.running && !sessionRunning
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // folded=整块步骤已经合成一行摘要，它自己就带着「已处理 N 个操作」，
        // 再挂一条同样的页脚就是同一句话说两遍。
        var folded = false
        // **只有失败、没有步骤**也要画这一行：供应商在第一步就 429 时，这一轮
        // 一个 step 都没有，早先的条件把整块跳过 —— 界面上就是「发完没下文」
        // （2026-11-01 实测：投影里 `fails=1`、记录已落库，屏幕上一个字都没有）。
        if (message.steps.isNotEmpty() || message.failure.isNotEmpty()) {
            folded = InlineProcess(message, showsThinking, live, stranded, onOpenProcess)
        }
        // 正在长的那段正文走打字机（`message.streaming` 由投影器打在流式消息上）；
        // 落定的正文原样整段渲染。
        if (message.text.isNotBlank()) AssistantText(message.text, streaming = message.streaming)
        // 失败原因**单独一行**，不塞进折叠摘要。
        //
        // 为什么不拼进 `foldSummary`：那一行会被 `mergeTurnPanels` 合并、被
        // `timelineOf` 按位置去重，而失败挂在**最后一段**上、合并体沿用第一段的身份
        // ——实测（2026-11-01）走了七八轮才定位到文案在合并后丢掉。独立渲染不受这些
        // 影响，而且视觉上也更对：错误该自己占一行，不该被挤进「已处理 N 个操作」。
        if (message.failure.isNotBlank()) FailureLine(message.failure)
        // 页脚只挂在这一轮最后一块面板上（累加器按 step 拆面板后打标），
        // 每块都挂会让一轮看起来像好几轮。
        if (!folded && message.footnote && !message.running && message.processTitle().isNotBlank()) {
            ProcessFootnote(message.processTitle()) { onOpenProcess(message) }
        }
    }
}

/**
 * 失败原因（供应商 429 限流、上下文超长、鉴权失败等）。
 *
 * 原文可能很长——截图里那条 429 响应体 300 多字，带着退避时间、上游地址、是否可重试。
 * 桌面端就是整段摊开给人看的，这里也整段显示，不做截断：排障需要的正是这些细节。
 */
@Composable
private fun FailureLine(failure: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_lucide_shield_alert),
            contentDescription = null,
            tint = Ds.statusError,
            modifier = Modifier.padding(top = 2.dp).size(15.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            failure,
            fontSize = 13.sp,
            color = Ds.statusError,
            lineHeight = 20.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 内联步骤流：整轮操作折成**一行**，旧缓存里的旁白（[Model.Step.TEXT]）原样内联。
 *
 * 连续的思考/工具步骤合并成一行摘要（[groupSteps]）——一轮里五六个工具调用
 * 逐条平铺会把消息流撑成一面墙，正文被挤出屏幕外；而思考与工具**同属「操作」**，
 * 分成两行会让「已处理 N 个操作」和「深度思考」上下并排（2026-10-29 反馈）。
 *
 * 摘要行**任何时候都报操作数**（运行中是 `已处理 N 个操作 · 思考：<尾部>`，
 * 落定后是 `已处理 N 个操作 · 用时 X 秒`）：早先运行中返回空串，真机上那一行只剩
 * 一段无头无尾的思考正文（2026-10-29 用户反馈「『已处理X个操作』也没有出现」）。
 *
 * @return 是否有步骤被折成摘要行（调用方据此省掉重复的页脚）。
 */
@Composable
private fun InlineProcess(
    message: Model.Message,
    showsThinking: Boolean,
    live: Boolean,
    /** 这一轮在记录里还开着，但会话已经不在跑了（见 [MessageRow] 的 `stranded`）。 */
    stranded: Boolean,
    onOpenProcess: (Model.Message) -> Unit,
): Boolean {
    val visible: (Int, Model.Step) -> Boolean = { index, step ->
        when (step.kind) {
            // 面板最后一段旁白常常与正文同文（旧缓存才会产生），别渲染两遍。
            Model.Step.TEXT -> index < message.steps.lastIndex || step.text.trim() != message.text.trim()
            Model.Step.THINKING -> showsThinking && step.text.isNotBlank()
            else -> step.actionIndex in message.actions.indices
        }
    }
    var folded = false
    val groups = groupSteps(message.steps, visible)
    // 一个步骤都没有、但有失败原因：`groupSteps` 只会返回空列表，这里补一条
    // 只有摘要的行（`Fold` 的 indices 为空，`foldSummary` 靠 failure 出文案）。
    if (groups.isEmpty() && message.failure.isNotEmpty()) {
        FoldedStepsRow(
            title = foldSummary(
                steps = message.steps,
                indices = emptyList(),
                running = live && !stranded,
                totalTools = message.toolCount(),
                failure = message.failure,
                interrupted = stranded,
            ),
            running = false,
            thinking = "",
            activity = null,
            footnote = message.footnote,
            tookLabel = message.tookLabel(),
        ) { onOpenProcess(message) }
        return true
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        for (group in groups) {
            when (group) {
                is StepGroup.Single -> MarkdownText(
                    source = message.steps[group.index].text,
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                is StepGroup.Fold -> {
                    folded = true
                    FoldedStepsRow(
                        title = foldSummary(
                            steps = message.steps,
                            indices = group.indices,
                            // 运行中也报操作数（2026-10-29 用户要求：折叠行始终看得到
                            // 「已处理 X 个操作」，运行中只是不带用时）。
                            // 中断的那一轮不转圈：它的「运行中」是记录里的历史状态，
                            // 不是此刻的事实（见 [MessageRow] 的 `stranded`）。
                            running = live && !stranded,
                            interrupted = stranded,
                            // 用时只在**整轮完结、而且这是这一轮最后一块面板**时给。
                            // 只看 message.running 不够：一步做完 step/end 就会把它置 false，
                            // 而这一轮还要继续跑下一步，于是中途冒出一句「用时 42 秒」
                            // 像是已经结束了（2026-10-28 截图反馈）。footnote 由 turn/end 打。
                            took = if (!live && message.footnote) message.tookLabel() else "",
                            // 计数口径取整块面板的 toolCount()——抽屉标题数的是同一个数
                            // （2026-10-29 反馈「写着 7 个操作，点开只有 5 条」）。
                            totalTools = message.toolCount(),
                            // 供应商/模型层报错（429 限流、上下文超长、鉴权失败）挂在
                            // `turn/end` 的 `data.reason` 上，由 Projector 落进 failure。
                            // 不显示的话，手机端看到的就是「回合莫名其妙结束了」。
                            failure = message.failure,
                        ),
                        running = live && !stranded,
                        // 只有「还在跑」才贴活思考：折起来之后没有别的信号能证明它真在
                        // 推进（2026-10-28 反馈）。落定的面板交给抽屉，别在这里占地方。
                        // 用户没开「显示思考」就别往摘要里塞思考正文。
                        thinking = if (live && !stranded && showsThinking) {
                            foldedThinkingText(message.steps, group.indices)
                        } else {
                            ""
                        },
                        // 此刻在干什么：思考 / 执行命令 / 技能名，直接顶掉「思考」那个前缀
                        // （2026-11-01 口径）。落定后照样给，回看时要能看出这一轮用了什么工具。
                        // 中断后不再报「此刻在干什么」——那个操作早就不在了。
                        activity = if (stranded) null else currentActivity(message),
                        showsThinking = showsThinking,
                        // 右侧耗时只在整轮完结时给；运行中留空（用户 2026-11-01）。
                        footnote = message.footnote,
                        tookLabel = message.tookLabel(),
                    ) { onOpenProcess(message) }
                }
            }
        }
    }
    return folded
}

/**
 * 折叠行：一行灰字 + 图标 + 尾部当前操作名 + 箭头，点开进抽屉看逐条步骤。
 *
 * 与页脚 [ProcessFootnote] 视觉一致（用户会把它们当成同一种东西）。
 *
 * **运行中**（[running]）：前导图标旋转，正文拼成
 * `已处理 N 个操作 · 思考：<尾部预览>`——操作数和「正在思考」同时可见。
 *
 * 早先运行中 [title] 是空串、正文只贴思考尾部，真机上那一行就只剩一段无头无尾的
 * 思考正文（2026-10-29 用户反馈「『已处理X个操作』也没有出现」）。现在
 * **操作数一直报**，思考正文退到「思考：」标签后面当补充。
 *
 * [thinking] 非空时（只在回合还在跑时给）拼在「思考：」后面，取尾部预览——折起来之后
 * 这是唯一能看出「它真的在想」的信号。
 *
 * [activity] 是「此刻在干什么」（执行命令 / 加载技能 xxx）。**任何时候都显示**，
 * 唯一的例外是它本身就是一句「思考中」、而 `title` 里已经写了「思考：<尾部>」——
 * 那是同一句话说两遍。
 *
 * 早先的判据是「只在没有活思考时才显示」，于是工具名反而永远看不到：有活思考时被
 * 「思考：…」挤掉，收尾之后整行只剩「已处理 N 个操作」，回看完全看不出这一轮用了
 * 什么工具（2026-10-31 用户要求「其他工具调用/技能等等也需要展示名称」）。
 */
@Composable
private fun FoldedStepsRow(
    title: String,
    running: Boolean,
    thinking: String = "",
    activity: ProcessActivity? = null,
    showsThinking: Boolean = true,
    footnote: Boolean = false,
    tookLabel: String = "",
    onOpen: () -> Unit,
) {
    // 前导图标**统一一颗星星**（2026-10-29 用户要求「外面统一用星星图标」）。
    // 早先按 activity.kind 换成 terminal / package：那颗 terminal 画出来是
    // 「上面一个盖子、下面一个箭头」，看着像下载/收起，跟「正在执行命令」毫无关系，
    // 用户第一反应是「这是干什么的、去掉」。何况折成一行之后，
    // 「在跑什么」由尾部操作名（activity.text）已经说清了，图标不必再重复表达一遍。
    // 抽屉里仍按类型区分（思考 sparkles / 技能 package / 工具 check_circle）——
    // 那里是逐条平铺的，图标承担的是「分辨相邻两行」的职责，性质不同。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .dsClickable { onOpen() }
            .padding(vertical = 6.dp),
    ) {
        // ---------- 第一行：已处理 N 个操作 · 操作名称 · 小标题 ----------
        //
        // 用户口径（2026-11-01）：「折叠栏改为 2 行，上方显示已处理… + 操作名称 + 小标题，
        // 下方显示预览内容」。小标题就是桌面端 `工具调用 · ops_bash · 改成十秒桶` 的最后
        // 那一段（模型写在工具参数 `description` 里，见 [Model.ToolAction.subtitle]）。
        //
        // 早先这一行是 `已处理 N 个操作 · 执行命令：npm test` —— 操作名与命令挤在同一个
        // Text 里，命令一长就把「已处理 N 个操作」和操作名一起挤成省略号，回看完全不知道
        // 这一轮做了什么。现在操作名与小标题留在第一行（末尾省略，最坏也只切掉小标题的
        // 尾巴），命令挪到第二行独占宽度。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 运行中前导图标**转起来**：折成两行之后，只有这一个信号能说明
            // 「里面还有没跑完的操作」。早先只有「还没认出在干什么」时才转圈，
            // 一旦认出是思考/命令/技能就换成静态图标，看着像已经结束了
            // （2026-10-29 用户要求「包含正在运行的操作时折叠栏图标要旋转」）。
            SpinningLeadIcon(icon = R.drawable.ic_lucide_sparkles, running = running)
            Spacer(Modifier.width(6.dp))
            Text(
                text = foldHead(title, activity, running),
                fontSize = 13.sp,
                color = if (running) Ds.textMuted else Ds.textSecondary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 右侧只放**耗时**，而且只在这条已经跑完的时候放。
            //
            // 用户口径（2026-11-01）：「已经完成的操作右侧是步骤的执行时间，
            // 未完成时不显示」——运行中右侧必须留空：那时还没有时长可说，
            // 挂一句「思考中」既与左边重复，又会让人以为这一步已经结束。
            // 时长取 [Model.Message.tookLabel]（整块面板从开始到收尾）。
            val took = if (!running && footnote) tookLabel else ""
            if (took.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    took,
                    fontSize = 12.sp,
                    color = Ds.textMuted,
                    maxLines = 1,
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_lucide_chevron_right),
                contentDescription = "打开模型处理详情",
                tint = Ds.textMuted,
                modifier = Modifier.size(13.dp),
            )
        }
        // ---------- 第二行：预览内容 ----------
        //
        // 正在思考 → 思考正文的尾部预览（跟着流式长，额度按这一行实际宽度算）；
        // 工具/技能 → 参数摘要（命令、技能名、路径）。没有内容就整行不画，
        // 不留一条空白把行高撑开。
        //
        // 左侧留 6dp 间距 + 13dp 图标 + 6dp 间隔 = 25dp，让第二行跟第一行的文字左对齐。
        var previewWidth by remember { mutableStateOf(0) }
        val charPx = with(LocalDensity.current) { 13.sp.toPx() }
        val preview = foldPreview(
            running = running,
            activity = activity,
            thinking = thinking,
            showsThinking = showsThinking,
            usedWidth = previewWidth,
            charPx = charPx,
        )
        if (preview.isNotEmpty()) {
            Text(
                text = preview,
                fontSize = 13.sp,
                color = Ds.textMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 25.dp, top = 2.dp)
                    .onGloballyPositioned { previewWidth = it.size.width },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 折叠行的前导图标：**运行中让它绕自己转**，落定后静止。
 *
 * 用户要求（2026-10-29）：「折叠栏中包含正在运行的思考/调用/技能/其他未完成操作时，
 * 折叠栏图标需要旋转」——折成一行之后，旋转是唯一能说明「里面还有东西在跑」的信号；
 * 换成菊花又会丢掉「在跑什么」（思考 sparkles / 命令 terminal / 技能 package）这一层信息，
 * 所以保留原图标、只加旋转。
 *
 * 转一圈 1.6 秒、线性匀速：太快像卡顿闪烁，太慢看不出在转。
 * 落定后 [running] = false，直接画静态图标，不残留动画。
 *
 * **动画帧不得触发重组**（2026-10-31，真机 GPU 96% 满帧空转的修复）：早先写的是
 * `val angle by transition.animateFloat(...)` 再 `graphicsLayer { rotationZ = angle }`——
 * `by` 委托让 state 读取发生在组合期间，于是 60fps 的每一帧都重组一次本函数与
 * [Icon]、重建 modifier。真机实测：页面 3/4 空白、只有这颗星与尾部菊花在转，
 * ColorOS GpuWorkReport 却报 96% GPU、`queueBuffer fps=60.2`，烤机 20 分钟机身发热。
 * 修法：**去掉 `by`，把 `.value` 的读取移进 `graphicsLayer` 的 lambda 里**——那是
 * deferred read，动画帧只重执行 layer 块（更新 RenderNode 的 rotationZ），组合树
 * 完全不动。同样的写法适用于所有「无限动画只动一个绘制属性」的场景。
 */
@Composable
private fun SpinningLeadIcon(icon: Int, running: Boolean) {
    if (!running) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = Ds.textTertiary,
            modifier = Modifier.size(13.dp),
        )
        return
    }
    // 满帧推进（60 次/秒），不用 rememberInfiniteTransition。
    //
    // 2026-10-31 真机实测（Android / ColorOS）：成本只跟「每秒变化几次」有关，
    // 同一页面 8fps 是 10% 单核、60fps 是 50%。这里选满帧是因为一屏通常只有
    // 这一个动画（用户正盯着折叠行看，转得顺比省电重要）；列表页那种一屏
    // 几十行的地方才需要压频率，那里是 30fps。
    val stepMs = 16L
    val perStep = 360f * stepMs / 1600f
    // 一圈仍是 1.6 秒，与原来的 tween 视觉速度一致。
    var angle by remember { mutableStateOf(0f) }
    LaunchedEffect(stepMs) {
        while (true) {
            delay(stepMs)
            angle = (angle + perStep) % 360f
        }
    }
    Icon(
        painter = painterResource(icon),
        contentDescription = null,
        tint = Ds.textTertiary,
        modifier = Modifier
            .size(13.dp)
            // 角度只走 layer 矩阵，不重新录制绘制命令。
            .graphicsLayer { rotationZ = angle },
    )
}

/** 处理脚注：一行灰字 + 箭头，点开完整抽屉（v1 Widgets.processFootnote）。 */
@Composable
private fun ProcessFootnote(title: String, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .dsClickable { onOpen() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            fontSize = 13.sp,
            color = Ds.textTertiary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            painter = painterResource(R.drawable.ic_lucide_chevron_right),
            contentDescription = "打开模型处理详情",
            tint = Ds.textMuted,
            modifier = Modifier.size(13.dp),
        )
    }
}

/** 用户气泡：Linear 靛青实心、右对齐；失败时左侧亮红色重试。 */
@Composable
@kotlin.OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun UserBubble(
    message: Model.Message,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    sessionId: String,
    attachmentsEnabled: Boolean,
    loadAttachment: (Model.ImageRef, (Bitmap?, String?) -> Unit) -> Unit,
) {
    // 正在看的大图（ref + 已解码的位图）。为 null 表示没在看。
    var viewing by remember { mutableStateOf<Pair<Model.ImageRef, Bitmap?>?>(null) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (message.failed) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_rotate_ccw),
                contentDescription = "重发",
                tint = Ds.statusError,
                modifier = Modifier
                    .size(18.dp)
                    .clickable { onRetry() },
            )
            Spacer(Modifier.width(8.dp))
        }
        // 还没被宿主确认（`sending`）的气泡压暗一档并标「等待发送」。
        //
        // 用户口径（2026-11-01）：「消息排队时，界面上用户的消息气泡已经出现，会误导
        // 用户已经发送了」。确实如此：排队中的消息在宿主那边只是 inbox 里的一条，
        // `user/message` 要等真正开跑那一轮才写，而本地回声从点发送那一刻就画出来了，
        // 跟已发送的气泡长得一模一样。
        //
        // 只压暗 + 一行小字，不加转圈、不加闪烁：这里需要的是「别让人误判」，
        // 不是又一个会动的装饰（用户明确要求过视觉标记要克制）。
        val pending = message.sending
        // 长按弹「删除」：用户口径（2026-11-01）「安卓端长按气泡弹出菜单可以本地删除消息」。
        //
        // 长按而不是点击：气泡是这一屏里最容易被按到的东西，点击必须留给后续可能的
        // 「复制/引用」，删除这种不可撤销动作不该挂在单击上。
        // 排队中的消息（`seq == 0` 的本地回声）**必须能删**：用户最想删的就是它们。
        // `sending` 为真时也照样给菜单 —— 删的是界面上这条影子，宿主那边另行取消。
        val canDelete = true
        var menuOpen by remember { mutableStateOf(false) }
        // 气泡左侧的复制：只对**有文字**的消息出现（纯图片消息没有可复制的正文）。
        //
        // 位置在气泡外面而不是塞进气泡里：气泡宽度跟着文字走，把按钮塞进去会让每一条
        // 短消息都多出一段空白，而且长按时容易误触。放外面也符合「一条消息一排」的读法：
        // 复制是这条消息的动作，不是消息内容的一部分。
        if (message.text.isNotBlank()) {
            val clipboard = LocalClipboardManager.current
            val context = androidx.compose.ui.platform.LocalContext.current
            // 复制成功的即时反馈：图标就地换成对勾 1.5 秒。
            // 原来只弹 Toast，但 Toast 在部分机型/系统版本上会被限流甚至不显示
            // （2026-11-05 模拟器实测：点了没有任何可见变化），而「复制」这种动作
            // 必须给得出确认；对勾也不往时间线里加东西。
            var copied by remember(message.text) { mutableStateOf(false) }
            LaunchedEffect(copied) {
                if (copied) {
                    kotlinx.coroutines.delay(1500)
                    copied = false
                }
            }
            // 视觉 18dp、触控 40dp：图标本身只有拇指的三分之一宽，直接给 18dp 的
            // 点击区会「看着有、就是点不中」（2026-11-05 实测踩到）。
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable {
                        clipboard.setText(AnnotatedString(message.text))
                        copied = true
                        // Toast 只当补充：有些系统会限流它，不能当唯一反馈。
                        android.widget.Toast
                            .makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT)
                            .show()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(
                        if (copied) R.drawable.ic_lucide_check else R.drawable.ic_lucide_copy,
                    ),
                    contentDescription = if (copied) "已复制" else "复制这条消息",
                    tint = if (copied) Ds.statusRunning else Ds.textMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(2.dp))
        }
        Box {
        Column(
            modifier = Modifier
                .background(
                    if (pending) Ds.bubbleUserBg.copy(alpha = 0.55f) else Ds.bubbleUserBg,
                    RoundedCornerShape(20.dp),
                )
                .combinedClickable(
                    enabled = canDelete,
                    onClick = {},
                    onLongClick = { menuOpen = true },
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (message.text.isNotBlank()) {
                Text(
                    message.text,
                    fontSize = 15.sp,
                    color = if (pending) Ds.bubbleUserText.copy(alpha = 0.75f) else Ds.bubbleUserText,
                    lineHeight = 22.sp,
                )
            }
            if (message.images.isNotEmpty()) {
                // 图片真的画出来：字节由 `session/attachment` 现取（durable 日志只留
                // attachmentId）。取不到/中继按设备关掉附件传输时，那一格写清原因，
                // 不再退化成一行文件名。
                MessageImages(
                    sessionId = sessionId,
                    images = message.images,
                    enabled = attachmentsEnabled,
                    load = loadAttachment,
                    onOpen = { ref, bmp -> viewing = ref to bmp },
                )
            }
            if (pending) {
                Text(
                    "等待发送",
                    fontSize = 11.sp,
                    color = Ds.bubbleUserText.copy(alpha = 0.6f),
                )
            }
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = Ds.bgPopup,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.width(168.dp),
        ) {
            DropdownMenuItem(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                modifier = Modifier.height(46.dp),
                onClick = {
                    menuOpen = false
                    onDelete()
                },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_lucide_trash),
                            contentDescription = null,
                            tint = Ds.statusError,
                            modifier = Modifier.size(19.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                        Text("删除", fontSize = 16.sp, color = Ds.statusError)
                    }
                },
            )
        }
        }
    }

    // 大图查看：盖住整屏（含顶栏与输入区），点任意处关闭。
    val shown = viewing
    if (shown != null && shown.second != null) {
        ImageViewer(bitmap = shown.second!!, name = shown.first.name) { viewing = null }
    }
}

/**
 * 助手正文：纯净画布、无框自然流淌（v1 视觉），走 Markdown 渲染（v1 Markdown.java 对等）。
 *
 * <p>`streaming = true` 时走打字机（[rememberTypewriterText]）：宿主一次 ack 就灌进来
 * 几十个字，直接整段替换在屏幕上表现为「一段一段地蹦」，看起来像卡顿而不是在写。
 * 落定的正文（`streaming = false`）原样整段渲染——那一轮已经结束，再让用户等着看
 * 字一个个冒出来是纯粹的拖延。
 */
@Composable
private fun AssistantText(text: String, streaming: Boolean = false) {
    if (text.isBlank()) return
    val shown = rememberTypewriterText(text, streaming)
    if (shown.isBlank()) return
    MarkdownText(source = shown, modifier = Modifier.fillMaxWidth())
}

// ============================================================
// 流程面板（模型处理详情）
// ============================================================

// ============================================================
// 模型处理详情：两级抽屉（v1 showProcessSheet → showStepSheet）
// ============================================================

/**
 * 抽屉①（步骤时间线）的高度：屏高 86%（v1 showProcessSheet 里 heightPixels * 0.86 写死）。
 *
 * 第二级故意比它矮一档，见 {@link stepSheetHeight}。
 */
@Composable
private fun sheetHeight(): androidx.compose.ui.unit.Dp {
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    return (config.screenHeightDp * 0.86f).dp
}

/**
 * 抽屉②（单步详情）的高度：比第一级矮 10%。
 *
 * 两级一样高的时候，从步骤行点进详情看起来像「同一层换了内容」，用户分不清自己
 * 是进了一层还是留在原地；矮一档让后开的那层明显叠在前一层上。
 */
@Composable
private fun stepSheetHeight(): androidx.compose.ui.unit.Dp {
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    return (config.screenHeightDp * 0.76f).dp
}

/**
 * 抽屉①：步骤时间线（v1 Widgets.processTimeline + showProcessSheet）。
 *
 * 左侧竖向虚导轨贯穿；每行「图标 + 名称 + › + 摘要 + 耗时」，整行可点进抽屉②。
 *
 * 整块跟着 [message] 走：调用方按面板身份每帧取最新那份传进来，于是正在写的那段
 * 思考在这里逐字长、正在跑的那一步转菊花（2026-10-28 要求：展开后也要流式加载）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProcessTimelineSheet(message: Model.Message, onDismiss: () -> Unit) {
    // 二级抽屉记的是**步骤号**而不是下标，也不是 Model.Step 对象：
    // - 存对象：对象是打开那一刻的快照，思考详情就冻住了（「展开后也要流式加载」）；
    // - 存下标：合并面板/重新快照后同一下标指向另一条，越界就直接消失
    //   （2026-10-30 反馈「二级抽屉自己关掉」）。步骤号由 Model.Step.nextId() 发，
    //   合并时随 `step.copy()` 带过去，同一份快照内始终指回同一条。
    //   老缓存里的 step.id 是空串 → 退化成按下标（见下面的 resolve）。
    var detailStepId by remember { mutableStateOf("") }
    var detailIndex by remember { mutableStateOf(-1) }
    // 先按步骤号找，找不到再按下标兜底。
    //
    // 只按号找过一次：`effectiveSteps` 里的流式思考步骤曾经每访问一次就发一个新号，
    // 于是抽屉里刚点开的那条下一帧就查不到，`detailStep` 变 null，二级详情整块
    // 不渲染 —— 用户看到的是「点开就自己关掉」（2026-10-30 真机反馈）。
    // 号已经改成确定性 id，但身份判等这类东西不该只有一条退路：重新快照、面板合并、
    // 老缓存里 id 是空串，都会让号失配。宁可退到「同一个位置那条」，也不让抽屉凭空消失。
    val detailStep: Model.Step? = when {
        detailStepId.isNotEmpty() -> message.steps.firstOrNull { it.id == detailStepId }
            ?: message.steps.getOrNull(detailIndex)
        else -> message.steps.getOrNull(detailIndex)
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 标题里的数字必须与折叠行、页脚完全同源（Model.toolCount()）：只数点得开的
    // 那几条，否则会出现「写着 7 个操作、下面只有 5 行」（2026-10-29 反馈）。
    val toolCount = message.toolCount()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // 抽屉只靠「点遮罩 / 系统返回」关闭，拖拽手势整个关掉。
        //
        // M3 1.3 曾用「在正文里主通道消费指针」来挡这个手势，两轮都不成立：`draggable`
        // 不看 `isConsumed`，所以抽屉照样跟着手指走；反而把正文自己的滚动手势掐死，
        // 滑到顶/底再往回滑就卡住（2026-11-06 用户：「滑到最顶端或者最底端的时候再次
        // 滑动，就会卡住，像是失灵了一样」）。material3 1.4 才给出这个开关。
        sheetGesturesEnabled = false,
        containerColor = Ds.bgSurface,
    ) {
        Column(modifier = Modifier.fillMaxWidth().height(sheetHeight())) {
            Text(
                if (toolCount > 0) "已处理 $toolCount 个操作" else "模型处理详情",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Ds.textPrimary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Ds.divider))
            // 抽屉里同样要贴底跟随：展开后思考/命令还在往下长，不跟就等于让人一直手动滑。
            val sheetScroll = rememberScrollState()
            StickToBottomOnGrowth(sheetScroll)
            // 用户翻上去之后「贴底跟随」会自动停掉（`StickToBottomOnGrowth` 的
            // `atBottom` 被清），这时右下角浮一个「跳到底部」，把控制权还给他。
            val sheetScope = rememberCoroutineScope()
            val scrollToBottom by rememberScrollToBottomVisible(sheetScroll)
            // 竖向导轨**逐行画在空隙里**，不是一条贯穿到底的线。
            //
            // F-08 那轮把导轨挪到容器 `drawBehind` 上解决「量不到视口高度」，代价是它
            // 从头到尾连成一条 —— 每一行前导图标（思考的 sparkles、命令的 check-circle）
            // 都被这条线从中间穿过去（2026-11-05 用户：「一级抽屉纵向时间线不要穿过图标」）。
            // 现在每一行自己画「上一段 / 下一段」，跳过图标的 16dp 高度；旁白行没有图标，
            // 由它补成整段，于是整条线在视觉上仍然是连续的，只是不再压在图元上。
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(sheetScroll)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        // 正在跑的那一步 = 最后一条还没落定的步骤：转菊花而不是打对勾。
                        val liveIndex = if (message.running) message.steps.lastIndex else -1
                        val lastStepIndex = message.steps.lastIndex
                        message.steps.forEachIndexed { index, step ->
                            val live = index == liveIndex
                            // 首行不画「上一段」、末行不画「下一段」：导轨止于第一个和最后一个图标，
                            // 不要悬在标题下面或页脚上面。
                            val railAbove = index > 0
                            val railBelow = index < lastStepIndex
                            when (step.kind) {
                                Model.Step.TEXT -> if (step.text.isNotBlank()) {
                                    // 旁白没有前导图标，导轨在它这里补成整段，把上下两行连起来。
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .timelineRail(color = Ds.borderStrong, above = railAbove, below = railBelow, through = true),
                                    ) {
                                        MarkdownText(
                                            // 旁白也在流式长：整轮没跑完时逐字吐，落定后整段给。
                                            source = rememberTypewriterText(step.text, message.running),
                                            modifier = Modifier.padding(start = 24.dp, top = 6.dp, bottom = 6.dp),
                                        )
                                    }
                                }

                                Model.Step.THINKING -> TimelineRow(
                                    icon = R.drawable.ic_lucide_sparkles,
                                    iconTint = Ds.textSecondary,
                                    name = "深度思考",
                                    // 原文交给 TimelineRow 自己截：它知道这一行有多宽。
                                    // 在这里先压平再交给 Text，末尾省略会把最新的思考切掉，
                                    // 于是「正在长」这件事在抽屉里完全看不出来。
                                    summary = step.text,
                                    duration = "",
                                    running = live,
                                    streamingSummary = true,
                                    railAbove = railAbove,
                                    railBelow = railBelow,
                                ) { detailStepId = step.id; detailIndex = index }

                                else -> {
                                    val action = step.actionIndex
                                        .takeIf { it in message.actions.indices }
                                        ?.let { message.actions[it] }
                                    if (action != null) {
                                        val busy = live || action.status == Model.ToolAction.Status.RUNNING
                                        TimelineRow(
                                            icon = if (action.skill) {
                                                R.drawable.ic_lucide_package
                                            } else {
                                                R.drawable.ic_lucide_check_circle
                                            },
                                            iconTint = if (busy) Ds.textSecondary else Ds.statusSuccess,
                                            name = action.name.ifBlank { "工具" },
                                            summary = action.detail.replace('\n', ' '),
                                            duration = action.durationLabel(),
                                            running = busy,
                                            railAbove = railAbove,
                                            railBelow = railBelow,
                                        ) { detailStepId = step.id; detailIndex = index }
                                    }
                                }
                            }
                        }
                        // 失败原文整段放这里：折叠行只有一行、放不下，而 429 那种响应
                        // 体本身就是给人看的（`{"type":"rate_limit_error",...}` 里有
                        // 退避时间、上游地址、是否可重试）。桌面端也是摊开显示的。
                        if (message.failure.isNotEmpty()) {
                            Text(
                                message.failure,
                                fontSize = 13.sp,
                                color = Ds.statusError,
                                lineHeight = 20.sp,
                                modifier = Modifier.padding(start = 24.dp, top = 10.dp, bottom = 6.dp),
                            )
                        }
                        if (message.steps.isEmpty()) {
                            Text(
                                "这一回合没有可展示的步骤。",
                                fontSize = 13.sp,
                                color = Ds.textMuted,
                                modifier = Modifier.padding(start = 24.dp, top = 8.dp),
                            )
                        }
                        // 垫底留白给「跳到底部」胶囊让位：它浮在右下角，24dp 不够，
                        // 最后一步的摘要会被盖住半行。
                        Spacer(Modifier.height(56.dp))
                    }
                }
                // 浮在步骤列表右下角。内容末尾的垫底留白（下面那个 56dp 的 Spacer）
                // 保证最后一步不会被它盖住。
                if (scrollToBottom) {
                    SheetScrollToBottomButton(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 12.dp),
                        onClick = {
                            sheetScope.launch { sheetScroll.scrollTo(sheetScroll.maxValue) }
                        },
                    )
                }
            }
        }
    }
    detailStep?.let { step ->
        StepDetailSheet(step = step, message = message, onDismiss = {
            detailStepId = ""
            detailIndex = -1
        })
    }
}

/**
 * 时间线的竖向虚导轨：只画**图标之外**的那一段。
 *
 * 判据全在行高上，不写死 34dp：行内子项是 `CenterVertically` 居中的，图标 16dp，
 * 于是圆心恒在 `size.height / 2`，遮住「圆心 ± 8dp」就正好跳过图元。
 *
 * [through] 是给没有图标的旁白行用的：它补成一整段，把上下的线段接起来。
 */
private fun Modifier.timelineRail(
    color: Color,
    above: Boolean,
    below: Boolean,
    through: Boolean = false,
): Modifier = drawBehind {
    if (!above && !below && !through) return@drawBehind
    val x = 8.dp.toPx()
    val halfIcon = 8.dp.toPx()
    val center = size.height / 2f
    val stroke = 1.dp.toPx()
    val dash = PathEffect.dashPathEffect(floatArrayOf(2.5.dp.toPx(), 3.dp.toPx()))
    // 虚线段：太短的段不画——画出来会是一个比缝隙还长的点。
    //
    // 这里既不能用局部 `fun`、也不能用普通 lambda：嵌套的 lambda 拿不到外层
    // `drawBehind` 的接收者（`DrawScope`），`drawLine` 会被解析成别的重载并报
    // 「@Composable invocations can only happen from the context of a @Composable」。
    // 先把接收者存成局部值，再用 `scope.drawLine(...)` 显式调用。
    val scope = this
    val segment: (Float, Float) -> Unit = { from, to ->
        if (to - from >= 0.75f) {
            scope.drawLine(
                color = color,
                start = Offset(x, from),
                end = Offset(x, to),
                strokeWidth = stroke,
                pathEffect = dash,
            )
        }
    }
    if (through) {
        segment(0f, size.height)
        return@drawBehind
    }
    if (above) segment(0f, center - halfIcon)
    if (below) segment(center + halfIcon, size.height)
}

/**
 * 时间线一行：图标 + 名称 + › + 摘要 + 耗时，整行可点。
 *
 * [running] 时前导换成菊花：抽屉里也要看得出「这一步还在跑」（2026-10-28 用户要求）。
 */
@Composable
private fun TimelineRow(
    icon: Int,
    iconTint: Color,
    name: String,
    summary: String,
    duration: String,
    running: Boolean = false,
    /**
     * [summary] 是**还在长的正文**（正在流的思考）：按实际宽度取尾部来画。
     *
     * 直接原样交给 `Text` 不行：这一行是 `maxLines = 1` + 末尾省略，长文本会从后面
     * 切掉，屏幕上永远停在开头那段，看着像冻住了（2026-11-01 用户要求
     * 「一级抽屉的思考也需要流式预览」）。
     */
    streamingSummary: Boolean = false,
    /** 这一行的**上方**要不要接一段导轨（首行为 false）。 */
    railAbove: Boolean = false,
    /** 这一行的**下方**要不要接一段导轨（末行为 false）。 */
    railBelow: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .timelineRail(color = Ds.borderStrong, above = railAbove, below = railBelow)
            .dsClickable { onClick() }
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (running) {
            Spinner(size = 16.dp, color = Ds.statusRunning, strokeWidth = 1.5.dp)
        } else {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(name, fontSize = 15.sp, color = Ds.textPrimary)
        Spacer(Modifier.width(4.dp))
        Icon(
            painter = painterResource(R.drawable.ic_lucide_chevron_right),
            contentDescription = null,
            tint = Ds.textMuted,
            modifier = Modifier.size(14.dp),
        )
        if (summary.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            var summaryWidth by remember { mutableStateOf(0) }
            val charPx = with(LocalDensity.current) { 13.sp.toPx() }
            val shown = if (streamingSummary) {
                // 抽屉里这一行同样是「有多少像素就填多少」，不能按字数估 ——
                // 见 `charWidthUnits` 的注释（右侧留白的老 bug）。
                thinkingPreviewByWidth(
                    summary,
                    streaming = true,
                    budgetPx = summaryWidth,
                    charPx = charPx,
                )
            } else {
                summary
            }
            Text(
                // 前面已经有一个 8dp 间隔和一个「›」箭头，再来一个「·」就是第三层
                // 分隔符 —— 一行里同一件事说三遍（W-01）。
                shown,
                fontSize = 13.sp,
                color = Ds.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .onGloballyPositioned { summaryWidth = it.size.width },
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (duration.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(duration, fontSize = 13.sp, color = Ds.textMuted)
        }
    }
}

/**
 * 抽屉②：单步详情（v1 showStepSheet）。
 *
 * 工具 → 标题「名称 · 耗时」+「参数」「输出」两个深色代码块；
 * 两者都缺时退化成「调用」说明（工具/状态/调用 ID），不是一句「无内容」。
 * 思考 → 标题「深度思考」+ Markdown 正文。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StepDetailSheet(step: Model.Step, message: Model.Message, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val action = step.actionIndex
        .takeIf { it in message.actions.indices }
        ?.let { message.actions[it] }
    val duration = action?.durationLabel().orEmpty()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // 抽屉只靠「点遮罩 / 系统返回」关闭，拖拽手势整个关掉。
        //
        // M3 1.3 曾用「在正文里主通道消费指针」来挡这个手势，两轮都不成立：`draggable`
        // 不看 `isConsumed`，所以抽屉照样跟着手指走；反而把正文自己的滚动手势掐死，
        // 滑到顶/底再往回滑就卡住（2026-11-06 用户：「滑到最顶端或者最底端的时候再次
        // 滑动，就会卡住，像是失灵了一样」）。material3 1.4 才给出这个开关。
        sheetGesturesEnabled = false,
        containerColor = Ds.bgSurface,
    ) {
        Column(modifier = Modifier.fillMaxWidth().height(stepSheetHeight())) {
            Text(
                when {
                    step.kind == Model.Step.THINKING -> "深度思考"
                    action == null -> "工具"
                    duration.isEmpty() -> action.name.ifBlank { "工具" }
                    else -> "${action.name} · $duration"
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Ds.textPrimary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Ds.divider))
            // 二级抽屉：思考正文与工具输出都在往下长，贴底时跟着滚（同上）。
            val detailScroll = rememberScrollState()
            StickToBottomOnGrowth(detailScroll)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(detailScroll)
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                if (step.kind == Model.Step.THINKING) {
                    if (step.text.isBlank()) {
                        Text("这一步没有可展示的内容。", fontSize = 13.sp, color = Ds.textMuted)
                    } else {
                        // 这一轮还在跑 → 思考正文正在往下长，同样走打字机；
                        // 落定的思考原样整段渲染。
                        MarkdownText(
                            source = rememberTypewriterText(step.text, message.running),
                        )
                    }
                } else if (action != null) {
                    var any = false
                    if (action.detail.isNotBlank()) {
                        DetailSectionLabel("参数")
                        DetailCodeBlock(action.detail)
                        any = true
                    }
                    if (action.output.isNotBlank()) {
                        DetailSectionLabel("输出")
                        DetailCodeBlock(action.output)
                        any = true
                    }
                    if (!any) {
                        val state = when (action.status) {
                            Model.ToolAction.Status.DONE -> "已完成"
                            Model.ToolAction.Status.RUNNING -> "进行中"
                            else -> "失败"
                        }
                        val text = buildString {
                            append("工具：").append(action.name.ifBlank { "未知" })
                            append('\n').append("状态：").append(state)
                            if (duration.isNotEmpty()) append(" · ").append(duration)
                            if (action.callId.isNotBlank()) {
                                append('\n').append("调用 ID：").append(action.callId)
                            }
                        }
                        DetailSectionLabel("调用")
                        DetailCodeBlock(text)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun DetailSectionLabel(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        color = Ds.textMuted,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
    )
}

/** 深色代码块（v1 Widgets.codeBlock 同款）。 */
@Composable
private fun DetailCodeBlock(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Ds.codeBg, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text,
            fontSize = 13.sp,
            color = Ds.codeText,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            lineHeight = 19.sp,
        )
    }
}


/**
 * 空会话 / 草稿的空态。
 *
 * 草稿页原来是全空的：点「新建」进来只有顶栏和输入框，没有任何提示说明「这里可以
 * 直接打字」「打完会发到哪台电脑上」。复刻首页空态的视觉语言（同一张图、同一个
 * 22sp 标题口径），一眼能看出这是「还没开始」而不是「没加载出来」（F-05）。
 */
@Composable
private fun EmptyChat(draft: Boolean, hostName: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.dafeiyu_logo),
            contentDescription = null,
            modifier = Modifier.size(96.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "说说你想让它做什么",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Ds.textPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            // 草稿还没落到宿主上，说清楚「发出去才会真的建会话」，
            // 免得以为进来就已经建好了一条空会话。
            if (draft && hostName.isNotBlank()) "发出第一条消息后，在「$hostName」上执行"
            else "发出第一条消息后开始",
            fontSize = 12.sp,
            color = Ds.textMuted,
        )
    }
}
