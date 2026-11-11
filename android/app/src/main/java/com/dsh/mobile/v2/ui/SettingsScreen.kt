package com.dsh.mobile.v2.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.mobile.v2.BuildConfig
import com.dsh.mobile.v2.R
import com.dsh.mobile.v2.data.Model
import kotlinx.coroutines.delay

/**
 * 屏幕四：设置。
 *
 * 左对齐大标题（根页面用大标题，不配返回箭头）+ 分组卡片：
 * 中继服务器 / 对话偏好 / 版本管理 / 设备管理 / 账户。
 */
@Composable
fun SettingsScreen(
    state: AppViewModel.UiState,
    onTogglePref: (Model.Preferences) -> Unit,
    onRotate: () -> Unit,
    onUnpair: (String) -> Unit,
    onLogout: () -> Unit,
    onForceSync: () -> Unit,
    /** 刷新中继状态快照；hero 卡的流量数字靠它走动。 */
    onRefresh: () -> Unit = {},
    /** 版本管理：检查更新 / 下载安装 / 去开安装权限。 */
    onCheckUpdate: () -> Unit = {},
    onDownloadUpdate: () -> Unit = {},
    onOpenInstallSettings: () -> Unit = {},
) {
    // hero 卡的「上行 / 下行」要像 v1 那样自己走：进页面先刷一次，之后每秒一次。
    // 计数在网关里是累积的，这里只把最新读数搬上界面，不额外发请求。
    LaunchedEffect(Unit) {
        onRefresh()
        while (true) {
            delay(TRAFFIC_TICK_MS)
            onRefresh()
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ds.bgPage)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            "设置",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = Ds.textPrimary,
            letterSpacing = (-0.9).sp,
            modifier = Modifier.padding(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 10.dp),
        )

        // 连接状态单独一张渐变 hero 卡（v1 的样式），不并进「中继服务器」分组：
        // 分组里那几行是配置事实，hero 说的是通道事实。
        // 断开连接 = 退出登录（v1 同款语义），不可撤销，先弹二次确认。
        var disconnectOpen by remember { mutableStateOf(false) }
        StatusHero(state.relay, onDisconnect = { disconnectOpen = true })
        if (disconnectOpen) {
            ConfirmDialog(
                title = "断开连接",
                message = "断开后这台手机将退出登录，需要重新用配对码登录才能继续控制电脑。",
                confirmLabel = "断开",
                onConfirm = {
                    disconnectOpen = false
                    onLogout()
                },
                onDismiss = { disconnectOpen = false },
            )
        }

        // ---------------- 中继服务器 ----------------
        SectionLabel("中继服务器")
        Card {
            val relay = state.relay
            SettingRow(
                icon = R.drawable.ic_lucide_server,
                title = "服务器地址",
                value = relay.endpoint.ifBlank { "尚未连接" },
            )
            val rotating = remember { mutableStateOf(false) }
            SettingRow(
                icon = R.drawable.ic_lucide_shield,
                title = "鉴权令牌",
                value = "••••••••",
                onClick = {
                    if (!rotating.value) {
                        rotating.value = true
                        onRotate()
                    }
                },
            )
            SwitchRow(
                icon = R.drawable.ic_lucide_refresh,
                title = "断线自动重连",
                checked = state.prefs.autoReconnect,
                onChange = {
                    onTogglePref(state.prefs.copy(autoReconnect = it))
                },
            )
            // 附件开关放在「中继服务器」区而不是「对话偏好」：它管的是中继要不要
            // 为这台设备搬附件字节（服务端策略），不是本机画不画缩略图。
            SwitchRow(
                icon = R.drawable.ic_lucide_image,
                title = "传输附件",
                checked = state.prefs.transferAttachments,
                onChange = {
                    onTogglePref(state.prefs.copy(transferAttachments = it))
                },
            )
        }

        // ---------------- 对话偏好 ----------------
        SectionLabel("对话偏好")
        Card {
            SwitchRow(
                icon = R.drawable.ic_lucide_sparkles,
                title = "显示思考",
                checked = state.prefs.showReasoning,
                onChange = { onTogglePref(state.prefs.copy(showReasoning = it)) },
            )
            SwitchRow(
                icon = R.drawable.ic_lucide_pencil,
                title = "编辑内容",
                checked = state.prefs.editContent,
                onChange = { onTogglePref(state.prefs.copy(editContent = it)) },
            )
        }

        if (BuildConfig.DEBUG) {
            SectionLabel("调试")
            Card {
                SettingRow(
                    icon = R.drawable.ic_lucide_rotate_ccw,
                    title = "强制重拉全部会话",
                    value = "执行",
                    onClick = onForceSync,
                )
            }
        }

        // ---------------- 版本管理 ----------------
        SectionLabel("版本管理")
        Card {
            VersionGroup(
                state = state,
                onCheckUpdate = onCheckUpdate,
                onDownloadUpdate = onDownloadUpdate,
                onOpenInstallSettings = onOpenInstallSettings,
            )
        }

        // ---------------- 设备管理 ----------------
        // 只管理**已配对**的设备（2026-10-28 按用户要求改）：这里是「已经连上的电脑」
        // 一览，动作只有「解除绑定」（删除）。未配对的行既不该出现，也不该带「去配对」——
        // 配对入口在首页（空态按钮 / 主机行），同一件事在设置页再放一遍会让两处走岔。
        SectionLabel("设备管理")
        Card {
            val devices = pairedHosts(state.hosts)
            if (devices.isEmpty()) {
                EmptyHint("还没有配对任何电脑")
            } else {
                devices.forEach { host ->
                    HostRow(host = host, onUnpair = { onUnpair(host.id) })
                }
            }
        }

        // ---------------- 账户 ----------------
        SectionLabel("账户")
        Card {
            SettingRow(
                icon = R.drawable.ic_lucide_log_out,
                title = "退出登录",
                value = "",
                titleColor = Ds.statusError,
                onClick = onLogout,
            )
        }

        Text(
            "v${BuildConfig.VERSION_NAME}",
            fontSize = 12.sp,
            color = Ds.textHint,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(30.dp))
    }
}

