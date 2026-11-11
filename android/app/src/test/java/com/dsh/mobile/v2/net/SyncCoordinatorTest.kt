package com.dsh.mobile.v2.net

import com.dsh.mobile.v2.data.LocalAckedSeq
import com.dsh.mobile.v2.data.RecordKind
import com.dsh.mobile.v2.data.TimelineRecord
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同步协调器的边界（设计 §invariant 1/6/8/9/10）。
 *
 * 每条断言对应一个真机上会「看起来只是偶尔少一段消息」的失败模式，所以测试名
 * 写的是症状而不是实现。
 */
class SyncCoordinatorTest {

    // ============================================================
    // 单飞（§invariant 6）
    // ============================================================

    @Test
    fun `a second trigger while a request is in flight only sets a dirty flag`() {
        val slot = SyncCoordinator.decideRun(inFlight = true, pending = false, cooldownUntil = 0, now = 1_000)
        assertFalse("在途时绝不能并发第二条请求", slot.run)
        assertTrue("被折叠的触发要留痕，落地后补跑一轮", slot.dirty)
    }

    @Test
    fun `a request runs when nothing is in flight and no cooldown is active`() {
        val slot = SyncCoordinator.decideRun(inFlight = false, pending = false, cooldownUntil = 0, now = 1_000)
        assertTrue(slot.run)
        assertFalse(slot.dirty)
    }

    @Test
    fun `a session in cooldown does not run but keeps the pending trigger`() {
        val slot = SyncCoordinator.decideRun(inFlight = false, pending = true, cooldownUntil = 9_000, now = 1_000)
        assertFalse("冷却期内不许发请求，否则单点卡死会扩散成 CPU 风暴", slot.run)
        assertTrue("脏标记不能丢，冷却结束还要补一次", slot.dirty)
    }

    // ============================================================
    // 退避（§invariant 9）
    // ============================================================

    @Test
    fun `backoff doubles from one second and stops at thirty`() {
        assertEquals(1_000L, SyncCoordinator.backoffMs(0))
        assertEquals(2_000L, SyncCoordinator.backoffMs(1))
        assertEquals(4_000L, SyncCoordinator.backoffMs(2))
        assertEquals(8_000L, SyncCoordinator.backoffMs(3))
        assertEquals(16_000L, SyncCoordinator.backoffMs(4))
        assertEquals(30_000L, SyncCoordinator.backoffMs(5))
        assertEquals("上限是硬的：卡死不该让用户等十分钟", 30_000L, SyncCoordinator.backoffMs(20))
    }

    @Test
    fun `backoff jitter stays inside plus or minus twenty percent`() {
        assertEquals(800L, SyncCoordinator.backoffMs(0, jitter = 0.5))
        assertEquals(1_200L, SyncCoordinator.backoffMs(0, jitter = 5.0))
        assertEquals("抖动后的上限仍是 30 秒", 36_000L, SyncCoordinator.backoffMs(9, jitter = 1.2))
    }

    @Test
    fun `three consecutive failures cool the session down for a minute`() {
        assertEquals(0L, SyncCoordinator.cooldownMs(0))
        assertEquals(0L, SyncCoordinator.cooldownMs(2))
        assertEquals(60_000L, SyncCoordinator.cooldownMs(3))
        assertEquals(60_000L, SyncCoordinator.cooldownMs(9))
    }

    @Test
    fun `the failure tracker cools down on the third failure and clears on success`() {
        var now = 10_000L
        val tracker = SyncCoordinator.FailureTracker { now }
        assertEquals(1_000L, tracker.noteFailure("s1"))
        assertEquals(2_000L, tracker.noteFailure("s1"))
        assertEquals("前两次只是退避，还没到冷却门槛", 0L, tracker.cooldownUntil("s1"))
        assertEquals(4_000L, tracker.noteFailure("s1"))
        assertEquals(70_000L, tracker.cooldownUntil("s1"))
        assertEquals(3, tracker.failuresOf("s1"))
        tracker.noteSuccess("s1")
        assertEquals("成功一次就该清零，否则偶发抖动会累积成永久冷却", 0L, tracker.cooldownUntil("s1"))
        assertEquals(0, tracker.failuresOf("s1"))
    }

