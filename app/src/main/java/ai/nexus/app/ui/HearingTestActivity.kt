package ai.nexus.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ai.nexus.app.hearing.AndroidEngine
import ai.nexus.app.hearing.DeepgramEngine
import ai.nexus.app.hearing.GroqWhisperEngine
import ai.nexus.app.hearing.SarvamEngine
import ai.nexus.app.hearing.SpeechEngine
import ai.nexus.app.hearing.SpeechKeys
import ai.nexus.app.hearing.WavRecorder
import ai.nexus.app.ui.Ui.button
import ai.nexus.app.ui.Ui.dp
import ai.nexus.app.ui.Ui.label
import ai.nexus.app.ui.Ui.vbox
import ai.nexus.core.hearing.HearingTest
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * The Hearing Test: you read each phrase once; the same recording goes to every speech engine;
 * each is scored on words heard and whether NEXUS would do the right thing. Then: Copy report.
 */
class HearingTestActivity : AppCompatActivity() {
    private lateinit var box: LinearLayout
    private val recorder by lazy { WavRecorder(this) }
    private val trials = ArrayList<HearingTest.Trial>()
    private var index = 0
    private var busy = false
    private var job: Job? = null
    private val prefs by lazy { getSharedPreferences("nexus", MODE_PRIVATE) }

