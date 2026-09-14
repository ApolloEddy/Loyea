package com.loyea.plugin.companion.runtime

import android.content.Context
import com.google.gson.JsonObject
import com.loyea.plugin.modulator.Context as ModulatorContext
import com.loyea.plugin.modulator.Fact
import com.loyea.plugin.modulator.Modulator
import com.loyea.plugin.modulator.ModulatorVocab
import com.loyea.plugin.modulator.Observation as ModulatorObservation
import com.loyea.plugin.modulator.StateProjection
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

/**
 * 陪伴运行协调器（Spec 接入文档 §4.1/§6）：单一入口。
 *
 * - 接纳互斥：连续点击发送只能接纳一次（覆盖 responseJob 建立前的窗口）。
 * - observation 唯一键 + inputHash：相同 ID 相同 hash 是重试（复用）；
 *   相同 ID 不同 hash 是协议冲突；不同 ID 相同正文是两次真实输入。
 * - 重试/重连/重生成不重新感知、不推进 seq、不加计数。
 * - 单次 SQLite 事务提交 Observation + 下一状态 + RequestView + ProjectionOutbox（CAS）。
 * - 进程内单例：旋转/进设置页/切生成模型共用同一份运行状态。
 */
internal class CompanionRuntimeCoordinator internal constructor(
    internal val store: CompanionStateStore,
    internal val clock: CompanionClock,
    internal val policyBook: CompanionPolicyBook,
) {

    @Volatile
    private var generation = 0L

    /** 感知实现；未挂接时文字感知按 NOT_READY 降级（不阻塞聊天）。 */
    @Volatile
    var textPerception: CompanionTextPerception? = null

    val admissionMutex = Mutex()

    // ------------------------------------------------------------------
    // Owner 生命周期
    // ------------------------------------------------------------------

    /**
     * 取得或建立 owner。existingIncarnation 由宿主从 ChatSession 读取；
     * 为空时宿主必须先分配并持久化 UUID（运行时不负责生成会话身份）。
     */
    fun ensureOwner(
        characterId: String,
        sessionId: String,
        incarnationId: String,
        bindingRevision: Long,
    ): OwnerStateRecord {
        val owner = CompanionOwner(characterId, sessionId, incarnationId).also { it.validate() }
        val existing = store.findActiveOwner(characterId, sessionId)
        if (existing != null) {
            require(existing.ownerKey == owner.storageKey()) {
                "incarnation mismatch: active owner ${existing.ownerKey} vs session incarnation ${owner.storageKey()}"
            }
            if (existing.bindingRevision != bindingRevision) {
                store.updateBindingRevision(existing.ownerKey, bindingRevision)
            }
            return existing.copy(bindingRevision = bindingRevision, active = true)
        }
        val fresh = initialOwnerState(owner, bindingRevision)
        check(store.insertOwnerState(fresh)) { "failed to create companion runtime owner" }
        // seq=-1 的初始检查点入历史：编辑回放能回到任何已接入的编辑点之前。
        store.insertCheckpointHistory(fresh.ownerKey, -1L, fresh.checkpointJson)
        return fresh
    }

    private fun initialOwnerState(owner: CompanionOwner, bindingRevision: Long): OwnerStateRecord {
        val now = clock.nowSeconds()
        val engine = Modulator(CompanionProfileRepository.personality(), now)
        return OwnerStateRecord(
            ownerKey = owner.storageKey(),
            active = true,
            tombstoned = false,
            bindingRevision = bindingRevision,
            acceptedSeq = -1L,
            stateAppliedSeq = -1L,
            algorithmVersion = ModulatorVocab.VERSION,
            checkpointSchemaVersion = ModulatorVocab.VERSION,
            personalityProfileVersion = CompanionProfileRepository.PERSONALITY_PROFILE_VERSION,
            loreProfileVersion = CompanionProfileRepository.LORE_PROFILE_VERSION,
            checkpointJson = engine.dumps(),
            clockAnchorJson = clockAnchorJson(),
            interactionCount = 0L,
            pendingStateNoteJson = null,
        )
    }

    private fun clockAnchorJson(): String =
        (clock as? SystemCompanionClock)?.anchorSnapshotJson()?.toString() ?: "{}"

    /** 模式关闭：撤销 lease（generation++），保留已提交状态与账本。 */
    fun closeCompanion() {
        generation++
    }

    /** 重开/校验 owner 后激活；不做重置。 */
    fun reopenOwner(record: OwnerStateRecord): OwnerStateRecord {
        store.setActiveOwnerOnly(record.ownerKey)
        return record.copy(active = true)
    }

    /**
     * "重新开始"：废弃旧 owner（tombstone），清理该陪伴的运行记忆/状态/去重/快照。
     * 聊天文件、普通会话与用户外观/开关设置由宿主 CompanionDataOps 清理。
     */
    fun resetCompanion(characterId: String, sessionId: String): Boolean {
        generation++
        val stale = store.findActiveOwner(characterId, sessionId) ?: return true
        val ok = store.markOwnerInactive(stale.ownerKey, tombstone = true)
        store.deleteObservationsForOwner(stale.ownerKey)
        store.deleteOutboxForOwner(stale.ownerKey)
        store.deleteRequestViewsForOwner(stale.ownerKey)
        return ok
    }

    fun currentGeneration(): Long = generation

    fun policySnapshot(physicalEnabled: Boolean, textEnabled: Boolean, memoryRevision: Long): CompanionPolicySnapshot =
        policyBook.current(physicalEnabled, textEnabled, memoryRevision)

    fun bumpPolicyRevision(): Long = policyBook.bump()

    // ------------------------------------------------------------------
    // 新用户输入的严格顺序（Spec §6.2）
    // ------------------------------------------------------------------

    /**
     * 接纳一次用户输入。调用方（ChatViewModel）已在主线程取得发送互斥；
     * 本方法内部的 admissionMutex 再覆盖"保存和感知尚未开始"的窗口。
     */
    suspend fun admitUserTurn(request: AdmitUserTurnRequest): AdmitOutcome = admissionMutex.withLock {
        val capturedGeneration = generation
        val owner = CompanionOwner(request.characterId, request.sessionId, request.incarnationId)
        val ownerKey = owner.storageKey()
        val state = store.findActiveOwner(request.characterId, request.sessionId)
            ?: return@withLock AdmitOutcome.Invalid("companion owner missing; reopen companion mode")
        if (state.ownerKey != ownerKey) return@withLock AdmitOutcome.Invalid("incarnation mismatch")
        if (state.tombstoned || !state.active) return@withLock AdmitOutcome.Invalid("owner inactive")

        val observationId = "obs:${request.messageId}:${request.inputRevision}"
        val canonical = canonicalInputJson(
            request.text, request.contextTurns, request.inputRevision, request.attachmentsHash,
        )
        val hash = sha256(canonical)

        // 步骤 2：查持久化 observation 唯一键。
        val existing = store.findObservation(ownerKey, observationId)
        if (existing != null) {
            return@withLock if (existing.inputHash == hash) {
                AdmitOutcome.Reused(existing, capturedGeneration)
            } else {
                AdmitOutcome.Conflict(
                    "same messageId admitted with different input (hash mismatch)",
                )
            }
        }

        val seq = state.acceptedSeq + 1
        val turnId = request.messageId

        // 步骤 2b：短锁内冻结感知上下文与配置引用，释放锁执行一次有界推理。
        val sensor = textPerception
        val policyAtFreeze = request.policy
        val outcome: PerceptionOutcome = if (!policyAtFreeze.textPerceptionEnabled) {
            PerceptionOutcome(SensorStatus.DISABLED, null)
        } else if (request.text.isBlank()) {
            PerceptionOutcome(SensorStatus.NO_TEXT, null)
        } else if (sensor == null) {
            PerceptionOutcome(SensorStatus.NOT_READY, null)
        } else {
            try {
                sensor.perceive(PerceptionRequest(request.text, request.contextTurns))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                PerceptionOutcome(SensorStatus.INVALID_OUTPUT, null)
            }
        }

        // 步骤 3：回到会话协调器重新验证 owner/generation/policy。
        val revalidated = store.findActiveOwner(request.characterId, request.sessionId)
        if (revalidated == null || revalidated.ownerKey != ownerKey || revalidated.acceptedSeq != state.acceptedSeq) {
            return@withLock AdmitOutcome.Invalid("owner changed during perception; turn discarded")
        }
        if (generation != capturedGeneration) {
            return@withLock AdmitOutcome.Invalid("companion mode changed during perception; turn discarded")
        }
        val sensorFinal = if (outcome.status == SensorStatus.READY_RESULT && !policyAtFreeze.textPerceptionEnabled) {
            // 感知来源在推理期间被关闭：该轮按缺失结果处理，不补算第二次。
            PerceptionOutcome(SensorStatus.DISABLED, null)
        } else {
            outcome
        }

        val isUserCounted = true // USER_INPUT 一律计数；重试不会走到这里（已有 Reused 分支）
        val now = clock.nowSeconds()

        // 步骤 4：正式引擎工作副本上推进。
        val perceptionForCore: JsonObject? = when {
            sensorFinal.status != SensorStatus.READY_RESULT -> null
            sensorFinal.waiveReason != null -> null // 归因弃权：原始解码入库，但不作为刺激
            else -> sensorFinal.perceptionJson
        }
        val facts = request.hostFacts
        val degradeReason = when (sensorFinal.status) {
            SensorStatus.READY_RESULT -> if (sensorFinal.waiveReason != null) "waived:${sensorFinal.waiveReason}" else null
            else -> sensorFinal.status.name
        }

        val engine = try {
            Modulator.loads(state.checkpointJson)
        } catch (corrupt: IllegalArgumentException) {
            return@withLock admitWithUnappliedState(
                state, request, ownerKey, observationId, turnId, seq, canonical, hash,
                sensorFinal, perceptionForCore, facts, now,
                note = "checkpoint_corrupt:${corrupt.message}",
            )
        }
        engine.idleTo(now)
        val modulatorObservation = ModulatorObservation(
            eventId = observationId,
            seq = seq,
            at = now,
            context = ModulatorContext(
                topicId = "session:${request.sessionId}",
                scene = "real",
                legitimateFeedback = request.legitimateFeedback,
                visibleEvidence = setOf(observationId) + facts.map { it.evidenceId },
                activeTags = request.activeTags,
                relations = CompanionProfileRepository.relationView(),
                explicitResolutionLinks = request.explicitResolutionLinks,
            ),
            perception = perceptionForCore,
            facts = facts,
        )
        val decision = try {
            engine.process(modulatorObservation)
        } catch (coreFailure: IllegalArgumentException) {
            // 核心异常 ≠ 正常感知失败：记录"已接纳但未应用状态"的显式账本标记。
            return@withLock admitWithUnappliedState(
                state, request, ownerKey, observationId, turnId, seq, canonical, hash,
                sensorFinal, perceptionForCore, facts, now,
                note = "core_rejected:${coreFailure.message}",
            )
        }

        val nextCheckpoint = engine.dumps()
        val committed = store.commitObservationTransaction(
            owner = revalidated.copy(active = true),
            expectedAcceptedSeq = state.acceptedSeq,
            nextCheckpointJson = nextCheckpoint,
            nextAcceptedSeq = seq,
            nextStateAppliedSeq = seq,
            interactionDelta = if (isUserCounted) 1 else 0,
            observation = ObservationRecord(
                observationId = observationId,
                ownerKey = ownerKey,
                inputHash = hash,
                turnId = turnId,
                kind = ObservationKind.USER_INPUT,
                seq = seq,
                canonicalInputJson = canonical,
                perceptionJson = sensorFinal.perceptionJson?.toString(),
                sensorStatus = sensorFinal.status,
                degradeReason = degradeReason,
                eligibilityJson = sensorFinal.waiveReason?.let { waiveJson(it) },
                factsJson = facts.takeIf { it.isNotEmpty() }?.let { factsJson(it) },
                createdAtWallMillis = clock.wallNowMillis(),
            ),
            outbox = listOf(Triple(request.messageId, 0, request.userMessageJson)),
        )
        if (!committed) {
            // CAS 失败/约束冲突：正式内存状态未动；调用方可按同 ID 重试。
            return@withLock AdmitOutcome.Conflict("commit failed; retry with same id")
        }
        store.insertCheckpointHistory(ownerKey, seq, nextCheckpoint)

        // 步骤 6：事务成功后幂等落下 JSON 用户消息。
        val projectionOk = request.projectionSink.upsertMessage(ownerKey, request.messageId, request.userMessageJson.toString())
        if (projectionOk) {
            store.pendingOutbox(ownerKey).firstOrNull { it.messageId == request.messageId }
                ?.let { store.markOutboxDone(it.id) }
        }
        AdmitOutcome.Accepted(
            observationId = observationId,
            turnId = turnId,
            seq = seq,
            rows = decision.rows,
            audit = decision.audit,
            sensorStatus = sensorFinal.status,
            degradeReason = degradeReason,
            interactionCounted = isUserCounted,
            projectionPending = !projectionOk,
            generation = capturedGeneration,
        )
    }

    /** 核心异常路径：acceptedSeq 推进、stateAppliedSeq 不动、显式 pending 标记（Spec §10.3）。 */
    private fun admitWithUnappliedState(
        state: OwnerStateRecord,
        request: AdmitUserTurnRequest,
        ownerKey: String,
        observationId: String,
        turnId: String,
        seq: Long,
        canonical: String,
        hash: String,
        sensor: PerceptionOutcome,
        perceptionForCore: JsonObject?,
        facts: List<Fact>,
        now: Double,
        note: String,
    ): AdmitOutcome {
        val degradeReason = when (sensor.status) {
            SensorStatus.READY_RESULT -> sensor.waiveReason?.let { "waived:$it" } ?: note
            else -> sensor.status.name
        }
        val committed = store.commitObservationTransaction(
            owner = state,
            expectedAcceptedSeq = state.acceptedSeq,
            nextCheckpointJson = state.checkpointJson, // 检查点保持最后应用状态
            nextAcceptedSeq = seq,
            nextStateAppliedSeq = state.stateAppliedSeq,
            interactionDelta = 1,
            observation = ObservationRecord(
                observationId = observationId,
                ownerKey = ownerKey,
                inputHash = hash,
                turnId = turnId,
                kind = ObservationKind.USER_INPUT,
                seq = seq,
                canonicalInputJson = canonical,
                perceptionJson = sensor.perceptionJson?.toString(),
                sensorStatus = sensor.status,
                degradeReason = degradeReason,
                eligibilityJson = null,
                factsJson = facts.takeIf { it.isNotEmpty() }?.let { factsJson(it) },
                createdAtWallMillis = clock.wallNowMillis(),
            ),
            outbox = listOf(Triple(request.messageId, 0, request.userMessageJson)),
            pendingStateNoteJson = pendingNoteJson(note, seq),
        )
        if (!committed) return AdmitOutcome.Conflict("commit failed; retry with same id")
        return AdmitOutcome.Accepted(
            observationId = observationId,
            turnId = turnId,
            seq = seq,
            rows = emptyList(),
            audit = listOf("state_not_applied:$note"),
            sensorStatus = sensor.status,
            degradeReason = degradeReason,
            interactionCounted = true,
            projectionPending = false,
            generation = generation,
        )
    }

    // ------------------------------------------------------------------
    // 编辑分支回放（Spec §6.3 / A28）
    // ------------------------------------------------------------------

    /**
     * 用户编辑历史输入并截断后文时调用：把 owner 回退到该输入被接纳前的检查点，
     * 废弃该输入及其后缀的全部观测、状态与请求引用。之后同 messageId 的新
     * inputRevision 作为新分支重新接纳（不重跑感知旧正文、不复用旧去重键）。
     */
    suspend fun rebaseToEditPoint(
        characterId: String,
        sessionId: String,
        incarnationId: String,
        messageId: String,
    ): EditRebaseResult = admissionMutex.withLock {
        generation++ // 编辑即打断进行中任务
        val ownerKey = CompanionOwner(characterId, sessionId, incarnationId).storageKey()
        val state = store.findActiveOwner(characterId, sessionId)
            ?: return@withLock EditRebaseResult.NoOwner
        if (state.ownerKey != ownerKey) return@withLock EditRebaseResult.NoOwner

        val latest = store.findLatestObservationForTurn(ownerKey, messageId)
            ?: return@withLock EditRebaseResult.BeforeRuntimeBaseline("turn never admitted at runtime")
        val targetSeq = latest.seq
        val before = store.findCheckpointAtOrBefore(ownerKey, targetSeq - 1)
            ?: return@withLock EditRebaseResult.BeforeRuntimeBaseline("no retained checkpoint before edit point")

        // 一次事务：CAS 回退 + 截断后缀观测/历史/请求引用 + 计数按有效谱系校准。
        val newCount = store.countUserInputObservations(ownerKey) -
            store.allObservations(ownerKey).count { it.seq >= targetSeq && it.kind == ObservationKind.USER_INPUT }
        val ok = store.rebaseOwnerToCheckpoint(
            owner = state,
            expectedAcceptedSeq = state.acceptedSeq,
            checkpointSeq = before.first,
            checkpointJson = before.second,
            fromSeq = targetSeq,
            newInteractionCount = newCount,
        )
        if (!ok) return@withLock EditRebaseResult.Failed("rebase commit failed")
        store.insertLifecycleOp(
            kind = "edit_rebase",
            phase = "committed",
            oldOwnerKey = ownerKey,
            newOwnerKey = ownerKey,
            manifestJson = JsonObject().apply {
                addProperty("message_id", messageId)
                addProperty("truncated_from_seq", targetSeq)
                addProperty("restored_seq", before.first)
            }.toString(),
            committed = true,
        )
        EditRebaseResult.Rebased(before.first)
    }

    // ------------------------------------------------------------------
    // 工具事实（Spec §5：仅 task_blocked；§6.3：首次可靠终态独立接纳）
    // ------------------------------------------------------------------

    /**
     * 接纳一次工具失败事实：独立 observationId（toolCallId+terminalRevision）、
     * 共用 turnId、推进 seq、不加交互计数。同一终态重复回调按 Reused 复用。
     */
    suspend fun admitToolFact(request: AdmitToolFactRequest): AdmitOutcome = admissionMutex.withLock {
        val ownerKey = CompanionOwner(request.characterId, request.sessionId, request.incarnationId).storageKey()
        val state = store.findActiveOwner(request.characterId, request.sessionId)
            ?: return@withLock AdmitOutcome.Invalid("companion owner missing")
        if (state.ownerKey != ownerKey) return@withLock AdmitOutcome.Invalid("incarnation mismatch")

        val observationId = "obs:tool:${request.toolCallId}:${request.terminalRevision}"
        store.findObservation(ownerKey, observationId)?.let {
            return@withLock AdmitOutcome.Reused(it, generation)
        }
        val seq = state.acceptedSeq + 1
        val now = clock.nowSeconds()
        val fact = Fact(
            kind = "task_blocked",
            strength = 0.45,
            evidenceId = observationId,
            confidence = 1.0,
            verified = true,
            source = "host",
            target = "event",
        )
        val engine = try {
            Modulator.loads(state.checkpointJson)
        } catch (corrupt: IllegalArgumentException) {
            return@withLock AdmitOutcome.Conflict("checkpoint corrupt: ${corrupt.message}")
        }
        engine.idleTo(now)
        val decision = try {
            engine.process(
                ModulatorObservation(
                    eventId = observationId,
                    seq = seq,
                    at = now,
                    context = ModulatorContext(
                        topicId = "session:${request.sessionId}",
                        scene = "real",
                        relations = CompanionProfileRepository.relationView(),
                        visibleEvidence = setOf(observationId),
                    ),
                    facts = listOf(fact),
                ),
            )
        } catch (coreFailure: IllegalArgumentException) {
            return@withLock AdmitOutcome.Conflict("core rejected tool fact: ${coreFailure.message}")
        }
        val nextCheckpoint = engine.dumps()
        val committed = store.commitObservationTransaction(
            owner = state,
            expectedAcceptedSeq = state.acceptedSeq,
            nextCheckpointJson = nextCheckpoint,
            nextAcceptedSeq = seq,
            nextStateAppliedSeq = seq,
            interactionDelta = 0,
            observation = ObservationRecord(
                observationId = observationId,
                ownerKey = ownerKey,
                inputHash = sha256("tool:${request.toolCallId}:${request.terminalRevision}"),
                turnId = request.turnId,
                kind = ObservationKind.TOOL_FACT,
                seq = seq,
                canonicalInputJson = JsonObject().apply {
                    addProperty("tool_call_id", request.toolCallId)
                    addProperty("terminal_revision", request.terminalRevision)
                    addProperty("fact", "task_blocked")
                }.toString(),
                perceptionJson = null,
                sensorStatus = SensorStatus.CANCELLED,
                degradeReason = null,
                eligibilityJson = null,
                factsJson = JsonObject().apply { addProperty("kinds", "task_blocked") }.toString(),
                createdAtWallMillis = clock.wallNowMillis(),
            ),
        )
        if (!committed) return@withLock AdmitOutcome.Conflict("commit failed; retry with same id")
        store.insertCheckpointHistory(ownerKey, seq, nextCheckpoint)
        AdmitOutcome.Accepted(
            observationId = observationId,
            turnId = request.turnId,
            seq = seq,
            rows = decision.rows,
            audit = decision.audit,
            sensorStatus = SensorStatus.CANCELLED,
            degradeReason = null,
            interactionCounted = false,
            projectionPending = false,
            generation = generation,
        )
    }

    /** 本轮已提交的 task_blocked 工具事实 → 模块渲染的 activeTags。 */
    fun activeTagsForTurn(characterId: String, sessionId: String, incarnationId: String, turnId: String): Set<String> {
        val ownerKey = CompanionOwner(characterId, sessionId, incarnationId).storageKey()
        val hasToolFact = store.allObservations(ownerKey).any {
            it.turnId == turnId && it.kind == ObservationKind.TOOL_FACT
        }
        return if (hasToolFact) setOf("host:task_blocked") else emptySet()
    }

    /** 保留消息 → 仍确实可见的证据（观测 id）集合（Spec §8.3 最终证据闭合）。 */
    fun visibleEvidenceIds(
        characterId: String,
        sessionId: String,
        incarnationId: String,
        retainedMessageIds: Set<String>,
    ): Set<String> {
        val ownerKey = CompanionOwner(characterId, sessionId, incarnationId).storageKey()
        return store.allObservations(ownerKey)
            .filter { it.turnId in retainedMessageIds }
            .map { it.observationId }
            .toSet()
    }

    /** 记录一次实际请求的投影（RequestView）。 */
    fun recordRequestView(
        characterId: String,
        sessionId: String,
        incarnationId: String,
        turnId: String,
        subRequestId: String,
        observationCutoffSeq: Long,
        module: CompanionPromptAdapter.RenderedModule,
        policyRevision: Long,
        memoryRevision: Long,
    ): Boolean {
        val ownerKey = CompanionOwner(characterId, sessionId, incarnationId).storageKey()
        val state = store.findOwnerState(ownerKey) ?: return false
        val revision = store.latestRequestRevision(ownerKey, turnId, subRequestId) + 1
        return store.insertRequestView(
            RequestViewRecord(
                ownerKey = ownerKey,
                turnId = turnId,
                subRequestId = subRequestId,
                requestRevision = revision,
                observationCutoffSeq = observationCutoffSeq,
                projectionJson = JsonObject().apply {
                    add("rows", com.google.gson.JsonArray().apply {
                        module.rows.forEach { r ->
                            add(JsonObject().apply {
                                addProperty("aspect", r.aspect)
                                addProperty("state", r.state)
                                addProperty("intensity", r.intensity)
                            })
                        }
                    })
                    add("sources", JsonObject().apply {
                        module.sources.forEach { (k, v) ->
                            add(k, com.google.gson.JsonArray().apply { v.forEach { add(it) } })
                        }
                    })
                }.toString(),
                loreJson = JsonObject().apply {
                    addProperty("book", CompanionPromptAdapter.PRIVATE_BOOK_ID)
                    addProperty("lore_version", state.loreProfileVersion)
                    add("selected", com.google.gson.JsonArray().apply { module.selectedLoreIds.forEach { add(it) } })
                }.toString(),
                sourcesJson = module.sources.keys.joinToString(","),
                policyRevision = policyRevision,
                memoryRevision = memoryRevision,
                budgetJson = JsonObject().apply {
                    addProperty("tokens", module.budgetTokens)
                    addProperty("estimated", module.budgetEstimated)
                }.toString(),
                payloadHash = sha256(module.text),
                createdAtWallMillis = System.currentTimeMillis(),
            ),
        )
    }

    fun acceptedSeqOf(characterId: String, sessionId: String, incarnationId: String): Long {
        val ownerKey = CompanionOwner(characterId, sessionId, incarnationId).storageKey()
        return store.findOwnerState(ownerKey)?.acceptedSeq ?: -1L
    }

    /**
     * debug 诊断快照（Spec §10.2）：owner、算法/检查点版本、最近观测的
     * observationId/seq/sensorStatus/降级原因、状态行、最近 RequestView 摘要。
     * 默认不含完整原文、位置或健康数据。
     */
    fun debugSnapshot(
        characterId: String,
        sessionId: String,
        incarnationId: String,
        sensorInfo: JsonObject? = null,
    ): JsonObject {
        val ownerKey = CompanionOwner(characterId, sessionId, incarnationId).storageKey()
        val state = store.findOwnerState(ownerKey)
        val root = JsonObject()
        root.addProperty("owner", ownerKey)
        root.addProperty("active", state?.active == true)
        root.addProperty("algorithm_version", state?.algorithmVersion)
        root.addProperty("checkpoint_schema", state?.checkpointSchemaVersion)
        root.addProperty("personality_profile", state?.personalityProfileVersion)
        root.addProperty("lore_profile", state?.loreProfileVersion)
        root.addProperty("accepted_seq", state?.acceptedSeq)
        root.addProperty("state_applied_seq", state?.stateAppliedSeq)
        root.addProperty("interaction_count", state?.interactionCount)
        root.addProperty("pending_state_note", state?.pendingStateNoteJson)
        if (sensorInfo != null) root.add("sensor", sensorInfo)
        val obs = com.google.gson.JsonArray()
        store.allObservations(ownerKey, limit = 12).reversed().forEach { o ->
            obs.add(JsonObject().apply {
                addProperty("observation_id", o.observationId)
                addProperty("seq", o.seq)
                addProperty("kind", o.kind.name)
                addProperty("sensor_status", o.sensorStatus.name)
                addProperty("degrade_reason", o.degradeReason)
                addProperty("input_hash", o.inputHash.take(12))
            })
        }
        root.add("recent_observations", obs)
        val checkpointRows = state?.let {
            try {
                val cp = com.google.gson.JsonParser.parseString(it.checkpointJson).asJsonObject
                cp.getAsJsonObject("last_decision")?.getAsJsonArray("rows")
            } catch (t: Throwable) {
                null
            }
        }
        if (checkpointRows != null) root.add("rows", checkpointRows)
        // 最近一次请求选中的私有 Lore（只读展示；编辑入口按 Spec 保持隐藏）
        val latestView = store.allRequestViews(ownerKey, limit = 1).firstOrNull()
        if (latestView != null) {
            try {
                val lore = com.google.gson.JsonParser.parseString(latestView.loreJson).asJsonObject
                root.add("last_selected_lore", lore)
                root.addProperty("last_request_revision", latestView.requestRevision)
            } catch (parse: Throwable) {
                root.addProperty("last_selected_lore", "unreadable")
            }
        }
        return root
    }

    // ------------------------------------------------------------------
    // 状态可视化（用户可见的只读快照；不推进任何状态）
    // ------------------------------------------------------------------

    /** 一次可见性过滤后的痕迹分量视图。 */
    data class TraceComponentView(val evidenceId: String, val strength: Double, val lastEventAt: Double)
    data class TraceView(val kind: String, val label: String, val strength: Double, val components: List<TraceComponentView>)
    data class StateView(
        val rows: List<com.loyea.plugin.modulator.Row>,
        val fast: List<Double>,
        val mood: List<Double>,
        val moodLabel: String,
        val traces: List<TraceView>,
        val checkpointAt: Double,
        val interactionCount: Long,
        val acceptedSeq: Long,
    )

    /**
     * 只读状态快照（可视化页用）。状态行取最近一次已提交决策（提交时已按
     * 当轮可见证据过滤）；连续量与痕迹来自检查点本体。坏检查点返回 null。
     */
    fun stateSnapshot(
        characterId: String,
        sessionId: String,
        incarnationId: String,
    ): StateView? {
        val ownerKey = CompanionOwner(characterId, sessionId, incarnationId).storageKey()
        val state = store.findOwnerState(ownerKey) ?: return null
        val engine = try {
            Modulator.loads(state.checkpointJson)
        } catch (corrupt: IllegalArgumentException) {
            return null
        }
        val snap = engine.state
        val traces = snap.traces.map { t ->
            TraceView(
                kind = t.kind,
                label = com.loyea.plugin.modulator.Rules.ALL[t.kind]?.let {
                    com.loyea.plugin.modulator.ModulatorVocab.STATE_ZH[it.label]
                } ?: t.kind,
                strength = t.strength,
                components = t.components.map { c ->
                    TraceComponentView(c.evidenceId, c.strength, c.lastEventAt)
                },
            )
        }
        val rows = engine.lastDecision?.rows ?: emptyList()
        return StateView(
            rows = rows,
            fast = snap.fast.toList(),
            mood = snap.mood.toList(),
            moodLabel = snap.moodLabel,
            traces = traces,
            checkpointAt = snap.at,
            interactionCount = state.interactionCount,
            acceptedSeq = state.acceptedSeq,
        )
    }

    // ------------------------------------------------------------------
    // 备份 v3：运行状态导出 / 导入（Spec §9.2）
    // ------------------------------------------------------------------

    /** 恢复成功但运行账本未导入（v1/v2 或检查点不符）时，显式登记新短期基线事件。 */
    fun recordRuntimeBaselineNote(characterId: String, sessionId: String, incarnationId: String) {
        store.insertLifecycleOp(
            kind = "runtime_baseline_reset",
            phase = "committed",
            oldOwnerKey = null,
            newOwnerKey = CompanionOwner(characterId, sessionId, incarnationId).storageKey(),
            manifestJson = JsonObject().apply {
                addProperty("reason", "runtime_state_not_imported")
            }.toString(),
            committed = true,
        )
    }

    /** 导出 owner 的完整运行账本（owner 行 + 观测）；无 owner 返回 null。 */
    fun exportRuntimeState(characterId: String, sessionId: String, incarnationId: String): JsonObject? {
        val ownerKey = CompanionOwner(characterId, sessionId, incarnationId).storageKey()
        val state = store.findOwnerState(ownerKey) ?: return null
        return JsonObject().apply {
            add("owner", JsonObject().apply {
                addProperty("binding_revision", state.bindingRevision)
                addProperty("accepted_seq", state.acceptedSeq)
                addProperty("state_applied_seq", state.stateAppliedSeq)
                addProperty("algorithm_version", state.algorithmVersion)
                addProperty("checkpoint_schema_version", state.checkpointSchemaVersion)
                addProperty("personality_profile_version", state.personalityProfileVersion)
                addProperty("lore_profile_version", state.loreProfileVersion)
                addProperty("checkpoint_json", state.checkpointJson)
                addProperty("clock_anchor_json", state.clockAnchorJson)
                addProperty("interaction_count", state.interactionCount)
                addProperty("pending_state_note_json", state.pendingStateNoteJson)
            })
            add("observations", com.google.gson.JsonArray().apply {
                store.allObservations(ownerKey, limit = 4096).forEach { o ->
                    add(JsonObject().apply {
                        addProperty("observation_id", o.observationId)
                        addProperty("input_hash", o.inputHash)
                        addProperty("turn_id", o.turnId)
                        addProperty("kind", o.kind.name)
                        addProperty("seq", o.seq)
                        addProperty("canonical_input_json", o.canonicalInputJson)
                        addProperty("perception_json", o.perceptionJson)
                        addProperty("sensor_status", o.sensorStatus.name)
                        addProperty("degrade_reason", o.degradeReason)
                        addProperty("eligibility_json", o.eligibilityJson)
                        addProperty("facts_json", o.factsJson)
                        addProperty("created_at_wall_millis", o.createdAtWallMillis)
                    })
                }
            })
            add("request_views", com.google.gson.JsonArray().apply {
                store.allRequestViews(ownerKey, limit = 256).forEach { r ->
                    add(JsonObject().apply {
                        addProperty("turn_id", r.turnId)
                        addProperty("sub_request_id", r.subRequestId)
                        addProperty("request_revision", r.requestRevision)
                        addProperty("observation_cutoff_seq", r.observationCutoffSeq)
                        addProperty("projection_json", r.projectionJson)
                        addProperty("lore_json", r.loreJson)
                        addProperty("sources_json", r.sourcesJson)
                        addProperty("policy_revision", r.policyRevision)
                        addProperty("memory_revision", r.memoryRevision)
                        addProperty("budget_json", r.budgetJson)
                        addProperty("payload_hash", r.payloadHash)
                        addProperty("created_at_wall_millis", r.createdAtWallMillis)
                    })
                }
            })
            addProperty("model_sha256", com.loyea.plugin.companion.perception.LegacyEmotionSensor.EXPECTED_MODEL_SHA256)
        }
    }

    /**
     * 恢复运行账本到新 owner 命名空间（Spec §9.2 单一激活协议的运行时部分）。
     * 检查点必须能被当前核心 loads 校验，否则整体按新短期基线处理（返回 false，
     * 调用方继续恢复流程并记录 RUNTIME_BASELINE_RESET）；部分写入不会发生。
     */
    fun importRuntimeState(
        characterId: String,
        newSessionId: String,
        newIncarnationId: String,
        bindingRevision: Long,
        runtime: JsonObject,
    ): Boolean {
        val ownerJson = runtime.getAsJsonObject("owner") ?: return false
        val checkpointJson = ownerJson.get("checkpoint_json")?.takeIf { it.isJsonPrimitive }?.asString ?: return false
        // 严格校验检查点（版本/边界/决策一致性）；坏检查点绝不部分载入。
        try {
            Modulator.loads(checkpointJson)
        } catch (corrupt: IllegalArgumentException) {
            return false
        }
        val newOwnerKey = CompanionOwner(characterId, newSessionId, newIncarnationId).storageKey()
        val record = OwnerStateRecord(
            ownerKey = newOwnerKey,
            active = true,
            tombstoned = false,
            bindingRevision = bindingRevision,
            acceptedSeq = ownerJson.get("accepted_seq")?.takeIf { it.isJsonPrimitive }?.asLong ?: -1L,
            stateAppliedSeq = ownerJson.get("state_applied_seq")?.takeIf { it.isJsonPrimitive }?.asLong ?: -1L,
            algorithmVersion = ownerJson.get("algorithm_version")?.takeIf { it.isJsonPrimitive }?.asString ?: ModulatorVocab.VERSION,
            checkpointSchemaVersion = ownerJson.get("checkpoint_schema_version")?.takeIf { it.isJsonPrimitive }?.asString ?: ModulatorVocab.VERSION,
            personalityProfileVersion = ownerJson.get("personality_profile_version")?.takeIf { it.isJsonPrimitive }?.asString
                ?: CompanionProfileRepository.PERSONALITY_PROFILE_VERSION,
            loreProfileVersion = ownerJson.get("lore_profile_version")?.takeIf { it.isJsonPrimitive }?.asString
                ?: CompanionProfileRepository.LORE_PROFILE_VERSION,
            checkpointJson = checkpointJson,
            clockAnchorJson = ownerJson.get("clock_anchor_json")?.takeIf { it.isJsonPrimitive }?.asString ?: "{}",
            interactionCount = ownerJson.get("interaction_count")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
            pendingStateNoteJson = ownerJson.get("pending_state_note_json")?.takeIf { it.isJsonPrimitive }?.asString,
        )
        if (!store.insertOwnerState(record)) return false
        store.insertCheckpointHistory(newOwnerKey, record.acceptedSeq, checkpointJson)
        var imported = 0
        val observations = runtime.getAsJsonArray("observations")
        for (el in observations ?: com.google.gson.JsonArray()) {
            if (!el.isJsonObject) continue
            val o = el.asJsonObject
            fun str(name: String): String? = o.get(name)?.takeIf { it.isJsonPrimitive }?.asString
            val observationId = str("observation_id") ?: continue
            val ok = store.commitObservationTransaction(
                owner = record,
                expectedAcceptedSeq = record.acceptedSeq, // CAS 锚定恢复后的 seq；导入不推进状态
                nextCheckpointJson = record.checkpointJson,
                nextAcceptedSeq = record.acceptedSeq,
                nextStateAppliedSeq = record.stateAppliedSeq,
                interactionDelta = 0,
                observation = ObservationRecord(
                    observationId = observationId,
                    ownerKey = newOwnerKey,
                    inputHash = str("input_hash") ?: continue,
                    turnId = str("turn_id") ?: continue,
                    kind = try {
                        ObservationKind.from(str("kind") ?: continue)
                    } catch (e: Exception) {
                        continue
                    },
                    seq = o.get("seq")?.takeIf { it.isJsonPrimitive }?.asLong ?: continue,
                    canonicalInputJson = str("canonical_input_json") ?: "{}",
                    perceptionJson = str("perception_json"),
                    sensorStatus = try {
                        SensorStatus.valueOf(str("sensor_status") ?: "CANCELLED")
                    } catch (e: Exception) {
                        SensorStatus.CANCELLED
                    },
                    degradeReason = str("degrade_reason"),
                    eligibilityJson = str("eligibility_json"),
                    factsJson = str("facts_json"),
                    createdAtWallMillis = o.get("created_at_wall_millis")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                ),
            )
            if (ok) imported++
        }
        for (el in runtime.getAsJsonArray("request_views") ?: com.google.gson.JsonArray()) {
            if (!el.isJsonObject) continue
            val r = el.asJsonObject
            fun str(name: String): String? = r.get(name)?.takeIf { it.isJsonPrimitive }?.asString
            store.insertRequestView(
                RequestViewRecord(
                    ownerKey = newOwnerKey,
                    turnId = str("turn_id") ?: continue,
                    subRequestId = str("sub_request_id") ?: "main",
                    requestRevision = r.get("request_revision")?.takeIf { it.isJsonPrimitive }?.asInt ?: continue,
                    observationCutoffSeq = r.get("observation_cutoff_seq")?.takeIf { it.isJsonPrimitive }?.asLong ?: continue,
                    projectionJson = str("projection_json") ?: "{}",
                    loreJson = str("lore_json") ?: "{}",
                    sourcesJson = str("sources_json") ?: "",
                    policyRevision = r.get("policy_revision")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                    memoryRevision = r.get("memory_revision")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                    budgetJson = str("budget_json") ?: "{}",
                    payloadHash = str("payload_hash") ?: "",
                    createdAtWallMillis = r.get("created_at_wall_millis")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                ),
            )
        }
        store.insertLifecycleOp(
            kind = "restore_import",
            phase = "committed",
            oldOwnerKey = null,
            newOwnerKey = newOwnerKey,
            manifestJson = JsonObject().apply {
                addProperty("observations_imported", imported)
                addProperty("accepted_seq", record.acceptedSeq)
            }.toString(),
            committed = true,
        )
        return true
    }

    // ------------------------------------------------------------------
    // 投影（不推进状态）
    // ------------------------------------------------------------------

    /**
     * 纯投影当前有效状态（问候与请求共用；不推进 seq、不跑感知）。
     * visibleEvidence 由调用方按"最终允许且确实可见"的证据给出。
     */
    fun projectCurrent(
        characterId: String,
        sessionId: String,
        incarnationId: String,
        visibleEvidence: Set<String>,
        activeTags: Set<String> = emptySet(),
    ): StateProjection? {
        val state = store.findActiveOwner(characterId, sessionId) ?: return null
        if (state.ownerKey != CompanionOwner(characterId, sessionId, incarnationId).storageKey()) return null
        val engine = try {
            Modulator.loads(state.checkpointJson)
        } catch (corrupt: IllegalArgumentException) {
            return null
        }
        return engine.project(
            ModulatorContext(
                topicId = "session:$sessionId",
                scene = "real",
                relations = CompanionProfileRepository.relationView(),
                activeTags = activeTags,
            ),
            visibleEvidence,
        )
    }

    /** 崩溃/重启恢复：校验检查点，补齐未完成的 JSON 投影；不重复有副作用工具。 */
    suspend fun recoverOwner(
        characterId: String,
        sessionId: String,
        incarnationId: String,
        sink: ProjectionSink,
    ): RecoveryReport {
        val state = store.findActiveOwner(characterId, sessionId)
            ?: return RecoveryReport("no_owner", drained = true)
        if (state.ownerKey != CompanionOwner(characterId, sessionId, incarnationId).storageKey()) {
            return RecoveryReport("incarnation_mismatch", drained = false)
        }
        val checkpointOk = try {
            Modulator.loads(state.checkpointJson); true
        } catch (corrupt: IllegalArgumentException) {
            false
        }
        var drained = true
        for (op in store.pendingOutbox(state.ownerKey)) {
            val ok = sink.upsertMessage(state.ownerKey, op.messageId, op.payloadJson)
            if (ok) store.markOutboxDone(op.id) else drained = false
        }
        return RecoveryReport(
            status = if (checkpointOk) "ok" else "checkpoint_corrupt",
            drained = drained,
            pendingStateNote = state.pendingStateNoteJson,
        )
    }

    fun interactionCount(characterId: String, sessionId: String): Long =
        store.findActiveOwner(characterId, sessionId)?.interactionCount ?: 0L

    // ------------------------------------------------------------------

    private fun waiveJson(reason: String): String =
        JsonObject().apply { addProperty("waived", reason) }.toString()

    private fun factsJson(facts: List<Fact>): String =
        JsonObject().apply {
            addProperty("count", facts.size)
            addProperty("kinds", facts.joinToString(",") { it.kind })
        }.toString()

    private fun pendingNoteJson(note: String, seq: Long): String =
        JsonObject().apply {
            addProperty("note", note)
            addProperty("accepted_seq", seq)
        }.toString()

    companion object {
        @Volatile
        private var instance: CompanionRuntimeCoordinator? = null

        /** application 级单例（A26：旋转/切页不新建运行状态）。 */
        fun getInstance(context: Context): CompanionRuntimeCoordinator =
            instance ?: synchronized(this) {
                instance ?: CompanionRuntimeCoordinator(
                    SqliteCompanionStateStore.getInstance(context),
                    SystemCompanionClock(context),
                    PrefsCompanionPolicyBook(context),
                ).also { instance = it }
            }

        fun sha256(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun canonicalInputJson(
            text: String,
            contextTurns: List<PerceptionTurn>,
            inputRevision: Long,
            attachmentsHash: String?,
        ): String = JsonObject().apply {
            addProperty("text", text)
            addProperty("input_revision", inputRevision)
            if (attachmentsHash != null) addProperty("attachments_hash", attachmentsHash)
            val turns = com.google.gson.JsonArray()
            contextTurns.forEach { t ->
                turns.add(JsonObject().apply {
                    addProperty("speaker", t.speaker)
                    addProperty("text", t.text)
                    addProperty("assistant", t.isAssistant)
                })
            }
            add("context", turns)
        }.toString()
    }
}

/** JSON 消息投影出口（实现方：ChatStorageManager 写路径；必须幂等且返回真实成败）。 */
fun interface ProjectionSink {
    suspend fun upsertMessage(ownerKey: String, messageId: String, messageJson: String): Boolean
}

/** 一次已接纳用户输入的请求载荷。 */
data class AdmitUserTurnRequest(
    val characterId: String,
    val sessionId: String,
    val incarnationId: String,
    /** 稳定 message ID（turnId）；重试必须复用。 */
    val messageId: String,
    /** 输入修订：编辑产生新修订，网络重试不变。 */
    val inputRevision: Long,
    /** 用户原文（不取正则改写后的生成副本）。 */
    val text: String,
    /** 冻结的最近上下文（当前陪伴会话的有效消息）。 */
    val contextTurns: List<PerceptionTurn>,
    /** 附件身份哈希（纯媒体输入时 text 为空、此项非空）。 */
    val attachmentsHash: String? = null,
    val policy: CompanionPolicySnapshot,
    /** 本轮确认的宿主事实（如 task_blocked；至多 1 条）。 */
    val hostFacts: List<Fact> = emptyList(),
    val legitimateFeedback: Boolean = false,
    val activeTags: Set<String> = emptySet(),
    val explicitResolutionLinks: Set<String> = emptySet(),
    /** 已序列化的用户消息 JSON（outbox 投影载荷）。 */
    val userMessageJson: JsonObject,
    val projectionSink: ProjectionSink,
)

/** 接纳结果。 */
sealed class AdmitOutcome {
    /** 已提交：状态推进一次。projectionPending=true 时调用方必须先补投影、不发网络。 */
    data class Accepted(
        val observationId: String,
        val turnId: String,
        val seq: Long,
        val rows: List<com.loyea.plugin.modulator.Row>,
        val audit: List<String>,
        val sensorStatus: SensorStatus,
        val degradeReason: String?,
        val interactionCounted: Boolean,
        val projectionPending: Boolean,
        val generation: Long,
    ) : AdmitOutcome()

    /** 相同 ID 相同 hash：重试/重连，复用原观测，不推进。 */
    data class Reused(val observation: ObservationRecord, val generation: Long) : AdmitOutcome()

    /** 相同 ID 不同 hash，或 CAS/约束冲突。 */
    data class Conflict(val reason: String) : AdmitOutcome()

    /** owner 缺失/失效/代际变化：旧任务丢弃。 */
    data class Invalid(val reason: String) : AdmitOutcome()
}

data class RecoveryReport(
    val status: String,
    val drained: Boolean,
    val pendingStateNote: String? = null,
)

/** 工具失败事实请求（task_blocked；同终态重复回调复用）。 */
data class AdmitToolFactRequest(
    val characterId: String,
    val sessionId: String,
    val incarnationId: String,
    /** 所属用户回合（共用 turnId）。 */
    val turnId: String,
    val toolCallId: String,
    /** 同一工具调用的终态修订；内容变化必须换修订号，不得生成随机 id 逃过去重。 */
    val terminalRevision: Long = 0,
)

/** 编辑回放结果（A28）。 */
sealed class EditRebaseResult {
    /** 已回退到 restorableSeq（该输入接纳前的检查点）；可按新 inputRevision 重新接纳。 */
    data class Rebased(val restorableSeq: Long) : EditRebaseResult()

    /** 编辑点早于运行时基线/历史已被清理：宿主初始化新的短期基线。 */
    data class BeforeRuntimeBaseline(val reason: String) : EditRebaseResult()

    data class Failed(val reason: String) : EditRebaseResult()
    data object NoOwner : EditRebaseResult()
}
