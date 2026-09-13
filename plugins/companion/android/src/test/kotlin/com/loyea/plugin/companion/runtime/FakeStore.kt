package com.loyea.plugin.companion.runtime

import com.google.gson.JsonObject

/**
 * 内存版陪伴账本（JVM 测试用）：实现与 SQLite 相同的事务语义——
 * CAS 失败返回 false、不产生部分提交；可注入提交失败以复现 A23。
 */
internal class FakeStore : CompanionStateStore {

    val owners = LinkedHashMap<String, OwnerStateRecord>()
    val obsTable = LinkedHashMap<String, ObservationRecord>()
    val requestViews = LinkedHashMap<String, RequestViewRecord>()
    val outboxTable = LinkedHashMap<Long, ProjectionOp>()
    val checkpoints = LinkedHashMap<String, Pair<Long, String>>() // ownerKey -> (seq, json) 简化：存列表
    val checkpointHistory = mutableListOf<Triple<String, Long, String>>()
    val lifecycleOps = mutableListOf<String>()
    var failNextCommit = false
    private var outboxId = 0L

    fun observations(ownerKey: String): List<ObservationRecord> =
        obsTable.values.filter { it.ownerKey == ownerKey }.sortedBy { it.seq }

    override fun findOwnerState(ownerKey: String): OwnerStateRecord? = owners[ownerKey]

    override fun findActiveOwner(characterId: String, sessionId: String): OwnerStateRecord? =
        owners.values
            .filter { it.ownerKey.startsWith("$characterId|$sessionId|") && it.active && !it.tombstoned }
            .singleOrNull()

    override fun insertOwnerState(record: OwnerStateRecord): Boolean {
        if (owners.containsKey(record.ownerKey)) return false
        owners[record.ownerKey] = record
        return true
    }

    override fun commitObservationTransaction(
        owner: OwnerStateRecord,
        expectedAcceptedSeq: Long,
        nextCheckpointJson: String,
        nextAcceptedSeq: Long,
        nextStateAppliedSeq: Long,
        interactionDelta: Long,
        observation: ObservationRecord,
        outbox: List<Triple<String, Int, JsonObject>>?,
        pendingStateNoteJson: String?,
    ): Boolean {
        val current = owners[owner.ownerKey] ?: return false
        if (current.acceptedSeq != expectedAcceptedSeq || !current.active || current.tombstoned) return false
        if (failNextCommit) return false
        if (obsTable.containsKey(key(owner.ownerKey, observation.observationId))) return false
        obsTable.values.firstOrNull {
            it.ownerKey == owner.ownerKey && it.turnId == observation.turnId && it.inputHash == observation.inputHash
        }?.let { return false }
        owners[owner.ownerKey] = current.copy(
            acceptedSeq = nextAcceptedSeq,
            stateAppliedSeq = nextStateAppliedSeq,
            checkpointJson = nextCheckpointJson,
            interactionCount = current.interactionCount + interactionDelta,
            pendingStateNoteJson = pendingStateNoteJson,
        )
        obsTable[key(owner.ownerKey, observation.observationId)] = observation
        for ((messageId, revision, payload) in outbox ?: emptyList()) {
            outboxId += 1
            outboxTable[outboxId] = ProjectionOp(outboxId, owner.ownerKey, messageId, revision, "upsert", payload.toString(), 0, 0L)
        }
        return true
    }

    override fun markOwnerInactive(ownerKey: String, tombstone: Boolean): Boolean {
        val current = owners[ownerKey] ?: return false
        owners[ownerKey] = current.copy(active = false, tombstoned = tombstone || current.tombstoned)
        return true
    }

    override fun setActiveOwnerOnly(ownerKey: String): Boolean {
        val current = owners[ownerKey] ?: return false
        owners[ownerKey] = current.copy(active = true)
        return true
    }

    override fun updateBindingRevision(ownerKey: String, bindingRevision: Long): Boolean {
        val current = owners[ownerKey] ?: return false
        owners[ownerKey] = current.copy(bindingRevision = bindingRevision)
        return true
    }

    override fun deleteOwnersForSession(characterId: String, sessionId: String): Int {
        val keys = owners.keys.filter { it.startsWith("$characterId|$sessionId|") }
        keys.forEach { owners.remove(it) }
        return keys.size
    }

    override fun deleteAllOwnersForCharacter(characterId: String): Int {
        val keys = owners.keys.filter { it.startsWith("$characterId|") }
        keys.forEach { owners.remove(it) }
        return keys.size
    }

    override fun findObservation(ownerKey: String, observationId: String): ObservationRecord? =
        obsTable[key(ownerKey, observationId)]

    override fun findLatestObservationForTurn(ownerKey: String, turnId: String): ObservationRecord? =
        obsTable.values.filter { it.ownerKey == ownerKey && it.turnId == turnId }.maxByOrNull { it.seq }

    override fun allObservations(ownerKey: String, limit: Int): List<ObservationRecord> =
        observations(ownerKey).take(limit)

    override fun insertRequestView(record: RequestViewRecord): Boolean {
        requestViews["${record.ownerKey}|${record.turnId}|${record.subRequestId}|${record.requestRevision}"] = record
        return true
    }

