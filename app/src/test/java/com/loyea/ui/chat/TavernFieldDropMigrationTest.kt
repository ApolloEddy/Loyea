package com.loyea.ui.chat

import com.google.gson.JsonParser
import com.loyea.plugins.tavern.storage.TavernCardDocumentStore
import com.loyea.plugins.tavern.storage.TavernStorageLayout
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * TODO1：wire 格式 v2 一次性迁移的表征测试。
 *
 * 验收点：
 *  - 旧格式（携带 Tavern 扩展字段）→ 备份原文件 + 补齐文档库 + v2 重写 + 幂等标记；
 *  - 已迁移/无扩展字段/无源文件三种旁路不破坏数据；
 *  - 迁移绝不覆盖已有文档（文档库是扩展字段在 v2 下的事实来源）。
 */
class TavernFieldDropMigrationTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun legacyCardsJson(): String = """
        [
          {"id":"c1","name":"Imported","shortIntro":"s","systemPrompt":"p",
           "description":"desc","tags":["a"],"extensionsJson":"{\"k\":1}",
           "spec":"chara_card_v2","specVersion":"2.0","isBuiltIn":false},
          {"id":"builtin1","name":"Native","shortIntro":"n","systemPrompt":"p","isBuiltIn":true}
        ]
    """.trimIndent()

    private fun migrate(
        source: File,
        layout: TavernStorageLayout,
        backupFile: File? = null,
        markerFile: File? = null
    ): TavernFieldDropMigration.Status {
        val backup = backupFile ?: File(source.parentFile, "backup.json")
        val marker = markerFile ?: File(source.parentFile, "marker.marker")
        return TavernFieldDropMigration.ensureWireV2(source, backup, marker, layout)
    }

    @Test
    fun `legacy file migrates to v2 with backup doc fill and idempotent marker`() {
        val dir = tempFolder.newFolder()
        val source = File(dir, "character_cards.json").apply { writeText(legacyCardsJson()) }
        val layout = TavernStorageLayout(File(dir, "tavern"))

        val status = migrate(source, layout)
        assertEquals(TavernFieldDropMigration.Status.MIGRATED, status)

        // 1. 原始文件被完整备份（内容与迁移前一致）。
        val backup = File(dir, "backup.json")
        assertTrue(backup.isFile)
        assertEquals(legacyCardsJson(), backup.readText())

        // 2. 非内置卡在插件文档库中有了完整文档。
        val store = TavernCardDocumentStore(layout)
        assertTrue(store.exists("c1"))
        val docRaw = store.read("c1")!!
        assertTrue(docRaw.contains("desc"))
        // 内置卡不入文档库。
        assertFalse(store.exists("builtin1"))

        // 3. 源文件已重写为 v2：Tavern 扩展字段不再落盘，native 字段保留。
        val root = JsonParser.parseString(source.readText()).asJsonArray
        val card0 = root[0].asJsonObject
        assertFalse(card0.has("description"))
        assertFalse(card0.has("tags"))
        assertTrue(card0.has("name"))

        // 4. 标记写入且重跑幂等。
        assertTrue(File(dir, "marker.marker").isFile)
        assertEquals(
            TavernFieldDropMigration.Status.ALREADY_MIGRATED,
            migrate(source, layout)
        )
        assertEquals(legacyCardsJson(), backup.readText())
    }

    @Test
    fun `native-only file gets marker but is left untouched`() {
        val dir = tempFolder.newFolder()
        val source = File(dir, "character_cards.json").apply {
            writeText("""[{"id":"n1","name":"Native","shortIntro":"n","systemPrompt":"p","isBuiltIn":true}]""")
        }
        val layout = TavernStorageLayout(File(dir, "tavern"))

        val status = migrate(source, layout)

        assertEquals(TavernFieldDropMigration.Status.NO_LEGACY_TAVERN, status)
        assertEquals(source.readText(), source.readText())
        assertTrue(File(dir, "marker.marker").isFile)
    }

    @Test
    fun `missing source returns NO_SOURCE`() {
        val dir = tempFolder.newFolder()
        val layout = TavernStorageLayout(File(dir, "tavern"))
        assertEquals(
            TavernFieldDropMigration.Status.NO_SOURCE,
            migrate(File(dir, "absent.json"), layout)
        )
    }

    @Test
    fun `migration never clobbers an existing plugin document`() {
        val dir = tempFolder.newFolder()
        val source = File(dir, "character_cards.json").apply { writeText(legacyCardsJson()) }
        val layout = TavernStorageLayout(File(dir, "tavern"))
        val store = TavernCardDocumentStore(layout)
        // 插件/宿主已编辑过 c1 文档（内容与旧 wire 不一致）。
        store.write("c1", """{"spec":"chara_card_v3","data":{"name":"Plugin-Edited","description":"edited"}}""")

        migrate(source, layout)

        // 已有文档保持不动，迁移只补缺失文档。
        assertEquals(
            """{"spec":"chara_card_v3","data":{"name":"Plugin-Edited","description":"edited"}}""",
            store.read("c1")
        )
    }
}
