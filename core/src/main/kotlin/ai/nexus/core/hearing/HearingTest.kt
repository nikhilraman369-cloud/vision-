package ai.nexus.core.hearing

import ai.nexus.core.nlu.Devanagari
import ai.nexus.core.nlu.Fuzzy
import ai.nexus.core.nlu.IntentParser

/**
 * The Hearing Test: the same recording of each phrase is sent to several speech engines, and each
 * engine's text is scored on (1) how many words it got right and (2) whether NEXUS would do the
 * right thing with it. The engine that hears *you* best wins.
 */
object HearingTest {
    /** Real commands from the task list (categories A–C), in the mix of English and Hinglish you actually speak. */
    val PHRASES = listOf(
        "WhatsApp kholo",
        "Moht ko WhatsApp pe message karo ki main das minute mein aa raha hoon",
        "Open the University examination group",
        "Call Yash Chourasia on WhatsApp",
        "Video call Moht",
        "Play Skyfall on YouTube Music",
        "YouTube pe Adele Skyfall chalao",
        "Pause the song",
        "Next song",
        "Gehra Hua bajao",
        "Ask ChatGPT what is depreciation",
        "Claude se poocho GST kya hai",
        "Gemini kholo",
        "Search best calisthenics plan on Chrome",
        "Set a timer for 10 minutes",
        "Kal subah 6 baje ka alarm lagao",
        "Remind me to submit the assignment at 5 pm",
        "Volume kam karo",
        "Torch on karo",
        "Open the camera",
        "Remember that my exam is on Monday",
        "What do you remember about my exam",
        "Scroll down three times",
        "Open the second video",
        "Type okay, I'll do it later",
        "Teesri chat kholo",
        "Brightness 50 percent",
        "Mujhe yaad dilana ki raat 9 baje mom ko call karna hai",
        "Hey Nexus, how was your day",
        "Read my last message from Moht",
    )

    private val SPELLING = mapOf(
        "hu" to "hoon", "hun" to "hoon", "hoo" to "hoon", "mai" to "main", "me" to "mein", "mei" to "mein", "kaha" to "kaho",
        "krna" to "karna", "kro" to "karo", "kr" to "kar", "h" to "hai", "he" to "hai", "hain" to "hai", "ky" to "kya",
        "10" to "das", "ten" to "das", "6" to "chhe", "9" to "nau", "5" to "five", "50" to "fifty", "3" to "three",
        "%" to "percent", "whatsapp" to "whatsapp", "whats" to "whatsapp", "youtube" to "youtube", "you" to "you",
    )

    /** Lowercase Latin words; Devanagari is transliterated, punctuation dropped, common Hinglish spellings unified. */
    fun words(s: String): List<String> {
        val latin = Devanagari.toLatin(s).lowercase().replace("what's app", "whatsapp").replace("whats app", "whatsapp").replace("you tube", "youtube")
        return latin.replace(Regex("[^a-z0-9%' ]"), " ").replace("'", "").split(Regex("\\s+")).filter { it.isNotBlank() }.map { SPELLING[it] ?: it }
    }

    /** 0–100: how much of what you said the engine got right (word edit distance, near-spellings count as right). */
    fun accuracy(expected: String, heard: String): Int {
        val a = words(expected); val b = words(heard)
        if (a.isEmpty()) return if (b.isEmpty()) 100 else 0
        val d = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in 0..a.size) d[i][0] = i
        for (j in 0..b.size) d[0][j] = j
        for (i in 1..a.size) for (j in 1..b.size) {
            val same = a[i - 1] == b[j - 1] || Fuzzy.similarity(a[i - 1], b[j - 1]) >= 0.8
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (same) 0 else 1)
        }
        return (100 * (1.0 - d[a.size][b.size].toDouble() / a.size)).coerceIn(0.0, 100.0).toInt()
    }

    /** Would NEXUS do the same thing with what the engine heard as with what you actually said? */
    fun sameCommand(expected: String, heard: String, parser: IntentParser = IntentParser()): Boolean {
        val e = parser.parse(expected).steps.map { it.command }
        val h = parser.parse(heard).steps.map { it.command }
        if (e.size != h.size || e.isEmpty()) return false
        return e.zip(h).all { (x, y) ->
            x::class == y::class && Fuzzy.similarity(Fuzzy.normalize(x.toString()), Fuzzy.normalize(y.toString())) >= 0.8
        }
    }

    data class Trial(val phrase: String, val engine: String, val heard: String?, val ms: Long, val error: String? = null) {
        val accuracy: Int get() = heard?.let { accuracy(phrase, it) } ?: 0
        val understood: Boolean get() = heard != null && sameCommand(phrase, heard)
    }

    data class EngineScore(val engine: String, val tried: Int, val avgAccuracy: Int, val understood: Int, val avgMs: Long, val errors: Int)

    fun score(trials: List<Trial>): List<EngineScore> = trials.groupBy { it.engine }.map { (engine, t) ->
        val ok = t.filter { it.heard != null }
        EngineScore(engine, t.size, if (t.isEmpty()) 0 else t.sumOf { it.accuracy } / t.size, t.count { it.understood },
            if (ok.isEmpty()) 0 else ok.sumOf { it.ms } / ok.size, t.count { it.heard == null })
    }.sortedWith(compareByDescending<EngineScore> { it.understood }.thenByDescending { it.avgAccuracy }.thenBy { it.avgMs })

    fun report(trials: List<Trial>, device: String): String = buildString {
        append("NEXUS Hearing Test · ").append(device).append('\n')
        append("Phrases: ").append(trials.map { it.phrase }.distinct().size).append("\n\n")
        append("RESULTS (best first)\n")
        for (s in score(trials)) append("• ${s.engine}: understood ${s.understood}/${s.tried} · words ${s.avgAccuracy}% · ${s.avgMs} ms" + (if (s.errors > 0) " · ${s.errors} errors" else "") + "\n")
        append("\nDETAILS\n")
        for ((phrase, t) in trials.groupBy { it.phrase }) {
            append("\n\"").append(phrase).append("\"\n")
            for (x in t) append("   ${x.engine}: " + (x.heard?.let { "\"$it\" (${x.accuracy}%${if (x.understood) ", ✓ command" else ", ✗ command"}, ${x.ms} ms)" } ?: "ERROR ${x.error}") + "\n")
        }
    }
}
