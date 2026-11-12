package ai.nexus.core.agent

import ai.nexus.core.device.Contact
import ai.nexus.core.device.DeviceController
import ai.nexus.core.device.Direction
import ai.nexus.core.device.GlobalAction
import ai.nexus.core.device.MediaKey
import ai.nexus.core.device.SettingsScreen
import ai.nexus.core.device.SysResult
import ai.nexus.core.device.SystemActions
import ai.nexus.core.memory.LongTermMemory
import ai.nexus.core.memory.MemoryKind
import ai.nexus.core.memory.PendingChoice
import ai.nexus.core.memory.WorkingContext
import ai.nexus.core.nlu.Command
import ai.nexus.core.nlu.CommandDsl
import ai.nexus.core.nlu.Control
import ai.nexus.core.nlu.Fuzzy
import ai.nexus.core.nlu.IntentParser
import ai.nexus.core.nlu.Level
import ai.nexus.core.nlu.Numbers
import ai.nexus.core.nlu.ParsedStep
import ai.nexus.core.nlu.Reaction
import ai.nexus.core.nlu.Section
import ai.nexus.core.nlu.TextNormalizer
import ai.nexus.core.screen.Packages
import ai.nexus.core.screen.ScreenClassifier
import ai.nexus.core.screen.ScreenKind
import ai.nexus.core.skills.AppSkill
import ai.nexus.core.skills.ContactLookup
import ai.nexus.core.skills.GenericSkill
import ai.nexus.core.skills.InstagramSkill
import ai.nexus.core.skills.SkillEnv
import ai.nexus.core.skills.WhatsAppSkill
import ai.nexus.core.skills.YouTubeSkill
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * NEXUS: understands a request, plans it as steps, and carries them out on
 * the phone with observe → act → verify, keeping context across steps and
 * across requests. Falls back to the LLM agent (when configured) for
 * screens and requests the built-in skills don't cover.
 */
