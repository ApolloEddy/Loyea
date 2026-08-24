package com.loyea.plugins.tavern.core

import com.loyea.context.core.*

import com.loyea.plugin.api.ChatRole
import com.loyea.plugin.api.ConversationInsertion
import com.loyea.plugin.api.GenerationPatch
import com.loyea.plugin.api.InsertionAnchor
import com.loyea.plugin.api.LOYEA_PLUGIN_API_VERSION
import com.loyea.plugin.api.LoyeaPlugin
import com.loyea.plugin.api.PersonaPluginRuntime
import com.loyea.plugin.api.PersonaProjection
import com.loyea.plugin.api.PersonaRef
import com.loyea.plugin.api.PluginCapability
import com.loyea.plugin.api.PluginDescriptor
import com.loyea.plugin.api.PluginId
import com.loyea.plugin.api.PluginRuntimeGeneration
import com.loyea.plugin.api.PluginTurnInput
import com.loyea.plugin.api.PluginTurnPlan
import com.loyea.plugin.api.PreparedPersonaTurn
import com.loyea.plugin.api.PromptPatch
import com.loyea.plugin.api.TextStage
import java.util.concurrent.atomic.AtomicBoolean

/** Stable identity and capabilities of the bundled SillyTavern compatibility plugin. */
object TavernPluginDefinition {
    val ID: PluginId = PluginId.of("com.loyea.tavern")

    val DESCRIPTOR: PluginDescriptor = PluginDescriptor(
        id = ID,
        displayName = "Tavern Compatibility",
        apiVersion = LOYEA_PLUGIN_API_VERSION,
        capabilities = setOf(
            PluginCapability.PERSONAS,
            PluginCapability.PROMPT_PIPELINE,
            PluginCapability.OUTPUT_PIPELINE
        )
    )
}

/** Android storage and prompt assembly implement this port; the plugin core owns no host state. */
interface TavernPersonaRepository {
    suspend fun resolve(personaId: String): PersonaProjection?

    suspend fun prepareTurn(
        personaId: String,
        input: PluginTurnInput,
        restoredSnapshot: String? = null,
        generation: PluginRuntimeGeneration
    ): TavernTurnSpec?

    fun discardGeneration(generation: PluginRuntimeGeneration) = Unit
}

/**
 * Complete Tavern-specific state captured for one request. Collections are copied so the
 * resulting [PreparedPersonaTurn] cannot observe later settings, card, or registry mutations.
 */
class TavernTurnSpec(
    val prompt: PromptPatch = PromptPatch(stablePersonaText = ""),
    presetMessages: Collection<TavernPresetPrompt> = emptyList(),
    worldInfoAtDepth: Map<Int, Collection<WorldInfoMatcher.WorldInfoInjectionBlock>> = emptyMap(),
    val generation: GenerationPatch = GenerationPatch(),
    regexScripts: Collection<TavernRegexScript> = emptyList(),
    val macroContext: TavernMacroContext = TavernMacroContext(),
    val opaqueSnapshot: String? = null,
    val generationType: String = "normal",
    val authorNote: TavernAuthorNote? = null,
    val userTurnIndex: Long = 0L
) {
    init {
        require(generationType.isNotBlank()) { "Tavern generation type must not be blank" }
        require(userTurnIndex >= 0L) { "Tavern user turn index must not be negative" }
    }

    val presetMessages: List<TavernPresetPrompt> = presetMessages.map { it.copy() }
    val worldInfoAtDepth: Map<Int, List<WorldInfoMatcher.WorldInfoInjectionBlock>> =
        worldInfoAtDepth.entries.associate { (depth, blocks) ->
            depth to blocks.map { block -> block.copy() }
        }
    val regexScripts: List<TavernRegexScript> = regexScripts.map { script ->
        script.copy(
            trimStrings = script.trimStrings.toList(),
            placement = script.placement.toList()
        )
    }
}

class TavernPersonaUnavailableException(val ref: PersonaRef) :
    IllegalStateException("Tavern persona is unavailable: ${ref.ownerId}/${ref.personaId}")

