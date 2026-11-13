package ai.nexus.app

import android.app.Application
import android.content.Context
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
    lateinit var nexus: Nexus
    lateinit var voice: VoiceEngine
    lateinit var device: AndroidDevice
    lateinit var brainManager: PhoneBrainManager

    private val _teaching = MutableStateFlow(false)
    val teaching: StateFlow<Boolean> = _teaching

    private val _chat = MutableStateFlow<List<ChatLine>>(emptyList())
    val chat: StateFlow<List<ChatLine>> = _chat
    private val _status = MutableStateFlow("Ready")
    val status: StateFlow<String> = _status

    override fun onCreate() {
        super.onCreate()
        memory = LongTermMemory(FileMemoryStorage(File(filesDir, "nexus-memory.json")))
        device = AndroidDevice(this)
        nexus = Nexus(device, AndroidSystemActions(this), memory, brain = buildBrain(), scope = scope)
        voice = VoiceEngine(this, chooseBest = ::bestGuess) { text -> ask(text) }
        voice.init()
        applySettings()

        // Free on-device brain: load it in the background if it has been downloaded.
        brainManager = PhoneBrainManager(this, scope)
        scope.launch {
            brainManager.state.collect { st ->
                nexus.phoneBrain = if (st is PhoneBrainManager.State.Ready) brainManager.brain else null
            }
        }
        brainManager.warmUp()

        scope.launch {
            nexus.events.collect { e ->
                when (e) {
                    is NexusEvent.Reply -> {
                        add(ChatLine(false, e.text))
                        if (e.speak) voice.speak(e.text)
                        voice.extendConversation()
                    }
                    is NexusEvent.StepStarted -> _status.value = if (e.total > 1) "Step ${e.index}/${e.total}: ${e.description}" else e.description
                    is NexusEvent.StepFinished -> if (e.outcome !is Outcome.Success) {
                        add(ChatLine(false, "• ${e.description}: ${e.outcome.message}", detail = true))
                        // Keep what the screen looked like when something went wrong, for "Copy screen info".
                        runCatching { device.snapshot() }.getOrNull()?.takeIf { it.packageName != packageName }?.let { lastAppScreen = it.toPromptText(150) }
                    }
                    is NexusEvent.StateChanged -> _status.value = when (e.state) {
                        AgentState.IDLE -> "Ready"
                        AgentState.THINKING -> "Thinking…"
                        AgentState.EXECUTING -> "Working…"
                        AgentState.PAUSED -> "Paused"
                        AgentState.WAITING_FOR_USER -> "Waiting for you"
                    }
                    is NexusEvent.Error -> add(ChatLine(false, "⚠ ${e.message}", detail = true))
                    is NexusEvent.Ack -> { add(ChatLine(false, e.text.ifBlank { "✓ Done" })); voice.tick() }
                    NexusEvent.HideBubble -> OverlayService.stop(this@NexusApp)
                    is NexusEvent.TeachingChanged -> _teaching.value = e.active
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

    fun ask(text: String) {
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

    private fun buildBrain() = apiKey?.let { runCatching { ClaudeBrain(it, model) }.getOrNull() }

    fun applySettings() {
        val s = memory.settings
        voice.continuous = s.continuousListening
        voice.wakeWordEnabled = s.wakeWordEnabled
        voice.wakeWord = s.wakeWord
        voice.language = s.language
        voice.speechRate = s.speechRate
        voice.accurate = s.accurateListening
    }

    fun onAccessibilityConnected() { scope.launch { nexus.apps.refresh() } }

    companion object {
        fun get(c: Context) = c.applicationContext as NexusApp
    }
}
