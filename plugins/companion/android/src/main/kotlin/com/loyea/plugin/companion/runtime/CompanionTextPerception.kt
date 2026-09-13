package com.loyea.plugin.companion.runtime

import com.google.gson.JsonObject

/**
 * 旧本地文本情绪感知的宿主接口（Spec 接入文档 §3/§5）。
 * 真实实现（ONNX + WordPiece + 解码 + 语法修正）见 perception 包；
 * 模型缺失/未就绪时宿主以对应 SensorStatus 降级，不阻塞聊天。
 */
interface CompanionTextPerception {

    /**
     * 对一次已接纳输入做感知。实现必须：有界等待、不并发第二次推理、
     * 超时不追加 LateResult（迟到的完整结果直接丢弃）。
     * "没有显著刺激"是 READY_RESULT 的合法内容，不是故障。
     */
    suspend fun perceive(request: PerceptionRequest): PerceptionOutcome
}

/** 感知输入（当前用户原文 + 冻结的最近上下文；不包含系统 Prompt/Lore/物理快照）。 */
data class PerceptionRequest(
    /** 当前说话者固定映射 speaker_0；改名不改变 speaker 身份（Spec §3.2）。 */
    val text: String,
    val contextTurns: List<PerceptionTurn>,
)

data class PerceptionTurn(
    val speaker: String,
    val text: String,
    /** assistant 历史可作上下文，但绝不作为当前被评价的说话者。 */
    val isAssistant: Boolean,
)

/** 感知结果。waiveReason 非空 = 归因弃权（原始解码保留入库，但不作为刺激提交）。 */
data class PerceptionOutcome(
    val status: SensorStatus,
    /** 兼容解码 JSON（schemaVersion 2.1.1）；status != READY_RESULT 时为 null。 */
    val perceptionJson: JsonObject?,
    val waiveReason: String? = null,
    /** 各阶段耗时（ms；tokenize/inference/decode，供 debug 诊断）。 */
    val timingMs: Map<String, Long> = emptyMap(),
) {
    companion object {
        fun degraded(status: SensorStatus, reason: String): PerceptionOutcome =
            PerceptionOutcome(status, null, null, emptyMap())
    }
}
