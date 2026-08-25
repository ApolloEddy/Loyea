package com.loyea.ui.chat

import com.loyea.plugins.tavern.core.TavernCardCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D2：验证 CharacterCard → PersonaSummary（原生最小集）与 CharacterCard → TavernCardDocument
 * （Tavern 完整字段）两条单向投影路径，以及 split 一次性拆分。
 */
class PersonaSummaryProjectionTest {

    private val card = CharacterCard(
        id = "char_test",
        name = "Test",
        avatarUri = "content://avatar/x",
        avatarColor = "#112233",
        shortIntro = "brief intro",
        systemPrompt = "sys prompt",
        personality = "calm",
        scenario = "a room",
        firstMessage = "hello there",
        chatExamples = "the example",
        isBuiltIn = false,
        creatorName = "creator",
        backgroundUri = "content://bg/y",
        // ---- Tavern 扩展字段（应只进入 TavernCardDocument，不进 PersonaSummary） ----
        description = "full description",
        creatorNotes = "notes",
        postHistoryInstructions = "phi",
        alternateGreetings = listOf("alt greeting"),
        groupOnlyGreetings = listOf("group greeting"),
        tags = listOf("tag-a", "tag-b"),
        characterVersion = "1.2.3",
        nickname = "nick",
        source = listOf("chub"),
        creatorNotesMultilingualJson = """{"zh":"中文备注"}""",
        assetsJson = """[{"name":"a"}]""",
        extensionsJson = """{"vendor":true}""",
        characterBookJson = """{"name":"book","entries":[]}""",
        spec = "chara_card_v3",
        specVersion = "3.0",
        originalCardJson = """{"spec":"chara_card_v3","data":{"name":"Test"}}"""
    )

    @Test
    fun nativeProjectionCarriesOnlyNativeFields() {
        val summary = TavernCharacterCardAdapter.toPersonaSummary(card)

        assertEquals(card.id, summary.id)
        assertEquals(card.name, summary.name)
        assertEquals(card.avatarUri, summary.avatarUri)
        assertEquals(card.avatarColor, summary.avatarColor)
        assertEquals(card.shortIntro, summary.shortIntro)
        assertEquals(card.description, summary.description)
        assertEquals(card.systemPrompt, summary.systemPrompt)
        assertEquals(card.personality, summary.personality)
        assertEquals(card.scenario, summary.scenario)
        assertEquals(card.firstMessage, summary.firstMessage)
        assertEquals(card.chatExamples, summary.mesExample)
        assertEquals(card.isBuiltIn, summary.isBuiltIn)
        assertEquals(card.creatorName, summary.creatorName)
        assertEquals(card.backgroundUri, summary.backgroundUri)
    }

    @Test
    fun nativeProjectionDoesNotLeakTavernExtensionFields() {
        val raw = GsonHolder.gson.toJson(TavernCharacterCardAdapter.toPersonaSummary(card))
        // PersonaSummary 不应携带任何 Tavern 扩展字段的序列化键。
        listOf("postHistoryInstructions", "alternateGreetings", "characterVersion", "nickname",
            "extensionsJson", "characterBook", "spec", "specVersion", "originalCardJson")
            .forEach { key -> assertFalse("PersonaSummary 不应包含 Tavern 字段: $key", raw.contains("\"$key\"")) }
    }

    @Test
    fun splitYieldsNativeSummaryAndTavernDocument() {
        val split = TavernCharacterCardAdapter.split(card)

        assertEquals(TavernCharacterCardAdapter.toPersonaSummary(card), split.summary)
        // Tavern 完整维度：
        assertEquals(card.name, split.document.data.name)
        assertEquals(card.description, split.document.data.description)
        assertEquals(card.creatorNotes, split.document.data.creatorNotes)
        assertEquals(card.alternateGreetings, split.document.data.alternateGreetings)
        assertEquals(card.groupOnlyGreetings, split.document.data.groupOnlyGreetings)
        assertEquals(card.tags, split.document.data.tags)
        assertEquals(card.tags, split.document.data.tags)
        assertEquals(card.nickname, split.document.data.nickname)
        assertEquals(card.characterVersion, split.document.data.characterVersion)
        assertEquals(card.spec, split.document.spec)
        assertEquals(card.originalCardJson, split.document.rawJson)
    }

    @Test
    fun tavernDocumentRoundTripPreservesExtensionAndUnknownWireFormat() {
        val document = TavernCharacterCardAdapter.toDocument(card)
        val exported = TavernCardCodec.toJson(document, "chara_card_v3")

        assertTrue(exported.contains("creator_notes"))
        assertTrue(exported.contains("alternate_greetings"))
        assertTrue(exported.contains("character_version"))
        assertTrue(exported.contains("vendor"))           // extensions 往返
        assertTrue(exported.contains("creator_notes_multilingual")) // multilingual 往返
        assertTrue(exported.contains("assets"))           // assets 往返

        // 往返后重新解析应保持结构化数据一致（wire 格式不回退）。
        val reparsed = TavernCardCodec.parseJson(exported)
        assertEquals(card.description, reparsed?.data?.description)
        assertEquals(card.tags, reparsed?.data?.tags)
        assertEquals(card.alternateGreetings, reparsed?.data?.alternateGreetings)
    }
}

/** 测试专用 Gson 单例，避免在生产路径混入测试配置。 */
private object GsonHolder {
    val gson: com.google.gson.Gson = com.google.gson.Gson()
}