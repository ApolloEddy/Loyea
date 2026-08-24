package com.loyea.ui.chat

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernCardCodecTest {

    @Test
    fun parsesV1AndKeepsStableIdentity() {
        val json = """
            {
              "name": "Legacy",
              "description": "A legacy card",
              "first_mes": "Hello"
            }
        """.trimIndent()

        val first = TavernCardCodec.parseJson(json)
        val second = TavernCardParser.parseJsonCard(json)

        assertNotNull(first)
        assertEquals("chara_card_v1", first?.spec)
        assertEquals("Legacy", first?.data?.name)
        assertEquals("Hello", second?.firstMessage)
        assertEquals(second?.id, TavernCardParser.parseJsonCard(json)?.id)
    }

    @Test
    fun parsesV3CharacterBookAndAdvancedFields() {
        val json = """
            {
              "spec": "chara_card_v3",
              "spec_version": "3.0",
              "data": {
                "name": "Archivist",
                "description": "full description",
                "creator_notes": "creator note",
                "post_history_instructions": "stay in character",
                "alternate_greetings": ["One", "Two"],
                "group_only_greetings": ["Group"],
                "tags": ["lore", "regex"],
                "nickname": "Archive",
                "source": ["https://example.invalid/card"],
                "creator_notes_multilingual": {"zh-CN": "说明"},
                "assets": [{"type": "icon", "uri": "file://icon.png"}],
                "extensions": {"vendor": {"keep": true}},
                "unknown_data": {"must_survive": 7},
                "character_book": {
                  "name": "Bound Book",
                  "scan_depth": 8,
                  "token_budget": 1200,
                  "recursive_scanning": true,
                  "extensions": {"world": "keep"},
                  "entries": [
                    {
                      "id": 12,
                      "keys": ["Alice", "/A\\\\d+/i"],
                      "secondary_keys": ["archive"],
                      "content": "Alice is an archivist.",
                      "enabled": true,
                      "insertion_order": 210,
                      "use_regex": true,
                      "selective": true,
                      "selectiveLogic": 3,
                      "position": "at_depth",
                      "depth": 2,
                      "role": "system",
                      "group": "alice",
                      "groupOverride": true,
                      "outletName": "lore",
                  "extensions": {
                    "future": "keep",
                    "scan_depth": 5,
                    "group_weight": 77,
                    "match_character_description": true,
                    "position": 4
                  }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val document = TavernCardCodec.parseJson(json)
        assertNotNull(document)
        assertEquals("chara_card_v3", document?.spec)
        assertEquals("Archive", document?.data?.nickname)
        assertEquals(listOf("One", "Two"), document?.data?.alternateGreetings)
        assertEquals(1, document?.data?.characterBook?.entries?.size)

        val entry = document!!.data.characterBook!!.entries.single()
        assertTrue(entry.useRegex)
        assertEquals(3, entry.selectiveLogic)
        assertEquals("at_depth", entry.position)
        assertEquals(2, entry.depth)
        assertEquals("system", entry.role)
        assertEquals("lore", entry.outletName)
        assertEquals(5, entry.scanDepth)
        assertEquals(77, entry.groupWeight)
        assertTrue(entry.matchCharacterDescription == true)
        assertEquals(4, entry.positionIndex)

        val roundTrip = TavernCardCodec.toJson(document)
        assertTrue(roundTrip.contains("unknown_data"))
        assertTrue(roundTrip.contains("must_survive"))
        assertTrue(roundTrip.contains("\"future\":\"keep\""))
        assertTrue(roundTrip.contains("\"use_regex\":true"))
        assertTrue(roundTrip.contains("\"scan_depth\":5"))
    }

    @Test
    fun ccv3WinsOverLegacyCharaAndBadCrcDoesNotPoisonFollowingChunk() {
        val legacy = cardJson("legacy")
        val modern = cardJson("modern", spec = "chara_card_v3")
        val png = pngWithChunks(
            textChunk("ccv3", Base64.getEncoder().encodeToString(modern.toByteArray()), corruptCrc = true),
            textChunk("chara", Base64.getEncoder().encodeToString(legacy.toByteArray()))
        )

        val document = TavernCardCodec.parsePng(ByteArrayInputStream(png))
        assertNotNull(document)
        assertEquals("legacy", document?.data?.name)
    }

    @Test
    fun readsCompressedZtxtAndRejectsInvalidPngSignature() {
        val json = cardJson("compressed")
        val deflater = Deflater().apply { setInput(json.toByteArray()); finish() }
        val compressed = ByteArrayOutputStream()
        val buffer = ByteArray(256)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            compressed.write(buffer, 0, count)
        }
        deflater.end()

        val data = "chara".toByteArray() + byteArrayOf(0) + byteArrayOf(0) + compressed.toByteArray()
        val png = pngWithChunks(rawChunk("zTXt", data))
        assertEquals("compressed", TavernCardCodec.parsePng(ByteArrayInputStream(png))?.data?.name)
        assertEquals(null, TavernCardCodec.parsePng(ByteArrayInputStream("not png".toByteArray())))
    }

    @Test
    fun readsCharxCardJsonWithoutExtractingAssets() {
        val json = cardJson("charx")
        val charx = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("assets/icon.png"))
                zip.write(byteArrayOf(1, 2, 3, 4))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("card.json"))
                zip.write(json.toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        val document = TavernCardCodec.parseCharx(ByteArrayInputStream(charx))
        assertNotNull(document)
        assertEquals("charx", document?.data?.name)
    }

    @Test
    fun charxArchiveReturnsOnlyReferencedSafeAssets() {
        val json = """
            {"spec":"chara_card_v3","spec_version":"3.0","data":{
              "name":"Asset Card","description":"","first_mes":"Hi","mes_example":"",
              "assets":[{"type":"icon","name":"icon.png","uri":"embeded://icon.png"}]
            }}
        """.trimIndent()
        val charx = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("card.json"))
                zip.write(json.toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("icon.png"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("../ignored.txt"))
                zip.write(byteArrayOf(9))
                zip.closeEntry()
            }
        }.toByteArray()

        val archive = TavernCardCodec.parseCharxWithAssets(ByteArrayInputStream(charx))!!
        assertEquals("Asset Card", archive.document.data.name)
        assertEquals(listOf<Byte>(1, 2, 3), archive.assets["icon.png"]?.toList())
        assertTrue("../ignored.txt" !in archive.assets)
    }

    @Test
    fun legacyCardCanBeExportedWithoutDroppingOriginalExtensions() {
        val json = cardJson("round-trip").replace(
            "\"first_mes\":\"hello\"",
            "\"first_mes\":\"hello\",\"vendor_field\":{\"x\":1}"
        )
        val card = TavernCardParser.parseJsonCard(json)
        assertNotNull(card)
        val exported = TavernCardCodec.toJson(TavernCardCodec.fromCharacterCard(card!!), "chara_card_v2")
        assertTrue(exported.contains("vendor_field"))
        assertTrue(exported.contains("round-trip"))
    }

    @Test
    fun boundCharacterBookProjectsIntoRuntimeWorldInfo() {
        val bookJson = """
            {
              "scan_depth": 6,
              "token_budget": 321,
              "recursive_scanning": false,
              "entries": [
                {
                  "id": 4,
                  "keys": ["forest"],
                  "secondary_keys": ["night"],
                  "content": "Forest lore",
                  "use_regex": true,
                  "position": "at_depth",
                  "depth": 3,
                  "role": "assistant",
                  "scanDepth": 2,
                  "groupOverride": true
                }
              ]
            }
        """.trimIndent()
        val book = TavernCardCodec.parseCharacterBook(bookJson)
        assertNotNull(book)
        val runtime = TavernCharacterBookAdapter.toWorldInfoBook(book!!, "card:test")
        val entry = runtime.entries.single()
        assertEquals("card:test:4", entry.id)
        assertTrue(entry.useRegex)
        assertEquals("at_depth", entry.positionType)
        assertEquals(3, entry.injectionDepth)
        assertEquals(2, entry.scanDepthOverride)
        assertEquals(321L, runtime.config.tokenBudget)
        assertTrue(!runtime.config.allowRecursion)
    }

    private fun cardJson(name: String, spec: String = "chara_card_v2"): String =
        """{"spec":"$spec","spec_version":"2.0","data":{"name":"$name","description":"desc","first_mes":"hello","extensions":{"x":1}}}"""

    private fun pngWithChunks(vararg chunks: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        chunks.forEach(output::write)
        output.write(rawChunk("IEND", byteArrayOf()))
    }.toByteArray()

    private fun textChunk(keyword: String, text: String, corruptCrc: Boolean = false): ByteArray =
        rawChunk("tEXt", keyword.toByteArray(StandardCharsets.US_ASCII) + byteArrayOf(0) + text.toByteArray()).let {
            if (!corruptCrc) it else it.copyOf().also { bytes -> bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte() }
        }

    private fun rawChunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(StandardCharsets.US_ASCII)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }.value
        return ByteArrayOutputStream().also { output ->
            output.write(intBytes(data.size))
            output.write(typeBytes)
            output.write(data)
            output.write(intBytes(crc))
        }.toByteArray()
    }

    private fun intBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte()
    )

    private fun intBytes(value: Long): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte()
    )
}
