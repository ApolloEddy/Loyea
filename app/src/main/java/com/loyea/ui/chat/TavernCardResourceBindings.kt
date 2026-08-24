package com.loyea.ui.chat

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Discovers external-resource references and inline books in a persisted Loyea card.
 * Host card traversal stays outside the Tavern plugin runtime.
 */
object TavernCardResourceBindings {
    fun worldBookNames(card: CharacterCard): List<String> = findNames(
        card,
        "world", "worldbook", "world_book", "worldBook", "worldInfo", "world_info",
        "boundWorldBook", "bound_world_book", "boundWorldbook"
    )

    fun presetNames(card: CharacterCard): List<String> = findNames(
        card,
        "presetId", "preset_id", "presetName", "preset_name", "boundPreset", "bound_preset",
        "preset", "prompt_preset", "tavern_preset"
    )

    fun regexCollectionNames(card: CharacterCard): List<String> = findNames(
        card,
        "regexId", "regex_id", "regexName", "regex_name", "boundRegex", "bound_regex",
        "regexCollection", "regex_collection"
    )

    /** Reads world-book objects embedded directly inside card extensions/vendor data. */
    fun inlineWorldBooks(card: CharacterCard): List<Pair<String, WorldInfoBook>> {
        val keys = setOf(
            "world", "worldbook", "world_book", "worldinfo", "world_info",
            "boundworldbook", "bound_world_book", "boundworldbook"
        ).map(::normalizeKey).toSet()
        return findBoundElements(card, keys).mapIndexedNotNull { index, (key, value) ->
            val candidates = when {
                value.isJsonObject -> listOf(value)
                value.isJsonArray -> listOf(
                    JsonObject().apply { add("entries", value) }
                ) + value.asJsonArray.filter { it.isJsonObject }
                value.isJsonPrimitive && value.asJsonPrimitive.isString -> listOfNotNull(
                    runCatching { JsonParser.parseString(value.asString) }.getOrNull()
                )
                else -> emptyList()
            }
            val book = candidates.asSequence()
                .mapNotNull { TavernWorldBookCodec.parse(it.toString()) }
                .firstOrNull { it.entries.isNotEmpty() }
                ?: return@mapIndexedNotNull null
            val id = "card_inline_world_${normalizeKey(key)}_$index"
            id to book
        }.distinctBy { (id, book) -> id to book.entries.map { it.rawJson ?: it.id }.hashCode() }
    }

    private fun findNames(card: CharacterCard, vararg keys: String): List<String> {
        val normalizedKeys = keys.map(::normalizeKey).toSet()
        return findBoundElements(card, normalizedKeys).flatMap { (_, value) -> namesFrom(value) }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    /** Bindings are often nested under third-party vendor/metadata/extensions objects. */
    private fun findBoundElements(card: CharacterCard, keys: Set<String>): List<Pair<String, JsonElement>> {
        val roots = buildList {
            card.extensionsJson.parseObject()?.let(::add)
            card.originalCardJson.parseObject()?.let { root ->
                add(root)
                root["data"]?.takeIf { it.isJsonObject }?.asJsonObject?.let(::add)
                root["data"]?.takeIf { it.isJsonObject }?.asJsonObject?.get("extensions")
                    ?.takeIf { it.isJsonObject }?.asJsonObject?.let(::add)
            }
        }.distinctBy(JsonObject::toString)
        val result = mutableListOf<Pair<String, JsonElement>>()
        fun collect(element: JsonElement) {
            if (!element.isJsonObject) return
            element.asJsonObject.entrySet().forEach { (key, value) ->
                if (normalizeKey(key) in keys) result += key to value
                if (value.isJsonObject) collect(value)
                if (value.isJsonArray) value.asJsonArray.forEach(::collect)
            }
        }
        roots.forEach(::collect)
        return result.distinctBy { (key, value) -> normalizeKey(key) to value.toString() }
    }

    private fun normalizeKey(value: String): String = value
        .filter(Char::isLetterOrDigit)
        .lowercase()

    private fun namesFrom(element: JsonElement?): List<String> {
        if (element == null || element.isJsonNull) return emptyList()
        return when {
            element.isJsonPrimitive &&
                (element.asJsonPrimitive.isString || element.asJsonPrimitive.isNumber) -> listOf(element.asString)
            element.isJsonArray -> element.asJsonArray.flatMap(::namesFrom)
            element.isJsonObject -> {
                val obj = element.asJsonObject
                listOf("id", "name", "uid", "file", "value")
                    .asSequence()
                    .mapNotNull { key -> obj[key] }
                    .firstOrNull { it.isJsonPrimitive && (it.asJsonPrimitive.isString || it.asJsonPrimitive.isNumber) }
                    ?.let { listOf(it.asString) }
                    ?: emptyList()
            }
            else -> emptyList()
        }
    }

    private fun String?.parseObject(): JsonObject? = runCatching {
        this?.takeIf { it.isNotBlank() }?.let(JsonParser::parseString)
            ?.takeIf { it.isJsonObject }?.asJsonObject
    }.getOrNull()
}