    @Test
    fun `stalls and failures are counted separately`() {
        // 事故形态：`consecutiveZeros()` 读的是 `failures` —— 名字说有零进展记账，
        // 实际一次都没记。将来有人照着名字接退避，接到的会是「HTTP 失败数」，
        // 而真正的「成功但原地打转」永远不触发任何退避。
        var now = 10_000L
        val tracker = SyncCoordinator.FailureTracker { now }

        // 三次原地打转：请求都「成功」了，但一页都没往前走。
        tracker.noteSuccess("s1") // 请求成功 ≠ 有进展
        assertEquals(1_000L, tracker.noteStall("s1"))
        assertEquals("零进展不能记成 HTTP 失败", 0, tracker.failuresOf("s1"))
        assertEquals(1, tracker.stallRounds("s1"))

        tracker.noteStall("s1")
        assertEquals("前两次只是退避", 0L, tracker.cooldownUntil("s1"))
        tracker.noteStall("s1")
        assertEquals("连续三次原地打转必须冷却，否则就是客户端 hot loop", 70_000L, tracker.cooldownUntil("s1"))
        assertEquals(3, tracker.stallRounds("s1"))

        // 真的往前走了：零进展计数清零，冷却解除。
        tracker.noteProgress("s1")
        assertEquals(0, tracker.stallRounds("s1"))
        assertEquals(0L, tracker.cooldownUntil("s1"))
    }

    @Test
    fun `a stall does not clear the failure streak and vice versa`() {
        // 两个计数器各自清零，互不掩盖：否则「5xx 三次」会被中间一次成功请求抹掉。
        val tracker = SyncCoordinator.FailureTracker { 0L }
        tracker.noteFailure("s1")
        tracker.noteFailure("s1")
        tracker.noteStall("s1")
        assertEquals("零进展不该清掉失败计数", 2, tracker.failuresOf("s1"))
        assertEquals(1, tracker.stallRounds("s1"))
        tracker.noteProgress("s1")
        assertEquals("有进展不该清掉失败计数", 2, tracker.failuresOf("s1"))
    }

    @Test
    fun `failures on different sessions are counted separately`() {
        val tracker = SyncCoordinator.FailureTracker { 0L }
        repeat(3) { tracker.noteFailure("s1") }
        repeat(2) { tracker.noteFailure("s2") }
        assertEquals(60_000L, tracker.cooldownUntil("s1"))
        assertEquals("别的会话不该被一个卡死的会话连坐", 0L, tracker.cooldownUntil("s2"))
    }

    // ============================================================
    // 半死判定（§invariant 1）
    // ============================================================

    @Test
    fun `a running session with twelve silent seconds is half dead`() {
        assertTrue(
            SyncCoordinator.halfDead(
                now = 20_000,
                lastPulseAt = 8_000,
                running = true,
                latestSeq = 100,
                localAcked = 100,
            )
        )
    }

    @Test
    fun `eleven silent seconds on a running session is not yet half dead`() {
        assertFalse(
            "丢一帧不能判死，否则正常抖动会触发无谓重放",
            SyncCoordinator.halfDead(
                now = 19_999,
                lastPulseAt = 8_000,
                running = true,
                latestSeq = 100,
                localAcked = 100,
            )
        )
    }

    @Test
    fun `a long silent reasoning step stays healthy as long as pulses arrive`() {
        // 这是 v1 卡 16 分钟的根因：把「没有字节」当成「链路死了」。
        assertFalse(
            SyncCoordinator.halfDead(
                now = 60_000,
                lastPulseAt = 58_000,
                running = true,
                latestSeq = 100,
                localAcked = 100,
            )
        )
    }

    @Test
    fun `an idle session with nothing owed is not half dead when pulses stop`() {
        assertFalse(
            "静态会话没有脉搏是正常的，插件不该为没人看的会话耗电",
            SyncCoordinator.halfDead(
                now = 100_000,
                lastPulseAt = 8_000,
                running = false,
                latestSeq = 100,
                localAcked = 100,
            )
        )
    }

    @Test
    fun `an idle session still owing records is half dead when pulses stop`() {
        assertTrue(
            "latestSeq 大于本地已确认却收不到脉搏 = 有东西下不来",
            SyncCoordinator.halfDead(
                now = 100_000,
                lastPulseAt = 8_000,
                running = false,
                latestSeq = 120,
                localAcked = 100,
            )
        )
    }

