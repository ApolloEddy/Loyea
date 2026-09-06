package com.loyea.character.core.migration

import com.loyea.character.core.api.CharacterOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.5 / 0.6.1 → 统一文档迁移的合并规则测试（Spec §9.1 / §9.2 / 验收矩阵 S01-S02 的映射层）。
 */
class LegacyMigratorTest {

    private val legacyCards = """
        [
          {"id":"char_loyea_default","name":"Loyea","avatarColor":"#E5D3B3",
           "shortIntro":"被用户改过的简介","systemPrompt":"用户改过的系统指令",
           "personality":"冷静","scenario":"日常","firstMessage":"你好",
           "chatExamples":"","isBuiltIn":true,"creatorName":"System"},
          {"id":"char_custom_1","name":"自创角色","avatarColor":"#CBE3F5",
           "shortIntro":"我自己的角色","systemPrompt":"扮演一只猫",
           "personality":"粘人","scenario":"家里","firstMessage":"喵",
           "chatExamples":"<START>\nUser: hi\nChar: meow","isBuiltIn":false}
        ]
    """.trimIndent()

    private val summaries = """
        [
          {"id":"char_custom_1","name":"自创角色","avatarColor":"#CBE3F5",
           "shortIntro":"我自己的角色","description":"详细描述",
           "systemPrompt":"扮演一只猫","personality":"粘人","scenario":"家里",
           "firstMessage":"喵","mesExample":"<START>\nUser: hi\nChar: meow","isBuiltIn":false}
        ]
    """.trimIndent()

    // 0.6.1 tavern/cards/<sha256(cardId)>.json 的内容
    private val cardDocumentJson = """
        {"spec":"chara_card_v3","spec_version":"3.0",
         "data":{"name":"自创角色","description":"详细描述",
           "system_prompt":"","personality":"粘人","scenario":"家里",
           "first_mes":"喵","mes_example":"",
           "extensions":{"third_party":{"keep":true}},
           "character_book":{"name":"书","entries":[
             {"keys":["诊所"],"content":"规则","enabled":true,"insertion_order":100,
              "extensions":{"probability":100}}
           ]}}}
    """.trimIndent()

    @Test
    fun `legacy 055 cards migrate with user state preserved`() {
        val result = LegacyMigrator.migrateCharacters(
            LegacyMigrator.CharacterSources(legacyCardsJson = legacyCards)
        )
        assertEquals(2, result.characters.size)
        val loyea = result.characters.first { it.document.profile.id == "char_loyea_default" }
        assertEquals(CharacterOrigin.BUILT_IN_TEMPLATE, loyea.document.profile.origin)
        assertEquals("被用户改过的简介", loyea.document.profile.display.shortIntro)
        assertEquals("用户改过的系统指令", loyea.document.profile.systemPrompt)
        assertTrue(loyea.sources.contains("0.5.5 cards"))
    }

    @Test
    fun `summary and card document merge into one document`() {
        val result = LegacyMigrator.migrateCharacters(
            LegacyMigrator.CharacterSources(
                personaSummariesJson = summaries,
                cardDocumentsById = mapOf("char_custom_1" to cardDocumentJson)
            )
        )
        assertEquals(1, result.characters.size)
        val doc = result.characters.single().document
        assertEquals("自创角色", doc.profile.name)
        assertEquals("详细描述", doc.profile.description)
        assertNotNull("完整文档的世界书必须恢复", doc.embeddedBookJson)
        assertTrue(doc.embeddedBookJson!!.contains("诊所"))
        assertTrue("未知扩展保真", doc.extensionsJson.contains("third_party"))
        assertEquals("chara_card_v3", doc.spec)
    }

    @Test
    fun `conflicting basic fields keep both copies and report`() {
        // summary 的 systemPrompt 与 0.5.5 卡片不同 → 冲突 → 双副本
        val conflictSummary = """
            [{"id":"char_custom_1","name":"自创角色改","shortIntro":"summary 版",
              "description":"详细描述","systemPrompt":"summary 指令","personality":"粘人",
              "scenario":"家里","firstMessage":"喵","mesExample":"","isBuiltIn":false}]
        """.trimIndent()
        val result = LegacyMigrator.migrateCharacters(
            LegacyMigrator.CharacterSources(
                legacyCardsJson = legacyCards,
                personaSummariesJson = conflictSummary,
                cardDocumentsById = mapOf("char_custom_1" to cardDocumentJson)
            )
        )
        assertEquals("主文档(冲突区) + 冲突副本 + 内置 Loyea", 2, result.characters.size)
        assertTrue(result.conflicts.isNotEmpty())
        val mainCopy = result.conflicts.single().keptDocument
        val summaryCopy = result.characters.first { it.document.profile.id == "char_custom_1.summary" }
        assertTrue("冲突双方都要保留世界书", mainCopy.embeddedBookJson != null && summaryCopy.document.embeddedBookJson != null)
    }

    @Test
    fun `identical legacy card and summary produce single document`() {
        // 0.5.5 的 systemPrompt/personality/scenario/firstMessage/mesExample 与 summary 一致
        // → 无冲突（description 只在 summary 有，不参与 0.5.5 比较）
        val summary2 = """
            [{"id":"char_custom_1","name":"自创角色","shortIntro":"我自己的角色",
              "description":"详细描述","systemPrompt":"扮演一只猫","personality":"粘人",
              "scenario":"家里","firstMessage":"喵",
              "mesExample":"<START>\nUser: hi\nChar: meow","isBuiltIn":false}]
        """.trimIndent()
        val result = LegacyMigrator.migrateCharacters(
            LegacyMigrator.CharacterSources(
                legacyCardsJson = legacyCards,
                personaSummariesJson = summary2,
                cardDocumentsById = mapOf("char_custom_1" to cardDocumentJson)
            )
        )
        // legacyCards 夹具含两个角色：Loyea（仅 0.5.5）+ 自创角色（合并成功）
        assertEquals(2, result.characters.size)
        assertTrue(result.conflicts.isEmpty())
        val merged = result.characters.first { it.document.profile.id == "char_custom_1" }
        assertEquals("详细描述", merged.document.profile.description)
        assertNotNull("合并后的文档必须带世界书", merged.document.embeddedBookJson)
    }

    @Test
    fun `card document file name is deterministic sha256`() {
        val name = LegacyMigrator.cardDocumentFileName("char_custom_1")
        assertEquals(name, LegacyMigrator.cardDocumentFileName(" char_custom_1 "))
        assertTrue(name.endsWith(".json"))
        assertEquals(64 + 5, name.length)
    }

    @Test
    fun `document only source derives basics from card`() {
        val result = LegacyMigrator.migrateCharacters(
            LegacyMigrator.CharacterSources(
                cardDocumentsById = mapOf("char_doc_only" to cardDocumentJson)
            )
        )
        assertEquals(1, result.characters.size)
        val doc = result.characters.single().document
        assertEquals(CharacterOrigin.IMPORTED, doc.profile.origin)
        assertEquals("自创角色", doc.profile.name)
        assertTrue(doc.profile.display.shortIntro.isBlank() || doc.profile.display.shortIntro == "详细描述")
    }
}
