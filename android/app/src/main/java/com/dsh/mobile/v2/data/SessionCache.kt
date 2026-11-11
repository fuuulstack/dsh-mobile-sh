package com.dsh.mobile.v2.data

import android.content.Context
import android.content.SharedPreferences
import com.dsh.mobile.v2.net.SyncPlanner
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 手机端本地缓存（v2，Kotlin 版）：让冷启动先看到上次的内容，再被网络结果覆盖。
 *
 * 缓存只做“上次看到的画面”，不做权威数据：网络结果一到就整体替换，因此不需要
 * 合并、失效或版本号。消息编码/落盘进单线程队列（主线程只做引用快照），同一会话
 * 连续多次保存自动合并为最后一次。
 *
 * 落盘 JSON 走 kotlinx-serialization（Model.kt 的 @Serializable），与 v1 的手写
 * org.json 编解码等价但少一半代码；prefs 文件名 `dsh-v2-cache` 与 v1 天然隔离。
 */
class SessionCache(context: Context) {

    companion object {
        /**
         * 每个会话最多留这么多条消息。
         *
         * <p>**这不是翻历史的上限**——正文的权威副本在 Room（`timeline_records`），
         * 上限与中继归档对齐，是 `ARCHIVE_PER_SESSION = 2000`（`relay/src/store.ts`）。
         * 这个常量只约束 `files/messages` 下的 JSON 这份**兼容缓存**：v2 的投影器读的是
         * Room，这份文件只在 Room 还没铺开时兜一下底。原来写 120，与云端 2000 差
         * 16 倍，于是「本地翻到头了、云端还有」这种错位会让人误判成丢历史
         * （2026-11-01 核对时发现）。取 2000 与云端一致，两边口径就只剩一个。
         */
        const val MESSAGE_LIMIT = 2000

        /** 最多留这么多个会话；超出的按 updatedAt 淘汰。 */
        const val SESSION_LIMIT = 80

        private const val PREFS = "dsh-v2-cache"
        private const val LAST_HOST = "lastHost"
        private const val LAST_HOST_ALIAS = "lastHostAlias"
        private const val HOSTS = "hosts"
        private const val HOSTS_SYNCED_AT = "hostsSyncedAt"
        private const val SORT_MODE = "sortMode"
        private const val MODEL_CATALOG = "modelCatalog"
        private const val READ_AT = "readAt"
        private const val READ_BASELINE = "readBaseline"
        /** 归档游标（服务端 nextCursor 的落盘位置，见 [archiveCursors]）。 */
        private const val ARCHIVE_CURSORS = "archiveCursors"

        /** 旧版游标表：只作为一次性迁移的来源读，迁完即删。 */
        private const val LEGACY_SYNC_CURSORS = "syncCursors"

        /** 迁移版本号；旧版本（0/1）的游标一律不可信。 */
        private const val ARCHIVE_CURSOR_VERSION = "archiveCursorVersion"
        private const val ARCHIVE_CURSOR_VERSION_CURRENT = 2
        private const val DRAFTS = "drafts"
        private const val HIDDEN = "hiddenSessions"

        /**
         * 消息正文的水位索引：{hostId: {sessionId: 本地已落盘消息的最大 seq}}。
         *
         * 存在的唯一理由是把「这个会话本地到哪了」从「解完整段消息 JSON」降成
         * 读一个几百字节的小表。注意它**只用于诊断与展示**（缓存里有没有正文），
         * 绝不是归档游标：归档游标只能来自服务端的 `nextCursor`（见 [ARCHIVE_CURSORS]）。
         */
        private const val MESSAGE_META = "messageMeta"

        /** 老版本把消息正文塞在 SharedPreferences 里的键前缀，迁移后不再写入。 */
        private const val LEGACY_MESSAGES_PREFIX = "messages:"

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val sessionList = ListSerializer(Model.Session.serializer())
        private val messageList = ListSerializer(Model.Message.serializer())
        private val hostList = ListSerializer(Model.Host.serializer())
        private val longMap = MapSerializer(String.serializer(), Long.serializer())
        private val stringMap = MapSerializer(String.serializer(), String.serializer())


        /** 兼容 v1 缓存的会话标题过滤：delegated 内部会话不在列表里展示。 */
        private fun visibleSessionTitle(title: String): Boolean =
            !title.contains("dsh-mnemon:delegated") && !title.startsWith("[dsh-mnemon:") && !title.contains("delegated review")
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 消息正文的落盘目录。
     *
     * 2026-10-29 之前正文直接写在 SharedPreferences 里，问题不是「慢一点」而是
     * **整表重写**：prefs 是一个 XML，`putString(messages:host:sess, …)` 每次都要把
     * 整个 `dsh-v2-cache.xml` 序列化一遍再落盘，80 个会话 × 120 条消息攒到几 MB 时，
     * 一次 `apply()` 就是几 MB 的写入 + 一次 fsync 排队；而同步流程是「每个会话都
     * saveMessages 一次」，于是一次 /sync 就是 N 次整表重写。改成每会话一个文件后
     * 单次落盘的成本只和这一个会话有关。
     */
    private val messagesDir: File = File(context.filesDir, "messages").apply { mkdirs() }

    /** 待写盘消息的合并队列：键为 messagesKey，值为调用线程做的快照。 */
    private val pendingSaves = ConcurrentHashMap<String, List<Model.Message>>()
    private val saveExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dsh-v2-cache").apply { isDaemon = true }
    }

