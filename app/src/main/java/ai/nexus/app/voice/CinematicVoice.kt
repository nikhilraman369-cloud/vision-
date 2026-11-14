package ai.nexus.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.PlaybackParams
import android.media.audiofx.BassBoost
import android.media.audiofx.PresetReverb
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * On-device neural voice (sherpa-onnx with a Piper or Kokoro British-male
 * pack). Replies are split into sentences; the next sentence is synthesised
 * while the current one plays, so the first words come out fast. Ducks other
 * audio, follows Bluetooth/earphones, optional "AI presence" reverb.
 */
class CinematicVoice(private val context: Context, private val packs: VoicePackManager) {
    @Volatile private var tts: OfflineTts? = null
    @Volatile private var loadedId: String? = null
    @Volatile private var speaker = 0
    @Volatile var lastError: String? = null
        private set
    private val loader = Executors.newSingleThreadExecutor()
    private val synth = Executors.newSingleThreadExecutor()
    private val player = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger(0)
    @Volatile private var track: AudioTrack? = null
    private val cache = object : LinkedHashMap<String, Pair<FloatArray, Int>>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Pair<FloatArray, Int>>?) = size > 24
    }
    private val am = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null

    val ready: Boolean get() = tts != null
    val loadedPack: String? get() = loadedId

    /** Load (or switch to) a downloaded voice pack in the background. */
    fun load(id: String, onLoaded: (Boolean) -> Unit = {}) {
        if (loadedId == id && tts != null) { onLoaded(true); return }
        // Another voice from the same download (e.g. Kokoro George → Emma): just switch speaker.
        val p0 = packs.pack(id)
        val cur = loadedId?.let { packs.pack(it) }
        if (p0 != null && cur != null && tts != null && p0.folder == cur.folder) {
            speaker = p0.speaker; loadedId = id; synchronized(cache) { cache.clear() }; onLoaded(true); return
        }
        loader.execute {
            lastError = null
            val ok = runCatching {
                val p = packs.pack(id) ?: error("Unknown voice pack")
                val dir = packs.dir(id)
                val files = dir.walkTopDown().toList()
                val onnx = files.firstOrNull { it.isFile && it.name.endsWith(".onnx") }?.absolutePath ?: error("Voice model missing")
                val tokens = files.firstOrNull { it.isFile && it.name == "tokens.txt" }?.absolutePath ?: ""
                val espeak = files.firstOrNull { it.isDirectory && it.name == "espeak-ng-data" }?.absolutePath ?: ""
                val model = if (p.kind == "kokoro") {
                    val voices = files.firstOrNull { it.isFile && it.name == "voices.bin" }?.absolutePath ?: ""
                    OfflineTtsModelConfig(kokoro = OfflineTtsKokoroModelConfig(model = onnx, voices = voices, tokens = tokens, dataDir = espeak), numThreads = 2, debug = false, provider = "cpu")
                } else {
                    OfflineTtsModelConfig(vits = OfflineTtsVitsModelConfig(model = onnx, tokens = tokens, dataDir = espeak), numThreads = 2, debug = false, provider = "cpu")
                }
                val engine = OfflineTts(config = OfflineTtsConfig(model = model, maxNumSentences = 1))
                tts?.release()
                tts = engine; loadedId = id; speaker = p.speaker
                synchronized(cache) { cache.clear() }
                true
            }.getOrElse { e -> lastError = (e.message ?: e.javaClass.simpleName); tts = null; loadedId = null; false }
            onLoaded(ok)
        }
    }

    fun unload() { loader.execute { tts?.release(); tts = null; loadedId = null } }

    private fun sentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?।])\\s+|\\n+")).map { it.trim() }.filter { it.isNotEmpty() }
            .flatMap { s -> if (s.length <= 220) listOf(s) else s.split(Regex("(?<=[,;:])\\s+")) }

    private fun synthOne(s: String, speed: Float): Pair<FloatArray, Int>? {
        synchronized(cache) { cache["$speed|$s"] }?.let { return it }
        val engine = tts ?: return null
        val a = engine.generate(text = s, sid = speaker, speed = speed.coerceIn(0.5f, 2.0f))
        val r = a.samples to a.sampleRate
        if (s.length < 40 && r.first.isNotEmpty()) synchronized(cache) { cache["$speed|$s"] = r }
        return r
    }

    /**
     * Speak [text]. [onStart] fires when audio begins, [onDone] when it ends or is stopped.
     */
    fun speak(text: String, speed: Float, pitch: Float, effects: Boolean, onStart: () -> Unit, onDone: () -> Unit, onFail: (String) -> Unit = {}): Boolean {
        if (tts == null) return false
        val gen = generation.incrementAndGet()
        val queue = LinkedBlockingQueue<Pair<FloatArray, Int>>()
        val parts = sentences(text)
        val end = FloatArray(0) to -1
        val lastErrRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
        synth.execute {
            for (p in parts) {
                if (generation.get() != gen) break
                runCatching { synthOne(p, speed) }.onFailure { lastErrRef.set(it.message ?: it.javaClass.simpleName) }.getOrNull()?.let { if (it.first.isNotEmpty()) queue.put(it) else lastErrRef.set("the voice produced no sound") }
            }
            queue.put(end)
        }
        player.execute {
            var started = false
            var framesWritten = 0L
            var reverb: PresetReverb? = null; var bass: BassBoost? = null
            try {
                requestFocus()
                while (generation.get() == gen) {
                    val (samples, rate) = queue.poll(15, TimeUnit.SECONDS) ?: break
                    if (rate < 0) break
                    if (samples.isEmpty()) continue
                    val t = track?.takeIf { it.sampleRate == rate } ?: newTrack(rate, pitch).also { nt ->
                        track?.release(); track = nt; framesWritten = 0
                        if (effects) {
                            reverb = runCatching { PresetReverb(0, nt.audioSessionId).apply { preset = PresetReverb.PRESET_SMALLROOM; enabled = true } }.getOrNull()
                            bass = runCatching { BassBoost(0, nt.audioSessionId).apply { if (strengthSupported) setStrength(220); enabled = true } }.getOrNull()
                        }
                        nt.play()
                    }
                    if (!started) { started = true; onStart() }
                    var off = 0
                    while (off < samples.size && generation.get() == gen) {
                        val n = t.write(samples, off, minOf(4096, samples.size - off), AudioTrack.WRITE_BLOCKING)
                        if (n <= 0) break
                        off += n; framesWritten += n
                    }
                }
                // Let the tail of the last sentence finish playing before releasing the track
                // (releasing early was cutting off the last words).
                track?.let { t ->
                    if (generation.get() == gen) {
                        val deadline = System.currentTimeMillis() + 4000
                        while (generation.get() == gen && System.currentTimeMillis() < deadline &&
                            (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < framesWritten) Thread.sleep(30)
                        Thread.sleep(120)
                        t.stop()
                    }
                }
            } catch (e: Throwable) {
                lastErrRef.set("audio output failed: " + (e.message ?: e.javaClass.simpleName))
            } finally {
                runCatching { reverb?.release(); bass?.release() }
                track?.release(); track = null
                abandonFocus()
                // Nothing was played (and we weren't interrupted): tell the caller so it can use another voice.
                if (!started && generation.get() == gen) { val why = lastErrRef.get() ?: "the voice produced no sound"; lastError = why; onFail(why) }
                onDone()
            }
        }
        return true
    }

    /** Android rejects a buffer that isn't a whole number of frames (4 bytes for mono float). 22050 Hz voices hit this. */
    private fun bufferBytes(rate: Int): Int {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val want = maxOf(if (min > 0) min * 2 else 0, rate)
        return (want + 3) / 4 * 4
    }

    private fun newTrack(rate: Int, pitch: Float): AudioTrack {
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(bufferBytes(rate))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (pitch != 1f) runCatching { t.playbackParams = PlaybackParams().setPitch(pitch).setSpeed(1f) }
        return t
    }

    /** Barge-in / stop: silence immediately. */
    fun stop() {
        generation.incrementAndGet()
        runCatching { track?.pause(); track?.flush() }
    }

    val speaking: Boolean get() = track?.playState == AudioTrack.PLAYSTATE_PLAYING

    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .build()
        focus = req
        runCatching { am?.requestAudioFocus(req) }
    }

    private fun abandonFocus() { focus?.let { f -> runCatching { am?.abandonAudioFocusRequest(f) } }; focus = null }

    fun installedDir(id: String): File = packs.dir(id)
}

