package org.yaop.ui.editor

/**
 * Utility for formatting LaTeX mathematical expressions into a clean,
 * human-readable, and typography-rich presentable string using Unicode symbols.
 */
object LatexFormatter {

    private val SUPERSCRIPT_MAP = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
        'n' to 'ⁿ', 'i' to 'ⁱ', 'x' to 'ˣ', 'y' to 'ʸ', 'a' to 'ᵃ',
        'b' to 'ᵇ', 'c' to 'ᶜ', 'd' to 'ᵈ', 'e' to 'ᵉ', 'm' to 'ᵐ',
        'p' to 'ᵖ', 'r' to 'ʳ', 't' to 'ᵗ', '*' to '﹡'
    )

    private val SUBSCRIPT_MAP = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
        '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
        'a' to 'ₐ', 'e' to 'ₑ', 'h' to 'ₕ', 'i' to 'ᵢ', 'j' to 'ⱼ',
        'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ', 'o' to 'ₒ',
        'p' to 'ₚ', 'r' to 'ᵣ', 's' to 'ₛ', 't' to 'ₜ', 'u' to 'ᵤ',
        'v' to 'ᵥ', 'x' to 'ₓ'
    )

    private val GREEK_AND_SYMBOLS = listOf(
        // Capital Greek
        "\\Gamma" to "Γ", "\\Delta" to "Δ", "\\Theta" to "Θ", "\\Lambda" to "Λ",
        "\\Xi" to "Ξ", "\\Pi" to "Π", "\\Sigma" to "Σ", "\\Upsilon" to "Υ",
        "\\Phi" to "Φ", "\\Psi" to "Ψ", "\\Omega" to "Ω",

        // Lowercase Greek
        "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ",
        "\\epsilon" to "ε", "\\varepsilon" to "ε", "\\zeta" to "ζ", "\\eta" to "η",
        "\\theta" to "θ", "\\vartheta" to "ϑ", "\\iota" to "ι", "\\kappa" to "κ",
        "\\lambda" to "λ", "\\mu" to "μ", "\\nu" to "ν", "\\xi" to "ξ",
        "\\pi" to "π", "\\varpi" to "ϖ", "\\rho" to "ρ", "\\varrho" to "ϱ",
        "\\sigma" to "σ", "\\varsigma" to "ς", "\\tau" to "τ", "\\upsilon" to "υ",
        "\\phi" to "φ", "\\varphi" to "ϕ", "\\chi" to "χ", "\\psi" to "ψ",
        "\\omega" to "ω",

        // Operators & Relations
        "\\times" to "×", "\\cdot" to "·", "\\div" to "÷", "\\pm" to "±",
        "\\mp" to "∓", "\\leq" to "≤", "\\le" to "≤", "\\geq" to "≥",
        "\\ge" to "≥", "\\neq" to "≠", "\\ne" to "≠", "\\approx" to "≈",
        "\\equiv" to "≡", "\\sim" to "∼", "\\propto" to "∝", "\\infty" to "∞",
        "\\partial" to "∂", "\\nabla" to "∇", "\\sum" to "∑", "\\prod" to "∏",
        "\\int" to "∫", "\\iint" to "∬", "\\iiint" to "∭", "\\oint" to "∮",
        "\\forall" to "∀", "\\exists" to "∃", "\\nexists" to "∄", "\\in" to "∈",
        "\\notin" to "∉", "\\subset" to "⊂", "\\supset" to "⊃", "\\subseteq" to "⊆",
        "\\supseteq" to "⊇", "\\cup" to "∪", "\\cap" to "∩", "\\emptyset" to "∅",
        "\\to" to "→", "\\rightarrow" to "→", "\\leftarrow" to "←",
        "\\Rightarrow" to "⇒", "\\Leftarrow" to "⇐", "\\leftrightarrow" to "↔",
        "\\Leftrightarrow" to "⇔", "\\iff" to "⇔", "\\ldots" to "…",
        "\\cdots" to "…", "\\dots" to "…", "\\circ" to "∘", "\\bullet" to "•",
        "\\angle" to "∠", "\\perp" to "⊥", "\\parallel" to "∥", "\\hbar" to "ħ",
        "\\ell" to "ℓ"
    )

    private val MATH_FUNCTIONS = listOf(
        "\\sin", "\\cos", "\\tan", "\\sec", "\\csc", "\\cot",
        "\\arcsin", "\\arccos", "\\arctan", "\\sinh", "\\cosh", "\\tanh",
        "\\ln", "\\log", "\\exp", "\\lim", "\\max", "\\min", "\\det", "\\deg"
    )

    /**
     * Formats a raw LaTeX mathematical expression into a clean, presentable string.
     */
    fun formatToPresentable(latex: String): String {
        if (latex.isBlank()) return ""

        var result = latex.trim()
            .removeSurrounding("$$")
            .removeSurrounding("$")
            .trim()

        // Replace common blackboard bold sets
        result = result
            .replace("\\mathbb{R}", "ℝ")
            .replace("\\mathbb{C}", "ℂ")
            .replace("\\mathbb{N}", "ℕ")
            .replace("\\mathbb{Z}", "ℤ")
            .replace("\\mathbb{Q}", "ℚ")

        // Format delimiters
        result = result
            .replace("\\left(", "(")
            .replace("\\right)", ")")
            .replace("\\left[", "[")
            .replace("\\right]", "]")
            .replace("\\left\\{", "{")
            .replace("\\right\\}", "}")
            .replace("\\left|", "|")
            .replace("\\right|", "|")
            .replace("\\{", "{")
            .replace("\\}", "}")

        // Text wrappers
        result = result.replace(Regex("\\\\(text|mathrm|mathbf|mathit)\\{([^{}]+)\\}")) {
            it.groupValues[2]
        }

        // Spacing macros
        result = result
            .replace("\\,", " ")
            .replace("\\;", " ")
            .replace("\\quad", "  ")
            .replace("\\qquad", "    ")
            .replace("\\!", "")

        // Fractions: \frac{a}{b} -> (a) / (b) or a/b
        val fracRegex = Regex("\\\\frac\\{([^{}]+)\\}\\{([^{}]+)\\}")
        while (fracRegex.containsMatchIn(result)) {
            result = result.replace(fracRegex) { match ->
                val num = match.groupValues[1].trim()
                val den = match.groupValues[2].trim()
                when {
                    (num == "1" && den == "2") -> "½"
                    (num == "1" && den == "4") -> "¼"
                    (num == "3" && den == "4") -> "¾"
                    (num == "1" && den == "3") -> "⅓"
                    (num == "2" && den == "3") -> "⅔"
                    num.all { it.isLetterOrDigit() } && den.all { it.isLetterOrDigit() } -> "$num / $den"
                    else -> "($num) / ($den)"
                }
            }
        }

        // Roots: \sqrt[n]{x} and \sqrt{x}
        result = result.replace(Regex("""\\sqrt\[([^]]+)\]\{([^{}]+)\}""")) {
            val root = it.groupValues[1]
            val content = it.groupValues[2]
            "${toSuperscript(root)}√($content)"
        }
        result = result.replace(Regex("""\\sqrt\{([^{}]+)\}""")) {
            "√(${it.groupValues[1]})"
        }

        // Superscripts with braces: ^{...}
        result = result.replace(Regex("\\^\\{([^{}]+)\\}")) {
            toSuperscript(it.groupValues[1])
        }
        // Single-character superscripts: ^x
        result = result.replace(Regex("\\^([a-zA-Z0-9+\\-=()])")) {
            toSuperscript(it.groupValues[1])
        }

        // Subscripts with braces: _{...}
        result = result.replace(Regex("_\\{([^{}]+)\\}")) {
            toSubscript(it.groupValues[1])
        }
        // Single-character subscripts: _x
        result = result.replace(Regex("_([a-zA-Z0-9+\\-=()])")) {
            toSubscript(it.groupValues[1])
        }

        // Greek and Math Symbols
        for ((macro, symbol) in GREEK_AND_SYMBOLS) {
            result = result.replace(macro, symbol)
        }

        // Common Math Functions
        for (func in MATH_FUNCTIONS) {
            result = result.replace(func, func.removePrefix("\\"))
        }

        // Clean up remaining single braces: {x} -> x
        result = result.replace(Regex("\\{([^{}]+)\\}")) {
            it.groupValues[1]
        }

        // Normalize spaces around brackets and multiple spaces
        result = result
            .replace(Regex("\\(\\s+"), "(")
            .replace(Regex("\\s+\\)"), ")")
            .replace(Regex("\\[\\s+"), "[")
            .replace(Regex("\\s+]"), "]")
            .replace(Regex("\\{\\s+"), "{")
            .replace(Regex("\\s+\\}"), "}")
            .replace(Regex("[ \\t]+"), " ")

        return result.trim()
    }

    private fun toSuperscript(text: String): String {
        val sb = StringBuilder()
        for (char in text) {
            val mapped = SUPERSCRIPT_MAP[char]
            if (mapped != null) {
                sb.append(mapped)
            } else {
                sb.append(char)
            }
        }
        return sb.toString()
    }

    private fun toSubscript(text: String): String {
        val sb = StringBuilder()
        for (char in text) {
            val mapped = SUBSCRIPT_MAP[char]
            if (mapped != null) {
                sb.append(mapped)
            } else {
                sb.append(char)
            }
        }
        return sb.toString()
    }
}
