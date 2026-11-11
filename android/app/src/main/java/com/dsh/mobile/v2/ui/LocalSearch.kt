package com.dsh.mobile.v2.ui

import com.dsh.mobile.v2.data.Model
import java.util.Locale

/**
 * 本地会话搜索（v1 LocalSearch.java 一比一移植）。
 *
 * <p>为什么需要它：宿主的 `session/search` 依赖 session-query 索引，本机部署里是
 * `openAt: "never"`（实测直接回 `SessionQueryError: session search is disabled`），
 * 也就是说这个功能在相当一部分宿主上是关着的。用户按了「搜索」却只看到一闪而过的
 * 英文报错，比「搜不到」更糟——所以先搜本地缓存，把能搜出来的先给出来。
 *
 * <p>只读 session/list 里的标题与已落盘的消息，不引外部依赖，纯函数便于单测。
 */
object LocalSearch {

    /** 命中条数上限：再多也没人翻，还会把弹层撑得很长。 */
    const val MAX_HITS = 30

    /** 摘要窗口：命中词前面留几个字做上下文。 */
    private const val BEFORE = 16
    private const val AFTER = 48

    /** 一条命中直接用宿主的 SearchHit 形状（v1 同款），本地与宿主结果可合并展示。 */
    /**
     * 在会话标题与消息正文里找关键词。
     *
     * @param query    用户输入；空白直接返回空列表
     * @param sessions 当前主机的会话列表（顺序即结果顺序，标题命中优先）
     * @param messages 会话 id → 已缓存消息；没有缓存的会话不必放进 map
     */
    fun find(
        query: String?,
        sessions: List<Model.Session>,
        messages: Map<String, List<Model.Message>>,
    ): List<Model.SearchHit> {
        if (query.isNullOrBlank()) return emptyList()
        val needle = query.trim().lowercase(Locale.ROOT)
        val titleHits = ArrayList<Model.SearchHit>()
        val bodyHits = ArrayList<Model.SearchHit>()
        for (s in sessions) {
            if (s.id.isEmpty()) continue
            val title = s.title
            if (title.lowercase(Locale.ROOT).contains(needle)) {
                titleHits.add(Model.SearchHit(s.id, title, "会话标题：$title"))
                if (titleHits.size + bodyHits.size >= MAX_HITS) break
                continue
            }
            val list = messages[s.id] ?: continue
            for (m in list) {
                val text = m.text
                if (text.isEmpty()) continue
                val at = text.lowercase(Locale.ROOT).indexOf(needle)
                if (at < 0) continue
                bodyHits.add(Model.SearchHit(s.id, title, snippet(text, at, needle.length)))
                break // 一条会话只出一条：同一个会话刷出十条没意义
            }
            if (titleHits.size + bodyHits.size >= MAX_HITS) break
        }
        return titleHits + bodyHits
    }

    /** 把换行压成空格，取命中词前后各一小段，过长时补省略号（v1 同款窗口）。 */
    fun snippet(text: String, at: Int, length: Int): String {
        val flat = text.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
        val start = maxOf(0, at - BEFORE)
        val end = minOf(flat.length, at + length + AFTER)
        if (start >= end) return flat
        val window = flat.substring(start, end)
        return (if (start > 0) "…" else "") + window + (if (end < flat.length) "…" else "")
    }

    /**
     * 宿主搜索失败时给用户看的一句话（v1 hostSearchHint 一比一）。
     *
     * <p>宿主原话是英文的 `SessionQueryError: session search is disabled` 之类，
     * 直接弹出来用户既看不懂也不知道该干什么。按原因分三类：能力关着、连不上、真没搜到。
     */
    fun hostSearchHint(reason: String?): String {
        val detail = reason?.lowercase(Locale.ROOT) ?: ""
        if (detail.contains("disabled") || detail.contains("session-query") ||
            detail.contains("never") || detail.contains("not enabled")
        ) {
            return "正文搜索未开启，当前只能搜已缓存的会话与消息"
        }
        if (detail.contains("timeout") || detail.contains("connect") ||
            detail.contains("network") || detail.contains("unreachable") ||
            detail.contains("offline")
        ) {
            return "连不上宿主，搜索未完成；当前只能搜已缓存的会话与消息"
        }
        return "没有匹配的会话"
    }
}