// ============================================================
// 组件
// ============================================================

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 20dp 与页面标题、SectionLabel 的水平边距同一条线，改一处要三处一起改。
            .padding(horizontal = 20.dp)
            .background(Ds.bgSurface, RoundedCornerShape(18.dp))
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) { content() }
}

/**
 * 中继状态 hero 卡：v1 的渐变卡（状态 + 主机 + 平台头像 + 延迟 / 上行 / 下行）。
 *
 * 尺寸按 v1 真机布局还原（1216x2640 / density 420）：
 * 卡高 427px ≈ 163dp、内边距 63px ≈ 24dp、字号 24sp/13sp。
 * 背景是主题里那对 brandGradientStart → brandGradientEnd 的横向渐变，与 v1 实测像素一致。
 * 卡里右下角的「断开连接」是从 v1 搬回来的（`DisconnectButton`）：v1 的
 * `SettingsScreen.java` 里它落在 metrics 行右侧、点下去就是 `host.logout()`，所以 v2
 * 对应同一个动作（退出登录 → 回登录页），并且**必须二次确认**——它是不可撤销的。
 *
 * **只保留一处延迟**（2026-10-28 按用户要求改）：卡里原本上下各有一个延迟读数
 * （标题行右侧的 `xx ms` 胶囊 + 底部「往返延迟」格），同一件事说两遍，底部那格已删除。
 * 头像同时从 48dp 放大到 60dp：素材（`platform_avatar_*.png`）画布四周留有约 15% 的白边，
 * 按 48dp 渲染时人像只剩 41dp，看上去「白色区域太多」。
 */
@Composable
private fun StatusHero(relay: Model.RelayStatus, onDisconnect: () -> Unit) {
    val white = Color.White
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 与页面标题、SectionLabel、其它卡同一条 20dp 水平线。
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.horizontalGradient(listOf(Ds.brandGradientStart, Ds.brandGradientEnd)),
            )
            .padding(horizontal = 20.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val avatar = platformAvatar(relay.hostPlatform)
            if (avatar != 0) {
                Image(
                    painter = painterResource(avatar),
                    contentDescription = null,
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(16.dp)),
                )
                Spacer(Modifier.width(16.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (relay.isConnected) "已连接" else "未连接",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = white,
                )
                val subtitle = relay.hostAlias.ifBlank { relay.endpoint }
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        fontSize = 13.sp,
                        color = white.copy(alpha = 0.82f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                if (relay.latencyMs >= 0) "${relay.latencyMs}ms" else "—",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = white,
            )
        }
        if (relay.note.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(relay.note, fontSize = 12.sp, color = white.copy(alpha = 0.92f))
        }
        Spacer(Modifier.height(20.dp))
        // 上行 / 下行显示实时速率（最近几秒的窗口平均）：SettingsScreen 每秒刷一次快照，
        // 这里只管画；刚进页面窗口还没攒出两点时显示「—」，不显示误导性的 0。
        // 延迟不在这里重复：标题行右侧已经有同一个读数。
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (stat in heroStats(relay)) HeroStat(stat.label, stat.value)
            Spacer(Modifier.weight(1f))
            DisconnectButton(onClick = onDisconnect)
        }
    }
}

