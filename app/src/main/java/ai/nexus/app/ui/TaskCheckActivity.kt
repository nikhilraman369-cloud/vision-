package ai.nexus.app.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ai.nexus.app.NexusApp
import ai.nexus.app.hearing.SpeechKeys
import ai.nexus.app.service.NexusAccessibilityService
import ai.nexus.app.ui.Ui.button
import ai.nexus.app.ui.Ui.dp
import ai.nexus.app.ui.Ui.label
import ai.nexus.app.ui.Ui.vbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Category A self-check. Part 1 checks everything each task depends on (no side effects) and shows ✅ / ❌ with the fix.
 * Part 2 has a "Try" button per task that runs the real command, so each one can be tested in seconds.
 * "Copy results" puts the whole check in the clipboard to paste into a chat.
 */
class TaskCheckActivity : AppCompatActivity() {
    private lateinit var box: LinearLayout
    private val app by lazy { NexusApp.get(this) }
    private val results = ArrayList<Triple<String, Boolean, String>>()   // name, ok, detail/fix
    private var checked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        box = vbox(18).apply { setBackgroundColor(Ui.BG) }
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; setBackgroundColor(Ui.BG); addView(box) })
        render()
        runChecks()
    }

    override fun onResume() { super.onResume(); if (checked) runChecks() }

    private fun installed(pkg: String) = runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess
    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun handles(i: Intent) = packageManager.queryIntentActivities(i, 0).isNotEmpty()

    private fun waCallEntries(): Int = if (!granted(Manifest.permission.READ_CONTACTS)) 0 else runCatching {
        contentResolver.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data._ID),
            "${ContactsContract.Data.MIMETYPE} = ?", arrayOf("vnd.android.cursor.item/vnd.com.whatsapp.voip.call"), null)?.use { it.count } ?: 0
    }.getOrDefault(0)

    private fun runChecks() {
        checked = true
        lifecycleScope.launch {
            val r = ArrayList<Triple<String, Boolean, String>>()
            val acc = NexusAccessibilityService.instance != null || (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
                .contains(ComponentName(this@TaskCheckActivity, NexusAccessibilityService::class.java).flattenToString())
            r += Triple("Screen control (Accessibility)", acc, if (acc) "On" else "Needed to type and tap in apps. Fix: Permissions → Accessibility → NEXUS.")
            r += Triple("Display over other apps", Settings.canDrawOverlays(this@TaskCheckActivity), "Needed so NEXUS can open apps from the bubble.")
            r += Triple("Hearing (Sarvam key)", SpeechKeys.get(this@TaskCheckActivity, SpeechKeys.SARVAM) != null, "Settings → Listening → Sarvam API key.")
            // WhatsApp
            val wa = installed("com.whatsapp")
            r += Triple("WhatsApp installed", wa, "Install WhatsApp.")
            r += Triple("Contacts permission", granted(Manifest.permission.READ_CONTACTS), "Needed to find who to message or call.")
            val entries = withContext(Dispatchers.IO) { waCallEntries() }
            r += Triple("WhatsApp call shortcuts", entries > 0, if (entries > 0) "$entries contacts can be called directly" else "None found, so calls will use the chat's call button instead (still works). To fix: WhatsApp → Settings → Privacy → Contacts, allow.")
            r += Triple("Phone calls permission", granted(Manifest.permission.CALL_PHONE), "For normal phone calls without the dialler step.")
            // Media
            r += Triple("YouTube installed", installed("com.google.android.youtube"), "Install YouTube.")
            r += Triple("YouTube Music installed", installed("com.google.android.apps.youtube.music"), "Install YouTube Music.")
            val lookup = withTimeoutOrNull(9000) { runCatching { app.systemActions.resolveVideoId("kesariya", true) }.getOrNull() }
            r += Triple("Song lookup online", lookup != null, if (lookup != null) "Works (finds the song so it starts playing directly)" else "Couldn't reach YouTube. Check internet; NEXUS will fall back to tapping results.")
            r += Triple("Media control (notification access)", ai.nexus.app.service.NexusNotificationListener.connected, "For pause / next / previous. Fix: Permissions → Notification access.")
            // AI apps
            r += Triple("ChatGPT installed", installed("com.openai.chatgpt"), "Install ChatGPT.")
            r += Triple("Claude installed", installed("com.anthropic.claude"), "Install Claude.")
            r += Triple("Gemini installed", installed("com.google.android.apps.bard"), "Install the Gemini app.")
            // Browser, clock, camera, torch
            r += Triple("Chrome installed", installed("com.android.chrome"), "Searches will open in your default browser.")
            r += Triple("Clock: alarms", handles(Intent(AlarmClock.ACTION_SET_ALARM)), "No clock app accepts alarms.")
            r += Triple("Clock: timers", handles(Intent(AlarmClock.ACTION_SET_TIMER)), "No clock app accepts timers.")
            r += Triple("Camera", handles(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)), "No camera app found.")
            val torch = runCatching { getSystemService(CameraManager::class.java)!!.let { cm -> cm.cameraIdList.any { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } } }.getOrDefault(false)
            r += Triple("Flashlight", torch, "No flash found.")
            results.clear(); results += r
            render()
        }
    }

    private fun card(build: LinearLayout.() -> Unit) {
        val c = vbox(14).apply { background = Ui.rounded(Ui.CARD, dp(16).toFloat()); build() }
        box.addView(c, Ui.matchWrap().apply { topMargin = dp(12) })
    }

    private val tryName by lazy { EditText(this).apply { hint = "A contact's name (for the WhatsApp tests)"; setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED); textSize = 14f } }

    private fun render() {
        box.removeAllViews()
        box.addView(label("Category A self-check", 26f, bold = true))
        box.addView(label("Part 1 checks what every task needs. Part 2 runs each task for real.", 13.5f, Ui.MUTED))

        card {
            val ok = results.count { it.second }
            addView(label(if (results.isEmpty()) "Checking…" else "Part 1: $ok of ${results.size} ready", 16f, bold = true))
            for ((name, good, detail) in results) {
                addView(label("${if (good) "✅" else "❌"}  $name", 14.5f), Ui.matchWrap().apply { topMargin = dp(8) })
                if (!good || detail.startsWith("Works") || detail.endsWith("directly")) addView(label("     $detail", 12.5f, if (good) Ui.MUTED else Ui.WARN))
            }
            val row = LinearLayout(this@TaskCheckActivity).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(button("Check again") { runChecks() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
            row.addView(button("Copy results", Ui.USER) { copy() }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(row, Ui.matchWrap().apply { topMargin = dp(12) })
            addView(button("Open permissions", Ui.BG) { startActivity(Intent(this@TaskCheckActivity, PanelActivity::class.java).putExtra("section", "permissions")) }, Ui.matchWrap().apply { topMargin = dp(6) })
        }

        card {
            addView(label("Part 2: try each task", 16f, bold = true))
            addView(label("Each button runs the real command. Watch the phone, then come back here.", 12.5f, Ui.MUTED))
            addView(tryName, Ui.matchWrap().apply { topMargin = dp(8) })
            fun who() = tryName.text.toString().trim().ifBlank { null }
            val tests: List<Pair<String, () -> String?>> = listOf(
                "WhatsApp: open chat" to { who()?.let { "open whatsapp chat with $it" } },
                "WhatsApp: type (won't send)" to { who()?.let { "type hello from nexus to $it but don't send" } },
                "WhatsApp: voice call ⚠ really calls" to { who()?.let { "whatsapp call $it" } },
                "WhatsApp: video call ⚠ really calls" to { who()?.let { "whatsapp video call $it" } },
                "YouTube: play a video" to { "play believer imagine dragons on youtube" },
                "YouTube Music: play a song" to { "play kesariya on youtube music" },
                "Pause" to { "pause" },
                "ChatGPT: ask" to { "ask chatgpt what is two plus two" },
                "Claude: ask" to { "ask claude say hi in one word" },
                "Gemini: ask" to { "ask gemini say hi in one word" },
                "Google search" to { "google karo weather today" },
                "Timer 10 seconds" to { "set a timer for 10 seconds" },
                "Alarm (next 7 o'clock)" to { "alarm laga do 7 baje" },
                "Reminder in 1 minute" to { "remind me in 1 minute to test nexus" },
                "Volume 50%" to { "volume 50" },
                "Volume up" to { "volume up" },
                "Flashlight on" to { "torch on" },
                "Flashlight off" to { "torch off" },
                "Camera" to { "open camera" },
                "Selfie camera" to { "take a selfie" },
            )
            for ((title, phrase) in tests) {
                addView(button("▶  $title", Ui.BG) {
                    val p = phrase() ?: run { Toast.makeText(this@TaskCheckActivity, "Type a contact's name first", Toast.LENGTH_SHORT).show(); return@button }
                    Toast.makeText(this@TaskCheckActivity, "\"$p\"", Toast.LENGTH_SHORT).show()
                    app.nexus.submit(p)
                }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }, Ui.matchWrap().apply { topMargin = dp(6) })
            }
        }
    }

    private fun copy() {
        val text = "NEXUS Category A self-check (${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}, NEXUS ${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()})\n" +
            results.joinToString("\n") { (n, ok, d) -> "${if (ok) "OK  " else "FAIL"}  $n" + if (!ok || d.startsWith("Works") || d.endsWith("directly")) " — $d" else "" }
        getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("NEXUS check", text))
        Toast.makeText(this, "Copied — paste it in the chat", Toast.LENGTH_SHORT).show()
    }
}
