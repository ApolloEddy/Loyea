package com.loyea.plugin.modulator

import kotlin.math.abs
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 移植自 tests/test_modulator.py 的数值测试（test_30–test_32）。 */
class NumericalTest {

    @Test
    fun test_30_exact_idle_partition_invariance() {
        val rng = kotlin.random.Random(90210)
        repeat(100) {
            val p = Personality(
                rng.nextDouble(), rng.nextDouble(), rng.nextDouble(), rng.nextDouble(), rng.nextDouble(),
            )
            val a = Modulator(p)
            val b = Modulator(p)
            val f = listOf(rng.nextDouble(-1.0, 1.0), rng.nextDouble(), rng.nextDouble(), rng.nextDouble())
            val mood = listOf(rng.nextDouble(-1.0, 1.0), rng.nextDouble(), rng.nextDouble(), rng.nextDouble())
            a.injectStateForTest(
                Snapshot(0.0, p.profileVersion, f.toMutableList(), mood.toMutableList()),
            )
            b.injectStateForTest(
                Snapshot(0.0, p.profileVersion, f.toMutableList(), mood.toMutableList()),
            )
            val total = rng.nextDouble(1.0, 86400.0)
            a.idleTo(total)
            for (t in listOf(total * 0.01, total * 0.15, total * 0.42, total)) b.idleTo(t)
            val allA = a.state.fast + a.state.mood
            val allB = b.state.fast + b.state.mood
            for (i in allA.indices) assertAlmostEqual(allA[i], allB[i], places = 12)
        }
    }

    @Test
    fun test_31_independent_rk4_verifies_exact_cascade() {
        val p = Personality()
        val exact = Modulator(p)
        exact.injectStateForTest(
            Snapshot(
                0.0, p.profileVersion,
                mutableListOf(-0.8, 0.9, 0.7, 0.8),
                mutableListOf(0.3, 0.1, 0.2, 0.1),
            ),
        )
        var y = exact.state.fast + exact.state.mood
        val (fh, mh) = p.halfLives()
        val b = p.baseline()
        fun derivative(y: List<Double>): List<Double> =
            List(4) { j -> -ln(2.0) / fh[j] * (y[j] - b[j]) } +
                List(4) { j -> ln(2.0) / mh[j] * (y[j] - y[j + 4]) }

        val dt = 0.5
        repeat(1200) {
            val k1 = derivative(y)
            val k2 = derivative(y.zip(k1).map { (v, k) -> v + dt / 2 * k })
            val k3 = derivative(y.zip(k2).map { (v, k) -> v + dt / 2 * k })
            val k4 = derivative(y.zip(k3).map { (v, k) -> v + dt * k })
            y = List(8) { i -> y[i] + dt / 6 * (k1[i] + 2 * k2[i] + 2 * k3[i] + k4[i]) }
        }
        exact.idleTo(600.0)
        val final = exact.state.fast + exact.state.mood
        for (i in final.indices) assertAlmostEqual(final[i], y[i], places = 10)
    }

    @Test
    fun test_32_bounded_stress_all_personality_corners() {
        val rng = kotlin.random.Random(42)
        val kinds = Rules.ALL.filterValues { it.source == "host" }.keys.filter { it != "threat_resolved" }
        val deltas = listOf(0.0, 0.001, 1.0, 60.0, 3600.0, 86400.0)
        for (traits in cartesian(listOf(0.0, 1.0), repeat = 5)) {
            val m = Modulator(Personality(traits[0], traits[1], traits[2], traits[3], traits[4]))
            var at = 0.0
            for (n in 1..500) {
                at += deltas.random(rng)
                val kind = kinds.random(rng)
                m.process(
                    Observation(
                        "e$n", n.toLong(), at,
                        Context(topicId = "t${n % 10}"),
                        facts = listOf(fact(kind, rng.nextDouble(), "f$n")),
                    ),
                )
                for (j in 0..3) {
                    val (lo, hi) = ModulatorVocab.BOUNDS[j]
                    assertTrue(m.state.fast[j] in lo..hi)
                    assertTrue(m.state.mood[j] in lo..hi)
                }
                assertTrue(m.state.traces.size <= 8)
                assertTrue(m.state.traces.all { it.evidenceIds.size <= 4 })
            }
            m.idleTo(at + 365.0 * 86400.0)
            val expected = m.personality.baseline() + m.personality.baseline()
            val values = m.state.fast + m.state.mood
            for (i in values.indices) assertAlmostEqual(values[i], expected[i], places = 12)
        }
    }

    private fun cartesian(values: List<Double>, repeat: Int): List<List<Double>> =
        (1 until repeat).fold(values.map { listOf(it) }) { acc, _ ->
            acc.flatMap { prefix -> values.map { prefix + it } }
        }
}
