package com.dsh.mobile.v2.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.mobile.v2.R

/**
 * 登录与配对屏（2026-10-27 按用户要求改为纯扫码）。
 *
 * <p>旧版是「中继地址 + 配对码 + 设备名称」三栏表单，加上扫一扫只是右上角的旁路。
 * 现在表单整个取消：配对信息全部由二维码携带（插件的「生成二维码」一个码包含
 * 中继地址 + 登录码 + 配对码），用户唯一要做的事就是扫一下。
 *
 * <p>错误与忙碌状态仍要显示（扫码失败、老格式码需要回退登录时用户得知道发生了什么），
 * 但不再有可编辑的输入框——回退路径走 [onScan] 重扫，而不是手敲。
 *
 * <p>设备名称沿用 [android.os.Build.MODEL]（自动带出，用户无需关心）。
 */
@Composable
fun LoginScreen(
    busy: Boolean,
    error: String,
    prefillEndpoint: String,
    prefillCode: String,
    onSubmit: (endpoint: String, code: String, label: String) -> Unit,
    onClearError: () -> Unit,
    onScan: () -> Unit = {},
    /**
     * 左上角返回会话列表。只在**已登录**时给（[AppRoot] 按 `canReturnHome()` 传）：
     * 未登录时这一页前面没有可回的地方，画一个返回键只会让人以为能退到某处。
     */
    onBack: (() -> Unit)? = null,
) {
    // 参数保留（调用方签名不变），但表单已取消：这两个预填值只用于深链旧格式
    // 兜底提示，不再渲染成输入框。
    @Suppress("UNUSED_PARAMETER") val unusedPrefill = prefillEndpoint
    @Suppress("UNUSED_PARAMETER") val unusedCode = prefillCode

    Box(modifier = Modifier.fillMaxSize().background(Ds.bgPage)) {
        if (onBack != null) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 4.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_chevron_left),
                    contentDescription = "返回会话列表",
                    tint = Ds.textSecondary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // 品牌头：大飞鱼立绘（图本身带淡色光环，不再套 tint）
            Image(
                painter = painterResource(R.drawable.dafeiyu_logo),
                contentDescription = null,
                modifier = Modifier.size(96.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "DeepSeek Harness",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Ds.textPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "在电脑的 DSH「手机访问」页生成二维码，扫一下即可连接",
                fontSize = 13.sp,
                color = Ds.textMuted,
            )

            Spacer(Modifier.height(40.dp))

            // 唯一的入口：圆形渐变扫码按钮。
            // 不放文字：这一屏只有一件事可做，图标本身就是说明；文字版会把按钮拉成
            // 跟着字数走的窄条（PrimaryButton 只收 label，尺寸由内容决定）。
            ScanButton(busy = busy, onClick = onScan)

            Spacer(Modifier.height(24.dp))

            if (error.isNotEmpty()) {
                Text(
                    error,
                    fontSize = 12.sp,
                    color = Ds.statusError,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            } else if (busy) {
                Text(
                    "正在连接…",
                    fontSize = 12.sp,
                    color = Ds.textMuted,
                )
            }
        }
    }
}

/** 扫码按钮：圆形品牌渐变底 + 居中扫码图标（大尺寸，触控 72dp 够用）。 */
@Composable
private fun ScanButton(busy: Boolean, onClick: () -> Unit) {
    val brush = Brush.linearGradient(listOf(Ds.brandGradientStart, Ds.brandGradientEnd))
    Box(
        modifier = Modifier
            .size(72.dp)
            .background(brush, CircleShape)
            .dsClickable(enabled = !busy) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            Spinner(size = 26.dp, color = Ds.onPrimary, strokeWidth = 2.dp)
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_scan),
                // 无文字按钮必须有描述，否则读屏只剩「按钮」两个字。
                contentDescription = "扫一扫",
                tint = Ds.onPrimary,
                modifier = Modifier.size(34.dp),
            )
        }
    }
}
