package com.dsh.mobile.v2.net

import com.dsh.mobile.v2.data.ArchiveCoveredSeq
import com.dsh.mobile.v2.data.LocalAckedSeq
import com.dsh.mobile.v2.data.RecordKind
import com.dsh.mobile.v2.data.SessionWatermarks
import com.dsh.mobile.v2.data.TimelineRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `ChatSyncActor` 的链路语义（设计 §4.2 时序图 + §4.3 双层隔离）。
 *
 * 每条用例复刻一个真机上会表现为「消息少一段」「审批复活」「长推理卡死」的序列，
 * 而不是对着实现写断言。
 */
class ChatSyncActorTest {

    // ============================================================
    // 进会话：本地先出、增量补齐
    // ============================================================

    @Test
    fun `opening a session renders local rows before the network answers`() = runBlocking {
        val store = FakeStore(seed = (1L..3L).map { record(it) })
        val transport = FakeTransport()
        val actor = ChatSyncActor("s1", "h1", store, transport)

        // start() 里第一件事是读本地；此时 transport 还没被调用过。
        assertEquals(0, transport.syncCalls.size)
        actor.start()

        assertEquals("本地已有的 3 条要立刻在 records 里", 3, actor.records.size)
        assertEquals("进会话必须自己发起补差，不能等列表页先跑过", 1, transport.syncCalls.size)
        assertEquals("必须从 local_acked 起步，不是 0", 3L, transport.syncCalls.first().afterSeq)
    }

    @Test
    fun `history loaded by the user survives the next sync landing`() = runBlocking {
        // 2026-11-03 用户实测：正在看历史、刚点了「载入更早」，下方一有输出，
        // 翻出来的内容立刻消失。根因是落盘之后 `records = store.tail(...)`
        // 一处赋值把整个内存窗口换回尾部 200 条，`loadEarlier()` 加进来的前部
        // 就此蒸发。落盘必须**只替换尾部**，保住已经翻出来的更早部分。
        val store = FakeStore(seed = (1L..300L).map { record(it) })
        val transport = FakeTransport()
        val actor = ChatSyncActor("s1", "h1", store, transport)
        actor.start()
        // 尾部窗口：最后 200 条 = 101..300。
        assertEquals(101L, actor.records.first().seq)

        // 用户上拉翻历史：取到 101 之前的 30 条 ⇒ 71..100。
        val added = actor.loadEarlier(pageSize = 30)
        assertEquals(30, added)
        assertEquals("翻出来的最老一条", 71L, actor.records.first().seq)
        assertEquals(230, actor.records.size)

        // 下方开始输出 ⇒ 同步落盘 301..302，触发重读尾部。
        transport.pages += page(nextCursor = 302, records = listOf(record(301), record(302)))
        actor.syncUntilCaughtUp()

        assertEquals("新记录要进来", 302L, actor.records.last().seq)
        assertEquals(
            "翻出来的历史不能被落盘冲掉",
            71L,
            actor.records.first().seq,
        )
        assertEquals("窗口是「历史 + 新尾部」的并集", 232, actor.records.size)
        // 且必须严格升序、无重复 —— 投影器按 seq 顺序消费。
        assertEquals(actor.records.map { it.seq }.sorted(), actor.records.map { it.seq })
        assertEquals(actor.records.size, actor.records.map { it.seq }.toSet().size)
    }

    @Test
    fun `the kept history is re-read from the store so it has no holes`() = runBlocking {
        // 「保住已翻出来的历史」必须靠**从 SQLite 连续读回**，而不是过滤内存里
        // 已有的 records：中间那段完全可能有些记录落进了 Room 却从没进过内存
        // （上一轮同步写进去、之后窗口被重置过）。过滤内存只会保住碰巧在内存里
        // 的那些，时间线上凭空多出洞。这条测试钉住「走的是数据库」。
        val store = FakeStore(seed = (1L..300L).map { record(it) })
        val transport = FakeTransport()
        val actor = ChatSyncActor("s1", "h1", store, transport)
        actor.start()
        assertEquals(101L, actor.records.first().seq)
        assertTrue("没翻过历史时不该白读一段", store.betweenCalls.isEmpty())

        // 用户上拉翻历史 ⇒ 窗口前推到 71。
        actor.loadEarlier(pageSize = 30)
        assertEquals(71L, actor.records.first().seq)

        // 下方输出 ⇒ 落盘 301..302 ⇒ 重读尾部。
        transport.pages += page(nextCursor = 302, records = listOf(record(301), record(302)))
        actor.syncUntilCaughtUp()

        assertEquals("必须向数据库要「已翻到的最老」到「新尾部起点」这一段", 1, store.betweenCalls.size)
        // 落盘 301..302 之后尾部 200 条是 103..302，所以读的区间是 [71, 103)。
        assertEquals(71L to 103L, store.betweenCalls.first())
        assertEquals("整段必须连续无洞", (71L..302L).toList(), actor.records.map { it.seq })
        assertEquals("最老一条还是翻到的那里", 71L, actor.records.first().seq)
        assertEquals("新记录也要在", 302L, actor.records.last().seq)
    }

    @Test
    fun `focus carries the local watermark so the replay is incremental`() = runBlocking {
        val store = FakeStore(seed = (1L..10L).map { record(it) })
        val transport = FakeTransport()
        Chatter(ChatSyncActor("s1", "h1", store, transport)).start()

        assertEquals("FOCUS 的 sinceSeq 必须是 local_acked，绝不复用 0", 10L, transport.focuses.first())
    }

    @Test
    fun `a gap filled by sync advances the watermark to what actually landed`() = runBlocking {
        val store = FakeStore(seed = (1L..100L).map { record(it) })
        val transport = FakeTransport()
        transport.pages += page(nextCursor = 104, records = (101L..104L).map { record(it) })
        val actor = ChatSyncActor("s1", "h1", store, transport)
        actor.start()

        assertEquals("落盘到 104，水位就该是 104", 104L, actor.watermarks.localAcked.value)
        assertEquals(104, store.rows.size)
        assertEquals(104L, store.watermark("s1").localAcked.value)
    }

    @Test
    fun `a server cursor past what landed never advances the watermark`() = runBlocking {
        // 中继声称覆盖到 200，但这一页只带了 101..104（预算截断）。
        val store = FakeStore(seed = (1L..100L).map { record(it) })
        val transport = FakeTransport()
        transport.pages += page(nextCursor = 200, hasMore = true, records = (101L..104L).map { record(it) })
        transport.pages += page(nextCursor = 200, hasMore = false, records = (105L..200L).map { record(it) })
        val actor = ChatSyncActor("s1", "h1", store, transport)
        actor.start()

        assertEquals("两页拉完才轮到 200", 200L, actor.watermarks.localAcked.value)
        assertEquals(200, store.rows.size)
    }

    @Test
    fun `sync stops when hasMore says yes but the cursor never moves`() = runBlocking {
        val store = FakeStore(seed = (1L..100L).map { record(it) })
        val transport = FakeTransport()
        // 中继一直说「还有」，但游标不动、也没有记录：这就是零进展。
        repeat(5) { transport.pages += page(nextCursor = 100, hasMore = true, records = emptyList()) }
        val actor = ChatSyncActor("s1", "h1", store, transport)
        actor.start()

        assertEquals("零进展要停，不能 tight loop 把手机烧热", 1, transport.syncCalls.size)
        assertEquals(100L, actor.watermarks.localAcked.value)
    }

