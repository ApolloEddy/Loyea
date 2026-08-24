package com.loyea.ui.chat

import com.loyea.plugin.api.ChatRole
import com.loyea.plugin.api.ConversationInsertion
import com.loyea.plugin.api.GenerationPatch
import com.loyea.plugin.api.InsertionAnchor
import com.loyea.plugin.api.PluginTurnPlan
import com.loyea.plugin.api.PreparedPersonaTurn
import com.loyea.plugin.api.PromptPatch
import com.loyea.plugin.api.TextStage

/** Freezes legacy Tavern state behind the generic request-turn contract. */
object LegacyTavernTurnAdapter {
    fun prepare(
        card: CharacterCard,
        userName: String,
        regexScripts: Collection<TavernRegexScript>,
        presetMessages: Collection<TavernPresetPrompt>,
        worldInfoAtDepth: Map<Int, List<WorldInfoMatcher.WorldInfoInjectionBlock>>,
        generation: GenerationPatch = GenerationPatch()
    ): PreparedPersonaTurn {
        val frozenMacroContext = TavernCardRegexAdapter.macroContext(card, userName)
        val frozenScripts = regexScripts.map { script ->
            script.copy(
                trimStrings = script.trimStrings.toList(),
                placement = script.placement.toList()
            )
        }
        val insertions = buildList {
            presetMessages.forEachIndexed { index, prompt ->
                if (prompt.content.isNotBlank()) {
                    add(
                        ConversationInsertion(
                            anchor = InsertionAnchor.AFTER_SYSTEM_BEFORE_SUMMARY,
                            role = prompt.role.toChatRole(),
                            content = "[PRESET SLOT / ${prompt.identifier}]\n${prompt.content.trim()}",
                            order = index
                        )
                    )
                }
            }
            var order = presetMessages.size
            worldInfoAtDepth.forEach { (depth, blocks) ->
                if (depth >= 0) {
                    blocks.forEach { block ->
                        if (block.content.isNotBlank()) {
                            add(
                                ConversationInsertion(
                                    anchor = InsertionAnchor.AT_DEPTH_FROM_LATEST,
                                    role = block.role.toChatRole(),
                                    content = "[WORLD INFO @ DEPTH / 深度世界书]\n${block.content}",
                                    depthFromLatest = depth,
                                    order = order++
                                )
                            )
                        }
                    }
                }
            }
        }
        val plan = PluginTurnPlan(
            prompt = PromptPatch(stablePersonaText = ""),
            insertions = insertions,
            generation = generation
        )

        return object : PreparedPersonaTurn {
            override val plan: PluginTurnPlan = plan

            override fun transform(
                stage: TextStage,
                text: String,
                depth: Int?,
                isMarkdown: Boolean
            ): String = when (stage) {
                TextStage.USER_INPUT -> TavernRegexEngine.apply(
                    text = text,
                    scripts = frozenScripts,
                    placement = TavernRegexPlacement.USER_INPUT,
                    context = frozenMacroContext,
                    depth = depth,
                    isMarkdown = isMarkdown,
                    isPrompt = true
                )
                TextStage.MODEL_OUTPUT -> TavernRegexEngine.applyOutput(
                    text = text,
                    scripts = frozenScripts,
                    context = frozenMacroContext,
                    depth = depth,
                    isMarkdown = isMarkdown
                )
                TextStage.REASONING -> TavernRegexEngine.apply(
                    text = text,
                    scripts = frozenScripts,
                    placement = TavernRegexPlacement.REASONING,
                    context = frozenMacroContext,
                    depth = depth,
                    isMarkdown = isMarkdown
                )
                TextStage.GREETING -> TavernRegexEngine.applyOutput(
                    text = text,
                    scripts = frozenScripts,
                    context = frozenMacroContext,
                    depth = depth,
                    isMarkdown = isMarkdown
                )
            }
        }
    }

    private fun String?.toChatRole(): ChatRole = when (this?.lowercase()) {
        "user" -> ChatRole.USER
        "assistant" -> ChatRole.ASSISTANT
        "tool" -> ChatRole.TOOL
        else -> ChatRole.SYSTEM
    }
}