    override fun findRequestView(ownerKey: String, turnId: String, subRequestId: String, revision: Int): RequestViewRecord? =
        requestViews["$ownerKey|$turnId|$subRequestId|$revision"]

    override fun latestRequestRevision(ownerKey: String, turnId: String, subRequestId: String): Int =
        requestViews.values
            .filter { it.ownerKey == ownerKey && it.turnId == turnId && it.subRequestId == subRequestId }
            .maxOfOrNull { it.requestRevision } ?: 0

    override fun deleteRequestViewsForOwner(ownerKey: String): Int {
        val keys = requestViews.keys.filter { it.startsWith("$ownerKey|") }
        keys.forEach { requestViews.remove(it) }
        return keys.size
    }

    override fun deleteRequestViewsFromSeq(ownerKey: String, seq: Long): Int {
        val keys = requestViews.values
            .filter { it.ownerKey == ownerKey && it.observationCutoffSeq >= seq }
            .map { "${it.ownerKey}|${it.turnId}|${it.subRequestId}|${it.requestRevision}" }
        keys.forEach { requestViews.remove(it) }
        return keys.size
    }

    override fun pendingOutbox(ownerKey: String): List<ProjectionOp> =
        outboxTable.values.filter { it.ownerKey == ownerKey && it.done == 0 }.sortedBy { it.id }

    override fun markOutboxDone(id: Long): Boolean {
        val op = outboxTable[id] ?: return false
        outboxTable[id] = op.copy(done = 1)
        return true
    }

    override fun enqueueOutbox(ownerKey: String, messageId: String, revision: Int, payloadJson: String, opType: String): Boolean {
        outboxId += 1
        outboxTable[outboxId] = ProjectionOp(outboxId, ownerKey, messageId, revision, opType, payloadJson, 0, 0L)
        return true
    }

    override fun deleteOutboxForOwner(ownerKey: String): Int {
        val ids = outboxTable.values.filter { it.ownerKey == ownerKey }.map { it.id }
        ids.forEach { outboxTable.remove(it) }
        return ids.size
    }

    override fun insertLifecycleOp(
        kind: String,
        phase: String,
        oldOwnerKey: String?,
        newOwnerKey: String?,
        manifestJson: String,
        committed: Boolean,
    ): Long {
        lifecycleOps += "$kind/$phase/${manifestJson.take(24)}"
        return lifecycleOps.size.toLong()
    }

    override fun updateLifecyclePhase(id: Long, phase: String, committed: Boolean, verificationJson: String?): Boolean = true

    override fun deleteObservationsForOwner(ownerKey: String): Int {
        val keys = obsTable.keys.filter { it.startsWith("$ownerKey|") }
        keys.forEach { obsTable.remove(it) }
        return keys.size
    }

    override fun insertCheckpointHistory(ownerKey: String, seq: Long, checkpointJson: String): Boolean {
        checkpointHistory.removeAll { it.first == ownerKey && it.second == seq }
        checkpointHistory += Triple(ownerKey, seq, checkpointJson)
        return true
    }

    override fun findCheckpointAtOrBefore(ownerKey: String, seq: Long): Pair<Long, String>? =
        checkpointHistory.filter { it.first == ownerKey && it.second <= seq }
            .maxByOrNull { it.second }
            ?.let { it.second to it.third }

    override fun deleteObservationsFromSeq(ownerKey: String, seq: Long): Int {
        val keys = obsTable.values.filter { it.ownerKey == ownerKey && it.seq >= seq }.map { key(ownerKey, it.observationId) }
        keys.forEach { obsTable.remove(it) }
        return keys.size
    }

    override fun deleteCheckpointHistoryFromSeq(ownerKey: String, seq: Long): Int {
        val removed = checkpointHistory.filter { it.first == ownerKey && it.second >= seq }.size
        checkpointHistory.removeAll { it.first == ownerKey && it.second >= seq }
        return removed
    }

    override fun countUserInputObservations(ownerKey: String): Long =
        obsTable.values.count { it.ownerKey == ownerKey && it.kind == ObservationKind.USER_INPUT }.toLong()

    override fun rebaseOwnerToCheckpoint(
        owner: OwnerStateRecord,
        expectedAcceptedSeq: Long,
        checkpointSeq: Long,
        checkpointJson: String,
        fromSeq: Long,
        newInteractionCount: Long,
    ): Boolean {
        val current = owners[owner.ownerKey] ?: return false
        if (current.acceptedSeq != expectedAcceptedSeq || !current.active || current.tombstoned) return false
        if (failNextCommit) return false
        owners[owner.ownerKey] = current.copy(
            acceptedSeq = checkpointSeq,
            stateAppliedSeq = checkpointSeq,
            checkpointJson = checkpointJson,
            interactionCount = newInteractionCount,
            pendingStateNoteJson = "edit_rebase",
        )
        deleteObservationsFromSeq(owner.ownerKey, fromSeq)
        deleteCheckpointHistoryFromSeq(owner.ownerKey, fromSeq)
        deleteRequestViewsFromSeq(owner.ownerKey, fromSeq)
        return true
    }

    private fun key(ownerKey: String, observationId: String) = "$ownerKey|$observationId"
}