    // ============================================================
    // 实时流：overlay 与空洞（§4.3）
    // ============================================================

    @Test
    fun `a live record above the watermark lands in the overlay, not in SQLite`() = runBlocking {
        val store = FakeStore(seed = (1L..100L).map { record(it) })
        val transport = FakeTransport()
        transport.pages += page(nextCursor = 100, hasMore = false, records = emptyList())
        val actor = ChatSyncActor("s1", "h1", store, transport)
        actor.start()

        // 实时推来 103（前面的 101..102 还没到）。
        transport.push(actor, listOf(record(103)))

        assertEquals("比水位高的记录先进内存", 1, actor.overlay.size)
        assertTrue(actor.overlay.containsKey(103L))
        assertEquals("本地水位一动不动，否则空洞会被永久跳过", 100L, actor.watermarks.localAcked.value)
        assertEquals(100, store.rows.size)
    }

    @Test
    fun `sync landing past an overlay entry drops that entry`() = runBlocking {
        val store = FakeStore(seed = (1L..100L).map { record(it) })
        val transport = FakeTransport()
        transport.pages += page(nextCursor = 100, hasMore = false, records = emptyList())
        val actor = ChatSyncActor("s1", "h1", store, transport)
        actor.start()
        transport.push(actor, listOf(record(103)))

        // 归档补齐到 105：103 已经有权威副本了。
        transport.pages += page(nextCursor = 105, hasMore = false, records = (101L..105L).map { record(it) })
        actor.syncUntilCaughtUp()

        assertFalse("已经有 SQLite 副本的 overlay 条目必须让位", actor.overlay.containsKey(103L))
        assertEquals(105L, actor.watermarks.localAcked.value)
    }

    @Test
    fun `a record for another session never enters this actor`() = runBlocking {
        val store = FakeStore(seed = emptyList())
        val transport = FakeTransport()
        transport.pages += page(nextCursor = 0, records = emptyList())
        val actor = ChatSyncActor("s1", "h1", store, transport)
        actor.start()

        // 插件推了别的会话：v1 的审批串会话就是这么进来的。
        transport.pushFrame(actor, sessionId = "other", listOf(record(50, sessionId = "other")))

        assertEquals("别的会话的记录必须丢掉", 0, actor.overlay.size)

        // 更坏的情况：帧自称是本会话，里面混了一条外会话记录。
        transport.pushFrame(actor, sessionId = "s1", listOf(record(51, sessionId = "other")))
        assertEquals("逐条也要挡：一条混进来的记录就是一次越权", 0, actor.overlay.size)
    }

    @Test
    fun `a streaming delta replaces the live text instead of appending`() = runBlocking {
        val store = FakeStore(seed = emptyList())
        val transport = FakeTransport()
        transport.pages += page(nextCursor = 0, records = emptyList())
        val actor = ChatSyncActor("s1", "h1", store, transport)
        actor.start()

        transport.pushRaw(actor, delta(7, "第一段"))
        transport.pushRaw(actor, delta(7, "第一段而且更长"))
        assertEquals("增量是累计文本，不是碎片：要替换不要拼接", "第一段而且更长", actor.streamingText)
    }

    // ============================================================
    // 单飞（§6）
    // ============================================================

    @Test
    fun `a trigger during an in-flight sync folds into one extra round`() = runBlocking {
        val store = FakeStore(seed = emptyList())
        val transport = SlowTransport()
        val actor = ChatSyncActor("s1", "h1", store, transport)

        // 飞行期间插件又推了一批：只该置脏标记，不该并发第二条请求。
        transport.beforeReply = {
            transport.pushRaw(actor, record(1))
            transport.pushRaw(actor, record(2))
        }
        actor.syncUntilCaughtUp()

        assertEquals("并发触发被折叠：只发了一条请求", 1, transport.syncCalls.size)
    }

    // ============================================================
    // 半死自愈（§1）
    // ============================================================

    @Test
    fun `thinking label ticks seconds but never forces a state publish`() = runBlocking {
        // 2026-10-31 GPU 满帧空转修复的回归钉：THINKING_SILENT 时 thinkingLabel 的秒数
        // 每秒都在变，但它已不再写进 ChatState（显示移除后全仓库无读方），只走诊断
        // 日志。这条测试钉住「ChatSyncActor 侧的 label 语义不变」：静默期非空、秒数
        // 递增、宿主动起来立刻回空。UI 侧的「不 publish」由 renderActor 的改动本身
        // 保证（写方已删，无状态可断言）。
        var now = 1_000L
        val store = FakeStore(seed = (1L..100L).map { record(it) })
        val transport = FakeTransport()
        transport.pages += page(nextCursor = 100, records = emptyList())
        val actor = ChatSyncActor("s1", "h1", store, transport, clock = { now })
        actor.start()

        transport.pushPulse(actor, latest = 100, covered = 100, state = "THINKING_SILENT", now = now)
        val first = actor.thinkingLabel()
        assertTrue("静默期必须有非空 label", first.isNotBlank())

        now += 5_000
        val later = actor.thinkingLabel()
        assertTrue("秒数要涨", later > first)

        transport.pushPulse(actor, latest = 100, covered = 100, state = "STREAMING", now = now)
        assertEquals("宿主动起来 label 必须立刻回空", "", actor.thinkingLabel())
    }

    @Test
    fun `a long silent reasoning keeps pulsing and is never declared half dead`() = runBlocking {
        var now = 1_000L
        val store = FakeStore(seed = (1L..100L).map { record(it) })
        val transport = FakeTransport()
        transport.pages += page(nextCursor = 100, records = emptyList())
        val actor = ChatSyncActor("s1", "h1", store, transport, clock = { now })
        actor.start()

        // 60 秒的「深度思考」：每 5 秒一帧脉搏。
        repeat(12) {
            now += 5_000
            transport.pushPulse(actor, latest = 100, covered = 100, state = "THINKING_SILENT", now = now)
            actor.tick()
        }

        assertEquals("脉搏正常就绝不重连：v1 卡 16 分钟就坏在这里", 1, transport.focuses.size)
        assertTrue(actor.lastPulse!!.thinkingSilent)
    }

    @Test
    fun `twelve silent seconds on a running session triggers an incremental repair`() = runBlocking {
        var now = 1_000L
        val store = FakeStore(seed = (1L..100L).map { record(it) })
        val transport = FakeTransport()
        transport.pages += page(nextCursor = 100, records = emptyList())
        val actor = ChatSyncActor("s1", "h1", store, transport, clock = { now })
        actor.start()
        transport.pushPulse(actor, latest = 120, covered = 120, state = "STREAMING", now = now)
        val syncCountBefore = transport.syncCalls.size

        // 12 秒没有任何脉搏。
        now += 12_000
        actor.tick()

        assertTrue("半死必须自己恢复", transport.syncCalls.size > syncCountBefore)
        assertTrue("恢复要重挂订阅", transport.focuses.size >= 2)
    }

