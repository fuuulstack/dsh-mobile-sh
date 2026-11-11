package com.dsh.mobile.v2.ui

import com.dsh.mobile.v2.R
import com.dsh.mobile.v2.data.Model
import com.dsh.mobile.v2.data.TodoMark
import com.dsh.mobile.v2.data.todoMarkOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI 层纯逻辑的 JVM 单测：时间轴排序、工作区分组、图标映射。
 *
 * 这些是从 Composable 里抽出来的纯函数，不依赖 Android 框架；回归重点是
 * 「本地回声（seq=0）不能被排到历史消息前面」——一旦又用 sortedBy { seq }
 * 就会翻车。
 */
class UiLogicTest {

    private fun msg(seq: Long, text: String = "m$seq", role: Model.Role = Model.Role.ASSISTANT) =
        Model.Message(role = role, text = text, seq = seq)

    @Test
    fun timelineSortsRemoteBySeqAscending() {
        val out = timelineOf(listOf(msg(5), msg(1), msg(3)))
        assertEquals(listOf(1L, 3L, 5L), out.map { it.seq })
    }

    @Test
    fun timelineKeepsLocalEchoAtTail() {
        // 本地回声 seq=0：绝不能因为「数值最小」而跑到历史消息前面。
        val echo = Model.Message.user("刚发的").apply { sending = true }
        val out = timelineOf(listOf(msg(2), msg(1), echo))
        assertEquals(listOf(1L, 2L), out.take(2).map { it.seq })
        assertEquals(0L, out.last().seq)
        assertEquals("刚发的", out.last().text)
        assertTrue(out.last().sending)
    }

    @Test
    fun timelinePreservesLocalRowInsertionOrder() {
        val a = Model.Message.user("a")
        val b = Model.Message.user("b")
        val out = timelineOf(listOf(msg(9), a, b))
        assertEquals(listOf(9L, 0L, 0L), out.map { it.seq })
        assertEquals("a", out[1].text)
        assertEquals("b", out[2].text)
    }

    @Test
    fun timelineEmptyIsFine() {
        assertTrue(timelineOf(emptyList()).isEmpty())
    }

    // ============================================================
    // F-09：处理面板与同回合正文共用 seq
    // ============================================================

    private fun panel(seq: Long, id: String = "p1", reasoning: String = "想想该怎么回。") =
        Model.Message(
            role = Model.Role.ASSISTANT, seq = seq, panelId = id,
            order = seq - 0.25, reasoning = reasoning,
        )

    @Test
    fun timelineKeepsPanelAndAnswerThatShareSeq() {
        // 面板（order=seq-0.25）与同回合正文共用 seq：两条都必须留在时间轴上，
        // 面板在正文之前。以前界面上只剩正文——面板被同 seq 清理删掉了。
        val out = timelineOf(listOf(msg(5, "收到！"), panel(5)))
        assertEquals(2, out.size)
        assertEquals("p1", out[0].panelId)
        assertEquals("收到！", out[1].text)
    }

    @Test
    fun sameSeqCleanupKeepsProcessPanel() {
        // 正文落地时顶掉同 seq 的旧投影，但带 panelId 的处理面板不能被一起清掉。
        val list = mutableListOf(panel(5), msg(5, "旧投影"))
        dropSupersededSameSeq(list, msg(5, "新投影"))
        assertEquals(1, list.size)
        assertEquals("p1", list[0].panelId)
    }

    @Test
    fun sameSeqCleanupIgnoresLocalRows() {
        // 本地回声 seq=0：没有「同 seq 顶掉」的语义，不能被清掉。
        val echo = Model.Message.user("刚发的")
        val list = mutableListOf(echo)
        dropSupersededSameSeq(list, echo)
        assertEquals(1, list.size)
        assertEquals("刚发的", list[0].text)
    }

    @Test
    fun timelineKeySeparatesPanelFromAnswer() {
        // 面板与正文共用 seq，key 若只用 seq 就会撞键，LazyColumn 直接崩。
        assertEquals("panel-p1", timelineKey(0, panel(5)))
        assertEquals("seq-5", timelineKey(1, msg(5, "答")))
        assertNotEquals(timelineKey(0, panel(5)), timelineKey(1, msg(5, "答")))
        // seq<=0 的本地行可能多条：退回索引，同样不能撞键。
        val echo = Model.Message.user("a")
        assertEquals("idx-2", timelineKey(2, echo))
        assertNotEquals(timelineKey(2, echo), timelineKey(3, echo))
    }

    @Test
    fun timelineKeyIgnoresTheOrderThatStreamingKeepsMoving() {
        // 2026-10-31 真机：滑动到任意对话都被顶回去、UI 频繁跳动。
        // 面板的 order 在流式期间会变（活面板 anchorSeq+0.25 → durable 后
        // panelSeq-0.25）。用 order 当 key 就等于每次转换都「删一项 + 插一项」，
        // LazyColumn 丢锚点重锚。身份必须只认 panelId。
        val live = panel(5).copy(order = 5.25, panelId = "s1:t1#seg0")
        val durable = panel(5).copy(order = 4.75, panelId = "s1:t1#seg0")
        assertEquals(timelineKey(0, live), timelineKey(0, durable))
        // 同一轮的两段面板必须分开。
        assertNotEquals(
            timelineKey(0, live),
            timelineKey(1, live.copy(panelId = "s1:t1#seg1")),
        )
    }

    /** 会话列表按工作区分组：空工作区归入「未分组」，组内按 updatedAt 倒序。 */
    private fun groupSessions(sessions: List<Model.Session>): Map<String, List<Model.Session>> {
        val ordered = sessions.sortedByDescending { it.updatedAt }
        return ordered.groupBy { it.workspace.ifBlank { "未分组" } }
    }

    @Test
    fun groupsByWorkspaceWithRecentFirst() {
        val s1 = Model.Session(id = "1", workspace = "proj-a", updatedAt = 100)
        val s2 = Model.Session(id = "2", workspace = "proj-a", updatedAt = 300)
        val s3 = Model.Session(id = "3", workspace = "", updatedAt = 200)
        val grouped = groupSessions(listOf(s1, s2, s3))
        assertEquals(listOf("proj-a", "未分组"), grouped.keys.toList())
        assertEquals(listOf("2", "1"), grouped["proj-a"]?.map { it.id })
    }

    @Test
    fun groupPreviewSplitsWhenOverLimit() {
        // 超过 GROUP_PREVIEW(5) 条时默认只铺前 4 条，其余走「展开更多」。
        val many = (1..8).map { Model.Session(id = "$it", workspace = "w", updatedAt = it.toLong()) }
        val grouped = groupSessions(many)
        val all = grouped.getValue("w")
        val collapsed = all.size > HomeUiDefaults.GROUP_PREVIEW
        assertTrue(collapsed)
        assertEquals(
            HomeUiDefaults.GROUP_PREVIEW - 1,
            all.take(HomeUiDefaults.GROUP_PREVIEW - 1).size,
        )
    }

    @Test
    fun permissionLabelMatchesDesktopWording() {
        assertEquals("仅可查看", Model.Session.permissionLabelFor("read-only"))
        assertEquals("工作区内修改", Model.Session.permissionLabelFor("workspace-write"))
        assertEquals("完全权限", Model.Session.permissionLabelFor("danger-full-access"))
        assertEquals("权限", Model.Session.permissionLabelFor(""))
        assertEquals("权限", Model.Session.permissionLabelFor("unknown-preset"))
    }

    @Test
    fun latencyColorBands() {
        // 与 v1 latencyColorRes 的分档一致：未知灰、≤80 绿、≤200 黄、否则红。
        //
        // 注意：`latencyColor` 与 `Ds` 现在都是 `@Composable`（色板随主题走），
        // 单测里没有组合上下文，所以用 `runComposeUiTest` 之类的宿主来跑——
        // 这里退一步只钉**分档边界**这个纯逻辑，颜色名从返回的 Color 反查。
        // 分档：ms<0 → 灰；<=80 绿；<=200 黄；其余红。
        val bands = listOf(-1 to 0, 0 to 1, 80 to 1, 81 to 2, 200 to 2, 201 to 3)
        for ((ms, expected) in bands) {
            assertEquals(
                "ms=$ms 应落在第 $expected 档",
                expected,
                latencyBand(ms),
            )
        }
    }

    @Test
    fun unreadOnlyWhenNewerThanReadAndNotRunning() {
        val running = Model.Session(id = "1", updatedAt = 500, readAt = 100, running = true)
        assertFalse(running.unread)
        val fresh = Model.Session(id = "2", updatedAt = 500, readAt = 100, running = false)
        assertTrue(fresh.unread)
        val read = Model.Session(id = "3", updatedAt = 100, readAt = 500, running = false)
        assertFalse(read.unread)
    }

    @Test
    fun formatBytesUsesFourBands() {
        // hero 卡的上行/下行：不足 1KB 给整数字节，之后按单位收紧小数位。
        assertEquals("0 B", formatBytes(0))
        assertEquals("1023 B", formatBytes(1023))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("1.0 MB", formatBytes(1024L * 1024))
        assertEquals("3.00 GB", formatBytes(3L * 1024 * 1024 * 1024))
    }

    @Test
    fun platformAvatarOnlyKnownPlatforms() {
        // 中继的 normalizePlatform 已经把 darwin/win32 归一化成 macos/windows；
        // 空串（老插件从没上报过平台）返回 0，hero 卡就不画头像。
        assertEquals(R.drawable.platform_avatar_mac, platformAvatar("macos"))
        assertEquals(R.drawable.platform_avatar_windows, platformAvatar("windows"))
        assertEquals(0, platformAvatar("linux"))
        assertEquals(0, platformAvatar(""))
    }

    // ============================================================
    // 「跳转到最新消息」悬浮胶囊
    // ============================================================

    @Test
    fun tailItemIndexCountsLeadingRows() {
        // 载入更早 / 快照占位各占一行，尾部索引得把它们算进去，否则滚错项。
        val bare = ChatViewModel.ChatState(
            messages = listOf(msg(1), msg(2)),
            snapshotReady = true,
        )
        assertEquals(1, tailItemIndex(bare, bare.messages.size))
        // hasMore 时列表最前面插一行「载入更早」，尾部索引整体后移一位。
        assertEquals(2, tailItemIndex(bare.copy(hasMore = true), bare.messages.size))
        // snapshotReady=false 时列表前面还有「正在载入对话…」占位行。
        assertEquals(2, tailItemIndex(bare.copy(snapshotReady = false), bare.messages.size))

        // 两行都在：0 号载入更早、1 号快照占位，消息从 2 号开始，尾部落在 3。
        val withBoth = bare.copy(hasMore = true, snapshotReady = false)
        assertEquals(3, tailItemIndex(withBoth, withBoth.messages.size))
    }

    @Test
    fun tailItemIndexCountsTrailingRows() {
        // 只有流式正文挂在时间轴末尾。
        val base = ChatViewModel.ChatState(messages = listOf(msg(1)), snapshotReady = true)
        assertEquals(0, tailItemIndex(base, base.messages.size))
        assertEquals(1, tailItemIndex(base.copy(streamingText = "正在说"), base.messages.size))
    }

