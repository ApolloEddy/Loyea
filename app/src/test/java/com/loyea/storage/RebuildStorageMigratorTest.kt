package com.loyea.storage

import com.google.gson.JsonParser
import com.loyea.character.core.migration.LegacyMigrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * rebuild_storage_v1 一次性迁移测试（Spec §9.2 / 验收矩阵 S01-S04 的存储层）。
 */
class RebuildStorageMigratorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun legacy055(filesDir: File) {
        File(filesDir, "character_cards.json").writeText(
            """[{"id":"char_loyea_default","name":"Loyea","shortIntro":"s","systemPrompt":"sys",
                "isBuiltIn":true,"avatarColor":"#E5D3B3"}]"""
        )
        File(filesDir, "sessions_metadata.json").writeText(
            """[{"id":"s1","title":"会话一","lastActiveTime":100,"characterId":"char_loyea_default"}]"""
        )
        val sessions = File(filesDir, "sessions").apply { mkdirs() }
        File(sessions, "session_s1.json").writeText("""[{"id":"m1","content":"hi","sender":"USER"}]""")
        File(sessions, "world_info_s1.json").writeText("""{"entries":[],"config":{}}""")
        File(filesDir, "global_world_info.json").writeText("""[]""")
        File(filesDir, "graph_memories.json").writeText("""[]""")
    }

    private fun legacy061(filesDir: File) {
        // 0.6.1 在 0.5.5 基础上新增：summaries、tavern/cards、persona 会话字段、群聊遗留字段
        File(filesDir, "character_persona_summaries.json").writeText(
            """[{"id":"char_custom_1","name":"自创角色","avatarColor":"#CBE3F5","shortIntro":"简介",
                "description":"完整描述","systemPrompt":"扮演","personality":"温柔","scenario":"家",
                "firstMessage":"嗨","mesExample":"","isBuiltIn":false}]"""
        )
        val cardsDir = File(filesDir, "tavern/cards").apply { mkdirs() }
        val cardJson = """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"自创角色",
            "description":"完整描述","extensions":{"third_party":{"k":1}}}}"""
        File(cardsDir, LegacyMigrator.cardDocumentFileName("char_custom_1")).writeText(cardJson)
        File(filesDir, "sessions_metadata.json").writeText(
            """[{"id":"s1","title":"群聊会话","lastActiveTime":100,"characterId":"char_custom_1",
                "personaOwnerId":"tavern","sessionIncarnationId":"inc-1","personaBindingRevision":3,
                "groupChatJson":"{roster}","authorNote":"备注"}]"""
        )
    }

    @Test
    fun `fresh install produces minimal root with manifest`() {
        val filesDir = tmp.newFolder("files")
        val outcome = RebuildStorageMigrator.ensureMigrated(filesDir)
        assertTrue(outcome.performed)
        assertTrue(File(outcome.root, "manifest.json").exists())
        assertEquals(listOf("fresh"), readSources(outcome.root))
    }

    @Test
    fun `055 data migrates with sessions and world info restored`() {
        val filesDir = tmp.newFolder("files")
        legacy055(filesDir)
        val outcome = RebuildStorageMigrator.ensureMigrated(filesDir)
        assertTrue(outcome.performed)
        val root = outcome.root
        assertEquals(listOf("v0.5.5"), readSources(root))
        assertTrue("会话消息文件复制", File(root, "sessions/session_s1.json").exists())
        assertTrue("会话世界书复制", File(root, "sessions/world_info_s1.json").exists())
        assertTrue("全局世界书复制", File(root, "global_world_info.json").exists())
        assertTrue("图谱记忆复制", File(root, "graph_memories.json").exists())
        val sessionJson = File(root, "sessions_metadata.json").readText()
        assertTrue("旧会话字段保留", sessionJson.contains("会话一"))
        assertTrue("迁移会话获得默认 bindingRevision", sessionJson.contains("bindingRevision"))
        // 内置人格 + 用户状态
        val charactersDir = File(root, "characters")
        assertTrue(charactersDir.listFiles()!!.isNotEmpty())
    }

    @Test
    fun `061 sessions preserve binding revision and unsupported extras`() {
        val filesDir = tmp.newFolder("files")
        legacy061(filesDir)
        val outcome = RebuildStorageMigrator.ensureMigrated(filesDir)
        assertTrue(outcome.performed)
        val sessionJson = File(outcome.root, "sessions_metadata.json").readText()
        assertTrue("personaBindingRevision → bindingRevision", sessionJson.contains(""""bindingRevision":3"""))
        assertTrue("sessionIncarnationId 保留", sessionJson.contains("inc-1"))
        assertTrue("群聊等超范围数据只读保留", sessionJson.contains("groupChatJson"))
        assertTrue("迁移报告提及不支持功能", outcome.notes.any { it.contains("groupChatJson") || it.contains("authorNote") })
        // 角色从 summary + 完整文档联合恢复
        val characterFile = File(outcome.root, "characters/char_custom_1.json")
        assertTrue(characterFile.exists())
        val docJson = characterFile.readText()
        assertTrue("世界书来自完整文档或字段留空", docJson.contains("extensionsJson"))
    }

    @Test
    fun `second migration is idempotent and never overwrites new data`() {
        val filesDir = tmp.newFolder("files")
        legacy055(filesDir)
        RebuildStorageMigrator.ensureMigrated(filesDir)
        // 用户在新根产生新数据
        val root = File(filesDir, RebuildStorageMigrator.ROOT_NAME)
        File(root, "sessions_metadata.json").writeText("""[{"id":"new-session","title":"新会话"}]""")
        // 第二次迁移（例如源文件后来又被外部修改）不得回写
        File(filesDir, "sessions_metadata.json").writeText("""[{"id":"s1","title":"被污染的源"}]""")
        val second = RebuildStorageMigrator.ensureMigrated(filesDir)
        assertFalse(second.performed)
        val current = File(root, "sessions_metadata.json").readText()
        assertTrue("新数据保留", current.contains("new-session"))
        assertFalse("旧副本不得重新导入", current.contains("被污染的源"))
    }

    @Test
    fun `interrupted staging is retried cleanly`() {
        val filesDir = tmp.newFolder("files")
        legacy055(filesDir)
        // 模拟上次迁移崩溃留下的残缺 staging
        val staging = File(filesDir, RebuildStorageMigrator.STAGING_NAME).apply { mkdirs() }
        File(staging, "garbage.txt").writeText("残缺中间状态")
        val outcome = RebuildStorageMigrator.ensureMigrated(filesDir)
        assertTrue(outcome.performed)
        assertTrue("残缺 staging 被清理重建", File(filesDir, RebuildStorageMigrator.STAGING_NAME).exists().not())
        assertTrue(File(outcome.root, "manifest.json").exists())
    }

    @Test
    fun `manifest records source snapshot hashes`() {
        val filesDir = tmp.newFolder("files")
        legacy055(filesDir)
        RebuildStorageMigrator.ensureMigrated(filesDir)
        val manifest = JsonParser.parseString(
            File(File(filesDir, RebuildStorageMigrator.ROOT_NAME), "manifest.json").readText()
        ).asJsonObject
        val sourceFiles = manifest.getAsJsonArray("sourceFiles")
        assertTrue("快照包含源文件", sourceFiles.size() >= 4)
        val paths = sourceFiles.map { it.asJsonObject.get("path").asString }
        assertTrue(paths.contains("character_cards.json"))
        assertTrue(paths.contains("sessions/session_s1.json"))
        assertTrue(sourceFiles.all { it.asJsonObject.get("sha256").asString.length == 64 })
    }

    private fun readSources(root: File): List<String> =
        JsonParser.parseString(File(root, "manifest.json").readText())
            .asJsonObject.get("sources").asString.split(",")
}