    /** 消息水位索引的内存镜像：{hostId: {sessionId: maxSeq}}。 */
    @Volatile
    private var metaCache: Map<String, Map<String, Long>> = emptyMap()

    @Volatile
    private var metaLoaded = false

    // ============================================================
    // 上次主机
    // ============================================================

    fun lastHost(): String = prefs.getString(LAST_HOST, "") ?: ""

    fun lastHostAlias(): String = prefs.getString(LAST_HOST_ALIAS, "DSH 主机") ?: "DSH 主机"

    fun setLastHost(hostId: String, alias: String = "DSH 主机") {
        if (hostId.isEmpty()) return
        prefs.edit()
            .putString(LAST_HOST, hostId)
            .putString(LAST_HOST_ALIAS, alias.ifEmpty { "DSH 主机" })
            .apply()
    }

    // ============================================================
    // 主机列表（本地优先：菜单点开就有内容）
    // ============================================================

    fun saveHosts(hosts: List<Model.Host>) {
        prefs.edit()
            .putString(HOSTS, json.encodeToString(hostList, hostsForDisk(hosts)))
            .putLong(HOSTS_SYNCED_AT, System.currentTimeMillis())
            .apply()
    }

    fun loadHosts(): List<Model.Host> = decode(HOSTS) { json.decodeFromString(hostList, it) } ?: emptyList()

    /** 解绑后把某台主机从本地快照里抹掉；顺带清 lastHost，避免冷启动选中无权主机。 */
    fun forgetHost(hostId: String) {
        if (hostId.isEmpty()) return
        val kept = loadHosts().filter { it.id != hostId }
        val editor = prefs.edit().putString(HOSTS, json.encodeToString(hostList, kept))
        if (hostId == lastHost()) editor.remove(LAST_HOST).remove(LAST_HOST_ALIAS)
        editor.apply()
    }

    fun hostsSyncedAt(): Long = prefs.getLong(HOSTS_SYNCED_AT, 0L)

    /**
     * 换了一台中继：把只对上一台有效的主机记账全部抹掉。
     *
     * <p>主机 id 是**中继局部**的：同一台电脑在两台中继上 id 完全不同，所以换中继后
     * 留着 `hosts` / `lastHost` 只会让冷启动把界面指到一台不存在的电脑——顶栏挂着旧
     * 机器名、`/changes` 每 3 秒换一个 404。会话/已读/游标按 hostId 分键存，换了
     * 中继再也不会被读到，留着也无害，不在这里做无谓的扫描。
     */
    fun clearRelayState() {
        prefs.edit()
            .remove(HOSTS)
            .remove(HOSTS_SYNCED_AT)
            .remove(LAST_HOST)
            .remove(LAST_HOST_ALIAS)
            .apply()
    }

