package ai.nexus.core.life

import ai.nexus.core.nlu.Fuzzy
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun dayKey(ms: Long): Int { val c = Calendar.getInstance().apply { timeInMillis = ms }; return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH) }

/** Voice capture inbox: "note: …" files itself into the right bucket. */
object Inbox {
    fun categorize(text: String): String {
        val t = text.lowercase()
        return when {
            Regex("\\b(?:spent|paid|bought|₹|rs\\.?|rupees|inr|expense|kharcha)\\b|\\b\\d+ ?(?:rs|rupees)\\b").containsMatchIn(t) -> "Expenses"
            Regex("\\b(?:accounts?|accounting|tax|gst|audit|costing|law|fm|sm|chapter|revision|revise|ca inter|icai|mtp|rtp|study)\\b").containsMatchIn(t) -> "Study"
            Regex("\\b(?:script|scene|article|story|idea|headline|editorial|poem|blog|caption|plot|character|write|writing)\\b").containsMatchIn(t) -> "Writing ideas"
            Regex("\\b(?:todo|to do|need to|have to|must|remind|call|buy|submit|finish|send|pay|book)\\b").containsMatchIn(t) -> "Tasks"
            else -> "General"
        }
    }

    private val expenseRx = Regex("(?:spent|paid|kharch(?:a|e) kiye?|expense(?: of)?|₹|rs\\.?|inr)\\s*(\\d+(?:\\.\\d+)?)|(\\d+(?:\\.\\d+)?)\\s*(?:rs|rupees|₹|inr|rupaye)", RegexOption.IGNORE_CASE)

    fun parseExpense(text: String): Expense? {
        val m = expenseRx.find(text) ?: return null
        val amount = (m.groupValues[1].ifBlank { m.groupValues[2] }).toDoubleOrNull() ?: return null
        val note = Regex("\\b(?:on|for|at|pe|par)\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim()?.trimEnd('.') ?: ""
        val cat = when {
            Regex("food|lunch|dinner|breakfast|snack|coffee|tea|chai|zomato|swiggy|restaurant", RegexOption.IGNORE_CASE).containsMatchIn(note) -> "Food"
            Regex("uber|ola|auto|metro|bus|petrol|fuel|cab|rapido|travel", RegexOption.IGNORE_CASE).containsMatchIn(note) -> "Travel"
            Regex("book|notes|course|fees|class|stationery|print", RegexOption.IGNORE_CASE).containsMatchIn(note) -> "Study"
            Regex("movie|netflix|game|party|outing", RegexOption.IGNORE_CASE).containsMatchIn(note) -> "Fun"
            Regex("recharge|bill|rent|electricity|wifi|internet", RegexOption.IGNORE_CASE).containsMatchIn(note) -> "Bills"
            else -> "Other"
        }
        return Expense(amount = amount, category = cat, note = note)
    }
}

object Briefs {
    fun whenText(ms: Long, now: Long): String {
        val today = dayKey(now); val d = dayKey(ms)
        val time = SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date(ms))
        return when (d) {
            today -> "today $time"
            dayKey(now + 86_400_000) -> "tomorrow $time"
            else -> SimpleDateFormat("EEE d MMM", Locale.ENGLISH).format(Date(ms)) + " $time"
        }
    }

