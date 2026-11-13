package ai.nexus.app.util

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Resumable HTTP download that follows cross-host redirects. */
object Downloader {
    fun download(url: String, target: File, isActive: () -> Boolean, progress: (Int) -> Unit) {
        val part = File(target.path + ".part")
        var u = URL(url)
        var conn: HttpURLConnection
        var hops = 0
        while (true) {
            conn = (u.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 20_000; readTimeout = 60_000
                if (part.exists() && part.length() > 0) setRequestProperty("Range", "bytes=${part.length()}-")
            }
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 8) { u = URL(u, conn.getHeaderField("Location")); conn.disconnect(); continue }
            if (code == 416) { conn.disconnect(); break } // already complete
            if (code !in 200..299) throw IllegalStateException("Download failed (HTTP $code)")
            val resumed = code == 206
            val already = if (resumed) part.length() else 0L
            val total = already + conn.contentLengthLong.coerceAtLeast(0)
            conn.inputStream.use { input ->
                FileOutputStream(part, resumed).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = already; var last = -1
                    while (isActive()) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != last) { last = pct; progress(pct) }
                    }
                }
            }
            break
        }
        if (!isActive()) return
        target.delete()
        if (!part.renameTo(target)) throw IllegalStateException("Couldn't save the download.")
    }
}