    fun hasHosts(): Boolean = (prefs.getString(HOSTS, "") ?: "").isNotEmpty()

    // ============================================================
    // 排序方式
    // ============================================================

    fun sortMode(): Model.SortMode = try {
        Model.SortMode.valueOf(prefs.getString(SORT_MODE, "") ?: "")
    } catch (ignored: Exception) {
        Model.SortMode.BY_WORKSPACE
    }

    fun setSortMode(mode: Model.SortMode) {
        prefs.edit().putString(SORT_MODE, mode.name).apply()
    }

    // ============================================================
    // 模型目录
    // ============================================================

    fun saveCatalog(catalog: Model.ModelCatalog) {
        prefs.edit().putString(MODEL_CATALOG, json.encodeToString(Model.ModelCatalog.serializer(), catalog)).apply()
    }

    /** 没有缓存时返回 null，调用方据此决定是否必须等网络。 */
    fun loadCatalog(): Model.ModelCatalog? =
        decode(MODEL_CATALOG) { json.decodeFromString(Model.ModelCatalog.serializer(), it) }

    // ============================================================
    // 会话 / 消息
    // ============================================================

    fun saveSessions(hostId: String, sessions: List<Model.Session>) {
        if (hostId.isEmpty()) return
        prefs.edit()
            .putString(sessionsKey(hostId), json.encodeToString(sessionList, sessions))
            .putString(LAST_HOST, hostId)
            .apply()
    }

    fun loadSessions(hostId: String): List<Model.Session> {
        // 冷启动时主机列表还没回来，hostId 为空：先用上次的主机，别把缓存白白丢掉。
        val key = if (hostId.isEmpty()) lastHost() else hostId
        if (key.isEmpty()) return emptyList()
        val rows = decode(sessionsKey(key)) { json.decodeFromString(sessionList, it) } ?: return emptyList()
        return rows.filter { visibleSessionTitle(it.title) }
    }

    fun saveMessages(hostId: String, sessionId: String, messages: List<Model.Message>) {
        if (hostId.isEmpty() || sessionId.isEmpty()) return
        val key = messagesKey(hostId, sessionId)
        // 深拷贝快照：Message.steps/actions/images 是 MutableList，流式更新时主线程
        // 会往同一个对象里 add，落盘线程再逐项序列化就撞 ConcurrentModificationException
        // （2026-10-27 真机实测：FATAL EXCEPTION: dsh-v2-cache / CollectionLikeSerializer）。
        // 浅拷贝 ArrayList(messages) 不够——元素仍是同一批可变对象。
        pendingSaves[key] = messages.map { it.copy(
            actions = it.actions.toMutableList(),
            steps = it.steps.toMutableList(),
            images = it.images.toMutableList(),
        ) }
        saveExecutor.execute {
            val snapshot = pendingSaves.remove(key) ?: return@execute // 已被更新的快照吸收
            writeMessages(hostId, sessionId, snapshot)
        }
    }

    /**
     * 同步写一条会话的正文，**返回是否真的落盘成功**。
     *
     * <p>[saveMessages] 是排队异步写、吞掉异常——对流的每一帧没问题（丢一帧下次
     * 还有），但对 `/sync` 的合并结论**不行**：那条合并结果一旦落盘失败，推进
     * 归档游标就等于永久丢掉这段记录，而队列写根本不会告诉调用方自己失败了。
     *
     * <p>调用点都在网络回调线程（不是主线程），一次几 KB 的同步写代价可以接受。
     */
    fun saveMessagesNow(hostId: String, sessionId: String, messages: List<Model.Message>): Boolean {
        if (hostId.isEmpty() || sessionId.isEmpty()) return false
        // 同一会话的排队写先落地，避免稍后一份更旧的快照把我们覆盖回去。
        val key = messagesKey(hostId, sessionId)
        pendingSaves.remove(key)
        val snapshot = messages.map { it.copy(
            actions = it.actions.toMutableList(),
            steps = it.steps.toMutableList(),
            images = it.images.toMutableList(),
        ) }
        return writeMessages(hostId, sessionId, snapshot)
    }

