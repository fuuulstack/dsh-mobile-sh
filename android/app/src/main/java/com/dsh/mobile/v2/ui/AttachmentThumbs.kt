package com.dsh.mobile.v2.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.mobile.v2.data.Model

/**
 * 定长 LRU。抽成泛型是为了能单测：Bitmap 在 JVM 单测里拿不到
 * （`unitTests.returnDefaultValues = true`，`Bitmap.createBitmap` 返回 null），
 * 而「淘汰顺序」恰恰是这里最容易写错、也最该被钉住的部分。
 */
internal class LruCache<V>(private val limit: Int) {
    private val entries = object : LinkedHashMap<String, V>(limit, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean =
            size > limit
    }

    fun get(key: String): V? = synchronized(entries) { entries[key] }

    fun put(key: String, value: V) {
        synchronized(entries) { entries[key] = value }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }
}

/**
 * 附件缩略图缓存。
 *
 * 时间线是 LazyColumn，往上滑再滑回来会重新组合；不缓存的话每回来一次就要
 * 再走一遍 `session/attachment`（宿主那边是现读现编码 base64），滑动会明显卡顿。
 * 用固定条数的 LRU 而不是无限 Map：图片是 Bitmap，几百张就能把内存吃干净。
 */
internal object ThumbCache {
    private const val LIMIT = 48
    private val cache = LruCache<Bitmap>(LIMIT)

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }

    /** 清空（退出登录/切主机时调用）：换一台电脑后 attachmentId 可能指向别的东西。 */
    fun clear() {
        cache.clear()
    }
}

/** 缩略图缓存键：附件 id 只在单个会话内唯一，必须带上会话。 */
internal fun thumbKey(sessionId: String, attachmentId: String): String =
    "$sessionId/$attachmentId"

/**
 * 缩略图加载不出来时那一格写什么。
 *
 * 三种情况必须**分得开**，因为用户能做的事完全不同：
 * - 中继按设备关掉了附件传输 → 告诉他开关在哪，而不是「加载失败」让他以为坏了；
 * - 真失败（宿主没这张图 / 网络断）→ 说失败；
 * - 还在加载 → 空串，那一格画转圈。
 */
internal fun thumbPlaceholder(enabled: Boolean, failed: Boolean, name: String): String = when {
    !enabled -> "附件传输已关闭"
    failed -> "图片加载失败"
    else -> name.ifBlank { "图片" }
}

/**
 * 这一格此刻该显示什么。
 *
 * <p>比 [thumbPlaceholder] 多认一种状态：**本地刚发出、id 还没回填**（`hasId == false`
 * 且没有本地位图）。那种情况既不是「失败」也不是「已关闭」，它只是「还在发」——
 * 说「图片加载失败」会把一次正常的发送说成故障（2026-11-01 用户实测）。
 */
internal fun thumbState(
    enabled: Boolean,
    failed: Boolean,
    hasId: Boolean,
    hasLocal: Boolean,
    name: String,
): String = when {
    !enabled -> "附件传输已关闭"
    failed -> "图片加载失败"
    !hasId && !hasLocal -> "发送中"
    else -> name.ifBlank { "图片" }
}

/**
 * 大图查看：全屏半透明背景 + 居中图片，点任意处关闭。
 *
 * <p>刻意做成**不可缩放**的最小实现：这里的目标是「看清那张图」，不是相册。
 * 加手势缩放要引入 transformable 状态、双击复位、边界钳位一整套，而当前需求
 * 只是「点开看看」。真要放大，先按屏幕宽度铺满即可——绝大多数聊天截图在这个
 * 尺寸下已经看得清。
 *
 * <p>用 `Dialog` 而不是 `ModalBottomSheet`：大图要盖住整个屏幕（含顶栏与输入区），
 * 抽屉的固有高度与圆角都不合适。
 */
@Composable
internal fun ImageViewer(bitmap: Bitmap, name: String, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .dsClickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = name.ifBlank { "图片" },
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 一条用户消息里的图片。
 *
 * 字节不在本地：durable 日志只留 attachmentId，要看图必须走
 * `session/attachment` 现取（见 [com.dsh.mobile.v2.net.RelayGateway.loadAttachment]）。
 * 所以这里既不是纯渲染，也不该在组合期发请求——用 LaunchedEffect 挂一次，
 * 命中缓存就直接画。
 */
@Composable
internal fun MessageImages(
    sessionId: String,
    images: List<Model.ImageRef>,
    enabled: Boolean,
    load: (Model.ImageRef, (Bitmap?, String?) -> Unit) -> Unit,
    /** 点缩略图：打开大图。为 null 时不可点（比如待发区的预览不需要）。 */
    onOpen: ((Model.ImageRef, Bitmap?) -> Unit)? = null,
) {
    if (images.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (image in images) {
            val key = thumbKey(sessionId, image.attachmentId)
            // **本地刚发的图直接用内存里那份字节**（`Model.ImageRef.local`）：
            // 它的 `attachmentId` 此刻还是空串（要等宿主回显 durable 记录才有 id），
            // 拿空 id 去请求只会得到「图片加载失败」，等发送成功 id 回填后又自己好了
            // —— 用户看到的就是「发送中图片坏了、发完又好了」（2026-11-01 实测）。
            // 字节本来就在手机里，压根不该走网络。
            var bitmap by remember(key, image.local) {
                mutableStateOf(image.local ?: ThumbCache.get(key))
            }
            var failed by remember(key, image.local) { mutableStateOf(false) }
            // enabled 进 key：开关从关到开时这一格要重新去取，
            // 否则用户打开开关后图仍旧停在「已关闭」上，像是没生效。
            LaunchedEffect(key, enabled, image.local) {
                // 本地有字节 / 已加载 / 传输关闭：都不该发请求。
                if (bitmap != null || !enabled) return@LaunchedEffect
                // id 还没回填（本地回声阶段）也别请求——等 durable 记录到了自然会重试。
                if (image.attachmentId.isEmpty()) return@LaunchedEffect
                failed = false
                load(image) { loaded, _ ->
                    if (loaded != null) {
                        ThumbCache.put(key, loaded)
                        bitmap = loaded
                    } else {
                        failed = true
                    }
                }
            }
            val bmp = bitmap
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.18f))
                    // 点开看大图（2026-11-01 用户要求「手机端发送的图片应支持在
                    // 消息气泡里面直接点开查看」）。只有真的画出来了才可点：
                    // 占位格（加载中/失败/发送中）点了没意义。
                    .then(
                        if (onOpen != null && bitmap != null) {
                            Modifier.dsClickable { onOpen(image, bitmap) }
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    bmp != null -> Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = image.name.ifBlank { "图片" },
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    enabled && !failed -> CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp),
                    )
                    else -> Text(
                        thumbState(
                            enabled = enabled,
                            failed = failed,
                            hasId = image.attachmentId.isNotEmpty(),
                            hasLocal = image.local != null,
                            name = image.name,
                        ),
                        fontSize = 10.sp,
                        color = Ds.textOnBrandDim,
                    )
                }
            }
        }
    }
}
