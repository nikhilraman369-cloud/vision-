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
        private fun piper(name: String, title: String, size: String = "≈64 MB", speaker: Int = 0, id: String = "piper-$name") =
            Pack(id, title, size, "$BASE/vits-piper-$name.tar.bz2", "vits", speaker = speaker, folder = "piper-$name")
        private fun kokoro(name: String, title: String, speaker: Int) =
            Pack("kokoro-en-v0_19-$name", title, "≈330 MB (one download, 11 voices)", "$BASE/kokoro-en-v0_19.tar.bz2", "kokoro", speaker = speaker, folder = "kokoro-en-v0_19")

        val PACKS = listOf(
            // Studio-quality pack: one download gives all of these voices.
            kokoro("george", "George — British male, deep butler (best)", 9),
            kokoro("lewis", "Lewis — British male, smooth", 10),
            kokoro("emma", "Emma — British female", 7),
            kokoro("isabella", "Isabella — British female, warm", 8),
            kokoro("adam", "Adam — American male, deep", 5),
            kokoro("michael", "Michael — American male, calm", 6),
            kokoro("bella", "Bella — American female, bright", 1),
            kokoro("sarah", "Sarah — American female", 3),
            kokoro("nicole", "Nicole — American female, soft", 2),
            kokoro("sky", "Sky — American female, young", 4),
            // Light, fast packs (one voice each, except Semaine).
            piper("en_GB-alan-medium", "Alan — British male, light & fast"),
            piper("en_GB-northern_english_male-medium", "Northern English male, deeper"),
            piper("en_GB-semaine-medium", "Spike — British male (Semaine)", speaker = 1, id = "piper-en_GB-semaine-spike"),
            piper("en_GB-semaine-medium", "Obadiah — British male, low (Semaine)", speaker = 2, id = "piper-en_GB-semaine-obadiah"),
            piper("en_GB-semaine-medium", "Prudence — British female (Semaine)", speaker = 0, id = "piper-en_GB-semaine-prudence"),
            piper("en_GB-semaine-medium", "Poppy — British female, cheerful (Semaine)", speaker = 3, id = "piper-en_GB-semaine-poppy"),
            piper("en_GB-alba-medium", "Alba — Scottish female"),
            piper("en_GB-jenny_dioco-medium", "Jenny — British female"),
            piper("en_US-ryan-high", "Ryan — American male, clear", "≈110 MB"),
            piper("en_US-joe-medium", "Joe — American male"),
            piper("en_US-lessac-medium", "Lessac — American female, newsreader"),
            piper("en_US-amy-medium", "Amy — American female"),
        )

        /** Voices grouped by the download that contains them. */
        fun groups(): Map<String, List<Pack>> = PACKS.groupBy { it.folder }
    }
}
