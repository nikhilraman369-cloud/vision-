package ai.nexus.app.learn

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import ai.nexus.core.screen.ScreenDump
import ai.nexus.core.screen.ScreenSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Teach NEXUS an app": while recording, every *different kind* of screen you open (in any app except NEXUS)
 * is saved as a structure dump: ids, buttons, texts, positions. Typed text is never saved.
 * The file is then shared so NEXUS can be taught that app from its real screens.
 */
object AppMapRecorder {
    private const val MAX_SCREENS = 120
    private val _count = MutableStateFlow(-1)
    /** -1 = not recording, else how many screens are saved. */
    val count: StateFlow<Int> = _count
    val recording: Boolean get() = _count.value >= 0

    private var file: File? = null
    private val seen = HashSet<Int>()
    private var ownPackage = ""

    fun start(ctx: Context) {
        ownPackage = ctx.packageName
        val dir = File(ctx.filesDir, "maps").apply { mkdirs() }
        file = File(dir, "nexus-app-map-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.ENGLISH).format(Date()) + ".txt").apply {
            writeText("NEXUS app map · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n\n")
        }
        seen.clear()
        _count.value = 0
    }

    fun stop() { if (recording) _count.value = -1 - (_count.value) }   // keep the count for display: -1 - n

    /** Saved screens of the last recording (also after stopping). */
    fun saved(): Int = _count.value.let { if (it >= 0) it else -1 - it }
    fun lastFile(): File? = file

    /** Called by the accessibility service whenever the screen has settled. */
    @Synchronized fun onScreen(s: ScreenSnapshot) {
        if (!recording || s.packageName == ownPackage || s.packageName == "com.android.systemui") return
        if (_count.value >= MAX_SCREENS) return
        val key = ScreenDump.structureKey(s)
        if (!seen.add(key)) return
        runCatching { file?.appendText(ScreenDump.format(s, "screen ${_count.value + 1}") + "\n") }
        _count.value = _count.value + 1
    }

    /** Copies the recording to Downloads/NEXUS (so you can attach it anywhere) and returns a shareable link. */
    fun exportToDownloads(ctx: Context): Uri? {
        val f = file ?: return null
        if (Build.VERSION.SDK_INT < 29) return null
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/NEXUS")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
        return uri
    }
}
