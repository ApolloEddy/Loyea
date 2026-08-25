package com.loyea.plugins.tavern.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TavernCardDocumentStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `write then read round-trips raw json`() {
        val layout = TavernStorageLayout(tempFolder.newFolder("plugin"))
        val store = TavernCardDocumentStore(layout)
        val id = "card/network-import-1"

        assertFalse(store.exists(id))
        assertNull(store.read(id))

        store.write(id, """{"spec":"chara_card_v2"}""")

        assertTrue(store.exists(id))
        assertEquals("""{"spec":"chara_card_v2"}""", store.read(id))
    }

    @Test
    fun `write overwrites existing document atomically`() {
        val layout = TavernStorageLayout(tempFolder.newFolder("plugin"))
        val store = TavernCardDocumentStore(layout)

        store.write("c1", "one")
        store.write("c1", "two")

        assertEquals("two", store.read("c1"))
    }

    @Test
    fun `distinct ids map to distinct document paths`() {
        val layout = TavernStorageLayout(tempFolder.newFolder("plugin"))
        val store = TavernCardDocumentStore(layout)

        store.write("c1", "{}")
        store.write("c2", "{}")

        assertTrue(layout.cardDocumentRelativePath("c1") != layout.cardDocumentRelativePath("c2"))
        assertTrue(layout.resolve(layout.cardDocumentRelativePath("c1")).isFile)
        assertTrue(layout.resolve(layout.cardDocumentRelativePath("c2")).isFile)
    }

    @Test
    fun `blank id is rejected`() {
        val layout = TavernStorageLayout(tempFolder.newFolder("plugin"))
        val store = TavernCardDocumentStore(layout)

        assertThrows(IllegalArgumentException::class.java) { store.write("", "{}") }
        assertThrows(IllegalArgumentException::class.java) { store.read("  ") }
    }

    @Test
    fun `hostile id cannot escape the storage root`() {
        val layout = TavernStorageLayout(tempFolder.newFolder("plugin"))
        val store = TavernCardDocumentStore(layout)

        // Ids are mapped through a SHA-256 digest before hitting the filesystem,
        // so traversal-shaped ids can never produce a path outside cards/.
        store.write("../escape", "{}")
        val target = layout.resolve(layout.cardDocumentRelativePath("../escape"))
        assertTrue(target.isFile)
        assertTrue(layout.cardDocumentRelativePath("../escape").startsWith("cards/"))
    }
}
