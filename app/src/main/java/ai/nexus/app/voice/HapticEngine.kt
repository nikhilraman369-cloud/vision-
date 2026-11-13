package ai.nexus.app.voice

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Silent feedback, so NEXUS never talks over your music or reading. */
class HapticEngine(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31)
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)

    private fun play(timings: LongArray, amps: IntArray) = runCatching {
        vibrator?.vibrate(VibrationEffect.createWaveform(timings, amps, -1))
    }

    /** Crisp double pulse: task done. */
    fun success() = play(longArrayOf(0, 28, 70, 28), intArrayOf(0, 200, 0, 255))
    /** Three short pulses: something failed. */
    fun error() = play(longArrayOf(0, 22, 60, 22, 60, 22), intArrayOf(0, 255, 0, 255, 0, 255))
    /** 15 ms micro tick: listening window opened/closed. */
    fun tick() = play(longArrayOf(0, 15), intArrayOf(0, 120))

    fun play(kind: String) = when (kind) { "success" -> success(); "error" -> error(); else -> tick() }
}
