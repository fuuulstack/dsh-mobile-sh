package com.dsh.mobile.v2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * 共享组件：按钮、药丸、状态点、延迟胶囊、图标按钮。
 *
 * 与 v1 Widgets.java 的对应件保持视觉一致：渐变主按钮、描边次按钮、
 * 透明波纹图标按钮、红黄绿延迟胶囊。
 */

// ====
// 点击反馈（v1 ripple 体系的 Compose 版）
// ====

/**
 * 点击反馈（v1 视觉基准的 Compose 版，无涟漪）。
 *
 * 用户明确要求不要涟漪（视觉克制口径：宁可低透明度、细线条，也不要扩散动效）。
 * 反馈走「按压态背景色」：按下时叠一层 bgPressed（#ECEEF2，v1 令牌已有但从未用上），
 * 抬起即恢复——静态视觉与 v1 完全一致，交互反馈比涟漪更安静。
 */

// 按压态背景原先是顶层 val（`private val PressedOverlay = Ds.bgPressed`），
// 而 `Ds` 现在是随主题走的组合局部 —— 顶层 val 在类初始化时求值，读不到它。
// 改成在 Modifier 里现取（见 dsClickable 的 pressedBg）。

/**
 * 无涟漪点击：主要可点区域（列表项、卡片、设置行等）。
 * 按下时背景叠 PressedOverlay，松开恢复。
 */
fun Modifier.dsClickable(
    enabled: Boolean = true,
    shape: Shape? = null,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = MutableInteractionSource()
    val pressed by interaction.collectIsPressedAsState()
    val pressedBg = if (pressed) Modifier.background(Ds.bgPressed, shape ?: RectangleShape) else Modifier
    this
        .then(pressedBg)
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = role,
            onClick = onClick,
        )
}

/**
 * 无涟漪中性点击：图标按钮、tab 等次要入口。
 * 不加按压背景（图标/文字本身变色已足够反馈），只去掉涟漪。
 */
fun Modifier.dsClickableNeutral(
    enabled: Boolean = true,
    shape: Shape? = null,
    onClick: () -> Unit,
): Modifier = composed {
    clickable(
        interactionSource = MutableInteractionSource(),
        indication = null,
        enabled = enabled,
        onClick = onClick,
    )
}

/** 品牌渐变主按钮（登录/连接/发送等主行动点）。 */
@Composable
fun PrimaryButton(
    label: String,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val brush = Brush.linearGradient(listOf(Ds.brandGradientStart, Ds.brandGradientEnd))
    Box(
        modifier = modifier
            .background(
                if (enabled) brush else Brush.linearGradient(
                    listOf(Ds.textHint, Ds.textHint)
                ),
                RoundedCornerShape(14.dp),
            )
            .dsClickable(enabled = enabled && !busy) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            Spinner(size = 18.dp, color = Ds.onPrimary, strokeWidth = 2.dp)
        } else {
            Text(
                label,
                color = Ds.onPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** 描边次按钮（一屏内只留一个主行动点，第二个行动点用描边款）。 */
@Composable
fun SecondaryButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {    Box(
        modifier = modifier
            .background(Color.Transparent, RoundedCornerShape(14.dp))
            .border(
                width = 1.dp,
                color = if (enabled) Ds.primarySoftBorder else Ds.borderStrong,
                shape = RoundedCornerShape(14.dp),
            )
            .dsClickableNeutral(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) Ds.primary else Ds.textTertiary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 状态小点（在线/未读/运行中）；克制标记：小尺寸、低饱和。 */
@Composable
fun StatusDot(color: Color, size: androidx.compose.ui.unit.Dp = Dimens.statusDotSm) {
    Box(
        modifier = Modifier
            .size(size)
            .background(color, androidx.compose.foundation.shape.CircleShape)
    )
}

/** 顶栏延迟胶囊：红黄绿点 + 毫秒数；-1 表示未知（灰点 + —）。 */
@Composable
fun LatencyChip(latencyMs: Int, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    Row(
        modifier = modifier
            .background(Ds.bgSurfaceVariant, RoundedCornerShape(11.dp))
            .dsClickableNeutral { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(latencyColor(latencyMs), Dimens.statusDotSm)
        Spacer(Modifier.width(5.dp))
        Text(
            if (latencyMs >= 0) "${latencyMs}ms" else "—",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = Ds.textSecondary,
        )
    }
}

/** 药丸标签（状态/筛选）；低饱和底色，不抢主行动点。 */
@Composable
fun Pill(
    text: String,
    textColor: Color,
    bgColor: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        color = textColor,
        fontSize = 12.sp,
        modifier = modifier
            .background(bgColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** 分组小标题（工作区名/设置分组）。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Ds.textTertiary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        // 中文不吃字距：0.4sp 的 tracking 是拉丁小标题的习惯，夹在汉字之间只会让
        // 「工作区 / 设置」这类两三个字的标题看着被扯开（W-03）。
        letterSpacing = 0.sp,
        // 水平边距取 20dp，与设置页的页面边距（标题、卡片）同一条线：
        // 原来这里是 4dp，标签比卡片还往左戳 16dp，看着像没对齐（2026-10-27 真机指出）。
        modifier = modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 8.dp),
    )
}

/** 空态提示文案。 */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Ds.textMuted,
        fontSize = 15.sp,
        modifier = modifier.padding(horizontal = 24.dp, vertical = 40.dp),
    )
}


/**
 * 圆形图标按钮（v1 顶栏按钮：完整圆形、浅灰底、直径 44dp）。
 * 用于首页搜索 / 更多、聊天页返回 / 更多。
 */
@Composable
fun CircleIconButton(
    icon: Int,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp = 40.dp,
    tint: Color = Ds.textSecondary,
    onClick: () -> Unit,
) {
    // 视觉圈保持 [size]（调用点给 34dp，与 v1 实测直径一致），触控目标放大到 48dp：
    // 34dp 只有约 5.4mm，比 Material 的 48dp 最小触控目标小一圈，拇指在顶栏上
    // 很容易点偏（W-02）。
    //
    // 内层用 requiredSize 而不是 size：`size` 会被父 Row 的剩余空间拉成椭圆，
    // 现场出现过搜索钮宽 48dp、更多钮宽 33.5dp 的一宽一窄（W-02 实测）。
    Box(
        modifier = Modifier
            .size(48.dp)
            .dsClickableNeutral { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .requiredSize(size)
                .background(Ds.bgSurfaceVariant, androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                painter = androidx.compose.ui.res.painterResource(icon),
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(size * 0.45f),
            )
        }
    }
}

/** 输入框：圆角浅灰底、无描边（v1 同款视觉）。登录屏表单取消后迁到这里：其他屏（配对抽屉、编辑标题）仍在用。 */
@Composable
fun DshInput(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    keyboard: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(
            fontSize = 15.sp,
            color = Ds.textPrimary,
        ),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboard),
        decorationBox = { inner ->
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .background(Ds.bgSurfaceVariant, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(hint, fontSize = 15.sp, color = Ds.textHint)
                }
                inner()
            }
        },
    )
}


