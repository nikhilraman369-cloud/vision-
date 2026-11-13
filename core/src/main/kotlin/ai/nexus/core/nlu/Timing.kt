package ai.nexus.core.nlu

import ai.nexus.core.agent.Dates
import ai.nexus.core.device.Direction

/**
 * Open-ended, time-based requests that aren't single commands:
 *  - "keep scrolling up", "scroll down slowly", "upar scroll karte raho"  → AutoScroll
 *  - "next reel every 15 seconds", "scroll the reel after 15 seconds"     → RepeatEvery
 *  - "after 10 minutes turn off wifi", "call mom in 5 minutes"             → After
 *  - "at 9 pm message Rahul saying hi", "schedule a message to Rahul tomorrow at 9 saying …",
 *    "kal subah 9 baje Rahul ko message karo ki …"                         → ScheduleAt
 */
object Timing {
    private const val N = "(\\d+|a|an|one|ek|two|do|five|ten|fifteen|thirty)"
    private const val UNIT = "(seconds?|secs?|sec|s|minutes?|mins?|min|m|hours?|hrs?|hr|h|second|minute|ghante?|ghanta|mint?)"
    private const val DIR = "(up|upwards|upward|down|downwards|downward|upar|oopar|neeche|niche)"
    private const val TARGET = "(?:\\s+(?:the\\s+)?(?:page|chat|screen|pdf|feed|document|doc|it|this|messages?|list))?"
    private val SLOW = Regex("\\b(slowly|slow|dheere|dhire|aram se|gently|very slowly)\\b")
    private val FAST = Regex("\\b(fast|quickly|quick|tez|jaldi|faster)\\b")

    private val DAYW = "(?:today|tonight|tomorrow|kal|aaj|parso|day after tomorrow|(?:next |this |on )?(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday))"
    private val PART = "(?:morning|evening|night|afternoon|subah|shaam|sham|raat|dopahar)"
    private val CLOCK = "(?:\\d{1,2}(?:[:.]\\d{2})?\\s*(?:am|pm|a\\.m\\.|p\\.m\\.|baje|o'?clock))"
    private val AT = "(?:(?:at|by|@)\\s+\\d{1,2}(?:[:.]\\d{2})?(?:\\s*(?:am|pm|baje))?)"
    val TIME = "(?:(?:$DAYW\\s+)?(?:(?:in the |ki |ko )?$PART\\s+)?(?:$AT|$CLOCK)(?:\\s+$DAYW)?(?:\\s+(?:in the )?$PART)?|$DAYW\\s+(?:in the )?$PART)"

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    fun seconds(n: String, unit: String): Int {
        val v = when (n.lowercase()) { "a", "an", "one", "ek" -> 1; "two", "do" -> 2; "five" -> 5; "ten" -> 10; "fifteen" -> 15; "thirty" -> 30; else -> n.toIntOrNull() ?: 1 }
        val u = unit.lowercase()
        return when {
            u.startsWith("h") || u.startsWith("ghant") -> v * 3600
            u.startsWith("m") -> v * 60
            else -> v
        }
    }

    private fun direction(w: String?): Direction = if (w != null && Regex("^(up|upwards|upward|upar|oopar)$").matches(w.lowercase())) Direction.UP else Direction.DOWN
    private fun speed(t: String): Int = when { SLOW.containsMatchIn(t) -> 1; FAST.containsMatchIn(t) -> 3; else -> 2 }

    private val AUTO = listOf(
        rx("^(?:keep|continue|start|go on|auto|automatically|continuously|just keep)\\s+(?:on\\s+)?(?:auto\\s*)?(?:scroll|scrolling|swiping|swipe)(?:ing)?$TARGET(?:\\s+$DIR)?\\b(.*)$"),
        rx("^auto\\s*scroll$TARGET(?:\\s+$DIR)?\\b(.*)$"),
        rx("^(?:scroll|swipe)$TARGET(?:\\s+$DIR)?\\s+(?:slowly|slow|very slowly|dheere(?: dheere)?|continuously|nonstop|non stop|until i say stop|till i say stop|automatically|aram se|gently|lagatar)\\b(.*)$"),
        rx("^(?:$DIR\\s+)?(?:dheere dheere\\s+|aram se\\s+)?scroll\\s+(?:karte raho|karta reh|karte rehna|karo lagatar|karo dheere dheere|karte jao)(.*)$"),
    )

