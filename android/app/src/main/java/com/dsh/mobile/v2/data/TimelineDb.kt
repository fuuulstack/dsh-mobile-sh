package com.dsh.mobile.v2.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 时间线记录表 —— 主键 `(sessionId, seq)`。
 *
 * 选复合主键而不是自增 rowid，是为了让「写入同一批记录」天然幂等：重投、重试、
 * 双开同步都落到 `INSERT OR IGNORE`，不需要先查后写（那在并发下照样重复）。
 *
 * `payloadJson` 存宿主事件原文，不做二次序列化 —— 投影器与归档字节完全一致，
 * 手机只有一个解析器。
 */
@Entity(
    tableName = "timeline_records",
    primaryKeys = ["session_id", "seq"],
    indices = [
        // 投影器按会话顺读；分页只取可视区附近 50 条。
        Index(value = ["session_id", "seq"]),
        // 首行预览：查该会话最后一条 ASSISTANT_TEXT（DESC 点查）。
        Index(value = ["session_id", "kind", "seq"]),
        // 首行预览 / 翻页边界：只找「真的带正文」的那些（DESC 点查，跳过无正文）。
        Index(value = ["session_id", "kind", "has_body", "seq"]),
        // 每小时小步删过期行。
        Index(value = ["created_at"]),
    ],
)
data class TimelineRecordEntity(
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "seq") val seq: Long,
    @ColumnInfo(name = "prev_seq") val prevSeq: Long,
    @ColumnInfo(name = "turn_id") val turnId: String,
    @ColumnInfo(name = "step_id") val stepId: String?,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "timestamp_ms") val timestampMs: Long,
    @ColumnInfo(name = "client_msg_id") val clientMsgId: String?,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "oversized_stub") val isOversizedStub: Boolean = false,
    @ColumnInfo(name = "blob_id") val blobId: String? = null,
    @ColumnInfo(name = "blob_size") val blobSize: Long = 0,
    @ColumnInfo(name = "truncated_from") val truncatedFrom: Long = 0,
    /**
     * 存根的正文头部（wire 上的 `stub.previewHead`）。
     *
     * <p>存根自己的 `payload_json` 只有 `{"type":"stub/payload","blobId":…}`，没有正文。
     * 列表首行预览必须靠这一列，否则会显示那段 JSON 原文。
     */
    @ColumnInfo(name = "stub_preview_head", defaultValue = "''") val stubPreviewHead: String = "",
    /**
     * 这条记录里**有没有正文**（写入时算好）。
     *
     * <p>为什么要有这一列：`ASSISTANT_TEXT` 这个 kind 名不副实 —— 宿主每次
     * `assistant/message` 都记成它，而一回合里大量只有 `type=reasoning`。
     * 生产库实测 7786 条里 5806 条没有正文（75%）。
     *
     * <p>「最近一条**带正文**的助手消息」是列表首行预览与翻页边界的唯一依据。
     * 没有这一列时只能倒扫逐条 `previewHead()` 试探，靠一个扫描上限兜底；
     * 一旦连续无正文的记录超过上限，预览直接变空白（2026-11-03 模拟器实测：
     * 造 200 条纯思考，`scanAssistantBody` 的 64 条上限当场失效）。
     *
     * <p>有这一列之后读侧是**索引点查**（索引
     * `(session_id, kind, has_body, seq)`），没有上限、没有 N 次查询。
     * 写时只对 `ASSISTANT_TEXT` 解析一次 JSON，其它 kind 直接 false。
     */
    @ColumnInfo(name = "has_body", defaultValue = "0") val hasBody: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    fun toRecord(): TimelineRecord = TimelineRecord(
        sessionId = sessionId,
        seq = seq,
        prevSeq = prevSeq,
        turnId = turnId,
        stepId = stepId,
        kind = RecordKind.of(kind),
        timestampMs = timestampMs,
        clientMsgId = clientMsgId,
        payloadJson = payloadJson,
        isOversizedStub = isOversizedStub,
        blobId = blobId,
        blobSize = blobSize,
        truncatedFrom = truncatedFrom,
        stubPreviewHead = stubPreviewHead,
    )

    companion object {
        fun of(record: TimelineRecord, createdAt: Long = System.currentTimeMillis()): TimelineRecordEntity =
            TimelineRecordEntity(
                sessionId = record.sessionId,
                seq = record.seq,
                prevSeq = record.prevSeq,
                turnId = record.turnId,
                stepId = record.stepId,
                // 后台任务的生命周期事件落盘时换成 JOB_EVENT 这个名字。
                //
                // 不改名就会出事：`TimelineStore.commit` 的过滤和 AppViewModel 启动
                // 时的老数据清理都是**按 kind 名**整批删 `META_PATCH` 的，部分家就会
                // 把任务记录一并清掉。这不是推演 —— 实测注入一行 `job/registered`
                // 后启动 App，5 秒内（GC 第一次跑）那一行就没了，「后台任务」胶囊
                // 随即消失，而 `todo/write`（TASK_LIST）那行活着，所以当时只看到
                // 任务胶囊。见 `TimelineRecord.isJobLifecycle`。
                kind = if (record.isJobLifecycle()) RecordKind.JOB_EVENT.name else record.kind.name,
                timestampMs = record.timestampMs,
                clientMsgId = record.clientMsgId,
                payloadJson = record.payloadJson,
                isOversizedStub = record.isOversizedStub,
                blobId = record.blobId,
                blobSize = record.blobSize,
                truncatedFrom = record.truncatedFrom,
                stubPreviewHead = record.stubPreviewHead,
                hasBody = record.hasBody(),
                createdAt = createdAt,
            )
    }
}

