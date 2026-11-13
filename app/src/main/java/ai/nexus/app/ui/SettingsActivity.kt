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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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

        section("PHONE BRAIN (FREE, OFFLINE)")
        box.addView(label("A small AI that runs on your phone — free, private, no internet needed after download. It understands rough or unusual commands. Common commands never wait for it, and anything it works out once becomes instant next time.", 13f, Ui.MUTED))
        val brainStatus = label("", 14f)
        box.addView(brainStatus, Ui.matchWrap().apply { topMargin = dp(6) })
        val bm = app.brainManager
        for (m in ai.nexus.app.brain.PhoneBrainManager.MODELS) {
            box.addView(button("Download ${m.title} (${m.sizeLabel})", Ui.CARD) { bm.download(m) }, Ui.matchWrap().apply { topMargin = dp(6) })
        }
        box.addView(label("Fast is recommended for speed. Use Wi-Fi; the download resumes if interrupted.", 12f, Ui.MUTED))
        val rowB = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowB.addView(button("Cancel download", Ui.CARD) { bm.cancelDownload() })
        rowB.addView(LinearLayout(this), LinearLayout.LayoutParams(dp(8), 1))
        rowB.addView(button("Delete model", Ui.WARN) { bm.delete() })
        box.addView(rowB, Ui.matchWrap().apply { topMargin = dp(6) })
        val customUrl = field("Advanced: custom .task model URL (optional)", null)
        box.addView(button("Download from custom URL", Ui.CARD) {
            val u = customUrl.text.toString().trim()
            if (u.startsWith("http")) { bm.setCustomUrl(u); bm.download(ai.nexus.app.brain.BrainModel("custom", "Custom model", "?", u, "custom.task")) }
        }, Ui.matchWrap().apply { topMargin = dp(4) })
        toggle("Use the phone brain", s.usePhoneBrain) { v -> update { it.copy(usePhoneBrain = v) } }
        val testOut = label("", 13f, Ui.MUTED)
        box.addView(button("Test the phone brain", Ui.CARD) {
            testOut.text = "Thinking…"
            lifecycleScope.launch { testOut.text = bm.test("insta pe reels dikha aur pehli wali like kar") }
        }, Ui.matchWrap().apply { topMargin = dp(6) })
        box.addView(testOut)
        lifecycleScope.launch {
            bm.state.collect { st ->
                brainStatus.text = when (st) {
                    ai.nexus.app.brain.PhoneBrainManager.State.NotDownloaded -> "Status: not downloaded"
                    is ai.nexus.app.brain.PhoneBrainManager.State.Downloading -> "Downloading ${st.model.title}… ${st.percent}%"
                    ai.nexus.app.brain.PhoneBrainManager.State.Loading -> "Loading into memory…"
                    is ai.nexus.app.brain.PhoneBrainManager.State.Ready -> "✅ Ready: ${st.model.title} (loaded in ${st.loadMs / 1000.0}s" +
                        (if (bm.lastLatencyMs > 0) ", last answer ${bm.lastLatencyMs} ms)" else ")")
                    is ai.nexus.app.brain.PhoneBrainManager.State.Error -> "⚠ ${st.message}"
                }
            }
        }

        section("HANDS-FREE: \"HEY NEXUS\" (FREE, 100% OFFLINE)")
        box.addView(label("Say \"Hey Nexus\" from anywhere, no tapping. After each command NEXUS keeps listening for 6.5 seconds, so you can chain commands. Speech is recognised on your phone by the free Vosk engine — nothing is sent online.", 13f, Ui.MUTED))
        val vm = app.voskModels
        val speechStatus = label("", 14f)
        box.addView(speechStatus, Ui.matchWrap().apply { topMargin = dp(6) })
        val modelIds = ai.nexus.app.voice.VoskModelManager.MODELS
        val modelSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, modelIds.map { it.title })
            setSelection(modelIds.indexOfFirst { it.id == s.speechModel }.coerceAtLeast(0))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    val mid = modelIds[pos].id
                    if (mid != app.memory.settings.speechModel) { update { it.copy(speechModel = mid) }; vm.refresh(mid) }
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
            box.addView(this, Ui.matchWrap())
        }
        box.addView(button("Download offline speech model", Ui.CARD) { vm.download(app.memory.settings.speechModel) }, Ui.matchWrap().apply { topMargin = dp(4) })
        toggle("Hands-free \"Hey Nexus\" (always listening)", s.handsFree) { v ->
            update { it.copy(handsFree = v) }
            if (v && vm.modelPath(app.memory.settings.speechModel) == null) {
                Toast.makeText(this, "Download the offline speech model first.", Toast.LENGTH_LONG).show()
            }
            app.ensureHandsFree(this)
        }
        toggle("Keep listening with the screen off / in your pocket (uses more battery)", s.listenScreenOff) { v ->
            update { it.copy(listenScreenOff = v) }; ai.nexus.app.voice.NexusVoiceService.refreshSettings()
        }
        toggle("Use Bluetooth earbuds' microphone (music sounds lower quality while it's on)", s.useEarbudMic) { v -> update { it.copy(useEarbudMic = v) } }
        lifecycleScope.launch {
            vm.state.collect { st ->
                speechStatus.text = when (st) {
                    ai.nexus.app.voice.VoskModelManager.State.Missing -> "Speech model: not downloaded"
                    is ai.nexus.app.voice.VoskModelManager.State.Downloading -> "Downloading speech model… ${st.percent}%"
                    ai.nexus.app.voice.VoskModelManager.State.Unpacking -> "Unpacking…"
                    is ai.nexus.app.voice.VoskModelManager.State.Ready -> "✅ Offline speech ready"
                    is ai.nexus.app.voice.VoskModelManager.State.Error -> "⚠ ${st.message}"
                }
                if (st is ai.nexus.app.voice.VoskModelManager.State.Ready) app.ensureHandsFree(this@SettingsActivity)
            }
        }

        section("SPEED")
        toggle("Quick confirmations (tick + vibration instead of saying \"Done\")", s.quietAcks) { v -> update { it.copy(quietAcks = v) } }
        toggle("Return to the app I was in after sending or sharing in the background", s.returnAfterTasks) { v -> update { it.copy(returnAfterTasks = v) } }

        section("AI BRAIN FOR HARD REQUESTS")
        box.addView(label("Simple commands always run instantly on the phone. Pick what handles unusual ones. Claude needs your own API key (paid, online); only the minimum text of each request is sent.", 12.5f, Ui.MUTED))
        val providers = listOf("local" to "Phone brain (free, offline)", "claude" to "Claude (online, your API key)", "off" to "Off (rules only)")
        Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, providers.map { it.second })
            setSelection(providers.indexOfFirst { it.first == s.llmProvider }.coerceAtLeast(0))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    if (providers[pos].first != app.memory.settings.llmProvider) { update { it.copy(llmProvider = providers[pos].first) }; app.rebuildBrain() }
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
            box.addView(this, Ui.matchWrap())
        }
        val keyField = EditText(this).apply {
            hint = if (app.apiKey != null) "Claude API key saved (type to replace)" else "Claude API key (sk-ant-…)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED)
        }
        box.addView(keyField, Ui.matchWrap())
        val modelField = EditText(this).apply { setText(app.model); hint = "Model"; setTextColor(Ui.TEXT); isSingleLine = true }
        box.addView(modelField, Ui.matchWrap())
        box.addView(button("Save AI settings", Ui.CARD) {
            keyField.text.toString().trim().takeIf { it.isNotEmpty() }?.let { app.apiKey = it }
            modelField.text.toString().trim().takeIf { it.isNotEmpty() }?.let { app.model = it }
            app.rebuildBrain(); keyField.setText("")
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }, Ui.matchWrap())

        section("VOICE")
        val langs = listOf("auto" to "Auto (English + Hinglish)", "en" to "English", "hi" to "Hindi (हिन्दी)")
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, langs.map { it.second })
            setSelection(langs.indexOfFirst { it.first == s.language }.coerceAtLeast(0))
            box.addView(this, Ui.matchWrap())
        }
        toggle("Tap-to-talk: use Google's online speech (more accurate, but not offline)", s.accurateListening) { v -> update { it.copy(accurateListening = v) } }
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
            val lang = langs[spinner.selectedItemPosition].first
            update { it.copy(name = name.text.toString().ifBlank { null }, wakeWord = wake.text.toString().ifBlank { "hey nexus" }.lowercase(), language = lang) }
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }, Ui.matchWrap().apply { topMargin = dp(20) })

        setContentView(ScrollView(this).apply { fitsSystemWindows = true; setBackgroundColor(Ui.BG); addView(box, LinearLayout.LayoutParams(-1, -2)) })
    }
}
