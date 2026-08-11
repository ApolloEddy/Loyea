package com.loyea.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class LatexToUnicodeTest {

    @Test
    fun convertsFractions() {
        assertEquals("1/2", latexToUnicode("\\frac{1}{2}"))
        assertEquals("(a+b)/c", latexToUnicode("\\frac{a+b}{c}"))
        assertEquals("√(2)/3", latexToUnicode("\\frac{\\sqrt{2}}{3}"))
        assertEquals("√(1/2)", latexToUnicode("\\sqrt{\\frac{1}{2}}"))
    }

    @Test
    fun convertsSqrt() {
        assertEquals("√(x)", latexToUnicode("\\sqrt{x}"))
        assertEquals("2√(x)", latexToUnicode("2\\sqrt{x}"))
    }

    @Test
    fun convertsSuperscripts() {
        assertEquals("x²", latexToUnicode("x^2"))
        assertEquals("e⁻ˣ", latexToUnicode("e^{-x}"))
        assertEquals("x²y³", latexToUnicode("x^2y^3"))
    }

    @Test
    fun convertsSubscripts() {
        assertEquals("a₁", latexToUnicode("a_1"))
        assertEquals("xᵢ₊₁", latexToUnicode("x_{i+1}"))
    }

    @Test
    fun convertsGreekAndSymbols() {
        assertEquals("α + β", latexToUnicode("\\alpha + \\beta"))
        assertEquals("a × b", latexToUnicode("a \\times b"))
        assertEquals("x ≤ y", latexToUnicode("x \\leq y"))
        assertEquals("x ≠ y", latexToUnicode("x \\neq y"))
        assertEquals("π ≈ 3.14", latexToUnicode("\\pi \\approx 3.14"))
    }

    @Test
    fun convertsSumWithLimits() {
        assertEquals("Σᵢ₌₁ⁿ i²", latexToUnicode("\\sum_{i=1}^{n} i^2"))
        assertEquals("∫ₐᵇ f(x)dx", latexToUnicode("\\int_{a}^{b} f(x)dx"))
    }

    @Test
    fun convertsTextAndFunctions() {
        assertEquals("hello", latexToUnicode("\\text{hello}"))
        assertEquals("sin(x) + log(y)", latexToUnicode("\\sin(x) + \\log(y)"))
        assertEquals("limₓ→∞", latexToUnicode("\\lim_{x\\to\\infty}"))
    }

    @Test
    fun convertsVectorsAndUnknownCommandsGracefully() {
        assertEquals("v⃗", latexToUnicode("\\vec{v}"))
        // 未知命令兜底：去掉反斜杠，不崩溃
        assertEquals("foo", latexToUnicode("\\foo"))
        assertEquals("$5%", latexToUnicode("\\$5\\%"))
    }
}
