package com.dsh.mobile.v2.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets

/**
 * 未捕获异常落盘：真机闪退时拿不到堆栈（logcat 缓冲区留不住应用日志，用户也不总能
 * 连着电脑）。进程死前把堆栈追加进 `files/crash.log`，下次启动弹窗展示。
 *
 * 只做「记录」，不改变崩溃行为：落盘后照旧交回系统原处理器。
 */
object CrashLog {
    private const val MAX_BYTES = 64 * 1024
    private var file: File? = null

    fun init(context: android.content.Context) {
        file = File(context.filesDir, "crash.log")
    }

    /** 先落盘，再交回系统原处理器（照常弹「应用已停止」并杀进程）。 */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val f = file ?: return@setDefaultUncaughtExceptionHandler
                // 反复崩溃时别让文件无限长：超限就重开一份，只留最近的现场。
                if (f.exists() && f.length() > MAX_BYTES) f.delete()
                val trace = StringWriter()
                error.printStackTrace(PrintWriter(trace))
                val head = "===== ${java.util.Date()} · thread=${thread.name} =====\n"
                File(f.parentFile, f.name).let { target ->
                    target.appendText(head + trace + "\n")
                }
            } catch (ignored: Exception) {
                // 崩溃路径上再抛就什么都留不下
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** 读走全部内容（不清空）；没有或读失败返回空串。 */
    fun read(): String {
        val f = file ?: return ""
        if (!f.exists()) return ""
        return try {
            val input = f.inputStream()
            val out = ByteArrayOutputStream()
            input.use { ins ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = ins.read(buffer)
                    if (n <= 0) break
                    out.write(buffer, 0, n)
                }
            }
            String(out.toByteArray(), StandardCharsets.UTF_8).trim()
        } catch (ignored: Exception) {
            ""
        }
    }

    /** 展示过就清掉，避免每次启动都弹同一份。 */
    fun clear() {
        file?.delete()
    }
}
