package com.dsh.mobile.v2.net

/**
 * 中继代次：换一台中继之后，上一台还没回来的答复一律作废。
 *
 * <p>为什么必须单独记一笔：用户换中继（`origin` 变了）时，旧中继上那些在途请求不会
 * 被取消，它们会陆续回来，而且内容看着完全正常。主机的 id 是新旧两台之间**唯一不通用**
 * 的东西——旧答复里的 hostId 在新中继上不存在，一旦被采用，界面就会选中一台不存在的
 * 电脑：顶栏挂着旧机器名，`/changes` 每 3 秒换一个 404，用户看到的是「请求失败（404）」
 * 一直不散。
 *
 * <p>2026-11-04 在 self-hosted 裸 IP 部署上实测到的正是这一条：生产中继的 `/native/v2/hosts`
 * 迟了 2.3 秒才回来，**正好压在配对成功之后**，把刚拿到授权的新主机从 `state.hosts`
 * 里顶掉，App 于是退回上一台的 `5853fdd2…`——那台在 self-hosted 上压根不存在。
 *
 * <p>用法：发请求前 `val epoch = relayEpoch.current()`，回调里
 * `if (!relayEpoch.accepts(epoch)) return`。丢弃时**不要报错**：用户此刻关心的是新中继
 * 的答复，旧中继的失败与他无关。
 *
 * <p>只在 JVM 单测里用，所以 `internal`；线程语义是「值本身可见即可」——写入只发生在
 * 登录成功那条主线程路径上。
 */
internal class RelayEpoch {

    private var value = 0

    /** 当前代次。 */
    fun current(): Int = value

    /** 换中继：开新的一代，返回新代次（便于顺手打日志）。 */
    fun rotate(): Int {
        value++
        return value
    }

    /** @param captured 发请求时记下的代次；对不上说明中途换过中继。 */
    fun accepts(captured: Int): Boolean = captured == value
}