    @Test
    fun `the repair replays from the local watermark, never from zero`() = runBlocking {
        var now = 1_000L
        val store = FakeStore(seed = (1L..100L).map { record(it) })
        val transport = FakeTransport()
        transport.pages += page(nextCursor = 100, records = emptyList())
        val actor = ChatSyncActor("s1", "h1", store, transport, clock = { now })
        actor.start()
        transport.pushPulse(actor, latest = 130, covered = 130, state = "STREAMING", now = now)
        now += 13_000
        actor.tick()

        val last = transport.syncCalls.last()
        assertEquals("半死恢复只能增量：从头重放是 v1 的老毛病", 100L, last.afterSeq)
    }

    @Test
    fun `the repair is declared once, not on every tick`() = runBlocking {
        var now = 1_000L
        val store = FakeStore(seed = (1L..100L).map { record(it) })
        val transport = FakeTransport()
        transport.pages += page(nextCursor = 100, records = emptyList())
        val actor = ChatSyncActor("s1", "h1", store, transport, clock = { now })
        actor.start()
        transport.pushPulse(actor, latest = 120, covered = 120, state = "STREAMING", now = now)
        now += 20_000
        actor.tick()
        val afterFirst = transport.focuses.size
        // 又过一个 tick，仍然没有新脉搏：不该每次都重连。
        now += 1_000
        actor.tick()
        assertEquals("同一个缺口上不许每 tick 重连一次", afterFirst, transport.focuses.size)
    }

    @Test
    fun `a control stream that never sends a pulse is not treated as healthy forever`() = runBlocking {
        // 实测事故：socket 建起来了（`ws open`），可一条脉搏都没到达——插件侧 focus
        // 没生效、半路丢帧、解帧失败都会长这样。旧判据把 lastPulseAt==0 当成「还没
        // 开始计时」，于是没有任何东西会判死，界面永久停在最后一帧。
        var now = 1_000L
        val store = FakeStore(seed = listOf(record(1)))
        val transport = FakeTransport()
        transport.pages += page(nextCursor = 1, records = emptyList())
        val actor = ChatSyncActor("s1", "h1", store, transport, clock = { now })
        actor.start()
        val focusesAfterStart = transport.focuses.size
        val syncsAfterStart = transport.syncCalls.size

        // 11.9 秒：还不到 12 秒的判死窗口，不许乱动。
        now += 11_999
        actor.tick()
        assertEquals("没到窗口就重连等于制造风暴", focusesAfterStart, transport.focuses.size)

        // 12 秒整：必须自救，而且**重开控制流**（只重挂订阅救不回来）。
        now += 1
        actor.tick()
        assertTrue("一条脉搏都没有也必须能判死", transport.focuses.size > focusesAfterStart)
        assertEquals("自救要重开 socket，不是只重挂订阅", focusesAfterStart + 2, transport.focuses.size)
        assertTrue("自救必须顺带补差", transport.syncCalls.size > syncsAfterStart)
        assertEquals(1, actor.recoveries)
    }

    @Test
    fun `a pulse after the repair resets the recovery budget`() = runBlocking {
        var now = 1_000L
        val store = FakeStore(seed = listOf(record(1)))
        val transport = FakeTransport()
        transport.pages += page(nextCursor = 1, records = emptyList())
        val actor = ChatSyncActor("s1", "h1", store, transport, clock = { now })
        actor.start()

        now += 12_000
        actor.tick()
        assertEquals(1, actor.recoveries)

        // 真的有脉搏回来了才算健康：重置只发生在 `feed.pulse()` 里，不在 recover()
        // 里——否则「重连也没用」会变成每 12 秒一次的静默死循环。
        transport.pushPulse(actor, latest = 1, covered = 1, state = "STREAMING", now = now)
        assertEquals(0, actor.recoveries)
    }

    @Test
    fun `records alone do not keep a pulseless control stream alive forever`() = runBlocking {
        // 坑 54 的另一半：插件侧的脉搏定时器停了（`MessageBridge` 的探针 abort），
        // 但 records 照旧在到。旧判据是 `maxOf(lastPulseAt, lastDataAt) <= 0`——
        // 只要收到过一条 records 就永久为假；而 `recover()` 又把 `lastPulseAt` 归零，
        // 让 `halfDead()` 第一行也永久短路。两条一起失效之后，没有任何东西会判死：
        // 界面内容还在更新，`hostState`/`running` 冻在最后一帧旧脉搏上。
        var now = 1_000L
        val store = FakeStore(seed = listOf(record(1)))
        val transport = FakeTransport()
        transport.pages += page(nextCursor = 1, records = emptyList())
        val actor = ChatSyncActor("s1", "h1", store, transport, clock = { now })
        actor.start()

        // 第 1 次自救：一条脉搏都没来过。
        now += SyncCoordinator.PULSE_DEAD_MS
        actor.tick()
        assertEquals(1, actor.recoveries)

        // 此后 records 每 12 秒到一条（模拟持续的实时流），脉搏一条都没有。
        var seq = 2L
        repeat(12) {
            now += 12_000
            transport.pushRaw(actor, record(seq++))
            actor.tick()
        }

        assertTrue(
            "records 再新鲜也不能替脉搏背书一辈子（recoveries=${actor.recoveries}）",
            actor.recoveries >= 2,
        )
    }

    @Test
    fun `records do keep a pulseless control stream alive through a replay`() = runBlocking {
        // 反向钉子：重放 500 KiB 把脉搏压在队尾十几秒时，records 还在到就**不许**
        // 再自救一次——那正是「最忙的时候」，重连会把重放从头再来一遍。
        var now = 1_000L
        val store = FakeStore(seed = listOf(record(1)))
        val transport = FakeTransport()
        transport.pages += page(nextCursor = 1, records = emptyList())
        val actor = ChatSyncActor("s1", "h1", store, transport, clock = { now })
        actor.start()

        now += SyncCoordinator.PULSE_DEAD_MS
        actor.tick()
        assertEquals(1, actor.recoveries)

        // 紧接着的重放窗口：records 每 3 秒一条，总共 30 秒，脉搏仍然一条没有。
        var seq = 2L
        repeat(10) {
            now += 3_000
            transport.pushRaw(actor, record(seq++))
            actor.tick()
        }

        assertEquals("重放期间的 records 就是活着的证据，不许再自救", 1, actor.recoveries)
    }

    @Test
    fun `a flaky control stream does not count as a repair`() = runBlocking {
        // 反向钉子：脉搏一直在就别重连。这一条与上面两条一起构成「判死只能发生在
        // 确实没有脉搏的时候」的完整边界。
        var now = 1_000L
        val store = FakeStore(seed = listOf(record(1)))
        val transport = FakeTransport()
        transport.pages += page(nextCursor = 1, records = emptyList())
        val actor = ChatSyncActor("s1", "h1", store, transport, clock = { now })
        actor.start()
        val focusesAfterStart = transport.focuses.size

        repeat(20) {
            now += 5_000
            transport.pushPulse(actor, latest = 1, covered = 1, state = "STREAMING", now = now)
            actor.tick()
        }

        assertEquals("有脉搏就不许重连", focusesAfterStart, transport.focuses.size)
        assertEquals(0, actor.recoveries)
    }

