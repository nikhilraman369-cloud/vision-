package ai.nexus.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import ai.nexus.app.hearing.SpeechKeys
import ai.nexus.core.nlu.Devanagari
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * NEXUS's online voice: Sarvam Bulbul v4 (streaming). Raw 24 kHz PCM is played the moment the first
 * bytes arrive, and the next sentences are fetched while the current one plays, so there is no gap.
 * Same contract as [CinematicVoice.speak]: onStart when sound begins, onDone at the end, onFail if no sound.
 */
class SarvamVoice(private val context: Context) {
    companion object {
        const val URL_STREAM = "https://api.sarvam.ai/text-to-speech/stream"
        const val RATE = 24000
        private val END = ByteArray(0)
    }

    @Volatile var lastError: String? = null
        private set
    private val fetchers = Executors.newFixedThreadPool(3)
    private val player = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger(0)
    @Volatile private var track: AudioTrack? = null
    private val am = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    /** Audio already being fetched for upcoming text (from [prefetch]). */
    private val ahead = ConcurrentHashMap<String, LinkedBlockingQueue<ByteArray>>()

    val ready: Boolean get() = SpeechKeys.get(context, SpeechKeys.SARVAM) != null

    private fun sentences(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (i in text.indices) {
            sb.append(text[i])
            val c = text[i]
            if ((c == '.' || c == '!' || c == '?') && (i + 1 == text.length || text[i + 1] == ' ') && sb.length > 25) { out += sb.toString().trim(); sb.clear() }
        }
        if (sb.isNotBlank()) out += sb.toString().trim()
        return out.filter { it.isNotBlank() }
    }

    /** Start fetching audio for text that will be spoken next (no sound yet). */
    fun prefetch(text: String, speed: Float = 1f) {
        val first = sentences(text).firstOrNull() ?: return
        if (ahead.containsKey(first)) return
        if (ahead.size > 6) ahead.clear()
        ahead[first] = fetch(first, speed, generation.get(), cancellable = false)
    }

    private fun fetch(sentence: String, speed: Float, gen: Int, cancellable: Boolean = true): LinkedBlockingQueue<ByteArray> {
        val q = LinkedBlockingQueue<ByteArray>()
        fetchers.execute {
            var conn: HttpURLConnection? = null
            try {
                val key = SpeechKeys.get(context, SpeechKeys.SARVAM) ?: error("no Sarvam key")
                val style = Persona.style(context)
                fun body(model: String, speaker: String) = JSONObject()
                    .put("text", sentence)
                    .put("language_code", if (Devanagari.containsDevanagari(sentence)) "hi-IN" else "en-IN")
                    .put("speaker", speaker).put("model", model)
                    .put("pace", (style.pace * speed).coerceIn(0.5, 2.0))
                    .put("speech_sample_rate", RATE)
                    .put("output_audio_codec", "linear16")
                    .put("temperature", style.temperature)
                    .apply { if (model == "bulbul:v3") put("pitch", style.pitch) }   // fine-tune the v3 voice; v4 personas already have their character
                fun open(b: JSONObject): HttpURLConnection = (URL(URL_STREAM).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 5000; readTimeout = 15_000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("api-subscription-key", key)
                    outputStream.use { it.write(b.toString().toByteArray()) }
                }
                // The chosen v4 persona voice; if refused, this style's v3 voice (with pitch), then without pitch.
                val attempts = listOf(
                    body("bulbul:v4-flash", Persona.voice(context, style)),
                    body("bulbul:v3", style.fallbackV3),
                    body("bulbul:v3", style.fallbackV3).apply { remove("pitch") },
                )
                for ((i, b) in attempts.withIndex()) {
                    conn?.disconnect()
                    conn = open(b)
                    if ((conn.responseCode == 400 || conn.responseCode == 422) && i < attempts.lastIndex) {
                        lastError = "voice refused (${b.optString("model")}): " + conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty().take(120)
                        continue
                    }
                    break
                }
                conn!!
                if (conn.responseCode !in 200..299) {
                    val why = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    error("Sarvam voice ${conn.responseCode}: ${why.take(140)}")
                }
                val input = conn.inputStream
                val buf = ByteArray(4096)
                var header = true
                var carry: Byte? = null
                val head = java.io.ByteArrayOutputStream()
                while (!cancellable || generation.get() == gen) {
                    val n = input.read(buf)
                    if (n < 0) break
                    var chunk = buf.copyOf(n)
                    if (header) {
                        // Usually raw PCM; if a WAV header comes first, skip it.
                        head.write(chunk)
                        val h = head.toByteArray()
                        if (h.size < 12) continue
                        chunk = if (String(h, 0, 4, Charsets.US_ASCII) == "RIFF") {
                            val idx = (0 until h.size - 8).firstOrNull { h[it] == 'd'.code.toByte() && h[it + 1] == 'a'.code.toByte() && h[it + 2] == 't'.code.toByte() && h[it + 3] == 'a'.code.toByte() }
                                ?: if (h.size < 512) continue else 44
                            h.copyOfRange(minOf(h.size, idx + 8), h.size)
                        } else h
                        header = false
                    }
                    carry?.let { chunk = byteArrayOf(it) + chunk; carry = null }
                    if (chunk.size % 2 == 1) { carry = chunk.last(); chunk = chunk.copyOf(chunk.size - 1) }
                    if (chunk.isNotEmpty()) q.put(chunk)
                }
            } catch (e: Throwable) {
                lastError = e.message ?: e.javaClass.simpleName
            } finally {
                runCatching { conn?.disconnect() }
                q.put(END)
            }
        }
        return q
    }