    fun loadMessages(hostId: String, sessionId: String): List<Model.Message> {
        if (hostId.isEmpty() || sessionId.isEmpty()) return emptyList()
        val file = messagesFile(hostId, sessionId)
        val raw = try { if (file.isFile) file.readText() else "" } catch (e: Exception) { "" }
        if (raw.isNotEmpty()) {
            try { return json.decodeFromString(messageList, raw) } catch (ignored: Exception) { }
        }
        // 老版本把正文存在 SharedPreferences 里：读到就顺手迁到独立文件并删掉旧键，
        // 否则那段几 MB 的 XML 会永远留在 prefs 里，冷启动照样要整表解析。
        val legacy = prefs.getString(legacyMessagesKey(hostId, sessionId), "") ?: ""
        if (legacy.isEmpty()) return emptyList()
        val restored = try { json.decodeFromString(messageList, legacy) } catch (ignored: Exception) { null } ?: return emptyList()
        saveExecutor.execute {
            // 只在文件还不存在时迁移：如果这期间已有 saveMessages 抢先落盘，那份更新，
            // 用过期的 legacy 覆盖会让刚同步下来的消息回退。
            if (!messagesFile(hostId, sessionId).isFile) writeMessages(hostId, sessionId, restored)
            prefs.edit().remove(legacyMessagesKey(hostId, sessionId)).apply()
        }
        return restored
    }

    /**
     * 某会话本地已落盘消息的最大 seq；没有消息返回 0。
     *
     * 走 [MESSAGE_META] 小表，不解消息正文——调用方（fillPreviews 给每个会话
     * 算水位）原先每调一次就是一次几十 KB 的全量反序列化。
     */
    fun maxSeq(hostId: String, sessionId: String): Long {
        if (hostId.isEmpty() || sessionId.isEmpty()) return 0
        metaOf(hostId)[sessionId]?.let { return it }
        // 索引缺失（老数据、外部清理）：退回解一次并补上索引，之后就走小表。
        val computed = loadMessages(hostId, sessionId).asSequence().map { it.seq }.filter { it > 0 }.maxOrNull() ?: 0L
        if (computed > 0) {
            synchronized(this) { metaCache = metaCache.toMutableMap().apply { this[hostId] = (this[hostId] ?: emptyMap()).toMutableMap().apply { put(sessionId, computed) } } }
            saveExecutor.execute { persistMeta() }
        }
        return computed
    }

    /**
     * 一次性迁移：清掉 v1 时代留在 SharedPreferences 里的正文，只留小的键值事实。
     *
     * <p>**绝不能再删 `files/messages/` 下的文件**。那里是 v2 自己的正文落盘目录
     * （每会话一个 JSON），和 v1 的 prefs 副本是两回事。2026-10-31 真机实测：本方法
     * 由 `AppViewModel` 的 `init` 无条件调用、且「删目录下全部文件」，于是每次**冷启动**
     * 都把最近一路写下的正文全抹掉——表现就是用户报的「每次重开 App，列表 snippet
     * 全清空、点进会话要重新连一遍再加载」。真正的 v1 残留在 [LEGACY_MESSAGES_PREFIX]
     * 这些 prefs 键里，删它们就够，且天然幂等。
     *
     * <p>游标有意**不迁移**：`local_acked_seq` 全置 0，由 `/v2/sync` 重拉。
     *
     * @return 释放的字节数（只统计真正删掉的 prefs 文本）。
     */
    fun purgeLegacyMessageBodies(): Int {
        pendingSaves.clear()
        metaCache = emptyMap()
        metaLoaded = false
        val editor = prefs.edit().remove(MESSAGE_META)
        // 老版本把正文直接写在 prefs 的 `messages:<host>:<sess>` 键上（迁移前的形态），
        // 这些几 MB 的记录不删掉，`dsh-v2-cache.xml` 每次 apply 都要整表重写。
        var freed = 0
        for (key in prefs.all.keys) {
            if (!key.startsWith(LEGACY_MESSAGES_PREFIX)) continue
            freed += (prefs.all[key] as? String)?.length ?: 0
            editor.remove(key)
        }
        editor.apply()
        return freed
    }

