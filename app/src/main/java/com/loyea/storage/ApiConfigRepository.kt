package com.loyea.storage

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.loyea.ui.settings.ApiConfig

/**
 * API 配置仓库：VM 与 Worker 共用的唯一配置入口（Spec §19——Worker 不得直读 vault/prefs）。
 *
 * 职责：配置存取（经加密库）、通道绑定解析、删除前反查与绑定清理。
 * 不负责：Provider 协议判断（Adapter 层）、网络请求（Transport 层）。
 */
class ApiConfigRepository(private val context: Context) {

    private val gson = Gson()
    private val prefs get() = context.getSharedPreferences("loyea_prefs", Context.MODE_PRIVATE)
    private val bindingStore = ChannelBindingStore(prefs)

    // ---------- 配置存取 ----------

    fun loadConfigs(): VaultResult<List<ApiConfig>> {
        return when (val result = ApiConfigVault.loadJson(context)) {
            is VaultResult.Failure -> result
            is VaultResult.Success -> {
                val json = result.value
                if (json.isNullOrBlank()) return VaultResult.Success(emptyList())
                try {
                    val type = object : TypeToken<List<ApiConfig>>() {}.type
                    VaultResult.Success(Gson().fromJson<List<ApiConfig>>(json, type) ?: emptyList())
                } catch (e: Exception) {
                    // 解析失败保留原文（Vault 不被覆盖），向上传 Failure 语义
                    VaultResult.Failure(e)
                }
            }
        }
    }

    fun saveConfigs(list: List<ApiConfig>): VaultResult<Unit> =
        ApiConfigVault.saveJson(context, Gson().toJson(list))

    fun activeConfigId(): String = prefs.getString("active_config_id", "") ?: ""

    fun setActiveConfigId(id: String) {
        prefs.edit().putString("active_config_id", id).apply()
    }

    // ---------- 通道绑定 ----------

    /** 一次性归一化遗留默认值（vision gpt-4o-mini 等）。VM/Worker 首次使用前调用。 */
    fun normalizeLegacyBindings() {
        bindingStore.normalizeLegacyDefaults()
    }

    /**
     * 一次性显式化迁移（Spec §15.1「设置时写入 Binding」的存量回填）：
     * STT 未绑定且存在可用的 MiMo 配置 → 回填显式 STT 绑定（保留既有自动选择行为）；
     * TTS / 生图未绑定 → 回填当前激活配置（保留既有「跟随主配置」行为）。
     * 此后删除配置即清绑定，不再有任何运行时静默兜底。
     */
    fun backfillExplicitBindings() {
        val configs = (loadConfigs() as? VaultResult.Success)?.value ?: return
        val activeId = activeConfigId()

        val stt = bindingStore.load(ChannelId.STT)
        if (stt.configId.isNullOrBlank()) {
            val mimo = configs.firstOrNull {
                it.provider.contains("mimo", ignoreCase = true) && it.isEnabled && it.apiKey.isNotBlank()
            }
            if (mimo != null) {
                bindingStore.save(ChannelId.STT, ChannelBinding(ChannelId.STT, mimo.id, stt.modelOverride))
            }
        }

        val tts = bindingStore.load(ChannelId.TTS)
        if (tts.configId.isNullOrBlank() && configs.any { it.id == activeId }) {
            bindingStore.save(ChannelId.TTS, ChannelBinding(ChannelId.TTS, activeId, tts.modelOverride))
        }

        val image = bindingStore.load(ChannelId.IMAGE_GENERATION)
        if (image.configId.isNullOrBlank() && configs.any { it.id == activeId }) {
            bindingStore.save(ChannelId.IMAGE_GENERATION, ChannelBinding(ChannelId.IMAGE_GENERATION, activeId, image.modelOverride))
        }
    }

    fun binding(channel: ChannelId): ChannelBinding = bindingStore.load(channel)

    fun saveBinding(binding: ChannelBinding) = bindingStore.save(binding.channel, binding)

    fun bindingsReferencing(configId: String): List<ChannelBinding> =
        bindingStore.bindingsReferencing(configId)

    fun clearBindingsFor(configId: String) = bindingStore.clearBindingsFor(configId)

    // ---------- 通道解析 ----------

    fun resolve(channel: ChannelId, configs: List<ApiConfig>, activeConfigId: String): ChannelResolution {
        normalizeLegacyBindings()
        return ChannelBindingResolver.resolve(channel, bindingStore.load(channel), configs, activeConfigId)
    }

    /** 便捷解析：内部加载配置（Worker 等无内存态调用方使用）。 */
    fun resolve(channel: ChannelId): ChannelResolution {
        val configs = (loadConfigs() as? VaultResult.Success)?.value ?: emptyList()
        return resolve(channel, configs, activeConfigId())
    }
}
