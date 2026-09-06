package com.loyea.character.core.migration

import com.loyea.character.core.api.CharacterCapability
import com.loyea.character.core.api.CharacterDisplayInfo
import com.loyea.character.core.api.CharacterDocument
import com.loyea.character.core.api.CharacterOrigin
import com.loyea.character.core.api.CharacterProfile
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 0.5.5 / 0.6.1 角色数据 → 统一 CharacterDocument 的纯映射逻辑（Spec §9.1/§9.2）。
 *
 * 输入全部是 JSON 字符串或已解析对象，不做任何文件 I/O，便于 JVM 单测；
 * 文件枚举、staging、manifest、原子切换由 app 存储层完成。
 *
 * 合并规则（Spec §9.2）：
 * - 基本字段优先级：0.5.5 character_cards.json（用户最新原生状态）>
 *   0.6.1 summary（用户编辑过的投影）> 0.6.1 完整卡文档；
 * - 世界书 / 未知扩展：0.6.1 完整卡文档是唯一来源；
 * - 无法判定的冲突保留双方副本并报告，不悄悄选一份丢掉另一份。
 */
object LegacyMigrator {

    /** 0.6.1 tavern/cards/<sha256(cardId)>.json 的文件名算法（供 app 定位文档文件）。 */
    fun cardDocumentFileName(cardId: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(cardId.trim().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) } + ".json"
    }

    data class CharacterSources(
        /** 0.5.5 character_cards.json 原文；null 表示该来源不存在。 */
        val legacyCardsJson: String? = null,
        /** 0.6.1 character_persona_summaries.json 原文；null 表示该来源不存在。 */
        val personaSummariesJson: String? = null,
        /** 0.6.1 tavern/cards/<sha256(cardId)>.json 的内容，key 为 cardId。 */
        val cardDocumentsById: Map<String, String> = emptyMap()
    )

    data class MigratedCharacter(
        val document: CharacterDocument,
        /** 该角色来自哪些来源（用于诊断与报告）。 */
        val sources: List<String>,
        /** 迁移决策说明（冲突、取舍），进入迁移 manifest。 */
        val notes: List<String> = emptyList()
    )

    /** 无法判定归属的冲突副本：同一来源 ID 保留两份文档。 */
    data class ConflictCopy(
        val sourceId: String,
        val keptDocument: CharacterDocument,
        val reason: String
    )

    data class CharacterMigrationResult(
        val characters: List<MigratedCharacter>,
        val conflicts: List<ConflictCopy>
    )

    fun migrateCharacters(sources: CharacterSources): CharacterMigrationResult {
        val legacyCards = parseLegacyCards(sources.legacyCardsJson)
        val summaries = parseSummaries(sources.personaSummariesJson)
        val allIds = linkedSetOf<String>().apply {
            addAll(legacyCards.keys)
            addAll(summaries.keys)
            addAll(sources.cardDocumentsById.keys)
        }

        val characters = ArrayList<MigratedCharacter>()
        val conflicts = ArrayList<ConflictCopy>()

        for (id in allIds) {
            val legacyCard = legacyCards[id]
            val summary = summaries[id]
            val cardJson = sources.cardDocumentsById[id]
            val cardDoc = cardJson?.let { parseCardDocument(it, id) }

            // —— 世界书 / 扩展唯一来源：0.6.1 完整卡文档 ——
            var extensionsJson = "{}"
            var embeddedBookJson: String? = null
            var spec: String? = null
            var specVersion: String? = null
            var rawCardJson: String? = null
            var capabilities: List<CharacterCapability> = emptyList()
            var documentBasics: DocumentBasics? = null
            if (cardDoc != null) {
                extensionsJson = cardDoc.document.extensionsJson
                embeddedBookJson = cardDoc.document.embeddedBookJson
                spec = cardDoc.document.spec
                specVersion = cardDoc.document.specVersion
                rawCardJson = cardJson
                capabilities = cardDoc.document.capabilities
                documentBasics = cardDoc.basics
            }

            val notes = ArrayList<String>()
            if (legacyCard != null && summary != null) {
                val differ = !basicsEqual(legacyCard.basics, summary.basics)
                when {
                    !differ -> notes += "0.5.5 卡片与 0.6.1 summary 共有字段一致，取 summary。"
                    legacyCard.basics.isBuiltIn -> {
                        // Spec §9.2：内置人格 summary 中被用户编辑的基本字段高于旧文档的旧基本字段。
                        notes += "内置人格：0.6.1 summary 为用户最新编辑状态，基本字段取 summary。"
                    }
                    else -> {
                        // 自定义角色两份用户状态不一致，无法判定新旧：保留双方副本并报告（Spec §9.2）。
                        val legacyDocument = buildDocument(
                            id = id,
                            basics = legacyCard.basics,
                            origin = CharacterOrigin.NATIVE,
                            spec = spec, specVersion = specVersion,
                            extensionsJson = extensionsJson,
                            embeddedBookJson = embeddedBookJson,
                            rawCardJson = rawCardJson,
                            capabilities = capabilities
                        )
                        conflicts += ConflictCopy(
                            sourceId = id,
                            keptDocument = legacyDocument,
                            reason = "0.5.5 卡片与 0.6.1 summary 基本字段不一致，保留 0.5.5 版本为主文档"
                        )
                        notes += "检测到冲突：另存 0.6.1 summary 版本副本（后缀 .summary）。"
                        val summaryDocument = buildDocument(
                            id = "$id.summary",
                            basics = summary.basics,
                            origin = CharacterOrigin.NATIVE,
                            spec = spec, specVersion = specVersion,
                            extensionsJson = extensionsJson,
                            embeddedBookJson = embeddedBookJson,
                            rawCardJson = rawCardJson,
                            capabilities = capabilities
                        )
                        characters += MigratedCharacter(
                            document = summaryDocument,
                            sources = listOf("summary", "tavern/cards").filter { source ->
                                (source == "summary") || cardJson != null
                            },
                            notes = listOf("冲突副本：0.6.1 summary 版本")
                        )
                        continue
                    }
                }
            }

            // —— 基本字段来源：0.5.5 卡片 > summary > 文档投影；
            // 0.5.5 缺失的字段（如 description）用 summary 回填，不因合并丢数据 ——
            val base = legacyCard?.basics ?: summary?.basics
            val filled = if (legacyCard != null && summary != null) base?.copy(
                description = base.description.ifBlank { summary.basics.description },
                postHistoryInstructions = base.postHistoryInstructions.ifBlank { summary.basics.postHistoryInstructions }
            ) else base
            val origin = when {
                base?.isBuiltIn == true -> CharacterOrigin.BUILT_IN_TEMPLATE
                legacyCard != null || summary != null -> CharacterOrigin.NATIVE
                else -> CharacterOrigin.IMPORTED
            }
            val resolved = filled ?: documentBasics
                ?: run {
                    notes += "无基本字段来源，仅从卡文档投影（可能缺少展示信息）。"
                    DocumentBasics(
                        name = cardDoc?.document?.profile?.name ?: id,
                        description = cardDoc?.document?.profile?.description ?: "",
                        personality = cardDoc?.document?.profile?.personality ?: "",
                        scenario = cardDoc?.document?.profile?.scenario ?: "",
                        systemPrompt = cardDoc?.document?.profile?.systemPrompt ?: "",
                        postHistoryInstructions = cardDoc?.document?.profile?.postHistoryInstructions ?: "",
                        firstMessage = cardDoc?.document?.profile?.firstMessage ?: "",
                        alternateGreetings = cardDoc?.document?.profile?.alternateGreetings ?: emptyList(),
                        mesExample = cardDoc?.document?.profile?.mesExample ?: "",
                        isBuiltIn = false,
                        display = cardDoc?.document?.profile?.display ?: CharacterDisplayInfo()
                    )
                }
            if (legacyCard != null && summary != null && legacyCard.basics.isBuiltIn) {
                notes += "内置人格：已保存覆盖优先于模板（Spec §4.8）。"
            }

            characters += MigratedCharacter(
                document = buildDocument(
                    id = id,
                    basics = resolved,
                    origin = origin,
                    spec = spec, specVersion = specVersion,
                    extensionsJson = extensionsJson,
                    embeddedBookJson = embeddedBookJson,
                    rawCardJson = rawCardJson,
                    capabilities = capabilities
                ),
                sources = listOfNotNull(
                    if (legacyCard != null) "0.5.5 cards" else null,
                    if (summary != null) "summary" else null,
                    if (cardJson != null) "tavern/cards" else null
                ),
                notes = notes
            )
        }
        return CharacterMigrationResult(characters, conflicts)
    }

    data class DocumentBasics(
        val name: String,
        val description: String,
        val personality: String,
        val scenario: String,
        val systemPrompt: String,
        val postHistoryInstructions: String,
        val firstMessage: String,
        val alternateGreetings: List<String>,
        val mesExample: String,
        val isBuiltIn: Boolean,
        val display: CharacterDisplayInfo,
        /** 原生 shortIntro（0.5.5 卡片 / 0.6.1 summary 的展示字段）。 */
        val shortIntro: String = ""
    )

    private fun basicsEqual(a: DocumentBasics, b: DocumentBasics): Boolean =
        // 只比较两版共有的可编辑字段：0.5.5 卡片没有 description/postHistoryInstructions，
        // summary 没有独立的旧版对照，缺省字段不参与冲突判定。
        a.name == b.name && a.personality == b.personality &&
            a.scenario == b.scenario && a.systemPrompt == b.systemPrompt &&
            a.firstMessage == b.firstMessage && a.mesExample == b.mesExample

    private fun buildDocument(
        id: String,
        basics: DocumentBasics,
        origin: CharacterOrigin,
        spec: String?,
        specVersion: String?,
        extensionsJson: String,
        embeddedBookJson: String?,
        rawCardJson: String?,
        capabilities: List<CharacterCapability>
    ): CharacterDocument = CharacterDocument(
        profile = CharacterProfile(
            id = id,
            revision = 1L,
            name = basics.name,
            description = basics.description,
            personality = basics.personality,
            scenario = basics.scenario,
            systemPrompt = basics.systemPrompt,
            postHistoryInstructions = basics.postHistoryInstructions,
            firstMessage = basics.firstMessage,
            alternateGreetings = basics.alternateGreetings,
            mesExample = basics.mesExample,
            origin = origin,
            display = CharacterDisplayInfo(
                avatarUri = basics.display.avatarUri,
                avatarColor = basics.display.avatarColor.ifBlank { "#E5D3B3" },
                shortIntro = basics.shortIntro.ifBlank { basics.display.shortIntro },
                creatorName = basics.display.creatorName,
                backgroundUri = basics.display.backgroundUri
            )
        ),
        spec = spec,
        specVersion = specVersion,
        extensionsJson = extensionsJson,
        embeddedBookJson = embeddedBookJson,
        rawCardJson = rawCardJson,
        capabilities = capabilities
    )

    // ---------- 0.5.5 character_cards.json ----------

    private data class LegacyCard(
        val basics: DocumentBasics,
        val isUserEditedBuiltIn: Boolean
    )

    private fun parseLegacyCards(json: String?): Map<String, LegacyCard> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val array = JsonParser.parseString(json).takeIf { it.isJsonArray }?.asJsonArray
                ?: return emptyMap()
            val byId = linkedMapOf<String, LegacyCard>()
            array.forEach { element ->
                val obj = (element as? JsonObject) ?: return@forEach
                val id = obj.stringOrNull("id") ?: return@forEach
                if (byId.containsKey(id)) return@forEach
                byId[id] = LegacyCard(
                    basics = DocumentBasics(
                        name = obj.stringOrNull("name") ?: "Unknown",
                        description = "",
                        personality = obj.stringOrNull("personality") ?: "",
                        scenario = obj.stringOrNull("scenario") ?: "",
                        // 0.5.5 卡片把 description 折叠进 systemPrompt；这里保留 systemPrompt 原文，
                        // description 置空等待 0.6.1 文档补全（冲突比较只比较共有字段）。
                        systemPrompt = obj.stringOrNull("systemPrompt") ?: "",
                        postHistoryInstructions = "",
                        firstMessage = obj.stringOrNull("firstMessage") ?: "",
                        alternateGreetings = emptyList(),
                        mesExample = obj.stringOrNull("chatExamples") ?: "",
                        isBuiltIn = obj.booleanOrNull("isBuiltIn") ?: false,
                        display = CharacterDisplayInfo(
                            avatarUri = obj.stringOrNull("avatarUri"),
                            avatarColor = obj.stringOrNull("avatarColor") ?: "#E5D3B3",
                            shortIntro = obj.stringOrNull("shortIntro") ?: "",
                            creatorName = obj.stringOrNull("creatorName"),
                            backgroundUri = obj.stringOrNull("backgroundUri")
                        ),
                        shortIntro = obj.stringOrNull("shortIntro") ?: ""
                    ),
                    // 0.5.5 无 pristine 标记：保守视为用户状态
                    isUserEditedBuiltIn = false
                )
            }
            byId
        }.getOrDefault(emptyMap())
    }

    // ---------- 0.6.1 character_persona_summaries.json ----------

    private data class PersonaSummaryRecord(
        val basics: DocumentBasics,
        val isUserEdited: Boolean
    )

    private fun parseSummaries(json: String?): Map<String, PersonaSummaryRecord> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val array = JsonParser.parseString(json).takeIf { it.isJsonArray }?.asJsonArray
                ?: return emptyMap()
            val byId = linkedMapOf<String, PersonaSummaryRecord>()
            array.forEach { element ->
                val obj = (element as? JsonObject) ?: return@forEach
                val id = obj.stringOrNull("id") ?: return@forEach
                if (byId.containsKey(id)) return@forEach
                val isBuiltIn = obj.booleanOrNull("isBuiltIn") ?: false
                byId[id] = PersonaSummaryRecord(
                    basics = DocumentBasics(
                        name = obj.stringOrNull("name") ?: "Unknown",
                        description = obj.stringOrNull("description") ?: "",
                        personality = obj.stringOrNull("personality") ?: "",
                        scenario = obj.stringOrNull("scenario") ?: "",
                        systemPrompt = obj.stringOrNull("systemPrompt") ?: "",
                        postHistoryInstructions = "",
                        firstMessage = obj.stringOrNull("firstMessage") ?: "",
                        alternateGreetings = emptyList(),
                        mesExample = obj.stringOrNull("mesExample") ?: "",
                        isBuiltIn = isBuiltIn,
                        display = CharacterDisplayInfo(
                            avatarUri = obj.stringOrNull("avatarUri"),
                            avatarColor = obj.stringOrNull("avatarColor") ?: "#E5D3B3",
                            shortIntro = obj.stringOrNull("shortIntro") ?: "",
                            creatorName = obj.stringOrNull("creatorName"),
                            backgroundUri = obj.stringOrNull("backgroundUri")
                        ),
                        shortIntro = obj.stringOrNull("shortIntro") ?: ""
                    ),
                    // 0.6.1 只保存「非内置 或 用户编辑过的内置」；入库即视为用户状态。
                    isUserEdited = true
                )
            }
            byId
        }.getOrDefault(emptyMap())
    }

    // ---------- 0.6.1 tavern/cards/<sha>.json ----------

    private data class ParsedCard(
        val document: CharacterDocument,
        val basics: DocumentBasics
    )

    private fun parseCardDocument(rawJson: String, fallbackId: String): ParsedCard? {
        return runCatching {
            val importer = com.loyea.character.core.api.CharacterCardImporter
            val result = importer.fromRawCardJson(rawJson)
            val profile = result.document.profile
            ParsedCard(
                document = result.document.withProfile(
                    profile.copy(id = fallbackId, origin = CharacterOrigin.IMPORTED)
                ),
                basics = DocumentBasics(
                    name = profile.name,
                    description = profile.description,
                    personality = profile.personality,
                    scenario = profile.scenario,
                    systemPrompt = profile.systemPrompt,
                    postHistoryInstructions = profile.postHistoryInstructions,
                    firstMessage = profile.firstMessage,
                    alternateGreetings = profile.alternateGreetings,
                    mesExample = profile.mesExample,
                    isBuiltIn = false,
                    display = profile.display,
                    shortIntro = profile.display.shortIntro
                )
            )
        }.getOrNull()
    }

    private fun JsonObject.stringOrNull(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.booleanOrNull(name: String): Boolean? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
}
