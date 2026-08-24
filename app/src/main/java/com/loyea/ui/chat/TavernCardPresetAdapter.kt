package com.loyea.ui.chat

import com.loyea.plugins.tavern.core.*

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Host-side discovery of a Tavern preset bound inside a persisted Loyea card. */
object TavernCardPresetAdapter {
    private val presetKeys = listOf(
        "preset",
        "prompt_preset",
        "promptPreset",
        "tavern_preset",
        "tavernPreset",
        "api_preset",
        "apiPreset"
    ).map(::normalizeKey).toSet()

    fun presetFrom(card: CharacterCard): TavernPromptPreset? {
        val roots = buildList {
            parseObject(card.extensionsJson)?.let(::add)
            card.originalCardJson?.let(::parseObject)?.let { rawRoot ->
                add(rawRoot)
                rawRoot["data"]?.takeIf { it.isJsonObject }?.asJsonObject?.let(::add)
                rawRoot["data"]?.takeIf { it.isJsonObject }?.asJsonObject?.get("extensions")
                    ?.takeIf { it.isJsonObject }?.asJsonObject?.let(::add)
            }
        }.distinctBy(JsonObject::toString)

        return roots.asSequence()
            .mapNotNull(::findEmbeddedPreset)
            .firstOrNull()
    }

    private fun parseObject(json: String): JsonObject? = runCatching {
        JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
    }.getOrNull()

    /** Third-party cards commonly wrap presets in nested vendor/metadata objects. */
    private fun findEmbeddedPreset(element: JsonElement): TavernPromptPreset? {
        if (element.isJsonObject) {
            element.asJsonObject.entrySet().forEach { (key, value) ->
                if (normalizeKey(key) in presetKeys) {
                    parseCandidate(value)?.let { return it }
                }
                findEmbeddedPreset(value)?.let { return it }
            }
        } else if (element.isJsonArray) {
            element.asJsonArray.forEach { child ->
                findEmbeddedPreset(child)?.let { return it }
            }
        }
        return null
    }

    private fun parseCandidate(candidate: JsonElement): TavernPromptPreset? = when {
        candidate.isJsonObject -> TavernPresetCodec.parse(candidate.toString())
        candidate.isJsonPrimitive && candidate.asJsonPrimitive.isString -> TavernPresetCodec.parse(candidate.asString)
        else -> null
    }

    private fun normalizeKey(value: String): String = value
        .filter(Char::isLetterOrDigit)
        .lowercase()
}