    // ============================================================
    // 首行预览
    // ============================================================

    @Test
    fun `the preview is read from local rows, never from the relay`() = runBlocking {
        val store = FakeStore(
            seed = listOf(
                record(1),
                record(2, kind = RecordKind.ASSISTANT_TEXT),
                record(3, kind = RecordKind.ASSISTANT_TEXT),
            ),
        )
        val transport = FakeTransport()
        transport.pages += page(nextCursor = 2, records = emptyList())
        val actor = ChatSyncActor("s1", "h1", store, transport)
        actor.start()

        val preview = store.latestAssistant("s1")
        assertNotNull("首行预览从本地 SQLite 现算，中继不再转发 snippet", preview)
        // 取最后一条：列表要的是「它刚答了什么」，不是几天前开局那句。
        assertEquals(3L, preview!!.seq)
    }

    @Test
    fun `nothing is claimed when no assistant text exists locally`() = runBlocking {
        val store = FakeStore(seed = listOf(record(1, kind = RecordKind.USER_INPUT)))
        assertNull(store.latestAssistant("s1"))
    }

    // ============================================================
    // 夹具
    // ============================================================

    private fun record(seq: Long, kind: RecordKind = RecordKind.ASSISTANT_TEXT, sessionId: String = "s1") =
        TimelineRecord(
            sessionId = sessionId,
            seq = seq,
            prevSeq = seq - 1,
            turnId = "$sessionId:t1",
            kind = kind,
            payloadJson = """{"type":"assistant/message","data":{"text":"第 $seq 条"}}""",
        )

    private fun delta(seq: Long, text: String) = TimelineRecord(
        sessionId = "s1",
        seq = seq,
        prevSeq = seq - 1,
        turnId = "s1:t1",
        kind = RecordKind.STREAM_DELTA,
        payloadJson = text,
    )

    private fun page(
        nextCursor: Long,
        records: List<TimelineRecord>,
        hasMore: Boolean = false,
    ) = RelayApiV2.SyncPage(
        sessionId = "s1",
        reqAfterSeq = 0,
        nextCursorSeq = nextCursor,
        archiveCoveredSeq = nextCursor,
        hasMore = hasMore,
        stopReason = if (hasMore) "BUDGET_EXHAUSTED" else "TAIL_COMPLETE",
        records = records,
        bytes = 0,
        skipped = 0,
    )

    /** 内存版时间线存储：`commit` 严格先写行、再写水位，和 Room 的事务同序。 */
    private class FakeStore(seed: List<TimelineRecord>) : ChatSyncActor.ChatStore {
        val rows = LinkedHashMap<Long, TimelineRecord>()
        private val marks = HashMap<String, SessionWatermarks>()

        init {
            seed.forEach { rows[it.seq] = it }
            marks["s1"] = SessionWatermarks(localAcked = LocalAckedSeq(seed.maxOfOrNull { it.seq } ?: 0))
        }

        override suspend fun watermark(sessionId: String): SessionWatermarks =
            marks[sessionId] ?: SessionWatermarks()

        override suspend fun commit(
            sessionId: String,
            records: List<TimelineRecord>,
            watermarks: SessionWatermarks,
        ): Int {
            var written = 0
            records.forEach { if (rows.put(it.seq, it) == null) written++ }
            marks[sessionId] = watermarks
            return written
        }

        override suspend fun tail(sessionId: String, limit: Int): List<TimelineRecord> =
            rows.values.sortedBy { it.seq }.takeLast(limit)

        val betweenCalls = mutableListOf<Pair<Long, Long>>()

        override suspend fun between(
            sessionId: String,
            sinceSeq: Long,
            beforeSeq: Long,
        ): List<TimelineRecord> {
            betweenCalls += sinceSeq to beforeSeq
            return rows.values.filter { it.seq >= sinceSeq && it.seq < beforeSeq }.sortedBy { it.seq }
        }

        override suspend fun earlier(sessionId: String, beforeSeq: Long, limit: Int): List<TimelineRecord> =
            rows.values.filter { it.seq < beforeSeq }.sortedBy { it.seq }.takeLast(limit)

        var deleted = mutableListOf<Long>()

        override suspend fun deleteRecord(sessionId: String, seq: Long): Boolean =
            rows.remove(seq) != null

        // 必须跟真实现走同一条判据（`hasBody()`），否则按「取最后一条
        // ASSISTANT_TEXT」的老语义写会假绿 —— 真实现会跳过纯思考记录。
        override suspend fun latestAssistant(sessionId: String): TimelineRecord? =
            rows.values
                .filter { it.kind == RecordKind.ASSISTANT_TEXT && it.hasBody() }
                .maxByOrNull { it.seq }

        override suspend fun pendingStubs(sessionId: String, limit: Int): List<TimelineRecord> =
            rows.values.filter { it.isOversizedStub }.sortedBy { it.seq }.take(limit)

        override suspend fun fillStub(sessionId: String, seq: Long, payload: String): Int {
            val row = rows[seq] ?: return 0
            if (!row.isOversizedStub) return 0
            rows[seq] = row.copy(payloadJson = payload, isOversizedStub = false, blobId = null)
            return 1
        }
    }

    /** 假网络：按预设队列回页，并允许测试直接喂帧。 */
    private class FakeTransport : ChatSyncActor.ChatTransport {
        data class Call(val afterSeq: Long)

        val syncCalls = ArrayList<Call>()
        val focuses = ArrayList<Long>()
        val pages = ArrayDeque<RelayApiV2.SyncPage>()
        private var feed: RelayApiV2.ControlFeed? = null

        override suspend fun sync(
            hostId: String,
            sessionId: String,
            afterSeq: LocalAckedSeq,
            priority: Int,
            pageByteBudget: Int,
        ): ChatSyncActor.SyncOutcome {
            syncCalls.add(Call(afterSeq.value))
            val page = pages.removeFirstOrNull()
                ?: return ChatSyncActor.SyncOutcome.Page(
                    RelayApiV2.SyncPage(sessionId, afterSeq.value, afterSeq.value, afterSeq.value, false, "TAIL_COMPLETE", emptyList(), 0, 0)
                )
            return ChatSyncActor.SyncOutcome.Page(page)
        }

        override fun openControl(hostId: String, sessionId: String, sinceSeq: LocalAckedSeq, feed: RelayApiV2.ControlFeed) {
            this.feed = feed
            focuses.add(sinceSeq.value)
        }

        override fun focus(sessionId: String, sinceSeq: LocalAckedSeq): Boolean {
            focuses.add(sinceSeq.value)
            return true
        }

        /** 发出的写操作（cmdId、op、payload），按顺序记；测试据此断言幂等与载荷。 */
        val commands = ArrayList<Triple<String, String, JsonObject>>()

        /** false 模拟「控制流还没连上」。 */
        var commandAccepted = true

        override fun command(cmdId: String, op: String, payload: JsonObject): Boolean {
            if (!commandAccepted) return false
            commands.add(Triple(cmdId, op, payload))
            return true
        }

