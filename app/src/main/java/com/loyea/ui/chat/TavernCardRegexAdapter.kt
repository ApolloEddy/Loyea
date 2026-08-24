package com.loyea.ui.chat

import com.loyea.plugins.tavern.core.*

import com.google.gson.JsonElement
import com.google.gson.JsonParser

/** Host-side projection from Loyea's persisted card model to Tavern regex inputs. */
object TavernCardRegexAdapter {
    fun scriptsFrom(card: CharacterCard): List<TavernRegexScript> {
        TavernRegexEngine.parseScripts(card.extensionsJson)
            .takeIf { it.isNotEmpty() }
            ?.let { return it }

        val roots = buildList {
            parseObject(card.extensionsJson)?.let(::add)
            parseObject(card.originalCardJson.orEmpty())?.let { rawRoot ->
                add(rawRoot)
                rawRoot.asJsonObject["data"]?.takeIf { it.isJsonObject }?.let(::add)
                rawRoot.asJsonObject["data"]?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("extensions")?.takeIf { it.isJsonObject }?.let(::add)
            }
        }.distinctBy(JsonElement::toString)
        val keys = setOf("regex_scripts", "regexScripts", "regex_collection", "regexCollection", "regex")
            .map(::normalizeKey)
            .toSet()

        return roots.asSequence()
            .mapNotNull { findEmbeddedScripts(it, keys) }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
    }

    fun macroContext(card: CharacterCard, userName: String): TavernMacroContext = TavernMacroContext(
        characterName = card.nickname?.takeIf { it.isNotBlank() } ?: card.name,
        description = card.description.takeIf { it.isNotBlank() } ?: card.shortIntro,
        userName = userName
    )

    private fun parseObject(json: String): JsonElement? = runCatching {
        JsonParser.parseString(json)
    }.getOrNull()?.takeIf(JsonElement::isJsonObject)

    private fun findEmbeddedScripts(element: JsonElement, keys: Set<String>): List<TavernRegexScript>? {
        if (element.isJsonObject) {
            element.asJsonObject.entrySet().forEach { (key, value) ->
                if (normalizeKey(key) in keys) {
                    TavernRegexEngine.parseScripts(value.toString())
                        .takeIf { it.isNotEmpty() }
                        ?.let { return it }
                }
                findEmbeddedScripts(value, keys)?.let { return it }
            }
        } else if (element.isJsonArray) {
            element.asJsonArray.forEach { child ->
                findEmbeddedScripts(child, keys)?.let { return it }
            }
        }
        return null
    }

    private fun normalizeKey(value: String): String = value
        .filter(Char::isLetterOrDigit)
        .lowercase()
}