class Nexus(
    private val device: DeviceController,
    private val system: SystemActions,
    val memory: LongTermMemory,
    @Volatile var brain: Brain?,
    private val scope: CoroutineScope,
    private val skills: List<AppSkill> = listOf(YouTubeSkill(), InstagramSkill(), WhatsAppSkill()),
) {
    /** Free on-device model for rough commands (null until downloaded/loaded). */
    @Volatile var phoneBrain: PhoneBrain? = null

    val ctx = WorkingContext()
    val apps = AppDirectory(device, memory)
    val parser = IntentParser { apps.isKnown(it) }
    private val ops = Ops(device, ctx)
    private val generic = GenericSkill()
    private val env = SkillEnv(ops, system, ctx, apps, memory, ::lookupContact)

    private val _events = MutableSharedFlow<NexusEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<NexusEvent> = _events
    private val _state = MutableStateFlow(AgentState.IDLE)
    val state: StateFlow<AgentState> = _state

    private val paused = MutableStateFlow(false)
    private var job: Job? = null
    private val requestLock = Mutex()
    /** Steps left when a task was stopped, for "continue". */
    private var interruptedSteps: List<ParsedStep> = emptyList()

    // ================================================================ input

    /** Entry point from voice/text UI. Control words act immediately, even mid-task. */
    fun submit(utterance: String): Job = scope.launch { process(utterance) }

    suspend fun process(utterance: String) {
        val text = utterance.trim()
        if (text.isEmpty()) return
        val parsed = parser.parse(text)

        // Stop / wait / continue must work while a task is running.
        if (parsed.isControl) {
            handleControl((parsed.steps[0].command as Command.ControlTask).control)
            return
        }

        if (job?.isActive == true && !paused.value) {
            // A new instruction while working replaces the current task – unless it corrects it.
            if (parsed.steps.firstOrNull()?.command !is Command.Correction) job?.cancel()
        }

        requestLock.withLock {
            ctx.addTurn("user", text)
            memory.recordTurn("user", text)

            ctx.pendingChoice?.let { pending ->
                val idx = matchChoice(text, pending)
                if (idx != null) {
                    ctx.pendingChoice = null
                    runJob { pending.onChoose(idx) }
                    return@withLock
                }
                if (pending.options.isEmpty() && parsed.steps.all { it.command is Command.Chat || it.command is Command.Agentic }) {
                    // Free-text answer to "What should I say?"
                    ctx.pendingChoice = null
                    pendingAnswer = text
                    runJob { pending.onChoose(-1) }
                    return@withLock
                }
                ctx.pendingChoice = null
            }

            // User-defined shortcut: "good night" → "turn off wifi and set brightness to 10".
            val norm = TextNormalizer.normalize(text)
            val key = Fuzzy.normalize(TextNormalizer.stripFillers(norm))
            val expanded = memory.shortcut(norm) ?: memory.shortcut(key)
            var request = if (expanded != null) parser.parse(expanded) else parsed
            if (request.steps.isEmpty()) return@withLock

            // A routine taught with "watch me": "do morning routine" / "morning routine".
            routineFor(key)?.let { cmds ->
                ctx.lastUtterance = text
                runJob { runSteps(cmds.map { ParsedStep(it, CommandDsl.toLine(it) ?: "", 1.0) }) }
                return@withLock
            }

            // Only the parts the rules didn't understand get help: a phrase learned
            // before (instant), else the phone brain — never the whole request.
            val learnPairs = ArrayList<Pair<String, List<Command>>>()
            val cloudReady = brain != null && memory.settings.useCloudAi
            fun unclearStep(st: ParsedStep) = st.command is Command.Agentic || st.confidence < 0.55 ||
                (st.command is Command.Chat && !cloudReady && !OfflineChat.handles(st.source) && !Calc.looksLikeMath(st.source))
            if (request.steps.any(::unclearStep)) {
                val pb = phoneBrain?.takeIf { memory.settings.usePhoneBrain }
                val newSteps = ArrayList<ParsedStep>()
                for (st in request.steps) {
                    if (!unclearStep(st)) { newSteps += st; continue }
                    val clauseKey = Fuzzy.normalize(st.source)
                    val learned = (memory.lookup(MemoryKind.LEARNED, clauseKey) ?: memory.lookup(MemoryKind.LEARNED, key))
                        ?.let { CommandDsl.parse(it) }?.takeIf { it.isNotEmpty() }
                    if (learned != null) { learned.forEach { newSteps += ParsedStep(it, st.source, 0.95) }; continue }
                    val isQuestion = st.command is Command.Chat && cloudReady
                    if (pb != null && !isQuestion) {
                        setState(AgentState.THINKING)
                        val translated = kotlinx.coroutines.withTimeoutOrNull(PHONE_BRAIN_TIMEOUT_MS) {
                            runCatching { pb.translate(st.source, ops.screen()) }.getOrNull()
                        }?.filter { it !is Command.Chat && it !is Command.Agentic }
                        if (!translated.isNullOrEmpty()) {
                            translated.forEach { newSteps += ParsedStep(it, st.source, 0.8) }
                            learnPairs += clauseKey to translated
                            continue
                        }
                    }
                    newSteps += st
                }
                setState(AgentState.IDLE)
                request = ai.nexus.core.nlu.ParsedRequest(text, norm, newSteps)
                if (request.isChat) { runJob { chat(text) }; return@withLock }
            }

            if (request.steps.size == 1 && request.steps[0].command == Command.Repeat) {
                if (ctx.lastRequestCommands.isEmpty()) say("There's nothing to repeat yet.")
                else runJob { runSteps(ctx.lastRequestCommands.map { ParsedStep(it, it.toString(), 0.9) }) }
                return@withLock
            }

            if (request.isChat) {
                runJob { chat(text) }
                return@withLock
            }
            ctx.lastUtterance = text
            val finalSteps = request.steps
            runJob {
                val ok = runSteps(finalSteps)
                // Remember what a rough phrase meant, so next time it's instant.
                if (ok) for ((k, cmds) in learnPairs) if (k.length > 2) memory.remember(MemoryKind.LEARNED, k, CommandDsl.toLines(cmds))
            }
        }
        job?.join()
    }

    private fun runJob(block: suspend () -> Unit) {
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(NexusEvent.Error(e.message ?: e.toString()))
                say("Something went wrong: ${e.message ?: "unknown error"}")
            } finally {
                if (_state.value != AgentState.WAITING_FOR_USER) setState(AgentState.IDLE)
            }
        }
    }

    private suspend fun handleControl(c: Control) {
        when (c) {
            Control.STOP, Control.CANCEL -> {
                val wasRunning = job?.isActive == true
                job?.cancel(); paused.value = false; ctx.pendingChoice = null
                setState(AgentState.IDLE)
                say(if (wasRunning) "Stopped." else "Okay.")
            }
            Control.PAUSE -> if (job?.isActive == true) {
                paused.value = true; setState(AgentState.PAUSED)
                say("Paused. Say \"continue\" when you're ready.")
            } else {
                system.mediaKey(MediaKey.PAUSE); say("Paused.", speak = false)
            }
            Control.RESUME -> when {
                paused.value -> { paused.value = false; setState(AgentState.EXECUTING); say("Continuing.", speak = false) }
                interruptedSteps.isNotEmpty() -> {
                    val rest = interruptedSteps; interruptedSteps = emptyList()
                    say("Continuing where I left off.")
                    runJob { runSteps(rest) }
                }
                else -> { system.mediaKey(MediaKey.PLAY); say("Resumed.", speak = false) }
            }
            Control.WRONG -> {
                job?.cancel()
                val last = ctx.lastCommand
                if (last != null && navigates(last)) { device.global(GlobalAction.BACK); ops.invalidate() }
                say("Sorry about that. What did you mean?")
            }
            Control.UNDO_LAST -> {
                when (val last = ctx.lastCommand) {
                    is Command.React -> runJob { report(runCommand(last.copy(on = !last.on)), "undo") }
                    null -> say("There's nothing to undo.")
                    else -> { device.global(GlobalAction.BACK); say("Went back.") }
                }
            }
            Control.REPEAT -> {}
        }
    }

    private fun matchChoice(text: String, p: PendingChoice): Int? {
        if (p.options.isEmpty()) return null
        val t = Fuzzy.normalize(text)
        if (p.options.size == 2 && p.options[0] == "yes") {
            if (Regex("^(?:yes|yeah|yep|sure|ok|okay|haan|ha|han|ji|send it|do it|go ahead|confirm)\\b").containsMatchIn(t)) return 0
            if (Regex("^(?:no|nope|nahi|na|cancel|don'?t|stop)\\b").containsMatchIn(t)) return 1
            return null
        }
        Regex("(first|second|third|fourth|fifth|last|1st|2nd|3rd|pehla|doosra|dusra|teesra|\\d)").find(t)?.let { m ->
            val n = Numbers.ordinal(m.value) ?: m.value.toIntOrNull()
            if (n != null) { val i = if (n < 0) p.options.size - 1 else n - 1; if (i in p.options.indices) return i }
        }
        val best = p.options.withIndex().maxByOrNull { (_, o) -> Fuzzy.similarity(o, t).let { if (Fuzzy.normalize(o).contains(t) || t.contains(Fuzzy.normalize(o))) maxOf(it, 0.9) else it } }
        return best?.takeIf { (_, o) -> Fuzzy.similarity(o, t) > 0.6 || Fuzzy.normalize(o).contains(t) || t.contains(Fuzzy.normalize(o)) }?.index
    }

    @Volatile private var pendingAnswer: String? = null

    private fun ask(question: String, options: List<String> = emptyList(), onChoose: suspend (Int) -> Unit) {
        ctx.pendingChoice = PendingChoice(question, options, onChoose)
        setState(AgentState.WAITING_FOR_USER)
        say(question)
    }

    // ================================================================ steps

    /** Runs the steps; true when every step succeeded. */
    private suspend fun runSteps(steps: List<ParsedStep>): Boolean {
        setState(AgentState.EXECUTING)
        val notes = ArrayList<String>()
        val total = steps.size
        val executed = ArrayList<Command>()
        for ((i, step) in steps.withIndex()) {
            awaitNotPaused()
            val cmd = resolveReferences(step.command)
            val desc = describe(cmd)
            emit(NexusEvent.StepStarted(i + 1, total, desc))
            interruptedSteps = steps.drop(i)

            // Screen first: if the thing named is right there on screen, just tap it.
            val onScreen = screenFirst(cmd)
            if (onScreen != null) {
                emit(NexusEvent.StepFinished(i + 1, desc, onScreen))
                ctx.lastCommand = cmd; executed += cmd
                if (onScreen !is Outcome.Success) notes += onScreen.message.orEmpty()
                continue
            }

            // Ask first when a contact name is ambiguous, then carry on with the rest.
            contactNeedingChoice(cmd)?.let { (name, options) ->
                val rest = steps.drop(i + 1)
                ask("I found ${options.size} contacts named $name: ${options.joinToString { it.name }}. Which one?", options.map { it.name }) { idx ->
                    val chosen = options[idx]
                    ctx.currentContact = chosen; ctx.currentContactName = chosen.name
                    runSteps(listOf(ParsedStep(withContact(cmd, chosen.name), step.source, 1.0)) + rest)
                }
                interruptedSteps = emptyList()
                return false
            }

            var outcome = runCommand(cmd, step.source)
            if (outcome is Outcome.NeedsAgent) outcome = agentFallback(step.source, cmd, outcome)
            emit(NexusEvent.StepFinished(i + 1, desc, outcome))
            ctx.lastCommand = cmd
            executed += cmd

            when (outcome) {
                is Outcome.Success -> outcome.message?.let { say(it) }
                is Outcome.Uncertain -> notes += outcome.message
                is Outcome.Asked -> {
                    // e.g. "What should I say?" – resume this step with the answer.
                    val rest = steps.drop(i + 1)
                    ask(outcome.message) { _ ->
                        val answer = pendingAnswer.orEmpty(); pendingAnswer = null
                        runSteps(listOf(ParsedStep(withText(cmd, answer), step.source, 1.0)) + rest)
                    }
                    interruptedSteps = emptyList()
                    return false
                }
                is Outcome.Failed -> {
                    val later = steps.drop(i + 1)
                    if (outcome.blocking && navigates(cmd) && later.isNotEmpty()) {
                        say(outcome.message + " So I stopped there.")
                        interruptedSteps = emptyList()
                        ctx.lastRequestCommands = executed
                        return false
                    }
                    notes += outcome.message
                }
                is Outcome.NeedsAgent -> notes += outcome.message
            }
        }
        interruptedSteps = emptyList()
        ctx.lastRequestCommands = steps.map { it.command }
        when {
            notes.isNotEmpty() -> say(if (total > 1) "Done, with a note: " + notes.joinToString(" ") else notes.joinToString(" "))
            total > 1 -> acknowledge("Done.")
            else -> if (needsAck(steps[0].command)) acknowledge(ack(steps[0].command))
        }
        return notes.isEmpty()
    }

    /** Short confirmations: a tick/vibration (quiet mode) instead of speech, to stay fast. */
    private fun acknowledge(text: String) {
        if (memory.settings.quietAcks) { ctx.addTurn("assistant", text); emit(NexusEvent.Ack(text)) } else say(text)
    }

    // ================================================================ screen first

    private val qualifiers = Regex(
        "\\b(?:the|group|groups|chat|chats|conversation|song|gaana|gana|video|playlist|channel|album|folder|contact|tab|option|button|wala|wali|vala|vali|waala|waali|wale|wali|app|one|item|link|page|section|ka|ki|ke)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * "Open the editorial group" in WhatsApp, "play Believer" with the song in
     * view, "open Rahul" with his chat on screen: tap what's visible instead
     * of launching apps or searching. Returns null when nothing visible matches.
     */
    private suspend fun screenFirst(cmd: Command): Outcome? {
        val name = when (cmd) {
            is Command.OpenApp -> cmd.app.takeIf { it != IntentParser.PREVIOUS_APP }
            is Command.OpenChat -> cmd.contact?.takeIf { it != IntentParser.SAME_CONTACT }
            is Command.PlayMedia -> cmd.query.takeIf { cmd.app == null }
            is Command.Search -> null
            else -> null
        } ?: return null
        val s = ops.screen() ?: return null
        val kind = ScreenClassifier.classify(s)
        val hasQualifier = qualifiers.containsMatchIn(name)
        // A plain app name: launching is faster and more reliable than finding its icon.
        if (cmd is Command.OpenApp && (kind == ScreenKind.LAUNCHER || (apps.isKnown(name) && !hasQualifier))) return null
        val cleaned = name.replace(qualifiers, " ").replace(Regex("\\s+"), " ").trim().ifBlank { name }
        val match = ai.nexus.core.screen.ElementFinder(s).rankByLabel(listOf(cleaned, name))
            .firstOrNull { m -> !m.node.editable && m.score >= 0.85 && s.clickableTarget(m.node).clickable && m.node.bounds.top > s.screenHeight / 30 }
            ?: return null
        val label = match.node.label
        val after = ops.tap(match.node, s) ?: return Outcome.Failed("\"$label\" didn't respond to a tap.")
        if (cmd is Command.OpenChat || ScreenClassifier.classify(after) in setOf(ScreenKind.WA_CHAT, ScreenKind.IG_DM_THREAD)) {
            ctx.currentContactName = label; ctx.currentChatApp = after.packageName
        }
        if (cmd is Command.PlayMedia) ctx.currentItem = "song: $label"
        return Outcome.Success()
    }

    // ================================================================ teaching ("watch me")

    @Volatile private var recording: MutableList<Command>? = null
    @Volatile private var recordingLastPkg: String? = null
    val isTeaching: Boolean get() = recording != null

    /** Fed by the accessibility service while teaching: what the user tapped or typed. */
    fun recordUserAction(pkg: String, label: String?, typed: String?) {
        val rec = recording ?: return
        synchronized(rec) {
            val launcher = pkg in ai.nexus.core.screen.Packages.LAUNCHERS
            if (typed != null) {
                if (rec.lastOrNull() is Command.Write) rec.removeAt(rec.lastIndex)
                rec += Command.Write(typed, false)
                return
            }
            val l = label?.trim()?.takeIf { it.isNotBlank() && it.length <= 60 } ?: return
            if (launcher) rec += Command.OpenApp(l)
            else if (Fuzzy.normalize(l) in setOf("send", "post", "reply") && rec.lastOrNull() is Command.Write) {
                val w = rec.removeAt(rec.lastIndex) as Command.Write
                rec += w.copy(submit = true)
            } else rec += Command.Tap(l)
            recordingLastPkg = pkg
        }
    }

    /** Called when the foreground app changes during teaching. */
    fun recordAppSwitch(pkg: String, appLabel: String?) {
        val rec = recording ?: return
        if (pkg in ai.nexus.core.screen.Packages.LAUNCHERS || pkg == recordingLastPkg) return
        synchronized(rec) {
            if (rec.lastOrNull() !is Command.OpenApp && appLabel != null) rec += Command.OpenApp(appLabel)
            recordingLastPkg = pkg
        }
    }

    private fun routineFor(key: String): List<Command>? {
        val k = key.removePrefix("run ").removePrefix("do ").removePrefix("start ").removeSuffix(" karo").removeSuffix(" chalao").trim()
        val dsl = memory.lookup(MemoryKind.ROUTINE, k) ?: memory.lookup(MemoryKind.ROUTINE, k.removeSuffix(" routine")) ?: return null
        return CommandDsl.parse(dsl).takeIf { it.isNotEmpty() }
    }

    private fun saveRoutine(name: String, cmds: List<Command>): Outcome {
        val key = Fuzzy.normalize(name)
        memory.remember(MemoryKind.ROUTINE, key, CommandDsl.toLines(cmds))
        return Outcome.Success("Saved \"$name\" with ${cmds.size} steps. Just say \"$name\" to run it.")
    }

    private suspend fun awaitNotPaused() {
        if (paused.value) {
            setState(AgentState.PAUSED)
            paused.first { !it }
            setState(AgentState.EXECUTING)
        }
    }

    private fun navigates(c: Command) = c is Command.OpenApp || c is Command.GoTo || c is Command.OpenResult || c is Command.OpenChat ||
        c is Command.Search || c is Command.OpenComments || c is Command.Tap || c is Command.OpenUrl || c is Command.FindFile

    private fun resolveReferences(c: Command): Command {
        fun alias(name: String?): String? = name?.let { n ->
            if (n == IntentParser.SAME_CONTACT) ctx.currentContact?.name ?: ctx.currentContactName ?: n
            else memory.contactAlias(n) ?: n
        }
        return when (c) {
            is Command.SendMessage -> c.copy(contact = alias(c.contact) ?: ctx.currentContactName)
            is Command.OpenChat -> c.copy(contact = alias(c.contact))
            is Command.Call -> c.copy(contact = alias(c.contact))
            is Command.ReadMessages -> c.copy(contact = alias(c.contact))
            is Command.FindContact -> c.copy(name = alias(c.name) ?: c.name)
            is Command.PlayMedia -> c.copy(query = memory.preference(c.query) ?: c.query)
            is Command.Search -> c.copy(query = memory.preference(c.query) ?: c.query)
            is Command.OpenApp -> if (c.app == IntentParser.PREVIOUS_APP) c else c.copy(app = memory.appAlias(c.app) ?: c.app)
            else -> c
        }
    }

    private fun withContact(c: Command, name: String): Command = when (c) {
        is Command.SendMessage -> c.copy(contact = name)
        is Command.OpenChat -> c.copy(contact = name)
        is Command.Call -> c.copy(contact = name)
        is Command.ReadMessages -> c.copy(contact = name)
        is Command.FindContact -> c.copy(name = name)
        else -> c
    }

    @Volatile private var pendingRoutine: List<Command>? = null

    private fun humanDuration(sec: Int): String = when {
        sec % 3600 == 0 -> "${sec / 3600} hour${if (sec == 3600) "" else "s"}"
        sec >= 60 && sec % 60 == 0 -> "${sec / 60} minute${if (sec == 60) "" else "s"}"
        sec > 60 -> "${sec / 60} min ${sec % 60} sec"
        else -> "$sec seconds"
    }

    private fun withText(c: Command, text: String): Command = when (c) {
        is Command.TeachStop -> { pendingRoutine?.let { r -> pendingRoutine = null; recording = r.toMutableList() }; c.copy(name = text) }
        is Command.SendMessage -> if (c.text == null) c.copy(text = text) else c.copy(contact = text)
        is Command.OpenChat -> c.copy(contact = text)
        is Command.Write -> c.copy(text = text)
        is Command.Call -> c.copy(contact = text)
        is Command.Email -> if (c.to == null) c.copy(to = text) else c.copy(body = text)
        else -> c
    }

    // ================================================================ contacts

    private suspend fun lookupContact(name: String): ContactLookup {
        val aliased = memory.contactAlias(name) ?: name
        val found = runCatching { system.contacts(aliased) }.getOrDefault(emptyList())
        if (found.isEmpty()) return ContactLookup.NotFound
        val n = Fuzzy.normalize(aliased)
        found.filter { Fuzzy.normalize(it.name) == n }.let { if (it.size == 1) return ContactLookup.Found(it[0]) }
        if (found.size == 1) return ContactLookup.Found(found[0])
        // A remembered choice from last time ("Rahul" → "Rahul Sharma").
        ctx.currentContact?.takeIf { cur -> found.any { it.id == cur.id } && Fuzzy.normalize(cur.name).startsWith(n) }?.let { return ContactLookup.Found(it) }
        return ContactLookup.Ambiguous(found.take(5))
    }

    private suspend fun contactNeedingChoice(c: Command): Pair<String, List<Contact>>? {
        val name = when (c) {
            is Command.Call -> c.contact
            is Command.FindContact -> c.name
            is Command.SendMessage -> if (targetMessagingApp(c.app) in setOf("sms", Packages.WHATSAPP, Packages.WHATSAPP_BUSINESS)) c.contact else null
            is Command.OpenChat -> if (targetMessagingApp(c.app) in setOf(Packages.WHATSAPP, Packages.WHATSAPP_BUSINESS)) c.contact else null
            else -> null
        } ?: return null
        if (name == IntentParser.SAME_CONTACT) return null
        val r = lookupContact(name)
        return if (r is ContactLookup.Ambiguous) name to r.options else null
    }

    // ================================================================ routing

    private suspend fun runCommand(cmd: Command, source: String = ""): Outcome = when (cmd) {
        is Command.OpenApp -> openApp(cmd.app)
        is Command.Navigate -> generic.handle(cmd, env)!!
        is Command.Call -> call(cmd)
        Command.EndCall -> sys(system.endCall())
        is Command.FindContact -> when (val r = lookupContact(cmd.name)) {
            is ContactLookup.Found -> { ctx.currentContact = r.contact; ctx.currentContactName = r.contact.name
                Outcome.Success("${r.contact.name}: ${r.contact.numbers.joinToString(", ").ifBlank { "no number saved" }}.") }
            is ContactLookup.Ambiguous -> Outcome.Success("I found: " + r.options.joinToString("; ") { "${it.name} ${it.numbers.firstOrNull() ?: ""}" })
            ContactLookup.NotFound -> Outcome.Failed("I couldn't find ${cmd.name} in your contacts.", blocking = false)
        }
        is Command.SetToggle -> sys(system.setToggle(cmd.setting, cmd.on))
        is Command.SetLevel -> {
            val pct = cmd.percent ?: cmd.delta ?: 0
            sys(if (cmd.level == Level.VOLUME) system.setVolume(pct, relative = cmd.percent == null) else system.setBrightness(pct, relative = cmd.percent == null))
        }
        is Command.OpenSettings -> {
            val pkg = cmd.app?.let { apps.resolve(it)?.packageName }
            sys(system.openSettings(cmd.screen, pkg))
        }
        is Command.Media -> sys(system.mediaKey(cmd.key)).let { if (it is Outcome.Success) Outcome.Success() else it }
        is Command.PlayMedia -> playMedia(cmd)
        is Command.Seek -> sys(system.seek(cmd.seconds))
        is Command.ReadNotifications -> readNotifications(cmd.app)
        is Command.FindFile -> findFile(cmd)
        is Command.OpenUrl -> if (device.openUri(cmd.url)) { ops.waitFor(4000) { it.packageName != ctx.currentPackage }; Outcome.Success() } else Outcome.Failed("I couldn't open ${cmd.url}.")
        is Command.Email -> email(cmd)
        is Command.Remember -> remember(cmd)
        is Command.Forget -> forget(cmd)
        Command.DescribeScreen -> describeScreen("What is on my screen right now? Summarise it briefly.")
        is Command.ExplainElement -> describeScreen("What does the \"${cmd.label}\" button or option on this screen do?")
        is Command.Chat -> { chat(cmd.text); Outcome.Success() }
        is Command.Agentic -> agentFallback(cmd.goal, cmd, Outcome.NeedsAgent(""))
        is Command.SetAlarm -> sys(system.setAlarm(cmd.hour, cmd.minute, cmd.label)).let {
            if (it is Outcome.Success && it.message == null) Outcome.Success("Alarm set for ${"%d:%02d".format(cmd.hour, cmd.minute)}${cmd.label?.let { l -> " — $l" } ?: ""}.") else it
        }
        is Command.SetTimer -> sys(system.setTimer(cmd.seconds, cmd.label)).let {
            if (it is Outcome.Success && it.message == null) Outcome.Success("Timer set for ${humanDuration(cmd.seconds)}${cmd.label?.let { l -> " — $l" } ?: ""}.") else it
        }
        is Command.Note -> if (memory.remember(MemoryKind.NOTE, null, cmd.text) != null) Outcome.Success("Noted.") else Outcome.Failed("I couldn't save that note.", blocking = false)
        Command.ReadNotes -> memory.items.filter { it.kind == MemoryKind.NOTE }.let { n ->
            Outcome.Success(if (n.isEmpty()) "You have no notes." else "Your notes: " + n.takeLast(10).joinToString(". ") { it.value })
        }
        Command.ClearNotes -> { memory.items.filter { it.kind == MemoryKind.NOTE }.forEach { memory.forget(it.id) }; Outcome.Success("Notes cleared.") }
        is Command.Calculate -> Calc.evaluate(cmd.expression)?.let { Outcome.Success("${cmd.expression.trim()} = ${Calc.format(it)}") }
            ?: Outcome.Failed("I couldn't work that out.", blocking = false)
        Command.TeachStart -> {
            recording = mutableListOf(); recordingLastPkg = ops.screen()?.packageName
            emit(NexusEvent.TeachingChanged(true))
            Outcome.Success("I'm watching. Do the task yourself, then tap me and say \"done, call it\" and a name.")
        }
        is Command.TeachStop -> {
            val rec = recording?.toList()
            recording = null
            emit(NexusEvent.TeachingChanged(false))
            when {
                rec == null -> Outcome.Failed("I wasn't watching. Say \"watch me\" first.", blocking = false)
                rec.isEmpty() -> Outcome.Failed("I didn't see any steps.", blocking = false)
                cmd.name != null -> saveRoutine(cmd.name, rec)
                else -> { pendingRoutine = rec; Outcome.Asked("Got ${rec.size} steps. What should I call it?") }
            }
        }
        Command.HideBubble -> { emit(NexusEvent.HideBubble); Outcome.Success() }
        is Command.Correction -> correction(cmd)
        is Command.ControlTask -> { handleControl(cmd.control); Outcome.Success() }
        Command.Repeat -> Outcome.Success()
        is Command.Shortcut -> Outcome.Success()
        is Command.GoTo -> goTo(cmd)
        is Command.Search -> search(cmd)
        is Command.SendMessage -> sendMessage(cmd)
        is Command.OpenChat -> appSkill(targetMessagingApp(cmd.app))?.let { ensureApp(targetMessagingApp(cmd.app)); it.handle(cmd, env) } ?: screenCommand(cmd)
        is Command.ReadMessages -> readMessages(cmd)
        is Command.Write -> write(cmd)
        else -> screenCommand(cmd)
    }

    /** Commands that act on whatever is on screen: try the app's skill, then the generic one. */
    private suspend fun screenCommand(cmd: Command): Outcome {
        val s = ops.screen() ?: return Outcome.Failed("I can't see the screen. Please turn on the NEXUS accessibility service.")
        appSkill(s.packageName)?.handle(cmd, env)?.let { return it }
        return generic.handle(cmd, env) ?: Outcome.NeedsAgent("I'm not sure how to do that here.")
    }

    private fun appSkill(pkg: String?): AppSkill? = pkg?.let { p -> skills.firstOrNull { p in it.packages } }

    private fun sys(r: SysResult): Outcome = when (r) {
        is SysResult.Done -> Outcome.Success(r.message.ifBlank { null })
        is SysResult.OpenedSettings -> Outcome.Success(r.message)
        is SysResult.NeedsPermission -> Outcome.Failed(r.message, blocking = false)
        is SysResult.Failed -> Outcome.Failed(r.message, blocking = false)
    }

    private suspend fun openApp(name: String): Outcome {
        val pkg = if (name == IntentParser.PREVIOUS_APP) ctx.previousApp() ?: return Outcome.Failed("I don't know which app you were using before.")
        else apps.resolve(name)?.packageName ?: run {
            // Not an installed app: maybe a chat/group/item in the current app.
            val cur = ops.screen()?.packageName
            if (cur in setOf(Packages.WHATSAPP, Packages.WHATSAPP_BUSINESS, Packages.INSTAGRAM)) {
                return appSkill(cur)?.handle(Command.OpenChat(name), env) ?: Outcome.NeedsAgent("I couldn't find $name.")
            }
            return Outcome.NeedsAgent("I couldn't find an app or anything on screen called $name.")
        }
        return ensureApp(pkg, name)
    }

    private suspend fun ensureApp(pkg: String?, label: String? = null): Outcome {
        pkg ?: return Outcome.Failed("Which app?")
        if (pkg == "sms") return Outcome.Success()
        val cur = ops.screen()
        if (cur?.packageName == pkg) return Outcome.Success()
        if (!device.launchApp(pkg)) return Outcome.Failed("I couldn't open ${label ?: pkg}.")
        memory.recordAppLaunch(pkg)
        val ok = ops.waitFor(6000) { it.packageName == pkg }
        return if (ok != null) Outcome.Success() else Outcome.Uncertain("I asked Android to open ${label ?: "the app"}, but it hasn't appeared yet.")
    }

    private suspend fun goTo(cmd: Command.GoTo): Outcome {
        val cur = ops.screen()
        val pkg = cur?.packageName
        // "Go to Reels" from anywhere means Instagram; "Shorts" means YouTube.
        val defaultApp = when (cmd.section) {
            Section.REELS, Section.STORIES -> Packages.INSTAGRAM
            Section.SHORTS -> Packages.YOUTUBE
            else -> null
        }
        val wanted = cmd.app?.let { apps.resolve(it)?.packageName } ?: defaultApp?.takeIf { pkg != it && (cmd.section != Section.STORIES) } 
        if (wanted != null && wanted != pkg) {
            val r = ensureApp(wanted)
            if (r is Outcome.Failed) return r
        }
        return screenCommand(cmd)
    }

    private suspend fun search(cmd: Command.Search): Outcome {
        val target = cmd.app?.let { apps.resolve(it)?.packageName }
        if (target != null) ensureApp(target)
        val cur = ops.screen()
        val onLauncher = cur == null || ScreenClassifier.classify(cur) == ScreenKind.LAUNCHER
        if (target == null && onLauncher) {
            // Nothing app-specific: search the web.
            val url = "https://www.google.com/search?q=" + URLEncoder.encode(cmd.query, "UTF-8")
            return if (device.openUri(url)) {
                ctx.lastSearchQuery = cmd.query; ctx.lastSearchApp = "web"
                ops.waitFor(5000) { it.packageName != cur?.packageName }
                Outcome.Success()
            } else Outcome.Failed("I couldn't open a browser.")
        }
        return screenCommand(cmd)
    }

    private fun targetMessagingApp(app: String?): String? {
        val a = app?.lowercase()
        return when {
            a == "sms" || a == "text" || a == "messages" -> "sms"
            a == "whatsapp" -> Packages.WHATSAPP
            a == "instagram" -> Packages.INSTAGRAM
            a != null -> BuiltInPackages.of(a)
            ctx.currentPackage in setOf(Packages.WHATSAPP, Packages.WHATSAPP_BUSINESS, Packages.INSTAGRAM) -> ctx.currentPackage
            ctx.currentChatApp != null -> ctx.currentChatApp
            memory.preference("messaging app")?.lowercase()?.contains("sms") == true -> "sms"
            else -> Packages.WHATSAPP
        }
    }

    private suspend fun sendMessage(cmd: Command.SendMessage): Outcome {
        val app = targetMessagingApp(cmd.app)
        val text = cmd.text ?: return Outcome.Asked("What should I say${cmd.contact?.let { " to $it" } ?: ""}?")
        if (cmd.send && memory.settings.confirmBeforeSending && pendingConfirmed != cmd) {
            val who = cmd.contact ?: "them"
            ask("Send \"$text\" to $who?", listOf("yes", "no")) { idx ->
                if (idx == 0) { pendingConfirmed = cmd; report(sendMessage(cmd), "send") } else say("Okay, not sending it.")
            }
            return Outcome.Success()
        }
        pendingConfirmed = null
        // Fastest path: reply straight from their notification, without opening the app.
        val name = cmd.contact
        if (cmd.send && name != null && name != IntentParser.SAME_CONTACT && cmd.app == null &&
            !(ctx.currentScreen == ScreenKind.WA_CHAT && ctx.currentContactName?.contains(name, true) == true)
        ) {
            runCatching { system.replyToNotification(name, text) }.getOrNull()?.let { r ->
                if (r is SysResult.Done) { ctx.currentContactName = name; return Outcome.Success() }
            }
        }
        if (app == "sms") {
            val name = cmd.contact ?: return Outcome.Asked("Who should I text?")
            val c = (lookupContact(name) as? ContactLookup.Found)?.contact
            val number = c?.numbers?.firstOrNull() ?: name.filter { it.isDigit() || it == '+' }.takeIf { it.length >= 6 }
                ?: return Outcome.Failed("I couldn't find a number for $name.")
            c?.let { ctx.currentContact = it; ctx.currentContactName = it.name }
            return sys(if (cmd.send) system.sendSms(number, text) else system.composeSms(number, text))
        }
        val skill = appSkill(app) ?: return screenCommand(cmd)
        if (app != Packages.WHATSAPP && app != Packages.WHATSAPP_BUSINESS) {
            val r = ensureApp(app); if (r is Outcome.Failed) return r
        }
        return skill.handle(cmd, env) ?: screenCommand(cmd)
    }

    @Volatile private var pendingConfirmed: Command.SendMessage? = null

    private suspend fun readMessages(cmd: Command.ReadMessages): Outcome {
        val app = targetMessagingApp(cmd.app)
        if (app == "sms" || (cmd.app == null && cmd.contact == null && ctx.currentPackage !in setOf(Packages.WHATSAPP, Packages.INSTAGRAM))) {
            val ns = system.recentNotifications(30).filter { n -> cmd.contact == null || n.title.contains(cmd.contact, true) }
            if (ns.isNotEmpty()) return Outcome.Success(ns.take(5).joinToString(". ") { "${it.appLabel}, ${it.title}: ${it.text}" })
        }
        appSkill(app)?.let { ensureApp(app); it.handle(cmd, env)?.let { o -> return o } }
        return screenCommand(Command.ReadScreen)
    }

    private suspend fun write(cmd: Command.Write): Outcome {
        val s = ops.screen() ?: return Outcome.Failed("I can't see the screen.")
        val kind = ScreenClassifier.classify(s)
        // "Write amazing video" while watching → it's a comment.
        if (s.editableFields().isEmpty() && kind in setOf(ScreenKind.YT_WATCH, ScreenKind.YT_SHORTS, ScreenKind.IG_REELS, ScreenKind.IG_FEED)) {
            val opened = screenCommand(Command.OpenComments)
            if (opened is Outcome.Failed) return opened
            return screenCommand(cmd.copy(submit = cmd.submit ?: true))
        }
        if (kind == ScreenKind.WA_CHAT || kind == ScreenKind.IG_DM_THREAD) {
            return sendMessage(Command.SendMessage(null, cmd.text, app = null, send = cmd.submit ?: false))
        }
        return screenCommand(cmd)
    }

    private suspend fun call(cmd: Command.Call): Outcome {
        val number = cmd.number ?: run {
            val name = cmd.contact ?: ctx.currentContact?.name ?: return Outcome.Asked("Who should I call?")
            val c = when (val r = lookupContact(name)) {
                is ContactLookup.Found -> r.contact
                is ContactLookup.Ambiguous -> r.options.first()
                ContactLookup.NotFound -> return Outcome.Failed("I couldn't find $name in your contacts.")
            }
            ctx.currentContact = c; ctx.currentContactName = c.name
            if (c.numbers.isEmpty()) return Outcome.Failed("${c.name} has no phone number saved.")
            val idx = (cmd.numberIndex ?: 1) - 1
            c.numbers.getOrNull(idx) ?: return Outcome.Failed("${c.name} only has ${c.numbers.size} number${if (c.numbers.size == 1) "" else "s"}.")
        }
        return sys(system.call(number))
    }

    private suspend fun playMedia(cmd: Command.PlayMedia): Outcome {
        val pkg = cmd.app?.let { apps.resolve(it)?.packageName }
            ?: ctx.currentPackage?.takeIf { it == Packages.SPOTIFY || it == Packages.YT_MUSIC || it == Packages.YOUTUBE }
            ?: memory.preference("music app")?.let { apps.resolve(it)?.packageName }
        if (pkg == Packages.YOUTUBE) {
            val r = runCommand(Command.Search(cmd.query, "YouTube"))
            if (r !is Outcome.Success) return r
            return runCommand(Command.OpenResult(1, "video"))
        }
        val r = sys(system.playFromSearch(cmd.query, pkg))
        if (r is Outcome.Success) ctx.currentItem = "song: ${cmd.query}"
        return if (r is Outcome.Success) Outcome.Success() else r
    }

    private suspend fun readNotifications(app: String?): Outcome {
        val list = system.recentNotifications(25).filter { n -> app == null || n.appLabel.contains(app, true) || n.packageName.contains(app.lowercase()) }
        if (list.isEmpty()) return Outcome.Success(if (app == null) "You have no new notifications." else "No notifications from $app.")
        val text = list.take(6).joinToString(". ") { "${it.appLabel}: ${it.title}${if (it.text.isNotBlank()) " — ${it.text}" else ""}" }
        return Outcome.Success("You have ${list.size} notification${if (list.size == 1) "" else "s"}. $text")
    }

    private suspend fun findFile(cmd: Command.FindFile): Outcome {
        val q = cmd.query.replace(Regex("\\b(yesterday|today|last week|recently)\\b"), "").trim()
        var files = system.searchFiles(q, cmd.mimePrefix, 20)
        val now = system.nowMs()
        val day = 24 * 3600_000L
        when {
            cmd.query.contains("yesterday") -> files = files.filter { now - it.modifiedMs in day..(2 * day + 6 * 3600_000L) }.ifEmpty { files }
            cmd.query.contains("today") -> files = files.filter { now - it.modifiedMs < day }.ifEmpty { files }
            cmd.query.contains("last week") -> files = files.filter { now - it.modifiedMs < 8 * day }.ifEmpty { files }
        }
        files = files.sortedByDescending { it.modifiedMs }
        if (files.isEmpty()) return Outcome.Failed("I couldn't find a matching file.", blocking = true)
        val f = files.first()
        ctx.currentItem = "file: ${f.name}"
        val opened = sys(system.openFile(f))
        return if (opened is Outcome.Success) Outcome.Success(if (files.size > 1) "Opening ${f.name}, the most recent of ${files.size} matches." else "Opening ${f.name}.") else opened
    }

    private suspend fun email(cmd: Command.Email): Outcome {
        val to = cmd.to?.let { name ->
            if (name.contains('@')) name else system.contacts(name).firstOrNull()?.name ?: name
        }
        val r = sys(system.composeEmail(to, cmd.subject, cmd.body))
        if (r !is Outcome.Success || !cmd.send) return if (r is Outcome.Success) Outcome.Success("The email draft is ready — say \"send\" to send it.") else r
        ops.waitFor(5000) { ScreenClassifier.classify(it) == ScreenKind.EMAIL_COMPOSE }
        return ops.submit(null, listOf("Send"), listOf("send"))
    }

    private suspend fun remember(cmd: Command.Remember): Outcome {
        if (!memory.enabled) return Outcome.Failed("Memory is turned off. You can turn it on in settings.", blocking = false)
        if (LongTermMemory.looksSensitive(cmd.value)) return Outcome.Failed("That looks like sensitive information, so I won't store it.", blocking = false)
        val key = cmd.key
        val item = when {
            key == null -> memory.remember(MemoryKind.FACT, null, cmd.value)
            apps.isKnown(cmd.value) -> memory.remember(MemoryKind.APP_ALIAS, key, cmd.value)
            parser.parse(cmd.value).steps.let { st -> st.isNotEmpty() && st.none { it.command is Command.Chat || it.command is Command.Agentic } && cmd.value.split(' ').size > 2 } ->
                memory.remember(MemoryKind.SHORTCUT, TextNormalizer.normalize(key), cmd.value)
            system.contacts(cmd.value).isNotEmpty() -> memory.remember(MemoryKind.CONTACT_ALIAS, key, cmd.value)
            key.lowercase().startsWith("my ") -> memory.remember(MemoryKind.PREFERENCE, key, cmd.value)
            else -> memory.remember(MemoryKind.FACT, key, "${key} is ${cmd.value}")
        }
        return if (item != null) Outcome.Success("Got it, I'll remember that.") else Outcome.Failed("I couldn't save that.", blocking = false)
    }

    private fun forget(cmd: Command.Forget): Outcome {
        if (cmd.what == null) { memory.clearAll(); ctx.conversation.clear(); return Outcome.Success("Done — I've cleared my memory.") }
        val n = memory.forgetMatching(cmd.what)
        return Outcome.Success(if (n > 0) "Forgotten." else "I didn't have anything stored about that.")
    }

    private suspend fun correction(cmd: Command.Correction): Outcome {
        job?.let { if (it.isActive && it != kotlinx.coroutines.currentCoroutineContext()[Job]) it.cancel() }
        val replacement = cmd.replacement ?: run { say("Sorry — what did you mean instead?"); return Outcome.Success() }
        val last = ctx.lastCommand
        if (last != null && navigates(last) && last !is Command.OpenApp) {
            device.global(GlobalAction.BACK)
            ops.awaitChange(ops.current(), 2000)
        }
        // "Use the other contact" after a message/call → redo with the next match.
        return runCommand(replacement)
    }

    // ================================================================ LLM

    private fun contextText(): String = buildString {
        append("Time: ").append(SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH).format(Date(system.nowMs()))).append('\n')
        append("Working context:\n").append(ctx.toPromptText())
        append("Memory:\n").append(memory.toPromptText())
        val top = memory.topApps(6)
        if (top.isNotEmpty()) append("Frequently used apps: ").append(top.joinToString()).append('\n')
    }

    private suspend fun chat(text: String) {
        setState(AgentState.THINKING)
        val b = brain
        val reply = if (b != null && memory.settings.useCloudAi) {
            val history = ctx.conversation.toList().dropLast(1)
            val screen = ops.screen()
            val extra = screen?.let { "\nOn screen now (${ScreenClassifier.classify(it)}):\n" + it.toPromptText(40) } ?: ""
            runCatching { b.chat(history, text, contextText() + extra) }.getOrElse { "I couldn't reach the AI service (${it.message}). Phone controls still work offline." }
        } else OfflineChat.reply(text, memory, system)
        say(reply)
    }

    private suspend fun describeScreen(question: String): Outcome {
        val s = ops.screen() ?: return Outcome.Failed("I can't see the screen.")
        val b = brain
        if (b != null && memory.settings.useCloudAi) {
            setState(AgentState.THINKING)
            val shot = if (memory.settings.sendScreenshotsToCloud) device.screenshot() else null
            val answer = runCatching { b.describeScreen(question, s, shot, contextText()) }.getOrNull()
            if (answer != null) return Outcome.Success(answer)
        }
        val kind = ScreenClassifier.classify(s)
        val app = apps.resolve(s.packageName.substringAfterLast('.'))?.label ?: s.packageName.substringAfterLast('.')
        val buttons = s.nodes.filter { it.clickable && it.label.isNotBlank() }.map { it.label }.distinct().take(8)
        val text = s.readableText().take(5)
        return Outcome.Success(buildString {
            append("You're in ").append(app)
            if (kind != ScreenKind.UNKNOWN) append(" (").append(kind.name.substringAfter('_').lowercase().replace('_', ' ')).append(")")
            append(". ")
            if (text.isNotEmpty()) append("It shows: ").append(text.joinToString("; ")).append(". ")
            if (buttons.isNotEmpty()) append("Buttons: ").append(buttons.joinToString(", ")).append('.')
        })
    }

    /** Rules couldn't do it – let the LLM agent operate the phone toward the goal. */
    private suspend fun agentFallback(goal: String, cmd: Command, why: Outcome): Outcome {
        val b = brain?.takeIf { memory.settings.useCloudAi }
        // The small phone brain is not reliable enough to drive screens by itself – only Claude does that.
        val pb: PhoneBrain? = null
        if (b == null && pb == null) {
            return if (cmd is Command.Agentic) Outcome.Failed("I didn't understand \"$goal\". Try saying it another way, or download the free phone brain in Settings.", blocking = false)
            else why
        }
        setState(AgentState.THINKING)
        val first = ops.screen()
        val shot = if (b != null && memory.settings.sendScreenshotsToCloud) device.screenshot() else null
        val fullGoal = if (ctx.lastUtterance != null && ctx.lastUtterance != goal) "$goal\n(This is one step of the user's request: \"${ctx.lastUtterance}\")" else goal
        val session = b?.startAgent(fullGoal, contextText(), first, shot) ?: pb!!.startAgent(goal, first)
        var feedback: ActionFeedback? = null
        setState(AgentState.EXECUTING)
        repeat(MAX_AGENT_STEPS) {
            awaitNotPaused()
            val action = try { session.next(feedback) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                return Outcome.Failed("The AI service failed: ${e.message}", blocking = false)
            }
            val before = ops.current()
            val desc: String
            when (action) {
                is AgentAction.Done -> return Outcome.Success(action.summary.takeIf { it.isNotBlank() })
                is AgentAction.Fail -> return Outcome.Failed(action.reason, blocking = false)
                is AgentAction.AskUser -> return Outcome.Asked(action.question)
                is AgentAction.Say -> { say(action.text); feedback = ActionFeedback("Told the user.", false, before); return@repeat }
                is AgentAction.Tap -> desc = before?.nodeById(action.elementId)?.let { n -> device.click(before.clickableTarget(n)); "Tapped [${action.elementId}] \"${n.label}\"" } ?: "No element ${action.elementId} on screen"
                is AgentAction.TapAt -> { device.tapAt(action.x, action.y); desc = "Tapped at ${action.x},${action.y}" }
                is AgentAction.LongPress -> desc = before?.nodeById(action.elementId)?.let { n -> device.longClick(n) || device.longPressAt(n.bounds.centerX, n.bounds.centerY); "Long-pressed \"${n.label}\"" } ?: "No element ${action.elementId}"
                is AgentAction.Type -> {
                    val field = action.elementId?.let { before?.nodeById(it) }
                    val r = ops.typeText(action.text, field)
                    if (action.submit && r !is Outcome.Failed) device.pressImeAction(field)
                    desc = "Typed \"${action.text}\": ${r::class.simpleName}${r.message?.let { m -> " ($m)" } ?: ""}"
                }
                is AgentAction.Scroll -> { device.scroll(action.elementId?.let { before?.nodeById(it) }, action.direction); desc = "Scrolled ${action.direction}" }
                is AgentAction.Swipe -> { device.swipe(action.direction); desc = "Swiped ${action.direction}" }
                AgentAction.Back -> { device.global(GlobalAction.BACK); desc = "Pressed Back" }
                AgentAction.Home -> { device.global(GlobalAction.HOME); desc = "Pressed Home" }
                is AgentAction.OpenApp -> desc = "Open app ${action.name}: " + (openApp(action.name).let { it::class.simpleName + (it.message?.let { m -> " ($m)" } ?: "") })
                is AgentAction.OpenUrl -> desc = if (device.openUri(action.url)) "Opened ${action.url}" else "Couldn't open ${action.url}"
                is AgentAction.Wait -> { kotlinx.coroutines.delay(action.ms.coerceIn(100, 4000)); desc = "Waited" }
            }
            val after = ops.awaitChange(before, 2500) ?: ops.screen()
            val changed = after != null && after.fingerprint != before?.fingerprint
            val img = if (b != null && memory.settings.sendScreenshotsToCloud) device.screenshot() else null
            feedback = ActionFeedback(desc, changed, after, img)
        }
        return Outcome.Uncertain("I worked on it for a while but couldn't confirm it's finished.")
    }

    // ================================================================ output

    private fun describe(c: Command): String = when (c) {
        is Command.OpenApp -> "Open ${c.app.takeUnless { it == IntentParser.PREVIOUS_APP } ?: "the previous app"}"
        is Command.Navigate -> c.action.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
        is Command.Scroll -> "Scroll ${c.direction.name.lowercase()}"
        Command.NextItem -> "Next"
        Command.PreviousItem -> "Previous"
        is Command.React -> (if (c.on) "" else "Un") + c.reaction.name.lowercase()
        Command.OpenComments -> "Open comments"
        is Command.Write -> "Write \"${c.text}\""
        is Command.Search -> "Search \"${c.query}\""
        is Command.OpenResult -> "Open result ${c.ordinal}"
        is Command.GoTo -> "Go to ${c.section.name.lowercase()}"
        is Command.SendMessage -> "Message ${c.contact ?: ""}".trim()
        is Command.OpenChat -> "Open chat with ${c.contact}"
        is Command.Call -> "Call ${c.contact ?: c.number}"
        is Command.Tap -> "Tap \"${c.label}\""
        is Command.Agentic -> c.goal
        is Command.Chat -> "Answer"
        else -> c.toString().substringBefore('(')
    }

    private fun needsAck(c: Command) = c is Command.OpenApp || c is Command.SetToggle || c is Command.SetLevel || c is Command.Call ||
        c is Command.React || c is Command.SendMessage || c is Command.Media || c is Command.PlayMedia || c is Command.Navigate && c.action == GlobalAction.SCREENSHOT

    private fun ack(c: Command): String = when (c) {
        is Command.OpenApp -> "Opened ${c.app.takeUnless { it == IntentParser.PREVIOUS_APP } ?: "it"}."
        is Command.React -> when (c.reaction) {
            Reaction.LIKE -> if (c.on) "Liked." else "Removed the like."
            Reaction.SAVE -> if (c.on) "Saved." else "Removed from saved."
            Reaction.SUBSCRIBE -> if (c.on) "Subscribed." else "Unsubscribed."
            Reaction.FOLLOW -> if (c.on) "Following." else "Unfollowed."
            Reaction.DISLIKE -> "Disliked."
        }
        is Command.SendMessage -> if (c.send) "Sent." else "Drafted."
        is Command.Call -> "Calling."
        is Command.PlayMedia -> "Playing ${c.query}."
        else -> "Done."
    }

    private suspend fun report(o: Outcome, what: String) {
        o.message?.let { say(it) } ?: if (o is Outcome.Success) say("Done.") else Unit
    }

    private fun say(text: String, speak: Boolean = true) {
        ctx.addTurn("assistant", text)
        memory.recordTurn("assistant", text)
        emit(NexusEvent.Reply(text, speak && memory.settings.speakResponses))
    }

    private fun emit(e: NexusEvent) { _events.tryEmit(e) }

    private fun setState(s: AgentState) {
        if (_state.value != s) { _state.value = s; emit(NexusEvent.StateChanged(s)) }
    }

    companion object {
        const val MAX_AGENT_STEPS = 20
        const val PHONE_BRAIN_TIMEOUT_MS = 6000L
    }
}

private object BuiltInPackages {
    fun of(name: String): String? = ai.nexus.core.nlu.BuiltInApps.packageFor(name)
}