/**
 * hero 卡右下角的「断开连接」（v1 就在这里，v1 是 `Metrics 行顶右 + 白底胶囊红字`）。
 *
 * 配色必须走主题：靛蓝渐变上直接压红字对比度只有 1.5:1，等于看不清，所以底色用
 * `Ds.textOnBrand`（白）的 92% 胶囊、字用 `Ds.statusError`——与 v1 的
 * `0xEBFFFFFF` 底色 + `status_error` 红字同构，也满足「不新增硬编码颜色」的约束。
 */
@Composable
private fun DisconnectButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Ds.textOnBrand.copy(alpha = 0.92f))
            .dsClickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "断开连接",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Ds.statusError,
            maxLines = 1,
            modifier = Modifier.semantics { contentDescription = "断开连接" },
        )
    }
}

/**
 * hero 卡底部的一格读数（标签 + 值）。
 *
 * 与下面同名的 `HeroStat` Composable 不是一个东西：这个数据类存在的意义是让
 * 「卡里到底有哪几格」变成可被 JVM 单测断言的纯数据（删掉重复的延迟格就是这么锁住的）。
 */
internal data class HeroReading(val label: String, val value: String)

/**
 * hero 卡底部的读数格。
 *
 * **不含延迟**：标题行右侧已经有 `xx ms`，底部再放一格「往返延迟」是同一件事说两遍
 * （2026-10-28 用户要求删掉重复的那个）。
 */
internal fun heroStats(relay: Model.RelayStatus): List<HeroReading> = listOf(
    HeroReading("上行", formatBps(relay.upstreamBps)),
    HeroReading("下行", formatBps(relay.downstreamBps)),
)

/** hero 卡里的一格读数：数值在上、标签在下（v1 就是这个排法）。 */
@Composable
private fun HeroStat(label: String, value: String) {
    Column(modifier = Modifier.width(84.dp)) {
        Text(
            value,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.82f),
            maxLines = 1,
        )
    }
}

