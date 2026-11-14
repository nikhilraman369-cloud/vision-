package ai.nexus.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import ai.nexus.core.nlu.Devanagari
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

enum class VoiceState { OFF, LISTENING, HEARING, SPEAKING }

/**
 * Speech in and out.
 *
 * Listening works in short windows: tap the bubble (or the quick-settings tile) and NEXUS listens for a few seconds. After it answers, it listens
 * again for a follow-up for [followUpMs]; if you say nothing, it goes back to
 * sleep. It never listens while it is speaking, so it can't hear itself, and
 * the visible state stays steady while the recogniser restarts in between.
 */
class VoiceEngine(
    private val context: Context,
    /** Picks the best of the recogniser's guesses (e.g. the one NEXUS understands). */
    private val chooseBest: (List<String>) -> String = { it.first() },
    private val onUtterance: (String) -> Unit,
) {
    /** Online recognition: more accurate for Hinglish and names (needs internet). */
    @Volatile var accurate = true
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val _state = MutableStateFlow(VoiceState.OFF)
    val state: StateFlow<VoiceState> = _state
    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial

    /** Kept for settings compatibility; listening is always window-based now. */
    @Volatile var continuous = false
    @Volatile var wakeWordEnabled = false
    @Volatile var wakeWord = "hey nexus"

    /** How long NEXUS keeps listening for a follow-up after it answers. */
    @Volatile var followUpMs = 6000L
    /** How long the first listening window lasts after you activate NEXUS. */
    @Volatile var firstWindowMs = 7000L
    @Volatile var language = "auto"
    @Volatile var speechRate = 1.0f
    @Volatile private var preferOffline = true

    // Listening session
    @Volatile private var conversation = false     // activated; follow-ups allowed
    @Volatile private var windowUntil = 0L         // recogniser may run until then
    @Volatile private var awaitingReply = false    // an utterance is being handled
    @Volatile private var systemSpeaking = false

    fun inConversation() = conversation
    fun startConversation() = startListening()
    fun endConversation() = main.post { closeSession() }
    /** Compatibility no-op (the follow-up window is opened after each reply). */
    fun extendConversation() {}

    // ---- what the engine needs to know about the rest of NEXUS
    /** True while NEXUS is thinking or working (so we don't start listening mid-task). */
    @Volatile var agentBusy: () -> Boolean = { false }
    /** True while NEXUS has asked you something and waits for your answer (we listen longer). */
    @Volatile var agentWaiting: () -> Boolean = { false }

    // ---- problems with speaking, shown to the user instead of failing silently
    private val _issue = MutableStateFlow<String?>(null)
    val issue: StateFlow<String?> = _issue
    private fun fail(why: String) { _issue.value = why }

    fun init() {
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (!ttsReady) fail("the phone's speech engine didn't start (Settings → Text-to-speech output)")
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { main.post { id?.let { sysStarted += it }; _issue.value = null; sysFailures = 0; _state.value = VoiceState.SPEAKING; pauseRecognizer() } }
                override fun onDone(id: String?) { main.post { sysFinished(id) } }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { main.post { fail("the phone's speech engine reported an error"); sysFinished(id) } }
                override fun onError(id: String?, errorCode: Int) { main.post { fail("the phone's speech engine failed (code $errorCode)"); sysFinished(id) } }
            })
        }
    }

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** Activate: interrupt any speech and listen for a command. */
    fun startListening() = main.post {
        if (isSpeaking) silence()
        conversation = true
        openWindow(firstWindowMs)
    }

    fun stopListening() = main.post { closeSession() }

    val listening: Boolean get() = _state.value == VoiceState.LISTENING || _state.value == VoiceState.HEARING

    fun toggle() { if (listening) stopListening() else startListening() }

    /** Stop talking immediately (voice interruption from the bubble or "stop"). */
    fun shutUp() = main.post { silence(); afterSpeaking() }

    /** Cut off all speech and forget anything waiting to be said. */
    private fun silence() {
        neuralToken++
        neuralQueue.clear(); cinematic?.stop(); tts?.stop()
        sysOpen.clear(); sysStarted.clear(); systemSpeaking = false; neuralBusy = false
    }

    private fun openWindow(ms: Long) {
        awaitingReply = false
        windowUntil = System.currentTimeMillis() + ms
        _partial.value = ""
        _state.value = VoiceState.LISTENING
        startRecognizer()
    }

    private fun closeSession() {
        conversation = false; awaitingReply = false; windowUntil = 0
        pauseRecognizer()
        if (_state.value != VoiceState.SPEAKING) _state.value = VoiceState.OFF
        _partial.value = ""
    }

    /** The command (and any reply) is finished: listen for a follow-up, or sleep. */
    fun commandFinished() = main.post { replyFinished() }

    /**
     * One turn = you speak → NEXUS says "On it" → does it → says the result. We only open the
     * follow-up window once NEXUS has finished talking AND finished working, so it never listens
     * in the middle of a task (and never hears itself).
     */
    private fun replyFinished(waitedMs: Long = 0) {
        if (!conversation || !awaitingReply || isSpeaking) return
        if (agentBusy()) {
            if (waitedMs < 90_000) main.postDelayed({ replyFinished(waitedMs + 400) }, 400)
            return
        }
        val ms = if (agentWaiting()) 10_000L else followUpMs
        main.postDelayed({ if (conversation && awaitingReply && !isSpeaking && !agentBusy()) openWindow(ms) }, 350)
    }

    // ---------------- voice output: neural pack first, then the system voice (always something is tried)
    @Volatile var cinematic: CinematicVoice? = null
    @Volatile var useNeural = true
    @Volatile var pitch = 0.95f
    @Volatile var effects = true
    @Volatile var muted = false
    private val neuralQueue = ArrayDeque<String>()
    @Volatile private var neuralBusy = false
    private var neuralToken = 0
    private var neuralStarted = false
    private var britishVoice: android.speech.tts.Voice? = null
    private var triedBritish = false

    // System-voice bookkeeping: which utterances are queued / have started.
    private val sysOpen = HashSet<String>()
    private val sysStarted = HashSet<String>()
    private var sysSeq = 0
    private var sysFailures = 0

    /** The system's British English male voice, if installed (Google TTS: rjs / gbd / gbb). */
    private fun pickBritishVoice(t: TextToSpeech): android.speech.tts.Voice? = runCatching {
        val vs = t.voices.orEmpty().filter { it.locale.language == "en" && it.locale.country == "GB" && !it.isNetworkConnectionRequired && it.features?.contains("notInstalled") != true }
        vs.firstOrNull { v -> listOf("rjs", "gbd", "gbb", "male").any { v.name.contains(it, true) } } ?: vs.firstOrNull()
    }.getOrNull()

    /** Pick the best voice this phone's engine can really speak with; never leave it on a language it lacks. */
    private fun configureSystemVoice(t: TextToSpeech, hindi: Boolean) {
        if (hindi) { if (t.isLanguageAvailable(Locale("hi", "IN")) >= TextToSpeech.LANG_AVAILABLE) { t.language = Locale("hi", "IN"); return } }
        else {
            if (!triedBritish) { britishVoice = pickBritishVoice(t); triedBritish = true }
            val ok = britishVoice?.let { v -> runCatching { t.setVoice(v) == TextToSpeech.SUCCESS }.getOrDefault(false) } ?: false
            if (ok) return
        }
        for (loc in listOf(Locale.UK, Locale("en", "IN"), Locale.US, Locale.ENGLISH)) {
            if (t.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) { t.language = loc; return }
        }
        // Otherwise leave the engine's own default voice.
    }

    fun speak(text: String) = main.post {
        if (muted) { afterSpeaking(); return@post }
        if (text.isBlank()) { afterSpeaking(); return@post }
        // Speaking during a listening window: stop listening; we listen again after the reply.
        if (conversation) { awaitingReply = true; windowUntil = 0 }
        pauseRecognizer()
        val c = cinematic
        if (useNeural && c != null && c.ready && !Devanagari.containsDevanagari(text)) {
            neuralQueue.addLast(text)
            if (!neuralBusy) playNextNeural()
            return@post
        }
        speakSystem(text)
    }

    private fun speakSystem(text: String) {
        val t = tts
        if (t == null || !ttsReady) {
            fail("the phone's speech engine isn't ready (Settings → Text-to-speech output)")
            afterSpeaking(); return
        }
        runCatching {
            configureSystemVoice(t, Devanagari.containsDevanagari(text) || language == "hi")
            t.setSpeechRate(speechRate)
            t.setPitch(pitch)
        }
        val id = "nexus-${++sysSeq}"
        sysOpen += id
        systemSpeaking = true
        _state.value = VoiceState.SPEAKING
        val r = runCatching { t.speak(text, TextToSpeech.QUEUE_ADD, null, id) }.getOrDefault(TextToSpeech.ERROR)
        if (r != TextToSpeech.SUCCESS) { fail("the phone's speech engine refused to speak"); sysFinished(id); return }
        // If the engine never starts (silent failure), don't leave NEXUS "speaking" forever.
        main.postDelayed({
            if (id in sysOpen && id !in sysStarted) {
                runCatching { t.stop() }
                if (++sysFailures >= 1) fail("the phone's speech engine didn't make any sound. Install or enable Google \"Speech Services\" text-to-speech")
                sysFinished(id)
            }
        }, 5000)
    }

    private fun sysFinished(id: String?) {
        id?.let { sysOpen.remove(it); sysStarted.remove(it) }
        if (sysOpen.isEmpty()) systemSpeaking = false
        afterSpeaking()
    }

    private fun playNextNeural() {
        val c = cinematic ?: return
        val next = neuralQueue.removeFirstOrNull() ?: run { neuralBusy = false; afterSpeaking(); return }
        neuralBusy = true
        neuralStarted = false
        val token = ++neuralToken
        _state.value = VoiceState.SPEAKING
        val ok = c.speak(next, speechRate, pitch, effects,
            onStart = { main.post { if (token == neuralToken) { neuralStarted = true; _issue.value = null; _state.value = VoiceState.SPEAKING; pauseRecognizer() } } },
            onDone = { main.post { if (token == neuralToken) playNextNeural() } },
            onFail = { why -> main.post { if (token == neuralToken) neuralFailed(next, why) } })
        if (!ok) { neuralFailed(next, "the voice isn't loaded"); return }
        // The first sentence should start within a few seconds; if not, use the phone's voice.
        main.postDelayed({ if (token == neuralToken && !neuralStarted) { cinematic?.stop(); neuralFailed(next, "the voice took too long to start") } }, 10_000)
    }

    /** The neural voice made no sound: say it (and anything queued) with the phone's voice instead. */
    private fun neuralFailed(text: String, why: String) {
        neuralToken++
        neuralBusy = false
        useNeural = false
        fail("the downloaded voice couldn't play ($why), so I'm using the phone's voice")
        val rest = neuralQueue.toList(); neuralQueue.clear()
        speakSystem(text)
        rest.forEach { speakSystem(it) }
    }

    /** For the "Test voice" button: speaks and reports what happened. */
    fun diagnose(): String = buildString {
        append(if (muted) "Voice is muted. " else "")
        append(if (useNeural && cinematic?.ready == true) "Neural voice: loaded. " else "Neural voice: not in use" + (cinematic?.lastError?.let { " ($it)" } ?: "") + ". ")
        append(if (ttsReady) "Phone voice: ready. " else "Phone voice: NOT ready. ")
        _issue.value?.let { append("Last problem: $it. ") }
        bluetoothHint()?.let { append(it) }
    }

    /** A connected Bluetooth speaker/earbuds takes the sound away from the phone speaker. */
    fun bluetoothHint(): String? = runCatching {
        val am = context.getSystemService(android.media.AudioManager::class.java)
        val bt = am?.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)?.any {
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == 26 /* BLE headset */ || it.type == 27 /* BLE speaker */
        }
        if (bt == true) "A Bluetooth audio device is connected, so my voice may be playing there instead of the phone speaker." else null
    }.getOrNull()

    val isSpeaking: Boolean get() = neuralBusy || systemSpeaking

    private fun afterSpeaking() {
        if (isSpeaking) return
        if (_state.value == VoiceState.SPEAKING) _state.value = VoiceState.OFF
        replyFinished()
    }

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        // en-IN recognises English, Hinglish and Indian names well; hi-IN returns Devanagari (handled by the parser).
        val tag = when (language) { "hi" -> "hi-IN"; "en" -> "en-US"; else -> "en-IN" }
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        // Give you time to finish your sentence (short pauses no longer cut you off).
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1100L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
        if (preferOffline && !accurate) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    /**
     * Normally the phone's default recogniser. If NEXUS itself has become the default (because you made it the
     * digital assistant), pick the phone's real one (Google's if present) so listening keeps working.
     */
    private fun createRecognizer(): SpeechRecognizer {
        val own = context.packageName
        val configured = runCatching { android.provider.Settings.Secure.getString(context.contentResolver, "voice_recognition_service") }.getOrNull()
        if (configured == null || !configured.startsWith("$own/")) return SpeechRecognizer.createSpeechRecognizer(context)
        val comp = runCatching {
            val all = context.packageManager.queryIntentServices(Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0).mapNotNull { it.serviceInfo }.filter { it.packageName != own }
            (all.firstOrNull { it.packageName == "com.google.android.googlequicksearchbox" } ?: all.firstOrNull { it.packageName.startsWith("com.google") } ?: all.firstOrNull())
                ?.let { android.content.ComponentName(it.packageName, it.name) }
        }.getOrNull()
        return if (comp != null) SpeechRecognizer.createSpeechRecognizer(context, comp) else SpeechRecognizer.createSpeechRecognizer(context)
    }

    private fun startRecognizer() {
        if (!available || isSpeaking) return
        if (recognizer == null) {
            recognizer = createRecognizer().also { it.setRecognitionListener(listener) }
        }
        runCatching { recognizer?.startListening(recognizerIntent()) }
            .onFailure { main.postDelayed({ if (windowOpen()) startRecognizer() }, 400) }
    }

    /** Stops the recogniser without changing what the bubble shows. */
    private fun pauseRecognizer() { runCatching { recognizer?.cancel() } }

    private fun windowOpen() = conversation && !awaitingReply && System.currentTimeMillis() < windowUntil

    /** Window over with nothing heard: go to sleep. */
    private fun windowExpired() {
        conversation = false; windowUntil = 0
        if (_state.value == VoiceState.LISTENING || _state.value == VoiceState.HEARING) _state.value = VoiceState.OFF
        _partial.value = ""
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { if (windowOpen()) _state.value = VoiceState.LISTENING }
        override fun onBeginningOfSpeech() { if (windowOpen()) { _state.value = VoiceState.HEARING; windowUntil = maxOf(windowUntil, System.currentTimeMillis() + 15_000) } }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {
            _partial.value = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            if (!windowOpen()) { if (!awaitingReply && !isSpeaking && windowUntil != 0L) windowExpired(); return }
            // Offline speech pack not installed: switch to online recognition and retry.
            if (preferOffline && (error == SpeechRecognizer.ERROR_SERVER || error == SpeechRecognizer.ERROR_NETWORK ||
                    error == 12 /* ERROR_LANGUAGE_NOT_SUPPORTED */ || error == 13 /* ERROR_LANGUAGE_UNAVAILABLE */)) {
                preferOffline = false
                main.postDelayed({ if (windowOpen()) startRecognizer() }, 100)
                return
            }
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { windowExpired(); return }
            // Nothing heard yet: quietly keep listening until the window closes (no flicker).
            val left = windowUntil - System.currentTimeMillis()
            if (left > 1200) {
                if (_state.value == VoiceState.HEARING) _state.value = VoiceState.LISTENING
                main.postDelayed({ if (windowOpen()) startRecognizer() }, if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 500 else 150)
            } else windowExpired()
        }

        override fun onResults(results: Bundle?) {
            val all = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            val text = if (all.isEmpty()) "" else runCatching { chooseBest(all) }.getOrDefault(all.first())
            _partial.value = ""
            if (text.isNotEmpty()) {
                awaitingReply = true; windowUntil = 0
                _state.value = VoiceState.OFF
                onUtterance(text)
            } else if (windowOpen() && windowUntil - System.currentTimeMillis() > 1200) main.postDelayed({ if (windowOpen()) startRecognizer() }, 150)
            else windowExpired()
        }
    }

    /** A quick confirmation finished (tick + vibration): listen for a follow-up. */
    fun tick() = main.post { replyFinished() }

    fun release() {
        main.post {
            recognizer?.destroy(); recognizer = null
            tts?.shutdown(); tts = null
        }
    }
}
