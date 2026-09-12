package com.loyea.plugin.modulator

import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 移植自 tests/test_modulator.py 的场景测试（test_01–test_29、test_33、test_34）。 */
class ScenarioTest {

    // ------------------------------------------------------------------
    // 主体区分与感知路由
    // ------------------------------------------------------------------

    @Test
    fun test_01_user_sadness_becomes_compassion_not_self_sadness() {
        val m = Modulator()
        val d = m.process(Observation("u1", 1, 0.0, perception = perception("sadness", target = "self")))
        assertEquals(setOf("compassion"), labels(d))
        assertEquals("unspecified", d.rows[0].state)
        assertEquals("unspecified", d.rows[1].state)
    }

    @Test
    fun test_02_angry_at_other_not_angry_at_user() {
        val m = Modulator()
        val p = perception("anger", target = "third_party", stances = mapOf("hostile" to 0.95))
        val d = m.process(Observation("u1", 1, 0.0, perception = p))
        assertFalse(labels(d).contains("anger"))
        assertEquals(0.0, m.state.fast[3], 0.0)
    }

    @Test
    fun test_03_direct_hostility_is_distinct() {
        val m = Modulator()
        val d = m.process(
            Observation("u1", 1, 0.0, perception = perception("anger", target = "listener", stances = mapOf("hostile" to 0.95))),
        )
        assertTrue(labels(d).contains("anger"))
        assertTrue(m.state.fast[3] > 0.1)
    }

    @Test
    fun test_04_negation_hypothesis_quote_unclear_do_not_become_self_state() {
        for (mode in listOf("negated", "hypothetical", "reported", "unclear")) {
            val m = Modulator()
            val p = perception("anger", target = "listener", stances = mapOf("hostile" to 0.95))
            p.getAsJsonObject("utteranceFactuality").addProperty("value", mode)
            val d = m.process(Observation("u1", 1, 0.0, perception = p))
            assertTrue(labels(d).isEmpty())
            assertEquals(0.0, m.state.fast[3], 0.0)
        }
    }

    @Test
    fun test_05_academic_question_not_automatic_distress() {
        val m = Modulator()
        val d = m.process(Observation("u1", 1, 0.0, perception = perception("sadness", act = "question")))
        assertTrue(labels(d).isEmpty())
    }

    @Test
    fun test_06_neutral_does_not_reset_old_state() {
        val m = Modulator()
        m.process(Observation("u1", 1, 0.0, perception = perception("anger", target = "listener", stances = mapOf("hostile" to 0.95))))
        val before = m.state.fast.toList()
        m.process(Observation("u2", 2, 0.0, perception = perception()))
        assertEquals(before, m.state.fast)
    }

    @Test
    fun test_07_factual_correction_does_not_punish_user() {
        val m = Modulator()
        val d = m.process(
            Observation(
                "u1", 1, 0.0,
                Context(legitimateFeedback = true),
                perception("anger", target = "listener", stances = mapOf("hostile" to 0.95)),
            ),
        )
        assertTrue(labels(d).isEmpty())
    }

    @Test
    fun test_08_banter_conflict_abstains() {
        val m = Modulator()
        val d = m.process(
            Observation(
                "u1", 1, 0.0,
                perception = perception("anger", target = "listener", stances = mapOf("hostile" to 0.95, "playful" to 0.9)),
            ),
        )
        assertTrue(labels(d).isEmpty())
    }

    // ------------------------------------------------------------------
    // 事务、重试与原子性
    // ------------------------------------------------------------------

    @Test
    fun test_09_same_turn_retry_once() {
        val m = Modulator()
        val o = Observation("u1", 1, 10.0, perception = perception("joy"))
        val first = m.process(o)
        val snap = m.dumps()
        repeat(10) { assertEquals(first, m.process(o)) }
        assertEquals(snap, m.dumps())
    }

    @Test
    fun test_10_stale_and_id_conflict_rejected_atomically() {
        val m = Modulator()
        m.process(Observation("u1", 1, 10.0))
        val before = m.dumps()
        for (o in listOf(
            Observation("other", 1, 10.0),
            Observation("older", 0, 5.0),
            Observation("u1", 2, 11.0),
        )) {
            try {
                m.process(o)
                fail("expected rejection for ${o.eventId}")
            } catch (expected: IllegalArgumentException) {
            }
        }
        assertEquals(before, m.dumps())
    }

