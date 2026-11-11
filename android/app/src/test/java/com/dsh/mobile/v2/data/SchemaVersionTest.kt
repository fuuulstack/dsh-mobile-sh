package com.dsh.mobile.v2.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 护栏：**schema 改了却没抬 `@Database(version)`** 这种改动，在真机上的表现是
 * 「整个本地时间线静默失效」，而且不崩、不报错给用户。
 *
 * 2026-11-02 线上就是这一跤：早期改动给 `timeline_records` 加了
 * `stub_preview_head`，版本号还是 1。Room 打开库时比对
 * `room_master_table.identityHash` 发现不符，直接抛
 * `IllegalStateException: Room cannot verify the data integrity`。之后：
 *
 * 1. 每次 `commit` 都被 `runCatching` 吞掉、返回 0 —— 一条记录都落不了盘；
 * 2. `local_acked_seq` 一步不前进，每轮同步都是 `from=0`；
 * 3. 中继判「同一个 afterSeq 反复问」返回 429，手机侧只看到 `sync-loop`；
 * 4. 会话内容与列表预览**永久停在旧值**（用户只能看到进程内的缓存）。
 *
 * 从外观上看它像网络问题、像中继问题、像预览逻辑问题，唯独不像数据库版本问题。
 * 所以这里钉两件事：
 *
 * - 磁盘上的 schema 文件与源码里的 `@Database(version = …)` 必须一致；
 * - **已经发布过的版本，它的 identityHash 不许变**。
 *
 * 改 schema 的正确姿势：抬版本号 → 加 `Migration` → 把新版本的哈希补进
 * `src/test/resources/schema-hashes.json`。只想改老版本的 schema 文件过不了这条测试。
 *
 * <p>为什么读源码而不是反射：Room 的 `@Database` 是 `RetentionPolicy.CLASS`，
 * 运行期反射拿不到，而 JVM 单测里又起不了真的 Room（需要 Android runtime）。
 * 源码文本是这里唯一能同时看见「代码声明的版本」和「导出的 schema」的交汇点。
 */
class SchemaVersionTest {

    private val schemaDir = File("schemas/com.dsh.mobile.v2.data.TimelineDatabase")
    private val source = File("src/main/java/com/dsh/mobile/v2/data/TimelineDb.kt")

    /** 导出的 schema：版本号 → identityHash。 */
    private fun schemas(): Map<Int, String> {
        assertTrue("找不到导出的 schema 目录：${schemaDir.absolutePath}", schemaDir.isDirectory)
        val files = schemaDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        assertTrue("schema 目录是空的，先跑一次 :app:assembleDebug 让 Room 导出", files.isNotEmpty())
        return files.associate { f ->
            val text = f.readText()
            val version = Regex("\"version\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?: error("${f.name} 里读不到 version")
            val hash = Regex("\"identityHash\"\\s*:\\s*\"([0-9a-f]+)\"").find(text)?.groupValues?.get(1)
                ?: error("${f.name} 里读不到 identityHash")
            version to hash
        }
    }

    /** 源码里 `@Database(… version = N …)` 声明的版本号。 */
    private fun declaredVersion(): Int {
        assertTrue("找不到源码：${source.absolutePath}", source.isFile)
        val text = source.readText()
        val block = Regex("@Database\\(([\\s\\S]*?)\\)\\s*\\nabstract class TimelineDatabase")
            .find(text)?.groupValues?.get(1)
            ?: error("TimelineDb.kt 里定位不到 @Database 注解块")
        return Regex("version\\s*=\\s*(\\d+)").find(block)?.groupValues?.get(1)?.toInt()
            ?: error("@Database 里读不到 version")
    }

    @Test
    fun `导出的 schema 版本与 Database 注解一致`() {
        val declared = declaredVersion()
        val actual = schemas().keys.max()
        assertEquals(
            "schema 目录里有 $actual.json，但源码写的是 @Database(version = $declared)。" +
                "改了实体就必须抬版本号并加 Migration，否则老用户的库打不开。",
            actual,
            declared,
        )
    }

    @Test
    fun `已发布版本的身份哈希不许变`() {
        val pinned = readPinned()
        val actual = schemas()
        assertEquals(
            "schema 的 identityHash 与已发布记录不一致。\n" +
                "已发布：$pinned\n" +
                "当前导出：$actual\n" +
                "改老版本的 schema 会让已装机库的 identityHash 对不上（Room 抛 " +
                "IllegalStateException，本地时间线全线失效）。要改结构就抬版本号、加新文件。",
            pinned,
            actual,
        )
    }

    @Test
    fun `每个新版本都必须有对应的 Migration`() {
        val text = source.readText()
        val migrated = Regex("Migration\\((\\d+),\\s*(\\d+)\\)").findAll(text)
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            .toSet()
        schemas().keys.sorted().zipWithNext().forEach { (from, to) ->
            assertTrue(
                "schema 从 $from 直接跳到 $to，但 TimelineDb.kt 里没有 Migration($from, $to)。" +
                    "缺了它，只有 `fallbackToDestructiveMigration` 兜底（用户本地历史全丢），" +
                    "而版本号没变的情况下连兜底都不会触发。",
                (from to to) in migrated,
            )
        }
    }

    private fun readPinned(): Map<Int, String> {
        val f = File("src/test/resources/schema-hashes.json")
        assertTrue("缺少已发布哈希表：${f.absolutePath}", f.isFile)
        return Regex("\"(\\d+)\"\\s*:\\s*\"([0-9a-f]+)\"").findAll(f.readText())
            .associate { it.groupValues[1].toInt() to it.groupValues[2] }
    }
}