    /** 退出登录时清空：缓存是明文，不该跨账号留下。 */
    fun clear() {
        pendingSaves.clear()
        metaCache = emptyMap()
        prefs.edit().clear().apply()
        saveExecutor.execute {
            try { messagesDir.listFiles()?.forEach { it.delete() } } catch (ignored: Exception) { }
        }
    }

    // ============================================================
    // 已读时刻（未读是纯客户端概念，必须自己记账）
    // ============================================================

    /** 全部会话的已读时刻；缺失或损坏时返回空表（全当未读，宁可多提示不漏提示）。 */
    fun readAtMap(): Map<String, Long> = decode(READ_AT) { json.decodeFromString(longMap, it) } ?: emptyMap()

    /** 首次安装时列表全亮绿点是「没有历史」不是「有新消息」：第一次拿到列表整体记为已读。 */
    fun hasReadBaseline(): Boolean = prefs.getBoolean(READ_BASELINE, false)

    /** 落基线并置位；一次写完，避免中途崩溃留下「半基线 + 已置位」。 */
    fun seedReadBaseline(sessions: List<Model.Session>) {
        val map = HashMap<String, Long>()
        for (s in sessions) if (s.id.isNotEmpty()) map[s.id] = s.updatedAt
        prefs.edit()
            .putString(READ_AT, json.encodeToString(longMap, map))
            .putBoolean(READ_BASELINE, true)
            .apply()
    }

    /** 记下某条会话已被读到这个时刻。只增不减，避免时钟回拨把旧记录改早。 */
    fun markRead(sessionId: String, at: Long) {
        if (sessionId.isEmpty() || at <= 0) return
        val map = HashMap(readAtMap())
        if ((map[sessionId] ?: 0L) >= at) return
        map[sessionId] = at
        prefs.edit().putString(READ_AT, json.encodeToString(longMap, map)).apply()
    }

    // ============================================================
    // 增量同步游标
    // ============================================================

    /**
     * 某主机下全部会话的**归档游标**：`hostId -> (sessionId -> seq)`。
     *
     * <p>语义只有一个：**服务端已确认「seq 小于等于它的归档记录这一端已经吃到」**。
     * 它只应该由服务端回的 `nextCursor` 写入，永远不该由以下任何一项推导：
     * 会话列表的 `lastSeq`（那是元数据水位，只能证明服务端有新活动）、本地消息的
     * 最大 seq（那是正文展示位置）、SSE 变更帧自带的 `latestSeq`。
     *
     * <p>把三者混用过一次，代价是整条同步链失灵：`lastSeq` 被写进游标之后，游标
     * 虚高到 908 而本地正文只到 59，服务端认为「你都齐了」于是一个字节都不下发了
     * （2026-10-29 评审现场复现）。
     */
    fun archiveCursors(hostId: String): Map<String, Long> = nestedLong(ARCHIVE_CURSORS, hostId)

    /** 单个会话的归档游标；没有返回 0（从归档头开始）。 */
    fun archiveCursor(hostId: String, sessionId: String): Long =
        if (hostId.isEmpty() || sessionId.isEmpty()) 0L else archiveCursors(hostId)[sessionId] ?: 0L

    /** 记一个归档游标（只增不减：seq 对应的记录内容不会再变）。 */
    fun saveArchiveCursor(hostId: String, sessionId: String, seq: Long) {
        if (sessionId.isEmpty() || seq <= 0) return
        saveArchiveCursors(hostId, mapOf(sessionId to seq))
    }

    /**
     * 批量推进归档游标，一次重写。
     *
     * `ARCHIVE_CURSORS` 是 `{hostId: {sessionId: seq}}` 的嵌套 JSON，单条写一次就是
     * 整表重写；/sync 回调里按会话逐条调的话，50 个会话 = 50 次整表序列化 + 50 次
     * `apply()`，这本身就够把主线程压出掉帧。
     */
    fun saveArchiveCursors(hostId: String, cursors: Map<String, Long>) {
        if (hostId.isEmpty() || cursors.isEmpty()) return
        updateNestedLong(ARCHIVE_CURSORS, hostId) { host ->
            for ((sessionId, seq) in cursors) {
                if (sessionId.isEmpty() || seq <= 0) continue
                if ((host[sessionId] ?: 0L) < seq) host[sessionId] = seq
            }
        }
    }

