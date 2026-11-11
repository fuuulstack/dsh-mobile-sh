package com.dsh.mobile.v2.net

import com.dsh.mobile.v2.data.Diag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「什么样的帧才算流还活着」（`docs/PRD.md` §5.2 不变量 1「半死自愈」）。
 *
 * 背景：看门狗的活性判据最初挂在「收到任何一条 WS 消息」上，而重放时
 * `snapshot-begin` / `snapshot-records` / `snapshot-end` 会连发，控制帧也会周期出现。
 * 于是半死的连接每隔几秒就能把计时清零，静默阈值永远等不到——看门狗被自己的判据
 * 喂饱了，而界面上没有任何迹象。
 *
 * 反过来，若把工具执行期间的合法静默也算活性，那「真的卡住」就要等更久。所以判据
 * 取两头之间：只有**推进了内容**的帧算。
 */
class StreamActivityTest {

    private fun frame(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    private fun useful(type: String): Boolean = Diag.isUsefulFrame(frame("""{"type":"$type"}"""))

    @Test
    fun `流式增量算活性`() {
        assertTrue(useful("assistant-stream"))
    }

    @Test
    fun `持久事件算活性`() {
        assertTrue(useful("event"))
    }

    @Test
    fun `快照里的真实记录算活性`() {
        // 重放中途才说明数据在动。
        assertTrue(Diag.isUsefulFrame(frame("""{"type":"snapshot-records","records":[{"event":{"seq":1}}]}""")))
        // 空的「查了，没有」什么都不推进，不算。
        assertFalse(Diag.isUsefulFrame(frame("""{"type":"snapshot-records","records":[]}""")))
    }

    @Test
    fun `控制帧不算活性`() {
        // 这几个正是把看门狗喂饱的元凶：它们会周期出现，却什么都没推进。
        for (type in listOf("snapshot-begin", "snapshot-end", "ack", "state", "unknown")) {
            assertFalse("$type 不该算活性", useful(type))
        }
    }
}
