package com.loyea.plugin.companion.perception

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.exp
import kotlin.math.ln

/**
 * 旧 April 感知模型解码器移植（Spec 接入文档 §3.2）。
 *
 * `decode` = 原 `Fi`：82 logits 按 12 头切分；6 个头 softmax、其余 sigmoid；
 * basePresence 每情绪做 per-label-vector-scaling 校准；tuning 默认恒等
 * （与原 Demo 打包默认一致）。输出 schemaVersion "2.1.1"。
 *
 * `applyGrammarFix` = 原 `hn`：只作用于当前句文本，调整 effectiveScore /
 * referencedAffect / 显示主情绪；**不**重新校准 calibratedProbability，
 * 也**不**替换调制器消费的对象/事实性输出（Spec §3.2 语义边界）。
 */
object LegacyDecoder {

    // 头顺序与尺寸（与 metadata.json headOrder/headSizes、legacy_schema.json 一致）。
    val HEAD_ORDER = listOf(
        "basePresence", "baseStrength", "fineStrength", "dialogueAct", "stances",
        "speakerAffectTarget", "utteranceFactuality", "dimensions",
        "referencePresence", "referenceEmotion", "referenceHolder", "referenceFactuality",
    )
    val HEAD_SIZES = linkedMapOf(
        "basePresence" to 8, "baseStrength" to 8, "fineStrength" to 8,
        "dialogueAct" to 13, "stances" to 7, "speakerAffectTarget" to 7,
        "utteranceFactuality" to 5, "dimensions" to 6, "referencePresence" to 1,
        "referenceEmotion" to 8, "referenceHolder" to 6, "referenceFactuality" to 5,
    )
    val BASE_PRESENCE_LABELS = listOf(
        "joy", "affection", "sadness", "anger", "fear", "disgust", "surprise", "neutral",
    )
    val FINE_LABELS = listOf(
        "grievance", "disappointment", "loneliness", "gratitude", "relief",
        "shame_guilt", "frustration", "anticipation",
    )
    val DIALOGUE_ACT_LABELS = listOf(
        "inform", "question", "answer", "request_command", "agree_ack", "disagree_reject",
        "apologize_repair", "thank_appreciate", "comfort_support", "complain_protest",
        "greet_close", "joke_irony", "other",
    )
    val STANCE_LABELS = listOf(
        "affiliative", "playful", "coquettish", "hostile", "defensive", "vulnerable", "repairing",
    )
    val TARGET_LABELS = listOf("self", "listener", "third_party", "event_object", "general", "none", "unclear")
    val FACTUALITY_LABELS = listOf("asserted", "negated", "hypothetical", "reported", "unclear")
    val DIMENSION_LABELS = listOf("valence", "arousal", "dominance", "intensity", "ambiguity", "toxicity")
    val HOLDER_LABELS = listOf("speaker", "listener", "third_party", "mixed", "none", "unclear")

    private val SOFTMAX_HEADS = setOf(
        "dialogueAct", "speakerAffectTarget", "utteranceFactuality",
        "referenceEmotion", "referenceHolder", "referenceFactuality",
    )

    /** 校准参数（calibration.json 的 per-label-vector-scaling）。 */
    data class Calibration(
        val temperature: Double,
        val bias: Double,
    )

    fun loadCalibration(calibrationJson: JsonObject?): Map<String, Calibration> {
        if (calibrationJson == null) return emptyMap()
        val labels = calibrationJson.getAsJsonObject("basePresence")?.getAsJsonObject("labels") ?: return emptyMap()
        val out = LinkedHashMap<String, Calibration>()
        for (label in BASE_PRESENCE_LABELS) {
            val el = labels.getAsJsonObject(label) ?: continue
            out[label] = Calibration(
                el.get("temperature")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 1.0,
                el.get("bias")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0,
            )
        }
        return out
    }

    // ------------------------------------------------------------------
    // Fi
    // ------------------------------------------------------------------

