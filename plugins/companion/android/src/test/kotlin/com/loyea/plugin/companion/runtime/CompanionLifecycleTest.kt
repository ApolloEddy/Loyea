package com.loyea.plugin.companion.runtime

import com.google.gson.JsonObject
import com.loyea.plugin.companion.CompanionBackupCodec
import com.loyea.plugin.companion.CompanionConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P5 生命周期行为（Spec §9）：备份 v3 运行账本导出/导入、重置清理、
 * 关闭保留状态、恢复换代后旧写入围栏（A25/A29/A31/A32 的协调器层）。
 */
class CompanionLifecycleTest {

    private lateinit var store: FakeStore
    private lateinit var clock: FixedCompanionClock
    private lateinit var coordinator: CompanionRuntimeCoordinator

    private val charId = "char_loyea_companion"
    private val sessionId = "session-1"
    private val incarnation = "inc-1"

    @Before
    fun setup() {
        store = FakeStore()
        clock = FixedCompanionClock(100.0)
        coordinator = CompanionRuntimeCoordinator(store, clock, FakePolicyBook())
    }

    private fun ownerKey(s: String = sessionId, i: String = incarnation) = "$charId|$s|$i"

    private fun admit(text: String, messageId: String, sink: ProjectionSink = RecordingSink()): AdmitOutcome = runBlocking {
        try {
            coordinator.ensureOwner(charId, sessionId, incarnation, bindingRevision = 1)
        } catch (expected: IllegalArgumentException) {
            // 已换代（重置/恢复）的场景由测试自行 ensureOwner；此处忽略旧 incarnation
        }
        coordinator.admitUserTurn(
            AdmitUserTurnRequest(
                characterId = charId, sessionId = sessionId, incarnationId = incarnation,
                messageId = messageId, inputRevision = 0, text = text, contextTurns = emptyList(),
                policy = CompanionPolicySnapshot(0, false, true, 0),
                userMessageJson = JsonObject().apply { addProperty("id", messageId) },
                projectionSink = sink,
            ),
        )
    }

    // ------------------------------------------------------------------
    // 备份 v3：运行账本导出 → 恢复到新 owner；状态/观测/计数一致（A29 面）
    // ------------------------------------------------------------------

    @Test
    fun backupV3ExportImportPreservesRuntimeState() = runBlocking {
        admit("今天有点难过", "m1")
        val before = coordinator.projectCurrent(charId, sessionId, incarnation, setOf("obs:m1:0"))
        val exported = coordinator.exportRuntimeState(charId, sessionId, incarnation)
        assertNotNull(exported)

        // 恢复到新会话/新 incarnation（命名空间隔离）
        val newSession = "session-2"
        val newIncarnation = "inc-2"
        val ok = coordinator.importRuntimeState(charId, newSession, newIncarnation, bindingRevision = 2, runtime = exported!!)
        assertTrue(ok)
        val after = coordinator.projectCurrent(charId, newSession, newIncarnation, setOf("obs:m1:0"))
        assertEquals(before?.rows, after?.rows)
        assertEquals(
            coordinator.interactionCount(charId, sessionId),
            coordinator.interactionCount(charId, newSession),
        )
        // 观测已迁入新命名空间（去重键随迁）
        assertEquals(1, store.observations(ownerKey(newSession, newIncarnation)).size)
        // 旧任务写回被换代围栏拒绝（A25）
        val stale = runBlocking {
            coordinator.admitUserTurn(
                AdmitUserTurnRequest(
                    characterId = charId, sessionId = newSession, incarnationId = incarnation, // 旧 incarnation
                    messageId = "m-late", inputRevision = 0, text = "迟到输入", contextTurns = emptyList(),
                    policy = CompanionPolicySnapshot(0, false, true, 0),
                    userMessageJson = JsonObject(), projectionSink = RecordingSink(),
                ),
            )
        }
        assertTrue(stale is AdmitOutcome.Invalid)
    }

    @Test
    fun corruptCheckpointImportFallsBackToBaseline() = runBlocking {
        admit("你好", "m1")
        val exported = coordinator.exportRuntimeState(charId, sessionId, incarnation)!!
        exported.getAsJsonObject("owner").addProperty("checkpoint_json", "{\"version\":\"1.1.0\",\"corrupt\":true}")
        val ok = coordinator.importRuntimeState(charId, "session-3", "inc-3", 1, exported)
        assertTrue(!ok) // 坏检查点：整体拒绝，调用方按新短期基线处理，不部分载入
    }

