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

    /**
     * English words spoken in Hindi come back from speech recognition in Devanagari ("कॉल", "टाइमर", "चैट जीपीटी").
     * Letter-by-letter they become "kol", "taimar", "chait jipiti": turn them back into the English word.
     * Only applied to text that was Devanagari, so real English is never touched.
     */
    private val devWords: Map<String, String> = mapOf(
        "kol" to "call", "kaul" to "call", "myujik" to "music", "myuzik" to "music", "myujhik" to "music", "myusik" to "music",
        "chait" to "chat", "chaitajipiti" to "chatgpt", "chaitjipiti" to "chatgpt", "chetajipiti" to "chatgpt", "jipiti" to "gpt", "jeepiti" to "gpt", "jipiti." to "gpt",
        "klod" to "claude", "klaud" to "claude", "klaude" to "claude", "kload" to "claude", "jemini" to "gemini", "jaimini" to "gemini", "jemni" to "gemini",
        "bnd" to "band", "taimar" to "timer", "taimer" to "timer", "minat" to "minute", "minut" to "minute", "minaton" to "minute", "sekand" to "second", "sekend" to "second", "sekind" to "second",
        "men" to "mein", "yad" to "yaad", "aavaz" to "awaaz", "avaz" to "awaaz", "aavaj" to "awaaz", "avaj" to "awaaz", "aavaaz" to "awaaz",
        "kaimara" to "camera", "kaimra" to "camera", "kemra" to "camera", "kaimre" to "camera", "selphi" to "selfie", "selfi" to "selfie", "sailphi" to "selfie",
        "kolej" to "college", "kalej" to "college", "grup" to "group", "grupa" to "group", "klas" to "class", "kainsal" to "cancel", "kensal" to "cancel",
        "phon" to "phone", "fon" to "phone", "sat" to "saat", "mesej" to "message", "maisej" to "message", "mesaj" to "message", "tekst" to "text",
        "sarch" to "search", "sarcha" to "search", "ple" to "play", "pley" to "play", "vois" to "voice", "voyas" to "voice", "krom" to "chrome", "brauzar" to "browser",
        "nyu" to "new", "agala" to "agla", "agali" to "agli", "pichala" to "pichla", "pichali" to "pichli", "gana" to "gaana", "mammi" to "mummy", "alarma" to "alarm", "alaram" to "alarm", "elarm" to "alarm", "torcha" to "torch", "volyum" to "volume", "voliyum" to "volume", "valyum" to "volume",
        "phlaish" to "flash", "flaish" to "flash", "lait" to "light", "on" to "on", "of" to "off", "aph" to "off", "oph" to "off", "ainda" to "and",
        "vidiyo" to "video", "vidio" to "video", "vediyo" to "video", "risent" to "recent", "lest" to "latest", "letest" to "latest", "send" to "send", "sainda" to "send",
        "guga" to "google", "gugal" to "google", "yutyub" to "YouTube", "watsaep" to "WhatsApp", "vhatsaep" to "WhatsApp", "vatsaep" to "WhatsApp", "vatsap" to "WhatsApp",
    )

    fun normalize(raw: String): String {
        val fromDevanagari = Devanagari.containsDevanagari(raw)
        var s = Devanagari.toLatin(raw).trim()
        if (fromDevanagari) s = s.split(' ').joinToString(" ") { w ->
            val core = w.trimEnd('.', ',', '?', '!'); val tail = w.substring(core.length)
            (devWords[core.lowercase()] ?: core) + tail
        }.replace(rx("\\bchat ?gpt\\b|\\bchat gpt\\b"), "chatgpt").replace(rx("\\bflash ?light\\b"), "flashlight")
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