/**
 * 会话元数据 + 三水位。
 *
 * 三个水位各占一列（[serverMetaSeq] / [archiveCoveredSeq] / [localAckedSeq]），
 * 因为它们的推进条件毫无交集；合成一列就等于让任一方把另外两方带过界。
 * 代码侧再用 value class 包一层，把「把元数据水位当同步起点」变成编译错误。
 */
@Entity(tableName = "session_metadata")
data class SessionMetaEntity(
    @PrimaryKey @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "title") val title: String = "",
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0,
    @ColumnInfo(name = "running") val running: Boolean = false,
    @ColumnInfo(name = "server_meta_seq") val serverMetaSeq: Long = 0,
    @ColumnInfo(name = "archive_covered_seq") val archiveCoveredSeq: Long = 0,
    @ColumnInfo(name = "local_acked_seq") val localAckedSeq: Long = 0,
    /** 宿主判定的空会话（只有 workspace、没有对话）。 */
    @ColumnInfo(name = "blank") val blank: Boolean = false,
    /** 首行预览：从本地 `timeline_records` 的 ASSISTANT_TEXT 现算，不额外存。 */
    @ColumnInfo(name = "preview") val preview: String = "",
)

/**
 * 审批 / 提问的持久账本（v2 §三）。
 *
 * `interactionId` 必须是 `"<sessionId>:<seq>"`，**绝不能用时间戳**：宿主对未结算的
 * waterfall 会在每次 `$events` 新连接时重投，时间戳做 id 会让同一张卡每次多堆一份，
 * 用户点过一次之后它又活过来。
 */
@Entity(
    tableName = "interaction_ledger",
    primaryKeys = ["session_id", "interaction_id"],
)
data class InteractionEntity(
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "interaction_id") val interactionId: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    fun toLedgerEntry(): InteractionLedgerEntry = InteractionLedgerEntry(
        sessionId = sessionId,
        interactionId = interactionId,
        kind = kind,
        state = LedgerState.of(state),
        payloadJson = payloadJson,
        updatedAt = updatedAt,
    )
}

/** 账本状态机。重投只对 [PENDING] 生效，其余一律静默忽略。 */
enum class LedgerState {
    /** 宿主问了，还没人回答。重投时应当重新显示。 */
    PENDING,

    /** 点了，RPC 在途。此时重投必须静默 —— 否则用户会看到自己刚点掉的东西又回来。 */
    CLICKED_INFLIGHT,

    /** 已结算。 */
    RESOLVED,

    /** 宿主撤回了这一问。 */
    CANCELLED,
    ;

    companion object {
        fun of(raw: String): LedgerState = entries.firstOrNull { it.name == raw } ?: PENDING
    }
}

data class InteractionLedgerEntry(
    val sessionId: String,
    val interactionId: String,
    val kind: String,
    val state: LedgerState,
    val payloadJson: String,
    val updatedAt: Long,
) {
    /** 这次重投是否应当让卡片重新出现。 */
    fun acceptsReplay(): Boolean = state == LedgerState.PENDING
}

