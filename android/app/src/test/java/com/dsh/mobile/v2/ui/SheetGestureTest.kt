package com.dsh.mobile.v2.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 护栏：**每个 `ModalBottomSheet` 都必须关掉拖拽手势**（`sheetGesturesEnabled = false`）。
 *
 * <p>这里连着栽过两次，两次都不是「参数忘填」而是「以为能用手势拦截绕过去」：
 *
 * 1. 第一版用 `nestedScroll` 拦 —— 真机上照样把抽屉拖下来。M3 的手势挂在抽屉 Surface 上，
 *    是**指针级**的，滚动容器滚到边界后压根不消费那一笔，`onPostScroll` 返回 0 毫无作用。
 * 2. 第二版在正文里 `PointerEventPass.Main` 上把「没被里面消费掉的位移」标记为已消费
 *    —— 抽屉**照样**跟着手指走（`draggable` 不看 `isConsumed`），反倒把正文自己的滚动手势
 *    掐死：滑到顶/底再往回滑，同一次触摸里后续位移全部失效，表现就是「卡住、过几秒才
 *    恢复」（2026-11-06 用户原话）。
 *
 * <p>能真正关掉它的是 material3 1.4 的 `sheetGesturesEnabled`（1.3.2 的 aar 里 grep 这个
 * 符号零命中，1.4.0 命中 `ModalBottomSheetKt`）。代价是抽屉只能靠「点遮罩 / 系统返回」关闭，
 * 这是取舍，不是遗漏 —— 别再加回指针层的消费逻辑。
 *
 * <p>为什么读源码：这里要钉的是「参数还在不在」，跑 UI 测试得真机 + 手势注入，成本远高于
 * 一条文本断言。（同样的取舍见 [PreviewSourceTest]。）
 */
class SheetGestureTest {

    private val screen = File("src/main/java/com/dsh/mobile/v2/ui/ChatScreen.kt")

    private fun source(): String {
        assertTrue("找不到源码：${screen.absolutePath}", screen.isFile)
        return screen.readText()
    }

    @Test
    fun everySheetDisablesDragGestures() {
        val text = source()
        // 只数真正的调用点：`rememberModalBottomSheetState(` 里没有 `ModalBottomSheet(` 这个子串。
        val calls = Regex("""ModalBottomSheet\(""").findAll(text).count()
        val flags = Regex("""sheetGesturesEnabled\s*=\s*false""").findAll(text).count()
        assertTrue("ChatScreen.kt 里连一个抽屉都没找到 —— 改名后请同步更新这条护栏", calls > 0)
        assertEquals("有 $calls 个 ModalBottomSheet，却只有 $flags 个关掉了拖拽手势", calls, flags)
    }

    @Test
    fun pointerLevelConsumptionHackStaysDeleted() {
        assertFalse(
            "又加回了指针层消费（sheetScrollIsContent）——它挡不住抽屉拖拽，只会让正文滚动卡死",
            source().contains("sheetScrollIsContent"),
        )
    }
}
