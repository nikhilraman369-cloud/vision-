package ai.nexus.core.nlu

/**
 * Rewrites Hindi/Hinglish word order (object first, verb last) into plain
 * English commands the rules already understand:
 *
 *   "insta pe reels dikha yaar"      → "show reels on insta"
 *   "pehla video chalao"             → "play first video"
 *   "editorial wala group kholo"     → "open editorial group"
 *   "wifi band kar do"               → "turn off wifi"
 *
 * Runs only when the rules didn't understand the original sentence, so it
 * can never break a command that already works. Instant, no model.
 */
object Canonicalizer {
    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    /** Verb phrase at the end of the clause → English verb (order matters: longer first). */
    private val verbs: List<Pair<Regex, String>> = listOf(
        "(?:open karo|open kar do|open kardo|open kariye|kholo|khol do|khol de|kholiye|kholna|khol|open)" to "open",
        "(?:play karo|play kar do|chalao|chala do|chala de|chalaiye|chala|bajao|baja do|baja de|laga do|lagado|lagao|laga de|sunao|suna do)" to "play",
        "(?:band kar do|band kardo|band karo|off kar do|off karo|bujha do|bujhao|band)" to "turn off",
        "(?:chalu kar do|chalu karo|on kar do|on karo|jala do|jalao|chalu)" to "turn on",
        "(?:click kar do|click karo|tap kar do|tap karo|press karo|daba do|dabao)" to "tap",
        "(?:type kar do|type karo|likh do|likh de|likho)" to "type",
        "(?:send kar do|send karo|bhej do|bhej de|bhejo)" to "send",
        "(?:search kar do|search karo|dhund do|dhundo|dhoondo|dhundho|khojo|dhundh)" to "search",
        "(?:dikha do|dikha de|dikhao|dikha|show karo)" to "show",
        "(?:like kar do|like kardo|like karo|pasand karo)" to "like",
        "(?:save kar lo|save kar do|save karo)" to "save",
        "(?:follow kar do|follow karo)" to "follow",
        "(?:subscribe kar do|subscribe karo)" to "subscribe",
        "(?:call kar do|call karo|call lagao|phone karo|phone lagao|phone kar do)" to "call",
        "(?:padh do|padh ke sunao|padho|read karo)" to "read",
        "(?:scroll kar do|scroll karo)" to "scroll",
        "(?:badha do|badhao|increase karo|tez karo)" to "increase",
        "(?:kam kar do|kam karo|ghatao|dheere karo)" to "decrease",
        "(?:pe jao|par jao|mein jao|me jao|pe chalo|par chalo|jao|chalo|jaiye)" to "go to",
        "(?:copy kar do|copy karo|copy kar lo)" to "copy",
        "(?:share kar do|share karo)" to "share",
        "(?:hata do|hatao|delete kar do|delete karo|mita do)" to "remove",
        "(?:ruk jao|roko|rok do)" to "pause",
    ).map { (p, v) -> rx("\\s+$p$") to v }

    private val words = mapOf(
        "agla" to "next", "agli" to "next", "agle" to "next", "pichla" to "previous", "pichli" to "previous", "pichle" to "previous",
        "pehla" to "first", "pehli" to "first", "pahla" to "first", "pehle" to "first", "dusra" to "second", "doosra" to "second",
        "dusri" to "second", "doosri" to "second", "teesra" to "third", "teesri" to "third", "aakhri" to "last", "akhri" to "last",
        "gaana" to "song", "gana" to "song", "gaane" to "songs", "neeche" to "down", "niche" to "down", "upar" to "up",
        "awaaz" to "volume", "awaz" to "volume", "roshni" to "brightness", "photo" to "photo", "tasveer" to "photo",
        "insta" to "instagram", "yt" to "youtube", "whatsapp" to "whatsapp", "msg" to "message", "sandesh" to "message",
    )
    private val particles = setOf(
        "ko", "ka", "ki", "ke", "wala", "wali", "wale", "vala", "vali", "waala", "waali", "zara", "jara", "yaar", "yar",
        "na", "ab", "abhi", "thoda", "woh", "wo", "yeh", "ye", "bhai", "please", "pls", "jaldi", "mera", "meri", "mere",
        "apna", "apni", "ek", "sa", "si", "se", "to", "toh",
    )
    private val places = rx("^(.+?)\\s+(?:pe|par|mein|me|main)\\s+(.+)$")

    /** Returns an English rewrite, or null if the sentence doesn't look like Hinglish word order. */
    fun toEnglish(clause: String): String? {
        var c = clause.trim().trimEnd('.', '!', '?')
        var verb: String? = null
        for ((r, v) in verbs) {
            val m = r.find(c) ?: continue
            verb = v; c = c.substring(0, m.range.first).trim(); break
        }
        val tokens = c.split(Regex("\\s+")).filter { it.isNotBlank() }
            .map { words[it.lowercase()] ?: it }
            .filterNot { it.lowercase() in particles }
        if (tokens.isEmpty()) return verb?.let { if (it == "pause") "pause" else null }
        var obj = tokens.joinToString(" ")
        var where: String? = null
        places.matchEntire(obj)?.let { m ->
            // "youtube pe filmmaking" → object "filmmaking", place "youtube"
            where = m.groupValues[1]; obj = m.groupValues[2]
        }
        val v = verb ?: return null
        val out = when (v) {
            "go to" -> "go to $obj"
            "read" -> "read $obj"
            "increase", "decrease" -> "$v $obj"
            else -> "$v $obj"
        }
        return (if (where != null) "$out on $where" else out).replace(Regex("\\s+"), " ").trim()
    }
}
