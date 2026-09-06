package com.loyea.character.core.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * 角色卡 codec 契约测试（Spec §4 / 验收矩阵 C01-C03）。
 */
class CharacterCardCodecTest {

    // ---------- JSON ----------

    @Test
    fun `v1 flat json parses without data wrapper`() {
        val json = """{"name":"A","description":"desc","personality":"p","scenario":"s",
            "first_mes":"hi","mes_example":"<START>\nUser: x\nChar: y","system_prompt":"sys"}"""
        val card = CharacterCardCodec.parseJson(json)
        assertNotNull(card)
        assertEquals("chara_card_v1", card!!.spec)
        assertEquals("A", card.data.name)
        assertEquals("desc", card.data.description)
        assertEquals("sys", card.data.systemPrompt)
        assertEquals("<START>\nUser: x\nChar: y", card.data.mesExample)
    }

    @Test
    fun `v2 nested data is runtime source and outer fields preserved`() {
        val json = """{"spec":"chara_card_v2","spec_version":"2.0","name":"outer-name",
            "data":{"name":"inner-name","description":"d","system_prompt":"sys","personality":"",
            "scenario":"","first_mes":"","mes_example":"","creator_notes":"note"}}"""
        val card = CharacterCardCodec.parseJson(json)
        assertNotNull(card)
        assertEquals("chara_card_v2", card!!.spec)
        assertEquals("inner-name", card.data.name)
        assertEquals("note", card.data.creatorNotes)
        assertTrue(card.rawJson!!.contains("outer-name"))
    }

    @Test
    fun `v3 spec declared inside chara keyword is recognized`() {
        // Spec C01：chara 块里声明 chara_card_v3，不能只认 ccv3 关键字
        val json = """{"spec":"chara_card_v3","spec_version":"3.0",
            "data":{"name":"V3","description":"d","assets":[{"type":"background","uri":"img/x.png"}]}}"""
        val png = pngWithTextChunk("chara", Base64.getMimeEncoder().encodeToString(json.toByteArray()))
        val card = CharacterCardCodec.parsePng(png.inputStream())
        assertNotNull(card)
        assertEquals("chara_card_v3", card!!.spec)
        assertEquals("3.0", card.specVersion)
        assertTrue(card.data.assetsJson.contains("x.png"))
    }

    @Test
    fun `unknown fields survive roundtrip through rawJson`() {
        val json = """{"spec":"chara_card_v2","spec_version":"2.0",
            "data":{"name":"A","description":"d","extensions":{"third_party":{"keep":1}},
            "future_field":"value"}}"""
        val card = CharacterCardCodec.parseJson(json)!!
        val exported = CharacterCardCodec.toJson(card)
        assertTrue(exported.contains("third_party"))
        assertTrue(exported.contains("future_field"))
    }

    @Test
    fun `editing a field keeps untouched unknown extensions`() {
        val json = """{"spec":"chara_card_v2","spec_version":"2.0",
            "data":{"name":"A","description":"old","extensions":{"x":{"y":2}}}}"""
        val card = CharacterCardCodec.parseJson(json)!!
        val edited = card.copy(data = card.data.copy(name = "B"))
        val exported = CharacterCardCodec.toJson(edited)
        assertTrue(exported.contains("\"name\":\"B\"") || exported.contains("\"name\": \"B\""))
        assertTrue(exported.contains("\"y\":2") || exported.contains("\"y\": 2"))
    }

    @Test
    fun `description is not truncated by shortDescription`() {
        val longDescription = "很长的正文设定".repeat(50)
        val json = """{"spec":"chara_card_v2","data":{"name":"A","description":"$longDescription"}}"""
        val card = CharacterCardCodec.parseJson(json)!!
        assertEquals(longDescription, card.data.description)
    }

    @Test
    fun `stable id is content derived and repeatable`() {
        val json = """{"spec":"chara_card_v2","data":{"name":"A","description":"d"}}"""
        val a = CharacterCardCodec.parseJson(json)!!
        val b = CharacterCardCodec.parseJson(json)!!
        assertEquals(CharacterCardCodec.stableId(a), CharacterCardCodec.stableId(b))
        assertTrue(CharacterCardCodec.stableId(a).startsWith("char_"))
    }

    @Test
    fun `corrupted json returns null instead of crashing`() {
        assertNull(CharacterCardCodec.parseJson("{not json"))
        assertNull(CharacterCardCodec.parseJson("[]"))
        assertNull(CharacterCardCodec.parseJson(""))
    }

    // ---------- PNG ----------

    @Test
    fun `png tEXt chara base64 card parses`() {
        val json = """{"spec":"chara_card_v2","data":{"name":"PNG卡","description":"d"}}"""
        val payload = Base64.getMimeEncoder().encodeToString(json.toByteArray())
        val png = pngWithTextChunk("chara", payload)
        val card = CharacterCardCodec.parsePng(png.inputStream())
        assertNotNull(card)
        assertEquals("PNG卡", card!!.data.name)
    }

    @Test
    fun `png zTXt compressed card parses`() {
        val json = """{"spec":"chara_card_v2","data":{"name":"ZTXt卡","description":"d"}}"""
        val deflater = Deflater()
        deflater.setInput(json.toByteArray(StandardCharsets.UTF_8))
        deflater.finish()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            output.write(buffer, 0, count)
        }
        deflater.end()
        val png = pngWithZTXtChunk("chara", output.toByteArray())
        val card = CharacterCardCodec.parsePng(png.inputStream())
        assertNotNull(card)
        assertEquals("ZTXt卡", card!!.data.name)
    }

    @Test
    fun `png without card data returns null`() {
        val png = pngWithTextChunk("comment", "hello")
        assertNull(CharacterCardCodec.parsePng(png.inputStream()))
    }

    @Test
    fun `png with broken signature returns null`() {
        val png = pngWithTextChunk("chara", "abc")
        png[3] = 0x00
        assertNull(CharacterCardCodec.parsePng(ByteArrayInputStream(png)))
    }

    @Test
    fun `oversized chunk is rejected not loaded`() {
        // 构造一个声明长度超上限的 chunk；解析器必须安全返回 null 而不是分配内存
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val length = 100 * 1024 * 1024 // > MAX_PNG_CHUNK_BYTES
        out.write(byteArrayOf(
            (length ushr 24).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte()
        ))
        out.write("tEXt".toByteArray(StandardCharsets.US_ASCII))
        val result = CharacterCardCodec.parsePng(ByteArrayInputStream(out.toByteArray()))
        assertNull(result)
    }

    // ---------- 测试用 PNG 构造 ----------

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val length = data.size
        out.write(byteArrayOf(
            (length ushr 24).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte()
        ))
        val typeBytes = type.toByteArray(StandardCharsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        val crcValue = crc.value
        out.write(byteArrayOf(
            (crcValue ushr 24).toByte(), (crcValue ushr 16).toByte(), (crcValue ushr 8).toByte(), crcValue.toByte()
        ))
        return out.toByteArray()
    }

    private fun pngWithTextChunk(keyword: String, text: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val data = keyword.toByteArray(StandardCharsets.US_ASCII) + byteArrayOf(0) +
            text.toByteArray(StandardCharsets.UTF_8)
        out.write(chunk("tEXt", data))
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun pngWithZTXtChunk(keyword: String, compressed: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val data = keyword.toByteArray(StandardCharsets.US_ASCII) + byteArrayOf(0, 0) + compressed
        out.write(chunk("zTXt", data))
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }
}
