package org.yaop.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class LatexFormatterTest {

    @Test
    fun testEmptyAndBlank() {
        assertEquals("", LatexFormatter.formatToPresentable(""))
        assertEquals("", LatexFormatter.formatToPresentable("   "))
    }

    @Test
    fun testSuperscripts() {
        assertEquals("E = mc²", LatexFormatter.formatToPresentable("E = mc^2"))
        assertEquals("a² + b² = c²", LatexFormatter.formatToPresentable("a^2 + b^2 = c^2"))
        assertEquals("x¹⁰ + y⁻¹", LatexFormatter.formatToPresentable("x^{10} + y^{-1}"))
    }

    @Test
    fun testSubscripts() {
        assertEquals("x₁ + x₂", LatexFormatter.formatToPresentable("x_1 + x_2"))
        assertEquals("aᵢ = bⱼ", LatexFormatter.formatToPresentable("a_{i} = b_{j}"))
    }

    @Test
    fun testGreekAndSymbols() {
        assertEquals("α + β = γ", LatexFormatter.formatToPresentable("\\alpha + \\beta = \\gamma"))
        assertEquals("∑ xᵢ", LatexFormatter.formatToPresentable("\\sum x_i"))
        assertEquals("∫ f(x) dx", LatexFormatter.formatToPresentable("\\int f(x) dx"))
        assertEquals("∞", LatexFormatter.formatToPresentable("\\infty"))
    }

    @Test
    fun testFractionsAndRoots() {
        assertEquals("½", LatexFormatter.formatToPresentable("\\frac{1}{2}"))
        assertEquals("a / b", LatexFormatter.formatToPresentable("\\frac{a}{b}"))
        assertEquals("√(x² + y²)", LatexFormatter.formatToPresentable("\\sqrt{x^2 + y^2}"))
    }

    @Test
    fun testDelimitersAndFunctions() {
        assertEquals("sin(x) + cos(x)", LatexFormatter.formatToPresentable("\\sin(x) + \\cos(x)"))
        assertEquals("(x + 1)", LatexFormatter.formatToPresentable("\\left( x + 1 \\right)"))
    }
}