        fun ack(cmdId: String, ok: Boolean, error: String = "") {
            feed?.ack(RelayApiV2.CommandAck(cmdId, ok, error))
        }

        override fun closeControl() {
            feed = null
        }

        /** 假 blob 仓库：`blobId → 正文`。缺的 key 模拟取不到（404 / 断网）。 */
        val blobs = HashMap<String, String>()
        val blobCalls = ArrayList<String>()

        override suspend fun blob(hostId: String, blobId: String): String? {
            blobCalls.add(blobId)
            return blobs[blobId]
        }

        fun push(actor: ChatSyncActor, records: List<TimelineRecord>) {
            feed?.records(RelayApiV2.RecordsFrame("s1", records, false, "TAIL_COMPLETE"))
        }

        fun pushRaw(actor: ChatSyncActor, record: TimelineRecord) {
            feed?.records(RelayApiV2.RecordsFrame("s1", listOf(record), false, "TAIL_COMPLETE"))
        }

        fun pushFrame(actor: ChatSyncActor, sessionId: String, records: List<TimelineRecord>) {
            feed?.records(RelayApiV2.RecordsFrame(sessionId, records, false, "TAIL_COMPLETE"))
        }

        fun pushPulse(actor: ChatSyncActor, latest: Long, covered: Long, state: String, now: Long) {
            feed?.pulse(
                RelayApiV2.SessionPulse("s1", latest, covered, state, silentDurationMs = 0, tunnelBufferBytes = 0, workerEpoch = 1)
            )
        }
    }

    /** 在回复之前执行一次钩子的传输：用于制造「飞行中又被触发」。 */
    private class SlowTransport : ChatSyncActor.ChatTransport {
        val syncCalls = ArrayList<Long>()
        var beforeReply: (() -> Unit)? = null
        private var feed: RelayApiV2.ControlFeed? = null

        override suspend fun sync(
            hostId: String,
            sessionId: String,
            afterSeq: LocalAckedSeq,
            priority: Int,
            pageByteBudget: Int,
        ): ChatSyncActor.SyncOutcome {
            syncCalls.add(afterSeq.value)
            beforeReply?.invoke()
            beforeReply = null
            return ChatSyncActor.SyncOutcome.Page(
                RelayApiV2.SyncPage(sessionId, afterSeq.value, afterSeq.value, afterSeq.value, false, "TAIL_COMPLETE", emptyList(), 0, 0)
            )
        }

        override fun openControl(hostId: String, sessionId: String, sinceSeq: LocalAckedSeq, feed: RelayApiV2.ControlFeed) {
            this.feed = feed
        }

        override fun focus(sessionId: String, sinceSeq: LocalAckedSeq): Boolean = true
        override fun closeControl() { feed = null }


        fun pushRaw(actor: ChatSyncActor, record: TimelineRecord) {
            feed?.records(RelayApiV2.RecordsFrame("s1", listOf(record), false, "TAIL_COMPLETE"))
        }
    }

    /** 只是为了让 `start()` 的调用点读起来是个动作。 */
    private class Chatter(private val actor: ChatSyncActor) {
        suspend fun start() = actor.start()
    }

    // ============================================================
    // 存根补齐（验收 #6：超大的一条要么能下、要么明确报错）
    // ============================================================

    @Test
    fun `an oversized stub is filled in place without moving the watermark`() = runBlocking {
        val stub = record(1).copy(
            isOversizedStub = true,
            blobId = "s1:1",
            blobSize = 400_000,
            payloadJson = """{"type":"stub/payload","blobId":"s1:1"}""",
        )
        val store = FakeStore(listOf(stub))
        val transport = FakeTransport()
        transport.blobs["s1:1"] = """{"type":"assistant/message","data":{"text":"真正的正文"}}"""
        val actor = ChatSyncActor("s1", "h1", store, transport, clock = { 10_000 })
        actor.start()

        val before = actor.watermarks.localAcked.value
        actor.tick()

        val filled = store.rows[1]!!
        assertFalse(filled.isOversizedStub)
        assertTrue(filled.payloadJson.contains("真正的正文"))
        // 补正文是就地 UPDATE，与「拉到了第几条」无关：水位必须一动不动，
        // 否则「正文补齐了但新的还没拉」会被误判成已经追平。
        assertEquals(before, actor.watermarks.localAcked.value)
        assertTrue(transport.blobCalls.contains("s1:1"))
    }

    @Test
    fun `a blob that cannot be fetched leaves the stub in place and is retried`() = runBlocking {
        val stub = record(1).copy(isOversizedStub = true, blobId = "s1:1", blobSize = 400_000)
        val store = FakeStore(listOf(stub))
        val transport = FakeTransport() // 仓库是空的：取不到
        var now = 10_000L
        val actor = ChatSyncActor("s1", "h1", store, transport, clock = { now })
        actor.start()

        actor.tick()
        assertTrue("取不到时存根必须留着", store.rows[1]!!.isOversizedStub)

        // 限速：3 秒内的下一次 tick 不再重试（存根动辄几百 KiB，逐秒重试会打出流量）。
        val callsAfterFirst = transport.blobCalls.size
        actor.tick()
        assertEquals(callsAfterFirst, transport.blobCalls.size)

        // 过了间隔、且这次拿得到：补上。
        now += 5_000
        transport.blobs["s1:1"] = """{"data":{"text":"补上了"}}"""
        actor.tick()
        assertFalse(store.rows[1]!!.isOversizedStub)
        assertTrue(store.rows[1]!!.payloadJson.contains("补上了"))
    }
    // ---- 翻历史：只在本地，不发请求 -------------------------------------

    @Test
    fun `loadEarlier reads older rows from the local table and stops at the beginning`() = runBlocking {
        val seeded = (1..120L).map { record(it) }
        val store = FakeStore(seeded)
        val transport = FakeTransport()
        val actor = ChatSyncActor("s1", "h1", store, transport)
        actor.start()

        // 尾部只读 200，这里 120 条全在窗口里，所以一开始就「没有更早的」。
        assertEquals(120, actor.records.size)
        assertFalse(actor.hasEarlier)

        // 把窗口缩小到 10 条：模拟一个特别长的会话（本地有 2000 条，只读尾部）。
        val long = (1..300L).map { record(it) }
        val longStore = FakeStore(long)
        val big = ChatSyncActor("s1", "h1", longStore, transport)
        big.start()
        // 300 条全在内存里（tail 200 只作用于 FakeStore 的 takeLast，这里确实取了
        // 尾部 200 条，最老一条是 101）。
        assertEquals(101L, big.records.first().seq)
        assertTrue(big.hasEarlier)

        val added = big.loadEarlier(pageSize = 50)
        assertEquals(50, added)
        assertEquals(51L, big.records.first().seq)
        assertTrue(big.hasEarlier)

        val rest = big.loadEarlier(pageSize = 50)
        assertEquals(50, rest)
        assertEquals(1L, big.records.first().seq)
        assertFalse(big.hasEarlier)

        // 到底之后再翻是空转，且明确报告到底了。
        assertEquals(0, big.loadEarlier(pageSize = 50))
        assertFalse(big.hasEarlier)
    }