/** Soft earcons: activation chime, thinking pulse, success and error tones. */
class SoundFx {
    private fun tone(freqs: List<Pair<Double, Int>>, volume: Float = 0.18f) = Thread {
        runCatching {
            val rate = 22050
            val data = ArrayList<Float>()
            for ((f, ms) in freqs) {
                val n = rate * ms / 1000
                for (i in 0 until n) {
                    val env = minOf(1.0, i / (rate * 0.01)) * minOf(1.0, (n - i) / (rate * 0.03))
                    data += (Math.sin(2 * Math.PI * f * i / rate) * env * volume).toFloat()
                }
            }
            val arr = data.toFloatArray()
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(arr.size * 4).setTransferMode(AudioTrack.MODE_STATIC).build()
            t.write(arr, 0, arr.size, AudioTrack.WRITE_BLOCKING)
            t.play()
            Thread.sleep(freqs.sumOf { it.second }.toLong() + 60)
            t.release()
        }
    }.start()

    fun activate() = tone(listOf(660.0 to 70, 990.0 to 90))
    fun thinking() = tone(listOf(520.0 to 60), 0.08f)
    fun success() = tone(listOf(784.0 to 60, 1046.0 to 110), 0.14f)
    fun error() = tone(listOf(440.0 to 90, 330.0 to 140), 0.16f)
}
