package ai.nexus.core.life

import ai.nexus.core.agent.Dates
import ai.nexus.core.nlu.Fuzzy

/** What NEXUS understood from a notification. */
data class Insight(
    val actionable: Boolean,
    /** task | deadline | event | question | info */
    val type: String,
    val title: String,
    val assignedBy: String?,
    val dueMs: Long?,
    /** 1 high … 3 low */
    val urgency: Int,
    val suggestedAction: String,
    val draftReply: String?,
)

/**
 * Offline understanding of notifications: filters out anything sensitive,
 * detects work assigned to the user ("Nikhil, please prepare the article on
 * X by Friday 6 PM"), extracts title and deadline. An LLM (if configured)
 * can refine this, but this works with no network at all.
 */
object NotificationIntel {
    private val sensitive = Regex(
        "\\b(?:otp|one[- ]time password|verification code|passcode|password|pin\\b|cvv|debited|credited|a/c|acct|account no|upi|bank|transaction|txn|payment|paid|refund|loan|emi|card ending|xx\\d{3,}|reset your password|login code|security code)\\b|\\b\\d{4,8}\\b.*\\b(?:is your|code)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val sensitiveApps = setOf("com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm", "in.org.npci.upiapp", "com.sbi.lotusintouch", "com.csam.icici.bank.imobile", "com.snapwork.hdfc", "com.axis.mobile")

    fun isSensitive(pkg: String, title: String, text: String): Boolean = pkg in sensitiveApps || sensitive.containsMatchIn("$title $text")

    private val requestRx = Regex(
        "\\b(?:please|pls|plz|kindly|can you|could you|need you to|you have to|you need to|make sure|assigned|your task|responsible for|prepare|submit|send|write|draft|complete|finish|share|review|edit|bring|upload|update|call|reply|confirm|karna hai|kar dena|bhej dena|bana dena|likh dena)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val dueRx = Regex(
        "\\b(?:by|before|till|until|due|deadline|on|latest by|tak)\\b|\\b(?:today|tonight|tomorrow|kal|parso|eod|end of day|asap|urgent|this week|next week|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val eventRx = Regex("\\b(?:meeting|meet|session|class|lecture|event|seminar|webinar|call at|interview|exam|test)\\b", RegexOption.IGNORE_CASE)

    /** Deadline in the text, as epoch ms, or null. */
    fun extractDue(text: String, now: Long): Long? {
        val t = text.lowercase()
        val clock = Regex("\\b(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)").find(t)
            ?: Regex("\\b(?:at|by)\\s+(\\d{1,2})(?:[:.](\\d{2}))?\\b").find(t)
        var hour: Int? = null; var minute: Int? = null
        clock?.let { m ->
            var h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toIntOrNull() ?: 0
            val ap = m.groupValues.getOrNull(3)?.replace(".", "") ?: ""
            if (ap.startsWith("p") && h < 12) h += 12
            if (ap.startsWith("a") && h == 12) h = 0
            if (ap.isEmpty() && h in 1..7) h += 12 // "by 6" in a work message usually means evening
            if (h in 0..23) { hour = h; minute = mi }
        }
        val day = Regex("\\b(today|tonight|tomorrow|kal|parso|day after tomorrow|eod|end of day|next week|(?:next )?(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)|\\d{1,2}(?:st|nd|rd|th)? (?:of )?(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*)\\b").find(t)?.value
        val inDays = Regex("\\bin (\\d+) days?\\b").find(t)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            inDays != null -> now + inDays * 24L * 3600_000
            day == "tonight" -> Dates.resolve(now, "today", hour ?: 21, minute ?: 0)
            day == "eod" || day == "end of day" -> Dates.resolve(now, "today", hour ?: 18, minute ?: 0)
            day != null -> Dates.resolve(now, if (day.startsWith("next ") && day != "next week") day.removePrefix("next ") else day, hour ?: 18, minute ?: 0)
            hour != null -> Dates.resolve(now, null, hour, minute)
            Regex("\\b(?:asap|urgent|right now|immediately)\\b").containsMatchIn(t) -> now + 2 * 3600_000L
            else -> null
        }
    }

    private fun titleFrom(text: String, userName: String): String {
        var t = text.replace(Regex("^(?:hi|hey|hello|dear)?\\s*,?\\s*${Regex.escape(userName)}\\s*[,:!-]?\\s*", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("^(?:please|pls|plz|kindly|can you|could you|you need to|you have to)\\s+", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s*(?:by|before|till|until|latest by|due)\\b.*$", RegexOption.IGNORE_CASE), "")
        t = t.trim().trimEnd('.', '!', '?', ',')
        return t.replaceFirstChar { it.uppercase() }.take(90).ifBlank { text.take(90) }
    }

    fun analyze(pkg: String, sender: String, text: String, userName: String, now: Long = System.currentTimeMillis()): Insight {
        val lower = text.lowercase()
        val mentionsMe = userName.isNotBlank() && Fuzzy.containsWord(lower, userName.lowercase())
        val isGroup = sender.contains(":") || sender.contains("@")
        val asked = requestRx.containsMatchIn(lower)
        val due = extractDue(text, now)
        val event = eventRx.containsMatchIn(lower)
        val question = text.trim().endsWith("?")
        val actionable = (asked && (mentionsMe || !isGroup) && (due != null || mentionsMe)) || (asked && due != null && mentionsMe) || (event && due != null)
        val type = when {
            actionable && event && !asked -> "event"
            actionable && due != null -> "deadline"
            actionable -> "task"
            question && (mentionsMe || !isGroup) -> "question"
            else -> "info"
        }
        val who = sender.substringBefore(":").substringBefore("(").trim().ifBlank { null }
        val hoursLeft = due?.let { (it - now) / 3600_000.0 }
        val urgency = when { hoursLeft != null && hoursLeft < 12 -> 1; hoursLeft != null && hoursLeft < 72 -> 2; Regex("urgent|asap").containsMatchIn(lower) -> 1; else -> 3 }
        val title = if (actionable) titleFrom(text, userName) else text.take(80)
        val draft = when (type) {
            "deadline", "task" -> "Noted${if (due != null) ", will share it by ${Briefs.whenText(due, now)}" else ", will do"}."
            "event" -> "Noted, I'll be there."
            "question" -> null
            else -> null
        }
        val suggested = when (type) {
            "deadline", "task" -> "Create a task, set reminders${if (Regex("article|write|script|draft|essay|post|report|content|editorial").containsMatchIn(lower)) " and prepare a first draft" else ""}"
            "event" -> "Add to calendar and remind before"
            "question" -> "Reply"
            else -> "Nothing needed"
        }
        return Insight(actionable || type == "question", type, title, who, due, urgency, suggested, draft)
    }

    /** First-draft outline for writing / editorial tasks, offline. */
    fun outline(title: String): String {
        val topic = title.replace(Regex("^(?:prepare|write|draft|make|create|do)\\s+(?:the |an? )?", RegexOption.IGNORE_CASE), "").trim()
        return """
            Draft outline: $topic

            1. Hook — a striking fact, story or question to open with.
            2. Context — why this matters now (on campus / in our city).
            3. Key points
               • What the problem is and who it affects
               • Facts and figures (cite sources)
               • Myths vs. facts
            4. Voices — a quote from a student, expert or cell member.
            5. What readers can do — practical steps, helplines, where to get help.
            6. Closing line — a memorable takeaway or call to action.

            Notes: keep it under 600 words; add a headline and 2 sub-heads.
        """.trimIndent()
    }

    /** Reminder times before a deadline, skipping ones already past. */
    fun reminderTimes(dueMs: Long, offsetsMin: List<Int>, now: Long): List<Long> =
        offsetsMin.map { dueMs - it * 60_000L }.filter { it > now }.sorted()
}