    @Test
    fun test_11_invalid_score_or_schema_does_not_partially_advance() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.2, 1.2)) {
            val m = Modulator()
            val before = m.dumps()
            val p = perception("joy")
            p.getAsJsonObject("emotions").getAsJsonObject("joy").add("strength", JsonPrimitive(invalid))
            try {
                m.process(Observation("u1", 1, 1000.0, perception = p))
                fail("expected rejection")
            } catch (expected: IllegalArgumentException) {
            }
            assertEquals(before, m.dumps())
        }
        val p = perception()
        p.addProperty("schemaVersion", "2.1.0")
        try {
            Modulator().process(Observation("u1", 1, 0.0, perception = p))
            fail("expected rejection")
        } catch (expected: IllegalArgumentException) {
        }
    }

    // ------------------------------------------------------------------
    // 人格、关系与语义边界
    // ------------------------------------------------------------------

    @Test
    fun test_12_personality_fixed_and_counter_not_a_gain() {
        val a = Modulator(Personality(plasticity = 0.0, totalInteractions = 0))
        val b = Modulator(Personality(plasticity = 1.0, totalInteractions = 1_000_000))
        val o = Observation("u1", 1, 0.0, perception = perception("joy"))
        a.process(o)
        b.process(o)
        assertEquals(a.state.fast, b.state.fast)
        assertEquals(0, a.personality.totalInteractions)
    }

    @Test
    fun test_13_relationship_not_derived_from_flattery_or_offense() {
        val ctx = Context(relations = RelationView("love", 0.8, "distrust", 0.6, "explicit-character-fact"))
        val m = Modulator()
        for (n in 1..19) {
            val d = m.process(
                Observation(
                    "u$n", n.toLong(), n * 60.0, ctx,
                    perception("affection", target = "listener", stances = mapOf("affiliative" to 0.9)),
                ),
            )
            assertEquals("love", d.rows[0].state)
            assertEquals("distrust", d.rows[1].state)
        }
        val empty = Modulator().process(
            Observation("u1", 1, 0.0, perception = perception("affection", target = "listener", stances = mapOf("affiliative" to 0.9))),
        )
        assertEquals("unspecified", empty.rows[0].state)
    }

    @Test
    fun test_14_shame_guilt_not_split() {
        val p = perception()
        p.getAsJsonObject("fineStates").addProperty("shame_guilt", 0.95)
        val d = Modulator().process(Observation("u1", 1, 0.0, perception = p))
        assertTrue(labels(d).isEmpty())
    }

    @Test
    fun test_15_verified_guilt_keeps_distinct_semantics() {
        val a = Modulator()
        val b = Modulator()
        val da = a.process(Observation("u1", 1, 0.0, facts = listOf(fact("own_harm_confirmed"))))
        val db = b.process(Observation("u1", 1, 0.0, facts = listOf(fact("future_threat"))))
        assertEquals(setOf("guilt"), labels(da))
        assertEquals(setOf("worry"), labels(db))
    }

    @Test
    fun test_16_unverified_host_fact_ignored() {
        val f = Fact("own_harm_confirmed", 0.9, "guess", verified = false)
        val d = Modulator().process(Observation("u1", 1, 0.0, facts = listOf(f)))
        assertTrue(labels(d).isEmpty())
    }

    @Test
    fun test_17_mood_is_slow_and_can_disagree_with_current_feeling() {
        val m = Modulator()
        for (n in 1..39) {
            m.process(
                Observation(
                    "u$n", n.toLong(), (n - 1) * 120.0,
                    perception = perception("anger", target = "listener", stances = mapOf("hostile" to 0.95)),
                ),
            )
        }
        val oldMood = m.state.mood.toList()
        val d = m.process(
            Observation("funny", 40, m.state.at, perception = perception(act = "joke_irony", stances = mapOf("playful" to 0.95))),
        )
        assertEquals(oldMood, m.state.mood)
        assertTrue(labels(d).contains("amusement"))
        assertTrue(d.rows[2].state in setOf("irritable", "tense", "gloomy"))
    }

    @Test
    fun test_18_absence_does_not_create_loneliness_or_relation_loss() {
        val m = Modulator()
        m.idleTo(30.0 * 86400.0)
        assertEquals(m.personality.baseline(), m.state.fast)
        assertEquals(m.personality.baseline(), m.state.mood)
        assertTrue(m.state.traces.isEmpty())
    }

    @Test
    fun test_19_topic_switch_hides_unsupported_old_label_but_keeps_state() {
        val m = Modulator()
        m.process(
            Observation(
                "u1", 1, 0.0, Context(topicId = "novel"),
                facts = listOf(fact("future_threat", evidence = "story")),
            ),
        )
        val before = m.state.fast.toList()
        val d = m.process(
            Observation("u2", 2, 0.0, Context(topicId = "code", visibleEvidence = setOf("story"))),
        )
        assertTrue(labels(d).isEmpty())
        assertEquals(before, m.state.fast)
    }

    @Test
    fun test_20_resolution_link_does_not_wipe_unrelated_trace() {
        val m = Modulator()
        m.process(
            Observation(
                "u1", 1, 0.0,
                facts = listOf(fact("future_threat", evidence = "threat"), fact("task_blocked", evidence = "task")),
            ),
        )
        val old = m.state.traces.associate { it.kind to it.strength }
        m.process(
            Observation(
                "u2", 2, 0.0,
                facts = listOf(fact("threat_resolved", evidence = "resolution", resolves = listOf("threat"))),
            ),
        )
        val new = m.state.traces.associate { it.kind to it.strength }
        assertTrue(new.getValue("future_threat") < old.getValue("future_threat"))
        assertEquals(old.getValue("task_blocked"), new.getValue("task_blocked"), 0.0)
    }

    @Test
    fun test_21_three_columns_five_rows_and_valid_labels() {
        val m = Modulator()
        val hostKinds = Rules.ALL.filterValues { it.source == "host" }.keys.take(8)
        val facts = hostKinds.map { fact(it, evidence = it) }
        val d = m.process(Observation("u1", 1, 0.0, facts = facts))
        assertTrue(d.rows.size <= 5)
        for (row in d.wire()) {
            assertEquals(setOf("aspect", "state", "intensity"), row.keys)
            assertTrue(ModulatorVocab.STATE_ZH.containsKey(row["state"]))
        }
    }

    @Test
    fun test_22_intensity_hysteresis_and_zero_semantics() {
        val m = Modulator()
        val s = m.state
        assertEquals("mild", quantize(s, "x", 0.29))
        assertEquals("mild", quantize(s, "x", 0.31))
        assertEquals("moderate", quantize(s, "x", 0.34))
        assertEquals("moderate", quantize(s, "x", 0.29))
        assertEquals("mild", quantize(s, "x", 0.26))
        assertEquals("none", quantize(s, "x", 0.0))
    }

    @Test
    fun test_23_checkpoint_roundtrip_and_retry() {
        val m = Modulator()
        val o = Observation("u1", 1, 0.0, perception = perception("joy"))
        val d = m.process(o)
        val n = Modulator.loads(m.dumps())
        assertEquals(m.dumps(), n.dumps())
        assertEquals(d, n.process(o))
        val o2 = Observation("u2", 2, 200.0, perception = perception("sadness", target = "self"))
        assertEquals(n.process(o2), m.process(o2))
        assertEquals(n.dumps(), m.dumps())
    }

    @Test
    fun test_24_malformed_checkpoint_and_clock_rollback() {
        val m = Modulator(at = 10.0)
        val raw = JsonParser.parseString(m.dumps()).asJsonObject
        raw.getAsJsonObject("state").getAsJsonArray("fast").set(0, JsonPrimitive(5.0))
        try {
            Modulator.loads(raw.toString())
            fail("expected rejection")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            m.idleTo(5.0)
            fail("expected rejection")
        } catch (expected: IllegalArgumentException) {
        }
        assertEquals(10.0, m.state.at, 0.0)
    }

    @Test
    fun test_25_lore_selected_by_state_context_group_budget() {
        val m = Modulator()
        val ctx = Context(activeTags = setOf("study"))
        val d = m.process(
            Observation("u1", 1, 0.0, ctx, perception("sadness", target = "self")),
        )
        val rules = listOf(
            LoreRule("care", "简洁地回应困境。", 20, statesAny = setOf("feeling/compassion"), group = "tone"),
            LoreRule("joke", "开一个玩笑。", 10, statesAny = setOf("feeling/amusement"), group = "tone"),
            LoreRule("study", "优先解决当前问题。", 10, contextAll = setOf("study")),
            LoreRule("off", "关闭条目", 99, always = true, enabled = false),
        )
        assertEquals(listOf("care", "study"), selectLore(d, ctx, rules))
        assertEquals(listOf("care"), selectLore(d, ctx, rules, maxEntries = 1))
    }

    @Test
    fun test_26_scope_specific_trust_not_generalized() {
        val ctx = Context(
            relations = RelationView(trust = "trust", trustStrength = 0.8, evidenceId = "tech", scope = "technical"),
            trustScope = "relationship",
        )
        val d = Modulator().process(Observation("u1", 1, 0.0, ctx))
        assertEquals("unspecified", d.rows[1].state)
    }

    @Test
    fun test_27_low_confidence_never_uses_global_confidence() {
        val p = perception("sadness", target = "self")
        p.getAsJsonObject("emotions").getAsJsonObject("sadness").addProperty("calibratedProbability", 0.3)
        val d = Modulator().process(Observation("u1", 1, 0.0, perception = p))
        assertTrue(labels(d).isEmpty())
    }

    @Test
    fun test_28_repeat_small_evidence_accumulates_boundedly() {
        val m = Modulator()
        val f = fact("future_threat", 0.12, "f1")
        val d0 = m.process(Observation("u1", 1, 0.0, facts = listOf(f)))
        assertTrue(labels(d0).isEmpty())
        var d = d0
        for (n in 2..8) {
            d = m.process(
                Observation("u$n", n.toLong(), n * 45.0, facts = listOf(fact("future_threat", 0.12, "f$n"))),
            )
        }
        assertTrue(labels(d).contains("worry"))
        assertTrue(m.state.traces[0].strength <= 0.95)
    }

    @Test
    fun test_29_within_turn_event_order_invariant() {
        val base = listOf(
            fact("future_threat", evidence = "a"),
            fact("self_achievement", evidence = "b"),
            fact("task_blocked", evidence = "c"),
        )
        val states = mutableListOf<List<Double>>()
        for (order in permutations(base)) {
            val m = Modulator()
            m.process(Observation("u1", 1, 0.0, facts = order))
            states.add(m.state.fast.toList())
        }
        for (x in states) {
            for (i in x.indices) assertAlmostEqual(x[i], states[0][i], places = 14)
        }
    }

    @Test
    fun test_33_corrupt_checkpoint_cannot_change_retry_or_quantizer() {
        val m = Modulator()
        m.process(Observation("u1", 1, 0.0, perception = perception("joy")))
        val bad = mutableListOf<String>()
        run {
            val obj = JsonParser.parseString(m.dumps()).asJsonObject
            obj.getAsJsonObject("state").getAsJsonObject("label_levels").addProperty("feeling:joy", 200)
            bad.add(obj.toString())
        }
        run {
            val obj = JsonParser.parseString(m.dumps()).asJsonObject
            obj.getAsJsonObject("last_decision").addProperty("seq", 2)
            bad.add(obj.toString())
        }
        run {
            val obj = JsonParser.parseString(m.dumps()).asJsonObject
            obj.getAsJsonObject("last_decision").getAsJsonArray("rows").get(2).asJsonObject.addProperty("state", "fake")
            bad.add(obj.toString())
        }
        run {
            val obj = JsonParser.parseString(m.dumps()).asJsonObject
            obj.add("last_decision", com.google.gson.JsonNull.INSTANCE)
            bad.add(obj.toString())
        }
        for (obj in bad) {
            try {
                Modulator.loads(obj)
                fail("expected rejection")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }

    /** wire 层的坏类型输入在进入核心前被拒绝（对应 test_34 的宿主侧防御）。 */
    @Test
    fun test_34_oversized_or_mistyped_host_inputs_are_atomic() {
        val m = Modulator()
        val before = m.dumps()

        val oversized = Observation(
            "u1", 1, 100.0,
            facts = (0..8).map { fact("novel_topic", evidence = "e$it") },
        )
        try {
            m.process(oversized)
            fail("expected rejection")
        } catch (expected: IllegalArgumentException) {
        }
        assertEquals(before, m.dumps())

        val badFact = JsonParser.parseString(
            """{"kind":"novel_topic","strength":0.8,"evidence_id":"e","verified":"false"}""",
        ).asJsonObject
        try {
            Wire.fact(badFact)
            fail("expected rejection")
        } catch (expected: IllegalArgumentException) {
        }

        val badContext = JsonParser.parseString("""{"legitimate_feedback":"false"}""").asJsonObject
        try {
            Wire.context(badContext)
            fail("expected rejection")
        } catch (expected: IllegalArgumentException) {
        }
    }

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.size <= 1) {
            listOf(items)
        } else {
            items.flatMap { item ->
                permutations(items - item).map { listOf(item) + it }
            }
        }
}
