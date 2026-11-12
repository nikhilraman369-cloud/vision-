package ai.nexus.app.device

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.SearchManager
import android.bluetooth.BluetoothManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import ai.nexus.app.service.NexusNotificationListener
import ai.nexus.core.device.Contact
import ai.nexus.core.device.FileItem
import ai.nexus.core.device.MediaKey
import ai.nexus.core.device.PhoneNotification
import ai.nexus.core.device.SettingsScreen
import ai.nexus.core.device.SysResult
import ai.nexus.core.device.SystemActions
import ai.nexus.core.device.SystemSetting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Contacts, calls, SMS, toggles, media, notifications and files through Android APIs. */
class AndroidSystemActions(private val context: Context) : SystemActions {

    private fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    private fun start(i: Intent): Boolean = runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    @Volatile private var torchOn = false

    // ------------------------------------------------------------ contacts & calls

    override suspend fun contacts(query: String): List<Contact> = withContext(Dispatchers.IO) {
        if (!has(Manifest.permission.READ_CONTACTS)) return@withContext emptyList()
        val out = LinkedHashMap<String, Pair<String, MutableList<String>>>()
        val proj = arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$query%"),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
        )?.use { c ->
            while (c.moveToNext() && out.size < 25) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val num = c.getString(2)?.replace(Regex("[^+\\d]"), "") ?: continue
                val e = out.getOrPut(id) { name to mutableListOf() }
                if (e.second.none { it.takeLast(10) == num.takeLast(10) }) e.second += num
            }
        }
        out.map { (id, v) -> Contact(v.first, v.second, id) }
    }

    @SuppressLint("MissingPermission")
    override suspend fun call(number: String, direct: Boolean): SysResult {
        val uri = Uri.parse("tel:" + Uri.encode(number))
        return if (direct && has(Manifest.permission.CALL_PHONE)) {
            if (start(Intent(Intent.ACTION_CALL, uri))) SysResult.Done("") else SysResult.Failed("Couldn't start the call.")
        } else {
            start(Intent(Intent.ACTION_DIAL, uri)); SysResult.Done("I've opened the dialler — tap call to confirm.")
        }
    }

    override suspend fun openDialer(number: String?): SysResult {
        start(Intent(Intent.ACTION_DIAL, number?.let { Uri.parse("tel:$it") })); return SysResult.Done("")
    }

    @SuppressLint("MissingPermission")
    override suspend fun endCall(): SysResult {
        if (!has(Manifest.permission.ANSWER_PHONE_CALLS)) return SysResult.NeedsPermission(Manifest.permission.ANSWER_PHONE_CALLS, "I need the phone-call permission to hang up.")
        val tm = context.getSystemService(TelecomManager::class.java)
        @Suppress("DEPRECATION")
        return if (tm?.endCall() == true) SysResult.Done("Call ended.") else SysResult.Failed("There's no call to end.")
    }

    override suspend fun sendSms(number: String, text: String): SysResult {
        if (!has(Manifest.permission.SEND_SMS)) return composeSms(number, text).let { SysResult.Done("I've opened the message — tap send to confirm.") }
        return runCatching {
            val sms = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java)!! else @Suppress("DEPRECATION") SmsManager.getDefault()
            sms.sendMultipartTextMessage(number, null, sms.divideMessage(text), null, null)
            SysResult.Done("")
        }.getOrElse { SysResult.Failed("The SMS couldn't be sent: ${it.message}") }
    }

    override suspend fun composeSms(number: String, text: String): SysResult {
        start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", text))
        return SysResult.Done("")
    }

    // ------------------------------------------------------------ toggles & levels

    @SuppressLint("MissingPermission")
    override suspend fun setToggle(setting: SystemSetting, on: Boolean?): SysResult = when (setting) {
        SystemSetting.FLASHLIGHT -> runCatching {
            val cm = context.getSystemService(CameraManager::class.java)!!
            val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            val target = on ?: !torchOn
            cm.setTorchMode(id, target); torchOn = target
            SysResult.Done(if (target) "Flashlight on." else "Flashlight off.")
        }.getOrElse { SysResult.Failed("I couldn't control the flashlight.") }

        SystemSetting.WIFI -> if (Build.VERSION.SDK_INT < 29) {
            @Suppress("DEPRECATION")
            val wm = context.applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION") val target = on ?: !(wm?.isWifiEnabled ?: false)
            @Suppress("DEPRECATION") wm?.setWifiEnabled(target)
            SysResult.Done(if (target) "Wi-Fi on." else "Wi-Fi off.")
        } else {
            start(Intent(Settings.Panel.ACTION_WIFI))
            SysResult.OpenedSettings("Android only lets you switch Wi-Fi yourself — I've opened the Wi-Fi panel.")
        }

        SystemSetting.BLUETOOTH -> if (Build.VERSION.SDK_INT < 33 && (Build.VERSION.SDK_INT < 31 || has(Manifest.permission.BLUETOOTH_CONNECT))) {
            val ad = context.getSystemService(BluetoothManager::class.java)?.adapter
            val target = on ?: !(ad?.isEnabled ?: false)
            @Suppress("DEPRECATION") if (target) ad?.enable() else ad?.disable()
            SysResult.Done(if (target) "Bluetooth on." else "Bluetooth off.")
        } else {
            start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            SysResult.OpenedSettings("I've opened Bluetooth settings for you.")
        }

        SystemSetting.DO_NOT_DISTURB -> {
            val nm = context.getSystemService(NotificationManager::class.java)!!
            if (nm.isNotificationPolicyAccessGranted) {
                val target = on ?: (nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL)
                nm.setInterruptionFilter(if (target) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
                SysResult.Done(if (target) "Do not disturb is on." else "Do not disturb is off.")
            } else {
                start(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                SysResult.NeedsPermission("dnd", "Please allow NEXUS to control Do Not Disturb, then ask again.")
            }
        }

        SystemSetting.AUTO_ROTATE -> if (Settings.System.canWrite(context)) {
            val cur = Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
            val target = on ?: !cur
            Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (target) 1 else 0)
            SysResult.Done(if (target) "Auto-rotate on." else "Auto-rotate off.")
        } else needWriteSettings()

        SystemSetting.MOBILE_DATA -> {
            start(Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS))
            SysResult.OpenedSettings("I've opened the internet settings.")
        }
        SystemSetting.AIRPLANE_MODE -> { start(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)); SysResult.OpenedSettings("I've opened airplane mode settings.") }
        SystemSetting.LOCATION -> { start(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); SysResult.OpenedSettings("I've opened location settings.") }
        SystemSetting.HOTSPOT -> { start(Intent(Settings.ACTION_WIRELESS_SETTINGS)); SysResult.OpenedSettings("I've opened network settings — hotspot is in there.") }
        SystemSetting.DARK_MODE -> { start(Intent(Settings.ACTION_DISPLAY_SETTINGS)); SysResult.OpenedSettings("I've opened display settings for dark mode.") }
    }

    private fun needWriteSettings(): SysResult {
        start(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
        return SysResult.NeedsPermission("write_settings", "Please allow NEXUS to modify system settings, then ask again.")
    }

    override suspend fun setVolume(percent: Int, relative: Boolean): SysResult {
        val am = context.getSystemService(AudioManager::class.java)!!
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = if (relative) (cur + Math.round(max * percent / 100f).let { if (it == 0) (if (percent > 0) 1 else -1) else it }) else Math.round(max * percent / 100f)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target.coerceIn(0, max), AudioManager.FLAG_SHOW_UI)
        return SysResult.Done("Volume ${(target.coerceIn(0, max) * 100 / max)}%.")
    }

    override suspend fun setBrightness(percent: Int, relative: Boolean): SysResult {
        if (!Settings.System.canWrite(context)) return needWriteSettings()
        val cr = context.contentResolver
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255
        val target = (if (relative) cur + percent else percent).coerceIn(1, 100)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target * 255 / 100)
        return SysResult.Done("Brightness $target%.")
    }

    // ------------------------------------------------------------ media

    private fun activeController() = runCatching {
        if (!NexusNotificationListener.connected) return@runCatching null
        context.getSystemService(MediaSessionManager::class.java)!!.getActiveSessions(NexusNotificationListener.component(context)).firstOrNull()
    }.getOrNull()

    override suspend fun mediaKey(key: MediaKey): SysResult {
        activeController()?.transportControls?.let { tc ->
            when (key) {
                MediaKey.PLAY -> tc.play()
                MediaKey.PAUSE -> tc.pause()
                MediaKey.NEXT -> tc.skipToNext()
                MediaKey.PREVIOUS -> tc.skipToPrevious()
                MediaKey.STOP -> tc.stop()
                MediaKey.PLAY_PAUSE -> tc.pause()
            }
            return SysResult.Done("")
        }
        val code = when (key) {
            MediaKey.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaKey.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaKey.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            MediaKey.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaKey.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            MediaKey.STOP -> KeyEvent.KEYCODE_MEDIA_STOP
        }
        val am = context.getSystemService(AudioManager::class.java)!!
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return SysResult.Done("")
    }

    override suspend fun seek(seconds: Int): SysResult {
        val c = activeController() ?: return SysResult.Failed("I need notification access to control playback position.")
        val pos = c.playbackState?.position ?: return SysResult.Failed("Nothing is playing.")
        c.transportControls.seekTo((pos + seconds * 1000L).coerceAtLeast(0))
        return SysResult.Done("")
    }

    override suspend fun playFromSearch(query: String, packageName: String?): SysResult {
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(SearchManager.QUERY, query)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        val pkg = packageName ?: listOf("com.spotify.music", "com.google.android.apps.youtube.music").firstOrNull { p ->
            runCatching { context.packageManager.getPackageInfo(p, 0) }.isSuccess
        }
        if (pkg != null) i.setPackage(pkg)
        if (start(i)) return SysResult.Done("")
        if (pkg == "com.spotify.music" && start(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(query))))) return SysResult.Done("I've searched Spotify for $query.")
        return SysResult.Failed("No music app could play that.")
    }

    // ------------------------------------------------------------ settings

    override suspend fun openSettings(screen: SettingsScreen, packageName: String?): SysResult {
        val action = when (screen) {
            SettingsScreen.MAIN -> Settings.ACTION_SETTINGS
            SettingsScreen.WIFI -> Settings.ACTION_WIFI_SETTINGS
            SettingsScreen.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
            SettingsScreen.DISPLAY -> Settings.ACTION_DISPLAY_SETTINGS
            SettingsScreen.BATTERY -> Intent.ACTION_POWER_USAGE_SUMMARY
            SettingsScreen.SOUND -> Settings.ACTION_SOUND_SETTINGS
            SettingsScreen.APPS -> Settings.ACTION_APPLICATION_SETTINGS
            SettingsScreen.APP_DETAILS -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            SettingsScreen.ACCESSIBILITY -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            SettingsScreen.NETWORK -> Settings.ACTION_WIRELESS_SETTINGS
            SettingsScreen.LOCATION -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            SettingsScreen.STORAGE -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            SettingsScreen.NOTIFICATIONS -> if (packageName != null) Settings.ACTION_APP_NOTIFICATION_SETTINGS else Settings.ACTION_SETTINGS
            SettingsScreen.DATE_TIME -> Settings.ACTION_DATE_SETTINGS
            SettingsScreen.PRIVACY -> Settings.ACTION_PRIVACY_SETTINGS
        }
        val i = Intent(action)
        if (screen == SettingsScreen.APP_DETAILS && packageName != null) i.data = Uri.parse("package:$packageName")
        if (screen == SettingsScreen.NOTIFICATIONS && packageName != null) i.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        return if (start(i)) SysResult.Done("") else SysResult.Failed("That settings page isn't available on this phone.")
    }

    // ------------------------------------------------------------ notifications

    override suspend fun recentNotifications(limit: Int): List<PhoneNotification> = NexusNotificationListener.recent(limit)

    override suspend fun openNotification(key: String): SysResult {
        val pi = NexusNotificationListener.intentFor(key) ?: return SysResult.Failed("That notification is gone.")
        return runCatching { pi.send(); SysResult.Done("") }.getOrElse { SysResult.Failed("Couldn't open it.") }
    }

    // ------------------------------------------------------------ files

    override suspend fun searchFiles(query: String, mimePrefix: String?, limit: Int): List<FileItem> = withContext(Dispatchers.IO) {
        val uri = MediaStore.Files.getContentUri("external")
        val sel = ArrayList<String>(); val args = ArrayList<String>()
        if (query.isNotBlank()) { sel += "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"; args += "%$query%" }
        if (mimePrefix != null) { sel += "${MediaStore.MediaColumns.MIME_TYPE} LIKE ?"; args += "$mimePrefix%" }
        val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.SIZE)
        val out = ArrayList<FileItem>()
        runCatching {
            context.contentResolver.query(uri, proj, sel.joinToString(" AND ").ifBlank { null }, args.toTypedArray(), "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val id = c.getLong(0)
                    out += FileItem(c.getString(1) ?: "file", ContentUris.withAppendedId(uri, id).toString(), c.getString(2), c.getLong(3) * 1000, c.getLong(4))
                }
            }
        }
        out
    }

    override suspend fun openFile(file: FileItem): SysResult {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(file.path), file.mimeType ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return if (start(i)) SysResult.Done("") else SysResult.Failed("No app can open ${file.name}.")
    }

    override suspend fun shareFile(file: FileItem): SysResult {
        val send = Intent(Intent.ACTION_SEND).setType(file.mimeType ?: "*/*").putExtra(Intent.EXTRA_STREAM, Uri.parse(file.path)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return if (start(Intent.createChooser(send, "Share ${file.name}"))) SysResult.Done("") else SysResult.Failed("Sharing isn't available.")
    }

    override suspend fun composeEmail(to: String?, subject: String?, body: String?): SysResult {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        to?.takeIf { it.contains('@') }?.let { i.putExtra(Intent.EXTRA_EMAIL, arrayOf(it)) }
        subject?.let { i.putExtra(Intent.EXTRA_SUBJECT, it) }
        body?.let { i.putExtra(Intent.EXTRA_TEXT, it) }
        if (runCatching { context.packageManager.getPackageInfo("com.google.android.gm", 0) }.isSuccess) i.setPackage("com.google.android.gm")
        return if (start(i)) SysResult.Done(if (to != null && !to.contains('@')) "Add $to as the recipient — I couldn't find their email address." else "") else SysResult.Failed("No email app found.")
    }

    override suspend fun setAlarm(hour: Int, minute: Int, label: String?): SysResult {
        val i = Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
            .putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
            .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
        label?.let { i.putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, it) }
        return if (start(i)) SysResult.Done("") else SysResult.Failed("No clock app could set the alarm.")
    }

    override suspend fun setTimer(seconds: Int, label: String?): SysResult {
        val i = Intent(android.provider.AlarmClock.ACTION_SET_TIMER)
            .putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
        label?.let { i.putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, it) }
        return if (start(i)) SysResult.Done("") else SysResult.Failed("No clock app could set the timer.")
    }

    override suspend fun replyToNotification(fromName: String, text: String): SysResult? {
        val (_, action) = NexusNotificationListener.replyTarget(fromName) ?: return null
        val inputs = action.remoteInputs ?: return null
        return runCatching {
            val results = android.os.Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
            val fill = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            android.app.RemoteInput.addResultsToIntent(inputs, fill, results)
            action.actionIntent.send(context, 0, fill)
            SysResult.Done("")
        }.getOrNull()
    }

    override suspend fun batteryPercent(): Int? = context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
}