@Composable
private fun SettingRow(
    icon: Int,
    title: String,
    value: String,
    subtitle: String = "",
    titleColor: Color = Ds.textPrimary,
    /** 行首自定义图标（如「检查中」的循环下落箭头）；给了就不再渲染 [icon]。 */
    leading: (@Composable () -> Unit)? = null,
    /** 行尾自定义内容（如「有新版本」胶囊）；给了就不再渲染 [value]。 */
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.dsClickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
        } else {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = Ds.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = titleColor)
            if (subtitle.isNotBlank()) {
                Text(subtitle, fontSize = 12.sp, color = Ds.textTertiary)
            }
        }
        if (trailing != null) {
            trailing()
        } else if (value.isNotBlank()) {
            Text(
                value,
                fontSize = 13.sp,
                color = Ds.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(120.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    icon: Int,
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String = "",
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = Ds.textSecondary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = Ds.textPrimary)
            if (subtitle.isNotBlank()) {
                Text(subtitle, fontSize = 12.sp, color = Ds.textTertiary)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            // 开关本身不带文字，TalkBack 只会念「开关，已开启」，读者无从知道是哪个
            // 开关。整行的标题就是它唯一的名字，显式挂上。
            modifier = Modifier.semantics { contentDescription = title },
            colors = SwitchDefaults.colors(
                checkedTrackColor = Ds.primary,
                uncheckedTrackColor = Ds.borderStrong,
                uncheckedThumbColor = Color.White,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/**
 * 能「用起来」的主机：只挑**已配对**的（`authorized`）。
 *
 * 中继的 `/native/v2/hosts` 会把「这台电脑在这个账号下、但还没配对给本机」的条目也发下来，
 * 这类行既不能切、也不能删，出现在任何列表里只会让人以为配对没生效（2026-10-29 用户直报：
 * 「主机下拉框里残留未配对的主机」）。所以**设备管理和顶栏主机下拉共用这一个口径**——
 * 下拉里能点的，就是设备管理里能解除绑定的那一批，两处永远一致。
 */
internal fun pairedHosts(hosts: List<Model.Host>): List<Model.Host> = hosts.filter { it.authorized }

/** 主机行：名称 + 状态（已配对在线/离线/未配对）+ 操作。 */
@Composable
private fun HostRow(
    host: Model.Host,
    onUnpair: () -> Unit,
) {
    var confirmOpen by remember(host.id) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 在线状态只用一个小圆点（原来是一整行「已配对 · 在线」文字，比设备名还抢眼）：
        // 绿点=在线、灰点=离线。文字去掉后读屏就拿不到这层信息了，所以点本身挂语义标签。
        // 位置在平台图标**左边**（2026-10-29 按用户要求从设备名右侧挪过来）：状态属于
        // 整台设备，贴在图标前比吊在名字尾巴上更像「这台机器亮着」。
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(
                    if (host.online) Ds.statusUnreadDot else Ds.statusOfflineDot,
                    CircleShape,
                )
                .semantics { contentDescription = if (host.online) "在线" else "离线" },
        )
        Spacer(Modifier.width(10.dp))
        Icon(
            painter = painterResource(platformIcon(host.platform)),
            contentDescription = null,
            tint = Ds.textSecondary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                host.alias.ifBlank { "未命名主机" },
                fontSize = 15.sp,
                color = Ds.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "解除绑定",
            fontSize = 12.sp,
            color = Ds.statusError,
            modifier = Modifier
                .dsClickable { confirmOpen = true }
                .padding(horizontal = 4.dp, vertical = 6.dp),
        )
    }

    if (confirmOpen) {
        ConfirmDialog(
            title = "解除绑定",
            message = "解除后，这台手机将无法再操作「${host.alias}」。" +
                "要恢复得在电脑上重新生成配对码。",
            confirmLabel = "解除",
            onConfirm = {
                confirmOpen = false
                onUnpair()
            },
            onDismiss = { confirmOpen = false },
        )
    }
}

/**
 * 版本管理分组（v1 SettingsScreen.versionGroup 的移植版；2026-10-29 按用户要求改成弹窗）。
 *
 * 卡片里**只留一行**「检查更新」+ 右侧当前状态；版本内容（新版号 / 体积 / 发行说明 /
 * 下载按钮 / 下载进度）全部搬进 [UpdateDialog]。原来的做法是把 notes 直接铺在设置页里：
 * 发行说明一长，卡片就把下面的「设备管理」「账户」整段挤出屏幕，而且这段文字的信息密度
 * 和卡片里其它行完全不是一回事。
 *
 * 卡片右侧状态：`有新版本 X` / `下载中 N%` / 当前版本号。有 pendingRelease 时点这一行
 * 是**重开弹窗**而不是再发一次清单请求——网络请求只在真正「检查」时发生一次。
 * 下载失败只在卡片上留红字（弹窗里也显示一份），不额外弹新窗。
 */
@Composable
private fun VersionGroup(
    state: AppViewModel.UiState,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onOpenInstallSettings: () -> Unit,
) {
    var dialogOpen by remember { mutableStateOf(false) }
    // 自动弹窗只认「这一次检查出来的结果」：初始值取进页面时已有的版本号，于是
    // 之前查过、用户手动关掉、切走再回来的场景不会又弹一次（SettingsScreen 每次
    // 切回都会重建，remember 随之归零，靠这个基线来区分「新结果」和「旧状态」）。
    var autoShownFor by remember { mutableStateOf(state.pendingRelease?.latest ?: "") }
    val release = state.pendingRelease
    val downloading = state.downloadPercent != -2

    LaunchedEffect(release?.latest) {
        val latest = release?.latest ?: return@LaunchedEffect
        if (autoShownFor != latest) {
            autoShownFor = latest
            dialogOpen = true
        }
    }
    // 下载完成、已经交给系统安装器：弹窗自动收起，否则它会停在「下载并安装」上，
    // 让用户以为刚才那一下没生效（真正的安装确认框是系统弹的，在本 App 之上）。
    LaunchedEffect(state.releaseNote) {
        if (state.releaseNote.startsWith("已下载")) dialogOpen = false
    }

    SettingRow(
        icon = R.drawable.ic_lucide_arrow_down,
        title = "检查更新",
        value = when {
            state.releaseChecking -> "检查中…"
            downloading && state.downloadPercent >= 0 -> "下载中 ${state.downloadPercent}%"
            downloading -> "下载中…"
            else -> "v${BuildConfig.VERSION_NAME}"
        },
        // 检查中：行首箭头改成「循环下落」的动效，替代原来在卡片下方单起一行的转圈
        // + 「正在检查…」。请求发生在这一行上，状态就该长在这一行上；下方多一行会
        // 把「设备管理」「账户」往下顶，检查一次跳一下。
        leading = if (state.releaseChecking) {
            { FallingArrow() }
        } else {
            null
        },
        // 有新版时行尾换成蓝框胶囊：这是整页唯一一个「要你去点一下」的行尾，
        // 和其它行的纯灰字版本号区分开，扫一眼就能看见。
        trailing = release?.takeIf { !downloading && !state.releaseChecking }?.let { pending ->
            { NewVersionPill("有新版本 ${pending.latest}") }
        },
        onClick = { if (release != null) dialogOpen = true else onCheckUpdate() },
    )
    if (state.releaseError.isNotBlank()) {
        VersionNote(state.releaseError, color = Ds.statusError)
    } else if (state.releaseNote.startsWith("已下载")) {
        // 「已下载，等待安装确认」这类一次性结论留在卡片上：弹窗此刻已经收起来了，
        // 没有这一行用户就看不到下载到底成没成。其余 note（「发现新版本 X · 2.2 MB」）
        // 的内容已经由行尾 value 和弹窗承担，不再重复一遍。
        VersionNote(state.releaseNote, color = Ds.textTertiary)
    }

    if (dialogOpen && release != null) {
        UpdateDialog(
            release = release,
            downloadPercent = state.downloadPercent,
            installPermissionMissing = state.installPermissionMissing,
            error = state.releaseError,
            onDownload = onDownloadUpdate,
            onOpenInstallSettings = onOpenInstallSettings,
            onDismiss = { dialogOpen = false },
        )
    }
}

/**
 * 更新弹窗：版本号 + 体积 + 发布日期 + 发行说明 + 下载/权限按钮 + 下载进度条。
 *
 * 三态互斥：下载中（进度条，**没有按钮**，进度只读）→ 缺安装权限（去开启）→ 可下载。
 * 下载中允许关掉弹窗（下载在 ViewModel 的后台线程里跑，关窗不会中断），
 * 想再看进度点卡片那一行就能回来，右侧 value 也会一直显示百分比。
 */
@Composable
private fun UpdateDialog(
    release: Model.Release,
    downloadPercent: Int,
    installPermissionMissing: Boolean,
    error: String,
    onDownload: () -> Unit,
    onOpenInstallSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Ds.bgSurface, RoundedCornerShape(20.dp))
                .padding(20.dp),
        ) {
            Text(
                "发现新版本 ${release.latest}",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Ds.textPrimary,
            )
            Spacer(Modifier.height(6.dp))
            // 发布日期只取日期段：清单里是 UTC 的 ISO 串（2026-10-28T15:55Z），
            // 原样显示既长又会被误读成本地时间，日期本身已经够用。
            val meta = listOfNotNull(
                "当前 v${BuildConfig.VERSION_NAME}",
                release.sizeLabel().takeIf { it.isNotEmpty() },
                release.publishedAt.take(10).takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            Text(meta, fontSize = 12.sp, color = Ds.textTertiary)

            if (release.notes.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                // 发行说明可能很长：给个高度上限让它自己滚，别把按钮顶出屏幕。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        release.notes,
                        fontSize = 14.sp,
                        color = Ds.textSecondary,
                        lineHeight = 20.sp,
                    )
                }
            }

            if (downloadPercent != -2) {
                Spacer(Modifier.height(18.dp))
                if (downloadPercent < 0) {
                    // 拿不到总大小（服务器没给 Content-Length）：**不画进度条**。
                    // 空轨道看着像画坏了，假进度更糟；下面那行「正在下载…」已经说明在做什么。
                    Spacer(Modifier.height(8.dp))
                    Text("正在下载…", fontSize = 12.sp, color = Ds.textTertiary)
                } else {
                    ProgressBar(progress = downloadPercent / 100f)
                    Spacer(Modifier.height(8.dp))
                    Text("正在下载 $downloadPercent%", fontSize = 12.sp, color = Ds.textTertiary)
                }
            }

            if (error.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(error, fontSize = 12.sp, color = Ds.statusError, lineHeight = 17.sp)
            }

            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    SecondaryButton(
                        label = if (downloadPercent != -2) "后台下载" else "稍后",
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                    ) { onDismiss() }
                }
                if (downloadPercent == -2) {
                    Box(modifier = Modifier.weight(1f)) {
                        PrimaryButton(
                            label = if (installPermissionMissing) "去开启安装权限" else "下载并安装",
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                        ) {
                            // 缺权限时不下载：装着也会被系统直接拒掉（AppViewModel 同口径）。
                            if (installPermissionMissing) onOpenInstallSettings() else onDownload()
                        }
                    }
                }
            }
        }
    }
}

