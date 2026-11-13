package ai.nexus.core.agent

import java.util.Calendar

/** "tomorrow at 5 pm", "on friday at 10" → epoch millis (local time). */
object Dates {
    private val days = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")

    fun resolve(nowMs: Long, dayText: String?, hour: Int?, minute: Int?): Long? {
        if (dayText == null && hour == null) return null
        val c = Calendar.getInstance().apply { timeInMillis = nowMs }
        val d = dayText?.lowercase()?.removePrefix("on ")?.trim()
        when {
            d == null || d == "today" || d == "aaj" -> {}
            d == "tomorrow" || d == "kal" -> c.add(Calendar.DAY_OF_YEAR, 1)
            d == "day after tomorrow" || d == "parso" -> c.add(Calendar.DAY_OF_YEAR, 2)
            d == "next week" -> c.add(Calendar.DAY_OF_YEAR, 7)
            days.any { d.endsWith(it) } -> {
                val target = days.indexOfFirst { d.endsWith(it) } + 1
                var diff = (target - c.get(Calendar.DAY_OF_WEEK) + 7) % 7
                if (diff == 0 || d.startsWith("next")) diff += if (d.startsWith("next") && diff == 0) 7 else if (diff == 0) 7 else 0
                c.add(Calendar.DAY_OF_YEAR, diff)
            }
            else -> Regex("(\\d{1,2})(?:st|nd|rd|th)?(?: of)? (\\w+)").find(d)?.let { m ->
                val month = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec").indexOfFirst { m.groupValues[2].lowercase().startsWith(it) }
                if (month >= 0) { c.set(Calendar.MONTH, month); c.set(Calendar.DAY_OF_MONTH, m.groupValues[1].toInt()); if (c.timeInMillis < nowMs) c.add(Calendar.YEAR, 1) }
            }
        }
        c.set(Calendar.HOUR_OF_DAY, hour ?: 9); c.set(Calendar.MINUTE, minute ?: 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        if (d == null && c.timeInMillis < nowMs) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }
}
