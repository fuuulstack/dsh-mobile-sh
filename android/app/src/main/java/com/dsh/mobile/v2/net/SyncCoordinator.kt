package com.dsh.mobile.v2.net

import com.dsh.mobile.v2.data.LocalAckedSeq

/**
 * 会话同步协调器的**决策逻辑**——纯逻辑、不碰 Room、不发请求、不看 WallClock。
 *
 * 抽出来的理由和 v1 的 `SyncPlanner` 一样，但赌注更大：v2 引入了后台几十个会话
 * 的补差、前台单会话独占预算、5 秒脉搏与 12 秒半死判定，这些东西混在一起之后，
 * 在真机上只会表现为「偶尔少一段消息」或「长推理时界面卡死」。把它们拆成
 * 「给定水位、脉搏、错误，下一步干什么」的纯函数，才能在 JVM 里用假时钟钉住
 * 每一个边界（设计 §invariant 1/6/8/9/10）。
 *
 * 三条硬规矩（照 v2 文档）：
 * 1. **单飞**：同一个 sessionId 同一时刻只允许一个 `/v2/sync` 在途。飞行中有新
 *    触发只置脏标记，落地后再跑一轮——不是排队十条请求。
 * 2. **只能增量**：`afterSeq` 永远取 `local_acked_seq`。脉搏发现半死时重放的是
 *    「本地已确认之后」那一段，**绝不重放整段历史**。
 * 3. **零进展要退避**：`hasMore=true` 但游标没动，说明中继手里有东西却发不出来
 *    （版本不匹配、单条超预算被跳过等），继续 tight loop 只会把手机烧热。
 */
internal object SyncCoordinator {

    /** 一次 `/v2/sync` 想要的形状。 */
    internal data class Request(
        val sessionId: String,
        val afterSeq: Long,
        val priority: Int,
        val mode: String,
    )

    /**
     * 单飞槽的结论：跑不跑、跑哪个游标。
     *
     * @param run 现在是否应该发请求。
     * @param dirty 是否需要在这一轮落地后立刻再跑一轮（飞行期间又被触发过）。
     */
    internal data class Slot(val run: Boolean, val dirty: Boolean)

    /**
     * 决定是否发起一轮同步。
     *
     * @param inFlight 该会话当前是否已有请求在途。
     * @param pending 飞行期间是否被再次触发过。
     * @param cooldownUntil 该会话的冷却截止时刻（连续失败后的退避，见 [backoffMs]）。
     * @param now 当前时刻（毫秒，注入以便测试）。
     */
    internal fun decideRun(inFlight: Boolean, pending: Boolean, cooldownUntil: Long, now: Long): Slot {
        // 单飞优先：在途时把触发折叠成脏标记，绝不并发第二条。
        if (inFlight) return Slot(run = false, dirty = true)
        if (now < cooldownUntil) return Slot(run = false, dirty = pending)
        return Slot(run = true, dirty = false)
    }

    /**
     * 有界退避 + 抖动（§invariant 9）：`min(30s, 1s×2^n) × jitter(0.8..1.2)`。
     *
     * [jitter] 由调用方从 `Random` 取，注入是为了测试能钉死区间而不是靠统计。
     * 上限 30 秒是硬的：一个会话卡死不该让用户等十分钟才看到重试。
     */
    internal fun backoffMs(attempt: Int, jitter: Double = 1.0): Long {
        val safeAttempt = attempt.coerceIn(0, 5)
        val base = minOf(30_000L, 1_000L * (1L shl safeAttempt))
        val factor = jitter.coerceIn(0.8, 1.2)
        return (base * factor).toLong()
    }

    /**
     * 一个会话连续失败 3 次就冷却 60 秒（§invariant 10）：单点卡死不能扩散成
     * 「所有会话都在重试」的 CPU 风暴。
     */
    internal fun cooldownMs(consecutiveFailures: Int): Long =
        if (consecutiveFailures >= 3) 60_000L else 0L

    // ============================================================
    // 半死判定（§invariant 1）
    // ============================================================

    /** 脉搏间隔：插件每 5 秒一帧（`SYNC_V2.pulseMs`）。 */
    internal const val PULSE_MS = 5_000L

