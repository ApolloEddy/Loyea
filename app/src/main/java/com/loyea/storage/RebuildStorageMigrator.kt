package com.loyea.storage

import com.loyea.character.core.migration.LegacyMigrator
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest

/**
 * 0.5.5 / 0.6.1 → rebuild_storage_v1 的一次性迁移（Spec §9）。
 *
 * 纪律：
 * - 旧文件保持原样，只读；新数据写入独立 staging 目录，验证后原子切换；
 * - manifest.json 是「迁移已完成」的唯一标记：存在即幂等跳过，
 *   重复迁移不得覆盖新数据或重新导入旧副本；
 * - 崩溃重启只能看到完整旧状态或完整新状态；staging 未完成可整体重试；
 * - 迁移期间所有调用方（前台与 Worker）在同一进程锁上串行，等效暂停后台写入。
 *
 * 本类只依赖 java.io / Gson，可在 JVM 单测中用临时目录验证。
 */
object RebuildStorageMigrator {

    const val ROOT_NAME = "rebuild_storage_v1"
    const val STAGING_NAME = "$ROOT_NAME.staging"
    const val MANIFEST_SCHEMA_VERSION = 1

    /** 视为 0.6.1 来源的文件。 */
    private const val SUMMARIES_FILE = "character_persona_summaries.json"
    private const val TAVERN_DIR = "tavern"
    private const val TAVERN_CARDS_DIR = "$TAVERN_DIR/cards"

    data class SnapshotEntry(val path: String, val bytes: Long, val sha256: String)

    data class MigrationOutcome(
        val performed: Boolean,
        val root: File,
        val notes: List<String>
    )

