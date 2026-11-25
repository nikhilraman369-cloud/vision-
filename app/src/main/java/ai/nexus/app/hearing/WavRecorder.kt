package ai.nexus.app.hearing

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * Records one utterance as 16 kHz mono 16-bit audio (what speech engines want). Stops by itself
 * ~1.2 s after you stop talking (or at 15 s), or when [stop] is called. Can use earbud mics.
 */
class WavRecorder(private val context: Context) {
    @Volatile private var stopRequested = false
    @Volatile var level: Float = 0f; private set
    private var routed = false

    fun stop() { stopRequested = true }

    @SuppressLint("MissingPermission")
    suspend fun record(useEarbuds: Boolean): ByteArray = withContext(Dispatchers.IO) {
        stopRequested = false
        if (useEarbuds) routeEarbuds()
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
        val ns = if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        val out = ByteArrayOutputStream()
        val buf = ShortArray(rate / 20)          // 50 ms chunks
        try {
            rec.startRecording()
            var heardSpeech = false
            var quietMs = 0
            var totalMs = 0
            var noise = 300.0
            while (coroutineContext.isActive && !stopRequested && totalMs < 15_000) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                var sum = 0.0
                for (i in 0 until n) { bb.putShort(buf[i]); sum += buf[i].toDouble() * buf[i] }
                out.write(bb.array())
                val rms = Math.sqrt(sum / n)
                level = (rms / 3000.0).coerceIn(0.0, 1.0).toFloat()
                totalMs += 50
                if (totalMs < 300) { noise = maxOf(noise, rms); continue }   // learn the background level first
                if (rms > noise * 2.5 + 200) { heardSpeech = true; quietMs = 0 } else if (heardSpeech) quietMs += 50
                if (heardSpeech && quietMs >= 1200) break
            }
        } finally {
            runCatching { rec.stop() }; rec.release(); runCatching { ns?.release() }
            unrouteEarbuds()
            level = 0f
        }
        out.toByteArray()
    }

    private fun routeEarbuds() {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        routed = runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val dev = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                dev != null && am.setCommunicationDevice(dev)
            } else { @Suppress("DEPRECATION") am.startBluetoothSco(); @Suppress("DEPRECATION") am.isBluetoothScoOn = true; true }
        }.getOrDefault(false)
        if (routed) Thread.sleep(400)   // the earbud mic needs a moment to switch on
    }

    private fun unrouteEarbuds() {
        if (!routed) return
        val am = context.getSystemService(AudioManager::class.java) ?: return
        runCatching { if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else { @Suppress("DEPRECATION") am.stopBluetoothSco() } }
        routed = false
    }

    companion object {
        /** Wraps raw 16 kHz mono PCM in a WAV header. */
        fun wav(pcm: ByteArray, rate: Int = 16000): ByteArray {
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size); h.put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1); h.putInt(rate); h.putInt(rate * 2); h.putShort(2); h.putShort(16)
            h.put("data".toByteArray()); h.putInt(pcm.size)
            return h.array() + pcm
        }

        fun save(dir: File, name: String, pcm: ByteArray): File = File(dir, name).apply { parentFile?.mkdirs(); writeBytes(wav(pcm)) }
    }
}
