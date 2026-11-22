package ai.nexus.core.nlu

import ai.nexus.core.device.Direction
import ai.nexus.core.device.GlobalAction
import ai.nexus.core.device.MediaKey
import ai.nexus.core.device.SettingsScreen
import ai.nexus.core.device.SystemSetting

/**
 * Fast, offline understanding of everyday phone requests in English, Hindi
 * and Hinglish. Anything it cannot break down confidently is marked
 * [Command.Agentic] (a phone task for the LLM agent) or [Command.Chat]
 * (conversation), so nothing is rejected just for being phrased unusually.
 *
 * @param isKnownApp tells the parser whether a name refers to an installed
 *   or well-known app, so "open Rahul" and "open Spotify" are told apart.
 */
class IntentParser(private val isKnownApp: (String) -> Boolean = { BuiltInApps.isKnown(it) }) {

    fun parse(utterance: String): ParsedRequest {
        val normalized = TextNormalizer.normalize(utterance)
        if (normalized.isBlank()) return ParsedRequest(utterance, normalized, emptyList())

        // Whole-utterance checks first.
        controlOnly(TextNormalizer.stripFillers(normalized))?.let {
            return ParsedRequest(utterance, normalized, listOf(ParsedStep(it, normalized, 0.95)))
        }

        // "when I say good night, turn off wifi and …" must not be split at "and".
        Regex("^(?:when|whenever|jab) (?:i|main|mai) (?:say|bolu+n?|kahu+n?) [\"']?(.+?)[\"']?[,]? (?:then )?(?:i mean|it means|that means|you should|to|toh|tab)?\\s*(.+)$", RegexOption.IGNORE_CASE)
            .matchEntire(TextNormalizer.stripFillers(normalized))?.let { m ->
                val trigger = m.groupValues[1].trim(); val action = m.groupValues[2].trim()
                if (trigger.split(' ').size <= 4) return ParsedRequest(utterance, normalized, listOf(ParsedStep(Command.Remember(trigger, action), normalized, 0.95)))
            }
        // "No, I meant Rahul Sharma" is one correction, not "no" + something else.
        Regex("^(?:no|nahi|nope)[,.!]?\\s+(?:i meant|i mean|i said|mera matlab(?: tha)?)\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(TextNormalizer.stripFillers(normalized))?.let { m ->
            return ParsedRequest(utterance, normalized, listOf(ParsedStep(Command.MeantInstead(m.groupValues[1].trim().trimEnd('.')), normalized, 0.95)))
        }
        // "done, call it morning" belongs together even with the comma.
        TEACH_STOP.matchEntire(TextNormalizer.stripFillers(normalized))?.let { m ->
            return ParsedRequest(utterance, normalized, listOf(ParsedStep(Command.TeachStop(clean(m.groupValues[1])), normalized, 0.95)))
        }

        // Open-ended / timed: "keep scrolling up", "next reel every 15 seconds", "at 9 pm message Rahul …".
        Timing.parse(TextNormalizer.stripFillers(normalized), ::isConcrete, ::isMessageText)?.let {
            return ParsedRequest(utterance, normalized, listOf(ParsedStep(it, normalized, 0.95)))
        }

        val clauses = CommandSplitter.split(
            normalized.replace(Regex("\\s+(?:kholke|khol ke|khol kar|kholkar|open karke|open kar ke|open krke)\\s+", RegexOption.IGNORE_CASE), " kholo phir "),
        )
        val steps = ArrayList<ParsedStep>()
        var appContext: String? = null
        for (clause in clauses) {
            // Don't trim words like "for me" off text the user wants typed.
            val c = if (PAYLOAD_START.containsMatchIn(clause)) TextNormalizer.stripFillers(clause, trailing = false) else TextNormalizer.stripFillers(clause)
            if (c.isBlank()) continue
            val cmds = parseClause(c, appContext)
            for (cmd in cmds) {
                val conf = when (cmd) {
                    is Command.Agentic -> 0.3
                    is Command.Chat -> 0.7
                    else -> 0.9
                }
                steps += ParsedStep(cmd, c, conf)
                if (cmd is Command.OpenApp) appContext = cmd.app
            }
        }

        // "Find the forecast and tell me whether I need an umbrella": a phone
        // action mixed with reasoning about its result → let the agent plan it.
        val hasPhoneAction = steps.any { it.command !is Command.Chat && it.command !is Command.Agentic }
        val hasChat = steps.any { it.command is Command.Chat }
        if (steps.size > 1 && hasPhoneAction && hasChat && REASONING.containsMatchIn(normalized)) {
            return ParsedRequest(utterance, normalized, listOf(ParsedStep(Command.Agentic(normalized), normalized, 0.4)))
        }
        return ParsedRequest(utterance, normalized, steps)
    }

    // ---------------------------------------------------------------- helpers

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private fun clean(s: String?): String? =
        s?.trim()?.trim('"', '\'', ',', '.', ' ')?.takeIf { it.isNotBlank() }

    private fun stripArticles(s: String): String =
        s.replace(rx("^(?:the|a|an|my|this|that|our)\\s+"), "").trim()

    // ---------------------------------------------------------------- control

    private val stopRx = rx("^(?:stop|stop it|stop that|stop everything|cancel|cancel it|cancel that|abort|never ?mind|forget it|ruko mat|bas|bas karo|band karo|rehne do|chhodo|leave it|quit that)$")
    private val waitRx = rx("^(?:wait|hold on|hold|one (?:sec|second|minute)|ek (?:second|minute|min)|ruko|ruk jao|thoda ruko|pause|pause it|pause that)$")
    private val resumeRx = rx("^(?:continue|resume|go on|carry on|keep going|proceed|go ahead|jari rakho|aage badho|chalu rakho|continue what you were doing|continue the task|carry on with it)$")
    private val wrongRx = rx("^(?:no|nope|wrong|that'?s wrong|that'?s not (?:what i meant|it|right)|not that|galat|galat hai|ye nahi|yeh nahi|nahi ye nahi)$")
    private val repeatRx = rx("^(?:again|do (?:it|that|the same(?: thing)?) again|repeat(?: that| it)?|same again|phir se(?: karo)?|dobara(?: karo)?|one more time|ek baar aur)$")
    private val undoRx = rx("^(?:undo(?: that| it)?|revert(?: that)?)$")

    private fun controlOnly(c: String): Command? = when {
        stopRx.matches(c) -> Command.ControlTask(Control.STOP)
        waitRx.matches(c) -> Command.ControlTask(Control.PAUSE)
        resumeRx.matches(c) -> Command.ControlTask(Control.RESUME)
        wrongRx.matches(c) -> Command.ControlTask(Control.WRONG)
        repeatRx.matches(c) -> Command.Repeat
        undoRx.matches(c) -> Command.ControlTask(Control.UNDO_LAST)
        else -> null
    }

    // ---------------------------------------------------------------- clauses

    /** True when [text] becomes real phone actions (not a question or something unknown). */
    fun isConcrete(text: String): Boolean = parse(text).steps.let { st -> st.isNotEmpty() && st.none { it.command is Command.Chat || it.command is Command.Agentic } }

    private fun isMessageText(text: String): Boolean = parse(text).steps.any { it.command is Command.SendMessage || it.command is Command.Write || it.command is Command.Email }

    fun parseClause(c0: String, appContext: String? = null, depth: Int = 0): List<Command> {
        val c = if (depth == 0) casual(c0) else c0
        controlOnly(c)?.let { return listOf(it) }

        // Corrections: "open the second result instead", "no, the other one"
        rx("^(?:no[,.!]?\\s+|not that[,.!]?\\s+|actually[,.!]?\\s+)?(.+?)\\s+instead$").matchEntire(c)?.let { m ->
            val inner = parseClause(m.groupValues[1], appContext).firstOrNull()
            return listOf(Command.Correction(inner))
        }
        rx("^(?:no|not that|nahi)[,.!]?\\s+(.+)$").matchEntire(c)?.let { m ->
            val inner = parseClause(m.groupValues[1], appContext).firstOrNull()
            if (inner != null && inner !is Command.Chat) return listOf(Command.Correction(inner))
        }
        rx("^(?:the other one|other one|use the other (?:one|contact|number)|dusra wala|doosra wala)$").matchEntire(c)?.let {
            return listOf(Command.Correction(Command.OpenResult(2)))
        }

        for (rule in rules) {
            val r = rule(c, appContext)
            if (r != null) return r
        }

        // Bare app name: "Instagram"
        if (isKnownApp(c)) return listOf(Command.OpenApp(c))
        // Hinglish word order: "insta pe reels dikha" → "show reels on instagram".
        if (depth == 0) Canonicalizer.toEnglish(c)?.takeIf { !it.equals(c, true) }?.let { en ->
            val r = parseClause(en, appContext, depth + 1)
            if (r.none { it is Command.Agentic || it is Command.Chat }) return r
        }
        // Casual verb endings: "torch on kr", "like kr do", "scroll kr" → without them.
        if (depth == 0) {
            val trimmed = CASUAL_TAIL.replace(c, "").trim()
            if (trimmed != c && trimmed.isNotBlank()) {
                val r = parseClause(trimmed, appContext, 1)
                if (r.isNotEmpty() && r.none { it is Command.Agentic || it is Command.Chat }) return r
                Canonicalizer.toEnglish(trimmed)?.let { en -> val r2 = parseClause(en, appContext, 1); if (r2.isNotEmpty() && r2.none { it is Command.Agentic || it is Command.Chat }) return r2 }
            }
        }
        return listOf(fallback(c))
    }

    private val CASUAL_TAIL = Regex("\\s+(?:kr do|kar do|krdo|kardo|kr de|kar de|kr dena|kar dena|kr|kar|karo|kro|karna|krna)$", RegexOption.IGNORE_CASE)
    private val CASUAL_WORDS = mapOf("vid" to "video", "vids" to "videos", "vdo" to "video", "plz" to "", "pls" to "", "plss" to "", "pic" to "photo", "pics" to "photos",
        "yt" to "youtube", "msg" to "message", "bhai" to "", "yaar" to "", "yr" to "", "bro" to "", "kr" to "kar", "krdo" to "kar do", "kro" to "karo")

    /** Casual speech → plain words ("plz open 2nd vid" → "open 2nd video"). Text meant to be typed is left alone. */
    private fun casual(c: String): String {
        val repairTyping = rx("^(?:type|write|likh|likho|search|send|comment|bhej) (?:kar|kr|karo|kro|kar do) ").containsMatchIn(c)
        if (PAYLOAD_START.containsMatchIn(c) && !repairTyping) return c
        var t = " " + c.trim() + " "
        if (!repairTyping) for ((a, b) in CASUAL_WORDS) t = t.replace(Regex(" ${Regex.escape(a)}(?= )", RegexOption.IGNORE_CASE), if (b.isEmpty()) "" else " $b")
        t = t.replace(Regex("\\s+"), " ").trim()
        // "type kar hello", "search kar cricket score" → "type hello", "search cricket score"
        t = rx("^(type|write|likh|likho|search|send|comment|bhej) (?:kar|kr|karo|kro|kar do) (.+)$").replace(t) { m -> "${m.groupValues[1]} ${m.groupValues[2]}" }
        return t.ifBlank { c }
    }

    private fun fallback(c: String): Command {
        val lower = c.lowercase()
        val looksLikeQuestion = QUESTION.containsMatchIn(lower) || lower.endsWith("?") || CONVERSATION.containsMatchIn(lower)
        val mentionsPhone = PHONE_WORDS.containsMatchIn(lower) || lower.split(' ').any { isKnownApp(it) }
        // NEXUS is a phone assistant: anything that isn't a question or small talk is treated as something to DO
        // (it is then matched against the screen), never as chat.
        return when {
            looksLikeQuestion && !mentionsPhone -> Command.Chat(c)
            else -> Command.Agentic(c)
        }
    }

    // ================================================================ screen grammar
    // Generic, pattern-based understanding of how people refer to things on screen and repeat actions,
    // in English, Hindi and Hinglish word order. These run before the specific rules.

    private val COUNT = "(\\d+|one|two|three|four|five|six|seven|eight|nine|ten|ek|do|teen|char|chaar|paanch|panch|chhe|che|saat|aath|nau|das)"
    private val COUNT_UNIT = "(?:times?|baar|bar|dafa|daffa|martaba)"

    /** "scroll 3 times", "teen baar scroll karo", "go back twice", "next 5 reels", "3 reels aage". */
    private fun counted(c: String, ctx: String?): List<Command>? {
        (rx("^(?:next|skip|agle|agla|agli|aage|move|go|jump)(?: ahead)? $COUNT (reels?|videos?|shorts?|posts?|stories|story|songs?|tracks?|gaane|gaana)(?: (?:aage|skip|forward|ahead))?(?: (?:karo|kar do|jao|badhao))?$").matchEntire(c)
            ?: rx("^$COUNT (reels?|videos?|shorts?|posts?|stories|story|songs?|tracks?|gaane|gaana) (?:aage|skip|next|forward|ahead|aage karo|skip karo|aage badhao|aage jao|aage kar do)$").matchEntire(c))
            ?.let { m ->
                val n = (Numbers.parse(m.groupValues[1]) ?: 1).coerceIn(1, 20)
                val songs = m.groupValues[2].lowercase().let { it.startsWith("song") || it.startsWith("track") || it.startsWith("gaan") }
                return List(n) { if (songs) Command.Media(MediaKey.NEXT) else Command.NextItem }
            }
        val trailing = rx("^(.+?)\\s+(?:$COUNT\\s*$COUNT_UNIT|(twice|thrice|once))$").matchEntire(c)
        val leading = rx("^(?:$COUNT\\s*$COUNT_UNIT|(twice|thrice))\\s+(.+)$").matchEntire(c)
        val (inner, nText, word) = when {
            trailing != null -> Triple(trailing.groupValues[1], trailing.groupValues[2], trailing.groupValues[3])
            leading != null -> Triple(leading.groupValues[3], leading.groupValues[1], leading.groupValues[2])
            else -> return null
        }
        val n = when (word.lowercase()) { "twice" -> 2; "thrice" -> 3; "once" -> 1; else -> Numbers.parse(nText) ?: return null }.coerceIn(1, 20)
        val cmds = parseClause(inner.trim(), ctx, 1)
        if (cmds.isEmpty() || cmds.any { it is Command.Chat || it is Command.Agentic || it is Command.SendMessage || it is Command.Call || it is Command.Email || it is Command.ShareTo || it is Command.Write }) return null
        if (cmds.size == 1) when (val one = cmds[0]) {
            is Command.Scroll -> return listOf(one.copy(times = n))
            is Command.Navigate -> return listOf(one.copy(times = n))
            else -> {}
        }
        return List(n) { cmds }.flatten().take(40)
    }

