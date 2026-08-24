package com.loyea.ui.chat

import com.loyea.plugin.api.PersonaRef
import com.loyea.plugin.api.PluginIds
import com.loyea.plugins.tavern.core.TavernCardCodec
import com.loyea.plugins.tavern.core.TavernPluginDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization tests for the host/plugin boundary before physical storage migration.
 *
 * These tests intentionally describe the current compatibility contract rather than a new
 * implementation. The upcoming Tavern storage split must keep these observations stable.
 */
class TavernMigrationBoundaryTest {

    @Test
    fun importedCardProjectionPreservesIdentityAndUnknownDocumentFields() {
        val json = """
            {
              "spec": "chara_card_v3",
              "spec_version": "3.0",
              "data": {
                "name": "Boundary Card",
                "description": "A card used by the migration contract.",
                "first_mes": "Hello from the boundary.",
                "alternate_greetings": ["Alt greeting"],
                "tags": ["migration", "boundary"],
                "extensions": {"vendor_flag": true},
                "future_data": {"must_survive": "yes"},
                "character_book": {
                  "name": "Boundary Book",
                  "entries": [
                    {
                      "id": 9,
                      "keys": ["boundary"],
                      "content": "Boundary lore",
                      "extensions": {"future_entry": 7}
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val sourceDocument = TavernCardCodec.parseJson(json)
        assertNotNull(sourceDocument)

        val card = TavernCardParser.fromDocument(sourceDocument!!)
        val projectedDocument = TavernCharacterCardAdapter.toDocument(card)
        val projection = TavernCharacterCardAdapter.toProjection(
            card,
            PersonaRef.plugin(TavernPluginDefinition.ID, card.id)
        )

        assertEquals(TavernCardCodec.stableId(sourceDocument), card.id)
        assertEquals(card.id, projection.ref.personaId)
        assertEquals(card.name, projection.displayName)
        assertEquals(card.description, projection.summary)
        assertEquals(card.id, TavernCardCodec.stableId(projectedDocument))
        assertEquals(sourceDocument.rawJson, projectedDocument.rawJson)
        assertEquals("Boundary Card", projectedDocument.data.name)
        assertEquals(listOf("Alt greeting"), projectedDocument.data.alternateGreetings)
        assertEquals(1, projectedDocument.data.characterBook?.entries?.size)

        val exported = TavernCardCodec.toJson(projectedDocument, "chara_card_v3")
        assertTrue(exported.contains("future_data"))
        assertTrue(exported.contains("must_survive"))
        assertTrue(exported.contains("future_entry"))
        assertTrue(exported.contains("vendor_flag"))
    }

    @Test
    fun nativeAndTavernPersonaOwnershipRemainDisjointAcrossSameIdLookups() {
        val imported = TavernCardParser.parseJsonCard(
            """{"name":"Imported","description":"","first_mes":"Hi"}"""
        )!!
        val native = CharacterPersonaOwnership.defaultNativeCard()

        assertEquals(PluginIds.NATIVE, CharacterPersonaOwnership.refFor(native).ownerId)
        assertEquals(TavernPluginDefinition.ID, CharacterPersonaOwnership.refFor(imported).ownerId)

        val importedRef = PersonaRef.plugin(TavernPluginDefinition.ID, imported.id)
        assertEquals(imported, CharacterPersonaOwnership.resolveCard(importedRef, listOf(imported)))
        assertNull(CharacterPersonaOwnership.resolveCard(PersonaRef.native(imported.id), listOf(imported)))
    }
}