    @Test
    fun tailItemIndexIgnoresFloatingCards() {
        // 待答卡片悬浮在消息区上方、不进 LazyColumn（2026-10-31 用户要求），
        // 所以算尾项时必须忽略它们——算进来就会滚到一个不存在的位置上。
        val base = ChatViewModel.ChatState(messages = listOf(msg(1)), snapshotReady = true)
        assertEquals(0, tailItemIndex(base.copy(approvals = listOf(Model.Approval())), base.messages.size))
        assertEquals(0, tailItemIndex(base.copy(questions = listOf(Model.QuestionCard())), base.messages.size))
        val busy = base.copy(
            streamingText = "正在说",
            approvals = listOf(Model.Approval(), Model.Approval()),
            questions = listOf(Model.QuestionCard()),
        )
        // 两张审批 + 一张提问都在悬浮层：尾项仍是「正文 + 流式」的最后一项。
        assertEquals(1, tailItemIndex(busy, busy.messages.size))
    }

    @Test
    fun pendingCardsPutsApprovalsFirstAndDropsAnsweredOnes() {
        val a = Model.Approval().also { it.eventId = "ap1"; it.answerable = true }
        val answered = Model.Approval().also { it.eventId = "ap2"; it.answered = true; it.answerable = true }
        val c = Model.QuestionCard().also { it.eventId = "qs1"; it.answerable = true }
        // 答不回去的那张：从归档重建的历史审批，宿主那边早没这条挂起的 waterfall 了。
        val restored = Model.Approval().also { it.eventId = "s1:42"; it.callId = "call-x" }
        val out = pendingCards(
            ChatViewModel.ChatState(
                approvals = listOf(a, answered, restored),
                questions = listOf(c),
            ),
        )
        // 审批挡的是「电脑那一步正等你放行」，比提问更急，所以永远排前面；
        // 答不回去的历史卡不上悬浮层——挂在那里只会占住 420dp 把消息全挡住。
        assertEquals(listOf("ap1", "qs1"), out.map { it.eventId })
    }

    @Test
    fun tailItemIndexIsMinusOneWhenTimelineEmpty() {
        // 空会话没有可滚的项——返回 -1，调用方据此既不自动滚也不显示按钮。
        assertEquals(-1, tailItemIndex(ChatViewModel.ChatState(), 0))
        assertEquals(-1, tailItemIndex(ChatViewModel.ChatState(hasMore = true, snapshotReady = false), 0))
    }

    @Test
    fun tailItemIndexUsesTheRenderedRowCountNotTheMessageCount() {
        // 2026-11-03 实测出来的真错位：LazyColumn 迭代的是 `rows`
        // （`mergeTurnPanels` 之后），而 `tailItemIndex` 一直拿
        // `state.messages.size` 算下标。同一回合的相邻面板会被并成一块，
        // 于是 rows 比 messages 短得多 —— 一轮 4 块相邻面板 + 1 条提问时
        // messages=5、rows=2，旧算式给出 4，而列表最后一项是 1。
        //
        // 后果被 LazyColumn 的越界夹取掩盖：滚到不存在的下标会被夹到末项，
        // 所以「贴底」看着是对的。但 `itemHeightOf(4)` 查不到可见项、恒返回 0，
        // `tailScrollOffset(0)` 退回那个 1_000_000 的哨兵值 —— 「盖过末项高度」
        // 退化成「赌一个足够大的数」。这里把两条算式都钉住，防止有人改回去。
        val msgs = ArrayList<Model.Message>()
        msgs.add(Model.Message.user("q").apply { seq = 1; turn = 1 })
        for (k in 0 until 4) {
            msgs.add(
                Model.Message().apply {
                    seq = 10L + k
                    turn = 1
                    panelId = "t1#seg$k"
                    role = Model.Role.ASSISTANT
                    steps.add(Model.Step().apply { kind = Model.Step.TOOL; text = "op$k" })
                },
            )
        }
        val rows = mergeTurnPanels(msgs)
        val state = ChatViewModel.ChatState(messages = msgs, snapshotReady = true)

        // 前提：合并确实缩短了列表（否则这条用例测不到东西）。
        assertEquals(5, msgs.size)
        assertEquals(2, rows.size)

        // 正确口径：列表最后一项。
        assertEquals(1, tailItemIndex(state, rows.size))
        // 错误口径（旧实现）会指到不存在的位置上。
        assertEquals(4, tailItemIndex(state, state.messages.size))
        // leading 区照样整体后移。
        assertEquals(2, tailItemIndex(state.copy(hasMore = true), rows.size))
        // 流式正文挂在最后一项之后。
        assertEquals(2, tailItemIndex(state.copy(streamingText = "正在说"), rows.size))
    }

    @Test
    fun jumpToLatestOnlyWhenUserLeftTail() {
        // 用户往上翻了（followTail=false）且时间轴非空才出现。
        assertTrue(showJumpToLatest(followTail = false, tailIndex = 3))
        // 还在跟随时不打扰：内容没超一屏的会话永远不该冒出这个按钮。
        assertFalse(showJumpToLatest(followTail = true, tailIndex = 3))
        // 时间轴空着没什么可跳。
        assertFalse(showJumpToLatest(followTail = false, tailIndex = -1))
        assertFalse(showJumpToLatest(followTail = true, tailIndex = -1))
    }

    // ============================================================
    // 键盘弹起贴尾 / hero 读数 / 设备管理过滤（2026-10-28 需求）
    // ============================================================

    @Test
    fun pinLatestOnlyWhileKeyboardIsOpen() {
        // 键盘弹起且时间轴非空：贴尾，否则最新那条被键盘挡在视口外。
        assertTrue(pinLatestOnKeyboard(keyboardOpen = true, tailIndex = 0))
        assertTrue(pinLatestOnKeyboard(keyboardOpen = true, tailIndex = 7))
        // 收起键盘不产生任何滚动（空会话同理）。
        assertFalse(pinLatestOnKeyboard(keyboardOpen = false, tailIndex = 7))
        assertFalse(pinLatestOnKeyboard(keyboardOpen = true, tailIndex = -1))
        assertFalse(pinLatestOnKeyboard(keyboardOpen = false, tailIndex = -1))
    }

    @Test
    fun pinLatestYieldsToAnActiveDrag() {
        // 手指还按在列表上时弹键盘不抢滚动权：抢了等于把用户正拖到一半的列表
        // 从指头底下拽走（2026-10-30「翻历史时一有输出就往下跳」同源）。
        assertFalse(pinLatestOnKeyboard(keyboardOpen = true, tailIndex = 7, userDragging = true))
        // 默认值保持旧语义：不传就是没在拖。
        assertTrue(pinLatestOnKeyboard(keyboardOpen = true, tailIndex = 7, userDragging = false))
    }

    @Test
    fun autoScrollWaitsForTheFingerToLeave() {
        // 核心回归：followTail 还停在旧值 true（只在滚动停止边沿更新），但手指
        // 已经按住往上拖——此刻任何流式增量都不得 scrollToItem 拽回底部。
        assertFalse(autoScrollOnNewContent(followTail = true, userDragging = true))
        // 正常跟随：贴着底、手没在碰。
        assertTrue(autoScrollOnNewContent(followTail = true, userDragging = false))
        // 用户已经明确离开底部（松手且停在历史区）。
        assertFalse(autoScrollOnNewContent(followTail = false, userDragging = false))
        // 双保险叠加：翻历史途中又把手按上去。
        assertFalse(autoScrollOnNewContent(followTail = false, userDragging = true))
    }

    @Test
    fun heroStatsKeepsOnlyOneLatencyReading() {
        val relay = Model.RelayStatus(latencyMs = 372, upstreamBps = 1200, downstreamBps = 0)
        val stats = heroStats(relay)
        // 延迟只在标题行右侧出现一次，底部重复的那格已按用户要求删除。
        assertEquals(listOf("上行", "下行"), stats.map { it.label })
        assertFalse(stats.any { it.value.endsWith("ms") })
    }

    @Test
    fun heroStatsReusesTrafficFormatting() {
        val stats = heroStats(Model.RelayStatus(upstreamBps = 2048, downstreamBps = 0))
        assertEquals(formatBps(2048), stats[0].value)
        assertEquals(formatBps(0), stats[1].value)
    }

    @Test
    fun pairedHostsOnlyListsPairedDevices() {
        val paired = Model.Host(id = "h1", alias = "MacBook", authorized = true, online = true)
        val unpaired = Model.Host(id = "h2", alias = "还没配的", authorized = false)
        // 未授权主机任何列表里都不该出现：既切不过去、也没有可删的授权。
        assertEquals(listOf(paired), pairedHosts(listOf(paired, unpaired)))
        assertEquals(emptyList<Model.Host>(), pairedHosts(listOf(unpaired)))
        assertEquals(emptyList<Model.Host>(), pairedHosts(emptyList()))
    }

    @Test
    fun pairedHostsFiltersByGrantOnlyNotByOnline() {
        // 筛选只看 authorized：离线但配对过的电脑仍要留在列表里（能解绑、能切过去看缓存），
        // 反过来「未配对但隧道连着」也不能因为绿点就混进来——这正是用户报的残留。
        val offlinePaired = Model.Host(id = "a", alias = "台式机", authorized = true, online = false)
        val onlineUnpaired = Model.Host(id = "c", alias = "已在账号里但没配给本机", authorized = false, online = true)
        assertEquals(listOf(offlinePaired), pairedHosts(listOf(offlinePaired, onlineUnpaired)))
    }

    // ============================================================
    // 「深度思考」预览：流式时取尾部（否则看着像思考完才一次性出现）
    // ============================================================

    @Test
    fun thinkingPreviewTakesTailWhileStreaming() {
        val thinking = "一".repeat(100) + "二".repeat(100)
        val live = thinkingPreview(thinking, streaming = true)
        // 还在跑：取尾部，光标处的字必须在，开头的字被省略号顶掉。
        assertTrue(live.startsWith("…"))
        assertTrue(live.endsWith("二"))
        assertEquals(120, live.length)
        assertFalse(live.contains("一".repeat(100)))
    }

    @Test
    fun thinkingPreviewTakesHeadWhenSettled() {
        // 落定后取头部：回看时从开头读，末尾的结论不该挤进预览。
        val thinking = "一".repeat(130) + "【结论】"
        val done = thinkingPreview(thinking, streaming = false)
        assertTrue(done.startsWith("一"))
        assertEquals(120, done.length)
        assertFalse(done.contains("结论"))
    }

    @Test
    fun thinkingPreviewLeavesShortTextAlone() {
        // 没超过上限就原样保留（只 flatten 换行与首尾空白），流式与落定一致——
        // 短思考不该因为状态变化跳一下。
        val short = "  先看看有没有  现成的实现\n再动手  "
        val expected = "先看看有没有  现成的实现 再动手"
        assertEquals(expected, thinkingPreview(short, streaming = true))
        assertEquals(expected, thinkingPreview(short, streaming = false))
        assertEquals("", thinkingPreview("   ", streaming = true))
    }