    @Test
    fun `loadEarlier never asks the relay and never moves the watermark`() = runBlocking {
        val seeded = (1..300L).map { record(it) }
        val store = FakeStore(seeded)
        val transport = FakeTransport()
        val actor = ChatSyncActor("s1", "h1", store, transport)
        actor.start()
        val callsBefore = transport.syncCalls
        val markBefore = actor.watermarks.localAcked.value

        actor.loadEarlier(pageSize = 50)

        assertEquals(callsBefore, transport.syncCalls)
        assertEquals(markBefore, actor.watermarks.localAcked.value)
        assertEquals(0, transport.blobCalls.size)
    }

    // ============================================================
    // 写操作：走控制流的 CMD，而不是中继直打宿主的 rpc
    // ============================================================

    @Test
    fun `a command carries one cmdId all the way to its ack`() = runBlocking {
        val store = FakeStore(emptyList())
        val transport = FakeTransport()
        val actor = ChatSyncActor("s1", "h1", store, transport)
        actor.start()

        var result: Pair<Boolean, String?>? = null
        actor.command("prompt", JsonObject(mapOf("sessionId" to JsonPrimitive("s1")))) { ok, error ->
            result = ok to error
        }

        assertEquals(1, transport.commands.size)
        val (cmdId, op, _) = transport.commands.single()
        assertEquals("prompt", op)
        assertTrue(actor.awaitingAck())
        assertNull(result)

        transport.ack(cmdId, ok = true)

        assertEquals(true to null, result)
        assertFalse(actor.awaitingAck())
        // ack 收到就该收口，**不留副本**：早先有个 `pendingAcks` 只写不清，唯一的
        // 读者又没有调用点，等于每发一条写操作就永久泄漏一个条目。
        assertFalse("收到 ack 之后不该还挂着", actor.awaitingAck())
    }

    @Test
    fun `a command sent before the control stream is up fails instead of hanging`() = runBlocking {
        val transport = FakeTransport().apply { commandAccepted = false }
        val actor = ChatSyncActor("s1", "h1", FakeStore(emptyList()), transport)
        actor.start()

        var result: Pair<Boolean, String?>? = null
        actor.command("prompt", JsonObject(emptyMap())) { ok, error -> result = ok to error }

        assertEquals(false to "not-connected", result)
        assertFalse(actor.awaitingAck())
        assertEquals(0, transport.commands.size)
    }

    @Test
    fun `an un-acked command is reported overdue once the race window passes`() = runBlocking {
        var now = 1_000L
        val transport = FakeTransport()
        val actor = ChatSyncActor("s1", "h1", FakeStore(emptyList()), transport, clock = { now })
        actor.start()

        actor.command("prompt", JsonObject(emptyMap())) { _, _ -> }
        assertEquals(0, actor.overdueAcks())

        now += ChatSyncActor.ACK_RACE_MS
        assertEquals(1, actor.overdueAcks())
    }

    @Test
    fun `an unanswered command is swept into a failure instead of hanging forever`() = runBlocking {
        // 事故形状：命令发出去了，插件没有回 ack。原来的实现只把它留在 writable 表里
        // 等一个可能永远不来的帧，于是界面一直显示「停止」，而宿主什么都没收到。
        val store = FakeStore(emptyList())
        val transport = FakeTransport()
        var now = 1_000L
        val actor = ChatSyncActor("s1", "h1", store, transport, clock = { now })
        actor.start()
        var verdict: Pair<Boolean, String?>? = null
        actor.command("prompt", buildJsonObject { put("sessionId", "s1") }) { ok, error -> verdict = ok to error }

        assertTrue(actor.awaitingAck())
        // 差 1 毫秒都不收口：竞速窗口是硬的，提前收口会把「慢」当成「丢」。
        assertEquals(0, actor.sweepAcks(now + 2_999L))
        assertNull(verdict)

        now += 3_000L
        assertEquals(1, actor.sweepAcks(now))
        assertEquals(false to "ack-timeout", verdict)
        assertFalse(actor.awaitingAck())
        assertEquals(0, actor.overdueAcks(now))
    }

    @Test
    fun `an ack that arrives before the sweep still wins`() = runBlocking {
        val store = FakeStore(emptyList())
        val transport = FakeTransport()
        var now = 1_000L
        val actor = ChatSyncActor("s1", "h1", store, transport, clock = { now })
        actor.start()
        var verdict: Pair<Boolean, String?>? = null
        actor.command("prompt", buildJsonObject { put("sessionId", "s1") }) { ok, error -> verdict = ok to error }
        val cmdId = transport.commands.single().first

        transport.ack(cmdId, true)
        assertEquals(true to null, verdict)
        assertEquals(0, actor.sweepAcks(now + 10_000L))
        assertEquals(true to null, verdict)
    }

    @Test
    fun `a duplicate ack for the same cmdId runs the callback once`() = runBlocking {
        val transport = FakeTransport()
        val actor = ChatSyncActor("s1", "h1", FakeStore(emptyList()), transport)
        actor.start()

        var calls = 0
        actor.command("prompt", JsonObject(emptyMap())) { _, _ -> calls++ }
        val cmdId = transport.commands.single().first

        transport.ack(cmdId, ok = true)
        transport.ack(cmdId, ok = true)

        assertEquals(1, calls)
    }

    @Test
    fun `a prompt with the long race window is not swept while the plugin loopback is still running`() = runBlocking {
        // 事故形状：插件打宿主的 loopback 有 20 秒超时，宿主忙时 prompt 的 ack
        // 晚到十几秒。3 秒就收口成 ack-timeout 会让调用方回落 v1 重发，而宿主的
        // requestId 幂等在「查重」与「写入 inbox」之间有 await 间隙——回落的
        // 那份落进间隙就真正执行两次，手机上「发一次，排队 2 条」（2026-10-31
        // 定案）。prompt 的竞速窗必须盖过整个 loopback 时限。
        val store = FakeStore(emptyList())
        val transport = FakeTransport()
        var now = 1_000L
        val actor = ChatSyncActor("s1", "h1", store, transport, clock = { now })
        actor.start()
        var verdict: Pair<Boolean, String?>? = null
        actor.command("prompt", buildJsonObject { put("sessionId", "s1") }, raceMs = ChatSyncActor.PROMPT_ACK_RACE_MS) { ok, error -> verdict = ok to error }

        // 3 秒：普通命令的竞速点——prompt 长窗绝不能在这里收口。
        assertEquals(0, actor.sweepAcks(now + ChatSyncActor.ACK_RACE_MS))
        assertNull(verdict)
        assertTrue(actor.awaitingAck())

        // 20 秒：插件 loopback 的超时点——还是不能收口（超时误差、转发排队都会再拖一会儿）。
        assertEquals(0, actor.sweepAcks(now + 20_000L))
        assertNull(verdict)
        assertEquals(0, actor.overdueAcks(now + 20_000L))

        // 25 秒：长窗到点，正常收口成 ack-timeout（调用方此时才允许等/提示）。
        assertEquals(1, actor.sweepAcks(now + ChatSyncActor.PROMPT_ACK_RACE_MS))
        assertEquals(false to "ack-timeout", verdict)
        assertFalse(actor.awaitingAck())
    }