    private val REF_VERB = setOf(
        "open", "play", "tap", "click", "press", "select", "choose", "watch", "pick", "show", "view", "start", "enter", "go", "to", "into", "on",
        "kholo", "khol", "chalao", "chala", "dikhao", "dikha", "bajao", "lagao", "karo", "kar", "de", "dena", "jao", "chalo", "please", "pls", "me",
        "can", "you", "could", "just", "now", "check", "see", "read", "up", "for", "i", "want", "wanna", "let", "lets", "let's", "us", "kindly", "quickly",
    )
    private val REF_FILLER = setOf("the", "a", "an", "which", "that", "is", "at", "of", "from", "in", "list", "wala", "wali", "wale", "vala", "vali", "vale",
        "waala", "waali", "waale", "ko", "pe", "par", "mein", "se", "sabse", "here", "screen", "this", "page", "my", "one", "ones", "number", "no", "no.", "jo", "hai", "there")
    private val REF_KIND = mapOf(
        "video" to "video", "videos" to "video", "short" to "short", "shorts" to "short", "reel" to "reel", "reels" to "reel",
        "song" to "song", "songs" to "song", "gaana" to "song", "gana" to "song", "gaane" to "song", "track" to "song", "tracks" to "song",
        "contact" to "contact", "contacts" to "contact", "chat" to "chat", "chats" to "chat", "conversation" to "chat", "group" to "chat", "groups" to "chat",
        "message" to "message", "messages" to "message", "email" to "email", "emails" to "email", "mail" to "email", "mails" to "email",
        "photo" to "photo", "photos" to "photo", "picture" to "photo", "pictures" to "photo", "pic" to "photo", "pics" to "photo", "image" to "photo", "images" to "photo",
        "post" to "post", "posts" to "post", "notification" to "notification", "notifications" to "notification", "story" to "story", "stories" to "story",
        "person" to "contact", "people" to "contact", "name" to "contact",
        "channel" to "channel", "account" to "account", "profile" to "profile", "playlist" to "playlist",
        "result" to "", "results" to "", "item" to "", "items" to "", "option" to "", "options" to "", "entry" to "", "row" to "", "link" to "", "links" to "",
    )

