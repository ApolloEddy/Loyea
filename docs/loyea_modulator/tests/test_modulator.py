"""Invariant and scenario tests, not a test of LLM response naturalness."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "prototype"))
from modulator import *
import unittest
import random
import itertools


def perception(label="neutral", strength=.75, target="event_object", act="inform", **stances):
    return {
        "schemaVersion": "2.1.1",
        "emotions": {label: {"calibratedProbability": .95, "strength": strength}},
        "fineStates": {}, "stances": stances,
        "dialogueAct": {"primary": act, "confidence": .95},
        "speakerAffectTarget": {"value": target, "confidence": .95},
        "utteranceFactuality": {"value": "asserted", "confidence": .95},
        "dimensions": {"toxicity": .80 if stances.get("hostile", 0) >= .7 else .02},
        "confidence": .999, "ambiguity": .8,
    }


def fact(kind, strength=.75, evidence="f1", **kw):
    return Fact(kind, strength, evidence, verified=True, **kw)


def labels(decision):
    return {r.state for r in decision.rows if r.aspect == "feeling"}


class Scenarios(unittest.TestCase):
    def test_01_user_sadness_becomes_compassion_not_self_sadness(self):
        m = Modulator()
        d = m.process(Observation("u1", 1, 0, perception=perception("sadness", target="self")))
        self.assertEqual(labels(d), {"compassion"})
        self.assertEqual(d.rows[0].state, "unspecified")
        self.assertEqual(d.rows[1].state, "unspecified")

    def test_02_angry_at_other_not_angry_at_user(self):
        m = Modulator()
        p = perception("anger", target="third_party", hostile=.95)
        self.assertNotIn("anger", labels(m.process(Observation("u1", 1, 0, perception=p))))
        self.assertEqual(m.state.fast[3], 0)

    def test_03_direct_hostility_is_distinct(self):
        m = Modulator()
        d = m.process(Observation("u1", 1, 0, perception=perception("anger", target="listener", hostile=.95)))
        self.assertIn("anger", labels(d)); self.assertGreater(m.state.fast[3], .1)

    def test_04_negation_hypothesis_quote_unclear_do_not_become_self_state(self):
        for mode in ("negated", "hypothetical", "reported", "unclear"):
            m = Modulator(); p = perception("anger", target="listener", hostile=.95)
            p["utteranceFactuality"]["value"] = mode
            d = m.process(Observation("u1", 1, 0, perception=p))
            self.assertFalse(labels(d)); self.assertEqual(m.state.fast[3], 0)

    def test_05_academic_question_not_automatic_distress(self):
        m = Modulator()
        d = m.process(Observation("u1", 1, 0, perception=perception("sadness", act="question")))
        self.assertFalse(labels(d))

    def test_06_neutral_does_not_reset_old_state(self):
        m = Modulator()
        m.process(Observation("u1", 1, 0, perception=perception("anger", target="listener", hostile=.95)))
        before = m.state.fast[:]
        m.process(Observation("u2", 2, 0, perception=perception()))
        self.assertEqual(m.state.fast, before)

    def test_07_factual_correction_does_not_punish_user(self):
        m = Modulator()
        d = m.process(Observation("u1", 1, 0, Context(legitimate_feedback=True),
                                  perception("anger", target="listener", hostile=.95)))
        self.assertFalse(labels(d))

    def test_08_banter_conflict_abstains(self):
        m = Modulator()
        d = m.process(Observation("u1", 1, 0, perception=perception("anger", target="listener", hostile=.95, playful=.9)))
        self.assertFalse(labels(d))

    def test_09_same_turn_retry_once(self):
        m = Modulator(); o = Observation("u1", 1, 10, perception=perception("joy"))
        first = m.process(o); snap = m.dumps()
        for _ in range(10): self.assertEqual(m.process(o), first)
        self.assertEqual(m.dumps(), snap)

    def test_10_stale_and_id_conflict_rejected_atomically(self):
        m = Modulator(); m.process(Observation("u1", 1, 10))
        before = m.dumps()
        for o in (Observation("other", 1, 10), Observation("older", 0, 5), Observation("u1", 2, 11)):
            with self.assertRaises(ValueError): m.process(o)
        self.assertEqual(m.dumps(), before)

    def test_11_invalid_score_or_schema_does_not_partially_advance(self):
        for invalid in (float("nan"), float("inf"), -.2, 1.2):
            m = Modulator(); before = m.dumps(); p = perception("joy")
            p["emotions"]["joy"]["strength"] = invalid
            with self.assertRaises(ValueError): m.process(Observation("u1", 1, 1000, perception=p))
            self.assertEqual(m.dumps(), before)
        p = perception(); p["schemaVersion"] = "2.1.0"
        with self.assertRaises(ValueError): Modulator().process(Observation("u1", 1, 0, perception=p))

    def test_12_personality_fixed_and_counter_not_a_gain(self):
        a = Modulator(Personality(plasticity=0, total_interactions=0))
        b = Modulator(Personality(plasticity=1, total_interactions=1000000))
        o = Observation("u1", 1, 0, perception=perception("joy"))
        a.process(o); b.process(o)
        self.assertEqual(a.state.fast, b.state.fast)
        self.assertEqual(a.personality.total_interactions, 0)

    def test_13_relationship_not_derived_from_flattery_or_offense(self):
        ctx = Context(relations=RelationView("love", .8, "distrust", .6, "explicit-character-fact"))
        m = Modulator()
        for n in range(1, 20):
            d = m.process(Observation(f"u{n}", n, n*60, ctx, perception("affection", target="listener", affiliative=.9)))
            self.assertEqual(d.rows[0].state, "love"); self.assertEqual(d.rows[1].state, "distrust")
        empty = Modulator().process(Observation("u1", 1, 0, perception=perception("affection", target="listener", affiliative=.9)))
        self.assertEqual(empty.rows[0].state, "unspecified")

    def test_14_shame_guilt_not_split(self):
        p = perception(); p["fineStates"]["shame_guilt"] = .95
        self.assertFalse(labels(Modulator().process(Observation("u1", 1, 0, perception=p))))

    def test_15_verified_guilt_keeps_distinct_semantics(self):
        a, b = Modulator(), Modulator()
        da = a.process(Observation("u1", 1, 0, facts=(fact("own_harm_confirmed"),)))
        db = b.process(Observation("u1", 1, 0, facts=(fact("future_threat"),)))
        self.assertEqual(labels(da), {"guilt"}); self.assertEqual(labels(db), {"worry"})

    def test_16_unverified_host_fact_ignored(self):
        f = Fact("own_harm_confirmed", .9, "guess", verified=False)
        self.assertFalse(labels(Modulator().process(Observation("u1", 1, 0, facts=(f,)))))

    def test_17_mood_is_slow_and_can_disagree_with_current_feeling(self):
        m = Modulator()
        for n in range(1, 40):
            m.process(Observation(f"u{n}", n, (n-1)*120, perception=perception("anger", target="listener", hostile=.95)))
        old_mood = m.state.mood[:]
        d = m.process(Observation("funny", 40, m.state.at, perception=perception(act="joke_irony", playful=.95)))
        self.assertEqual(m.state.mood, old_mood)
        self.assertIn("amusement", labels(d)); self.assertIn(d.rows[2].state, {"irritable", "tense", "gloomy"})

    def test_18_absence_does_not_create_loneliness_or_relation_loss(self):
        m = Modulator(); m.idle_to(30*86400)
        self.assertEqual(m.state.fast, m.personality.baseline())
        self.assertEqual(m.state.mood, m.personality.baseline())
        self.assertFalse(m.state.traces)

    def test_19_topic_switch_hides_unsupported_old_label_but_keeps_state(self):
        m = Modulator()
        m.process(Observation("u1", 1, 0, Context(topic_id="novel"), facts=(fact("future_threat", evidence="story"),)))
        before = m.state.fast[:]
        d = m.process(Observation("u2", 2, 0, Context(topic_id="code", visible_evidence=frozenset({"story"}))))
        self.assertFalse(labels(d)); self.assertEqual(m.state.fast, before)

    def test_20_resolution_link_does_not_wipe_unrelated_trace(self):
        m = Modulator()
        m.process(Observation("u1", 1, 0, facts=(fact("future_threat", evidence="threat"), fact("task_blocked", evidence="task"))))
        old = {t.kind: t.strength for t in m.state.traces}
        m.process(Observation("u2", 2, 0, facts=(fact("threat_resolved", evidence="resolution", resolves=("threat",)),)))
        new = {t.kind: t.strength for t in m.state.traces}
        self.assertLess(new["future_threat"], old["future_threat"])
        self.assertEqual(new["task_blocked"], old["task_blocked"])

    def test_21_three_columns_five_rows_and_valid_labels(self):
        m = Modulator()
        facts = tuple(fact(k, evidence=k) for k, v in RULES.items() if v.source == "host")[:8]
        d = m.process(Observation("u1", 1, 0, facts=facts))
        self.assertLessEqual(len(d.rows), 5)
        for row in d.wire():
            self.assertEqual(set(row), {"aspect", "state", "intensity"})
            self.assertIn(row["state"], STATE_ZH)

    def test_22_intensity_hysteresis_and_zero_semantics(self):
        m = Modulator(); s = m.state
        self.assertEqual(quantize(s, "x", .29), "mild")
        self.assertEqual(quantize(s, "x", .31), "mild")
        self.assertEqual(quantize(s, "x", .34), "moderate")
        self.assertEqual(quantize(s, "x", .29), "moderate")
        self.assertEqual(quantize(s, "x", .26), "mild")
        self.assertEqual(quantize(s, "x", 0), "none")

    def test_23_checkpoint_roundtrip_and_retry(self):
        m = Modulator(); o = Observation("u1", 1, 0, perception=perception("joy"))
        d = m.process(o); n = Modulator.loads(m.dumps())
        self.assertEqual(n.dumps(), m.dumps()); self.assertEqual(n.process(o), d)
        o2 = Observation("u2", 2, 200, perception=perception("sadness", target="self"))
        self.assertEqual(n.process(o2), m.process(o2)); self.assertEqual(n.dumps(), m.dumps())

    def test_24_malformed_checkpoint_and_clock_rollback(self):
        m = Modulator(at=10); raw = json.loads(m.dumps()); raw["state"]["fast"][0] = 5
        with self.assertRaises(ValueError): Modulator.loads(json.dumps(raw))
        with self.assertRaises(ValueError): m.idle_to(5)
        self.assertEqual(m.state.at, 10)

    def test_25_lore_selected_by_state_context_group_budget(self):
        m = Modulator(); ctx = Context(active_tags=frozenset({"study"}))
        d = m.process(Observation("u1", 1, 0, ctx, perception("sadness", target="self")))
        rules = (LoreRule("care", "简洁地回应困境。", 20, frozenset({"feeling/compassion"}), group="tone"),
                 LoreRule("joke", "开一个玩笑。", 10, frozenset({"feeling/amusement"}), group="tone"),
                 LoreRule("study", "优先解决当前问题。", 10, context_all=frozenset({"study"})),
                 LoreRule("off", "关闭条目", 99, always=True, enabled=False))
        self.assertEqual(select_lore(d, ctx, rules), ("care", "study"))
        self.assertEqual(select_lore(d, ctx, rules, max_entries=1), ("care",))

    def test_26_scope_specific_trust_not_generalized(self):
        ctx = Context(relations=RelationView(trust="trust", trust_strength=.8, evidence_id="tech", scope="technical"), trust_scope="relationship")
        d = Modulator().process(Observation("u1", 1, 0, ctx))
        self.assertEqual(d.rows[1].state, "unspecified")

    def test_27_low_confidence_never_uses_global_confidence(self):
        p = perception("sadness", target="self"); p["emotions"]["sadness"]["calibratedProbability"] = .3
        self.assertFalse(labels(Modulator().process(Observation("u1", 1, 0, perception=p))))

    def test_28_repeat_small_evidence_accumulates_boundedly(self):
        m = Modulator()
        f = fact("future_threat", .12, "f1")
        self.assertFalse(labels(m.process(Observation("u1", 1, 0, facts=(f,)))))
        for n in range(2, 9):
            d = m.process(Observation(f"u{n}", n, n*45, facts=(fact("future_threat", .12, f"f{n}"),)))
        self.assertIn("worry", labels(d)); self.assertLessEqual(m.state.traces[0].strength, .95)

    def test_29_within_turn_event_order_invariant(self):
        facts = (fact("future_threat", evidence="a"), fact("self_achievement", evidence="b"), fact("task_blocked", evidence="c"))
        states = []
        for order in itertools.permutations(facts):
            m = Modulator(); m.process(Observation("u1", 1, 0, facts=order)); states.append(m.state.fast)
        for x in states:
            for a, b in zip(x, states[0]): self.assertAlmostEqual(a, b, places=14)

    def test_33_corrupt_checkpoint_cannot_change_retry_or_quantizer(self):
        m = Modulator(); m.process(Observation("u1", 1, 0, perception=perception("joy")))
        bad = []
        obj = json.loads(m.dumps()); obj["state"]["label_levels"]["feeling:joy"] = 200; bad.append(obj)
        obj = json.loads(m.dumps()); obj["last_decision"]["seq"] = 2; bad.append(obj)
        obj = json.loads(m.dumps()); obj["last_decision"]["rows"][2]["state"] = "fake"; bad.append(obj)
        obj = json.loads(m.dumps()); obj["last_decision"] = None; bad.append(obj)
        for obj in bad:
            with self.assertRaises(ValueError): Modulator.loads(json.dumps(obj))

    def test_34_oversized_or_mistyped_host_inputs_are_atomic(self):
        m = Modulator(); before = m.dumps()
        observations = [
            Observation("u1", 1, 100, facts=tuple(fact("novel_topic", evidence=f"e{i}") for i in range(9))),
            Observation("u1", 1, 100, facts=(Fact("novel_topic", .8, "e", verified="false"),)),
            Observation("u1", 1, 100, Context(legitimate_feedback="false")),
        ]
        for o in observations:
            with self.assertRaises(ValueError): m.process(o)
            self.assertEqual(m.dumps(), before)


class Numerical(unittest.TestCase):
    def test_30_exact_idle_partition_invariance(self):
        rng = random.Random(90210)
        for _ in range(100):
            p = Personality(*[rng.random() for _ in range(5)])
            a, b = Modulator(p), Modulator(p)
            f = [rng.uniform(-1, 1)] + [rng.random() for _ in range(3)]
            mood = [rng.uniform(-1, 1)] + [rng.random() for _ in range(3)]
            a.state.fast, b.state.fast = f[:], f[:]
            a.state.mood, b.state.mood = mood[:], mood[:]
            total = rng.uniform(1, 86400)
            a.idle_to(total)
            for t in (total*.01, total*.15, total*.42, total): b.idle_to(t)
            for x, y in zip(a.state.fast+a.state.mood, b.state.fast+b.state.mood):
                self.assertAlmostEqual(x, y, places=12)

    def test_31_independent_rk4_verifies_exact_cascade(self):
        p = Personality(); exact = Modulator(p)
        exact.state.fast = [-.8, .9, .7, .8]; exact.state.mood = [.3, .1, .2, .1]
        y = exact.state.fast[:] + exact.state.mood[:]
        fh, mh = p.half_lives(); b = p.baseline()
        def derivative(y):
            return [-math.log(2)/fh[j]*(y[j]-b[j]) for j in range(4)] + [math.log(2)/mh[j]*(y[j]-y[j+4]) for j in range(4)]
        dt = .5
        for _ in range(1200):
            k1=derivative(y); k2=derivative([v+dt/2*k for v,k in zip(y,k1)])
            k3=derivative([v+dt/2*k for v,k in zip(y,k2)]); k4=derivative([v+dt*k for v,k in zip(y,k3)])
            y=[v+dt/6*(a+2*b+2*c+d) for v,a,b,c,d in zip(y,k1,k2,k3,k4)]
        exact.idle_to(600)
        for x, v in zip(exact.state.fast+exact.state.mood, y): self.assertAlmostEqual(x, v, places=10)

    def test_32_bounded_stress_all_personality_corners(self):
        rng = random.Random(42)
        kinds = [k for k, r in RULES.items() if r.source == "host" and k != "threat_resolved"]
        for traits in itertools.product((0., 1.), repeat=5):
            m = Modulator(Personality(*traits)); at = 0.
            for n in range(1, 501):
                at += rng.choice([0., .001, 1., 60., 3600., 86400.])
                kind = rng.choice(kinds)
                m.process(Observation(f"e{n}", n, at, Context(topic_id=f"t{n%10}"), facts=(fact(kind, rng.random(), f"f{n}"),)))
                for j, (lo, hi) in enumerate(BOUNDS):
                    self.assertTrue(lo <= m.state.fast[j] <= hi)
                    self.assertTrue(lo <= m.state.mood[j] <= hi)
                self.assertLessEqual(len(m.state.traces), 8)
                self.assertTrue(all(len(t.evidence_ids) <= 4 for t in m.state.traces))
            m.idle_to(at+365*86400)
            for value, expected in zip(m.state.fast+m.state.mood, m.personality.baseline()*2):
                self.assertAlmostEqual(value, expected, places=12)


if __name__ == "__main__":
    unittest.main(verbosity=2)
