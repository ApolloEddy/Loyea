package com.loyea.plugin.companion.runtime

import com.google.gson.JsonObject
import com.loyea.plugin.modulator.Fact
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 陪伴运行协调器的 JVM 行为测试（内存账本 + 固定时钟 + 可编程感知）。
 * 覆盖验收矩阵 A03/A04/A05(复用面)/A10(降级面)/A23/A25/A28 的协调器层行为。
 */
class CompanionRuntimeCoordinatorTest {

    private lateinit var store: FakeStore
    private lateinit var clock: FixedCompanionClock
    private lateinit var sensor: ProgrammableSensor
    private lateinit var coordinator: CompanionRuntimeCoordinator

    private val charId = "char_loyea_companion"
    private val sessionId = "session-1"
    private val incarnation = "inc-1"

    @Before
    fun setup() {
        store = FakeStore()
        clock = FixedCompanionClock(100.0)
        sensor = ProgrammableSensor()
        coordinator = CompanionRuntimeCoordinator(store, clock, FakePolicyBook())
        coordinator.textPerception = sensor
    }

    private fun ownerKey() = "$charId|$sessionId|$incarnation"

    private fun request(
        messageId: String,
        text: String,
        inputRevision: Long = 0,
        sink: ProjectionSink = RecordingSink(),
        perception: com.loyea.plugin.modulator.Observation.() -> Unit = {},
    ): AdmitUserTurnRequest = AdmitUserTurnRequest(
        characterId = charId,
        sessionId = sessionId,
        incarnationId = incarnation,
        messageId = messageId,
        inputRevision = inputRevision,
        text = text,
        contextTurns = emptyList(),
        policy = CompanionPolicySnapshot(0, physicalPerceptionEnabled = false, textPerceptionEnabled = true, memoryRevision = 0),
        userMessageJson = JsonObject().apply { addProperty("id", messageId) },
        projectionSink = sink,
    )

    // ------------------------------------------------------------------
    // A03：相同原文、重复点击同一次发送 → 一个观测、一次计数
    // ------------------------------------------------------------------

    @Test
    fun a03_duplicateSendAdmitsOnce() = runBlocking {
        coordinator.ensureOwner(charId, sessionId, incarnation, bindingRevision = 1)
        val first = coordinator.admitUserTurn(request("m1", "今天好累"))
        assertTrue(first is AdmitOutcome.Accepted)
        val second = coordinator.admitUserTurn(request("m1", "今天好累"))
        assertTrue(second is AdmitOutcome.Reused)
        assertEquals(1, coordinator.interactionCount(charId, sessionId))
        assertEquals(1, store.observations(ownerKey()).size)
        assertEquals(0L, (first as AdmitOutcome.Accepted).seq)
    }

    // ------------------------------------------------------------------
    // A04：两次由用户分别发送相同正文 → 两个稳定 ID，分别接纳
    // ------------------------------------------------------------------

    @Test
    fun a04_identicalTextSeparateSendsAdmittedTwice() = runBlocking {
        coordinator.ensureOwner(charId, sessionId, incarnation, bindingRevision = 1)
        val a = coordinator.admitUserTurn(request("m1", "在吗"))
        val b = coordinator.admitUserTurn(request("m2", "在吗"))
        assertTrue(a is AdmitOutcome.Accepted)
        assertTrue(b is AdmitOutcome.Accepted)
        assertEquals(2, coordinator.interactionCount(charId, sessionId))
        assertEquals(listOf(0L, 1L), store.observations(ownerKey()).map { it.seq })
    }

    // ------------------------------------------------------------------
    // A05 面：网络重试复用原观测；seq/计数不随重试增加
    // ------------------------------------------------------------------

    @Test
    fun a05_retryReuseDoesNotAdvanceState() = runBlocking {
        coordinator.ensureOwner(charId, sessionId, incarnation, bindingRevision = 1)
        val first = coordinator.admitUserTurn(request("m1", "你好")) as AdmitOutcome.Accepted
        val checkpointAfterFirst = store.findOwnerState(ownerKey())!!.checkpointJson
        repeat(10) {
            val retry = coordinator.admitUserTurn(request("m1", "你好"))
            assertTrue(retry is AdmitOutcome.Reused)
        }
        assertEquals(checkpointAfterFirst, store.findOwnerState(ownerKey())!!.checkpointJson)
        assertEquals(1, coordinator.interactionCount(charId, sessionId))
        assertEquals(first.seq, 0L)
    }

    // ------------------------------------------------------------------
    // 相同 ID 不同内容 = 协议冲突（§4.2）
    // ------------------------------------------------------------------