    fun partOfDay(now: Long): String = when (Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)) {
        in 4..11 -> "morning"; in 12..16 -> "afternoon"; in 17..21 -> "evening"; else -> "night"
    }

    /**
     * "What should I do now?": deadline pressure first, then priority, then
     * tasks that fit the current mode (study tasks in Study mode, …).
     */
    fun rank(tasks: List<TaskItem>, mode: String?, now: Long): List<TaskItem> = tasks.filter { !it.done }.sortedByDescending { t ->
        var s = (4 - t.priority) * 10.0
        t.dueMs?.let { due ->
            val h = (due - now) / 3600_000.0
            s += when { h < 0 -> 60.0; h < 3 -> 50.0; h < 24 -> 35.0; h < 72 -> 20.0; else -> 5.0 }
        }
        if (mode != null && t.category.equals(mode, true)) s += 15
        if (mode.equals("study", true) && t.category == "Study") s += 15
        if (mode.equals("writing", true) && (t.category == "Writing ideas" || t.title.contains("article", true) || t.title.contains("script", true))) s += 15
        s
    }

    fun streak(days: List<Int>, now: Long): Int {
        val set = days.toSet(); var n = 0; var t = now
        if (dayKey(t) !in set) t -= 86_400_000 // today not done yet doesn't break the streak
        while (dayKey(t) in set) { n++; t -= 86_400_000 }
        return n
    }

    fun minutesOn(sessions: List<ModeSession>, logAs: String, sinceMs: Long, now: Long) = sessions.filter { it.mode.equals(logAs, true) && it.startMs >= sinceMs }
        .sumOf { (((it.endMs ?: now) - it.startMs) / 60_000).toInt() }

    fun morning(d: LifeData, name: String, now: Long): String = buildString {
        val date = SimpleDateFormat("EEEE, d MMMM", Locale.ENGLISH).format(Date(now))
        append("Good morning, $name. It's $date. ")
        val open = d.tasks.filter { !it.done }
        val dueToday = open.filter { it.dueMs != null && dayKey(it.dueMs) == dayKey(now) }
        val overdue = open.filter { it.dueMs != null && it.dueMs < now }
        if (overdue.isNotEmpty()) append("${overdue.size} overdue: ${overdue.take(2).joinToString(" and ") { it.title }}. ")
        if (dueToday.isNotEmpty()) append("Due today: ${dueToday.take(3).joinToString(", ") { it.title + " at " + SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date(it.dueMs!!)) }}. ")
        val cell = open.filter { it.source.startsWith("notification") }.take(2)
        if (cell.isNotEmpty()) append("From your groups: ${cell.joinToString("; ") { it.title }}. ")
        val next = rank(open, d.activeMode, now).take(3)
        if (next.isNotEmpty()) append("Suggested plan: ${next.mapIndexed { i, t -> "${i + 1}, ${t.title}" }.joinToString("; ")}. ")
        val dueCards = d.flashcards.count { it.dueMs <= now }
        if (dueCards > 0) append("You have $dueCards revision cards due. ")
        if (open.isEmpty()) append("Your list is clear. A good day to get ahead on CA Inter.")
    }.trim()

    fun evening(d: LifeData, name: String, now: Long): String = buildString {
        val start = now - (now % 86_400_000) - 6 * 3600_000 // roughly since this morning
        val done = d.tasks.filter { it.done && (it.doneMs ?: 0) >= start }
        val slipped = d.tasks.filter { !it.done && it.dueMs != null && it.dueMs < now }
        val study = minutesOn(d.sessions, "study", start, now)
        append("Evening review, $name. ")
        append(if (done.isEmpty()) "No tasks marked done today. " else "Done today: ${done.take(4).joinToString(", ") { it.title }}. ")
        if (study > 0) append("You studied ${study / 60} hours ${study % 60} minutes. ")
        if (slipped.isNotEmpty()) append("Slipped: ${slipped.take(3).joinToString(", ") { it.title }}. ")
        val tomorrow = d.tasks.filter { !it.done && it.dueMs != null && dayKey(it.dueMs) == dayKey(now + 86_400_000) }
        if (tomorrow.isNotEmpty()) append("Tomorrow: ${tomorrow.take(3).joinToString(", ") { it.title }}.")
    }.trim()

    fun weekly(d: LifeData, now: Long): String {
        val week = now - 7 * 86_400_000L
        val study = minutesOn(d.sessions, "study", week, now)
        val done = d.tasks.count { it.done && (it.doneMs ?: 0) >= week }
        val streaks = listOf("study", "gym", "writing").joinToString(", ") { h -> "$h ${streak(d.habitDays[h] ?: emptyList(), now)} days" }
        val learned = d.skills.count { it.createdMs >= week }
        val weak = d.skills.filter { it.weak }.map { it.name }
        return "This week: ${study / 60}h ${study % 60}m study, $done tasks done, streaks: $streaks, $learned new skills learned" +
            (if (weak.isNotEmpty()) ", needs re-teaching: ${weak.joinToString()}" else "") + "."
    }
}

