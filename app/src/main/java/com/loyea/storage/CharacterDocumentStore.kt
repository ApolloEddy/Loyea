package com.loyea.storage

import com.loyea.character.core.api.CharacterDocument
import com.loyea.character.core.api.CharacterDocumentJson
import com.loyea.character.core.api.CharacterDisplayInfo
import com.loyea.character.core.api.CharacterOrigin
import com.loyea.character.core.api.CharacterProfile
import com.loyea.ui.chat.CharacterCard
import java.io.File

/**
 * CharacterDocument 的 app 侧存储与投影（rebuild_storage_v1/characters/<id>.json）。
 *
 * 单一真源：运行时 UI 使用的 CharacterCard 只是从文档派生的投影；
 * 编辑经 [applyCardToDocument] 回写文档并递增 revision。
 * 本类只做文件级 CRUD，不做迁移决策（迁移见 RebuildStorageMigrator）。
 */
class CharacterDocumentStore(private val charactersDir: File) {

    // ---------- CRUD ----------

    fun loadAll(): List<CharacterDocument> {
        if (!charactersDir.exists()) return emptyList()
        return charactersDir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            .orEmpty()
            .mapNotNull { file ->
                runCatching { CharacterDocumentJson.fromJson(file.readText()) }.getOrNull()
                    ?.let { doc -> doc to file.lastModified() }
            }
            // 创建时间升序（0.5.5 的插入序习惯：先建在前）；旧文档未记录 createdAt 时用文件 mtime 兜底，
            // 避免按 id 字母序导致后建的人格跳到先导入的卡之上。
            .sortedBy { (doc, fileModified) -> if (doc.profile.createdAt > 0) doc.profile.createdAt else fileModified }
            .map { it.first }
    }

    fun load(id: String): CharacterDocument? {
        val file = fileFor(id)
        if (!file.exists()) return null
        return runCatching { CharacterDocumentJson.fromJson(file.readText()) }.getOrNull()
    }

    fun save(document: CharacterDocument) {
        if (!charactersDir.exists()) charactersDir.mkdirs()
        val file = fileFor(document.profile.id)
        val tmp = File(charactersDir, file.name + ".tmp")
        tmp.writeText(CharacterDocumentJson.toJson(document))
        if (!tmp.renameTo(file)) {
            tmp.delete()
            file.writeText(CharacterDocumentJson.toJson(document))
        }
    }

    fun delete(id: String) {
        fileFor(id).delete()
    }

    fun exists(id: String): Boolean = fileFor(id).exists()

    fun count(): Int = loadAll().size

    private fun fileFor(id: String): File {
        val safeName = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(charactersDir, "$safeName.json")
    }

    // ---------- 投影：CharacterDocument ↔ CharacterCard ----------

    companion object {

        /** 文档 → UI 卡片投影。description 保留在文档里，shortIntro 只做展示。 */
        fun projectCard(document: CharacterDocument): CharacterCard = CharacterCard(
            id = document.profile.id,
            name = document.profile.name,
            avatarUri = document.profile.display.avatarUri,
            avatarColor = document.profile.display.avatarColor,
            shortIntro = document.profile.display.shortIntro,
            systemPrompt = document.profile.systemPrompt,
            personality = document.profile.personality,
            scenario = document.profile.scenario,
            firstMessage = document.profile.firstMessage,
            chatExamples = document.profile.mesExample,
            isBuiltIn = document.profile.isBuiltIn,
            creatorName = document.profile.display.creatorName,
            backgroundUri = document.profile.display.backgroundUri
        )

        /** 用户在 UI 编辑卡片后，把改动按字段写回文档（世界书/扩展/原始卡不动）。 */
        fun applyCardToDocument(document: CharacterDocument, card: CharacterCard): CharacterDocument =
            document.copy(
                profile = document.profile.copy(
                    revision = document.profile.revision + 1,
                    name = card.name,
                    personality = card.personality,
                    scenario = card.scenario,
                    systemPrompt = card.systemPrompt,
                    firstMessage = card.firstMessage,
                    mesExample = card.chatExamples,
                    display = document.profile.display.copy(
                        avatarUri = card.avatarUri,
                        avatarColor = card.avatarColor,
                        shortIntro = card.shortIntro,
                        creatorName = card.creatorName,
                        backgroundUri = card.backgroundUri
                    )
                )
            )

        /** 从 UI 新建的原生人格创建文档。 */
        fun documentFromCard(card: CharacterCard): CharacterDocument = CharacterDocument(
            profile = CharacterProfile(
                id = card.id,
                revision = 1L,
                name = card.name,
                description = "",
                personality = card.personality,
                scenario = card.scenario,
                systemPrompt = card.systemPrompt,
                postHistoryInstructions = "",
                firstMessage = card.firstMessage,
                alternateGreetings = emptyList(),
                mesExample = card.chatExamples,
                origin = if (card.isBuiltIn) CharacterOrigin.BUILT_IN_TEMPLATE else CharacterOrigin.NATIVE,
                display = CharacterDisplayInfo(
                    avatarUri = card.avatarUri,
                    avatarColor = card.avatarColor,
                    shortIntro = card.shortIntro,
                    creatorName = card.creatorName,
                    backgroundUri = card.backgroundUri
                )
            )
        )
    }
}