    @Test
    fun `before the first pulse arrives the session is not judged`() {
        assertFalse(
            "刚 focus 时还没开始计时，立刻判死会自伤",
            SyncCoordinator.halfDead(
                now = 100_000,
                lastPulseAt = 0,
                running = true,
                latestSeq = 120,
                localAcked = 100,
            )
        )
    }

    @Test
    fun `records arriving without pulses keep a busy session alive`() {
        // 2026-10-30 生产日志：`focus … replay=200`（约 500 KiB / 32 帧）之后紧跟
        // `tunnel credit stall 10s pending=12KiB buffered=0KiB`。插件的 MessageBridge
        // 把 records 与脉搏严格串行在同一条 channel 上，重放期间脉搏排在队尾十几秒。
        // 只认脉搏就会把「正在被 records 灌满」这条最健康的连接判死，于是重连→重放
        // →再堵，形成自我维持的循环。
        assertFalse(
            "两秒前刚收到 records，脉搏虽然 30 秒没来，链路明明是活的",
            SyncCoordinator.halfDead(
                now = 100_000,
                lastPulseAt = 70_000,
                running = true,
                latestSeq = 120,
                localAcked = 100,
                lastDataAt = 98_000,
            )
        )
    }

    @Test
    fun `records stop substituting for the pulse after two minutes`() {
        // 替身有期限。插件侧脉搏定时器停掉之后（探针 abort，见坑 54），records
        // 照旧一条条来，`lastDataAt` 永远是新的——没有期限的话，一个正在跑、
        // `latestSeq` 还在涨的会话会**永远**被判成健康的：`hostState` / `running`
        // 冻在最后一帧旧脉搏上，长推理的进度行不再走，界面上却看不出异常。
        assertTrue(
            "records 一直在到，但脉搏已经三分多钟没来了，那不是排队，是脉搏通道坏了",
            SyncCoordinator.halfDead(
                now = 200_000,
                lastPulseAt = 70_000,
                running = true,
                latestSeq = 120,
                localAcked = 100,
                lastDataAt = 199_000,
            )
        )
    }

    @Test
    fun `the substitute window stays open for a replay that outlasts one dead window`() {
        // 反向钉子：期限是**从脉搏算起**的两分钟，不是十二秒。
        // 也就是「脉搏 98 秒没来、records 一秒前刚到」仍然算活着——长重放叠上
        // 一次系统调度就可能这么久，这时判死会重连→重放→再堵。
        assertFalse(
            "期限是脉搏两百秒内，不是十二秒；records 还在到就别拽人",
            SyncCoordinator.halfDead(
                now = 158_000,
                lastPulseAt = 60_000,
                running = true,
                latestSeq = 120,
                localAcked = 100,
                lastDataAt = 157_000,
            )
        )
    }

    @Test
    fun `records stop arriving too so the session is still judged dead`() {
        assertTrue(
            "records 也断了 30 秒，那就真的没东西在流",
            SyncCoordinator.halfDead(
                now = 100_000,
                lastPulseAt = 70_000,
                running = true,
                latestSeq = 120,
                localAcked = 100,
                lastDataAt = 70_000,
            )
        )
    }

    @Test
    fun `a pulse alone still proves liveness when no records flow`() {
        assertFalse(
            "静态会话只有脉搏没有 records，脉搏本身就是到达证明",
            SyncCoordinator.halfDead(
                now = 100_000,
                lastPulseAt = 98_000,
                running = true,
                latestSeq = 120,
                localAcked = 100,
                lastDataAt = 70_000,
            )
        )
    }

    // ============================================================
    // 脉搏证据的新鲜度（坑 54 的另一半）
    // ============================================================

    @Test
    fun `a fresh pulse keeps the evidence alive`() {
        assertFalse(
            "两秒前刚收到脉搏，什么都不用做",
            SyncCoordinator.pulseEvidenceLost(
                now = 100_000,
                armedAt = 1_000,
                lastPulseAt = 98_000,
                lastDataAt = 0,
            )
        )
    }

