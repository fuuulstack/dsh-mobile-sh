package com.dsh.mobile.v2.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * 设计令牌：色板继承 v1 的取色，尺寸按 Material 的 4dp 栅格重新定。
 *
 * 命名遵循「语义_用途」而非颜色值，屏幕组件只引用令牌、不写字面量。
 * 不追求与 v1 像素一致——同样的 15sp 中文在 Compose 里行盒就是比 TextView 高约 3.7dp，
 * 与其为每处做补偿，不如让间距按 Material 栅格自成一套。
 */

// ==================== 尺寸（4dp 基准网格） ====================
/**
 * 尺寸令牌（4dp 基准网格）。
 *
 * <p>**放在顶层而不是 `Ds` 里**：`Ds` 现在是 `@Composable` getter（随主题取色板），
 * 而尺寸与主题无关——`Dimens.pagePaddingH` 在非 Composable 的地方也要能用
 * （`Modifier` 构造、测试断言）。
 */
object Dimens {
    val pagePaddingH = 20.dp
    val radiusXs = 6.dp
    val radiusSm = 10.dp
    val radiusMd = 14.dp
    val radiusLg = 18.dp
    val radiusBubble = 20.dp
    val heightNavbar = 52.dp
    val heightBottomNav = 64.dp
    val heightButton = 48.dp
    val heightSettingRow = 56.dp
    val statusDot = 8.dp
    val statusDotSm = 6.dp
    val avatarSize = 36.dp
    val iconSm = 16.dp
    val iconMd = 20.dp
    val iconLg = 24.dp
    val iconButton = 36.dp

    val sessionRowPaddingV = 12.dp
}


/** 延迟毫秒 → 红/黄/绿点颜色（与 v1 latencyColorRes 一致）。 */
/**
 * 延迟毫秒 → 红/黄/绿点颜色（与 v1 latencyColorRes 一致）。
 *
 * <p>与 [Dimens] 同理放顶层：它要被非 Composable 的调用方用（`StatusDot` 的
 * modifier 构造、单测），而 `Ds` 已经是组合局部了。
 */
/**
 * 延迟分档：0=未知/离线，1=好，2=一般，3=差。
 *
 * <p>抽成纯函数是为了可测——[latencyColor] 现在要读组合局部（色板随主题走），
 * 单测里没有组合上下文，只能钉分档边界。
 */
fun latencyBand(ms: Int): Int = when {
    ms < 0 -> 0
    ms <= 80 -> 1
    ms <= 200 -> 2
    else -> 3
}

/** 分档 → 颜色（与 v1 latencyColorRes 一致）。 */
@Composable
@ReadOnlyComposable
fun latencyColor(ms: Int): Color = when (latencyBand(ms)) {
    0 -> Ds.statusOfflineDot
    1 -> Ds.statusSuccess
    2 -> Ds.statusWarning
    else -> Ds.statusError
}

class DshColors(private val dark: Boolean = false) {
    // 浅色值原样保留；深色值见下面的 `dark` 分支。
    // 之所以是「运行时算」而不是两个常量类：调用点全是 `Ds.bgPage` 这种写法，
    // 换成两套实例就得改 329 处。这样写调用点一处不动。