/** Fuzzy matching of user-taught skills ("send report to {name} on telegram"). */
object SkillMatcher {
    data class Hit(val skill: Skill, val slots: Map<String, String>, val score: Double)

    /** "send {message} to {name} on telegram" → ^send\s+(.+?)\s+to\s+(.+?)\s+on\s+telegram$ */
    private fun patternRx(trigger: String): Regex {
        val pieces = ArrayList<String>()
        var last = 0
        for (m in Regex("\\{(\\w+)}").findAll(trigger)) {
            Fuzzy.normalize(trigger.substring(last, m.range.first)).split(' ').filter { it.isNotBlank() }.forEach { pieces += Regex.escape(it) }
            pieces += "(.+?)"
            last = m.range.last + 1
        }
        Fuzzy.normalize(trigger.substring(last)).split(' ').filter { it.isNotBlank() }.forEach { pieces += Regex.escape(it) }
        return Regex("^" + pieces.joinToString("\\s+") + "$")
    }

    fun match(text: String, skills: List<Skill>): Hit? {
        val t = Fuzzy.normalize(text)
        var best: Hit? = null
        for (s in skills.filter { it.enabled }) for (trig in s.triggers) {
            val names = Regex("\\{(\\w+)}").findAll(trig).map { it.groupValues[1] }.toList()
            if (names.isEmpty()) {
                val sim = Fuzzy.similarity(t, trig)
                if (sim >= 0.86 && (best == null || sim > best.score)) best = Hit(s, emptyMap(), sim)
            } else {
                val m = runCatching { patternRx(trig).matchEntire(t) }.getOrNull() ?: continue
                val slots = names.mapIndexed { i, n -> n to m.groupValues[i + 1].trim() }.toMap()
                if (best == null || 0.95 > best.score) best = Hit(s, slots, 0.95)
            }
        }
        return best
    }

    fun fill(text: String?, slots: Map<String, String>): String? = text?.let { var r = it; slots.forEach { (k, v) -> r = r.replace("{$k}", v) }; r }

    /**
     * Turn a recording into a reusable skill: any typed text or tapped label
     * that also appears in the user's request becomes a parameter, so
     * "send report to Rahul" also works for "send notes to Priya".
     */
    fun build(name: String, utterance: String, steps: List<RecipeStep>): Skill {
        var trigger = Fuzzy.normalize(utterance.ifBlank { name })
        val outSteps = steps.toMutableList()
        var typeN = 0; var nameN = 0
        for ((i, st) in steps.withIndex()) {
            val value = when (st.action) { "type" -> st.text; "tap" -> st.selector?.text ?: st.selector?.desc; else -> null } ?: continue
            val v = Fuzzy.normalize(value)
            if (v.length < 2 || !Fuzzy.containsWord(trigger, v) && !trigger.contains(v)) continue
            if (st.action == "tap" && Fuzzy.normalize(name).contains(v)) continue
            val slot = if (st.action == "type") (if (typeN++ == 0) "message" else "text$typeN") else (if (nameN++ == 0) "name" else "name$nameN")
            trigger = trigger.replaceFirst(v, "{$slot}")
            outSteps[i] = when (st.action) {
                "type" -> st.copy(text = "{$slot}")
                else -> st.copy(selector = st.selector?.copy(text = "{$slot}", desc = null, viewId = null))
            }
        }
        val triggers = listOf(trigger, Fuzzy.normalize(name)).distinct().filter { it.isNotBlank() }
        val app = steps.firstOrNull { it.pkg != null }?.pkg
        return Skill(name = name, triggers = triggers, app = app, steps = outSteps)
    }
}
