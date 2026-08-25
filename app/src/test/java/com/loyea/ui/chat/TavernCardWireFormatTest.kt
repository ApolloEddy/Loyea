package com.loyea.ui.chat

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TODO1：宿主 character_cards.json wire 格式 v2 的表征测试。
 *
 * 验收点：
 *  - wire gson 序列化时排除全部 Tavern 扩展字段、保留全部 native 字段；
 *  - 排除仅作用于 CharacterCard 自身（PersonaSummary 等类不受影响）；
 *  - 普通 gson 仍能宽容读取旧格式（带扩展字段），保证迁移期反向兼容；
 *  - v2 往返后扩展字段经 [TavernCharacterCardAdapter.overlayTavernFields] 从文档库补回，
 *    宿主编辑过的值优先于文档值。
 */
class TavernCardWireFormatTest {

    private fun importedCard(): CharacterCard = requireNotNull(
        TavernCardParser.parseJsonCard(
            """
            {
              "spec": "chara_card_v3",
              "spec_version": "3.0",
              "data": {
                "name": "Wire",
                "description": "Full tavern description",
                "creator_notes": "notes",
                "tags": ["a", "b"],
                "alternate_greetings": ["alt"],
                "first_mes": "hello",
                "extensions": {"k": "v"},
                "character_book": {"name": "Book", "entries": []}
              }
            }
            """.trimIndent()
        )
    )

    @Test
    fun `wire gson drops every tavern extension field but keeps native fields`() {
        val card = importedCard()
        val json = TavernCardWireFormat.createWireGson().toJson(listOf(card))
        val root = JsonParser.parseString(json).asJsonArray[0].asJsonObject

        TavernCardWireFormat.TAVERN_EXTENSION_FIELD_NAMES.forEach { name ->
            assertFalse("Tavern field '$name' leaked into wire v2", root.has(name))
        }
        listOf("id", "name", "avatarColor", "shortIntro", "systemPrompt", "personality",
            "scenario", "firstMessage", "chatExamples", "isBuiltIn", "creatorName")
            .forEach { name ->
                assertTrue("Native field '$name' must survive wire v2", root.has(name))
            }
    }

    @Test
    fun `exclusion is scoped to CharacterCard only`() {
        // PersonaSummary 的同名/任意字段（含 description）不受 wire 排除影响。
        val summary = PersonaSummary(
            id = "p1",
            name = "Summary",
            avatarUri = null,
            avatarColor = "#E5D3B3",
            shortIntro = "intro",
            description = "native description",
            systemPrompt = "prompt",
            personality = "",
            scenario = "",
            firstMessage = "",
            mesExample = "",
            isBuiltIn = false,
            creatorName = null,
            backgroundUri = null
        )
        val json = TavernCardWireFormat.createWireGson().toJson(summary)
        val root = JsonParser.parseString(json).asJsonObject
        assertTrue("PersonaSummary.description must not be excluded", root.has("description"))
        assertEquals("native description", root["description"].asString)
    }

    @Test
    fun `plain gson still reads legacy tavern fields for migration compatibility`() {
        val legacy = """
            [{"id":"c1","name":"N","shortIntro":"s","systemPrompt":"p",
              "description":"desc","tags":["t"],"extensionsJson":"{\"x\":1}",
              "spec":"chara_card_v3","specVersion":"3.0","originalCardJson":"{}"}]
        """.trimIndent()
        val type = object : TypeToken<List<CharacterCard>>() {}.type
        val cards = Gson().fromJson<List<CharacterCard>>(legacy, type)

        val card = cards!!.single()
        assertEquals("desc", card.description)
        assertEquals(listOf("t"), card.tags)
        assertEquals("chara_card_v3", card.spec)
        assertEquals("{\"x\":1}", card.extensionsJson)
    }

    @Test
    fun `v2 round trip then overlay restores tavern fields from document`() {
        val card = importedCard()
        val document = TavernCharacterCardAdapter.toDocument(card)

        // 真实 v2 往返：wire gson 落盘 → 普通 gson 读回 → 扩展字段缺失（gson 置 null，
        // 真实加载路径里有 self-heal 兜底，这里直接验证 overlay 对 null 的容忍）。
        val wireJson = TavernCardWireFormat.createWireGson().toJson(listOf(card))
        val type = object : TypeToken<List<CharacterCard>>() {}.type
        val stripped = Gson().fromJson<List<CharacterCard>>(wireJson, type)!!.single()
        assertTrue(stripped.description.isNullOrBlank())
        assertTrue(stripped.tags.isNullOrEmpty())

        // 用文档库补齐扩展字段（含 spec 缺失被文档真实值覆盖）。
        val restored = TavernCharacterCardAdapter.overlayTavernFields(stripped, document)
        assertEquals(card.description, restored.description)
        assertEquals(card.tags, restored.tags)
        assertEquals(card.spec, restored.spec)
        assertEquals(card.extensionsJson, restored.extensionsJson)

        // 宿主编辑过的值优先于文档值（基于已补齐的卡片构造，字段均为非空）。
        val edited = restored.copy(name = "Renamed", tags = listOf("edited"))
        val remerged = TavernCharacterCardAdapter.overlayTavernFields(edited, document)
        assertEquals("Renamed", remerged.name)
        assertEquals(listOf("edited"), remerged.tags)
        assertEquals(card.description, remerged.description)
    }
}
