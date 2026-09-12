package com.loyea.plugin.modulator

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.expm1
import kotlin.math.ln

// ---------------------------------------------------------------------------
// JSON 小工具（文件内私有；只服务检查点序列化/反序列化）
// ---------------------------------------------------------------------------

private fun num(x: Double): JsonPrimitive {
    if (!x.isFinite()) throw IllegalArgumentException("non-finite value in checkpoint")
    return JsonPrimitive(x)
}

private fun jstr(s: String): JsonPrimitive = JsonPrimitive(s)

private fun JsonObject.sortedString(name: String): String {
    val el = get(name) ?: throw IllegalArgumentException("missing $name")
    if (!el.isJsonPrimitive || !el.asJsonPrimitive.isString) throw IllegalArgumentException("$name: string required")
    return el.asJsonPrimitive.asString
}

private fun JsonObject.sortedObject(name: String): JsonObject {
    val el = get(name) ?: throw IllegalArgumentException("missing $name")
    if (!el.isJsonObject) throw IllegalArgumentException("$name: object required")
    return el.asJsonObject
}

private fun JsonObject.sortedArray(name: String): JsonArray {
    val el = get(name) ?: throw IllegalArgumentException("missing $name")
    if (!el.isJsonArray) throw IllegalArgumentException("$name: array required")
    return el.asJsonArray
}

private fun JsonObject.sortedDouble(name: String): Double {
    val el = get(name) ?: throw IllegalArgumentException("missing $name")
    if (!el.isJsonPrimitive || !el.asJsonPrimitive.isNumber) throw IllegalArgumentException("$name: number required")
    val v = el.asJsonPrimitive.asDouble
    if (!v.isFinite()) throw IllegalArgumentException("$name: finite number required")
    return v
}

/** 严格整数：拒绝 JSON 里的 1.0 / 1e2 之类浮点写法。 */
private fun JsonObject.sortedIntegral(name: String): Double {
    val el = get(name) ?: throw IllegalArgumentException("missing $name")
    if (!el.isJsonPrimitive || !el.asJsonPrimitive.isNumber) throw IllegalArgumentException("$name: integer required")
    val lexeme = el.asJsonPrimitive.toString()
    if (lexeme.any { it == '.' || it == 'e' || it == 'E' }) throw IllegalArgumentException("$name: integer required")
    val v = el.asJsonPrimitive.asDouble
    if (!v.isFinite()) throw IllegalArgumentException("$name: integer required")
    return v
}

// ---------------------------------------------------------------------------
// 解析恢复（Spec §8.1）：f'=-a(f-b), m'=c(f-m) 的解析解；不用 Euler 小步积分。
// ---------------------------------------------------------------------------

fun evolve(state: Snapshot, at: Double, p: Personality) {
    finite(at, "time")
    if (at < state.at) throw IllegalArgumentException("time moved backwards: normalize host clock or replay")
    val dt = at - state.at
    if (dt == 0.0) return
    val (fh, mh) = p.halfLives()
    val baseline = p.baseline()
    for (i in 0..3) {
        val a = ln(2.0) / fh[i]
        val c = ln(2.0) / mh[i]
        val ea = exp(-a * dt)
        val ec = exp(-c * dt)
        val coupling = if (abs(a - c) < 1e-12) c * dt * ec else c * (ea - ec) / (c - a)
        val oldF = state.fast[i] - baseline[i]
        state.mood[i] = baseline[i] + (state.mood[i] - baseline[i]) * ec + oldF * coupling
        state.fast[i] = baseline[i] + oldF * ea
        val (lo, hi) = ModulatorVocab.BOUNDS[i]
        // 只有浮点舍入；解析动力学本身保持有界。
        state.fast[i] = clip(state.fast[i], lo, hi)
        state.mood[i] = clip(state.mood[i], lo, hi)
    }
    for (t in state.traces) {
        t.strength *= exp(-ln(2.0) * dt / t.halfLife)
    }
    state.traces.removeAll { it.strength < ModulatorVocab.TRACE_PRUNE }
    state.at = at
}