    @Test
    fun `records arriving on time stand in for a late pulse`() {
        // 重放 500 KiB 时脉搏被压在队尾十几秒：这时判死是最坏的选择。
        assertFalse(
            "脉搏 30 秒没来，但两秒前刚收到 records",
            SyncCoordinator.pulseEvidenceLost(
                now = 100_000,
                armedAt = 1_000,
                lastPulseAt = 70_000,
                lastDataAt = 98_000,
            )
        )
    }

    @Test
    fun `records cannot stand in for a pulse forever`() {
        // 这是本组最重要的一条：records 一直很新鲜，但脉搏已经两分钟没来了。
        // 那不是排队，是脉搏通道自己坏了（插件侧探针 abort，见坑 54）。
        // 旧判据 `maxOf(lastPulseAt, lastDataAt) <= 0` 在这里永久为假，永远不判死。
        assertTrue(
            "records 再新鲜，也不能替一条两分钟没来的脉搏背书",
            SyncCoordinator.pulseEvidenceLost(
                now = 200_000,
                armedAt = 1_000,
                lastPulseAt = 70_000,
                lastDataAt = 199_000,
            )
        )
    }

    @Test
    fun `records cannot stand in for a pulse that never came after a repair`() {
        // 自救会把 lastPulseAt 归零。旧代码里这会让 halfDead() 第一行直接短路，
        // 于是「records 一直在到、脉搏一条不来」在自救一次之后再也不判死。
        // 新判据用 armedAt 兜住这个空档。
        assertTrue(
            "刚自救完、脉搏一条没来，records 也撑不了多久",
            SyncCoordinator.pulseEvidenceLost(
                now = 200_000,
                armedAt = 50_000,
                lastPulseAt = 0,
                lastDataAt = 199_000,
            )
        )
    }

    @Test
    fun `right after a repair the substitute window is still open`() {
        // 反向钉子：刚自救完的头两分钟，records 还在到就不该再自救第二次。
        assertFalse(
            "自救之后 10 秒、records 一直在流：这时候立刻再自救就是坑 54 那个循环",
            SyncCoordinator.pulseEvidenceLost(
                now = 60_000,
                armedAt = 50_000,
                lastPulseAt = 0,
                lastDataAt = 59_000,
            )
        )
    }

    @Test
    fun `pulses and records interleaved never lose the evidence`() {
        // 评审 §六.3 要求的那条：不是「只有脉搏」或「只有 records」的极端，而是两者
        // 交替到达的真实节奏。此前没有这条，`sincePulse` 用「最后一次脉搏」而不是
        // 「证据最后一次更新」时也能全绿 —— 那正是坑 54 里「自救一次就再也不判死」
        // 的同一个错误。
        val armed = 1_000L
        // 第 55 秒：一批 records 到（距上次脉搏 55 秒，已过替身期限的一半）
        assertFalse(
            "脉搏 55 秒没来但 records 刚到：还在替身窗口内，不该判死",
            SyncCoordinator.pulseEvidenceLost(
                now = 56_000,
                armedAt = armed,
                lastPulseAt = 1_000,
                lastDataAt = 55_000,
            )
        )
        // 第 110 秒：又来一条真脉搏
        assertFalse(
            "真脉搏刚到，证据必然新鲜",
            SyncCoordinator.pulseEvidenceLost(
                now = 110_000,
                armedAt = armed,
                lastPulseAt = 109_000,
                lastDataAt = 55_000,
            )
        )
        // 第 120 秒：脉搏又停了 11 秒（还在 PULSE_DEAD_MS 内），records 也没动
        assertFalse(
            "脉搏 11 秒前刚来过，还没到判死窗口",
            SyncCoordinator.pulseEvidenceLost(
                now = 120_000,
                armedAt = armed,
                lastPulseAt = 109_000,
                lastDataAt = 55_000,
            )
        )
    }

    @Test
    fun `stale records and no pulse at all lose the evidence`() {
        assertTrue(
            "既没有脉搏、records 也断了",
            SyncCoordinator.pulseEvidenceLost(
                now = 100_000,
                armedAt = 1_000,
                lastPulseAt = 0,
                lastDataAt = 70_000,
            )
        )
    }

    // ============================================================
    // 脉搏驱动的增量修复
    // ============================================================

