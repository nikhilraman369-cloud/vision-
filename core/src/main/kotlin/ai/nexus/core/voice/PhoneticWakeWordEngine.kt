package ai.nexus.core.voice

import ai.nexus.core.nlu.Fuzzy
import org.apache.commons.codec.language.DoubleMetaphone

/** Where the wake word ended inside an utterance, and what the user said after it. */
data class WakeMatch(val score: Double, val tokensUsed: Int, val remainder: String)

/**
 * Fuzzy "Hey Nexus" detector for offline speech-recognition output. Offline
 * recognisers often mishear the wake word ("hey naxu", "hay nexis",
 * "he next us", "naxus"), so this combines:
 *
 *  - normalised Levenshtein similarity on the letters, and
 *  - Double Metaphone similarity on how the words sound,
 *
 * and only looks at the first few words, so "open next reel" can never wake it.
 * Works on partial (still-streaming) results, so it fires before you pause.
 */
class PhoneticWakeWordEngine(wakeWord: String = "hey nexus") {
    private val dm = DoubleMetaphone().apply { maxCodeLen = 8 }
    private val prefixes = setOf("hey", "hay", "he", "hi", "hei", "hae", "a", "ok", "okay", "o", "oye", "ay", "eh", "hello")
    /** Real words that sound near "nexus" but are common in commands. */
    private val blocked = setOf("next", "nest", "necks", "nets", "text", "next's", "nexts", "nice", "news", "max", "mix", "fix")
    private var core = "nexus"
    private var coreCodes = codes(core)

    init { setWakeWord(wakeWord) }

    fun setWakeWord(phrase: String) {
        val toks = tokens(phrase)
        core = toks.lastOrNull { it !in prefixes } ?: "nexus"
        coreCodes = codes(core)
    }

    private fun tokens(s: String) = Fuzzy.normalize(s).split(' ').filter { it.isNotBlank() }

    private fun codes(w: String): List<String> = listOfNotNull(dm.doubleMetaphone(w), dm.doubleMetaphone(w, true)).distinct()

    private fun textSim(a: String, b: String): Double =
        if (a.isEmpty() || b.isEmpty()) 0.0 else 1.0 - Fuzzy.levenshtein(a, b).toDouble() / maxOf(a.length, b.length)

    private fun soundSim(a: String): Double {
        val ca = codes(a)
        var best = 0.0
        for (x in ca) for (y in coreCodes) best = maxOf(best, textSim(x, y))
        return best
    }

    /** Similarity (0..1) of a candidate word (or glued words) to the wake word. */
    fun similarity(candidate: String): Double {
        val c = candidate.lowercase().filter { it.isLetter() }
        if (c.length < 3 || c in blocked) return 0.0
        val t = textSim(c, core)
        val p = soundSim(c)
        return maxOf(t, 0.55 * t + 0.45 * p)
    }

    /**
     * Finds the wake word at the start of [utterance]. With a greeting before it
     * ("hey …") a looser match is enough; on its own the name must be close.
     */
    fun match(utterance: String): WakeMatch? {
        val toks = tokens(utterance)
        if (toks.isEmpty()) return null
        var best: WakeMatch? = null
        fun consider(score: Double, used: Int) {
            if (best == null || score > best!!.score) best = WakeMatch(score, used, toks.drop(used).joinToString(" "))
        }
        val start = if (toks[0] in prefixes) 1 else 0
        val threshold = if (start == 1) 0.58 else 0.76
        for (n in 1..3) {
            if (start + n > toks.size) break
            val words = toks.subList(start, start + n)
            if (n == 1 && words[0] in blocked) continue
            val glued = words.joinToString("")
            val s = similarity(glued) - (n - 1) * 0.04 // small cost for gluing several words
            if (s >= threshold) consider(s, start + n)
        }
        return best
    }
}

/** Hands-free listening states. */
enum class HandsFreeState { SLEEP_WAKEWORD, ACTIVE_COMMAND_WINDOW, PAUSED }

