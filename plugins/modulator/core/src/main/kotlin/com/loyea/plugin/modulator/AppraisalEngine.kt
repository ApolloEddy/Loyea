package com.loyea.plugin.modulator

import com.google.gson.JsonElement
import com.google.gson.JsonObject

// ---------------------------------------------------------------------------
// 感知结果校验：只校验被消费的分数（Spec §6.1）。
// ---------------------------------------------------------------------------

private fun JsonObject.objOrEmpty(name: String): JsonObject {
    val el = get(name) ?: return JsonObject()
    if (!el.isJsonObject) throw IllegalArgumentException("$name: object required")
    return el.asJsonObject
}

private fun unitJson(el: JsonElement, name: String): Double {
    if (!el.isJsonPrimitive || !el.asJsonPrimitive.isNumber) {
        throw IllegalArgumentException("$name: expected [0,1]")
    }
    return unit(el.asJsonPrimitive.asDouble, name)
}

private fun JsonObject.unitOrSkip(name: String): Double? =
    get(name)?.let { unitJson(it, name) }

private fun JsonElement.asDoubleOr0(): Double =
    if (isJsonPrimitive && asJsonPrimitive.isNumber) asJsonPrimitive.asDouble else 0.0

private fun JsonObject.scoreOr0(name: String): Double = get(name)?.asDoubleOr0() ?: 0.0

private fun JsonObject.stringOr(name: String, fallback: String): String {
    val el = get(name) ?: return fallback
    return if (el.isJsonPrimitive && el.asJsonPrimitive.isString) el.asJsonPrimitive.asString else fallback
}

/** 校验被消费的分数；不校验负 logits 或模型延迟等未消费内容。 */
fun checkScores(value: JsonObject, path: String = "perception") {
    for (name in listOf("fineStates", "stances")) {
        val obj = value.objOrEmpty(name)
        if (obj.entrySet().size > 8) throw IllegalArgumentException("$name: bounded object required")
        for ((key, v) in obj.entrySet()) unitJson(v, "$path.$name.$key")
    }
    val emotions = value.objOrEmpty("emotions")
    if (emotions.entrySet().size > 8) throw IllegalArgumentException("emotions: bounded object required")
    for ((key, scores) in emotions.entrySet()) {
        if (!scores.isJsonObject) throw IllegalArgumentException("emotion score object required")
        val obj = scores.asJsonObject
        obj.unitOrSkip("calibratedProbability")?.let { unit(it, "emotions.$key.calibratedProbability") }
        obj.unitOrSkip("strength")?.let { unit(it, "emotions.$key.strength") }
    }
    for (key in listOf("dialogueAct", "speakerAffectTarget", "utteranceFactuality")) {
        val el = value.get(key)
        if (el != null && el.isJsonObject) {
            el.asJsonObject.get("confidence")?.let { unitJson(it, "$key.confidence") }
        }
    }
    val dimensions = value.objOrEmpty("dimensions")
    dimensions.get("toxicity")?.let { unitJson(it, "dimensions.toxicity") }
}

// ---------------------------------------------------------------------------
// 事件评价（Spec §6.3 感知路由 + §7 宿主事件）
// ---------------------------------------------------------------------------

/**
 * 把感知结果与已确认事实转换成少量评价事件。
 * 宿主事件先处理；感知路由顺序与 prototype 完全一致。
 */