    /**
     * 确保迁移完成。sourceRoot 为旧版 filesDir；调用方可重复调用（幂等）。
     * 所有存储访问方必须在首次访问前调用本方法（进程内串行）。
     */
    @Synchronized
    fun ensureMigrated(sourceRoot: File): MigrationOutcome {
        val root = File(sourceRoot, ROOT_NAME)
        val manifest = File(root, "manifest.json")
        if (manifest.exists()) {
            return MigrationOutcome(performed = false, root = root, notes = emptyList())
        }
        // 上次 staging 未完成：整体作废重来（旧状态仍在 sourceRoot，未受损）
        val staging = File(sourceRoot, STAGING_NAME)
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()
        // 标准子目录在 staging 中预建，切换后新根即具备完整布局
        File(staging, "characters").mkdirs()
        File(staging, "sessions").mkdirs()

        val notes = ArrayList<String>()

        // —— 源快照：清单 + 大小 + hash（Spec §9.2）——
        val snapshot = ArrayList<SnapshotEntry>()
        listOf(
            "character_cards.json",
            "sessions_metadata.json",
            "global_world_info.json",
            "graph_memories.json",
            SUMMARIES_FILE
        ).forEach { name -> snapshotFile(File(sourceRoot, name), name, snapshot) }
        File(sourceRoot, "sessions").takeIf { it.isDirectory }?.listFiles()?.forEach { file ->
            if (file.isFile) snapshotFile(file, "sessions/${file.name}", snapshot)
        }
        File(sourceRoot, TAVERN_CARDS_DIR).takeIf { it.isDirectory }?.listFiles()?.forEach { file ->
            if (file.isFile) snapshotFile(file, "$TAVERN_CARDS_DIR/${file.name}", snapshot)
        }

        val sources = detectSources(sourceRoot)
        notes += "来源：${sources.joinToString(" + ")}。"

        // —— 角色：统一为每 ID 一份 CharacterDocument ——
        val charactersDir = File(staging, "characters")
        val cardDocsById = loadLegacyCardDocuments(sourceRoot)
        val migration = LegacyMigrator.migrateCharacters(
            LegacyMigrator.CharacterSources(
                legacyCardsJson = readIfExists(File(sourceRoot, "character_cards.json")),
                personaSummariesJson = readIfExists(File(sourceRoot, SUMMARIES_FILE)),
                cardDocumentsById = cardDocsById
            )
        )
        val characterNotes = ArrayList<JsonObject>()
        migration.characters.forEach { migrated ->
            CharacterDocumentStore(charactersDir).save(migrated.document)
            characterNotes += JsonObject().apply {
                addProperty("id", migrated.document.profile.id)
                addProperty("sources", migrated.sources.joinToString(","))
                if (migrated.notes.isNotEmpty()) addProperty("notes", migrated.notes.joinToString("；"))
            }
        }
        notes += "迁移角色 ${migration.characters.size} 个，冲突副本 ${migration.conflicts.size} 份。"
        migration.conflicts.forEach { conflict ->
            notes += "冲突：${conflict.sourceId} — ${conflict.reason}。"
        }
        // 冲突副本（0.5.5 主版本）也要落盘，让用户在列表里看到双方
        val documentStore = CharacterDocumentStore(charactersDir)
        migration.conflicts.forEach { conflict ->
            val marked = conflict.keptDocument.copy(
                profile = conflict.keptDocument.profile.copy(
                    display = conflict.keptDocument.profile.display.copy(
                        shortIntro = (conflict.keptDocument.profile.display.shortIntro.ifBlank { "迁移副本" }) +
                            "〔迁移冲突副本：0.5.5 版本〕"
                    )
                )
            )
            documentStore.save(marked)
        }

        // —— 会话元数据：已知字段映射 + 未知字段保留 + 超范围功能报告 ——
        migrateSessionMetadata(
            File(sourceRoot, "sessions_metadata.json"),
            File(staging, "sessions_metadata.json"),
            notes
        )

        // —— 会话消息 / 会话世界书 / 全局世界书 / 图谱：字节级复制 ——
        File(sourceRoot, "sessions").takeIf { it.isDirectory }?.let { dir ->
            val target = File(staging, "sessions").apply { mkdirs() }
            dir.listFiles { f -> f.isFile }?.forEach { file -> file.copyTo(File(target, file.name), overwrite = false) }
        }
        copyIfExists(File(sourceRoot, "global_world_info.json"), File(staging, "global_world_info.json"), notes, "全局世界书")
        copyIfExists(File(sourceRoot, "graph_memories.json"), File(staging, "graph_memories.json"), notes, "关系图谱记忆")

        // —— manifest 最后写入 staging ——
        val counts = JsonObject().apply {
            addProperty("characters", migration.characters.size + migration.conflicts.size)
            addProperty("sessions", sessionCount(File(staging, "sessions_metadata.json")))
        }
        val manifestObj = JsonObject().apply {
            addProperty("schemaVersion", MANIFEST_SCHEMA_VERSION)
            addProperty("migratedAt", System.currentTimeMillis())
            addProperty("sources", sources.joinToString(","))
            add("sourceFiles", JsonArray().apply {
                snapshot.forEach { entry ->
                    add(JsonObject().apply {
                        addProperty("path", entry.path)
                        addProperty("bytes", entry.bytes)
                        addProperty("sha256", entry.sha256)
                    })
                }
            })
            add("counts", counts)
            add("characters", JsonArray().apply { characterNotes.forEach { note -> add(note) } })
            add("notes", JsonArray().apply { notes.forEach { note -> add(com.google.gson.JsonPrimitive(note)) } })
        }
        File(staging, "manifest.json").writeText(manifestObj.toString())

        // —— 原子切换：manifest 已在 staging 内，rename 后目标要么完整要么不存在 ——
        if (root.exists()) root.deleteRecursively()
        if (!staging.renameTo(root)) {
            // rename 失败（同卷罕见）：回退为复制
            root.mkdirs()
            staging.copyRecursively(root, overwrite = true)
            staging.deleteRecursively()
        }
        return MigrationOutcome(performed = true, root = root, notes = notes)
    }

    // ---------- 内部 ----------

    private fun detectSources(sourceRoot: File): List<String> {
        val sources = ArrayList<String>()
        if (File(sourceRoot, "character_cards.json").exists()) sources += "v0.5.5"
        if (File(sourceRoot, SUMMARIES_FILE).exists() || File(sourceRoot, TAVERN_DIR).isDirectory) sources += "v0.6.1"
        if (sources.isEmpty()) sources += "fresh"
        return sources
    }

