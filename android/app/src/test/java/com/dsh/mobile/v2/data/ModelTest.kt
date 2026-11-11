package com.dsh.mobile.v2.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Model 纯逻辑的 JVM 单测（不依赖 Android 框架）。
 * 重点覆盖 v1 事故修复里沉淀的行为：版本比较、未读判定、标题过滤。
 */
class ModelTest {

    // ============================================================
    // 版本号比较
    // ============================================================

    @Test
    fun `字符串比较会把 0_1_10 判旧——必须逐段按数字`() {
        assertTrue(Model.Release.compareVersions("0.1.10", "0.1.9") > 0)
        assertTrue(Model.Release.compareVersions("0.1.9", "0.2.0") < 0)
        assertTrue(Model.Release.compareVersions("1.0.0", "0.2.0") > 0)
        assertEquals(0, Model.Release.compareVersions("0.2.0", "0.2.0"))
    }

    @Test
    fun `段数不齐按 0 补`() {
        assertTrue(Model.Release.compareVersions("0.1", "0.1.0") == 0)
        assertTrue(Model.Release.compareVersions("0.1.1", "0.1") > 0)
    }

    @Test
    fun `预发布段退化成字符串比较`() {
        // "9-beta" 里的 "beta" 非数字：与 "0" 走字符串比较
        assertTrue(Model.Release.compareVersions("0.1.9-beta", "0.1.9") != 0)
    }

    @Test
    fun `newerThan 与 forcesUpgrade`() {
        val release = Model.Release(latest = "0.2.1", minSupported = "0.2.0")
        assertTrue(release.newerThan("0.2.0"))
        assertFalse(release.newerThan("0.2.1"))
        assertFalse(Model.Release(latest = "0.2.1").forcesUpgrade("0.1.9"))
        assertTrue(Model.Release(latest = "0.2.1", minSupported = "0.2.0").forcesUpgrade("0.1.9"))
        assertTrue(Model.Release(latest = "0.2.1", url = "https://x/a.apk").valid)
        assertFalse(Model.Release(latest = "0.2.1").valid) // 没有下载地址不算有效版本
    }

    // ============================================================
    // 未读判定
    // ============================================================

    @Test
    fun `updatedAt 晚于 readAt 才是未读`() {
        val s = Model.Session(updatedAt = 100, readAt = 100)
        assertFalse(s.unread)
        s.updatedAt = 101
        assertTrue(s.unread)
    }

    @Test
    fun `正在跑的会话不算未读`() {
        val s = Model.Session(updatedAt = 100, readAt = 0, running = true)
        assertFalse(s.unread)
    }

    // ============================================================
    // 标签文案
    // ============================================================

    @Test
    fun `模型胶囊缺投影时回中性占位`() {
        assertEquals("默认模型", Model.Session().modelLabel())
        assertEquals("DeepSeek-V4.1-Flash · High", Model.Session(modelName = "DeepSeek-V4.1-Flash", reasoningEffort = "high").modelLabel())
    }

    @Test
    fun `权限预设映射中文`() {
        assertEquals("仅可查看", Model.Session.permissionLabelFor("read-only"))
        assertEquals("工作区内修改", Model.Session.permissionLabelFor("workspace-write"))
        assertEquals("完全权限", Model.Session.permissionLabelFor("danger-full-access"))
        assertEquals("权限", Model.Session.permissionLabelFor("unknown-preset"))
    }

    @Test
    fun `中继状态胶囊文案优先级`() {
        assertEquals("登录已过期，请在设置中轮换令牌，或重新登录", Model.RelayStatus(note = "登录已过期，请在设置中轮换令牌，或重新登录").pillText())
        assertEquals("正在连接中继…", Model.RelayStatus(state = Model.RelayState.CONNECTING).pillText())
        assertEquals("中继已连接 · 12ms", Model.RelayStatus(state = Model.RelayState.CONNECTED, latencyMs = 12).pillText())
        assertEquals("中继未连接", Model.RelayStatus().pillText())
    }

