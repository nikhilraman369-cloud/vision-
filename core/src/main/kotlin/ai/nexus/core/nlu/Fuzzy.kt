package ai.nexus.core.nlu

import java.text.Normalizer

object Fuzzy {
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")

    /** Lowercase, strip accents and punctuation, collapse whitespace. */
    fun normalize(s: String): String {
        val decomposed = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return decomposed.lowercase().replace(nonWord, " ").trim().replace(Regex("\\s+"), " ")
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** 1.0 = identical, 0.0 = nothing in common. */
    fun similarity(a: String, b: String): Double {
        val x = normalize(a); val y = normalize(b)
        if (x.isEmpty() && y.isEmpty()) return 1.0
        val d = levenshtein(x, y)
        return 1.0 - d.toDouble() / maxOf(x.length, y.length)
    }

    fun containsWord(haystack: String, word: String): Boolean {
        val h = " ${normalize(haystack)} "
        return h.contains(" ${normalize(word)} ")
    }

    /** Best candidate by similarity, if it clears [threshold]. */
    fun <T> best(query: String, items: Collection<T>, threshold: Double = 0.72, key: (T) -> List<String>): T? {
        val q = normalize(query)
        var bestItem: T? = null
        var bestScore = 0.0
        for (item in items) {
            for (k in key(item)) {
                val kn = normalize(k)
                val s = when {
                    kn == q -> 1.0
                    kn.startsWith("$q ") || kn.endsWith(" $q") -> 0.9
                    else -> similarity(q, kn)
                }
                if (s > bestScore) { bestScore = s; bestItem = item }
            }
        }
        return if (bestScore >= threshold) bestItem else null
    }
}
