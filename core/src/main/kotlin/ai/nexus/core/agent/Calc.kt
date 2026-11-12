package ai.nexus.core.agent

/** Small offline calculator for "what's 15% of 2400" / "25 times 4". */
object Calc {
    fun evaluate(input: String): Double? {
        var e = input.lowercase()
            .replace(Regex("(\\d+(?:\\.\\d+)?)\\s*%\\s*of\\s*(\\d+(?:\\.\\d+)?)"), "($1/100*$2)")
            .replace("multiplied by", "*").replace("divided by", "/").replace("times", "*").replace("into", "*")
            .replace("guna", "*").replace("plus", "+").replace("aur", "+").replace("minus", "-").replace("over", "/")
            .replace(" by ", " / ").replace("×", "*").replace("÷", "/").replace(Regex("(?<=\\d)\\s*x\\s*(?=\\d)"), "*")
            .replace(Regex("(\\d+(?:\\.\\d+)?)%"), "($1/100)")
            .replace(" ", "")
        if (e.isEmpty() || e.any { it !in "0123456789.+-*/()^" }) return null
        return runCatching { Parser(e).parse() }.getOrNull()?.takeIf { it.isFinite() }
    }

    fun looksLikeMath(s: String): Boolean = evaluate(s.replace(Regex("^(?:what(?:'s| is)|calculate)\\s+", RegexOption.IGNORE_CASE), "").trimEnd('?')) != null

    fun format(v: Double): String =
        if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString()
        else "%.4f".format(v).trimEnd('0').trimEnd('.')

    private class Parser(val s: String) {
        var i = 0
        fun parse(): Double { val v = expr(); require(i == s.length); return v }
        private fun expr(): Double {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) { val op = s[i++]; val r = term(); v = if (op == '+') v + r else v - r }
            return v
        }
        private fun term(): Double {
            var v = power()
            while (i < s.length && (s[i] == '*' || s[i] == '/')) { val op = s[i++]; val r = power(); v = if (op == '*') v * r else v / r }
            return v
        }
        private fun power(): Double {
            val b = unary()
            if (i < s.length && s[i] == '^') { i++; return Math.pow(b, power()) }
            return b
        }
        private fun unary(): Double = if (i < s.length && s[i] == '-') { i++; -unary() } else atom()
        private fun atom(): Double {
            if (s[i] == '(') { i++; val v = expr(); require(s[i] == ')'); i++; return v }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(start, i).toDouble()
        }
    }
}
