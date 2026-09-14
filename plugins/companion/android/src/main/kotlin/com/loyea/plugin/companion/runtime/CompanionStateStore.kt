package com.loyea.plugin.companion.runtime

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 陪伴运行账本存储 `companion_runtime.db`（Spec 接入文档 §6.1）。
 *
 * 只保存陪伴运行记录（Owner/State、Observation、RequestView、ProjectionOutbox、
 * LifecycleOperation），不迁移普通聊天。SQLite 事务只覆盖本数据库内的多项变更；
 * 外部 JSON 与 SharedPreferences 的写入通过 ProjectionOutbox 幂等投影。
 * 网络调用、模型推理与长时 IO 一律不持有数据库事务（由调用结构保证）。
 */
class SqliteCompanionStateStore private constructor(context: Context) : CompanionStateStore, SQLiteOpenHelper(
    context.applicationContext, DB_NAME, null, DB_VERSION,
) {
    @Suppress("RedundantOverride")
    override fun close() { super.close() }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE owner_state (
                owner_key TEXT PRIMARY KEY,
                active INTEGER NOT NULL,
                tombstoned INTEGER NOT NULL,
                binding_revision INTEGER NOT NULL,
                accepted_seq INTEGER NOT NULL,
                state_applied_seq INTEGER NOT NULL,
                algorithm_version TEXT NOT NULL,
                checkpoint_schema_version TEXT NOT NULL,
                personality_profile_version TEXT NOT NULL,
                lore_profile_version TEXT NOT NULL,
                checkpoint_json TEXT NOT NULL,
                clock_anchor_json TEXT NOT NULL,
                interaction_count INTEGER NOT NULL,
                pending_state_note_json TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE observation (
                owner_key TEXT NOT NULL,
                observation_id TEXT NOT NULL,
                input_hash TEXT NOT NULL,
                turn_id TEXT NOT NULL,
                kind TEXT NOT NULL,
                seq INTEGER NOT NULL,
                canonical_input_json TEXT NOT NULL,
                perception_json TEXT,
                sensor_status TEXT NOT NULL,
                degrade_reason TEXT,
                eligibility_json TEXT,
                facts_json TEXT,
                created_at_wall_millis INTEGER NOT NULL,
                PRIMARY KEY (owner_key, observation_id)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX idx_obs_turn_hash ON observation(owner_key, turn_id, input_hash)")
        db.execSQL(
            """
            CREATE TABLE request_view (
                owner_key TEXT NOT NULL,
                turn_id TEXT NOT NULL,
                sub_request_id TEXT NOT NULL,
                request_revision INTEGER NOT NULL,
                observation_cutoff_seq INTEGER NOT NULL,
                projection_json TEXT NOT NULL,
                lore_json TEXT NOT NULL,
                sources_json TEXT NOT NULL,
                policy_revision INTEGER NOT NULL,
                memory_revision INTEGER NOT NULL,
                budget_json TEXT NOT NULL,
                payload_hash TEXT NOT NULL,
                created_at_wall_millis INTEGER NOT NULL,
                PRIMARY KEY (owner_key, turn_id, sub_request_id, request_revision)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE projection_outbox (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                owner_key TEXT NOT NULL,
                message_id TEXT NOT NULL,
                revision INTEGER NOT NULL,
                op_type TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                done INTEGER NOT NULL DEFAULT 0,
                created_at_wall_millis INTEGER NOT NULL,
                UNIQUE (owner_key, message_id, revision)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE checkpoint_history (
                owner_key TEXT NOT NULL,
                seq INTEGER NOT NULL,
                checkpoint_json TEXT NOT NULL,
                created_at_wall_millis INTEGER NOT NULL,
                PRIMARY KEY (owner_key, seq)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE lifecycle_operation (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                phase TEXT NOT NULL,
                old_owner_key TEXT,
                new_owner_key TEXT,
                manifest_json TEXT NOT NULL,
                verification_json TEXT,
                committed INTEGER NOT NULL DEFAULT 0,
                created_at_wall_millis INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 → v2：v1 是未发布开发轮的 schema，真机上可能残留**表结构不同的旧账本**
        // （同版本号导致 onCreate/onUpgrade 均不触发，运行时静默降级——真机实测缺陷）。
        // 策略：先把旧文件整份归档为 .legacy_v1（尽力而为，失败不阻断），
        // 再检查期望表是否存在——缺表即判定为未知/损坏 schema，重建空账本；
        // 显式基线重置，不静默丢弃可识别数据（Spec §9.1/§9.3）。
        try {
            val dbFile = java.io.File(db.path)
            if (dbFile.isFile) {
                java.io.File(dbFile.parentFile, dbFile.name + ".legacy_v1")
                    .writeBytes(dbFile.readBytes())
            }
        } catch (archive: Throwable) {
            archive.printStackTrace()
        }
        val expected = listOf(
            "owner_state", "observation", "request_view",
            "projection_outbox", "lifecycle_operation", "checkpoint_history",
        )
        val existing = HashSet<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", emptyArray()).use { c ->
            while (c.moveToNext()) existing.add(c.getString(0))
        }
        if (expected.any { it !in existing }) {
            for (table in existing) {
                if (table == "android_metadata") continue
                db.execSQL("DROP TABLE IF EXISTS $table")
            }
            onCreate(db)
        }
        // 期望表齐全时：v1 即当前 schema，无需变更。
    }

    // ------------------------------------------------------------------
    // Owner/State
    // ------------------------------------------------------------------

    override fun findOwnerState(ownerKey: String): OwnerStateRecord? {
        readableDatabase.rawQuery(
            "SELECT * FROM owner_state WHERE owner_key = ?",
            arrayOf(ownerKey),
        ).use { c ->
            return if (c.moveToFirst()) ownerFrom(c) else null
        }
    }

    override fun findActiveOwner(characterId: String, sessionId: String): OwnerStateRecord? {
        readableDatabase.rawQuery(
            "SELECT * FROM owner_state WHERE owner_key LIKE ? AND active = 1 AND tombstoned = 0 LIMIT 2",
            arrayOf("$characterId|$sessionId|%"),
        ).use { c ->
            val first = if (c.moveToFirst()) ownerFrom(c) else null
            // 同一会话出现两条 active owner 属于数据异常：都不返回，交由调用方重建。
            return if (first != null && !c.moveToNext()) first else null
        }
    }

    override fun insertOwnerState(record: OwnerStateRecord): Boolean {
        writableDatabase.beginTransaction()
        try {
            val values = ownerValues(record)
            val id = writableDatabase.insertWithOnConflict(
                "owner_state", null, values, SQLiteDatabase.CONFLICT_IGNORE,
            )
            writableDatabase.setTransactionSuccessful()
            return id != -1L
        } finally {
            writableDatabase.endTransaction()
        }
    }

    /** 比较并交换推进 owner 状态 + 写观测 + 入 outbox，一次事务（Spec §6.2 步骤 5）。 */
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
        writableDatabase.beginTransaction()
        try {
            val cas = ContentValues().apply {
                put("accepted_seq", nextAcceptedSeq)
                put("state_applied_seq", nextStateAppliedSeq)
                put("checkpoint_json", nextCheckpointJson)
                put("interaction_count", owner.interactionCount + interactionDelta)
                put("pending_state_note_json", pendingStateNoteJson)
                put("clock_anchor_json", owner.clockAnchorJson)
            }
            val updated = writableDatabase.update(
                "owner_state", cas,
                "owner_key = ? AND accepted_seq = ? AND active = 1 AND tombstoned = 0",
                arrayOf(owner.ownerKey, expectedAcceptedSeq.toString()),
            )
            if (updated != 1) return false
            val inserted = writableDatabase.insertWithOnConflict(
                "observation", null, observationValues(observation), SQLiteDatabase.CONFLICT_ABORT,
            )
            if (inserted == -1L) return false
            for ((messageId, revision, payload) in outbox ?: emptyList()) {
                val op = ContentValues().apply {
                    put("owner_key", owner.ownerKey)
                    put("message_id", messageId)
                    put("revision", revision)
                    put("op_type", "upsert")
                    put("payload_json", payload.toString())
                    put("done", 0)
                    put("created_at_wall_millis", observation.createdAtWallMillis)
                }
                if (writableDatabase.insertWithOnConflict(
                        "projection_outbox", null, op, SQLiteDatabase.CONFLICT_IGNORE,
                    ) == -1L
                ) {
                    return false
                }
            }
            writableDatabase.setTransactionSuccessful()
            return true
        } catch (conflict: android.database.SQLException) {
            // 约束冲突（重复 observation / 唯一键）→ 按 CAS 失败处理，不留部分提交。
            return false
        } finally {
            writableDatabase.endTransaction()
        }
    }

    override fun markOwnerInactive(ownerKey: String, tombstone: Boolean): Boolean {
        val values = ContentValues().apply {
            put("active", 0)
            if (tombstone) put("tombstoned", 1)
        }
        return writableDatabase.update("owner_state", values, "owner_key = ?", arrayOf(ownerKey)) > 0
    }

    override fun setActiveOwnerOnly(ownerKey: String): Boolean {
        val values = ContentValues().apply { put("active", 1) }
        return writableDatabase.update("owner_state", values, "owner_key = ?", arrayOf(ownerKey)) > 0
    }

    override fun updateBindingRevision(ownerKey: String, bindingRevision: Long): Boolean {
        val values = ContentValues().apply { put("binding_revision", bindingRevision) }
        return writableDatabase.update("owner_state", values, "owner_key = ?", arrayOf(ownerKey)) > 0
    }

    override fun deleteOwnersForSession(characterId: String, sessionId: String): Int =
        writableDatabase.delete("owner_state", "owner_key LIKE ?", arrayOf("$characterId|$sessionId|%"))

    override fun deleteAllOwnersForCharacter(characterId: String): Int =
        writableDatabase.delete("owner_state", "owner_key LIKE ?", arrayOf("$characterId|%"))

    // ------------------------------------------------------------------
    // Observation
    // ------------------------------------------------------------------

    override fun findObservation(ownerKey: String, observationId: String): ObservationRecord? {
        readableDatabase.rawQuery(
            "SELECT * FROM observation WHERE owner_key = ? AND observation_id = ?",
            arrayOf(ownerKey, observationId),
        ).use { c ->
            return if (c.moveToFirst()) observationFrom(c) else null
        }
    }

    override fun allObservations(ownerKey: String, limit: Int): List<ObservationRecord> {
        val out = mutableListOf<ObservationRecord>()
        readableDatabase.rawQuery(
            "SELECT * FROM observation WHERE owner_key = ? ORDER BY seq ASC LIMIT ?",
            arrayOf(ownerKey, limit.toString()),
        ).use { c ->
            while (c.moveToNext()) out += observationFrom(c)
        }
        return out
    }

    // ------------------------------------------------------------------
    // RequestView
    // ------------------------------------------------------------------

    override fun insertRequestView(record: RequestViewRecord): Boolean {
        val values = ContentValues().apply {
            put("owner_key", record.ownerKey)
            put("turn_id", record.turnId)
            put("sub_request_id", record.subRequestId)
            put("request_revision", record.requestRevision)
            put("observation_cutoff_seq", record.observationCutoffSeq)
            put("projection_json", record.projectionJson)
            put("lore_json", record.loreJson)
            put("sources_json", record.sourcesJson)
            put("policy_revision", record.policyRevision)
            put("memory_revision", record.memoryRevision)
            put("budget_json", record.budgetJson)
            put("payload_hash", record.payloadHash)
            put("created_at_wall_millis", record.createdAtWallMillis)
        }
        return writableDatabase.insertWithOnConflict(
            "request_view", null, values, SQLiteDatabase.CONFLICT_REPLACE,
        ) != -1L
    }

    override fun findRequestView(ownerKey: String, turnId: String, subRequestId: String, revision: Int): RequestViewRecord? {
        readableDatabase.rawQuery(
            "SELECT * FROM request_view WHERE owner_key = ? AND turn_id = ? AND sub_request_id = ? AND request_revision = ?",
            arrayOf(ownerKey, turnId, subRequestId, revision.toString()),
        ).use { c ->
            return if (c.moveToFirst()) requestViewFrom(c) else null
        }
    }

    override fun latestRequestRevision(ownerKey: String, turnId: String, subRequestId: String): Int {
        readableDatabase.rawQuery(
            "SELECT MAX(request_revision) FROM request_view WHERE owner_key = ? AND turn_id = ? AND sub_request_id = ?",
            arrayOf(ownerKey, turnId, subRequestId),
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getInt(0)
        }
        return 0
    }

    override fun deleteRequestViewsForOwner(ownerKey: String): Int =
        writableDatabase.delete("request_view", "owner_key = ?", arrayOf(ownerKey))

    override fun allRequestViews(ownerKey: String, limit: Int): List<RequestViewRecord> {
        val out = mutableListOf<RequestViewRecord>()
        readableDatabase.rawQuery(
            "SELECT * FROM request_view WHERE owner_key = ? ORDER BY created_at_wall_millis DESC LIMIT ?",
            arrayOf(ownerKey, limit.toString()),
        ).use { c ->
            while (c.moveToNext()) out += requestViewFrom(c)
        }
        return out
    }

    // ------------------------------------------------------------------
    // ProjectionOutbox
    // ------------------------------------------------------------------

    override fun pendingOutbox(ownerKey: String): List<ProjectionOp> {
        val out = mutableListOf<ProjectionOp>()
        readableDatabase.rawQuery(
            "SELECT * FROM projection_outbox WHERE owner_key = ? AND done = 0 ORDER BY id ASC",
            arrayOf(ownerKey),
        ).use { c ->
            while (c.moveToNext()) out += outboxFrom(c)
        }
        return out
    }

    override fun markOutboxDone(id: Long): Boolean {
        val values = ContentValues().apply { put("done", 1) }
        return writableDatabase.update("projection_outbox", values, "id = ?", arrayOf(id.toString())) > 0
    }

    override fun enqueueOutbox(ownerKey: String, messageId: String, revision: Int, payloadJson: String, opType: String): Boolean {
        val values = ContentValues().apply {
            put("owner_key", ownerKey)
            put("message_id", messageId)
            put("revision", revision)
            put("op_type", opType)
            put("payload_json", payloadJson)
            put("done", 0)
            put("created_at_wall_millis", System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict("projection_outbox", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    override fun deleteOutboxForOwner(ownerKey: String): Int =
        writableDatabase.delete("projection_outbox", "owner_key = ?", arrayOf(ownerKey))

    // ------------------------------------------------------------------
    // LifecycleOperation
    // ------------------------------------------------------------------

    override fun insertLifecycleOp(
        kind: String,
        phase: String,
        oldOwnerKey: String?,
        newOwnerKey: String?,
        manifestJson: String,
        committed: Boolean,
    ): Long {
        val values = ContentValues().apply {
            put("kind", kind)
            put("phase", phase)
            put("old_owner_key", oldOwnerKey)
            put("new_owner_key", newOwnerKey)
            put("manifest_json", manifestJson)
            put("committed", if (committed) 1 else 0)
            put("created_at_wall_millis", System.currentTimeMillis())
        }
        return writableDatabase.insert("lifecycle_operation", null, values)
    }

    override fun updateLifecyclePhase(id: Long, phase: String, committed: Boolean, verificationJson: String?): Boolean {
        val values = ContentValues().apply {
            put("phase", phase)
            put("committed", if (committed) 1 else 0)
            if (verificationJson != null) put("verification_json", verificationJson)
        }
        return writableDatabase.update("lifecycle_operation", values, "id = ?", arrayOf(id.toString())) > 0
    }

    override fun deleteObservationsForOwner(ownerKey: String): Int =
        writableDatabase.delete("observation", "owner_key = ?", arrayOf(ownerKey))

    // ------------------------------------------------------------------

    private fun ownerFrom(c: android.database.Cursor): OwnerStateRecord = OwnerStateRecord(
        ownerKey = c.getString(c.getColumnIndexOrThrow("owner_key")),
        active = c.getInt(c.getColumnIndexOrThrow("active")) == 1,
        tombstoned = c.getInt(c.getColumnIndexOrThrow("tombstoned")) == 1,
        bindingRevision = c.getLong(c.getColumnIndexOrThrow("binding_revision")),
        acceptedSeq = c.getLong(c.getColumnIndexOrThrow("accepted_seq")),
        stateAppliedSeq = c.getLong(c.getColumnIndexOrThrow("state_applied_seq")),
        algorithmVersion = c.getString(c.getColumnIndexOrThrow("algorithm_version")),
        checkpointSchemaVersion = c.getString(c.getColumnIndexOrThrow("checkpoint_schema_version")),
        personalityProfileVersion = c.getString(c.getColumnIndexOrThrow("personality_profile_version")),
        loreProfileVersion = c.getString(c.getColumnIndexOrThrow("lore_profile_version")),
        checkpointJson = c.getString(c.getColumnIndexOrThrow("checkpoint_json")),
        clockAnchorJson = c.getString(c.getColumnIndexOrThrow("clock_anchor_json")),
        interactionCount = c.getLong(c.getColumnIndexOrThrow("interaction_count")),
        pendingStateNoteJson = c.getString(c.getColumnIndexOrThrow("pending_state_note_json")),
    )

    private fun ownerValues(record: OwnerStateRecord): ContentValues = ContentValues().apply {
        put("owner_key", record.ownerKey)
        put("active", if (record.active) 1 else 0)
        put("tombstoned", if (record.tombstoned) 1 else 0)
        put("binding_revision", record.bindingRevision)
        put("accepted_seq", record.acceptedSeq)
        put("state_applied_seq", record.stateAppliedSeq)
        put("algorithm_version", record.algorithmVersion)
        put("checkpoint_schema_version", record.checkpointSchemaVersion)
        put("personality_profile_version", record.personalityProfileVersion)
        put("lore_profile_version", record.loreProfileVersion)
        put("checkpoint_json", record.checkpointJson)
        put("clock_anchor_json", record.clockAnchorJson)
        put("interaction_count", record.interactionCount)
        put("pending_state_note_json", record.pendingStateNoteJson)
    }

    private fun observationFrom(c: android.database.Cursor): ObservationRecord = ObservationRecord(
        observationId = c.getString(c.getColumnIndexOrThrow("observation_id")),
        ownerKey = c.getString(c.getColumnIndexOrThrow("owner_key")),
        inputHash = c.getString(c.getColumnIndexOrThrow("input_hash")),
        turnId = c.getString(c.getColumnIndexOrThrow("turn_id")),
        kind = ObservationKind.from(c.getString(c.getColumnIndexOrThrow("kind"))),
        seq = c.getLong(c.getColumnIndexOrThrow("seq")),
        canonicalInputJson = c.getString(c.getColumnIndexOrThrow("canonical_input_json")),
        perceptionJson = c.getString(c.getColumnIndexOrThrow("perception_json")),
        sensorStatus = SensorStatus.valueOf(c.getString(c.getColumnIndexOrThrow("sensor_status"))),
        degradeReason = c.getString(c.getColumnIndexOrThrow("degrade_reason")),
        eligibilityJson = c.getString(c.getColumnIndexOrThrow("eligibility_json")),
        factsJson = c.getString(c.getColumnIndexOrThrow("facts_json")),
        createdAtWallMillis = c.getLong(c.getColumnIndexOrThrow("created_at_wall_millis")),
    )

    private fun observationValues(r: ObservationRecord): ContentValues = ContentValues().apply {
        put("owner_key", r.ownerKey)
        put("observation_id", r.observationId)
        put("input_hash", r.inputHash)
        put("turn_id", r.turnId)
        put("kind", r.kind.name)
        put("seq", r.seq)
        put("canonical_input_json", r.canonicalInputJson)
        put("perception_json", r.perceptionJson)
        put("sensor_status", r.sensorStatus.name)
        put("degrade_reason", r.degradeReason)
        put("eligibility_json", r.eligibilityJson)
        put("facts_json", r.factsJson)
        put("created_at_wall_millis", r.createdAtWallMillis)
    }

    private fun requestViewFrom(c: android.database.Cursor): RequestViewRecord = RequestViewRecord(
        ownerKey = c.getString(c.getColumnIndexOrThrow("owner_key")),
        turnId = c.getString(c.getColumnIndexOrThrow("turn_id")),
        subRequestId = c.getString(c.getColumnIndexOrThrow("sub_request_id")),
        requestRevision = c.getInt(c.getColumnIndexOrThrow("request_revision")),
        observationCutoffSeq = c.getLong(c.getColumnIndexOrThrow("observation_cutoff_seq")),
        projectionJson = c.getString(c.getColumnIndexOrThrow("projection_json")),
        loreJson = c.getString(c.getColumnIndexOrThrow("lore_json")),
        sourcesJson = c.getString(c.getColumnIndexOrThrow("sources_json")),
        policyRevision = c.getLong(c.getColumnIndexOrThrow("policy_revision")),
        memoryRevision = c.getLong(c.getColumnIndexOrThrow("memory_revision")),
        budgetJson = c.getString(c.getColumnIndexOrThrow("budget_json")),
        payloadHash = c.getString(c.getColumnIndexOrThrow("payload_hash")),
        createdAtWallMillis = c.getLong(c.getColumnIndexOrThrow("created_at_wall_millis")),
    )

    private fun outboxFrom(c: android.database.Cursor): ProjectionOp = ProjectionOp(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        ownerKey = c.getString(c.getColumnIndexOrThrow("owner_key")),
        messageId = c.getString(c.getColumnIndexOrThrow("message_id")),
        revision = c.getInt(c.getColumnIndexOrThrow("revision")),
        opType = c.getString(c.getColumnIndexOrThrow("op_type")),
        payloadJson = c.getString(c.getColumnIndexOrThrow("payload_json")),
        done = c.getInt(c.getColumnIndexOrThrow("done")),
        createdAtWallMillis = c.getLong(c.getColumnIndexOrThrow("created_at_wall_millis")),
    )

    // ------------------------------------------------------------------
    // 检查点历史（编辑分支回放）
    // ------------------------------------------------------------------

    override fun insertCheckpointHistory(ownerKey: String, seq: Long, checkpointJson: String): Boolean {
        val values = ContentValues().apply {
            put("owner_key", ownerKey)
            put("seq", seq)
            put("checkpoint_json", checkpointJson)
            put("created_at_wall_millis", System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict(
            "checkpoint_history", null, values, SQLiteDatabase.CONFLICT_REPLACE,
        ) != -1L
    }

    override fun findCheckpointAtOrBefore(ownerKey: String, seq: Long): Pair<Long, String>? {
        readableDatabase.rawQuery(
            "SELECT seq, checkpoint_json FROM checkpoint_history WHERE owner_key = ? AND seq <= ? ORDER BY seq DESC LIMIT 1",
            arrayOf(ownerKey, seq.toString()),
        ).use { c ->
            if (c.moveToFirst()) return c.getLong(0) to c.getString(1)
        }
        return null
    }

    override fun deleteObservationsFromSeq(ownerKey: String, seq: Long): Int =
        writableDatabase.delete("observation", "owner_key = ? AND seq >= ?", arrayOf(ownerKey, seq.toString()))

    override fun deleteCheckpointHistoryFromSeq(ownerKey: String, seq: Long): Int =
        writableDatabase.delete("checkpoint_history", "owner_key = ? AND seq >= ?", arrayOf(ownerKey, seq.toString()))

    override fun countUserInputObservations(ownerKey: String): Long {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM observation WHERE owner_key = ? AND kind = ?",
            arrayOf(ownerKey, ObservationKind.USER_INPUT.name),
        ).use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return 0L
    }

    override fun findLatestObservationForTurn(ownerKey: String, turnId: String): ObservationRecord? {
        readableDatabase.rawQuery(
            "SELECT * FROM observation WHERE owner_key = ? AND turn_id = ? ORDER BY seq DESC LIMIT 1",
            arrayOf(ownerKey, turnId),
        ).use { c ->
            return if (c.moveToFirst()) observationFrom(c) else null
        }
    }

    override fun deleteRequestViewsFromSeq(ownerKey: String, seq: Long): Int =
        writableDatabase.delete(
            "request_view", "owner_key = ? AND observation_cutoff_seq >= ?", arrayOf(ownerKey, seq.toString()),
        )

    override fun rebaseOwnerToCheckpoint(
        owner: OwnerStateRecord,
        expectedAcceptedSeq: Long,
        checkpointSeq: Long,
        checkpointJson: String,
        fromSeq: Long,
        newInteractionCount: Long,
    ): Boolean {
        writableDatabase.beginTransaction()
        try {
            val cas = ContentValues().apply {
                put("accepted_seq", checkpointSeq)
                put("state_applied_seq", checkpointSeq)
                put("checkpoint_json", checkpointJson)
                put("interaction_count", newInteractionCount)
                put("pending_state_note_json", "edit_rebase")
            }
            val updated = writableDatabase.update(
                "owner_state", cas,
                "owner_key = ? AND accepted_seq = ? AND active = 1 AND tombstoned = 0",
                arrayOf(owner.ownerKey, expectedAcceptedSeq.toString()),
            )
            if (updated != 1) return false
            writableDatabase.delete("observation", "owner_key = ? AND seq >= ?", arrayOf(owner.ownerKey, fromSeq.toString()))
            writableDatabase.delete("checkpoint_history", "owner_key = ? AND seq >= ?", arrayOf(owner.ownerKey, fromSeq.toString()))
            writableDatabase.delete("request_view", "owner_key = ? AND observation_cutoff_seq >= ?", arrayOf(owner.ownerKey, fromSeq.toString()))
            writableDatabase.setTransactionSuccessful()
            return true
        } catch (conflict: android.database.SQLException) {
            return false
        } finally {
            writableDatabase.endTransaction()
        }
    }

    companion object {
        private const val DB_NAME = "companion_runtime.db"
        private const val DB_VERSION = 2

        @Volatile
        private var instance: CompanionStateStore? = null

        /** 进程内单例（application scope）；旋转/切页共用同一份账本。 */
        fun getInstance(context: Context): CompanionStateStore =
            instance ?: synchronized(this) {
                instance ?: SqliteCompanionStateStore(context).also { instance = it }
            }

        /** 解析 owner 状态里保存的检查点（坏检查点由核心 loads 拒绝）。 */
        fun parseCheckpointObject(checkpointJson: String): JsonObject =
            JsonParser.parseString(checkpointJson).asJsonObject
    }
}
