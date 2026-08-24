package com.loyea.perception.memory

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.loyea.ui.chat.PersonaBindingSnapshot
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Persona-namespaced, local graph-memory storage. */
class GraphMemoryManager(private val context: Context) {
    private val gson = Gson()
    private val memoriesFile = File(context.filesDir, "graph_memories.json")
    private val personaMigrationBackupFile =
        File(context.filesDir, "graph_memories.pre_persona_binding_v1.json")

    companion object {
        private val fileMutex = Mutex()
        private var lastPurgeTime = 0L
        private const val PURGE_THROTTLE_MS = 24L * 3600 * 1000L
        private const val PURGE_EXPIRE_MS = 90L * 24 * 3600 * 1000L
    }

    private fun loadTriplesLocked(): List<MemoryTriple> {
        if (!memoriesFile.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<MemoryTriple>>() {}.type
            val raw = gson.fromJson<List<MemoryTriple>>(memoriesFile.readText(), type) ?: emptyList()
            raw.map { triple ->
                MemoryTriple(
                    id = triple.id,
                    characterId = triple.characterId ?: "",
                    sessionId = triple.sessionId ?: "",
                    personaOwnerId = triple.personaOwnerId ?: "",
                    sessionIncarnationId = triple.sessionIncarnationId ?: "",
                    personaBindingRevision = triple.personaBindingRevision,
                    subject = triple.subject ?: "",
                    predicate = triple.predicate ?: "",
                    `object` = triple.`object` ?: "",
                    creationTime = triple.creationTime,
                    lastMentionedTime = triple.lastMentionedTime,
                    mentionCount = triple.mentionCount.coerceAtLeast(1),
                    baseWeight = triple.baseWeight.takeIf(Float::isFinite) ?: 1.0f
                )
            }
        } catch (failure: Exception) {
            failure.printStackTrace()
            backupCorruptFile(memoriesFile)
            emptyList()
        }
    }

    private fun saveTriplesLocked(triples: List<MemoryTriple>) {
        atomicWrite(memoriesFile, gson.toJson(triples))
    }

    private fun purgeExpiredLocked(triples: List<MemoryTriple>, now: Long): List<MemoryTriple> {
        if (now - lastPurgeTime < PURGE_THROTTLE_MS) return triples
        lastPurgeTime = now
        val filtered = triples.filter { it.lastMentionedTime >= now - PURGE_EXPIRE_MS }
        if (filtered.size != triples.size) saveTriplesLocked(filtered)
        return filtered
    }

    /**
     * Legacy triples had only session + character ids. They are attached only when both match the
     * currently persisted binding; otherwise they remain quarantined and invisible.
     */
    private fun migrateLegacyForBindingLocked(
        triples: List<MemoryTriple>,
        binding: PersonaBindingSnapshot
    ): List<MemoryTriple> {
        var changed = false
        val migrated = triples.map { triple ->
            if (triple.isLegacyIdentity() &&
                triple.sessionId == binding.sessionId &&
                triple.characterId == binding.ref.personaId
            ) {
                changed = true
                triple.copy(
                    personaOwnerId = binding.ref.ownerId.value,
                    sessionIncarnationId = binding.sessionIncarnationId,
                    personaBindingRevision = binding.personaBindingRevision
                )
            } else {
                triple
            }
        }
        if (changed) {
            if (!personaMigrationBackupFile.exists() && memoriesFile.exists()) {
                atomicWrite(personaMigrationBackupFile, memoriesFile.readText())
            }
            saveTriplesLocked(migrated)
        }
        return migrated
    }

    suspend fun purgeExpiredIfNeeded() {
        fileMutex.withLock {
            purgeExpiredLocked(loadTriplesLocked(), System.currentTimeMillis())
        }
    }

    /** Whole-batch read/modify/write under one mutex, avoiding lost updates between triples. */
    suspend fun upsertTriples(
        binding: PersonaBindingSnapshot,
        drafts: List<MemoryTripleDraft>
    ) {
        if (drafts.isEmpty()) return
        fileMutex.withLock {
            val now = System.currentTimeMillis()
            val current = migrateLegacyForBindingLocked(
                purgeExpiredLocked(loadTriplesLocked(), now),
                binding
            ).toMutableList()
            drafts.forEach { draft ->
                require(draft.subject.isNotBlank() && draft.predicate.isNotBlank() && draft.`object`.isNotBlank()) {
                    "Graph memory fields must not be blank"
                }
                val index = current.indexOfFirst { triple ->
                    triple.belongsTo(binding) &&
                        triple.subject.equals(draft.subject, ignoreCase = true) &&
                        triple.predicate.equals(draft.predicate, ignoreCase = true) &&
                        triple.`object`.equals(draft.`object`, ignoreCase = true)
                }
                if (index >= 0) {
                    val existing = current[index]
                    current[index] = existing.copy(
                        lastMentionedTime = now,
                        mentionCount = existing.mentionCount + 1
                    )
                } else {
                    val nextId = (current.maxOfOrNull(MemoryTriple::id) ?: 0L) + 1L
                    current += MemoryTriple(
                        id = nextId,
                        characterId = binding.ref.personaId,
                        sessionId = binding.sessionId,
                        personaOwnerId = binding.ref.ownerId.value,
                        sessionIncarnationId = binding.sessionIncarnationId,
                        personaBindingRevision = binding.personaBindingRevision,
                        subject = draft.subject,
                        predicate = draft.predicate,
                        `object` = draft.`object`,
                        creationTime = now,
                        lastMentionedTime = now
                    )
                }
            }
            saveTriplesLocked(current)
        }
    }