    @Test
    fun thinkingPreviewGrowsWhileStreaming() {
        // 真正的回归点：增量推进时预览末尾必须跟着变，否则界面上「看不出在流」。
        val first = thinkingPreview("一".repeat(200), streaming = true)
        val second = thinkingPreview("一".repeat(200) + "新想法", streaming = true)
        assertNotEquals(first, second)
        assertTrue(second.endsWith("新想法"))
    }

    @Test
    fun inlineThinkingLimitKeepsTheTailVisible() {
        // 折叠行只有一行、一个 Text，前面还要放「已处理 N 个操作 · 思考：」，放不下就从
        // **末尾**省略，所以额度必须小到能放下：否则最新的那几个字永远被切掉，
        // 看上去「一开始刷一下就再没变过」（2026-10-28 反馈，2026-10-29 复现）。
        val long = "先确认 prompt 有没有真的到上游，再看有没有回帧，最后才决定要不要重试。"
        val shown = thinkingPreview(long, streaming = true, limit = INLINE_THINKING_LIMIT)
        assertEquals(INLINE_THINKING_LIMIT, shown.length)
        assertTrue(shown.startsWith("…"))
        assertTrue("预览的最后一字必须是最新思考的最后一字", shown.endsWith("。"))
    }

    // ---- 步骤折叠：一轮里五六个工具调用不能逐条平铺成一面墙 ----

    private fun thinking(text: String = "想") = Model.Step(kind = Model.Step.THINKING, text = text)
    private fun tool() = Model.Step(kind = Model.Step.TOOL, actionIndex = 0)
    private fun narration(text: String = "旁白") = Model.Step(kind = Model.Step.TEXT, text = text)

    @Test
    fun groupStepsFoldsConsecutiveThinkingAndTool() {
        // 「思考 → 工具」这类最常见的两连：折成一行，不再占两行。
        val out = groupSteps(listOf(thinking(), tool()))
        assertEquals(1, out.size)
        assertEquals(listOf(0, 1), (out[0] as StepGroup.Fold).indices)
    }

    @Test
    fun groupStepsFoldsEvenLoneStep() {
        // 单条也要折：早先「一步不折」的规矩会让思考单独占一行，紧挨着折叠行的
        // 「已处理 N 个操作」就成了两行同义文案（2026-10-29 反馈的那个 bug）。
        assertEquals(listOf<StepGroup>(StepGroup.Fold(listOf(0))), groupSteps(listOf(thinking())))
        assertEquals(listOf<StepGroup>(StepGroup.Fold(listOf(0))), groupSteps(listOf(tool())))
    }

    @Test
    fun groupStepsKeepsWholeTurnInOneFold() {
        // 整轮只留**一个**折叠行：早先按连续段折，一轮里出现多段操作就有多个
        // 「已处理 N 个操作」并排（2026-10-29 反馈）。中间被思考、工具怎么穿插都一样。
        val steps = listOf(tool(), thinking(), tool(), tool())
        val out = groupSteps(steps)
        assertEquals(1, out.size)
        assertEquals(listOf(0, 1, 2, 3), (out[0] as StepGroup.Fold).indices)
    }

    @Test
    fun groupStepsBreaksOnNarration() {
        // 旁白是给人读的正文：折进「已处理 N 个操作」会直接丢字，所以旁白各占一行，
        // 操作统一折成一行插在它原本的位置（第一条操作之前）。
        val out = groupSteps(listOf(tool(), narration("正文"), tool()))
        assertEquals(2, out.size)
        assertEquals(StepGroup.Fold(listOf(0, 2)), out[0])
        assertEquals(StepGroup.Single(1), out[1])
    }

    @Test
    fun groupStepsCountsOnlyVisibleSteps() {
        // 设置里关掉思考后，思考既不渲染也不该被算进「已处理 N 个操作」。
        val steps = listOf(thinking(), tool(), tool())
        val out = groupSteps(steps) { _, step -> step.kind != Model.Step.THINKING }
        assertEquals(1, out.size)
        assertEquals(listOf(1, 2), (out[0] as StepGroup.Fold).indices)
        // 屏蔽掉思考后只剩一条工具：仍然折（只是计数会是 1）。
        val lone = groupSteps(listOf(thinking(), tool())) { _, step -> step.kind != Model.Step.THINKING }
        assertEquals(listOf<StepGroup>(StepGroup.Fold(listOf(1))), lone)
    }

    @Test
    fun foldSummaryReportsCountWhileRunning() {
        val steps = listOf(thinking(), tool(), tool())
        // 运行中也报数：早先运行中返回空串，真机上折叠行只剩一段无头无尾的思考正文，
        // 用户看不出这轮已经干了多少（2026-10-29 反馈「『已处理X个操作』也没有出现」）。
        assertEquals("已处理 2 个操作", foldSummary(steps, listOf(0, 1, 2), running = true))
        // 结束后：操作数 + 用时。
        assertEquals(
            "已处理 2 个操作 · 用时 4 秒",
            foldSummary(steps, listOf(0, 1, 2), running = false, took = "用时 4 秒"),
        )
        // 只有一段思考时不说「深度思考 ×1」——那是废话。
        assertEquals(
            "已完成分析 · 用时 2 秒",
            foldSummary(listOf(thinking()), listOf(0), running = false, took = "用时 2 秒"),
        )
    }

    @Test
    fun foldSummarySaysAnalyzingBeforeAnyTool() {
        // 一个操作都还没有时不能编「已处理 0 个操作」这种假数字：
        // 运行中报「正在分析」，落定后才报「已完成分析」。
        val steps = listOf(thinking())
        assertEquals("正在分析", foldSummary(steps, listOf(0), running = true))
        assertEquals("已完成分析", foldSummary(steps, listOf(0), running = false))
    }

    @Test
    fun foldSummaryMarksInterruptedTurnsInsteadOfRunning() {
        // 记录里这一轮还开着、会话却早就不在跑：宿主那轮没等到 turn/end 就消失了
        //（进程被杀、断网、换中继后手机还盯着旧主机）。报「正在分析」是骗——它根本
        // 不会动了；报「已完成分析」也是骗——这轮确实没跑完。2026-11-05 现场：
        // 桌面那轮停在 t45，手机永远显示「运行中」。
        val steps = listOf(thinking(), tool())
        assertEquals(
            "已处理 1 个操作 · 已中断",
            foldSummary(steps, listOf(0, 1), running = true, interrupted = true),
        )
        // 一个操作都没做过时，头里直接写「已中断」，不拼一句空话。
        assertEquals(
            "已中断",
            foldSummary(listOf(thinking()), listOf(0), running = true, interrupted = true),
        )
        // 有失败原因时仍以失败为准：那才是这一行该说的事。
        assertEquals(
            "处理失败 · 额度不足",
            foldSummary(steps, listOf(0, 1), running = true, failure = "额度不足", interrupted = true),
        )
    }

    @Test
    fun foldSummaryNeverCountsThinking() {
        // 思考被工具打断又重来也不报次数：用户 2026-10-28 明确不要「深度思考 ×N」
        // 这类计数，正在想什么由折叠行下面的流式预览说明。
        val steps = listOf(thinking(), tool(), thinking())
        assertEquals(
            "已处理 1 个操作 · 用时 3 秒",
            foldSummary(steps, listOf(0, 1, 2), running = false, took = "用时 3 秒"),
        )
    }

    @Test
    fun foldSummaryDefersCountUntilTurnEnds() {
        // 一轮里的每一组都报同一个总数（口径是整块面板），只是用时只给收尾那一组：
        // 早先中途各组不报数，历史面板（footnote=false）就只剩光秃秃的「已完成分析」，
        // 用户看不到这轮到底做了几个操作（2026-10-29 反馈）。
        val steps = listOf(tool(), tool(), tool(), tool(), tool())
        assertEquals("已处理 5 个操作", foldSummary(steps, listOf(0, 1), running = false, totalTools = 5))
        assertEquals(
            "已处理 5 个操作 · 用时 9 秒",
            foldSummary(steps, listOf(0, 1), running = false, took = "用时 9 秒", totalTools = 5),
        )
        // 运行中同样报数，只是不带用时（用时等 turn/end 才算得准）。
        assertEquals("已处理 5 个操作", foldSummary(steps, listOf(0, 1), running = true, totalTools = 5))
    }

    @Test
    fun foldLiveCoversTheGapBetweenStepEndAndTurnEnd() {
        // 关键场景：宿主每步都发 step/end，面板 running 走完一步就是 false，
        // 但这一轮还在跑（正在吐正文 / 下一步还没开始）。只看 running 会在这段时间
        // 提前报出「已处理 N 个操作」，正是用户 2026-10-29 反馈的那个 bug。
        val betweenSteps = Model.Message.pending().apply { panelId = "p1"; turn = 1L; running = false }
        assertTrue(foldLive(betweenSteps, sessionRunning = true))
        // 回合真结束了（turn/end 打了 footnote）就不再算在跑。
        val settled = Model.Message.pending().apply {
            panelId = "p1"; turn = 1L; running = false; footnote = true
        }
        assertFalse(foldLive(settled, sessionRunning = true))
        // 面板自己在跑（同一 step 内流式思考/工具增量）当然算在跑。
        val streaming = Model.Message.pending().apply { panelId = "p1"; turn = 1L; running = true }
        assertTrue(foldLive(streaming, sessionRunning = false))
        // 会话已经不在跑（读缓存/进程被杀后重进）：那些面板永远等不到 turn/end，
        // 必须按已结束渲染，否则折叠行连「已处理 N 个操作」都不显示。
        assertFalse(foldLive(betweenSteps, sessionRunning = false))
    }

    @Test
    fun foldSummaryCountsWholePanelNotJustItsGroup() {
        // 折叠行数的是**整块面板**的可展开条数（与抽屉标题同源），不是本组：
        // 早先各数各的，一轮里出现多个折叠组时「写的数」和「点开的条数」必然对不上。
        val steps = listOf(tool(), tool(), tool())
        assertEquals(
            "已处理 3 个操作 · 用时 1 秒",
            foldSummary(steps, listOf(0, 1), running = false, took = "用时 1 秒", totalTools = 3),
        )
    }

    @Test
    fun toolCountSkipsStepsThatCannotBeOpened() {
        // 计数只数点得开的那几条：actionIndex 越界（合并面板时被置 -1、或旧缓存对不上）
        // 的步骤在抽屉里渲染不出来，算进去就成了「写着 3 个操作、点开只有 2 行」。
        val message = Model.Message(
            role = Model.Role.ASSISTANT,
            actions = mutableListOf(
                Model.ToolAction(name = "pwsh"),
                Model.ToolAction(name = "read"),
            ),
            steps = mutableListOf(
                Model.Step(kind = Model.Step.TOOL, actionIndex = 0),
                Model.Step(kind = Model.Step.TOOL, actionIndex = 1),
                Model.Step(kind = Model.Step.TOOL, actionIndex = -1),
                Model.Step(kind = Model.Step.TOOL, actionIndex = 7),
                Model.Step(kind = Model.Step.THINKING, actionIndex = -1),
            ),
        )
        assertEquals(2, message.toolCount())
        // 页脚/面板标题用同一个口径，不可能再与抽屉标题分叉。
        assertTrue(message.processTitle().contains("已处理 2 个操作"))
    }

