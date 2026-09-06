package com.loyea.character.core.api

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * CharacterDocument 的 JSON 序列化（存储格式 rebuild_storage_v1/characters/<id>.json）。
 * 只做纯数据变换，不做文件 I/O；app 存储层负责落盘与原子替换。
 *
 * schemaVersion=1 字段布局：
 * {
 *   "schemaVersion": 1,
 *   "profile": { id, revision, name, description, personality, scenario,
 *                systemPrompt, postHistoryInstructions, firstMessage,
 *                alternateGreetings, mesExample, origin,
 *                display: { avatarUri, avatarColor, shortIntro, creatorName, backgroundUri } },
 *   "spec", "specVersion", "extensionsJson", "embeddedBookJson", "rawCardJson",
 *   "capabilities": [ { field, kind, detail } ]
 * }
 *
 * 反序列化逐字段兜底：未知 JSON 键忽略（向前兼容），缺失键用默认值，不产生运行时 null。
 */
object CharacterDocumentJson {
    const val SCHEMA_VERSION = 1

    fun toJson(document: CharacterDocument): String {
        val profile = document.profile
        val display = profile.display
        val root = JsonObject()
        root.addProperty("schemaVersion", SCHEMA_VERSION)

        val profileObj = JsonObject()
        profileObj.addProperty("id", profile.id)
        profileObj.addProperty("revision", profile.revision)
        profileObj.addProperty("name", profile.name)
        profileObj.addProperty("description", profile.description)
        profileObj.addProperty("personality", profile.personality)
        profileObj.addProperty("scenario", profile.scenario)
        profileObj.addProperty("systemPrompt", profile.systemPrompt)
        profileObj.addProperty("postHistoryInstructions", profile.postHistoryInstructions)
        profileObj.addProperty("firstMessage", profile.firstMessage)
        profileObj.add("alternateGreetings", stringArray(profile.alternateGreetings))
        profileObj.addProperty("mesExample", profile.mesExample)
        profileObj.addProperty("origin", profile.origin.name)
        val displayObj = JsonObject()
        display.avatarUri?.let { displayObj.addProperty("avatarUri", it) }
        displayObj.addProperty("avatarColor", display.avatarColor)
        displayObj.addProperty("shortIntro", display.shortIntro)
        display.creatorName?.let { displayObj.addProperty("creatorName", it) }
        display.backgroundUri?.let { displayObj.addProperty("backgroundUri", it) }
        profileObj.add("display", displayObj)
        root.add("profile", profileObj)

        document.spec?.let { root.addProperty("spec", it) }
        document.specVersion?.let { root.addProperty("specVersion", it) }
        root.addProperty("extensionsJson", document.extensionsJson)
        document.embeddedBookJson?.let { root.addProperty("embeddedBookJson", it) }
        document.rawCardJson?.let { root.addProperty("rawCardJson", it) }
        val caps = JsonArray()
        document.capabilities.forEach { capability ->
            caps.add(JsonObject().apply {
                addProperty("field", capability.field)
                addProperty("kind", capability.kind)
                if (capability.detail.isNotBlank()) addProperty("detail", capability.detail)
            })
        }
        root.add("capabilities", caps)
        return root.toString()
    }

    fun fromJson(json: String): CharacterDocument? = runCatching {
        val root = JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
            ?: return null
        val profileObj = root.objectOrNull("profile") ?: return null
        val displayObj = profileObj.objectOrNull("display") ?: JsonObject()
        val profile = CharacterProfile(
            id = profileObj.stringOrNull("id") ?: return null,
            revision = profileObj.longOrNull("revision") ?: 1L,
            name = profileObj.stringOrNull("name") ?: "",
            description = profileObj.stringOrNull("description") ?: "",
            personality = profileObj.stringOrNull("personality") ?: "",
            scenario = profileObj.stringOrNull("scenario") ?: "",
            systemPrompt = profileObj.stringOrNull("systemPrompt") ?: "",
            postHistoryInstructions = profileObj.stringOrNull("postHistoryInstructions") ?: "",
            firstMessage = profileObj.stringOrNull("firstMessage") ?: "",
            alternateGreetings = profileObj.stringListOrNull("alternateGreetings"),
            mesExample = profileObj.stringOrNull("mesExample") ?: "",
            origin = runCatching { CharacterOrigin.valueOf(profileObj.stringOrNull("origin") ?: "NATIVE") }
                .getOrDefault(CharacterOrigin.NATIVE),
            display = CharacterDisplayInfo(
                avatarUri = displayObj.stringOrNull("avatarUri"),
                avatarColor = displayObj.stringOrNull("avatarColor") ?: "#E5D3B3",
                shortIntro = displayObj.stringOrNull("shortIntro") ?: "",
                creatorName = displayObj.stringOrNull("creatorName"),
                backgroundUri = displayObj.stringOrNull("backgroundUri")
            )
        )
        val capabilities = root.arrayOrNull("capabilities")?.mapNotNull { element ->
            val capability = element.takeIf { it.isJsonObject }?.asJsonObject
                ?: return@mapNotNull null
            CharacterCapability(
                field = capability.stringOrNull("field") ?: return@mapNotNull null,
                kind = capability.stringOrNull("kind") ?: CharacterCapability.KIND_PRESERVED,
                detail = capability.stringOrNull("detail") ?: ""
            )
        } ?: emptyList()
        CharacterDocument(
            profile = profile,
            spec = root.stringOrNull("spec"),
            specVersion = root.stringOrNull("specVersion"),
            extensionsJson = root.stringOrNull("extensionsJson") ?: "{}",
            embeddedBookJson = root.stringOrNull("embeddedBookJson"),
            rawCardJson = root.stringOrNull("rawCardJson"),
            capabilities = capabilities
        )
    }.getOrNull()

    private fun stringArray(values: List<String>): JsonArray = JsonArray().also { array ->
        values.forEach(array::add)
    }

    private fun JsonObject.objectOrNull(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.arrayOrNull(name: String) =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.stringOrNull(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.longOrNull(name: String): Long? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

    private fun JsonObject.stringListOrNull(name: String): List<String> {
        val value = get(name) ?: return emptyList()
        if (!value.isJsonArray) return emptyList()
        return value.asJsonArray.mapNotNull { element ->
            element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        }
    }
}