/** 版本分组里的一行小字（错误/结果/进度/发行说明）。 */
@Composable
private fun VersionNote(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        fontSize = 12.sp,
        color = color,
        lineHeight = 17.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * 「检查中」的循环下落箭头。
 *
 * 就是一个 18dp 的下箭头，每 900ms 从上方 4dp 处落到原位、透明度 0→1 再淡出。
 * 不引 `rememberInfiniteTransition` 之外的东西：检查请求通常 1-3 秒，动效只是
 * 「这一行在动」的信号，不需要真实进度。
 *
 * 为什么不用 CircularProgressIndicator：那个圆环和行首其它图标（线性描边、18dp）
 * 完全不是一套视觉；用户要的就是「箭头往下掉」，和下载图标语义也一致。
 *
 * **动画帧不得触发重组**（2026-10-31，与 ChatScreen.SpinningLeadIcon 同族修复）：
 * 早先 `val progress by …` 把 state 读取放在组合期间，每帧重组一次；现在 `.value`
 * 只在 `graphicsLayer` 的 lambda 里读（deferred read），偏移与透明度都在 layer 块
 * 内换算（translationY 单位是像素，1dp ≈ density），动画帧不再走组合。
 */
@Composable
private fun FallingArrow() {
    // 手动按 10 次/秒推进进度，不用 rememberInfiniteTransition。
    //
    // 2026-10-31 真机实测：无限动画让 Compose 每帧失效一次，整个窗口跟着每帧合成。
    // 列表页只因为一个 14dp 的转圈就吃到 96% GPU，换成静态点后同一页 10 秒 0 帧、
    // GPU 13.9% —— 代价来自「有没有在动」。这里 10fps 足够表现「在往下掉」。
    val stepMs = 100L
    val perStep = stepMs / 900f
    var progress by remember { mutableStateOf(0f) }
    LaunchedEffect(stepMs) {
        while (true) {
            delay(stepMs)
            progress = (progress + perStep) % 1f
        }
    }
    Icon(
        painter = painterResource(R.drawable.ic_lucide_arrow_down),
        contentDescription = "正在检查",
        tint = Ds.textSecondary,
        modifier = Modifier
            .size(18.dp)
            .graphicsLayer {
                val p = progress
                translationY = (-4 + p * 8) * density
                alpha = 1f - (p - 0.7f).coerceAtLeast(0f) / 0.3f
            },
    )
}

/**
 * 「有新版本 X」蓝框胶囊。
 *
 * 用主色描边 + 主色浅底，不加填充实底：行尾只是**提示**，真正要点的是整行（点了开弹窗），
 * 实底按钮会让人以为只有那一小块可点。高度按 12sp 字 + 上下 4dp 撑到约 24dp，
 * 和右侧原来那行 13sp 灰字的视觉重量对齐，不把行高顶大。
 */
@Composable
private fun NewVersionPill(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = Ds.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Ds.primarySoft)
            .border(1.dp, Ds.primarySoftBorder, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** 确认对话框：用 Dialog 而不是内嵌 scrim——内嵌层会被滚动容器裁掉，盖不住整屏。 */@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Ds.bgSurface, RoundedCornerShape(20.dp))
                .padding(20.dp),
        ) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ds.textPrimary)
            Spacer(Modifier.height(10.dp))
            Text(message, fontSize = 14.sp, color = Ds.textSecondary, lineHeight = 20.sp)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    SecondaryButton(
                        label = "取消",
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                    ) { onDismiss() }
                }
                Box(modifier = Modifier.weight(1f)) {
                    PrimaryButton(
                        label = confirmLabel,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                    ) { onConfirm() }
                }
            }
        }
    }
}

/**
 * hero 卡流量数字的刷新间隔。流量是累积计数，刷太快只是白重组，
 * 1 秒足够看出「上行/下行在动」；延迟另有自己的 8 秒探测节奏（AppViewModel）。
 */
private const val TRAFFIC_TICK_MS = 1000L
