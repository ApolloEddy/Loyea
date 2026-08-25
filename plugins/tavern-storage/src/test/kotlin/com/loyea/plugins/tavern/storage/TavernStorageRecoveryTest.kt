package com.loyea.plugins.tavern.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 资源文件接管的恢复与冲突可观测性测试。
 * 覆盖：原子写失败残留临时文件的清理、目标内容 SHA-256 冲突检测、
 * 损坏备份报告、冲突保留命名约定、迁移标记版本不匹配报告，以及高阶 reconcileRegistry。
 */
class TavernStorageRecoveryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `原子写残留的孤儿临时文件会被扫描并清理`() {
        val layout = TavernStorageLayout(tempFolder.newFolder("plugin"))
        layout.ensureDirectories()
        val orphan = layout.resolve("registry/.tavern_resources.json.abc123.tmp")
            .apply { parentFile.mkdirs(); writeText("partial") }

        val orphans = TavernStorageRecovery.scanOrphans(layout)
        assertEquals(listOf(TavernStorageOrphanKind.ORPHAN_TEMP), orphans.map { it.kind })

        val removed = TavernStorageRecovery.removeOrphanTempFiles(layout)
        assertEquals(listOf(orphan.canonicalFile), removed.map { it.canonicalFile })
        assertFalse(orphan.exists())
    }

    @Test
    fun `损坏备份 corrupt 只被报告而不被自动删除`() {
        val layout = TavernStorageLayout(tempFolder.newFolder("plugin"))
        layout.ensureDirectories()
        val corrupt = layout.resolve("registry/tavern_resources.json.corrupt")
            .apply { parentFile.mkdirs(); writeText("broken") }

        val orphans = TavernStorageRecovery.scanOrphans(layout)
        assertEquals(listOf(TavernStorageOrphanKind.CORRUPT_BACKUP), orphans.map { it.kind })

        // 清理只动 .tmp，.corrupt 被保留生生世世。
        TavernStorageRecovery.removeOrphanTempFiles(layout)
        assertTrue(corrupt.exists())
    }

    @Test
    fun `目标内容与期望记录 sha256 不一致时报告内容冲突`() {
        val layout = TavernStorageLayout(tempFolder.newFolder("plugin"))
        val expected = TavernStorageMigrator.writeUtf8(layout, "registry/resource.json", "one")
        assertNull(
            "内容一致不应报冲突",
            TavernStorageRecovery.detectContentConflict(layout, "registry/resource.json", expected)
        )

        layout.resolve("registry/resource.json").writeText("other")
        val conflict = TavernStorageRecovery.detectContentConflict(layout, "registry/resource.json", expected)
        assertNotNull(conflict)
        assertEquals(TavernStorageConflictType.CONTENT_DIFFERS, conflict!!.type)
        assertEquals(expected.sha256, conflict.expectedSha256)
        assertTrue(conflict.onDiskSha256 != expected.sha256)

        // 期望记录缺失时无法判定，不应误报。
        assertNull(
            TavernStorageRecovery.detectContentConflict(layout, "registry/resource.json", null)
        )
    }

    @Test
    fun `冲突保留命名约定生成相邻 conflict 副本且不破坏目标`() {
        val layout = TavernStorageLayout(tempFolder.newFolder("plugin"))
        layout.ensureDirectories()
        val target = layout.resolve("cards/abcdef.json").apply { parentFile.mkdirs(); writeText("foreign") }

        val preserved = TavernStorageRecovery.preserveConflictingContent(layout, "cards/abcdef.json")
        assertNotNull(preserved)
        // 目标内容原样保留。
        assertTrue(target.isFile)
        assertEquals("foreign", target.readText())
        // 冲突副本位于同目录、采用 .conflict 后缀，内容与目标一致。
        assertTrue(preserved!!.preservedRelativePath.startsWith("cards/.abcdef.json."))
        assertTrue(preserved.preservedRelativePath.endsWith(".conflict"))
        assertEquals("foreign", layout.resolve(preserved.preservedRelativePath).readText())

        // 目标不存在时返回 null。
        assertNull(
            TavernStorageRecovery.preserveConflictingContent(layout, "cards/missing.json")
        )
    }

    @Test
    fun `迁移标记版本高于当前实现时报告版本不匹配`() {
        val layout = TavernStorageLayout(tempFolder.newFolder("plugin"))
        layout.ensureDirectories()
        layout.migrationMarkerFile.writeText("schemaVersion=2\n")

        assertEquals(2, TavernStorageRecovery.readMarkerSchemaVersion(layout))
        val mismatch = TavernStorageRecovery.detectMarkerVersionMismatch(layout)
        assertNotNull(mismatch)
        assertEquals(TavernStorageConflictType.MARKER_VERSION_MISMATCH, mismatch!!.type)
        assertEquals(layout.migrationMarkerRelativePath, mismatch.relativePath)
        assertEquals(2, mismatch.markerSchemaVersionOnDisk)

        // 与当前版本一致则不算不匹配。
        layout.migrationMarkerFile.writeText("schemaVersion=${TavernStorageMigrator.SCHEMA_VERSION}\n")
        assertNull(TavernStorageRecovery.detectMarkerVersionMismatch(layout))
    }

    @Test
    fun `reconcileRegistry 扩展多维收敛：清理孤儿、保留冲突、核对有效记录并报缺失`() {
        val layout = TavernStorageLayout(tempFolder.newFolder("plugin"))
        // 有效记录
        val intact = TavernStorageMigrator.writeUtf8(layout, "registry/a.json", "intact")
        // 之后被篡改的记录
        val tampered = TavernStorageMigrator.writeUtf8(layout, "registry/b.json", "original")
        layout.resolve("registry/b.json").writeText("tampered")
        // 缺失的记录（目录存在但文件消失）
        val vanished = TavernStorageMigrator.writeUtf8(layout, "registry/c.json", "gone")
        layout.resolve("registry/c.json").delete()
        // 遗留孤儿临时文件
        layout.resolve("registry/.a.json.deadbeef.tmp")
            .apply { parentFile.mkdirs(); writeText("partial") }

        val result = TavernStorageRecovery.reconcileRegistry(
            layout,
            listOf(intact, tampered, vanished)
        )

        assertEquals(1, result.removedOrphanTempFiles.size)
        // a 完好进入 verified，b 被保留为冲突副本，c 缺失。
        assertEquals(listOf(intact.relativePath), result.verifiedRecords.map { it.relativePath })
        assertEquals(listOf("registry/b.json"), result.preservedConflicts.map { it.originalRelativePath })
        assertEquals(listOf(vanished.relativePath), result.missingRecords.map { it.relativePath })
        // 未写入迁移标记（本测试未触发 migrate），版本不匹配为 null。
        assertNull(result.markerVersionMismatch)

        // 冲突内容确实被保留在 .conflict 副本中，磁盘并未被覆盖。
        val kept = result.preservedConflicts.single()
        assertEquals("tampered", layout.resolve(kept.preservedRelativePath).readText())
        // 孤儿临时文件已被清理。
        assertFalse(layout.resolve("registry/.a.json.deadbeef.tmp").exists())
    }
}