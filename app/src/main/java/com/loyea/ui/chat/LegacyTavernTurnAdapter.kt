package com.loyea.ui.chat

import com.loyea.plugin.api.GenerationPatch
import com.loyea.plugin.api.PreparedPersonaTurn

/** Temporary Android bridge while card lookup moves behind [TavernPersonaRepository]. */
object LegacyTavernTurnAdapter {
    fun prepare(
        card: CharacterCard,
        userName: String,
        regexScripts: Collection<TavernRegexScript>,
        presetMessages: Collection<TavernPresetPrompt>,
        worldInfoAtDepth: Map<Int, List<WorldInfoMatcher.WorldInfoInjectionBlock>>,
        generation: GenerationPatch = GenerationPatch()
    ): PreparedPersonaTurn = TavernPreparedTurnFactory.prepare(
        TavernTurnSpec(
            presetMessages = presetMessages,
            worldInfoAtDepth = worldInfoAtDepth,
            generation = generation,
            regexScripts = regexScripts,
            macroContext = TavernCardRegexAdapter.macroContext(card, userName)
        )
    )
}