    @Test
    fun sameIdDifferentHashIsConflict() = runBlocking {
        coordinator.ensureOwner(charId, sessionId, incarnation, bindingRevision = 1)
        coordinator.admitUserTurn(request("m1", "第一版内容"))
        val conflict = coordinator.admitUserTurn(request("m1", "被篡改的内容"))
        assertTrue(conflict is AdmitOutcome.Conflict)
        assertEquals(1, coordinator.interactionCount(charId, sessionId))
    }

    // ------------------------------------------------------------------
    // A23：事务提交失败 → 正式状态未变、同 ID 可重试、不留部分提交
    // ------------------------------------------------------------------

    @Test
    fun a23_commitFailureLeavesNoPartialState() = runBlocking {
        coordinator.ensureOwner(charId, sessionId, incarnation, bindingRevision = 1)
        store.failNextCommit = true
        val failed = coordinator.admitUserTurn(request("m1", "你好"))
        assertTrue(failed is AdmitOutcome.Conflict)
        val state = store.findOwnerState(ownerKey())!!
        assertEquals(-1L, state.acceptedSeq)
        assertEquals(0, store.observations(ownerKey()).size)
        assertEquals(0L, state.interactionCount)
        store.failNextCommit = false
        val retried = coordinator.admitUserTurn(request("m1", "你好"))
        assertTrue(retried is AdmitOutcome.Accepted)
        assertEquals(1, coordinator.interactionCount(charId, sessionId))
    }

    // ------------------------------------------------------------------
    // A25 面：旧 incarnation 的晚到写入被拒
    // ------------------------------------------------------------------

    @Test
    fun a25_staleIncarnationRejected() = runBlocking {
        coordinator.ensureOwner(charId, sessionId, "inc-OLD", bindingRevision = 1)
        // 会话恢复后 owner 已换代：active owner 不再是 inc-OLD。
        coordinator.resetCompanion(charId, sessionId)
        coordinator.ensureOwner(charId, sessionId, incarnation, bindingRevision = 1)
        val stale = coordinator.admitUserTurn(
            request("m-old", "旧会话的迟到输入").let { req ->
                req.copy(incarnationId = "inc-OLD")
            },
        )
        assertTrue(stale is AdmitOutcome.Invalid)
    }

    // ------------------------------------------------------------------
    // 感知降级：未就绪/被关闭都是合法空感知，seq 推进、聊天继续（A10/A16 面）
    // ------------------------------------------------------------------

    @Test
    fun sensorNotReadyDegradesGracefully() = runBlocking {
        coordinator.ensureOwner(charId, sessionId, incarnation, bindingRevision = 1)
        sensor.nextStatus = SensorStatus.NOT_READY
        val outcome = coordinator.admitUserTurn(request("m1", "你好")) as AdmitOutcome.Accepted
        assertEquals(SensorStatus.NOT_READY, outcome.sensorStatus)
        assertEquals(0L, outcome.seq)
        assertEquals(1, coordinator.interactionCount(charId, sessionId))
        val obs = store.observations(ownerKey()).single()
        assertEquals(null, obs.perceptionJson)
        assertEquals("NOT_READY", obs.degradeReason)
    }

    @Test
    fun sensorDisabledDegradesWithoutPerception() = runBlocking {
        coordinator.ensureOwner(charId, sessionId, incarnation, bindingRevision = 1)
        val req = AdmitUserTurnRequest(
            characterId = charId, sessionId = sessionId, incarnationId = incarnation,
            messageId = "m1", inputRevision = 0, text = "你好", contextTurns = emptyList(),
            policy = CompanionPolicySnapshot(1, physicalPerceptionEnabled = false, textPerceptionEnabled = false, memoryRevision = 0),
            userMessageJson = JsonObject().apply { addProperty("id", "m1") },
            projectionSink = RecordingSink(),
        )
        val outcome = coordinator.admitUserTurn(req) as AdmitOutcome.Accepted
        assertEquals(SensorStatus.DISABLED, outcome.sensorStatus)
        assertEquals(0, sensor.calls)
    }

    // ------------------------------------------------------------------
    // A28：编辑旧输入 → 回退检查点 + 新 inputRevision 新分支
    // ------------------------------------------------------------------