    /**
     * 连续 12 秒没有脉搏就判半死（设计 §invariant 1：「连续 12 秒（丢 2 周期）」）。
     *
     * 12 秒而不是 10 秒：脉搏自己也要经过隧道与一次系统调度，把门限压在
     * `2×5s` 上会让正常抖动反复判死、把增量重放打成全量重放。
     * **也不要**调大到 30s——v1 卡 16 分钟就是这么来的。
     */
    internal const val PULSE_MISSES = 2
    internal const val PULSE_DEAD_MS = 12_000L

    /**
     * records 能给脉搏当多久的**替身**。
     *
     * <p>替身本身的理由是 [PULSE_DEAD_MS] 旁边那条注释（重放把脉搏压在队尾）。但
     * 替身没有期限就变成另一个洞：插件侧脉搏定时器停掉之后（`MessageBridge` 的探针
     * abort，见坑 54），records 照旧在到，`halfDead()` 于是一直返回 false，而
     * `ChatSyncActor.recover()` 又会把 `lastPulseAt` 归零、让 `halfDead()` 第一行
     * 直接短路——「records 一直在到、脉搏一条不来」从此没有任何东西会判死。界面上
     * 内容还在更新，`hostState` / `running` 却冻在最后一帧旧脉搏上，长推理进度行
     * 不再走。
     *
     * <p>120 秒是重放窗口（`TAIL_LIMIT` 200 条约 500 KiB / 30 多帧，实测 10~15 秒）
     * 的八倍余量：正常的流控排队绝撑不到两分钟，撑到了就说明脉搏通道本身坏了。
     */
    internal const val PULSE_SUBSTITUTE_MAX_MS = 120_000L

    /**
     * 控制流断开后，旧面板按「断流前在跑」续命多久（坑 85 的反向护栏）。
     *
     * <p>断流瞬间 `state(false)` 会收回脉搏凭据；可重连到第一条新脉搏之间有一段
     * 真空，这段时间把一条明明还在跑的轮次画成「已中断」是误报。宽限只补这段真空：
     * 新脉搏一到就以它为准（真 IDLE 立刻收口），到点还没有脉搏就按坑 85 判「已中断」，
     * 绝不无限转圈。
     */
    internal const val DISCONNECT_GRACE_MS = 30_000L

    /**
     * 从**第一次断流**算起的绝对上限：重连可以把 30 秒窗口往后推，但不能
     * 无限推——`recover()` 每 12 秒重开一次 socket 时，若每次 `state(true)`
     * 都重启宽限，坑 85 的「永远运行中」就会从后门回来。
     */
    internal const val DISCONNECT_GRACE_MAX_MS = 120_000L

    /**
     * 「一条脉搏都没收到」那条判死分支的宽限时长——**坑 54 的根修**。
     *
     * <p>事故现场（2026-10-30 本机实测）：`ChatSyncActor.recover()` 把 `lastPulseAt`
     * 归零、`armedAt` 重置成「此刻」，而 tick 里那条「没脉搏」分支**没有一次性守卫**
     * （旁边那条 `halfDead` 分支有 `!halfDeadDeclared`）。于是每次自救都把发条重新
     * 上满 12 秒，12 秒一到又自救，无限循环。插件日志里是每 12 秒一条
     * `focus … replay=200`，手机端每 12 秒重开一次控制流、插件每 12 秒重放整个尾部
     * 窗口（200 条约 500 KiB，分 30 多帧），紧接着就是 `tunnel credit stall 10s`。
     *
     * <p>修法不是「加个布尔守卫就完事」：真的没有脉搏时，重连本身可能是有效的
     * （新 socket 上脉搏会来）。所以这里按**连续自救次数**做渐进宽限——第一次
     * 仍然 12 秒（快速自救，别让用户等），之后每救一次翻倍，封顶 120 秒。
     * 任何一条真脉搏都会把 `recoveries` 清零，宽限随之回到 12 秒。
     *
     * @param recoveries 本次聚焦内连续自救次数（脉搏到达即清零）。
     */
    internal fun noPulseGraceMs(recoveries: Int): Long {
        val n = recoveries.coerceIn(0, 4)
        return minOf(120_000L, PULSE_DEAD_MS shl n)
    }

