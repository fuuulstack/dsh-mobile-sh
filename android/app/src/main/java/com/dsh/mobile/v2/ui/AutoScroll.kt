package com.dsh.mobile.v2.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.dsh.mobile.v2.data.Diag

/**
 * 「内容长大后继续贴着底」——聊天页与两级抽屉共用同一套判据。
 *
 * <p>为什么不能只跟着 `revision` 滚：打字机（[rememberTypewriterText]）让正文在
 * **两次 publish 之间**也在长，屏幕上字一个个冒出来，而列表滚动只跟着 `revision`
 * 走 —— 字长出来了、列表没跟上，最新那行就被推出视口，用户得自己往回拨。
 *
 * <p>判据是「**上次贴着底** 且 **现在能往下滚了**」：
 * - 本来就贴着底 → 新内容到达时 `canScrollForward` false → true → 滚回底部；
 * - 用户往上翻了 → 上一次观测时就不在底 → 一个动作都不做。
 *
 * 这正是「只有贴底才自动滚」的语义，不需要再维护一份「用户想不想跟随」的意图位。
 * 但**首次量测不算数**：抽屉刚打开、列表刚首帧时 `canScrollForward` 也是从 false
 * 变 true 的，那不是「有新内容」，而是「刚量出来」，照做会把一打开就停在开头的
 * 抽屉直接拽到底。
 */

/**
 * 一次「内容长大」的滚动决策（纯函数，便于单测钉住「翻历史时绝不拽人」）。
 *
 * @param canScrollForward 现在还能往下滚吗（＝内容比视口长）
 * @param atBottom 上一次观测时是不是贴着底
 * @param enabled 调用方是否额外允许跟随（聊天页＝`followTail && !userDragging`）
 */
fun stickOnGrowth(canScrollForward: Boolean, atBottom: Boolean, enabled: Boolean): Boolean =
    canScrollForward && atBottom && enabled

/**
 * 内容总高（可见区 + 可滚距离）。
 *
 * <p>「内容长高了没有」只能用这个数比。只看 [ScrollState.getMaxValue] 时，
 * 「内容不满一屏（恒 0）→ 刚撑满一屏（0 变 800）」这一步会被判成「没长」，
 * 而它恰恰是最该滚的那一次（2026-11-01 用户实测二级抽屉）。视口高矮在
 * 同一方向上是常量，所以合成之后仍然单调。
 */
fun contentHeightOf(maxValue: Int, viewportSize: Int): Int = maxValue + viewportSize

/** 抽屉要不要跟着滚：内容确实长高了、上次贴着底、现在能往下滚、且没人在拖。 */
fun stickOnContentGrowth(height: Int, lastHeight: Int?, canScrollForward: Boolean, atBottom: Boolean, dragging: Boolean): Boolean {
    if (lastHeight == null) return false // 首帧只记基线
    if (height <= lastHeight) return false
    return canScrollForward && atBottom && !dragging
}

/** LazyColumn 版：滚到 [tailIndex] 这一项，偏移由 [tailOffset] 给（越界即夹到末端）。 */
@Composable
fun StickToBottomOnGrowth(
    listState: LazyListState,
    enabled: () -> Boolean,
    tailIndex: () -> Int,
    tailOffset: () -> Int,
) {
    // 这三个 lambda 每帧都会重建（依赖 state），而 LaunchedEffect 只在 key 变化时
    // 重启：直接捕获它们等于永远读到第一帧的旧值。用 rememberUpdatedState 转一手。
    val enabledNow = rememberUpdatedState(enabled)
    val tailNow = rememberUpdatedState(tailIndex)
    val offsetNow = rememberUpdatedState(tailOffset)
    LaunchedEffect(listState) {
        var measured = false
        var atBottom = true
        snapshotFlow { listState.canScrollForward to listState.layoutInfo.totalItemsCount }
            .collect { (canForward, items) ->
                // 还没排出任何一项：值全是初值，不是「内容变长了」。
                if (items <= 0) return@collect
                if (!measured) {
                    measured = true
                    atBottom = !canForward
                    return@collect
                }
                if (stickOnGrowth(canForward, atBottom, enabledNow.value())) {
                    // 手指/惯性还在动就一个动作都不做。`followTail` 要经过一次重组
                    // 才生效，而 `canScrollForward` 的翻转是立刻可见的——打字机每
                    // 16ms 就动一次，少这道守卫，用户刚往上拖的那一帧就会被拽回底部
                    // （v1 时代的老 bug：看历史时一有输出就跳）。
                    if (!listState.isScrollInProgress) {
                        val tail = tailNow.value()
                        if (tail >= 0) listState.scrollToItem(tail, offsetNow.value())
                        atBottom = true
                    }
                } else {
                    // 没滚（用户翻上去了 / 正在拖动）就把「贴底」清掉：
                    // 只有等他真的滑回底部（canScrollForward 变回 false）才重新武装。
                    atBottom = !canForward
                }
            }
    }
}