/**
 * 字节数 → 短文案（「0 B」「12.4 KB」「3.06 MB」）：诊断与累计值用。
 *
 * 用 B/KB/MB/GB 十进制档位而不是 KiB，跟中继与宿主日志里的口径一致；
 * 小数位随单位放大而收紧，免得 GB 级还显示成「1.2345 GB」。
 */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

/**
 * 速率 → 短文案（「0 B/s」「12.4 KB/s」）：设置页 hero 卡的上行/下行速率用。
 * 负数是「还没攒出窗口」（刚进页面），显示「—」而不是 0。
 */
internal fun formatBps(bps: Long): String {
    if (bps < 0) return "—"
    return formatBytes(bps) + "/s"
}

/**
 * 小转圈：定值 270° 弧 + 按固定节奏换角度。
 *
 * **为什么不是 `rememberInfiniteTransition`**（2026-10-31 真机实测）：无限动画会让
 * Compose 每帧（60fps）都失效一次，进而整个窗口每帧重新合成 —— 在 Android/ColorOS 上
 * `GpuWorkeBPF` 直接报 **96% GPU**，而同一个 App 的其它页面是 0 帧、GPU 个位数。
 * 对照实验：列表页 `dumpsys gfxinfo` 10 秒 614 帧；把这个 Spinner 换成静态
 * `StatusDot` 后同样 10 秒 **0 帧**。也就是说，「一屏里有一个小小的转圈」就足以
 * 把 GPU 拉满 —— 与它多小、转得多快无关，只与「有没有在动」有关。
 *
 * 所以这里用 `LaunchedEffect` + `delay` 手动推进角度，把失效频率从 60Hz 降到
 * [stepsPerSecond] —— 默认 15，肉眼仍连续，但 GPU 占用大约只有原来的四分之一。
 * 列表页那种「一屏几十行」的场景可以再调低。
 *
 * @param stepsPerSecond 每秒更新几次角度。低于 8 会看出顿感。
 */
@Composable
fun Spinner(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 14.dp,
    color: Color = Ds.primary,
    strokeWidth: androidx.compose.ui.unit.Dp = 1.6.dp,
    stepsPerSecond: Int = 15,
) {
    // 一圈 900ms：换算成每次推进的角度，视觉速度与原来的 tween 一致。
    val stepMs = (1000L / stepsPerSecond.coerceAtLeast(1)).coerceAtLeast(16L)
    val perStep = 360f * stepMs / 900f
    var angle by remember { mutableStateOf(0f) }
    LaunchedEffect(stepMs) {
        while (true) {
            delay(stepMs)
            angle = (angle + perStep) % 360f
        }
    }
    Canvas(
        modifier = modifier
            .size(size)
            // 角度变化只走 layer 矩阵：不重新录制绘制命令。
            .graphicsLayer { rotationZ = angle },
    ) {
        val stroke = strokeWidth.toPx()
        // 内缩半个线宽，否则描边会被画布边缘裁掉一半。
        val inset = stroke / 2f
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    }
}

/**
 * 进度条的确定态：一条底色轨道 + 一段主色。
 *
 * 换掉 Material3 的 [androidx.compose.material3.LinearProgressIndicator]：
 * 只有下载进度在用，值由 `downloadPercent` 驱动（不是动画），自绘没有额外开销，
 * 也省掉 Material3 那套 `progress = { }` 的 lambda 间接层。
 */
@Composable
fun ProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = Ds.primary,
    trackColor: Color = Ds.bgSurfaceVariant,
) {
    Canvas(modifier = modifier.height(4.dp).fillMaxWidth()) {
        val r = this.size.height / 2f
        drawRoundRect(
            color = trackColor,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
        )
        val w = (this.size.width * progress.coerceIn(0f, 1f))
        if (w > 0f) {
            drawRoundRect(
                color = color,
                size = Size(w, this.size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
            )
        }
    }
}