    val primary: Color = Color(0xFF5E6AD2)
    val primaryHover: Color = Color(0xFF4F5BC4)
    val primarySoft: Color = if (dark) Color(0xFF23273A) else Color(0xFFF0F2FA)
    val primarySoftBorder: Color = if (dark) Color(0xFF333A5C) else Color(0xFFD7DBF5)
    val onPrimary: Color = Color.White
    val brandGradientStart: Color = Color(0xFF5E6AD2)
    val brandGradientEnd: Color = Color(0xFF4F5BC4)
    val bgPage: Color = if (dark) Color(0xFF121317) else Color(0xFFF7F8FA)
    val bgSurface: Color = if (dark) Color(0xFF1B1D22) else Color(0xFFFFFFFF)
    val bgSurfaceVariant: Color = if (dark) Color(0xFF23262C) else Color(0xFFF4F5F7)
    val bgElevated: Color = if (dark) Color(0xFF22252B) else Color(0xFFFFFFFF)
    val bgPressed: Color = if (dark) Color(0xFF2A2D34) else Color(0xFFECEEF2)
    // 遮罩：亮色下 40% 黑压得太重 —— 菜单只是「浮起一层」，不是模态对话框；降到
    // 10% 才符合这套克制的口径。暗色底色本来就深，10% 等于没压，保持 40%（F-04）。
    val bgScrim: Color = if (dark) Color(0x66000000) else Color(0x1A000000)
    val bgPopup: Color = if (dark) Color(0xFF23262C) else Color.White
    val textPrimary: Color = if (dark) Color(0xFFE8EAF0) else Color(0xFF121316)
    val textSecondary: Color = if (dark) Color(0xFFB6BBC7) else Color(0xFF4E5360)
    val textTertiary: Color = if (dark) Color(0xFF9AA0AC) else Color(0xFF62666D)
    val textMuted: Color = if (dark) Color(0xFF8A909C) else Color(0xFF6B7280)
    val textHint: Color = if (dark) Color(0xFF868C98) else Color(0xFF666B75)
    val textOnBrand: Color = Color.White
    val textOnBrandDim: Color = Color(0xFFD7DBF5)
    val borderDefault: Color = if (dark) Color(0xFF2A2D34) else Color(0xFFF0F2F5)
    val borderSubtle: Color = if (dark) Color(0xFF24272D) else Color(0xFFF4F5F7)
    val borderStrong: Color = if (dark) Color(0xFF343841) else Color(0xFFE5E7EB)
    val divider: Color = if (dark) Color(0xFF2A2D34) else Color(0xFFECEEF2)
    val bubbleUserBg: Color = Color(0xFF5E6AD2)
    val bubbleUserText: Color = Color.White
    val bubbleAssistantBg: Color = if (dark) Color(0xFF1B1D22) else Color.White
    val bubbleAssistantText: Color = if (dark) Color(0xFFE8EAF0) else Color(0xFF121316)
    val statusOfflineDot: Color = Color(0xFFD1D5DB)
    val statusUnreadDot: Color = Color(0xFF10B981)
    val statusSuccess: Color = Color(0xFF16A34A)
    val statusSuccessSoft: Color = if (dark) Color(0xFF14301F) else Color(0xFFF0FDF4)
    val statusWarning: Color = Color(0xFFF59E0B)
    val statusWarningSoft: Color = if (dark) Color(0xFF33260E) else Color(0xFFFEF3C7)
    val statusError: Color = Color(0xFFDC2626)
    val statusErrorSoft: Color = if (dark) Color(0xFF35191B) else Color(0xFFFEE2E2)
    val statusRunning: Color = Color(0xFF5E6AD2)
    val navActiveFg: Color = Color(0xFF5E6AD2)
    val navInactiveFg: Color = if (dark) Color(0xFF868C98) else Color(0xFF62666D)
    val composerBg: Color = if (dark) Color(0xFF23262C) else Color(0xFFE6E8EE)
    val composerSendDisabledBg: Color = if (dark) Color(0xFF2E323A) else Color(0xFFD8DBE3)
    val codeBg: Color = if (dark) Color(0xFF0E0F13) else Color(0xFF15161B)
    val codeText: Color = Color(0xFFD8DEE9)
    val timelineDone: Color = Color(0xFF16A34A)
    val timelineActive: Color = Color(0xFF5E6AD2)
    val timelinePending: Color = if (dark) Color(0xFF343841) else Color(0xFFE5E7EB)

    // ==================== 品牌与强调（Linear 风格靛青） ====================

    // ==================== 页面与表面 ====================
    // 页面底要比卡片底暗一档：两者原先同为 #FFFFFF，分组卡片在设置页里完全消失，
    // 「SectionLabel + Card」的分组语义只剩标签文字（2026-10-27 评审 F-03）。

    // ==================== 文字 ====================
    // 灰字三档都按 WCAG AA（小字 4.5:1）实测定过，别按观感调浅：
    // 对 #FFFFFF 依次是 5.77 / 4.83 / 5.35，对各自的浅灰底（#F4F5F7）也仍 ≥4.5（2026-10-27 评审 F-06）。

