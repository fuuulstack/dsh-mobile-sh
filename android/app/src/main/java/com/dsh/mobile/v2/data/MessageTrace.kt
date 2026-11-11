package com.dsh.mobile.v2.data

/**
 * 六跳消息打点：一句话从「手指按下」到「出现在屏幕上」经过的可观测点。
 *
 * <p>为什么需要它：用户说「发出去没反应」时，界面上只有三种可能——请求没到中继、
 * 中继到了插件没到、插件到了宿主但宿主没回。三种情况在 UI 上长得**一模一样**，
 * 而修复方式完全不同（重连 / 换插件包 / 看宿主日志）。没有打点时唯一的取证办法
 * 是同时抓三端日志再按时间拼，一次就是半小时。
 *
 * <p>实际记录的跳（见 [HOPS]）：
 * - `T1_SEND`      手机点了发送
 * - `T3_PLUGIN`    插件开始处理写操作 —— 由 `CMD_ACK.pluginMs` 反推（T4 减去插件耗时）
 * - `T4_HOST`      宿主答复了 —— ack 抵达手机的时刻
 * - `T5_FRAME`     第一帧回传（这一轮的第一条 `USER_INPUT` 记录落到本地）
 * - `T6_ONSCREEN`  投影完成、进入渲染
 *
 * <p>**为什么没有 `T2_RELAY`**：v2 里中继是透明字节管道，按设计**从不解析**业务帧
 * （`packages/relay/src/server.ts` 的 `/v2/sync` 特例除外）。要给它盖一个时间戳就得
 * 在那里 `JSON.parse` 每一帧，那正是这次重构要根治的毛病（2 核 / 768MB 的中继
 * 被业务解析吃掉 CPU 与堆）。`T3_PLUGIN` 存在就已经证明请求穿过了中继，所以这一跳
 * 不是「没测」，是「按设计不必测」。
 *
 * <p>反过来：**缺哪一跳就说明断在那一跳之前**。只有 `T1_SEND` 说明写操作没到插件
 * （重连问题）；有 `T4_HOST` 没有 `T5_FRAME` 说明宿主没回（去看宿主日志）。
 *
 * <p>存最近 [CAPACITY] 条：一次联调通常只关心刚才那几句话，更早的已被 [Diag]
 * 的完整日志覆盖。刻意不落盘——它是诊断快照，不是业务数据。
 */
object MessageTrace {
    /** 最多留这么多条，超出的丢最旧。 */
    const val CAPACITY = 200

    const val T1_SEND = "T1_SEND"
    const val T3_PLUGIN = "T3_PLUGIN"
    const val T4_HOST = "T4_HOST"
    const val T5_FRAME = "T5_FRAME"
    const val T6_ONSCREEN = "T6_ONSCREEN"

    /** 跳名，顺序即时间顺序，[Entry.render] 按它输出。 */
    val HOPS = listOf(T1_SEND, T3_PLUGIN, T4_HOST, T5_FRAME, T6_ONSCREEN)

    /**
     * 不是跳，是「这条句子被放弃了」的标记，只在 [noteFailed] 里写。
     *
     * <p>刻意留在 [HOPS] 之外：它是结论，不是途经点，混进跳列表会让
     * [Entry.missing] 把失败记录报成「全部到齐」。
     */
    const val FAILED = "FAILED"

    /** 一句话的跳点。`at` 缺失表示这一跳没发生过（不是 0，0 是合法的时刻）。 */
    data class Entry(
        val clientMsgId: String,
        val sessionId: String,
        val at: MutableMap<String, Long> = LinkedHashMap(),
    ) {
        /** 第一跳与最后一跳之间的毫秒数；没有结尾跳就返回 -1。 */
        fun totalMs(): Long {
            val start = at[T1_SEND] ?: return -1
            val end = at[T6_ONSCREEN] ?: at[T5_FRAME] ?: return -1
            return (end - start).coerceAtLeast(0)
        }

        /** 第一个缺失的跳名——「断在哪一跳」的直接答案。 */
        fun missing(): String? = HOPS.firstOrNull { !at.containsKey(it) }

        /** 一行紧凑表示，够贴进 `diag.log` 与错误报告。 */
        fun render(): String {
            val start = at[T1_SEND]
            val cells = HOPS.map { hop ->
                val value = at[hop]
                when {
                    value == null -> "$hop=-"
                    start == null -> "$hop=@$value"
                    else -> "$hop=+${value - start}"
                }
            }
            return "${clientMsgId.take(12)} s=${sessionId.take(8)} ${cells.joinToString(" ")}"
        }
    }

    private val ring = ArrayDeque<Entry>()
    private val index = HashMap<String, Entry>()

    /** 开一条记录并打上 T1。重复调用同一个 id 不会重置已有的跳。 */
    @Synchronized
    fun begin(clientMsgId: String, sessionId: String, now: Long = System.currentTimeMillis()): Entry {
        val existing = index[clientMsgId]
        if (existing != null) {
            existing.at.putIfAbsent(T1_SEND, now)
            return existing
        }
        val entry = Entry(clientMsgId = clientMsgId, sessionId = sessionId)
        entry.at[T1_SEND] = now
        ring.addLast(entry)
        index[clientMsgId] = entry
        while (ring.size > CAPACITY) {
            val dropped = ring.removeFirst()
            // 只删索引里那条**同一个对象**：同 id 被重发时新条目已替换旧条目，
            // 无条件 remove 会把新的也删掉，之后这句话的跳点全部丢失。
            if (index[dropped.clientMsgId] === dropped) index.remove(dropped.clientMsgId)
        }
        return entry
    }