    /** 丢弃某主机的归档游标（debug「强制重拉」、退出登录）：下次 /sync 从 0 开始。 */
    fun clearArchiveCursors(hostId: String) {
        removeNestedHost(ARCHIVE_CURSORS, hostId)
    }

    /**
     * 一次性把**已污染的旧游标**迁移成可信的归档游标，返回迁移的会话数。
     *
     * 判定规则在 [SyncPlanner.migrateCursors]（纯函数，有单测）：旧值一律不可信，
     * 只能取 `min(旧游标, 本地正文最大 seq)` 这个可证下界。这里只负责读写与版本号。
     */
    fun migrateArchiveCursors(hostId: String): Int {
        if (hostId.isEmpty()) return 0
        if (archiveCursorMigrated()) return 0
        val legacy = nestedLong(LEGACY_SYNC_CURSORS, hostId)
        val migrated = if (legacy.isEmpty()) {
            0
        } else {
            val seeded = SyncPlanner.migrateCursors(legacy) { maxSeq(hostId, it) }
            saveArchiveCursors(hostId, seeded)
            // 旧表只在这一版里当作迁移来源，之后不再读写——两套相反语义不能长期并存。
            removeNestedHost(LEGACY_SYNC_CURSORS, hostId)
            seeded.size
        }
        prefs.edit().putInt(ARCHIVE_CURSOR_VERSION, ARCHIVE_CURSOR_VERSION_CURRENT).apply()
        return migrated
    }

    /** 迁移是否已经跑过（debug 面板/诊断用）。 */
    fun archiveCursorMigrated(): Boolean =
        prefs.getInt(ARCHIVE_CURSOR_VERSION, 0) >= ARCHIVE_CURSOR_VERSION_CURRENT

    // ============================================================
    // 仅本机删除（隐藏）的会话：记录删除时的 lastSeq，有新消息才重新出现
    // ============================================================

    /** 从本机列表隐藏一个会话，并记下删除时的最大 seq（作为「新消息」的判据）。 */
    fun hideSession(hostId: String, sessionId: String, lastSeq: Long) {
        updateNestedLong(HIDDEN, hostId) { host -> host[sessionId] = maxOf(0L, lastSeq) }
    }

    /** 隐藏时记录的 seq；没隐藏返回 -1。 */
    fun hiddenCursor(hostId: String, sessionId: String): Long =
        nestedLong(HIDDEN, hostId)[sessionId] ?: -1L

    /** 是否应从本机列表隐藏；currentSeq 超过隐藏时的 seq 说明来了新消息，自动解除隐藏。 */
    fun isHidden(hostId: String, sessionId: String, currentSeq: Long): Boolean {
        val cursor = hiddenCursor(hostId, sessionId)
        if (cursor < 0) return false
        if (currentSeq > cursor) {
            clearHidden(hostId, sessionId)
            return false
        }
        return true
    }

    /** 解除隐藏（有新消息，或用户手动恢复）。 */
    fun clearHidden(hostId: String, sessionId: String) {
        updateNestedLong(HIDDEN, hostId) { host -> host.remove(sessionId) }
        removeEmptyHost(HIDDEN)
    }

    // ============================================================
    // 草稿
    // ============================================================

    /** 会话草稿；无草稿返回空串。 */
    fun draft(hostId: String, sessionId: String): String =
        nestedString(DRAFTS, hostId)[sessionId] ?: ""

    /** 存草稿；空文本删除该条，避免草稿表无界增长。 */
    fun saveDraft(hostId: String, sessionId: String, text: String) {
        if (hostId.isEmpty() || sessionId.isEmpty()) return
        synchronized(this) {
            try {
                val all = decodeTop(DRAFTS) { json.decodeFromString(stringMap, it) }.toMutableMap()
                val host = (all[hostId] ?: emptyMap()).toMutableMap()
                if (text.isEmpty()) host.remove(sessionId) else host[sessionId] = text
                if (host.isEmpty()) all.remove(hostId) else all[hostId] = host
                prefs.edit().putString(DRAFTS, encodeTop(all) { m -> json.encodeToString(stringMap, m) }).apply()
            } catch (ignored: Exception) { }
        }
    }