    /** 用 summary/cardId 重算 sha256 找回 0.6.1 卡文档；注册表等附加来源暂不枚举 ID。 */
    private fun loadLegacyCardDocuments(sourceRoot: File): Map<String, String> {
        val cardsDir = File(sourceRoot, TAVERN_CARDS_DIR)
        if (!cardsDir.isDirectory) return emptyMap()
        val ids = linkedSetOf<String>()
        readIfExists(File(sourceRoot, SUMMARIES_FILE))?.let { json ->
            runCatching {
                val array = JsonParser.parseString(json).takeIf { it.isJsonArray }?.asJsonArray
                array?.forEach { element ->
                    (element as? JsonObject)?.stringOrNull("id")?.let { ids.add(it) }
                }
            }
        }
        readIfExists(File(sourceRoot, "character_cards.json"))?.let { json ->
            runCatching {
                val array = JsonParser.parseString(json).takeIf { it.isJsonArray }?.asJsonArray
                array?.forEach { element ->
                    (element as? JsonObject)?.stringOrNull("id")?.let { ids.add(it) }
                }
            }
        }
        val result = linkedMapOf<String, String>()
        ids.forEach { id ->
            val file = File(cardsDir, LegacyMigrator.cardDocumentFileName(id))
            if (file.exists()) {
                readIfExists(file)?.let { result[id] = it }
            }
        }
        return result
    }

    /**
     * 会话元数据迁移：已知字段逐个映射；无法识别的字段整体保留进 legacyExtrasJson；
     * 检测到群聊 / Author's Note 等超范围功能时写入迁移报告（不丢弃、不转换）。
     */
    private fun migrateSessionMetadata(source: File, target: File, notes: MutableList<String>) {
        if (!source.exists()) return
        val gson = com.google.gson.Gson()
        val knownKeys = setOf(
            "id", "title", "lastActiveTime", "characterId", "useSystemTime", "coreMemories",
            "isTitleSummarized", "compressedSummary", "compressedAtCount", "promptTokens",
            "completionTokens", "lastContextTokens", "promptCacheHitTokens", "promptCacheMissTokens",
            "bindingRevision", "sessionIncarnationId", "legacyExtrasJson"
        )
        val unsupportedMarkers = listOf("groupChatJson", "authorNote")
        val array = runCatching {
            JsonParser.parseString(source.readText()).takeIf { it.isJsonArray }?.asJsonArray
        }.getOrNull() ?: JsonArray()
        val out = JsonArray()
        array.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val clean = JsonObject()
            val extras = JsonObject()
            obj.entrySet().forEach { (key, value) ->
                if (key in knownKeys) clean.add(key, value) else extras.add(key, value)
            }
            // personaBindingRevision → bindingRevision；sessionIncarnationId 原样保留
            obj.get("personaBindingRevision")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.let {
                if (it.asLong > 0) clean.addProperty("bindingRevision", it.asLong)
            }
            if (!clean.has("bindingRevision")) clean.addProperty("bindingRevision", 1L)
            obj.get("sessionIncarnationId")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.let {
                clean.add("sessionIncarnationId", it)
            }
            if (extras.size() > 0) {
                clean.addProperty("legacyExtrasJson", extras.toString())
                val found = unsupportedMarkers.filter { extras.has(it) }
                if (found.isNotEmpty()) {
                    notes += "会话「${obj.stringOrNull("title") ?: obj.stringOrNull("id")}」含本轮不支持的功能数据（${found.joinToString("/")}），已只读保留。"
                }
            }
            out.add(clean)
        }
        target.writeText(gson.toJson(out))
    }

    private fun sessionCount(sessionsMetadata: File): Int = runCatching {
        JsonParser.parseString(sessionsMetadata.readText()).takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0
    }.getOrDefault(0)

    private fun snapshotFile(file: File, relativePath: String, out: MutableList<SnapshotEntry>) {
        if (!file.exists()) return
        val bytes = file.readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        out += SnapshotEntry(
            path = relativePath,
            bytes = bytes.size.toLong(),
            sha256 = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        )
    }

    private fun copyIfExists(source: File, target: File, notes: MutableList<String>, label: String) {
        if (!source.exists()) return
        source.copyTo(target, overwrite = false)
        notes += "$label 已恢复。"
    }

    private fun readIfExists(file: File): String? =
        if (file.exists()) runCatching { file.readText() }.getOrNull() else null

    private fun JsonObject.stringOrNull(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}
