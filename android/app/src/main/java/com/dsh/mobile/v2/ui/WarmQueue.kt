package com.dsh.mobile.v2.ui

/**
 * 首行预览预热的排队表。
 *
 * <p>抽成一个纯 Kotlin 类，只为一件事：把「闩锁什么时候松开」钉死在单测里。
 * 2026-11-02 那个「会话列表页的预览永远不刷新、非要进一次聊天页再退出来」的
 * 真身就在这里——原实现用一个 `HashSet<String> warmQueued` 当闩锁，可是
 * **只有批量同步那条路会 remove，单会话同步（`launchSessionSync`）只 add 从不
 * remove**，于是每个会话一辈子只可能被预热一次；更糟的是那仅有一次若恰好落在
 * 「最新一条助手消息还只有思考、没有正文」的时刻，查到空串、界面一次都没更新，
 * 而闩锁照样留着，之后再也不会重试。
 *
 * <p>正确的语义是：**闩锁由「数据变了」来释放，不由「查过一遍」来释放**。
 * 每一次真的落了新记录都会先走 [AppViewModel.invalidatePreview]（内部调 [release]），
 * 于是这一行重新可以被排队；而一次什么都没查到的预热**不该**自己解开闩锁 ——
 * 否则任何一个空会话都会在每一次列表重绘时被重新排一次队、重查一次库
 * （`previewOf` 命中不了缓存就排队，[dequeue] 又把它放开，形成每帧一次点查）。
 *
 * <p>这两个角色必须是两套存储，不能像早先那样共用一个 `LinkedHashSet`：
 * 一个键在 [dequeue] 之后应当「不再有排队位置、但闩锁仍在」。共享一个集合时
 * 「取走待办」与「解开闩锁」是同一个动作，上面两条要求互相矛盾。于是这里拆成
 * [pending]（复合键，插入序即排队序）与 [latched]（闩锁），不变式 **`pending ⊆ latched`**：
 *
 * - [enqueue]：闩住 + 排队（已闩住则返回 false，调用方据此避免重复 `scheduleWarm()`）；
 * - [dequeue]：只从 [pending] 取走，**不动** [latched]；
 * - [release]：两者一起撤（数据变了）；
 * - [requeue]：先 [release] 再 [enqueue]。
 *
 * <p>键用 `host/session` 复合键而不是裸 sessionId：换主机之后同名会话不该互相
 * 顶掉（[dequeue] 因此需要 host 参数才知道该取走哪些键）。
 */
internal class WarmQueue {
    /** 待办：插入序即排队序（先来的先算）。 */
    private val pending = LinkedHashSet<String>()

    /** 闩锁：这一版数据已经安排过重算，不变式 `pending ⊆ latched`。 */
    private val latched = HashSet<String>()

    /** 队列里现有多少条待办（测试与诊断用；不含「闩着但没有排队位置」的键）。 */
    val size: Int get() = pending.size

    /** 这个会话当前是否被闩住（= 这一版数据算过了、不必再算）。 */
    fun isQueued(hostId: String, sessionId: String): Boolean = latched.contains(keyOf(hostId, sessionId))

    /**
     * 排队。**已经排过就返回 false**，调用方据此避免重复 `scheduleWarm()`
     * （每次都重新起一个协程会把攒批的意义抹掉）。
     */
    fun enqueue(hostId: String, sessionId: String): Boolean {
        if (hostId.isEmpty() || sessionId.isEmpty()) return false
        val key = keyOf(hostId, sessionId)
        if (!latched.add(key)) return false
        pending.add(key)
        return true
    }

    /**
     * 取走该主机下的整批（返回 sessionId，保持排队顺序）。
     *
     * <p>**只取走，不解开闩锁**：闩锁表示「这一版数据已经安排过重算了」，与「这一轮
     * 有没有查到东西」无关。解开它的唯一途径是数据变化（[release] / [requeue]）。
     *
     * <p>按 host 过滤而不是整表清空：待办里可能混着上一台主机的，那些不该被这一轮
     * 误取走。
     */
    fun dequeue(hostId: String): List<String> {
        if (hostId.isEmpty()) return emptyList()
        val prefix = "$hostId/"
        val out = ArrayList<String>()
        val it = pending.iterator()
        while (it.hasNext()) {
            val k = it.next()
            if (k.startsWith(prefix)) {
                out.add(k.substring(prefix.length))
                it.remove()
            }
        }
        return out
    }

    /**
     * 强制重新排队：列表整体作废（批量补页、下拉刷新）时用，不管当前闩没闩住都要
     * 让这一行再算一次。
     */
    fun requeue(hostId: String, sessionId: String): Boolean {
        release(hostId, sessionId)
        return enqueue(hostId, sessionId)
    }

    /**
     * 撤掉闩锁（连带排队位置）：**数据变了，这一行需要重算** ——
     * `AppViewModel.invalidatePreview` 就是调它。会话被删掉时也走这里。
     */
    fun release(hostId: String, sessionId: String) {
        if (hostId.isEmpty() || sessionId.isEmpty()) return
        val key = keyOf(hostId, sessionId)
        latched.remove(key)
        pending.remove(key)
    }

    private fun keyOf(hostId: String, sessionId: String) = "$hostId/$sessionId"
}
