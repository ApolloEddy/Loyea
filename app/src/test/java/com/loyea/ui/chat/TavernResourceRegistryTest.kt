package com.loyea.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernResourceRegistryTest {
    @Test
    fun registryRoundTripKeepsRawResources() {
        val registry = TavernResourceRegistry(
            worldBooks = listOf(
                TavernResourceRegistryCodec.worldBookResource(
                    id = "book-1",
                    name = "Lore",
                    rawJson = """{"entries":{"a":{"key":["castle"],"content":"Castle lore"}}}"""
                )
            ),
            presets = listOf(
                TavernResourceRegistryCodec.presetResource(
                    id = "preset-1",
                    name = "Roleplay",
                    rawJson = """{"name":"Roleplay","temperature":0.7}"""
                )
            ),
            regexCollections = listOf(
                TavernResourceRegistryCodec.regexResource(
                    id = "regex-1",
                    name = "Clean",
                    rawJson = """[{"id":"r","findRegex":"/foo/g","replaceString":"bar","placement":[2]}]"""
                )
            ),
            revision = 7
        )
        val parsed = TavernResourceRegistryCodec.parse(TavernResourceRegistryCodec.toJson(registry))!!
        assertEquals(7, parsed.revision)
        assertEquals(registry.worldBooks.single().rawJson, parsed.worldBooks.single().rawJson)
        assertEquals(registry.presets.single().name, parsed.presets.single().name)
        assertEquals(registry.regexCollections.single().rawJson, parsed.regexCollections.single().rawJson)
    }

    @Test
    fun worldBookCodecMapsStandardEntryFields() {
        val json = """
            {
              "name":"Lore",
              "scan_depth":12,
              "token_budget":512,
              "recursive_scanning":false,
              "entries":{
                "7":{
                  "uid":7,
                  "key":["castle"],
                  "keysecondary":["night"],
                  "content":"Castle at night",
                  "selective":true,
                  "selectiveLogic":3,
                  "constant":false,
                  "position":4,
                  "depth":2,
                  "role":"system",
                  "useRegex":true
                }
              }
            }
        """.trimIndent()
        val book = TavernWorldBookCodec.parse(json)!!
        assertEquals(12, book.config.scanDepth)
        assertEquals(512, book.config.tokenBudget)
        assertTrue(!book.config.allowRecursion)
        val entry = book.entries.single()
        assertEquals(listOf("castle"), entry.keywords)
        assertEquals(listOf("night"), entry.keysecondary)
        assertEquals("at_depth", entry.positionType)
        assertEquals("system", entry.role)
        assertTrue(entry.useRegex)
    }

    @Test
    fun worldBookCodecUsesObjectKeyAndHonorsDisableFlag() {
        val book = TavernWorldBookCodec.parse(
            """
            {
              "entries": {
                "stable-key": {"key":["x"],"content":"disabled","enabled":true,"disable":true},
                "active-key": {"key":["x"],"content":"active"}
              }
            }
            """.trimIndent()
        )!!
        assertEquals(listOf("stable-key", "active-key"), book.entries.map { it.id })
        assertTrue(!book.entries.first().enabled)
        assertTrue(book.entries[1].enabled)
    }

    @Test
    fun worldBookCodecRoundTripsRuntimeConfig() {
        val source = WorldInfoBook(
            entries = listOf(WorldInfoEntry("entry", listOf("k"), "lore")),
            config = WorldInfoConfig(
                scanDepth = 8,
                position = "top",
                insertionOrderMode = WorldInfoInsertionOrder.INSERT_AT_BOTTOM,
                tokenBudget = 321,
                recursionDepthCap = 5,
                allowRecursion = false,
                emitGroupHeaders = true,
                caseSensitive = true,
                matchWholeWords = true,
                useGroupScoring = true,
                budgetCap = 99
            )
        )
        val parsed = TavernWorldBookCodec.parse(TavernWorldBookCodec.export(source))!!
        assertEquals(source.config.scanDepth, parsed.config.scanDepth)
        assertEquals(source.config.position, parsed.config.position)
        assertEquals(source.config.insertionOrderMode, parsed.config.insertionOrderMode)
        assertEquals(source.config.tokenBudget, parsed.config.tokenBudget)
        assertEquals(source.config.recursionDepthCap, parsed.config.recursionDepthCap)
        assertTrue(!parsed.config.allowRecursion)
        assertTrue(parsed.config.emitGroupHeaders)
        assertTrue(parsed.config.caseSensitive)
        assertTrue(parsed.config.matchWholeWords)
        assertTrue(parsed.config.useGroupScoring)
        assertEquals(source.config.budgetCap, parsed.config.budgetCap)
    }

    @Test
    fun cardBindingsReadCommonExternalReferenceShapes() {
        val card = CharacterCard(
            id = "card",
            name = "Card",
            shortIntro = "",
            systemPrompt = "",
            extensionsJson = """{"world_info":"Lore","preset":{"name":"unused"},"boundRegex":["Clean"]}"""
        )
        assertEquals(listOf("Lore"), TavernCardResourceBindings.worldBookNames(card))
        assertEquals(listOf("Clean"), TavernCardResourceBindings.regexCollectionNames(card))
    }

    @Test
    fun cardBindingsFindNestedSillyTavernWorldReference() {
        val card = CharacterCard(
            id = "card",
            name = "Card",
            shortIntro = "",
            systemPrompt = "",
            extensionsJson = """{"vendor":{"metadata":{"world":"Nested Lore"}}}"""
        )
        assertEquals(listOf("Nested Lore"), TavernCardResourceBindings.worldBookNames(card))
    }

    @Test
    fun inlineWorldBookObjectIsAvailableWithoutExternalRegistry() {
        val card = CharacterCard(
            id = "card",
            name = "Card",
            shortIntro = "",
            systemPrompt = "",
            extensionsJson = """
                {"vendor":{"world":{"name":"Inline","entries":{
                  "entry-a":{"key":["tower"],"content":"Tower lore","role":2,
                    "characterFilter":{"names":["Card"],"isExclude":false}}
                }}}}
            """.trimIndent()
        )
        val inline = TavernCardResourceBindings.inlineWorldBooks(card)
        assertEquals(1, inline.size)
        assertEquals("Tower lore", inline.single().second.entries.single().content)
        assertEquals("assistant", inline.single().second.entries.single().role)
        assertEquals(listOf("Card"), inline.single().second.entries.single().characterFilterNames)
    }
}
