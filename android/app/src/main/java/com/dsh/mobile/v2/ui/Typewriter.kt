package com.dsh.mobile.v2.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * 模拟逐字吐字的打字机。
 *
 * <p>为什么需要它：宿主把正文按增量推过来，端侧每次 `publish` 都会把 `streamingText`
 * 整段替换掉，于是屏幕上表现为「一段一段地蹦」——尤其上游是快模型时，一次 ack 就
 * 灌进来几十个字，看起来像卡顿而不是在写。这里在**渲染层**把已经收到的文本按 tick
 * 慢慢放出来，网络层完全不动。
 *
 * <p>速度必须跟着**积压量**走，不能是固定速率：
 * - 积压少（模型一字一字地吐）→ 每个 tick 吐一个字，视觉上就是「正在写」；
 * - 积压多（一次来 500 字）→ 固定速率要追八秒，用户盯着半截话更难受，所以按
 *   [TYPEWRITER_CATCHUP_TICKS] 个 tick 追平的比例加速，最多两百毫秒就补齐。
 *
 * <p>落定（`streaming == false`）时**立刻**整段显示，不做收尾动画：这一轮已经结束，
 * 再让用户等着看字一个个蹦出来就是纯粹的拖延。
 */

/** 每个 tick 的间隔：约一帧（60Hz）。 */
private const val TYPEWRITER_TICK_MS = 16L

/** 没有新内容时的轮询间隔，避免空转。 */
private const val TYPEWRITER_IDLE_MS = 48L

/** 积压多少 tick 追平。12 × 16ms ≈ 200ms。 */
const val TYPEWRITER_CATCHUP_TICKS = 12

/**
 * 一个 tick 从积压里吐出多少字（0 表示没积压）。
 *
 * 速度必须与积压成**正比**（`pending / CATCHUP_TICKS`），不能是次线性的。
 *
 * <p>曾经试过「指数平滑」（`pending^0.7 / 8`）想让加速更自然，实测是错的：
 * 次线性反馈下积压会随输入速率**线性累积**且永不收敛——宿主 1000 字/秒时屏幕上
 * 稳定落后约 1 秒，3750 字/秒时落后 1.8 秒，而比例公式在任何速率下都是恒定
 * 约 170ms（12 个 tick）。打字机是**追赶器**，不是动画曲线：它唯一的指标是
 * 「别让用户读到半截话等下去」，所以这里用比例、并且积压越多吐得越快。
 *
 * @param pending 还没显示出来的字符数（UTF-16 单元）。
 */
fun typewriterChunk(pending: Int): Int {
    if (pending <= 0) return 0
    return maxOf(1, (pending + TYPEWRITER_CATCHUP_TICKS - 1) / TYPEWRITER_CATCHUP_TICKS)
}

/**
 * 从 [shown] 往后推进 [chunk] 个字，返回新的「已显示到第几个字符」。
 *
 * <p>按 **code point** 推进，不按 UTF-16 单元：emoji 与多数扩展区汉字是代理对，
 * 按单元切会切出一个孤立的高位代理，渲染出来是一个方块（或直接崩）。这里用
 * [String.offsetByCodePoints] 保证每次都停在字符边界上。
 *
 * @param shown 当前已显示到的下标（必须落在字符边界上）。
 */
fun typewriterNext(text: String, shown: Int, chunk: Int): Int {
    val from = shown.coerceIn(0, text.length)
    if (chunk <= 0 || from >= text.length) return from
    val remaining = text.codePointCount(from, text.length)
    return text.offsetByCodePoints(from, minOf(chunk, remaining))
}

/**
 * 打字机版的文本：`streaming` 时只返回「已经吐出来」的前缀，落定后原样返回全文。
 *
 * <p>进度存在 `rememberSaveable` 里，是因为调用点在 `LazyColumn` 的 item 内：正文
 * 一旦滚出屏幕，item 的 composition 就被销毁，`remember` 的进度会跟着丢——滚回来
 * 时从头再打一遍，比不打字机更奇怪。`LazyColumn` 会为带 key 的 item 保留 saveable
 * 状态，所以滚出再滚回是接着上次的位置继续。
 *
 * <p>进度只在「同一条消息继续变长」时才保留：换了另一段文本（新回合、或复用了同一个
 * item 位置）必须从 0 重来，否则会从中途开始——前几百字直接闪现，后半截再慢慢吐，
 * 比不打字机还怪。判据是「新文本是否以上一次那段开头」，而不是比长度：长度只增不减
 * 的情形太多（同一轮里换了段落、上游重发），比长度会漏掉一大类。
 */
@Composable
fun rememberTypewriterText(source: String, streaming: Boolean): String {
    if (!streaming) return source
    // 协程里要读到**最新**的 source：LaunchedEffect 捕获的是启动那一刻的值。
    val latest by rememberUpdatedState(source)
    var shown by rememberSaveable { mutableStateOf(0) }
    // 上一次见到的全文。它是「这还是不是同一段」的唯一判据（见上面的注释）。
    var previous by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            val target = latest
            // 换了一段文本：进度归零重新吐。注意要连 previous 一起更新，
            // 否则下一次比较还是拿旧的比。
            if (target != previous) {
                if (!target.startsWith(previous)) shown = 0
                previous = target
            }
            if (shown > target.length) shown = 0
            val pending = target.length - shown
            if (pending <= 0) {
                delay(TYPEWRITER_IDLE_MS)
                continue
            }
            shown = typewriterNext(target, shown, typewriterChunk(pending))
            delay(TYPEWRITER_TICK_MS)
        }
    }
    val end = shown.coerceIn(0, source.length)
    return remember(source, end) { source.substring(0, end) }
}