/** `verticalScroll` 版（一级/二级抽屉）：贴到 [ScrollState.maxValue]。 */
@Composable
fun StickToBottomOnGrowth(scrollState: ScrollState) {
    LaunchedEffect(scrollState) {
        var measured = false
        var lastHeight = 0
        var atBottom = true
        // 判据必须是**内容总高**（`maxValue + viewportSize`），不能只看 `maxValue`。
        // 内容不满一屏时 `maxValue` 恒为 0，等正文撑满一屏才从 0 变成 800：
        // 拿 `maxValue` 比较，这一步「0 → 800」会被 `grew = max > lastMax` 判成
        // 「没长」（0 > 0 为假），于是**第一次长过一屏永远不滚**；早先还有一版用
        // `max <= 0` 当「还没量出来」直接 return，症状一样（2026-11-01 用户实测
        // 「二级抽屉从头开始即不满一屏，到铺满屏幕后没有触发自动滚动」）。
        // 内容总高从第一帧起就单调反映真实高度，也只在这时才分得清「还没排版」
        // （totalHeight 恒 0）与「内容真的没长」。
        snapshotFlow {
            Triple(
                scrollState.maxValue + scrollState.viewportSize,
                scrollState.canScrollForward,
                scrollState.viewportSize,
            )
        }
            .collect { (height, canForward, viewport) ->
                // 视口还没排版：值全是初值，不是「内容变长了」。
                if (viewport <= 0) return@collect
                // 首帧只记基线（`stickOnContentGrowth` 收 null 即返回 false）：
                // 打开抽屉时该停在开头，不能一上来就拽到底。
                val should = stickOnContentGrowth(
                    height = height,
                    lastHeight = if (measured) lastHeight else null,
                    canScrollForward = canForward,
                    atBottom = atBottom,
                    dragging = scrollState.isScrollInProgress,
                )
                // 这段 Compose 逻辑没有单测可覆盖，现场证据只能来自运行时打点。
                //
                // **只记「跨过一屏」那一次**，不记每一次滚动：思考流式增长时内容每
                // 58px 就长一次，逐次记录会把 diag.log（256KB 轮转）冲掉。而
                // 「不满一屏（`maxValue` 恒 0）→ 第一次撑满视口」正是用户
                // 2026-11-01 反馈的「二级抽屉铺满后没触发自动滚动」那一刻，
                // 记它就能一眼看出修好没有。
                val crossing = measured && lastHeight <= viewport && height > viewport
                if (crossing) {
                    Diag.log(
                        "scroll",
                        "sheet crossing ${if (should) "scroll" else "no-scroll"} h=$height " +
                            "last=$lastHeight vp=$viewport fwd=$canForward atBottom=$atBottom",
                    )
                }
                measured = true
                lastHeight = height
                if (should) {
                    // maxValue 在这一刻已经是新的内容高度，滚到它就是真正的底。
                    scrollState.scrollTo(scrollState.maxValue)
                    atBottom = true
                } else {
                    // 没滚（用户翻上去了 / 正在拖动 / 只是首帧）就把「贴底」清掉：
                    // 只有等他真的滑回底部（canScrollForward 变回 false）才重新武装。
                    // 拖动中也照样清，否则手指刚抬起来的下一次增长就会把人拽回底部。
                    atBottom = !canForward && !scrollState.isScrollInProgress
                }
            }
    }
}

/**
 * 「跳到底部」入口的显隐（`verticalScroll` 版，给一级抽屉用）。
 *
 * <p>只看「现在还能不能往下滚」：能滚就说明视线停在中间某处。**不拿
 * `maxValue - value` 反推「离底多远」** —— 流式期间内容先变、布局后到，按距离算
 * 会在正常跟随时算出「离底很远」，按钮闪一下（与 [showJumpToLatest] 同一个坑）。
 *
 * <p>内容没超过一屏时 [canScrollForward] 恒为 false，本来就没有「下面」可去。
 * `viewportSize` 那一项是防首帧：视口还没量出来时 `canScrollForward` 也是 false，
 * 但那时连内容都没有，不该显示。
 */
fun showScrollToBottom(canScrollForward: Boolean, viewportSize: Int): Boolean =
    canScrollForward && viewportSize > 0

/**
 * 持续报告「跳到底部」入口现在该不该显示。
 *
 * <p>抽屉里没有 `LazyListState` 那样的 `firstVisibleItemIndex` 可用，[ScrollState]
 * 也不暴露「在不在底」这个布尔，只能订阅 `canScrollForward`。用 `snapshotFlow`
 * 而不是每帧读：读值发生在组合阶段的话，每滚动一个像素都会重组整块抽屉内容。
 *
 * <p>`viewportSize` 一起进流：它本身也是一个可观察的 state（`mutableIntStateOf`），
 * 在组合里直接读只会拿到首帧的 0，而在流里读才能在排版完成后如实回报。
 */
@Composable
fun rememberScrollToBottomVisible(scrollState: ScrollState): State<Boolean> {
    val visible = remember { mutableStateOf(false) }
    LaunchedEffect(scrollState) {
        snapshotFlow { showScrollToBottom(scrollState.canScrollForward, scrollState.viewportSize) }
            .collect { visible.value = it }
    }
    return visible
}
