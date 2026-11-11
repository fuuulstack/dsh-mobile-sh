package com.dsh.mobile.v2.net

import com.dsh.mobile.v2.data.LocalAckedSeq
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * [ChatSyncActor.ChatTransport] 的真实实现：把回调式的 [RelayApiV2] 包成挂起函数。
 *
 * 这一层的存在只为两件事：
 *
 * 1. **把回调变成承诺**。`ChatSyncActor` 的同步循环是 suspend 的（要能被取消、
 *    要能按顺序推进水位），而 OkHttp 是回调式的。桥接点只有这里一处。
 * 2. **不重试**。重试策略全部由 `ChatSyncActor` + `SyncCoordinator` 决定（有界退避
 *    带抖动、连续失败冷却）。在传输层偷偷重试会让「一次失败」变成「三次请求」，
 *    而退避算的是前者——真机上会打出一波没有抖动对齐的请求。
 */
class RelayChatTransport(
    private val api: RelayApiV2,
) : ChatSyncActor.ChatTransport {

    companion object {
        /**
         * 单次网络往返的硬上限。
         *
         * <p>这一层把回调包成挂起函数，而**回调不保证会来**：OkHttp 的 `onFailure`
         * 在半死连接上可能几十秒不触发，中继掐断时也可能只剩一个 FIN。没有超时，
         * 调用方就永久挂在这句 `suspendCancellableCoroutine` 上——实测事故里
         * `tick()` 正是这样卡住的：看门狗自己成了唯一不动的东西，界面停在最后一帧、
         * 一个字节都不再写，而两端日志都显示健康。
         *
         * <p>30 秒的依据：`/v2/sync` 冷启动实测 3.79 秒（240 条 / 501 KB），留十倍
         * 余量足够，又不会让用户等过一个「看起来卡死」的观感阈值。
         */
        private const val CALL_TIMEOUT_MS = 30_000L
    }

    /**
     * 取回一条被离载的正文。返回 `null` 表示这次没拿到（网络或 404），调用方保留存根。
     *
     * <p>挂在传输层而不是 actor 里：actor 只认「记录」这一种抽象，不该知道 HTTP 路径。
     */
    override suspend fun blob(hostId: String, blobId: String): String? =
        withTimeoutOrNull(CALL_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                api.fetchBlob(hostId, blobId) { text, _ ->
                    if (continuation.isActive) continuation.resume(text)
                }
            }
        }

    override suspend fun sync(
        hostId: String,
        sessionId: String,
        afterSeq: LocalAckedSeq,
        priority: Int,
        pageByteBudget: Int,
    ): ChatSyncActor.SyncOutcome = withTimeoutOrNull(CALL_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
        api.sync(
            hostId = hostId,
            sessionId = sessionId,
            afterSeq = afterSeq,
            // 前台单会话：`FOREGROUND_SINGLE` 让中继把这一个会话排到所有后台补差
            // 之前，并让它独占整份字节预算（bug B9）。
            mode = "FOREGROUND_SINGLE",
            priority = priority,
            pageByteBudget = pageByteBudget,
        ) { page, error, retryAfterMs ->
            if (continuation.isActive) {
                continuation.resume(
                    if (page != null) {
                        ChatSyncActor.SyncOutcome.Page(page)
                    } else {
                        ChatSyncActor.SyncOutcome.Failed(error ?: "同步失败", retryAfterMs)
                    }
                )
            }
        }
        }
    } ?: ChatSyncActor.SyncOutcome.Failed("timeout", 0)

    override fun openControl(
        hostId: String,
        sessionId: String,
        sinceSeq: LocalAckedSeq,
        feed: RelayApiV2.ControlFeed,
    ) {
        api.openControl(hostId, sessionId, sinceSeq, feed)
    }

    override fun focus(sessionId: String, sinceSeq: LocalAckedSeq): Boolean =
        api.focus(sessionId, sinceSeq)

    override fun command(cmdId: String, op: String, payload: JsonObject): Boolean =
        api.command(cmdId, op, payload)

    override fun closeControl() = api.closeControl()

    /**
     * 控制流是否仍然连着。
     *
     * <p>用 `RelayApiV2` 的 socket 状态，而不是「我们发过 FOCUS」这种本地推断：
     * 坑 54 的现场恰恰是「本地以为连着、数据也在来，但没有脉搏」。这里的语义只
     * 回答一件事——**关掉它会不会浪费一条能用的连接**。
     */
    override fun controlAlive(): Boolean = api.alive()
}