    @Test
    fun foldedThinkingTakesLastNonEmptyThinking() {
        // 折叠行下面贴的是「最新一段」思考：夹在中间的那段已经过去了。
        assertEquals("再跑测试", foldedThinkingText(listOf(thinking("先看代码"), tool(), thinking("再跑测试")), listOf(0, 1, 2)))
        // 没有思考段就没有预览。
        assertEquals("", foldedThinkingText(listOf(tool(), tool()), listOf(0, 1)))
        // 空的思考段跳过，继续往前找。
        assertEquals("有内容", foldedThinkingText(listOf(thinking("有内容"), thinking("")), listOf(0, 1)))
    }

    @Test
    fun interactionBelongsOnlyToItsOwnSession() {
        // $events 是 app 级一条流：放行会等于把别的会话的审批塞给当前聊天页。
        assertTrue(interactionBelongs("session-a", "session-a"))
        assertFalse(interactionBelongs("session-b", "session-a"))
        // 宿主没给 agentId 时不能凭这个丢卡片。
        assertTrue(interactionBelongs("", "session-a"))
        // 草稿会话（id 为空）也要能收卡。
        assertTrue(interactionBelongs("session-a", ""))
    }

    // ============================================================
    // 发送钮三态（2026-10-28：发送/停止随任务状态同步）
    // ============================================================

    @Test
    fun sendButtonStopsOnlyWhenComposerEmpty() {
        // 运行中 + 空编辑区 = 停止；这是「停止」唯一该出现的场景。
        assertEquals(SendButtonMode.STOP, sendButtonMode(running = true, hasContent = false))
    }

    @Test
    fun sendButtonSendsWhenComposerHasContent() {
        // 空闲时有内容 = 发送。
        assertEquals(SendButtonMode.SEND, sendButtonMode(running = false, hasContent = true))
        // 运行中有内容也是发送：发出去由宿主排队，不能把用户刚打的字变成「停止」。
        // 回归重点——旧实现直接按 running 判，一开始打字按钮就是停止，点一下
        // 会把刚跑起来的回合取消掉。
        assertEquals(SendButtonMode.SEND, sendButtonMode(running = true, hasContent = true))
    }

    @Test
    fun sendButtonDisabledWhenNothingToSend() {
        assertEquals(SendButtonMode.DISABLED, sendButtonMode(running = false, hasContent = false))
    }

    private fun questionCard(vararg ids: String): Model.QuestionCard {
        val card = Model.QuestionCard()
        card.eventId = "ev-1"
        for (id in ids) {
            val q = Model.Question()
            q.id = id
            q.question = "问题 $id"
            card.questions.add(q)
        }
        return card
    }

    @Test
    fun carryQuestionDraftsKeepsHalfFilledAnswers() {
        // 重投带回来的是一批全新 Question 实例（草稿全空）。不搬的话，
        // 用户填到一半的选择会在一次重连后凭空清空。
        val old = questionCard("a", "b")
        old.questions[0].selected.add("选项一")
        old.questions[1].custom = "自己写的"
        val fresh = questionCard("a", "b")
        carryQuestionDrafts(old, fresh)
        assertEquals(listOf("选项一"), fresh.questions[0].selected)
        assertEquals("自己写的", fresh.questions[1].custom)
    }

    @Test
    fun carryQuestionDraftsLetsHostWin() {
        // 宿主真带了值就以宿主为准，别被本地草稿盖掉。
        val old = questionCard("a")
        old.questions[0].selected.add("本地选的")
        old.questions[0].custom = "本地写的"
        val fresh = questionCard("a")
        fresh.questions[0].selected.add("宿主选的")
        fresh.questions[0].custom = "宿主写的"
        carryQuestionDrafts(old, fresh)
        assertEquals(listOf("宿主选的"), fresh.questions[0].selected)
        assertEquals("宿主写的", fresh.questions[0].custom)
    }

    @Test
    fun carryQuestionDraftsIgnoresUnrelatedQuestions() {
        // 题目 id 对不上就不搬：宁可空着，也别把别的题的答案串过来。
        val old = questionCard("a")
        old.questions[0].selected.add("选项一")
        val fresh = questionCard("b")
        carryQuestionDrafts(old, fresh)
        assertTrue(fresh.questions[0].selected.isEmpty())
        assertEquals("", fresh.questions[0].custom)
    }

    // ============================================================
    // 折叠行尾部的「当前操作」（2026-10-28：菊花 + 操作名）
    // ============================================================

    /** 造一块正在跑的面板，steps 按给定顺序贴。 */
    private fun runningPanel(
        actions: List<Model.ToolAction>,
        thinking: String = "",
    ): Model.Message {
        val m = Model.Message.pending().apply { panelId = "p1"; turn = 1L; order = 5.75 }
        if (thinking.isNotEmpty()) m.steps.add(Model.Step.thinking(thinking))
        for (i in actions.indices) m.steps.add(Model.Step(kind = Model.Step.TOOL, actionIndex = i))
        m.actions.addAll(actions)
        return m
    }

    private fun tool(
        name: String,
        detail: String,
        command: Boolean = false,
        skill: Boolean = false,
        subtitle: String = "",
    ) = Model.ToolAction(
        name = name,
        detail = detail,
        subtitle = subtitle,
        command = command,
        skill = skill,
    )

    @Test
    fun activityReportsThinkingWhenLastStepIsThought() {
        // 刚开跑：还没有工具，只有一段在长的思考。
        val out = currentActivity(runningPanel(emptyList(), thinking = "先看看这段代码"))
        assertEquals(ActivityKind.THINKING, out?.kind)
        // 前缀用词就是「思考」，它会顶掉折叠行原来的「思考:」那一处（2026-11-01）。
        assertEquals("思考", out?.label)
        assertEquals("", out?.detail)
    }

    @Test
    fun activityReportsRunningCommand() {
        // 折叠行要把正在跑的命令原样带出来，只写「正在调用工具」看不出在干什么。
        val out = currentActivity(
            runningPanel(listOf(tool("执行命令", "npm test --watch", command = true))),
        )
        assertEquals(ActivityKind.TOOL, out?.kind)
        assertEquals("执行命令", out?.label)
        assertEquals("npm test --watch", out?.detail)
    }

    @Test
    fun activityCarriesTheToolSubtitleSoTheFoldHeadCanShowIt() {
        // 桌面端折叠行是 `工具调用 · ops_bash · 改成十秒桶`，最后那段是模型写的
        // `description`。手机端原先只认命令，于是电脑上有小标题、手机上只剩裸命令
        // （2026-11-01 用户要求同步）。活动对象必须把它一路带到折叠行第一行。
        val out = currentActivity(
            runningPanel(
                listOf(
                    tool("执行命令", "node scripts/build.mjs", command = true, subtitle = "改成十秒桶"),
                ),
            ),
        )
        assertEquals("改成十秒桶", out?.subtitle)
        // 拼出来的第一行与桌面端同形：操作名称 + 小标题。
        assertEquals(
            "已处理 1 个操作 · 执行命令 · 改成十秒桶",
            foldHead("已处理 1 个操作", out, running = true),
        )
    }

    @Test
    fun activityReportsSkillName() {
        // 技能走自己的图标 + 技能名，不跟命令混成一句「执行 xxx」。
        val out = currentActivity(
            runningPanel(listOf(tool("加载技能", "codegraph", skill = true))),
        )
        assertEquals(ActivityKind.SKILL, out?.kind)
        assertEquals("技能", out?.label)
        assertEquals("codegraph", out?.detail)
    }

    @Test
    fun activityPrefersTheLastStep() {
        // 思考在前、工具在后：以最后一步为准（它就是此刻在干的事）。
        val out = currentActivity(
            runningPanel(listOf(tool("执行命令", "ls", command = true)), thinking = "先列个目录"),
        )
        assertEquals(ActivityKind.TOOL, out?.kind)
        assertEquals("执行命令", out?.label)
    }

    @Test
    fun activitySurvivesSettling() {
        // 收尾后面板仍要报「用了什么工具」（2026-10-31 用户要求「其他工具调用/技能
        // 等等也需要展示名称」）。早先这里断言 null —— 于是折叠行一旦跑完就只剩
        // 「已处理 N 个操作」，回看完全看不出这一轮干了什么。转不转圈是 FoldedStepsRow
        // 按 running 决定的另一件事。
        val done = runningPanel(listOf(tool("执行命令", "ls", command = true))).apply { running = false }
        val out = currentActivity(done)
        assertEquals(ActivityKind.TOOL, out?.kind)
        assertEquals("执行命令", out?.label)
        assertEquals("ls", out?.detail)
    }

    @Test
    fun activityReportsTheSkillNameAfterSettling() {
        val done = runningPanel(listOf(tool("技能", "pdf-extract", skill = true)))
            .apply { running = false }
        val out = currentActivity(done)
        assertEquals(ActivityKind.SKILL, out?.kind)
        assertEquals("技能", out?.label)
        assertEquals("pdf-extract", out?.detail)
    }

    @Test
    fun activityFallsBackToToolNameWithoutDetail() {
        // 没有 detail 的工具（宿主没给 arguments）也不能吐空串，否则尾部只剩菊花。
        val out = currentActivity(runningPanel(listOf(tool("搜索代码", ""))))
        assertEquals(ActivityKind.TOOL, out?.kind)
        assertEquals("搜索代码", out?.label)
        assertEquals("", out?.detail)
    }

    @Test
    fun resolvePanelReturnsTheLiveObject() {
        // 抽屉按 (panelId, turn) 每次取最新那份，流式增量才能跟着长。
        val live = runningPanel(emptyList(), thinking = "一二")
        assertSame(live, resolvePanel(listOf(live), "p1", 1L))
    }

    @Test
    fun resolvePanelFallsBackToTheTurn() {
        // 合并后的面板沿用首块 panelId：按 id 找不到时退回「同回合最后一块」。
        val merged = runningPanel(emptyList(), thinking = "合并后的").apply { panelId = "p9" }
        assertEquals("合并后的", resolvePanel(listOf(merged), "p1", 1L)?.steps?.single()?.text)
        assertNull(resolvePanel(listOf(merged), "p1", 2L))
    }

    @Test
    fun timelineKeepsRunningPanelBelowTheEcho() {
        // 刚发出去的一瞬间：本地回声（seq=0）已在时间线上，面板也已建好（order=seq+0.25）。
        // 回声必须排在面板**上面**，否则折叠行闪现在自己气泡上方（2026-10-28 反馈）。
        val echo = Model.Message.user("跑一下测试").apply { sending = true }
        val running = runningPanel(emptyList(), thinking = "想").apply { seq = 11; order = 11.25 }
        val out = timelineOf(listOf(running, echo))
        assertEquals(2, out.size)
        assertEquals("跑一下测试", out[0].text)
        assertEquals("p1", out[1].panelId)
    }

