package ai.nexus.app.brain

import android.content.Context
import ai.nexus.core.agent.PhoneBrain
import ai.nexus.core.agent.TextGenerator
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A downloadable on-device model. */
data class BrainModel(val id: String, val title: String, val sizeLabel: String, val url: String, val fileName: String)

/**
 * Downloads, loads and runs the free on-device model (MediaPipe LLM
 * Inference). The model stays loaded in memory so each command only pays
 * for thinking, not for start-up.
 */
class PhoneBrainManager(private val context: Context, private val scope: CoroutineScope) {

    sealed interface State {
        data object NotDownloaded : State
        data class Downloading(val percent: Int, val model: BrainModel) : State
        data object Loading : State
        data class Ready(val model: BrainModel, val loadMs: Long) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.NotDownloaded)
    val state: StateFlow<State> = _state

    private var llm: LlmInference? = null
    private val runLock = Mutex()
    private var downloadJob: Job? = null
    private val dir = File(context.filesDir, "models").apply { mkdirs() }
    private val prefs get() = context.getSharedPreferences("nexus_brain", Context.MODE_PRIVATE)

    /** Time the last answer took, for the Settings screen. */
    @Volatile var lastLatencyMs: Long = 0

    val brain: PhoneBrain = PhoneBrain(TextGenerator { prompt -> generate(prompt) })

    fun installedModel(): BrainModel? {
        val id = prefs.getString("model_id", null) ?: return null
        val m = MODELS.firstOrNull { it.id == id } ?: customModel() ?: return null
        return m.takeIf { File(dir, it.fileName).let { f -> f.exists() && f.length() > 1_000_000 } }
    }

    private fun customModel(): BrainModel? = prefs.getString("custom_url", null)?.let { u ->
        BrainModel("custom", "Custom model", "?", u, "custom.task")
    }

    /** Load the installed model in the background (call at app start). */
    fun warmUp() {
        val m = installedModel() ?: run { _state.value = State.NotDownloaded; return }
        if (_state.value is State.Ready || _state.value is State.Loading) return
        scope.launch(Dispatchers.IO) { load(m) }
    }

    private fun load(m: BrainModel) {
        _state.value = State.Loading
        val path = File(dir, m.fileName).absolutePath
        val t0 = System.currentTimeMillis()
        llm?.close(); llm = null
        // CPU is the most reliable for these quantised models; GPU only as a fallback.
        val created = listOf(LlmInference.Backend.CPU, LlmInference.Backend.GPU).firstNotNullOfOrNull { backend ->
            runCatching {
                LlmInference.createFromOptions(
                    context,
                    LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(path)
                        .setMaxTokens(1024)
                        .setPreferredBackend(backend)
                        .build(),
                )
            }.getOrNull()
        }
        if (created == null) { _state.value = State.Error("This phone couldn't load the model."); return }
        llm = created
        // Warm-up run so the first real command doesn't pay for initialisation.
        runCatching {
            val sess = LlmInferenceSession.createFromOptions(created, LlmInferenceSession.LlmInferenceSessionOptions.builder().setTopK(1).setTemperature(0f).build())
            sess.addQueryChunk("<|im_start|>user\nhi<|im_end|>\n<|im_start|>assistant\n"); sess.generateResponse(); sess.close()
        }
        _state.value = State.Ready(m, System.currentTimeMillis() - t0)
    }

    private suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        if (!runLock.tryLock()) throw IllegalStateException("Phone brain is busy")
        try {
            val engine = llm ?: throw IllegalStateException("Phone brain not loaded")
            val t0 = System.currentTimeMillis()
            val session = LlmInferenceSession.createFromOptions(
                engine,
                LlmInferenceSession.LlmInferenceSessionOptions.builder().setTopK(1).setTemperature(0f).build(),
            )
            try {
                session.addQueryChunk(prompt)
                session.generateResponse()
            } finally {
                session.close()
                lastLatencyMs = System.currentTimeMillis() - t0
            }
        } finally { runLock.unlock() }
    }

    fun download(model: BrainModel) {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch(Dispatchers.IO) {
            val target = File(dir, model.fileName)
            val part = File(dir, model.fileName + ".part")
            try {
                _state.value = State.Downloading(0, model)
                var url = URL(model.url)
                var conn: HttpURLConnection
                var hops = 0
                while (true) { // follow cross-host redirects (Hugging Face → CDN)
                    conn = (url.openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false; connectTimeout = 20_000; readTimeout = 60_000
                        if (part.exists()) setRequestProperty("Range", "bytes=${part.length()}-")
                    }
                    val code = conn.responseCode
                    if (code in 300..399 && hops++ < 6) { url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue }
                    if (code !in 200..299) throw IllegalStateException("Download failed (HTTP $code)")
                    break
                }
                val resumed = conn.responseCode == 206
                val already = if (resumed) part.length() else 0L
                val total = already + conn.contentLengthLong.coerceAtLeast(0)
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(part, resumed).use { out ->
                        val buf = ByteArray(256 * 1024)
                        var done = already
                        var lastPct = -1
                        while (isActive) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else 0
                            if (pct != lastPct) { lastPct = pct; _state.value = State.Downloading(pct, model) }
                        }
                    }
                }
                if (!isActive) return@launch
                // Remove any other model to save space.
                dir.listFiles()?.filter { it.name != part.name && it.name != target.name }?.forEach { it.delete() }
                part.renameTo(target)
                prefs.edit().putString("model_id", model.id).apply()
                load(model)
            } catch (e: Exception) {
                _state.value = State.Error((e.message ?: "Download failed") + ". Check your internet and try again — it resumes where it stopped.")
            }
        }
    }

    /** For the Settings "Test" button: what the brain makes of a sample command, and how long it took. */
    suspend fun test(sample: String): String {
        if (!isReady) return "The phone brain isn't loaded yet."
        val t0 = System.currentTimeMillis()
        val out = runCatching { brain.translate(sample, null) }.getOrElse { return "Error: ${it.message}" }
        val ms = System.currentTimeMillis() - t0
        return "\"$sample\" → " + (out?.joinToString(", ") { ai.nexus.core.nlu.CommandDsl.toLine(it) ?: it.toString() } ?: "(nothing)") + "  ·  $ms ms"
    }

    fun setCustomUrl(url: String) { prefs.edit().putString("custom_url", url.trim()).apply() }

    fun cancelDownload() { downloadJob?.cancel(); _state.value = if (installedModel() != null) State.Loading.also { warmUp() } else State.NotDownloaded }

    fun delete() {
        llm?.close(); llm = null
        dir.listFiles()?.forEach { it.delete() }
        prefs.edit().remove("model_id").apply()
        _state.value = State.NotDownloaded
    }

    val isReady: Boolean get() = _state.value is State.Ready

    companion object {
        private const val HF = "https://huggingface.co/litert-community"
        val MODELS = listOf(
            BrainModel(
                "fast", "Fast — Qwen 2.5 0.5B", "≈550 MB",
                "$HF/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task?download=true",
                "qwen2.5-0.5b-q8.task",
            ),
            BrainModel(
                "smart", "Smart — Qwen 2.5 1.5B", "≈1.6 GB",
                "$HF/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task?download=true",
                "qwen2.5-1.5b-q8.task",
            ),
        )
    }
}
