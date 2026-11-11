package com.dsh.mobile.v2.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.dsh.mobile.v2.R
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 扫一扫（v1 ScanScreen 的 Compose 移植）：对准中继网页上的配对二维码，
 * 直接拿到中继地址与 8 位配对码。
 *
 * <p>技术选型与 v1 完全一致：framework Camera2 + zxing 纯 Java 解码器
 * （{@code com.google.zxing:core}）。不引 CameraX（体积大、要额外 androidx 包），
 * 不用 ML Kit（依赖 GMS，国内设备装不上）。Camera2 的 YUV 帧直接喂
 * {@link com.google.zxing.PlanarYUVLuminanceSource}，不需要 Bitmap 转换；
 * 只取 Y 平面跳过色彩转换，单帧成本很低，再叠 300ms 节流防 CPU 打满。
 *
 * <p>解码与解析拆成顶层函数 {@link #decodeLuminance} / {@link #parsePairing}，
 * JVM 单测可用（zxing 生成矩阵铺成 Y 平面解回，验证「相机帧 → 解码」链路；
 * URI 解析用 java.net.URI 而非 android.net.Uri——后者在 JVM 测试里是 stub）。
 */

/** 扫码结果：中继地址 + 8 位配对码 + 可选的 8 位登录码（一码配齐）。 */
data class ScanPairing(val relay: String, val code: String, val login: String? = null)

/**
 * 解析二维码内容。
 *
 * 约定格式（插件「生成二维码」/ 中继网页生成）：
 * - 一码配齐：`dsh://pair?relay=https://relay.example.com&login=12345678&code=87654321`
 *   （`login` 是与配对码同批签发的设备登录码，扫码即完成「登录 + 配对」，用户全程不见）
 * - 旧格式：`dsh://pair?relay=…&code=…`（无 login：手机回退到旧的两步登录）
 * 同时容忍纯 URL 形式 `https://relay.example.com/#12345678`，方便手写测试。
 */
fun parsePairing(text: String?): ScanPairing? {
    if (text.isNullOrBlank()) return null
    val value = text.trim()
    return try {
        val uri = URI(value)
        val scheme = uri.scheme ?: return null
        if (scheme.equals("dsh", ignoreCase = true)) {
            val query = queryOf(uri.rawQuery)
            val relay = query["relay"] ?: return null
            val code = query["code"] ?: return null
            val login = query["login"]?.takeIf { it.matches(Regex("\\d{8}")) }
            normalizePairing(relay, code, login)
        } else if (scheme.lowercase().startsWith("http")) {
            // 兜底：https://relay.example.com/#12345678
            val fragment = uri.fragment
            if (fragment != null && fragment.matches(Regex("\\d{8}")) && uri.host != null) {
                normalizePairing("$scheme://${uri.host}", fragment, null)
            } else null
        } else null
    } catch (_: Exception) {
        null
    }
}

/** 拆 query 串并做 percent-decode；非法转义直接跳过该键，不抛。 */
private fun queryOf(rawQuery: String?): Map<String, String> {
    if (rawQuery.isNullOrEmpty()) return emptyMap()
    val out = mutableMapOf<String, String>()
    for (pair in rawQuery.split("&")) {
        val eq = pair.indexOf('=')
        if (eq <= 0) continue
        try {
            out[URLDecoder.decode(pair.substring(0, eq), "UTF-8")] =
                URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
        } catch (_: Exception) {
            // 转义坏了就当这个键不存在
        }
    }
    return out
}

private fun normalizePairing(relay: String, code: String, login: String?): ScanPairing? {
    val trimmed = code.trim()
    if (!trimmed.matches(Regex("\\d{8}"))) return null
    var base = relay.trim()
    if (!base.startsWith("http://") && !base.startsWith("https://")) {
        // 没写协议时按主机形态补：域名补 https（线上中继必有 TLS），**IP 字面量补 http**
        // ——自建裸 IP 部署通常拿不到证书（IP 证书要单独签发），中继那边也要显式开
        // RELAY_ALLOW_INSECURE 才肯明文启动。补 https 只会让扫码后直接连不上。
        val hostPart = base.substringBefore('/').substringBefore(':')
        val ipLike = hostPart.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) || hostPart.contains(':')
        base = if (ipLike) "http://$base" else "https://$base"
    }
    while (base.endsWith("/")) base = base.dropLast(1)
    return ScanPairing(base, trimmed, login)
}

/** 只解二维码：不去试一维码能省掉一半的无效扫描。 */
private val HINTS: Map<com.google.zxing.DecodeHintType, Any> = mapOf(
    com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE),
    com.google.zxing.DecodeHintType.TRY_HARDER to false,
)

/**
 * 从相机 Y 平面解一个二维码。
 *
 * @param rowStride 行跨度（相机给的每行字节数，通常大于 width）
 */
