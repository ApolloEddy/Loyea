package com.loyea.plugin.companion.runtime

import com.google.gson.JsonObject

/**
 * 陪伴运行时的身份与账本记录模型（Spec 接入文档 §4.2/§6.1）。
 *
 * 状态归属使用 (companionCharacterId, sessionId, sessionIncarnationId) 三元组；
 * 激活旧陪伴会话时为空 incarnation 一次性分配并持久保存 UUID（由宿主负责写入
 * ChatSession，运行时只消费完整三元组）。
 */

/** 陪伴运行 owner 的完整身份。 */
data class CompanionOwner(
    val characterId: String,
    val sessionId: String,
    val incarnationId: String,
) {
    fun validate() {
        check(characterId.isNotEmpty() && sessionId.isNotEmpty() && incarnationId.isNotEmpty()) {
            "companion owner requires characterId/sessionId/incarnationId"
        }
    }

    /** 存储层主键（三元组拼接；字段本身不含 '|'）。 */
    fun storageKey(): String = "$characterId|$sessionId|$incarnationId"

    companion object {
        fun fromStorageKey(key: String): CompanionOwner {
            val parts = key.split("|")
            require(parts.size == 3) { "corrupt owner key" }
            return CompanionOwner(parts[0], parts[1], parts[2])
        }
    }
}

/** 观测种类：决定感知/计数语义。问候读取不产生观测（只投影，不落 Observation 表）。 */
enum class ObservationKind {
    /** 已接纳的用户输入（文本或完整转写）：感知一次、计数一次。 */
    USER_INPUT,

    /** 首次可靠工具终态事实：独立 observationId，共用 turnId，不加计数。 */
    TOOL_FACT,

    /** 合法空感知观测（模型未就绪/超时/被关闭等）：seq 照常推进，无感知刺激。 */
    DEGRADED;

    companion object {
        fun from(name: String): ObservationKind = valueOf(name)
    }
}

/** 文本感知返回状态（Spec §5；"没有显著刺激"是有效结果，不是故障）。 */
enum class SensorStatus {
    READY_RESULT,
    NOT_READY,
    DISABLED,
    TIMEOUT,
    INVALID_ASSET,
    INVALID_OUTPUT,
    UNSUPPORTED_VERSION,
    NO_TEXT,
    CANCELLED,
}

/** 感知接纳资格/弃权原因（admissionEligibility；与模型故障分开记录）。 */
data class AdmissionEligibility(
    val eligible: Boolean,
    val reason: String? = null,
) {
    companion object {
        val ELIGIBLE = AdmissionEligibility(true)
        fun waived(reason: String) = AdmissionEligibility(false, reason)
    }
}

/** 一次状态刺激的持久账本记录。 */
data class ObservationRecord(
    val observationId: String,
    val ownerKey: String,
    val inputHash: String,
    val turnId: String,
    val kind: ObservationKind,
    val seq: Long,
    /** 规范化输入与冻结上下文引用（JSON；确定性回放的权威依据）。 */
    val canonicalInputJson: String,
    /** 感知解码 JSON；null = 合法空感知（degradeReason 说明）。 */
    val perceptionJson: String?,
    val sensorStatus: SensorStatus,
    val degradeReason: String?,
    /** 归因弃权原因（perception 合格但证据不足/冲突时不提交刺激）。 */
    val eligibilityJson: String?,
    /** 有效宿主事实（task_blocked 等；JSON 数组）。 */
    val factsJson: String?,
    val createdAtWallMillis: Long,
)

/** 一次实际请求的投影快照（RequestView）。 */
data class RequestViewRecord(
    val ownerKey: String,
    val turnId: String,
    val subRequestId: String,
    val requestRevision: Int,
    /** 观测截止 seq：投影只覆盖 ≤ 该 seq 的已提交观测。 */
    val observationCutoffSeq: Long,
    /** 完整投影结果（rows + sources + interpretation，JSON）。 */
    val projectionJson: String,
    /** 选中私有 Lore：entryId/版本/文本（JSON 数组）。 */
    val loreJson: String,
    /** 来源清单（证据 id + 内容修订 + 权限来源，JSON）。 */
    val sourcesJson: String,
    val policyRevision: Long,
    val memoryRevision: Long,
    /** 预算结果（JSON；含 estimated 标记与动态预算占用）。 */
    val budgetJson: String,
    /** 最终动态模块 payload 的规范化哈希。 */
    val payloadHash: String,
    val createdAtWallMillis: Long,
)

/** 待投影到 JSON 聊天文件的写操作（按 messageId + revision 幂等执行）。 */
data class ProjectionOp(
    val id: Long,
    val ownerKey: String,
    val messageId: String,
    val revision: Int,
    /** "upsert" 或 "tombstone"。 */
    val opType: String,
    /** upsert: Message JSON；tombstone: {id, reason}。 */
    val payloadJson: String,
    val done: Int,
    val createdAtWallMillis: Long,
)

/** owner 状态行（Owner/State 记录）。 */
data class OwnerStateRecord(
    val ownerKey: String,
    val active: Boolean,
    val tombstoned: Boolean,
    val bindingRevision: Long,
    val acceptedSeq: Long,
    val stateAppliedSeq: Long,
    val algorithmVersion: String,
    val checkpointSchemaVersion: String,
    val personalityProfileVersion: String,
    val loreProfileVersion: String,
    /** 完整调制器检查点 JSON（最后已提交状态）。 */
    val checkpointJson: String,
    /** 时钟锚点（JSON：bootCount/wall/elapsed/logic）。 */
    val clockAnchorJson: String,
    /** 64 位用户交互计数（仅成功接纳 USER_INPUT 加一）。 */
    val interactionCount: Long,
    /** 异常路径中"已接纳但未应用状态"的显式标记（JSON 或 null）。 */
    val pendingStateNoteJson: String?,
)
