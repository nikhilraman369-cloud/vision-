package ai.nexus.core.nlu

/**
 * Splits one utterance into clauses: "open YouTube, search filmmaking and
 * like the first video" → ["open YouTube", "search filmmaking", "like the first video"].
 *
 * The hard part is free text the user wants typed ("write amazing video",
 * "message Rahul saying I'll reach and call you"): once a clause has started a
 * payload, it only ends at a strong separator that is followed by a new action.
 */
object CommandSplitter {

    /** Words that can start an action clause (English imperative style). */
    private val actionStarts = setOf(
        "open", "go", "take", "return", "come", "scroll", "swipe", "find", "search", "look", "play", "pause", "resume",
        "stop", "like", "unlike", "dislike", "save", "unsave", "bookmark", "comment", "write", "type", "post", "send", "reply",
        "message", "text", "call", "dial", "ring", "phone", "follow", "unfollow", "subscribe", "unsubscribe", "share", "tap", "click",
        "press", "select", "choose", "long", "read", "show", "turn", "switch", "enable", "disable", "increase", "decrease",
        "raise", "lower", "set", "mute", "unmute", "skip", "next", "previous", "close", "copy", "paste", "back", "home", "check",
        "launch", "start", "watch", "listen", "tell", "put", "toggle", "navigate", "visit", "browse", "submit", "hit", "move",
        "pick", "use", "remember", "forget", "wait", "cancel", "continue", "repeat", "describe", "explain", "end", "hang",
        "rewind", "forward", "seek", "mark", "add", "remove", "delete", "download", "upload", "lock", "screenshot", "email",
        "compose", "whatsapp", "sms", "and", "then", "also", "jump", "exit", "quit", "refresh", "reload", "sort", "filter",
    )
    private val hindiVerbEndings = Regex(
        "\\b(kholo|khol do|karo|kar do|kariye|kijiye|jao|chalo|bhejo|bhej do|likho|likh do|dhundo|dhoondo|chalao|bajao|" +
            "lagao|dabao|padho|sunao|dikhao|band|chalu|ruko|badhao|ghatao)\\b",
        RegexOption.IGNORE_CASE,
    )

    private val payloadTrigger = Regex(
        "\\b(saying|says|say|that|write|type|comment|reply|replying|likho|likh do|ki|ke|bolo|bol do|with the text|message|text|caption|named|called)\\b",
        RegexOption.IGNORE_CASE,
    )

    private val strongReturn = Regex(
        "^(go back|go to|come back|return|send it|post it|send|post|submit|then|and then|go home|open|close|exit|wapas|pichhe)\\b",
        RegexOption.IGNORE_CASE,
    )

    // Separators, longest first.
    private val separator = Regex(
        "\\s*(?:,\\s*(?:and\\s+then|and|then)?|;|\\.\\s+|\\s(?:and\\s+then|after\\s+that|afterwards|and\\s+also|then|and|phir|fir|aur|uske\\s+baad|uske\\s+bad|iske\\s+baad|&)\\s)\\s*",
        RegexOption.IGNORE_CASE,
    )

    fun split(text: String): List<String> {
        val clauses = ArrayList<String>()
        var start = 0
        var current = StringBuilder()
        for (m in separator.findAll(text)) {
            val before = text.substring(start, m.range.first)
            val after = text.substring(m.range.last + 1).trimStart()
            val sep = m.value.trim().lowercase()
            val candidate = (current.toString() + before)
            if (shouldSplit(candidate, sep, after)) {
                clauses += candidate
                current = StringBuilder()
            } else {
                current.append(before).append(m.value)
            }
            start = m.range.last + 1
        }
        current.append(text.substring(start))
        clauses += current.toString()
        return clauses.map { it.trim().trim(',', '.', ';').trim() }.filter { it.isNotEmpty() }
    }

    private fun firstWord(s: String) = s.trimStart().substringBefore(' ').lowercase().trim(',', '.', '!', '?')

    private fun startsAction(after: String): Boolean {
        val w = firstWord(after)
        if (w in actionStarts) return true
        // Hindi is verb-final: "aur YouTube kholo" – the clause after the separator ends in a verb.
        val nextClause = after.split(Regex("[,;]|\\s(?:and|then|phir|aur)\\s")).firstOrNull().orEmpty()
        return hindiVerbEndings.containsMatchIn(nextClause) && nextClause.split(' ').size <= 6
    }

    private fun inPayload(clause: String): Boolean {
        val m = payloadTrigger.find(clause) ?: return false
        return clause.substring(m.range.last + 1).isNotBlank()
    }

    private fun shouldSplit(clause: String, sep: String, after: String): Boolean {
        if (clause.isBlank() || after.isBlank()) return false
        val isAndOnly = sep == "and" || sep == "&" || sep == "aur" || sep == "and also"
        val isComma = sep.startsWith(",") || sep == ";" || sep == "."
        if (inPayload(clause)) {
            // Inside text-to-type: only split for a clear new action.
            if (strongReturn.containsMatchIn(after)) return true
            if (isAndOnly || isComma) return false
            return startsAction(after)
        }
        if (isAndOnly) return startsAction(after)
        if (isComma) return startsAction(after) || after.split(' ').size <= 4
        return true // then / phir / after that
    }
}
