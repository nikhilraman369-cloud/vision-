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
import kotlinx.coroutines.async
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
    /** Tasks, modes, skills, study, notifications, diagnostics (null in minimal setups/tests). */
    val life: ai.nexus.core.life.LifeEngine? = null,
) {
    /** Speech post-correction against known names, apps and past mishearings. */
    val vocab = ai.nexus.core.voice.VocabularyCorrector()
    private val boss: String get() = memory.settings.addressAs.ifBlank { "Boss" }

    /** Refresh the speech vocabulary (call when contacts/apps/memory change). */
    fun refreshVocabulary(extraNames: Collection<String>) {
        val mem = memory.items
        val names = extraNames + mem.filter { it.kind == MemoryKind.CONTACT_ALIAS || it.kind == MemoryKind.APP_ALIAS }.flatMap { listOfNotNull(it.key, it.value) }
        vocab.update(names, mem.filter { it.kind == MemoryKind.MISHEARING && it.key != null }.associate { it.key!! to it.value })
    }

    /** A step NEXUS couldn't do and is waiting to be taught. */
    data class Stuck(val command: Command, val source: String, val rest: List<ParsedStep>, val app: String?, val description: String)
    @Volatile var stuck: Stuck? = null
        private set
    @Volatile private var teachTrigger: String? = null

    /** Free on-device model for rough commands (null until downloaded/loaded). */
    @Volatile var phoneBrain: PhoneBrain? = null
    /** What to say when a question needs the phone brain but it isn't ready (e.g. download progress). */
    @Volatile var phoneBrainMissing: (() -> String?)? = null

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
        val text = runCatching { vocab.correct(utterance.trim()) }.getOrDefault(utterance.trim())
        if (text.isEmpty()) return
        // Answers to "Boss, I'm stuck…": skip / teach me / or a typed instruction.
        stuck?.let { st ->
            val n = Fuzzy.normalize(text)
            when {
                n in setOf("skip", "skip it", "skip this", "skip this step", "chhodo", "aage badho") -> { stuckSkip(); return }
                n in setOf("teach me", "i will show you", "i ll show you", "let me show you", "let me teach you", "watch me", "main dikhata hoon", "sikhata hoon") -> { stuckTeach(); return }
            }
        }
        val parsed = parser.parse(text)

        // Stuck and waiting: whatever you say next (that isn't stop/cancel) is the instruction for the stuck step.
        if (stuck != null && !parsed.isControl) {
            ctx.addTurn("user", text)
            emit(NexusEvent.TeachingChanged(false)) // clears the stuck card
            stuckInstruction(text)
            return
        }

        // Stop / wait / continue must work while a task is running.
        if (parsed.isControl) {
            handleControl((parsed.steps[0].command as Command.ControlTask).control)
            return
        }

        // Going somewhere else ends an auto-scroll / repeat.
        if (loopJob?.isActive == true && parsed.steps.any { it.command is Command.OpenApp || it.command is Command.Navigate }) stopLoop()
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

            // A skill you taught NEXUS always wins over improvising.
            life?.let { l ->
                ai.nexus.core.life.SkillMatcher.match(key, l.data.skills)?.let { hit ->
                    ctx.lastUtterance = text
                    runJob { runSkill(hit, text) }
                    return@withLock
                }
            }
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
            // Real questions go to the conversation brain (chat); rough commands go to the translator.
            fun isQuestion(st: ParsedStep) = st.command is Command.Chat && (cloudReady || IntentParser.isConversational(st.source))
            fun unclearStep(st: ParsedStep) = !isQuestion(st) && (st.command is Command.Agentic || st.confidence < 0.55 ||
                (st.command is Command.Chat && !cloudReady && !OfflineChat.handles(st.source) && !Calc.looksLikeMath(st.source)))
            if (request.steps.any(::unclearStep)) {
                val pb = phoneBrain?.takeIf { memory.settings.usePhoneBrain }
                val newSteps = ArrayList<ParsedStep>()
                for (st in request.steps) {
                    if (!unclearStep(st)) { newSteps += st; continue }
                    val clauseKey = Fuzzy.normalize(st.source)
                    val learned = (memory.lookup(MemoryKind.LEARNED, clauseKey) ?: memory.lookup(MemoryKind.LEARNED, key))
                        ?.let { CommandDsl.parse(it) }?.takeIf { it.isNotEmpty() }
                    if (learned != null) { learned.forEach { newSteps += ParsedStep(it, st.source, 0.95) }; continue }
                    // Screen first: a short phrase that names something visible ("Vikas chat") is a tap.
                    val snap = kotlinx.coroutines.withTimeoutOrNull(1500) { ops.screen() }
                    val visible = visibleLabelFor(st.source, snap)
                    if (visible != null) { newSteps += ParsedStep(Command.Tap(visible), st.source, 0.9); continue }
                    if (pb != null && !pb.busy) {
                        setState(AgentState.THINKING)
                        // Detached: if the model is slow or stuck, we stop waiting and it can never block later commands.
                        val work = scope.async { runCatching { pb.translate(st.source, snap) }.getOrNull() }
                        val translated = kotlinx.coroutines.withTimeoutOrNull(PHONE_BRAIN_TIMEOUT_MS) { work.await() }
                            ?.filter { it !is Command.Chat && it !is Command.Agentic }
                        if (!translated.isNullOrEmpty()) {
                            translated.forEach { newSteps += ParsedStep(it, st.source, 0.8) }
                            learnPairs += clauseKey to translated
                            continue
                        }
                    }
                    newSteps += st
                }
                setState(AgentState.IDLE)
                if (newSteps.all { it.command is Command.Agentic || it.command is Command.Chat }) lastMiss = key to System.currentTimeMillis()
                // Still not understood and no cloud AI: repeat back and offer the closest command, or ask to be taught.
                if (!cloudReady && newSteps.size == 1 && newSteps[0].command is Command.Agentic) {
                    val guess = CommandCatalog.closest(norm, life?.data?.skills?.flatMap { it.triggers }.orEmpty() + memory.items.filter { it.kind == MemoryKind.LEARNED }.mapNotNull { it.key })
                    if (guess != null) {
                        ask("$boss, I heard \"$text\". Did you mean \"$guess\"?", listOf("yes", "no")) { idx ->
                            if (idx == 0) {
                                val steps = parser.parse(guess).steps
                                if (runSteps(steps) && steps.all { CommandDsl.toLine(it.command) != null }) memory.remember(MemoryKind.LEARNED, key, CommandDsl.toLines(steps.map { it.command }))
                            } else say("Okay $boss, say it another way, or say \"teach me\" and show me once.")
                        }
                    } else {
                        teachTrigger = text
                        say("$boss, I heard \"$text\" but I don't know that one yet. Say it another way, or say \"teach me\" and show me once.")
                    }
                    return@withLock
                }
                request = ai.nexus.core.nlu.ParsedRequest(text, norm, newSteps)
                if (request.isChat) { runJob { chat(text) }; return@withLock }
            }

            if (request.steps.size == 1 && request.steps[0].command == Command.Repeat) {
                if (ctx.lastRequestCommands.isEmpty()) say("There's nothing to repeat yet.")
                else runJob { runSteps(ctx.lastRequestCommands.map { ParsedStep(it, it.toString(), 0.9) }) }
                return@withLock
            }

            if (request.isChat) {
                if (maybeCaptureFact(text)) return@withLock
                runJob { chat(text) }
                return@withLock
            }
            ctx.lastUtterance = text
            if (memory.settings.spokenAcks && (request.steps.size > 1 || request.steps.any { slow(it.command) })) say(opener())
            val finalSteps = request.steps
            val missed = lastMiss?.takeIf { (_, at) -> System.currentTimeMillis() - at < 15_000 }?.first
            runJob {
                val ok = runSteps(finalSteps)
                if (!ok && finalSteps.size == 1) lastMiss = key to System.currentTimeMillis()
                if (ok) {
                    lastMiss = null
                    // You said X, NEXUS didn't get it, you rephrased as Y and it worked: X now means Y.
                    if (missed != null && missed != key && Fuzzy.similarity(missed, key) >= 0.45 && finalSteps.size <= 3 && finalSteps.all { CommandDsl.toLine(it.command) != null }) {
                        memory.remember(MemoryKind.LEARNED, missed, CommandDsl.toLines(finalSteps.map { it.command }))
                    }
                }
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
                val wasRunning = job?.isActive == true || loopJob?.isActive == true || stuck != null
                if (stuck != null) { stuck = null; emit(NexusEvent.TeachingChanged(false)) }
                job?.cancel(); stopLoop(); paused.value = false; ctx.pendingChoice = null
                setState(AgentState.IDLE)
                say(if (wasRunning) "Stopped." else "Okay.")
            }
            Control.PAUSE -> if (loopJob?.isActive == true && job?.isActive != true) {
                loopPaused = true; say("Paused. Say \"continue\" to carry on.", speak = false)
            } else if (job?.isActive == true) {
                paused.value = true; setState(AgentState.PAUSED)
                say("Paused. Say \"continue\" when you're ready.")
            } else {
                system.mediaKey(MediaKey.PAUSE); say("Paused.", speak = false)
            }
            Control.RESUME -> when {
                loopPaused && loopJob?.isActive == true -> { loopPaused = false; say("Carrying on.", speak = false) }
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

    /** Ask the user something from outside a command (e.g. "Shall I reply …?"); the next answer goes to [onChoose]. */
    fun prompt(question: String, options: List<String>, onChoose: suspend (Int) -> Unit) = ask(question, options, onChoose)

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
        var saidSomething = false
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

            // A step can never hang the whole assistant.
            val t0 = System.currentTimeMillis()
            val learnedSkill = if (steps.size > 1) life?.let { l -> ai.nexus.core.life.SkillMatcher.match(Fuzzy.normalize(step.source), l.data.skills) } else null
            var outcome = kotlinx.coroutines.withTimeoutOrNull(STEP_TIMEOUT_MS) {
                if (learnedSkill != null) executeSkill(learnedSkill) else runCommand(cmd, step.source)
            } ?: Outcome.Failed("That step took too long, so I moved on.", blocking = navigates(cmd))
            if (outcome is Outcome.NeedsAgent) outcome = agentFallback(step.source, cmd, outcome)
            emit(NexusEvent.StepFinished(i + 1, desc, outcome))
            logStep(step.source, desc, outcome, System.currentTimeMillis() - t0)
            // Can't do it and nobody else can: stop and ask to be taught, instead of guessing.
            if (life != null && memory.settings.askWhenStuck && screenStep(cmd) &&
                (outcome is Outcome.NeedsAgent || (outcome is Outcome.Failed && outcome.message.startsWith("I couldn't find") || outcome is Outcome.Failed && outcome.message.startsWith("I don't see")))
            ) {
                val app = ops.current()?.packageName
                val appName = app?.let { p -> apps.labelFor(p) } ?: "this app"
                stuck = Stuck(cmd, step.source, steps.drop(i + 1), app, desc)
                interruptedSteps = emptyList()
                emit(NexusEvent.Stuck(desc, appName, step.source))
                setState(AgentState.WAITING_FOR_USER)
                say("$boss, I'm stuck at \"$desc\" in $appName. Tell me what to do: say the step in words, say \"teach me\" and show me once, or say \"skip\".")
                return false
            }
            ctx.lastCommand = cmd
            executed += cmd

            when (outcome) {
                is Outcome.Success -> outcome.message?.let { saidSomething = true; say(it) }
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
            total > 1 -> acknowledge("All done, $boss.")
            else -> {
                val only = steps[0].command
                if (needsAck(only)) acknowledge(ack(only))
                else if (slow(only) && !saidSomething) acknowledge("Done, $boss.")
                else if (!saidSomething) emit(NexusEvent.Ack(""))
            }
        }
        return notes.isEmpty()
    }

    private var openerIdx = 0
    /** What NEXUS says the moment it starts a task. */
    private fun opener() = listOf("On it, $boss.", "Right away, $boss.", "On it, $boss.", "Certainly, $boss.")[openerIdx++ % 4]

    /** Result of a task: always a tick/vibration, and spoken ("Opened YouTube.", "All done, Boss.") unless confirmations are off. */
    private fun acknowledge(text: String) {
        if (memory.settings.spokenAcks) { say(text); emit(NexusEvent.Ack("")) }
        else { ctx.addTurn("assistant", text); emit(NexusEvent.Ack(text)) }
    }

    private fun slow(c: Command) = c is Command.OpenApp || c is Command.Search || c is Command.PlayMedia || c is Command.ShareTo ||
        c is Command.SendMessage || c is Command.OpenChat || c is Command.OpenResult || c is Command.Agentic || c is Command.StartMode

    private fun screenStep(c: Command) = c is Command.Tap || c is Command.OpenResult || c is Command.GoTo || c is Command.OpenComments ||
        c is Command.Write || c is Command.Search || c is Command.OpenChat || c is Command.React || c is Command.Submit || c is Command.Share ||
        c is Command.ShareTo || c is Command.FindOnScreen || c is Command.Agentic || c is Command.NextItem

    private suspend fun logStep(request: String, desc: String, o: Outcome, ms: Long) {
        val l = life ?: return
        val snap = runCatching { ops.current() }.getOrNull()
        val screen = snap?.let { sn -> sn.packageName.substringAfterLast('.') + ": " + sn.nodes.filter { it.label.isNotBlank() && it.bounds.area > 0 }.take(10).joinToString(" | ") { it.label.take(25) } } ?: ""
        l.store.log(ai.nexus.core.life.DiagEntry(request = ctx.lastUtterance ?: request, step = desc, app = snap?.packageName ?: "",
            result = o::class.simpleName ?: "", detail = o.message ?: "", durationMs = ms, screen = screen))
    }

    // ================================================================ self-learning: stuck → teach → skill

    fun stuckSkip() {
        val st = stuck ?: return
        stuck = null
        say("Skipped.")
        if (st.rest.isNotEmpty()) runJob { runSteps(st.rest) }
    }

    fun stuckCancel() { stuck = null; setState(AgentState.IDLE); say("Cancelled, $boss.") }

    /** Re-teach (or teach) a skill for [trigger] from the app's Skills / Diagnostics screens. */
    fun teach(trigger: String) {
        teachTrigger = trigger
        recording = mutableListOf(); recordingLastPkg = null
        emit(NexusEvent.TeachingChanged(true))
        say("Show me \"$trigger\", $boss. Do it yourself, then tap Done.")
    }

    /** Start recording the user's own taps for the stuck step. */
    fun stuckTeach() {
        val st = stuck ?: return
        teachTrigger = st.source
        recording = mutableListOf(); recordingLastPkg = st.app
        emit(NexusEvent.TeachingChanged(true))
        say("Show me, $boss. Do this step yourself, then tap Done.")
    }

    /** "Type instruction": run the user's words for the stuck step, and remember them for next time. */
    fun stuckInstruction(text: String) {
        val st = stuck ?: return
        stuck = null
        runJob {
            val steps = parser.parse(text).steps
            val ok = runSteps(steps)
            if (ok && steps.all { CommandDsl.toLine(it.command) != null }) memory.remember(MemoryKind.LEARNED, Fuzzy.normalize(st.source), CommandDsl.toLines(steps.map { it.command }))
            // A brand-new "open <app>" means you've moved on: don't carry on with the old plan.
            if (ok && st.rest.isNotEmpty() && steps.firstOrNull()?.command !is Command.OpenApp) runSteps(st.rest)
        }
    }

    /** Done button while teaching. */
    fun finishTeaching(name: String? = null) {
        scope.launch { process(if (name != null) "done, call it $name" else "done") }
    }

    private suspend fun runSkill(hit: ai.nexus.core.life.SkillMatcher.Hit, text: String) {
        setState(AgentState.EXECUTING)
        if (memory.settings.spokenAcks && hit.skill.steps.size > 1) say(opener())
        val o = executeSkill(hit)
        logStep(text, "Skill: ${hit.skill.name}", o, 0)
        when (o) {
            is Outcome.Success -> acknowledge(o.message ?: "Done, $boss.")
            else -> {
                if (memory.settings.askWhenStuck) {
                    val app = ops.current()?.packageName
                    stuck = Stuck(Command.Agentic(text), text, emptyList(), app, hit.skill.name)
                    emit(NexusEvent.Stuck(hit.skill.name, app?.let { apps.labelFor(it) } ?: "this app", text))
                    setState(AgentState.WAITING_FOR_USER)
                    say("$boss, my \"${hit.skill.name}\" skill failed: ${o.message}. Say \"teach me\" to show me again, or \"skip\".")
                } else say(o.message ?: "That didn't work.")
            }
        }
    }

    /** Replays a learned recipe through observe → act → verify, with selector fallbacks. */
    private suspend fun executeSkill(hit: ai.nexus.core.life.SkillMatcher.Hit): Outcome {
        val l = life ?: return Outcome.Failed("No skills store.")
        val skill = hit.skill
        val fill = { t: String? -> ai.nexus.core.life.SkillMatcher.fill(t, hit.slots) }
        for ((i, st) in skill.steps.withIndex()) {
            awaitNotPaused()
            val ok: Boolean = when (st.action) {
                "open_app" -> {
                    val pkg = st.pkg ?: fill(st.selector?.text)?.let { apps.resolve(it)?.packageName }
                    ensureApp(pkg, fill(st.selector?.text)) !is Outcome.Failed
                }
                "tap", "long_press" -> {
                    val sel = st.selector
                    val snap = ops.waitForReady(memory.patience(st.pkg ?: ctx.currentPackage, 6000)) { sn -> findBySelector(sn, sel, fill) != null }
                    val node = snap?.let { findBySelector(it, sel, fill) }
                    when {
                        node != null && st.action == "tap" -> ops.tapReliably(node, snap) != null
                        node != null -> device.longClick(node) || device.longPressAt(node.bounds.centerX, node.bounds.centerY)
                        sel?.xPct != null && sel.yPct != null && (skill.lowConfidence || (sel.text == null && sel.desc == null && sel.viewId == null)) -> {
                            val sn = ops.screen(); device.tapAt(((sel.xPct) * (sn?.screenWidth ?: 1080)).toInt(), ((sel.yPct) * (sn?.screenHeight ?: 2400)).toInt()).also { ops.awaitChange(sn, 1500) }
                        }
                        else -> false
                    }
                }
                "type" -> ops.typeText(fill(st.text) ?: "") !is Outcome.Failed
                "scroll" -> { ops.scrollAndWait(if (st.direction == "up") ai.nexus.core.device.Direction.UP else ai.nexus.core.device.Direction.DOWN); true }
                "back" -> { val sn = ops.current(); device.global(GlobalAction.BACK); ops.awaitChange(sn, 1500); true }
                "home" -> { device.global(GlobalAction.HOME); true }
                else -> true
            }
            if (!ok) {
                l.store.updateSkill(skill.id) { it.copy(failures = it.failures + 1, lastUsedMs = System.currentTimeMillis()) }
                val what = st.selector?.let { fill(it.text) ?: it.desc ?: it.viewId?.substringAfter(":id/") } ?: st.action
                return Outcome.Failed("I couldn't find \"$what\" (step ${i + 1})", blocking = true)
            }
        }
        l.store.updateSkill(skill.id) { it.copy(successes = it.successes + 1, lastUsedMs = System.currentTimeMillis()) }
        return Outcome.Success()
    }

    private fun findBySelector(s: ai.nexus.core.screen.ScreenSnapshot, sel: ai.nexus.core.life.Selector?, fill: (String?) -> String?): ai.nexus.core.screen.UiNode? {
        sel ?: return null
        val text = fill(sel.text)
        val nodes = s.nodes.filter { it.bounds.area > 0 }
        sel.viewId?.let { vid -> nodes.filter { it.viewId == vid }.let { c -> (if (text != null) c.firstOrNull { n -> n.label.equals(text, true) } else null) ?: c.singleOrNull() }?.let { return it } }
        text?.let { t -> nodes.firstOrNull { it.text.equals(t, true) || it.contentDescription.equals(t, true) }?.let { return it } }
        sel.desc?.let { d -> nodes.firstOrNull { it.contentDescription.equals(d, true) }?.let { return it } }
        text?.let { t -> ai.nexus.core.screen.ElementFinder(s).byLabel(t, minScore = 0.8)?.let { return it } }
        if (sel.cls != null && sel.xPct != null && sel.yPct != null) {
            val x = sel.xPct * s.screenWidth; val y = sel.yPct * s.screenHeight
            nodes.filter { it.className == sel.cls && s.clickableTarget(it).clickable }
                .minByOrNull { kotlin.math.hypot(it.bounds.centerX - x.toDouble(), it.bounds.centerY - y.toDouble()) }
                ?.takeIf { kotlin.math.hypot(it.bounds.centerX - x.toDouble(), it.bounds.centerY - y.toDouble()) < s.screenWidth * 0.08 }?.let { return it }
        }
        return null
    }

    /** "My gym time is 6 PM" said in passing: offer to remember it. */
    private fun maybeCaptureFact(text: String): Boolean {
        val mode = memory.settings.autoCapture
        if (mode == "off" || !memory.enabled) return false
        val m = Regex("^(?:my|mera|meri|mere) (.+?) (?:is|are|hai|hain) (.+)$", RegexOption.IGNORE_CASE).matchEntire(text.trim().trimEnd('.'))
            ?: Regex("^(.+?) is my (.+)$", RegexOption.IGNORE_CASE).matchEntire(text.trim().trimEnd('.'))?.let { mm -> Regex("^(my .+?) is (.+)$").matchEntire("my ${mm.groupValues[2]} is ${mm.groupValues[1]}") }
            ?: return false
        val key = "my " + m.groupValues[1].trim().removePrefix("my ")
        val value = m.groupValues[2].trim()
        if (LongTermMemory.looksSensitive(value) || value.split(' ').size > 12) return false
        if (mode == "always") { memory.remember(MemoryKind.PREFERENCE, key, value); say("Noted, $boss."); return true }
        ask("Shall I remember that your ${key.removePrefix("my ")} is $value, $boss?", listOf("yes", "no")) { idx ->
            if (idx == 0) { memory.remember(MemoryKind.PREFERENCE, key, value); say("Remembered.") } else say("Okay, I won't.")
        }
        return true
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
        val after = ops.tapReliably(match.node, s) ?: return Outcome.Failed("\"$label\" didn't respond to a tap.")
        memory.rememberSelector(s.packageName, "tap:$cleaned", match.node.viewId)
        if (cmd is Command.OpenChat || ScreenClassifier.classify(after) in setOf(ScreenKind.WA_CHAT, ScreenKind.IG_DM_THREAD)) {
            ctx.currentContactName = label; ctx.currentChatApp = after.packageName
        }
        if (cmd is Command.PlayMedia) ctx.currentItem = "song: $label"
        return Outcome.Success()
    }

    private val leadVerbs = Regex("^(?:open|show|go to|tap|click|press|select|play|kholo|dikhao)\\s+", RegexOption.IGNORE_CASE)

    /** The on-screen label a short phrase refers to, if any. */
    private fun visibleLabelFor(phrase: String, s: ai.nexus.core.screen.ScreenSnapshot?): String? {
        s ?: return null
        if (phrase.split(' ').size > 5) return null
        val cleaned = phrase.replace(leadVerbs, "").replace(qualifiers, " ").replace(Regex("\\s+"), " ").trim()
        if (cleaned.length < 2) return null
        return ai.nexus.core.screen.ElementFinder(s).rankByLabel(listOf(cleaned))
            .firstOrNull { m -> !m.node.editable && m.score >= 0.85 && s.clickableTarget(m.node).clickable }?.node?.label
    }

    // ================================================================ teaching ("watch me")

    @Volatile private var recording: MutableList<ai.nexus.core.life.RecipeStep>? = null
    @Volatile private var recordingLastPkg: String? = null
    val isTeaching: Boolean get() = recording != null

    /** Rich recording from the accessibility service: selectors for every tap and typed text. */
    fun recordStep(step: ai.nexus.core.life.RecipeStep) {
        val rec = recording ?: return
        synchronized(rec) {
            if (step.action == "type") {
                if (rec.lastOrNull()?.action == "type") rec.removeAt(rec.lastIndex)
                if (LongTermMemory.looksSensitive(step.text ?: "")) return // never record secrets
            }
            if (step.action == "open_app" && rec.lastOrNull()?.action == "open_app" && rec.last().pkg == step.pkg) return
            if (step.action == "scroll" && rec.lastOrNull()?.action == "scroll" && rec.last().direction == step.direction) return
            rec += step
            step.pkg?.let { recordingLastPkg = it }
        }
    }

    /** Simple recording API (label / typed text). */
    fun recordUserAction(pkg: String, label: String?, typed: String?) {
        if (typed != null) { recordStep(ai.nexus.core.life.RecipeStep("type", pkg, text = typed)); return }
        val l = label?.trim()?.takeIf { it.isNotBlank() && it.length <= 60 } ?: return
        if (pkg in ai.nexus.core.screen.Packages.LAUNCHERS) recordStep(ai.nexus.core.life.RecipeStep("open_app", null, ai.nexus.core.life.Selector(text = l)))
        else recordStep(ai.nexus.core.life.RecipeStep("tap", pkg, ai.nexus.core.life.Selector(text = l)))
    }

    /** Called when the foreground app changes during teaching. */
    fun recordAppSwitch(pkg: String, appLabel: String?) {
        val rec = recording ?: return
        if (pkg in ai.nexus.core.screen.Packages.LAUNCHERS || pkg == recordingLastPkg || pkg == selfPackage) return
        synchronized(rec) {
            if (rec.lastOrNull()?.action != "open_app") rec += ai.nexus.core.life.RecipeStep("open_app", pkg, ai.nexus.core.life.Selector(text = appLabel))
            recordingLastPkg = pkg
        }
    }

    private fun stepsToCommands(steps: List<ai.nexus.core.life.RecipeStep>): List<Command> = steps.mapNotNull { st ->
        when (st.action) {
            "open_app" -> (st.selector?.text ?: st.pkg)?.let { Command.OpenApp(it) }
            "tap" -> (st.selector?.text ?: st.selector?.desc)?.let { Command.Tap(it) }
            "long_press" -> (st.selector?.text ?: st.selector?.desc)?.let { Command.Tap(it, long = true) }
            "type" -> st.text?.let { Command.Write(it, false) }
            "back" -> Command.Navigate(GlobalAction.BACK)
            else -> null
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

    private suspend fun lifeCommand(cmd: Command): Outcome {
        val l = life ?: return Outcome.Failed("That feature isn't set up.", blocking = false)
        val r = l.handle(cmd) ?: return Outcome.Failed("I couldn't do that.", blocking = false)
        for (line in r.runLines) {
            CommandDsl.parse(line).forEach { c -> kotlinx.coroutines.withTimeoutOrNull(15_000) { runCommand(c) } }
        }
        r.askQuiz?.let { card ->
            ask("${card.subject}: ${card.question}") { _ ->
                val answer = pendingAnswer.orEmpty(); pendingAnswer = null
                say(l.answerQuiz(card, answer))
            }
            return Outcome.Success()
        }
        if (cmd is Command.StartMode || cmd is Command.StopMode) emit(NexusEvent.ModeChanged(l.data.activeMode))
        return r.outcome
    }

    /** "No, I meant Rahul Sharma": fix the name (and remember it), then redo the last action. */
    private suspend fun meantInstead(text: String): Outcome {
        val last = ctx.lastCommand
        val lastName = when (last) {
            is Command.Call -> last.contact; is Command.SendMessage -> last.contact; is Command.OpenChat -> last.contact
            is Command.FindContact -> last.name; else -> null
        }
        if (last != null && lastName != null && text.split(' ').size <= 4 && parser.parse(text).steps.all { it.command is Command.Chat || it.command is Command.Agentic || it.command is Command.OpenApp }) {
            memory.remember(MemoryKind.CONTACT_ALIAS, lastName, text)
            ctx.currentContactName = text
            return runCommand(withContact(last, text))
        }
        // Otherwise: the last request was misheard/misunderstood. Learn it and do the right thing.
        val prev = ctx.lastUtterance
        val steps = parser.parse(text).steps
        val ok = runSteps(steps)
        if (ok && prev != null) {
            if (steps.all { CommandDsl.toLine(it.command) != null }) memory.remember(MemoryKind.LEARNED, Fuzzy.normalize(prev), CommandDsl.toLines(steps.map { it.command }))
            if (prev.split(' ').size <= 4 && text.split(' ').size <= 4) memory.remember(MemoryKind.MISHEARING, Fuzzy.normalize(prev), text)
        }
        return Outcome.Success()
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

    @Volatile private var pendingRoutine: List<ai.nexus.core.life.RecipeStep>? = null

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
        is Command.Navigate -> { var r: Outcome = Outcome.Success(); repeat(cmd.times) { r = generic.handle(cmd, env)!! }; r }
        is Command.Ringer -> sys(system.setRinger(cmd.mode))
        is Command.Directions -> sys(system.directions(memory.preference(cmd.place) ?: cmd.place, cmd.mode))
        is Command.CalendarEvent -> sys(system.addCalendarEvent(cmd.title, Dates.resolve(system.nowMs(), cmd.dayText, cmd.hour, cmd.minute))).let {
            if (it is Outcome.Success && it.message == null) Outcome.Success("Added \"${cmd.title}\" to your calendar — check and save it.") else it
        }
        is Command.TakePhoto -> sys(system.openCamera(cmd.front, cmd.video))
        is Command.TapRegion -> tapRegion(cmd)
        is Command.ShareTo -> shareTo(cmd)
        Command.ClearNotifications -> {
            device.global(GlobalAction.NOTIFICATIONS); ops.awaitChange(null, 1500)
            val s = ops.screen()
            val btn = s?.let { ai.nexus.core.screen.ElementFinder(it).byLabel("Clear all", "Clear all notifications", "Clear", "Manage", minScore = 0.85) }
            if (btn != null) { ops.tap(btn, s); Outcome.Success("Cleared.") } else { device.global(GlobalAction.BACK); Outcome.Success("No notifications to clear.") }
        }
        Command.OpenLatestNotification -> system.recentNotifications(1).firstOrNull()?.let { n -> sys(system.openNotification(n.key)) } ?: Outcome.Failed("There are no notifications.", blocking = false)
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
        is Command.Note -> life?.let { lifeCommand(cmd) } ?: if (memory.remember(MemoryKind.NOTE, null, cmd.text) != null) Outcome.Success("Noted.") else Outcome.Failed("I couldn't save that note.", blocking = false)
        is Command.Capture -> life?.let { lifeCommand(cmd) } ?: runCommand(Command.Note(cmd.text))
        is Command.StartMode, is Command.StopMode, is Command.AddExpense, is Command.AddTask, Command.ListTasks, is Command.CompleteTask,
        Command.WhatNow, is Command.Brief, is Command.QuizMe, is Command.AddFlashcard, is Command.AllowApp ->
            life?.let { lifeCommand(cmd) } ?: Outcome.Failed("That feature isn't set up.", blocking = false)
        is Command.WhatDoYouKnow -> {
            val t = Fuzzy.normalize(cmd.topic)
            val hits = memory.items.filter { it.kind != MemoryKind.LEARNED && it.kind != MemoryKind.MISHEARING }
                .filter { Fuzzy.normalize("${it.key ?: ""} ${it.value}").let { h -> h.contains(t) || t.split(' ').filter { w -> w.length > 2 }.any { w -> h.contains(w) } } }
            Outcome.Success(if (hits.isEmpty()) "I don't know anything about ${cmd.topic} yet, $boss." else "About ${cmd.topic}: " + hits.take(6).joinToString("; ") { (it.key?.let { k -> "$k: " } ?: "") + it.value } + ".")
        }
        is Command.MeantInstead -> meantInstead(cmd.text)
        is Command.PreferApp -> {
            val who = if (cmd.contact == IntentParser.SAME_CONTACT) ctx.currentContactName ?: ctx.currentContact?.name else cmd.contact
            if (who == null) Outcome.Asked("For whom, $boss?") else { memory.remember(MemoryKind.PREFERENCE, "app for ${Fuzzy.normalize(who)}", cmd.app); Outcome.Success("Okay, I'll always use ${cmd.app} for $who.") }
        }
        is Command.Toggle -> when (cmd.what) {
            "notifications" -> { memory.updateSettings { it.copy(notificationWatching = cmd.on) }; Outcome.Success(if (cmd.on) "Watching notifications again." else "Paused notification watching.") }
            else -> {
                val wasMuted = memory.settings.voiceMuted
                memory.updateSettings { it.copy(voiceMuted = !cmd.on) }; emit(NexusEvent.VoiceMuted(!cmd.on))
                Outcome.Success(if (!cmd.on) null else if (wasMuted) "Voice back on, $boss." else "Always, $boss. I'm right here. What's on your mind?")
            }
        }
        Command.ReadNotes -> memory.items.filter { it.kind == MemoryKind.NOTE }.let { n ->
            Outcome.Success(if (n.isEmpty()) "You have no notes." else "Your notes: " + n.takeLast(10).joinToString(". ") { it.value })
        }
        Command.ClearNotes -> { memory.items.filter { it.kind == MemoryKind.NOTE }.forEach { memory.forget(it.id) }; Outcome.Success("Notes cleared.") }
        is Command.Calculate -> Calc.evaluate(cmd.expression)?.let { Outcome.Success("${cmd.expression.trim()} = ${Calc.format(it)}") }
            ?: Outcome.Failed("I couldn't work that out.", blocking = false)
        Command.TeachStart -> {
            if (stuck != null) { stuckTeach(); Outcome.Success() } else {
                if (teachTrigger == null) teachTrigger = lastMiss?.first
                recording = mutableListOf(); recordingLastPkg = ops.screen()?.packageName
                emit(NexusEvent.TeachingChanged(true))
                Outcome.Success("I'm watching, $boss. Do the task yourself, then tap Done${if (teachTrigger == null) " and give it a name" else ""}.")
            }
        }
        is Command.TeachStop -> {
            val rec = recording?.toList()
            recording = null
            emit(NexusEvent.TeachingChanged(false))
            val trigger = teachTrigger
            when {
                rec == null -> Outcome.Failed("I wasn't watching. Say \"watch me\" first.", blocking = false)
                rec.isEmpty() -> { teachTrigger = null; Outcome.Failed("I didn't see any steps.", blocking = false) }
                cmd.name != null || trigger != null -> {
                    val name = cmd.name ?: trigger!!
                    teachTrigger = null
                    if (life != null) {
                        val built = ai.nexus.core.life.SkillMatcher.build(name, trigger ?: name, rec)
                        // Re-teaching an existing skill keeps the old version for rollback.
                        val existing = life.data.skills.firstOrNull { sk -> sk.triggers.any { it in built.triggers } || Fuzzy.normalize(sk.name) == Fuzzy.normalize(name) }
                        val skill = existing?.copy(steps = built.steps, triggers = (existing.triggers + built.triggers).distinct(), version = existing.version + 1,
                            history = (existing.history + listOf(existing.steps)).takeLast(5), failures = 0) ?: built
                        life.store.saveSkill(skill)
                        val st = stuck; stuck = null
                        if (st != null && st.rest.isNotEmpty()) scope.launch { runSteps(st.rest) }
                        Outcome.Success("Learned \"${skill.name}\" with ${rec.size} steps. I'll do it myself next time, $boss.")
                    } else saveRoutine(name, stepsToCommands(rec))
                }
                else -> { pendingRoutine = rec; Outcome.Asked("Got ${rec.size} steps. What should I call it?") }
            }
        }
        Command.HideBubble -> { emit(NexusEvent.HideBubble); Outcome.Success() }
        is Command.Correction -> correction(cmd)
        is Command.ControlTask -> { handleControl(cmd.control); Outcome.Success() }
        is Command.AutoScroll -> { startLoop(if (cmd.direction == Direction.UP) "Scrolling up" else "Scrolling down") { autoScrollLoop(cmd.direction, cmd.speed) }
            Outcome.Success("Scrolling ${if (cmd.direction == Direction.UP) "up" else "down"}, $boss. Say faster, slower, or stop.") }
        is Command.LoopSpeed -> if (loopJob?.isActive == true) {
            loopSpeed = (loopSpeed + if (cmd.faster) 1 else -1).coerceIn(0, 4)
            Outcome.Success(if (cmd.faster) "Faster." else "Slower.")
        } else Outcome.Success("Nothing is running right now, $boss.")
        is Command.RepeatEvery -> repeatEvery(cmd)
        is Command.After -> after(cmd)
        is Command.ScheduleAt -> {
            val at = ai.nexus.core.nlu.Timing.resolve(cmd.whenText, system.nowMs())
            when {
                at == null -> Outcome.Failed("I couldn't work out when \"${cmd.whenText}\" is. Try \"tomorrow at 9 pm\".")
                !parser.isConcrete(cmd.command) -> Outcome.Failed("I'm not sure how to do \"${cmd.command}\".")
                else -> schedule(at, cmd.command)
            }
        }
        Command.ListScheduled -> listScheduled()
        is Command.CancelScheduled -> cancelScheduled(cmd.what)
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
                val who = name.replace(qualifiers, " ").replace(Regex("\\s+"), " ").trim().ifBlank { name }
                return appSkill(cur)?.handle(Command.OpenChat(who), env) ?: Outcome.NeedsAgent("I couldn't find $name.")
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
        val t0 = System.currentTimeMillis()
        if (!device.launchApp(pkg)) return Outcome.Failed("I couldn't open ${label ?: pkg}.")
        memory.recordAppLaunch(pkg)
        // Wait as long as this app usually needs (learned), not a fixed guess.
        val ok = ops.waitFor(memory.patience(pkg)) { it.packageName == pkg }
        if (ok != null) memory.recordLoadTime(pkg, System.currentTimeMillis() - t0)
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

    private fun targetMessagingApp(app: String?, contact: String? = null): String? {
        val a = app?.lowercase() ?: contact?.let { c -> memory.preference("app for ${Fuzzy.normalize(c)}")?.lowercase() }
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
        val app = targetMessagingApp(cmd.app, cmd.contact)
        val text = cmd.text ?: return Outcome.Asked("What should I say${cmd.contact?.let { " to $it" } ?: ""}?")
        if (cmd.send && memory.settings.confirmBeforeSending && pendingConfirmed != cmd && !autoConfirm) {
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
        val origin = ops.screen()?.packageName
        if (app != Packages.WHATSAPP && app != Packages.WHATSAPP_BUSINESS) {
            val r = ensureApp(app); if (r is Outcome.Failed) return r
        }
        val result = skill.handle(cmd, env) ?: screenCommand(cmd)
        // Flash-and-return: if you were in another app (reading a PDF…), go straight back there.
        if (cmd.send && result is Outcome.Success) returnTo(origin, app)
        return result
    }

    /** "Tap top right", "double tap center": for unlabeled buttons, games and custom views. */
    private suspend fun tapRegion(c: Command.TapRegion): Outcome {
        val s = ops.screen()
        val w = s?.screenWidth ?: 1080; val h = s?.screenHeight ?: 2400
        val x = (w * listOf(0.15, 0.5, 0.85)[c.col]).toInt(); val y = (h * listOf(0.13, 0.5, 0.87)[c.row]).toInt()
        val ok = when {
            c.double -> device.doubleTapAt(x, y)
            c.long -> device.longPressAt(x, y)
            else -> device.tapAt(x, y)
        }
        ops.awaitChange(s, 1200)
        return if (ok) Outcome.Success() else Outcome.Failed("That tap didn't go through.")
    }

    /**
     * "Send this to Arjun": open the screen's Share menu, pick the contact (or
     * WhatsApp → contact), send, then return to where you were.
     */
    private suspend fun shareTo(c: Command.ShareTo): Outcome {
        val start = ops.screen() ?: return Outcome.Failed("I can't see the screen.")
        val origin = start.packageName
        val who = memory.contactAlias(c.contact) ?: c.contact
        val shareBtn = ai.nexus.core.screen.SemanticUiDisambiguator(start).bestNavigation(listOf("Share", "Send", "Forward", "Share via"))
            ?: ai.nexus.core.screen.ElementFinder(start).byLabel("Share", "Send", "Forward", minScore = 0.85)
            ?: return Outcome.Failed("I don't see a share button here.", blocking = false)
        ops.tapReliably(shareBtn, start)
        var sheet = ops.waitForReady(3000) { snap -> snap.fingerprint != start.fingerprint } ?: return Outcome.Failed("The share menu didn't open.", blocking = false)
        // Direct share: the contact may be listed right in the share sheet.
        val direct = ai.nexus.core.screen.ElementFinder(sheet).rankByLabel(listOf(who)).firstOrNull { it.score >= 0.85 }?.node
        val appLabel = c.app ?: "WhatsApp"
        if (direct == null) {
            val appBtn = ai.nexus.core.screen.ElementFinder(sheet).byLabel(appLabel, minScore = 0.85)
                ?: run { ops.scrollAndWait(ai.nexus.core.device.Direction.RIGHT); ops.current()?.let { ai.nexus.core.screen.ElementFinder(it).byLabel(appLabel, minScore = 0.85) } }
                ?: return Outcome.Failed("I couldn't find $appLabel in the share menu.", blocking = false)
            ops.tapReliably(appBtn, sheet)
            sheet = ops.waitForReady(6000) { snap -> snap.packageName != origin && snap.nodes.isNotEmpty() } ?: return Outcome.Failed("$appLabel didn't open.", blocking = false)
            // Contact picker: search the name, tap the row.
            val inList = ai.nexus.core.screen.ElementFinder(sheet).rankByLabel(listOf(who)).firstOrNull { it.score >= 0.85 && !it.node.editable }?.node
            val row = inList ?: run {
                ai.nexus.core.screen.ElementFinder(sheet).byLabel("Search", minScore = 0.85)?.let { ops.tapReliably(it, sheet) }
                ops.typeText(who)
                ops.waitForReady(4000) { snap -> ai.nexus.core.screen.ElementFinder(snap).rankByLabel(listOf(who)).any { it.score >= 0.85 && !it.node.editable } }
                    ?.let { snap -> ai.nexus.core.screen.ElementFinder(snap).rankByLabel(listOf(who)).first { it.score >= 0.85 && !it.node.editable }.node }
            } ?: return Outcome.Failed("I couldn't find $who to share with.", blocking = false)
            ops.tapReliably(row, ops.current())
        } else ops.tapReliably(direct, sheet)
        // Confirm / send button (WhatsApp shows a green send arrow; the chat may then open).
        ops.waitFor(4000) { snap -> ai.nexus.core.screen.SemanticUiDisambiguator(snap).submitFor(null) != null }?.let { snap ->
            ai.nexus.core.screen.SemanticUiDisambiguator(snap).submitFor(null)?.let { b -> ops.tapReliably(b, snap) }
        }
        ops.awaitChange(ops.current(), 1500)
        ctx.currentContactName = who
        returnTo(origin, null)
        return Outcome.Success("Sent to $who.")
    }

    /** Return to the app the user was in before a background task pulled the screen away. */
    private suspend fun returnTo(origin: String?, taskApp: String?) {
        if (!memory.settings.returnAfterTasks || origin == null || origin == taskApp || origin == selfPackage) return
        if (origin in Packages.LAUNCHERS || origin == Packages.SYSTEM_UI) return
        val now = ops.screen()?.packageName
        if (now == origin) return
        device.launchApp(origin)
        ops.waitFor(2500) { it.packageName == origin }
    }

    @Volatile private var pendingConfirmed: Command.SendMessage? = null
    /** Last request that wasn't understood (normalized text, time) — for learning from rephrasing. */
    @Volatile private var lastMiss: Pair<String, Long>? = null
    /** This app's own package, so we never "return" into NEXUS itself. */
    @Volatile var selfPackage: String = "ai.nexus.app"

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
        // Tier 1: headless "play from search" – no screen change at all.
        val r = sys(system.playFromSearch(cmd.query, pkg))
        if (r is Outcome.Success) {
            ctx.currentItem = "song: ${cmd.query}"
            when (waitForMusic(1800)) {
                true, null -> return Outcome.Success()   // playing (or this phone can't tell us)
                false -> {}                               // nothing playing: fall through to the UI macro
            }
        }
        // Tier 2: drive the music app's UI: search → wait for results to load → tap the best song → verify.
        val target = pkg ?: listOf(Packages.YT_MUSIC, Packages.SPOTIFY).firstOrNull { p -> apps.resolve(p.substringAfterLast('.'))?.packageName == p }
            ?: Packages.YT_MUSIC
        return playViaUi(cmd.query, target)
    }

    private suspend fun waitForMusic(ms: Long): Boolean? {
        val deadline = System.currentTimeMillis() + ms
        var last: Boolean? = system.isMusicActive() ?: return null
        while (last == false && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(150)
            last = system.isMusicActive()
        }
        return last
    }

    private suspend fun playViaUi(query: String, pkg: String): Outcome {
        val opened = ensureApp(pkg)
        if (opened is Outcome.Failed) return opened
        val searched = generic.handle(Command.Search(query), env) ?: Outcome.Failed("I couldn't search in the music app.")
        if (searched is Outcome.Failed) return searched
        val q = Fuzzy.normalize(query)
        repeat(3) { attempt ->
            val s = ops.waitForReady(memory.patience(pkg, 8000)) { snap -> songRows(snap, q).isNotEmpty() }
                ?: return Outcome.Failed("The search results didn't load.", blocking = false)
            val row = songRows(s, q).firstOrNull() ?: return Outcome.Failed("I couldn't find \"$query\" in the results.", blocking = false)
            ops.tapReliably(row, s)
            when (waitForMusic(3000)) {
                true, null -> { ctx.currentItem = "song: $query"; return Outcome.Success() }
                false -> if (attempt == 2) return Outcome.Uncertain("I tapped the song, but it doesn't seem to be playing.")
            }
        }
        return Outcome.Uncertain("I tapped the song, but couldn't confirm it's playing.")
    }

    /** Result rows that look like the song asked for, best first (songs before videos/albums). */
    private fun songRows(s: ai.nexus.core.screen.ScreenSnapshot, q: String): List<ai.nexus.core.screen.UiNode> {
        val firstWord = q.substringBefore(' ')
        return ai.nexus.core.screen.ElementFinder(s).listItems()
            .filter { !it.editable && it.bounds.top > s.screenHeight * 0.12 }
            .map { n -> val t = Fuzzy.normalize(n.allText()); n to (if (t.contains(q)) 3 else if (t.contains(firstWord)) 1 else 0) + (if (t.contains("song")) 1 else 0) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<ai.nexus.core.screen.UiNode, Int>> { it.second }.thenBy { it.first.bounds.top })
            .map { it.first }
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

    // ================================================================ loops, delays & schedules

    private var loopJob: Job? = null
    @Volatile private var loopSpeed = 2
    @Volatile private var loopPaused = false
    private val _loop = MutableStateFlow<String?>(null)
    /** What is auto-running right now ("Scrolling down", "Every 15 s: next"), for the UI. */
    val loop: StateFlow<String?> = _loop
    /** Set by the app: arm an alarm for a scheduled action / remove it. */
    @Volatile var onSchedule: ((ai.nexus.core.life.ScheduledAction) -> Unit)? = null
    @Volatile var onUnschedule: ((String) -> Unit)? = null
    /** Scheduled actions run without the usual "Send …?" question (you already asked for them). */
    @Volatile private var autoConfirm = false
    private data class Delayed(val text: String, val atMs: Long, val job: Job)
    private val delayed = java.util.concurrent.CopyOnWriteArrayList<Delayed>()

    fun stopLoop() { loopJob?.cancel(); loopJob = null; loopPaused = false; _loop.value = null }

    private fun startLoop(label: String, block: suspend () -> Unit) {
        loopJob?.cancel()
        loopPaused = false
        _loop.value = label
        lateinit var me: Job
        me = scope.launch {
            try { block() } finally { if (loopJob === me) { _loop.value = null; loopJob = null } }
        }
        loopJob = me
    }

    private suspend fun waitWhilePaused() { while (loopPaused) kotlinx.coroutines.delay(200) }

    private fun fmtSeconds(s: Int) = when {
        s % 3600 == 0 -> "${s / 3600} hour" + if (s / 3600 == 1) "" else "s"
        s % 60 == 0 -> "${s / 60} minute" + if (s / 60 == 1) "" else "s"
        else -> "$s seconds"
    }

    /** Keep scrolling (reading speed when "slowly") until "stop", the end of the page, or an hour. */
    private suspend fun autoScrollLoop(dir: Direction, speed: Int) {
        loopSpeed = speed.coerceIn(0, 4)
        val kind = ops.screen()?.let { ScreenClassifier.classify(it) }
        val shortVideos = kind == ScreenKind.IG_REELS || kind == ScreenKind.YT_SHORTS
        val end = system.nowMs() + 60 * 60_000L
        var lastSig: Int? = null
        var same = 0
        kotlinx.coroutines.delay(500)
        while (system.nowMs() < end) {
            waitWhilePaused()
            val sp = loopSpeed.coerceIn(0, 4)
            if (shortVideos) {
                runCommand(if (dir == Direction.DOWN) Command.NextItem else Command.PreviousItem)
                kotlinx.coroutines.delay(longArrayOf(20_000, 15_000, 10_000, 6_000, 4_000)[sp])
                continue
            }
            // A swipe up moves the content down (and vice versa); slow = short, gentle swipes for reading.
            device.swipe(if (dir == Direction.DOWN) Direction.UP else Direction.DOWN, floatArrayOf(0.12f, 0.2f, 0.35f, 0.5f, 0.65f)[sp], longArrayOf(900, 700, 450, 350, 300)[sp])
            kotlinx.coroutines.delay(longArrayOf(3_000, 1_800, 1_100, 700, 450)[sp])
            val sig = ops.screen()?.let { s -> s.nodes.joinToString("|") { it.label }.hashCode() }
            if (sig != null && sig == lastSig) {
                if (++same >= 3) { say("That's the ${if (dir == Direction.DOWN) "end" else "top"}, $boss."); return }
            } else same = 0
            lastSig = sig
        }
    }

    private fun repeatEvery(cmd: Command.RepeatEvery): Outcome {
        val steps = parser.parse(cmd.command).steps.filter { it.command !is Command.Chat && it.command !is Command.Agentic }
        if (steps.isEmpty()) return Outcome.Failed("I don't know how to do \"${cmd.command}\" yet.")
        val what = if (steps.size == 1 && steps[0].command == Command.NextItem) "next one" else cmd.command
        startLoop("Every ${fmtSeconds(cmd.seconds)}: $what") {
            loopSpeed = 2
            val end = system.nowMs() + 3 * 3600_000L
            var n = 0
            while (system.nowMs() < end && n < 2000) {
                val factor = doubleArrayOf(2.0, 1.5, 1.0, 0.66, 0.5)[loopSpeed.coerceIn(0, 4)]
                kotlinx.coroutines.delay((cmd.seconds * 1000 * factor).toLong())
                waitWhilePaused()
                for (st in steps) runCommand(st.command, st.source)
                n++
            }
        }
        return Outcome.Success("Okay $boss: $what every ${fmtSeconds(cmd.seconds)}. Say stop to end.")
    }

    private fun after(cmd: Command.After): Outcome {
        // Long waits survive the app being closed (alarm); short ones just wait here.
        if (cmd.seconds >= 600 && life != null) return schedule(system.nowMs() + cmd.seconds * 1000L, cmd.command)
        val at = system.nowMs() + cmd.seconds * 1000L
        lateinit var d: Delayed
        val j = scope.launch {
            kotlinx.coroutines.delay(cmd.seconds * 1000L)
            delayed.remove(d)
            autoConfirm = true
            try { process(cmd.command) } finally { autoConfirm = false }
        }
        d = Delayed(cmd.command, at, j); delayed += d
        return Outcome.Success("Okay $boss, in ${fmtSeconds(cmd.seconds)}: ${cmd.command}.")
    }

    private fun schedule(at: Long, text: String): Outcome {
        val l = life ?: return Outcome.Failed("Scheduling isn't available.")
        val a = ai.nexus.core.life.ScheduledAction(text = text, atMs = at)
        l.store.mutate { d -> d.copy(scheduled = (d.scheduled.filter { !it.done && it.atMs > system.nowMs() - 86_400_000L } + a)) }
        onSchedule?.invoke(a)
        return Outcome.Success("Scheduled ${ai.nexus.core.life.Briefs.whenText(at, system.nowMs())}, $boss: $text.")
    }

    private fun listScheduled(): Outcome {
        val now = system.nowMs()
        val items = (life?.data?.scheduled.orEmpty().filter { !it.done && it.atMs > now }.map { it.atMs to it.text } +
            delayed.map { it.atMs to it.text }).sortedBy { it.first }
        if (items.isEmpty()) return Outcome.Success("Nothing scheduled, $boss.")
        return Outcome.Success(items.take(5).joinToString(" ") { (at, t) -> "${ai.nexus.core.life.Briefs.whenText(at, now)}: $t." })
    }

    private fun cancelScheduled(what: String?): Outcome {
        val key = what?.let { Fuzzy.normalize(it) }
        fun hit(t: String) = key == null || Fuzzy.normalize(t).contains(key)
        var n = 0
        delayed.filter { hit(it.text) }.forEach { it.job.cancel(); delayed.remove(it); n++ }
        life?.let { l ->
            val gone = l.data.scheduled.filter { !it.done && hit(it.text) }
            gone.forEach { onUnschedule?.invoke(it.id) }
            n += gone.size
            l.store.mutate { d -> d.copy(scheduled = d.scheduled.filterNot { g -> gone.any { it.id == g.id } }) }
        }
        return Outcome.Success(if (n == 0) "I couldn't find that in your schedule, $boss." else "Cancelled $n scheduled ${if (n == 1) "item" else "items"}.")
    }

    /** Called by the app's alarm when a scheduled action is due. */
    fun runScheduled(id: String): Job? {
        val l = life ?: return null
        val a = l.data.scheduled.firstOrNull { it.id == id && !it.done } ?: return null
        l.store.mutate { d -> d.copy(scheduled = d.scheduled.map { if (it.id == id) it.copy(done = true) else it }) }
        return scope.launch {
            autoConfirm = true
            try { process(a.text) } finally { autoConfirm = false }
        }
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
        } else {
            val pb = phoneBrain
            if (OfflineChat.factual(text) || (pb == null && OfflineChat.handles(text))) OfflineChat.reply(text, memory, system)
            else if (pb == null) phoneBrainMissing?.invoke() ?: OfflineChat.reply(text, memory, system)
            else {
                val history = ctx.conversation.toList().dropLast(1).map { it.role to it.text }
                val now = SimpleDateFormat("EEEE d MMMM, h:mm a", Locale.ENGLISH).format(Date(system.nowMs()))
                val facts = memory.relevant(text, 700).joinToString("\n") { (it.key?.let { k -> "$k: " } ?: "") + it.value }
                // Stream: speak each sentence the moment the model finishes it.
                val parts = kotlinx.coroutines.channels.Channel<String>(16)
                val work = scope.async {
                    try { runCatching { pb.answer(text, facts, history, boss, now) { parts.trySend(it) } }.getOrNull() } finally { parts.close() }
                }
                val spoken = ArrayList<String>()
                kotlinx.coroutines.withTimeoutOrNull(ANSWER_TIMEOUT_MS) {
                    for (sentence in parts) {
                        emit(NexusEvent.Reply(sentence, memory.settings.speakResponses, continued = spoken.isNotEmpty()))
                        spoken += sentence
                    }
                }
                if (spoken.isNotEmpty()) {
                    val full = spoken.joinToString(" ")
                    ctx.addTurn("assistant", full); memory.recordTurn("assistant", full)
                    return
                }
                (if (work.isCompleted) work.await() else null)
                    ?: "Sorry $boss, I'm still thinking about the last thing. Ask me again in a moment."
            }
        }
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

    private suspend fun ack(c: Command): String = when (c) {
        is Command.OpenApp -> "Opened ${c.app.takeUnless { it == IntentParser.PREVIOUS_APP }?.let { name -> apps.resolve(name)?.label ?: name } ?: "it"}."
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
        const val PHONE_BRAIN_TIMEOUT_MS = 5000L
        const val ANSWER_TIMEOUT_MS = 30_000L
        const val STEP_TIMEOUT_MS = 30_000L
    }
}

private object BuiltInPackages {
    fun of(name: String): String? = ai.nexus.core.nlu.BuiltInApps.packageFor(name)
}
