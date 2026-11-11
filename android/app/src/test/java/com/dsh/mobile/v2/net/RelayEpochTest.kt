package com.dsh.mobile.v2.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 中继代次：换中继之后，上一台还在途的答复必须被丢掉。
 *
 * <p>这一组的现场依据：2026-11-04 在 self-hosted（裸 IP）上换中继后，App 顶栏挂着旧机器名，
 * `/native/v2/hosts/<旧 id>/changes` 每 3 秒换一个 404 永不停止。根因是生产中继那台
 * 的 `/hosts` 迟了 2.3 秒才回来（比配对成功还晚），答复里的 hostId 在 self-hosted 上根本不存在。
 * 单看代码谁都没错——错在「问的是 A，答的是 A，但此刻已经在跟 B 说话」。
 */
class RelayEpochTest {

    @Test
    fun `同一台中继的答复一律accept`() {
        val epoch = RelayEpoch()
        val captured = epoch.current()
        assertTrue(epoch.accepts(captured))
        assertTrue("没换中继时反复问都算数", epoch.accepts(epoch.current()))
    }

    @Test
    fun `换中继之后旧的captured作废`() {
        val epoch = RelayEpoch()
        val before = epoch.current()
        epoch.rotate()
        assertFalse("上一台的在途答复不能落到新中继的状态里", epoch.accepts(before))
        assertTrue("换完之后记下的代次才算数", epoch.accepts(epoch.current()))
    }

    @Test
    fun `连续换两次之后中间那一代也作废`() {
        val epoch = RelayEpoch()
        val first = epoch.current()
        epoch.rotate()
        val second = epoch.current()
        epoch.rotate()
        assertFalse(epoch.accepts(first))
        assertFalse(epoch.accepts(second))
        assertEquals(second + 1, epoch.current())
    }

    @Test
    fun `rotate 返回新代次供日志使用`() {
        val epoch = RelayEpoch()
        assertEquals(0, epoch.current())
        assertEquals(1, epoch.rotate())
        assertEquals(2, epoch.rotate())
        assertEquals(2, epoch.current())
    }
}
