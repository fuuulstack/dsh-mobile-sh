package com.dsh.mobile.v2.net

import com.dsh.mobile.v2.data.Model
import com.dsh.mobile.v2.data.taskProgressOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网关纯逻辑的 JVM 单测：事件归约、判重、摘要解析、目录解析。
 * RelayGateway 构造需要 RelayApi/TokenVault（okhttp/keystore），
 * 所以这里只测静态/纯函数路径，通过 parseJson 构造帧。
 */
class GatewayLogicTest {

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    /** 任意 JSON 值（`todosOf` 要的是数组或 null，不能先 narrow 成对象）。 */
    private fun any(text: String): JsonElement = Json.parseToJsonElement(text)

    // ============================================================
    // HostErrors
    // ============================================================

    @Test
    fun `已知 code 翻中文 未知 code 包中文骨架`() {
        assertEquals("会话不存在或已被删除", HostErrors.text("session/not-found"))
        assertEquals("电脑端 DSH 版本太旧，没有这个接口", HostErrors.text("gateway/method-unavailable"))
        assertNull(HostErrors.text("totally/unknown"))
        assertEquals("DSH 请求失败（totally/unknown）", HostErrors.message(obj("""{"code":"totally/unknown"}""")))
        assertEquals("DSH 拒绝了请求", HostErrors.message(null))
        assertEquals("DSH 请求失败", HostErrors.message(obj("""{}""")))
    }

    @Test
    fun `original 拼接 code 与英文原文`() {
        assertEquals("session/not-found: session gone", HostErrors.original(obj("""{"code":"session/not-found","message":"session gone"}""")))
        assertEquals("", HostErrors.original(null))
    }

    // ============================================================
    // 状态判定
    // ============================================================

    @Test
    fun `凭据级失败的判定`() {
        assertTrue(RelayApi.tokenRejected(401))
        assertTrue(RelayApi.tokenRejected(403))
        assertFalse(RelayApi.tokenRejected(500))
        assertTrue(RelayApi.hostGone(410))
        assertFalse(RelayApi.hostGone(404))
        assertTrue(RelayApi.credentialFailure(RelayApi.TOKEN_REJECTED))
        assertTrue(RelayApi.credentialFailure(RelayApi.AUTH_REVOKED))
        assertFalse(RelayApi.credentialFailure("连接已断开，正在重连…"))
    }

    // ============================================================
    // normalize
    // ============================================================

    @Test
    fun `normalize 域名走 HTTPS、IP 字面量可以明文 http`() {
        assertEquals("https://relay.example.com", RelayApi.normalize("https://relay.example.com/"))
        assertEquals("https://relay.example.com", RelayApi.normalize(" https://relay.example.com "))
        // 裸 IP 部署：IP 证书要单独签发，自建中继一律明文起服务。App 侧必须同一口径，
        // 否则 ScanScreen 拼出来的 http://… 会在归一化这一步被否掉（扫码后毫无反应）。
        assertEquals("http://203.0.113.10", RelayApi.normalize("http://203.0.113.10"))
        assertEquals("http://203.0.113.10", RelayApi.normalize("http://203.0.113.10/"))
        assertEquals("http://203.0.113.10:8080", RelayApi.normalize("http://203.0.113.10:8080/"))
        assertEquals("http://[::1]:8080", RelayApi.normalize("http://[::1]:8080"))
        fun rejects(input: String) {
            try {
                RelayApi.normalize(input)
                throw AssertionError("应拒绝：$input")
            } catch (expected: IllegalArgumentException) {
            }
        }
        // 域名不能走明文：那样任何人都能把手机引到一台假中继上（IP 至少是用户亲手填的）。
        rejects("http://relay.example.com")
        rejects("https://u:p@relay.example.com")
        rejects("https://relay.example.com/path")
        rejects("https://relay.example.com?q=1")
        rejects("relay.example.com")
    }

    // ============================================================
    // JSON 助手
    // ============================================================

    @Test
    fun `str 的中性值语义`() {
        val o = obj("""{"a":"x","n":null,"i":3,"b":true,"o":{"k":"v"}}""")
        assertEquals("x", o.str("a"))
        assertEquals("", o.str("missing"))
        assertEquals("", o.str("n"))
        assertEquals("3", o.str("i"))
        assertEquals("true", o.str("b"))
        assertEquals("", o.str("o"))
        assertEquals(3, o.int("i"))
        assertEquals(3L, o.long("i"))
        assertTrue(o.bool("b"))
        assertFalse(o.bool("missing", false))
        assertNotNull(o.obj("o"))
        assertNull(o.obj("missing"))
    }

    @Test
    fun `textContent 只拼 type=text 的块`() {
        val content = """[{"type":"text","text":"a"},{"type":"image","attachmentId":"x"},{"type":"text","text":"b"}]"""
        assertEquals("ab", textContent(obj("""{"content":$content}""")["content"]))
    }