    private val REELS_EVERY = rx("^(?:scroll|swipe|change|move|go|skip)\\s+(?:to\\s+)?(?:the\\s+)?(?:next\\s+)?(?:reel|reels|short|shorts|video|videos|story|stories|post|posts)\\s+(?:after|every|each|har)\\s+(?:every\\s+)?$N?\\s*$UNIT\\b.*$")
    private val EVERY_LEAD = rx("^(?:every|har|each)\\s+$N?\\s*$UNIT\\s*(?:,|ke baad|baad)?\\s+(.+)$")
    private val EVERY_TRAIL = rx("^(.+?)\\s+(?:every|har|each)\\s+$N?\\s*$UNIT(?:\\s+(?:until|till) .*)?$")
    private val AFTER_LEAD = rx("^(?:after|in)\\s+$N\\s*$UNIT\\s*,?\\s+(.+)$")
    private val AFTER_LEAD_HI = rx("^$N\\s*$UNIT\\s+(?:ke baad|baad|mein|me)\\s*,?\\s*(.+)$")
    private val AFTER_TRAIL = rx("^(.+?)\\s+(?:after|in)\\s+$N\\s*$UNIT$")
    private val AFTER_TRAIL_HI = rx("^(.+?)\\s+$N\\s*$UNIT\\s+(?:ke baad|baad|mein|me)$")
    /** Things that already carry their own time (alarms, reminders, timers) or where the time is the object ("6 baje ka alarm"). */
    private val NOT_DELAYABLE = rx("\\b(?:remind|reminder|timer|alarm|wake me|wake up|uthana|uthao|jagana|jagao|add (?:a )?task|meeting|event|calendar)\\b|^(?:note|ka|ki|ke|wala|wali|tak)\\b")
    private val SCHEDULE_MSG = rx("^(?:schedule|set|plan)\\s+(?:a\\s+|an?\\s+)?(?:whatsapp\\s+)?(?:message|msg|text|sms|reminder message)\\s+(?:to|for)\\s+(.+)$")
    private val MSG_TIME_FIRST = rx("^(send|message|text|whatsapp|tell|msg)\\s+(.+?)\\s+($TIME)\\s+(saying|that says|that|ki|:|-)\\s*(.+)$")
    private val LEAD_TIME = rx("^($TIME)\\s*,?\\s+(.+)$")
    private val TIME_RX = rx("\\b$TIME\\b")
    private val SPEED = rx("^(?:(?:a bit |thoda |little )?(faster|speed up|go faster|tez karo|thoda tez|tez|jaldi karo)|(?:(?:a bit |thoda |little )?(slower|slow down|go slower|dheere karo|thoda dheere|dheere)))$")

