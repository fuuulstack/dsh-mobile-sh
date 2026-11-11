package com.dsh.mobile.v2.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 护栏：**列表页首行预览只能来自 Room，不能读 `files/messages` 下的旧消息文件。**
 *
 * <p>2026-11-02 线上那个「列表页预览一直停在旧文案」的真身。`previewOf` 当时是三级
 * 回退，第二级读 v1 时代的整份消息文件，并且**命中就 return**：
 *
 * 1. 那份文件唯一的写入点是 `ChatViewModel.persist()`，只在「进过聊天页」时更新。
 *    会话在后台跑完整轮，只要没再进过聊天页，预览就一直是上次退出时的那句话；
 * 2. 它还把那句旧文本**回填进 `previewCache`**，于是 `invalidatePreview()` 清掉
 *    内存缓存后，下一帧又从文件读回同一份旧文本重新缓存 —— 自锁。「数据变了就重算」
 *    这条通路被彻底堵死。
 *
 * <p>实测配对（同一份磁盘：旧文件停在第 1 轮、Room 已有第 3 轮，只换这一处代码）：
 * 带旧文件回退的版本冷启动 28 秒仍显示「第 1 轮」；删掉之后 T+4s 就显示「第 3 轮」。
 *
 * <p>为什么读源码而不是跑单测：`previewOf` 是 private、依赖 Android `Context`
 * 与 SharedPreferences，JVM 单测起不来。而这处错误的形态是「**某一级读盘被加回来了**」，
 * 用一条文本断言就能钉住，比为了它去造一整套 fake 环境便宜得多。
 * （同样的取舍见 `data/SchemaVersionTest`。）
 */
class PreviewSourceTest {

    private val viewModel = File("src/main/java/com/dsh/mobile/v2/ui/AppViewModel.kt")

    /** 截出 `private fun previewOf(...) { ... }` 的函数体（到第一个行首的 `}` 为止）。 */
    private fun previewOfBody(): String {
        assertTrue("找不到源码：${viewModel.absolutePath}", viewModel.isFile)
        val text = viewModel.readText()
        val start = text.indexOf("private fun previewOf(")
        assertTrue("AppViewModel.kt 里找不到 previewOf —— 改名后请同步更新这条护栏", start >= 0)
        val open = text.indexOf('{', start)
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, i)
                }
            }
            i++
        }
        error("previewOf 的函数体没有闭合")
    }

    @Test
    fun `previewOf 不得读取旧消息文件`() {
        val body = previewOfBody()
        // 注释里会提到这个名字（记录事故），所以先剥掉注释再断言。
        val code = body
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//[^\\n]*"), "")
        assertFalse(
            "previewOf 又读回 files/messages 了。那份文件只在「进过聊天页」时更新，" +
                "读它会让列表预览永远停在旧文案，并且把旧值回填进 previewCache 形成自锁。" +
                "预览的正文来源只能是 Room 预热（scheduleWarm）。",
            code.contains("loadMessages"),
        )
    }

    @Test
    fun `previewOf 必须把未命中交给预热队列`() {
        val code = previewOfBody()
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//[^\\n]*"), "")
        // 删掉旧文件那一级之后，唯一的填充途径就是预热；这一句若被删掉，
        // 预览会永久为空（`withReadAt` 虽然保留 prefs 旧值，但永远不会再更新）。
        assertTrue(
            "previewOf 未命中时必须 enqueue + scheduleWarm，否则 Room 里的最新正文永远到不了列表",
            code.contains("warmQueue.enqueue") && code.contains("scheduleWarm"),
        )
    }
}