fun decodeLuminance(data: ByteArray?, rowStride: Int, width: Int, height: Int): String? {
    if (data == null || width <= 0 || height <= 0 || rowStride < width) return null
    val source = com.google.zxing.PlanarYUVLuminanceSource(data, rowStride, height, 0, 0, width, height, false)
    return try {
        com.google.zxing.MultiFormatReader()
            .decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source)), HINTS)
            .text
    } catch (_: Exception) {
        // NotFoundException 是常态（这一帧没对上焦），交给下一帧。
        null
    }
}

/**
 * 扫码屏：相机预览 + 取景框 + 解码循环。
 *
 * @param onScanned 解出可用配对内容（主线程回调）
 * @param onFailed  相机打不开、没权限、设备没摄像头
 * @param onClose   用户主动退出
 */
@Composable
fun ScanScreen(
    onScanned: (ScanPairing) -> Unit,
    onFailed: (String) -> Unit,
    onClose: () -> Unit,
    /** 相机权限被授予的次数（AppViewModel.cameraGrantTick）：变化即自动重试一次。 */
    grantTick: Int = 0,
) {
    var hint by remember { mutableStateOf("对准电脑网页上的配对二维码") }
    var failed by remember { mutableStateOf<String?>(null) }
    val engine = remember { ScanEngine() }
    var textureReady by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current

    DisposableEffect(Unit) {
        onDispose {
            // 页面退出：先停解码回调（防 onScanned 晚到打到已销毁的登录态），再放相机。
            engine.delivered = true
            engine.release()
        }
    }

    // 权限弹窗关掉后才拿到授权：页面早已建好、start() 却已按「没权限」失败过，
    // 这里跟着 grantTick 自动再试一次。不改 attempt 之外的状态，避免冲掉已有画面。
    LaunchedEffect(grantTick) {
        if (grantTick > 0) {
            engine.release()
            engine.resetForRetry()
            failed = null
            textureReady = engine.pendingTexture != null
            attempt++
        }
    }

    LaunchedEffect(textureReady, attempt) {
        if (textureReady) engine.start(
            context = context,
            texture = engine.pendingTexture,
            onFailed = { failed = it },
            onDecoded = { text ->
                val pairing = parsePairing(text)
                if (pairing == null) {
                    // 扫到别的码（付款码、网址）不该直接失败，提示一句继续扫。
                    hint = "这不是 DSH 的配对码，换个二维码试试"
                } else {
                    engine.delivered = true
                    onScanned(pairing)
                }
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                TextureView(context).also { tv ->
                    tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surface: android.graphics.SurfaceTexture, width: Int, height: Int,
                        ) {
                            engine.pendingTexture = surface
                            textureReady = true
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surface: android.graphics.SurfaceTexture, width: Int, height: Int,
                        ) {}

                        @Suppress("UnusedParameter")
                        override fun onSurfaceTextureDestroyed(
                            surface: android.graphics.SurfaceTexture,
                        ): Boolean {
                            engine.pendingTexture = null
                            textureReady = false
                            return true
                        }

                        override fun onSurfaceTextureUpdated(
                            surface: android.graphics.SurfaceTexture,
                        ) {}
                    }
                }
            },
        )

        // 相机画面亮度不受控：纯白墙面前白字顶栏会整块消失（模拟器虚拟场景
        // 就复现了——标题与返回按钮一个字都看不见）。上下各垫一层深色渐变，
        // 让白色文字始终有对比度；渐变本身不可点，不影响遮罩下的取景框。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0x99000000), Color(0x00000000)),
                    )
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0x00000000), Color(0x99000000)),
                    )
                ),
        )

        // 取景框：四条半透明白边围出方形区域（v1 同款视觉，克制不加动效）
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(240.dp)
                .border(2.dp, Color(0x66FFFFFF), RoundedCornerShape(20.dp)),
        )

        // 顶部：返回 + 标题
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 28.dp, start = 8.dp, end = 16.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 返回键带一层半透明底：相机画面是不可控的，白底墙面上原来的纯白箭头
            // 几乎看不见（用户口径 2026-11-05「扫码页新增一个返回按钮」——按钮其实在，
            // 但在亮画面里读不出来）。44dp 圆底既是视觉锚点，也满足触控尺寸。
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color(0x66000000), CircleShape)
                    .dsClickable { onClose() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_chevron_left),
                    contentDescription = "返回",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.size(8.dp))
            Text(
                "扫一扫",
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
            )
        }

        // 底部提示 / 失败原因 + 重试
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                failed ?: hint,
                fontSize = 13.sp,
                color = if (failed != null) Color(0xFFFFB4AB) else Color(0xB3FFFFFF),
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (failed != null) {
                Spacer(Modifier.height(12.dp))
                SecondaryButton(
                    label = "重试",
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    // 权限弹窗期间相机被系统断开等场景：释放后整个重来（含单飞标志）。
                    engine.release()
                    engine.resetForRetry()
                    failed = null
                    attempt++
                }
            }
        }
    }
}

/**
 * 相机与解码引擎：Compose 状态之外的原生存留（闭包持有 CameraDevice/Session/Reader，
 * 不进重组作用域）。release() 必须在页面销毁时调用——不释放相机会让下一次打开直接失败。
 */
