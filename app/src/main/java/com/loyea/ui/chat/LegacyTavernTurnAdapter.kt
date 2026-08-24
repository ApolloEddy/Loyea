package com.loyea.ui.chat

import com.loyea.plugins.tavern.core.*

import com.loyea.plugin.api.GenerationPatch
import com.loyea.plugin.api.PreparedPersonaTurn
import com.loyea.plugin.api.PromptPatch

/** Temporary Android bridge while card lookup moves behind [TavernPersonaRepository]. */
object LegacyTavernTurnAdapter {
    fun prepare(
        card: CharacterCard,
        userName: String,
        regexScripts: Collection<TavernRegexScript>,
        presetMessages: Collection<TavernPresetPrompt>,
        worldInfoAtDepth: Map<Int, List<WorldInfoMatcher.WorldInfoInjectionBlock>>,
        generation: GenerationPatch = GenerationPatch(),
        prompt: PromptPatch = PromptPatch(stablePersonaText = "")
    ): PreparedPersonaTurn = TavernPreparedTurnFactory.prepare(
        spec(
            card = card,
            userName = userName,
            regexScripts = regexScripts,
            presetMessages = presetMessages,
            worldInfoAtDepth = worldInfoAtDepth,
            generation = generation,
            prompt = prompt
        )
    )

    fun spec(
        card: CharacterCard,
        userName: String,
        regexScripts: Collection<TavernRegexScript>,
        presetMessages: Collection<TavernPresetPrompt>,
        worldInfoAtDepth: Map<Int, List<WorldInfoMatcher.WorldInfoInjectionBlock>>,
        generation: GenerationPatch = GenerationPatch(),
        prompt: PromptPatch = PromptPatch(stablePersonaText = "")
    ): TavernTurnSpec = TavernTurnSpec(
            prompt = prompt,
            presetMessages = presetMessages,
            worldInfoAtDepth = worldInfoAtDepth,
            generation = generation,
            regexScripts = regexScripts,
            macroContext = TavernCardRegexAdapter.macroContext(card, userName)
        )
}