fun appraise(obs: Observation, state: Snapshot): Pair<List<Appraisal>, List<String>> {
    val ctx = obs.context
    val out = mutableListOf<Appraisal>()
    val audit = mutableListOf<String>()

    for (fact in obs.facts) {
        val rule = Rules.ALL[fact.kind]
        if (rule == null || rule.source != "host") {
            throw IllegalArgumentException("unsupported host fact: ${fact.kind}")
        }
        unit(fact.strength)
        unit(fact.confidence)
        identifier(fact.evidenceId, "fact evidence")
        identifier(fact.target, "fact target")
        if (fact.resolves.size > 32) throw IllegalArgumentException("invalid fact verification or resolution count")
        fact.resolves.forEach { identifier(it, "resolved evidence") }
        if (!fact.verified || fact.source !in setOf("host", "authored_scenario")) {
            audit += "ignored_unverified:${fact.kind}"
            continue
        }
        if (fact.kind == "threat_resolved" && fact.resolves.isEmpty()) {
            audit += "ignored_resolution_without_link"
            continue
        }
        out += Appraisal(fact.kind, fact.strength, fact.confidence, fact.evidenceId, fact.target, ctx.topicId, fact.resolves)
    }

    val p = obs.perception ?: return out to (audit + "sensor_missing:no_new_sensor_impulse")
    if (obs.modelMetadataVersion != "2.1.0" || (p.get("schemaVersion")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString) != "2.1.1") {
        throw IllegalArgumentException("unsupported legacy metadata/decoder version pair")
    }
    checkScores(p)

    val factuality = p.objOrEmpty("utteranceFactuality")
    val factualityValue = factuality.stringOr("value", "")
    val fc = factuality.scoreOr0("confidence")
    if (factualityValue != "asserted" || fc < 0.65) {
        return out to (audit + "sensor_factuality_blocked")
    }

    val target = p.objOrEmpty("speakerAffectTarget")
    val targetId = target.stringOr("value", "unclear")
    val tc = target.scoreOr0("confidence")
    val direct = targetId == "listener" && tc >= 0.75 && ctx.scene == "real"

    val stance = p.objOrEmpty("stances")
    val act = p.objOrEmpty("dialogueAct")
    val actId = act.stringOr("primary", "other")
    val ac = act.scoreOr0("confidence")
    val toxicity = p.objOrEmpty("dimensions").scoreOr0("toxicity")

    fun base(key: String): Pair<Double, Double> {
        val e = p.objOrEmpty("emotions").objOrEmpty(key)
        return e.scoreOr0("calibratedProbability") to e.scoreOr0("strength")
    }

    fun add(kind: String, magnitude: Double, confidence: Double, target: String) {
        out += Appraisal(kind, magnitude, confidence, obs.eventId, target, ctx.topicId)
    }

    val hostile = stance.scoreOr0("hostile")
    val playful = stance.scoreOr0("playful")

    // 模糊玩笑永远不被自动判为敌意。
    if (direct && hostile >= 0.70 && toxicity >= 0.55 && playful < 0.55 && !ctx.legitimateFeedback) {
        val (bp, bs) = base("anger")
        add("hostility", maxOf(if (bp >= 0.65) bs else 0.0, toxicity), minOf(fc, tc, hostile), "user")
    } else if (direct && hostile >= 0.70 && playful >= 0.55) {
        audit += "ambiguous_hostility_playfulness:no_hostility"
    }

    if (direct && actId in setOf("thank_appreciate", "comfort_support") && ac >= 0.70) {
        add("kindness", 0.60, minOf(fc, tc, ac), "user")
    }

    val (ap, av) = base("affection")
    if (direct && ap >= 0.70 && stance.scoreOr0("affiliative") >= 0.60) {
        add("affection", av, minOf(fc, tc, ap), "user")
    }

    if (direct && actId == "apologize_repair" && ac >= 0.75) {
        val related = state.traces.filter { it.kind == "hostility" && it.topicId == ctx.topicId }
        if (related.isNotEmpty()) {
            val ids = related.flatMap { it.evidenceIds }
            out += Appraisal("repair", 0.65, minOf(fc, tc, ac), obs.eventId, "user", ctx.topicId, ids)
        } else {
            audit += "apology_without_related_tension:no_relief"
        }
    }

    if (actId == "joke_irony" && ac >= 0.65 && playful >= 0.70 && hostile < 0.55) {
        add("humor", 0.65, minOf(fc, ac, playful), "event")
    }

    val experiential = actId !in setOf("question", "request_command") || stance.scoreOr0("vulnerable") >= 0.65
    if (experiential && tc >= 0.65 && targetId in setOf("self", "event_object", "general")) {
        val candidates = listOf(base("sadness"), base("fear")).filter { (prob, _) -> prob >= 0.70 }
        if (candidates.isNotEmpty()) {
            val (prob, strength) = candidates.maxBy { (_, strength) -> strength }
            add("user_distress", strength, minOf(fc, tc, prob), "user")
        }
        val (jp, js) = base("joy")
        if (jp >= 0.70) {
            add("shared_joy", js, minOf(fc, tc, jp), "event")
        }
    }

    // fineStates、dominance、effectiveScore、全局 confidence 刻意不被当作角色自身感受
    // 或校准过的普适证据。
    return out to audit
}
