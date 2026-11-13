package ai.nexus.core.device

data class Contact(
    val name: String,
    val numbers: List<String>,
    val id: String = name,
)

data class PhoneNotification(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAtMs: Long,
    val key: String = "$packageName:$postedAtMs",
)

data class FileItem(val name: String, val path: String, val mimeType: String?, val modifiedMs: Long, val sizeBytes: Long)

enum class SystemSetting { WIFI, BLUETOOTH, FLASHLIGHT, MOBILE_DATA, AIRPLANE_MODE, DO_NOT_DISTURB, AUTO_ROTATE, LOCATION, HOTSPOT, DARK_MODE }
enum class MediaKey { PLAY, PAUSE, PLAY_PAUSE, NEXT, PREVIOUS, STOP }
enum class SettingsScreen { MAIN, WIFI, BLUETOOTH, DISPLAY, BATTERY, SOUND, APPS, APP_DETAILS, ACCESSIBILITY, NETWORK, LOCATION, STORAGE, NOTIFICATIONS, DATE_TIME, PRIVACY }

/** Outcome of a system-level request. */
sealed interface SysResult {
    data class Done(val message: String) : SysResult
    /** Android does not let apps do this directly; the settings screen was opened instead. */
    data class OpenedSettings(val message: String) : SysResult
    data class NeedsPermission(val permission: String, val message: String) : SysResult
    data class Failed(val message: String) : SysResult
}

/**
 * Phone capabilities that go through Android APIs rather than taps:
 * contacts, calls, SMS, toggles, media keys, notifications and files.
 */
interface SystemActions {
    suspend fun contacts(query: String): List<Contact>
    suspend fun call(number: String, direct: Boolean = true): SysResult
    suspend fun openDialer(number: String? = null): SysResult
    suspend fun endCall(): SysResult
    suspend fun sendSms(number: String, text: String): SysResult
    suspend fun composeSms(number: String, text: String): SysResult

    suspend fun setToggle(setting: SystemSetting, on: Boolean?): SysResult
    /** Volume as a 0..100 percent, or relative step when [relative] is true. */
    suspend fun setVolume(percent: Int, relative: Boolean = false): SysResult
    suspend fun setBrightness(percent: Int, relative: Boolean = false): SysResult
    suspend fun mediaKey(key: MediaKey): SysResult
    /** "Play Skyfall" – asks a music app to search and play directly. */
    suspend fun playFromSearch(query: String, packageName: String?): SysResult
    suspend fun seek(seconds: Int): SysResult = SysResult.Failed("Seeking isn't supported here.")
    suspend fun openSettings(screen: SettingsScreen, packageName: String? = null): SysResult

    suspend fun recentNotifications(limit: Int = 20): List<PhoneNotification>
    suspend fun openNotification(key: String): SysResult

    suspend fun searchFiles(query: String, mimePrefix: String? = null, limit: Int = 20): List<FileItem>
    suspend fun openFile(file: FileItem): SysResult
    suspend fun shareFile(file: FileItem): SysResult

    suspend fun composeEmail(to: String?, subject: String?, body: String?): SysResult
    suspend fun setAlarm(hour: Int, minute: Int, label: String?): SysResult = SysResult.Failed("Alarms aren't available.")
    suspend fun setTimer(seconds: Int, label: String?): SysResult = SysResult.Failed("Timers aren't available.")
    /**
     * Reply straight from a notification's reply action (WhatsApp, Telegram, SMS…)
     * without opening the app. Returns null when no matching notification can be replied to.
     */
    suspend fun replyToNotification(fromName: String, text: String): SysResult? = null
    /** "silent" | "vibrate" | "normal" */
    suspend fun setRinger(mode: String): SysResult = SysResult.Failed("Ringer control isn't available.")
    suspend fun directions(place: String, mode: String?): SysResult = SysResult.Failed("No maps app.")
    suspend fun addCalendarEvent(title: String, startMs: Long?, durationMin: Int = 60): SysResult = SysResult.Failed("No calendar app.")
    suspend fun openCamera(front: Boolean, video: Boolean): SysResult = SysResult.Failed("No camera app.")
    /** Whether audio is actually playing right now; null if unknown. Used to verify "play X" really started. */
    suspend fun isMusicActive(): Boolean? = null
    /** Short haptic patterns for silent feedback. */
    fun haptic(kind: String) {}
    suspend fun batteryPercent(): Int?
    fun nowMs(): Long = System.currentTimeMillis()
}
