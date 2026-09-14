package com.loyea.plugin.reader

import androidx.test.platform.app.InstrumentationRegistry
import com.loyea.storage.ApiConfigRepository
import com.loyea.storage.ChannelId
import com.loyea.storage.ChannelBinding
import com.loyea.ui.settings.ApiConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/** Seeds a MiMo chat channel for reader panel LLM round-trip verification. */
class SeedChatChannelTest {
    @Test
    fun seedMiMoChannel() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        val key = args.getString("mimoKey") ?: return@runBlocking
        val config = ApiConfig(
            id = "mimo_reader_test",
            name = "MiMo Reader",
            provider = "mimo",
            apiUrl = "https://api.xiaomimimo.com/v1",
            apiKey = key,
            modelName = "mimo-v2.5-pro",
            isEnabled = true,
        )
        val repo = ApiConfigRepository(context)
        val existing = (repo.loadConfigs() as? com.loyea.storage.VaultResult.Success)?.value ?: emptyList()
        val updated = existing.filter { it.id != config.id } + config
        val saveResult = repo.saveConfigs(updated)
        assertTrue("saveConfigs failed", saveResult is com.loyea.storage.VaultResult.Success)
        repo.setActiveConfigId(config.id)
        repo.saveBinding(ChannelBinding(
            channel = ChannelId.CHAT,
            configId = config.id,
        ))
        val resolved = repo.resolve(ChannelId.CHAT)
        assertTrue("resolve should be Ready after seeding", resolved is com.loyea.storage.ChannelResolution.Ready)
    }
}
