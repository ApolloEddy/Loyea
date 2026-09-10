package com.loyea.plugin.modulator

import kotlin.math.abs

// ---------------------------------------------------------------------------
// 校验原语：与 prototype/modulator.py 的 finite/unit/identifier/clip/gate 一致。
// 所有非法输入以 IllegalArgumentException 拒绝（对应 Python ValueError）。
// ---------------------------------------------------------------------------

fun finite(x: Double, name: String = "number"): Double {
    if (!x.isFinite()) throw IllegalArgumentException("$name: finite numeric value required")
    return x
}

fun unit(x: Double, name: String = "score"): Double {
    finite(x, name)
    if (x < 0.0 || x > 1.0) throw IllegalArgumentException("$name: expected [0,1]")
    return x
}

fun identifier(value: String, name: String = "id"): String {
    if (value.length !in 1..128) {
        throw IllegalArgumentException("$name: nonempty string of at most 128 characters required")
    }
    return value
}

fun clip(x: Double, lo: Double = 0.0, hi: Double = 1.0): Double = maxOf(lo, minOf(hi, x))

/** 证据软门槛：刻意区别于强度，也不是概率（Spec §8.2）。 */
fun gate(confidence: Double): Double = clip((confidence - 0.55) / 0.45)

/**
 * 高精度有界求和（Babuška–Neumaier 补偿求和）。
 * 对应 Python math.fsum 在本模块全部调用点（≤8 项、量级 ≤1）上的语义，
 * 与 fsum 的偏差远小于移植容差 1e-10 与轮内次序不变性容差 5e-15。
 */
fun compensatedSum(values: Iterable<Double>): Double {
    var sum = 0.0
    var comp = 0.0
    for (v in values) {
        val t = sum + v
        comp += if (abs(sum) >= abs(v)) (sum - t) + v else (v - t) + sum
        sum = t
    }
    return sum + comp
}

// ---------------------------------------------------------------------------
// 人格（固定 OCEAN 配置；Plasticity/TotalInteractions 不参与短期增益，Spec §5.2）
// ---------------------------------------------------------------------------

data class Personality(
    val openness: Double = 0.50,
    val conscientiousness: Double = 0.50,
    val extraversion: Double = 0.50,
    val agreeableness: Double = 0.50,
    val neuroticism: Double = 0.50,
    val plasticity: Double = 0.50,
    val totalInteractions: Int = 0,
    val profileVersion: String = "default-1",
) {
    init {
        listOf(
            "openness" to openness, "conscientiousness" to conscientiousness,
            "extraversion" to extraversion, "agreeableness" to agreeableness,
            "neuroticism" to neuroticism, "plasticity" to plasticity,
        ).forEach { (key, value) -> unit(value, key) }
        if (totalInteractions < 0) throw IllegalArgumentException("total_interactions: nonnegative integer required")
        identifier(profileVersion, "profile_version")
    }

    fun baseline(): List<Double> = listOf(0.0, 0.10 + 0.20 * extraversion, 0.0, 0.0)

    fun halfLives(): Pair<List<Double>, List<Double>> {
        val fastFactor = (0.75 + 0.75 * neuroticism) * (1.10 - 0.20 * conscientiousness)
        val moodFactor = 0.90 + 0.20 * neuroticism
        return ModulatorVocab.FAST_HALFLIVES.map { it * fastFactor } to
            ModulatorVocab.MOOD_HALFLIVES.map { it * moodFactor }
    }

    fun gain(family: String): Double = when (family) {
        "positive" -> 0.75 + 0.50 * extraversion
        "empathy" -> 0.70 + 0.60 * agreeableness
        "hostility" -> (0.70 + 0.60 * neuroticism) * (1.15 - 0.30 * agreeableness)
        "negative" -> 0.60 + 0.80 * neuroticism
        "novelty" -> 0.60 + 0.80 * openness
        "repair" -> 0.80 + 0.40 * conscientiousness
        "neutral" -> 1.0
        else -> throw IllegalArgumentException("unknown gain family: $family")
    }
}

