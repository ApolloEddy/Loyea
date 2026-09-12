/**
 * Loyea deterministic state modulator, Kotlin port of modulator.py v1.0.0-prototype.
 *
 * Pure Kotlin/JVM plugin core. No Android, no network, no clock, no randomness.
 * Engineering defaults, not a psychological scale. This is the porting contract
 * verified against integration/golden_cases.json (absolute error <= 1e-10).
 */
package com.loyea.plugin.modulator

/** 词表与固定常量（Spec §5、§9；与 prototype/modulator.py 逐一对应）。 */
object ModulatorVocab {
    const val VERSION: String = "1.0.0-prototype"

    const val AXIS_VALENCE = "valence"
    const val AXIS_ACTIVATION = "activation"
    const val AXIS_TENSION = "tension"
    const val AXIS_IRRITATION = "irritation"

    val AXES: List<String> = listOf(AXIS_VALENCE, AXIS_ACTIVATION, AXIS_TENSION, AXIS_IRRITATION)
    val BOUNDS: List<Pair<Double, Double>> =
        listOf(-1.0 to 1.0, 0.0 to 1.0, 0.0 to 1.0, 0.0 to 1.0)

    val FAST_HALFLIVES: List<Double> = listOf(180.0, 90.0, 240.0, 300.0)
    val MOOD_HALFLIVES: List<Double> = listOf(1800.0, 1200.0, 2400.0, 2700.0)

    val LEVELS: List<String> = listOf("mild", "moderate", "strong", "very_strong")
    val THRESHOLDS: List<Double> = listOf(0.30, 0.55, 0.80)

    val LEVEL_ZH: Map<String, String> = linkedMapOf(
        "mild" to "轻微",
        "moderate" to "中等",
        "strong" to "强烈",
        "very_strong" to "非常强烈",
        "none" to "无",
        "—" to "—",
    )

    val ASPECT_ZH: Map<String, String> = linkedMapOf(
        "relationship_affect" to "关系感情",
        "relationship_trust" to "关系信任",
        "mood" to "背景心境",
        "feeling" to "当前感受",
    )

    /** 前 31 个键是候选感受词；随后追加的非感受词用于关系与背景标签的展示。 */
    val STATE_ZH: Map<String, String> = buildMap {
        val feelingIds = listOf(
            "joy", "affection", "sadness", "anger", "fear", "disgust", "surprise",
            "grievance", "disappointment", "loneliness", "gratitude", "relief",
            "shame_guilt", "frustration", "anticipation", "amusement", "excitement",
            "interest", "curiosity", "confusion", "hope", "admiration", "pride",
            "compassion", "worry", "hurt", "guilt", "shame", "embarrassment",
            "boredom", "despair",
        )
        val feelingZh = listOf(
            "快乐", "喜爱", "悲伤", "愤怒", "恐惧", "厌恶", "惊讶", "委屈", "失望",
            "孤独", "感激", "释然", "羞愧／内疚（未细分）", "挫败", "期待", "被逗乐",
            "兴奋", "兴趣", "好奇", "困惑", "希望", "钦佩", "自豪", "心疼", "担忧",
            "情感受伤", "内疚", "羞愧", "难为情", "无聊", "绝望",
        )
        feelingIds.forEachIndexed { i, id -> put(id, feelingZh[i]) }
        put("neutral", "中性")
        put("unspecified", "未确定")
        put("aversion", "反感")
        put("love", "爱")
        put("trust", "信任")
        put("distrust", "不信任")
        put("calm", "平静")
        put("cheerful", "愉快")
        put("gloomy", "低落")
        put("tense", "紧绷")
        put("irritable", "烦躁")
    }

    val FEELING_IDS: Set<String> = STATE_ZH.keys.take(31).toSet()

    val ASPECT_STATES: Map<String, Set<String>> = linkedMapOf(
        "relationship_affect" to setOf("unspecified", "neutral", "affection", "aversion", "love"),
        "relationship_trust" to setOf("unspecified", "trust", "distrust"),
        "mood" to setOf("unspecified", "neutral", "calm", "cheerful", "gloomy", "tense", "irritable"),
        "feeling" to FEELING_IDS,
    )

    const val INTERPRETATION: String =
        "表格描述角色当前被显式指定的内部状态。请结合 Soul、当前情境、上下文、记忆、Lorebook 与本轮行为指令共同理解。" +
            "各行可以同时成立，强度表示内在感受，不规定必须表现多少。未确定表示系统没有提供判断，未列出的状态不等于零。" +
            "以本轮表格为当前显式状态，不自动沿用旧表中的遗漏条目。请自然回应，无须逐项点名或表演状态。"

    /** 与 prototype 相同的通用常量（Spec §5.3）。 */
    const val EVIDENCE_GATE_LOW: Double = 0.55
    const val IMPULSE_CAP: Double = 0.90
    const val BLEND_CAP: Double = 0.55
    const val BLEND_RATE: Double = 0.75
    const val REFRACTORY_SECONDS: Double = 45.0
    const val REFRACTORY_FLOOR: Double = 0.25
    const val TRACE_CAP: Double = 0.95
    const val TRACE_PRUNE: Double = 0.015
    const val MAX_TRACES: Int = 8
    const val MAX_EVIDENCE_PER_TRACE: Int = 4
    const val MAX_EVENTS_PER_TURN: Int = 8
    const val FEELING_ENTER: Double = 0.18
    const val FEELING_EXIT: Double = 0.12
    const val MOOD_ENTER: Double = 0.16
    const val MOOD_EXIT: Double = 0.10
    const val SELECTION_BIAS: Double = 0.04
    const val HYSTERESIS: Double = 0.03
    const val RESOLUTION_DAMP: Double = 0.55
}