/**
 * The always-on voice state machine:
 *
 *  SLEEP_WAKEWORD → only "Hey Nexus" (fuzzy) is acted on. "Hey Nexus open
 *  Instagram" in one breath runs "open Instagram" straight away.
 *
 *  ACTIVE_COMMAND_WINDOW → every sentence is a command, no wake word needed.
 *  The 6.5 s window restarts after each command finishes. After 6.5 s of
 *  silence it goes back to sleep (with a tiny haptic tick).
 *
 *  PAUSED → NEXUS is speaking; anything heard is ignored so it never obeys itself.
 */
class HandsFreeStateMachine(
    private val engine: PhoneticWakeWordEngine = PhoneticWakeWordEngine(),
    private val clock: () -> Long = System::currentTimeMillis,
    var windowMs: Long = 6500,
    private val listener: Listener,
) {
    interface Listener {
        fun onWake()
        fun onCommand(text: String)
        fun onSleep()
    }

    @Volatile var state: HandsFreeState = HandsFreeState.SLEEP_WAKEWORD
        private set
    private var deadline = 0L
    private var resumeTo = HandsFreeState.SLEEP_WAKEWORD
    private var wokeOnPartial = false

    @Synchronized fun onPartial(text: String) {
        if (state == HandsFreeState.SLEEP_WAKEWORD && engine.match(text) != null) {
            wake(); wokeOnPartial = true
        } else if (state == HandsFreeState.ACTIVE_COMMAND_WINDOW && text.isNotBlank()) {
            deadline = clock() + windowMs // you're still talking: keep the window open
        }
    }

    @Synchronized fun onFinal(text: String) {
        val t = text.trim()
        when (state) {
            HandsFreeState.PAUSED -> return
            HandsFreeState.SLEEP_WAKEWORD -> {
                val m = engine.match(t) ?: return
                wake()
                if (m.remainder.isNotBlank()) listener.onCommand(m.remainder)
            }
            HandsFreeState.ACTIVE_COMMAND_WINDOW -> {
                if (t.isBlank()) { if (wokeOnPartial) wokeOnPartial = false; return }
                val cmd = engine.match(t)?.remainder ?: t // "hey nexus" repeated inside the window is fine
                wokeOnPartial = false
                deadline = clock() + windowMs
                if (cmd.isNotBlank()) listener.onCommand(cmd)
            }
        }
    }

    private fun wake() {
        val was = state
        state = HandsFreeState.ACTIVE_COMMAND_WINDOW
        deadline = clock() + windowMs
        if (was != HandsFreeState.ACTIVE_COMMAND_WINDOW) listener.onWake()
    }

    /** Open the command window directly (bubble tap, earbud button). */
    @Synchronized fun activate() = wake()

    /** Restart the 6.5 s countdown, e.g. when a command has just finished executing. */
    @Synchronized fun extend() { if (state == HandsFreeState.ACTIVE_COMMAND_WINDOW) deadline = clock() + windowMs }

    /** Ignore audio while NEXUS speaks. */
    @Synchronized fun pause() { if (state != HandsFreeState.PAUSED) { resumeTo = state; state = HandsFreeState.PAUSED } }

    @Synchronized fun resume() {
        if (state == HandsFreeState.PAUSED) { state = resumeTo; if (state == HandsFreeState.ACTIVE_COMMAND_WINDOW) deadline = clock() + windowMs }
    }

    /** Call regularly (e.g. every 100–250 ms). */
    @Synchronized fun tick() {
        if (state == HandsFreeState.ACTIVE_COMMAND_WINDOW && clock() > deadline) {
            state = HandsFreeState.SLEEP_WAKEWORD; wokeOnPartial = false
            listener.onSleep()
        }
    }

    @Synchronized fun sleepNow() { if (state != HandsFreeState.SLEEP_WAKEWORD) { state = HandsFreeState.SLEEP_WAKEWORD; listener.onSleep() } }
}