    /**
     * Whole-utterance check. [concrete] says whether some text parses into real
     * phone commands (not chat), so we only delay/repeat things NEXUS can do.
     */
    fun parse(t0: String, concrete: (String) -> Boolean, isMessage: (String) -> Boolean): Command? {
        val t = t0.trim().trimEnd('.', '!')
        SPEED.matchEntire(t)?.let { m -> return Command.LoopSpeed(faster = m.groupValues[1].isNotEmpty()) }
        for (r in AUTO) r.matchEntire(t)?.let { m ->
            val dirWord = m.groupValues.drop(1).firstOrNull { Regex("^$DIR$", RegexOption.IGNORE_CASE).matches(it) }
            return Command.AutoScroll(direction(dirWord), speed(t))
        }
        REELS_EVERY.matchEntire(t)?.let { m -> return Command.RepeatEvery(seconds(m.groupValues[1].ifBlank { "1" }, m.groupValues[2]).coerceAtLeast(2), "next") }
        EVERY_LEAD.matchEntire(t)?.let { m -> val inner = m.groupValues[3]; if (concrete(inner)) return Command.RepeatEvery(seconds(m.groupValues[1].ifBlank { "1" }, m.groupValues[2]).coerceAtLeast(2), inner) }
        EVERY_TRAIL.matchEntire(t)?.let { m -> val inner = m.groupValues[1]; if (!isMessage(inner) && concrete(inner)) return Command.RepeatEvery(seconds(m.groupValues[2].ifBlank { "1" }, m.groupValues[3]).coerceAtLeast(2), inner) }

        // Scheduled for a clock time / day.
        SCHEDULE_MSG.matchEntire(t)?.let { m ->
            val rest = m.groupValues[1]
            val time = TIME_RX.findAll(rest).lastOrNull()
            if (time != null) {
                val inner = "message " + (rest.removeRange(time.range)).replace(Regex("\\s{2,}"), " ").trim()
                return Command.ScheduleAt(time.value.trim(), inner)
            }
        }
        MSG_TIME_FIRST.matchEntire(t)?.let { m ->
            val inner = "${m.groupValues[1]} ${m.groupValues[2]} saying ${m.groupValues[5]}"
            return Command.ScheduleAt(m.groupValues[3].trim(), inner)
        }
        LEAD_TIME.matchEntire(t)?.let { m ->
            val inner = m.groupValues[2]
            if (!NOT_DELAYABLE.containsMatchIn(inner) && concrete(inner)) return Command.ScheduleAt(m.groupValues[1].trim(), inner)
        }

        // Relative delays.
        for (r in listOf(AFTER_LEAD, AFTER_LEAD_HI)) r.matchEntire(t)?.let { m ->
            val inner = m.groupValues[3]
            if (!NOT_DELAYABLE.containsMatchIn(inner) && concrete(inner)) return Command.After(seconds(m.groupValues[1], m.groupValues[2]), inner)
        }
        // Trailing "… in 10 minutes": not for messages (that's usually part of the text).
        for (r in listOf(AFTER_TRAIL, AFTER_TRAIL_HI)) r.matchEntire(t)?.let { m ->
            val inner = m.groupValues[1]
            if (!NOT_DELAYABLE.containsMatchIn(inner) && !isMessage(inner) && concrete(inner)) return Command.After(seconds(m.groupValues[2], m.groupValues[3]), inner)
        }
        return null
    }

    /** "tomorrow at 9", "9:30 pm", "kal subah 9 baje", "tonight", "monday evening" → epoch ms (always in the future). */
    fun resolve(expr: String, now: Long): Long? {
        val t = expr.lowercase().trim()
        val day = Regex(DAYW).find(t)?.value?.removePrefix("on ")?.removePrefix("this ")?.trim()
        val part = Regex(PART).find(t)?.value
        val clock = Regex("(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?").find(t.replace(Regex("^(?:at|by|@)\\s+"), "").let { Regex(DAYW).replace(it, " ") })
        var h = clock?.groupValues?.get(1)?.toIntOrNull()
        val m = clock?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val ap = clock?.groupValues?.get(3)?.replace(".", "").orEmpty()
        val lateNight = part in setOf("raat", "night") && h != null && h in 1..4 && ap.isEmpty() // "raat 2 baje" = 2 am
        val pm = !lateNight && (ap.startsWith("p") || part in setOf("evening", "night", "shaam", "sham", "raat", "afternoon", "dopahar") || day == "tonight")
        val am = ap.startsWith("a") || part in setOf("morning", "subah")
        if (h != null) {
            if (h > 23 || m > 59) return null
            if (pm && h < 12) h += 12
            if (am && h == 12) h = 0
        } else h = when (part) { "morning", "subah" -> 9; "afternoon", "dopahar" -> 14; "evening", "shaam", "sham" -> 19; "night", "raat" -> 21; else -> if (day == "tonight") 21 else null }
        if (h == null && day == null) return null
        val dayWord = if (day == "tonight") "today" else day
        val explicit = ap.isNotEmpty() || part != null
        val first = Dates.resolve(now, dayWord, h, m) ?: return null
        if (!explicit && h != null && h < 12) {
            // "at 9" with no am/pm: the next 9 o'clock that is still ahead.
            val alt = Dates.resolve(now, dayWord, h + 12, m)
            if (dayWord == null || dayWord == "today" || dayWord == "aaj") {
                val candidates = listOfNotNull(first, alt).filter { it > now }
                return candidates.minOrNull() ?: first
            }
            if (h in 1..6 && alt != null) return alt
        }
        return if (first <= now && (dayWord == "today" || dayWord == "aaj")) null else first
    }
}
