package ai.nexus.app

import android.app.Application
import android.content.Context
import android.content.Intent
import ai.nexus.app.brain.PhoneBrainManager
import ai.nexus.app.device.AndroidDevice
import ai.nexus.app.service.OverlayService
import ai.nexus.app.device.AndroidSystemActions
import ai.nexus.app.voice.VoiceEngine
import ai.nexus.core.agent.AgentState
import ai.nexus.core.agent.Nexus
import ai.nexus.core.agent.NexusEvent
import ai.nexus.core.agent.Outcome
import ai.nexus.core.llm.ClaudeBrain
import ai.nexus.core.memory.FileMemoryStorage
import ai.nexus.core.memory.LongTermMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

data class ChatLine(val fromUser: Boolean, val text: String, val detail: Boolean = false)

/** Owns the single NEXUS instance shared by the app screen, the bubble and voice. */
class NexusApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var memory: LongTermMemory
    lateinit var life: ai.nexus.core.life.LifeEngine
    lateinit var systemActions: AndroidSystemActions
    lateinit var voicePacks: ai.nexus.app.voice.VoicePackManager
    lateinit var cinematic: ai.nexus.app.voice.CinematicVoice
    val sounds = ai.nexus.app.voice.SoundFx()

    private val _stuck = MutableStateFlow<NexusEvent.Stuck?>(null)
    /** Set while NEXUS is waiting to be taught; the bubble shows the stuck card. */
    val stuck: StateFlow<NexusEvent.Stuck?> = _stuck
    private val _mode = MutableStateFlow<String?>(null)
    val mode: StateFlow<String?> = _mode
    lateinit var nexus: Nexus
    lateinit var voice: VoiceEngine
    lateinit var device: AndroidDevice
    lateinit var brainManager: PhoneBrainManager
    lateinit var voskModels: ai.nexus.app.voice.VoskModelManager
    lateinit var haptics: ai.nexus.app.voice.HapticEngine

    /** Set while hands-free ("Hey Nexus") is running: opens its command window. */
    @Volatile var handsFreeActivate: (() -> Unit)? = null

    private val _teaching = MutableStateFlow(false)
    val teaching: StateFlow<Boolean> = _teaching

    private val _chat = MutableStateFlow<List<ChatLine>>(emptyList())
    val chat: StateFlow<List<ChatLine>> = _chat
    private val _status = MutableStateFlow("Ready")
    val status: StateFlow<String> = _status

    override fun onCreate() {
        super.onCreate()
        // Everything is stored encrypted on the phone (AES-GCM, key in the Android Keystore).
        memory = LongTermMemory(ai.nexus.app.util.EncryptedStorage(File(filesDir, "nexus-memory.json")))
        life = ai.nexus.core.life.LifeEngine(ai.nexus.core.life.LifeStore(ai.nexus.app.util.EncryptedStorage(File(filesDir, "nexus-life.json"))), memory)
        installCrashLog()
        device = AndroidDevice(this)
        systemActions = AndroidSystemActions(this)
        nexus = Nexus(device, systemActions, memory, brain = buildBrain(), scope = scope, life = life)
        voice = VoiceEngine(this, chooseBest = ::bestGuess) { text -> ask(text) }
        voice.init()
        voice.agentBusy = { nexus.state.value == AgentState.THINKING || nexus.state.value == AgentState.EXECUTING }
        voice.agentWaiting = { nexus.state.value == AgentState.WAITING_FOR_USER }
        migrateTo21()
        migrateTo23()
        applySettings()

        // Free on-device brain: load it in the background if it has been downloaded.
        brainManager = PhoneBrainManager(this, scope)
        scope.launch {
            brainManager.state.collect { st ->
                nexus.phoneBrain = if (st is PhoneBrainManager.State.Ready) brainManager.brain else null
            }
        }
        brainManager.warmUp()
        nexus.phoneBrainMissing = {
            val boss = memory.settings.addressAs
            when (val st = brainManager.state.value) {
                is PhoneBrainManager.State.Downloading -> "My brain is still downloading, $boss. ${st.percent}% done. Ask me again when it finishes."
                PhoneBrainManager.State.Loading -> "Give me a few seconds, $boss. I'm still waking my brain up."
                else -> null
            }
        }
        haptics = ai.nexus.app.voice.HapticEngine(this)
        voskModels = ai.nexus.app.voice.VoskModelManager(this, scope)
        voskModels.refresh(memory.settings.speechModel)
        nexus.selfPackage = packageName
        _mode.value = life.data.activeMode
        voicePacks = ai.nexus.app.voice.VoicePackManager(this, scope)
        cinematic = ai.nexus.app.voice.CinematicVoice(this, voicePacks)
        voice.cinematic = cinematic
        loadVoice()
        life.store.cleanup()
        scope.launch { refreshVocabulary() }
        nexus.onSchedule = { a -> runCatching { ai.nexus.app.life.ReminderScheduler.scheduleAction(this@NexusApp, a) } }
        nexus.onUnschedule = { id -> runCatching { ai.nexus.app.life.ReminderScheduler.cancelAction(this@NexusApp, id) } }
        runCatching { ai.nexus.app.life.ReminderScheduler.scheduleAll(this@NexusApp) }

        // If speaking ever fails, say so in the chat (once per reason) instead of staying silent.
        scope.launch {
            var lastShown: String? = null
            voice.issue.collect { why ->
                if (why != null && why != lastShown) {
                    lastShown = why
                    add(ChatLine(false, "⚠ I couldn't speak out loud: $why." + (voice.bluetoothHint()?.let { " $it" } ?: "") + " Open Panel → Voice → Test voice to check.", detail = true))
                } else if (why == null) lastShown = null
            }
        }

        scope.launch {
            nexus.events.collect { e ->
                when (e) {
                    is NexusEvent.Reply -> {
                        val last = _chat.value.lastOrNull()
                        if (e.continued && last != null && !last.fromUser) _chat.value = _chat.value.dropLast(1) + last.copy(text = last.text + " " + e.text)
                        else add(ChatLine(false, e.text))
                        if (e.speak) voice.speak(e.text)
                    }
                    is NexusEvent.StepStarted -> _status.value = if (e.total > 1) "Step ${e.index}/${e.total}: ${e.description}" else e.description
                    is NexusEvent.StepFinished -> if (e.outcome !is Outcome.Success) {
                        add(ChatLine(false, "• ${e.description}: ${e.outcome.message}", detail = true))
                        if (e.outcome is Outcome.Failed) haptics.error()
                        // Keep what the screen looked like when something went wrong, for "Copy screen info".
                        runCatching { device.snapshot() }.getOrNull()?.takeIf { it.packageName != packageName }?.let { lastAppScreen = it.toPromptText(150) }
                    }
                    is NexusEvent.StateChanged -> _status.value = when (e.state.also { if (it == AgentState.THINKING && memory.settings.earcons) sounds.thinking() }.also { if (it == AgentState.IDLE) scope.launch { kotlinx.coroutines.delay(700); if (nexus.state.value == AgentState.IDLE) voice.commandFinished() } }) {
                        AgentState.IDLE -> "Ready"
                        AgentState.THINKING -> "Thinking…"
                        AgentState.EXECUTING -> "Working…"
                        AgentState.PAUSED -> "Paused"
                        AgentState.WAITING_FOR_USER -> "Waiting for you"
                    }
                    is NexusEvent.Error -> { add(ChatLine(false, "⚠ ${e.message}", detail = true)); voice.commandFinished() }
                    is NexusEvent.Ack -> { if (e.text.isNotBlank()) add(ChatLine(false, e.text)); haptics.success(); if (memory.settings.earcons) sounds.success(); voice.tick() }
                    NexusEvent.HideBubble -> OverlayService.stop(this@NexusApp)
                    is NexusEvent.TeachingChanged -> { _teaching.value = e.active; if (!e.active) _stuck.value = null; teachNotification(e.active) }
                    is NexusEvent.Stuck -> { _stuck.value = e; haptics.error(); if (memory.settings.earcons) sounds.error() }
                    is NexusEvent.ModeChanged -> _mode.value = e.mode
                    is NexusEvent.VoiceMuted -> { voice.muted = e.muted; if (e.muted) voice.shutUp() }
                }
            }
        }
    }

    /** Element list of the last non-NEXUS screen, for "Copy screen info". */
    @Volatile var lastAppScreen: String? = null

    /**
     * The speech engine gives several guesses for what you said. Take the first
     * one NEXUS fully understands, instead of blindly trusting guess number one.
     */
    private fun bestGuess(options: List<String>): String {
        fun understood(t: String) = nexus.parser.parse(t).steps.let { st ->
            st.isNotEmpty() && st.none { it.command is ai.nexus.core.nlu.Command.Agentic || it.command is ai.nexus.core.nlu.Command.Chat }
        }
        return options.firstOrNull(::understood) ?: options.first()
    }

    /** Bubble tap / mic button: listen (tap again to stop). */
    fun talk() {
        if (voice.listening) { voice.stopListening(); return }
        if (memory.settings.earcons) sounds.activate()
        voice.startListening()
    }

    /** Quick-settings tile / any external trigger: start listening from anywhere. */
    fun quickListen() {
        haptics.success()
        if (memory.settings.earcons) sounds.activate()
        voice.startListening()
    }

    /** Double-tap: a continuous conversation (keeps listening after each reply for a few seconds). */
    fun startConversation() { if (memory.settings.earcons) sounds.activate(); voice.startConversation() }

    /** "Note" chip: whatever you say next is captured to the inbox. */
    @Volatile var pendingPrefix: String? = null
    fun talkWithPrefix(prefix: String) { pendingPrefix = prefix; talk() }

    fun rebuildBrain() { nexus.brain = buildBrain() }

    fun notifyHandsFreeError(message: String) { add(ChatLine(false, "⚠ $message", detail = true)); haptics.error() }

    /** Start "Hey Nexus" if the user turned it on (must be called while the app is in front). */
    fun ensureHandsFree(context: android.content.Context) {
        // Always-on "Hey Nexus" was replaced by bubble tap / quick-settings tile (simpler, no flicker).
        val want = false
        if (want && ai.nexus.app.voice.NexusVoiceService.instance == null) ai.nexus.app.voice.NexusVoiceService.start(context)
        if (!want && ai.nexus.app.voice.NexusVoiceService.instance != null) ai.nexus.app.voice.NexusVoiceService.stop(context)
    }

    /** Speak something NEXUS starts on its own (reminders, briefs, insights). */
    fun speakProactive(text: String) {
        add(ChatLine(false, text))
        if (memory.settings.speakResponses && !memory.settings.voiceMuted) voice.speak(text)
    }

    fun clearStuck() { _stuck.value = null }

    /** "Done" while teaching: save now if it has a name, else ask for one. */
    fun finishTeachingUi(ctx: Context) {
        if (nexus.teachingName != null) nexus.finishTeaching()
        else ctx.startActivity(Intent(ctx, ai.nexus.app.ui.TeachActivity::class.java).putExtra("finish", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    /** An ongoing notification with Done / Cancel while teaching: works even if the bubble isn't shown. */
    private fun teachNotification(active: Boolean) {
        val nm = getSystemService(android.app.NotificationManager::class.java) ?: return
        if (!active) { nm.cancel(TEACH_NOTIF); return }
        nm.createNotificationChannel(android.app.NotificationChannel("nexus_teach", "NEXUS teaching", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        val done = android.app.PendingIntent.getActivity(this, 31, Intent(this, ai.nexus.app.ui.TeachActivity::class.java).putExtra("finish", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = android.app.PendingIntent.getBroadcast(this, 32, Intent(this, ai.nexus.app.life.LifeReceiver::class.java).setAction(ai.nexus.app.life.LifeReceiver.ACTION_TEACH_CANCEL),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(TEACH_NOTIF, android.app.Notification.Builder(this, "nexus_teach").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("● NEXUS is watching").setContentText("Do the task yourself, then tap Done.")
            .addAction(android.app.Notification.Action.Builder(null, "Done ✓", done).build())
            .addAction(android.app.Notification.Action.Builder(null, "Cancel", cancel).build())
            .setOngoing(true).build())
    }

    /** A scheduled action is due. Behind a PIN lock it waits for you to unlock (via a notification). */
    fun runScheduledAction(id: String, force: Boolean = false) {
        val a = life.data.scheduled.firstOrNull { it.id == id && !it.done } ?: return
        val km = getSystemService(android.app.KeyguardManager::class.java)
        if (!force && km?.isKeyguardLocked == true && km.isKeyguardSecure && ai.nexus.app.voice.ScreenWakeController.needsScreen(this, a.text)) {
            ai.nexus.app.life.ReminderScheduler.notifyRunWhenUnlocked(this, id, a.text); return
        }
        getSystemService(android.app.NotificationManager::class.java)?.cancel(id.hashCode())
        add(ChatLine(false, "⏰ Scheduled: ${a.text}", detail = true))
        ai.nexus.app.voice.ScreenWakeController.prepareFor(this, this, a.text) { nexus.runScheduled(id) }
    }

    /** Load the chosen neural voice pack if it's downloaded. */
    private val _voiceStatus = MutableStateFlow("")
    /** One line about the butler voice: not downloaded / loading / ready / why it failed. */
    val voiceStatus: StateFlow<String> = _voiceStatus

    /**
     * Load the neural voice. If the selected pack isn't downloaded but another one is, switch to that
     * one (otherwise nothing would ever load). [onResult] is called with true when it's ready to speak.
     */
    fun loadVoice(onResult: (Boolean) -> Unit = {}) {
        val s = memory.settings
        voice.useNeural = s.voiceEngine == "neural"
        if (s.voiceEngine != "neural") { _voiceStatus.value = "Using the phone's own voice."; onResult(false); return }
        var id = s.voicePack
        if (!voicePacks.installed(id)) {
            val other = ai.nexus.app.voice.VoicePackManager.PACKS.firstOrNull { voicePacks.installed(it.id) }
            if (other == null) { _voiceStatus.value = "No voice downloaded yet, so the phone's own voice is speaking. Download a pack below."; voice.useNeural = false; onResult(false); return }
            id = other.id
            memory.updateSettings { it.copy(voicePack = id) }
        }
        val title = voicePacks.pack(id)?.title ?: id
        _voiceStatus.value = "Loading $title… (a few seconds)"
        cinematic.load(id) { ok ->
            if (!ok) voice.useNeural = false
            _voiceStatus.value = if (ok) "✅ Butler voice ready: $title" else "⚠ The downloaded voice couldn't start: ${cinematic.lastError ?: "unknown reason"}. Using the phone's voice."
            onResult(ok)
        }
    }

    /** Contacts, apps and remembered people feed the speech corrector. */
    suspend fun refreshVocabulary() {
        val contacts = runCatching { systemActions.allContactNames() }.getOrDefault(emptyList())
        nexus.refreshVocabulary(contacts + nexus.apps.allLabels())
    }

    /** JSON from the configured LLM (Claude), or null when the provider is local/off. */
    suspend fun llmJson(prompt: String): org.json.JSONObject? {
        val b = nexus.brain as? ClaudeBrain ?: return null
        if (memory.settings.llmProvider != "claude") return null
        val out = runCatching { b.complete("Return only valid JSON.", prompt, 800) }.getOrNull() ?: return null
        return runCatching { org.json.JSONObject(out.substring(out.indexOf('{'), out.lastIndexOf('}') + 1)) }.getOrNull()
    }

    suspend fun llmText(prompt: String): String? {
        val b = nexus.brain as? ClaudeBrain ?: return null
        if (memory.settings.llmProvider != "claude") return null
        return runCatching { b.complete("You are NEXUS, a concise writing assistant.", prompt, 1200) }.getOrNull()
    }

    /** Crashes are written to the diagnostics log, so they can be reviewed in the app. */
    private fun installCrashLog() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { File(filesDir, "crash.txt").appendText("${java.util.Date()} [${t.name}] ${e.stackTraceToString().take(4000)}\n\n") }
            prev?.uncaughtException(t, e)
        }
    }

    fun ask(text: String) {
        val n = ai.nexus.core.nlu.Fuzzy.normalize(text)
        if (n in setOf("that s all", "thats all", "that is all", "bas", "bas itna hi", "thank you that s all", "ok that s all", "goodbye", "bye")) {
            voice.endConversation(); add(ChatLine(true, text)); speakProactive("Anytime, ${memory.settings.addressAs}."); return
        }
        pendingPrefix?.let { p -> pendingPrefix = null; if (!text.startsWith(p, true)) { ask(p + text); return } }
        if (!text.equals("watch me", true)) scope.launch {
            runCatching { device.snapshot() }.getOrNull()?.takeIf { it.packageName != packageName }?.let { lastAppScreen = it.toPromptText(150) }
        }
        add(ChatLine(true, text))
        nexus.submit(text)
    }

    private fun add(line: ChatLine) { _chat.value = (_chat.value + line).takeLast(200) }

    fun clearChat() { _chat.value = emptyList() }

    // ------------------------------------------------------------ settings

    private val secrets get() = getSharedPreferences("nexus_secrets", Context.MODE_PRIVATE)

    var apiKey: String?
        get() = secrets.getString("anthropic_api_key", null)?.takeIf { it.isNotBlank() }
        set(v) { secrets.edit().putString("anthropic_api_key", v?.trim()).apply(); nexus.brain = buildBrain() }

    var model: String
        get() = secrets.getString("model", ClaudeBrain.DEFAULT_MODEL) ?: ClaudeBrain.DEFAULT_MODEL
        set(v) { secrets.edit().putString("model", v.trim()).apply(); nexus.brain = buildBrain() }

    private fun buildBrain() = apiKey?.takeIf { memory.settings.llmProvider == "claude" }?.let { runCatching { ClaudeBrain(it, model) }.getOrNull() }

    /** 2.1: simpler listening. Turn off the old always-on modes once. */
    private fun migrateTo21() {
        val prefs = getSharedPreferences("nexus", MODE_PRIVATE)
        if (prefs.getBoolean("migrated21", false)) return
        memory.updateSettings { it.copy(handsFree = false, continuousListening = false, wakeWordEnabled = false, followUpSeconds = 6,
            speakResponses = true, accurateListening = true, usePhoneBrain = true, volumeKeys = true) }
        prefs.edit().putBoolean("migrated21", true).apply()
    }

    /** 2.3: NEXUS talks through every task ("On it" → result). Make sure speech is on and never "quiet". */
    private fun migrateTo23() {
        val prefs = getSharedPreferences("nexus", MODE_PRIVATE)
        if (prefs.getBoolean("migrated23", false)) return
        memory.updateSettings { it.copy(quietAcks = false, spokenAcks = true, speakResponses = true, voiceMuted = false) }
        prefs.edit().putBoolean("migrated23", true).apply()
    }

    fun applySettings() {
        val s = memory.settings
        voice.continuous = s.continuousListening
        voice.wakeWordEnabled = s.wakeWordEnabled
        voice.wakeWord = s.wakeWord
        voice.language = s.language
        voice.speechRate = s.speechRate
        voice.accurate = s.accurateListening
        voice.followUpMs = s.followUpSeconds * 1000L
        voice.pitch = s.voicePitch
        voice.effects = s.voiceEffects
        voice.muted = s.voiceMuted
    }

    fun onAccessibilityConnected() { scope.launch { nexus.apps.refresh() } }

    companion object {
        private const val TEACH_NOTIF = 4242
        fun get(c: Context) = c.applicationContext as NexusApp
    }
}
