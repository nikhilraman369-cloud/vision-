package ai.nexus.core.nlu

import ai.nexus.core.device.SystemSetting

/**
 * Category A: the fixed tasks NEXUS must always get right. These run before
 * every other rule, so a known task is never misread by a general rule.
 *
 *  - WhatsApp messages, chats and calls (voice / video, people and groups)
 *  - Ask ChatGPT / Claude / Gemini
 *  - Google search in the browser
 *  - Timers, alarms and reminders in English and Hindi
 *  - Camera
 *
 * Everything here is pinned by core/src/test/resources/category_a_corpus.txt.
 */
internal class FixedTasks(
    private val isKnownApp: (String) -> Boolean,
    private val knownContact: (String) -> Boolean,
) {
    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    // ------------------------------------------------------------ AI apps

    private val AI = "(chat ?gpt|chat ?gbt|chatgtp|chad ?gpt|gpt|claude|claud|gemini|jemini|gemni|bard)"
    private fun aiApp(w: String): String = when {
        w.lowercase().replace(" ", "").let { it.contains("gpt") || it.contains("gbt") || it.contains("gtp") } -> "chatgpt"
        w.lowercase().startsWith("cla") -> "claude"
        else -> "gemini"
    }
    private val HI_PREP = "(?:se|ko|pe|par|pr|mein|me|main|mai|in|on)"
    private val HI_ASK = "(?:pucho|poocho|puchho|poochho|puch|pooch|puchiye|bolo|bol|likho|likh|type karo|type kar|search karo|search kar)"

    /** Whole-utterance: "ask ChatGPT …" keeps its whole prompt, even with "and" or times inside it. */
    fun askAi(t: String): Command.AskAi? {
        rx("^(?:start |open )?(?:a )?(?:new|naya|nayi) chat(?: kholo| karo)?(?: in| on| mein| me| pe| par)? $AI$|^$AI (?:mein|me|pe|par|in|on) (?:a )?(?:new|naya|nayi) chat(?: kholo| karo| open karo| start karo| shuru karo)?$").matchEntire(t)?.let { m ->
            return Command.AskAi(aiApp(m.groupValues[1].ifBlank { m.groupValues[2] }), null)
        }
        val patterns = listOf(
            // open chatgpt and ask how to make tea
            rx("^(?:open|launch|go to|kholo) $AI(?: app)?(?: and|,| then| aur)? (?:ask|tell|type|write|search|pucho|poocho|puchho|likho)(?: it| him| her| them)?(?: to| that| about| for| ki)?[,:]? (.+)$"),
            // ask chatgpt what is …, tell claude to …
            rx("^(?:ask|tell|query|message) $AI(?: app)?(?: to| that| about| for| ki)?[,:]? (.+)$"),
            // chatgpt se pucho …, gemini ko bolo …, chatgpt pe likho …
            rx("^$AI $HI_PREP $HI_ASK(?: ki| ke)?[,:]? (.+)$"),
            // on chatgpt, ask …
            rx("^(?:on|in|using) $AI,? (?:ask|type|write|search)(?: for)? (.+)$"),
        )
        for (p in patterns) p.matchEntire(t)?.let { m -> clean(m.groupValues[2])?.let { return Command.AskAi(aiApp(m.groupValues[1]), it) } }
        // chatgpt se … pucho
        rx("^$AI $HI_PREP (.+?) $HI_ASK$").matchEntire(t)?.let { m -> clean(m.groupValues[2])?.let { return Command.AskAi(aiApp(m.groupValues[1]), it) } }
        // search how to cook pasta on chatgpt
        rx("^(?:ask|search|type|write|search for) (.+?) (?:on|in|to|with|using) $AI$").matchEntire(t)?.let { m -> clean(m.groupValues[1])?.let { return Command.AskAi(aiApp(m.groupValues[2]), it) } }
        return null
    }

    // ------------------------------------------------------------ clause rules

    fun clause(c: String): List<Command>? =
        waCall(c) ?: waSection(c) ?: draftTo(c) ?: groupMessage(c) ?: messageWithText(c) ?: webSearch(c) ?: camera(c) ?: reminder(c) ?: timer(c) ?: alarm(c) ?: flashlight(c) ?: volume(c) ?: music(c)

    private val WA = "(?:whats ?app|watsapp|whatsap|wa)"

    /** "whatsapp call rahul", "video call mom", "rahul ko whatsapp pe call karo", "call college group on whatsapp". */
    private fun waCall(c0: String): List<Command>? {
        if (rx("^(?:end|cut|hang up|disconnect|drop|stop)\\b").containsMatchIn(c0) || rx("\\b(?:kaato|kaat do|band karo|cut karo|calls|call log|call history)\\b").containsMatchIn(c0)) return null
        if (!rx("\\bcall\\b").containsMatchIn(c0)) return null
        val hasWa = rx("\\b$WA\\b").containsMatchIn(c0)
        val video = rx("\\bvideo\\s+call\\b").containsMatchIn(c0)
        val voice = rx("\\b(?:voice|audio)\\s+call\\b").containsMatchIn(c0)
        if (!hasWa && !video && !voice) return null
        var t = c0
            .replace(rx("\\s+(?:on|via|through|using|in|from|over) $WA\\b"), " ")
            .replace(rx("\\b$WA (?:pe|par|pr|se|mein|me|ke through)\\s+"), " ")
            .replace(rx("\\b$WA\\s+"), " ")
            .replace(rx("\\b(?:video|voice|audio)\\s+call\\b"), "call")
            .replace(Regex("\\s+"), " ").trim()
        t = t.replace(rx("\\s+(?:now|please|abhi|jaldi|right now)$"), "")
        val who = rx("^(?:make|start|place|do|give|put)? ?(?:a |an )?call (?:to |with )?(.+)$").matchEntire(t)?.groupValues?.get(1)
            ?: rx("^(.+?) ko call(?: (?:karo|kar do|kar|kro|krdo|lagao|laga do|lgao|milao|mila do|karna|kijiye|karein))?$").matchEntire(t)?.groupValues?.get(1)
            ?: return null
        val name = stripArticles(who).trim().takeIf { it.isNotBlank() && !isKnownApp(it) } ?: return null
        val number = name.replace(" ", "").takeIf { rx("^\\+?\\d{6,15}$").matches(it) }
        return listOf(Command.Call(if (number == null) contactRef(name) else null, number, app = "whatsapp", video = video))
    }

    private fun waSection(c: String): List<Command>? {
        rx("^$WA (?:ka |ke |ki |pe |par |mein |me )?(status|updates|calls|chats|communities) (?:dikhao|kholo|open karo|dekho|khol do|batao)$|^(?:open|show|go to)(?: my)? $WA (status|updates|calls|chats|communities)$").matchEntire(c)?.let { m ->
            val sec = when (m.groupValues[1].ifBlank { m.groupValues[2] }.lowercase()) { "calls" -> Section.CALLS; "chats" -> Section.CHATS; else -> Section.STATUS }
            return listOf(Command.GoTo(sec, "whatsapp"))
        }
        return null
    }

    /** "type hello to rahul but don't send" */
    private fun draftTo(c: String): List<Command>? {
        rx("^(?:type|write|draft) (.+) (?:to|for) (\\S+(?: \\S+){0,2}?),? (?:but )?(?:don'?t|do not|mat) (?:send|bhejna)(?: it)?$").matchEntire(c)?.let { m ->
            return listOf(Command.SendMessage(contactRef(m.groupValues[2]), clean(m.groupValues[1]), null, send = false))
        }
        rx("^(.+?) ko (?:message|msg|text|whatsapp) (?:karo|kar do|kar|kro|bhejo|bhej do)$").matchEntire(c)?.let { m ->
            return listOf(Command.SendMessage(contactRef(m.groupValues[1]), null, null))
        }
        return null
    }

    /** Positions ("top group", "pehla gaana") are picks from the screen, not names. */
    private fun isPosition(s: String) = s.split(' ').any { w -> Numbers.ordinal(w) != null || w.lowercase() in setOf("top", "upar", "upper", "sabse", "wala", "wali", "wale", "neeche", "bottom", "next", "agla", "agli", "pichla", "pichli", "previous") }

    /** Group names are never phone contacts: "college group mein bhejo …", "message family group good morning". */
    private fun groupMessage(c: String): List<Command>? {
        if (isPosition(c.substringBefore(" group"))) return null
        val g = "(.+? group)"
        rx("^$g (?:mein|me|main|mai|ko|pe|par) (?:bhejo|bhej do|send karo|send kar do|message karo|likho|bolo|bol do)(?: ki| ke| that)? (.+)$").matchEntire(c)?.let { m ->
            return listOf(Command.SendMessage(stripArticles(m.groupValues[1]), clean(m.groupValues[2]), null))
        }
        rx("^$g (?:mein|me|main|mai|ko|pe|par) (.+?) (?:bhejo|bhej do|send karo|send kar do|likh do|likho|bolo|bol do)$").matchEntire(c)?.let { m ->
            return listOf(Command.SendMessage(stripArticles(m.groupValues[1]), clean(m.groupValues[2]), null))
        }
        rx("^(?:message|msg|text|whatsapp) (?:the |my |our )?(.+? group) (?!saying\\b|that\\b)(.+)$").matchEntire(c)?.let { m ->
            return listOf(Command.SendMessage(m.groupValues[1].trim(), clean(m.groupValues[2]), null))
        }
        rx("^(?:open|show|go to)(?: the| my| our)? (.+? group)(?: chat)?$|^(.+? group)(?: ki| ka)? (?:chat )?(?:kholo|open karo|dikhao)$").matchEntire(c)?.let { m ->
            return listOf(Command.OpenChat(m.groupValues[1].ifBlank { m.groupValues[2] }.trim()))
        }
        return null
    }

    /** "message rahul hi kaise ho", "tell rahul i'll be late": split the known contact from the text. */
    private fun messageWithText(c: String): List<Command>? {
        val m = rx("^(?:message|msg|text|whatsapp|ping|tell) (.+)$").matchEntire(c) ?: return null
        val rest = m.groupValues[1].trim()
        if (rx("^(?:me|him|her|them|it|that|this)\\b").containsMatchIn(rest)) return null
        if (rx("\\b(?:saying|that says|that|ki|which says|to say)\\b|:").containsMatchIn(rest)) return null  // the general rules handle these
        val words = rest.split(' ')
        for (n in minOf(3, words.size - 1) downTo 1) {
            val name = words.take(n).joinToString(" ")
            if (knownContact(name)) return listOf(Command.SendMessage(name, words.drop(n).joinToString(" "), null))
        }
        return null
    }

    private val WEB = "(?:google|chrome|browser|the web|web|internet|net)"

    /** Google search, opened in the browser. */
    private fun webSearch(c: String): List<Command>? {
        fun web(q: String?): List<Command>? = clean(q)?.takeIf { !rx("^(?:kholo|open|open karo|khol do|app)$").matches(it) }?.let { listOf(Command.Search(it, "web")) }
        rx("^(?:search|look up|lookup|find|google|search for)(?: for)? (.+?) (?:on|in|using|with|from) $WEB$").matchEntire(c)?.let { return web(it.groupValues[1]) }
        rx("^(?:search|look up|find|look)(?: on| in| the| up on)? $WEB(?: for| about)? (.+)$").matchEntire(c)?.let { return web(it.groupValues[1]) }
        rx("^$WEB (?:pe|par|pr|mein|me) (?:search karo|search kar do|search kar|search|dhundho|dhoondo|dekho|check karo)(?: ki)? (.+)$").matchEntire(c)?.let { return web(it.groupValues[1]) }
        rx("^$WEB (?:pe|par|pr|mein|me) (.+?) (?:search karo|search kar do|search kar|search|dhundho|dhoondo|dekho|check karo)$").matchEntire(c)?.let { return web(it.groupValues[1]) }
        rx("^(.+?) (?:google karo|google kar|google kar do|google kr|google pe search karo|google pe dekho|google par dekho|google pe search kar|search karo google pe)$").matchEntire(c)?.let { return web(it.groupValues[1]) }
        rx("^google(?: (?:kar|karo|kar do|kr|krdo|search|it))? (.+)$").matchEntire(c)?.let { m ->
            val q = m.groupValues[1]
            if (isKnownApp("google $q") || isKnownApp(q) || rx("^(?:pe|par|mein|me|app|kholo|open|khol)\\b").containsMatchIn(q)) return null
            return web(q)
        }
        return null
    }

    private fun camera(c: String): List<Command>? {
        rx("^(?:open|launch|start|chalu karo|kholo)(?: the| my)? (front |selfie |back |rear |main )?camera(?: app)?$|^(front |selfie |back |rear )?camera (?:kholo|khol do|open karo|open kar do|open kar|chalu karo|on karo|start karo|kholna)$").matchEntire(c)?.let { m ->
            val w = (m.groupValues[1] + m.groupValues[2]).trim().lowercase()
            return listOf(Command.TakePhoto(front = w == "front" || w == "selfie"))
        }
        if (rx("^(?:ek )?selfie (?:lo|le lo|khicho|kheencho|lena|click karo|le)$|^(?:click|take) (?:a |my )?selfie$").matches(c)) return listOf(Command.TakePhoto(front = true))
        if (rx("^(?:ek )?(?:photo|pic|picture|tasveer) (?:lo|le lo|khicho|kheencho|click karo)$").matches(c)) return listOf(Command.TakePhoto())
        if (rx("^(?:video record karo|video record kar do|video banao|video shoot karo|start (?:a )?video recording|record video|shoot a video)$").matches(c)) return listOf(Command.TakePhoto(video = true))
        return null
    }

    // ------------------------------------------------------------ time

    private val PERIOD = "subah|savere|sawere|morning|dopahar|dopehar|afternoon|shaam|sham|evening|raat|night|tonight"
    private val MOD = "saade|sade|saadhe|sava|sawa|paune|pone"
    private val TIME_RX = rx("^(?:(?:kal|aaj|tomorrow|today)\\s+)?(?:($PERIOD)\\s+)?(?:ko\\s+)?(?:($MOD)\\s+)?(\\d{1,2}|[a-z]+)(?:[:.](\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?(?:\\s*(?:baje|bje|bajkar|o'?clock|bajke))?(?:\\s+($PERIOD|ko|ka|ki|ke|pe|par))?(?:\\s+(?:kal|tomorrow))?$")

    data class Clock(val hour: Int, val minute: Int, val exact: Boolean)

    /** "subah 6 baje", "7:30 pm", "saade 6", "raat 10 baje", "5" → a clock time. */
    fun clockOf(s: String): Clock? {
        val m = TIME_RX.matchEntire(s.trim()) ?: return null
        var h = Numbers.parse(m.groupValues[3]) ?: return null
        var min = m.groupValues[4].toIntOrNull() ?: 0
        when (m.groupValues[2].lowercase()) {
            "saade", "sade", "saadhe" -> min = 30
            "sava", "sawa" -> min = 15
            "paune", "pone" -> { h -= 1; min = 45; if (h == 0) h = 12 }
        }
        val ampm = m.groupValues[5].lowercase().replace(".", "")
        val period = (m.groupValues[1].ifBlank { m.groupValues[6] }).lowercase().takeIf { it.isNotBlank() && it !in setOf("ko", "ka", "ki", "ke", "pe", "par") }
        var exact = false
        if (ampm.isNotEmpty()) {
            exact = true
            if (ampm == "pm" && h < 12) h += 12
            if (ampm == "am" && h == 12) h = 0
        } else if (period != null) {
            exact = true
            when (period) {
                "subah", "savere", "sawere", "morning" -> if (h == 12) h = 0
                "dopahar", "dopehar", "afternoon" -> if (h in 1..11) h += 12
                "shaam", "sham", "evening" -> if (h < 12) h += 12
                "raat", "night", "tonight" -> if (h == 12) h = 0 else if (h in 5..11) h += 12
            }
        }
        if (h > 12 || h == 0) exact = true
        return if (h in 0..23 && min in 0..59) Clock(h, min, exact) else null
    }

    private val UNIT = "(seconds?|secs?|sec|second|minutes?|mins?|min|minat|hours?|hrs?|hr|ghante|ghanta|ghante)"
    private val N = "(\\d+(?:\\.\\d+)?|an?|one|two|three|four|five|ten|fifteen|twenty|thirty|ek|do|teen|char|chaar|paanch|panch|das|aadha|adha)"
    private fun seconds(n: String, unit: String): Int? {
        val v = when (n.lowercase()) { "a", "an" -> 1.0; "aadha", "adha" -> 0.5; "fifteen" -> 15.0; "twenty" -> 20.0; "thirty" -> 30.0; else -> n.toDoubleOrNull() ?: Numbers.parse(n)?.toDouble() } ?: return null
        val u = unit.lowercase()
        val mult = when { u.startsWith("h") || u.startsWith("ghant") -> 3600; u.startsWith("s") -> 1; else -> 60 }
        return (v * mult).toInt().takeIf { it > 0 }
    }

    private val TIMER_VERB = "(?:lagao|laga do|laga|lga do|lgao|set karo|set kar do|set kar|start karo|start kar do|chalu karo|rakho|rakh do|kar do|karo)"

    private fun timer(c: String): List<Command>? {
        rx("^$N $UNIT (?:ka|ki|ke) timer(?: $TIMER_VERB)?$").matchEntire(c)?.let { return seconds(it.groupValues[1], it.groupValues[2])?.let { s -> listOf(Command.SetTimer(s)) } }
        rx("^timer $TIMER_VERB $N $UNIT(?: ka| ki| ke)?(?: (?:for|called) (.+))?$").matchEntire(c)?.let { return seconds(it.groupValues[1], it.groupValues[2])?.let { s -> listOf(Command.SetTimer(s, clean(it.groupValues[3]))) } }
        rx("^(?:set|start|put)(?: an?| the)? timer(?: for| of)? $N $UNIT$").matchEntire(c)?.let { return seconds(it.groupValues[1], it.groupValues[2])?.let { s -> listOf(Command.SetTimer(s)) } }
        return null
    }

    private val ALARM_VERB = "(?:laga do|lagao|laga|lga do|lgao|set karo|set kar do|set kar|rakh do|rakho|lagana|laga dena|kar do)"

    private fun alarm(c: String): List<Command>? {
        fun at(time: String, label: String? = null): List<Command>? = clockOf(time)?.let { listOf(Command.SetAlarm(it.hour, it.minute, label, it.exact)) }
        rx("^alarm $ALARM_VERB (.+)$").matchEntire(c)?.let { m -> at(m.groupValues[1].replace(rx("\\s+(?:ka|ki|ke|pe|par)$"), ""))?.let { return it } }
        rx("^(.+?) (?:ka |ki |ke |pe |par |ko )?alarm(?: $ALARM_VERB)?$").matchEntire(c)?.let { m -> at(m.groupValues[1])?.let { return it } }
        rx("^(?:set |put |create )?(?:an? |the )?alarm (?:for |at )(.+)$").matchEntire(c)?.let { m -> at(m.groupValues[1])?.let { return it } }
        rx("^(?:mujhe )?(.+?) (?:baje )?(?:utha dena|uthana|utha do|jaga dena|jagana)$").matchEntire(c)?.let { m -> at(m.groupValues[1], "Wake up")?.let { return it } }
        return null
    }

    private val YAAD = "(?:yaad (?:dilana|dila dena|dilao|dila do|karwana|karana|dilaana))"

    /** Hindi reminders: "10 minute mein yaad dilana pani peena", "shaam 6 baje yaad dilana gym jana hai". */
    private fun reminder(c: String): List<Command>? {
        val t = c.replace(rx("^mujhe\\s+"), "").replace(rx("\\s+mujhe\\s+"), " ")
        rx("^$N $UNIT (?:mein|me|main|baad|bad|ke baad) $YAAD(?: ki| ke)? (.+)$").matchEntire(t)?.let { m ->
            return seconds(m.groupValues[1], m.groupValues[2])?.let { listOf(Command.SetTimer(it, clean(m.groupValues[3]))) }
        }
        rx("^$YAAD $N $UNIT (?:mein|me|main|baad|bad|ke baad)(?: ki| ke)? (.+)$").matchEntire(t)?.let { m ->
            return seconds(m.groupValues[1], m.groupValues[2])?.let { listOf(Command.SetTimer(it, clean(m.groupValues[3]))) }
        }
        rx("^(.+?) $YAAD(?: ki| ke)? (.+)$").matchEntire(t)?.let { m ->
            clockOf(m.groupValues[1].replace(rx("\\s+(?:pe|par|ko)$"), ""))?.let { k -> return listOf(Command.SetAlarm(k.hour, k.minute, clean(m.groupValues[2]), k.exact)) }
        }
        rx("^$YAAD (.+)$").matchEntire(t)?.let { m ->
            val words = m.groupValues[1].split(' ')
            for (n in minOf(4, words.size - 1) downTo 1) {
                val time = words.take(n).joinToString(" ").replace(rx("\\s+(?:pe|par|ko)$"), "")
                val k = clockOf(time) ?: continue
                val label = words.drop(n).joinToString(" ").replace(rx("^(?:pe|par|ko|ki|ke)\\s+"), "")
                return listOf(Command.SetAlarm(k.hour, k.minute, clean(label), k.exact))
            }
        }
        return null
    }

    // ------------------------------------------------------------ torch, volume, music

    private fun flashlight(c: String): List<Command>? {
        val torch = "(?:torch|flashlight|flash light|flash)"
        rx("^$torch (?:jalao|jala do|chalu karo|chalu kar do|on karo|on kar do|on kar|start karo|kholo)$|^(?:jalao|chalu karo) $torch$").matchEntire(c)?.let { return listOf(Command.SetToggle(SystemSetting.FLASHLIGHT, true)) }
        rx("^$torch (?:bujhao|bujha do|band karo|band kar do|band kar|off karo|off kar do|off kar|hatao|band)$").matchEntire(c)?.let { return listOf(Command.SetToggle(SystemSetting.FLASHLIGHT, false)) }
        return null
    }

    private fun volume(c: String): List<Command>? {
        val v = "(?:volume|awaaz|awaz|aawaz|sound)"
        rx("^$v (\\d{1,3})(?: percent| %)? (?:pe|par|par kar do|pe karo|pe kar do|kar do|karo|rakho|pe rakho)$").matchEntire(c)?.let { return listOf(Command.SetLevel(Level.VOLUME, it.groupValues[1].toInt().coerceIn(0, 100), null)) }
        if (rx("^$v (?:full|poora|puri|max|maximum|sabse tez)(?: (?:karo|kar do|kar))?$|^(?:max|maximum|full) $v$").matches(c)) return listOf(Command.SetLevel(Level.VOLUME, 100, null))
        if (rx("^$v (?:kam|kum|dheere|dheemi|neeche|low)(?: (?:karo|kar do|kar|kro))?$").matches(c)) return listOf(Command.SetLevel(Level.VOLUME, null, -15))
        if (rx("^$v (?:badhao|badha do|tez karo|tez kar do|zyada karo|upar karo|badhaao|bdhao)$").matches(c)) return listOf(Command.SetLevel(Level.VOLUME, null, 15))
        if (rx("^$v (?:band karo|band kar do|band|zero karo|mute karo|mute kar do)$").matches(c)) return listOf(Command.SetLevel(Level.VOLUME, 0, null))
        return null
    }

    private fun music(c: String): List<Command>? {
        val play = "(?:chalao|chala do|chalado|lagao|laga do|bajao|baja do|play karo|play kar do|play kar|sunao|suna do)"
        val yt = "(youtube music|yt music|youtube|yt)"
        rx("^$yt (?:pe|par|pr|mein|me) (.+?) $play$").matchEntire(c)?.let { return listOf(Command.PlayMedia(stripSong(it.groupValues[2]), appName(it.groupValues[1]))) }
        rx("^(.+?) $yt (?:pe|par|pr|mein|me) $play$").matchEntire(c)?.let { return listOf(Command.PlayMedia(stripSong(it.groupValues[1]), appName(it.groupValues[2]))) }
        rx("^$yt (?:pe|par|pr|mein|me) (.+?) (?:search karo|search kar do|search kar|dhundho|dhoondo)$|^$yt (?:pe|par|pr|mein|me) (?:search karo|search kar) (.+)$").matchEntire(c)?.let {
            val q = it.groupValues[2].ifBlank { it.groupValues[4] }; val app = it.groupValues[1].ifBlank { it.groupValues[3] }
            return listOf(Command.Search(q.trim(), appName(app)))
        }
        rx("^play (?:some|a few|kuch|any) (.+?)(?: on $yt)?$").matchEntire(c)?.let { return listOf(Command.PlayMedia(it.groupValues[1].trim(), it.groupValues[2].takeIf { a -> a.isNotBlank() }?.let(::appName))) }
        // "kesariya gaana chalao", "arijit singh ke gaane bajao", "kesariya bajao"
        rx("^(.+?) $play$").matchEntire(c)?.let { m ->
            val q = m.groupValues[1].trim()
            if (q.isBlank() || isKnownApp(q) || isPosition(q) || rx("\\b(?:video|videos|reel|reels|phone|call|ko)\\b").containsMatchIn(q) || rx("\\s(?:pe|par|pr|mein|me|on|in)\\s").containsMatchIn(" $q ") || rx("^(?:video|gaana|gana|song|music|it|isko|ise|ye|yeh|next|agla|pichla|previous|reel)$").matches(q) || rx("\\b(?:timer|alarm|torch|flash|volume|awaaz|video ko)\\b").containsMatchIn(q)) return null
            return listOf(Command.PlayMedia(stripSong(q), null))
        }
        return null
    }

    private fun appName(w: String) = when (w.lowercase()) { "yt", "youtube" -> "youtube"; else -> "youtube music" }
    private fun stripSong(q: String) = q.replace(rx("\\s+(?:gaana|gana|song|wala gaana|wala song)$"), "").trim()

    // ------------------------------------------------------------ helpers

    private fun clean(s: String?): String? = s?.trim()?.trim('"', '\'', ',', ' ')?.takeIf { it.isNotBlank() }
    private fun stripArticles(s: String): String = s.replace(rx("^(?:the|a|an|my|this|that|our)\\s+"), "").trim()
    private fun contactRef(s: String): String = when (s.lowercase().trim()) {
        "him", "her", "them", "usko", "use", "unko", "this person", "that person" -> IntentParser.SAME_CONTACT
        else -> s.trim()
    }
}