// ---------------------------------------------------------------------------
// 强度滞回分档（Spec §9.4）
// ---------------------------------------------------------------------------

fun quantize(state: Snapshot, key: String, value: Double): String {
    if (value == 0.0) {
        state.labelLevels.remove(key)
        return "none"
    }
    var new = ModulatorVocab.THRESHOLDS.count { value >= it }
    val old = state.labelLevels[key]
    if (old != null) {
        if (new > old) {
            new = old
            while (new < 3 && value >= ModulatorVocab.THRESHOLDS[new] + ModulatorVocab.HYSTERESIS) new++
        } else if (new < old) {
            new = old
            while (new > 0 && value < ModulatorVocab.THRESHOLDS[new - 1] - ModulatorVocab.HYSTERESIS) new--
        }
    }
    state.labelLevels[key] = new
    return ModulatorVocab.LEVELS[new]
}

// ---------------------------------------------------------------------------
// 状态表编译（Spec §9）：关系两行、背景一行、当前感受最多两行。
// ---------------------------------------------------------------------------

fun compileRows(state: Snapshot, ctx: Context, visible: Set<String>): List<Row> {
    val r = ctx.relations
    val rows = mutableListOf<Row>()

    // 关系值保持只读；绝不把喜爱自动晋级为爱。
    val relationSpecs = listOf(
        "relationship_affect" to (r.affect to r.affectStrength),
        "relationship_trust" to (r.trust to r.trustStrength),
    )
    for ((aspect, pair) in relationSpecs) {
        var label = pair.first
        var value = pair.second
        if (aspect == "relationship_trust" && r.scope != "general" && r.scope != ctx.trustScope) {
            label = "unspecified"
            value = null
        }
        rows += Row(aspect, label, if (value == null) "—" else quantize(state, "$aspect:$label", value))
    }

    val v = state.mood[0]
    val a = state.mood[1]
    val t = state.mood[2]
    val i = state.mood[3]
    // 派生标签评分不产生额外的动力学状态变量。
    val scores = linkedMapOf(
        "cheerful" to maxOf(0.0, v),
        "gloomy" to maxOf(0.0, -v) * (1 - 0.5 * a),
        "tense" to t,
        "irritable" to i,
        "calm" to minOf(maxOf(0.0, v), maxOf(0.0, 0.35 - a), maxOf(0.0, 0.20 - t)) * 1.5,
    )
    val old = state.moodLabel
    val eligible = scores.filter { (k, score) ->
        score >= if (k == old) ModulatorVocab.MOOD_EXIT else ModulatorVocab.MOOD_ENTER
    }
    if (eligible.isNotEmpty()) {
        var bestKey: String? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for ((k, score) in eligible) {
            val biased = score + if (k == old) ModulatorVocab.SELECTION_BIAS else 0.0
            if (bestKey == null || biased > bestScore || (biased == bestScore && k > bestKey)) {
                bestKey = k
                bestScore = biased
            }
        }
        state.moodLabel = bestKey!!
        rows += Row("mood", bestKey, quantize(state, "mood:$bestKey", scores.getValue(bestKey)))
    } else {
        state.moodLabel = "neutral"
        rows += Row("mood", "neutral", "—")
    }

    // 标签保留因果证据；不从 V/A 单独解码内疚或愤怒。
    val feelings = linkedMapOf<String, Double>()
    for (tr in state.traces) {
        if (tr.topicId != ctx.topicId || tr.evidenceIds.none { it in visible }) continue
        val label = Rules.ALL.getValue(tr.kind).label
        feelings[label] = maxOf(feelings[label] ?: 0.0, tr.strength)
    }
    val candidates = feelings.filter { (k, score) ->
        score >= if (k in state.selectedFeelings) ModulatorVocab.FEELING_EXIT else ModulatorVocab.FEELING_ENTER
    }
    val chosen = candidates.keys.sortedWith(
        compareBy(
            { -(candidates.getValue(it) + if (it in state.selectedFeelings) ModulatorVocab.SELECTION_BIAS else 0.0) },
            { it },
        ),
    ).take(2)
    state.selectedFeelings.clear()
    state.selectedFeelings.addAll(chosen)
    for (label in chosen) {
        rows += Row("feeling", label, quantize(state, "feeling:$label", feelings.getValue(label)))
    }
    // 持久量化器簿记由有限词表约束。
    return rows
}

