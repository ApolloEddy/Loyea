package com.loyea.storage

import android.content.Context
import android.content.SharedPreferences

/**
 * 功能通道 ID（Spec §5.3）。每个通道的 Config/Model 解析只有 Resolver 一个实现位置。
 */
enum class ChannelId {
    CHAT,
    VISION,
    AUDIO_INPUT,
    STT,
    TTS,
    IMAGE_GENERATION,
    MEMORY
}

/**
 * 通道绑定（Spec §5.3）：
 * - configId == null：该通道未独立绑定，是否继承由该 Channel 的固定规则决定；
 * - modelOverride == null：使用 ApiConfig.modelName（defaultModel）；
 * - 禁止用具体模型字符串表达「默认值」。
 */
data class ChannelBinding(
    val channel: ChannelId,
    val configId: String?,
    val modelOverride: String? = null
)

/**
 * 通道解析结果：进入 Transport 前必须不可歧义（Spec §5.5）。
 */
sealed class ChannelResolution {
    /** 解析成功：config/model/adapter 全部就绪。 */
    data class Ready(val resolved: ResolvedChannel) : ChannelResolution()

    /** 该能力未配置（如 STT/TTS/生图无绑定且规则不允许继承）。 */
    data class Unconfigured(val channel: ChannelId, val userMessage: String) : ChannelResolution()

    /** 绑定指向的配置被禁用。 */
    data class ConfigDisabled(val channel: ChannelId, val configName: String) : ChannelResolution()

    /** 绑定的配置不存在（悬空引用未被清理的兜底显式化，不静默换 Provider）。 */
    data class DanglingBinding(val channel: ChannelId, val configId: String) : ChannelResolution()
}

data class ResolvedChannel(
    val channel: ChannelId,
    val config: com.loyea.ui.settings.ApiConfig,
    val model: String,
    val adapter: com.loyea.llm.ProviderAdapter
)

/**
 * ChannelBinding Resolver（Spec §5.4/§5.5/§6）。
 *
 * 各通道继承规则（全仓唯一实现位置）：
 * - CHAT：activeConfig，必须存在可用；
 * - VISION / AUDIO_INPUT：无绑定继承 CHAT（继承时验证能力；显式绑定 = 用户声明，不再字符串猜测）；
 * - STT / TTS / IMAGE_GENERATION：无绑定一律 Unconfigured，绝不偷用 CHAT；
 * - MEMORY：无绑定继承 CHAT（规则只在此处）；
 * - Greeting / 标题等聊天辅助：使用 CHAT。
 */
object ChannelBindingResolver {

