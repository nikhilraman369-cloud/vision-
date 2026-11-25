package ai.nexus.core.voice

/** Turning streamed AI text into sentences that can be spoken. */
object SpeechText {
    /** Splits streamed text into speakable sentences; returns (finished sentences, leftover). */
    fun takeSentences(buf: String): Pair<List<String>, String> {
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < buf.length) {
            val c = buf[i]
            val end = c == '\n' || (c == '.' || c == '!' || c == '?') &&
                (i + 1 < buf.length && buf[i + 1].isWhitespace()) &&
                // "Dr. ", "e.g. ", "3.5" are not sentence ends
                !(c == '.' && Regex("(?:\\b(?:mr|mrs|ms|dr|st|vs|etc|e\\.g|i\\.e)|\\d)$", RegexOption.IGNORE_CASE).containsMatchIn(buf.substring(start, i)))
            if (end) {
                val s = buf.substring(start, i + 1).trim()
                if (s.length > 1) out += s
                start = i + 1
            }
            i++
        }
        return out to buf.substring(start)
    }

    /** Strip anything that must not be read aloud. */
    fun speakable(s: String): String = s
        .replace(Regex("\\*\\*|__|`+|#+\\s*"), "")
        .replace(Regex("^\\s*[-•*]\\s+", RegexOption.MULTILINE), "")
        .replace(Regex("https?://\\S+"), "")
        .replace(Regex("[\\p{So}\\p{Cn}]"), "")
        .replace(Regex("\\s+"), " ").trim()
}