// ---------------------------------------------------------------------------
// 世界书语义选择（Spec §10）：只处理已解析的单一活动世界书候选。
// ---------------------------------------------------------------------------

fun selectLore(
    decision: Decision,
    ctx: Context,
    rules: List<LoreRule>,
    maxEntries: Int = 6,
    charBudget: Int = 1600,
): List<String> {
    if (rules.size > 128) throw IllegalArgumentException("semantic extension supports at most 128 rules per active book")
    if (rules.map { it.entryId }.toSet().size != rules.size) throw IllegalArgumentException("duplicate lore entry id")
    val ids = decision.rows.filter { it.intensity != "none" }.map { "${it.aspect}/${it.state}" }.toSet()
    val candidates = rules
        .filter { rule ->
            rule.enabled && (
                rule.always || (
                    (rule.statesAny.isEmpty() || rule.statesAny.intersect(ids).isNotEmpty()) &&
                        ctx.activeTags.containsAll(rule.contextAll)
                    )
                )
        }
        .sortedWith(compareBy({ -it.priority }, { it.entryId }))
    val chosen = mutableListOf<String>()
    val groups = mutableSetOf<String>()
    var size = 0
    for (rule in candidates) {
        if (rule.group.isNotEmpty() && rule.group in groups) continue
        val cost = rule.text.codePointCount(0, rule.text.length)
        if (chosen.size >= maxEntries || size + cost > charBudget) continue
        chosen += rule.entryId
        size += cost
        if (rule.group.isNotEmpty()) groups += rule.group
    }
    return chosen
}

// ---------------------------------------------------------------------------
// 调制器引擎：事务、重试与检查点（Spec §11）
// ---------------------------------------------------------------------------