// ---------------------------------------------------------------------------
// 评价规则表（Spec §7 完整 v1 刺激表；稀疏目标：缺失轴不被该事件清零）
// ---------------------------------------------------------------------------

data class Rule(
    val label: String,
    val family: String,
    val targets: Map<String, Double>,
    val halfLife: Double,
    val source: String,
)

object Rules {
    val ALL: Map<String, Rule> = linkedMapOf(
        "shared_joy" to Rule("joy", "positive", linkedMapOf("valence" to .80, "activation" to .55), 180.0, "legacy"),
        "user_distress" to Rule("compassion", "empathy", linkedMapOf("valence" to -.25, "activation" to .35, "tension" to .25), 300.0, "legacy"),
        "kindness" to Rule("gratitude", "positive", linkedMapOf("valence" to .60, "activation" to .25), 240.0, "legacy"),
        "affection" to Rule("affection", "positive", linkedMapOf("valence" to .65, "activation" to .30), 300.0, "legacy"),
        "hostility" to Rule("anger", "hostility", linkedMapOf("valence" to -.70, "activation" to .65, "tension" to .55, "irritation" to .85), 240.0, "legacy"),
        "repair" to Rule("relief", "repair", linkedMapOf("valence" to .30, "activation" to .10, "tension" to 0.0, "irritation" to 0.0), 150.0, "legacy"),
        "humor" to Rule("amusement", "positive", linkedMapOf("valence" to .65, "activation" to .45), 120.0, "legacy"),
        "task_blocked" to Rule("frustration", "negative", linkedMapOf("valence" to -.50, "activation" to .35, "tension" to .30), 180.0, "host"),
        "unresolved_information" to Rule("confusion", "neutral", linkedMapOf("activation" to .30, "tension" to .10), 120.0, "host"),
        "novel_topic" to Rule("curiosity", "novelty", linkedMapOf("valence" to .25, "activation" to .40), 180.0, "host"),
        "future_threat" to Rule("worry", "empathy", linkedMapOf("valence" to -.35, "activation" to .45, "tension" to .60), 360.0, "host"),
        "threat_resolved" to Rule("relief", "repair", linkedMapOf("valence" to .40, "activation" to .10, "tension" to 0.0), 150.0, "host"),
        "expectation_unmet" to Rule("disappointment", "negative", linkedMapOf("valence" to -.60, "activation" to .20), 300.0, "host"),
        "own_harm_confirmed" to Rule("guilt", "repair", linkedMapOf("valence" to -.45, "activation" to .30, "tension" to .35), 300.0, "host"),
        "self_achievement" to Rule("pride", "positive", linkedMapOf("valence" to .65, "activation" to .50), 240.0, "host"),
        "unexpected_event" to Rule("surprise", "neutral", linkedMapOf("activation" to .70), 60.0, "host"),
    )
}

// ---------------------------------------------------------------------------
// 输入契约（Spec §6.2）
// ---------------------------------------------------------------------------

data class Fact(
    val kind: String,
    val strength: Double,
    val evidenceId: String,
    val confidence: Double = 1.0,
    val verified: Boolean = false,
    val source: String = "host",
    val target: String = "event",
    val resolves: List<String> = emptyList(),
)

/** 只读的已确认关系数据；绝不由感知情感推断（Spec §9.1）。 */
data class RelationView(
    val affect: String = "unspecified",
    val affectStrength: Double? = null,
    val trust: String = "unspecified",
    val trustStrength: Double? = null,
    val evidenceId: String? = null,
    val scope: String = "general",
) {
    fun validate() {
        if (affect !in setOf("unspecified", "neutral", "affection", "aversion", "love")) {
            throw IllegalArgumentException("invalid relationship affect")
        }
        if (trust !in setOf("unspecified", "trust", "distrust")) {
            throw IllegalArgumentException("invalid relationship trust")
        }
        for ((label, strength) in listOf(affect to affectStrength, trust to trustStrength)) {
            if (label != "unspecified" && label != "neutral") {
                if (evidenceId.isNullOrEmpty() || strength == null) {
                    throw IllegalArgumentException("relationship claims require evidence and strength")
                }
                unit(strength)
            } else if (strength != null) {
                throw IllegalArgumentException("neutral/unspecified do not have intensity")
            }
        }
        if (affect == "neutral" && evidenceId.isNullOrEmpty()) {
            throw IllegalArgumentException("neutral relationship requires a known baseline")
        }
        identifier(scope, "relation scope")
        if (evidenceId != null) identifier(evidenceId, "relation evidence")
    }
}

