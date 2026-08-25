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
        val data = TavernCardData(
            name = card.name,
            description = card.description.ifBlank { originalData?.description ?: card.shortIntro },
            shortDescription = originalData?.shortDescription ?: card.shortIntro,
            personality = card.personality,
            scenario = card.scenario,
            firstMessage = card.firstMessage,
            mesExample = card.chatExamples,
            creatorNotes = card.creatorNotes.ifBlank { originalData?.creatorNotes.orEmpty() },
            systemPrompt = card.systemPrompt,
            postHistoryInstructions = card.postHistoryInstructions.ifBlank {
                originalData?.postHistoryInstructions.orEmpty()
            },
            alternateGreetings = card.alternateGreetings.ifEmpty { originalData?.alternateGreetings.orEmpty() },
            groupOnlyGreetings = card.groupOnlyGreetings.ifEmpty { originalData?.groupOnlyGreetings.orEmpty() },
            tags = card.tags.ifEmpty { originalData?.tags.orEmpty() },
            // `creatorName` may be the host-only display fallback "网络导入". Never write that
            // fallback into an imported document, otherwise a blank source creator changes the
            // stable card identity during a host -> plugin -> host round trip.
            creator = originalData?.creator ?: card.creatorName.orEmpty(),
            characterVersion = card.characterVersion.ifBlank { originalData?.characterVersion.orEmpty() },
            nickname = card.nickname ?: originalData?.nickname,
            source = card.source.ifEmpty { originalData?.source.orEmpty() },
            creationDate = card.creationDate ?: originalData?.creationDate,
            modificationDate = card.modificationDate ?: originalData?.modificationDate,
            creatorNotesMultilingualJson = card.creatorNotesMultilingualJson
                .takeIf { it != "{}" } ?: originalData?.creatorNotesMultilingualJson ?: "{}",
            assetsJson = card.assetsJson.takeIf { it != "[]" } ?: originalData?.assetsJson ?: "[]",
            extensionsJson = card.extensionsJson.takeIf { it != "{}" } ?: originalData?.extensionsJson ?: "{}",
            characterBook = card.characterBookJson?.let(TavernCardCodec::parseCharacterBook)
                ?: originalData?.characterBook
        )
        return TavernCardDocument(
            spec = card.spec.ifBlank { original?.spec ?: "chara_card_v2" },
            specVersion = card.specVersion.ifBlank { original?.specVersion ?: "2.0" },
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