@Dao
interface TimelineDao {
    /**
     * 一批记录 + 水位推进，同一个事务。
     *
     * 顺序是硬的（不变量 4）：**先落盘、后推进水位**。反过来的话进程在两者之间被杀，
     * 水位已经越过一批没落盘的记录，那些 seq 就再也不会被拉回来了。
     */
    /**
     * 一批记录 + 水位，同一个事务。
     *
     * <p>顺序是硬的：**先 insertAll 再 saveMeta**。反过来的话（水位先落盘、记录后
     * 落盘）一旦进程在两者之间死掉，`local_acked_seq` 就指向一批不在库里的记录，
     * 它们再也不会被拉第二次。
     *
     * <p>`keep > 0` 时顺带把该会话滚出窗口的老记录删掉（见 [trim]）。做在同一个
     * 事务里是为了让「推进水位」与「丢最旧」这两件事不可能只发生一半。
     */
    @Transaction
    suspend fun commit(requested: List<TimelineRecordEntity>, watermark: SessionMetaEntity?, keep: Int = 0) {
        insertAll(requested)
        if (watermark != null) saveMeta(watermark)
        val session = watermark?.sessionId ?: requested.firstOrNull()?.sessionId
        if (keep > 0 && !session.isNullOrEmpty()) trim(session, keep)
    }