private class ScanEngine {
    var pendingTexture: android.graphics.SurfaceTexture? = null
    @Volatile var delivered = false

    /** 单飞守卫：start 只允许一次（重组/键变化会重复调，二次 openCamera 会被
     *  CameraService 顶掉旧连接并误报「被占用」——2026-10-26 真机实测踩坑）。 */
    @Volatile private var started = false

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: android.media.ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val decoding = AtomicBoolean(false)
    private var lastDecodeAt = 0L
    /** 曾经拿到过已打开的相机（onOpened 回调过）：之后的 onDisconnected 是被新连接顶掉
     *  或页面退出，属预期，不再当失败报。 */
    @Volatile private var everOpened = false

    fun start(
        context: Context,
        texture: android.graphics.SurfaceTexture?,
        onFailed: (String) -> Unit,
        onDecoded: (String) -> Unit,
    ) {
        if (started) return
        started = true
        val fail: (String) -> Unit = { started = false; onFailed(it) }
        val surfaceTexture = texture ?: return fail("相机画面还没有准备好")
        if (!hasCameraPermission(context)) {
            return fail("没有相机权限")
        }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: run {
            return fail("这台设备没有相机服务")
        }
        try {
            val cameraId = pickBackCamera(manager) ?: return fail("没有找到可用的摄像头")
            val cameraThread = HandlerThread("dsh-v2-scan").also { it.start() }
            val cameraHandler = Handler(cameraThread.looper)
            thread = cameraThread
            handler = cameraHandler
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    everOpened = true
                    camera = device
                    openSession(device, surfaceTexture, onFailed, onDecoded)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    camera = null
                    // 曾成功打开过：断开多半是页面退出或被系统回收，不按「被占用」报错；
                    // 从未打开过就断开：才真的是权限/占用问题。
                    if (!everOpened) onFailed("相机被其它应用占用")
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    camera = null
                    onFailed("相机打开失败（$error）")
                }
            }, cameraHandler)
        } catch (_: android.hardware.camera2.CameraAccessException) {
            onFailed("无法访问相机")
        } catch (_: SecurityException) {
            onFailed("没有相机权限")
        }
    }

    private fun openSession(
        device: CameraDevice,
        surfaceTexture: android.graphics.SurfaceTexture,
        onFailed: (String) -> Unit,
        onDecoded: (String) -> Unit,
    ) {
        val h = handler ?: return
        try {
            val size = Size(1280, 720)
            val imageReader = android.media.ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
            imageReader.setOnImageAvailableListener({ source -> onImage(source, onDecoded) }, h)
            reader = imageReader

            surfaceTexture.setDefaultBufferSize(size.width, size.height)
            val previewSurface = Surface(surfaceTexture)

            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(previewSurface)
            builder.addTarget(imageReader.surface)
            builder.set(
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            )
            builder.set(
                android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_ON,
            )

            device.createCaptureSession(
                listOf(previewSurface, imageReader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        session = configured
                        try {
                            configured.setRepeatingRequest(builder.build(), null, h)
                        } catch (_: android.hardware.camera2.CameraAccessException) {
                            onFailed("无法开始预览")
                        } catch (_: IllegalStateException) {
                            onFailed("无法开始预览")
                        }
                    }

                    override fun onConfigureFailed(configured: CameraCaptureSession) {
                        onFailed("相机配置失败")
                    }
                },
                h,
            )
        } catch (_: android.hardware.camera2.CameraAccessException) {
            onFailed("无法打开预览")
        } catch (_: IllegalArgumentException) {
            onFailed("无法打开预览")
        }
    }

    /** 每帧回调：只取 Y 平面，节流到 300ms 一次解码。 */
    private fun onImage(source: android.media.ImageReader, onDecoded: (String) -> Unit) {
        val image = try {
            source.acquireLatestImage() ?: return
        } catch (_: Exception) {
            return
        }
        try {
            val now = System.currentTimeMillis()
            if (delivered || now - lastDecodeAt < 300) return
            if (!decoding.compareAndSet(false, true)) return
            lastDecodeAt = now
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val data = ByteArray(buffer.remaining())
                buffer.get(data)
                val text = decodeLuminance(data, plane.rowStride, image.width, image.height)
                if (text != null) onDecoded(text)
            } finally {
                decoding.set(false)
            }
        } catch (_: Exception) {
            // 单帧解码失败很正常（对焦中、光线不足），下一帧继续。
        } finally {
            image.close()
        }
    }

    /** 页面销毁时必须调用：不释放相机会让下一次打开直接失败。 */
    fun release() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { camera?.close() } catch (_: Exception) {}
        camera = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    /** 用户点「重试」：释放已由调用方做过，这里只清标志好让 start() 能再进。 */
    fun resetForRetry() {
        started = false
        everOpened = false
        delivered = false
        lastDecodeAt = 0L
    }

    private fun pickBackCamera(manager: CameraManager): String? {
        var fallback: String? = null
        for (id in manager.cameraIdList) {
            val facing = manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING)
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id
            if (fallback == null) fallback = id
        }
        return fallback
    }

    private fun hasCameraPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}
