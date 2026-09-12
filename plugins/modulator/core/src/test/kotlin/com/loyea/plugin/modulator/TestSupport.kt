package com.loyea.plugin.modulator

import com.google.gson.JsonObject
import kotlin.math.abs

/** 与 tests/test_modulator.py 相同的合成感知构造器。 */
internal fun perception(
    label: String = "neutral",
    strength: Double = 0.75,
    target: String = "event_object",
    act: String = "inform",
    stances: Map<String, Double> = emptyMap(),
): JsonObject {
    val p = JsonObject()
    p.addProperty("schemaVersion", "2.1.1")
    val emotions = JsonObject()
    val entry = JsonObject()
    entry.addProperty("calibratedProbability", 0.95)
    entry.addProperty("strength", strength)
    emotions.add(label, entry)
    p.add("emotions", emotions)
    p.add("fineStates", JsonObject())
    val stance = JsonObject()
    stances.forEach { (k, v) -> stance.addProperty(k, v) }
    p.add("stances", stance)
    val dialogueAct = JsonObject()
    dialogueAct.addProperty("primary", act)
    dialogueAct.addProperty("confidence", 0.95)
    p.add("dialogueAct", dialogueAct)
    val affectTarget = JsonObject()
    affectTarget.addProperty("value", target)
    affectTarget.addProperty("confidence", 0.95)
    p.add("speakerAffectTarget", affectTarget)
    val factuality = JsonObject()
    factuality.addProperty("value", "asserted")
    factuality.addProperty("confidence", 0.95)
    p.add("utteranceFactuality", factuality)
    val dimensions = JsonObject()
    dimensions.addProperty(
        "toxicity",
        if ((stances["hostile"] ?: 0.0) >= 0.7) 0.80 else 0.02,
    )
    p.add("dimensions", dimensions)
    p.addProperty("confidence", 0.999)
    p.addProperty("ambiguity", 0.8)
    return p
}

internal fun fact(
    kind: String,
    strength: Double = 0.75,
    evidence: String = "f1",
    confidence: Double = 1.0,
    target: String = "event",
    resolves: List<String> = emptyList(),
): Fact = Fact(kind, strength, evidence, confidence, verified = true, target = target, resolves = resolves)

internal fun labels(decision: Decision): Set<String> =
    decision.rows.filter { it.aspect == "feeling" }.map { it.state }.toSet()

/** 对应 Python assertAlmostEqual(x, y, places=n)：|x-y| < 0.5e-n。 */
internal fun assertAlmostEqual(actual: Double, expected: Double, places: Int) {
    val tolerance = 0.5 * Math.pow(10.0, places.toDouble())
    if (abs(actual - expected) >= tolerance) {
        throw AssertionError("expected <$expected> within $places places but was <$actual>")
    }
}
