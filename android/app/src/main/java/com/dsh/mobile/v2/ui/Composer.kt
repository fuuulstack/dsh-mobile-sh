package com.dsh.mobile.v2.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import kotlinx.coroutines.launch
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.mobile.v2.R
import com.dsh.mobile.v2.data.Model

/**
 * 底部输入 Dock：胶囊一体的微灰悬浮条（权限胶囊 / 输入 / 模型胶囊 / 发送-停止）。
 *
 * 草稿按会话存进本地缓存；模型与权限胶囊显示宿主投影里的真实值，
 * 没有投影时说中性占位（「默认模型」「权限」），不编造。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Composer(
    chat: ChatViewModel,
    session: Model.Session,
    running: Boolean,
    pending: List<ChatViewModel.Pending>,
    attaching: Boolean,
    /** 正在等服务端确认的排队操作（键是 `QueuedMessage.id`）。 */
    queuedBusy: Set<String>,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickFile: () -> Unit,
    /** @param steer true = 长按发送：立刻插进正在跑的这一轮，而不是排在它后面。 */
    onSend: (text: String, steer: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember(session.id) { mutableStateOf(chat.draft()) }
    var showModelSheet by remember { mutableStateOf(false) }
    var showPermMenu by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }
    // 刚发出的这段时间里，同一颗按钮已经变成「停止」了：手抖连点会把刚发出去的
    // 回合立刻取消掉（日志里 prompt 与 cancel 同一毫秒发出）。按下的意图是发送，
    // 就别让第二次点击被解释成停止——短暂忽略点击比事后恢复更省事。
    var lastSendAt by remember(session.id) { mutableStateOf(0L) }

    // 发送钮的三态由纯函数决定（规则集中在一处，也便于单测）：
    //   有内容          → SEND    （宿主运行中发出是**排队**，不是停止）
    //   无内容 + 运行中 → STOP
    //   无内容 + 空闲   → DISABLED
    // 也就是说「运行中」不再是停止的唯一条件：一旦开始打字，意图就是发。
    val canSubmit = text.isNotBlank() || pending.isNotEmpty()
    val sendMode = sendButtonMode(running = running, hasContent = canSubmit)

    // 两行结构：第一行全宽输入，第二行放胶囊与发送。
    // 单行横排时「权限+模型(110dp)+发送(38dp)+间距」会把输入框挤到不足 50dp，
    // 中文字符串立刻折行——v1 用两行就是为了避免这个。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Ds.bgPage)
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
    ) {
        // 排队消息：宿主 inbox 里还没被领走的那几条，一条一张卡片，贴在输入框上方。
        // 与桌面端同一套交互：右侧编辑 / 删除 / 立即插话。
        if (session.queue.isNotEmpty()) {
            QueuedRow(
                queue = session.queue,
                running = running,
                busy = queuedBusy,
                onEdit = { item, value -> chat.editQueued(item, value) },
                onRemove = { item -> chat.removeQueued(item) },
                onSteer = { item -> chat.steerQueued(item) },
            )
            Spacer(Modifier.height(6.dp))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Ds.composerBg, RoundedCornerShape(22.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            // 待发区：选好的图/文件按缩略图横向排开，发出去之前都能单独撤掉。
            // 放在输入框上方而不是第二行动作行里，是为了不挤压输入宽度。
            if (pending.isNotEmpty()) {
                PendingRow(pending = pending, onRemove = { chat.removePending(it) })
                Spacer(Modifier.height(6.dp))
            }
            BasicTextField(
                value = text,
                onValueChange = {
                    if (it.length <= 32000) {
                        text = it
                        chat.saveDraft(it)
                    }
                },
                textStyle = TextStyle(fontSize = 15.sp, color = Ds.textPrimary, lineHeight = 21.sp),
                cursorBrush = SolidColor(Ds.primary),
                maxLines = 6,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 36.dp),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) {
                            Text("发送消息或输入指令…", fontSize = 15.sp, color = Ds.textHint)
                        }
                        inner()
                    }
                },
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 附件入口：一个「+」把相册/拍照/文件三条路收在同一个菜单里。
                // 三条路各占一个常驻按钮会把动作行挤满，而这三种选择的频率都不高。
                Box {
                    if (attaching) {
                        // 读图中：原位显示一个小转圈，宽度不变，动作行不跳。
                        Box(
                            modifier = Modifier.size(30.dp).padding(7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Spinner(modifier = Modifier.fillMaxSize(), color = Ds.textSecondary)
                        }
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_lucide_plus),
                            contentDescription = "添加附件",
                            tint = Ds.textSecondary,
                            modifier = Modifier
                                .size(30.dp)
                                .dsClickableNeutral { showAttachMenu = true }
                                .padding(7.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = showAttachMenu,
                        onDismissRequest = { showAttachMenu = false },
                        containerColor = Ds.bgPopup,
                        shape = RoundedCornerShape(20.dp),
                        offset = DpOffset(x = 0.dp, y = (-6).dp),
                        modifier = Modifier.width(168.dp),
                    ) {
                        listOf(
                            Triple("照片", R.drawable.ic_lucide_image, onPickImage),
                            Triple("拍照", R.drawable.ic_lucide_camera, onTakePhoto),
                            Triple("文件", R.drawable.ic_lucide_file, onPickFile),
                        ).forEach { (label, icon, action) ->
                            DropdownMenuItem(
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                                modifier = Modifier.height(46.dp),
                                onClick = {
                                    showAttachMenu = false
                                    action()
                                },
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            painter = painterResource(icon),
                                            contentDescription = null,
                                            tint = Ds.textSecondary,
                                            modifier = Modifier.size(19.dp),
                                        )
                                        Spacer(Modifier.width(14.dp))
                                        Text(label, fontSize = 16.sp, color = Ds.textPrimary)
                                    }
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                // 权限只留图标：预设名长度不受控（「工作区内修改」最长），带文字就得定宽，
                // 而定宽的空胶囊在「仅可查看」这种短名下留一片死白。图标 + 点开选择器更省地方，
                // 当前预设靠颜色区分：完全权限用警示红，另两档是常规次要色。
                val permDanger = session.permissionPreset == "danger-full-access"
                Box {
                    Icon(
                        painter = painterResource(permissionIcon(session.permissionPreset)),
                        contentDescription = session.permissionLabel(),
                        tint = if (permDanger) Ds.statusError else Ds.textSecondary,
                        modifier = Modifier
                            .size(30.dp)
                            .dsClickableNeutral { showPermMenu = true }
                            .padding(7.dp),
                    )
                    // 和右上角三点菜单同一种弹窗：贴锚点、自动避让屏幕边缘（这里在屏幕底部，
                    // 会向上翻）。三档预设就三条，用抽屉太重了。
                    DropdownMenu(
                        expanded = showPermMenu,
                        onDismissRequest = { showPermMenu = false },
                        containerColor = Ds.bgPopup,
                        shape = RoundedCornerShape(20.dp),
                        offset = DpOffset(x = 0.dp, y = (-6).dp),
                        modifier = Modifier.width(196.dp),
                    ) {
                        PERMISSION_PRESETS.forEach { (preset, label) ->
                            val active = session.permissionPreset == preset
                            val danger = preset == "danger-full-access"
                            DropdownMenuItem(
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                                modifier = Modifier.height(46.dp),
                                onClick = {
                                    showPermMenu = false
                                    chat.setPermission(preset)
                                },
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            painter = painterResource(permissionIcon(preset)),
                                            contentDescription = null,
                                            tint = when {
                                                danger -> Ds.statusError
                                                active -> Ds.primary
                                                else -> Ds.textSecondary
                                            },
                                            modifier = Modifier.size(19.dp),
                                        )
                                        Spacer(Modifier.width(14.dp))
                                        Text(
                                            label,
                                            fontSize = 16.sp,
                                            // 红色留给图标：权限预设是一档**常驻**状态，
                                            // 名字整条红字挂在输入框旁边，比警告还像报错；
                                            // 图标染色已经足够说明「这档是危险的」（W-06）。
                                            color = if (active) Ds.primary else Ds.textPrimary,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (active) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_lucide_check),
                                                contentDescription = null,
                                                tint = Ds.primary,
                                                modifier = Modifier.size(15.dp),
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Chip(
                    label = session.modelLabel(),
                    icon = 0,
                    width = 96.dp,
                    onClick = { showModelSheet = true },
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            // 运行态保持品牌渐变（v1 同款：渐变常驻，只换图标）；
                            // 禁用态才用中性底，提示「现在发不了」。
                            if (sendMode == SendButtonMode.DISABLED) {
                                SolidColor(Ds.composerSendDisabledBg)
                            } else {
                                Brush.linearGradient(
                                    listOf(Ds.brandGradientStart, Ds.brandGradientEnd),
                                )
                            },
                            RoundedCornerShape(18.dp),
                        )
                        .sendButtonGestures(
                            enabled = sendMode != SendButtonMode.DISABLED,
                            onClick = {
                                if (sendMode == SendButtonMode.STOP) {
                                    // 发送后的 700ms 内不认停止，避免连点自杀。
                                    if (System.currentTimeMillis() - lastSendAt > 700L) onCancel()
                                    return@sendButtonGestures
                                }
                                lastSendAt = System.currentTimeMillis()
                                onSend(text, false)
                                text = ""
                                chat.saveDraft("")
                            },
                            // 长按只对「有内容」有意义：停止钮长按没有第二种语义。
                            onLongClick = if (sendMode == SendButtonMode.SEND) {
                                {
                                    lastSendAt = System.currentTimeMillis()
                                    // 长按 = 立即发送（steer）：越过宿主那侧的队列，
                                    // 直接插进正在跑的这轮；空闲时宿主按同一条路径处理。
                                    onSend(text, true)
                                    text = ""
                                    chat.saveDraft("")
                                }
                            } else {
                                null
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(
                            if (sendMode == SendButtonMode.STOP) R.drawable.ic_lucide_square
                            else R.drawable.ic_lucide_arrow_up,
                        ),
                        // 文案随状态走：读屏用户也要听出「这一下是停止还是发送」。
                        contentDescription = if (sendMode == SendButtonMode.STOP) "停止" else "发送",
                        tint = Ds.onPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }

    if (showModelSheet) {
        val catalog = remember { chat.catalogSnapshot() }
        // 目录可能很长（多提供方 × 多模型），本地过滤即可：数据已经全在内存里，
        // 不必再往返宿主。搜索词放在 sheet 之外提出来，才能让表头读到它。
        var query by remember { mutableStateOf("") }
        val needle = query.trim().lowercase()
        val choices = catalog?.choices.orEmpty()
        val hits = if (needle.isEmpty()) choices
        else choices.filter { it.label().lowercase().contains(needle) }
        // 滚动状态提到 sheet 外面：标题右侧那个「定位到当前模型」要能滚它。
        val listScroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        // 当前模型在过滤后列表里的下标；-1 = 不在列表里（没选中 / 被搜索过滤掉了）。
        val currentIndex = hits.indexOfFirst {
            session.modelName.isNotEmpty() && it.model == session.modelName
        }
        PickerSheet(
            title = "选择模型",
            onDismiss = { showModelSheet = false },
            titleAction = if (currentIndex < 0) null else {
                {
                    Icon(
                        painter = painterResource(R.drawable.ic_lucide_target),
                        contentDescription = "定位到当前模型",
                        tint = Ds.textSecondary,
                        modifier = Modifier
                            .size(30.dp)
                            .dsClickableNeutral {
                                // 跳到当前模型那一条。
                                //
                                // 行高不固定（副标题有无、尾部等级胶囊），所以按
                                // 「下标 / 总数」估算比例而不是算精确像素：目标是
                                // 「一眼看到它」，不是像素级对齐。
                                // 用 `scrollTo` 而不是 `animateScrollTo`：列表可能
                                // 有几十项，动画滚动会让用户等；直接到位更符合
                                // 「定位」这个动作的语义。
                                scope.launch {
                                    val ratio = currentIndex.toFloat() / hits.size.coerceAtLeast(1)
                                    listScroll.scrollTo((listScroll.maxValue * ratio).toInt())
                                }
                            }
                            .padding(7.dp),
                    )
                }
            },
            header = {
                if (choices.isNotEmpty()) {
                    PickerSearch(
                        value = query,
                        onValueChange = { query = it },
                        hint = "搜索模型",
                    )
                }
            },
            // 把滚动状态交给 sheet，标题右侧的定位图标才能滚这个列表。
            scroll = listScroll,
        ) {
            when {
                choices.isEmpty() -> EmptyHint("还没有模型目录：打开会话后会自动向宿主拉取。")
                hits.isEmpty() -> EmptyHint("没有匹配「${query.trim()}」的模型")
                else -> {
                    hits.forEach { choice ->
                        PickerRow(
                            title = choice.name,
                            subtitle = choice.providerName.ifEmpty { choice.provider },
                            // modelName 存的是模型 id（projection 给的就是 id），
                            // 而 choice.name 是给人看的显示名——拿两者相比永远不会相等，
                            // 选中项的对勾就永远画不出来。
                            selected = session.modelName.isNotEmpty() &&
                                choice.model == session.modelName,
                            onClick = {
                                showModelSheet = false
                                chat.selectModel(choice)
                            },
                            trailing = if (choice.efforts.isEmpty()) null else {
                                {
                                    EffortPicker(
                                        choice = choice,
                                        // 当前会话正用这个模型时才回显真实等级，
                                        // 其它模型显示它自己的默认值，避免整列都是同一个 high。
                                        current = if (choice.model == session.modelName) {
                                            session.reasoningEffort
                                        } else {
                                            choice.reasoningEffort
                                        },
                                        onPick = { effort ->
                                            showModelSheet = false
                                            chat.selectModel(choice.copy(reasoningEffort = effort))
                                        },
                                    )
                                }
                            },
                        )
                    }
                    val failures = catalog?.failures.orEmpty()
                    if (failures.isNotEmpty()) {
                        Text(
                            "部分提供方不可用：${failures.joinToString("、")}",
                            fontSize = 11.sp,
                            color = Ds.textMuted,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }

}

private val PERMISSION_PRESETS = listOf(
    "read-only" to "仅可查看",
    "workspace-write" to "工作区内修改",
    "danger-full-access" to "完全权限",
)

/** 权限预设 → 图标（与桌面端同一套图案：勾 / 笔 / 叹号）。 */
fun permissionIcon(preset: String): Int = when (preset) {
    "read-only" -> R.drawable.ic_lucide_shield_check
    "workspace-write" -> R.drawable.ic_lucide_shield_pen
    "danger-full-access" -> R.drawable.ic_lucide_shield_alert
    else -> R.drawable.ic_lucide_shield
}

/** 发送钮的三种脸：停止 / 发送 / 灰掉（既没内容也不能停止）。 */
enum class SendButtonMode {
    /** 回合运行中且编辑区空着：这一下是「停止」。 */
    STOP,

    /** 有内容可发（无论宿主是否运行）。运行中发出是排队，长按才是插话。 */
    SEND,

    /** 没内容、也没在跑：禁用态，灰底。 */
    DISABLED,
}

/**
 * 发送钮状态判定（纯函数，便于单测）。
 *
 * 优先级是「有内容就先发」：宿主运行中时编辑区一旦有字，用户想的是把这句话送出去，
 * 而不是取消自己刚刚跑起来的回合。停止只在空编辑区时提供。
 *
 * @param hasContent 编辑区有文本，或待发区有附件（两者任一都算「有内容可发」）。
 */
fun sendButtonMode(running: Boolean, hasContent: Boolean): SendButtonMode = when {
    hasContent -> SendButtonMode.SEND
    running -> SendButtonMode.STOP
    else -> SendButtonMode.DISABLED
}

/**
 * 发送钮的手势：单击 + 长按。
 *
 * 不用 [dsClickable] 是因为它只认单击；长按要「插话（steer）」，必须走
 * combinedClickable。同样去掉涟漪，只保留按压态背景。
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.sendButtonGestures(
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressedBg =
        if (pressed && enabled) Modifier.background(Ds.bgPressed, RoundedCornerShape(18.dp)) else Modifier
    this
        .then(pressedBg)
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}

/**
 * 排队消息行：宿主 inbox 里还没被领走的消息，一条一张卡片。
 *
 * 对齐桌面端的排队卡片：左侧「↩ 继续」标出这是排队等发的，右侧三个动作
 * （编辑 / 删除 / 提前插话）。多条时纵向堆叠——队列通常只有一两条，
 * 横向滚动反而让「哪条先发」看不出来。
 *
 * 「↑ 立即发送」对 `next-turn` 恒显示：宿主在跑就 steer 插进当前轮，空闲就直接
 * 发出去——由插件按 `hostState` 决定，手机端不做这个判断（脉搏可能差一个 tick）。
 * `next-step` 不显示：那一桶宿主不接受 carry-over。
 */
@Composable
private fun QueuedRow(
    queue: List<Model.QueuedMessage>,
    running: Boolean,
    busy: Set<String>,
    onEdit: (Model.QueuedMessage, String) -> Unit,
    onRemove: (Model.QueuedMessage) -> Unit,
    onSteer: (Model.QueuedMessage) -> Unit,
) {
    // 正在编辑的那条（null = 没在编辑）。编辑就地展开成输入框，不弹对话框：
    // 排队消息都很短，弹窗反而挡住了正在看的上下文。
    var editing by remember(queue) { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        queue.forEachIndexed { index, item ->
            if (index > 0) Spacer(Modifier.height(6.dp))
            // 这条卡片正在等服务端确认：整行按下去都没反应，动作图标换成转圈。
            // 删除与「立即插话」都是同步写操作，点下去到宿主回 updateQueue 之间
            // 有几百毫秒的空窗；不给等待态用户会以为没点上，于是连点几次
            // （2026-10-30 反馈）。
            val waiting = item.id in busy
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Ds.bgSurface, RoundedCornerShape(16.dp))
                    .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.label(),
                        fontSize = 13.sp,
                        color = if (waiting) Ds.textMuted else Ds.textSecondary,
                        maxLines = if (editing == item.id) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    if (waiting) {
                        // 与折叠行同一个菊花：尺寸对齐动作图标，避免等待时整行高度跳动。
                        Spinner(
                            color = Ds.textMuted,
                            modifier = Modifier
                                .size(28.dp)
                                .padding(8.dp),
                        )
                    } else {
                        QueueAction(R.drawable.ic_lucide_pencil, "编辑排队消息") {
                            editing = if (editing == item.id) null else item.id
                        }
                        // 始终显示。宿主在跑就插进当前这轮（steer），空闲就直接发出去
                        // ——这个判断放在插件端，因为只有它看得见宿主的 `hostState`：
                        // 手机这边的 `running` 来自脉搏，可能差一个 tick，而宿主对空闲态
                        // 的 steer 是直接抛 `session/steer-unavailable` 的。
                        // 只有 `next-step` 例外：那一桶宿主根本不接受 carry-over。
                        if (item.steerable) {
                            QueueAction(R.drawable.ic_lucide_arrow_up, "立即发送") { onSteer(item) }
                        }
                        QueueAction(R.drawable.ic_lucide_trash, "删除排队消息") { onRemove(item) }
                    }
                }
                if (editing == item.id) {
                    QueuedEditor(
                        initial = item.text,
                        onCancel = { editing = null },
                        onConfirm = { value ->
                            editing = null
                            onEdit(item, value)
                        },
                    )
                }
            }
        }
    }
}

/** 排队卡片上的一个小动作图标（编辑 / 删除 / 插话）。 */
@Composable
private fun QueueAction(icon: Int, label: String, onClick: () -> Unit) {
    Icon(
        painter = painterResource(icon),
        contentDescription = label,
        tint = Ds.textMuted,
        modifier = Modifier
            .size(28.dp)
            .dsClickableNeutral { onClick() }
            .padding(6.dp),
    )
}

/**
 * 就地编辑一条排队消息：单行输入 + 「取消 / 保存」。
 *
 * 保存空串会被宿主拒（`queue edit content must include non-whitespace text`），
 * 所以空白时禁用保存钮——比让用户点了收到一条报错更直接。
 */
@Composable
private fun QueuedEditor(initial: String, onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 6.dp)) {
        BasicTextField(
            value = value,
            onValueChange = { if (it.length <= 32000) value = it },
            textStyle = TextStyle(fontSize = 14.sp, color = Ds.textPrimary, lineHeight = 20.sp),
            cursorBrush = SolidColor(Ds.primary),
            maxLines = 4,
            modifier = Modifier
                .fillMaxWidth()
                .background(Ds.bgSurfaceVariant, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "取消",
                fontSize = 13.sp,
                color = Ds.textMuted,
                modifier = Modifier
                    .dsClickableNeutral { onCancel() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "保存",
                fontSize = 13.sp,
                color = if (value.isBlank()) Ds.textMuted else Ds.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .dsClickableNeutral(enabled = value.isNotBlank()) { onConfirm(value.trim()) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * 待发附件的缩略图行：图片给缩略图，文件给图标 + 文件名。
 *
 * 每枚右上角一个叉，点掉即从待发区移除。横向可滚，选十张也不会把输入框顶走。
 */
@Composable
private fun PendingRow(pending: List<ChatViewModel.Pending>, onRemove: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        pending.forEachIndexed { index, item ->
            Box(modifier = Modifier.size(56.dp)) {
                val bitmap = item.preview?.bitmap
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Ds.bgSurface),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = item.attachment.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)),
                        )
                    } else {
                        // 文件没有缩略图：图标 + 最多两行文件名。
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_lucide_file),
                                contentDescription = null,
                                tint = Ds.textSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                item.attachment.name.ifEmpty { "文件" },
                                fontSize = 9.sp,
                                color = Ds.textMuted,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                // 叉放在缩略图右上角外沿：压在图上会盖掉内容。
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(18.dp)
                        .background(Ds.bgPage, RoundedCornerShape(9.dp))
                        .dsClickableNeutral { onRemove(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lucide_x),
                        contentDescription = "移除附件",
                        tint = Ds.textSecondary,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Chip(
    label: String,
    icon: Int,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier)
            .height(30.dp)
            .background(Ds.bgSurface, RoundedCornerShape(15.dp))
            .dsClickableNeutral { onClick() }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != 0) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = Ds.textSecondary,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Ds.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerSheet(
    title: String,
    onDismiss: () -> Unit,
    header: @Composable () -> Unit = {},
    /** 标题右侧的动作图标（目前只有模型面板的「定位到当前模型」用）。 */
    titleAction: (@Composable () -> Unit)? = null,
    /** 列表的滚动状态。由调用方提供时，标题动作才能滚它（见模型面板的定位图标）。 */
    scroll: ScrollState = rememberScrollState(),
    content: @Composable () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Ds.bgSurface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = Ds.textPrimary,
                )
                // 图标**紧挨标题**（2026-11-01 用户要求），不推到最右。
                // 所以这里不给 Text 加 weight，改用 Spacer(weight=1f) 把余量吃掉：
                // 标题短时图标贴着字，标题长到放不下时 Text 先被压（它在前）。
                titleAction?.let {
                    Spacer(Modifier.width(2.dp))
                    it()
                }
                Spacer(Modifier.weight(1f))
            }
            // 搜索框放在滚动区之外：它要一直停在标题下面，不能跟着列表滚走。
            header()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 列表本身要能长：多提供方目录常常十几项，380dp 只能看到四五条。
                    .heightIn(max = 520.dp)
                    .verticalScroll(scroll),
            ) {
                content()
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun PickerSearch(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .background(Ds.bgSurfaceVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_lucide_search),
            contentDescription = null,
            tint = Ds.textMuted,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(hint, fontSize = 14.sp, color = Ds.textHint)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = Ds.textPrimary),
                cursorBrush = SolidColor(Ds.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_x),
                contentDescription = "清空",
                tint = Ds.textMuted,
                modifier = Modifier
                    .size(15.dp)
                    .dsClickableNeutral { onValueChange("") },
            )
        }
    }
}

/**
 * 模型行尾的思考等级下拉。
 *
 * 等级集合由宿主下发（`reasoning.efforts`），这里只负责选择；选完连同模型一起
 * 提交给 `session/selectModel`。没有等级声明的模型不给入口——宁可没有，也不编一个
 * 宿主不认的 high 发上去。
 */
@Composable
private fun EffortPicker(
    choice: Model.ModelChoice,
    current: String,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val label = choice.effortLabel(current).ifEmpty { choice.effortLabel(choice.reasoningEffort) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(Ds.bgSurface)
                .dsClickableNeutral { open = true }
                .padding(start = 9.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label.ifEmpty { "思考" },
                fontSize = 12.sp,
                color = Ds.textSecondary,
                maxLines = 1,
            )
            Icon(
                painter = painterResource(R.drawable.ic_lucide_chevron_down),
                contentDescription = "思考等级",
                tint = Ds.textTertiary,
                modifier = Modifier.size(12.dp),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = Ds.bgPopup,
            shape = RoundedCornerShape(18.dp),
            offset = DpOffset(x = 0.dp, y = (-4).dp),
        ) {
            choice.efforts.forEach { effort ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = effort.name.ifEmpty { effort.id },
                            fontSize = 15.sp,
                            color = if (effort.id == current) Ds.primary else Ds.textPrimary,
                        )
                    },
                    onClick = {
                        open = false
                        if (effort.id != current) onPick(effort.id)
                    },
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
                    modifier = Modifier.height(44.dp),
                )
            }
        }
    }
}

@Composable
private fun PickerRow(
    title: String,
    subtitle: String,
    icon: Int = 0,
    selected: Boolean = false,
    onClick: () -> Unit,
    /** 行尾的动作区：模型行放思考等级下拉，其它行留空。 */
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .dsClickableNeutral { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != 0) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = Ds.textSecondary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = Ds.textPrimary)
            if (subtitle.isNotBlank()) {
                Text(subtitle, fontSize = 12.sp, color = Ds.textTertiary)
            }
        }
        if (trailing != null) {
            trailing()
            Spacer(Modifier.width(6.dp))
        }
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_check),
                contentDescription = null,
                tint = Ds.primary,
                modifier = Modifier.size(16.dp),
            )
        } else if (trailing != null) {
            // 未选中时给对勾留出同样宽度，避免行尾动作区随选中状态左右跳动。
            Spacer(Modifier.size(16.dp))
        }
    }
}
