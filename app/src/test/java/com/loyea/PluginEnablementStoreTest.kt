package com.loyea

import android.content.SharedPreferences
import com.loyea.plugin.api.PluginId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PluginEnablementStoreTest {
    private val pluginId = PluginId.of("com.loyea.test-plugin")

    @Test
    fun `stored value overrides bundled default`() {
        val preferences = mock<SharedPreferences>()
        whenever(preferences.getBoolean("enabled.${pluginId.value}", true)).thenReturn(false)

        assertFalse(PluginEnablementStore(preferences).isEnabled(pluginId, defaultEnabled = true))
    }

    @Test
    fun `enablement change is committed before returning`() {
        val preferences = mock<SharedPreferences>()
        val editor = mock<SharedPreferences.Editor>()
        whenever(preferences.edit()).thenReturn(editor)
        whenever(editor.putBoolean("enabled.${pluginId.value}", false)).thenReturn(editor)
        whenever(editor.commit()).thenReturn(true)

        PluginEnablementStore(preferences).setEnabled(pluginId, enabled = false)

        verify(editor).commit()
    }

    @Test
    fun `failed persistence does not report success`() {
        val preferences = mock<SharedPreferences>()
        val editor = mock<SharedPreferences.Editor>()
        whenever(preferences.edit()).thenReturn(editor)
        whenever(editor.putBoolean("enabled.${pluginId.value}", true)).thenReturn(editor)
        whenever(editor.commit()).thenReturn(false)

        assertThrows(IllegalStateException::class.java) {
            PluginEnablementStore(preferences).setEnabled(pluginId, enabled = true)
        }
    }
}
