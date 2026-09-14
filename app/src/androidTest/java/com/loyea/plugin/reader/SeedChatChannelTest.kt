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
        // base64 传参：绕开 adb shell 对特殊字符的二次解析（设备 shell 会吃 $/*/~ 等）
        val key = args.getString("mimoKeyB64")?.let {
            android.util.Base64.decode(it, android.util.Base64.NO_WRAP).toString(Charsets.UTF_8)
        } ?: args.getString("mimoKey")
        if (key.isNullOrEmpty()) return@runBlocking
        android.util.Log.i("SEED", "key length=" + key.length + " tail=" + key.takeLast(2))
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
        // am instrument 结束即杀进程，apply() 的异步写入会丢；这里必须 commit 同步落盘
        val committed = context.getSharedPreferences("loyea_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putString("active_config_id", config.id).commit()
        assertTrue("active_config_id commit failed", committed)
        repo.saveBinding(ChannelBinding(
            channel = ChannelId.CHAT,
            configId = config.id,
        ))
        val resolved = repo.resolve(ChannelId.CHAT)
        assertTrue("resolve should be Ready after seeding", resolved is com.loyea.storage.ChannelResolution.Ready)
    }
}