    /**
     * 打一跳。没有对应的 [begin] 就自动开一条（T1 取同一个时刻，缺失）——
     * 回放记录可能先到，而打点不该因此丢掉后半段。
     */
    @Synchronized
    fun hop(clientMsgId: String, hop: String, now: Long = System.currentTimeMillis()) {
        if (clientMsgId.isEmpty()) return
        val entry = index[clientMsgId] ?: begin(clientMsgId, sessionId = "", now = now)
        // 只记第一次：追加重投会让「T5 用了 40ms」变成「T5 是最后一次推送的时刻」。
        entry.at.putIfAbsent(hop, now)
    }

    /**
     * 宿主答复了：T4 就是现在，T3 由 [pluginMs] 反推。
     *
     * <p>反推而不是让插件报时间戳——两端时钟不可比（手机与电脑可能差几十秒），
     * 相减会得到负数或几分钟，比没有更糟。这里是同一台机器上的时长，可以安全地
     * 从 ack 抵达的时刻往前推。
     */
    @Synchronized
    fun hostAccepted(clientMsgId: String, pluginMs: Long?, now: Long = System.currentTimeMillis()) {
        if (pluginMs != null && pluginMs in 0..MAX_PLUGIN_MS) {
            hop(clientMsgId, T3_PLUGIN, (now - pluginMs).coerceAtLeast(0))
        }
        hop(clientMsgId, T4_HOST, now)
    }

    /**
     * 收到一条实时/归档记录：如果它是某个还没有 T5 的待追踪会话的第一条用户消息，
     * 就把 T5 打上并返回它的 id（调用方投影完再打 T6）。
     *
     * <p>为什么按会话 + 顺序匹配而不是按 requestId：`grep -rn requestId` 在
     * `packages/plugin/src`、`packages/core/src`、`scripts/` 下零命中——宿主事件里
     * **没有**这个字段的回声，`RelayGateway.sendPrompt` 生成的 requestId 只能到宿主为止。
     * 于是「T1 之后该会话第一条 USER_INPUT」就是这句话的到达点。
     */
    @Synchronized
    fun noteUserMessage(sessionId: String, now: Long = System.currentTimeMillis()): String? {
        val pending = ring.firstOrNull {
            it.sessionId == sessionId && !it.at.containsKey(T5_FRAME)
        } ?: return null
        pending.at.putIfAbsent(T5_FRAME, now)
        return pending.clientMsgId
    }

    /**
     * 投影完成、进入渲染：给该会话**最近一条**已打 T5、还没有 T6 的记录补上 T6。
     *
     * <p>按会话取「最近一条有 T5 没 T6」而不是传 id：渲染发生在
     * `ChatViewModel.renderActor()` 里，那里只有一屏消息、拿不到当初那个 id。
     * 这也符合「只看刚才那句话」的用途。
     */
    @Synchronized
    fun noteOnScreen(sessionId: String, now: Long = System.currentTimeMillis()): Boolean {
        val target = ring.lastOrNull {
            it.sessionId == sessionId && it.at.containsKey(T5_FRAME) && !it.at.containsKey(T6_ONSCREEN)
        } ?: return false
        target.at[T6_ONSCREEN] = now
        // 闭环的那一跳才落日志：只打了一半的句子写进 diag 会被读成「已经到屏幕了」。
        Diag.log("trace", target.render())
        return true
    }

    /**
     * 把一条已经放弃追踪的记录写进 `diag.log`——用户报「发出去没反应」时，
     * 最有价值的是那条**没走完**的句子，而它永远不会走到 [noteOnScreen]。
     *
     * <p>`reason` 说明是谁放弃的：`ack-timeout` 是插件没回执、`not-connected`
     * 是控制流没连上、`send-failed` 是宿主拒了。缺哪一跳就是断在哪一跳之前。
     */
    @Synchronized
    fun noteFailed(clientMsgId: String, reason: String, now: Long = System.currentTimeMillis()) {
        val entry = index[clientMsgId] ?: return
        // 已经打到 T6 的句子是成功闭合的，别再标成失败。
        if (entry.at.containsKey(T6_ONSCREEN)) return
        Diag.log("trace", "FAILED ${entry.render()} reason=$reason")
    }

    /** 当前所有记录的紧凑文本（最旧在前），供诊断面板/日志导出。 */
    @Synchronized
    fun report(): String = ring.joinToString("\n") { it.render() }

    /** 最近一条记录，测试与诊断面板用。 */
    @Synchronized
    fun last(): Entry? = ring.lastOrNull()

    @Synchronized
    fun find(clientMsgId: String): Entry? = index[clientMsgId]

    /** 单测用：清空。生产代码不该调它。 */
    @Synchronized
    fun clear() {
        ring.clear()
        index.clear()
    }

    /**
     * 插件耗时超过一分钟的 ack 按「没报」处理：那多半是宿主编译了一轮 Gradle，
     * 反推出来的 T3 会早于 T1，画出来比缺失更误导。
     */
    private const val MAX_PLUGIN_MS = 60_000L
}
