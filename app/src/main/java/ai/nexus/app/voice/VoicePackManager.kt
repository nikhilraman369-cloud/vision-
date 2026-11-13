package ai.nexus.app.voice

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import ai.nexus.app.util.Downloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File

/** Neural voice packs (free, on-device) for the sherpa-onnx runtime. */
class VoicePackManager(private val context: Context, private val scope: CoroutineScope) {
    data class Pack(val id: String, val title: String, val size: String, val url: String, val kind: String, val speaker: Int = 0, val folder: String = id)

    sealed interface State {
        data object Idle : State
        data class Downloading(val id: String, val percent: Int) : State
        data class Unpacking(val id: String) : State
        data class Error(val message: String) : State
    }

    private val root = File(context.filesDir, "voices").apply { mkdirs() }
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var job: Job? = null

    fun dir(id: String) = File(root, pack(id)?.folder ?: id)
    fun installed(id: String) = dir(id).walkTopDown().maxDepth(3).any { it.isFile && it.name.endsWith(".onnx") }
    fun pack(id: String) = PACKS.firstOrNull { it.id == id }

    fun onWifi(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun download(id: String, wifiOnly: Boolean, onDone: () -> Unit = {}) {
        if (job?.isActive == true) return
        val p = pack(id) ?: return
        if (wifiOnly && !onWifi()) { _state.value = State.Error("Connect to Wi-Fi (or turn off Wi-Fi-only downloads)."); return }
        job = scope.launch(Dispatchers.IO) {
            try {
                val archive = File(root, "${p.folder}.tar.bz2")
                _state.value = State.Downloading(id, 0)
                Downloader.download(p.url, archive, { isActive }) { _state.value = State.Downloading(id, it) }
                if (!isActive) return@launch
                _state.value = State.Unpacking(id)
                val dest = dir(id).apply { deleteRecursively(); mkdirs() }
                TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered(1 shl 16))).use { tar ->
                    var e = tar.nextEntry
                    while (e != null) {
                        val f = File(dest, e.name)
                        if (!f.canonicalPath.startsWith(dest.canonicalPath)) throw SecurityException("Bad archive entry")
                        if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { tar.copyTo(it) } }
                        e = tar.nextEntry
                    }
                }
                archive.delete()
                _state.value = if (installed(id)) State.Idle else State.Error("That voice pack looks incomplete. Try again.")
                if (installed(id)) onDone()
            } catch (e: Exception) {
                _state.value = State.Error((e.message ?: "Download failed") + ". It resumes if you try again.")
            }
        }
    }

    fun delete(id: String) { dir(id).deleteRecursively() }

    companion object {
        private const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"
        val PACKS = listOf(
            Pack("piper-en_GB-alan-medium", "Alan — British male, light & fast", "≈64 MB", "$BASE/vits-piper-en_GB-alan-medium.tar.bz2", "vits"),
            Pack("piper-en_GB-northern_english_male-medium", "Northern English male, deeper", "≈64 MB", "$BASE/vits-piper-en_GB-northern_english_male-medium.tar.bz2", "vits"),
            Pack("kokoro-en-v0_19-george", "George — British male, top quality (Kokoro)", "≈330 MB", "$BASE/kokoro-en-v0_19.tar.bz2", "kokoro", speaker = 9, folder = "kokoro-en-v0_19"),
            Pack("kokoro-en-v0_19-lewis", "Lewis — British male, top quality (Kokoro)", "≈330 MB", "$BASE/kokoro-en-v0_19.tar.bz2", "kokoro", speaker = 10, folder = "kokoro-en-v0_19"),
        )
    }
}