    /**
     * 是否需要重连+重放。
     *
     * 判据刻意分成两半，因为「连接活着但没数据」和「连接死了」要不同处理：
     * - 会话**正在跑**（`running`）却 12 秒没脉搏 → 半死，要重开控制流并增量重放。
     * - 会话没在跑，但 `latestSeq > localAcked`（有东西没同步下来）且没脉搏 → 同样半死。
     * - 会话没在跑、也没有欠账 → 没有脉搏是正常的，插件不必为没人看的静态会话耗电。
     *
     * 长推理（`hostState == "THINKING_SILENT"`）只要脉搏还在就**不算**半死：这正是
     * v1 搞错的地方——它把「没字节」当成「链路死了」，于是把好好的长推理重放打断。
     *
     * @param lastPulseAt 最后一次收到脉搏的时刻（0 = 本次聚焦内还没收到过）。
     */
    internal fun halfDead(
        now: Long,
        lastPulseAt: Long,
        running: Boolean,
        latestSeq: Long,
        localAcked: Long,
        lastDataAt: Long = 0,
    ): Boolean {
        if (lastPulseAt <= 0) return false // 还没开始计时（刚 focus），不算半死
        if (now - lastPulseAt < PULSE_DEAD_MS) return false
        // 脉搏迟到不代表链路死了：records 也是一条到达证明。
        //
        // 事故现场（2026-10-30 生产日志实测）：`focus … replay=200`（约 500 KiB，
        // 按 16 KiB 分片是 32 帧）之后紧跟 `tunnel credit stall 10s pending=12KiB
        // buffered=0KiB`。`MessageBridge` 用 `tail.then()` 把 records 与脉搏**严格
        // 串行**在同一条 channel、同一份流控信用上（每条消息还要一次 header→credit
        // 往返），于是 32 帧重放足以把脉搏压在队尾十几秒。手机这边只认脉搏，
        // 12 秒没脉搏就判死 → 重连 → 插件重放一遍 → 再堵一次，自我维持。
        //
        // 判活的正确口径是「这条控制流最近有没有交出任何东西」，不是「有没有交出
        // 我指定的那一种」。records 帧带 seq 且逐条校验，伪造不了活性。
        //
        // 但替身**同样有期限**，而且和 [pulseEvidenceLost] 共用同一个额度
        // [PULSE_SUBSTITUTE_MAX_MS]。没有期限的话这一支就是另一个「永远不判死」：
        // 插件侧脉搏定时器停掉之后（`MessageBridge` 的探针 abort，见坑 54），
        // records 照旧一条条来、`lastDataAt` 永远是新的，于是一个正在跑、
        // `latestSeq` 还在涨的会话会永远被判成「健康的」——`hostState` / `running`
        // 冻在最后一帧旧脉搏上，长推理的进度行不再走，而界面上看一切正常。
        // 这里不直接复用 [pulseEvidenceLost]：它的 `armedAt` 语义（发条起点）属于
        // 「可以反复自救」那一支，而这一支只判一次，判据只能看「脉搏有多旧」。
        if (lastDataAt > 0 &&
            now - lastDataAt < PULSE_DEAD_MS &&
            now - lastPulseAt < PULSE_SUBSTITUTE_MAX_MS
        ) {
            return false
        }
        return running || latestSeq > localAcked
    }

    /**
     * 脉搏证据是不是已经**彻底没了**（该走自救的那一支）。
     *
     * <p>与 [halfDead] 分工：[halfDead] 判「脉搏很旧、但有人在忙」，只判一次；
     * 这一支判「连脉搏通道还好不好都不知道」，**带渐进宽限，是可以反复自救的那一支**
     * （见 [noPulseGraceMs]）。
     *
     * <p>原来这条分支的判据是「从来没有收到过任何东西」（`lastPulseAt` 与
     * `lastDataAt` 都是 0）。它有两个洞：
     * 1. 只要收到过一条 records，`lastDataAt` 就再也不是 0，这一支**永久不再成立**；
     * 2. [ChatSyncActor.recover] 会把 `lastPulseAt` 归零，于是 [halfDead] 第一行
     *    `lastPulseAt <= 0` 也永久短路。
     *
     * <p>两条一起失效之后，「records 一直在到、脉搏一条不来」（插件侧探针 abort，
     * 见坑 54）就再没有任何东西会判死：内容还在更新，`hostState` / `running` 冻在
     * 最后一帧旧脉搏上，长推理的进度行不再走。所以判据必须是**新鲜度**，不是
     * 「有没有过」。
     *
     * @param armedAt 发条起点（[ChatSyncActor.start] 或上一次自救的时刻）。
     * @param lastDataAt 最近一次收到 records 的时刻，只在 [PULSE_SUBSTITUTE_MAX_MS]
     *   之内可以给脉搏当替身。
     */
    internal fun pulseEvidenceLost(
        now: Long,
        armedAt: Long,
        lastPulseAt: Long,
        lastDataAt: Long,
    ): Boolean {
        // 真脉搏比什么都硬：有就按一个判死窗口算新鲜。
        if (lastPulseAt > 0 && now - lastPulseAt < PULSE_DEAD_MS) return false
        // records 可以当替身，但**有期限**：重放 500 KiB 时脉搏被压在队尾十几秒，
        // 那时把连接判死是最坏的选择；可要是过了两分钟还是「只有 records」，
        // 那就不是排队，是脉搏通道自己坏了。
        val sincePulse = if (lastPulseAt > 0) now - lastPulseAt else now - armedAt
        val substitute = lastDataAt > 0 &&
            now - lastDataAt < PULSE_DEAD_MS &&
            sincePulse < PULSE_SUBSTITUTE_MAX_MS
        return !substitute
    }

