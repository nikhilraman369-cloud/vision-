package ai.nexus.core.agent

import ai.nexus.core.device.SystemActions
import ai.nexus.core.memory.LongTermMemory
import ai.nexus.core.nlu.Fuzzy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small talk and simple facts that work with no network or AI key. */
object OfflineChat {
    private val jokes = listOf(
        "I'd tell you a UDP joke, but you might not get it.",
        "My phone battery and I have one thing in common: we both drain fastest when you open Instagram.",
        "I asked the Wi-Fi for a date. It said we have no connection.",
    )

    suspend fun reply(text: String, memory: LongTermMemory, system: SystemActions): String {
        val t = Fuzzy.normalize(text)
        val name = memory.settings.name
        val now = Date(system.nowMs())
        return when {
            Regex("^(hi|hello|hey|namaste|hii+|yo)\\b").containsMatchIn(t) -> "Hey${name?.let { " $it" } ?: ""}! What can I do for you?"
            t.contains("how are you") || t.contains("kaise ho") -> "Running smoothly and ready to help. How about you?"
            t.contains("thank") || t.contains("shukriya") || t.contains("dhanyavaad") -> "Anytime!"
            t.contains("time") && (t.contains("what") || t.contains("kitne")) -> "It's ${SimpleDateFormat("h:mm a", Locale.ENGLISH).format(now)}."
            t.contains("date") || t.contains("what day") || t.contains("today") -> "Today is ${SimpleDateFormat("EEEE, d MMMM yyyy", Locale.ENGLISH).format(now)}."
            t.contains("battery") -> system.batteryPercent()?.let { "Your battery is at $it%." } ?: "I can't read the battery level right now."
            t.contains("joke") -> jokes.random()
            t.contains("your name") || t.contains("who are you") -> "I'm NEXUS, your personal assistant. I can run your phone for you and, with the cloud AI connected, chat about anything."
            t.contains("what can you do") || t.contains("help") -> "I can open apps, search YouTube, like and save Reels, write comments, message people on WhatsApp, make calls, change settings, read your screen and notifications — just tell me what you want in your own words."
            else -> "I can do phone tasks offline, but for questions like that I need the Claude AI connected — add your API key in NEXUS settings."
        }
    }
}
