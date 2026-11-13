package ai.nexus.app.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import ai.nexus.app.NexusApp
import ai.nexus.app.service.NexusAccessibilityService
import ai.nexus.app.service.OverlayService
import ai.nexus.app.ui.Ui.bubble
import ai.nexus.app.ui.Ui.button
import ai.nexus.app.ui.Ui.dp
import ai.nexus.app.ui.Ui.label
import ai.nexus.app.ui.Ui.vbox
import ai.nexus.app.voice.VoiceState
import kotlinx.coroutines.launch

/** Conversation screen + setup checklist for the permissions NEXUS needs. */
class MainActivity : AppCompatActivity() {
    private val app by lazy { NexusApp.get(this) }
    private lateinit var chatBox: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var status: TextView
    private lateinit var homeBox: LinearLayout
    private lateinit var setupBox: LinearLayout
    private lateinit var mic: TextView
    private lateinit var input: EditText
    private lateinit var bubbleBtn: TextView
    private lateinit var heyBtn: TextView

    private fun refreshBubbleBtn() { bubbleBtn.text = if (OverlayService.running) "Hide bubble" else "Show bubble" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = vbox(0).apply { setBackgroundColor(Ui.BG) }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(12), dp(6))
        }
        val titles = vbox(0).apply {
            addView(label("NEXUS", 22f, bold = true))
            status = label("Ready", 13f, Ui.MUTED); addView(status)
        }
        header.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bubbleBtn = button("Bubble", Ui.CARD) {
            if (OverlayService.running) OverlayService.stop(this) else if (Settings.canDrawOverlays(this)) startBubble() else renderSetup()
            bubbleBtn.postDelayed({ refreshBubbleBtn() }, 300)
        }
        header.addView(bubbleBtn)
        header.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        header.addView(button("Panel", Ui.ACCENT) { startActivity(Intent(this, PanelActivity::class.java)) })
        header.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        header.addView(button("Memory", Ui.CARD) { startActivity(Intent(this, MemoryActivity::class.java)) })
        header.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        header.addView(button("Settings", Ui.CARD) { startActivity(Intent(this, SettingsActivity::class.java)) })
        root.addView(header, Ui.matchWrap())

        // Second row: routines, help, and a debug helper to send exactly what NEXUS sees.
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), 0, dp(12), dp(4)) }
        heyBtn = button("Hey Nexus: off", Ui.CARD) {
            val on = !app.memory.settings.handsFree
            if (on && app.voskModels.modelPath(app.memory.settings.speechModel) == null) {
                android.widget.Toast.makeText(this, "First download the offline speech model in Settings.", android.widget.Toast.LENGTH_LONG).show()
                startActivity(Intent(this, SettingsActivity::class.java)); return@button
            }
            if (on && !ensureMic()) return@button
            app.memory.updateSettings { it.copy(handsFree = on) }
            app.ensureHandsFree(this)
        }.apply { textSize = 13f }
        tools.addView(heyBtn)
        tools.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        tools.addView(button("Routines", Ui.CARD) { startActivity(Intent(this, RoutinesActivity::class.java)) }.apply { textSize = 13f })
        tools.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        tools.addView(button("What can I say?", Ui.CARD) { startActivity(Intent(this, HelpActivity::class.java)) }.apply { textSize = 13f })
        tools.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        tools.addView(button("Copy screen info", Ui.CARD) { copyDebug() }.apply { textSize = 13f })
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tools) }, Ui.matchWrap())

        setupBox = vbox(12)
        root.addView(setupBox, Ui.matchWrap())
        homeBox = vbox(0).apply { setPadding(dp(12), 0, dp(12), 0) }
        root.addView(homeBox, Ui.matchWrap())

        chatScroll = ScrollView(this)
        chatBox = vbox(12)
        chatScroll.addView(chatBox)
        root.addView(chatScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Suggestion chips
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), 0, dp(8), 0) }
        listOf("Open Instagram and go to Reels", "What's on my screen?", "Read my notifications", "Timer 5 minutes", "Watch me", "YouTube pe filmmaking search karo")
            .forEach { s -> chips.addView(button(s, Ui.CARD) { app.ask(s) }.apply { textSize = 13f }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) }) }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, Ui.matchWrap())

        // Input row
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(12)) }
        input = EditText(this).apply {
            hint = "Ask or tell NEXUS anything…"; setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED)
            background = Ui.rounded(Ui.CARD, dp(22).toFloat()); setPadding(dp(16), dp(12), dp(16), dp(12))
            imeOptions = EditorInfo.IME_ACTION_SEND; isSingleLine = true
            setOnEditorActionListener { _, id, ev -> if (id == EditorInfo.IME_ACTION_SEND || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { send(); true } else false }
        }
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        mic = label("🎤", 22f).apply {
            gravity = Gravity.CENTER; background = Ui.rounded(Ui.ACCENT, dp(26).toFloat())
            contentDescription = "Talk to NEXUS"
            setOnClickListener { if (ensureMic()) app.talk() }
        }
        row.addView(mic, LinearLayout.LayoutParams(dp(52), dp(52)).apply { leftMargin = dp(8) })
        root.addView(row, Ui.matchWrap())
        root.fitsSystemWindows = true
        setContentView(root)

        lifecycleScope.launch { app.chat.collect { render(it) } }
        lifecycleScope.launch { app.status.collect { status.text = it } }
        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(ai.nexus.app.voice.NexusVoiceService.on, ai.nexus.app.voice.NexusVoiceService.state) { on, st -> on to st }.collect { (on, st) ->
                heyBtn.text = if (!on) "Hey Nexus: off" else if (st == ai.nexus.core.voice.HandsFreeState.ACTIVE_COMMAND_WINDOW) "Hey Nexus: listening" else "Hey Nexus: on"
                heyBtn.background = Ui.rounded(if (on) Ui.OK else Ui.CARD, dp(12).toFloat())
            }
        }
        lifecycleScope.launch {
            app.voice.state.collect { v ->
                mic.background = Ui.rounded(if (v == VoiceState.LISTENING || v == VoiceState.HEARING) Ui.OK else Ui.ACCENT, dp(26).toFloat())
                if (v == VoiceState.LISTENING) status.text = "Listening…"
            }
        }
        lifecycleScope.launch { app.voice.partial.collect { if (it.isNotBlank()) status.text = "“$it”" } }

        if (intent?.action == Intent.ACTION_ASSIST && ensureMic()) app.voice.startListening()
        handleAction(intent)
        lifecycleScope.launch { app.mode.collect { renderHome() } }
        offerVoicePack()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleAction(intent) }

    override fun onResume() { super.onResume(); renderSetup(); renderHome(); refreshBubbleBtn(); app.ensureHandsFree(this) }

    /** The bubble's stuck card sends "Type instruction" here. */
    private fun handleAction(i: Intent?) {
        if (i?.getStringExtra("action") != "instruct") return
        i.removeExtra("action")
        val e = EditText(this).apply { hint = "e.g. tap the three dots, then Settings" }
        android.app.AlertDialog.Builder(this).setTitle("Tell NEXUS what to do").setView(e)
            .setPositiveButton("Go") { _, _ -> val t = e.text.toString().trim(); if (t.isNotEmpty()) { app.clearStuck(); app.nexus.stuckInstruction(t) ; moveTaskToBack(true) } }
            .setNegativeButton("Cancel", null).show()
    }

    /** Mode, next deadline and top tasks at a glance. */
    private fun renderHome() {
        if (!::homeBox.isInitialized) return
        homeBox.removeAllViews()
        val now = System.currentTimeMillis()
        val d = app.life.data
        val top = ai.nexus.core.life.Briefs.rank(app.life.store.openTasks(), d.activeMode, now).take(3)
        if (d.activeMode == null && top.isEmpty()) return
        val card = vbox(0).apply { background = Ui.rounded(Ui.CARD, dp(12).toFloat()); setPadding(dp(12), dp(8), dp(12), dp(8)) }
        d.activeMode?.let { m -> card.addView(label("● $m mode is on", 14f, Ui.OK, bold = true)) }
        top.forEach { t -> card.addView(label("• ${t.title}" + (t.dueMs?.let { " — " + ai.nexus.core.life.Briefs.whenText(it, now) } ?: ""), 13.5f)) }
        card.setOnClickListener { startActivity(Intent(this, PanelActivity::class.java).putExtra("section", "tasks")) }
        homeBox.addView(card, Ui.matchWrap().apply { topMargin = dp(4); bottomMargin = dp(4) })
    }

    /** First run: offer the butler voice once. */
    private fun offerVoicePack() {
        val prefs = getSharedPreferences("nexus", MODE_PRIVATE)
        val id = app.memory.settings.voicePack
        if (prefs.getBoolean("voiceOffered", false) || app.voicePacks.installed(id)) return
        prefs.edit().putBoolean("voiceOffered", true).apply()
        android.app.AlertDialog.Builder(this).setTitle("Give NEXUS its butler voice?")
            .setMessage("A natural British male voice that runs fully offline (about 60 MB, one-time download). You can change it later in Panel → Voice.")
            .setPositiveButton("Download") { _, _ -> app.voicePacks.download(id, wifiOnly = false) { app.loadVoice() } }
            .setNegativeButton("Later", null).show()
    }

    /**
     * Copies what NEXUS saw on the last app screen plus the recent conversation,
     * so you can paste it to whoever is helping you improve NEXUS.
     */
    private fun copyDebug() {
        lifecycleScope.launch {
            val screen = app.lastAppScreen ?: "(no app screen captured yet — use NEXUS in another app first)"
            val chat = app.chat.value.takeLast(15).joinToString("\n") { (if (it.fromUser) "You: " else "NEXUS: ") + it.text }
            val text = "NEXUS ${packageManager.getPackageInfo(packageName, 0).versionName} debug\n--- last screen ---\n$screen\n--- recent ---\n$chat"
            getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("NEXUS debug", text))
            android.widget.Toast.makeText(this@MainActivity, "Copied. Paste it in your chat with Claude.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun send() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        app.ask(t)
    }

    private fun render(lines: List<ai.nexus.app.ChatLine>) {
        chatBox.removeAllViews()
        if (lines.isEmpty()) {
            chatBox.addView(label("Hi! Talk to me naturally — in English, Hindi or Hinglish.\n\nTry: “Open YouTube, search filmmaking, play the first video and like it”, or “Rahul ko WhatsApp pe bolo ki main das minute mein pahunchunga”.", 15f, Ui.MUTED))
        }
        lines.forEach { chatBox.addView(bubble(it.text, it.fromUser, it.detail)) }
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ------------------------------------------------------------ setup checklist

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun accessibilityOn(): Boolean {
        if (NexusAccessibilityService.instance != null) return true
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.contains(ComponentName(this, NexusAccessibilityService::class.java).flattenToString())
    }

    private fun notificationsOn(): Boolean =
        (Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "").contains(packageName)

    private fun renderSetup() {
        setupBox.removeAllViews()
        data class Step(val title: String, val why: String, val ok: Boolean, val fix: () -> Unit)
        val steps = listOf(
            Step("Phone control", "Accessibility service, so NEXUS can see and operate the screen", accessibilityOn()) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            Step("Microphone", "To hear you", granted(Manifest.permission.RECORD_AUDIO)) { ensureMic() },
            Step("Floating bubble", "Display over other apps", Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            },
            Step("Contacts, calls & SMS", "To call and message people by name", granted(Manifest.permission.READ_CONTACTS) && granted(Manifest.permission.CALL_PHONE)) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS, Manifest.permission.ANSWER_PHONE_CALLS), 2)
            },
            Step("Notifications", "To read notifications and control media", notificationsOn()) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            Step("Files", "To find your photos, videos and documents", if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_IMAGES) else granted(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                val perms = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                ActivityCompat.requestPermissions(this, perms, 3)
            },
            Step("Phone brain (free)", "Download once so NEXUS understands rough commands offline", app.brainManager.installedModel() != null) {
                startActivity(Intent(this, SettingsActivity::class.java))
            },
            Step("Offline voice (free)", "Download the speech model for hands-free \"Hey Nexus\"", app.voskModels.modelPath(app.memory.settings.speechModel) != null) {
                startActivity(Intent(this, SettingsActivity::class.java))
            },
        )
        val missing = steps.filter { !it.ok }
        if (missing.isEmpty()) {
            setupBox.addView(button(if (OverlayService.running) "Hide floating bubble" else "Show floating bubble", Ui.CARD) {
                if (OverlayService.running) OverlayService.stop(this) else startBubble()
                setupBox.postDelayed({ renderSetup() }, 300)
            })
            return
        }
        setupBox.addView(label("Set up NEXUS (${steps.size - missing.size}/${steps.size})", 14f, Ui.MUTED, bold = true))
        for (s in missing.take(3)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = Ui.rounded(Ui.CARD, dp(12).toFloat()); setPadding(dp(12), dp(8), dp(8), dp(8))
            }
            val txt = vbox(0).apply { addView(label(s.title, 15f, bold = true)); addView(label(s.why, 12.5f, Ui.MUTED)) }
            row.addView(txt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(button("Allow") { s.fix() })
            setupBox.addView(row, Ui.matchWrap().apply { topMargin = dp(6) })
        }
        if (Settings.canDrawOverlays(this) && granted(Manifest.permission.RECORD_AUDIO) && !OverlayService.running) {
            setupBox.addView(button("Show floating bubble", Ui.CARD) { startBubble() }, Ui.matchWrap().apply { topMargin = dp(6) })
        }
    }

    private fun startBubble() {
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4)
        }
        OverlayService.start(this)
    }

    private fun ensureMic(): Boolean {
        if (granted(Manifest.permission.RECORD_AUDIO)) return true
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        renderSetup()
    }

}
