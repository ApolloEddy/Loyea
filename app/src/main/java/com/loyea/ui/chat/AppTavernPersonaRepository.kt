package com.loyea.ui.chat

import com.loyea.plugins.tavern.core.*

import android.content.Context
import com.loyea.plugin.api.PersonaRef
import com.loyea.plugin.api.PersonaProjection
import com.loyea.plugin.api.PluginRuntimeGeneration
import com.loyea.plugin.api.PluginTurnInput
import com.loyea.plugin.api.PreparedPersonaTurn
import com.loyea.plugin.host.PersonaRuntimeLease
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Application-scoped adapter between Android card storage/request assembly and Tavern core.
 * It owns no Activity or ViewModel reference; staged turns are bounded and single-consumption.
 */
class AppTavernPersonaRepository(
    private val loadCards: suspend () -> List<CharacterCard>,
    private val maxPendingTurns: Int = DEFAULT_MAX_PENDING_TURNS,
    private val maxPendingBytes: Long = DEFAULT_MAX_PENDING_BYTES,
    private val pendingTtlNanos: Long = DEFAULT_PENDING_TTL_NANOS,
    private val nanoTime: () -> Long = System::nanoTime
) : TavernPersonaRepository {
    private val pendingLock = Any()
    private val pendingTurns = linkedMapOf<PendingTurnKey, PendingTurn>()
    private var pendingBytes = 0L

    constructor(
        context: Context,
        maxPendingTurns: Int = DEFAULT_MAX_PENDING_TURNS,
        maxPendingBytes: Long = DEFAULT_MAX_PENDING_BYTES
    ) : this(
        loadCards = ioCardLoader(ChatStorageManager(context.applicationContext)),
        maxPendingTurns = maxPendingTurns,
        maxPendingBytes = maxPendingBytes
    )

    init {
        require(maxPendingTurns > 0) { "Tavern pending-turn capacity must be positive" }
        require(maxPendingBytes > 0L) { "Tavern pending-turn byte capacity must be positive" }
        require(pendingTtlNanos > 0L) { "Tavern pending-turn TTL must be positive" }
    }

    override suspend fun resolve(personaId: String): PersonaProjection? =
        loadCards().firstOrNull { card ->
            card.id == personaId && CharacterPersonaOwnership.refFor(card).ownerId == TavernPluginDefinition.ID
        }
            ?.let { card ->
                TavernCharacterCardAdapter.toProjection(
                    card,
                    PersonaRef.plugin(TavernPluginDefinition.ID, card.id)
                )
            }

    override suspend fun prepareTurn(
        personaId: String,
        input: PluginTurnInput,
        restoredSnapshot: String?,
        generation: PluginRuntimeGeneration
    ): TavernTurnSpec? = synchronized(pendingLock) {
        purgeExpiredLocked(nanoTime())
        removeLocked(PendingTurnKey(input.sessionId, personaId, input.turnId, generation))?.spec
    }

    override fun discardGeneration(generation: PluginRuntimeGeneration) {
        synchronized(pendingLock) {
            val keys = pendingTurns.keys.filter { it.generation == generation }
            keys.forEach(::removeLocked)
        }
    }

    fun clearPendingTurns() {
        synchronized(pendingLock) {
            pendingTurns.clear()
            pendingBytes = 0L
        }
    }

    fun stage(
        sessionId: String,
        personaId: String,
        requestId: String,
        generation: PluginRuntimeGeneration,
        spec: TavernTurnSpec
    ): StagedTavernTurn {
        require(sessionId.isNotBlank()) { "Tavern staged session id must not be blank" }
        require(personaId.isNotBlank()) { "Tavern staged persona id must not be blank" }
        require(requestId.isNotBlank()) { "Tavern staged request id must not be blank" }
        require(generation.pluginId == TavernPluginDefinition.ID) { "Tavern stage uses another plugin generation" }
        val key = PendingTurnKey(sessionId, personaId, requestId, generation)
        val entry = PendingTurn(spec, estimateBytes(spec), nanoTime())
        synchronized(pendingLock) {
            purgeExpiredLocked(entry.createdAtNanos)
            check(key !in pendingTurns) { "Tavern turn is already staged for this request" }
            check(pendingTurns.size < maxPendingTurns) { "Tavern pending-turn capacity exceeded" }
            check(entry.byteSize <= maxPendingBytes - pendingBytes) {
                "Tavern pending-turn byte capacity exceeded"
            }
            pendingTurns[key] = entry
            pendingBytes += entry.byteSize
        }
        return StagedTavernTurn {
            synchronized(pendingLock) {
                if (pendingTurns[key] === entry) removeLocked(key)
            }
        }
    }

    suspend fun prepareStagedTurn(
        lease: PersonaRuntimeLease,
        ref: PersonaRef,
        input: PluginTurnInput,
        spec: TavernTurnSpec
    ): PreparedPersonaTurn {
        require(normalizeGenerationType(spec.generationType) == normalizeGenerationType(input.generationType)) {
            "Tavern staged turn generation type does not match plugin input"
        }
        val staged = stage(
            sessionId = input.sessionId,
            personaId = ref.personaId,
            requestId = input.turnId,
            generation = lease.generation,
            spec = spec
        )
        return try {
            lease.preparePersonaTurn(ref, input, restoredSnapshot = null)
        } finally {
            // Successful prepare consumes the stage; every failure path discards it here.
            staged.close()
        }
    }

    internal fun pendingTurnCountForTest(): Int = synchronized(pendingLock) {
        purgeExpiredLocked(nanoTime())
        pendingTurns.size
    }

    class StagedTavernTurn internal constructor(
        discard: () -> Unit
    ) : AutoCloseable {
        private val discard = AtomicReference<(() -> Unit)?>(discard)

        override fun close() {
            discard.getAndSet(null)?.invoke()
        }
    }

    private fun purgeExpiredLocked(nowNanos: Long) {
        val expired = pendingTurns
            .filterValues { entry -> nowNanos - entry.createdAtNanos >= pendingTtlNanos }
            .keys
            .toList()
        expired.forEach(::removeLocked)
    }

    private fun removeLocked(key: PendingTurnKey): PendingTurn? = pendingTurns.remove(key)?.also {
        pendingBytes = (pendingBytes - it.byteSize).coerceAtLeast(0L)
    }

    private fun estimateBytes(spec: TavernTurnSpec): Long {
        fun String?.bytes(): Long = this?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
        var total = spec.prompt.stablePersonaText.bytes() +
            spec.prompt.turnContextText.bytes() +
            spec.prompt.postHistoryText.bytes() +
            spec.opaqueSnapshot.bytes() +
            spec.macroContext.characterName.bytes() +
            spec.macroContext.description.bytes() +
            spec.macroContext.userName.bytes() +
            spec.generation.modelHint.bytes()
        total += spec.generation.stopStrings.sumOf { it.bytes() }
        total += spec.presetMessages.sumOf { prompt ->
            prompt.name.bytes() + prompt.identifier.bytes() + prompt.content.bytes() +
                prompt.role.bytes() + prompt.rawJson.bytes()
        }
        total += spec.worldInfoAtDepth.values.flatten().sumOf { block ->
            block.content.bytes() + block.role.bytes()
        }
        total += spec.regexScripts.sumOf { script ->
            script.id.bytes() + script.scriptName.bytes() + script.findRegex.bytes() +
                script.replaceString.bytes() + script.rawJson.bytes() +
                script.trimStrings.sumOf { it.bytes() }
        }
        return total.coerceAtLeast(1L)
    }

    private fun normalizeGenerationType(value: String): String =
        value.trim().removePrefix(":").lowercase()

    private data class PendingTurnKey(
        val sessionId: String,
        val personaId: String,
        val requestId: String,
        val generation: PluginRuntimeGeneration
    )

    private class PendingTurn(
        val spec: TavernTurnSpec,
        val byteSize: Long,
        val createdAtNanos: Long
    )

    companion object {
        private const val DEFAULT_MAX_PENDING_TURNS = 64
        private const val DEFAULT_MAX_PENDING_BYTES = 16L * 1024L * 1024L
        private const val DEFAULT_PENDING_TTL_NANOS = 60L * 1_000_000_000L

        private fun ioCardLoader(storage: ChatStorageManager): suspend () -> List<CharacterCard> = {
            withContext(Dispatchers.IO) { storage.loadCharacterCards() }
        }
    }
}
