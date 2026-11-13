package ai.nexus.core.agent

import ai.nexus.core.nlu.Fuzzy

/**
 * Canonical example commands: used by the in-app help, and to suggest
 * "Did you mean …?" when something wasn't understood.
 */
object CommandCatalog {
    val sections: List<Pair<String, List<String>>> = listOf(
        "KEEP GOING · LATER · SCHEDULE" to listOf("Keep scrolling up", "Scroll down slowly", "Faster", "Slower", "Stop", "Scroll the reel after 15 seconds", "After 10 minutes turn off wifi", "Call mom in 5 minutes", "At 9 pm message Rahul saying happy birthday", "Kal subah 9 baje Rahul ko message karo ki meeting hai", "What have I scheduled", "Cancel the scheduled message to Rahul"),
        "ASK ME ANYTHING (tap N or press volume up + down)" to listOf("Can you talk to me?", "Explain GST in simple words", "What is depreciation?", "Give me a quick pep talk", "How do I prepare for CA Inter audit?"),
        "ON ANY SCREEN" to listOf("What's on my screen", "Read this page", "Tap Settings", "Open the second result", "Scroll down", "Go back", "Tap top right", "Double tap center", "Send this to Arjun"),
        "TYPING IN ANY APP" to listOf("Type do it later", "Send see you tomorrow", "Copy this", "Paste"),
        "YOUTUBE" to listOf("Search filmmaking on YouTube", "Open the first video", "Open the first short", "Go to Shorts", "Next short", "Like it", "Comment amazing video", "Pause the video"),
        "INSTAGRAM" to listOf("Open Instagram and go to Reels", "Next reel", "Like it and save it", "Comment nice video", "Follow this account", "Open my messages"),
        "WHATSAPP & CALLS" to listOf("Open WhatsApp", "Open the family group", "Message Rahul saying I'll be late", "Call Rahul", "Read my notifications"),
        "MUSIC" to listOf("Open YT Music", "Play Believer", "Next song", "Pause the music"),
        "PHONE" to listOf("Turn on the flashlight", "Volume 40", "Brightness up", "Silent mode", "Turn off wifi", "Do not disturb on", "Take a screenshot", "Take a selfie", "Clear all notifications"),
        "MODES" to listOf("Study mode for taxation", "Gym mode", "Writing mode", "College mode", "Sleep mode", "Stop study mode", "Allow Instagram for 10 minutes"),
        "TASKS & DAY" to listOf("Add task: submit audit notes by Friday 5 pm", "What should I do now", "Show my tasks", "Mark audit notes as done", "Morning brief", "Evening review", "What did I miss"),
        "CAPTURE" to listOf("Note: idea for a script about a hostel night", "Spent 200 on lunch", "Add flashcard for taxation: GST full form | Goods and Services Tax", "Quiz me on taxation"),
        "PRODUCTIVITY" to listOf("Wake me up at 6:30 am", "Timer 10 minutes", "Remind me to drink water in 30 minutes", "Navigate to Connaught Place", "Add meeting with Rahul tomorrow at 5 pm", "What's 18% of 2450"),
        "MEMORY & TEACHING" to listOf("Remember that mom means Sunita Sharma", "What do you know about Rahul", "Always use WhatsApp for Rahul", "No, I meant Rahul Sharma", "Teach me", "Watch me", "Do the same thing again"),
        "CONTROL" to listOf("Stop", "Wait", "Continue", "That's not what I meant", "Skip", "Not now", "Hide the bubble"),
    )

    val all: List<String> by lazy { sections.flatMap { it.second }.filterNot { it.startsWith("Hey Nexus") } }

    /** The closest known command to something not understood, if it's reasonably close. */
    fun closest(text: String, extra: List<String> = emptyList()): String? {
        val t = Fuzzy.normalize(text)
        if (t.split(' ').size > 12) return null
        return (all + extra).map { it to Fuzzy.similarity(t, Fuzzy.normalize(it)) }.maxByOrNull { it.second }?.takeIf { it.second >= 0.6 }?.first
    }
}
