package com.dsh.mobile.v2.ui

/**
 * 变更流（SSE `/native/v2/hosts/:id/changes`）的重连判据。
 *
 * <p>抽成纯 Kotlin 类只为一件事：把「什么时候该补一次差量」钉死在单测里。
 *
 * <p>为什么需要它：中继的变更总线**刻意不做重放**（`changes.ts` 的注释：
 * 「断线期间的变更由手机重连后走一次 /sync 按游标补齐」）。也就是说断线期间
 * 哪几条会话变了，手机永远不会知道——它只能靠「连上了就挨个问一遍」来补齐。
 * 补的起点是每条会话各自的 `localAckedSeq`（存在 Room 的
 * `session_metadata.local_acked_seq`），所以**绝大多数会话早已追平，中继返回空页**；
 * 实测 38 个会话全部追平的一轮是 0.38 ms。
 *
 * <p>但补这件事**必须有触发点**。早先没有任何代码在 SSE 恢复后叫醒补差，于是
 * 「停在列表页、SSE 断了几分钟又自己连上」这段时间里变过的会话不会更新，
 * 要等用户下拉刷新或重新进列表页。
 *
 * <p>而「连上」这件事本身不能无条件当成触发点：冷启动时 `selectHost` 已经走过
 * 一次 `loadSessions()`（它内部就会 `fillPreviews` → 批量补差），再补一次就是
 * 白白翻倍。所以判据是**「这次连上之前掉过」**，不是「连上了」：
 *
 * - 冷启动第一次就成功 → [onConnected] 返回 false，不重复补；
 * - 掉线（或首次连接失败）之后连上 → 返回 true，补一次；
 * - 连着的时候又来一次成功回调（不该发生，但真发生时）→ 返回 false。
 *
 * <p>注意 `note` 的两种取值不能混：失败/断开时 `RelayApi` 传的是**非空**文案
 * （「连接失败」「流已结束」「读取中断」…），成功时传的是**空串**。判据就是它。
 */
internal class ChangesReconnect {

    /** 上次「连上」之后是否掉过（含首次连接就失败的情形）。 */
    private var dropped = false

    /** 变更流报告了断开/失败。文案非空即为此类。 */
    fun onDropped() {
        dropped = true
    }

    /**
     * 变更流报告「已连上」。
     *
     * @return true 表示这次是**恢复**，调用方应当补一次差量（`loadSessions(silent = true)`）。
     */
    fun onConnected(): Boolean {
        val wasDropped = dropped
        dropped = false
        return wasDropped
    }

    /** 测试与诊断用：当前是否处于「掉过、还没补」的状态。 */
    internal fun pending(): Boolean = dropped
}