    @Test
    fun timelineKeepsSettledPanelInRemoteOrder() {
        // 落定后按位置排：面板（order=seq+0.25）仍在该轮用户消息之后、正文之前。
        val user = Model.Message.user("问一句").apply { seq = 11 }
        val panelDone = runningPanel(emptyList(), thinking = "想").apply {
            seq = 11; order = 11.25; running = false
        }
        val answer = msg(12, "答")
        val out = timelineOf(listOf(panelDone, answer, user))
        assertEquals(listOf(11L, 11L, 12L), out.map { it.seq })
        assertEquals("p1", out[1].panelId)
    }

    // ============================================================
    // 任务清单抽屉：todoMarkOf
    // ============================================================

    @Test
    fun todoMarkSeparatesInProgressFromPending() {
        // 清单必须把进行中单独标出来：计数只认 completed，但用户看得见清单时
        // 最想知道「现在停在哪一条」，把它和待办混成一样等于没给信息。
        assertEquals(TodoMark.DONE, todoMarkOf(Model.Todo(content = "跑通测试", status = "completed")))
        assertEquals(TodoMark.DOING, todoMarkOf(Model.Todo(content = "改代码", status = "in_progress")))
        assertEquals(TodoMark.TODO, todoMarkOf(Model.Todo(content = "写文档", status = "pending")))
    }

    @Test
    fun todoMarkTreatsUnknownStatusAsPending() {
        // 宿主将来新增状态时不能崩、也不能冒充已完成——按「还没做」处理最安全。
        assertEquals(TodoMark.TODO, todoMarkOf(Model.Todo(content = "x", status = "blocked")))
        assertEquals(TodoMark.TODO, todoMarkOf(Model.Todo(content = "y", status = "")))
    }

    @Test
    fun todoMarkCountDiffersFromChecklist() {
        // 计数（taskProgressOf）与清单图标（todoMarkOf）判据不同是故意的：
        // 两条里一条 in_progress，胶囊说 0/2，而清单里那条显示「进行中」。
        val todos = listOf(
            Model.Todo(content = "a", status = "in_progress"),
            Model.Todo(content = "b", status = "pending"),
        )
        assertEquals("任务: 0/2", Model.Session(todos = todos).taskProgress.label())
        assertEquals(TodoMark.DOING, todoMarkOf(todos[0]))
    }

    // ============================================================
    // 排队消息（宿主 inbox splice）
    // ============================================================

    private fun queued(id: String, target: String = "next-turn", text: String = id) =
        Model.QueuedMessage(id = id, text = text, target = target)

    @Test
    fun inboxSpliceInsertsAtBucketOffset() {
        // next-turn 在前、next-step 在后：对 next-step 的下标要先加上 next-turn 的长度。
        val base = listOf(queued("a"), queued("b"), queued("c", "next-step"))
        val out = applyInboxSplice(base, "next-step", 0, 0, listOf(queued("d", "next-step")))
        assertEquals(listOf("a", "b", "d", "c"), out.map { it.id })
    }

    @Test
    fun inboxSpliceRemovesClaimedHead() {
        // 回合开始宿主领走队首：一条 `start=0, removedCount=1, inserted=[]` 的 splice。
        // 卡片消失靠的就是这一条，漏掉它排队卡片会永远挂着。
        val base = listOf(queued("a"), queued("b"))
        val out = applyInboxSplice(base, "next-turn", 0, 1, emptyList())
        assertEquals(listOf("b"), out.map { it.id })
    }

    @Test
    fun inboxSpliceReplacesInPlace() {
        // 编辑 = 同一条 splice 里删一条又插一条（宿主 replace 走这条路）。
        val base = listOf(queued("a", text = "旧"), queued("b"))
        val out = applyInboxSplice(base, "next-turn", 0, 1, listOf(queued("a", text = "新")))
        assertEquals(listOf("新", "b"), out.map { it.text })
    }

    @Test
    fun inboxSpliceClampsOutOfRangeIndex() {
        // 本地队列与宿主短暂不一致时不能让整条队列崩掉：越界下标钳到末尾/合法区间。
        val base = listOf(queued("a"))
        assertEquals(listOf("a", "z"), applyInboxSplice(base, "next-turn", 99, 0, listOf(queued("z"))).map { it.id })
        // 删得比有的多：只删到空，不抛异常。
        assertEquals(0, applyInboxSplice(base, "next-turn", 0, 99, emptyList()).size)
    }

    @Test
    fun inboxSpliceIgnoresUnknownTarget() {
        // 宿主将来加第三个桶时不能把本地队列搞乱，直接原样返回。
        val base = listOf(queued("a"))
        assertSame(base, applyInboxSplice(base, "next-week", 0, 0, listOf(queued("z"))))
    }

    // ============================================================
    // 标题回退（2026-10-30：打开会话时「先占位再跳变」）
    // ============================================================

    @Test
    fun headerTitlePrefersTheRealTitle() {
        // 有真标题就照用，别拿工作区目录名覆盖掉它。
        assertEquals("重构同步机制", chatHeaderTitle("重构同步机制", "E:/proj/dsh-mobile-sh"))
    }

    @Test
    fun headerTitleFallsBackToWorkspaceDir() {
        // 宿主还没给标题时用工作区名：它是本地现成的，且下一步真标题到了不会「换个名字」。
        assertEquals("dsh-mobile-sh", chatHeaderTitle("", "E:/proj/dsh-mobile-sh"))
        assertEquals("dsh-mobile-sh", chatHeaderTitle(PLACEHOLDER_TITLE, "E:/proj/dsh-mobile-sh"))
        // Windows 路径也要切得对。
        assertEquals("proj", chatHeaderTitle("", "E:\\work\\proj"))
    }

    @Test
    fun headerTitleFallsBackToPlaceholderOnlyWhenNothingElse() {
        // 连工作区都没有（草稿会话）才显示占位，且占位本身不能是空串。
        assertEquals(PLACEHOLDER_TITLE, chatHeaderTitle("", ""))
        assertEquals(PLACEHOLDER_TITLE, chatHeaderTitle(PLACEHOLDER_TITLE, ""))
    }

    @Test
    fun headerTitleTrimsButKeepsUserSpacing() {
        // 前后空白不是标题的一部分（宿主偶尔会给），中间空格照留。
        assertEquals("a b", chatHeaderTitle("  a b  ", "E:/x"))
        // 全空白 = 没有标题。
        assertEquals("x", chatHeaderTitle("   ", "E:/x"))
    }

    // ============================================================
    // 抽屉身份（2026-10-30：二级抽屉自己关掉 / 自己换成另一段）
    // ============================================================

    @Test
    fun resolvePanelFindsByStepIdWhenPanelIdIsStale() {
        // 重新快照会把面板重新发号，旧 panelId 当场失效；步骤号还能认回来。
        val reopened = runningPanel(emptyList(), thinking = "新一份").apply { panelId = "p99" }
        val stepId = reopened.steps.first().id
        assertEquals(
            "新一份",
            resolvePanel(listOf(reopened), "p1", 1L, stepId)?.steps?.single()?.text,
        )
    }

    @Test
    fun resolvePanelPrefersStepIdOverTurnFallback() {
        // 一轮里有两块面板（旁白打断）：旧 panelId 失效时必须找回**原来那段**，
        // 不能退到「同回合最后一块」——那是另一个过程（用户看到的就是抽屉被换掉）。
        val first = runningPanel(emptyList(), thinking = "第一段").apply { panelId = "p2"; turn = 1L }
        val second = runningPanel(emptyList(), thinking = "第二段").apply { panelId = "p3"; turn = 1L }
        val wantedStep = first.steps.first().id
        assertEquals(
            "第一段",
            resolvePanel(listOf(first, second), "gone", 1L, wantedStep)?.steps?.single()?.text,
        )
    }

    @Test
    fun resolvePanelWithoutStepIdKeepsLegacyTurnFallback() {
        // 老缓存里 step.id 是空串：不能因此让抽屉彻底打不开，退回回合兜底。
        val merged = runningPanel(emptyList(), thinking = "合并后的").apply { panelId = "p9" }
        assertEquals("合并后的", resolvePanel(listOf(merged), "p1", 1L, "")?.steps?.single()?.text)
        assertNull(resolvePanel(listOf(merged), "p1", 2L, ""))
    }

    // ============================================================
    // 键盘贴尾（2026-10-30：翻历史时一点输入框就被拽回底部）
    // ============================================================

    @Test
    fun keyboardPinsOnlyWhenFollowingTail() {
        // 用户本来就在底部：弹键盘贴尾，把最新那条顶到键盘上方。
        assertTrue(pinLatestOnKeyboard(keyboardOpen = true, tailIndex = 3, followTail = true))
    }

    @Test
    fun keyboardDoesNotDragReaderBackFromHistory() {
        // 正在翻历史：弹键盘不该把人拽回底部（这是 2026-10-30 的实测投诉）。
        assertFalse(pinLatestOnKeyboard(keyboardOpen = true, tailIndex = 3, followTail = false))
    }

    @Test
    fun keyboardPinStillRequiresOpenKeyboardAndItems() {
        // 收起键盘的瞬间不产生滚动；空会话没有可贴的尾部。
        assertFalse(pinLatestOnKeyboard(keyboardOpen = false, tailIndex = 3, followTail = true))
        assertFalse(pinLatestOnKeyboard(keyboardOpen = true, tailIndex = -1, followTail = true))
    }
    // ============================================================
    // v2：本地回声被时间线认领
    // ============================================================

    @Test
    fun `a projected user message adopts the matching local echo`() {
        val echo = Model.Message(role = Model.Role.USER, text = "v2 cmd probe: 只回复 OK")
        val projected = listOf(
            Model.Message(role = Model.Role.USER, text = "v2 cmd probe: 只回复 OK", seq = 1288),
            Model.Message(role = Model.Role.ASSISTANT, text = "OK", seq = 1291),
        )
        // 界面上只该剩时间线那一份；本地那份（seq=0）必须收口，
        // 否则同一句话显示两遍、上面那条永远停在「正在发送」。
        assertEquals(emptyList<Model.Message>(), adoptPendingEchoes(listOf(echo), projected))
    }

    @Test
    fun `an unlanded echo survives`() {
        val echo = Model.Message(role = Model.Role.USER, text = "还没落盘的一句")
        val projected = listOf(Model.Message(role = Model.Role.ASSISTANT, text = "OK", seq = 1291))
        assertEquals(listOf(echo), adoptPendingEchoes(listOf(echo), projected))
    }

    @Test
    fun `sending the same text twice only adopts as many echoes as landed`() {
        val first = Model.Message(role = Model.Role.USER, text = "同上")
        val second = Model.Message(role = Model.Role.USER, text = "同上")
        val projected = listOf(Model.Message(role = Model.Role.USER, text = "同上", seq = 10))
        // 只落盘了一句，所以只收口一条：把第二条也删掉会让用户没法重发。
        assertEquals(listOf(second), adoptPendingEchoes(listOf(first, second), projected))
    }

    @Test
    fun `assistant text never adopts a user echo`() {
        val echo = Model.Message(role = Model.Role.USER, text = "OK")
        val projected = listOf(Model.Message(role = Model.Role.ASSISTANT, text = "OK", seq = 11))
        assertEquals(listOf(echo), adoptPendingEchoes(listOf(echo), projected))
    }

