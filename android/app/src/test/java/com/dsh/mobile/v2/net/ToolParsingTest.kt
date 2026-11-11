package com.dsh.mobile.v2.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `tool/call` 归一化：尤其是**操作小标题**（模型写在参数 `description` 里那句话）。
 *
 * 2026-11-01 用户拿桌面端截图提问：「工具调用 · ops_bash · 改成十秒桶」这些小标题
 * 是哪来的、手机端也要。桌面端 `toolRowModel` 里
 * `summary = [工具名, base].join(" · ")`，`base` 优先取参数 `description`；
 * 手机端的 [toolActionOfRecord] 原先**完全不读**这个字段，于是同一批调用在电脑上
 * 有小标题、在手机上只剩一条裸命令。
 *
 * 这里守三件事：①描述读得到；②没描述时留空串，绝不拿命令冒充；③畸形载荷不抛。
 */
class ToolParsingTest {

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    /** 真宿主的 `arguments` 是**字符串**（JSON 文本），不是对象；这里照实拼。 */
    private fun call(argumentsJson: String, name: String = "ops_bash"): JsonObject {
        val quoted = JsonPrimitive(argumentsJson).toString()
        return obj("""{"callId":"c1","name":"$name","arguments":$quoted}""")
    }

    @Test
    fun `the description argument becomes the subtitle`() {
        val parsed = toolActionOfRecord(
            call("""{"command":"node scripts/build.mjs","description":"改成十秒桶"}""")
        )
        assertEquals("改成十秒桶", parsed.subtitle)
        // 命令仍在自己的字段里：两段各归各位，折叠行第一行放小标题、第二行放命令。
        assertEquals("node scripts/build.mjs", parsed.command)
    }

    @Test
    fun `a tool without a description leaves the subtitle empty instead of borrowing the command`() {
        // 拿命令冒充小标题会让折叠行变成「已处理 N 个操作 · 执行命令 · npm test」，
        // 而第二行又写一遍 npm test —— 同一句话说两次。
        val parsed = toolActionOfRecord(call("""{"command":"npm test"}"""))
        assertEquals("", parsed.subtitle)
    }

    @Test
    fun `a description object payload is read through its text field`() {
        // 少数宿主把描述写成 `{description:{text:"…"}}`，与 content part 同形。
        val parsed = toolActionOfRecord(call("""{"command":"ls","description":{"text":"看看目录"}}"""))
        assertEquals("看看目录", parsed.subtitle)
    }

    @Test
    fun `arguments given as an object instead of a string work the same way`() {
        // 插件某些版本直接把参数当对象塞进来（不走 `JSON.stringify`）。
        val parsed = toolActionOfRecord(
            obj("""{"callId":"c1","name":"ops_bash","arguments":{"command":"ls","description":"看看目录"}}""")
        )
        assertEquals("看看目录", parsed.subtitle)
        assertEquals("ls", parsed.command)
    }

    @Test
    fun `a description at the top level of the event is accepted too`() {
        // 参数被拍平到事件顶层的老版本（`arguments` 缺失）也要读得到。
        val parsed = toolActionOfRecord(
            obj("""{"callId":"c1","name":"ops_bash","description":"跑测试","command":"npm test"}""")
        )
        assertEquals("跑测试", parsed.subtitle)
    }

    @Test
    fun `surrounding whitespace in the description is trimmed`() {
        val parsed = toolActionOfRecord(call("""{"command":"ls","description":"  看看目录  "}"""))
        assertEquals("看看目录", parsed.subtitle)
    }

    @Test
    fun `a description only payload still yields a subtitle and no command`() {
        // 文件类工具（read/write/edit）本来就只带路径 + 描述，没有命令。
        val parsed = toolActionOfRecord(
            call("""{"file_path":"src/App.kt","description":"读一下入口"}""", name = "ops_read")
        )
        assertEquals("读一下入口", parsed.subtitle)
        assertEquals("", parsed.command)
        assertEquals("src/App.kt", parsed.detail)
    }

    @Test
    fun `a malformed arguments payload degrades to an empty subtitle instead of throwing`() {
        // 投影路径上不许抛异常：这一条调用解析不出描述，下一帧就是完整参数。
        // （退化形态会把原文塞进 command，这是既有行为，这里只钉「不抛 + 不编小标题」。）
        val parsed = toolActionOfRecord(obj("""{"callId":"c1","name":"ops_bash","arguments":"{not json"}"""))
        assertEquals("", parsed.subtitle)
    }
}
