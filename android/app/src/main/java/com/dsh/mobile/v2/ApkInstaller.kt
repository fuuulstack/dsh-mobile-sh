package com.dsh.mobile.v2

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import com.dsh.mobile.v2.data.Diag
import java.io.File

/**
 * 安装自己下载的更新包。
 *
 * 首选 [PackageInstaller] 会话：字节直接交给 installd，不经过「打开 URI」这一步。
 *
 * 为什么不再只用 `ACTION_VIEW + content://`：我们的读授权只跟着「被解析到的那个
 * Activity 所属包」走，而 ColorOS 把安装 Intent 转手给 `com.oplus.appdetail`，
 * 授权没跟过去、provider 又不可导出，安装器只做一次失败的权限校验就静默退出。
 * 真机现象就是「提示更新到 0.2.7 → 下载完成 → 装上去还是 0.2.2」。
 *
 * 会话方式在极少数 ROM 上可能被拒（没有 INSTALL_PACKAGES 时的自更新限制），
 * 这时回落到老的 `ACTION_VIEW` 路径——那条路在原生 Android 上一直是好的。
 */
internal object ApkInstaller {

    private const val ACTION_RESULT = "com.dsh.mobile.v2.action.INSTALL_RESULT"

    /** 正在等待结果的会话；同一时刻只允许一个（连点两次会撞上 installer 锁）。 */
    private var pending: BroadcastReceiver? = null

    /**
     * 把 [apk] 写进安装会话并提交。
     *
     * @param onResult 结果回调，主线程；null 表示已进入系统确认流程或安装成功，
     *                非 null 是失败原因，可直接展示给用户。
     */
    fun install(context: Context, apk: File, onResult: (String?) -> Unit) {
        val app = context.applicationContext
        pending?.let {
            runCatching { app.unregisterReceiver(it) }
            pending = null
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                Diag.log("update", "install status=$status msg=$message")
                when (status) {
                    // 系统要先弹确认框（「是否更新此应用」）：把它的 Intent 交出去，
                    // 最终结果会再回调一次，所以这里不注销监听。
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirm = if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
                        }
                        if (confirm != null) {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { app.startActivity(confirm) }
                                .onFailure { onResult("无法打开安装确认：${it.message}") }
                        } else {
                            onResult("系统未返回安装确认界面")
                        }
                    }
                    PackageInstaller.STATUS_SUCCESS -> {
                        stop(app, this)
                        onResult(null)
                    }
                    else -> {
                        stop(app, this)
                        onResult(failureText(status, message))
                    }
                }
            }
        }

        val filter = IntentFilter(ACTION_RESULT)
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }
        pending = receiver

        val pi = PendingIntent.getBroadcast(
            app, 0, Intent(ACTION_RESULT).setPackage(app.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        try {
            val installer = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            params.setSize(apk.length())
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                session.commit(pi.intentSender)
            }
        } catch (e: Exception) {
            Diag.log("update", "install session rejected: ${e.javaClass.simpleName} ${e.message}")
            stop(app, receiver)
            // 会话被拒（自更新限制 / ROM 改造）：退回老的 ACTION_VIEW 路径。
            legacyInstall(app, apk, onResult)
        }
    }

    /** 老的安装方式：把外部缓存里的包映射成 content:// 再交给系统安装器。 */
    private fun legacyInstall(app: Context, apk: File, onResult: (String?) -> Unit) {
        try {
            val uri = Uri.parse("content://${app.packageName}.apkprovider/updates/${apk.name}")
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            onResult(null)
        } catch (e: Exception) {
            Diag.log("update", "legacy install failed: ${e.message}")
            onResult("无法启动安装：${e.message}")
        }
    }

    private fun stop(app: Context, receiver: BroadcastReceiver) {
        runCatching { app.unregisterReceiver(receiver) }
        if (pending === receiver) pending = null
    }

    /** 结果码翻成人话；未知码把原始信息带上，下次排查不必再猜。 */
    private fun failureText(status: Int, message: String): String {
        val reason = when (status) {
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "被系统或安全软件拦截"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "安装被中止"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "与已安装版本冲突（签名或版本号不一致）"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "存储空间不足"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "与当前系统不兼容"
            PackageInstaller.STATUS_FAILURE_INVALID -> "安装包无效"
            else -> "安装失败（$status）"
        }
        val detail = message.substringAfter(':', "").trim()
        return if (detail.isEmpty()) "安装失败：$reason" else "安装失败：$reason · $detail"
    }
}