    fun resolve(
        channel: ChannelId,
        binding: ChannelBinding,
        configs: List<com.loyea.ui.settings.ApiConfig>,
        activeConfigId: String
    ): ChannelResolution {
        return when (channel) {
            ChannelId.CHAT -> {
                val config = configs.find { it.id == activeConfigId }
                    ?: return ChannelResolution.Unconfigured(
                        ChannelId.CHAT, "未找到可用的聊天 API 配置，请前往设置选择模型连接")
                if (!config.isEnabled) {
                    return ChannelResolution.ConfigDisabled(ChannelId.CHAT, config.name)
                }
                ready(channel, config, binding.modelOverride)
            }

            ChannelId.VISION -> {
                val boundConfigId = binding.configId
                if (!boundConfigId.isNullOrBlank()) {
                    // 显式绑定：配置缺失 = 悬空引用显式暴露（不静默继承），禁用 = 拒绝
                    val bound = configs.find { it.id == boundConfigId }
                        ?: return ChannelResolution.DanglingBinding(ChannelId.VISION, boundConfigId)
                    if (!bound.isEnabled) {
                        return ChannelResolution.ConfigDisabled(ChannelId.VISION, bound.name)
                    }
                    // 显式绑定 = 用户显式声明该配置具备视觉能力，不做 provider/model 字符串猜测
                    return ready(channel, bound, binding.modelOverride)
                }
                // 无绑定：继承 CHAT，但必须有视觉能力佐证（Adapter 声明或模型启发式）
                val config = configs.find { it.id == activeConfigId }
                    ?: return ChannelResolution.Unconfigured(
                        ChannelId.VISION, "未找到可用的聊天配置用于识图，请配置视觉模型或聊天模型")
                if (!config.isEnabled) {
                    return ChannelResolution.ConfigDisabled(ChannelId.VISION, config.name)
                }
                val adapter = com.loyea.llm.ProviderAdapters.forProvider(config.provider)
                val declared = adapter.chatCapabilities(config.modelName).vision
                val heuristic = VisionAudioCapability.supportsVision(config.provider, config.modelName)
                if (declared == com.loyea.llm.CapabilityState.UNSUPPORTED ||
                    (declared == com.loyea.llm.CapabilityState.UNKNOWN && !heuristic)) {
                    return ChannelResolution.Unconfigured(
                        ChannelId.VISION, "当前聊天模型不具备识图能力，图片将以文字占位随消息发送")
                }
                ready(channel, config, binding.modelOverride)
            }

            ChannelId.AUDIO_INPUT -> {
                // 音频直接理解永远继承 CHAT（无独立 UI Binding）；能力不足时上层回落 STT 转写
                val config = configs.find { it.id == activeConfigId }
                    ?: return ChannelResolution.Unconfigured(
                        ChannelId.AUDIO_INPUT, "未找到可用的聊天配置用于音频理解")
                if (!config.isEnabled) {
                    return ChannelResolution.ConfigDisabled(ChannelId.AUDIO_INPUT, config.name)
                }
                val adapter = com.loyea.llm.ProviderAdapters.forProvider(config.provider)
                val declared = adapter.chatCapabilities(config.modelName).audioInput
                val heuristic = VisionAudioCapability.supportsAudioInput(config.provider, config.modelName)
                if (declared == com.loyea.llm.CapabilityState.UNSUPPORTED ||
                    (declared == com.loyea.llm.CapabilityState.UNKNOWN && !heuristic)) {
                    return ChannelResolution.Unconfigured(
                        ChannelId.AUDIO_INPUT, "当前聊天模型不支持音频输入（input_audio）")
                }
                ready(channel, config, null)
            }

            ChannelId.STT, ChannelId.TTS, ChannelId.IMAGE_GENERATION -> {
                // Spec §5.4：找不到绑定 → 明确不可用，禁止拿 active 配置试一下
                val configId = binding.configId
                if (configId.isNullOrBlank()) {
                    return ChannelResolution.Unconfigured(
                        channel,
                        when (channel) {
                            ChannelId.STT -> "语音转写（STT）未配置专用 API，请前往多模态设置选择"
                            ChannelId.TTS -> "语音合成（TTS）未配置专用 API，请前往多模态设置选择"
                            else -> "图像生成未配置专用 API，请前往多模态设置选择"
                        }
                    )
                }
                val config = configs.find { it.id == configId }
                    ?: return ChannelResolution.DanglingBinding(channel, configId)
                if (!config.isEnabled) {
                    return ChannelResolution.ConfigDisabled(channel, config.name)
                }
                ready(channel, config, binding.modelOverride)
            }

            ChannelId.MEMORY -> {
                // Spec §5.4：Memory 允许继承 CHAT，该规则只存在于这一处
                val (config, _) = inheritOrBinding(binding, configs, activeConfigId, inheritIfAbsent = true)
                    ?: return ChannelResolution.Unconfigured(
                        ChannelId.MEMORY, "未找到可用的聊天配置用于记忆整理")
                if (!config.isEnabled) {
                    return ChannelResolution.ConfigDisabled(ChannelId.MEMORY, config.name)
                }
                ready(channel, config, binding.modelOverride)
            }
        }
    }

    private fun ready(
        channel: ChannelId,
        config: com.loyea.ui.settings.ApiConfig,
        modelOverride: String?
    ): ChannelResolution {
        val model = modelOverride?.takeIf { it.isNotBlank() } ?: config.modelName
        return ChannelResolution.Ready(
            ResolvedChannel(
                channel = channel,
                config = config,
                model = model,
                adapter = com.loyea.llm.ProviderAdapters.forProvider(config.provider)
            )
        )
    }

    /** 绑定缺失时按规则继承 CHAT；返回 null 表示无可用配置。 */
    private fun inheritOrBinding(
        binding: ChannelBinding,
        configs: List<com.loyea.ui.settings.ApiConfig>,
        activeConfigId: String,
        inheritIfAbsent: Boolean
    ): Pair<com.loyea.ui.settings.ApiConfig, String?>? {
        val configId = binding.configId
        if (!configId.isNullOrBlank()) {
            val config = configs.find { it.id == configId } ?: return null
            return config to binding.modelOverride
        }
        if (!inheritIfAbsent) return null
        val active = configs.find { it.id == activeConfigId } ?: return null
        return active to binding.modelOverride
    }
}

/**
 * 视觉/音频能力的模型名启发式（自 ChatViewModel 平移，仅用于 UNKNOWN 能力的继承佐证）。
 */
object VisionAudioCapability {

    fun supportsVision(provider: String, model: String): Boolean {
        val p = provider.lowercase()
        val m = model.lowercase()
        return when {
            p.contains("anthropic") || p.contains("google") -> true
            p.contains("openai") ->
                listOf("4o", "4.1", "4.5", "omni", "gpt-4-vision", "gpt-4-turbo").any { m.contains(it) }
            p.contains("alibaba") || p.contains("zhipu") || p.contains("moonshot") ->
                listOf("vl", "vision", "4v", "glm-4v", "kimi", "4.5v", "glm-5.3").any { m.contains(it) }
            p.contains("openrouter") ->
                listOf("vision", "vl", "4o", "4.5", "omni", "gemini", "claude").any { m.contains(it) }
            else -> false
        }
    }