    fun decode(logits: DoubleArray, calibration: Map<String, Calibration>): JsonObject {
        require(logits.size == HEAD_SIZES.values.sum()) {
            "expected ${HEAD_SIZES.values.sum()} logits, received ${logits.size}"
        }
        var offset = 0
        val heads = LinkedHashMap<String, LinkedHashMap<String, Double>>()
        for (head in HEAD_ORDER) {
            val size = HEAD_SIZES.getValue(head)
            val slice = logits.slice(offset until offset + size)
            offset += size
            val mapped = LinkedHashMap<String, Double>()
            val values = if (head in SOFTMAX_HEADS) softmax(slice) else slice.map { sigmoid(it) }
            val labels = labelsFor(head)
            for (i in values.indices) mapped[labels[i]] = values[i]
            heads[head] = mapped
        }

        val emotions = LinkedHashMap<String, JsonObject>()
        val calibrated = LinkedHashMap<String, Double>()
        val effective = LinkedHashMap<String, Double>()
        for (label in BASE_PRESENCE_LABELS) {
            val raw = heads.getValue("basePresence").getValue(label)
            val cal = calibration[label] ?: Calibration(1.0, 0.0)
            val calibratedP = sigmoid(logit(raw) / cal.temperature + cal.bias)
            // tuning 恒等基线（temperature=1, bias=0, policyWeight=1）。
            val eff = calibratedP
            calibrated[label] = calibratedP
            effective[label] = eff
            emotions[label] = JsonObject().apply {
                addProperty("rawProbability", raw)
                addProperty("calibratedProbability", calibratedP)
                addProperty("strength", heads.getValue("baseStrength").getValue(label))
                addProperty("policyWeight", 1.0)
                addProperty("effectiveScore", eff)
            }
        }

        val (modelDominant, modelConfidence) = argmax(calibrated)
        val (dominant, _) = argmax(effective)
        val (actPrimary, actConfidence) = argmax(heads.getValue("dialogueAct"))
        val (targetValue, targetConfidence) = argmax(heads.getValue("speakerAffectTarget"))
        val (factualityValue, factualityConfidence) = argmax(heads.getValue("utteranceFactuality"))

        val refPresence = heads.getValue("referencePresence").getValue("present")
        var referencedAffect: JsonObject? = null
        if (refPresence >= REFERENCE_THRESHOLD) {
            val (refEmotion, _) = argmax(heads.getValue("referenceEmotion"))
            val (refHolder, _) = argmax(heads.getValue("referenceHolder"))
            val (refFactuality, _) = argmax(heads.getValue("referenceFactuality"))
            val emoConf = heads.getValue("referenceEmotion").getValue(refEmotion)
            val holderConf = heads.getValue("referenceHolder").getValue(refHolder)
            val factConf = heads.getValue("referenceFactuality").getValue(refFactuality)
            referencedAffect = JsonObject().apply {
                addProperty("emotion", refEmotion)
                addProperty("holder", refHolder)
                addProperty("factuality", refFactuality)
                addProperty("confidence", refPresence * cbrt(emoConf * holderConf * factConf))
                add("distributions", JsonObject().apply {
                    add("emotion", doubles(heads.getValue("referenceEmotion")))
                    add("holder", doubles(heads.getValue("referenceHolder")))
                    add("factuality", doubles(heads.getValue("referenceFactuality")))
                })
            }
        }

        val out = JsonObject()
        out.addProperty("schemaVersion", "2.1.1")
        out.addProperty("dominantEmotion", dominant)
        out.addProperty("modelDominantEmotion", modelDominant)
        out.addProperty("confidence", modelConfidence)
        out.addProperty("ambiguity", normalizedEntropy(calibrated.values))
        out.add("emotions", JsonObject().apply { emotions.forEach { (k, v) -> add(k, v) } })
        out.add("fineStates", doubles(heads.getValue("fineStrength")))
        out.add("dialogueAct", JsonObject().apply {
            addProperty("primary", actPrimary)
            addProperty("confidence", actConfidence)
            add("distribution", doubles(heads.getValue("dialogueAct")))
        })
        out.add("stances", doubles(heads.getValue("stances")))
        out.add("speakerAffectTarget", JsonObject().apply {
            addProperty("value", targetValue)
            addProperty("confidence", targetConfidence)
            add("distribution", doubles(heads.getValue("speakerAffectTarget")))
        })
        out.add("utteranceFactuality", JsonObject().apply {
            addProperty("value", factualityValue)
            addProperty("confidence", factualityConfidence)
            add("distribution", doubles(heads.getValue("utteranceFactuality")))
        })
        out.add("dimensions", JsonObject().apply {
            val dims = heads.getValue("dimensions")
            DIMENSION_LABELS.forEach { label ->
                var v = dims.getValue(label)
                if (label == "valence" || label == "dominance") v = v * 2 - 1
                addProperty(label, v)
            }
        })
        out.add("referencedAffect", referencedAffect ?: com.google.gson.JsonNull.INSTANCE)
        out.addProperty("referencePresence", refPresence)
        return out
    }

