package com.loyea.plugin.companion.perception

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.google.gson.JsonParser
import com.loyea.plugin.companion.runtime.PerceptionOutcome
import com.loyea.plugin.companion.runtime.PerceptionRequest
import com.loyea.plugin.companion.runtime.SensorStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.LongBuffer
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 旧文本情绪感知传感器（Spec 接入文档 §3.3/§5/§10.3）。
 *
 * - 首次进入陪伴模式时 [prepareAsync] 异步准备；应用启动不加载模型。
 * - 单进程一份 tokenizer + OrtSession；专用单线程执行器（顺序、intra-op 1）。
 * - 一次输入至多一次推理；等待截止 [TIMEOUT_MS]，超时只使等待者失效并把
 *   迟到结果丢弃，native 推理进行中绝不关闭 Session；旧推理未退出时新轮
 *   立即按暂不可用降级（至多一个真实推理 + 有界待处理队列）。
 * - 连续失败 [FAILURE_LIMIT] 次进入暂时不可用；仅在后续用户事件且距失败
 *   ≥[RECOVERY_COOLDOWN_MS]、旧推理已退出时尝试一次恢复，不做后台空转重试。
 * - 上下文子集在本地核算 token 后冻结（右截断保护当前输入，§5）。
 * - 语法修正只作用于当前句原文，且不改调制器消费的概率分数（§3.2）。
 */
class LegacyEmotionSensor private constructor(private val appContext: Context) : com.loyea.plugin.companion.runtime.CompanionTextPerception {

    enum class PrepareState { IDLE, PREPARING, READY, FAILED }

    data class PrepareInfo(
        val state: PrepareState,
        val modelSha256: String? = null,
        val metadataSchema: String? = null,
        val error: String? = null,
    )

    private val state = AtomicReference(PrepareState.IDLE)
    private val prepareError = AtomicReference<String?>(null)
    private val modelHash = AtomicReference<String?>(null)
    private val metadataSchema = AtomicReference<String?>(null)

    private val tokenizerRef = AtomicReference<LegacyTokenizer?>(null)
    private val calibrationRef = AtomicReference<Map<String, LegacyDecoder.Calibration>>(emptyMap())
    private val sessionRef = AtomicReference<OrtSession?>(null)
    private val envRef = AtomicReference<OrtEnvironment?>(null)

    /** 专用有界执行器：至多 1 个真实推理 + 1 个待处理（Spec §11 并发约束）。 */
    private val executor = ThreadPoolExecutor(
        0, 1, 30L, TimeUnit.SECONDS, LinkedBlockingQueue(2),
    ).apply { allowCoreThreadTimeOut(true) }

    /** 等待者侧准入：0=空闲。 */
    private val admitting = AtomicInteger(0)

    /** native 推理生命周期：release() 必须等它归零。 */
    private val nativeJobs = AtomicInteger(0)

    @Volatile private var releaseRequested = false
    private val consecutiveFailures = AtomicInteger(0)
    @Volatile private var lastFailureWallMillis = 0L

    // ------------------------------------------------------------------
    // 准备
    // ------------------------------------------------------------------

    fun prepareAsync() {
        if (!state.compareAndSet(PrepareState.IDLE, PrepareState.PREPARING)) return
        executor.execute {
            try {
                prepareBlocking()
            } catch (t: Throwable) {
                prepareError.set(t.message ?: t.toString())
                state.set(PrepareState.FAILED)
            }
        }
    }

    fun prepareInfo(): PrepareInfo =
        PrepareInfo(state.get(), modelHash.get(), metadataSchema.get(), prepareError.get())