    suspend fun upsertTriple(
        binding: PersonaBindingSnapshot,
        subject: String,
        predicate: String,
        `object`: String
    ) = upsertTriples(binding, listOf(MemoryTripleDraft(subject, predicate, `object`)))

    suspend fun getTriplesForSession(binding: PersonaBindingSnapshot): List<MemoryTriple> =
        fileMutex.withLock {
            migrateLegacyForBindingLocked(loadTriplesLocked(), binding).filter { it.belongsTo(binding) }
        }

    suspend fun deleteTriple(id: Long, binding: PersonaBindingSnapshot): Boolean = fileMutex.withLock {
        val current = migrateLegacyForBindingLocked(loadTriplesLocked(), binding)
        val filtered = current.filterNot { it.id == id && it.belongsTo(binding) }
        if (filtered.size == current.size) return@withLock false
        saveTriplesLocked(filtered)
        true
    }

    suspend fun retrieveRelationalContext(
        binding: PersonaBindingSnapshot,
        userInput: String
    ): String = fileMutex.withLock {
        val now = System.currentTimeMillis()
        val allTriples = migrateLegacyForBindingLocked(
            purgeExpiredLocked(loadTriplesLocked(), now),
            binding
        )
        val sessionTriples = allTriples.filter { it.belongsTo(binding) }
        if (sessionTriples.isEmpty()) return@withLock ""

        val matchedEntities = mutableSetOf<String>()
        sessionTriples.flatMap { listOf(it.subject, it.`object`) }.distinct().forEach { entity ->
            if (entity.length >= 2 && userInput.contains(entity, ignoreCase = true)) {
                matchedEntities += entity
            }
        }
        if (matchedEntities.isEmpty()) return@withLock ""

        val candidates = mutableMapOf<MemoryTriple, Float>()
        matchedEntities.forEach { entity ->
            sessionTriples.filter { it.subject == entity || it.`object` == entity }.forEach { firstHop ->
                candidates[firstHop] = maxOf(candidates[firstHop] ?: 0f, firstHop.getCalculatedWeight(now))
                val nextEntity = if (firstHop.subject == entity) firstHop.`object` else firstHop.subject
                sessionTriples.filter {
                    (it.subject == nextEntity || it.`object` == nextEntity) && it != firstHop
                }.forEach { secondHop ->
                    candidates[secondHop] = maxOf(
                        candidates[secondHop] ?: 0f,
                        secondHop.getCalculatedWeight(now) * 0.4f
                    )
                }
            }
        }
        val selected = candidates.entries.sortedByDescending(Map.Entry<MemoryTriple, Float>::value)
            .take(8)
            .map(Map.Entry<MemoryTriple, Float>::key)
        if (selected.isEmpty()) return@withLock ""
        buildString {
            append("[Recall Memory:\n")
            selected.forEach { append("- Relationship: ${it.subject} -> ${it.predicate} -> ${it.`object`}\n") }
            append("]")
        }
    }

    suspend fun deleteExpiredMemories(binding: PersonaBindingSnapshot, expireTime: Long) {
        fileMutex.withLock {
            val current = migrateLegacyForBindingLocked(loadTriplesLocked(), binding)
            val filtered = current.filterNot { it.belongsTo(binding) && it.lastMentionedTime < expireTime }
            if (filtered.size != current.size) saveTriplesLocked(filtered)
        }
    }

    suspend fun clearMemoriesForBinding(binding: PersonaBindingSnapshot) {
        fileMutex.withLock {
            val current = loadTriplesLocked()
            val filtered = current.filterNot { triple ->
                triple.belongsTo(binding) ||
                    (triple.isLegacyIdentity() &&
                        triple.sessionId == binding.sessionId &&
                        triple.characterId == binding.ref.personaId)
            }
            if (filtered.size != current.size) saveTriplesLocked(filtered)
        }
    }

    private fun MemoryTriple.belongsTo(binding: PersonaBindingSnapshot): Boolean =
        sessionId == binding.sessionId &&
            characterId == binding.ref.personaId &&
            personaOwnerId == binding.ref.ownerId.value &&
            sessionIncarnationId == binding.sessionIncarnationId &&
            personaBindingRevision == binding.personaBindingRevision

    private fun MemoryTriple.isLegacyIdentity(): Boolean =
        personaOwnerId.isBlank() && sessionIncarnationId.isBlank() && personaBindingRevision <= 0L

    private fun atomicWrite(file: File, content: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun backupCorruptFile(file: File) {
        runCatching {
            if (file.exists()) file.renameTo(File(file.parentFile, "${file.name}.corrupt"))
        }
    }
}
