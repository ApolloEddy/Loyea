package com.loyea.perception.memory

import android.content.Context
import com.loyea.plugin.api.PersonaRef
import com.loyea.plugin.api.PluginIds
import com.loyea.ui.chat.PersonaBindingSnapshot
import com.loyea.plugins.tavern.core.TavernPluginDefinition
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.`when`
import org.mockito.kotlin.mock

class GraphMemoryManagerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context: Context = mock()
    private lateinit var manager: GraphMemoryManager
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        filesDir = tempFolder.newFolder("files")
        `when`(context.filesDir).thenReturn(filesDir)
        manager = GraphMemoryManager(context)
    }

    @Test
    fun `same local persona id stays isolated by owner and session incarnation`() = runBlocking {
        val native = binding(ownerNative = true, incarnation = "native-inc")
        val tavern = binding(ownerNative = false, incarnation = "tavern-inc")

        manager.upsertTriple(native, "user", "likes", "tea")
        manager.upsertTriple(tavern, "user", "likes", "coffee")

        assertEquals(listOf("tea"), manager.getTriplesForSession(native).map { it.`object` })
        assertEquals(listOf("coffee"), manager.getTriplesForSession(tavern).map { it.`object` })
        manager.clearMemoriesForBinding(native)
        assertTrue(manager.getTriplesForSession(native).isEmpty())
        assertEquals(1, manager.getTriplesForSession(tavern).size)
    }

    @Test
    fun `binding revision prevents ABA graph visibility`() = runBlocking {
        val revisionOne = binding(revision = 1L)
        val revisionThree = binding(revision = 3L)
        manager.upsertTriple(revisionOne, "A", "knows", "B")

        assertTrue(manager.getTriplesForSession(revisionThree).isEmpty())
        assertEquals(1, manager.getTriplesForSession(revisionOne).size)
    }

    @Test
    fun `legacy triples migrate only into an exact current binding with backup`() = runBlocking {
        val now = System.currentTimeMillis()
        val legacyJson = """
            [{
              "id":1,
              "characterId":"shared-card",
              "sessionId":"session",
              "subject":"user",
              "predicate":"likes",
              "object":"matcha",
              "creationTime":$now,
              "lastMentionedTime":$now,
              "mentionCount":1,
              "baseWeight":1.0
            }]
        """.trimIndent()
        File(filesDir, "graph_memories.json").writeText(legacyJson)
        val current = binding(ownerNative = false)

        val migrated = manager.getTriplesForSession(current)

        assertEquals(1, migrated.size)
        assertEquals(TavernPluginDefinition.ID.value, migrated.single().personaOwnerId)
        assertEquals(current.sessionIncarnationId, migrated.single().sessionIncarnationId)
        assertEquals(legacyJson, File(filesDir, "graph_memories.pre_persona_binding_v1.json").readText())
        val collidingNative = current.copy(ref = PersonaRef.native("shared-card"))
        assertTrue(manager.getTriplesForSession(collidingNative).isEmpty())
    }

    @Test
    fun `batch upsert is one namespace transaction and strengthens duplicates`() = runBlocking {
        val binding = binding()
        manager.upsertTriples(
            binding,
            listOf(
                MemoryTripleDraft("user", "likes", "tea"),
                MemoryTripleDraft("user", "owns", "mug")
            )
        )
        manager.upsertTriple(binding, "USER", "LIKES", "TEA")

        val triples = manager.getTriplesForSession(binding)
        assertEquals(2, triples.size)
        assertEquals(2, triples.first { it.predicate == "likes" }.mentionCount)
    }

    @Test
    fun `delete by id cannot cross a persona namespace`() = runBlocking {
        val first = binding(ownerNative = true, incarnation = "first")
        val second = binding(ownerNative = false, incarnation = "second")
        manager.upsertTriple(first, "user", "likes", "tea")
        manager.upsertTriple(second, "user", "likes", "coffee")
        val firstId = manager.getTriplesForSession(first).single().id

        assertFalse(manager.deleteTriple(firstId, second))
        assertEquals(1, manager.getTriplesForSession(first).size)
        assertTrue(manager.deleteTriple(firstId, first))
        assertTrue(manager.getTriplesForSession(first).isEmpty())
    }

    private fun binding(
        ownerNative: Boolean = false,
        incarnation: String = "incarnation",
        revision: Long = 1L
    ): PersonaBindingSnapshot = PersonaBindingSnapshot(
        sessionId = "session",
        sessionIncarnationId = incarnation,
        personaBindingRevision = revision,
        ref = if (ownerNative) {
            PersonaRef(PluginIds.NATIVE, "shared-card")
        } else {
            PersonaRef.plugin(TavernPluginDefinition.ID, "shared-card")
        }
    )
}