    private fun prepareBlocking() {
        releaseRequested = false
        val modelBytes = appContext.assets.open(MODEL_ASSET).use { it.readBytes() }
        if (modelBytes.size != EXPECTED_MODEL_BYTES) {
            throw IllegalStateException("model size mismatch: ${modelBytes.size}")
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(modelBytes)
        val hex = digest.joinToString("") { "%02x".format(it) }
        if (hex != EXPECTED_MODEL_SHA256) {
            throw IllegalStateException("model hash mismatch")
        }
        modelHash.set(hex)

        val tokenizerJson = appContext.assets.open(TOKENIZER_ASSET).use { it.readBytes().decodeToString() }
        tokenizerRef.set(LegacyTokenizer.fromJson(tokenizerJson, MAX_TOKENS))

        val calibrationJson = appContext.assets.open(CALIBRATION_ASSET).use { it.readBytes().decodeToString() }
        calibrationRef.set(LegacyDecoder.loadCalibration(JsonParser.parseString(calibrationJson).asJsonObject))

        val metadata = JsonParser.parseString(
            appContext.assets.open(METADATA_ASSET).use { it.readBytes().decodeToString() },
        ).asJsonObject
        val schema = metadata.get("schemaVersion")?.takeIf { it.isJsonPrimitive }?.asString
        if (schema != METADATA_SCHEMA) {
            throw IllegalStateException("unsupported metadata schema: $schema")
        }
        metadataSchema.set(schema)

        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1) // 省电初值：单 intra-op 线程、顺序执行（Spec §3.3）
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val session = env.createSession(modelBytes, options)
        options.close()
        envRef.set(env)
        sessionRef.set(session)
        state.set(PrepareState.READY)
    }

    // ------------------------------------------------------------------
    // 感知
    // ------------------------------------------------------------------

    override suspend fun perceive(request: PerceptionRequest): PerceptionOutcome {
        when (state.get()) {
            PrepareState.IDLE, PrepareState.PREPARING -> return PerceptionOutcome(SensorStatus.NOT_READY, null)
            PrepareState.FAILED -> return PerceptionOutcome(SensorStatus.INVALID_ASSET, null)
            PrepareState.READY -> Unit
        }
        if (consecutiveFailures.get() >= FAILURE_LIMIT) {
            // 熔断：后续用户事件且距上次失败 ≥ 冷却期时允许尝试一次（不做后台重试）。
            val cooled = System.currentTimeMillis() - lastFailureWallMillis >= RECOVERY_COOLDOWN_MS
            if (cooled) consecutiveFailures.set(0) else return PerceptionOutcome(SensorStatus.NOT_READY, null)
        }
        if (!admitting.compareAndSet(0, 1)) {
            // 旧一轮尚未结束：立即降级，不排队第二等待者。
            return PerceptionOutcome(SensorStatus.NOT_READY, null)
        }
        try {
            val tokenizer = tokenizerRef.get()
                ?: return PerceptionOutcome(SensorStatus.NOT_READY, null)

            // 本地核算 token：右截断会吃掉当前输入 → 从最旧开始减少 context（§5）。
            val tokenizeStart = System.currentTimeMillis()
            var usableContext = request.contextTurns
            var encoded = tokenizeFor(tokenizer, request.text, usableContext)
            while (encoded.truncated && usableContext.isNotEmpty()) {
                usableContext = usableContext.drop(1)
                encoded = tokenizeFor(tokenizer, request.text, usableContext)
            }
            val tokenizeMs = System.currentTimeMillis() - tokenizeStart

            val session = sessionRef.get() ?: return PerceptionOutcome(SensorStatus.NOT_READY, null)
            val env = envRef.get() ?: return PerceptionOutcome(SensorStatus.NOT_READY, null)

            val deferred = CompletableDeferred<PerceptionOutcome>()
            executor.execute {
                nativeJobs.incrementAndGet()
                try {
                    deferred.complete(runInference(session, env, encoded, request.text, tokenizeMs))
                } catch (t: Throwable) {
                    deferred.complete(PerceptionOutcome(SensorStatus.INVALID_OUTPUT, null))
                } finally {
                    if (nativeJobs.decrementAndGet() == 0 && releaseRequested) {
                        doRelease()
                    }
                }
            }
            // 等待者经可超时通道与 native 执行器分离；超时后迟到结果直接丢弃。
            val outcome = withTimeoutOrNull(TIMEOUT_MS) { deferred.await() }
            if (outcome == null) {
                noteFailure()
                return PerceptionOutcome(SensorStatus.TIMEOUT, null)
            }
            if (outcome.status == SensorStatus.INVALID_OUTPUT) noteFailure() else consecutiveFailures.set(0)
            return outcome
        } finally {
            admitting.set(0)
        }
    }

    private fun tokenizeFor(
        tokenizer: LegacyTokenizer,
        text: String,
        contextTurns: List<com.loyea.plugin.companion.runtime.PerceptionTurn>,
    ): LegacyTokenizer.Encoded = tokenizer.encode(
        LegacyTokenizer.joinInput(
            text, SPEAKER_CURRENT, contextTurns.map { it.speaker to it.text },
        ),
    )