    @Test
    fun `a pulse reporting a covered gap triggers one incremental replay`() {
        val act = SyncCoordinator.onPulse(
            localAcked = 100,
            pulseLatestSeq = 120,
            pulseCovered = 120,
            halfDeadAlready = false,
        )
        assertTrue("中继已经收下这 20 条，增量拉一次就够", act.replay)
        assertFalse("脉搏正常就不该重开控制流", act.focus)
    }

    @Test
    fun `a pulse whose gap is not archived yet does not replay`() {
        val act = SyncCoordinator.onPulse(
            localAcked = 100,
            pulseLatestSeq = 120,
            pulseCovered = 110,
            halfDeadAlready = false,
        )
        assertFalse("中继还没排空 outbox，这时候重连拿不到东西", act.replay)
    }

    @Test
    fun `a caught up session does nothing on pulse`() {
        val act = SyncCoordinator.onPulse(
            localAcked = 120,
            pulseLatestSeq = 120,
            pulseCovered = 120,
            halfDeadAlready = false,
        )
        assertFalse(act.replay)
        assertFalse(act.focus)
    }

    @Test
    fun `after a half death is declared the repair is throttled`() {
        val act = SyncCoordinator.onPulse(
            localAcked = 100,
            pulseLatestSeq = 120,
            pulseCovered = 120,
            halfDeadAlready = true,
        )
        assertTrue("已经重连过：这一轮只补数据", act.replay)
        assertFalse("同一个缺口上不许每 5 秒重连一次", act.focus)
    }

    // ============================================================
    // 水位推进（§invariant 2/4/8）
    // ============================================================

    @Test
    fun `the watermark advances to the next cursor when the page landed in full`() {
        val next = SyncCoordinator.advance(requestAfter = 100, nextCursor = 104, landed = 104)
        assertEquals(104L, next.value)
    }

    @Test
    fun `the watermark never passes what actually landed on disk`() {
        val next = SyncCoordinator.advance(requestAfter = 100, nextCursor = 200, landed = 104)
        assertEquals("中继说覆盖到 200，但本地只落盘到 104：水位只能到 104", 104L, next.value)
    }

    @Test
    fun `a stale cursor from the server never rolls the watermark back`() {
        val next = SyncCoordinator.advance(requestAfter = 100, nextCursor = 90, landed = 100)
        assertEquals("游标倒退会把已知进度抹掉，宁可不更新", 100L, next.value)
    }

    @Test
    fun `a page that landed nothing leaves the watermark untouched`() {
        val next = SyncCoordinator.advance(requestAfter = 100, nextCursor = 100, landed = 0)
        assertEquals(100L, next.value)
    }

    @Test
    fun `a stub-only page still moves the cursor forward`() {
        // 存根记录也是记录：它落盘了，就必须推进游标，否则下一页还是它。
        val next = SyncCoordinator.advance(requestAfter = 100, nextCursor = 140, landed = 140)
        assertEquals(140L, next.value)
    }

    // ============================================================
    // 继续/停止
    // ============================================================

    @Test
    fun `a page that says hasMore with a moved cursor continues immediately`() {
        val page = page(nextCursor = 140, hasMore = true)
        assertTrue(SyncCoordinator.continueAfter(page, requestAfter = 100))
    }

    @Test
    fun `hasMore with a frozen cursor stops instead of spinning`() {
        val page = page(nextCursor = 100, hasMore = true)
        assertFalse(
            "零进展还继续拉就是 tight loop：v1 的「26/29 次空响应」正是这样烧出来的",
            SyncCoordinator.continueAfter(page, requestAfter = 100)
        )
    }

    @Test
    fun `hasMore false stops even when the cursor moved`() {
        assertFalse(SyncCoordinator.continueAfter(page(nextCursor = 140, hasMore = false), 100))
    }

    @Test
    fun `no-pulse grace doubles on every failed self-heal`() {
        // 坑 54：固定 12 秒门限 + recover() 重置发条 ⇒ 每 12 秒自我重连一次。
        // 首次仍须是 12 秒（真故障要快速自救，不磨用户），之后每救一次翻倍。
        assertEquals(12_000L, SyncCoordinator.noPulseGraceMs(0))
        assertEquals(24_000L, SyncCoordinator.noPulseGraceMs(1))
        assertEquals(48_000L, SyncCoordinator.noPulseGraceMs(2))
        assertEquals(96_000L, SyncCoordinator.noPulseGraceMs(3))
    }