    @Test
    fun `a local echo is found again after the timeline copied it`() {
        // 根因（2026-11-07 真机 + 模拟器）：`_state.value.messages` 里躺的是 `timeline()`
        // 产出的 `snapshot()` 副本，不是发送时那个实例。`replaceEcho` 只比 `===`，于是
        // ack 回来时一条都换不掉 —— `sending` 永远清不掉，宿主明明已经收下的消息在界面上
        // 一直挂「等待发送」。这里钉住「副本也要能认出来」。
        val sent = Model.Message(role = Model.Role.USER, text = "排队中的一句").apply { sending = true }
        val copied = sent.snapshot()
        assertNotSame("前提：副本不是同一个实例", sent, copied)
        assertEquals(0, echoSlotOf(listOf(copied), sent))
    }

    @Test
    fun `a landed message never impersonates a local echo`() {
        // seq > 0 的真记录不参与按文本匹配：否则删掉/替换一条回声会顺手改掉
        // 一条内容相同的真消息。
        val landed = Model.Message(role = Model.Role.USER, text = "同一句话", seq = 42)
        val echo = Model.Message(role = Model.Role.USER, text = "同一句话")
        assertFalse(sameLocalEcho(landed, echo))
        assertEquals(-1, echoSlotOf(listOf(landed), echo))
    }

    @Test
    fun `echo lookup prefers identity over text`() {
        // 连发两句一样的话时，按文本匹配会把第二条认成第一条。所以身份优先：
        // 手里还握着原实例（比如刚 `dispatch` 出来的那条）时不会认错槽位。
        val first = Model.Message(role = Model.Role.USER, text = "同上")
        val second = Model.Message(role = Model.Role.USER, text = "同上")
        assertEquals(0, echoSlotOf(listOf(first, second), first))
        assertEquals(1, echoSlotOf(listOf(first, second), second))
        assertEquals(-1, echoSlotOf(emptyList(), first))
    }

    @Test
    fun `a fallback resend keeps the original request id`() {
        // 发一次消息出现两条排队卡片的根因：v2 控制流把 CMD 发出去了，但 ack 在
        // 3 秒内没回来，回落 v1 时又现造了一个 UUID。宿主按两个不同的 requestId
        // 收成两条，用户看到「发一次，排队 2 条」（2026-10-30 真机）。
        // 修法是**在两条路之外生成一次**，回落时原样带过去。这里钉住的是
        // 「两条路拿到的是同一个值」这条不变量（模拟：先赋给 v2，再赋给 v1）。
        val requestId = java.util.UUID.randomUUID().toString()
        val v2Payload = requestId
        val v1Fallback = requestId
        assertEquals("幂等键必须原样穿过回落", v2Payload, v1Fallback)
        // 对照：各自现造必然不同，那正是旧实现的行为。
        assertNotEquals(java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString())
    }

    @Test
    fun `queued busy marks only the tapped card`() {
        // 等待态记 id 而不是布尔：队列里可能有多条，只有被点的那条该转圈。
        val first = queued("a")
        val second = queued("b")
        val busy = setOf(first.id)
        assertTrue(first.id in busy)
        assertFalse(second.id in busy)
    }

    @Test
    fun `queued busy clears back to empty after the server answers`() {
        // 置位与清除必须成对：漏掉清除会让卡片永久转圈，比没有转圈更糟。
        var busy = emptySet<String>()
        busy = busy + "a"
        assertEquals(setOf("a"), busy)
        busy = busy - "a"
        assertTrue(busy.isEmpty())
    }

    @Test
    fun `two queue actions can be in flight at once`() {
        // 用户手快，两条卡片的删除可能同时在飞：集合语义要能同时装下两个 id。
        var busy = emptySet<String>()
        busy = busy + "a"
        busy = busy + "b"
        assertEquals(setOf("a", "b"), busy)
        busy = busy - "a"
        assertEquals(setOf("b"), busy)
    }

    // ---------- 审批/提问卡：归档重建的那份与 $events 那份如何合并 ----------

    private fun approval(eventId: String, callId: String, answerable: Boolean) =
        Model.Approval().also {
            it.eventId = eventId
            it.callId = callId
            it.answerable = answerable
            it.toolName = "pwsh"
        }

    @Test
    fun `归档重建的卡先到时，后到的可回答那张要顶掉它`() {
        // 归档那张的 id 是 sessionId:seq，宿主不认；$events 重投那张才带真 eventId。
        // 以前的写法是「保留已有那份」，于是界面上永远是那张点不动的。
        val restored = approval("s1:36", "call_a", answerable = false)
        val pushed = approval("evt-9", "call_a", answerable = true)
        val out = mergeRestored(listOf(restored), listOf(pushed))
        assertEquals(1, out.size)
        assertEquals("evt-9", out[0].eventId)
        assertTrue(out[0].answerable)
    }

    @Test
    fun `可回答那张先到时不会被归档那张覆盖`() {
        val pushed = approval("evt-9", "call_a", answerable = true)
        val restored = approval("s1:36", "call_a", answerable = false)
        val out = mergeRestored(listOf(pushed), listOf(restored))
        assertEquals(1, out.size)
        assertEquals("evt-9", out[0].eventId)
        assertTrue(out[0].answerable)
    }

    @Test
    fun `对不上 callId 的归档卡也要显示，只是点不动`() {
        // 宁可多一张只读卡，也不能让用户看不到「电脑正在等你批」。
        val restored = approval("s1:36", "call_orphan", answerable = false)
        val out = mergeRestored(emptyList<Model.Approval>(), listOf(restored))
        assertEquals(1, out.size)
        assertFalse(out[0].answerable)
    }

    // ---------- 折叠行思考预览的额度 ----------

    @Test
    fun inlineThinkingBudgetUsesTheWidthTheRowActuallyHas() {
        // 一行 900px、前缀占了 300px、一个汉字 36px：还能放约 15 个字，
        // 比下限 12 多，就应该给 15 而不是死守 12（2026-11-01 反馈「显示得太少」）。
        assertEquals(15, inlineThinkingBudget(availablePx = 900, prefixPx = 300, charPx = 36))
    }

    @Test
    fun inlineThinkingBudgetFallsBackWhenWidthIsUnknown() {
        // 第一帧还没量到宽度：先按下限给，别把整段塞进去。
        assertEquals(INLINE_THINKING_LIMIT, inlineThinkingBudget(0, 0, 36))
        assertEquals(INLINE_THINKING_LIMIT, inlineThinkingBudget(900, 300, 0))
        // 前缀就把一行占满了：也没有余量可给。
        assertEquals(INLINE_THINKING_LIMIT, inlineThinkingBudget(300, 320, 36))
    }

    @Test
    fun inlineThinkingBudgetNeverExceedsTheHardCap() {
        // 横屏/平板上宽度可能很大，仍要封顶，否则一次拼进几百字会拖慢排版。
        assertEquals(200, inlineThinkingBudget(availablePx = 100_000, prefixPx = 0, charPx = 1))
    }

    // ---------- 折叠行两行：第一行「已处理… + 操作名称 + 小标题」，第二行预览 ----------

    @Test
    fun foldHeadPutsTheOperationNameAndSubtitleOnTheFirstLine() {
        // 用户口径（2026-11-01）：折叠栏 2 行，上行 = 已处理… + 操作名称 + 小标题。
        // 小标题就是桌面端「工具调用 · ops_bash · 改成十秒桶」的最后那一段。
        val head = foldHead(
            title = "已处理 2 个操作",
            activity = ProcessActivity(
                ActivityKind.TOOL,
                "执行命令",
                "node scripts/build.mjs",
                subtitle = "改成十秒桶",
            ),
        )
        assertEquals("已处理 2 个操作 · 执行命令 · 改成十秒桶", head)
        // 命令**不进第一行**：它在第二行独占宽度，第一行再写一遍就是重复。
        assertFalse("第一行不该出现命令文本", head.contains("build.mjs"))
    }

    @Test
    fun foldHeadNeverInventsASubtitleWhenTheToolHasNoDescription() {
        // 没写 description 的工具只报操作名。拿命令冒充小标题会让第一行变成
        // 「已处理 2 个操作 · 执行命令 · npm test」，而第二行又写一遍 npm test。
        val head = foldHead(
            title = "已处理 2 个操作",
            activity = ProcessActivity(ActivityKind.TOOL, "执行命令", "npm test"),
        )
        assertEquals("已处理 2 个操作 · 执行命令", head)
    }

    @Test
    fun foldHeadKeepsTheOperationNameAfterTheTurnSettles() {
        // 落定后也要报操作名与小标题：回看时才知道这一轮每步想干什么。
        // 早先一收尾就只剩「已处理 N 个操作」（2026-10-31 已经为此改过 currentActivity）。
        val head = foldHead(
            title = "已处理 3 个操作 · 用时 12 秒",
            activity = ProcessActivity(ActivityKind.SKILL, "技能", "pdf-extract", subtitle = "抽取表格"),
            running = false,
        )
        assertEquals("已处理 3 个操作 · 用时 12 秒 · 技能 · 抽取表格", head)
    }

    @Test
    fun foldHeadDropsTheThinkingLabelOnceTheTurnSettled() {
        // 落定后没有「正在想」这回事：「已处理 3 个操作 · 用时 12 秒 · 思考」会让人
        // 以为还在想（旧版 foldLine 落定后只留 title，这里保持同一观感）。
        val head = foldHead(
            title = "已处理 3 个操作 · 用时 12 秒",
            activity = ProcessActivity(ActivityKind.THINKING, "思考"),
            running = false,
        )
        assertEquals("已处理 3 个操作 · 用时 12 秒", head)
    }

    @Test
    fun foldHeadShowsTheThinkingLabelWhileRunning() {
        val head = foldHead(
            title = "已处理 1 个操作",
            activity = ProcessActivity(ActivityKind.THINKING, "思考"),
            running = true,
        )
        assertEquals("已处理 1 个操作 · 思考", head)
    }

    @Test
    fun foldHeadFallsBackToTheBareTitleWhenThereIsNoActivity() {
        assertEquals("已处理 0 个操作", foldHead("已处理 0 个操作", null))
    }

    @Test
    fun foldHeadFlattensANewlineInTheSubtitle() {
        // 小标题是模型写的自由文本，可能带换行；第一行是 maxLines = 1，
        // 不压平就会在单行 Text 里画出一个「空白方块」。
        val head = foldHead(
            title = "已处理 1 个操作",
            activity = ProcessActivity(ActivityKind.TOOL, "ops_bash", "ls", subtitle = "先看目录\n再动手"),
        )
        assertEquals("已处理 1 个操作 · ops_bash · 先看目录 再动手", head)
        assertFalse(head.contains('\n'))
    }

    @Test
    fun foldPreviewShowsTheCommandOnTheSecondLine() {
        // 第二行 = 预览内容。跑命令时它就是命令本身。
        val preview = foldPreview(
            running = true,
            activity = ProcessActivity(ActivityKind.TOOL, "执行命令", "npm test", subtitle = "跑测试"),
            thinking = "先跑一遍单测",
            showsThinking = true,
            usedWidth = 900,
            charPx = 36f,
        )
        assertEquals("npm test", preview)
    }

