package com.loyea.plugin.companion.runtime

/**
 * 陪伴运行账本存储契约（Spec 接入文档 §6.1）。
 *
 * SQLite 实现见 [SqliteCompanionStateStore]；JVM 测试用内存假实现复现
 * A03–A07 / A23–A28 的故障行为。实现必须：事务内 CAS、真实返回成败、
 * 不在网络/推理路径上持有事务。
 */
interface CompanionStateStore {
    fun findOwnerState(ownerKey: String): OwnerStateRecord?

    fun findActiveOwner(characterId: String, sessionId: String): OwnerStateRecord?

    fun insertOwnerState(record: OwnerStateRecord): Boolean

    fun commitObservationTransaction(
        owner: OwnerStateRecord,
        expectedAcceptedSeq: Long,
        nextCheckpointJson: String,
        nextAcceptedSeq: Long,
        nextStateAppliedSeq: Long,
        interactionDelta: Long,
        observation: ObservationRecord,
        outbox: List<Triple<String, Int, com.google.gson.JsonObject>>? = null,
        pendingStateNoteJson: String? = null,
    ): Boolean

    fun markOwnerInactive(ownerKey: String, tombstone: Boolean): Boolean

    fun setActiveOwnerOnly(ownerKey: String): Boolean

    fun updateBindingRevision(ownerKey: String, bindingRevision: Long): Boolean

    fun deleteOwnersForSession(characterId: String, sessionId: String): Int

    fun deleteAllOwnersForCharacter(characterId: String): Int

    fun findObservation(ownerKey: String, observationId: String): ObservationRecord?

    /** 取某 turn 最新的观测（编辑回放定位用）。 */
    fun findLatestObservationForTurn(ownerKey: String, turnId: String): ObservationRecord?

    fun allObservations(ownerKey: String, limit: Int = 512): List<ObservationRecord>

    fun insertRequestView(record: RequestViewRecord): Boolean

    fun findRequestView(ownerKey: String, turnId: String, subRequestId: String, revision: Int): RequestViewRecord?

    fun latestRequestRevision(ownerKey: String, turnId: String, subRequestId: String): Int

    fun deleteRequestViewsForOwner(ownerKey: String): Int

    /** 全部请求视图（备份导出用）。 */
    fun allRequestViews(ownerKey: String, limit: Int = 256): List<RequestViewRecord>

    /** 删除观测截止 seq >= [seq] 的请求视图（编辑截断：后缀请求引用失效）。 */
    fun deleteRequestViewsFromSeq(ownerKey: String, seq: Long): Int

    fun pendingOutbox(ownerKey: String): List<ProjectionOp>

    fun markOutboxDone(id: Long): Boolean

    fun enqueueOutbox(
        ownerKey: String,
        messageId: String,
        revision: Int,
        payloadJson: String,
        opType: String = "upsert",
    ): Boolean

    fun deleteOutboxForOwner(ownerKey: String): Int

    fun insertLifecycleOp(
        kind: String,
        phase: String,
        oldOwnerKey: String?,
        newOwnerKey: String?,
        manifestJson: String,
        committed: Boolean = false,
    ): Long

    fun updateLifecyclePhase(id: Long, phase: String, committed: Boolean, verificationJson: String?): Boolean

    fun deleteObservationsForOwner(ownerKey: String): Int

    // ------------------------------------------------------------------
    // 检查点历史（编辑分支回放；A28）
    // ------------------------------------------------------------------

    /** 保存"该 seq 提交后"的完整检查点；seq=-1 为 owner 初始检查点。 */
    fun insertCheckpointHistory(ownerKey: String, seq: Long, checkpointJson: String): Boolean

    /** 取 seq <= [seq] 的最近检查点；无历史返回 null（调用方按 EDIT_BEFORE_RUNTIME_BASELINE 处理）。 */
    fun findCheckpointAtOrBefore(ownerKey: String, seq: Long): Pair<Long, String>?

    /** 删除 seq >= [seq] 的观测（编辑截断：废弃后缀的事实/去重键）。 */
    fun deleteObservationsFromSeq(ownerKey: String, seq: Long): Int

    /** 删除 seq >= [seq] 的检查点历史。 */
    fun deleteCheckpointHistoryFromSeq(ownerKey: String, seq: Long): Int

    /** 统计 owner 下 USER_INPUT 观测数（编辑回放后的计数校准）。 */
    fun countUserInputObservations(ownerKey: String): Long

    /**
     * 编辑回放：一次事务内 CAS 回退 owner 到 [checkpointSeq] 的检查点，并删除
     * seq >= [fromSeq] 的观测/检查点历史/请求引用；[newInteractionCount] 为回放后
     * 的权威用户交互计数。返回 false = CAS 失败或无行变化，调用方按冲突处理。
     */
    fun rebaseOwnerToCheckpoint(
        owner: OwnerStateRecord,
        expectedAcceptedSeq: Long,
        checkpointSeq: Long,
        checkpointJson: String,
        fromSeq: Long,
        newInteractionCount: Long,
    ): Boolean
}