    @Test
    fun `a slow prompt ack still wins inside the long race window`() = runBlocking {
        // 与「3 秒内 ack 先到」对称：插件 loopback 跑了十几秒后 ack 成功，长窗
        // 必须还留得住这条命令——晚到的成功不该被提前收口吞掉。
        val transport = FakeTransport()
        var now = 1_000L
        val actor = ChatSyncActor("s1", "h1", FakeStore(emptyList()), transport, clock = { now })
        actor.start()
        var verdict: Pair<Boolean, String?>? = null
        actor.command("prompt", buildJsonObject { put("sessionId", "s1") }, raceMs = ChatSyncActor.PROMPT_ACK_RACE_MS) { ok, error -> verdict = ok to error }
        val cmdId = transport.commands.single().first

        now += 18_000L
        transport.ack(cmdId, ok = true)

        assertEquals(true to null, verdict)
        assertEquals(0, actor.sweepAcks(now))
        assertFalse(actor.awaitingAck())
    }

    @Test
    fun `deleting a record drops it from the local tail without moving the watermark`() = runBlocking {
        // 长按气泡「本地删除」：删行、重读尾部、通知 UI。水位**不动**——退回去会让
        // 下一次同步把这条重新拉下来，用户删了又自己冒出来。
        val store = FakeStore(listOf(record(1), record(2), record(3)))
        var changes = 0
        val actor = ChatSyncActor("s1", "h1", store, FakeTransport(), clock = { 1_000L }, onChange = { changes++ })
        actor.start()
        val before = store.watermark("s1").localAcked.value

        assertEquals(true, actor.deleteLocal(2))

        assertEquals(listOf(1L, 3L), actor.records.map { it.seq })
        assertEquals(before, store.watermark("s1").localAcked.value)
        assertEquals(1, changes)
    }

    @Test
    fun `deleting a record that is not there reports false and changes nothing`() = runBlocking {
        val store = FakeStore(listOf(record(1)))
        var changes = 0
        val actor = ChatSyncActor("s1", "h1", store, FakeTransport(), clock = { 1_000L }, onChange = { changes++ })
        actor.start()

        assertEquals(false, actor.deleteLocal(99))

        assertEquals(listOf(1L), actor.records.map { it.seq })
        assertEquals(0, changes)
    }

    @Test
    fun `a control stream that claims to be alive but cannot focus gets reopened`() = runBlocking {
        // 2026-11-01 真机事故（0.2.34）：聊天页不再出新内容，返回列表再进会话立刻恢复。
        // 现场是手机侧没有任何到中继的 TCP 连接，而 `recover()` 一直走「连接仍在、
        // 只重挂聚焦」那条分支——因为 `controlAlive()` 只看 `open && socket != null`，
        // 而 OkHttp 的 socket 被 NAT 静默丢弃时回调不一定到，`open` 一直是 true；
        // `WebSocket.send()` 在死连接上又不抛异常，`focus()` 照样"成功"。
        // 于是每 12 秒"自救"一次，一个字节都没出去，永远修不好。
        val store = FakeStore(listOf(record(1)))
        val transport = ZombieTransport()
        var now = 1_000L
        val actor = ChatSyncActor("s1", "h1", store, transport, clock = { now })
        actor.start()

        // 先让它认为自己在跑，然后推进到判死窗口之后。
        now += SyncCoordinator.PULSE_DEAD_MS + 1_000L
        actor.tick()

        // 关键断言：既然聚焦发不出去，就必须重开 socket（openControl 计数 +1）。
        assertTrue(
            "聚焦失败必须重开 socket，实际 openControl=${transport.opens} focus=${transport.focuses}",
            transport.opens >= 2,
        )
    }

    /**
     * 一条「自称在线但发不出任何东西」的控制流：`controlAlive()` 恒 true，
     * `focus()` 恒 false——正是 OkHttp 死连接的表现。
     */
    private class ZombieTransport(private val focusWorks: Boolean = false) : ChatSyncActor.ChatTransport {
        var opens = 0
        var focuses = 0

        override suspend fun sync(
            hostId: String,
            sessionId: String,
            afterSeq: LocalAckedSeq,
            priority: Int,
            pageByteBudget: Int,
        ): ChatSyncActor.SyncOutcome =
            ChatSyncActor.SyncOutcome.Page(
                RelayApiV2.SyncPage(
                    sessionId, afterSeq.value, afterSeq.value, afterSeq.value,
                    false, "TAIL_COMPLETE", emptyList(), 0, 0,
                )
            )

        override fun openControl(hostId: String, sessionId: String, sinceSeq: LocalAckedSeq, feed: RelayApiV2.ControlFeed) {
            opens++
        }

        override fun controlAlive(): Boolean = true
        override fun focus(sessionId: String, sinceSeq: LocalAckedSeq): Boolean {
            focuses++
            return focusWorks
        }

        override fun closeControl() {}
    }


    @Test
    fun `repeated recoveries with a zombie socket eventually force a reopen`() = runBlocking {
        // OkHttp 的 `WebSocket.send()` 在死连接上不抛异常，`focus()` 会一直返回 true。
        // 只信返回值的话，「自称在线」的僵尸 socket 永远走不到重开那一步——这正是
        // 2026-11-01 真机「聊天页卡住、退出去再进又好了」的成因。
        // 判据：连着自救两轮、脉搏一个都没回来，就不再信 `controlAlive()`。
        val store = FakeStore(listOf(record(1)))
        val transport = ZombieTransport(focusWorks = true)
        var now = 1_000L
        val actor = ChatSyncActor("s1", "h1", store, transport, clock = { now })
        actor.start()
        val opensAfterStart = transport.opens

        // 两轮自救，中间不给任何脉搏。
        repeat(2) {
            now += SyncCoordinator.PULSE_DEAD_MS + 60_000L
            actor.tick()
        }

        assertTrue(
            "连续自救后必须强制重开（openControl ${opensAfterStart} -> ${transport.opens}）",
            transport.opens > opensAfterStart,
        )
    }


    @Test
    fun `a zombie control stream is reopened on the second window, not the third`() = runBlocking {
        // 恢复时间必须是 **24 秒**（两次 12 秒窗口），不是 36 秒。
        // 指数宽限（12→24→48）是为「重开也失败」准备的；僵尸连接这一支连重开都还没
        // 试过，跟着翻倍只会让用户多盯 12 秒黑屏。
        val store = FakeStore(listOf(record(1)))
        val transport = ZombieTransport(focusWorks = true)
        var now = 1_000L
        val actor = ChatSyncActor("s1", "h1", store, transport, clock = { now })
        actor.start()
        val opensAfterStart = transport.opens

        // 第 1 个窗口末：第 1 次自救（走「自称在线」那条，focus 假成功）。
        now += SyncCoordinator.PULSE_DEAD_MS
        actor.tick()
        assertEquals("第 1 次自救不该重开", opensAfterStart, transport.opens)

        // 第 2 个窗口末（再 12 秒，共 24 秒）：必须已经强制重开。
        now += SyncCoordinator.PULSE_DEAD_MS
        actor.tick()
        assertTrue(
            "24 秒时必须已经重开（实际 openControl=${transport.opens}）",
            transport.opens > opensAfterStart,
        )
    }

