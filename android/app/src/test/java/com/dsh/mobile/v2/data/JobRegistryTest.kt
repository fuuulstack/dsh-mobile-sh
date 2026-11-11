package com.dsh.mobile.v2.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台任务的两种状态转换（`job/registered` / `job/settled`）。
 *
 * 这一屏的数据**不在宿主的投影表里**（`SessionProjectionMap` 没有 jobs），
 * 只能靠记录自己聚合，所以这里把「怎么进表、怎么出表、乱序怎么办」逐条钉住。
 *
 * 失败信息都写成「现象 + 为什么错」：这类 bug 在真机上表现为「胶囊该在的时候不在」，
 * 从堆栈看不出是哪一步漏了。
 */
class JobRegistryTest {

    private fun record(seq: Long, type: String, data: String) = TimelineRecord(
        sessionId = "s1",
        seq = seq,
        prevSeq = seq - 1,
        turnId = "s1:t1",
        kind = RecordKind.META_PATCH,
        timestampMs = 1_000L * seq,
        payloadJson = """{"type":"$type","seq":$seq,"data":$data}""",
    )

    private fun registered(
        seq: Long,
        id: String,
        label: String,
        kind: String = "shell",
        at: Long = 1_700_000_000_000L,
    ) = record(
        seq,
        "job/registered",
        """{"jobId":"$id","owner":"s1","kind":"$kind","label":"$label","startedAt":$at}""",
    )

    private fun settled(seq: Long, id: String) =
        record(seq, "job/settled", """{"jobId":"$id","owner":"s1","status":"ok"}""")

    /** 同一类桶里的另一种事件：`step/start` 也是 META_PATCH，但它不是任务事件。 */
    private fun stepStart(seq: Long) = record(seq, "step/start", """{"turn":1,"step":1}""")

    @Test
    fun `a registered job stays until it settles`() {
        val one = jobsOf(listOf(registered(1, "a", "跑测试")))
        assertEquals("注册完就该在「在跑」列表里", 1, one.size)
        assertEquals("跑测试", one[0].label)

        val after = jobsOf(listOf(registered(1, "a", "跑测试"), settled(2, "a")))
        assertEquals("结算之后必须出表，否则胶囊会挂着一个早就跑完的任务", 0, after.size)
    }

    @Test
    fun `a settle without a registration is ignored`() {
        val jobs = jobsOf(listOf(settled(1, "ghost")))
        assertEquals("没见过注册的结算属于更早的窗口，不该凭空造出一条任务", 0, jobs.size)
    }

    @Test
    fun `jobs keep the order they were registered in`() {
        val jobs = jobsOf(
            listOf(
                registered(1, "a", "第一个"),
                registered(2, "b", "第二个"),
                registered(3, "c", "第三个"),
                settled(4, "b"),
            ),
        )
        assertEquals("先起的排前面；结算掉的那个不能留下空位", listOf("第一个", "第三个"), jobs.map { it.label })
    }

    @Test
    fun `a repeated registration of the same job replaces the label`() {
        val jobs = jobsOf(
            listOf(
                registered(1, "a", "旧标签"),
                registered(2, "a", "新标签"),
            ),
        )
        assertEquals("同一个 jobId 重复注册只能是覆盖，不能变成两条", 1, jobs.size)
        assertEquals("新标签", jobs[0].label)
    }

    @Test
    fun `only job lifecycle events feed the registry`() {
        assertTrue("job/registered 必须认出来", registered(1, "a", "x").isJobLifecycle())
        assertTrue("job/settled 必须认出来", settled(2, "a").isJobLifecycle())
        assertFalse(
            "step/start 同样是 META_PATCH，但它不是任务事件 —— 认错会让胶囊凭空出现",
            stepStart(3).isJobLifecycle(),
        )
        assertEquals("非任务事件一律不进表", 0, jobsOf(listOf(stepStart(3))).size)
    }

    @Test
    fun `eventType reads the payload type and tolerates junk`() {
        assertEquals("job/registered", registered(1, "a", "x").eventType())
        val broken = record(1, "unused", """not json""").copy(payloadJson = "{")
        assertEquals("payload 坏掉时给空串，绝不能在解析路径上抛异常", "", broken.eventType())
    }

    @Test
    fun `a job without an id is skipped`() {
        val jobs = jobsOf(listOf(record(1, "job/registered", """{"label":"没有 id"}""")))
        assertEquals("没有 jobId 就没法结算也没法当列表 key，只能丢掉", 0, jobs.size)
    }

    @Test
    fun `a job event is stored under its own kind so the cleanup cannot take it`() {
        assertEquals(
            "任务事件落盘必须改名成 JOB_EVENT：老数据清理是按 kind 名整批删 META_PATCH 的，" +
                "混在里面 5 秒后就被 GC 清掉，胶囊随之消失（实测过）",
            "JOB_EVENT",
            TimelineRecordEntity.of(registered(1, "a", "x")).kind,
        )
        assertEquals(
            "同一桶里的其它事件照旧落 META_PATCH，好让清理能删掉它们",
            "META_PATCH",
            TimelineRecordEntity.of(stepStart(2)).kind,
        )
        assertEquals("读回来还得是任务事件", RecordKind.JOB_EVENT, RecordKind.of("JOB_EVENT"))
        assertTrue("改名回来后 isJobLifecycle 仍然成立", registered(1, "a", "x").isJobLifecycle())
    }

    @Test
    fun `elapsed label reports seconds and minutes`() {
        val now = System.currentTimeMillis()
        assertEquals("", Model.Job(startedAt = 0).elapsedLabel())
        assertEquals("已运行 5 秒", Model.Job(startedAt = now - 5_000).elapsedLabel())
        assertEquals("已运行 2 分 3 秒", Model.Job(startedAt = now - 123_000).elapsedLabel())
    }
}
