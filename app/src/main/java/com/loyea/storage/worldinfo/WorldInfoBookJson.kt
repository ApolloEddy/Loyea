package com.loyea.storage.worldinfo

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.loyea.ui.chat.WorldInfoConfigStorage
import com.loyea.ui.chat.WorldInfoEntry

/**
 * 书文档 JSON 序列化（WorldInfo 2.0 Spec §3.2 schema）。
 *
 * 书级标量字段手工构建（schemaVersion 契约、逐字段兜底，对齐 CharacterDocumentJson 风格）；
 * 条目沿用 Gson 反射 + selfHeal 逐字段兜底（与既有 global_world_info.json /
 * world_info_<sid>.json 的读写同一套语义；W5 收敛时与 ChatStorageManager 的
 * selfHealWorldInfo 统一）。
 *
 * 反序列化未知 JSON 键忽略（向前兼容）；预留 injectionMode 等扩展位不阻断旧版读取。
 */
object WorldInfoBookJson {
    const val SCHEMA_VERSION = 1

    private val gson = Gson()

    fun toJson(book: WorldInfoBookDocument): String {
        val root = JsonObject()
        root.addProperty("schemaVersion", SCHEMA_VERSION)
        root.addProperty("id", book.id)
        root.addProperty("name", book.name)
        root.addProperty("createdAt", book.createdAt)
        root.addProperty("updatedAt", book.updatedAt)
        root.addProperty("origin", book.origin.name)
        book.originCharacterId?.let { root.addProperty("originCharacterId", it) }
        root.addProperty("scope", book.scope.name)
        root.add("sessionIds", JsonArray().apply { book.sessionIds.forEach(::add) })
        root.addProperty("isGlobalActive", book.isGlobalActive)
        root.add("entries", gson.toJsonTree(book.entries).asJsonArray)
        root.add("disabledUids", JsonArray().apply { book.disabledUids.forEach(::add) })
        book.config?.let {
            root.add("config", JsonParser.parseString(WorldInfoConfigStorage.toJson(it)))
        }
        return root.toString()
    }

    fun fromJson(json: String): WorldInfoBookDocument? = runCatching {
        val root = JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
            ?: return null
        val id = root.stringOrNull("id") ?: return null
        WorldInfoBookDocument(
            id = id,
            name = root.stringOrNull("name") ?: "未命名世界书",
            createdAt = root.longOrNull("createdAt") ?: 0L,
            updatedAt = root.longOrNull("updatedAt") ?: 0L,
            origin = runCatching {
                WorldInfoBookOrigin.valueOf(root.stringOrNull("origin") ?: "CREATED")
            }.getOrDefault(WorldInfoBookOrigin.CREATED),
            originCharacterId = root.stringOrNull("originCharacterId"),
            scope = runCatching {
                WorldInfoBookScope.valueOf(root.stringOrNull("scope") ?: "GLOBAL")
            }.getOrDefault(WorldInfoBookScope.GLOBAL),
            sessionIds = root.stringListOrNull("sessionIds"),
            isGlobalActive = root.booleanOrNull("isGlobalActive") ?: false,
            entries = root.arrayOrNull("entries")?.let(::readEntries) ?: emptyList(),
            disabledUids = root.arrayOrNull("disabledUids")?.mapNotNull { element ->
                element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
            } ?: emptyList(),
            config = root.objectOrNull("config")?.toString()
                ?.let { WorldInfoConfigStorage.fromJson(it) }
        )
    }.getOrNull()

    /**
     * Gson 反射对缺失字段不触发 Kotlin 默认值（原始类型退化为 0/false、对象类型为 null），
     * 逐字段兜底回默认（与 ChatStorageManager.selfHealWorldInfo 同语义）。
     */
    internal fun selfHealEntries(rawList: List<WorldInfoEntry>): List<WorldInfoEntry> =
        rawList.map { raw ->
            WorldInfoEntry(
                id = raw.id ?: System.currentTimeMillis().toString(),
                keywords = raw.keywords ?: emptyList(),
                content = raw.content ?: "",
                enabled = raw.enabled ?: true,
                uid = raw.uid ?: 0,
                keysecondary = raw.keysecondary ?: emptyList(),
                constant = raw.constant ?: false,
                order = raw.order ?: 100,
                depth = raw.depth ?: 4,
                comment = raw.comment ?: "",
                selective = raw.selective ?: false,
                disable = raw.disable ?: false,
                selectiveLogic = raw.selectiveLogic ?: 0,
                group = raw.group ?: "",
                probability = raw.probability ?: 100,
                useProbability = raw.useProbability ?: false,
                delayUntilRecursion = raw.delayUntilRecursion ?: 0,
                preventRecursion = raw.preventRecursion ?: false,
                allowRecursion = raw.allowRecursion ?: true,
                excludeRecursion = raw.excludeRecursion ?: false,
                keysContainedIn = raw.keysContainedIn ?: "chat",
                position = raw.position ?: 0,
                weight = raw.weight ?: 0
            )
        }

    private fun readEntries(array: JsonArray): List<WorldInfoEntry> = runCatching {
        selfHealEntries(gson.fromJson(array, Array<WorldInfoEntry>::class.java).toList())
    }.getOrDefault(emptyList())

    private fun JsonObject.objectOrNull(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.arrayOrNull(name: String): JsonArray? =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.stringOrNull(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.longOrNull(name: String): Long? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

    private fun JsonObject.booleanOrNull(name: String): Boolean? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private fun JsonObject.stringListOrNull(name: String): List<String> {
        val value = get(name) ?: return emptyList()
        if (!value.isJsonArray) return emptyList()
        return value.asJsonArray.mapNotNull { element ->
            element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        }
    }
}
