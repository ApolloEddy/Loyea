package com.loyea.ui.chat

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernCardCodecIsolationTest {
    @Test
    fun `codec parses and exports unknown fields without host models`() {
        val raw = """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"Lya","description":"Archivist","first_mes":"Hi","vendor":{"keep":7}}}"""

        val document = requireNotNull(TavernCardCodec.parseJson(raw))
        val exported = TavernCardCodec.toJson(document)

        assertEquals("Lya", document.data.name)
        assertTrue(exported.contains("\"vendor\""))
        assertTrue(exported.contains("\"keep\":7"))
    }

    @Test
    fun `codec rejects invalid container bytes without Android`() {
        assertNull(TavernCardCodec.parsePng(ByteArrayInputStream("not a png".toByteArray())))
        assertNull(TavernCardCodec.parseCharx(ByteArrayInputStream("not a zip".toByteArray())))
    }
}
