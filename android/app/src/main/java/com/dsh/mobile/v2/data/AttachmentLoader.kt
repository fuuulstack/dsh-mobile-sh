package com.dsh.mobile.v2.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

/**
 * 相册 / 拍照 / 文件三个入口共用的取字节管道。
 *
 * 三个入口给的都只是一个 Uri，后面的处理完全一样：读流 → 图片就压缩、非图片原样 →
 * 包成 [Model.Attachment]。压缩规则跟随宿主的 `imageLimits`（单图默认 20MB、单边 8192、
 * 总量 200MB），但这几个数在手机端不该被顶到——一张 20MB 的原图 base64 后 27MB，
 * 走一条 rpc 太重，所以按 v1 的老规矩压到长边 2048、PNG 起手、超 5MB 转 JPEG。
 *
 * 全部解码在单线程池里做：一次只处理一张，连着选十张也不会同时开十个 Bitmap。
 */
object AttachmentLoader {

    /** 长边上限：超过这个尺寸对模型理解没有增益，只是白占带宽。 */
    private const val MAX_EDGE = 2048

    /** 单图字节上限：超过就换更省的有损编码。 */
    private const val MAX_BYTES = 5 * 1024 * 1024

    /** 宿主 imageLimits.mediaTypes 的白名单，四项之外一律当普通文件。 */
    private val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dsh-v2-attachment").apply { isDaemon = true }
    }

    /** 解码后的缩略图：给待发区与气泡共用，避免同一张图解码两次。 */
    class Preview(val name: String, val bitmap: Bitmap?)

    /** 读一个 Uri 得到的附件，`attachment` 为空表示这类内容不能当图片发。 */
    class Result(
        val attachment: Model.Attachment?,
        val preview: Preview?,
        val error: String?,
        /** 非图片文件：宿主要求先换成 receiptId，走 file 类型而不是 image。 */
        val isFile: Boolean = false,
    )

    /**
     * 读取并压缩一个 Uri。
     *
     * @param asImage 只当图片处理（相册与拍照两个入口）。为 false 时按 MIME 判断，
     *   非白名单类型走文件上传那条路。
     */
    fun load(context: Context, uri: Uri, asImage: Boolean, done: (Result) -> Unit) {
        worker.execute {
            val result = try {
                read(context, uri, asImage)
            } catch (e: Exception) {
                Diag.log("attach", "load ${e.javaClass.simpleName}: ${e.message}")
                Result(null, null, "读取附件失败", isFile = false)
            }
            done(result)
        }
    }

    /** 拍照产物落在 cacheDir：相机 App 写文件，我们读回来。 */
    fun newCaptureTarget(context: Context): File {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        return File(dir, "capture-${System.currentTimeMillis()}.jpg")
    }

    private fun read(context: Context, uri: Uri, asImage: Boolean): Result {
        val resolver = context.contentResolver
        val declared = resolver.getType(uri).orEmpty().lowercase()
        val name = displayName(context, uri)
        val isImage = if (asImage) true else declared in IMAGE_TYPES

        if (!isImage) {
            // 非图片：原字节交给上层去换 receiptId。超过 12MB 直接拒——中继单请求
            // 上限 300MiB，但手机端读进内存再传一遍没有意义。
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return Result(null, null, "读不到这个文件", isFile = true)
            if (bytes.size > 12 * 1024 * 1024) {
                return Result(null, null, "文件超过 12MB，暂不支持", isFile = true)
            }
            return Result(
                attachment = Model.Attachment().apply {
                    this.name = name
                    this.mediaType = declared.ifEmpty { "application/octet-stream" }
                    this.data = bytes
                },
                preview = Preview(name, null),
                error = null,
                isFile = true,
            )
        }

        val raw = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return Result(null, null, "读不到这张图片")
        val oriented = decodeOriented(raw)
            ?: return Result(null, null, "这个格式的图片解不开")

        val scaled = scaleDown(oriented)
        val (bytes, mediaType) = encode(scaled, declared)
        // 压缩后仍超限：不再降质，明确告诉用户，而不是发一张宿主会拒的图。
        if (bytes.size > MAX_BYTES * 2) return Result(null, null, "图片太大，换一张试试")

        return Result(
            attachment = Model.Attachment().apply {
                this.name = name.ifEmpty { "image-${System.currentTimeMillis()}.png" }
                this.mediaType = mediaType
                this.data = bytes
            },
            preview = Preview(name, scaled),
            error = null,
        )
    }

    /** 两段解码 + EXIF 方向。竖拍照片不校正就会躺倒。 */
    private fun decodeOriented(raw: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_EDGE && bounds.outHeight / (sample * 2) >= MAX_EDGE) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            raw, 0, raw.size,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 },
        ) ?: return null

        val rotation = try {
            when (ExifInterface(raw.inputStream()).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) {
            0f
        }
        if (rotation == 0f) return decoded
        val matrix = Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_EDGE) return bitmap
        val ratio = MAX_EDGE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    /** PNG 起手；压不进 5MB 就换 JPEG（照片类的有损编码通常小一个量级）。 */
    private fun encode(bitmap: Bitmap, declared: String): Pair<ByteArray, String> {
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }.toByteArray()
        if (png.size <= MAX_BYTES) {
            // 声明是 webp/gif 的也统一转 png：宿主只认四种，png 一定在名单里，
            // 而 gif 动图在 v2 里既不能播也不会因此丢内容。
            return png to "image/png"
        }
        val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
        return jpeg to "image/jpeg"
    }

    /** 文件名：SAF 的 DISPLAY_NAME，取不到就按时间戳造一个。 */
    fun displayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) {
                            val value = cursor.getString(index)
                            if (!value.isNullOrBlank()) return value
                        }
                    }
                }
        } catch (e: Exception) {
            Diag.log("attach", "displayName ${e.javaClass.simpleName}")
        }
        return ""
    }
}
