package com.dsh.mobile.v2.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 增量帧的「类型」与「文本」必须能分别取到。
 *
 * 2026-10-30 真机反馈：手机上「思考过程被当成正文输出在聊天页了，而没有进入折叠栏」，
 * 同时「折叠栏右侧的思考流式预览没有了」。两句话是同一个 bug 的两面 ——
 * 插件原先无条件把 `chunk.text` 当正文，`reasoning-delta` 的思考于是进了正文缓冲，
 * 折叠行那条通道自然一直空着。
 *
 * 插件侧已按 type 分流；这一组用例守的是手机侧：**必须自己也能分辨**，
 * 一条老插件推来的帧不该把整屏正文换成思考。
 */
class DeltaPayloadTest {

    @Test
    fun `an assistant-stream payload is recognised as answer text`() {
        val payload = """{"type":"assistant-stream","data":{"text":"答案是 4。"}}"""
        assertEquals("assistant-stream", payloadTypeOf(payload))
        assertEquals("答案是 4。", payloadTextOf(payload))
    }

    @Test
    fun `an assistant-reasoning payload is recognised as thinking`() {
        // 判据只有 type：两种帧的 data.text 形状完全一样。
        val payload = """{"type":"assistant-reasoning","data":{"text":"先看看题目里有什么"}}"""
        assertEquals("assistant-reasoning", payloadTypeOf(payload))
        assertEquals("先看看题目里有什么", payloadTextOf(payload))
    }

    @Test
    fun `the two kinds are told apart even though both carry text`() {
        // 这个用例存在的理由：如果有人把判据退回「有没有 text 字段」，它必须红。
        val answer = """{"type":"assistant-stream","data":{"text":"同一段字"}}"""
        val thinking = """{"type":"assistant-reasoning","data":{"text":"同一段字"}}"""
        assertEquals("同一段字", payloadTextOf(answer))
        assertEquals("同一段字", payloadTextOf(thinking))
        assert(payloadTypeOf(answer) != payloadTypeOf(thinking)) {
            "同样的文本必须靠 type 分辨，否则思考会被画成正文"
        }
    }

    @Test
    fun `a broken payload degrades to empty instead of throwing`() {
        // 坏帧不该让整条流断掉：这一帧没有文本，下一帧就是完整文本。
        assertEquals("", payloadTypeOf("{not json"))
        assertEquals("", payloadTextOf("{not json"))
        assertEquals("", payloadTypeOf(""))
        assertEquals("", payloadTextOf(""))
    }

    @Test
    fun `a bare string payload still yields its text`() {
        // 老宿主/夹具可能直接推一个 JSON 字符串当正文。
        assertEquals("裸正文", payloadTextOf("\"裸正文\""))
    }

    @Test
    fun `a payload with no type reports an empty kind rather than a guess`() {
        // 猜错方向的代价是「思考被当正文」，所以宁可返回空让调用方走正文默认路径。
        assertEquals("", payloadTypeOf("""{"data":{"text":"没说类型"}}"""))
        assertEquals("没说类型", payloadTextOf("""{"data":{"text":"没说类型"}}"""))
    }
}
