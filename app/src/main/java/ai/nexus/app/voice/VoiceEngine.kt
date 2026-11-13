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
 * Listening works in short windows: tap the bubble (or press volume up + down
 * together) and NEXUS listens for a few seconds. After it answers, it listens
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

    fun init() {
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { main.post { _state.value = VoiceState.SPEAKING; pauseRecognizer() } }
                override fun onDone(id: String?) { main.post { if (tts?.isSpeaking != true) systemSpeaking = false; afterSpeaking() } }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { main.post { systemSpeaking = false; afterSpeaking() } }
            })
        }
    }

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** Activate: interrupt any speech and listen for a command. */
    fun startListening() = main.post {
        if (isSpeaking) { neuralQueue.clear(); cinematic?.stop(); tts?.stop(); systemSpeaking = false; neuralBusy = false }
        conversation = true
        openWindow(firstWindowMs)
    }

    fun stopListening() = main.post { closeSession() }

    val listening: Boolean get() = _state.value == VoiceState.LISTENING || _state.value == VoiceState.HEARING

    fun toggle() { if (listening) stopListening() else startListening() }

    /** Stop talking immediately (voice interruption from the bubble or "stop"). */
    fun shutUp() = main.post { neuralQueue.clear(); cinematic?.stop(); tts?.stop(); systemSpeaking = false; neuralBusy = false; afterSpeaking() }

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

    private fun replyFinished() {
        if (!conversation || !awaitingReply || isSpeaking) return
        main.postDelayed({ if (conversation && awaitingReply && !isSpeaking) openWindow(followUpMs) }, 300)
    }

    // ---------------- voice output: neural pack first, then the system British voice
    @Volatile var cinematic: CinematicVoice? = null
    @Volatile var useNeural = true
    @Volatile var pitch = 0.95f
    @Volatile var effects = true
    @Volatile var muted = false
    private val neuralQueue = ArrayDeque<String>()
    @Volatile private var neuralBusy = false
    private var britishVoice: android.speech.tts.Voice? = null

    /** The system's British English male voice, if installed (Google TTS: rjs / gbd / gbb). */
    private fun pickBritishVoice(t: TextToSpeech): android.speech.tts.Voice? = runCatching {
        val vs = t.voices.orEmpty().filter { it.locale.language == "en" && it.locale.country == "GB" && !it.isNetworkConnectionRequired && it.features?.contains("notInstalled") != true }
        vs.firstOrNull { v -> listOf("rjs", "gbd", "gbb", "male").any { v.name.contains(it, true) } } ?: vs.firstOrNull()
    }.getOrNull()

    fun speak(text: String) = main.post {
        if (muted || text.isBlank()) { afterSpeaking(); return@post }
        // Speaking during a listening window: stop listening; we listen again after the reply.
        if (conversation) { awaitingReply = true; windowUntil = 0 }
        pauseRecognizer()
        val c = cinematic
        if (useNeural && c != null && c.ready && !Devanagari.containsDevanagari(text)) {
            neuralQueue.addLast(text)
            if (!neuralBusy) playNextNeural()
            return@post
        }
        val t = tts
        if (t == null || !ttsReady) { afterSpeaking(); return@post }
        if (Devanagari.containsDevanagari(text) || language == "hi") t.language = Locale("hi", "IN")
        else {
            if (britishVoice == null) britishVoice = pickBritishVoice(t)
            britishVoice?.let { v -> runCatching { t.voice = v } } ?: run { t.language = Locale.UK }
        }
        t.setSpeechRate(speechRate)
        t.setPitch(pitch)
        systemSpeaking = true
        _state.value = VoiceState.SPEAKING
        t.speak(text, TextToSpeech.QUEUE_ADD, null, "nexus-${System.nanoTime()}")
    }

    private fun playNextNeural() {
        val c = cinematic ?: return
        val next = neuralQueue.removeFirstOrNull() ?: run { neuralBusy = false; afterSpeaking(); return }
        neuralBusy = true
        _state.value = VoiceState.SPEAKING
        val ok = c.speak(next, speechRate, pitch, effects,
            onStart = { main.post { _state.value = VoiceState.SPEAKING; pauseRecognizer() } },
            onDone = { main.post { playNextNeural() } })
        if (!ok) { neuralBusy = false; neuralQueue.clear(); useNeural = false; speak(next) }
    }

    val isSpeaking: Boolean get() = neuralBusy || systemSpeaking || tts?.isSpeaking == true

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

    private fun startRecognizer() {
        if (!available || isSpeaking) return
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { it.setRecognitionListener(listener) }
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
