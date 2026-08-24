package com.loyea.plugins.tavern.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TavernStorageMigrationTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `legacy registry is copied, verified, and marked without deleting source`() {
        val sourceRoot = tempFolder.newFolder("legacy")
        val layout = TavernStorageLayout(tempFolder.newFolder("plugin"))
        val source = File(sourceRoot, "tavern_resources.json").apply { writeText("{\"revision\":7}") }
        val spec = TavernStorageFileSpec("tavern_resources.json", layout.registryRelativePath)

        val first = TavernStorageMigrator.migrate(sourceRoot, layout, listOf(spec), nowEpochMillis = 10L)
        assertEquals(TavernStorageMigrationStatus.COPIED, first.status)
        assertEquals(source.readText(), layout.registryFile.readText())
        assertTrue(layout.migrationMarkerFile.isFile)
        assertTrue(source.isFile)
        assertTrue(TavernStorageMigrator.verify(layout, first.records.single()))

        val second = TavernStorageMigrator.migrate(sourceRoot, layout, listOf(spec), nowEpochMillis = 11L)
        assertEquals(TavernStorageMigrationStatus.ALREADY_CURRENT, second.status)
        assertEquals(10L, TavernStorageMigrator.readMarker(layout.migrationMarkerFile)?.createdAtEpochMillis)
    }

    @Test
    fun `source changes update an untouched target but never overwrite a changed target`() {
        val sourceRoot = tempFolder.newFolder("legacy")
        val layout = TavernStorageLayout(tempFolder.newFolder("plugin"))
        val source = File(sourceRoot, "resource.json").apply { writeText("one") }
        val spec = TavernStorageFileSpec("resource.json", "registry/resource.json")

        TavernStorageMigrator.migrate(sourceRoot, layout, listOf(spec), nowEpochMillis = 1L)
        source.writeText("two")
        val updated = TavernStorageMigrator.migrate(sourceRoot, layout, listOf(spec), nowEpochMillis = 2L)
        assertEquals(TavernStorageMigrationStatus.COPIED, updated.status)
        assertEquals("two", layout.resolve("registry/resource.json").readText())

        layout.resolve("registry/resource.json").writeText("plugin-owned")
        source.writeText("three")
        val conflict = TavernStorageMigrator.migrate(sourceRoot, layout, listOf(spec), nowEpochMillis = 3L)
        assertEquals(TavernStorageMigrationStatus.RECONCILED_EXISTING, conflict.status)
        assertEquals(listOf("registry/resource.json"), conflict.preservedConflicts)
        assertEquals("plugin-owned", layout.resolve("registry/resource.json").readText())
    }

    @Test
    fun `optional sources can appear after an empty migration`() {
        val sourceRoot = tempFolder.newFolder("legacy")
        val layout = TavernStorageLayout(tempFolder.newFolder("plugin"))
        val spec = TavernStorageFileSpec("optional.json", "registry/optional.json", required = false)

        val missing = TavernStorageMigrator.migrate(sourceRoot, layout, listOf(spec), nowEpochMillis = 1L)
        assertEquals(listOf("optional.json"), missing.missingOptionalSources)
        assertFalse(layout.resolve("registry/optional.json").exists())

        File(sourceRoot, "optional.json").writeText("appeared")
        val copied = TavernStorageMigrator.migrate(sourceRoot, layout, listOf(spec), nowEpochMillis = 2L)
        assertEquals(TavernStorageMigrationStatus.COPIED, copied.status)
        assertEquals("appeared", layout.resolve("registry/optional.json").readText())
    }

    @Test
    fun `card document paths and root traversal checks are stable`() {
        val layout = TavernStorageLayout(tempFolder.newFolder("plugin"))
        assertEquals(layout.cardDocumentRelativePath("card/a"), layout.cardDocumentRelativePath("card/a"))
        assertTrue(layout.cardDocumentRelativePath("card/a").startsWith("cards/"))
        assertTrue(layout.cardDocumentRelativePath("card/a").endsWith(".json"))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            layout.resolve("../outside.json")
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            TavernStorageFileSpec("../legacy.json", "registry/legacy.json")
        }
    }
}