    private fun engines(): List<SpeechEngine> = listOf(
        SarvamEngine(this), DeepgramEngine(this), GroqWhisperEngine(this),
        AndroidEngine(this, "en-IN"), AndroidEngine(this, "hi-IN"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        box = vbox(18).apply { setBackgroundColor(Ui.BG) }
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; setBackgroundColor(Ui.BG); addView(box) })
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        render()
    }

    private fun card(build: LinearLayout.() -> Unit) {
        val c = vbox(14).apply { background = Ui.rounded(Ui.CARD, dp(16).toFloat()); build() }
        box.addView(c, Ui.matchWrap().apply { topMargin = dp(12) })
    }

    private fun render() {
        box.removeAllViews()
        box.addView(label("Hearing Test", 26f, bold = true))
        box.addView(label("Read each phrase the way you normally talk. The same recording goes to every speech engine, and we see which one hears you best.", 13.5f, Ui.MUTED))

        // ---- keys
        card {
            addView(label("Online engines (add the keys you have)", 15f, bold = true))
            addView(label("Keys stay on this phone and are only sent to that engine. Never paste them in a chat.", 12f, Ui.MUTED))
            for ((title, key, hint) in listOf(
                Triple("Sarvam AI", SpeechKeys.SARVAM, "sarvam.ai → dashboard → API key"),
                Triple("Deepgram", SpeechKeys.DEEPGRAM, "deepgram.com → console → API keys"),
                Triple("Groq (Whisper)", SpeechKeys.GROQ, "console.groq.com → API keys"),
            )) {
                val have = SpeechKeys.get(this@HearingTestActivity, key) != null
                addView(label((if (have) "✅ " else "○ ") + title, 14f, bold = true), Ui.matchWrap().apply { topMargin = dp(8) })
                val f = EditText(this@HearingTestActivity).apply {
                    this.hint = if (have) "saved — type to replace" else hint
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED); textSize = 13f
                }
                addView(f, Ui.matchWrap())
                f.setOnFocusChangeListener { _, has -> if (!has && f.text.isNotBlank()) SpeechKeys.set(this@HearingTestActivity, key, f.text.toString()) }
                tag = (tag as? MutableList<Pair<String, EditText>> ?: mutableListOf()).also { it += key to f }
            }
            addView(button("Save keys", Ui.BG) {
                @Suppress("UNCHECKED_CAST")
                (tag as? List<Pair<String, EditText>>)?.forEach { (k, f) -> if (f.text.isNotBlank()) SpeechKeys.set(this@HearingTestActivity, k, f.text.toString()) }
                Toast.makeText(this@HearingTestActivity, "Saved", Toast.LENGTH_SHORT).show(); render()
            }, Ui.matchWrap().apply { topMargin = dp(8) })
            val ready = engines().filter { it.unavailable() == null }.map { it.name }
            addView(label("Will test: " + ready.joinToString(", ").ifBlank { "nothing yet — add at least one key" }, 12f, Ui.MUTED), Ui.matchWrap().apply { topMargin = dp(6) })
        }

        // ---- mic
        card {
            addView(Switch(this@HearingTestActivity).apply {
                text = "Use my earbuds' microphone"; isChecked = prefs.getBoolean("hearing_earbuds", true); setTextColor(Ui.TEXT)
                setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("hearing_earbuds", v).apply() }
            })
            addView(label("Test the way you'll really use NEXUS. If your earbuds are in, keep this on.", 12f, Ui.MUTED))
        }

        // ---- the phrase
        if (index < HearingTest.PHRASES.size) card {
            addView(label("Phrase ${index + 1} of ${HearingTest.PHRASES.size}", 12.5f, Ui.MUTED))
            addView(label("“${HearingTest.PHRASES[index]}”", 21f, bold = true), Ui.matchWrap().apply { topMargin = dp(6) })
            val status = label(if (busy) "Listening… speak now (stops by itself when you finish)" else "Tap Record, then say it naturally.", 13f, Ui.MUTED)
            addView(status, Ui.matchWrap().apply { topMargin = dp(8) })
            val row = LinearLayout(this@HearingTestActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(button(if (busy) "■ Stop" else "● Record", if (busy) Ui.WARN else Ui.ACCENT) { if (busy) recorder.stop() else recordAndTest(status) })
            row.addView(LinearLayout(this@HearingTestActivity), LinearLayout.LayoutParams(dp(8), 1))
            if (!busy) row.addView(button("Skip", Ui.BG) { index++; render() })
            addView(row, Ui.matchWrap().apply { topMargin = dp(10) })
            // the last phrase's results
            trials.filter { it.phrase == HearingTest.PHRASES.getOrNull(index - 1) }.takeIf { it.isNotEmpty() }?.let { last ->
                addView(label("Last phrase:", 12.5f, Ui.MUTED, bold = true), Ui.matchWrap().apply { topMargin = dp(12) })
                last.forEach { t -> addView(label(resultLine(t), 12.5f, if (t.understood) Ui.OK else Ui.TEXT)) }
            }
        } else card {
            addView(label("Done! 🎉", 20f, bold = true))
            addView(label("Copy the report and send it in the chat. It shows which engine heard you best.", 13f, Ui.MUTED))
        }

        // ---- results so far
        if (trials.isNotEmpty()) card {
            addView(label("Scores so far (best first)", 15f, bold = true))
            HearingTest.score(trials).forEach { s ->
                addView(label("${s.engine}\n   understood ${s.understood}/${s.tried} · words ${s.avgAccuracy}% · ${s.avgMs} ms" + (if (s.errors > 0) " · ${s.errors} errors" else ""), 13f), Ui.matchWrap().apply { topMargin = dp(4) })
            }
            val row = LinearLayout(this@HearingTestActivity).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(button("Copy report") { copyReport() })
            row.addView(LinearLayout(this@HearingTestActivity), LinearLayout.LayoutParams(dp(8), 1))
            row.addView(button("Share", Ui.BG) {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report()), "Send the hearing report"))
            })
            addView(row, Ui.matchWrap().apply { topMargin = dp(10) })
        }
    }

    private fun resultLine(t: HearingTest.Trial) =
        (if (t.understood) "✓ " else "✗ ") + t.engine + ": " + (t.heard?.let { "“$it” · ${t.accuracy}% · ${t.ms} ms" } ?: "error: ${t.error}")

    private fun recordAndTest(status: TextView) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return }
        val ready = engines().filter { it.unavailable() == null }
        if (ready.isEmpty()) { Toast.makeText(this, "Add at least one key first (or use Android 13+ for the phone recogniser).", Toast.LENGTH_LONG).show(); return }
        val phrase = HearingTest.PHRASES[index]
        busy = true; render()
        job = lifecycleScope.launch {
            val pcm = recorder.record(prefs.getBoolean("hearing_earbuds", true))
            busy = false
            if (pcm.size < 16000) { Toast.makeText(this@HearingTestActivity, "That was too short. Try again.", Toast.LENGTH_SHORT).show(); render(); return@launch }
            val wav = WavRecorder.wav(pcm)
            runCatching { WavRecorder.save(File(filesDir, "hearing"), "phrase-${index + 1}.wav", pcm) }
            status.text = "Sending to ${ready.size} engines…"
            val results = ready.map { e ->
                async {
                    val t0 = System.currentTimeMillis()
                    runCatching { e.transcribe(wav, pcm) }.fold(
                        { HearingTest.Trial(phrase, e.name, it, System.currentTimeMillis() - t0) },
                        { HearingTest.Trial(phrase, e.name, null, 0, it.message?.take(120) ?: it.javaClass.simpleName) },
                    )
                }
            }.awaitAll()
            trials += results
            index++
            render()
        }
    }

    private fun report() = HearingTest.report(trials, "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · earbud mic ${prefs.getBoolean("hearing_earbuds", true)}")

    private fun copyReport() {
        getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("NEXUS hearing", report()))
        Toast.makeText(this, "Copied. Paste it in the chat.", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() { recorder.stop(); job?.cancel(); super.onDestroy() }
}