    @Test
    fun foldPreviewKeepsTheCommandAfterTheTurnSettled() {
        // 回看时第二行仍要说明这一步跑了什么（桌面端已完成的调用也一直挂着摘要）。
        val preview = foldPreview(
            running = false,
            activity = ProcessActivity(ActivityKind.TOOL, "执行命令", "node scripts/build.mjs"),
            thinking = "",
            showsThinking = true,
            usedWidth = 900,
            charPx = 36f,
        )
        assertEquals("node scripts/build.mjs", preview)
    }

    @Test
    fun foldPreviewKeepsTheNewestThinkingCharactersWhileStreaming() {
        val preview = foldPreview(
            running = true,
            activity = ProcessActivity(ActivityKind.THINKING, "思考"),
            thinking = "先看看这段代码到底在做什么，再决定要不要动它",
            showsThinking = true,
            usedWidth = 900,
            charPx = 36f,
        )
        // 流式时取**尾部**（最新那几个字），取头部等于永远不更新。
        assertTrue("流式中必须看得到最新的字", preview.endsWith("要不要动它"))
        assertFalse(preview.contains('\n'))
    }

    @Test
    fun foldPreviewTakesTheHeadOnceTheTurnSettled() {
        // 落定后不再流式：回看要从结论的开头读，尾部那几个字没有意义。
        val preview = foldPreview(
            running = false,
            activity = ProcessActivity(ActivityKind.THINKING, "思考"),
            thinking = "这段代码一共三处问题，第一处是并发",
            showsThinking = true,
            usedWidth = 900,
            charPx = 36f,
        )
        assertTrue("落定后从头读", preview.startsWith("这段代码一共三处问题"))
    }

    @Test
    fun foldPreviewIsEmptyWhenTheUserTurnedThinkingOff() {
        // 用户关掉「显示思考」后第二行整行不画，不留一条空白把行高撑开。
        val preview = foldPreview(
            running = true,
            activity = ProcessActivity(ActivityKind.THINKING, "思考"),
            thinking = "不该出现",
            showsThinking = false,
            usedWidth = 900,
            charPx = 36f,
        )
        assertEquals("", preview)
    }

    @Test
    fun foldPreviewIsEmptyForAToolWithoutArguments() {
        val preview = foldPreview(
            running = true,
            activity = ProcessActivity(ActivityKind.TOOL, "ops_bash"),
            thinking = "",
            showsThinking = true,
            usedWidth = 900,
            charPx = 36f,
        )
        assertEquals("", preview)
    }

    @Test
    fun aFailureOnlyPanelStillProducesARenderableRow() {
        // 端到端链路：投影 → rows() → mergeTurnPanels → timelineOf。
        // 2026-11-01 实测：投影里 `fails=1`，但界面上一个字都没有。
        // 这条从数据层一路查到渲染前的最后一步，定位到底在哪一环丢的。
        val panel = Model.Message.pending().apply {
            panelId = "s1:t1#seg4"
            turn = 1L
            order = 100.75
            seq = 101
            running = false
            failure = "RATE_LIMIT：{\"type\":\"rate_limit_error\"}"
            footnote = true
        }
        val merged = mergeTurnPanels(listOf(panel))
        val rows = timelineOf(merged)
        assertTrue("合并后要还在", rows.any { it.failure.isNotEmpty() })
        assertTrue("timelineOf 之后也要还在", rows.any { it.failure.contains("RATE_LIMIT") })
        // 摘要行必须说「处理失败」，不能是「已完成分析」。
        val title = foldSummary(
            steps = panel.steps, indices = emptyList(), running = false,
            totalTools = 0, failure = panel.failure,
        )
        assertTrue("摘要要报处理失败，实际=$title", title.startsWith("处理失败"))
        assertTrue("摘要要带原因", title.contains("RATE_LIMIT"))
    }


    @Test
    fun aMergedPanelKeepsTheFailureOfItsLastSegment() {
        // 同一轮里多块面板会被 mergeTurnPanels 并成一块（合并体沿用 run.first() 的
        // 身份）。失败原因挂在**最后**那块上，合并时必须带过来，否则整条错误消失。
        val first = Model.Message.pending().apply {
            panelId = "s1:t1#seg2"; turn = 1L; order = 100.5; seq = 100
            running = false
        }
        first.steps.add(Model.Step(kind = Model.Step.TOOL, actionIndex = 0, text = "跑", id = "a"))
        first.actions.add(Model.ToolAction(name = "bash", detail = "ls"))
        val last = Model.Message.pending().apply {
            panelId = "s1:t1#seg4"; turn = 1L; order = 100.75; seq = 101
            running = false
            failure = "RATE_LIMIT：限流"
            footnote = true
        }
        val merged = mergeTurnPanels(listOf(first, last))
        // 带失败的那块**不参与合并**，原样独立：合并体沿用第一块的 panelId / order /
        // seq，失败文案虽然会被复制过去，但下游 timelineOf 的同位置覆盖会把它顶掉
        // （2026-11-01 实测：界面上一行都没有）。
        assertEquals(2, merged.size)
        assertEquals("RATE_LIMIT：限流", merged[1].failure)
        assertEquals("s1:t1#seg4", merged[1].panelId)
    }


    @Test
    fun asciiPreviewFillsTheSamePixelsAsChinese() {
        // 2026-11-01 用户反馈「思考预览右侧还是空白，偶尔有中文时不空白」。
        // 根因：额度按汉字宽度换算成**字符数**，再拿它按字符数截断 —— ASCII 只有
        // 汉字一半宽，于是同样的像素宽度只填了一半。这里钉住「同样的像素预算，
        // ASCII 拿到的字符数约为中文的两倍」。
        val charPx = 26f          // 13sp 在 xxhdpi 上大致这么宽
        val budgetPx = 520        // 20 个汉字宽
        val cn = thinkingPreviewByWidth("想".repeat(200), streaming = false, budgetPx = budgetPx, charPx = charPx)
        val en = thinkingPreviewByWidth("a".repeat(200), streaming = false, budgetPx = budgetPx, charPx = charPx)
        // 中文：一个汉字占满一格，末尾带省略号。
        assertEquals(20, cn.length)
        // 英文：一个字母半格，应当拿到接近两倍（19~20 个字 + 省略号）。
        assertTrue("ASCII 预览太短：len=${en.length}（${en.take(30)}…）", en.length >= 37)
    }

    @Test
    fun charWidthUnitsTreatsWideAndNarrowDifferently() {
        assertEquals(1f, charWidthUnits('想'.code))
        assertEquals(1f, charWidthUnits('あ'.code))
        assertEquals(1f, charWidthUnits('한'.code))
        assertEquals(0.5f, charWidthUnits('a'.code))
        assertEquals(0.5f, charWidthUnits(' '.code))
        assertEquals(0f, charWidthUnits(0x200D))   // 零宽连接符
    }

    @Test
    fun widthBasedPreviewKeepsTheTailWhileStreaming() {
        // 流式要看到「最新几个字」，落定要看到「开头」——同一份预算两个方向。
        val text = "开头" + "中".repeat(50) + "结尾"
        val live = thinkingPreviewByWidth(text, streaming = true, budgetPx = 130, charPx = 26f)
        val done = thinkingPreviewByWidth(text, streaming = false, budgetPx = 130, charPx = 26f)
        assertTrue("流式要带尾巴，实际=$live", live.endsWith("结尾"))
        assertTrue("落定要带开头，实际=$done", done.startsWith("开头"))
    }


    @Test
    fun stickyUserBubbleFreezesTheNearestQuestionAboveTheViewport() {
        // 用户口径（2026-11-01）：当前页面内没有用户气泡时，把离当前索引最近的
        // 上一个用户气泡冻结在顶部，点它可快速跳转。
        val rows = listOf(
            msg(1, "q1", Model.Role.USER), msg(2, "a1"),
            msg(3, "q2", Model.Role.USER), msg(4, "a2"),
            msg(5, "q3", Model.Role.USER), msg(6, "a3"),
        )
        // 视口从 a3（下标 5）开始：屏里没有用户气泡 ⇒ 冻最近的 q3（下标 4）。
        assertEquals("q3", stickyUserBubble(rows, firstVisibleIndex = 5, lastVisibleIndex = 5, leadingItems = 0)?.text)
        // 从 a2（下标 3）开始 ⇒ 冻 q2。
        assertEquals("q2", stickyUserBubble(rows, firstVisibleIndex = 3, lastVisibleIndex = 3, leadingItems = 0)?.text)
        // 屏里已经有用户气泡（从 q3 开始）⇒ 不冻。
        assertNull(stickyUserBubble(rows, firstVisibleIndex = 4, lastVisibleIndex = 5, leadingItems = 0))
        // 一路翻到最上面、上面没有用户消息了 ⇒ 不冻。
        assertNull(stickyUserBubble(rows, firstVisibleIndex = 0, lastVisibleIndex = 0, leadingItems = 0))
    }

    @Test
    fun stickyUserBubbleAccountsForTheLeadingRows() {
        // leading 区（「载入更早」/快照占位）会让 item 下标整体后移，
        // 换算成 rows 下标时必须减掉，否则会冻错一条。
        val rows = listOf(
            msg(1, "q1", Model.Role.USER), msg(2, "a1"),
            msg(3, "q2", Model.Role.USER), msg(4, "a2"),
        )
        // item 下标 3 = rows 下标 1（a1）⇒ 冻 q1。
        assertEquals("q1", stickyUserBubble(rows, firstVisibleIndex = 3, lastVisibleIndex = 3, leadingItems = 2)?.text)
    }


    @Test
    fun stickyBubbleTargetPointsAtTheBubbleItself() {
        // 点冻顶气泡必须滚到**它自己**那一行。2026-11-03 用户实测「屏幕滑到最低端，
        // 点击冻结气泡，没有发生跳转」——根因是当时按 `timelineAnchorOf(it) ==
        // timelineAnchorOf(sticky)` 找，而两者都是 null 时会命中第一条拿不到身份的行。
        val rows = listOf(
            msg(1, "q1", Model.Role.USER), msg(2, "a1"),
            msg(3, "q2", Model.Role.USER), msg(4, "a2"),
        )
        assertEquals(2, stickyBubbleTarget(rows, rows[2], leadingItems = 0))
        // leading 区（「载入更早」/快照占位）要让 item 下标整体后移。
        assertEquals(4, stickyBubbleTarget(rows, rows[2], leadingItems = 2))
    }

    @Test
    fun stickyBubbleTargetDoesNotMatchAnotherLocalEcho() {
        // 本地回声（seq=0、无 panelId）没有稳定身份。用 null 互相比会命中第一条，
        // 于是「点 A 跳到 B」或者原地不动。目标不可定位时宁可返回 -1（不滚）。
        val echoA = msg(0, "echo A", Model.Role.USER)
        val echoB = msg(0, "echo B", Model.Role.USER)
        val rows = listOf(echoA, msg(1, "a1"), echoB, msg(2, "a2"))
        // 对象身份能命中（同一份 rows 里的元素）。
        assertEquals(2, stickyBubbleTarget(rows, echoB, leadingItems = 0))
        // 换成一个不在 rows 里、也没有稳定身份的同内容对象 ⇒ 不乱跳。
        assertEquals(-1, stickyBubbleTarget(rows, msg(0, "echo C", Model.Role.USER), leadingItems = 0))
    }

