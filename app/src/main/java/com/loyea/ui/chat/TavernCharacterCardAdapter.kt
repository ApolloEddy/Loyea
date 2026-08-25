package com.loyea.ui.chat

import com.loyea.plugins.tavern.core.*
import com.loyea.plugin.api.PersonaProjection
import com.loyea.plugin.api.PersonaRef

/** Host-side projection between Loyea's legacy card model and the isolated Tavern document. */
object TavernCharacterCardAdapter {

    /**
     * 原生单向路径：Card → [PersonaSummary]。
     * 供宿主运行时消费原生最小集；不投射任何 Tavern 扩展字段。
     */
    fun toPersonaSummary(card: CharacterCard): PersonaSummary = PersonaSummary(
        id = card.id,
        name = card.name,
        avatarUri = card.avatarUri,
        avatarColor = card.avatarColor,
        shortIntro = card.shortIntro,
        description = card.description,
        systemPrompt = card.systemPrompt,
        personality = card.personality,
        scenario = card.scenario,
        firstMessage = card.firstMessage,
        mesExample = card.chatExamples,
        isBuiltIn = card.isBuiltIn,
        creatorName = card.creatorName,
        backgroundUri = card.backgroundUri
    )

    /**
     * 一次性拆分：把遗留桥类型 Card 同时投影成原生 [PersonaSummary] 与 Tavern 完整文档
     * [TavernCardDocument]。用于迁移路径（读旧 → 拆两个新结构写回）。
     */
    fun split(card: CharacterCard): PersonaSplitResult =
        PersonaSplitResult(summary = toPersonaSummary(card), document = toDocument(card))

    /**
     * TODO1：用插件私有文档库的 [TavernCardDocument] 给宿主 [CharacterCard] 补回扩展字段。
     *
     * v2 wire 下宿主 character_cards.json 不再携带 Tavern 扩展字段（见 [TavernCardWireFormat]），
     * 宿主读取非内置卡后用本函数从文档库恢复完整扩展字段。合并方向固定：卡片自身已有的
     * 非空/非默认值优先（宿主可编辑），否则回退文档值。
     *
     * spec/specVersion 需要特判：其占位默认值（"chara_card_v2"/"2.0"）非空，直接用
     * `ifBlank` 会让默认值压制文档里的真实 spec。规则改为"卡片值非占位默认才保留"。
     */
    fun overlayTavernFields(card: CharacterCard, document: TavernCardDocument): CharacterCard {
        val d = document.data
        // 卡片字段可能来自 raw gson（v2 wire 缺字段 → null），逐字段做 null/空白兜底，
        // 语义与 ChatStorageManager 的 self-heal 一致：卡片已有非空值优先，否则回退文档。
        return card.copy(
            description = card.description?.takeIf { it.isNotBlank() } ?: d.description,
            creatorNotes = card.creatorNotes?.takeIf { it.isNotBlank() } ?: d.creatorNotes,
            postHistoryInstructions = card.postHistoryInstructions?.takeIf { it.isNotBlank() }
                ?: d.postHistoryInstructions,
            alternateGreetings = card.alternateGreetings?.takeIf { it.isNotEmpty() } ?: d.alternateGreetings,
            groupOnlyGreetings = card.groupOnlyGreetings?.takeIf { it.isNotEmpty() } ?: d.groupOnlyGreetings,
            tags = card.tags?.takeIf { it.isNotEmpty() } ?: d.tags,
            characterVersion = card.characterVersion?.takeIf { it.isNotBlank() } ?: d.characterVersion,
            nickname = card.nickname ?: d.nickname,
            source = card.source?.takeIf { it.isNotEmpty() } ?: d.source,
            creationDate = card.creationDate ?: d.creationDate,
            modificationDate = card.modificationDate ?: d.modificationDate,
            creatorNotesMultilingualJson = card.creatorNotesMultilingualJson?.takeIf { it != "{}" }
                ?: d.creatorNotesMultilingualJson,
            assetsJson = card.assetsJson?.takeIf { it != "[]" } ?: d.assetsJson,
            extensionsJson = card.extensionsJson?.takeIf { it != "{}" } ?: d.extensionsJson,
            characterBookJson = card.characterBookJson ?: d.characterBook?.rawJson,
            spec = card.spec?.takeIf { it != "chara_card_v2" } ?: document.spec,
            specVersion = card.specVersion?.takeIf { it != "2.0" } ?: document.specVersion,
            originalCardJson = card.originalCardJson ?: document.rawJson
        )
    }

