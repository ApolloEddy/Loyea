package com.loyea.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class TavernCardResourceBindingsTest {
    @Test
    fun readsCommonExternalReferenceShapes() {
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
    fun findsNestedSillyTavernWorldReference() {
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
    fun makesInlineWorldBookAvailableWithoutExternalRegistry() {
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
