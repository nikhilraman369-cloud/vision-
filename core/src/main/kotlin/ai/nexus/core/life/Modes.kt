package ai.nexus.core.life

import ai.nexus.core.nlu.Fuzzy

/** Built-in modes (all editable) and mode voice commands. */
object Modes {
    fun defaults(): List<Mode> = listOf(
        Mode(name = "Study", onStart = listOf("toggle do_not_disturb on", "timer 3000 | Study focus block"), onStop = listOf("toggle do_not_disturb off"),
            blockedApps = listOf("com.instagram.android", "com.google.android.youtube", "com.snapchat.android", "com.facebook.katana", "com.twitter.android"),
            focusMinutes = 50, breakMinutes = 10, logAs = "study", builtIn = true),
        Mode(name = "Gym", onStart = listOf("toggle do_not_disturb on", "play workout playlist"), onStop = listOf("toggle do_not_disturb off"),
            logAs = "gym", builtIn = true),
        Mode(name = "Writing", onStart = listOf("toggle do_not_disturb on", "open_app Google Docs", "timer 2700 | Writing block"), onStop = listOf("toggle do_not_disturb off"),
            blockedApps = listOf("com.instagram.android", "com.google.android.youtube", "com.snapchat.android"), focusMinutes = 45, logAs = "writing", builtIn = true),
        Mode(name = "College", onStart = listOf("ringer silent"), onStop = listOf("ringer normal"), logAs = "college", builtIn = true),
        Mode(name = "Sleep", onStart = listOf("toggle do_not_disturb on", "brightness 5"), onStop = listOf("toggle do_not_disturb off"), logAs = "sleep", builtIn = true),
    )

    data class ModeCommand(val start: Boolean, val name: String?, val subject: String?)

    private val startRx = Regex("^(?:start |begin |enter |switch to |turn on |activate |go into )?(?:the )?([a-z ]+?) mode(?: (?:for|on) (.+))?(?: (?:on|start|chalu karo|shuru karo|lagao))?$", RegexOption.IGNORE_CASE)
    private val stopRx = Regex("^(?:stop|end|exit|leave|turn off|quit|band karo|khatam karo)(?: the)? ?(?:([a-z ]+?) )?mode(?: (?:off|band karo))?$|^(?:([a-z ]+?) )?mode (?:off|band karo|khatam)$", RegexOption.IGNORE_CASE)

    fun parse(text: String, known: List<Mode>): ModeCommand? {
        val t = Fuzzy.normalize(text)
        stopRx.matchEntire(t)?.let { m -> return ModeCommand(false, m.groupValues[1].ifBlank { m.groupValues[2] }.trim().ifBlank { null }, null) }
        startRx.matchEntire(t)?.let { m ->
            val name = m.groupValues[1].trim()
            val mode = known.firstOrNull { Fuzzy.normalize(it.name) == name } ?: known.firstOrNull { Fuzzy.similarity(it.name, name) > 0.75 } ?: return null
            return ModeCommand(true, mode.name, m.groupValues[2].trim().ifBlank { null })
        }
        return null
    }
}

/** CA Inter study helpers. */
object Study {
    fun caInterSubjects() = listOf(
        Subject("Advanced Accounting"), Subject("Corporate and Other Laws"), Subject("Taxation"),
        Subject("Cost and Management Accounting"), Subject("Auditing and Ethics"), Subject("Financial Management and Strategic Management"),
    )

    private val aliases = mapOf(
        "accounts" to "Advanced Accounting", "accounting" to "Advanced Accounting", "law" to "Corporate and Other Laws", "laws" to "Corporate and Other Laws",
        "tax" to "Taxation", "gst" to "Taxation", "income tax" to "Taxation", "costing" to "Cost and Management Accounting", "cost" to "Cost and Management Accounting",
        "audit" to "Auditing and Ethics", "fm" to "Financial Management and Strategic Management", "sm" to "Financial Management and Strategic Management",
        "fmsm" to "Financial Management and Strategic Management",
    )

    fun resolve(name: String?, subjects: List<Subject>): String? {
        name ?: return null
        val n = Fuzzy.normalize(name)
        aliases[n]?.let { return it }
        return subjects.firstOrNull { Fuzzy.normalize(it.name).contains(n) || n.contains(Fuzzy.normalize(it.name)) }?.name
            ?: subjects.maxByOrNull { Fuzzy.similarity(it.name, n) }?.takeIf { Fuzzy.similarity(it.name, n) > 0.5 }?.name
    }

    /** SM-2 style update: good answer → longer interval. */
    fun review(c: Flashcard, correct: Boolean, now: Long = System.currentTimeMillis()): Flashcard {
        val ease = (c.ease + if (correct) 0.1 else -0.3).coerceIn(1.3, 3.0)
        val interval = if (correct) maxOf(1, (c.intervalDays * ease).toInt()) else 1
        return c.copy(ease = ease, intervalDays = interval, dueMs = now + interval * 24L * 3600_000)
    }
}
