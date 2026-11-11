package com.dsh.mobile.v2.data

import android.content.Context
import androidx.room.Room
import com.dsh.mobile.v2.net.ChatSyncActor

/**
 * [ChatSyncActor.ChatStore] 的 Room 实现，兼 [TimelineDatabase] 的进程级入口。
 *
 * 这里唯一有语义的东西是 [commit]：它必须把「写记录」和「推进水位」放进同一个
 * 事务（`TimelineDao.commit` 的 `@Transaction`），并且**返回真正落盘的最大 seq**，
 * 让调用方拿它跟中继声称的 `nextCursorSeq` 对账。两者不一致时以本地为准——
 * 否则进程在两次写之间被杀，水位就越过了一批没落盘的记录，那些 seq 再也不会
 * 被拉回来（设计 §invariant 4）。
 */
class TimelineStore(
    private val db: TimelineDatabase,
) : ChatSyncActor.ChatStore {

    private val dao get() = db.timeline()
    private val ledger get() = db.interactions()

    override suspend fun deleteRecord(sessionId: String, seq: Long): Boolean =
        dao.deleteRecord(sessionId, seq) > 0

    override suspend fun watermark(sessionId: String): SessionWatermarks {
        val meta = dao.meta(sessionId) ?: return SessionWatermarks()
        return SessionWatermarks(
            archiveCovered = ArchiveCoveredSeq(meta.archiveCoveredSeq),
            localAcked = LocalAckedSeq(meta.localAckedSeq),
        )
    }

    override suspend fun commit(
        sessionId: String,
        records: List<TimelineRecord>,
        watermarks: SessionWatermarks,
    ): Int {
        val now = System.currentTimeMillis()
        val existing = dao.meta(sessionId)
        val meta = SessionMetaEntity(
            sessionId = sessionId,
            title = existing?.title ?: "",
            updatedAt = maxOf(existing?.updatedAt ?: 0, records.maxOfOrNull { it.timestampMs } ?: 0),
            running = existing?.running ?: false,
            // 列还在（避免一次破坏性迁移），但不再从水位读写：它已不是水位的一部分。
            serverMetaSeq = 0L,
            // 水位只进不退：中继重启后 archiveCovered 可能短暂回落到 0，跟着退会让
            // 手机误判「缺口不可恢复」，然后去重拉一遍已经有的历史。
            archiveCoveredSeq = maxOf(existing?.archiveCoveredSeq ?: 0, watermarks.archiveCovered.value),
            localAckedSeq = maxOf(existing?.localAckedSeq ?: 0, watermarks.localAcked.value),
            blank = existing?.blank ?: false,
            preview = existing?.preview ?: "",
        )
        // 投影器永远不读的 kind 不落盘。
        //
        // 这是「缓存凭空涨了好几倍」的直接成因（2026-11-03 生产库实测）：
        // `Projector` 对 `META_PATCH` / `STREAM_DELTA` 的处理是 `-> Unit`
        // （见 Projector.kt 的 when(record.kind)），一个字段都不读；可这里照单全收
        // 写进 Room。生产库 58736 行里 META_PATCH 占 33022 条 / 14.2MB（9.4%），
        // 全是 job/settled、step/start、step/end、job/registered、request/header、
        // agent/inbox/spliced 这类纯结构事件。
        //
        // 它还有第二个代价：TAIL_LIMIT = 200 是**按记录条数**取的，元数据占掉的
        // 名额正文就进不来。实测 session-37ab11 的尾部 200 条里 42 条是元数据、
        // 正文 0 条；import-2a5da4a 里元数据 107 条、正文只有 1 条 —— 用户看到的
        // 就是「翻历史全是思考，没有正文」。
        //
        // 水位不受影响：watermarks 由调用方按 page.records 的最大 seq 算好传进来
        // （ChatSyncActor.commitPage），与这里丢不丢无关。丢掉的只是本地副本，
        // 中继那份仍是完整的 14 天归档。
        //
        // 先算出「这批里真正会被写进去的最大 seq」并不容易（INSERT OR IGNORE 的
        // changes 不告诉我们具体是哪几条），所以只统计条数；水位的正确性由
        // ChatSyncActor 用 page.records.maxOf{seq} 侧算，不依赖这个返回值。
        // 唯一的例外是后台任务的生命周期事件（`job/registered` / `job/settled`）：
        // 它们是**会话级状态**，而且「后台任务」那颗胶囊要在冷启动时就能重建「现在
        // 有哪些任务在跑」。不落盘的话进会话只拿到尾部窗口，刷一下就全丢，胶囊会
        // 时有时无。体量也小：生产库里这两种事件合计 11705 条 / 4.4MB，
        // 远不是 `ASSISTANT_TEXT` 那 67MB 的量级。
        val kept = if (records.isEmpty()) {
            records
        } else {
            records.filterNot { it.kind in DROPPED_KINDS && !it.isJobLifecycle() }
        }
        dao.commit(kept.map { TimelineRecordEntity.of(it, now) }, meta, TimelineDatabase.PER_SESSION_LIMIT)
        return records.maxOfOrNull { it.seq }?.let { seq ->
            if (seq > (existing?.localAckedSeq ?: 0)) records.size else 0
        } ?: 0
    }

    override suspend fun tail(sessionId: String, limit: Int): List<TimelineRecord> {
        // 表里按 seq DESC 取尾部，回来要按顺序给投影器。
        val raw = dao.tail(sessionId, limit).map { it.toRecord() }.sortedBy { it.seq }
        if (raw.isEmpty()) return raw
        // 尾部窗口按**记录条数**取是不够的。
        //
        // 2026-11-03 用户实测「查看历史消息的时候全是思考内容，没有正文」：
        // 一轮对话在库里是十几二十条记录，而 `ASSISTANT_TEXT` 里 70% 只有
        // `type=reasoning`（见 [DROPPED_KINDS] 与 [TimelineDao.bodyPage] 的说明）。
        // 生产库按这个口径数出来的「进会话第一屏」：`session-37ab11` 200 条里
        // 正文 0 条、`import-2a5da4a` 正文 1 条、`session-1a2105` 正文 10 条
        // —— 用户滑一屏看到的几乎全是思考与工具。
        //
        // 所以尾部窗口再补一个**正文条数下限**：不足 [TAIL_BODY_MIN] 段就往回扩。
        // 扩窗点由 `bodyPage` 一条索引查询给出：它只返回 `has_body = 1` 的行，
        // 取「还差几段」那么多条，最老那条就是该扩到的地方。
        //
        // 这里刻意**不再**逐批倒扫 + 计数：那个做法要靠一个扫描上限兜底，
        // 2026-11-03 模拟器实测「200 条纯思考」就把 64 条上限打穿，预览整行消失。
        // 有 `has_body` 列之后不需要上限，也不需要 N 次查询。
        val bodies = raw.count { it.hasBody() }
        if (bodies >= TAIL_BODY_MIN) return raw
        val need = TAIL_BODY_MIN - bodies
        // 先花一条点查确认「下面真的还有记录」。否则对于「整个会话就只有几条正文」
        // 的会话，每次 commitPage 都会白跑一次扩窗查询。
        if (dao.earlier(sessionId, raw.first().seq, 1).isEmpty()) return raw
        val bodiesBefore = dao.bodyPage(sessionId, raw.first().seq, need)
        if (bodiesBefore.isEmpty()) return raw
        // 倒序结果里最老的那条 = 第 need 条正文（不足 need 时就是库里最老的正文）。
        val scannedOldest = bodiesBefore.last().seq
        // 把这一段整段取回来：正文之间的步骤、用户消息、工具调用都在里面，
        // 只补 ASSISTANT_TEXT 会让时间线断成「正文孤岛」。
        val older = dao.between(sessionId, scannedOldest, raw.first().seq)
        if (older.isEmpty()) return raw
        return (older.map { it.toRecord() } + raw).sortedBy { it.seq }
    }

    override suspend fun between(
        sessionId: String,
        sinceSeq: Long,
        beforeSeq: Long,
    ): List<TimelineRecord> =
        dao.between(sessionId, sinceSeq, beforeSeq).map { it.toRecord() }.sortedBy { it.seq }

    override suspend fun earlier(sessionId: String, beforeSeq: Long, limit: Int): List<TimelineRecord> =
        dao.earlier(sessionId, beforeSeq, limit).map { it.toRecord() }.sortedBy { it.seq }

    override suspend fun earlierByTurns(
        sessionId: String,
        beforeSeq: Long,
        turns: Int,
    ): List<TimelineRecord> {
        // 边界按**真的带正文的记录**数，不是按 `kind = 'ASSISTANT_TEXT'` 数。
        //
        // 这里原来用的是 `dao.assistantBoundary`（一条 SQL 数第 N 条 ASSISTANT_TEXT），
        // 而那个 kind 名不副实：宿主每次 `assistant/message` 都记成它，一回合里
        // 大量只有 `type=reasoning`。生产库实测 70% 的 ASSISTANT_TEXT 没有正文
        // （5763/7774 条、67MB），于是「30 段正文」实际只带回
        // `session-8ac8cd`=1 段、`session-9f99c5`=5 段、`session-03ca06`=6 段
        // —— 用户点一次「载入更早」几乎看不到新正文（2026-11-03 实测）。
        //
        // 改成查 `has_body = 1` 的索引：一条 SQL 直接给出第 turns 条真正文，
        // 不需要倒扫、不需要上限、也不会因为连续无正文而失效。
        val boundary = dao.bodyPage(sessionId, beforeSeq, turns).lastOrNull()?.seq
        if (boundary != null) {
            // 命中：取 [boundary, beforeSeq) 这一整段，正文与步骤都在里面。
            return dao.between(sessionId, boundary, beforeSeq).map { it.toRecord() }.sortedBy { it.seq }
        }
        // 不足 turns 段正文：把 beforeSeq 之前的全部取回来（真到底了）。
        return dao.earlier(sessionId, beforeSeq, EARLIER_SCAN_LIMIT).map { it.toRecord() }.sortedBy { it.seq }
    }

    /**
     * 列表首行预览：该会话**最近一条真的带正文**的助手消息。
     *
     * <p>不是「最近一条 `ASSISTANT_TEXT`」——那个 kind 名不副实：宿主每次
     * `assistant/message` 都记成它，而一回合里助手会发好几条，其中大量只有
     * `type=reasoning`、一个字正文都没有。直接取最新那条会让预览变空白
     * （2026-11-02 用户实测）。生产库实测 79% 的 `ASSISTANT_TEXT` 没有正文，
     * 27 个会话里 7 个的「最新一条」正是这种。
     *
     * <p>判据落在 `has_body` 列上（`dao.latestBody`）：一条索引点查，没有扫描上限。
     * 上一版是「倒扫 + 64 条上限」，2026-11-03 模拟器实测 —— 造 200 条纯思考就把
     * 上限打穿，列表预览整行消失。上限兜底是权宜之计，不是修复。
     */
    override suspend fun latestAssistant(sessionId: String): TimelineRecord? =
        dao.latestBody(sessionId)?.toRecord()

    /**
     * 把一条存根的正文补上。
     *
     * <p>只对「还是存根」的行生效（SQL 里带 `oversized_stub = 1`）：重复补、或者补
     * 一份比后来更新的更旧的正文，都会把完整内容换回半截，那比不补更糟。
     */
    override suspend fun fillStub(sessionId: String, seq: Long, payload: String): Int =
        dao.replaceStub(sessionId, seq, payload)

    /** 还没取回正文的存根，按 seq 升序（最老的最该补）。 */
    override suspend fun pendingStubs(sessionId: String, limit: Int): List<TimelineRecord> =
        dao.stubs(sessionId, limit).map { it.toRecord() }

    /** 会话列表用：全部会话的元数据（含三水位与本地预览）。 */
    suspend fun allMeta(): List<SessionMetaEntity> = dao.allMeta()

    suspend fun pruning(before: Long): Int = dao.pruneChunk(before)

    /**
     * 把老版本写进来的 [DROPPED_KINDS] 清掉（一次性、分块）。
     *
     * <p>[commit] 的过滤只对新写入生效。升级前已经落盘的 `META_PATCH` 不会自己
     * 消失，而它们**挤占的是同一个 2000 条/会话的额度** —— 不清就等于修复只做了一半
     * （实测一台设备的库里 91 条 `META_PATCH` 对 43 条 `ASSISTANT_TEXT`，占掉七成）。
     *
     * <p>分块删（[CLEANUP_CHUNK] 行一轮）而不是一句 `DELETE`：与 [pruning] 同样的理由，
     * 一次删几十万行会长时间顶着写锁，用户下一次翻页就卡在那里。
     *
     * @return 一共删掉多少行（0 表示本来就没有）。
     */
    suspend fun cleanupDroppedKinds(): Int {
        var total = 0
        while (true) {
            val deleted = dao.deleteByKindsChunk(DROPPED_KINDS.map { it.name }, CLEANUP_CHUNK)
            if (deleted <= 0) return total
            total += deleted
        }
    }

    suspend fun count(sessionId: String): Int = dao.count(sessionId)

    // ============================================================
    // 审批/提问账本（§8：跨重启也不能让点过的卡复活）
    // ============================================================

    /**
     * 记一张卡。
     *
     * 同名 [interactionId] 已有 [LedgerState.RESOLVED] / [LedgerState.CANCELLED] 时
     * **不覆盖**——宿主在新连接上会把未结算的 waterfall 重投一遍，而「已经结算过」
     * 这个事实比「它又来了」重要。`CLICKED_INFLIGHT` 同理：用户刚点过，正在等回执。
     */
    suspend fun noteInteraction(entry: InteractionLedgerEntry) {
        val existing = ledger.get(entry.sessionId, entry.interactionId)?.toLedgerEntry()
        if (existing != null && existing.state != LedgerState.PENDING) return
        ledger.put(
            InteractionEntity(
                sessionId = entry.sessionId,
                interactionId = entry.interactionId,
                kind = entry.kind,
                state = entry.state.name,
                payloadJson = entry.payloadJson,
                updatedAt = entry.updatedAt,
            )
        )
    }

    /** 用户点了：从这一刻起，同一张卡的重投一律静默。 */
    suspend fun markClicked(sessionId: String, interactionId: String, now: Long = System.currentTimeMillis()) {
        val existing = ledger.get(sessionId, interactionId) ?: return
        ledger.put(existing.copy(state = LedgerState.CLICKED_INFLIGHT.name, updatedAt = now))
    }

    /** 宿主确认结算。 */
    suspend fun markResolved(sessionId: String, interactionId: String, now: Long = System.currentTimeMillis()) {
        val existing = ledger.get(sessionId, interactionId) ?: return
        ledger.put(existing.copy(state = LedgerState.RESOLVED.name, updatedAt = now))
    }

    /** 宿主 cancel，或者回答没送达：撤销记账，让这张卡还能重新出现。 */
    suspend fun forgetInteraction(sessionId: String, interactionId: String, cancelled: Boolean = false, now: Long = System.currentTimeMillis()) {
        val existing = ledger.get(sessionId, interactionId) ?: return
        ledger.put(
            existing.copy(
                state = if (cancelled) LedgerState.CANCELLED.name else LedgerState.PENDING.name,
                updatedAt = now,
            )
        )
    }

    /** 还没结算、也没被点过的卡，按到达顺序。重连/重启后靠它把卡片画回来。 */
    suspend fun pendingInteractions(sessionId: String): List<InteractionLedgerEntry> =
        ledger.pending(sessionId).map { it.toLedgerEntry() }

    companion object {
        private const val NAME = "dsh-timeline.db"

        /**
         * 不落盘的 kind：投影器一个字段都不读，存下来只是占空间、挤掉正文名额。
         *
         * <p>见 [commit] 里的说明。`META_PATCH` 是 `job/settled` / `step/start` /
         * `step/end` / `job/registered` / `request/header` 这类结构事件（生产库
         * 14.2MB / 9.4%）；`STREAM_DELTA` 是直播期间的流式增量，宿主的
         * `ASSISTANT_TEXT` 落定后会完全覆盖它，归档侧本来就不存。
         *
         * <p>**队列与任务不受影响**：`agent/inbox/spliced` 与 `todo/write` 在
         * `packages/core/src/classify.ts` 里已经是独立的 `INBOX_SPLICE` /
         * `TASK_LIST` kind，不落在 `META_PATCH` 里。丢的只有 `step/start`、
         * `step/end`、`job/settled`、`job/registered`、`turn/start`、
         * `request/header` 这些纯结构事件。
         *
         * <p>**已知代价（前向兼容）**：[RecordKind.of] 把不认识的 kind 名字一律
         * 退成 `META_PATCH`，所以「新版插件新增了分类、旧 App 还没升级」时，
         * 那些事件会跟着被丢掉。这是刻意的取舍：真需要它们时得先升级 App 才有
         * 解析能力，留着也只是占位。中继那份归档仍完整保留 14 天，重新同步可补。
         */
        private val DROPPED_KINDS = setOf(RecordKind.META_PATCH, RecordKind.STREAM_DELTA)

        /**
         * 一次性清理 [DROPPED_KINDS] 时每轮删多少行。
         *
         * <p>与 [TimelineDao.pruneChunk] 的 500 行同一口径：分块是为了不长时间顶写锁，
         * 不是为了少删 —— [cleanupDroppedKinds] 会一直删到删不动为止。
         */
        private const val CLEANUP_CHUNK = 500

        /**
         * 「剩下的不足一页正文」时一次最多捞多少条记录。
         *
         * <p>每会话上限 2000 条（与中继一致），所以这个值只是兜底上限、不是性能旋钮：
         * 真到底了就把剩下的全给出去，让用户一次滑到头，别让他连点十几次「载入更早」。
         */
        private const val EARLIER_SCAN_LIMIT = 2000

        /**
         * 尾部窗口至少要带多少段**正文**（不是多少条记录）。
         *
         * <p>与 [ChatSyncActor.EARLIER_PAGE] 同一个数量级：进会话第一屏看到的
         * 正文段数不该比点一次「载入更早」还少。生产库实测尾部 200 条记录里
         * 正文最少的会话只有 0 条（`session-37ab11`，全是元数据与工具），
         * 取 10 段能把这类会话救回来。
         */
        private const val TAIL_BODY_MIN = 10

        @Volatile
        private var instance: TimelineDatabase? = null

        /** 进程级单例：Room 的实例本身是线程安全的，且只该有一个连接池。 */
        fun database(context: Context): TimelineDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, TimelineDatabase::class.java, NAME)
                    // 迁移必须显式给：`fallbackToDestructiveMigration` 只在**版本号变了**
                    // 且没有对应迁移时才兜底，对「schema 改了但没抬版本」这种半吊子
                    // 升级完全无能为力（Room 直接拒绝打开库）。见 MIGRATION_1_2 的注释。
                    .addMigrations(TimelineDatabase.MIGRATION_1_2, TimelineDatabase.MIGRATION_2_3)
                    // 本地时间线是**可重建的缓存**：真正的 14 天权威归档在中继。
                    // 遇到 schema 变更时重建比写迁移便宜得多，也不会把用户卡在
                    // 一个升不上去的旧库上（迁移到 v2 时游标本来就全置 0 重拉）。
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }

        fun store(context: Context): TimelineStore = TimelineStore(database(context))
    }
}