    @Test
    fun `imageRefs 兼容嵌套与扁平形态`() {
        val nested = obj("""{"content":[{"type":"image","attachment":{"attachmentId":"sha256:1","name":"p.png"}}]}""")["content"]
        val flat = obj("""{"content":[{"type":"image","attachmentId":"sha256:2","name":"q.png"}]}""")["content"]
        val nestedRefs = imageRefs(nested)
        assertEquals(1, nestedRefs.size)
        assertEquals("sha256:1", nestedRefs[0].attachmentId)
        assertEquals("p.png", nestedRefs[0].name)
        assertEquals("sha256:2", imageRefs(flat)[0].attachmentId)
        assertTrue(imageRefs(obj("""{"content":[{"type":"text","text":"x"}]}""")["content"]).isEmpty())
    }

    @Test
    fun `localizedText 取 zh 优先 en 兜底`() {
        assertEquals("中文", localizedText(obj("""{"zh":"中文","en":"en"}""")))
        assertEquals("en", localizedText(obj("""{"en":"en"}""")))
        assertEquals("plain", Json.parseToJsonElement("\"plain\"").let { localizedText(it) })
        assertEquals("", localizedText(obj("""{"fr":"x"}""")))
    }

    // ============================================================
    // 快照回放：哪些回合的面板会被重建
    // ============================================================

    private fun records(vararg events: String): JsonArray =
        Json.parseToJsonElement(events.joinToString(",", "[", "]") { """{"event":$it}""" }).jsonArray

    @Test
    fun `只有 turn 头尾事件时不认定回放会重建面板`() {
        // 增量快照常常只带一条 turn/end：此时缓存里那些完整的旧面板必须保留，
        // 否则重进会话「深度思考 / 处理详情」会整块消失（真机复现）。
        val recs = records(
            """{"type":"turn/end","seq":7172439048,"data":{"turn":1}}""",
            """{"type":"turn/start","seq":7172439033,"data":{"turn":1}}""",
        )
        assertTrue(turnsWithAssistantContent(recs).isEmpty())
    }

    @Test
    fun `带助手消息的回合才算回放会重建`() {
        val recs = records(
            """{"type":"turn/end","seq":7172439048,"data":{"turn":1}}""",
            """{"type":"assistant/message","seq":7172439047,"data":{"turn":2,"message":{"content":[]}}}""",
        )
        assertEquals(setOf(2L), turnsWithAssistantContent(recs))
    }

    @Test
    fun `缺失 turn 字段的助手事件不参与判定`() {
        val recs = records("""{"type":"assistant/message","seq":7172439047,"data":{"message":{"content":[]}}}""")
        assertTrue(turnsWithAssistantContent(recs).isEmpty())
        assertTrue(turnsWithAssistantContent(null).isEmpty())
    }

    // ============================================================
    // 流量账：进程级唯一账本
    // ============================================================

    @Test
    fun `流量账在进程级只有一本`() {
        // 真机踩过的坑：mobilewright 重复 launch 会堆叠 MainActivity，各自再建一套
        // RelayApi——账若是实例级的，设置页 hero 读到的是新实例那份近乎为零的账。
        // 计数器放在 companion 上，谁挪回实例字段，`RelayApi.trafficDown` 在这里
        // 会立刻编译失败，暴露回归。
        val before = RelayApi.trafficDown.get()
        RelayApi.trafficDown.addAndGet(17)
        assertEquals(before + 17, RelayApi.trafficDown.get())
    }

    // ============================================================
    // 版本清单解析：Model.Release 的比对语义（v1 同口径）
    // ============================================================

    @Test
    fun `Release 解析与比对语义`() {
        val release = Model.Release(
            latest = "0.3.0", minSupported = "0.2.0",
            url = "https://downloads.example.test/api/ybs/downloads/dsh-mobile-0.3.0.apk",
            latestUrl = "https://downloads.example.test/api/ybs/downloads/latest.apk",
            size = 19_900_000, sha256 = "", notes = "修复", publishedAt = "2026-10-28",
        )
        // valid：版本号 + 下载地址（url 优先 latestUrl）。
        assertTrue(release.valid)
        assertEquals("https://downloads.example.test/api/ybs/downloads/dsh-mobile-0.3.0.apk", release.downloadUrl())
        // 大小标签：MB 档保留 1 位小数。
        assertEquals("19.0 MB", release.sizeLabel())
        // 服务器版本更新 / 强制升级判定。
        assertTrue(release.newerThan("0.2.9"))
        assertFalse(release.newerThan("0.3.0"))
        assertFalse(release.newerThan("0.3.1"))
        assertFalse(release.forcesUpgrade("0.2.9"))
        assertTrue(Model.Release(latest = "0.3.0", minSupported = "0.3.0").forcesUpgrade("0.2.9"))
    }