    @Test
    fun `note 非空时 isConnected 为 false`() {
        assertFalse(Model.RelayStatus(state = Model.RelayState.CONNECTED, note = "x").isConnected)
        assertTrue(Model.RelayStatus(state = Model.RelayState.CONNECTED).isConnected)
    }

    // ============================================================
    // 处理详情
    // ============================================================

    @Test
    fun `运行中的回合不报折叠标题`() {
        val m = Model.Message.pending()
        assertTrue(m.running)
        // 用户 2026-10-29 明确要求删掉「正在分析请求」那一行：运行中由折叠行的
        // 旋转图标 + 当前操作名说明「在跑」，标题留空。
        assertEquals("", m.processTitle())
    }

    @Test
    fun `失败回合标题用 failure 文案`() {
        val m = Model.Message(running = false, failure = "分析失败")
        assertEquals("分析失败", m.processTitle())
    }

    @Test
    fun `已完成回合带操作数与用时`() {
        val m = Model.Message(
            running = false, reasoning = "想过了",
            startedAt = 1000, endedAt = 5000,
        )
        m.actions.add(Model.ToolAction(name = "pwsh"))
        m.steps.add(Model.Step(kind = Model.Step.TOOL, actionIndex = 0))
        assertEquals("已完成分析 · 已处理 1 个操作 · 用时 4 秒", m.processTitle())
    }

    @Test
    fun `点数与抽屉同源 越界的工具步骤不计入`() {
        // processTitle 与抽屉标题共用 toolCount()：合并面板时被置 -1、
        // 或旧缓存里对不上号的步骤点开是空白行，计进去就成了
        // 「写着 3 个操作、点开只有 2 条」（2026-10-29 反馈）。
        val m = Model.Message(running = false, startedAt = 1000, endedAt = 3000)
        m.actions.add(Model.ToolAction(name = "pwsh"))
        m.actions.add(Model.ToolAction(name = "read"))
        m.steps.add(Model.Step(kind = Model.Step.TOOL, actionIndex = 0))
        m.steps.add(Model.Step(kind = Model.Step.TOOL, actionIndex = 1))
        m.steps.add(Model.Step(kind = Model.Step.TOOL, actionIndex = -1))
        m.steps.add(Model.Step(kind = Model.Step.TOOL, actionIndex = 9))
        assertEquals(2, m.toolCount())
        assertEquals("已完成分析 · 已处理 2 个操作 · 用时 2 秒", m.processTitle())
    }

    @Test
    fun `没有可展开内容时标题为空`() {
        val m = Model.Message(running = false)
        assertEquals("", m.processTitle())
        assertFalse(m.hasProcess)
    }

    @Test
    fun `hasProcess 覆盖 running 失败 思考与操作`() {
        assertTrue(Model.Message(running = true).hasProcess)
        assertTrue(Model.Message(failure = "x").hasProcess)
        assertTrue(Model.Message(reasoning = "x").hasProcess)
        val m = Model.Message()
        m.actions.add(Model.ToolAction())
        assertTrue(m.hasProcess)
    }

    // ============================================================
    // 相对时间
    // ============================================================

    @Test
    fun `relativeTime 各档位`() {
        // 基准时间必须自己造，不能用 System.currentTimeMillis()：那样「今天 09:12」在
        // 09:50 跑测试时就只差 38 分钟，会落进「N 分钟前」分支，用例随钟点翻脸。
        val now = at(20, 30, 0).timeInMillis
        assertEquals("刚刚", Model.relativeTime(now - 30_000, now))
        assertEquals("5 分钟前", Model.relativeTime(now - 5 * 60_000, now))
        assertEquals("今天 09:12", Model.relativeTime(at(9, 12, 0).timeInMillis, now))
        assertEquals("昨天", Model.relativeTime(now - 86_400_000L, now))
        assertEquals("3 天前", Model.relativeTime(now - 3 * 86_400_000L, now))
        assertEquals("", Model.relativeTime(0, now))
    }

    @Test
    fun `dateGroupOf 与 relativeTime 同口径`() {
        val now = at(20, 30, 0).timeInMillis
        assertEquals("今天", Model.dateGroupOf(at(9, 12, 0).timeInMillis, now))
        assertEquals("昨天", Model.dateGroupOf(now - 86_400_000L, now))
        assertEquals("本周", Model.dateGroupOf(now - 3 * 86_400_000L, now))
        assertEquals("更早", Model.dateGroupOf(now - 40 * 86_400_000L, now))
    }

