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
        val voice = card("🔊  Voice & conversation", "NEXUS's voice and character. Online through Sarvam (same key as hearing).")
        val P = ai.nexus.app.voice.Persona
        val styleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val voicePick = Spinner(this)
        val styleNote = label("", 12.5f, Ui.MUTED)
        fun showStyle() {
            val st = P.style(this)
            styleRow.removeAllViews()
            for (x in P.ALL) styleRow.addView(button(if (x.key == st.key) "✓ ${x.title}" else x.title, if (x.key == st.key) Ui.ACCENT else Ui.BG) {
                P.setStyle(this, x.key); showStyle()
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
            styleNote.text = st.blurb
            voicePick.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, st.voices.map { it.title })
            voicePick.setSelection(st.voices.indexOfFirst { it.id == P.voice(this, st) }.coerceAtLeast(0))
            voicePick.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) { P.setVoice(this@SettingsActivity, st, st.voices[pos].id) }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        }
        voice.addView(styleRow, Ui.matchWrap().apply { topMargin = dp(10) })
        voice.addView(styleNote, Ui.matchWrap().apply { topMargin = dp(4) })
        voice.addView(voicePick, Ui.matchWrap().apply { topMargin = dp(6) })
        voice.addView(button("▶  Hear it") {
            val line = if (P.style(this).key == "cute") "Hiii ${s.addressAs.ifBlank { "Boss" }}! I'm right here. What are we doing today?"
                else "Good to hear you, ${s.addressAs.ifBlank { "Boss" }}. Everything's under control. What do you need?"
            if (ai.nexus.app.hearing.SpeechKeys.get(this, ai.nexus.app.hearing.SpeechKeys.SARVAM) == null) Toast.makeText(this, "Save your Sarvam key below (Listening) first", Toast.LENGTH_LONG).show()
            app.voice.speak(line)
        }, Ui.matchWrap().apply { topMargin = dp(6) })
        showStyle()
        voice.toggle("Online voice (Sarvam)", P.onlineVoice(this)) { v -> P.setOnlineVoice(this, v) }
        voice.toggle("Online conversation (Sarvam)", P.onlineTalk(this)) { v -> P.setOnlineTalk(this, v); app.refreshTalker() }
        voice.addView(label("If the internet drops, NEXUS falls back to the downloaded voice or the phone's voice.", 12f, Ui.MUTED))
        voice.addView(button("Offline voices →", Ui.BG) { startActivity(Intent(this, PanelActivity::class.java).putExtra("section", "voice")) }, Ui.matchWrap().apply { topMargin = dp(8) })
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
            "Tap the floating N, the mic button, or the NEXUS tile in your quick settings (swipe down, tap the pencil, drag NEXUS up). NEXUS listens, does it, then keeps listening 6 seconds for a follow-up and goes back to sleep. Your volume buttons are never touched.")
        listen.addView(button("Use NEXUS as my phone's assistant (power button)", Ui.BG) {
            val i = Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)
            runCatching { startActivity(i) }.onFailure { runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) } }
        }, Ui.matchWrap().apply { topMargin = dp(8) })
        listen.addView(label("Pick NEXUS under “Digital assistant app”. Then the phone's assistant gesture (usually a long-press of the power button) starts NEXUS. A normal power-button press still locks and wakes the screen.", 12f, Ui.MUTED), Ui.matchWrap().apply { topMargin = dp(4) })
        // Ears: Sarvam (online, best for Hinglish) or the phone's own recogniser
        val np = getSharedPreferences("nexus", MODE_PRIVATE)
        val haveKey = ai.nexus.app.hearing.SpeechKeys.get(this, ai.nexus.app.hearing.SpeechKeys.SARVAM) != null
        listen.addView(label("Hearing", 13f, Ui.MUTED), Ui.matchWrap().apply { topMargin = dp(8) })
        val ears = listOf("sarvam" to "Sarvam AI (online, best for Hinglish)", "phone" to "Phone's recogniser")
        listen.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, ears.map { it.second })
            setSelection(ears.indexOfFirst { it.first == np.getString("ear_engine", "sarvam") }.coerceAtLeast(0))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) { np.edit().putString("ear_engine", ears[pos].first).apply() }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        }, Ui.matchWrap())
        val sarvamKey = EditText(this).apply {
            hint = if (haveKey) "Sarvam key saved (type to replace)" else "Sarvam API key (needed for Sarvam)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED); textSize = 13f
            setOnFocusChangeListener { _, has -> if (!has && text.isNotBlank()) { ai.nexus.app.hearing.SpeechKeys.set(this@SettingsActivity, ai.nexus.app.hearing.SpeechKeys.SARVAM, text.toString()); app.refreshTalker() } }
        }
        listen.addView(sarvamKey, Ui.matchWrap())
        listen.addView(Switch(this).apply {
            text = "Listen through my earbuds' microphone"; isChecked = np.getBoolean("hearing_earbuds", true); setTextColor(Ui.TEXT)
            setOnCheckedChangeListener { _, v -> np.edit().putBoolean("hearing_earbuds", v).apply() }
        }, Ui.matchWrap())
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
            if (sarvamKey.text.isNotBlank()) { ai.nexus.app.hearing.SpeechKeys.set(this, ai.nexus.app.hearing.SpeechKeys.SARVAM, sarvamKey.text.toString()); app.refreshTalker() }
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }, Ui.matchWrap().apply { topMargin = dp(10) })

        setContentView(ScrollView(this).apply { fitsSystemWindows = true; setBackgroundColor(Ui.BG); addView(box, LinearLayout.LayoutParams(-1, -2)) })
    }
}
