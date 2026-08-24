package com.loyea.plugin.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginContractTest {
    @Test
    fun `plugin id rejects unstable storage values`() {
        assertThrows(IllegalArgumentException::class.java) { PluginId.of("") }
        assertThrows(IllegalArgumentException::class.java) { PluginId.of("Tavern Plugin") }
        assertThrows(IllegalArgumentException::class.java) { PluginId.of("tavern:plugin") }

        assertEquals("com.loyea.tavern", PluginId.of("com.loyea.tavern").value)
    }

    @Test
    fun `persona reference keeps native and plugin ownership explicit`() {
        val native = PersonaRef.native("char_loyea_default")
        val tavern = PersonaRef.plugin(PluginId.of("com.loyea.tavern"), "card-42")

        assertTrue(native.isNative)
        assertFalse(tavern.isNative)
        assertEquals(PluginIds.NATIVE, native.ownerId)
        assertEquals(PluginId.of("com.loyea.tavern"), tavern.ownerId)
        assertEquals("card-42", tavern.personaId)
    }

    @Test
    fun `descriptor compatibility is exact and capabilities are immutable`() {
        val source = mutableSetOf(PluginCapability.PERSONAS, PluginCapability.PROMPT_PIPELINE)
        val descriptor = PluginDescriptor(
            id = PluginId.of("com.loyea.tavern"),
            displayName = "Tavern compatibility",
            apiVersion = LOYEA_PLUGIN_API_VERSION,
            capabilities = source
        )

        source.clear()

        assertTrue(descriptor.isCompatibleWith(LOYEA_PLUGIN_API_VERSION))
        assertFalse(descriptor.isCompatibleWith(LOYEA_PLUGIN_API_VERSION + 1))
        assertEquals(
            setOf(PluginCapability.PERSONAS, PluginCapability.PROMPT_PIPELINE),
            descriptor.capabilities
        )
    }

    @Test
    fun `request lease exposes one immutable runtime generation`() {
        val pluginId = PluginId.of("com.loyea.tavern")
        val first = PluginRuntimeGeneration(pluginId = pluginId, revision = 7L)
        val second = first.next()

        assertEquals(7L, first.revision)
        assertEquals(8L, second.revision)
        assertEquals(pluginId, second.pluginId)
        assertThrows(IllegalArgumentException::class.java) {
            PluginRuntimeGeneration(pluginId = pluginId, revision = -1L)
        }
    }
}
