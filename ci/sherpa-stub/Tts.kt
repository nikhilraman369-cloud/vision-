// Fallback used only when the CI build could not download sherpa-onnx's real
// Tts.kt + native libraries. Same API; creating OfflineTts throws, so NEXUS
// falls back to the system British voice instead of crashing.
package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

data class OfflineTtsVitsModelConfig(
    var model: String = "", var lexicon: String = "", var tokens: String = "", var dataDir: String = "", var dictDir: String = "",
    var noiseScale: Float = 0.667f, var noiseScaleW: Float = 0.8f, var lengthScale: Float = 1.0f,
)

data class OfflineTtsKokoroModelConfig(
    var model: String = "", var voices: String = "", var tokens: String = "", var dataDir: String = "", var lexicon: String = "",
    var dictDir: String = "", var lengthScale: Float = 1.0f,
)

data class OfflineTtsModelConfig(
    var vits: OfflineTtsVitsModelConfig = OfflineTtsVitsModelConfig(),
    var kokoro: OfflineTtsKokoroModelConfig = OfflineTtsKokoroModelConfig(),
    var numThreads: Int = 1, var debug: Boolean = false, var provider: String = "cpu",
)

data class OfflineTtsConfig(
    var model: OfflineTtsModelConfig = OfflineTtsModelConfig(), var ruleFsts: String = "", var ruleFars: String = "",
    var maxNumSentences: Int = 1, var silenceScale: Float = 0.2f,
)

class GeneratedAudio(val samples: FloatArray, val sampleRate: Int)

class OfflineTts(assetManager: AssetManager? = null, var config: OfflineTtsConfig) {
    init { throw UnsupportedOperationException("Neural voice runtime not bundled in this build") }
    fun sampleRate(): Int = 0
    fun numSpeakers(): Int = 0
    fun generate(text: String, sid: Int = 0, speed: Float = 1.0f): GeneratedAudio = GeneratedAudio(FloatArray(0), 22050)
    fun release() {}
}
