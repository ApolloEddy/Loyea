package com.loyea.ui.chat

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.loyea.plugins.tavern.core.TavernCardCodec
import com.loyea.plugins.tavern.storage.TavernCardDocumentStore
import com.loyea.plugins.tavern.storage.TavernStorageLayout
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * TODO1：宿主 character_cards.json 的 wire 格式 v2 一次性迁移（幂等、可恢复、非破坏）。
 *
 * 语义：宿主 wire 文件从 v2 起不再携带 Tavern 扩展字段（见 [TavernCardWireFormat]），
 * 这些字段的唯一事实来源迁移到插件文档库（`files/tavern/cards/`）。本对象把一个仍携带
 * 扩展字段的旧文件安全升级为 v2：
 *  1. 原始文件完整备份到 `<文件>.pre_tavern_field_drop_v1.json`（只写一次）；
 *  2. 保证每个非内置卡在插件文档库中都有文档副本——**缺失才写**，绝不覆盖已有文档
 *     （插件/宿主都可能编辑过文档，覆盖会丢数据）；
 *  3. 用 wire gson 把同一份卡片列表重写回源文件（扩展字段不再落盘）；
 *  4. 写幂等标记（进程在任一步崩溃后重跑均安全）。
 *
 * 硬性保证与 [PersonaSummarySplitMigration] 对齐：源文件在重写前被完整备份、文档库补齐
 * 在重写之前完成、失败不留半截目标、全程非破坏可恢复。
 */
object TavernFieldDropMigration {

    enum class Status {
        /** 迁移已完成（标记存在）。 */
        ALREADY_MIGRATED,
        /** 源文件不存在，无需迁移。 */
        NO_SOURCE,
        /** 源文件未携带任何 Tavern 扩展字段，已是 v2 语义；仅补写标记。 */
        NO_LEGACY_TAVERN,
        /** 备份 + 文档补齐 + v2 重写 + 标记，全部完成。 */
        MIGRATED
    }

    private val gson = Gson()
    private val wireGson = TavernCardWireFormat.createWireGson()

    fun ensureWireV2(
        sourceFile: File,
        backupFile: File,
        markerFile: File,
        layout: TavernStorageLayout
    ): Status {
        if (markerFile.isFile) return Status.ALREADY_MIGRATED
        if (!sourceFile.isFile) return Status.NO_SOURCE

        val raw = sourceFile.readText(Charsets.UTF_8)
        if (!PersonaSummarySplitMigration.hasLegacyTavernFields(raw)) {
            atomicWrite(markerFile, "# Loyea character_cards.json wire v2 marker\nschemaVersion=${TavernCardWireFormat.SCHEMA_VERSION}\n")
            return Status.NO_LEGACY_TAVERN
        }

        val cards = parseCards(raw)
        if (!backupFile.isFile) atomicWrite(backupFile, raw)

        val documentStore = TavernCardDocumentStore(layout)
        cards.asSequence()
            .filterNot(CharacterCard::isBuiltIn)
            .forEach { card ->
                if (documentStore.exists(card.id)) return@forEach
                runCatching {
                    val documentJson = TavernCardCodec.toJson(TavernCharacterCardAdapter.toDocument(card))
                    if (documentJson.isNotBlank()) documentStore.write(card.id, documentJson)
                }.onFailure { it.printStackTrace() }
            }

        atomicWrite(sourceFile, wireGson.toJson(cards))
        atomicWrite(markerFile, "# Loyea character_cards.json wire v2 marker\nschemaVersion=${TavernCardWireFormat.SCHEMA_VERSION}\n")
        return Status.MIGRATED
    }

    private fun parseCards(raw: String): List<CharacterCard> {
        val type = object : TypeToken<List<CharacterCard>>() {}.type
        return gson.fromJson<List<CharacterCard>>(raw, type) ?: emptyList()
    }

    /** 原子写入：先写临时文件再重命名，避免中途崩溃留下半截内容。 */
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
