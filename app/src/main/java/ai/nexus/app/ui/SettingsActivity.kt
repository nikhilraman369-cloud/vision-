package ai.nexus.app.ui

import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ai.nexus.app.NexusApp
import ai.nexus.app.ui.Ui.button
import ai.nexus.app.ui.Ui.dp
import ai.nexus.app.ui.Ui.label
import ai.nexus.app.ui.Ui.vbox
import ai.nexus.core.memory.UserSettings

/** Voice, language, privacy and AI settings. */
class SettingsActivity : AppCompatActivity() {
    private val app by lazy { NexusApp.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = app.memory.settings
        val box = vbox(16).apply { setBackgroundColor(Ui.BG) }
        fun section(t: String) = box.addView(label(t, 13f, Ui.ACCENT, bold = true), Ui.matchWrap().apply { topMargin = dp(18) })
        fun field(hint: String, value: String?, secret: Boolean = false) = EditText(this).apply {
            this.hint = hint; setText(value ?: ""); setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED)
            if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            box.addView(this, Ui.matchWrap())
        }
        fun toggle(title: String, value: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
            text = title; isChecked = value; setTextColor(Ui.TEXT); textSize = 15f
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, v -> onChange(v) }
            box.addView(this, Ui.matchWrap())
        }
        fun update(f: (UserSettings) -> UserSettings) { app.memory.updateSettings(f); app.applySettings() }

        box.addView(label("Settings", 22f, bold = true))

        section("YOU")
        val name = field("Your name", s.name)

        section("CLOUD AI (CLAUDE)")
        box.addView(label("Optional. Without a key NEXUS still runs phone commands offline. With it, NEXUS can chat, answer questions and handle apps it doesn't know. Get a key at console.anthropic.com.", 13f, Ui.MUTED))
        val key = field("Anthropic API key", app.apiKey, secret = true)
        val model = field("Model", app.model)
        toggle("Use cloud AI", s.useCloudAi) { v -> update { it.copy(useCloudAi = v) } }
        toggle("Send screenshots to the AI when needed (better on apps with unlabeled buttons)", s.sendScreenshotsToCloud) { v -> update { it.copy(sendScreenshotsToCloud = v) } }

        section("VOICE")
        val langs = listOf("auto" to "Auto (English + Hinglish)", "en" to "English", "hi" to "Hindi (हिन्दी)")
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, langs.map { it.second })
            setSelection(langs.indexOfFirst { it.first == s.language }.coerceAtLeast(0))
            box.addView(this, Ui.matchWrap())
        }
        toggle("Speak replies", s.speakResponses) { v -> update { it.copy(speakResponses = v) } }
        toggle("Continuous listening (keeps listening after each reply)", s.continuousListening) { v -> update { it.copy(continuousListening = v) } }
        toggle("Wake word (only respond after \"Hey Nexus\" in continuous mode)", s.wakeWordEnabled) { v -> update { it.copy(wakeWordEnabled = v) } }
        val wake = field("Wake word", s.wakeWord)
        box.addView(label("Speech speed", 14f, Ui.MUTED))
        SeekBar(this).apply {
            max = 150; progress = ((s.speechRate - 0.5f) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) update { it.copy(speechRate = 0.5f + p / 100f) } }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            box.addView(this, Ui.matchWrap())
        }

        section("SAFETY")
        toggle("Ask before sending messages", s.confirmBeforeSending) { v -> update { it.copy(confirmBeforeSending = v) } }

        box.addView(button("Save") {
            app.apiKey = key.text.toString().ifBlank { null }
            app.model = model.text.toString().ifBlank { "claude-opus-5-5" }
            val lang = langs[spinner.selectedItemPosition].first
            update { it.copy(name = name.text.toString().ifBlank { null }, wakeWord = wake.text.toString().ifBlank { "hey nexus" }.lowercase(), language = lang) }
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }, Ui.matchWrap().apply { topMargin = dp(20) })

        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(box, LinearLayout.LayoutParams(-1, -2)) })
    }
}
