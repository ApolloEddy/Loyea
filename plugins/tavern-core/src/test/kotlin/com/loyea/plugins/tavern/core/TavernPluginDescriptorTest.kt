package com.loyea.plugins.tavern.core

import com.loyea.plugin.api.PluginCapability
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D3：Tavern 插件描述符的能力自述门禁。
 *
 * USER_INTERFACE 是宿主决定是否暴露 Tavern 管理 UI 的依据——插件停用时宿主应隐藏其
 * UI 入口，重新启用后恢复；能力声明缺失会静默破坏这一热插拔语义。
 */
class TavernPluginDescriptorTest {

    @Test
    fun tavernDescriptorDeclaresUserInterfaceCapability() {
        assertTrue(
            "Tavern plugin must self-describe its UI surface via USER_INTERFACE capability",
            TavernPluginDefinition.DESCRIPTOR.capabilities.contains(PluginCapability.USER_INTERFACE)
        )
    }

    @Test
    fun tavernDescriptorStillDeclaresRuntimeCapabilities() {
        val caps = TavernPluginDefinition.DESCRIPTOR.capabilities
        assertTrue(caps.contains(PluginCapability.PERSONAS))
        assertTrue(caps.contains(PluginCapability.PROMPT_PIPELINE))
        assertTrue(caps.contains(PluginCapability.OUTPUT_PIPELINE))
        assertTrue(caps.contains(PluginCapability.USER_INTERFACE))
    }
}
