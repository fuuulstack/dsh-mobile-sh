package com.dsh.mobile.v2.net

/**
 * 同步侧的纯逻辑工具——**不碰 Android、不发请求**。
 *
 * <p>抽出来的原因很实在：水位的三条线（列表水位、本地正文最大 seq、归档覆盖位点）
 * 一旦混用，表现出来只是「手机上的消息偶尔少一段」，在真机上看不出来也复现不了。
 * 变成可以对着断言的问题之后，边界才能被 JVM 单测钉住。
 *
 * <p>v1 那套「归档游标 + `nextCursor` + `recordTooLarge`」的补页决策（`decide` /
 * `Outcome` / `pushedCursor` / `accepted`）已随 `/native/v1/…` 路由一起删除：
 * v2 的游标就是 `local_acked_seq` 一个值，`/v2/sync` 一页给完，没有第二条水位。
 * 留下的是两件与协议版本无关的事：**同一会话的请求串行化**（[Tracker]）与
 * **旧游标的迁移口径**（[migrateCursors]）。
 */
internal object SyncPlanner {

    /**
     * 同一会话的补页调度状态：**同一时刻最多一个 `/sync` 在途**。
     *
     * <p>一次流式回复会连发几十个 `api-session` 下的各种事件，每个都触发一次补拉的话，
     * 手机上就是几十条并发 `/sync`（公网上每条都要几百毫秒到几秒），而且它们
     * 拿的是同一个游标、下载同一批记录——评审现场「同一批 3/28/21 条记录被反复
     * 下载」正是这么来的。串行化之后，期间的变更只标 dirty，等当前这轮结束再对账。
     */
    internal class Tracker {
        private val states = HashMap<String, BooleanArray>()

        /** @return true 表示调用方应当负责发起这一轮请求。 */
        fun begin(sessionId: String): Boolean {
            if (sessionId.isEmpty()) return false
            val s = states[sessionId] ?: BooleanArray(2).also { states[sessionId] = it }
            if (s[0]) { s[1] = true; return false }
            s[0] = true
            return true
        }

        /** 一轮结束：有 dirty 就要再来一轮。 */
        fun finish(sessionId: String): Boolean {
            val s = states[sessionId] ?: return false
            s[0] = false
            val rerun = s[1]
            s[1] = false
            if (!rerun) states.remove(sessionId)
            return rerun
        }

        /** 主机切走/页面关闭时丢弃所有状态，旧回调因此不会再写新主机的账。 */
        fun reset() = states.clear()

        fun isRunning(sessionId: String): Boolean = states[sessionId]?.get(0) == true
    }

    /**
     * 旧版游标的一次性迁移：把**已污染**的 `syncCursors` 折算成可信的归档游标。
     *
     * <p>旧表里两种互相矛盾的值混在一起：有时是本地正文最大 seq，有时被元数据
     * 水位 `latestSeq` 顶高（`AppViewModel` 里「无 merged 记录也把游标推到
     * row.latestSeq」那一段）。事后无法分辨，所以**一律不信任原值**。
     *
     * <p>但也不能简单归零：那会让每个会话从 14 天归档头重下一遍（现场有 908 条
     * 的会话）。取可证下界 `min(旧游标, 本地正文最大 seq)` 就够——
     * - 游标被顶高 → 落回本地真正拥有的位置，缺的那段下次 `/sync` 补；
     * - 游标本来就诚实 → 原样保留，零额外流量；
     * - 本地没有消息（现场 32 个里有 13 个）→ 0，从归档头拉，这正是对的。
     *
     * @param legacy 旧表内容（`sessionId -> seq`）。
     * @param localMax 取本地正文最大 seq（无消息时返回 0）。
     * @return 应当写入新表的游标（只含大于 0 的项）。
     */
    internal fun migrateCursors(
        legacy: Map<String, Long>,
        localMax: (String) -> Long,
    ): Map<String, Long> {
        val seeded = HashMap<String, Long>()
        for ((sessionId, old) in legacy) {
            if (sessionId.isEmpty() || old <= 0) continue
            val safe = minOf(old, localMax(sessionId).coerceAtLeast(0))
            if (safe > 0) seeded[sessionId] = safe
        }
        return seeded
    }

    /**
     * 零进展后的退避：连续 N 次原地打转就把节奏放到几十秒，别把电量和流量
     * 全烧在一个确定要不到的东西上。
     *
     * @param attempts 连续零进展次数（从 1 开始）。
     */
    internal fun backoffMs(attempts: Int): Long {
        if (attempts <= 0) return 0L
        val capped = attempts.coerceAtMost(4)
        return 1_000L shl (capped - 1) // 1s, 2s, 4s, 8s
    }
}