    private fun noteFailure() {
        consecutiveFailures.incrementAndGet()
        lastFailureWallMillis = System.currentTimeMillis()
    }

    private fun runInference(
        session: OrtSession,
        env: OrtEnvironment,
        encoded: LegacyTokenizer.Encoded,
        currentText: String,
        tokenizeMs: Long,
    ): PerceptionOutcome {
        val start = System.currentTimeMillis()
        val idsTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(encoded.inputIds), longArrayOf(1, MAX_TOKENS.toLong()),
        )
        val maskTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(encoded.attentionMask), longArrayOf(1, MAX_TOKENS.toLong()),
        )
        try {
            session.run(mapOf("input_ids" to idsTensor, "attention_mask" to maskTensor)).use { results ->
                val inferenceMs = System.currentTimeMillis() - start
                val output = results.get(0)
                check(output is OnnxTensor) { "unexpected output type" }
                val info = output.info as TensorInfo
                val shape = info.shape
                if (shape == null || shape.size != 2 || shape[1] != LOGIT_COUNT.toLong()) {
                    return PerceptionOutcome(SensorStatus.INVALID_OUTPUT, null)
                }
                val row = (output.value as Array<FloatArray>)[0]
                if (row.any { !it.isFinite() }) {
                    return PerceptionOutcome(SensorStatus.INVALID_OUTPUT, null)
                }
                // float32 logits 精确放宽为 double；解码全程 double 数学。
                val doubles = DoubleArray(row.size) { row[it].toDouble() }
                val decoded = LegacyDecoder.decode(doubles, calibrationRef.get())
                val decodeStart = System.currentTimeMillis()
                val waived = LegacyDecoder.attributionConflict(decoded)
                // 语法修正只作用于当前句原文；调制器消费的分数在此之前读取，不受影响。
                val fixed = LegacyDecoder.applyGrammarFix(decoded, currentText)
                val timing = mapOf(
                    "tokenize_ms" to tokenizeMs,
                    "inference_ms" to inferenceMs,
                    "decode_ms" to (System.currentTimeMillis() - decodeStart),
                )
                return PerceptionOutcome(
                    status = SensorStatus.READY_RESULT,
                    perceptionJson = fixed,
                    waiveReason = waived,
                    timingMs = timing,
                )
            }
        } finally {
            idsTensor.close()
            maskTensor.close()
        }
    }

    // ------------------------------------------------------------------
    // 释放
    // ------------------------------------------------------------------

    /** 释放可释放的模型资源（模式关闭时调用）；native 推理退出前不关闭 Session。 */
    fun release() {
        releaseRequested = true
        if (nativeJobs.get() == 0 && admitting.get() == 0) doRelease()
    }

    private fun doRelease() {
        sessionRef.getAndSet(null)?.close()
        tokenizerRef.set(null)
        calibrationRef.set(emptyMap())
        state.set(PrepareState.IDLE)
        prepareError.set(null)
        consecutiveFailures.set(0)
        releaseRequested = false
    }

    companion object {
        private const val MODEL_ASSET = "perception/model.onnx"
        private const val TOKENIZER_ASSET = "perception/tokenizer.json"
        private const val METADATA_ASSET = "perception/metadata.json"
        private const val CALIBRATION_ASSET = "perception/calibration.json"
        const val EXPECTED_MODEL_BYTES = 24085508
        const val EXPECTED_MODEL_SHA256 = "a3e763f4bad5e4e9dfd5d2e75e10407aa2e5a336f438c67113761ff0c84419cb"
        const val METADATA_SCHEMA = "2.1.0"
        const val MAX_TOKENS = 96
        const val LOGIT_COUNT = 82
        const val SPEAKER_CURRENT = "speaker_0"

        /** 热态等待截止（ms）；冷启动不阻塞首条消息（prepare 异步）。 */
        const val TIMEOUT_MS = 500L
        private const val FAILURE_LIMIT = 3
        private const val RECOVERY_COOLDOWN_MS = 60_000L

        @Volatile
        private var instance: LegacyEmotionSensor? = null

        fun getInstance(context: Context): LegacyEmotionSensor =
            instance ?: synchronized(this) {
                instance ?: LegacyEmotionSensor(context.applicationContext).also { instance = it }
            }
    }
}