class Modulator(
    val personality: Personality = Personality(),
    at: Double = 0.0,
) {
    private val initialAt: Double = finite(at, "initial time")

    var state: Snapshot = Snapshot(
        initialAt,
        personality.profileVersion,
        personality.baseline().toMutableList(),
        personality.baseline().toMutableList(),
    )
        private set

    var lastDecision: Decision? = null
        private set

    /** 测试与宿主迁移需要显式注入状态；保持整体替换，不做部分更新。 */
    internal fun injectStateForTest(s: Snapshot) {
        state = s
    }

    /** 处理一条已提交观测；同一 event_id+seq 的重试返回已缓存结果，不再推进状态。 */
    fun process(obs: Observation): Decision {
        if (obs.seq < 0 || obs.eventId.isEmpty()) {
            throw IllegalArgumentException("seq and event_id are required")
        }
        finite(obs.at, "observation time")
        identifier(obs.eventId, "event_id")
        val current = state
        if (obs.seq == current.lastSeq && obs.eventId == current.lastEventId) {
            return lastDecision ?: throw IllegalArgumentException("retry must use persisted turn decision")
        }
        if (obs.seq <= current.lastSeq) {
            throw IllegalArgumentException("stale or conflicting sequence: use historical snapshot/replay")
        }
        if (obs.eventId == current.lastEventId) {
            throw IllegalArgumentException("same event id with a new sequence")
        }
        if (obs.at < current.at) {
            throw IllegalArgumentException("out-of-order event: replay required")
        }
        if (current.profileVersion != personality.profileVersion) {
            throw IllegalArgumentException("personality version change requires explicit rebase")
        }
        if (obs.facts.size > 8 || obs.context.visibleEvidence.size > 128 || obs.context.activeTags.size > 64) {
            throw IllegalArgumentException("observation exceeds bounded input contract")
        }
        identifier(obs.context.topicId, "topic_id")
        identifier(obs.context.scene, "scene")
        identifier(obs.context.trustScope, "trust_scope")
        for (item in obs.context.visibleEvidence + obs.context.activeTags) {
            identifier(item, "context item")
        }
        obs.context.relations.validate()

        // 写时复制：非法数据不能部分推进实时状态。
        val s = current.copy()
        evolve(s, obs.at, personality)
        val (events, appraisalAudit) = appraise(obs, s)
        val audit = appraisalAudit.toMutableList()
        if (events.size > ModulatorVocab.MAX_EVENTS_PER_TURN) {
            throw IllegalArgumentException("at most eight semantic events per admitted observation")
        }

        // 同一观测内完全相同的 (kind, evidence_id, target) 去重。
        val seen = HashSet<Triple<String, String, String>>()
        val unique = mutableListOf<Appraisal>()
        for (e in events) {
            val key = Triple(e.kind, e.evidenceId, e.target)
            if (key !in seen) {
                unique += e
                seen += key
            }
        }

        val amplitudes = mutableListOf<Pair<Appraisal, Double>>()
        for (e in unique) {
            val rule = Rules.ALL.getValue(e.kind)
            var q = e.strength * gate(e.confidence) * personality.gain(rule.family)
            if (e.kind == "future_threat") q *= 1 + 0.15 * s.mood[2]
            if (e.kind == "hostility") q *= 1 + 0.10 * s.mood[3]
            val previous = s.traces.firstOrNull {
                it.kind == e.kind && it.target == e.target && it.topicId == e.topicId
            }
            if (previous != null) {
                val elapsed = maxOf(0.0, obs.at - previous.lastEventAt)
                q *= 0.25 + 0.75 * minOf(1.0, elapsed / ModulatorVocab.REFRACTORY_SECONDS)
            }
            q = clip(q, 0.0, ModulatorVocab.IMPULSE_CAP)
            if (q > 0) {
                amplitudes += e to q
                audit += String.format(Locale.ROOT, "appraisal:%s:amplitude=%.6f", e.kind, q)
            }
        }

        // 同轮稀疏更新：结果与轮内事件顺序无关。
        val before = s.fast.toList()
        for ((j, axis) in ModulatorVocab.AXES.withIndex()) {
            val applicable = amplitudes.mapNotNull { (e, q) ->
                Rules.ALL.getValue(e.kind).targets[axis]?.let { it to q }
            }
            if (applicable.isNotEmpty()) {
                val weight = compensatedSum(applicable.map { it.second })
                val anchor = compensatedSum(applicable.map { (target, q) -> target * q }) / weight
                val alpha = minOf(ModulatorVocab.BLEND_CAP, -expm1(-ModulatorVocab.BLEND_RATE * weight))
                s.fast[j] = (1 - alpha) * before[j] + alpha * anchor
            }
        }

        // 显式修复只按证据关联衰减痕迹，绝不全局清空记忆。
        for ((e, q) in amplitudes) {
            if (e.resolves.isEmpty()) continue
            for (tr in s.traces) {
                if (e.resolves.any { it in tr.evidenceIds }) {
                    tr.strength *= 1 - ModulatorVocab.RESOLUTION_DAMP * q
                }
            }
        }

        for ((e, q) in amplitudes) {
            val rule = Rules.ALL.getValue(e.kind)
            val tr = s.traces.firstOrNull {
                it.kind == e.kind && it.target == e.target && it.topicId == e.topicId
            }
            if (tr != null) {
                tr.strength = minOf(ModulatorVocab.TRACE_CAP, 1 - (1 - tr.strength) * (1 - q))
                val merged = LinkedHashSet(tr.evidenceIds)
                merged += e.evidenceId
                tr.evidenceIds.clear()
                tr.evidenceIds.addAll(merged.toList().takeLast(ModulatorVocab.MAX_EVIDENCE_PER_TRACE))
                tr.lastEventAt = obs.at
            } else {
                s.traces += Trace(e.kind, e.target, e.topicId, q, rule.halfLife, mutableListOf(e.evidenceId), obs.at)
            }
        }
        s.traces.sortWith(compareBy({ -it.strength }, { it.kind }, { it.target }, { it.topicId }))
        while (s.traces.size > ModulatorVocab.MAX_TRACES) {
            s.traces.removeAt(s.traces.size - 1)
        }

        val visible = HashSet(obs.context.visibleEvidence)
        visible += obs.eventId
        for ((e, _) in amplitudes) visible += e.evidenceId

        val rows = compileRows(s, obs.context, visible)
        s.lastSeq = obs.seq
        s.lastEventId = obs.eventId
        val decision = Decision(obs.eventId, obs.seq, rows, audit)
        state = s
        lastDecision = decision
        return decision
    }

    /** 无新输入时按经过时间推进恢复；不产生任何新刺激。 */
    fun idleTo(at: Double) {
        val s = state.copy()
        evolve(s, at, personality)
        state = s
    }

    fun dumps(): String {
        val root = JsonObject()

        val personalityJson = JsonObject()
        personalityJson.add("agreeableness", num(personality.agreeableness))
        personalityJson.add("conscientiousness", num(personality.conscientiousness))
        personalityJson.add("extraversion", num(personality.extraversion))
        personalityJson.add("neuroticism", num(personality.neuroticism))
        personalityJson.add("openness", num(personality.openness))
        personalityJson.add("plasticity", num(personality.plasticity))
        personalityJson.add("profile_version", jstr(personality.profileVersion))
        personalityJson.add("total_interactions", JsonPrimitive(personality.totalInteractions))

        val st = state
        val stateJson = JsonObject()
        stateJson.add("at", num(st.at))
        stateJson.add("fast", JsonArray().apply { st.fast.forEach { add(num(it)) } })
        stateJson.add("mood", JsonArray().apply { st.mood.forEach { add(num(it)) } })
        val labelLevelsJson = JsonObject()
        st.labelLevels.keys.sorted().forEach { labelLevelsJson.add(it, JsonPrimitive(st.labelLevels.getValue(it))) }
        stateJson.add("label_levels", labelLevelsJson)
        stateJson.add("last_event_id", jstr(st.lastEventId))
        stateJson.add("last_seq", JsonPrimitive(st.lastSeq))
        val tracesJson = JsonArray()
        for (t in st.traces) {
            val tj = JsonObject()
            tj.add("evidence_ids", JsonArray().apply { t.evidenceIds.forEach { add(jstr(it)) } })
            tj.add("half_life", num(t.halfLife))
            tj.add("kind", jstr(t.kind))
            tj.add("last_event_at", num(t.lastEventAt))
            tj.add("strength", num(t.strength))
            tj.add("target", jstr(t.target))
            tj.add("topic_id", jstr(t.topicId))
            tracesJson.add(tj)
        }
        stateJson.add("traces", tracesJson)
        stateJson.add("mood_label", jstr(st.moodLabel))
        stateJson.add("profile_version", jstr(st.profileVersion))
        stateJson.add("selected_feelings", JsonArray().apply { st.selectedFeelings.forEach { add(jstr(it)) } })

        val decisionJson: JsonElement = lastDecision?.let { d ->
            val dj = JsonObject()
            dj.add("audit", JsonArray().apply { d.audit.forEach { add(jstr(it)) } })
            dj.add("event_id", jstr(d.eventId))
            val rowsJson = JsonArray()
            for (r in d.rows) {
                val rj = JsonObject()
                rj.add("aspect", jstr(r.aspect))
                rj.add("intensity", jstr(r.intensity))
                rj.add("state", jstr(r.state))
                rowsJson.add(rj)
            }
            dj.add("rows", rowsJson)
            dj.add("seq", JsonPrimitive(d.seq))
            dj
        } ?: JsonNull.INSTANCE

        root.add("last_decision", decisionJson)
        root.add("personality", personalityJson)
        root.add("state", stateJson)
        root.add("version", jstr(ModulatorVocab.VERSION))
        return GSON.toJson(root)
    }

    companion object {
        private val GSON = com.google.gson.GsonBuilder().disableHtmlEscaping().create()

        /** 从检查点恢复；坏检查点绝不部分载入（Spec §11.3）。 */
        fun loads(raw: String): Modulator {
            if (raw.length > 65536) throw IllegalArgumentException("oversized checkpoint")
            val obj = JsonParser.parseString(raw).asJsonObject
            if (obj.sortedString("version") != ModulatorVocab.VERSION) {
                throw IllegalArgumentException("unsupported checkpoint version")
            }

            val personalityJson = obj.sortedObject("personality")
            val personality = Personality(
                openness = personalityJson.sortedDouble("openness"),
                conscientiousness = personalityJson.sortedDouble("conscientiousness"),
                extraversion = personalityJson.sortedDouble("extraversion"),
                agreeableness = personalityJson.sortedDouble("agreeableness"),
                neuroticism = personalityJson.sortedDouble("neuroticism"),
                plasticity = personalityJson.sortedDouble("plasticity"),
                totalInteractions = personalityJson.sortedIntegral("total_interactions").toInt().also {
                    if (it < 0) throw IllegalArgumentException("total_interactions: nonnegative integer required")
                },
                profileVersion = personalityJson.sortedString("profile_version"),
            )

            val d = obj.sortedObject("state")
            val at = finite(d.sortedDouble("at"), "at")
            for (name in listOf("fast", "mood")) {
                val arr = d.sortedArray(name)
                if (arr.size() != 4) throw IllegalArgumentException("corrupt state dimension")
                for (k in 0 until 4) {
                    val value = finite(arr.get(k).asDouble, name)
                    val (lo, hi) = ModulatorVocab.BOUNDS[k]
                    if (value < lo || value > hi) throw IllegalArgumentException("corrupt state bound")
                }
            }
            val tracesJson = d.sortedArray("traces")
            if (tracesJson.size() > ModulatorVocab.MAX_TRACES) throw IllegalArgumentException("corrupt bounded cache")
            val labelLevelsJson = d.sortedObject("label_levels")
            if (labelLevelsJson.entrySet().size > 64) throw IllegalArgumentException("corrupt bounded cache")
            val allowedKeys = ModulatorVocab.ASPECT_STATES
                .flatMap { (aspect, labels) -> labels.map { "$aspect:$it" } }
                .toSet()
            for ((key, el) in labelLevelsJson.entrySet()) {
                val primitive = el as? JsonPrimitive
                val value = primitive?.let { if (it.isNumber) it.asInt else -1 } ?: -1
                val lexeme = primitive?.toString() ?: ""
                if (key !in allowedKeys || lexeme.any { it == '.' || it == 'e' || it == 'E' } || value !in 0..3) {
                    throw IllegalArgumentException("corrupt quantizer state")
                }
            }
            val moodLabel = d.sortedString("mood_label")
            if (moodLabel !in ModulatorVocab.ASPECT_STATES.getValue("mood")) {
                throw IllegalArgumentException("corrupt selected labels")
            }
            val selected = d.sortedArray("selected_feelings").map { it.asString }
            if (selected.size > 2 || !selected.all { it in ModulatorVocab.FEELING_IDS }) {
                throw IllegalArgumentException("corrupt selected labels")
            }
            val lastSeq = d.sortedIntegral("last_seq").toLong()
            if (lastSeq < -1) throw IllegalArgumentException("corrupt last sequence")
            val lastEventId = d.sortedString("last_event_id")

            val traces = tracesJson.map { el ->
                val t = el.asJsonObject
                val kind = t.sortedString("kind")
                val rule = Rules.ALL[kind] ?: throw IllegalArgumentException("corrupt trace")
                val evidenceIds = t.sortedArray("evidence_ids").map { it.asString }
                if (evidenceIds.isEmpty() || evidenceIds.size > ModulatorVocab.MAX_EVIDENCE_PER_TRACE) {
                    throw IllegalArgumentException("corrupt trace")
                }
                val strength = unit(t.sortedDouble("strength"), "trace strength")
                val target = t.sortedString("target")
                val topicId = t.sortedString("topic_id")
                evidenceIds.forEach { identifier(it, "trace evidence") }
                identifier(target, "trace target")
                identifier(topicId, "trace topic")
                val halfLife = t.sortedDouble("half_life")
                val lastEventAt = t.sortedDouble("last_event_at")
                if (halfLife <= 0 || lastEventAt > at) throw IllegalArgumentException("corrupt trace time")
                if (halfLife != rule.halfLife) throw IllegalArgumentException("checkpoint rule mismatch")
                Trace(kind, target, topicId, strength, halfLife, evidenceIds.toMutableList(), lastEventAt)
            }

            val profileVersion = d.sortedString("profile_version")
            if (profileVersion != personality.profileVersion) {
                throw IllegalArgumentException("checkpoint profile mismatch")
            }

            val engine = Modulator(personality, at)
            engine.injectStateForTest(
                Snapshot(
                    at, profileVersion,
                    d.sortedArray("fast").map { it.asDouble }.toMutableList(),
                    d.sortedArray("mood").map { it.asDouble }.toMutableList(),
                    traces.toMutableList(),
                    lastSeq, lastEventId,
                    labelLevelsJson.entrySet().associate { (k, el) ->
                        k to (el as JsonPrimitive).asInt
                    }.toMutableMap(),
                    selected.toMutableList(),
                    moodLabel,
                ),
            )

            val dec = obj.get("last_decision")
            if (dec != null && dec.isJsonObject) {
                val dj = dec.asJsonObject
                val decSeq = dj.sortedIntegral("seq").toLong()
                val decEventId = dj.sortedString("event_id")
                if (decSeq != lastSeq || decEventId != lastEventId) {
                    throw IllegalArgumentException("checkpoint decision mismatch")
                }
                identifier(decEventId)
                val rowsJson = dj.sortedArray("rows")
                val auditJson = dj.sortedArray("audit")
                if (rowsJson.size() !in 3..5 || auditJson.size() > 32) {
                    throw IllegalArgumentException("corrupt decision size")
                }
                val rows = rowsJson.map { el ->
                    val r = el.asJsonObject
                    Row(r.sortedString("aspect"), r.sortedString("state"), r.sortedString("intensity"))
                }
                if (rows.take(3).map { it.aspect } != listOf("relationship_affect", "relationship_trust", "mood") ||
                    rows.drop(3).any { it.aspect != "feeling" }
                ) {
                    throw IllegalArgumentException("corrupt decision aspects")
                }
                for (row in rows) {
                    if (row.state !in ModulatorVocab.ASPECT_STATES.getValue(row.aspect) ||
                        row.intensity !in ModulatorVocab.LEVEL_ZH
                    ) {
                        throw IllegalArgumentException("corrupt decision vocabulary")
                    }
                    val placeholder = row.state == "neutral" || row.state == "unspecified"
                    if (placeholder != (row.intensity == "—")) {
                        throw IllegalArgumentException("corrupt placeholder semantics")
                    }
                }
                val audit = auditJson.map { it.asString }
                engine.commitDecision(Decision(decEventId, decSeq, rows, audit))
            } else if (dec != null && !dec.isJsonNull) {
                throw IllegalArgumentException("corrupt last decision")
            } else if (lastSeq != -1L || lastEventId.isNotEmpty()) {
                throw IllegalArgumentException("missing committed decision")
            }
            return engine
        }

        private fun Modulator.commitDecision(decision: Decision) {
            lastDecision = decision
        }
    }
}