    /** 今天某个钟点的 Calendar（秒/毫秒归零，避免用例自己带时间噪声）。 */
    private fun at(hour: Int, minute: Int, second: Int): Calendar =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, 0)
        }

    // ============================================================
    // 提问卡片
    // ============================================================

    @Test
    fun `提问卡片 complete 需要全部回答`() {
        val card = Model.QuestionCard()
        assertFalse(card.complete) // 空卡片不算
        val q1 = Model.Question().apply { selected.add("a") }
        val q2 = Model.Question()
        card.questions.add(q1)
        card.questions.add(q2)
        assertFalse(card.complete)
        q2.custom = "自定义答案"
        assertTrue(card.complete)
    }

    @Test
    fun `审批卡片标题优先 reason`() {
        val a = Model.Approval().apply { reason = "  需要写文件  " }
        assertEquals("需要写文件", a.headline())
        val b = Model.Approval().apply { toolName = "pwsh" }
        assertEquals("pwsh 请求越权执行", b.headline())
    }

    // ============================================================
    // 任务胶囊（宿主 todo_write 的进度）
    // ============================================================

    private fun todo(status: String, content: String = "t") =
        Model.Todo(content = content, status = status)

    @Test
    fun `任务胶囊只数 completed——in_progress 对用户仍是未完成`() {
        val todos = listOf(
            todo("completed"),
            todo("in_progress"),
            todo("pending"),
            todo("completed"),
        )
        val p = taskProgressOf(todos)
        assertEquals(2, p.done)
        assertEquals(4, p.total)
        assertEquals("任务: 2/4", p.label())
        assertFalse(p.allDone)
    }

    @Test
    fun `没有任务清单时胶囊不渲染`() {
        // total=0 是「宿主没写过任务」的信号，label 给空串，界面据此整块不画。
        assertEquals(0, taskProgressOf(emptyList()).total)
        assertEquals("", taskProgressOf(emptyList()).label())
        // 空清单不算「全部完成」——否则没任务的会话也会被当成收工。
        assertFalse(taskProgressOf(emptyList()).allDone)
    }

    @Test
    fun `全部完成后 allDone 为真`() {
        val p = taskProgressOf(listOf(todo("completed"), todo("completed")))
        assertTrue(p.allDone)
        assertEquals("任务: 2/2", p.label())
    }

    @Test
    fun `Todo 的 completed 与 inProgress 只认宿主的三个字面值`() {
        assertTrue(todo("completed").completed)
        assertFalse(todo("completed").inProgress)
        assertTrue(todo("in_progress").inProgress)
        // 未知状态（宿主将来加新值）既不算完成也不算进行中，别抛异常。
        assertFalse(todo("blocked").completed)
        assertFalse(todo("blocked").inProgress)
    }

    // ============================================================
    // 主机快照落盘：在线状态不许写进磁盘
    // ============================================================

    @Test
    fun `落盘的主机快照一律不带在线标记`() {
        val live = listOf(
            Model.Host(id = "h1", alias = "MacBook", online = true, authorized = true),
            Model.Host(id = "h2", alias = "公司台式", online = false, authorized = true),
        )
        val disk = hostsForDisk(live)
        // 在线是时效性事实：写进 prefs 就等于给下次冷启动埋一个几天前还亮着的绿点
        //（电脑关机后 App 仍显示「在线」，2026-10-29 用户直报）。
        assertFalse(disk.any { it.online })
        // 除 online 外一个字段都不许动：快照还要撑起「顶栏立刻显示电脑名」。
        assertEquals(live.map { it.copy(online = false) }, disk)
        assertEquals(listOf("h1", "h2"), disk.map { it.id })
        assertTrue(disk.all { it.authorized })
    }

    @Test
    fun `空快照落盘不炸`() {
        assertEquals(emptyList<Model.Host>(), hostsForDisk(emptyList()))
    }
}
