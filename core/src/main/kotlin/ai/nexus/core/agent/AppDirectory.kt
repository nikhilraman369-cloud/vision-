package ai.nexus.core.agent

import ai.nexus.core.device.DeviceController
import ai.nexus.core.device.InstalledApp
import ai.nexus.core.memory.LongTermMemory
import ai.nexus.core.nlu.BuiltInApps
import ai.nexus.core.nlu.Fuzzy

/** Resolves spoken app names ("insta", "my bank app", user aliases) to installed packages. */
class AppDirectory(private val device: DeviceController, private val memory: LongTermMemory) {
    @Volatile private var installed: List<InstalledApp> = emptyList()
    @Volatile private var loadedAt = 0L

    suspend fun refresh() {
        installed = runCatching { device.installedApps() }.getOrDefault(emptyList())
        loadedAt = System.currentTimeMillis()
    }

    private suspend fun apps(): List<InstalledApp> {
        if (installed.isEmpty() || System.currentTimeMillis() - loadedAt > 10 * 60_000) refresh()
        return installed
    }

    /** Synchronous check used by the parser (best effort, cached). */
    fun isKnown(name: String): Boolean {
        val n = Fuzzy.normalize(name)
        if (n.isBlank()) return false
        if (BuiltInApps.isKnown(n) || memory.appAlias(n) != null) return true
        return installed.any { Fuzzy.normalize(it.label) == n }
    }

    suspend fun resolve(name: String): InstalledApp? {
        val n = Fuzzy.normalize(name)
        if (n.isBlank()) return null
        val list = apps()
        memory.appAlias(n)?.let { alias ->
            list.firstOrNull { it.packageName == alias || Fuzzy.normalize(it.label) == Fuzzy.normalize(alias) }?.let { return it }
        }
        list.firstOrNull { Fuzzy.normalize(it.label) == n }?.let { return it }
        BuiltInApps.packageFor(n)?.let { pkg ->
            list.firstOrNull { it.packageName == pkg }?.let { return it }
            // Built-in alias for a role (dialer, sms, camera…) – match any installed app with a similar label.
            if (list.isEmpty()) return InstalledApp(name, pkg)
        }
        roleFallback(n, list)?.let { return it }
        return Fuzzy.best(n, list, 0.75) { listOf(it.label, it.packageName.substringAfterLast('.')) }
    }

    private fun roleFallback(n: String, list: List<InstalledApp>): InstalledApp? {
        val hints = when (n) {
            "phone", "dialer", "dialler", "phone app", "calls" -> listOf("dialer", "phone", "contacts")
            "messages", "sms", "text messages", "messaging" -> listOf("messaging", "mms", "messages")
            "camera" -> listOf("camera")
            "gallery", "photos" -> listOf("gallery", "photos")
            "files", "file manager", "my files", "downloads" -> listOf("files", "filemanager", "documentsui", "fileexplorer")
            "clock", "alarm", "timer" -> listOf("clock")
            "calculator" -> listOf("calculator")
            "settings" -> listOf("settings")
            "browser", "internet" -> listOf("chrome", "browser", "firefox")
            "email", "mail" -> listOf("gm", "email", "outlook")
            else -> return null
        }
        return list.firstOrNull { app -> hints.any { h -> app.packageName.lowercase().contains(h) || Fuzzy.normalize(app.label).contains(h) } }
    }
}