    // ============================================================
    // 内部工具
    // ============================================================

    private fun read(key: String): String = prefs.getString(key, "") ?: ""

    private fun <T> decode(key: String, block: (String) -> T): T? {
        val raw = read(key)
        if (raw.isEmpty()) return null
        return try { block(raw) } catch (ignored: Exception) { null }
    }

    private fun sessionsKey(hostId: String) = "sessions:$hostId"

    private fun messagesKey(hostId: String, sessionId: String) = "$hostId:$sessionId"

    /** 老版本存在 prefs 里的消息键（迁移用，迁移完就删）。 */
    private fun legacyMessagesKey(hostId: String, sessionId: String) = "$LEGACY_MESSAGES_PREFIX$hostId:$sessionId"

    /** 每会话一个正文文件；会话 id 只保留安全字符，避免路径注入/非法文件名。 */
    private fun messagesFile(hostId: String, sessionId: String): File {
        val name = messagesKey(hostId, sessionId).replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(messagesDir, "$name.json")
    }

    /** 原子写：先写临时文件再 rename，进程被杀时不会留下半截 JSON。 */
    /**
     * 原子写一条会话的正文。
     *
     * @return 是否落盘成功。失败时**不能**当作「已经保存」——`/sync` 的调用方要靠
     *   这个结果决定归档游标能不能推进（推进了就等于宣称这段记录已经到手）。
     */
    private fun writeMessages(hostId: String, sessionId: String, messages: List<Model.Message>): Boolean {
        val kept = trimForDisk(messages)
        val file = messagesFile(hostId, sessionId)
        if (kept.isEmpty()) {
            // 空会话不留空文件，也不留索引条目：否则 maxSeq 每次都要退回解一次正文。
            file.delete()
            updateMeta(hostId, sessionId, null)
            return true
        }
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            // 单线程 executor + 同步 FileOutputStream：这里不追求并发，只求崩溃安全。
            java.io.FileOutputStream(tmp).use { out ->
                out.write(json.encodeToString(messageList, kept).toByteArray())
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
        } catch (ignored: Exception) {
            Diag.log("cache", "写入会话正文失败 host=$hostId session=$sessionId: ${ignored.message}")
            file.delete()
            return false
        }
        // 0 也要记：说明「文件在、但没有 seq>0 的正文」，读的时候不必再解开确认。
        updateMeta(hostId, sessionId, kept.asSequence().map { it.seq }.filter { it > 0 }.maxOrNull() ?: 0L)
        return true
    }

    /** 更新水位索引；seq 为 null 表示这个会话没有正文，删掉条目。 */
    private fun updateMeta(hostId: String, sessionId: String, seq: Long?) {
        synchronized(this) {
            val host = (metaCache[hostId] ?: emptyMap()).toMutableMap()
            if (seq == null) host.remove(sessionId) else host[sessionId] = seq
            metaCache = metaCache.toMutableMap().apply {
                if (host.isEmpty()) remove(hostId) else put(hostId, host)
            }
        }
        saveExecutor.execute { persistMeta() }
    }

    /** 水位索引落盘：几百字节的小表。调用方保证在 saveExecutor 单线程里。 */
    private fun persistMeta() {
        val serialized = synchronized(this) {
            encodeTop(metaCache.mapValues { it.value.toMutableMap() }) { m -> json.encodeToString(longMap, m) }
        }
        prefs.edit().putString(MESSAGE_META, serialized).apply()
    }

    /** 读取水位索引（内存镜像优先，缺失时解一次小表）。 */
    @Synchronized
    private fun metaOf(hostId: String): Map<String, Long> {
        if (!metaLoaded) {
            metaCache = decodeTop(MESSAGE_META) { json.decodeFromString(longMap, it) }
            metaLoaded = true
        }
        return metaCache[hostId] ?: emptyMap()
    }

