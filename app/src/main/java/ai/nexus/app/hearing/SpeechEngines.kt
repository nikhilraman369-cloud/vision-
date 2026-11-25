package ai.nexus.app.hearing

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/** A speech-to-text engine that can transcribe a recorded utterance. */
interface SpeechEngine {
    val name: String
    /** Why it can't run (e.g. no key), or null if it can. */
    fun unavailable(): String?
    suspend fun transcribe(wav: ByteArray, pcm: ByteArray): String
}

/** API keys for the online engines, stored privately on the phone (never sent anywhere except that engine). */
object SpeechKeys {
    private fun prefs(c: Context) = c.getSharedPreferences("nexus_secrets", Context.MODE_PRIVATE)
    fun get(c: Context, k: String): String? = prefs(c).getString(k, null)?.takeIf { it.isNotBlank() }
    fun set(c: Context, k: String, v: String?) = prefs(c).edit().putString(k, v?.trim()).apply()
    const val SARVAM = "sarvam_key"; const val DEEPGRAM = "deepgram_key"; const val GROQ = "groq_key"
}

private fun multipart(url: String, headers: Map<String, String>, fields: Map<String, String>, file: ByteArray, fileField: String = "file"): String {
    val boundary = "----nexus" + System.nanoTime()
    val c = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; doOutput = true; connectTimeout = 8000; readTimeout = 20000
        setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        headers.forEach { (k, v) -> setRequestProperty(k, v) }
    }
    c.outputStream.use { o ->
        fun w(s: String) = o.write(s.toByteArray())
        for ((k, v) in fields) { w("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n") }
        w("--$boundary\r\nContent-Disposition: form-data; name=\"$fileField\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n")
        o.write(file); w("\r\n--$boundary--\r\n")
    }
    val code = c.responseCode
    val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
    if (code !in 200..299) throw IllegalStateException("HTTP $code: ${body.take(160)}")
    return body
}

/** Sarvam AI — speech recognition built for Indian languages and Hindi-English mixing. */
class SarvamEngine(private val ctx: Context, private val model: String = "saarika:v2.5") : SpeechEngine {
    override val name = "Sarvam ($model)"
    override fun unavailable() = if (SpeechKeys.get(ctx, SpeechKeys.SARVAM) == null) "no Sarvam key" else null
    override suspend fun transcribe(wav: ByteArray, pcm: ByteArray) = withContext(Dispatchers.IO) {
        val body = multipart("https://api.sarvam.ai/speech-to-text", mapOf("api-subscription-key" to SpeechKeys.get(ctx, SpeechKeys.SARVAM)!!),
            mapOf("model" to model, "language_code" to "unknown"), wav)
        JSONObject(body).optString("transcript")
    }
}

/** Deepgram Nova-3, multilingual mode (handles switching between Hindi and English). */
class DeepgramEngine(private val ctx: Context) : SpeechEngine {
    override val name = "Deepgram (nova-3 multi)"
    override fun unavailable() = if (SpeechKeys.get(ctx, SpeechKeys.DEEPGRAM) == null) "no Deepgram key" else null
    override suspend fun transcribe(wav: ByteArray, pcm: ByteArray) = withContext(Dispatchers.IO) {
        val c = (URL("https://api.deepgram.com/v1/listen?model=nova-3&language=multi&smart_format=true").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 8000; readTimeout = 20000
            setRequestProperty("Authorization", "Token " + SpeechKeys.get(ctx, SpeechKeys.DEEPGRAM))
            setRequestProperty("Content-Type", "audio/wav")
        }
        c.outputStream.use { it.write(wav) }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw IllegalStateException("HTTP $code: ${body.take(160)}")
        JSONObject(body).getJSONObject("results").getJSONArray("channels").getJSONObject(0).getJSONArray("alternatives").getJSONObject(0).optString("transcript")
    }
}

/** OpenAI's Whisper large-v3 (turbo), served very fast by Groq. Asked to write Hinglish in Latin letters. */
class GroqWhisperEngine(private val ctx: Context, private val model: String = "whisper-large-v3-turbo") : SpeechEngine {
    override val name = "Whisper ($model, Groq)"
    override fun unavailable() = if (SpeechKeys.get(ctx, SpeechKeys.GROQ) == null) "no Groq key" else null
    override suspend fun transcribe(wav: ByteArray, pcm: ByteArray) = withContext(Dispatchers.IO) {
        val body = multipart("https://api.groq.com/openai/v1/audio/transcriptions", mapOf("Authorization" to "Bearer " + SpeechKeys.get(ctx, SpeechKeys.GROQ)),
            mapOf("model" to model, "response_format" to "json", "temperature" to "0",
                "prompt" to "Hinglish phone commands written in Latin letters, e.g.: WhatsApp kholo, Moht ko message karo ki main aa raha hoon, play Skyfall on YouTube Music."), wav)
        JSONObject(body).optString("text").trim()
    }
}

/**
 * The phone's built-in recogniser (what previous NEXUS versions used), fed the same recording.
 * Needs Android 13+ and a recogniser that accepts recorded audio; otherwise it reports that.
 */
class AndroidEngine(private val ctx: Context, private val lang: String) : SpeechEngine {
    override val name = "Phone recogniser ($lang)"
    override fun unavailable() = when {
        Build.VERSION.SDK_INT < 33 -> "needs Android 13+"
        !SpeechRecognizer.isRecognitionAvailable(ctx) -> "no recogniser on this phone"
        else -> null
    }
    override suspend fun transcribe(wav: ByteArray, pcm: ByteArray): String {
        val f = File(ctx.cacheDir, "hearing.pcm").apply { writeBytes(pcm) }
        val main = Handler(Looper.getMainLooper())
        return withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { cont ->
                main.post {
                    val r = SpeechRecognizer.createSpeechRecognizer(ctx)
                    val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                    fun finish(v: String) { runCatching { r.destroy() }; runCatching { pfd.close() }; if (cont.isActive) cont.resume(v) }
                    r.setRecognitionListener(object : RecognitionListener {
                        override fun onResults(b: Bundle?) = finish(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: "")
                        override fun onError(error: Int) = finish("\u0000error $error")
                        override fun onReadyForSpeech(p: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(v: Float) {}
                        override fun onBufferReceived(b: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onPartialResults(b: Bundle?) {}
                        override fun onEvent(t: Int, p: Bundle?) {}
                    })
                    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfd)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                    }
                    runCatching { r.startListening(i) }.onFailure { finish("\u0000error ${it.message}") }
                    cont.invokeOnCancellation { main.post { runCatching { r.destroy() }; runCatching { pfd.close() } } }
                }
            }
        }?.also { if (it.startsWith("\u0000")) throw IllegalStateException(it.drop(1)) } ?: throw IllegalStateException("timed out")
    }
}