    /**
     * 只保留最新 [keep] 条，更早的删掉。
     *
     * <p>口径与中继 1:1（v2 §七：每会话 2000 条）。本地多留没用——中继那边没有更早
     * 的；少留则离线时看得比服务端少。留成无上限的话，一个长跑会话能把这台手机的
     * 磁盘吃到报警。
     *
     * <p>用 `seq NOT IN (最新 N 个)` 而不是 `created_at`：`seq` 才是时间线顺序，
     * 而 `created_at` 是**落盘**时间——补历史（翻页读回老记录）时它比 seq 新得多，
     * 按它删会把刚补回来的历史当成「最旧」立刻丢掉。
     */
    @Query(
        "DELETE FROM timeline_records WHERE session_id = :sessionId AND seq NOT IN " +
            "(SELECT seq FROM timeline_records WHERE session_id = :sessionId ORDER BY seq DESC LIMIT :keep)"
    )
    suspend fun trim(sessionId: String, keep: Int): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(records: List<TimelineRecordEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMeta(meta: SessionMetaEntity)

    /**
     * 用真正的正文替换一条存根记录。
     *
     * 只改 payload 与存根标记，不动 `prev_seq`/`turn_id`/`created_at`：那些确定的是
     * 「这条在时间线的哪个位置」，与正文补没补上无关。
     */
    @Query(
        "UPDATE timeline_records SET payload_json = :payload, oversized_stub = 0, blob_id = NULL " +
            "WHERE session_id = :sessionId AND seq = :seq AND oversized_stub = 1"
    )
    suspend fun replaceStub(sessionId: String, seq: Long, payload: String): Int

    /** 还挂着的存根（正文不在本地），按 seq 升序。 */
    @Query(
        "SELECT * FROM timeline_records WHERE session_id = :sessionId AND oversized_stub = 1 " +
            "ORDER BY seq ASC LIMIT :limit"
    )
    suspend fun stubs(sessionId: String, limit: Int): List<TimelineRecordEntity>

    /**
     * 删掉某一条记录（长按气泡「本地删除」）。
     *
     * <p>**只删本地这一行，不动水位**：`session_metadata.local_acked_seq` 是「拉到哪儿」的
     * 记账，把它退回去会让下一次同步把这条又拉回来 —— 用户删了又自己冒出来，比不能删更糟。
     * 代价是这条记录在**这台手机上**永久缺失（中继那边 14 天后才过期），重装才会补回。
     */
    @Query("DELETE FROM timeline_records WHERE session_id = :sessionId AND seq = :seq")
    suspend fun deleteRecord(sessionId: String, seq: Long): Int

    @Query("SELECT * FROM timeline_records WHERE session_id = :sessionId AND seq > :afterSeq ORDER BY seq ASC LIMIT :limit")
    suspend fun since(sessionId: String, afterSeq: Long, limit: Int): List<TimelineRecordEntity>

    @Query("SELECT * FROM timeline_records WHERE session_id = :sessionId ORDER BY seq DESC LIMIT :limit")
    suspend fun tail(sessionId: String, limit: Int): List<TimelineRecordEntity>

    /**
     * 尾部窗口再往前读一页（翻历史）。
     *
     * <p>与 [tail] 分开而不是加 `OFFSET`：`OFFSET` 在「新记录不断写进来」时会让
     * 同一行被读两遍或跳过，而按 `seq < beforeSeq` 取是稳定的——新记录只会在
     * 更大的 seq 上，翻历史只看更小的那一侧。
     */
    @Query(
        "SELECT * FROM timeline_records WHERE session_id = :sessionId AND seq < :beforeSeq " +
            "ORDER BY seq DESC LIMIT :limit"
    )
    suspend fun earlier(sessionId: String, beforeSeq: Long, limit: Int): List<TimelineRecordEntity>

    /**
     * 只取**带正文**的助手记录（`has_body = 1`），按 seq 倒序。
     *
     * <p>上一版没有这一列，调用方得自己逐条 `previewHead()` 试探、还得靠扫描上限
     * 兜底。有 `has_body` 列之后，「跳过无正文」是索引条件
     * （`(session_id, kind, has_body, seq)`），一条 SQL 就够，没有上限。
     *
     * <p>2026-11-03 模拟器实测：造 200 条纯思考后 `scanAssistantBody` 的 64 条
     * 上限被打穿，列表预览整行消失 —— 靠上限兜底不是修复。
     */
    @Query(
        "SELECT * FROM timeline_records WHERE session_id = :sessionId AND kind = 'ASSISTANT_TEXT' " +
            "AND has_body = 1 AND seq < :beforeSeq ORDER BY seq DESC LIMIT :limit"
    )
    suspend fun bodyPage(sessionId: String, beforeSeq: Long, limit: Int): List<TimelineRecordEntity>

    /**
     * 首行预览：该会话**最后一条带正文**的助手消息。
     *
     * <p>与 [latestAssistant] 的区别就是那个 `has_body = 1`。生产库实测 75% 的
     * `ASSISTANT_TEXT` 没有正文，所以「最后一条 ASSISTANT_TEXT」经常什么都没有。
     */
    @Query(
        "SELECT * FROM timeline_records WHERE session_id = :sessionId AND kind = 'ASSISTANT_TEXT' " +
            "AND has_body = 1 ORDER BY seq DESC LIMIT 1"
    )
    suspend fun latestBody(sessionId: String): TimelineRecordEntity?

    /** [sinceSeq] 之后（含）到 [beforeSeq] 之前的所有记录，按 seq 升序。 */
    @Query(
        "SELECT * FROM timeline_records WHERE session_id = :sessionId " +
            "AND seq >= :sinceSeq AND seq < :beforeSeq ORDER BY seq ASC"
    )
    suspend fun between(sessionId: String, sinceSeq: Long, beforeSeq: Long): List<TimelineRecordEntity>

    @Query("SELECT MAX(seq) FROM timeline_records WHERE session_id = :sessionId")
    suspend fun maxSeq(sessionId: String): Long?

    @Query("SELECT * FROM session_metadata WHERE session_id = :sessionId")
    suspend fun meta(sessionId: String): SessionMetaEntity?

    @Query("SELECT * FROM session_metadata ORDER BY updated_at DESC")
    suspend fun allMeta(): List<SessionMetaEntity>

    /** 每小时小步删：一次删光会在 WAL 上顶着写锁很久，中继只有 2 核。 */
    @Query("DELETE FROM timeline_records WHERE rowid IN (SELECT rowid FROM timeline_records WHERE created_at < :before LIMIT 500)")
    suspend fun pruneChunk(before: Long): Int

    /**
     * 按 kind 小步删（清理老版本写进来的、投影器根本不读的记录）。
     *
     * <p>与 [pruneChunk] 同样分块：SQLite 默认没编 `SQLITE_ENABLE_UPDATE_DELETE_LIMIT`，
     * `DELETE … LIMIT` 不可用，所以走 `rowid IN (SELECT … LIMIT n)`。
     */
    @Query("DELETE FROM timeline_records WHERE rowid IN (SELECT rowid FROM timeline_records WHERE kind IN (:kinds) LIMIT :limit)")
    suspend fun deleteByKindsChunk(kinds: List<String>, limit: Int): Int

    @Query("SELECT COUNT(*) FROM timeline_records WHERE session_id = :sessionId")
    suspend fun count(sessionId: String): Int
}

@Dao
interface InteractionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: InteractionEntity)

    @Query("SELECT * FROM interaction_ledger WHERE session_id = :sessionId AND state = 'PENDING' ORDER BY updated_at ASC")
    suspend fun pending(sessionId: String): List<InteractionEntity>

    @Query("SELECT * FROM interaction_ledger WHERE session_id = :sessionId AND interaction_id = :interactionId")
    suspend fun get(sessionId: String, interactionId: String): InteractionEntity?
}

