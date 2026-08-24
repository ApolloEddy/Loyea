package com.loyea.plugins.tavern.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernResourceCodecTest {
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
        val book = TavernWorldBookCodec.parse(
            """
            {
              "name":"Lore",
              "scan_depth":12,
              "token_budget":512,
              "recursive_scanning":false,
              "entries":{"7":{
                "uid":7,
                "key":["castle"],
                "keysecondary":["night"],
                "content":"Castle at night",
                "selective":true,
                "selectiveLogic":3,
                "position":4,
                "depth":2,
                "role":"system",
                "useRegex":true
              }}
            }
            """.trimIndent()
        )!!

        assertEquals(12, book.config.scanDepth)
        assertEquals(512, book.config.tokenBudget)
        assertTrue(!book.config.allowRecursion)
        assertEquals(listOf("castle"), book.entries.single().keywords)
        assertEquals(listOf("night"), book.entries.single().keysecondary)
        assertEquals("at_depth", book.entries.single().positionType)
        assertEquals("system", book.entries.single().role)
        assertTrue(book.entries.single().useRegex)
    }

    @Test
    fun worldBookCodecUsesObjectKeyAndHonorsDisableFlag() {
        val book = TavernWorldBookCodec.parse(
            """
            {"entries":{
              "stable-key":{"key":["x"],"content":"disabled","enabled":true,"disable":true},
              "active-key":{"key":["x"],"content":"active"}
            }}
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
}
