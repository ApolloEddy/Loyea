package com.loyea.ui.chat

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * D2 一次性迁移的安全备份（host legacy 桥类型的原始备份）。
 *
 * 语义：在把 `character_cards.json`（旧序列化格式，CharacterCard 中内嵌 Tavern 扩展字段）
 * 拆成【原生 [PersonaSummary] 宿主存储】与【插件私有 TavernCardDocument 存储】之前，把源文件
 * 原样复制到 `<文件名>.pre_persona_summary_v1.json`，与既有 `pre_*_v1.json` 备份命名纪律对齐
 * （参见 `sessions_metadata.pre_persona_binding_v1.json`）。
 *
 * 不可转移的硬性保证：
 *   - 源文件永不删除、从不改写（可供降级/恢复）；
 *   - 备份只写一次（已存在则跳过），过程崩溃后可安全重跑（幂等）；
 *   - 迁移失败不破坏原数据，且失败不会留下已损坏的目标。
 *
 * “拆两个新结构的写回”由宿主的 [ChatStorageManager.syncPersonaSummariesInternal]（PersonaSummary
 * 宿主存储）与既有的 syncTavernCardDocumentsInternal（插件 TavernCardDocument 存储）共同承担；
 * 本对象只负责迁移前的那一份原始备份与遗留格式探测。
 */
object PersonaSummarySplitMigration {

    enum class Status {
        /** 源文件不存在，无需迁移。 */
        NO_SOURCE,
        /** 源文件存在但未携带任何 Tavern 扩展字段，保持原样。 */
        NO_LEGACY_TAVERN,
        /** 本次写入了原始备份。 */
        BACKUP_CREATED,
        /** 备份已存在（幂等重跑），不再重复写入。 */
        ALREADY_BACKED_UP
    }

    /**
     * 探测给定 JSON 是否由"内嵌 Tavern 扩展字段的 CharacterCard"序列化而来。
     *
     * 根文件为角色卡数组或单个对象。逐卡检查：
     * - 字符串字段（creatorNotes/postHistoryInstructions/characterVersion/nickname）非空白；
     * - 列表字段（alternateGreetings/groupOnlyGreetings）非空；
     * - JSON 字符串字段（creatorNotesMultilingualJson/assetsJson/extensionsJson）与默认占位不同；
     * - 嵌套对象/原始字段（characterBookJson/originalCardJson）非 null。
     * spec/specVersion 不算标记：它们是原生 CharacterCard 的固定默认值，原生卡同样携带。
     */
    fun hasLegacyTavernFields(raw: String): Boolean {
        val root = runCatching { JsonParser.parseString(raw) }.getOrNull() ?: return false
        val cards = when {
            root.isJsonArray -> root.asJsonArray.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
            root.isJsonObject -> listOf(root.asJsonObject)
            else -> return false
        }
        return cards.any { card -> cardHasLegacyTavernContent(card) }
    }

    private fun cardHasLegacyTavernContent(card: JsonObject): Boolean {
        fun string(key: String): String? =
            card[key]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

        fun stringList(key: String): List<String> {
            val el = card[key] ?: return emptyList()
            if (!el.isJsonArray) return emptyList()
            return el.asJsonArray.mapNotNull {
                it.takeIf { value -> value.isJsonPrimitive && value.asJsonPrimitive.isString }?.asString
            }.filter { it.isNotBlank() }
        }

        // JSON 字符串字段：既可能是 CharacterCard 的 JSON 文本占位（"{}"/"[]"），也可能是
        // 已解析的对象/数组直接出现。两者都按"是否携带实质内容"判断。
        fun jsonLikeContent(key: String): Boolean {
            val el = card[key] ?: return false
            val text = if (el.isJsonPrimitive && el.asJsonPrimitive.isString) {
                el.asString
            } else if (el.isJsonObject || el.isJsonArray) {
                el.toString()
            } else {
                return false
            }
            return text.isNotBlank() && text != "{}" && text != "[]"
        }

        return listOf("creatorNotes", "postHistoryInstructions", "characterVersion", "nickname")
            .any { key -> !string(key).isNullOrBlank() } ||
            listOf("alternateGreetings", "groupOnlyGreetings")
                .any { key -> stringList(key).isNotEmpty() } ||
            listOf("creatorNotesMultilingualJson", "assetsJson", "extensionsJson")
                .any(::jsonLikeContent) ||
            listOf("characterBookJson", "originalCardJson").any { key -> card[key] != null }
    }

    /**
     * 迁移前写一份原始备份。幂等、非破坏：
     * 源文件缺失 → [Status.NO_SOURCE]；无 Tavern 扩展字段 → [Status.NO_LEGACY_TAVERN]；
     * 备份已存在 → [Status.ALREADY_BACKED_UP]；否则原子写入并返回 [Status.BACKUP_CREATED]。
     */
    fun ensureBackup(sourceFile: File, backupFile: File): Status {
        if (!sourceFile.isFile) return Status.NO_SOURCE
        if (backupFile.isFile) return Status.ALREADY_BACKED_UP
        val raw = runCatching { sourceFile.readText(Charsets.UTF_8) }
            .getOrElse { e ->
                e.printStackTrace()
                return Status.NO_SOURCE
            }
        if (!hasLegacyTavernFields(raw)) return Status.NO_LEGACY_TAVERN
        atomicWrite(backupFile, raw)
        return Status.BACKUP_CREATED
    }

    /** 原子写入：先写临时文件再重命名，避免中途崩溃留下半截备份。 */
    private fun atomicWrite(file: File, content: String) {
        file.parentFile?.mkdirs()
        val tmpFile = File(file.parentFile, "${file.name}.tmp")
        try {
            FileOutputStream(tmpFile).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(
                    tmpFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmpFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (tmpFile.exists()) tmpFile.delete()
        }
    }
}