    // ------------------------------------------------------------------
    // hn：语法修正（显示层）
    // ------------------------------------------------------------------

    data class GrammarHint(
        val emotion: String,
        val term: String,
        val start: Int,
        val holder: String,
        val factuality: String,
    )

    /**
     * 原语义：asserted 的 speaker 情绪词把 effectiveScore 提到 ≥0.82（厌恶词除外）；
     * negated 全部 ×0.18 且 neutral ≥0.62；第三人称词写 referencedAffect，
     * 且无直述词时该情绪 ×0.15、neutral ≥0.66、attributionAdjusted=true。
     */
    fun applyGrammarFix(decoded: JsonObject, currentText: String): JsonObject {
        val hints = scanEmotionTerms(currentText)
        if (hints.isEmpty()) return decoded
        val emotions = decoded.getAsJsonObject("emotions")
        fun score(label: String): Double = emotions.getAsJsonObject(label)?.get("effectiveScore")?.asDouble ?: 0.0
        fun setScore(label: String, value: Double) {
            emotions.getAsJsonObject(label)?.addProperty("effectiveScore", value)
        }

        val speaker = hints.filter { it.holder == "speaker" }
        val others = hints.filter { it.holder != "speaker" }
        val asserted = speaker.filter { it.factuality != "negated" }
        val negated = speaker.filter { it.factuality == "negated" }
        val disgustTerms = setOf("讨厌", "嫌弃")

        for (hint in asserted) {
            if (hint.term in disgustTerms) continue
            setScore(hint.emotion, maxOf(score(hint.emotion), 0.82))
        }
        if (negated.isNotEmpty()) {
            for (hint in negated) setScore(hint.emotion, score(hint.emotion) * 0.18)
            setScore("neutral", maxOf(score("neutral"), 0.62))
        }
        if (others.isNotEmpty()) {
            val last = others.last()
            val confidence = if (last.factuality == "asserted") 0.82 else 0.9
            val existing = decoded.get("referencedAffect")?.takeIf { it.isJsonObject }?.asJsonObject
            val existingConfidence = existing?.get("confidence")?.takeIf { it.isJsonPrimitive }?.asDouble ?: -1.0
            if (existing == null || existingConfidence < confidence) {
                decoded.add("referencedAffect", JsonObject().apply {
                    addProperty("emotion", last.emotion)
                    addProperty("holder", last.holder)
                    addProperty("factuality", last.factuality)
                    addProperty("confidence", confidence)
                    addProperty("source", "high_precision_grammar")
                    existing?.get("distributions")?.let { add("distributions", it) }
                })
                decoded.addProperty(
                    "referencePresence",
                    maxOf(
                        decoded.get("referencePresence")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0,
                        confidence,
                    ),
                )
            }
            if (asserted.isEmpty()) {
                setScore(last.emotion, score(last.emotion) * 0.15)
                setScore("neutral", maxOf(score("neutral"), 0.66))
                decoded.addProperty("attributionAdjusted", true)
            }
        }
        // 与 JS 的稳定排序一致：按 effectiveScore 降序，平局按 basePresence 声明序。
        var best = BASE_PRESENCE_LABELS.first()
        var bestScore = score(BASE_PRESENCE_LABELS.first())
        for (label in BASE_PRESENCE_LABELS.drop(1)) {
            val s = score(label)
            if (s > bestScore) {
                best = label
                bestScore = s
            }
        }
        decoded.addProperty("dominantEmotion", best)
        val hintsArray = JsonArray()
        hints.forEach {
            hintsArray.add(JsonObject().apply {
                addProperty("emotion", it.emotion)
                addProperty("term", it.term)
                addProperty("start", it.start)
                addProperty("holder", it.holder)
                addProperty("factuality", it.factuality)
            })
        }
        decoded.add("grammarHints", hintsArray)
        return decoded
    }

