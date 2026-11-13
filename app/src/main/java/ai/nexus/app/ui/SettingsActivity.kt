package ai.nexus.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ai.nexus.app.NexusApp
import ai.nexus.app.brain.PhoneBrainManager
import ai.nexus.app.ui.Ui.button
import ai.nexus.app.ui.Ui.dp
import ai.nexus.app.ui.Ui.label
import ai.nexus.app.ui.Ui.vbox
import ai.nexus.core.memory.UserSettings

/** Just the essentials: you, the brain, the voice, how to start listening, and safety. */
class SettingsActivity : AppCompatActivity() {
    private val app by lazy { NexusApp.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = app.memory.settings
        val box = vbox(20).apply { setBackgroundColor(Ui.BG) }
        fun update(f: (UserSettings) -> UserSettings) { app.memory.updateSettings(f); app.applySettings() }
        fun card(title: String, sub: String? = null): LinearLayout {
            val c = vbox(0).apply { background = Ui.rounded(Ui.CARD, dp(16).toFloat()); setPadding(dp(16), dp(14), dp(16), dp(14)) }
            c.addView(label(title, 17f, bold = true))
            sub?.let { c.addView(label(it, 13f, Ui.MUTED), Ui.matchWrap().apply { topMargin = dp(2) }) }
            box.addView(c, Ui.matchWrap().apply { topMargin = dp(14) })
            return c
        }
        fun LinearLayout.toggle(title: String, value: Boolean, onChange: (Boolean) -> Unit) = addView(Switch(this@SettingsActivity).apply {
            text = title; isChecked = value; setTextColor(Ui.TEXT); textSize = 15f; setPadding(0, dp(10), 0, dp(4))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }, Ui.matchWrap())
        fun LinearLayout.field(hint: String, value: String?) = EditText(this@SettingsActivity).apply {
            this.hint = hint; setText(value ?: ""); setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED); isSingleLine = true
            this@field.addView(this, Ui.matchWrap())
        }

        box.addView(label("Settings", 26f, bold = true))

        // ---- You
        val you = card("You")
        val name = you.field("Your name", s.name)
        val callMe = you.field("What should NEXUS call you? (e.g. Boss)", s.addressAs)

        // ---- Brain
        val brain = card("🧠  Brain", "Answers your questions and understands rough commands. Free, private, runs on your phone. Download once.")
        val bm = app.brainManager
        val status = label("", 14.5f)
        brain.addView(status, Ui.matchWrap().apply { topMargin = dp(8) })
        val smart = PhoneBrainManager.MODELS.first { it.id == "smart" }
        val fast = PhoneBrainManager.MODELS.first { it.id == "fast" }
        brain.addView(button("Download brain — Smart (${smart.sizeLabel}, best answers)") { bm.download(smart) }, Ui.matchWrap().apply { topMargin = dp(8) })
        brain.addView(button("Lighter brain — Fast (${fast.sizeLabel})", Ui.BG) { bm.download(fast) }, Ui.matchWrap().apply { topMargin = dp(6) })
        val ask = brain.field("Ask your brain something…", null)
        brain.addView(button("Ask", Ui.BG) { val q = ask.text.toString().trim(); if (q.isNotEmpty()) { app.ask(q); finish() } }, Ui.matchWrap())
        lifecycleScope.launch {
            bm.state.collect { st ->
                status.text = when (st) {
                    PhoneBrainManager.State.NotDownloaded -> "Not downloaded yet. Use Wi-Fi; it resumes if interrupted."
                    is PhoneBrainManager.State.Downloading -> "Downloading ${st.model.title}… ${st.percent}%"
                    PhoneBrainManager.State.Loading -> "Waking up…"
                    is PhoneBrainManager.State.Ready -> "✅ Ready — ${st.model.title}"
                    is PhoneBrainManager.State.Error -> "⚠ ${st.message}"
                }
            }
        }

        // ---- Voice
        val voice = card("🔊  Voice", "Pick from 20+ offline voices (British butler, female, American…).")
        voice.addView(button("Choose a voice →") { startActivity(Intent(this, PanelActivity::class.java).putExtra("section", "voice")) }, Ui.matchWrap().apply { topMargin = dp(8) })
        voice.toggle("Speak replies", s.speakResponses) { v -> update { it.copy(speakResponses = v) } }
        voice.addView(label("Speaking speed", 13f, Ui.MUTED), Ui.matchWrap().apply { topMargin = dp(8) })
        voice.addView(SeekBar(this).apply {
            max = 100; progress = ((s.speechRate - 0.6f) * 100).toInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) update { it.copy(speechRate = 0.6f + p / 100f) } }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }, Ui.matchWrap())

        // ---- Listening
        val listen = card("🎙  Listening",
            "Tap the floating N, or press volume up + down together. NEXUS listens, does it, then keeps listening 6 seconds for a follow-up and goes back to sleep.")
        listen.toggle("Volume up + down together starts listening", s.volumeKeys) { v -> update { it.copy(volumeKeys = v) } }
        listen.addView(label("Language", 13f, Ui.MUTED), Ui.matchWrap().apply { topMargin = dp(8) })
        val langs = listOf("auto" to "English + Hinglish", "en" to "English only", "hi" to "Hindi (हिन्दी)")
        listen.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, langs.map { it.second })
            setSelection(langs.indexOfFirst { it.first == s.language }.coerceAtLeast(0))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) { update { it.copy(language = langs[pos].first) } }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        }, Ui.matchWrap())

        // ---- Safety
        val safety = card("🛡  Safety", "Banking, payments, passwords and OTPs are never touched.")
        safety.toggle("Ask before sending messages", s.confirmBeforeSending) { v -> update { it.copy(confirmBeforeSending = v) } }

        box.addView(button("Memory — what NEXUS knows about you", Ui.CARD) { startActivity(Intent(this, MemoryActivity::class.java)) }.apply { gravity = Gravity.CENTER }, Ui.matchWrap().apply { topMargin = dp(14) })
        box.addView(button("Save") {
            update { it.copy(name = name.text.toString().trim().ifBlank { null }, addressAs = callMe.text.toString().trim().ifBlank { "Boss" }) }
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }, Ui.matchWrap().apply { topMargin = dp(10) })

        setContentView(ScrollView(this).apply { fitsSystemWindows = true; setBackgroundColor(Ui.BG); addView(box, LinearLayout.LayoutParams(-1, -2)) })
    }
}