    @Test
    fun `no-pulse grace stops doubling at two minutes`() {
        // 不封顶的话第 6 次就到 12 分钟，真断线要等十几分钟才自救。
        assertEquals(120_000L, SyncCoordinator.noPulseGraceMs(4))
        assertEquals(120_000L, SyncCoordinator.noPulseGraceMs(9))
        assertEquals(120_000L, SyncCoordinator.noPulseGraceMs(1_000))
    }

    @Test
    fun `no-pulse grace treats a negative recovery count as a first attempt`() {
        // 负数只可能来自误用；按 0 处理，而不是给出 0ms（那会每 tick 都自救）。
        assertEquals(12_000L, SyncCoordinator.noPulseGraceMs(-1))
    }

    // ============================================================
    // 夹具
    // ============================================================

    private fun page(nextCursor: Long, hasMore: Boolean): RelayApiV2.SyncPage = RelayApiV2.SyncPage(
        sessionId = "s1",
        reqAfterSeq = 100,
        nextCursorSeq = nextCursor,
        archiveCoveredSeq = nextCursor,
        hasMore = hasMore,
        stopReason = if (hasMore) "BUDGET_EXHAUSTED" else "TAIL_COMPLETE",
        records = emptyList<TimelineRecord>(),
        bytes = 0,
        skipped = 0,
    )

    @Test
    fun `record parsing never invents a seq`() {
        assertTrue(RelayApiV2.recordOf(JsonNull) == null)
        val noSeq = buildJsonObject {
            put("sessionId", "s1")
            put("kind", "USER_INPUT")
        }
        assertEquals("没有 seq 的记录必须丢掉，不能编一个", null, RelayApiV2.recordOf(noSeq))
        val badSeq = buildJsonObject {
            put("sessionId", "s1")
            put("seq", JsonPrimitive(-3))
            put("kind", "USER_INPUT")
        }
        assertEquals(null, RelayApiV2.recordOf(badSeq))
    }

    @Test
    fun `an unknown kind degrades to meta patch instead of dropping the record`() {
        val row = buildJsonObject {
            put("sessionId", "s1")
            put("seq", JsonPrimitive(7))
            put("prevSeq", JsonPrimitive(6))
            put("kind", "brand/new/event-from-a-later-plugin")
            put("payload", buildJsonObject { put("type", "brand/new") })
        }
        val record = RelayApiV2.recordOf(row)
        assertEquals("不认识的类型要原样透传，投影器自己决定忽略", RecordKind.META_PATCH, record!!.kind)
        assertEquals(7L, record.seq)
        assertTrue(record.payloadJson.contains("brand/new"))
    }

    @Test
    fun `a stub record keeps its blob pointer and preview`() {
        val row = buildJsonObject {
            put("sessionId", "s1")
            put("seq", JsonPrimitive(9))
            put("prevSeq", JsonPrimitive(8))
            put("kind", "ASSISTANT_TEXT")
            put("stub", buildJsonObject {
                put("blobId", "s1:9")
                put("blobSize", JsonPrimitive(300_000))
                put("previewHead", "很长的正文开头…")
            })
        }
        val record = RelayApiV2.recordOf(row)!!
        assertTrue(record.isOversizedStub)
        assertEquals("s1:9", record.blobId)
        assertEquals(300_000L, record.blobSize)
    }

    @Test
    fun `a truncated record keeps the original size for the error row`() {
        val row = buildJsonObject {
            put("sessionId", "s1")
            put("seq", JsonPrimitive(9))
            put("kind", "ERROR_OVERSIZED_TRUNCATED")
            put("truncated", buildJsonObject { put("originalBytes", JsonPrimitive(12_000_000)) })
        }
        assertEquals(12_000_000L, RelayApiV2.recordOf(row)!!.truncatedFrom)
    }

    @Test
    fun `an unrecognised mux tag is ignored rather than thrown`() {
        val decoded = RelayApiV2.recordOf(
            buildJsonObject { put("seq", JsonPrimitive(0)) }
        )
        assertEquals(null, decoded)
    }
}
