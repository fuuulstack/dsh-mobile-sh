package com.dsh.mobile.v2.net

/**
 * `/sync` 的客户端限流闸门。
 *
 * <p>为什么需要它：早先中继对 `/sync` 的限流是**每个登录会话 30 次 / 60 秒**
 * （账号级，不是「每个聊天会话」）。手机这边一冷启动就有几十个会话要补页，早先的
 * 写法是「批量四轮没补完的剩下十几个逐个单发」，一拥而上就把 30 次配额吃光，
 * 之后每个请求都 429；退避重试又会反过来继续消耗，实测一轮能打出三百多次 429、
 * 拖好几分钟才收敛。
 *
 * <p>那道限流本身已经按「自托管、一个人用」放宽到 600 次 / 60 秒（见 relay
 * `server.ts` 的 `RATE.sync`），所以闸门不再是「躲服务端」的必需品。留着它是因为
 * 还有两个真实用途：①同一台设备上冷启动的补页风暴仍然会占满连接与线程池，让首屏
 * 与发消息排在后面；②服务端额度将来若被调小，客户端不至于又变回硬撞。
 *
 * <p>下限给到 120 次 / 60 秒：仍比服务端留了五倍余量（客户端算「我发了几次」，
 * 服务端算「它收到几次」，中间还有重连与其它设备），但正常冷启动永远撞不上。
 *
 * <p>纯逻辑、可注入时钟，方便直接断言。
 */
internal class SyncRateGate(
    private val windowMs: Long = 60_000L,
    private val max: Int = 120,
) {
    /** 本窗口内已发出请求的时间戳，按发出顺序排列（超出窗口的会被裁掉）。 */
    private val issued = ArrayDeque<Long>()

    private fun prune(now: Long) {
        while (issued.isNotEmpty() && now - issued.first() >= windowMs) issued.removeFirst()
    }

    /** 现在能不能发。能发就记账并返回 true。 */
    fun tryAcquire(now: Long): Boolean {
        prune(now)
        if (issued.size >= max) return false
        issued.addLast(now)
        return true
    }

    /**
     * 想发但被挡下时，还要等多久才有额度。
     *
     * @return 毫秒；没有历史记录时返回 0。
     */
    fun waitMs(now: Long): Long {
        prune(now)
        if (issued.size < max) return 0L
        val oldest = issued.first()
        val wait = windowMs - (now - oldest)
        return if (wait > 0) wait else 0L
    }

    /** 当前窗口里已经用掉多少（诊断用）。 */
    fun used(now: Long): Int {
        prune(now)
        return issued.size
    }

    fun reset() = issued.clear()
}