    @Test
    fun `Release 缺版本号或下载地址视为无效`() {
        assertFalse(Model.Release(latest = "", url = "https://x/y.apk").valid)
        assertFalse(Model.Release(latest = "0.3.0", url = "", latestUrl = "").valid)
        // url 空但 latestUrl 在：仍然可用（老服务器只给 latest.apk）。
        assertTrue(Model.Release(latest = "0.3.0", latestUrl = "https://x/latest.apk").valid)
        // 大小未知：标签空串，不显示误导性的 0 B。
        assertEquals("", Model.Release(latest = "0.3.0", url = "https://x/y.apk").sizeLabel())
    }

    @Test
    fun `版本号逐段数字比较`() {
        // 逐段比数字而不是字符串比：0.10.0 > 0.9.0（字典序会判反）。
        assertTrue(Model.Release(latest = "0.10.0").newerThan("0.9.0"))
        assertTrue(Model.Release(latest = "1.0.0").newerThan("0.99.99"))
        assertEquals(0, Model.Release.compareVersions("0.2.0", "0.2.0"))
    }

    // ============================================================
    // 任务清单解析（session/projections 的 todos + todo/write 事件）
    // ============================================================

    @Test
    fun `todosOf 解析宿主的任务清单`() {
        val todos = todosOf(
            any(
                """[
                    {"content":"勘察代码","status":"completed"},
                    {"content":"改发送钮","status":"in_progress"},
                    {"content":"跑测试","status":"pending"}
                ]""",
            ),
        )
        assertEquals(3, todos.size)
        assertEquals("勘察代码", todos[0].content)
        assertTrue(todos[0].completed)
        assertTrue(todos[1].inProgress)
        // 整表语义下计数必须和胶囊文案对得上。
        assertEquals("任务: 1/3", taskProgressOf(todos).label())
    }

    @Test
    fun `todosOf 对残缺形状容错`() {
        // 不是数组（宿主给 null 或对象）→ 空清单，不抛异常。
        assertEquals(0, todosOf(null).size)
        assertEquals(0, todosOf(any("""{"todos":[]}""")).size)
        // 纯字符串条目按 pending 收（老宿主/适配器只给 content）。
        val strings = todosOf(any("""["甲","乙"]"""))
        assertEquals(2, strings.size)
        assertEquals("pending", strings[0].status)
        // 没有 status 字段也按 pending；没有 content 的条目直接跳过。
        val partial = todosOf(any("""[{"content":"只有内容"},{"status":"completed"}]"""))
        assertEquals(1, partial.size)
        assertEquals("pending", partial[0].status)
    }

    // ============================================================
    // 排队队列解析（session/projections 的 inbox + agent/inbox/spliced）
    // ============================================================

    @Test
    fun `queueOf 解析宿主的 inbox 两个桶`() {
        val queue = queueOf(
            any(
                """{
                    "next-turn":[
                        {"id":"a","role":"user","content":[{"type":"text","text":"继续"}]},
                        {"id":"b","role":"user","content":[{"type":"image","mediaType":"image/png","data":"x"}]}
                    ],
                    "next-step":[
                        {"id":"c","role":"user","content":[{"type":"text","text":"顺便看一下测试"}]}
                    ]
                }""",
            ),
        )
        // next-turn 在前、next-step 在后：宿主也按这个顺序领取。
        assertEquals(listOf("a", "b", "c"), queue.map { it.id })
        assertEquals("继续", queue[0].text)
        assertEquals("next-turn", queue[0].target)
        assertTrue(queue[0].steerable)
        // 只有图片的消息：正文为空，卡片退化成「[图片]」。
        assertEquals("", queue[1].text)
        assertEquals(1, queue[1].imageCount)
        assertEquals("[图片]", queue[1].label())
        // next-step 的条目不能被「提前插话」。
        assertEquals("next-step", queue[2].target)
        assertFalse(queue[2].steerable)
    }

    @Test
    fun `queueOf 对残缺形状容错`() {
        // 不是对象 → 空队列，不抛异常。
        assertEquals(0, queueOf(null).size)
        assertEquals(0, queueOf(any("""[]""")).size)
        // 没有 id 的条目直接丢掉：编辑/删除/插话全靠 id 定位，留着点不动。
        val noId = queueOf(any("""{"next-turn":[{"content":[{"type":"text","text":"甲"}]}]}"""))
        assertEquals(0, noId.size)
        // 缺桶按空桶处理，多出来的桶忽略。
        val partial = queueOf(any("""{"next-turn":[{"id":"x"}],"other":[{"id":"y"}]}"""))
        assertEquals(1, partial.size)
        assertEquals("x", partial[0].id)
    }
}