    /** 归因弃权的确定性规则（Spec §3.2）：语法层判为第三人称/非直述，
     *  而模型仍按“对 listener 的直述敌意”给出可触发路由的分数 → 弃权。 */
    fun attributionConflict(decoded: JsonObject): String? {
        val adjusted = decoded.get("attributionAdjusted")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        if (!adjusted) return null
        val stances = decoded.get("stances")?.takeIf { it.isJsonObject }?.asJsonObject
        val hostile = stances?.get("hostile")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0
        val toxicity = decoded.get("dimensions")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("toxicity")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0
        val target = decoded.get("speakerAffectTarget")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("value")?.takeIf { it.isJsonPrimitive }?.asString
        if (hostile >= 0.70 && toxicity >= 0.55 && target == "listener") {
            return "grammar_third_party_conflict"
        }
        return null
    }

    // ------------------------------------------------------------------
    // 情绪词表与 holder/factuality 启发式（ei/ii/si/li）
    // ------------------------------------------------------------------

    private val EMOTION_TERMS = linkedMapOf(
        "joy" to Regex("开心|高兴|快乐|兴奋|喜悦|欣喜|太好了"),
        "affection" to Regex("喜欢|喜爱|爱上|在乎|心疼"),
        "sadness" to Regex("难过|伤心|失落|悲伤|痛苦|想哭|哭了"),
        "anger" to Regex("生气|愤怒|气愤|气死|火大|恼火"),
        "fear" to Regex("害怕|恐惧|焦虑|担心|发慌|紧张"),
        "disgust" to Regex("讨厌|恶心|反感|嫌弃"),
        "surprise" to Regex("惊讶|震惊|吃惊|没想到|意外"),
    )
    private val SPEAKER_RE = Regex("我|本人|咱|我们")
    private val LISTENER_RE = Regex("你|您|你们")
    private val THIRD_RE =
        Regex("他|她|它|他们|她们|爸爸|妈妈|父亲|母亲|哥哥|姐姐|弟弟|妹妹|朋友|同事|老师|孩子|宝宝")
    private val REPORTED_RE = Regex("说|表示|告诉|听说|据说|提到|转述")
    private val HYPOTHETICAL_RE =
        Regex("是不是|是否|会不会|会|可能|如果|好像|看起来|似乎|吗|嘛|么|\\?")
    private val NEGATED_SUFFIX_RE = Regex("(?:不|没|没有|并非|无|别|不要|才不)(?:太|很|再)?\\z")

    private fun lastMatch(regex: Regex, text: String): MatchResult? =
        regex.findAll(text).lastOrNull()