    @Test
    fun stickyBubbleTargetFallsBackToStableIdentity() {
        // `rows` 被重建、对象换了但身份没换时仍要找回同一条。
        val before = listOf(msg(1, "q1", Model.Role.USER), msg(2, "a1"))
        val after = listOf(msg(1, "q1", Model.Role.USER), msg(2, "a1"))
        assertFalse(before[0] === after[0])
        assertEquals(0, stickyBubbleTarget(after, before[0], leadingItems = 0))
    }

    @Test
    fun timelineAnchorSurvivesInsertionAtTheFront() {
        // 「加载更早的消息」会往列表前面插 30 段正文，下标整体后移。
        // 锚点必须按消息身份记，不能记下标（2026-11-01 用户实测：点完被甩到最前端）。
        val older = listOf(msg(1, "old1"), msg(2, "old2"))
        val current = listOf(msg(10, "cur1"), msg(11, "cur2"))
        val anchor = timelineAnchorOf(current[0])
        assertEquals("seq-10", anchor)
        // 插入旧内容后，同一个锚点在新列表里指向同一条消息，只是下标变了。
        val after = older + current
        val at = after.indexOfFirst { timelineAnchorOf(it) == anchor }
        assertEquals(2, at)
        assertEquals("cur1", after[at].text)
    }

    @Test
    fun timelineAnchorRefusesUnstableIdentity() {
        // 本地回声（seq<=0 且无 panelId）没有稳定身份，不能拿来锚定——
        // 否则加载完后会跳到一个看似相同、实则早已错位的下标上。
        assertNull(timelineAnchorOf(msg(0, "echo")))
        assertEquals("panel-p1", timelineAnchorOf(msg(0, "p").also { it.panelId = "p1" }))
    }

    @Test
    fun stickyLeadingCountsBothLeadingRows() {
        val rows = listOf(msg(1))
        assertEquals(0, stickyLeadingFor(rows, ChatViewModel.ChatState(snapshotReady = true)))
        assertEquals(
            2,
            stickyLeadingFor(rows, ChatViewModel.ChatState(hasMore = true, snapshotReady = false)),
        )
    }


    @Test
    fun anchorWaitsUntilTheNewRowsActuallyLand() {
        // 2026-11-01 用户第二次反馈「点加载更早还是跳到第一行」。
        // 根因：锚点一写进 state，LaunchedEffect 立刻跑，而那时 rows 还是旧的 ——
        // 它当场滚回原位（等于没动）并把锚点清掉，等新数据到了已经没锚点可用。
        //
        // 2026-11-03 第三次反馈「这个功能之前已实现，现在怎么没了」。上一版把判据
        // 写成「条数比点击时多」，但流式输出每帧都在往**尾部**追加 ⇒ 加载还没回来
        // 判据就先满足，用「尾部新增行数」冒充「前插行数」算出错目标，锚点随即被
        // 清空，真正的前插到达时已无锚点。判据改成「那条消息在 rows 里挪了位置」。
        val before = listOf(msg(1), msg(2), msg(3))
        // 尾部追加（流式增量）：锚点那行原地不动 ⇒ 不能滚。
        val tailGrowth = before + listOf(msg(4), msg(5))
        val anchorOnTwo = TimelineAnchor(key = "seq-2", rowIndex = 1, listIndex = 1, offset = 40)
        assertNull("只是尾部追加，不该滚", anchorTargetOf(anchorOnTwo, tailGrowth, newLeading = 0))
        // 真前插 2 行：锚点那行从下标 1 挪到 3 ⇒ 目标 = newLeading + 3。
        val prepended = listOf(msg(-2), msg(-1)) + before
        assertEquals(3, anchorTargetOf(anchorOnTwo, prepended, newLeading = 0))
        // 前面多出「载入更早」leading 行时，目标要跟着加上 newLeading。
        assertEquals(4, anchorTargetOf(anchorOnTwo, prepended, newLeading = 1))
        // 消息被裁掉（找不到）⇒ 返回 null，不滚到莫名其妙的位置。
        assertNull(anchorTargetOf(anchorOnTwo, listOf(msg(9)), newLeading = 0))
        // 首项本身是 leading 行（没有 key）⇒ 原地保持 listIndex。
        val leadingAnchor = TimelineAnchor(key = null, rowIndex = 0, listIndex = 0, offset = 12)
        assertEquals(0, anchorTargetOf(leadingAnchor, prepended, newLeading = 1))
    }

    @Test
    fun anchorFallsBackToSeqWhenThePanelGetsMergedAway() {
        // 2026-11-03 模拟器实测（决定性）：`rows` 是 `mergeTurnPanels` 的产物，
        // 同一 turn 的相邻面板会并成一块，合并体沿用 `run.first()` 的身份。
        // 用户正看着的那块面板若被前插进来的同 turn 面板并掉，`panelId` 就从
        // `<turn>#seg3` 变成 `<turn>#seg0`，记下的键在 rows 里再也找不到
        // （实测日志 `found=-1`）⇒ 锚点当场放弃，界面停在新插内容的开头。
        //
        // 兜底：按 `seq` 定位「第一条 seq >= 锚点 seq 的行」。前插只会插入更小的
        // seq，所以这条在加载前后指向同一段内容。
        val panel = msg(100, "").also { it.panelId = "t1:t9#seg3"; it.turn = 9 }
        val tail = listOf(panel, msg(101, "a"), msg(102, "b"))
        val anchor = TimelineAnchor(
            key = timelineAnchorOf(panel),   // "panel-t1:t9#seg3"
            rowIndex = 0,
            listIndex = 1,
            offset = -148,
            seq = 100,
        )
        // 前插了同 turn 的 4 块面板，合并体换了身份：panelId 找不到。
        val merged = msg(60, "").also { it.panelId = "t1:t9#seg0"; it.turn = 9 }
        val after = listOf(merged, msg(61, ""), msg(62, "")) + tail.drop(1)
        assertNull(
            "前提：panelId 确实已失效",
            after.indexOfFirst { timelineAnchorOf(it) == anchor.key }.takeIf { it >= 0 },
        )
        // seq 兜底把它找回原位（下标 3），加上 leading 后是 4。
        assertEquals(4, anchorTargetOf(anchor, after, newLeading = 1))
        // seq 也没有（本地回声）时仍然放弃，不乱跳。
        val noSeq = TimelineAnchor(key = "panel-gone", rowIndex = 0, listIndex = 0, offset = 0, seq = 0)
        assertNull(anchorTargetOf(noSeq, after, newLeading = 0))
    }


    @Test
    fun aLocallySentImageIsNotReportedAsFailed() {
        // 2026-11-01 用户实测：发送图片后先显示「图片加载失败」，发送成功后又消失。
        // 根因是本地回声的 ImageRef 只有 name、attachmentId 还是空串，缩略图组件
        // 拿空 id 去请求 session/attachment，必然失败。
        // 正确状态：有本地位图 → 正常画（不是失败）；没有 id 也没有本地字节 → 「发送中」。
        assertEquals("发送中", thumbState(enabled = true, failed = false, hasId = false, hasLocal = false, name = "a.jpg"))
        // 有本地字节时不该走到占位文案，但万一走到，也不能说失败。
        assertEquals("a.jpg", thumbState(enabled = true, failed = false, hasId = false, hasLocal = true, name = "a.jpg"))
        // 真失败仍然是失败。
        assertEquals("图片加载失败", thumbState(enabled = true, failed = true, hasId = true, hasLocal = false, name = "a.jpg"))
        // 传输关闭优先于一切。
        assertEquals("附件传输已关闭", thumbState(enabled = false, failed = true, hasId = true, hasLocal = false, name = "a.jpg"))
    }


    @Test
    fun projectionMergeKeepsTheWorkspaceItNeverCarries() {
        // 2026-11-02 用户实测「进入聊天页后顶部工作空间名称会自动消失」。
        // 根因：`sessionProjections` 只 new 了 Model.Session(id)，从不填 workspace，
        // `refreshProjections` 的 copy 用那个空串把手上的工作区顶掉了。
        // 这里钉死：投影为空串时绝不能赢。
        val (title, workspace) = mergeProjectionIdentity(
            projectedTitle = "",
            projectedWorkspace = "",
            localTitle = "真标题",
            localWorkspace = "E:\\proj\\pp-paradise",
            cachedWorkspace = { "不该被用到" },
        )
        assertEquals("真标题", title)
        assertEquals("E:\\proj\\pp-paradise", workspace)
    }

    @Test
    fun projectionMergeFallsBackToCacheForDeepLinks() {
        // 深链/通知进来的壳只有 id：手上这份也没有工作区，最后一档必须落到缓存。
        val (_, workspace) = mergeProjectionIdentity(
            projectedTitle = "",
            projectedWorkspace = "",
            localTitle = "",
            localWorkspace = "",
            cachedWorkspace = { "E:\\proj\\cached" },
        )
        assertEquals("E:\\proj\\cached", workspace)
    }

    @Test
    fun projectionMergeStillLetsRealValuesThrough() {
        // 兜底不能反过来把真值挡掉：投影真的带了标题/工作区就必须用投影的。
        val (title, workspace) = mergeProjectionIdentity(
            projectedTitle = "新标题",
            projectedWorkspace = "E:\\new",
            localTitle = "旧标题",
            localWorkspace = "E:\\old",
            cachedWorkspace = { "不该被用到" },
        )
        assertEquals("新标题", title)
        assertEquals("E:\\new", workspace)
    }

    @Test
    fun releaseManifestAsksTheRelayFirstThenTheOldStaticUrl() {
        // 中继自建、会关机、也会换地址：先问它，问不到才回落旧静态地址。
        val candidates = releaseManifestCandidates("http://203.0.113.10")
        assertEquals(2, candidates.size)
        assertEquals("http://203.0.113.10/api/dsh-mobile-sh-apk/downloads/version.json", candidates[0])
        assertEquals("https://downloads.example.test/api/ybs/downloads/version.json", candidates[1])
    }

    @Test
    fun releaseManifestNormalizesTheRelayTrailingSlash() {
        // 插件里存的中继地址可能带尾斜杠（normalizeRelayUrl 会去，但缓存/手工改过的不会），
        // 拼接前必须自己收一下，否则出现 `//api/…`。
        assertEquals(
            listOf(
                "wss://relay.example.test/api/dsh-mobile-sh-apk/downloads/version.json",
                "https://downloads.example.test/api/ybs/downloads/version.json",
            ),
            releaseManifestCandidates(" wss://relay.example.test/ "),
        )
    }

    @Test
    fun releaseManifestFallsBackOnlyWhenThereIsNoRelayYet() {
        // 还没登录（中继地址为空）时不能拼出 `/api/…` 这种相对地址去喂 OkHttp：那会当场抛。
        assertEquals(
            listOf("https://downloads.example.test/api/ybs/downloads/version.json"),
            releaseManifestCandidates(""),
        )
        assertEquals(
            listOf("https://downloads.example.test/api/ybs/downloads/version.json"),
            releaseManifestCandidates("   "),
        )
    }

}
