package ai.nexus.core.voice

import ai.nexus.core.nlu.Fuzzy
import org.apache.commons.codec.language.DoubleMetaphone

/**
 * Post-corrects speech recognition using what NEXUS knows: contact names and
 * nicknames, app names, people in memory, and mishearings you corrected
 * before. "call rahool" → "call Rahul", "open what sap" → "open WhatsApp".
 * Only replaces words that sound alike AND are spelled alike, so normal
 * words are left alone.
 */
class VocabularyCorrector {
    private val dm = DoubleMetaphone()
    @Volatile private var vocab: List<Pair<String, String>> = emptyList() // normalized → display
    @Volatile private var fixes: Map<String, String> = emptyMap()

    private val common = setOf("open", "call", "send", "message", "the", "and", "to", "on", "in", "play", "like", "save", "next", "back", "home",
        "search", "type", "tap", "show", "read", "set", "timer", "alarm", "note", "mode", "study", "start", "stop", "reply", "with", "for", "chat", "group")

    fun update(names: Collection<String>, mishearings: Map<String, String>) {
        vocab = names.map { it.trim() }.filter { it.length >= 3 }.distinct().map { Fuzzy.normalize(it) to it }.filter { it.first.isNotBlank() }
        fixes = mishearings.mapKeys { Fuzzy.normalize(it.key) }
    }

    private fun sounds(a: String, b: String): Boolean =
        dm.isDoubleMetaphoneEqual(a.replace(" ", ""), b.replace(" ", "")) || dm.isDoubleMetaphoneEqual(a.replace(" ", ""), b.replace(" ", ""), true)

    fun correct(text: String): String {
        var t = text
        // 1. Exact corrections the user taught (heard → meant).
        for ((heard, meant) in fixes) {
            if (heard.isNotBlank() && Fuzzy.containsWord(t, heard)) t = Regex("\\b${Regex.escape(heard)}\\b", RegexOption.IGNORE_CASE).replace(t, meant)
        }
        if (vocab.isEmpty()) return t
        // 2. Sound-alike names (1–3 word windows).
        val words = t.split(' ').toMutableList()
        var i = 0
        while (i < words.size) {
            var replaced = false
            for (n in 3 downTo 1) {
                if (i + n > words.size) continue
                val window = words.subList(i, i + n).joinToString(" ")
                val w = Fuzzy.normalize(window)
                if (w.length < 3 || (n == 1 && w in common)) continue
                val hit = vocab.filter { (v, _) -> v.split(' ').size == n || (n == 1 && v.replace(" ", "").length <= w.length + 2) }
                    .map { (v, disp) -> Triple(v, disp, Fuzzy.similarity(w, v)) }
                    .filter { (v, _, sim) -> v != w && sim >= 0.6 && sounds(w, v) }
                    .maxByOrNull { it.third }
                if (hit != null) {
                    for (k in 0 until n) words.removeAt(i)
                    words.add(i, hit.second)
                    replaced = true
                    break
                }
            }
            i++
            if (replaced) continue
        }
        return words.joinToString(" ")
    }
}
