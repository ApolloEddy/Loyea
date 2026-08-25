package com.loyea.ui.chat

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.`when`
import org.mockito.kotlin.mock
import java.io.File

/**
 * D2 一次性迁移与备份：验证 ensureBackup 幂等、非破坏、可重试，以及 ChatStorageManager
 * 在加载旧 `character_cards.json` 时落 PersonaSummary 宿主存储并写原始备份。
 */
class PersonaSummarySplitMigrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val gson = Gson()

    // ---- PersonaSummarySplitMigration 纯逻辑 ----

    private fun legacyRaw(): String = gson.toJson(
        listOf(
            CharacterCard(
                id = "char_legacy",
                name = "Legacy",
                shortIntro = "intro",
                systemPrompt = "sys",
                description = "desc",
                extensionsJson = """{"vendor":1}""",
                spec = "chara_card_v2",
                specVersion = "2.0",
                originalCardJson = """{"spec":"chara_card_v2"}"""
            )
        )
    )

    private fun nativeRaw(): String = gson.toJson(
        listOf(
            CharacterCard(
                id = "char_native",
                name = "Native",
                shortIntro = "intro",
                systemPrompt = "sys"
            )
        )
    )

    @Test
    fun missingSourceReturnsNoSourceWithoutCreatingBackup() {
        val source = File(tempFolder.root, "missing.json")
        val backup = File(tempFolder.root, "missing.pre_persona_summary_v1.json")

        assertEquals(
            PersonaSummarySplitMigration.Status.NO_SOURCE,
            PersonaSummarySplitMigration.ensureBackup(source, backup)
        )
        assertFalse(backup.exists())
    }

    @Test
    fun nativeOnlyJsonSkipsBackup() {
        val source = File(tempFolder.root, "cards.json")
        val backup = File(tempFolder.root, "cards.pre_persona_summary_v1.json")
        source.writeText(nativeRaw())

        assertEquals(
            PersonaSummarySplitMigration.Status.NO_LEGACY_TAVERN,
            PersonaSummarySplitMigration.ensureBackup(source, backup)
        )
        assertFalse(backup.exists())
    }

    @Test
    fun legacyJsonWritesBackupAndKeepsSourceUntouched() {
        val source = File(tempFolder.root, "cards.json")
        val backup = File(tempFolder.root, "cards.pre_persona_summary_v1.json")
        source.writeText(legacyRaw())

        assertEquals(
            PersonaSummarySplitMigration.Status.BACKUP_CREATED,
            PersonaSummarySplitMigration.ensureBackup(source, backup)
        )
        assertTrue(backup.isFile)
        // 备份与原数据一致，且源文件保持原样（可为降级/恢复）。
        assertEquals(legacyRaw(), backup.readText())
        assertEquals(legacyRaw(), source.readText())
    }

    @Test
    fun rerunIsIdempotentAndBackupStaysByteStable() {
        val source = File(tempFolder.root, "cards.json")
        val backup = File(tempFolder.root, "cards.pre_persona_summary_v1.json")
        source.writeText(legacyRaw())

        assertEquals(
            PersonaSummarySplitMigration.Status.BACKUP_CREATED,
            PersonaSummarySplitMigration.ensureBackup(source, backup)
        )
        val first = backup.readText()
        assertEquals(
            PersonaSummarySplitMigration.Status.ALREADY_BACKED_UP,
            PersonaSummarySplitMigration.ensureBackup(source, backup)
        )
        assertEquals(first, backup.readText())
        assertEquals(legacyRaw(), backup.readText())
    }

    @Test
    fun detectLegacyTavernMarkersIsConservative() {
        // 只带默认占位（spec/extensionsJson 空对象）不算 legacy；有实质内容才算。
        assertFalse(PersonaSummarySplitMigration.hasLegacyTavernFields("""{"spec":"chara_card_v2"}"""))
        assertFalse(PersonaSummarySplitMigration.hasLegacyTavernFields("""{"extensionsJson":"{}"}"""))
        assertFalse(PersonaSummarySplitMigration.hasLegacyTavernFields("""{"name":"Native","shortIntro":"i"}"""))
        assertTrue(PersonaSummarySplitMigration.hasLegacyTavernFields("""{"extensionsJson":{"vendor":1}}"""))
        assertTrue(PersonaSummarySplitMigration.hasLegacyTavernFields("""{"creatorNotes":"note"}"""))
        assertTrue(PersonaSummarySplitMigration.hasLegacyTavernFields("""{"originalCardJson":"{...}"}"""))
    }

    // ---- ChatStorageManager 集成 ----

    private class Storage(context: Context) {
        val manager: ChatStorageManager = ChatStorageManager(context)
        val cardsFile = File(context.filesDir, "character_cards.json")
        val backupFile = File(context.filesDir, "character_cards.pre_persona_summary_v1.json")
        val personaStoreFile = File(context.filesDir, "character_persona_summaries.json")
    }

    @Test
    fun loadingLegacyCardsWritesBackupAndPersonaSummaryStore() = runBlocking {
        val filesDir = tempFolder.newFolder("files")
        val context: Context = mock()
        `when`(context.getSharedPreferences("loyea_prefs", Context.MODE_PRIVATE)).thenReturn(mock())
        `when`(context.filesDir).thenReturn(filesDir)

        val storage = Storage(context)
        storage.cardsFile.parentFile.mkdirs()
        storage.cardsFile.writeText(legacyRaw())

        val loaded = storage.manager.loadCharacterCards().single()

        assertEquals("char_legacy", loaded.id)
        // 一次性备份已写入，源文件未被动过。
        assertTrue(storage.backupFile.isFile)
        assertEquals(legacyRaw(), storage.backupFile.readText())
        assertEquals(legacyRaw(), storage.cardsFile.readText())
        // 拆出的 PersonaSummary 宿主存储已存在，且只含原生字段。
        assertTrue(storage.personaStoreFile.isFile)
        val summaries = storage.manager.loadPersonaSummaries().single()
        assertEquals("char_legacy", summaries.id)
        assertEquals("desc", summaries.description)
    }

    @Test
    fun personaSummaryStoreCoversNonBuiltInCardsOnly() = runBlocking {
        val filesDir = tempFolder.newFolder("files2")
        val context: Context = mock()
        `when`(context.getSharedPreferences("loyea_prefs", Context.MODE_PRIVATE)).thenReturn(mock())
        `when`(context.filesDir).thenReturn(filesDir)
        val storage = Storage(context)

        // 首次加载会注入内置卡片并落 PersonaSummary 存储（内置卡不进 store）。
        storage.manager.loadCharacterCards()
        val summaries = storage.manager.loadPersonaSummaries()
        assertTrue("内置卡片不应进入 PersonaSummary 存储", summaries.none { it.isBuiltIn })
        assertTrue("默认卡不应进入 PersonaSummary 存储", summaries.none { it.id == "char_loyea_default" })
    }
}