    /**
     * 断流真空期里「在跑」的判据：**真脉搏优先**，宽限只补「断开→重连→新脉搏」这段。
     *
     * <p>坑 85 要求断流即收回凭据，否则手机会把昨天那条没打 `turn/end` 的轮次
     * 永远画成「运行中」。但一刀切又会在每次 socket 重连的几秒里把正在跑的轮次
     * 误标成「已中断」（2026-11-12 用户反馈「折叠栏总是出现已经中断」）。所以这里
     * 只给断流前确实在跑的会话一段有限宽限：新脉搏一到立刻以它为准，到点还没有
     * 脉搏就仍按坑 85 收口。重连能把 30 秒窗口往后推，但绝对上限见
     * [DISCONNECT_GRACE_MAX_MS]，反复 `recover()` 不许把宽限续成无限。
     */
    internal fun hostRunningAfterDisconnect(
        now: Long,
        disconnectedAt: Long,
        reconnectedAt: Long,
        wasRunning: Boolean,
        pulseHostState: String?,
        graceMs: Long = DISCONNECT_GRACE_MS,
    ): Boolean {
        // 真脉搏比宽限硬：插件明确说 IDLE 就立刻收口，别拿宽限把它盖回去。
        if (pulseHostState != null) return pulseHostState != "IDLE"
        if (!wasRunning || disconnectedAt <= 0) return false
        // 重连把宽限起点推后：真空期算的是「这次连上之后还要等多久」；
        // 但无论重连多少次，都不能越过第一次断流起的绝对上限（坑 85）。
        val since = now - maxOf(disconnectedAt, reconnectedAt)
        if (since < 0 || since >= graceMs) return false
        return now - disconnectedAt < DISCONNECT_GRACE_MAX_MS
    }

    /**
     * 收到脉搏后的动作。
     *
     * 返回的 [Act.replay] 表示「要发一次增量 `/v2/sync`」。[Act.focus] 表示还要在
     * 控制流上重发 `FOCUS_SESSION`（让插件重开它那边的订阅）。
     */
    internal data class Act(
        val replay: Boolean = false,
        val focus: Boolean = false,
        /** 已经判过一轮半死了：不要每个 tick 都重连，否则 5 秒一次自伤。 */
        val throttle: Boolean = false,
    )

    /**
     * 根据脉搏推进状态机。
     *
     * @param pulseLatestSeq 脉搏里的 `latestSeq`（插件已分配的最新 seq）。
     * @param pulseCovered 脉搏里的 `archiveCoveredSeq`（中继已确认归档到的位置）。
     */
    internal fun onPulse(
        localAcked: Long,
        pulseLatestSeq: Long,
        pulseCovered: Long,
        halfDeadAlready: Boolean,
    ): Act {
        // 本地落后于插件、而插件那一批已经被中继收下了 → 增量拉一次就够。
        val behind = pulseLatestSeq > localAcked
        val repairable = behind && pulseCovered >= pulseLatestSeq
        return when {
            // 已经判过半死：本轮只做静默等待，不要在同一个缺口上反复重连。
            halfDeadAlready -> Act(replay = repairable)
            repairable -> Act(replay = true)
            // 欠账但中继还没收到：等插件把 outbox 排空，这里重连也拿不到东西。
            else -> Act()
        }
    }

