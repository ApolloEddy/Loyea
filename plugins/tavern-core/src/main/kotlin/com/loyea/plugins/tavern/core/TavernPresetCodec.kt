package com.loyea.plugins.tavern.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.loyea.plugin.api.GenerationPatch

/** SillyTavern/OpenAI preset 中可参与提示词构建的单个 prompt slot。 */
data class TavernPresetPrompt(
    val name: String,
    val identifier: String,
    val content: String,
    val role: String = "system",
    val systemPrompt: Boolean = true,
    val marker: Boolean = false,
    val enabled: Boolean = true,
    val rawJson: String? = null
)

data class TavernPresetPromptOrder(
    val identifier: String,
    val enabled: Boolean = true,
    val order: Int = 0
)

/**
 * SillyTavern preset 的安全、可往返子集。
 *
 * 不认识的字段由 rawJson 保留在角色卡 extensions 中；这里结构化的是会直接影响
 * Loyea 请求的 prompt slots 与常见生成参数，避免把一个 preset 降级成只有温度的配置。
 */
data class TavernPromptPreset(
    val name: String = "",
    val chatCompletionSource: String? = null,
    val model: String? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val maxContext: Int? = null,
    val maxTokens: Int? = null,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
    val repetitionPenalty: Double? = null,
    val stopStrings: List<String> = emptyList(),
    val wiFormat: String? = null,
    val scenarioFormat: String? = null,
    val personalityFormat: String? = null,
    val prompts: List<TavernPresetPrompt> = emptyList(),
    val promptOrder: List<TavernPresetPromptOrder> = emptyList(),
    val regexScripts: List<TavernRegexScript> = emptyList(),
    val rawJson: String? = null
) {
    /** 按 prompt_order 排序并应用启用状态；没有 order 时保留 preset 原始顺序。 */
    fun orderedPrompts(): List<TavernPresetPrompt> {
        if (prompts.isEmpty()) return emptyList()
        if (promptOrder.isEmpty()) return prompts.filter { it.enabled }
        val byId = prompts.associateBy { it.identifier }
        val ordered = promptOrder.mapNotNull { item ->
            byId[item.identifier]?.takeIf { item.enabled && it.enabled }
        }
        val orderedIds = ordered.mapTo(mutableSetOf()) { it.identifier }
        return ordered + prompts.filter { it.enabled && it.identifier !in orderedIds }
    }

    fun auxiliaryPrompts(): List<TavernPresetPrompt> = orderedPrompts().filterNot {
        it.identifier.equals("main", ignoreCase = true) || isPostHistory(it)
    }

    fun mainPrompts(): List<TavernPresetPrompt> = orderedPrompts().filter {
        it.identifier.equals("main", ignoreCase = true)
    }

    fun postHistoryPrompts(): List<TavernPresetPrompt> = orderedPrompts().filter(::isPostHistory)

    fun explicitPostHistoryInstructions(): String = listOfNotNull(
        readStringFromRaw("post_history_instructions"),
        readStringFromRaw("postHistoryInstructions"),
        postHistoryPrompts().joinToString("\n\n") { it.content.trim() }.takeIf { it.isNotBlank() }
    ).filter { it.isNotBlank() }.joinToString("\n\n")

    fun generationOverrides(): GenerationPatch = GenerationPatch(
        modelHint = model,
        temperature = temperature,
        topP = topP,
        topK = topK,
        maxOutputTokens = maxTokens,
        maxContextTokens = maxContext,
        frequencyPenalty = frequencyPenalty,
        presencePenalty = presencePenalty,
        repetitionPenalty = repetitionPenalty,
        stopStrings = stopStrings
    )

    private fun readStringFromRaw(key: String): String? = rawJson?.let { json ->
        runCatching { JsonParser.parseString(json).asJsonObject[key]?.asString }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun isPostHistory(prompt: TavernPresetPrompt): Boolean =
        prompt.identifier.contains("post", ignoreCase = true) ||
            prompt.identifier.contains("history", ignoreCase = true) ||
            prompt.name.contains("post history", ignoreCase = true) ||
            prompt.name.contains("历史消息后", ignoreCase = true)
}

object TavernPresetCodec {
    fun parse(json: String): TavernPromptPreset? = parseObject(json)?.let(::parseObject)

    private fun parseObject(json: String): JsonObject? = runCatching {
        JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
    }.getOrNull()

    private fun parseObject(obj: JsonObject): TavernPromptPreset {
        fun string(vararg keys: String): String? = keys.asSequence()
            .mapNotNull { obj[it] }
            .firstOrNull { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?.takeIf { it.isNotBlank() }

        fun double(vararg keys: String): Double? = keys.asSequence()
            .mapNotNull { obj[it] }
            .firstOrNull { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asDouble

        fun int(vararg keys: String): Int? = keys.asSequence()
            .mapNotNull { obj[it] }
            .firstOrNull { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asInt

        fun strings(vararg keys: String): List<String> {
            val value = keys.asSequence().mapNotNull { obj[it] }.firstOrNull() ?: return emptyList()
            return when {
                value.isJsonArray -> value.asJsonArray.mapNotNull { item ->
                    item.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                }
                value.isJsonPrimitive && value.asJsonPrimitive.isString -> listOf(value.asString)
                else -> emptyList()
            }
        }

        val promptElements = when (val value = obj["prompts"]) {
            null -> emptyList()
            else -> when {
                value.isJsonArray -> value.asJsonArray.toList()
                value.isJsonObject -> value.asJsonObject.entrySet().map { it.value }
                else -> emptyList()
            }
        }
        val prompts = promptElements.mapIndexedNotNull { index, item ->
            item.takeIf { it.isJsonObject }?.asJsonObject?.let { prompt ->
                TavernPresetPrompt(
                    name = prompt.string("name") ?: "Prompt $index",
                    identifier = prompt.string("identifier", "id") ?: "prompt_$index",
                    content = prompt.string("content") ?: "",
                    role = prompt.string("role") ?: "system",
                    systemPrompt = prompt.boolean("system_prompt", "systemPrompt") ?: true,
                    marker = prompt.boolean("marker") ?: false,
                    enabled = prompt.boolean("enabled") ?: true,
                    rawJson = prompt.toString()
                )
            }
        }

        val promptOrder = parsePromptOrder(obj["prompt_order"] ?: obj["promptOrder"])
        val regexScripts = obj["regex_scripts"]?.let(TavernRegexEngine::parseScriptElement)
            ?: obj["regexScripts"]?.let(TavernRegexEngine::parseScriptElement)
            ?: emptyList()

        return TavernPromptPreset(
            name = string("name", "preset_name") ?: "",
            chatCompletionSource = string("chat_completion_source", "chatCompletionSource"),
            model = string("model", "model_name", "modelName"),
            temperature = double("temperature", "temp"),
            topP = double("top_p", "topP"),
            topK = int("top_k", "topK"),
            maxContext = int("max_context", "maxContext", "openai_max_context"),
            maxTokens = int("max_tokens", "maxTokens", "openai_max_tokens", "max_length"),
            frequencyPenalty = double("frequency_penalty", "frequencyPenalty", "freq_pen"),
            presencePenalty = double("presence_penalty", "presencePenalty", "presence_pen"),
            repetitionPenalty = double("repetition_penalty", "repetitionPenalty", "rep_pen"),
            stopStrings = strings("stop", "stop_strings", "stopStrings"),
            wiFormat = string("wi_format", "wiFormat"),
            scenarioFormat = string("scenario_format", "scenarioFormat"),
            personalityFormat = string("personality_format", "personalityFormat"),
            prompts = prompts,
            promptOrder = promptOrder,
            regexScripts = regexScripts,
            rawJson = obj.toString()
        )
    }

    private fun parsePromptOrder(element: com.google.gson.JsonElement?): List<TavernPresetPromptOrder> {
        if (element == null || !element.isJsonArray) return emptyList()
        val array = element.asJsonArray
        val direct = array.mapIndexedNotNull { index, item ->
            item.takeIf { it.isJsonObject }?.asJsonObject?.let { order ->
                val identifier = order.string("identifier", "id") ?: return@let null
                TavernPresetPromptOrder(
                    identifier = identifier,
                    enabled = order.boolean("enabled") ?: true,
                    order = index
                )
            }
        }
        if (direct.isNotEmpty() && direct.any { it.identifier.isNotBlank() }) return direct

        // ST 常见格式是 [{character_id, order:[{identifier, enabled}]}]。
        val grouped = array.asSequence()
            .filter { it.isJsonObject }
            .mapNotNull { it.asJsonObject["order"] }
            .filter { it.isJsonArray }
            .flatMap { it.asJsonArray.asSequence() }
            .mapIndexedNotNull { index, item ->
                item.takeIf { it.isJsonObject }?.asJsonObject?.let { order ->
                    val identifier = order.string("identifier", "id") ?: return@let null
                    TavernPresetPromptOrder(identifier, order.boolean("enabled") ?: true, index)
                }
            }
            .toList()
        return grouped
    }

    private fun JsonObject.string(vararg keys: String): String? = keys.asSequence()
        .mapNotNull { this[it] }
        .firstOrNull { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString

    private fun JsonObject.boolean(vararg keys: String): Boolean? = keys.asSequence()
        .mapNotNull { this[it] }
        .firstOrNull { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
        ?.asBoolean
}

/** ST 的 `{0}` / `{{world_info}}` 等格式占位符统一渲染器。 */
object TavernPresetTemplate {
    fun render(template: String?, value: String, vararg aliases: String): String {
        if (template.isNullOrBlank()) return value
        var result = template
            .replace("{0}", value)
            .replace("{{value}}", value, ignoreCase = true)
        aliases.forEach { alias ->
            result = result.replace("{{$alias}}", value, ignoreCase = true)
        }
        return if (result == template && !template.contains("{")) {
            "$template\n$value"
        } else {
            result
        }
    }
}