/** Pure request factory shared by the plugin runtime and the temporary Android bridge. */
object TavernPreparedTurnFactory {
    fun prepare(spec: TavernTurnSpec): PreparedPersonaTurn {
        val frozenSpec = TavernTurnSpec(
            prompt = spec.prompt.copy(),
            presetMessages = spec.presetMessages,
            worldInfoAtDepth = spec.worldInfoAtDepth,
            generation = spec.generation,
            regexScripts = spec.regexScripts,
            macroContext = spec.macroContext.copy(),
            opaqueSnapshot = spec.opaqueSnapshot,
            generationType = spec.generationType,
            authorNote = spec.authorNote?.copy(),
            userTurnIndex = spec.userTurnIndex
        )
        val insertions = buildList {
            frozenSpec.presetMessages.forEachIndexed { index, prompt ->
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
            var order = frozenSpec.presetMessages.size
            frozenSpec.authorNote
                ?.takeIf { it.shouldInsert(frozenSpec.userTurnIndex) }
                ?.let { note ->
                    if (note.normalizedPosition() == TavernAuthorNote.POSITION_AFTER_SCENARIO) {
                        add(
                            ConversationInsertion(
                                anchor = InsertionAnchor.AFTER_SYSTEM_BEFORE_SUMMARY,
                                role = ChatRole.SYSTEM,
                                content = "[AUTHOR'S NOTE / 作者注释]\n${note.text.trim()}",
                                order = order++
                            )
                        )
                    }
                }
            frozenSpec.worldInfoAtDepth.forEach { (depth, blocks) ->
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
            frozenSpec.authorNote
                ?.takeIf { it.shouldInsert(frozenSpec.userTurnIndex) }
                ?.takeIf { it.normalizedPosition() == TavernAuthorNote.POSITION_IN_CHAT }
                ?.let { note ->
                    add(
                        ConversationInsertion(
                            anchor = InsertionAnchor.AT_DEPTH_FROM_LATEST,
                            role = ChatRole.SYSTEM,
                            content = "[AUTHOR'S NOTE / 作者注释]\n${note.text.trim()}",
                            depthFromLatest = note.depth,
                            order = order++
                        )
                    )
                }
        }
        val plan = PluginTurnPlan(
            prompt = frozenSpec.prompt,
            insertions = insertions,
            generation = frozenSpec.generation,
            opaqueSnapshot = frozenSpec.opaqueSnapshot
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
                    scripts = frozenSpec.regexScripts,
                    placement = TavernRegexPlacement.USER_INPUT,
                    context = frozenSpec.macroContext,
                    depth = depth,
                    isMarkdown = isMarkdown,
                    isPrompt = true
                )
                TextStage.MODEL_OUTPUT,
                TextStage.GREETING -> TavernRegexEngine.applyOutput(
                    text = text,
                    scripts = frozenSpec.regexScripts,
                    context = frozenSpec.macroContext,
                    depth = depth,
                    isMarkdown = isMarkdown
                )
                TextStage.REASONING -> TavernRegexEngine.apply(
                    text = text,
                    scripts = frozenSpec.regexScripts,
                    placement = TavernRegexPlacement.REASONING,
                    context = frozenSpec.macroContext,
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

class TavernPlugin(private val repository: TavernPersonaRepository) : LoyeaPlugin {
    override val descriptor: PluginDescriptor = TavernPluginDefinition.DESCRIPTOR

    override fun createRuntime(generation: PluginRuntimeGeneration): PersonaPluginRuntime {
        require(generation.pluginId == descriptor.id) {
            "Tavern runtime generation belongs to ${generation.pluginId}, expected ${descriptor.id}"
        }
        return TavernPluginRuntime(repository, generation)
    }
}

private class TavernPluginRuntime(
    private val repository: TavernPersonaRepository,
    override val generation: PluginRuntimeGeneration
) : PersonaPluginRuntime {
    private val closed = AtomicBoolean(false)

    override val descriptor: PluginDescriptor = TavernPluginDefinition.DESCRIPTOR
    override val providerId: PluginId = TavernPluginDefinition.ID

    override suspend fun resolve(ref: PersonaRef): PersonaProjection? {
        checkOpen()
        if (ref.ownerId != providerId) return null
        val projection = repository.resolve(ref.personaId) ?: return null
        if (projection.ref.ownerId != providerId || projection.ref.personaId != ref.personaId) {
            return null
        }
        return projection
    }

    override suspend fun prepareTurn(
        ref: PersonaRef,
        input: PluginTurnInput,
        restoredSnapshot: String?
    ): PreparedPersonaTurn {
        checkOpen()
        if (ref.ownerId != providerId) throw TavernPersonaUnavailableException(ref)
        val spec = repository.prepareTurn(ref.personaId, input, restoredSnapshot, generation)
            ?: throw TavernPersonaUnavailableException(ref)
        return TavernPreparedTurnFactory.prepare(spec)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            repository.discardGeneration(generation)
        }
    }

    private fun checkOpen() {
        check(!closed.get()) { "Tavern plugin runtime generation is closed" }
    }
}