    internal fun holderOf(prefix: String): String {
        val tail = prefix.takeLast(10)
        data class Candidate(val holder: String, val index: Int, val length: Int)
        val candidates = buildList {
            lastMatch(SPEAKER_RE, tail)?.let { add(Candidate("speaker", it.range.first, it.value.length)) }
            lastMatch(LISTENER_RE, tail)?.let { add(Candidate("listener", it.range.first, it.value.length)) }
            lastMatch(THIRD_RE, tail)?.let { add(Candidate("third_party", it.range.first, it.value.length)) }
        }
        if (candidates.isEmpty()) return "speaker"
        // JS：按最后出现位置取最晚者（index 降序，同 index 取先列入者——speaker 优先）。
        return candidates.sortedWith(compareByDescending<Candidate> { it.index })
            .let { sorted -> sorted.first().holder }
    }

    internal fun factualityOf(prefix: String, suffix: String): String {
        val r = prefix.takeLast(7)
        val window = prefix.takeLast(12)
        return when {
            HYPOTHETICAL_RE.containsMatchIn(r + suffix.take(4)) -> "hypothetical"
            REPORTED_RE.containsMatchIn(window) -> "reported"
            NEGATED_SUFFIX_RE.containsMatchIn(r) -> "negated"
            else -> "asserted"
        }
    }

    fun scanEmotionTerms(text: String): List<GrammarHint> {
        val t = text
        val hints = mutableListOf<GrammarHint>()
        for ((emotion, regex) in EMOTION_TERMS) {
            for (match in regex.findAll(t)) {
                val start = match.range.first
                val prefix = t.substring(0, start)
                val suffix = t.substring(start + match.value.length)
                hints += GrammarHint(
                    emotion = emotion,
                    term = match.value,
                    start = start,
                    holder = holderOf(prefix),
                    factuality = factualityOf(prefix, suffix),
                )
            }
        }
        return hints.sortedBy { it.start }
    }

    // ------------------------------------------------------------------
    // 数学原语（Ar/Bi/Ca/Mi）
    // ------------------------------------------------------------------

    private const val REFERENCE_THRESHOLD = 0.55

    internal fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x.coerceIn(-30.0, 30.0)))

    internal fun softmax(values: List<Double>): List<Double> {
        val max = values.max()
        val exps = values.map { exp(it - max) }
        val sum = exps.sum()
        return exps.map { it / sum }
    }

    private fun logit(p: Double): Double {
        val clamped = p.coerceIn(1e-6, 0.999999)
        return ln(clamped / (1 - clamped))
    }

    private fun normalizedEntropy(values: Collection<Double>): Double {
        if (values.size < 2) return 0.0
        val sum = values.sum().coerceAtLeast(1e-8)
        var entropy = 0.0
        for (v in values) {
            val p = v / sum
            if (p > 0) entropy += p * ln(p)
        }
        return -entropy / ln(values.size.toDouble())
    }

    private fun argmax(map: Map<String, Double>): Pair<String, Double> {
        var bestKey = map.keys.first()
        var bestValue = Double.NEGATIVE_INFINITY
        for ((k, v) in map) {
            if (v > bestValue) {
                bestKey = k
                bestValue = v
            }
        }
        return bestKey to bestValue
    }

    private fun doubles(map: Map<String, Double>): JsonObject = JsonObject().apply {
        map.forEach { (k, v) -> addProperty(k, v) }
    }

    private fun labelsFor(head: String): List<String> = when (head) {
        "basePresence", "baseStrength", "referenceEmotion" -> BASE_PRESENCE_LABELS
        "fineStrength" -> FINE_LABELS
        "dialogueAct" -> DIALOGUE_ACT_LABELS
        "stances" -> STANCE_LABELS
        "speakerAffectTarget" -> TARGET_LABELS
        "utteranceFactuality", "referenceFactuality" -> FACTUALITY_LABELS
        "dimensions" -> DIMENSION_LABELS
        "referencePresence" -> listOf("present")
        "referenceHolder" -> HOLDER_LABELS
        else -> throw IllegalArgumentException("unknown head $head")
    }
}