    /**
     * "play the second video", "click on the sixth contact", "open the group at the top", "sabse upar wala chat kholo",
     * "second last video", "video number 3", "fifth wala kholo" → OpenResult(n, kind). Only fires when every word is
     * part of the reference (verb, position, kind, filler), so it can't swallow other commands.
     */
    private fun refs(c: String, ctx: String?): List<Command>? {
        var t = " " + c.lowercase().replace(Regex("[,.!?]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        var ordinal: Int? = null
        fun take(p: String, value: Int): Boolean { val r = Regex(p); return if (r.containsMatchIn(t)) { t = r.replace(t, " "); ordinal = ordinal ?: value; true } else false }
        // Multi-word positions first.
        take(" (?:second|2nd|doosra|dusra|doosri|dusri) (?:last|from (?:the )?(?:last|bottom|end)) | last se (?:doosra|dusra|doosri|dusri|second) ", -2)
        take(" (?:third|3rd|teesra|teesri) (?:last|from (?:the )?(?:last|bottom|end)) ", -3)
        var fromPosition = false
        var namedByWala = false
        if (take(" (?:upar|top|sabse upar|sab se upar) (?:wala|wali|vala|vali|wale|waala|waali) ", 1)) namedByWala = true
        if (take(" (?:neeche|niche|bottom|sabse neeche|sab se neeche|sabse niche|last|aakhri) (?:wala|wali|vala|vali|wale|waala|waali) ", -1)) namedByWala = true
        if (take(" (?:(?:at|on|in) (?:the )?(?:very )?top|top ?most|sabse upar|sab se upar) ", 1)) fromPosition = true
        if (take(" (?:(?:at|on|in) (?:the )?(?:very )?bottom|bottom ?most|sabse neeche|sab se neeche|sabse niche) ", -1)) fromPosition = true
        Regex(" (?:number|no\\.?|#) ?(\\d+) ").find(t)?.let { m -> if (ordinal == null) { ordinal = m.groupValues[1].toInt(); t = t.replace(m.value, " ") } }
        var kind: String? = null
        var sawNoun = false
        var sawVerb = false
        var bareNumber: Int? = null
        for (w in t.trim().split(' ').filter { it.isNotBlank() }) {
            val ord = Numbers.ordinal(w)
            when {
                ord != null -> { if (ordinal == null) ordinal = ord else return null }
                w in setOf("latest", "newest", "recent", "upar", "top") -> { if (ordinal == null) { ordinal = 1; fromPosition = true } }
                w in setOf("bottom", "neeche", "niche") -> { if (ordinal == null) { ordinal = -1; fromPosition = true } }
                REF_KIND.containsKey(w) -> { if (kind == null) kind = REF_KIND[w]; sawNoun = true }
                w.toIntOrNull() != null -> { if (bareNumber != null) return null; bareNumber = w.toInt() }
                w in REF_VERB -> sawVerb = true
                w in REF_FILLER -> { if (w == "one" || w.startsWith("wa") || w.startsWith("va")) sawNoun = true }
                else -> return null
            }
        }
        if (ordinal == null && bareNumber != null && (sawNoun || sawVerb)) ordinal = bareNumber else if (bareNumber != null) return null
        val n = ordinal ?: return null
        if (n == 0 || !(sawNoun || sawVerb || namedByWala)) return null
        if (fromPosition && kind == null && !sawNoun && !namedByWala) return null   // "upar jao" = scroll, "click at the bottom" = screen area
        val k = kind?.takeIf { it.isNotEmpty() }
        if (k == "notification" && n == 1) return listOf(Command.OpenLatestNotification)
        return listOf(Command.OpenResult(n, k))
    }

    /** Hindi/Hinglish word orders and shapes that the English rules below would misread. */
    private fun hinglish(c: String, ctx: String?): List<Command>? {
        // "whatsapp kholo", "insta khol de", "camera chalu karo" → open the app (never a message to "kholo").
        rx("^(.+?)(?: app)? (?:kholo|khol do|khol de|kholiye|khol|kholna|open karo|open kar do|open kardo|chalu karo|chalu kar do|start karo|launch karo|pe jao|par jao)$").matchEntire(c)?.let { m ->
            val name = stripArticles(m.groupValues[1].trim())
            if (isKnownApp(name)) return listOf(Command.OpenApp(name))
        }
        // "settings pe click karo", "search bar par tap karo", "Rahul pe tap karo", "like button dabao"
        // "press back", "back button dabao" → the back key, not a button called "back"
        if (rx("^(?:press|hit|tap|click)(?: on)? (?:the )?back(?: button| key)?$|^back (?:button )?(?:dabao|daba do)$").matches(c)) return listOf(Command.Navigate(GlobalAction.BACK))
        (rx("^(?:the )?(.+?) (?:pe|par|per|ko) (?:click|tap|press|touch)(?: karo| kar do| kardo| kariye| kar)?$").matchEntire(c)
            ?: rx("^(?:the )?(.+?) (?:dabao|daba do|daba de)$").matchEntire(c))
            ?.let { m ->
                val label = m.groupValues[1].trim().removeSuffix(" button").removeSuffix(" icon").trim()
                if (label.isNotBlank()) return refs("open $label", ctx) ?: listOf(Command.Tap(label))
            }
        // "google pe weather dhundo", "youtube par cats search karo"
        rx("^(.+?) (?:pe|par|mein|me) (.+?) (?:search karo|search kar do|search kardo|search kar|search|dhundo|dhoondo|dhundho|dhundh do|dhoond do|khojo|dhundhna)$").matchEntire(c)?.let { m ->
            val app = m.groupValues[1].trim().let { if (it == "yt") "youtube" else it }
            if (isKnownApp(app) || app in setOf("google", "youtube", "insta", "instagram", "chrome")) return listOf(Command.Search(m.groupValues[2].trim(), app))
        }
        rx("^(.+?) (?:search karo|search kar do|search kardo|dhundo|dhoondo|dhundho|dhundh do|dhoond do|khojo)$").matchEntire(c)?.let { m ->
            return listOf(Command.Search(m.groupValues[1].trim()))
        }
        // "rahul ko bolo main aa raha hoon", "mom ko batao ki ghar aa raha hoon"
        rx("^(?:(whatsapp|insta|instagram|telegram|sms) (?:pe|par|mein|me|se) )?(.+?) ko (?:bolo|bol do|bol de|batao|bata do|bata de|keh do|kaho|message karo|message kar do|msg karo|msg kar do|text karo|likh do|likho|bhejo|bhej do)(?: ki| ke)? (.+)$").matchEntire(c)?.let { m ->
            val who = m.groupValues[2].trim()
            if (who.split(' ').size <= 3) return listOf(Command.SendMessage(who, m.groupValues[3].trim(), m.groupValues[1].takeIf { it.isNotBlank() }?.let { if (it.startsWith("insta")) "Instagram" else it.replaceFirstChar { ch -> ch.uppercase() } }))
        }
        // "whatsapp pe rahul ko call karo" / "whatsapp pe ..." → the rest, on that app
        rx("^(whatsapp|insta|instagram|telegram) (?:pe|par|mein|me|se) (.+)$").matchEntire(c)?.let { m ->
            val app = if (m.groupValues[1].startsWith("insta")) "Instagram" else m.groupValues[1].replaceFirstChar { it.uppercase() }
            val inner = parseClause(m.groupValues[2], app, 1)
            if (inner.size == 1) when (val one = inner[0]) {
                is Command.SendMessage -> return listOf(one.copy(app = one.app ?: app))
                is Command.OpenChat -> return listOf(one.copy(app = one.app ?: app))
                is Command.Search -> return listOf(one.copy(app = one.app ?: app))
                else -> {}
            }
        }
        // Scrolling in Hindi: "neeche jao", "thoda upar", "aur neeche", "sabse upar jao", "neeche karo"
        rx("^(?:sabse|sab se) (upar|uppar|neeche|niche)(?: (?:jao|chalo|karo|scroll karo|le jao))?$").matchEntire(c)?.let { m ->
            return listOf(Command.Scroll(if (m.groupValues[1].startsWith("u")) Direction.UP else Direction.DOWN, 10))
        }
        rx("^(?:(?:thoda|thodi|thode|sa|si|se|aur|or|zara|bas|ek baar|jara) )*(upar|uppar|neeche|niche)(?: (?:jao|chalo|karo|kar do|scroll karo|scroll|le jao|aao|karna))?$").matchEntire(c)?.let { m ->
            return listOf(Command.Scroll(if (m.groupValues[1].startsWith("u")) Direction.UP else Direction.DOWN))
        }
        rx("^(?:scroll|swipe) (?:(?:thoda|aur|zara) )*(upar|uppar|neeche|niche)(?: (?:karo|kar do|jao))?$").matchEntire(c)?.let { m ->
            return listOf(Command.Scroll(if (m.groupValues[1].startsWith("u")) Direction.UP else Direction.DOWN))
        }
        // "spotify pe tum hi ho chalao", "youtube par kesariya bajao" (app first, then the song)
        rx("^(spotify|youtube music|yt music|youtube|gaana|jiosaavn|wynk|insta|instagram) (?:pe|par|mein|me) (.+?) (?:chalao|chala do|bajao|baja do|play karo|play kar do|sunao|suna do|laga do|lagao)$").matchEntire(c)?.let { m ->
            val app = m.groupValues[1].let { if (it.startsWith("insta")) "Instagram" else it }
            return listOf(Command.PlayMedia(m.groupValues[2].trim(), app))
        }
        // "isko save", "reel like", "ye video like karo" → the reaction on what's on screen
        rx("^(?:(?:ye|yeh|is|isko|ise|iss|this|that|it) )?(?:(?:reel|video|post|short|photo|song|pic) )?(?:ko )?(like|unlike|dislike|save|unsave|follow|unfollow|subscribe|unsubscribe|share)(?: (?:karo|kar do|kar|kardo))?$").matchEntire(c)?.let { m ->
            if (c.trim().contains(' ')) {
                val r = parseClause(m.groupValues[1], ctx, 1)
                if (r.isNotEmpty() && r.none { it is Command.Agentic || it is Command.Chat }) return r
            }
        }
        // "hello likh", "hello likh do" → type it
        rx("^(.+?) (?:likh|likh do|likh de|likho)$").matchEntire(c)?.let { m -> return listOf(Command.Write(m.groupValues[1].trim(), false)) }
        // "awaaz kam", "volume zyada", "brightness full"
        rx("^(awaaz|awaz|aawaz|volume|sound|brightness|roshni) (kam|zyada|jyada|badha|badhao|ghatao|ghata|tez|dheemi|dheere|kam kar|kam karo|full|poori|puri|zero|band)$").matchEntire(c)?.let { m ->
            val lvl = if (m.groupValues[1].startsWith("b") || m.groupValues[1].startsWith("r")) Level.BRIGHTNESS else Level.VOLUME
            return listOf(when (m.groupValues[2]) {
                "full", "poori", "puri" -> Command.SetLevel(lvl, 100, null)
                "zero", "band" -> Command.SetLevel(lvl, 0, null)
                "zyada", "jyada", "badha", "badhao", "tez" -> Command.SetLevel(lvl, null, 20)
                else -> Command.SetLevel(lvl, null, -20)
            })
        }
        // "peeche jao" (Hindi for back)
        if (rx("^(?:peeche|piche|pichhe|peechhe) (?:jao|chalo|karo|aao|ja)$").matches(c)) return listOf(Command.Navigate(GlobalAction.BACK))
        // "10 second aage karo", "skip 10 seconds", "30 second peeche"
        rx("^(?:skip|aage|forward) (\\d+) ?(seconds?|secs?|second|minutes?|mins?|minute)(?: aage)?(?: (?:karo|kar do|jao))?$").matchEntire(c)
            ?.let { m -> val mult = if (m.groupValues[2].startsWith("m")) 60 else 1; return listOf(Command.Seek(m.groupValues[1].toInt() * mult, true)) }
        rx("^(\\d+) ?(seconds?|secs?|second|minutes?|mins?|minute) (aage|peeche|piche|pichhe|back|forward)(?: (?:karo|kar do|jao))?$").matchEntire(c)?.let { m ->
            val mult = if (m.groupValues[2].startsWith("m")) 60 else 1
            val sign = if (m.groupValues[3] in setOf("aage", "forward")) 1 else -1
            return listOf(Command.Seek(sign * m.groupValues[1].toInt() * mult, true))
        }
        // "reel aage karo", "video aage", "agla reel" handled elsewhere
        if (rx("^(?:reel|reels|video|short|shorts|post|story)? ?(?:aage|agla|agli|agle)(?: (?:karo|kar do|badhao|jao|wala|dikhao))?$").matches(c) && c.trim().contains(' ')) return listOf(Command.NextItem)
        // "share this youtube video to rahul" → drop the app word
        rx("^(share|send|forward) (this|that|the|ye|yeh) (?:youtube|yt|insta|instagram|whatsapp) (.+)$").matchEntire(c)?.let { m ->
            return parseClause("${m.groupValues[1]} ${m.groupValues[2]} ${m.groupValues[3]}", ctx, 1).takeIf { r -> r.none { it is Command.Agentic || it is Command.Chat } }
        }
        // "scroll down a lot" / "scroll a lot"
        rx("^(?:scroll|swipe)(?: (up|down))? (?:a lot|lots|bahut|bohot|zyada|much more|a long way)$").matchEntire(c)?.let { m ->
            return listOf(Command.Scroll(if (m.groupValues[1] == "up") Direction.UP else Direction.DOWN, 4))
        }
        // "double tap the screen", "double tap"
        if (rx("^double ?(?:tap|click)(?: on)?(?: the)?(?: screen| it| this)?$").matches(c)) return listOf(Command.TapRegion(1, 1, double = true))
        return null
    }

    private val rules: List<(String, String?) -> List<Command>?> = listOf(
        ::counted, ::refs, ::hinglish, ::life, ::extras, ::navigation, ::seek, ::scrolling, ::nextPrev, ::screenQueries, ::reactions, ::comments, ::submit,
        ::messaging, ::calls, ::toggles, ::levels, ::settings, ::notifications, ::files, ::media, ::results,
        ::sections, ::search, ::sendText, ::write, ::tap, ::misc, ::memory, ::url, ::openApp,
    )

    private fun navigation(c: String, ctx: String?): List<Command>? {
        rx("^(?:go |press )?back (twice|two times|three times|thrice|\\d times)$").matchEntire(c)?.let {
            val n = when (it.groupValues[1].lowercase()) { "twice", "two times" -> 2; "thrice", "three times" -> 3; else -> it.groupValues[1].filter { ch -> ch.isDigit() }.toIntOrNull() ?: 2 }
            return listOf(Command.Navigate(GlobalAction.BACK, n))
        }
        if (rx("^(?:clear|dismiss|remove|hatao) (?:all )?(?:the |my )?notifications?$|^notifications? (?:clear|hatao|saaf) (?:karo|kar do)$").matches(c)) return listOf(Command.ClearNotifications)
        if (rx("^open (?:the |that |this )?(?:latest|last|new|recent|top)? ?notification$|^(?:open|show) (?:it|that)$").matches(c)) return listOf(Command.OpenLatestNotification)
        if (rx("^(?:go |take me |navigate |move |jump )?(?:back|backwards?)(?: (?:to|there|a page|one page|to (?:the )?(?:previous|last) (?:page|screen)|to (?:the )?search results|to results))?$").matches(c) ||
            rx("^(?:return|come back)(?: to (?:the )?(?:previous|last) (?:page|screen)| to (?:the )?search results| to results| back| there)?$").matches(c) ||
            rx("^(?:previous|last) (?:page|screen)$").matches(c) ||
            rx("^(?:ab |abhi )?(?:pichhe|wapas|back)(?: (?:jao|chalo|jaiye|aao|ja|karo|le chalo|jaana))?$").matches(c)
        ) return listOf(Command.Navigate(GlobalAction.BACK))
        if (rx("^(?:go |take me |back )?(?:to )?(?:the )?home ?screen$|^go home$|^home (?:pe|par) jao$|^home screen (?:pe|par)? ?jao$|^minimi[sz]e(?: (?:it|this|the app))?$").matches(c))
            return listOf(Command.Navigate(GlobalAction.HOME))
        if (rx("^(?:open |show |go to )?(?:the )?(?:recent apps|recents|recent applications|app switcher|multitask(?:ing)?|overview)$").matches(c))
            return listOf(Command.Navigate(GlobalAction.RECENTS))
        if (rx("^(?:open|show|pull down|expand) (?:the )?(?:notification (?:panel|shade|bar|center)|notifications? (?:panel|shade)|status bar)$").matches(c))
            return listOf(Command.Navigate(GlobalAction.NOTIFICATIONS))
        if (rx("^(?:open|show|pull down) (?:the )?quick ?settings$").matches(c))
            return listOf(Command.Navigate(GlobalAction.QUICK_SETTINGS))
        if (rx("^(?:take (?:a )?)?screenshot(?: lo| le lo| karo)?$|^capture (?:the )?screen$").matches(c))
            return listOf(Command.Navigate(GlobalAction.SCREENSHOT))
        if (rx("^lock (?:the |my )?(?:phone|screen|device)$|^phone lock karo$").matches(c))
            return listOf(Command.Navigate(GlobalAction.LOCK_SCREEN))
        if (rx("^(?:switch|go back|return)(?: back)? to (?:the )?(?:previous|last|other) app$|^switch apps?$|^(?:previous|last) app$|^pichhla app$").matches(c))
            return listOf(Command.OpenApp(PREVIOUS_APP))
        if (rx("^close (?:this|the)? ?(?:dialog|popup|pop-up|menu|keyboard|ad|sheet|panel|overlay|comments)$|^dismiss(?: (?:it|this))?$|^keyboard (?:hatao|band karo)$").matches(c))
            return listOf(Command.CloseOverlay)
        return null
    }

    private fun seek(c: String, ctx: String?): List<Command>? {
        rx("^(?:skip|go|jump|seek|fast)? ?(forward|ahead|back|backward|backwards|rewind)(?: by)? (\\d+) ?(seconds?|secs?|s|minutes?|mins?)$").matchEntire(c)?.let { m ->
            val sign = if (m.groupValues[1].startsWith("back") || m.groupValues[1] == "rewind") -1 else 1
            val mult = if (m.groupValues[3].startsWith("m")) 60 else 1
            return listOf(Command.Seek(sign * m.groupValues[2].toInt() * mult, true))
        }
        rx("^rewind (\\d+) ?(seconds?|secs?|s)$").matchEntire(c)?.let { return listOf(Command.Seek(-it.groupValues[1].toInt(), true)) }
        return null
    }

    private fun scrolling(c: String, ctx: String?): List<Command>? {
        val m = rx("^(scroll|swipe)(?: (up|down|left|right|upward|downward|forward|back))?(?: (?:a little|a bit|once|more))?(?: (\\d+|two|three|four|five) times)?$").matchEntire(c)
            ?: rx("^(?:(neeche|niche|upar|uppar|left|right|daaye|baaye) )?(scroll|swipe) ?(karo|kar do)?$").matchEntire(c)?.let { mm ->
                val dir = when (mm.groupValues[1].lowercase()) {
                    "upar", "uppar" -> Direction.UP
                    "left", "baaye" -> Direction.LEFT
                    "right", "daaye" -> Direction.RIGHT
                    else -> Direction.DOWN
                }
                return listOf(Command.Scroll(dir))
            }
            ?: return when {
                rx("^scroll to (?:the )?(?:top|beginning|start)$").matches(c) -> listOf(Command.Scroll(Direction.UP, 10))
                rx("^scroll to (?:the )?(?:bottom|end)$").matches(c) -> listOf(Command.Scroll(Direction.DOWN, 10))
                else -> null
            }
        val verb = m.groupValues[1].lowercase()
        val word = m.groupValues[2].lowercase()
        val times = Numbers.parse(m.groupValues[3]) ?: 1
        // Content direction: "scroll down" and "swipe up" both reveal what's below.
        val dir = when (verb) {
            "scroll" -> when (word) { "up", "upward", "back" -> Direction.UP; "left" -> Direction.LEFT; "right" -> Direction.RIGHT; else -> Direction.DOWN }
            else -> when (word) { "down", "downward", "back" -> Direction.UP; "left", "forward" -> Direction.RIGHT; "right" -> Direction.LEFT; else -> Direction.DOWN }
        }
        return listOf(Command.Scroll(dir, times))
    }

    private val itemNoun = "(?:reel|reels|video|videos|short|shorts|post|posts|one|story|stories|clip|item|result|photo|picture|image|page|chat|email|mail|message)"

    private fun nextPrev(c: String, ctx: String?): List<Command>? {
        if (rx("^(?:next|skip|agla|agli|aglaa) (?:song|track|gaana|gana)$|^(?:play )?(?:the )?next (?:song|track)$|^skip (?:this )?(?:song|track)$|^gaana badlo$").matches(c))
            return listOf(Command.Media(MediaKey.NEXT))
        if (rx("^(?:previous|last|pichhla) (?:song|track|gaana)$|^(?:play )?(?:the )?previous (?:song|track)$").matches(c))
            return listOf(Command.Media(MediaKey.PREVIOUS))
        if (rx("^(?:(?:go|scroll|swipe|move|jump|skip) (?:to )?)?(?:the )?next(?: $itemNoun)?$").matches(c) ||
            rx("^(?:find|show|show me|get|play|give me)? ?(?:another|one more|the next)(?: one| $itemNoun)?$").matches(c) ||
            rx("^(?:find|show|show me|get|play) (?:me )?(?:another|one more|the next)(?: one| $itemNoun)?$").matches(c) ||
            rx("^skip(?: (?:this|it))?(?: $itemNoun)?$").matches(c) ||
            rx("^(?:agla|agli|aglaa|next)(?: $itemNoun)?(?: (?:dikhao|chalao|lagao|pe jao|kholo))?$").matches(c) ||
            rx("^aage(?: (?:jao|badho|karo))?$").matches(c)
        ) return listOf(Command.NextItem)
        if (rx("^(?:(?:go|scroll|swipe|move) (?:back )?(?:to )?)?(?:the )?(?:previous|last|prior)(?: $itemNoun)?$").matches(c) ||
            rx("^go back to (?:the )?(?:previous|last) $itemNoun$").matches(c) ||
            rx("^(?:pichhla|pichhli|pehle wala)(?: $itemNoun)?(?: (?:dikhao|chalao|pe jao|kholo))?$").matches(c)
        ) return listOf(Command.PreviousItem)
        return null
    }

    private fun screenQueries(c: String, ctx: String?): List<Command>? {
        if (rx("^(?:what'?s|what is|whats) (?:currently |now )?(?:on |in )?(?:my |the |this )?(?:screen|display|page)(?: right now)?$|^what am i (?:looking at|seeing)$|^describe (?:my |the |this )?(?:screen|page)$|^(?:screen|is screen) (?:pe|par|mein) kya hai$|^where am i$").matches(c))
            return listOf(Command.DescribeScreen)
        if (rx("^read (?:it |this |that )?(?:out )?(?:loud|aloud)$").matches(c)) return listOf(Command.ReadScreen)
        if (rx("^read (?:out |aloud )?(?:this|the|it|that|my)?(?: ?(?:page|screen|article|post|text|out|aloud|content|message|messages|comments|it|everything))?(?: (?:out|aloud|to me))?$").matches(c) ||
            rx("^(?:padho|padh ke sunao|ye padho|isko padho|read karo|sunao)$").matches(c))
            return listOf(Command.ReadScreen)
        rx("^what (?:does|will) (?:the |this |that )?(.+?) (?:button|icon|option|toggle|switch|link)? ?do$").matchEntire(c)?.let {
            return listOf(Command.ExplainElement(it.groupValues[1]))
        }
        rx("^(?:find|where is|where'?s|locate|show me) (?:the )?(.+?) (option|button|icon|tab|field|menu|link|setting|bar|box)$").matchEntire(c)?.let {
            return listOf(Command.FindOnScreen("${it.groupValues[1]} ${it.groupValues[2]}".trim()))
        }
        return null
    }

    private fun reactions(c: String, ctx: String?): List<Command>? {
        val obj = "(?: (?:it|this|that|this one|the|the current|is|isko|ise|ye|yeh)?(?: ?$itemNoun| channel| account| page| profile| person| user| creator)?)?(?: (?:karo|kar do|kardo|please))?"
        fun m(p: String) = rx("^$p$obj$").matches(c)
        return when {
            m("(?:unlike|remove (?:the |my )?like(?: from)?|undo (?:the )?like)") -> listOf(Command.React(Reaction.LIKE, false))
            m("(?:like|heart|love|double tap)") || rx("^(?:isko |ise |ye |is $itemNoun ko )?like (?:karo|kar do|kardo)$").matches(c) -> listOf(Command.React(Reaction.LIKE, true))
            m("(?:dislike|thumbs down)") -> listOf(Command.React(Reaction.DISLIKE, true))
            m("(?:unsave|remove (?:it )?from (?:saved|bookmarks|watch later)|unbookmark)") -> listOf(Command.React(Reaction.SAVE, false))
            m("(?:save|bookmark|add to watch later|save to watch later)") || rx("^(?:isko |ise |ye )?save (?:karo|kar do|kardo|kar lo)$").matches(c) -> listOf(Command.React(Reaction.SAVE, true))
            m("unsubscribe(?: (?:from|to))?") -> listOf(Command.React(Reaction.SUBSCRIBE, false))
            m("subscribe(?: (?:to|this))?") || rx("^(?:channel )?subscribe (?:karo|kar do)$").matches(c) -> listOf(Command.React(Reaction.SUBSCRIBE, true))
            m("unfollow") || rx("^(?:(?:him|her|them) )?unfollow (?:karo|kar do)$").matches(c) -> listOf(Command.React(Reaction.FOLLOW, false))
            m("follow(?: (?:him|her|them))?") || rx("^(?:isko |ise )?follow (?:karo|kar do)$").matches(c) -> listOf(Command.React(Reaction.FOLLOW, true))
            else -> null
        }
    }

    private fun comments(c: String, ctx: String?): List<Command>? {
        if (rx("^(?:open|show|show me|go to|check|see|view|display|load)(?: the| all)?(?: $itemNoun'?s?)? comments?(?: section)?(?: (?:of|on|for) (?:it|this|the $itemNoun))?$").matches(c) ||
            rx("^comments?(?: section)?(?: (?:kholo|dikhao|open karo|pe jao|kholo na))$").matches(c) ||
            rx("^comments$").matches(c))
            return listOf(Command.OpenComments)
        if (rx("^read (?:the |all )?comments$").matches(c)) return listOf(Command.OpenComments, Command.ReadScreen)
        return null
    }

    private fun submit(c: String, ctx: String?): List<Command>? {
        if (rx("^(?:send|post|submit|publish)(?: (?:it|this|that|the (?:comment|message|reply|email|mail)))?(?: now)?$|^(?:bhej do|bhejo|post karo|post kar do|send karo|send kar do)$").matches(c))
            return listOf(Command.Submit)
        return null
    }

    private val noSend = rx("[,\\s]*(?:but |and )?(?:don'?t|do not|dont) (?:send|post)(?: it)?(?: yet| now| right now)?$|[,\\s]*(?:as a draft|abhi mat bhejo|mat bhejna|bhejna mat|send mat karna)$")

    private fun messaging(c: String, ctx: String?): List<Command>? {
        if (rx("^(?:tap|click|press|touch|select|hit)\\b").containsMatchIn(c)) return null
        var s = c
        var send = true
        noSend.find(s)?.let { send = false; s = s.removeRange(it.range).trim() }
        if (rx("^(?:draft|prepare) ").containsMatchIn(s)) send = false
        val app = MSG_APPS.find(s)?.let { m ->
            s = s.removeRange(m.range).replace(Regex("\\s+"), " ").trim()
            m.groupValues[1].lowercase().let { if (it == "text" || it == "sms") "sms" else it }
        }
        val say = "(?:saying|that says|that|with|which says|telling (?:him|her|them)|to say|:)"
        val msgNoun = "(?:a |an )?(?:message|msg|text|sms|whatsapp|dm|note)"
        val patterns = listOf(
            // send a message to Rahul saying hello
            rx("^(?:send|write|type|draft|prepare|compose|drop) $msgNoun (?:to) (.+?) $say (.+)$") to intArrayOf(1, 2),
            // send Rahul a message saying hello
            rx("^(?:send|write|drop) (.+?) $msgNoun(?: $say)? (.+)$") to intArrayOf(1, 2),
            // message/text Rahul saying hello | message Rahul hello
            rx("^(?:message|msg|text|whatsapp|dm|ping|sms) (.+?) $say (.+)$") to intArrayOf(1, 2),
            // tell Rahul that I'm late
            rx("^tell (?!me\\b)(.+?) (?:that|ki) (.+)$") to intArrayOf(1, 2),
            // send hello to Rahul
            rx("^send (.+?) to (.+)$") to intArrayOf(2, 1),
            // reply saying ok / reply to Rahul saying ok
            rx("^reply(?: (?:to|back to) (.+?))?(?: $say| saying)? (.+)$") to intArrayOf(1, 2),
            // Rahul ko message bhejo ki main aa raha hoon
            rx("^(.+?) ko (?:message|msg|text|whatsapp|sms) (?:bhejo|bhej do|karo|kar do|likho)(?: ki| ke| that)? (.+)$") to intArrayOf(1, 2),
            rx("^(.+?) ko (?:bolo|bol do|batao|bata do|kaho) (?:ki|ke) (.+)$") to intArrayOf(1, 2),
            rx("^(.+?) ko (.+?) (?:bhejo|bhej do|likh ke bhejo)$") to intArrayOf(1, 2),
        )
        for ((r, g) in patterns) {
            val m = r.matchEntire(s) ?: continue
            val contactRaw = m.groupValues.getOrNull(g[0])?.takeIf { g[0] < m.groupValues.size }
            val text = clean(m.groupValues[g[1]]) ?: continue
            val contact = clean(contactRaw)?.let { stripArticles(it) }?.let { normalizeContactRef(it) }
            if (r.pattern.startsWith("^send (.+?) to") && contact != null && isKnownApp(contact)) continue
            if (r.pattern.startsWith("^(?:send|write|drop) (.+?) ") && contact != null && contact.split(' ').size > 3) continue
            return listOf(Command.SendMessage(contact, text, app, send))
        }
        // "message Rahul" / "send a message to Rahul" without text
        rx("^(?:send (?:a )?(?:message|text|msg) to|message|text|whatsapp|dm) (.+)$").matchEntire(s)?.let {
            val who = normalizeContactRef(stripArticles(it.groupValues[1]))
            if (!isKnownApp(who)) return listOf(Command.SendMessage(who, null, app, send))
        }
        // open chat
        rx("^(?:open|go to|show|find|search for|look for|search) (?:my |the )?(?:chat|conversation|messages|thread|dm|dms)(?: with| of| from) (.+)$").matchEntire(s)?.let {
            return listOf(Command.OpenChat(normalizeContactRef(stripArticles(it.groupValues[1])), app))
        }
        rx("^(?:open|go to|show) (.+?)'?s (?:chat|conversation|messages|dm)$").matchEntire(s)?.let {
            return listOf(Command.OpenChat(normalizeContactRef(it.groupValues[1]), app))
        }
        rx("^(.+?) (?:ki|ka|ke) (?:chat|conversation|messages) (?:kholo|open karo|dikhao)$").matchEntire(s)?.let {
            return listOf(Command.OpenChat(normalizeContactRef(it.groupValues[1]), app))
        }
        rx("^(?:open |show |go to )?(?:chat|conversation) (?:with|of) (.+)$").matchEntire(s)?.let { return listOf(Command.OpenChat(normalizeContactRef(stripArticles(it.groupValues[1])), app)) }
        rx("^(.+?) (?:ka |ki |ke )?(?:chat|conversation|group)$").matchEntire(s)?.let {
            val who = stripArticles(it.groupValues[1].replace(rx("^(?:open|show|go to)\\s+"), ""))
            if (who.isNotBlank() && who.split(' ').size <= 4 && !isKnownApp(who) && !rx("^(?:my|new|this|that|a|group|the)$").matches(who)) return listOf(Command.OpenChat(normalizeContactRef(who), app))
        }
        // find Rahul (inside a messaging app)
        if (app != null || ctx?.let { MSG_APP_NAMES.contains(it.lowercase()) } == true) {
            rx("^(?:find|search for|search|look for|open|go to) (.+)$").matchEntire(s)?.let {
                val who = stripArticles(it.groupValues[1])
                if (!isKnownApp(who) && sectionOf(who) == null && !rx("^(?:message|messages|chats?|first|second|latest|next|previous|the next)").containsMatchIn(who))
                    return listOf(Command.OpenChat(normalizeContactRef(who), app ?: ctx))
            }
        }
        // read messages
        rx("^(?:read|show|check|find|open|what (?:are|is))(?: me)? (?:my |the )?(?:latest |last |new |recent |unread )?(?:messages?|texts?|chats?)(?: (?:from|of|by) (.+))?$").matchEntire(s)?.let {
            return listOf(Command.ReadMessages(clean(it.groupValues[1])?.let(::normalizeContactRef), app))
        }
        rx("^what did (.+?) (?:say|send|write)(?: me)?$").matchEntire(s)?.let {
            return listOf(Command.ReadMessages(normalizeContactRef(it.groupValues[1]), app))
        }
        // email
        rx("^(?:compose|write|send|draft) (?:an? )?(?:e-?mail|mail) (?:to (.+?))?(?: (?:about|regarding|with subject) (.+?))?(?: $say (.+))?$").matchEntire(s)?.let {
            return listOf(Command.Email(clean(it.groupValues[1]), clean(it.groupValues[2]), clean(it.groupValues[3]), send = send && it.groupValues[3].isNotBlank()))
        }
        rx("^e-?mail (.+?) $say (.+)$").matchEntire(s)?.let {
            return listOf(Command.Email(clean(it.groupValues[1]), null, clean(it.groupValues[2]), send))
        }
        return null
    }

    private fun normalizeContactRef(s: String): String = when (s.lowercase().trim()) {
        "him", "her", "them", "usko", "use", "unko", "this person", "that person", "this contact", "the same person" -> SAME_CONTACT
        else -> s.trim()
    }

    private fun calls(c: String, ctx: String?): List<Command>? {
        if (rx("^(?:end|cut|hang up|disconnect|drop)(?: the)?(?: call)?$|^hang up$|^call (?:kaato|kaat do|band karo|cut karo)$|^phone (?:kaato|rakho)$").matches(c))
            return listOf(Command.EndCall)
        if (rx("^(?:open|show|launch)(?: the)? (?:dialer|dialler|dial ?pad|keypad|phone app)$|^dialer kholo$").matches(c))
            return listOf(Command.OpenApp("phone"))
        if (rx("^(?:show|open|check)(?: my| the)? (?:recent calls|call history|call log|missed calls)$").matches(c))
            return listOf(Command.GoTo(Section.HISTORY, "phone"))
        rx("^(?:find|get|show|what'?s|what is|tell me)(?: me)? (.+?)'s (?:phone )?number$").matchEntire(c)?.let {
            return listOf(Command.FindContact(it.groupValues[1].trim()))
        }
        rx("^(.+?) ka (?:phone )?number(?: (?:batao|dikhao|kya hai|do))?$").matchEntire(c)?.let { return listOf(Command.FindContact(it.groupValues[1].trim())) }
        rx("^call (?:the |this contact'?s? |his |her |their )?(first|second|third|other|another|1st|2nd|3rd|work|mobile|home) (?:number|phone)(?: (?:for|of) (?:this contact|him|her|them|(.+)))?$").matchEntire(c)?.let {
            val idx = when (it.groupValues[1].lowercase()) { "other", "another" -> 2; "mobile" -> 1; "work" -> 2; "home" -> 3; else -> Numbers.ordinal(it.groupValues[1]) ?: 1 }
            return listOf(Command.Call(clean(it.groupValues[2]) ?: SAME_CONTACT, numberIndex = idx))
        }
        rx("^(?:call|phone|dial|ring|video call)(?: up)? (.+?)(?: on (?:his|her|their|the) (second|other|work|first|mobile) number)?(?: (?:now|please))?$").matchEntire(c)?.let {
            val target = stripArticles(it.groupValues[1])
            if (isKnownApp(target) && !rx("^\\+?[\\d ]+$").matches(target)) return null
            val number = target.replace(" ", "").takeIf { n -> rx("^\\+?\\d{3,15}$").matches(n) }
            val idx = it.groupValues[2].takeIf { g -> g.isNotBlank() }?.let { g -> if (g == "first" || g == "mobile") 1 else 2 }
            return listOf(Command.Call(if (number == null) normalizeContactRef(target) else null, number, idx))
        }
        rx("^(.+?) ko (?:call|phone|fone) (?:karo|kar do|lagao|laga do|milao)$").matchEntire(c)?.let {
            return listOf(Command.Call(normalizeContactRef(it.groupValues[1].trim())))
        }
        return null
    }

    private fun settingOf(word: String): SystemSetting? = when (word.lowercase().replace(" ", "").replace("-", "")) {
        "wifi", "wlan", "internet" -> SystemSetting.WIFI
        "bluetooth", "bt" -> SystemSetting.BLUETOOTH
        "flashlight", "torch", "flash", "light" -> SystemSetting.FLASHLIGHT
        "mobiledata", "data", "cellulardata" -> SystemSetting.MOBILE_DATA
        "airplanemode", "aeroplanemode", "flightmode", "airplane", "flight" -> SystemSetting.AIRPLANE_MODE
        "dnd", "donotdisturb", "silentmode", "silent" -> SystemSetting.DO_NOT_DISTURB
        "autorotate", "rotation", "autorotation", "screenrotation" -> SystemSetting.AUTO_ROTATE
        "location", "gps" -> SystemSetting.LOCATION
        "hotspot", "mobilehotspot", "tethering" -> SystemSetting.HOTSPOT
        "darkmode", "darktheme", "nightmode" -> SystemSetting.DARK_MODE
        else -> null
    }

    private val settingWords = "(wi-?fi|wlan|internet|bluetooth|bt|flash ?light|torch|flash|light|mobile data|data|cellular data|airplane mode|aeroplane mode|flight mode|dnd|do not disturb|silent mode|auto ?rotat(?:e|ion)|screen rotation|rotation|location|gps|(?:mobile )?hotspot|tethering|dark mode|dark theme|night mode)"

    private fun toggles(c: String, ctx: String?): List<Command>? {
        rx("^(?:turn|switch|put|set) (on|off) (?:the |my )?$settingWords$").matchEntire(c)?.let {
            return listOf(Command.SetToggle(settingOf(it.groupValues[2])!!, it.groupValues[1].lowercase() == "on"))
        }
        rx("^(?:turn|switch|put|set) (?:the |my )?$settingWords (on|off)$").matchEntire(c)?.let {
            return listOf(Command.SetToggle(settingOf(it.groupValues[1])!!, it.groupValues[2].lowercase() == "on"))
        }
        rx("^(enable|disable|activate|deactivate|start|stop|toggle) (?:the |my )?$settingWords$").matchEntire(c)?.let {
            val v = it.groupValues[1].lowercase()
            val on = when (v) { "enable", "activate", "start" -> true; "toggle" -> null; else -> false }
            return listOf(Command.SetToggle(settingOf(it.groupValues[2])!!, on))
        }
        rx("^$settingWords (on|off|chalu|band|jalao|bujhao|on karo|off karo|chalu karo|band karo|on kar do|off kar do|band kar do|chalu kar do|kholo)$").matchEntire(c)?.let {
            val v = it.groupValues[2].lowercase()
            val on = v.startsWith("on") || v.startsWith("chalu") || v == "jalao" || v == "kholo"
            return listOf(Command.SetToggle(settingOf(it.groupValues[1])!!, on))
        }
        return null
    }

    private fun levels(c: String, ctx: String?): List<Command>? {
        fun lvl(w: String) = if (w.lowercase().startsWith("bright") || w.lowercase() == "screen light") Level.BRIGHTNESS else Level.VOLUME
        val lv = "(volume|sound|brightness|screen brightness)"
        if (rx("^mute(?: (?:the )?(?:phone|sound|volume|it|audio|media))?$|^(?:awaaz|volume) band karo$").matches(c)) return listOf(Command.SetLevel(Level.VOLUME, 0, null))
        if (rx("^unmute(?: (?:the )?(?:phone|sound|it|audio|media))?$").matches(c)) return listOf(Command.SetLevel(Level.VOLUME, 50, null))
        rx("^(?:set|change|make|put) (?:the )?$lv (?:to|at) (\\d{1,3})(?: ?%| percent)?$").matchEntire(c)?.let {
            return listOf(Command.SetLevel(lvl(it.groupValues[1]), it.groupValues[2].toInt().coerceIn(0, 100), null))
        }
        rx("^$lv (?:to )?(\\d{1,3})(?: ?%| percent)?(?: (?:karo|kar do|pe karo|par karo))?$").matchEntire(c)?.let {
            return listOf(Command.SetLevel(lvl(it.groupValues[1]), it.groupValues[2].toInt().coerceIn(0, 100), null))
        }
        rx("^(?:set )?(?:the )?$lv (?:to )?(?:max|maximum|full|100)$|^(?:max|maximum|full) $lv$").matchEntire(c)?.let {
            return listOf(Command.SetLevel(lvl(it.groupValues[1].ifBlank { it.groupValues[2] }), 100, null))
        }
        rx("^(?:set )?(?:the )?$lv (?:to )?(?:min|minimum|lowest|zero)$|^(?:min|minimum|lowest) $lv$").matchEntire(c)?.let {
            return listOf(Command.SetLevel(lvl(it.groupValues[1].ifBlank { it.groupValues[2] }), if (lvl(it.groupValues[1].ifBlank { it.groupValues[2] }) == Level.BRIGHTNESS) 5 else 0, null))
        }
        rx("^(increase|raise|turn up|boost|up|increase the|badhao|badha do)(?: the)? $lv(?: (?:a (?:little|bit)|more))?(?: by (\\d{1,3})(?: ?%| percent)?)?$").matchEntire(c)?.let {
            return listOf(Command.SetLevel(lvl(it.groupValues[2]), null, (it.groupValues[3].toIntOrNull() ?: 15)))
        }
        rx("^(decrease|lower|reduce|turn down|down|dim)(?: the)? $lv(?: (?:a (?:little|bit)|more))?(?: by (\\d{1,3})(?: ?%| percent)?)?$").matchEntire(c)?.let {
            return listOf(Command.SetLevel(lvl(it.groupValues[2]), null, -(it.groupValues[3].toIntOrNull() ?: 15)))
        }
        rx("^$lv (up|down|badhao|badha do|kam karo|kam kar do|ghatao|thoda badhao|thoda kam karo|aur badhao)$").matchEntire(c)?.let {
            val up = it.groupValues[2].let { v -> v == "up" || v.contains("badha") }
            return listOf(Command.SetLevel(lvl(it.groupValues[1]), null, if (up) 15 else -15))
        }
        if (rx("^(?:it'?s )?too (?:loud)$").matches(c)) return listOf(Command.SetLevel(Level.VOLUME, null, -20))
        if (rx("^(?:it'?s )?too (?:bright)$").matches(c)) return listOf(Command.SetLevel(Level.BRIGHTNESS, null, -20))
        if (rx("^(?:dim|darken) (?:the )?screen$").matches(c)) return listOf(Command.SetLevel(Level.BRIGHTNESS, null, -25))
        return null
    }

    private fun settings(c: String, ctx: String?): List<Command>? {
        rx("^(?:open|show|go to|take me to)(?: the)? (wi-?fi|bluetooth|display|screen|battery|sound|volume|apps?|application|accessibility|network|internet|mobile network|location|storage|notification|notifications|date and time|privacy|security) settings?$").matchEntire(c)?.let {
            val scr = when (it.groupValues[1].lowercase().replace("-", "")) {
                "wifi" -> SettingsScreen.WIFI; "bluetooth" -> SettingsScreen.BLUETOOTH; "display", "screen" -> SettingsScreen.DISPLAY
                "battery" -> SettingsScreen.BATTERY; "sound", "volume" -> SettingsScreen.SOUND; "app", "apps", "application" -> SettingsScreen.APPS
                "accessibility" -> SettingsScreen.ACCESSIBILITY; "network", "internet", "mobile network" -> SettingsScreen.NETWORK
                "location" -> SettingsScreen.LOCATION; "storage" -> SettingsScreen.STORAGE; "notification", "notifications" -> SettingsScreen.NOTIFICATIONS
                "date and time" -> SettingsScreen.DATE_TIME; else -> SettingsScreen.PRIVACY
            }
            return listOf(Command.OpenSettings(scr))
        }
        rx("^(?:open|show)(?: the)? (?:app (?:info|settings|permissions) (?:for|of) (.+)|(.+?)(?:'s)? (?:app info|app settings|permissions|settings))$").matchEntire(c)?.let {
            val app = clean(it.groupValues[1]) ?: clean(it.groupValues[2])
            if (app != null && app.lowercase() != "the" && isKnownApp(app)) return listOf(Command.OpenSettings(SettingsScreen.APP_DETAILS, app))
        }
        if (rx("^(?:open|show|go to)(?: the| phone| system)? settings$|^settings (?:kholo|open karo)$").matches(c)) return listOf(Command.OpenSettings(SettingsScreen.MAIN))
        return null
    }

    private fun notifications(c: String, ctx: String?): List<Command>? {
        rx("^(?:read|show|check|tell me|what are|any)(?: me)?(?: my| the)?(?: latest| new| recent| unread)? notifications?(?: (?:from|of) (.+))?$").matchEntire(c)?.let {
            return listOf(Command.ReadNotifications(clean(it.groupValues[1])))
        }
        if (rx("^(?:notifications? (?:padho|dikhao|batao)|koi notification(?: hai)?)$").matches(c)) return listOf(Command.ReadNotifications())
        return null
    }

    private fun files(c: String, ctx: String?): List<Command>? {
        val kind = "(pdfs?|documents?|docs?|files?|photos?|pictures?|pics?|images?|videos?|screenshots?|downloads?|recordings?|audio|songs?|spreadsheets?|presentations?)"
        val m = rx("^(?:find|open|show|search|search for|get|locate|share)(?: me)?(?: my| the| that)?(?: (latest|last|recent|newest|most recent|new))? $kind(?: (?:that |which )?i (?:downloaded|saved|took|received|got)(?: (yesterday|today|last week|recently))?)?(?: (?:named|called|about|with|for) (.+))?$").matchEntire(c)
            ?: return null
        val k = m.groupValues[2].lowercase()
        // "open the first video" or "find videos" inside YouTube are not files.
        if ((k.startsWith("video") || k.startsWith("song")) && m.groupValues[1].isBlank() && m.groupValues[3].isBlank() && m.groupValues[4].isBlank()) return null
        if ((ctx != null && !ctx.equals("files", true)) && m.groupValues[3].isBlank() && k.startsWith("video")) return null
        val mime = when {
            k.startsWith("pdf") -> "application/pdf"
            k.startsWith("photo") || k.startsWith("pic") || k.startsWith("image") || k.startsWith("screenshot") -> "image/"
            k.startsWith("video") || k.startsWith("recording") -> "video/"
            k.startsWith("audio") || k.startsWith("song") -> "audio/"
            k.startsWith("doc") -> "application/"
            else -> null
        }
        val newest = m.groupValues[1].isNotBlank() || m.groupValues[3].isNotBlank()
        val query = listOf(m.groupValues[4], if (k.startsWith("screenshot")) "screenshot" else "", m.groupValues[3]).filter { it.isNotBlank() }.joinToString(" ")
        val cmd = Command.FindFile(query, mime, newest)
        return if (c.lowercase().startsWith("share")) listOf(cmd, Command.Share) else listOf(cmd)
    }

    private fun media(c: String, ctx: String?): List<Command>? {
        if (rx("^(?:pause|stop)(?: the| this)? (?:video|music|song|playback|audio|media|track|it)$|^pause it$|^(?:video|gaana|music) (?:roko|rok do|band karo|pause karo)$").matches(c))
            return listOf(Command.Media(MediaKey.PAUSE))
        if (rx("^(?:play|resume|unpause|continue)(?: the| this)? (?:video|music|song|playback|audio|media|track|it)$|^(?:resume|unpause)$|^(?:play)$|^(?:video|gaana|music) (?:chalao|chalu karo|play karo)$").matches(c))
            return listOf(Command.Media(MediaKey.PLAY))
        rx("^(?:play|put on|listen to|start playing|bajao|chalao)(?: me)? (?!the (?:first|second|third|last|next|previous) |reels?$|shorts?$)(.+?)(?: (?:on|in|using|from) (spotify|youtube music|youtube|yt music|gaana|jiosaavn|wynk|apple music|amazon music|instagram|insta))?$").matchEntire(c)?.let {
            val q = clean(it.groupValues[1]) ?: return null
            if (Numbers.ordinal(q.substringBefore(' ')) != null) return null
            if (rx("^(?:a |the )?(?:video|music|song|it)$").matches(q)) return listOf(Command.Media(MediaKey.PLAY))
            if (it.groupValues[2].isBlank() && isKnownApp(q)) return listOf(Command.OpenApp(q))
            return listOf(Command.PlayMedia(q, clean(it.groupValues[2])?.let { a -> if (a.equals("insta", true)) "Instagram" else a }))
        }
        rx("^(.+?) (?:bajao|chalao|play karo|sunao|laga do|lagao)(?: (spotify|youtube|instagram|insta) (?:pe|par|mein))?$").matchEntire(c)?.let {
            val q = it.groupValues[1].replace(rx("^(?:gaana|gana|song)\\s+"), "").trim()
            if (Numbers.ordinal(q.substringBefore(' ')) != null || rx("^(?:reels?|shorts?|video)$").matches(q)) return null
            if (it.groupValues[2].isBlank() && isKnownApp(q)) return listOf(Command.OpenApp(q))
            return listOf(Command.PlayMedia(q, clean(it.groupValues[2])?.let { a -> if (a.equals("insta", true)) "Instagram" else a }))
        }
        return null
    }

    private fun results(c: String, ctx: String?): List<Command>? {
        val ord = "(first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|last|1st|2nd|3rd|4th|5th|6th|7th|8th|9th|10th|top|pehla|pahla|pehli|doosra|dusra|doosri|teesra|tisra|chautha|paanchva|aakhri|akhri)"
        val noun = "(result|video|one|link|reel|post|song|item|option|short|chat|conversation|email|mail|message|search result|channel|account|profile|photo|story|track|playlist|website|site|page|contact|number|entry)"
        val m = rx("^(?:(?:open|play|tap|click|select|choose|watch|pick|go to|show|view|start|tap on|click on|kholo|chalao)(?: on)? )?(?:the )?$ord $noun(?: (?:in|of|from) (?:the )?(?:list|results|search results))?(?: (?:kholo|chalao|play karo|open karo|dikhao|pe click karo|pe tap karo))?$").matchEntire(c)
            ?: rx("^(?:the )?$ord $noun(?: (?:kholo|chalao|play karo|open karo|dikhao|pe click karo|pe tap karo))?$").matchEntire(c)
            ?: rx("^(?:open|play|tap|select) (?:result|video|item|option) (?:number )?(\\d+|one|two|three|four|five)$").matchEntire(c)?.let { mm ->
                return listOf(Command.OpenResult(Numbers.parse(mm.groupValues[1]) ?: 1))
            }
            ?: return null
        val ordinalWord = m.groupValues[1]
        val n = Numbers.ordinal(ordinalWord) ?: return null
        return listOf(Command.OpenResult(n, m.groupValues[2].lowercase().takeIf { it != "one" && it != "result" && it != "search result" && it != "item" && it != "option" }))
    }

    private fun sectionOf(w: String): Section? = when (w.lowercase().trim().removeSuffix(" tab").removeSuffix(" section").removeSuffix(" page").removeSuffix(" feed").trim()) {
        "reels", "reel", "reels section" -> Section.REELS
        "shorts", "short", "youtube shorts" -> Section.SHORTS
        "home", "home feed", "feed", "main feed" -> Section.HOME
        "messages", "dms", "dm", "direct messages", "direct", "my messages", "inbox", "my dms" -> Section.MESSAGES
        "profile", "my profile", "account", "my account" -> Section.PROFILE
        "explore", "discover", "search tab" -> Section.EXPLORE
        "subscriptions", "subs", "my subscriptions" -> Section.SUBSCRIPTIONS
        "library", "you", "my library", "watch later", "saved", "playlists" -> Section.LIBRARY
        "stories", "story" -> Section.STORIES
        "chats" -> Section.CHATS
        "calls", "call log" -> Section.CALLS
        "status", "updates" -> Section.STATUS
        "sent", "sent mail", "sent emails" -> Section.SENT
        "drafts" -> Section.DRAFTS
        "history", "watch history" -> Section.HISTORY
        "downloads" -> Section.DOWNLOADS
        "activity", "notifications tab" -> Section.NOTIFICATIONS
        else -> null
    }

    private fun sections(c: String, ctx: String?): List<Command>? {
        val m = rx("^(?:go to|open|show|show me|take me to|switch to|navigate to|play|watch|check|see|view)(?: the| my)? (.+?)(?: (?:tab|section|page|feed))?(?: (?:on|in|of) (.+))?$").matchEntire(c)
        if (m != null) {
            val sec = sectionOf(m.groupValues[1])
            val app = clean(m.groupValues[2])
            if (sec != null && (app == null || isKnownApp(app))) {
                return if (app != null) listOf(Command.OpenApp(app), Command.GoTo(sec, app)) else listOf(Command.GoTo(sec))
            }
            // "open instagram reels" / "open youtube shorts"
            val words = m.groupValues[1].split(' ')
            if (words.size >= 2) {
                val lastSec = sectionOf(words.last())
                val maybeApp = words.dropLast(1).joinToString(" ")
                if (lastSec != null && isKnownApp(maybeApp)) return listOf(Command.OpenApp(maybeApp), Command.GoTo(lastSec, maybeApp))
            }
        }
        rx("^(.+?) (?:kholo|dikhao|pe jao|par jao|mein jao|me jao|chalao|open karo)$").matchEntire(c)?.let {
            val sec = sectionOf(it.groupValues[1]) ?: return@let
            return listOf(Command.GoTo(sec))
        }
        sectionOf(c)?.let { if (it == Section.REELS || it == Section.SHORTS || it == Section.MESSAGES) return listOf(Command.GoTo(it)) }
        return null
    }

    private fun search(c: String, ctx: String?): List<Command>? {
        val appTail = "(?: (?:on|in|using|at) (youtube|instagram|google|chrome|spotify|whatsapp|gmail|play store|amazon|flipkart|maps|google maps|youtube music|the web|internet|(?:[\\w]+)))?"
        rx("^(?:search|search for|look up|look for|google|find|find me|search up|browse for)(?: (?:on|in) (youtube|instagram|google|chrome|spotify|play store|maps|gmail)(?: for)?)? (.+?)$appTail$").matchEntire(c)?.let {
            val appA = clean(it.groupValues[1]); var q = clean(it.groupValues[2]) ?: return null
            var appB = clean(it.groupValues[3])
            if (appB != null && !isKnownApp(appB) && appB !in setOf("the web", "internet")) { q = "$q ${it.groupValues[3]}".trim(); appB = null }
            val app = (appA ?: appB)?.let { a -> if (a == "the web" || a == "internet") "chrome" else a }
            if (q.lowercase().startsWith("for ")) q = q.substring(4)
            return if (app != null) listOf(Command.OpenApp(app), Command.Search(q, app)) else listOf(Command.Search(q, null))
        }
        rx("^(?:(youtube|instagram|google|chrome|spotify|play store) (?:pe|par|mein|me) )?(.+?) (?:search karo|search kar do|search|dhundo|dhoondo|dhundho|khojo|sarch karo|ko search karo|ko dhundo)$").matchEntire(c)?.let {
            val app = clean(it.groupValues[1]); val q = clean(it.groupValues[2]) ?: return null
            return if (app != null) listOf(Command.OpenApp(app), Command.Search(q, app)) else listOf(Command.Search(q, null))
        }
        rx("^search (?:karo|kar do) (.+)$").matchEntire(c)?.let { return listOf(Command.Search(it.groupValues[1].trim())) }
        return null
    }

    private val onThis = rx("\\s+(?:on|in|under|to)\\s+(?:this|the|that|it|is|current)(?:\\s+(?:video|reel|post|short|photo|pic|one))?$|\\s+(?:on|under)\\s+it$|\\s+(?:is|iss|isme|ispe|is par|is pe)\\s+(?:video|reel|post)\\s+(?:pe|par|mein|me)$")

    private fun write(c: String, ctx: String?): List<Command>? {
        var s = c.replace(onThis, "")
        var submit: Boolean? = null
        noSend.find(s)?.let { submit = false; s = s.removeRange(it.range).trim() }
        rx("^(?:write|type|enter|put|add|input)(?: a| the)? (?:comment|reply)(?: saying| that says|:)? (.+)$").matchEntire(s)?.let {
            return listOf(Command.OpenComments, Command.Write(clean(it.groupValues[1])!!, submit ?: true))
        }
        rx("^(?:post|leave|drop)(?: a| the)? comment(?: saying| that says|:)? (.+)$").matchEntire(s)?.let {
            return listOf(Command.OpenComments, Command.Write(clean(it.groupValues[1])!!, submit ?: true))
        }
        rx("^comment(?: saying| that says|:)? (.+)$").matchEntire(s)?.let {
            return listOf(Command.OpenComments, Command.Write(clean(it.groupValues[1])!!, submit ?: true))
        }
        rx("^(?:write|type|enter|input|put|likho|likh do|type karo)(?: in| down)?(?: (?:saying|that|the text|text))? (.+?)$").matchEntire(s)?.let {
            return listOf(Command.Write(clean(it.groupValues[1])!!, submit))
        }
        rx("^(?:comment (?:me|mein|main) )(.+?) (?:likho|likh do|daalo|type karo)$").matchEntire(s)?.let {
            return listOf(Command.OpenComments, Command.Write(clean(it.groupValues[1])!!, submit ?: true))
        }
        rx("^(.+?) (?:likho|likh do|type karo|type kar do)$").matchEntire(s)?.let {
            return listOf(Command.Write(clean(it.groupValues[1])!!, submit))
        }
        rx("^(.+?) (?:comment karo|comment kar do)$").matchEntire(s)?.let {
            return listOf(Command.OpenComments, Command.Write(clean(it.groupValues[1])!!, submit ?: true))
        }
        return null
    }

    /** "send do it later" / "send hello" with no recipient: type it where you are and send. */
    private fun sendText(c: String, ctx: String?): List<Command>? {
        rx("^send (?:him|her|them|usko|unko) (?:a )?(?:message |msg |text )?(?:saying |that )?(.+)$").matchEntire(c)?.let {
            return listOf(Command.SendMessage(SAME_CONTACT, clean(it.groupValues[1])!!, null, true))
        }
        rx("^(?:send|bhejo|bhej do|post|reply)(?: (?:the )?(?:message|text|msg))?(?: saying| that|:)? (.+)$").matchEntire(c)?.let {
            val text = clean(it.groupValues[1].replace(onThis, "")) ?: return null
            if (rx("^(?:an? |the )?(?:comment|reply|e-?mail|mail)\\b").containsMatchIn(text)) return null
            if (rx("^(?:it|this|that|the (?:message|comment|email|mail|reply))$").matches(text)) return listOf(Command.Submit)
            return listOf(Command.Write(text, true))
        }
        rx("^(.+?) (?:bhejo|bhej do|send karo|send kar do|send kardo)$").matchEntire(c)?.let {
            val text = clean(it.groupValues[1]) ?: return null
            if (rx("^(?:message|msg|ye|yeh|isko)$").matches(text)) return listOf(Command.Submit)
            return listOf(Command.Write(text, true))
        }
        return null
    }

    // ---------------------------------------------------- alarms, notes, maths, teaching, bubble

    private fun timeOf(h: String, m: String?, ampm: String?): Pair<Int, Int>? {
        var hour = h.toIntOrNull() ?: Numbers.parse(h) ?: return null
        val min = m?.toIntOrNull() ?: 0
        val a = ampm?.lowercase()?.replace(".", "")?.trim()
        if (a != null) {
            if ((a == "pm" || a == "shaam" || a == "raat" || a == "evening" || a == "night") && hour < 12) hour += 12
            if ((a == "am" || a == "subah" || a == "morning") && hour == 12) hour = 0
        }
        return if (hour in 0..23 && min in 0..59) hour to min else null
    }

    private fun durationSeconds(n: String, unit: String): Int? {
        val v = n.toDoubleOrNull() ?: Numbers.parse(n)?.toDouble() ?: return null
        val u = unit.lowercase()
        val mult = when { u.startsWith("h") || u.startsWith("ghant") -> 3600; u.startsWith("s") -> 1; else -> 60 }
        return (v * mult).toInt().takeIf { it > 0 }
    }

    private val unitRx = "(seconds?|secs?|minutes?|mins?|min|hours?|hrs?|hr|ghante|ghanta|minute)"
    private val clock = "(\\d{1,2})(?:[:.](\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?|am|pm)?"

    /** Productivity, modes, study, memory questions, corrections. */
    private fun life(c: String, ctx: String?): List<Command>? {
        if (rx("^(?:what(?:'s| is| have i| did i)? (?:scheduled|i scheduled|i have scheduled)|(?:show|list|read)(?: me)?(?: my| all)? scheduled(?: messages?| tasks?| actions?)?|(?:my )?scheduled (?:messages|actions|tasks)|what is (?:on )?my schedule(?:d)?)\\??$").matches(c)) return listOf(Command.ListScheduled)
        rx("^(?:cancel|delete|remove|stop)\\s+(all\\s+)?(?:the\\s+|my\\s+)?scheduled(?:\\s+(?:message|messages|action|actions|task|tasks))?(?:\\s+(?:to|for)\\s+(.+))?$").matchEntire(c)?.let { m ->
            return listOf(Command.CancelScheduled(m.groupValues[2].trim().ifBlank { null }))
        }
        rx("^(?:no[,.!]?\\s+)?(?:i meant|i mean|i said|mera matlab(?: tha)?|matlab)\\s+(.+?)(?:\\s+tha)?$").matchEntire(c)?.let { return listOf(Command.MeantInstead(it.groupValues[1].trim())) }
        rx("^(?:always|hamesha) (?:use|message|text|contact) (whatsapp|instagram|telegram|sms|messages|text) (?:for|to|with) (.+)$").matchEntire(c)?.let {
            return listOf(Command.PreferApp(normalizeContactRef(it.groupValues[2].trim()), it.groupValues[1].lowercase()))
        }
        rx("^(?:what do you know about|what do you remember about|tell me what you know about|(.+?) ke baare mein kya pata hai) ?(.*)$").matchEntire(c)?.let {
            val topic = it.groupValues[2].ifBlank { it.groupValues[1] }.trim()
            if (topic.isNotBlank()) return listOf(Command.WhatDoYouKnow(topic))
        }
        if (rx("^(?:what should i do(?: now| next)?|what now|what'?s next|ab kya karu+n?|kya karna hai(?: ab)?|what'?s my next task)$").matches(c)) return listOf(Command.WhatNow)
        if (rx("^(?:show|read|list|what are|tell me)(?: me)? (?:my |all )?(?:open |pending )?(?:tasks|to ?dos|to do list|deadlines)(?: for today)?$|^(?:my )?(?:tasks|deadlines)$|^kya kya karna hai$").matches(c)) return listOf(Command.ListTasks)
        rx("^(?:mark |set )?(?:the )?(.+?) (?:as )?(?:done|complete|completed|finished|ho gaya)$|^(?:i )?(?:finished|completed|did) (?:the )?(.+)$").matchEntire(c)?.let {
            val t = it.groupValues[1].ifBlank { it.groupValues[2] }.trim()
            if (t.isNotBlank() && !rx("^(?:it|that|this|all)$").matches(t)) return listOf(Command.CompleteTask(t))
        }
        rx("^(?:add (?:a )?task|new task|create (?:a )?task|task|add to my (?:tasks|to ?do(?: list)?))[: ]+(.+?)(?: (?:by|due|before|till) (.+))?$").matchEntire(c)?.let {
            return listOf(Command.AddTask(it.groupValues[1].trim(), it.groupValues[2].trim().ifBlank { null }))
        }
        if (rx("^(?:morning brief(?:ing)?|good morning brief|give me (?:my |the )?(?:morning )?brief(?:ing)?|brief me|plan my day|aaj ka plan)$").matches(c)) return listOf(Command.Brief("morning"))
        if (rx("^(?:evening review|review my day|how did i do today|day review)$").matches(c)) return listOf(Command.Brief("evening"))
        if (rx("^(?:weekly review|how was my week|week review)$").matches(c)) return listOf(Command.Brief("weekly"))
        if (rx("^(?:what did i miss|daily digest|catch me up|any updates|kya miss hua)$").matches(c)) return listOf(Command.Brief("digest"))
        rx("^(?:quiz me|test me|ask me questions)(?: on| in| about)? ?(.*)$").matchEntire(c)?.let { return listOf(Command.QuizMe(clean(it.groupValues[1]))) }
        rx("^(?:add (?:a )?flash ?card|new flash ?card|flash ?card)(?: for| in)? ?([a-z ]+?)?[: ]+(.+?)\\s*(?:\\||answer is|answer:|ans:)\\s*(.+)$").matchEntire(c)?.let {
            return listOf(Command.AddFlashcard(clean(it.groupValues[1]), it.groupValues[2].trim(), it.groupValues[3].trim()))
        }
        rx("^(?:allow|let me use|unblock|open) (.+?) for (\\d+) ?(?:minutes?|mins?)(?: because .+)?$").matchEntire(c)?.let {
            return listOf(Command.AllowApp(it.groupValues[1].trim(), it.groupValues[2].toInt()))
        }
        rx("^(?:spent|paid|kharch(?:a|e) kiye?|expense)\\b.*\\d.*$|^.*\\b\\d+ ?(?:rs|rupees|₹|rupaye)\\b.*(?:on|for|pe|par)\\b.*$").matchEntire(c)?.let { return listOf(Command.AddExpense(c)) }
        rx("^(?:note|capture|idea|jot|save (?:this )?idea|add to (?:my )?inbox)\\s*[:,-]?\\s+(.+)$").matchEntire(c)?.let { return listOf(Command.Capture(it.groupValues[1].trim())) }
        if (rx("^(?:pause|stop|turn off) (?:watching )?notifications?(?: watching| reading)?$").matches(c)) return listOf(Command.Toggle("notifications", false))
        if (rx("^(?:resume|start|turn on) (?:watching )?notifications?(?: watching| reading)?$").matches(c)) return listOf(Command.Toggle("notifications", true))
        if (rx("^(?:mute (?:your )?voice|be quiet|stop talking|chup|chup raho|not now|abhi nahi)$").matches(c)) return listOf(Command.Toggle("voice", false))
        if (rx("^(?:(?:can you |will you |please )?(?:unmute (?:your )?voice|speak again|talk to me|you can talk)\\??)$").matches(c)) return listOf(Command.Toggle("voice", true))
        rx("^(?:stop|end|exit|leave|turn off|quit)(?: the)? ?([a-z]+)? mode$|^([a-z]+) mode (?:off|band karo|khatam)$").matchEntire(c)?.let {
            val name = it.groupValues[1].ifBlank { it.groupValues[2] }.trim().ifBlank { null }
            if (name == null || !rx("^(?:dark|night|airplane|aeroplane|flight|silent|vibrate|dnd)$").matches(name)) return listOf(Command.StopMode(name))
        }
        rx("^(?:start |begin |enter |switch to |turn on |activate |go into )?(?:the )?([a-z]+(?: [a-z]+)?) mode(?: (?:for|on) (.+?))?(?: (?:on|chalu karo|shuru karo|lagao))?$").matchEntire(c)?.let {
            val name = it.groupValues[1].trim()
            if (!rx("^(?:dark|night|airplane|aeroplane|flight|silent|vibrate|vibration|ring|ringer|normal|reading|power saving|battery saver|do not disturb|dnd|incognito|driving|sound|general)$").matches(name))
                return listOf(Command.StartMode(name, clean(it.groupValues[2])))
        }
        return null
    }

    private fun extras(c: String, ctx: String?): List<Command>? {
        // Spatial grid: "tap top right", "double tap center", "long press bottom left corner"
        rx("^(double ?tap|double click|long ?press|press and hold|tap|click|touch|press)(?: on| at)?(?: the)? (top|upper|middle|center|centre|bottom|lower)?(?:[ -](left|right|middle|center|centre))?(?: corner| side| part)?(?: of (?:the )?screen)?$").matchEntire(c)?.let {
            val v = it.groupValues[2].lowercase(); val h = it.groupValues[3].lowercase()
            if (v.isEmpty() && h.isEmpty()) return@let
            val row = when (v) { "top", "upper" -> 0; "bottom", "lower" -> 2; else -> 1 }
            val col = when (h) { "left" -> 0; "right" -> 2; else -> 1 }
            val verb = it.groupValues[1].lowercase().replace(" ", "")
            return listOf(Command.TapRegion(row, col, double = verb.startsWith("double"), long = verb.startsWith("long") || verb.startsWith("pressand")))
        }
        // "send this to Arjun" / "share this with Rahul on WhatsApp" / "ye Rahul ko bhejo"
        rx("^(?:send|share|forward) (?:this|that|it|ye|yeh|this (?:video|reel|post|link|photo|page|song|short))(?: video| reel| post| link| photo| page)? (?:to|with) (.+?)(?: (?:on|via|using) (whatsapp|instagram|telegram|messages))?$").matchEntire(c)?.let {
            return listOf(Command.ShareTo(it.groupValues[1].trim(), clean(it.groupValues[2])))
        }
        rx("^(?:ye|yeh|isko|ise) (.+?) ko (?:bhejo|bhej do|share karo|forward karo)$").matchEntire(c)?.let {
            return listOf(Command.ShareTo(it.groupValues[1].trim()))
        }
        rx("^(?:put (?:the )?phone (?:on|in) |set (?:the )?phone to |switch to |go to |turn on |enable |phone ko )?(silent|vibrate|vibration|general|ring|ringer|normal|sound)(?: mode)?(?: (?:on|pe|par|karo|kar do|mein dalo|me dalo))?$").matchEntire(c)?.let {
            val m = when (it.groupValues[1].lowercase()) { "silent" -> "silent"; "vibrate", "vibration" -> "vibrate"; else -> "normal" }
            return listOf(Command.Ringer(m))
        }
        if (rx("^(?:phone )?(?:silent|chup) kar do$|^silent karo$").matches(c)) return listOf(Command.Ringer("silent"))
        rx("^(?:navigate to|navigate me to|directions to|direction to|get directions to|take me to|drive to|route to|how do i get to|(?:mujhe )?(.+?) (?:le chalo|ka rasta batao|ka rasta dikhao|tak le chalo)|rasta (?:batao|dikhao) )(.*)$").matchEntire(c)?.let {
            val place = clean(it.groupValues[2].ifBlank { it.groupValues[1] }) ?: return null
            val clearlyMaps = rx("^(?:direction|navigate|route|drive|how do i get)").containsMatchIn(c)
            if (isKnownApp(place) || (sectionOf(place) != null && !clearlyMaps)) return null
            val mode = when { place.contains(" by walk") || place.contains(" walking") -> "w"; place.contains(" by bike") -> "b"; else -> null }
            return listOf(Command.Directions(place.replace(rx(" (?:by (?:walk|bike|car)|walking|driving)$"), ""), mode))
        }
        rx("^(?:add|create|schedule|set|put|make|book)(?: an?| a new)? (?:event|meeting|appointment|calendar event|reminder in calendar)(?: (?:called|named|for|about|with|titled|:))? (.+)$").matchEntire(c)?.let {
            var rest = it.groupValues[1]
            var hour: Int? = null; var minute: Int? = null; var day: String? = null
            rx("(?:\\s+at\\s+|\\s+)$clock\\s*$").find(rest)?.let { m -> timeOf(m.groupValues[1], m.groupValues[2].ifBlank { null }, m.groupValues[3].ifBlank { null })?.let { (h, mi) -> hour = h; minute = mi }; rest = rest.substring(0, m.range.first) }
            rx("\\s+(today|tomorrow|day after tomorrow|kal|parso|aaj|on (?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)|next (?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|week)|on \\d{1,2}(?:st|nd|rd|th)?(?: of)? \\w+)$").find(rest)?.let { m -> day = m.groupValues[1]; rest = rest.substring(0, m.range.first) }
            val title = clean(rest.replace(rx("^(?:with|for|about)\\s+"), "").replace(rx("\\s+(?:with|for|about)$"), "")) ?: "Event"
            return listOf(Command.CalendarEvent(title, day, hour, minute))
        }
        rx("^(?:take|click|capture|khicho|kheecho) (?:a |an |ek )?(selfie|photo|picture|pic|snap|video|photo lo|selfie lo)$|^(photo|selfie|video) (?:lo|le lo|kheecho|khicho|banao|record karo)$|^(?:record|shoot) (?:a )?(video)$").matchEntire(c)?.let {
            val w = (it.groupValues[1].ifBlank { it.groupValues[2].ifBlank { it.groupValues[3] } }).lowercase()
            return listOf(Command.TakePhoto(front = w.startsWith("selfie"), video = w.startsWith("video")))
        }
        if (rx("^(?:redial|call back|call (?:him|her|them) back|call again|dobara call karo|wapas call karo|phir se call karo|call the last number)$").matches(c)) return listOf(Command.Call(SAME_CONTACT))
        // Teaching / routines
        if (rx("^(?:watch me|watch and learn|learn this|teach me|let me teach you|i will teach you|i'?ll teach you|sikhata hu+n?|let me show you|i'?ll show you|record this|dekho main kaise karta hu+n?|main dikhata hu+n?)$").matches(c)) return listOf(Command.TeachStart)
        rx("^(?:done|stop watching|stop recording|that'?s it|ho gaya|bas ho gaya|finish(?:ed)?)(?:[,.]?\\s*(?:save (?:it|this) as|call it|name it|naam rakho)\\s+(.+))?$").matchEntire(c)?.let {
            return listOf(Command.TeachStop(clean(it.groupValues[1])))
        }
        rx("^(?:save (?:it|this) as|call it|name it)\\s+(.+)$").matchEntire(c)?.let { return listOf(Command.TeachStop(clean(it.groupValues[1]))) }
        // Bubble
        if (rx("^(?:hide|close|remove|stop|turn off|band karo|hatao)(?: the)?(?: floating)? (?:bubble|button|window|overlay|icon)$|^(?:bubble|floating button) (?:hatao|band karo|hide karo)$").matches(c)) return listOf(Command.HideBubble)
        // Reminders → timers/alarms with a label
        rx("^remind me (?:to |about )?(.+?) in (\\d+(?:\\.\\d+)?|an?|one|two|five|ten) $unitRx$").matchEntire(c)?.let {
            val n = if (it.groupValues[2] == "a" || it.groupValues[2] == "an") "1" else it.groupValues[2]
            return listOf(Command.SetTimer(durationSeconds(n, it.groupValues[3]) ?: return null, clean(it.groupValues[1])))
        }
        rx("^remind me in (\\d+(?:\\.\\d+)?|an?|one|two|five|ten) $unitRx (?:to |about )?(.+)$").matchEntire(c)?.let {
            val n = if (it.groupValues[1] == "a" || it.groupValues[1] == "an") "1" else it.groupValues[1]
            return listOf(Command.SetTimer(durationSeconds(n, it.groupValues[2]) ?: return null, clean(it.groupValues[3])))
        }
        rx("^remind me (?:to |about )?(.+?) (?:at|by) $clock$").matchEntire(c)?.let {
            val (h, m) = timeOf(it.groupValues[2], it.groupValues[3].ifBlank { null }, it.groupValues[4].ifBlank { null }) ?: return null
            return listOf(Command.SetAlarm(h, m, clean(it.groupValues[1])))
        }
        rx("^remind me (?:at|by) $clock (?:to |about )?(.+)$").matchEntire(c)?.let {
            val (h, m) = timeOf(it.groupValues[1], it.groupValues[2].ifBlank { null }, it.groupValues[3].ifBlank { null }) ?: return null
            return listOf(Command.SetAlarm(h, m, clean(it.groupValues[4])))
        }
        // Timers
        rx("^(?:set |start |put |lagao )?(?:an? )?(?:timer|countdown) (?:for |of )?(\\d+(?:\\.\\d+)?|an?|one|two|five|ten) $unitRx(?: (?:for|called) (.+))?$").matchEntire(c)?.let {
            val n = if (it.groupValues[1].startsWith("a")) "1" else it.groupValues[1]
            return listOf(Command.SetTimer(durationSeconds(n, it.groupValues[2]) ?: return null, clean(it.groupValues[3])))
        }
        rx("^(\\d+(?:\\.\\d+)?) $unitRx (?:ka |ki )?timer(?: (?:lagao|laga do|set karo|start karo))?$|^(\\d+(?:\\.\\d+)?) $unitRx timer$").matchEntire(c)?.let {
            val n = it.groupValues[1].ifBlank { it.groupValues[3] }; val u = it.groupValues[2].ifBlank { it.groupValues[4] }
            return listOf(Command.SetTimer(durationSeconds(n, u) ?: return null))
        }
        // Alarms
        rx("^(?:set |put |create |lagao )?(?:an? )?alarm (?:for |at )?$clock(?: (?:tomorrow|today|kal))?(?: (?:for|to|called|saying) (.+))?$").matchEntire(c)?.let {
            val (h, m) = timeOf(it.groupValues[1], it.groupValues[2].ifBlank { null }, it.groupValues[3].ifBlank { null }) ?: return null
            return listOf(Command.SetAlarm(h, m, clean(it.groupValues[4])))
        }
        rx("^wake me(?: up)? (?:at )?$clock$").matchEntire(c)?.let {
            val (h, m) = timeOf(it.groupValues[1], it.groupValues[2].ifBlank { null }, it.groupValues[3].ifBlank { null }) ?: return null
            return listOf(Command.SetAlarm(h, m, "Wake up"))
        }
        rx("^(subah|shaam|raat)? ?(\\d{1,2})(?:[:.](\\d{2}))? baje (?:ka )?alarm(?: (?:lagao|laga do|set karo))?$").matchEntire(c)?.let {
            val (h, m) = timeOf(it.groupValues[2], it.groupValues[3].ifBlank { null }, it.groupValues[1].ifBlank { null }) ?: return null
            return listOf(Command.SetAlarm(h, m))
        }
        // Notes
        rx("^(?:take (?:a )?note|make a note|add (?:a )?note|note down|note|write down|jot down|note karo|likh lo)(?: that)?:? (.+)$").matchEntire(c)?.let {
            return listOf(Command.Note(clean(it.groupValues[1])!!))
        }
        if (rx("^(?:read|show|what are|tell me)(?: me)? (?:my |the )?notes$|^notes (?:padho|dikhao|batao)$").matches(c)) return listOf(Command.ReadNotes)
        if (rx("^(?:clear|delete|remove|erase) (?:all )?(?:my |the )?notes$").matches(c)) return listOf(Command.ClearNotes)
        // Maths
        rx("^(?:what(?:'s| is)|calculate|compute|how much is|kitna hai|kitne hote hain|solve)?\\s*([\\d\\s.+\\-*/x×÷%()^]+|[\\d.]+ ?% of [\\d.]+|[\\d.]+ (?:plus|minus|times|into|multiplied by|divided by|by|over|guna|aur|minus) [\\d.]+(?: (?:plus|minus|times|into|multiplied by|divided by|by|over) [\\d.]+)*)\\s*\\??$").matchEntire(c)?.let {
            val e = it.groupValues[1].trim()
            if (e.any { ch -> ch.isDigit() } && (e.any { ch -> ch in "+-*/x×÷%^" } || e.contains(' '))) return listOf(Command.Calculate(e))
        }
        return null
    }

    private fun tap(c: String, ctx: String?): List<Command>? {
        rx("^(?:long ?press|press and hold|hold)(?: on)?(?: the)? (.+?)(?: button| icon| option)?$").matchEntire(c)?.let {
            return listOf(Command.Tap(it.groupValues[1].trim(), long = true))
        }
        rx("^(?:tap|click|press|hit|select|choose|touch|tick|check|uncheck|toggle)(?: on)?(?: the)? (.+?)(?: (button|icon|option|tab|link|menu|item|checkbox|switch))?$").matchEntire(c)?.let {
            val label = it.groupValues[1].trim()
            if (label.isBlank()) return null
            // "check Instagram" means open it; "tap/click Instagram" taps it (or opens it if it isn't on screen).
            if (isKnownApp(label) && !rx("^(?:tap|click|press|hit|touch)\\b").containsMatchIn(c)) return null
            return listOf(Command.Tap(label))
        }
        rx("^(.+?) (?:pe|par) (?:tap|click|press) (?:karo|kar do)$|^(.+?) dabao$").matchEntire(c)?.let {
            return listOf(Command.Tap((it.groupValues[1].ifBlank { it.groupValues[2] }).trim()))
        }
        return null
    }

    private fun misc(c: String, ctx: String?): List<Command>? {
        if (rx("^share(?: (?:it|this|that|the|is|ye|yeh))?(?: (?:video|reel|post|link|file|photo|page|song|short|screenshot|image|pic))?$|^(?:ise |isko |ye )?share karo$").matches(c)) return listOf(Command.Share)
        if (rx("^copy(?: (?:it|this|that|the (?:text|link|url|message|number|address)))?$|^copy karo$").matches(c)) return listOf(Command.Copy)
        if (rx("^paste(?: (?:it|this|that|the text))?(?: here)?$|^paste karo$").matches(c)) return listOf(Command.Paste)
        return null
    }

    private fun memory(c: String, ctx: String?): List<Command>? {
        if (rx("^(?:forget everything|clear (?:my |your |all )?(?:memory|memories|history)|wipe (?:your )?memory|sab bhool jao)$").matches(c)) return listOf(Command.Forget(null))
        rx("^(?:forget|don'?t remember)(?: that| about)? (.+)$").matchEntire(c)?.let { return listOf(Command.Forget(it.groupValues[1].trim())) }
        rx("^(?:remember|note|keep in mind|yaad rakho|yaad rakhna)(?: that| ki)?:? (.+)$").matchEntire(c)?.let {
            val v = it.groupValues[1].trim()
            // "remember that mom means Sunita Sharma" → alias
            rx("^(.+?) (?:means|is|=|refers to|matlab) (.+)$").matchEntire(v)?.let { a ->
                return listOf(Command.Remember(a.groupValues[1].trim(), a.groupValues[2].trim()))
            }
            return listOf(Command.Remember(null, v))
        }
        rx("^(?:when i say|if i say) [\"']?(.+?)[\"']?,? (?:i mean|it means|that means|open|do) (.+)$").matchEntire(c)?.let {
            return listOf(Command.Remember(it.groupValues[1].trim(), it.groupValues[2].trim()))
        }
        return null
    }

    private fun url(c: String, ctx: String?): List<Command>? {
        rx("^(?:open|go to|visit|browse to|load|navigate to|kholo)? ?(?:the )?(?:website |site )?((?:https?://)?(?:www\\.)?[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.(?:com|in|org|net|io|co|ai|edu|gov|dev|app|me|tv|info|co\\.in)(?:/\\S*)?)(?: kholo)?$").matchEntire(c)?.let {
            val u = it.groupValues[1].let { s -> if (s.startsWith("http")) s else "https://$s" }
            return listOf(Command.OpenUrl(u))
        }
        return null
    }

    private fun openApp(c: String, ctx: String?): List<Command>? {
        val m = rx("^(?:open|launch|start|run|load|fire up|bring up|show me|show|go to|go on|take me to|switch to|get me to|check|use|i want to check|let me check|navigate to|get on|jump to|go into|open up)(?: up)?(?: the| my)? (.+?)(?: app| application)?(?: (?:app )?for me)?$").matchEntire(c)
            ?: rx("^(.+?)(?: app)? (?:kholo|khol do|kholiye|khol|open karo|open kar do|open kariye|chalu karo|chalao|pe jao|par jao|me jao|mein jao|start karo|launch karo|kholna|kholo na|open)$").matchEntire(c)
            ?: return null
        val name = stripArticles(m.groupValues[1].trim())
        if (name.isBlank()) return null
        if (isKnownApp(name)) return listOf(Command.OpenApp(name))
        // "open youtube search tum hi ho", "open whatsapp the first chat" → open the app, then do the rest there.
        val words = name.split(' ')
        for (k in minOf(3, words.size - 1) downTo 1) {
            val app = words.take(k).joinToString(" ")
            if (!isKnownApp(app)) continue
            val rest = parseClause(words.drop(k).joinToString(" "), app, 1)
            if (rest.isNotEmpty() && rest.none { it is Command.Agentic || it is Command.Chat || it is Command.OpenApp }) return listOf(Command.OpenApp(app)) + rest
        }
        // "open the search", "open menu", "open settings of this video" → tap something on screen
        if (rx("^(?:menu|search|search bar|options|more options|three dots|settings menu|keyboard|camera|gallery)$").matches(name)) {
            return if (name.startsWith("camera") || name.startsWith("gallery")) listOf(Command.OpenApp(name)) else listOf(Command.Tap(name))
        }
        // Unknown name after "open": might be an installed app we have not seen, or something on screen.
        return listOf(Command.OpenApp(name))
    }

    companion object {
        /** A question or small talk (to be answered), as opposed to a rough command (to be done). */
        fun isConversational(text: String): Boolean {
            val l = text.trim().lowercase()
            return QUESTION.containsMatchIn(l) || l.endsWith("?") || CONVERSATION.containsMatchIn(l)
        }
        const val SAME_CONTACT = "__same_contact__"
        private val TEACH_STOP = Regex("^(?:done|stop watching|stop recording|that'?s it|ho gaya|bas ho gaya|finish(?:ed)?)[,.]?\\s*(?:save (?:it|this) as|call it|name it|naam rakho)\\s+(.+)$", RegexOption.IGNORE_CASE)
        private val PAYLOAD_START = Regex("^\\s*(?:(?:please|pls|can you|could you|just|hey nexus)\\s+)*(?:type|write|send|comment|reply|message|text|note|post|likho)\\b|\\b(?:saying|that says)\\b", RegexOption.IGNORE_CASE)
        const val PREVIOUS_APP = "__previous_app__"

        private val REASONING = Regex("\\b(?:tell me (?:if|whether|what|how|about)|let me know|whether|summari[sz]e|should i|figure out|compare|decide|explain (?:it|this)|batao ki|bata do ki)\\b", RegexOption.IGNORE_CASE)
        private val QUESTION = Regex("^(?:what|who|whom|whose|why|how|when|where|which|is|are|am|was|were|(?:do|does|did) (?:you|i|we|they|he|she|it|my|your|u)|can|could|should|would|will|shall|may|might|tell me|explain|define|describe|give me|suggest|recommend|help me|plan|brainstorm|write me|make me|create|calculate|translate|kya|kaun|kaise|kyun|kyon|kab|kahan|kitna|kitne|batao|samjhao)\\b|\\b(?:kya hai|kaise hai|kya hota hai|ka matlab|kitni hai|kitna hai|kitne hai|kitne baje|kaun hai|kab hai|kahan hai|kyun hai)\\b")
        private val CONVERSATION = Regex("^(?:hi|hello|hey|hii+|yo|good (?:morning|afternoon|evening|night)|thanks|thank you|thank u|ok thanks|great|awesome|cool|nice|lol|haha|how are you|namaste|kaise ho|shukriya|dhanyavaad|i love you|you'?re (?:awesome|great|funny)|i'?m (?:bored|sad|happy|tired))\\b")
        private val PHONE_WORDS = Regex("\\b(?:screen|app|button|tap|click|scroll|swipe|notification|setting|settings|wifi|bluetooth|phone|contact|message|call|video|reel|post|comment|tab|menu|install|uninstall|download|upload|photo|gallery|camera|alarm|timer|reminder|calendar|event|schedule|playlist|song)\\b")

        private val MSG_APP_NAMES = setOf("whatsapp", "instagram", "telegram", "messages", "sms", "messenger", "signal", "gmail")
        private val MSG_APPS = Regex("\\s+(?:on|via|using|through|in|par|pe)\\s+(whatsapp|instagram|telegram|messenger|signal|sms|text|messages)\\b", RegexOption.IGNORE_CASE)
    }
}

object Numbers {
    private val words = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "once" to 1, "twice" to 2, "thrice" to 3, "ek" to 1, "do" to 2, "teen" to 3, "char" to 4, "chaar" to 4, "paanch" to 5, "panch" to 5,
        "chhe" to 6, "che" to 6, "saat" to 7, "aath" to 8, "nau" to 9, "das" to 10,
    )
    private val ordinals = mapOf(
        "first" to 1, "1st" to 1, "top" to 1, "pehla" to 1, "pahla" to 1, "pehli" to 1, "pehle" to 1,
        "second" to 2, "2nd" to 2, "doosra" to 2, "dusra" to 2, "doosri" to 2, "dusri" to 2,
        "third" to 3, "3rd" to 3, "teesra" to 3, "tisra" to 3, "teesri" to 3,
        "fourth" to 4, "4th" to 4, "chautha" to 4, "fifth" to 5, "5th" to 5, "paanchva" to 5,
        "sixth" to 6, "6th" to 6, "seventh" to 7, "7th" to 7, "eighth" to 8, "8th" to 8, "ninth" to 9, "9th" to 9, "tenth" to 10, "10th" to 10,
        "last" to -1, "aakhri" to -1, "akhri" to -1,
        "eleventh" to 11, "twelfth" to 12, "thirteenth" to 13, "fourteenth" to 14, "fifteenth" to 15, "sixteenth" to 16,
        "seventeenth" to 17, "eighteenth" to 18, "nineteenth" to 19, "twentieth" to 20,
        "teesre" to 3, "tisre" to 3, "doosre" to 2, "dusre" to 2, "chauthe" to 4, "paanchve" to 5, "panchve" to 5, "chhathe" to 6,
        "chauthi" to 4, "paanchvi" to 5, "panchva" to 5, "panchvi" to 5, "chhatha" to 6, "chhathi" to 6, "chhata" to 6, "chatha" to 6, "chhatva" to 6,
        "saatva" to 7, "saatvi" to 7, "satva" to 7, "aathva" to 8, "aathvi" to 8, "nauva" to 9, "nauvi" to 9, "dasva" to 10, "dasvi" to 10,
    )

    fun parse(s: String?): Int? {
        val t = s?.trim()?.lowercase() ?: return null
        if (t.isEmpty()) return null
        return t.toIntOrNull() ?: words[t]
    }

    fun ordinal(s: String?): Int? = s?.trim()?.lowercase()?.let { w ->
        ordinals[w] ?: Regex("^(\\d{1,3})(?:st|nd|rd|th|va|vi|wa|wi)$").matchEntire(w)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
    }
}