data class Context(
    val topicId: String = "general",
    val scene: String = "real",
    val legitimateFeedback: Boolean = false,
    val visibleEvidence: Set<String> = emptySet(),
    val activeTags: Set<String> = emptySet(),
    val relations: RelationView = RelationView(),
    val trustScope: String = "general",
)

data class Observation(
    val eventId: String,
    val seq: Long,
    val at: Double,
    val context: Context = Context(),
    /** 旧感知模型（2.1.0 元数据 / 2.1.1 解码）的单观测解码结果；可缺失。 */
    val perception: com.google.gson.JsonObject? = null,
    val facts: List<Fact> = emptyList(),
    val modelMetadataVersion: String = "2.1.0",
)

// ---------------------------------------------------------------------------
// 内部结构与输出
// ---------------------------------------------------------------------------

data class Appraisal(
    val kind: String,
    val strength: Double,
    val confidence: Double,
    val evidenceId: String,
    val target: String,
    val topicId: String,
    val resolves: List<String> = emptyList(),
)

class Trace(
    val kind: String,
    val target: String,
    val topicId: String,
    var strength: Double,
    val halfLife: Double,
    val evidenceIds: MutableList<String>,
    var lastEventAt: Double,
) {
    fun copy(): Trace = Trace(kind, target, topicId, strength, halfLife, evidenceIds.toMutableList(), lastEventAt)
}

class Snapshot(
    var at: Double,
    val profileVersion: String,
    val fast: MutableList<Double>,
    val mood: MutableList<Double>,
    val traces: MutableList<Trace> = mutableListOf(),
    var lastSeq: Long = -1L,
    var lastEventId: String = "",
    val labelLevels: MutableMap<String, Int> = linkedMapOf(),
    val selectedFeelings: MutableList<String> = mutableListOf(),
    var moodLabel: String = "neutral",
) {
    fun copy(): Snapshot = Snapshot(
        at, profileVersion,
        fast.toMutableList(), mood.toMutableList(),
        traces.map { it.copy() }.toMutableList(),
        lastSeq, lastEventId,
        labelLevels.toMutableMap(),
        selectedFeelings.toMutableList(),
        moodLabel,
    )
}

data class Row(
    val aspect: String,
    val state: String,
    val intensity: String,
)

data class Decision(
    val eventId: String,
    val seq: Long,
    val rows: List<Row>,
    val audit: List<String>,
) {
    fun markdown(): String {
        val lines = mutableListOf("| aspect | state | intensity |", "|---|---|---|")
        for (r in rows) {
            lines += "| ${ModulatorVocab.ASPECT_ZH[r.aspect]} | ${ModulatorVocab.STATE_ZH[r.state]} | ${ModulatorVocab.LEVEL_ZH[r.intensity]} |"
        }
        return lines.joinToString("\n")
    }

    fun wire(): List<Map<String, String>> =
        rows.map { mapOf("aspect" to it.aspect, "state" to it.state, "intensity" to it.intensity) }
}

/** 语义扩展条目（已解析的单一活动世界书内的候选；Spec §10）。 */
data class LoreRule(
    val entryId: String,
    val text: String,
    val priority: Int = 0,
    val statesAny: Set<String> = emptySet(),
    val contextAll: Set<String> = emptySet(),
    val group: String = "",
    val always: Boolean = false,
    val enabled: Boolean = true,
)
