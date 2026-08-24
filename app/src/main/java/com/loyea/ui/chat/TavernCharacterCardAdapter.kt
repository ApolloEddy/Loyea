package com.loyea.ui.chat

import com.loyea.plugins.tavern.core.*
import com.loyea.plugin.api.PersonaProjection
import com.loyea.plugin.api.PersonaRef

/** Host-side projection between Loyea's legacy card model and the isolated Tavern document. */
object TavernCharacterCardAdapter {
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