@Database(
    entities = [TimelineRecordEntity::class, SessionMetaEntity::class, InteractionEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class TimelineDatabase : RoomDatabase() {
    abstract fun timeline(): TimelineDao
    abstract fun interactions(): InteractionDao

    companion object {
        /**
         * v1 → v2：`timeline_records` 补 `stub_preview_head`。
         *
         * **没有这条迁移，所有老用户的本地时间线都是死的。** 加列那次
         * （早期改动：存根预览头）忘了抬 `version`，Room 打开库时比对
         * `room_master_table` 里的 identityHash 发现不符，直接抛
         * `IllegalStateException: Room cannot verify the data integrity`。
         * 之后每一次 `commit` 都被 `runCatching` 吞掉返回 0：
         * 记录一条不落盘、`local_acked_seq` 一步不前进 ⇒ 每轮同步都是
         * `from=0` ⇒ 中继判「同一个 afterSeq 反复问」返回 429 ⇒ 手机侧
         * 只看到 `sync-loop`，会话内容与列表预览从此永久停在旧值。
         *
         * 列定义必须与 [TimelineRecordEntity.stubPreviewHead] 完全一致
         * （`TEXT NOT NULL DEFAULT ''`），否则 Room 校验照样不过。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE timeline_records ADD COLUMN stub_preview_head TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v2 → v3：`timeline_records` 补 `has_body`（这条记录有没有正文）。
         *
         * <p>加列本身是 `ALTER TABLE ... DEFAULT 0`，但**必须同时回填老数据**：
         * 不回填的话升级后所有历史会话 `has_body` 全是 0，`latestBody` 查不到任何
         * 东西，列表预览集体变空白 —— 比修复前更糟。
         *
         * <p>回填判据与 `TimelineRecord.hasBody()` 保持一致：老行没有 `has_body`，
         * 只能按 payload 里有没有 `"type":"text"` 判。**这里允许用字面量近似**
         * （一次性迁移，跑完就再也不看这个判据），而 `hasBody()` 用的是真解析 ——
         * 两边对生产库 7786 条比对过，零漏零误
         * （`bodyReal=1980 / bodyFast=1980 / misses=0 / falsePositives=0`）。
         * 存根记录（`oversized_stub = 1`）另按 `stub_preview_head` 非空判。
         *
         * <p>回填走一条 `UPDATE`，SQLite 单条语句内部就是一个事务；实测老库
         * 2000 条/会话的规模在百毫秒级，不必分块。**列定义必须与
         * [TimelineRecordEntity.hasBody] 一致**（`INTEGER NOT NULL DEFAULT 0`），
         * 否则 Room 的 identityHash 校验照样不过 —— 那是 `MIGRATION_1_2` KDoc 里
         * 记录过的事故（改实体没抬 version，落盘全线失效）。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE timeline_records ADD COLUMN has_body INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE timeline_records SET has_body = 1 WHERE " +
                        "(kind = 'ASSISTANT_TEXT' AND (" +
                        "payload_json LIKE '%\"type\":\"text\"%' OR " +
                        "payload_json LIKE '%\"type\": \"text\"%')) OR " +
                        "(oversized_stub = 1 AND stub_preview_head <> '')",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_timeline_records_session_id_kind_has_body_seq " +
                        "ON timeline_records (session_id, kind, has_body, seq)",
                )
            }
        }

        /**
         * 容量口径（v2 §七）：每会话 2000 条，50 会话约 30~80 MB。
         *
         * 中继同样保留 2000 条，两边口径一致 —— 手机多留也无用（中继没有更早的），
         * 少留则离线时看得比服务端还少。
         */
        const val PER_SESSION_LIMIT = 2000

        /** 可视区附近按需加载的条数。 */
        const val PAGE_SIZE = 50
    }
}
