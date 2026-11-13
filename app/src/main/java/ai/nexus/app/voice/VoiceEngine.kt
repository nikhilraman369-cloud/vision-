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
 * Speech in and out. Never listens while speaking (so NEXUS can't hear and
 * obey itself), resumes listening automatically after replies in continuous
 * mode, and supports an optional wake word.
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

    /** Keep listening after each utterance and reply. */
    @Volatile var continuous = false

    // Conversation mode: after each reply, keep listening for a follow-up (no wake word) for a few seconds.
    @Volatile var followUpMs = 8000L
    @Volatile private var conversationUntil = 0L
    fun inConversation() = System.currentTimeMillis() < conversationUntil
    fun startConversation() = main.post { conversationUntil = System.currentTimeMillis() + followUpMs; startListening() }
    fun endConversation() = main.post { conversationUntil = 0; if (!continuous) stopListening() }
    @Volatile var wakeWordEnabled = false
    @Volatile var wakeWord = "hey nexus"
    @Volatile var language = "auto"
    @Volatile var speechRate = 1.0f
    /** After the wake word is heard, follow-ups don't need it for a short while. */
    @Volatile private var awakeUntil = 0L
    @Volatile private var wantListening = false
    @Volatile private var preferOffline = true

    fun init() {
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { main.post { _state.value = VoiceState.SPEAKING; stopRecognizer() } }
                override fun onDone(id: String?) { main.post { afterSpeaking() } }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { main.post { afterSpeaking() } }
            })
        }
    }

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening() = main.post {
        wantListening = true
        if (isSpeaking) { neuralQueue.clear(); cinematic?.stop(); tts?.stop() } // tapping the mic interrupts NEXUS (barge-in)
        startRecognizer()
    }

    fun stopListening() = main.post { wantListening = false; stopRecognizer(); _state.value = VoiceState.OFF }

    fun toggle() { if (_state.value == VoiceState.LISTENING || _state.value == VoiceState.HEARING) stopListening() else startListening() }

    /** Stop talking immediately (voice interruption from the bubble or "stop"). */
    fun shutUp() = main.post { neuralQueue.clear(); cinematic?.stop(); tts?.stop(); afterSpeaking() }

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
        stopRecognizer()
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
        t.speak(text, TextToSpeech.QUEUE_ADD, null, "nexus-${System.nanoTime()}")
    }

    private fun playNextNeural() {
        val c = cinematic ?: return
        val next = neuralQueue.removeFirstOrNull() ?: run { neuralBusy = false; afterSpeaking(); return }
        neuralBusy = true
        val ok = c.speak(next, speechRate, pitch, effects,
            onStart = { main.post { _state.value = VoiceState.SPEAKING; stopRecognizer() } },
            onDone = { main.post { playNextNeural() } })
        if (!ok) { neuralBusy = false; neuralQueue.clear(); useNeural = false; speak(next) }
    }

    val isSpeaking: Boolean get() = neuralBusy || tts?.isSpeaking == true

    private fun afterSpeaking() {
        if (tts?.isSpeaking == true || neuralBusy) return
        _state.value = VoiceState.OFF
        if (inConversation()) conversationUntil = System.currentTimeMillis() + followUpMs
        if (continuous || inConversation()) main.postDelayed({ if (!isSpeaking && (continuous || inConversation())) startRecognizer() }, 350)
    }

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        // en-IN recognises English, Hinglish and Indian names well; hi-IN returns Devanagari (handled by the parser).
        val tag = when (language) { "hi" -> "hi-IN"; "en" -> "en-US"; else -> "en-IN" }
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        // Finish quickly after you stop talking.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 600L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 450L)
        // On-device recognition is faster; falls back to online if the language pack is missing.
        if (preferOffline && !accurate) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    private fun startRecognizer() {
        if (!available) return
        if (tts?.isSpeaking == true) return
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { it.setRecognitionListener(listener) }
        }
        runCatching { recognizer?.startListening(recognizerIntent()) }
        _state.value = VoiceState.LISTENING
    }

    private fun stopRecognizer() {
        runCatching { recognizer?.cancel() }
        if (_state.value == VoiceState.LISTENING || _state.value == VoiceState.HEARING) _state.value = VoiceState.OFF
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { _state.value = VoiceState.LISTENING }
        override fun onBeginningOfSpeech() { _state.value = VoiceState.HEARING }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {
            _partial.value = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            _state.value = VoiceState.OFF
            // Offline speech pack not installed: switch to online recognition and retry once.
            if (preferOffline && (error == SpeechRecognizer.ERROR_SERVER || error == SpeechRecognizer.ERROR_NETWORK ||
                    error == 12 /* ERROR_LANGUAGE_NOT_SUPPORTED */ || error == 13 /* ERROR_LANGUAGE_UNAVAILABLE */)) {
                preferOffline = false
                main.postDelayed({ startRecognizer() }, 100)
                return
            }
            val retry = (continuous || inConversation()) && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT)
            if (retry) main.postDelayed({ startRecognizer() }, if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 600 else 250)
        }

        override fun onResults(results: Bundle?) {
            _state.value = VoiceState.OFF
            _partial.value = ""
            val all = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            val text = if (all.isEmpty()) "" else runCatching { chooseBest(all) }.getOrDefault(all.first())
            if (text.isNotEmpty() && accept(text)) {
                if (inConversation()) conversationUntil = System.currentTimeMillis() + 60_000 // hold the conversation while the command runs
                onUtterance(text)
            }
            else if (continuous || inConversation()) main.postDelayed({ startRecognizer() }, 200)
        }
    }

    /** With a wake word, only act on speech that starts with it (or shortly after it). */
    private fun accept(text: String): Boolean {
        if (!wakeWordEnabled || !continuous) return true
        val t = text.lowercase()
        val now = System.currentTimeMillis()
        val ww = wakeWord.lowercase()
        val heard = t.startsWith(ww) || t.startsWith(ww.removePrefix("hey ")) || t.startsWith("hey next") || t.startsWith("hey lexus")
        if (heard) { awakeUntil = now + 20_000; return t.length > ww.length + 2 || run { speak("Yes?"); false } }
        return now < awakeUntil
    }

    /** Keep the follow-up window open while a conversation is going. */
    fun extendConversation() { if (awakeUntil > System.currentTimeMillis()) awakeUntil = System.currentTimeMillis() + 20_000 }

    /** A short tick + vibration for quick confirmations (faster than speaking). */
    /** After a quick confirmation: in continuous mode, listen again. (Vibration is done by HapticEngine.) */
    fun tick() = main.post {
        if (inConversation()) conversationUntil = System.currentTimeMillis() + followUpMs
        if (continuous || inConversation()) main.postDelayed({ if (!isSpeaking && _state.value == VoiceState.OFF) startRecognizer() }, 250)
    }

    fun release() {
        main.post {
            recognizer?.destroy(); recognizer = null
            tts?.shutdown(); tts = null
        }
    }
}
