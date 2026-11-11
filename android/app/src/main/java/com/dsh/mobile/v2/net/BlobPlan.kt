package com.dsh.mobile.v2.net

/**
 * 存根正文续传的决策逻辑，抽成纯函数以便用假响应单测。
 *
 * <p>为什么值得单独一个文件：这条通路的三种响应（`200` / `206` / `416`）语义各不相同，
 * 而**其中两种写错都不会在 happy path 上暴露**——
 * - 把 `200` 当 `206` 追加，会在一段区间被服务端忽略时拼出重复字节，结果是「正文多了一截」，
 *   界面上看起来只是那段内容重复了一遍，不像 bug；
 * - 把 `416` 一律当失败，会让「刚好取完最后一段」这种正常收尾变成一次永久的补不齐。
 *
 * <p>真正的传输（OkHttp、`ByteArrayOutputStream`）留在 [RelayApiV2]，这里只回答
 * 「拿到这批字节之后下一步做什么」。
 */
internal object BlobPlan {

    /** 单条存根背后最多这么多字节，与 `SYNC_V2.hardCeiling` 一致。 */
    const val LIMIT = 10 * 1024 * 1024

    /** 续传轮数上限：够覆盖「进电梯再出来」，又不至于对着已裁剪的 blob 空转。 */
    const val MAX_ATTEMPTS = 4

    sealed interface Step {
        /** 正文已经完整，可以把缓冲区当结果交出去。 */
        data object Complete : Step

        /** 还需要一轮；[offset] 是下一段 `Range` 的起点，[replace] 为真表示整块替换。 */
        data class Again(val offset: Int, val replace: Boolean, val attempt: Int) : Step

        /** 放弃，并说清楚为什么。 */
        data class Fail(val error: String) : Step
    }

    /**
     * 收到一批字节之后。
     *
     * @param status HTTP 状态码（只可能是 200 或 206，其余走 [onFailure]）。
     * @param chunk 这一批的字节数。
     * @param offset 请求时已经持有的字节数。
     */
    fun onChunk(status: Int, chunk: Int, offset: Int, limit: Int = LIMIT): Step {
        // `200` 意味着服务端没按区间给：要么 Range 没支持，要么它判断整块更划算。
        // 无论哪种，手里这份都是**完整正文**，必须替换而不是追加。
        val held = if (status == 200) chunk else offset + chunk
        if (held > limit) return Step.Fail("too-large")
        if (status == 200) return Step.Complete
        // 空的 `206` 说明服务端没有更多了。再问一次会拿到完全相同的空响应，
        // 那不叫续传，那叫死循环。
        if (chunk <= 0) return Step.Complete
        return Step.Again(offset = held, replace = false, attempt = 0)
    }

    /**
     * 请求失败之后。
     *
     * <p>`416` 单独对待：服务端说「这个起点越界了」，而越界的唯一正常来源就是
     * 「我们已经拿完了」，所以 [offset] 大于 0 时按成功收尾。从这里重试永远拿不到
     * 新字节——中继真的裁剪过这条 blob 时也不会因为多问几次就变出来。
     */
    fun onFailure(
        status: Int,
        offset: Int,
        attempt: Int,
        error: String,
        maxAttempts: Int = MAX_ATTEMPTS,
    ): Step {
        if (status == 416) {
            return if (offset > 0) Step.Complete else Step.Fail("range-rejected")
        }
        if (attempt + 1 >= maxAttempts) return Step.Fail(error)
        return Step.Again(offset = offset, replace = false, attempt = attempt + 1)
    }
}
