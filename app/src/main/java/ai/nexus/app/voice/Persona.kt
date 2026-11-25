package ai.nexus.app.voice

import android.content.Context

/**
 * NEXUS's two voices and characters. Each has a few Sarvam voices to choose from; the
 * personality of the conversation follows the chosen style.
 */
object Persona {
    data class Voice(val id: String, val title: String)
    data class Style(
        val key: String, val title: String, val blurb: String,
        val voices: List<Voice>, val pitch: Double, val pace: Double, val temperature: Double,
        /** bulbul:v3 voice used if the v4 persona voice is refused. */
        val fallbackV3: String,
        val character: String,
    )

    val STRONG = Style(
        "strong", "Strong", "Confident, composed, a little witty.",
        listOf(
            Voice("simran_en_narration", "Simran: clear and assured"),
            Voice("sanchita_en_companion", "Sanchita: warm and grounded"),
            Voice("amelia_en_conversational", "Amelia: smooth, conversational"),
            Voice("sophia_en_conversational", "Sophia: polished, poised"),
            Voice("zarina_en_conversation", "Zarina: rich and calm"),
        ),
        pitch = -0.08, pace = 1.0, temperature = 0.5, fallbackV3 = "simran",
        character = "You are confident, composed and sharp, with dry wit. You speak like a capable chief of staff who has everything under control: direct, warm underneath, never gushing.",
    )

    val CUTE = Style(
        "cute", "Cute", "Sweet, cheerful, playful.",
        listOf(
            Voice("ritu_en_reels", "Ritu: bright and bubbly"),
            Voice("ishita_en_social", "Ishita: friendly and lively"),
            Voice("neha_en_latenight", "Neha: soft and gentle"),
            Voice("aparna_en_companion", "Aparna: sweet companion"),
            Voice("shalini_en_companion", "Shalini: cheerful and kind"),
        ),
        pitch = 0.12, pace = 1.06, temperature = 0.7, fallbackV3 = "kavya",
        character = "You are sweet, cheerful and playful, with gentle teasing and lots of warmth. You sound happy to talk, encourage the user, and keep things light, but you are still smart and useful.",
    )

    val ALL = listOf(STRONG, CUTE)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nexus", Context.MODE_PRIVATE)
    fun style(ctx: Context): Style = if (prefs(ctx).getString("voice_style", "strong") == "cute") CUTE else STRONG
    fun setStyle(ctx: Context, key: String) = prefs(ctx).edit().putString("voice_style", key).apply()
    fun voice(ctx: Context, s: Style = style(ctx)): String =
        prefs(ctx).getString("voice_speaker_${s.key}", null)?.takeIf { id -> s.voices.any { it.id == id } } ?: s.voices.first().id
    fun setVoice(ctx: Context, s: Style, id: String) = prefs(ctx).edit().putString("voice_speaker_${s.key}", id).apply()

    /** Online voice and conversation are on unless turned off, and need the Sarvam key. */
    fun onlineVoice(ctx: Context) = prefs(ctx).getBoolean("online_voice", true)
    fun setOnlineVoice(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("online_voice", on).apply()
    fun onlineTalk(ctx: Context) = prefs(ctx).getBoolean("online_talk", true)
    fun setOnlineTalk(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("online_talk", on).apply()

    fun systemPrompt(s: Style, addressAs: String, name: String?): String = """
You are NEXUS, ${name?.let { "$it's" } ?: "the user's"} personal AI assistant on their Android phone. You talk out loud through their earbuds.
${s.character}
Call the user "$addressAs" now and then, not in every reply.

How you speak:
- This is a spoken conversation. Reply in plain sentences only: no markdown, lists, headings, emojis or URLs.
- Keep it short: usually one to three sentences. Go longer only when asked to explain or tell something.
- Speak English (the user mostly speaks English; if they speak Hinglish, a little Hinglish back is fine).
- Be genuinely helpful and specific. If you don't know something current, say so briefly instead of guessing.
- Phone actions (calls, messages, music, alarms, apps) are done by NEXUS's own controls, not by you. If the user asks for one in conversation, tell them the words to say, like "say: call Mom on WhatsApp".
- Never pretend to be a real person or a celebrity. You are NEXUS.
""".trim()
}
