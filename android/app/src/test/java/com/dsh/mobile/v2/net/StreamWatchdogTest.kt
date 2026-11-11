package com.dsh.mobile.v2.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 流活性看门狗的裁决逻辑。
 *
 * 背景：2026-10-29 真机上「思考到一半停住、界面永久卡在深度思考」——socket 不报错
 * 也不关闭，宿主那边回合其实早就结束了，归档里正文都落了盘。v1 有这层看门狗，v2
 * 重写时漏了，于是没有任何东西去发现「连着但没帧」。
 *
 * 这里只测纯函数：真机上要复现一次 45s 静默成本太高，而三条判据全是算术。
 */
class StreamWatchdogTest {

    private val silence = STREAM_SILENCE_MS

    @Test
    fun `刚来过的帧不触发重开`() {
        assertEquals(
            WatchdogVerdict.WAIT,
            watchdogVerdict(idleMs = silence - 1, sinceResyncMs = silence * 10, attempts = 0),
        )
    }

    @Test
    fun `静默超过阈值就重开`() {
        assertEquals(
            WatchdogVerdict.RESYNC,
            watchdogVerdict(idleMs = silence + 1, sinceResyncMs = silence * 10, attempts = 0),
        )
    }

    /**
     * 刚重开完、快照还在路上：此时 `idleMs` 必然也很大（旧连接早就没帧了），
     * 没有这条判据就会在下一个 10s tick 里再重开一次，白下三遍快照。
     */
    @Test
    fun `刚重开过就等着别叠加`() {
        assertEquals(
            WatchdogVerdict.WAIT,
            watchdogVerdict(idleMs = silence * 10, sinceResyncMs = silence - 1, attempts = 1),
        )
    }

    @Test
    fun `到了次数上限就放弃`() {
        assertEquals(
            WatchdogVerdict.GIVE_UP,
            watchdogVerdict(idleMs = silence + 1, sinceResyncMs = silence + 1, attempts = 3),
        )
    }

    /** 上限之下的最后一次仍要真的重试，别提前放弃。 */
    @Test
    fun `上限前一次仍然重开`() {
        // 第 2 次重开前的退避是 silence × 3（见下一条用例）。
        assertEquals(
            WatchdogVerdict.RESYNC,
            watchdogVerdict(idleMs = silence + 1, sinceResyncMs = silence * 3, attempts = 2),
        )
    }

    /**
     * 重开后要求等的间隔随次数**线性加长**：链路真的坏了时，每 30 秒重下一份
     * 几百 KB 的快照是二次伤害。第一轮等 30s、第二轮 60s、第三轮 90s。
     */
    @Test
    fun `连续重开的等待间隔逐次加长`() {
        // 第 0 次：只要静默够了就动。
        assertEquals(WatchdogVerdict.RESYNC, watchdogVerdict(silence + 1, silence, 0))
        // 第 1 次：必须等满 2×silence，1.5 倍还不够。
        assertEquals(WatchdogVerdict.WAIT, watchdogVerdict(silence + 1, silence * 3 / 2, 1))
        assertEquals(WatchdogVerdict.RESYNC, watchdogVerdict(silence + 1, silence * 2, 1))
        // 第 2 次同理。
        assertEquals(WatchdogVerdict.WAIT, watchdogVerdict(silence + 1, silence * 2, 2))
        assertEquals(WatchdogVerdict.RESYNC, watchdogVerdict(silence + 1, silence * 3, 2))
    }

    /**
     * 到上限后**立刻**认输，不要求再等一个退避窗口。
     *
     * 反过来会让用户多盯 90 秒的「正在重新同步…」才看到「请重新进入会话」。
     */
    @Test
    fun `到上限就不必再等退避窗口`() {
        assertEquals(WatchdogVerdict.GIVE_UP, watchdogVerdict(idleMs = 0, sinceResyncMs = 0, attempts = 3))
    }

    /** 阈值要能按链路实测结果调整，而不是写死在判据里。 */
    @Test
    fun `静默阈值可以按链路调整`() {
        // 阈值 20s：19s 还等，25s 就动。
        assertEquals(WatchdogVerdict.WAIT, watchdogVerdict(19_000, 600_000, 0, silenceMs = 20_000))
        assertEquals(WatchdogVerdict.RESYNC, watchdogVerdict(25_000, 600_000, 0, silenceMs = 20_000))
        // 生产默认值收到 30s（评审 §7.4 建议的 20–30s 区间）。
        assertEquals(30_000L, silence)
    }

    /**
     * `lastFrameAt` 为 0（还没收到过任何帧）时调用方直接 return，不该进到这里；
     * 但万一进来，判据必须给出「等」，不能对着 1970 年的时间戳狂重连。
     */
    @Test
    fun `时间戳缺失时不重开`() {
        assertEquals(
            WatchdogVerdict.WAIT,
            watchdogVerdict(idleMs = 0, sinceResyncMs = 0, attempts = 0),
        )
    }
}