    // ------------------------------------------------------------------
    // A32：重新开始清运行数据；关闭保留状态（§9.1）
    // ------------------------------------------------------------------

    @Test
    fun restartClearsRuntimeLedger() = runBlocking {
        admit("第一条", "m1")
        admit("第二条", "m2")
        assertTrue(coordinator.resetCompanion(charId, sessionId))
        val state = store.findOwnerState(ownerKey())
        assertNotNull(state)
        assertTrue(state!!.tombstoned)
        assertEquals(0, store.observations(ownerKey()).size)
        assertEquals(0, store.pendingOutbox(ownerKey()).size)
        // 重置后同会话新 incarnation 重新从 -1 开始
        coordinator.ensureOwner(charId, sessionId, "inc-new", bindingRevision = 1)
        val outcome = runBlocking {
            coordinator.admitUserTurn(
                AdmitUserTurnRequest(
                    characterId = charId, sessionId = sessionId, incarnationId = "inc-new",
                    messageId = "m3", inputRevision = 0, text = "重置后的输入", contextTurns = emptyList(),
                    policy = CompanionPolicySnapshot(0, false, true, 0),
                    userMessageJson = JsonObject().apply { addProperty("id", "m3") },
                    projectionSink = RecordingSink(),
                ),
            )
        }
        assertTrue(outcome is AdmitOutcome.Accepted)
        assertEquals(0L, (outcome as AdmitOutcome.Accepted).seq)
    }

    @Test
    fun closePreservesCommittedState() = runBlocking {
        admit("状态应该保留", "m1")
        val checkpointBefore = store.findOwnerState(ownerKey())!!.checkpointJson
        coordinator.closeCompanion() // 关闭模式：仅撤销 lease
        val state = store.findOwnerState(ownerKey())!!
        assertTrue(state.active)
        assertEquals(checkpointBefore, state.checkpointJson)
        assertEquals(1, coordinator.interactionCount(charId, sessionId))
    }

    // ------------------------------------------------------------------
    // 备份编解码 v3：runtime 字段随 v3 往返；v1/v2 → null（新短期基线）；坏 runtime 拒绝
    // ------------------------------------------------------------------

    @Test
    fun codecV3RoundTripsRuntime() {
        val runtime = JsonObject().apply {
            add("owner", JsonObject().apply { addProperty("checkpoint_json", "{}") })
            add("observations", com.google.gson.JsonArray())
        }
        val json = CompanionBackupCodec.exportJson(
            config = CompanionConfig(),
            exportedAt = 123L,
            session = com.loyea.ui.chat.ChatSession(id = "s", title = "t", characterId = charId),
            messages = emptyList(),
            graphTriples = emptyList(),
            runtime = runtime,
        )
        assertTrue(json.contains("\"version\":3"))
        val result = CompanionBackupCodec.parse(json)
        assertTrue(result is CompanionBackupCodec.ParseResult.Ok)
        assertNotNull((result as CompanionBackupCodec.ParseResult.Ok).preview.runtime)

        // v1 迁移：runtime 为 null → 恢复侧按新短期基线
        val v1 = json.replace("\"version\":3", "\"version\":1").replace(Regex("\"runtime\":\\{.*?\\}(,\"model)"), "$1")
        val v1Result = CompanionBackupCodec.parse(v1)
        assertTrue(v1Result is CompanionBackupCodec.ParseResult.Ok)
        assertNull((v1Result as CompanionBackupCodec.ParseResult.Ok).preview.runtime)
    }

    @Test
    fun codecRejectsCorruptRuntimeInsteadOfSilentlyDropping() {
        val json = CompanionBackupCodec.exportJson(
            config = CompanionConfig(),
            exportedAt = 123L,
            session = com.loyea.ui.chat.ChatSession(id = "s", title = "t", characterId = charId),
            messages = emptyList(),
            graphTriples = emptyList(),
            runtime = JsonObject(),
        )
        val broken = json.replace("\"runtime\":{", "\"runtime\":\"not-an-object\",\"x\":{")
        val result = CompanionBackupCodec.parse(broken)
        assertTrue(result is CompanionBackupCodec.ParseResult.Rejected)
    }
}
