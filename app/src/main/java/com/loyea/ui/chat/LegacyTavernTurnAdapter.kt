package com.loyea.ui.chat

import com.loyea.context.core.*
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
        prompt: PromptPatch = PromptPatch(stablePersonaText = ""),
        generationType: String = "normal",
        authorNote: TavernAuthorNote? = null,
        userTurnIndex: Long = 0L,
        macroContext: TavernMacroContext? = null,
        continueNudge: String? = null,
        continuePrefill: Boolean = false
    ): PreparedPersonaTurn = TavernPreparedTurnFactory.prepare(
        spec(
            card = card,
            userName = userName,
            regexScripts = regexScripts,
            presetMessages = presetMessages,
            worldInfoAtDepth = worldInfoAtDepth,
            generation = generation,
            prompt = prompt,
            generationType = generationType,
            authorNote = authorNote,
            userTurnIndex = userTurnIndex,
            macroContext = macroContext,
            continueNudge = continueNudge,
            continuePrefill = continuePrefill
        )
    )

    fun spec(
        card: CharacterCard,
        userName: String,
        regexScripts: Collection<TavernRegexScript>,
        presetMessages: Collection<TavernPresetPrompt>,
        worldInfoAtDepth: Map<Int, List<WorldInfoMatcher.WorldInfoInjectionBlock>>,
        generation: GenerationPatch = GenerationPatch(),
        prompt: PromptPatch = PromptPatch(stablePersonaText = ""),
        generationType: String = "normal",
        authorNote: TavernAuthorNote? = null,
        userTurnIndex: Long = 0L,
        macroContext: TavernMacroContext? = null,
        continueNudge: String? = null,
        continuePrefill: Boolean = false
    ): TavernTurnSpec = TavernTurnSpec(
            prompt = prompt,
            presetMessages = presetMessages,
            worldInfoAtDepth = worldInfoAtDepth,
            generation = generation,
            regexScripts = regexScripts,
            macroContext = macroContext ?: TavernCardRegexAdapter.macroContext(card, userName),
            generationType = generationType,
            authorNote = authorNote,
            userTurnIndex = userTurnIndex,
            continueNudge = continueNudge,
            continuePrefill = continuePrefill
        )
}