    /** 顶层形状 {"<hostId>": {…}} 的读取。 */
    private fun <V> decodeTop(key: String, block: (String) -> V): Map<String, V> {
        val raw = read(key)
        if (raw.isEmpty()) return emptyMap()
        return try {
            val obj = json.parseToJsonElement(raw).jsonObject
            obj.entries.associate { (hostId, v) -> hostId to block(v.toString()) }
        } catch (ignored: Exception) {
            emptyMap()
        }
    }

    private fun <V> encodeTop(map: Map<String, V>, block: (V) -> String): String = buildJsonObject {
        for ((hostId, value) in map) put(hostId, json.parseToJsonElement(block(value)))
    }.toString()

    /** 嵌套长整型表的读取：{hostId: {sessionId: seq}}。 */
    private fun nestedLong(key: String, hostId: String): Map<String, Long> {
        val all = decodeTop(key) { json.decodeFromString(longMap, it) }
        return all[hostId] ?: emptyMap()
    }

    private fun nestedString(key: String, hostId: String): Map<String, String> {
        val all = decodeTop(key) { json.decodeFromString(stringMap, it) }
        return all[hostId] ?: emptyMap()
    }

    /** 原子更新某个顶层键下某主机的长整型子表。 */
    private fun updateNestedLong(key: String, hostId: String, mutate: (MutableMap<String, Long>) -> Unit) {
        if (hostId.isEmpty()) return
        synchronized(this) {
            try {
                val all = decodeTop(key) { json.decodeFromString(longMap, it) }.toMutableMap()
                val host = (all[hostId] ?: emptyMap()).toMutableMap()
                mutate(host)
                if (host.isEmpty()) all.remove(hostId) else all[hostId] = host
                prefs.edit().putString(key, encodeTop(all) { m -> json.encodeToString(longMap, m) }).apply()
            } catch (ignored: Exception) { }
        }
    }

    private fun removeNestedHost(key: String, hostId: String) {
        if (hostId.isEmpty()) return
        synchronized(this) {
            try {
                val all = decodeTop(key) { json.decodeFromString(longMap, it) }.toMutableMap()
                all.remove(hostId)
                prefs.edit().putString(key, encodeTop(all) { m -> json.encodeToString(longMap, m) }).apply()
            } catch (ignored: Exception) { }
        }
    }

    /** 删除所有空主机条目（clearHidden 之后避免空对象积压）。 */
    private fun removeEmptyHost(key: String) {
        synchronized(this) {
            try {
                val all = decodeTop(key) { json.decodeFromString(longMap, it) }.filterValues { it.isNotEmpty() }
                prefs.edit().putString(key, encodeTop(all) { m -> json.encodeToString(longMap, m) }).apply()
            } catch (ignored: Exception) { }
        }
    }

    /** 落盘前裁剪：只留 MESSAGE_LIMIT 条，并丢掉空占位回合（流式中的临时壳）。 */
    private fun trimForDisk(messages: List<Model.Message>): List<Model.Message> {
        val kept = ArrayList<Model.Message>()
        val from = maxOf(0, messages.size - MESSAGE_LIMIT)
        for (i in from until messages.size) {
            val m = messages[i]
            if (m.text.isEmpty() && m.failure.isEmpty() && m.steps.isEmpty() && !m.hasProcess) continue
            kept.add(m)
        }
        return kept
    }
}

/**
 * 落盘前把 `online` 一律清成 false。
 *
 * 在线状态是**时效性事实**，只能来自中继的实时答复：把它写进 prefs，就等于给下次冷启动
 * 埋一个「三天前还亮着的绿点」。电脑关机、被拔网线、被重装之后，App 却仍显示「在线」，
 * 用户会以为中继出问题了（2026-10-29 用户直报）。所以快照只保留「有哪些主机」这类稳定
 * 事实，在线与否等 `listHosts` 回来再填。
 */
internal fun hostsForDisk(hosts: List<Model.Host>): List<Model.Host> = hosts.map { it.copy(online = false) }