    // ============================================================
    // 并发：主线程写 overlay / IO 线程遍历，不许炸
    // ============================================================

    @Test
    fun `concurrent writes to the overlay never break a reader`() = runBlocking {
        // 事故形态：`overlay` 是 `LinkedHashMap`。写方在主线程（`api.post{}` →
        // `mainHandler.post` → `feed.records`），读/删方在 `Dispatchers.IO`
        // （`tick()` 的 `dropOverlayUpTo`、`renderActor` 取快照）。流式 + 补差本来
        // 就是常态并行，并发改+遍历会抛 `ConcurrentModificationException`；渲染协程
        // 里没有兜底，一次异常就把整条 tick 打死（界面永久静默）。
        //
        // 判据不是「跑得对」，而是「并发跑也不抛」：让一个线程持续塞、另一个持续
        // 遍历并调用 `overlay()` 取快照。
        val store = FakeStore(emptyList())
        val transport = FakeTransport()
        val actor = ChatSyncActor("s1", "h1", store, transport, clock = { 1_000L })

        val stop = AtomicBoolean(false)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())

        val writer = Thread {
            var seq = 100L
            while (!stop.get()) {
                actor.overlay[seq] = record(seq)
                seq++
                if (seq > 5_000L) { actor.overlay.clear(); seq = 100L }
            }
        }
        val reader = Thread {
            while (!stop.get()) {
                try {
                    // 投影器读的就是这个快照；同时遍历一次活容器模拟 `dropOverlayUpTo`。
                    val snap = actor.overlay()
                    snap.uncommitted.size
                    for (entry in actor.overlay.entries) entry.key
                } catch (e: Throwable) {
                    failures.add(e)
                }
            }
        }
        writer.start(); reader.start()
        Thread.sleep(300)
        stop.set(true)
        writer.join(); reader.join()

        assertTrue(
            "并发读写 overlay 抛了 ${failures.size} 次：${failures.firstOrNull()}",
            failures.isEmpty(),
        )
        // 快照必须是**真拷贝**：拿到之后写方继续改，不能再影响已交出去的视图。
        val snapshot = actor.overlay()
        val before = snapshot.uncommitted.size
        actor.overlay[9_999L] = record(9_999L)
        assertEquals("overlay() 必须返回快照，不是活引用", before, snapshot.uncommitted.size)
    }

    @Test
    fun `concurrent ack and sweep never break the pending writes map`() = runBlocking {
        // 同一簇：`feed.ack()` 在主线程 `writes.remove`，`sweepAcks()` 在 tick 的 IO
        // 线程上遍历 + `remove`。LinkedHashMap 下这两条路径互相踩。
        val store = FakeStore(emptyList())
        val transport = FakeTransport()
        val actor = ChatSyncActor("s1", "h1", store, transport, clock = { 1_000L })
        actor.start() // 装好控制流，transport.feed 才有落点

        val stop = AtomicBoolean(false)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val acker = Thread {
            while (!stop.get()) {
                actor.command("prompt", JsonObject(emptyMap())) { _, _ -> }
                // cmdId 由 actor 自己生成，只能「发一条、再 ack 所有还在途的」。
                transport.commands.map { it.first }.forEach { transport.ack(it, true) }
            }
        }
        val sweeper = Thread {
            while (!stop.get()) {
                try { actor.sweepAcks(2_000L) } catch (e: Throwable) { failures.add(e) }
            }
        }
        acker.start(); sweeper.start()
        Thread.sleep(300)
        stop.set(true)
        acker.join(); sweeper.join()
        assertTrue("并发 ack + sweep 抛了：${failures.firstOrNull()}", failures.isEmpty())
    }

    // ============================================================
    // 存根补齐失败重试（评审 §六.2）
    // ============================================================

    @Test
    fun `a stub that cannot be fetched stays pending and is retried on the next tick`() = runBlocking {
        // 判据是「失败后存根还在、下次 tick 真的又去取了一次」——不是「取到之后没了」。
        // 早先只有后者，所以「拿不到就留在原地」这条设计其实没被测过：把 `continue`
        // 改成 `break`、或者失败时顺手清掉存根，测试一样全绿。
        var now = 1_000L
        val stub = record(2, kind = RecordKind.STEP_TOOL).copy(
            payloadJson = """{"type":"stub/payload","blobId":"s1:2","blobSize":999}""",
            isOversizedStub = true,
            blobId = "s1:2",
        )
        val store = FakeStore(listOf(record(1), stub))
        val transport = FakeTransport()
        val actor = ChatSyncActor("s1", "h1", store, transport, clock = { now })
        actor.start()

        // 第一次：blob 取不到（仓库里没有这个 id）
        now += 1_000
        actor.tick()
        assertEquals("失败也要真的去取过", listOf("s1:2"), transport.blobCalls)
        assertEquals("取不到就必须留在原地", 1, store.pendingStubs("s1", 10).size)

        // 第二次：仓库里有货了，同一个 tick 路径必须再试一次并补上
        transport.blobs["s1:2"] = """{"type":"tool/result","data":{"text":"真正的正文"}}"""
        now += 1_000
        actor.tick()
        assertEquals("失败过的存根必须在下一个 tick 重试", listOf("s1:2", "s1:2"), transport.blobCalls)
        assertEquals("补上之后就不再是存根", 0, store.pendingStubs("s1", 10).size)
    }

    @Test
    fun `stub fill is rate limited so one tick cannot fetch a whole batch`() = runBlocking {
        // 限速是设计的一部分（蜂窝网上连打十个 256KiB 请求会把用户正在读的那屏挤掉），
        // 但它此前只写在注释里：把 blobGapMs 改成 0、blobBatch 改成 100，测试全绿。
        var now = 1_000L
        val stubs = (2L..11L).map {
            record(it, kind = RecordKind.STEP_TOOL).copy(
                payloadJson = """{"type":"stub/payload","blobId":"s1:$it","blobSize":999}""",
                isOversizedStub = true,
                blobId = "s1:$it",
            )
        }
        val store = FakeStore(listOf(record(1)) + stubs)
        val transport = FakeTransport()
        stubs.forEach { transport.blobs[it.blobId!!] = """{"type":"tool/result","data":{"text":"x"}}""" }
        val actor = ChatSyncActor("s1", "h1", store, transport, clock = { now })
        actor.start()

        // 同一个时刻连 tick 三次：只允许第一批出去
        now += 1_000
        actor.tick()
        val first = transport.blobCalls.size
        assertTrue("一次 tick 不能把十条存根全取回来（实际取了 $first 条）", first <= 3)

        actor.tick()
        actor.tick()
        assertEquals("同一毫秒内不许再取：间隔没到就该直接返回", first, transport.blobCalls.size)

        // 隔一个间隔之后允许下一批
        now += 1_000
        actor.tick()
        assertTrue("间隔到了必须继续补，否则存根永远补不完", transport.blobCalls.size > first)
    }

}
