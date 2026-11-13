package ai.nexus.app.voice

import android.content.Context
import ai.nexus.app.util.Downloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.zip.ZipInputStream

/** Downloads and unpacks the free offline Vosk speech models (one-time, ~40 MB). */
class VoskModelManager(private val context: Context, private val scope: CoroutineScope) {
    data class SpeechModel(val id: String, val title: String, val url: String)

    sealed interface State {
        data object Missing : State
        data class Downloading(val percent: Int) : State
        data object Unpacking : State
        data class Ready(val path: String) : State
        data class Error(val message: String) : State
    }

    private val root = File(context.filesDir, "vosk").apply { mkdirs() }
    private val _state = MutableStateFlow<State>(State.Missing)
    val state: StateFlow<State> = _state
    private var job: Job? = null

    /** Folder that holds the unpacked model (contains am/ conf/ …), or null. */
    fun modelPath(id: String): String? = File(root, id).walkTopDown().maxDepth(2)
        .firstOrNull { it.isDirectory && File(it, "conf").isDirectory }?.absolutePath

    fun refresh(id: String) { _state.value = modelPath(id)?.let { State.Ready(it) } ?: State.Missing }

    fun download(id: String) {
        if (job?.isActive == true) return
        val m = MODELS.firstOrNull { it.id == id } ?: return
        job = scope.launch(Dispatchers.IO) {
            try {
                val zip = File(root, "$id.zip")
                _state.value = State.Downloading(0)
                Downloader.download(m.url, zip, { isActive }) { _state.value = State.Downloading(it) }
                if (!isActive) return@launch
                _state.value = State.Unpacking
                val dest = File(root, id).apply { deleteRecursively(); mkdirs() }
                ZipInputStream(zip.inputStream().buffered()).use { zin ->
                    var e = zin.nextEntry
                    while (e != null) {
                        val f = File(dest, e.name)
                        if (!f.canonicalPath.startsWith(dest.canonicalPath)) throw SecurityException("Bad zip entry")
                        if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zin.copyTo(it) } }
                        e = zin.nextEntry
                    }
                }
                zip.delete()
                refresh(id)
                if (_state.value !is State.Ready) _state.value = State.Error("The speech model looks incomplete. Try again.")
            } catch (e: Exception) {
                _state.value = State.Error((e.message ?: "Download failed") + ". Check your internet and try again.")
            }
        }
    }

    companion object {
        private const val BASE = "https://alphacephei.com/vosk/models"
        val MODELS = listOf(
            SpeechModel("en-in", "Indian English (best for English + Hinglish), 36 MB", "$BASE/vosk-model-small-en-in-0.4.zip"),
            SpeechModel("hi", "Hindi, 42 MB", "$BASE/vosk-model-small-hi-0.22.zip"),
            SpeechModel("en-us", "US English, 40 MB", "$BASE/vosk-model-small-en-us-0.15.zip"),
        )
    }
}
