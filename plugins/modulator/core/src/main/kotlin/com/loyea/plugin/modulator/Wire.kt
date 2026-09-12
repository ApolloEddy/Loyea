package com.loyea.plugin.modulator

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * 观测 wire 格式解析（与 integration/golden_cases.json 的 input 结构一致）。
 * 宿主以 JSON 提交观测；这里做类型与契约校验，非法输入在进入核心前被拒绝。
 */
object Wire {

    fun relationView(o: JsonObject): RelationView = RelationView(
        affect = o.stringOr("affect", "unspecified"),
        affectStrength = o.doubleOrNull("affect_strength"),
        trust = o.stringOr("trust", "unspecified"),
        trustStrength = o.doubleOrNull("trust_strength"),
        evidenceId = o.stringOrNull("evidence_id"),
        scope = o.stringOr("scope", "general"),
    ).also { it.validate() }

    fun context(o: JsonObject): Context = Context(
        topicId = o.stringOr("topic_id", "general"),
        scene = o.stringOr("scene", "real"),
        legitimateFeedback = o.booleanOr("legitimate_feedback", false),
        visibleEvidence = o.stringSet("visible_evidence"),
        activeTags = o.stringSet("active_tags"),
        relations = o.objOrNull("relations")?.let { relationView(it) } ?: RelationView(),
        trustScope = o.stringOr("trust_scope", "general"),
    )

    fun fact(o: JsonObject): Fact {
        val verified = o.booleanStrict("verified")
        val source = o.stringOr("source", "host")
        if (source !in setOf("host", "authored_scenario")) {
            throw IllegalArgumentException("invalid fact source: $source")
        }
        return Fact(
            kind = o.stringOr("kind", ""),
            strength = o.doubleStrict("strength"),
            evidenceId = o.stringOr("evidence_id", ""),
            confidence = o.doubleOr("confidence", 1.0),
            verified = verified,
            source = source,
            target = o.stringOr("target", "event"),
            resolves = o.stringList("resolves"),
        )
    }

    fun observation(o: JsonObject): Observation = Observation(
        eventId = o.stringOr("event_id", ""),
        seq = o.longStrict("seq"),
        at = o.doubleStrict("at"),
        context = o.objOrNull("context")?.let { context(it) } ?: Context(),
        perception = o.objOrNull("perception"),
        facts = o.arrayOrEmpty("facts").map { fact(it.asJsonObject) },
        modelMetadataVersion = o.stringOr("model_metadata_version", "2.1.0"),
    )

    // ------------------------------------------------------------------

    private fun JsonObject.objOrNull(name: String): JsonObject? {
        val el = get(name) ?: return null
        if (el.isJsonNull) return null
        if (!el.isJsonObject) throw IllegalArgumentException("$name: object required")
        return el.asJsonObject
    }

    private fun JsonObject.arrayOrEmpty(name: String): List<JsonElement> {
        val el = get(name) ?: return emptyList()
        if (!el.isJsonArray) throw IllegalArgumentException("$name: array required")
        return el.asJsonArray.toList()
    }

    private fun JsonObject.stringOr(name: String, fallback: String): String {
        val el = get(name) ?: return fallback
        if (!el.isJsonPrimitive || !el.asJsonPrimitive.isString) throw IllegalArgumentException("$name: string required")
        return el.asJsonPrimitive.asString
    }

    private fun JsonObject.stringOrNull(name: String): String? {
        val el = get(name) ?: return null
        if (el.isJsonNull) return null
        if (!el.isJsonPrimitive || !el.asJsonPrimitive.isString) throw IllegalArgumentException("$name: string required")
        return el.asJsonPrimitive.asString
    }

    private fun JsonObject.doubleOrNull(name: String): Double? {
        val el = get(name) ?: return null
        if (el.isJsonNull) return null
        if (!el.isJsonPrimitive || !el.asJsonPrimitive.isNumber) throw IllegalArgumentException("$name: number required")
        val v = el.asJsonPrimitive.asDouble
        if (!v.isFinite()) throw IllegalArgumentException("$name: finite number required")
        return v
    }

    private fun JsonObject.doubleOr(name: String, fallback: Double): Double =
        doubleOrNull(name) ?: fallback

    private fun JsonObject.doubleStrict(name: String): Double =
        doubleOrNull(name) ?: throw IllegalArgumentException("$name: number required")

    private fun JsonObject.longStrict(name: String): Long {
        val el = get(name) ?: throw IllegalArgumentException("missing $name")
        if (!el.isJsonPrimitive || !el.asJsonPrimitive.isNumber) throw IllegalArgumentException("$name: integer required")
        val lexeme = el.asJsonPrimitive.toString()
        if (lexeme.any { it == '.' || it == 'e' || it == 'E' }) throw IllegalArgumentException("$name: integer required")
        return el.asJsonPrimitive.asLong
    }

    private fun JsonObject.booleanOr(name: String, fallback: Boolean): Boolean {
        val el = get(name) ?: return fallback
        if (!el.isJsonPrimitive || !el.asJsonPrimitive.isBoolean) throw IllegalArgumentException("$name: boolean required")
        return el.asJsonPrimitive.asBoolean
    }

    private fun JsonObject.booleanStrict(name: String): Boolean {
        val el = get(name) ?: throw IllegalArgumentException("missing $name")
        // verified 必须是真正的布尔值（Spec §7）；字符串 "false" 之类一律拒绝。
        if (!el.isJsonPrimitive || !el.asJsonPrimitive.isBoolean) throw IllegalArgumentException("$name: boolean required")
        return el.asJsonPrimitive.asBoolean
    }

    private fun JsonObject.stringSet(name: String): Set<String> =
        arrayOrEmpty(name).map {
            if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) throw IllegalArgumentException("$name: string items required")
            it.asJsonPrimitive.asString
        }.toSet()

    private fun JsonObject.stringList(name: String): List<String> =
        arrayOrEmpty(name).map {
            if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) throw IllegalArgumentException("$name: string items required")
            it.asJsonPrimitive.asString
        }
}
