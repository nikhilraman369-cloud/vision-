package ai.nexus.core.nlu

/**
 * Cleans up an utterance before parsing while keeping the user's original
 * casing for anything that will be typed (comments, messages, queries).
 *
 *  - Devanagari → Hinglish
 *  - wake word and filler/politeness removal ("hey nexus", "please", "can you … for me", "zara")
 *  - common speech-recognition mistakes ("you tube" → "YouTube", "rails" → "Reels")
 */
object TextNormalizer {
    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val wakeWord = rx("^\\s*(?:(?:hey|hi|hello|ok|okay|oye|arre|are)\\s+)?ne?xu?s\\b[\\s,.!:]*")
    private val wakeWordAnywhere = rx("\\b(?:hey|ok|okay)\\s+nexus\\b[\\s,.!:]*")

    private val leadingFillers = listOf(
        "please", "plz", "pls", "kindly", "can you", "could you", "would you", "will you", "can u", "could u",
        "i want you to", "i want to", "i'd like to", "i would like to", "i need you to", "i need to", "let's", "lets",
        "just", "quickly", "now", "ok", "okay", "so", "and", "umm", "um", "uh", "hmm", "achha", "acha", "zara", "jara",
        "bhai", "yaar", "nexus", "go ahead and", "help me", "try to", "for me",
    )
    private val trailingFillers = listOf(
        "please", "plz", "pls", "for me", "now", "right now", "quickly", "na", "yaar", "bhai", "zara", "jaldi", "jaldi se",
        "thanks", "thank you", "if you can", "dena", "do na", "kar do na", "karo na",
    )

    private val asrFixes: List<Pair<Regex, String>> = listOf(
        rx("\\binsta\\s*gram\\b") to "Instagram",
        rx("\\b(?:you|u)\\s*tube\\b") to "YouTube",
        rx("\\bwhat'?s\\s*app\\b") to "WhatsApp",
        rx("\\bwhat\\s*sap\\b") to "WhatsApp",
        rx("\\bwhatsapps?\\b") to "WhatsApp",
        rx("\\b(?:rails|reals|riels|reel's|realz)\\b") to "Reels",
        rx("\\breal\\b(?=\\s+(?:video|feed|section|tab))") to "reel",
        rx("\\bnext real\\b") to "next reel",
        rx("\\bshot(?:s)?\\b(?=\\s+(?:video|tab|feed|section))") to "Shorts",
        rx("\\bg\\s*mail\\b") to "Gmail",
        rx("\\bspot\\s*if(?:y|ai)\\b") to "Spotify",
        rx("\\bblue\\s*tooth\\b") to "Bluetooth",
        rx("\\bwi\\s*-?\\s*fi\\b|\\bwhy\\s*fi\\b") to "WiFi",
        rx("\\bflash\\s*light\\b") to "flashlight",
        rx("\\bcomment section\\b") to "comments",
        rx("\\bcommence\\b") to "comments",
        rx("\\bsubscribe button\\b") to "subscribe",
        rx("\\bface\\s*book\\b") to "Facebook",
        rx("\\btele\\s*gram\\b") to "Telegram",
        rx("\\bplay\\s*store\\b") to "Play Store",
        rx("\\bgo\\s+bak\\b") to "go back",
        rx("\\bpeeche\\b|\\bpichhe\\b|\\bpiche\\b|\\bpichey\\b|\\bpeechhe\\b") to "pichhe",
        rx("\\bwapas\\b|\\bvapas\\b|\\bwaapas\\b") to "wapas",
    )

    fun normalize(raw: String): String {
        var s = Devanagari.toLatin(raw).trim()
        s = s.replace('’', '\'').replace('“', '"').replace('”', '"')
        s = s.replace(wakeWord, "").replace(wakeWordAnywhere, " ")
        for ((r, rep) in asrFixes) s = s.replace(r, rep)
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }

    /** Removes politeness and filler words from the edges of one clause. */
    fun stripFillers(clause: String, trailing: Boolean = true): String {
        var s = clause.trim().trimEnd('.', '!', '?', ',').trim()
        var changed = true
        while (changed && s.isNotEmpty()) {
            changed = false
            for (f in leadingFillers) {
                val r = Regex("^${Regex.escape(f)}\\b[\\s,]*", RegexOption.IGNORE_CASE)
                if (r.containsMatchIn(s)) { s = s.replace(r, "").trim(); changed = true }
            }
            if (trailing) for (f in trailingFillers) {
                val r = Regex("[\\s,]*\\b${Regex.escape(f)}$", RegexOption.IGNORE_CASE)
                if (r.containsMatchIn(s) && s.length > f.length) { s = s.replace(r, "").trim(); changed = true }
            }
            val t = s.trimEnd('.', '!', '?', ',').trim()
            if (t != s) { s = t; changed = true }
        }
        return s
    }
}