    @Test
    fun a28_editRebasesToPreTurnCheckpoint() = runBlocking {
        coordinator.ensureOwner(charId, sessionId, incarnation, bindingRevision = 1)
        coordinator.admitUserTurn(request("m1", "原始版本"))
        coordinator.admitUserTurn(request("m2", "后续消息")) as AdmitOutcome.Accepted
        assertEquals(2, coordinator.interactionCount(charId, sessionId))

        val rebase = coordinator.rebaseToEditPoint(charId, sessionId, incarnation, "m1")
        assertTrue(rebase is EditRebaseResult.Rebased)

        // m1 与后缀 m2 的观测一并废弃；计数按有效谱系归零。
        assertEquals(0, store.observations(ownerKey()).size)
        assertEquals(0, coordinator.interactionCount(charId, sessionId))
        val state = store.findOwnerState(ownerKey())!!
        assertEquals(-1L, state.acceptedSeq)

        // 编辑后的 m1（同 messageId、新 inputRevision）作为新分支重新接纳。
        val reAdmitted = coordinator.admitUserTurn(request("m1", "编辑后的版本", inputRevision = 1))
        assertTrue(reAdmitted is AdmitOutcome.Accepted)
        assertEquals(0L, (reAdmitted as AdmitOutcome.Accepted).seq)
        assertEquals(1, coordinator.interactionCount(charId, sessionId))
    }

    @Test
    fun a28_editBeforeRuntimeBaselineReportsExplicitly() = runBlocking {
        coordinator.ensureOwner(charId, sessionId, incarnation, bindingRevision = 1)
        coordinator.admitUserTurn(request("m1", "运行时内的消息"))
        val rebase = coordinator.rebaseToEditPoint(charId, sessionId, incarnation, "m-ancient")
        assertTrue(rebase is EditRebaseResult.BeforeRuntimeBaseline)
    }

    // ------------------------------------------------------------------
    // 归因弃权：原始解码入库，但不作为刺激提交
    // ------------------------------------------------------------------

    @Test
    fun waivedPerceptionStoredButNotApplied() = runBlocking {
        coordinator.ensureOwner(charId, sessionId, incarnation, bindingRevision = 1)
        sensor.nextOutcome = PerceptionOutcome(
            SensorStatus.READY_RESULT,
            hostilePerception(),
            waiveReason = "grammar_model_conflict",
        )
        val outcome = coordinator.admitUserTurn(request("m1", "第三人称转述的愤怒")) as AdmitOutcome.Accepted
        assertEquals(SensorStatus.READY_RESULT, outcome.sensorStatus)
        val obs = store.observations(ownerKey()).single()
        assertNotNull(obs.perceptionJson) // 原始解码保留
        assertNotNull(obs.eligibilityJson)
        // 弃权不产生 anger 感受刺激（工作副本未收到感知）。
        assertTrue(outcome.rows.none { it.aspect == "feeling" && it.state == "anger" })
    }

    private fun hostilePerception(): JsonObject {
        val p = JsonObject()
        p.addProperty("schemaVersion", "2.1.1")
        val emotions = JsonObject()
        val anger = JsonObject()
        anger.addProperty("calibratedProbability", 0.9)
        anger.addProperty("strength", 0.9)
        emotions.add("anger", anger)
        p.add("emotions", emotions)
        p.add("fineStates", JsonObject())
        p.add("stances", JsonObject())
        val act = JsonObject()
        act.addProperty("primary", "inform")
        act.addProperty("confidence", 0.95)
        p.add("dialogueAct", act)
        val target = JsonObject()
        target.addProperty("value", "listener")
        target.addProperty("confidence", 0.95)
        p.add("speakerAffectTarget", target)
        val factuality = JsonObject()
        factuality.addProperty("value", "asserted")
        factuality.addProperty("confidence", 0.95)
        p.add("utteranceFactuality", factuality)
        val dimensions = JsonObject()
        dimensions.addProperty("toxicity", 0.8)
        p.add("dimensions", dimensions)
        return p
    }
}

// ---------------------------------------------------------------------------
// 测试基础设施
// ---------------------------------------------------------------------------

/** 可编程感知：按调用返回预设结果。 */
private class ProgrammableSensor : CompanionTextPerception {
    var calls = 0
    var nextStatus: SensorStatus = SensorStatus.READY_RESULT
    var nextOutcome: PerceptionOutcome? = null

    override suspend fun perceive(request: PerceptionRequest): PerceptionOutcome {
        calls++
        nextOutcome?.let { return it }
        return if (nextStatus == SensorStatus.READY_RESULT) {
            PerceptionOutcome(SensorStatus.READY_RESULT, JsonObject().apply { addProperty("schemaVersion", "2.1.1") })
        } else {
            PerceptionOutcome(nextStatus, null)
        }
    }
}

private class RecordingSink : ProjectionSink {
    val written = mutableListOf<String>()
    var failNext = false
    override suspend fun upsertMessage(ownerKey: String, messageId: String, messageJson: String): Boolean {
        if (failNext) return false
        written += messageId
        return true
    }
}

private class FakePolicyBook : CompanionPolicyBook {
    private var revision = 0L
    override fun current(physical: Boolean, text: Boolean, memoryRevision: Long) =
        CompanionPolicySnapshot(revision, physical, text, memoryRevision)
    override fun bump(): Long = ++revision
}