    fun toProjection(card: CharacterCard, ref: PersonaRef): PersonaProjection {
        require(ref.personaId == card.id) { "Persona projection ref does not match card id" }
        return PersonaProjection(
            ref = ref,
            displayName = card.name,
            avatarUri = card.avatarUri,
            summary = card.description.ifBlank { card.shortIntro },
            greetingTemplates = buildList {
                card.firstMessage.takeIf(String::isNotBlank)?.let(::add)
                card.alternateGreetings.filterTo(this) { it.isNotBlank() }
            }.distinct()
        )
    }

    fun toDocument(card: CharacterCard): TavernCardDocument {
        val original = card.originalCardJson?.let(TavernCardCodec::parseJson)
        val originalData = original?.data
        // 卡片可能来自 raw gson（迁移路径 / v2 wire），字段可为 null；逐字段兜底，避免
        // ifBlank/ifEmpty 对 null 抛 NPE。语义与 ChatStorageManager 的 self-heal 一致。
        val data = TavernCardData(
            name = card.name ?: "",
            description = card.description?.takeIf { it.isNotBlank() }
                ?: originalData?.description ?: card.shortIntro ?: "",
            shortDescription = originalData?.shortDescription ?: card.shortIntro,
            personality = card.personality.orEmpty(),
            scenario = card.scenario.orEmpty(),
            firstMessage = card.firstMessage.orEmpty(),
            mesExample = card.chatExamples.orEmpty(),
            creatorNotes = card.creatorNotes?.takeIf { it.isNotBlank() }
                ?: originalData?.creatorNotes.orEmpty(),
            systemPrompt = card.systemPrompt.orEmpty(),
            postHistoryInstructions = card.postHistoryInstructions?.takeIf { it.isNotBlank() }
                ?: originalData?.postHistoryInstructions.orEmpty(),
            alternateGreetings = card.alternateGreetings?.takeIf { it.isNotEmpty() }
                ?: originalData?.alternateGreetings.orEmpty(),
            groupOnlyGreetings = card.groupOnlyGreetings?.takeIf { it.isNotEmpty() }
                ?: originalData?.groupOnlyGreetings.orEmpty(),
            tags = card.tags?.takeIf { it.isNotEmpty() } ?: originalData?.tags.orEmpty(),
            // `creatorName` may be the host-only display fallback "网络导入". Never write that
            // fallback into an imported document, otherwise a blank source creator changes the
            // stable card identity during a host -> plugin -> host round trip.
            creator = originalData?.creator ?: card.creatorName.orEmpty(),
            characterVersion = card.characterVersion?.takeIf { it.isNotBlank() }
                ?: originalData?.characterVersion.orEmpty(),
            nickname = card.nickname ?: originalData?.nickname,
            source = card.source?.takeIf { it.isNotEmpty() } ?: originalData?.source.orEmpty(),
            creationDate = card.creationDate ?: originalData?.creationDate,
            modificationDate = card.modificationDate ?: originalData?.modificationDate,
            creatorNotesMultilingualJson = card.creatorNotesMultilingualJson?.takeIf { it != "{}" }
                ?: originalData?.creatorNotesMultilingualJson ?: "{}",
            assetsJson = card.assetsJson?.takeIf { it != "[]" }
                ?: originalData?.assetsJson ?: "[]",
            extensionsJson = card.extensionsJson?.takeIf { it != "{}" }
                ?: originalData?.extensionsJson ?: "{}",
            characterBook = card.characterBookJson?.let(TavernCardCodec::parseCharacterBook)
                ?: originalData?.characterBook
        )
        return TavernCardDocument(
            spec = card.spec?.takeIf { it.isNotBlank() } ?: original?.spec ?: "chara_card_v2",
            specVersion = card.specVersion?.takeIf { it.isNotBlank() } ?: original?.specVersion ?: "2.0",
            data = data,
            rawJson = card.originalCardJson
        )
    }
}

/**
 * D2 一次性拆分的结果：原生最小集 + Tavern 完整文档。
 * 迁移路径使用它对遗留桥类型做“拆两个新结构写回”。
 */
data class PersonaSplitResult(
    val summary: PersonaSummary,
    val document: TavernCardDocument
)