    /**
     * 一次 `/v2/sync` 落地后水位能推进到哪里。
     *
     * **只认 `nextCursorSeq`**（中继明说这一页覆盖到哪），并且**不得倒退**。
     * `archiveCoveredSeq` 只更新「归档覆盖」那个水位，不能拿来当本地已落盘证明——
     * 这正是 §invariant 2 要求三水位物理分离的原因。
     *
     * @param requestAfter 本轮请求带上去的游标。
     * @param nextCursor 中继回的 `nextCursorSeq`。
     * @param landed 实际成功写进 Room 的最大 seq（0 = 一条都没写）。
     */
    internal fun advance(requestAfter: Long, nextCursor: Long, landed: Long): LocalAckedSeq {
        // 游标只能前进：服务端回一个更小的值是异常，宁可用本地的也不抹掉进度。
        val safeNext = if (nextCursor > requestAfter) nextCursor else requestAfter
        // 但绝不能越过「真的落盘了」的位置：先落盘后推进水位是硬的（§invariant 4）。
        val landedMax = if (landed > requestAfter) landed else requestAfter
        return LocalAckedSeq(minOf(safeNext, landedMax))
    }

    /** 这一页之后是否还应该立刻再拉一次。 */
    internal fun continueAfter(page: RelayApiV2.SyncPage, requestAfter: Long): Boolean {
        if (!page.hasMore) return false
        // hasMore 但游标没动 = 原地打转（§invariant 8 的反面），退到退避路径。
        return page.nextCursorSeq > requestAfter
    }

    /**
     * 记一条会话的同步结果，集中算「连续失败几次」「原地打转几轮」「冷却到何时」。
     *
     * <p>**两个计数器必须分开**：`failures` 是「请求本身没成功」（网络/5xx），
     * `stalls` 是「请求成功了但一页都没往前走」（`hasMore` 却游标不动）。早先只有
     * 前者，而 `consecutiveZeros()` 读的也是它 —— 名字说有零进展记账，实际一次都没记，
     * 将来有人照着名字接退避，接到的会是「HTTP 失败数」，语义完全错位。
     *
     * <p>冷却由两者共同决定：任一计数器到阈值就冷却，两个都清零才解除。
     */
    internal class FailureTracker(private val clock: () -> Long = System::currentTimeMillis) {
        private val failures = HashMap<String, Int>()
        private val stalls = HashMap<String, Int>()
        private val cooldown = HashMap<String, Long>()

        fun cooldownUntil(sessionId: String): Long = cooldown[sessionId] ?: 0L

        /** 一页真的往前走了。 */
        fun noteProgress(sessionId: String) {
            stalls.remove(sessionId)
            if ((failures[sessionId] ?: 0) == 0) cooldown.remove(sessionId)
        }

        fun noteSuccess(sessionId: String) {
            failures.remove(sessionId)
            if ((stalls[sessionId] ?: 0) == 0) cooldown.remove(sessionId)
        }

        fun noteFailure(sessionId: String): Long {
            val next = (failures[sessionId] ?: 0) + 1
            failures[sessionId] = next
            val wait = cooldownMs(next)
            if (wait > 0) cooldown[sessionId] = clock() + wait
            return backoffMs(next - 1)
        }

        /**
         * 请求成功但原地打转：`hasMore` 为真、游标却没动。
         *
         * <p>不记账的后果是客户端 hot loop —— 每一轮都「成功」返回同一页，
         * `dirty` 又被脉搏反复置真，于是每 tick 都发一次注定无进展的请求。
         * 中继侧虽然有 `SyncLoopGuard`，但那是护栏不是协议：客户端不该依赖对端替自己刹车。
         */
        fun noteStall(sessionId: String): Long {
            val next = (stalls[sessionId] ?: 0) + 1
            stalls[sessionId] = next
            val wait = cooldownMs(next)
            if (wait > 0) cooldown[sessionId] = clock() + wait
            return backoffMs(next - 1)
        }

        fun failuresOf(sessionId: String): Int = failures[sessionId] ?: 0

        /** 连续「成功但没进展」的轮数。 */
        internal fun stallRounds(sessionId: String): Int = stalls[sessionId] ?: 0
    }
}