    fun speak(text: String, speed: Float, onStart: () -> Unit, onDone: () -> Unit, onFail: (String) -> Unit = {}): Boolean {
        if (!ready) return false
        val gen = generation.incrementAndGet()
        val parts = sentences(text)
        if (parts.isEmpty()) { onDone(); return true }
        // Start fetching every sentence now (the first may already be on its way from prefetch).
        val queues = parts.map { s -> ahead.remove(s) ?: fetch(s, speed, gen) }
        player.execute {
            var started = false
            var frames = 0L
            try {
                requestFocus()
                for (q in queues) {
                    while (generation.get() == gen) {
                        val chunk = q.poll(12, TimeUnit.SECONDS) ?: break
                        if (chunk === END) break
                        val t = track ?: newTrack().also { track = it; it.play(); frames = 0 }
                        if (!started) { started = true; lastError = null; onStart() }
                        var off = 0
                        while (off < chunk.size && generation.get() == gen) {
                            val w = t.write(chunk, off, chunk.size - off, AudioTrack.WRITE_BLOCKING)
                            if (w <= 0) break
                            off += w
                        }
                        frames += chunk.size / 2
                    }
                    if (generation.get() != gen) break
                }
                track?.let { t ->
                    if (generation.get() == gen) {
                        val deadline = System.currentTimeMillis() + 5000
                        while (generation.get() == gen && System.currentTimeMillis() < deadline &&
                            (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < frames) Thread.sleep(25)
                        Thread.sleep(80)
                        t.stop()
                    }
                }
            } catch (e: Throwable) {
                lastError = "audio output failed: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                track?.release(); track = null
                abandonFocus()
                if (!started && generation.get() == gen) onFail(lastError ?: "no sound came back")
                onDone()
            }
        }
        return true
    }

    private fun newTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min * 2, RATE / 2) / 2 * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /** Barge-in / stop: silence immediately. */
    fun stop() {
        generation.incrementAndGet()
        ahead.clear()
        runCatching { track?.pause(); track?.flush() }
    }

    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .build()
        focus = req
        runCatching { am?.requestAudioFocus(req) }
    }

    private fun abandonFocus() { focus?.let { f -> runCatching { am?.abandonAudioFocusRequest(f) } }; focus = null }
}