    // ==================== 边框与分割 ====================

    // ==================== 聊天气泡 ====================

    // ==================== 状态语义 ====================
    // 危险色加深一档：原 #EF4444 对白只有 3.76（不达 4.5），红底白字按钮同样不达标；
    // #DC2626 对白 4.83，当文字用、当底色用都过线（2026-10-27 评审 F-06）。

    // ==================== 组件专用 ====================
    // 底栏未选中态是 11sp 小字，同样要过 4.5:1：旧值 #8A8F9D 对白只有 3.23（2026-10-27 评审 F-06）。
    /**
     * 输入框底色。
     *
     * 原值 #F4F5F7 与页面底 #F7F8FA 只差 3 个灰阶，输入框在聊天页里几乎看不出来
     * （2026-10-28 用户反馈：「聊天框颜色稍微加深一点点，与背景色区分开」）。
     * #E6E8EE 与页面底的亮度差足够让边框「显形」，又不至于重到抢正文的注意力；
     * 它比 [composerSendDisabledBg] 浅，保留「空态发送钮更深一档」的语义。
     */
    /** 发送钮空态底色（无文本且未运行）：比 composerBg 深一档，提示「现在发不了」。 */


}

/**
 * 当前生效的色板。
 *
 * <p>`compositionLocalOf` 而不是 `staticCompositionLocalOf`：切换深浅色时值会变，
 * 用 static 版本所有读取方都不会重组。
 */
val LocalDs = compositionLocalOf { DshColors(dark = false) }

/**
 * 全局色板入口。
 *
 * <p>**保持 `Ds.xxx` 的写法不变**（全仓 329 处调用点一处都不用改）：原来 `Ds` 是
 * 一个 `object`，现在它退成一个 `@Composable` getter，从 [LocalDs] 取当前实例。
 *
 * <p>代价：它只能在 `@Composable` 作用域里读。这是**有意的**——色板本来就该随主题
 * 走，任何非 Composable 的地方读到「当前主题色」都说明那个函数拿错了依赖。
 */
val Ds: DshColors
    @Composable
    @ReadOnlyComposable
    get() = LocalDs.current

/**
 * 应用主题：把 Ds 令牌映射进 Material3 配色，屏幕内再叠加语义色。
 * v1 仅有浅色方案（参考稿为浅色），这里跟随系统但把暗色调成低饱和深灰，
 * 避免纯黑闪屏；不单独维护两套视觉稿。
 */
@Composable
fun DshTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val tokens = androidx.compose.runtime.remember(dark) { DshColors(dark = dark) }
    // 一律从 `tokens` 取，不再把暗色写成另一组硬编码字面量 ——
    // 早先这里暗色分支自己写了一组 `Color(0xFF16171B)` 之类，而全仓界面读的是
    // `Ds.*`（当时是硬编码浅色 object），于是系统切暗色时**只有 Material 组件变暗、
    // 自绘部分全白**（2026-11-01 真机 `cmd uimode night yes` 实测：列表底仍是
    // F7F8FA）。现在 `tokens` 由 `Ds(dark=)` 生成，Material 与自绘走同一套值。
    val colors = if (dark) {
        darkColorScheme(
            primary = tokens.primary,
            onPrimary = tokens.onPrimary,
            background = tokens.bgPage,
            surface = tokens.bgSurface,
            surfaceVariant = tokens.bgSurfaceVariant,
            onSurface = tokens.textPrimary,
            onSurfaceVariant = tokens.textSecondary,
            outline = tokens.borderStrong,
            error = tokens.statusError,
        )
    } else {
        lightColorScheme(
            primary = tokens.primary,
            onPrimary = tokens.onPrimary,
            background = tokens.bgPage,
            surface = tokens.bgSurface,
            surfaceVariant = tokens.bgSurfaceVariant,
            onSurface = tokens.textPrimary,
            onSurfaceVariant = tokens.textSecondary,
            outline = tokens.borderStrong,
            error = tokens.statusError,
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalDs provides tokens) {
        MaterialTheme(colorScheme = colors) {
            CompositionLocalProvider(LocalTextStyle provides TextStyle.Default, content = content)
        }
    }
}