/** 插件元数据（宿主以后通过这层契约接入，不改聊天链路）。 */
object ModulatorPlugin {
    const val ID: String = "loyea-modulator"
    const val NAME: String = "Loyea 情绪调制器"
    const val VERSION: String = ModulatorVocab.VERSION
    const val STATE_REPRESENTATION: String = "0.2"
    const val LEGACY_MODEL_METADATA: String = "2.1.0"
    const val LEGACY_DECODER: String = "2.1.1"
    const val SPEC: String = "docs/loyea_modulator/Loyea_Modulator_Spec_v1.0.md"
}

// ---------------------------------------------------------------------------
// 参数登记表（Spec §5.3；与 prototype 的 config_dict 一致，不是热更新引擎）
// ---------------------------------------------------------------------------

fun configDictionary(): JsonObject {
    val root = JsonObject()
    root.add("version", jstr(ModulatorVocab.VERSION))
    root.add("axes", JsonArray().apply { ModulatorVocab.AXES.forEach { add(jstr(it)) } })
    root.add("bounds", JsonArray().apply {
        ModulatorVocab.BOUNDS.forEach { (lo, hi) ->
            add(JsonArray().apply { add(num(lo)); add(num(hi)) })
        }
    })
    root.add("fast_half_lives_seconds", JsonArray().apply { ModulatorVocab.FAST_HALFLIVES.forEach { add(num(it)) } })
    root.add("mood_half_lives_seconds", JsonArray().apply { ModulatorVocab.MOOD_HALFLIVES.forEach { add(num(it)) } })
    root.add("intensity_boundaries", JsonArray().apply { ModulatorVocab.THRESHOLDS.forEach { add(num(it)) } })
    root.add("intensity_hysteresis", num(ModulatorVocab.HYSTERESIS))
    root.add("feeling_enter", num(ModulatorVocab.FEELING_ENTER))
    root.add("feeling_exit", num(ModulatorVocab.FEELING_EXIT))
    root.add("selection_bias", num(ModulatorVocab.SELECTION_BIAS))
    root.add("mood_enter", num(ModulatorVocab.MOOD_ENTER))
    root.add("mood_exit", num(ModulatorVocab.MOOD_EXIT))
    root.add("max_traces", JsonPrimitive(ModulatorVocab.MAX_TRACES))
    root.add("max_source_ids_per_trace", JsonPrimitive(ModulatorVocab.MAX_EVIDENCE_PER_TRACE))
    root.add("trace_prune", num(ModulatorVocab.TRACE_PRUNE))
    root.add("trace_cap", num(ModulatorVocab.TRACE_CAP))
    root.add("impulse_cap", num(ModulatorVocab.IMPULSE_CAP))
    root.add("blend_cap", num(ModulatorVocab.BLEND_CAP))
    root.add("blend_rate", num(ModulatorVocab.BLEND_RATE))
    root.add("refractory_seconds", num(ModulatorVocab.REFRACTORY_SECONDS))
    root.add("refractory_floor", num(ModulatorVocab.REFRACTORY_FLOOR))
    val rules = JsonObject()
    for ((kind, rule) in Rules.ALL) {
        val rj = JsonObject()
        rj.add("label", jstr(rule.label))
        rj.add("family", jstr(rule.family))
        val targets = JsonObject()
        rule.targets.forEach { (axis, target) -> targets.add(axis, num(target)) }
        rj.add("targets", targets)
        rj.add("half_life", num(rule.halfLife))
        rj.add("source", jstr(rule.source))
        rules.add(kind, rj)
    }
    root.add("rules", rules)
    val legacy = JsonObject()
    legacy.add("model_metadata", jstr(ModulatorPlugin.LEGACY_MODEL_METADATA))
    legacy.add("decoder", jstr(ModulatorPlugin.LEGACY_DECODER))
    root.add("legacy_versions", legacy)
    return root
}
