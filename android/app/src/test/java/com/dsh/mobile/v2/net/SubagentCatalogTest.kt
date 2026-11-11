package com.dsh.mobile.v2.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 宿主 `subagentCatalog` 的解析（`session/projections` 的 `values.subagentCatalog`）。
 *
 * 宿主侧形状是
 * `{id, createdAt} & ({mode:'one-shot'|'external'|'continuable'|'unknown', label?})`：
 * **`label` 只有 `continuable` 一定存在**，其余模式是可选的。解析不能假设它有值，
 * 也不能让缺 id 的条目混进列表 —— 胶囊点开的清单拿 id 当 key。
 */
class SubagentCatalogTest {

    private fun any(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test
    fun `entries keep id label mode and createdAt`() {
        val list = subagentsOf(
            any(
                """[
                    {"id":"s-1","createdAt":1700000000000,"mode":"continuable","label":"审查代码"},
                    {"id":"s-2","createdAt":1700000001000,"mode":"one-shot"}
                ]""",
            ),
        )
        assertEquals("两条都该留下", 2, list.size)
        assertEquals("s-1", list[0].id)
        assertEquals("审查代码", list[0].label)
        assertEquals("continuable", list[0].mode)
        assertEquals(1_700_000_000_000L, list[0].createdAt)
        assertEquals("可选的 label 缺了就是空串，不能变成字面的 null", "", list[1].label)
        assertEquals("one-shot", list[1].mode)
    }

    @Test
    fun `an entry without an id is dropped`() {
        val list = subagentsOf(any("""[{"label":"没有 id","mode":"one-shot"}]"""))
        assertEquals("没有 id 就当不了列表 key，留着只会在点开时崩", 0, list.size)
    }

    @Test
    fun `junk shapes degrade to an empty list instead of throwing`() {
        assertEquals("字段缺失（老宿主）就是空目录", 0, subagentsOf(null).size)
        assertEquals("形状不对（不是数组）也当空目录", 0, subagentsOf(any("""{"id":"s-1"}""")).size)
        assertEquals(
            "数组里混进非对象时跳过那一条，别整批丢掉",
            1,
            subagentsOf(any("""["nonsense",{"id":"s-9","mode":"external"}]""")).size,
        )
    }

    @Test
    fun `the running flag is three-state and absent stays absent`() {
        // 目录条目本身不带状态（宿主只发 {id, createdAt, mode, label?}），插件会把
        // 子代理**自己那条会话**的 running 盖上来。老插件不发这个字段，
        // 读成 false 等于凭空宣布它跑完了 ⇒ 必须读成 null（不知道）。
        val list = subagentsOf(
            any(
                """[
                    {"id":"s-1","mode":"continuable","running":true},
                    {"id":"s-2","mode":"one-shot","running":false},
                    {"id":"s-3","mode":"unknown"}
                ]""",
            ),
        )
        assertEquals("在跑", true, list[0].running)
        assertEquals("跑完了", false, list[1].running)
        assertEquals("缺席 ≠ 跑完了", null, list[2].running)
        assertEquals(
            "字符串形式的布尔值也认（宿主偶尔把布尔塞成字符串）",
            true,
            subagentsOf(any("""[{"id":"s-4","running":"true"}]"""))[0].running,
        )
        assertEquals(
            "解析不出来按「不知道」，不能按 false",
            null,
            subagentsOf(any("""[{"id":"s-5","running":"maybe"}]"""))[0].running,
        )
    }
}
