package com.loyea.ui.chat

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.loyea.plugin.api.GenerationPatch

/** Applies host-approved, provider-compatible generation settings to request JSON. */
internal object GenerationRequestMapper {
    fun apply(requestJson: JsonObject, provider: String, patch: GenerationPatch?) {
        if (patch == null) return
        patch.temperature?.let { requestJson.addProperty("temperature", it) }
        patch.topP?.let { requestJson.addProperty("top_p", it) }
        patch.maxOutputTokens?.takeIf { it > 0 }?.let { requestJson.addProperty("max_tokens", it) }
        patch.frequencyPenalty?.let { requestJson.addProperty("frequency_penalty", it) }
        patch.presencePenalty?.let { requestJson.addProperty("presence_penalty", it) }
        if (patch.stopStrings.isNotEmpty()) {
            requestJson.add("stop", JsonArray().apply { patch.stopStrings.forEach(::add) })
        }

        // Strict compatible gateways commonly reject these local/open-source extension fields.
        val strictProvider = provider.lowercase() in setOf("openai", "deepseek", "anthropic", "mimo")
        if (!strictProvider) {
            patch.topK?.let { requestJson.addProperty("top_k", it) }
            patch.repetitionPenalty?.let { requestJson.addProperty("repetition_penalty", it) }
        }
    }
}