    fun supportsAudioInput(provider: String, model: String): Boolean {
        val p = provider.lowercase()
        val m = model.lowercase()
        return (p.contains("openai") && (m.contains("omni") || m.contains("4o") || m.contains("audio"))) ||
            (p.contains("google") && m.contains("gemini"))
    }
}

/**
 * 通道绑定的持久化：沿用既有散装 prefs 键作为唯一事实来源（零迁移风险），
 * 一次性归一化 VISION 的遗留默认值（gpt-4o-mini → 空 = 跟随配置 defaultModel）。
 * 归一化之后运行时不再做任何「值等于出厂默认 → 视为没改过」的字符串猜测（Spec §14.2）。
 */
class ChannelBindingStore(private val prefs: SharedPreferences) {

    companion object {
        private const val VISION_NORMALIZED = "vision_binding_normalized_v1"
        /** v0.8.x 出厂默认视觉模型名：仅迁移一次性识别，此后无意义 */
        private const val LEGACY_VISION_DEFAULT = "gpt-4o-mini"
    }

    /** 一次性归一化：出厂默认视觉模型名清空（= 跟随 defaultModel），用户显式改过的保留 */
    fun normalizeLegacyDefaults() {
        if (prefs.getBoolean(VISION_NORMALIZED, false)) return
        val current = prefs.getString("vision_model_name", null)
        if (current.equals(LEGACY_VISION_DEFAULT, ignoreCase = true)) {
            prefs.edit().putString("vision_model_name", "").apply()
        }
        prefs.edit().putBoolean(VISION_NORMALIZED, true).apply()
    }

    fun load(channel: ChannelId): ChannelBinding = when (channel) {
        ChannelId.VISION -> ChannelBinding(
            channel = channel,
            configId = prefs.getString("vision_config_id", null)?.takeIf { it.isNotBlank() },
            modelOverride = prefs.getString("vision_model_name", null)?.takeIf { it.isNotBlank() }
        )
        ChannelId.STT -> ChannelBinding(
            channel = channel,
            configId = prefs.getString("stt_config_id", null)?.takeIf { it.isNotBlank() },
            modelOverride = prefs.getString("stt_model_name", null)?.takeIf { it.isNotBlank() }
        )
        ChannelId.TTS -> ChannelBinding(
            channel = channel,
            configId = prefs.getString("tts_config_id", null)?.takeIf { it.isNotBlank() },
            modelOverride = prefs.getString("tts_model_name", null)?.takeIf { it.isNotBlank() }
        )
        ChannelId.IMAGE_GENERATION -> ChannelBinding(
            channel = channel,
            configId = prefs.getString("image_gen_config_id", null)?.takeIf { it.isNotBlank() },
            modelOverride = prefs.getString("image_gen_model", null)?.takeIf { it.isNotBlank() }
        )
        ChannelId.MEMORY -> ChannelBinding(
            channel = channel,
            configId = prefs.getString("memory_api_config_id", null)?.takeIf { it.isNotBlank() },
            modelOverride = null
        )
        else -> ChannelBinding(channel, null, null)
    }

    fun save(channel: ChannelId, binding: ChannelBinding) {
        val (idKey, modelKey) = keys(channel) ?: return
        val editor = prefs.edit()
        editor.putString(idKey, binding.configId ?: "")
        if (modelKey != null) {
            editor.putString(modelKey, binding.modelOverride ?: "")
        }
        editor.apply()
    }

    /** 引用了某 configId 的全部绑定（删除配置前反查，Spec §6.2）。 */
    fun bindingsReferencing(configId: String): List<ChannelBinding> =
        ChannelId.values().mapNotNull { channel ->
            val binding = load(channel)
            if (binding.configId == configId) binding else null
        }

    /** 清除引用某 configId 的绑定（删除配置时同步清理，杜绝悬空 ID，Spec §6.2）。 */
    fun clearBindingsFor(configId: String) {
        ChannelId.values().forEach { channel ->
            val binding = load(channel)
            if (binding.configId == configId) {
                save(channel, ChannelBinding(channel, null, null))
            }
        }
    }

    private fun keys(channel: ChannelId): Pair<String, String?>? = when (channel) {
        ChannelId.VISION -> "vision_config_id" to "vision_model_name"
        ChannelId.STT -> "stt_config_id" to "stt_model_name"
        ChannelId.TTS -> "tts_config_id" to "tts_model_name"
        ChannelId.IMAGE_GENERATION -> "image_gen_config_id" to "image_gen_model"
        ChannelId.MEMORY -> "memory_api_config_id" to null
        else -> null
    }
}
