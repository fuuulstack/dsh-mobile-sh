package com.dsh.mobile.v2

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * 把外部缓存里的安装包暴露成 `content://` URI。
 *
 * Android 7 起，向其它应用（这里是系统安装器）传 `file://` 会直接抛
 * [IllegalArgumentException]，必须经 ContentProvider 转一手。这里没有引
 * `androidx.core.content.FileProvider`：它的核心逻辑本来就只有「按路径打开
 * 文件描述符」几十行，而 v2 现有的 file_paths.xml 只开了 captures 目录，
 * 为安装包再扩一条 external-cache-path 语义两可——照抄 v1 的自研 provider
 * 更直观，也与 v1 的行为逐字节一致。
 *
 * 只放开 `getExternalCacheDir()/updates/` 一个目录，且文件名不允许带
 * 路径分隔符——否则一个构造出来的 URI 就能读到缓存里的任何文件。
 */
internal class ApkProvider : ContentProvider() {

    /** 拼在 applicationId 后面的 authority 后缀；Manifest 与调用方必须用同一个。 */
    companion object {
        const val AUTHORITY_SUFFIX = ".apkprovider"
        private const val CODE_APK = 1
    }

    private lateinit var matcher: UriMatcher

    override fun onCreate(): Boolean {
        // authority 要到 onCreate 之后才知道（applicationId 由构建系统注入），
        // 所以匹配器在这里现搭，而不是静态常量。
        val ctx = context ?: return false
        matcher = UriMatcher(UriMatcher.NO_MATCH)
        matcher.addURI(ctx.packageName + AUTHORITY_SUFFIX, "updates/*", CODE_APK)
        return true
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException("provider 未就绪")
        if (matcher.match(uri) != CODE_APK) throw FileNotFoundException("不支持的路径：$uri")
        val name = uri.lastPathSegment ?: ""
        if (name.isEmpty() || name.contains("/") || name.contains("..")) {
            throw FileNotFoundException("非法文件名：$uri")
        }
        val file = File(File(ctx.getExternalCacheDir(), "updates"), name)
        if (!file.isFile()) throw FileNotFoundException("文件不存在：$name")
        val bits = if (mode == "r") ParcelFileDescriptor.MODE_READ_ONLY
        else try {
            ParcelFileDescriptor.parseMode(mode)
        } catch (e: IllegalArgumentException) {
            // 只读分发，任何写请求都是调用方用错了。
            throw FileNotFoundException("不支持的打开模式：$mode")
        }
        return ParcelFileDescriptor.open(file, bits)
    }

    /**
     * 安装器会问文件名和大小（决定弹窗里显示什么）。
     *
     * 返回 null 也能装上，但系统安装界面的标题会退成一个 content:// 串。
     */
    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?,
    ): Cursor? {
        val ctx = context ?: return null
        if (matcher.match(uri) != CODE_APK) return null
        val name = uri.lastPathSegment ?: return null
        if (name.contains("/") || name.contains("..")) return null
        val file = File(File(ctx.getExternalCacheDir(), "updates"), name)
        if (!file.isFile()) return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        val row = cursor.newRow()
        for (column in columns) {
            when (column) {
                OpenableColumns.DISPLAY_NAME -> row.add(name)
                OpenableColumns.SIZE -> row.add(file.length())
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun getType(uri: Uri): String? =
        // 系统安装器按 MIME 决定用哪个界面；给错了会当成普通文件转手给别人。
        if (matcher.match(uri) == CODE_APK) "application/vnd.android.package-archive" else null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("只读")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int =
        throw UnsupportedOperationException("只读")

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?,
    ): Int = throw UnsupportedOperationException("只读")
}
