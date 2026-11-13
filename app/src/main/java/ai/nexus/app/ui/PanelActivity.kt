package ai.nexus.app.ui

import android.Manifest
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import ai.nexus.app.NexusApp
import ai.nexus.app.life.ReminderScheduler
import ai.nexus.app.service.NexusAccessibilityService
import ai.nexus.app.service.OverlayService
import ai.nexus.app.ui.Ui.button
import ai.nexus.app.ui.Ui.dp
import ai.nexus.app.ui.Ui.label
import ai.nexus.app.ui.Ui.vbox
import ai.nexus.app.voice.VoicePackManager
import ai.nexus.core.life.Briefs
import ai.nexus.core.life.Mode
import ai.nexus.core.life.NotificationIntel
import ai.nexus.core.life.SenderRule
import ai.nexus.core.life.TaskItem
import ai.nexus.core.memory.UserSettings
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The control panel: every NEXUS feature can be viewed, added, edited and
 * customised here. One screen per section (opened with extra "section").
 */
class PanelActivity : AppCompatActivity() {
    private val app by lazy { NexusApp.get(this) }
    private lateinit var box: LinearLayout
    private var section = "panel"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        section = intent.getStringExtra("section") ?: "panel"
        box = vbox(16).apply { setBackgroundColor(Ui.BG) }
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; setBackgroundColor(Ui.BG); addView(box) })
        if (section == "voice") {
            lifecycleScope.launch { app.voicePacks.state.collect { refresh() } }
            lifecycleScope.launch { app.voiceStatus.collect { refresh() } }
        }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        box.removeAllViews()
        when (section) {
            "tasks" -> tasks(); "skills" -> skills(); "modes" -> modes(); "notifications" -> notifications(); "voice" -> voice()
            "bubble" -> bubble(); "study" -> study(); "diagnostics" -> diagnostics(); "permissions" -> permissions(); "privacy" -> privacy()
            "general" -> general(); "home" -> { section = "panel"; panel() }
            else -> panel()
        }
    }

    // ------------------------------------------------------------ building blocks
    private val now get() = System.currentTimeMillis()
    private val s get() = app.memory.settings
    private fun update(f: (UserSettings) -> UserSettings) { app.memory.updateSettings(f); app.applySettings() }
    private fun title(t: String, sub: String? = null) { box.addView(label(t, 22f, bold = true)); sub?.let { box.addView(label(it, 13f, Ui.MUTED)) } }
    private fun header(t: String) = box.addView(label(t, 13f, Ui.ACCENT, bold = true), Ui.matchWrap().apply { topMargin = dp(18) })
    private fun note(t: String) = box.addView(label(t, 12.5f, Ui.MUTED), Ui.matchWrap().apply { topMargin = dp(4) })

    private fun card(build: LinearLayout.() -> Unit) {
        val c = vbox(12).apply { background = Ui.rounded(Ui.CARD, dp(12).toFloat()); build() }
        box.addView(c, Ui.matchWrap().apply { topMargin = dp(8) })
    }

    private fun LinearLayout.actions(vararg a: Pair<String, () -> Unit>) {
        val row = LinearLayout(this@PanelActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        a.forEach { (t, f) -> row.addView(button(t, Ui.BG) { f() }.apply { textSize = 12.5f }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) }) }
        addView(android.widget.HorizontalScrollView(this@PanelActivity).apply { isHorizontalScrollBarEnabled = false; addView(row) }, Ui.matchWrap().apply { topMargin = dp(6) })
    }

    private fun toggle(t: String, v: Boolean, f: (Boolean) -> Unit) = box.addView(Switch(this).apply {
        text = t; isChecked = v; setTextColor(Ui.TEXT); textSize = 14.5f; setPadding(0, dp(6), 0, dp(6)); setOnCheckedChangeListener { _, x -> f(x) }
    }, Ui.matchWrap())

    private fun field(hint: String, value: String?, multi: Boolean = false, onSave: (String) -> Unit) {
        val e = EditText(this).apply {
            this.hint = hint; setText(value ?: ""); setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED); textSize = 14f
            if (multi) { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 2 }
            setOnFocusChangeListener { _, has -> if (!has) onSave(text.toString()) }
        }
        box.addView(label(hint, 12f, Ui.MUTED), Ui.matchWrap().apply { topMargin = dp(6) })
        box.addView(e, Ui.matchWrap())
    }

    private fun slider(t: String, min: Int, max: Int, value: Int, fmt: (Int) -> String, f: (Int) -> Unit) {
        val l = label("$t: ${fmt(value)}", 14f)
        box.addView(l, Ui.matchWrap().apply { topMargin = dp(8) })
        box.addView(SeekBar(this).apply {
            this.max = max - min; progress = value - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) { l.text = "$t: ${fmt(p + min)}" }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { f((sb?.progress ?: 0) + min) }
            })
        }, Ui.matchWrap())
    }

    private fun spinner(t: String, options: List<Pair<String, String>>, current: String, f: (String) -> Unit) {
        box.addView(label(t, 12f, Ui.MUTED), Ui.matchWrap().apply { topMargin = dp(8) })
        box.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@PanelActivity, android.R.layout.simple_spinner_dropdown_item, options.map { it.second })
            setSelection(options.indexOfFirst { it.first == current }.coerceAtLeast(0))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) { if (options[pos].first != current) f(options[pos].first) }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        }, Ui.matchWrap())
    }

    private fun dialog(t: String, fields: List<Pair<String, String>>, onOk: (List<String>) -> Unit) {
        val form = vbox(8)
        val edits = fields.map { (hint, v) -> EditText(this).apply { this.hint = hint; setText(v) }.also { form.addView(it) } }
        AlertDialog.Builder(this).setTitle(t).setView(form).setPositiveButton("Save") { _, _ -> onOk(edits.map { it.text.toString().trim() }) }.setNegativeButton("Cancel", null).show()
    }

    private fun show(t: String, body: String) = AlertDialog.Builder(this).setTitle(t).setMessage(body)
        .setPositiveButton("Copy") { _, _ -> copy(body) }.setNegativeButton("Close", null).show()

    private fun copy(text: String) {
        getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("NEXUS", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun open(sec: String) =
        if (sec == "skills") startActivity(Intent(this, TeachActivity::class.java))
        else startActivity(Intent(this, PanelActivity::class.java).putExtra("section", sec))
    private fun fmtTime(ms: Long) = SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH).format(Date(ms))

    // ------------------------------------------------------------ sections

    private fun panel() {
        title("Control panel", "Everything NEXUS does, in one place.")
        listOf(
            "tasks" to "☑  Tasks & deadlines", "skills" to "🎓  Teach NEXUS & what it learned", "modes" to "◐  Modes & routines",
            "notifications" to "✉  Notification rules", "voice" to "♪  Voice", "bubble" to "●  Bubble", "study" to "📚  Study & habits",
            "diagnostics" to "⚙  Diagnostics", "permissions" to "✔  Permissions", "privacy" to "🔒  Data & privacy", "general" to "☰  General",
        ).forEach { (k, t) -> box.addView(button(t, Ui.CARD) { open(k) }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }, Ui.matchWrap().apply { topMargin = dp(6) }) }
        box.addView(button("🧠  Memory", Ui.CARD) { startActivity(Intent(this, MemoryActivity::class.java)) }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }, Ui.matchWrap().apply { topMargin = dp(6) })
        box.addView(button("⟳  Routines (shortcuts)", Ui.CARD) { startActivity(Intent(this, RoutinesActivity::class.java)) }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }, Ui.matchWrap().apply { topMargin = dp(6) })
        box.addView(button("⚙  Settings — brain, voice, listening", Ui.CARD) { startActivity(Intent(this, SettingsActivity::class.java)) }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }, Ui.matchWrap().apply { topMargin = dp(6) })
    }

    private fun tasks() {
        title("Tasks & deadlines", "From your voice, notes and messages. Reminders fire before each deadline.")
        box.addView(button("＋ Add task") {
            dialog("New task", listOf("Title" to "", "Due (e.g. Friday 6 pm, tomorrow 9 am)" to "")) { (t, due) ->
                if (t.isNotBlank()) { app.ask("add task: $t" + (if (due.isNotBlank()) " by $due" else "")) ; window.decorView.postDelayed({ refresh() }, 400) }
            }
        }, Ui.matchWrap().apply { topMargin = dp(8) })
        val open = Briefs.rank(app.life.store.openTasks(), app.life.data.activeMode, now)
        if (open.isEmpty()) note("No open tasks.")
        for (t in open) card {
            addView(label(t.title, 15f, bold = true))
            addView(label(listOfNotNull(t.dueMs?.let { "Due " + Briefs.whenText(it, now) }, "Priority ${t.priority}", t.assignedBy?.let { "from $it" }, t.source).joinToString(" · "), 12f, Ui.MUTED))
            actions(
                "Done" to { app.life.store.updateTask(t.id) { it.copy(done = true, doneMs = now) }; refresh() },
                "Edit" to { editTask(t) },
                *(if (t.draft != null) arrayOf<Pair<String, () -> Unit>>("Draft" to { show(t.title, t.draft!!) }) else emptyArray()),
                *(if (t.details.isNotBlank()) arrayOf<Pair<String, () -> Unit>>("Source" to { show("Original message", t.details) }) else emptyArray()),
                "Delete" to { app.life.store.deleteTask(t.id); refresh() },
            )
        }
        val done = app.life.data.tasks.filter { it.done }
        if (done.isNotEmpty()) { header("COMPLETED (${done.size})"); done.takeLast(10).reversed().forEach { note("✓ ${it.title}") } }
    }

    private fun editTask(t: TaskItem) = dialog("Edit task", listOf("Title" to t.title, "Due (e.g. tomorrow 5 pm, blank = none)" to "", "Priority 1-3" to t.priority.toString())) { (title, due, pr) ->
        val d = if (due.isBlank()) t.dueMs else NotificationIntel.extractDue("by $due", now)
        app.life.store.updateTask(t.id) { it.copy(title = title.ifBlank { t.title }, dueMs = d, priority = pr.toIntOrNull()?.coerceIn(1, 3) ?: t.priority, firedReminders = emptyList()) }
        app.life.data.tasks.firstOrNull { it.id == t.id }?.let { ReminderScheduler.scheduleTask(this, it, app.life.data.rules.reminderOffsetsMin) }
        refresh()
    }

    private fun skills() {
        title("Skills", "Things you taught NEXUS. It checks these first. Say \"teach me\" any time, or tap Re-teach.")
        card { addView(label("Weekly self-review", 15f, bold = true)); addView(label(Briefs.weekly(app.life.data, now), 13f, Ui.MUTED))
            val fails = app.life.data.diagnostics.filter { it.ms > now - 7 * 86_400_000L && it.result != "Success" }.groupBy { it.request }.entries.sortedByDescending { it.value.size }.take(5)
            fails.forEach { (req, list) -> addView(label("✗ \"$req\" failed ${list.size}×: ${list.last().detail.take(70)}", 12.5f)); actions("Re-teach" to { app.nexus.teach(req); finish() }) }
        }
        val list = app.life.data.skills
        if (list.isEmpty()) note("No skills yet. When NEXUS gets stuck it will ask you to teach it.")
        for (sk in list.sortedByDescending { it.lastUsedMs ?: it.createdMs }) card {
            addView(label(sk.name + (if (!sk.enabled) "  (off)" else "") + (if (sk.weak) "  ⚠ weak" else ""), 15f, bold = true))
            addView(label("Say: " + sk.triggers.joinToString("  |  "), 12.5f))
            addView(label("${sk.steps.size} steps · ${sk.successRate}% success (${sk.successes}✓ ${sk.failures}✗) · v${sk.version}" + (sk.app?.let { " · $it" } ?: ""), 12f, Ui.MUTED))
            actions(
                "Run" to { app.ask(sk.triggers.first().replace(Regex("\\{\\w+}"), "")); finish() },
                "Re-teach" to { app.nexus.teach(sk.triggers.first()); finish() },
                "Triggers" to { dialog("Trigger phrases (comma separated, {name} = slot)", listOf("Triggers" to sk.triggers.joinToString(", "))) { (t) -> app.life.store.updateSkill(sk.id) { it.copy(triggers = t.split(",").map { x -> x.trim() }.filter { x -> x.isNotBlank() }) }; refresh() } },
                (if (sk.enabled) "Disable" else "Enable") to { app.life.store.updateSkill(sk.id) { it.copy(enabled = !it.enabled) }; refresh() },
                *(if (sk.history.isNotEmpty()) arrayOf<Pair<String, () -> Unit>>("Roll back" to { app.life.store.updateSkill(sk.id) { it.copy(steps = it.history.last(), history = it.history.dropLast(1), version = it.version - 1, failures = 0) }; refresh() }) else emptyArray()),
                "Steps" to { show(sk.name, sk.steps.mapIndexed { i, st -> "${i + 1}. ${st.action} ${st.selector?.let { s2 -> s2.text ?: s2.desc ?: s2.viewId ?: "" } ?: st.text ?: st.direction ?: ""}" }.joinToString("\n")) },
                "Delete" to { app.life.store.deleteSkill(sk.id); refresh() },
            )
        }
    }

    private fun modes() {
        title("Modes", "Say \"Study mode\", tap Mode on the bubble, or set a schedule. Actions use simple lines like \"toggle do_not_disturb on\", \"open_app Spotify\", \"timer 3000 | Focus\".")
        val active = app.life.data.activeMode
        for (m in app.life.data.modes) card {
            addView(label(m.name + if (m.name == active) "  ● on" else "", 16f, bold = true))
            addView(label(listOfNotNull(
                m.focusMinutes?.let { "Focus ${it}m" + (m.breakMinutes?.let { b -> "/${b}m" } ?: "") },
                if (m.blockedApps.isNotEmpty()) "Blocks ${m.blockedApps.size} apps" else null,
                m.scheduleTime?.let { "Auto at $it" },
            ).joinToString(" · ").ifBlank { "No extras" }, 12f, Ui.MUTED))
            actions(
                (if (m.name == active) "Stop" else "Start") to { app.ask(if (m.name == active) "stop ${m.name} mode" else "${m.name} mode"); window.decorView.postDelayed({ refresh() }, 700) },
                "Edit" to { editMode(m) },
                *(if (!m.builtIn) arrayOf<Pair<String, () -> Unit>>("Delete" to { app.life.store.mutate { d -> d.copy(modes = d.modes.filterNot { it.id == m.id }) }; refresh() }) else emptyArray()),
            )
        }
        box.addView(button("＋ Create a mode") { editMode(Mode(name = "")) }, Ui.matchWrap().apply { topMargin = dp(10) })
    }

    private fun editMode(m: Mode) = dialog(if (m.name.isBlank()) "New mode" else "Edit ${m.name}", listOf(
        "Name" to m.name,
        "On start (one action per line → use ';')" to m.onStart.joinToString("; "),
        "On stop (';' separated)" to m.onStop.joinToString("; "),
        "Blocked apps (package names, comma separated)" to m.blockedApps.joinToString(", "),
        "Allowed contacts during mode (comma separated)" to m.allowedContacts.joinToString(", "),
        "Focus minutes (blank = none)" to (m.focusMinutes?.toString() ?: ""),
        "Daily start time HH:MM (blank = manual)" to (m.scheduleTime ?: ""),
    )) { v ->
        if (v[0].isBlank()) return@dialog
        val nm = m.copy(name = v[0], onStart = v[1].split(";").map { it.trim() }.filter { it.isNotBlank() }, onStop = v[2].split(";").map { it.trim() }.filter { it.isNotBlank() },
            blockedApps = v[3].split(",").map { it.trim() }.filter { it.isNotBlank() }, allowedContacts = v[4].split(",").map { it.trim() }.filter { it.isNotBlank() },
            focusMinutes = v[5].toIntOrNull(), scheduleTime = v[6].ifBlank { null }, logAs = m.logAs ?: v[0].lowercase())
        app.life.store.mutate { d -> d.copy(modes = d.modes.filterNot { it.id == m.id } + nm) }
        ReminderScheduler.scheduleModes(this)
        refresh()
    }

    private fun notifications() {
        title("Notification rules", "NEXUS reads only the apps you choose. OTPs, bank and payment messages are always ignored and never stored.")
        val r = app.life.data.rules
        toggle("Watch notifications", s.notificationWatching && r.enabled) { v -> update { it.copy(notificationWatching = v) }; app.life.store.mutate { d -> d.copy(rules = d.rules.copy(enabled = v)) } }
        toggle("Tell me about important ones out loud (never in calls, sleep or quiet hours)", r.announce) { v -> app.life.store.mutate { d -> d.copy(rules = d.rules.copy(announce = v)) } }
        header("WATCHED APPS")
        val known = listOf("com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp Business", "com.google.android.gm" to "Gmail", "org.telegram.messenger" to "Telegram",
            "com.google.android.calendar" to "Calendar", "com.instagram.android" to "Instagram", "com.google.android.apps.messaging" to "Messages", "com.microsoft.teams" to "Teams", "com.slack" to "Slack")
        known.forEach { (pkg, name) -> toggle(name, pkg in r.watchedApps) { v -> app.life.store.mutate { d -> d.copy(rules = d.rules.copy(watchedApps = if (v) (d.rules.watchedApps + pkg).distinct() else d.rules.watchedApps - pkg)) } } }
        field("Keywords that always matter (comma separated)", r.keywords.joinToString(", ")) { t -> app.life.store.mutate { d -> d.copy(rules = d.rules.copy(keywords = t.split(",").map { it.trim() }.filter { it.isNotBlank() })) } }
        field("Ignore these senders / groups (comma separated)", r.ignoreSenders.joinToString(", ")) { t -> app.life.store.mutate { d -> d.copy(rules = d.rules.copy(ignoreSenders = t.split(",").map { it.trim() }.filter { it.isNotBlank() })) } }
        field("Reminder times before a deadline, in minutes (comma separated)", r.reminderOffsetsMin.joinToString(", ")) { t -> app.life.store.mutate { d -> d.copy(rules = d.rules.copy(reminderOffsetsMin = t.split(",").mapNotNull { it.trim().toIntOrNull() }.ifEmpty { listOf(1440, 180, 60, 15) })) } }
        header("TRUST PER SENDER")
        note("Observe = only read · Suggest/Ask = prepare a reply and wait for your OK · Auto = send the pre-approved reply. New senders are always Ask.")
        r.senders.forEach { sr -> card { addView(label("${sr.name} — ${sr.trust}" + (sr.autoReply?.let { " · \"$it\"" } ?: ""), 14f)); actions("Edit" to { editSender(sr) }, "Remove" to { app.life.store.mutate { d -> d.copy(rules = d.rules.copy(senders = d.rules.senders.filterNot { it.name == sr.name })) }; refresh() }) } }
        box.addView(button("＋ Add sender rule", Ui.CARD) { editSender(SenderRule("")) }, Ui.matchWrap().apply { topMargin = dp(6) })
        header("RECENT INSIGHTS — tap to correct")
        app.life.data.notifications.takeLast(25).reversed().forEach { n -> card {
            addView(label("${n.app} · ${n.title}", 13f, bold = true))
            addView(label("${n.type}${if (n.actionable) " · actionable" else ""} · ${n.summary.take(90)}", 12.5f))
            n.replyState?.let { addView(label("Reply: ${n.draftReply ?: ""} ($it)", 12f, Ui.MUTED)) }
            actions(
                "Not a task" to { app.life.store.mutate { d -> d.copy(notifications = d.notifications.map { if (it.id == n.id) it.copy(actionable = false, type = "info", userCorrected = true) else it }, tasks = d.tasks.filterNot { it.id == n.taskId }) }; refresh() },
                "Make task" to { app.life.store.addTask(TaskItem(title = n.summary.ifBlank { n.text.take(80) }, details = n.text, source = "notification:${n.app}")); app.life.store.mutate { d -> d.copy(notifications = d.notifications.map { if (it.id == n.id) it.copy(actionable = true, type = "task", userCorrected = true) else it }) }; refresh() },
                "Ignore sender" to { app.life.store.mutate { d -> d.copy(rules = d.rules.copy(ignoreSenders = (d.rules.ignoreSenders + n.title.substringBefore(":")).distinct())) }; refresh() },
            )
        } }
    }

    private fun editSender(sr: SenderRule) = dialog("Sender rule", listOf("Sender or group name" to sr.name, "Trust: observe / suggest / ask / auto" to sr.trust, "Auto reply text (for auto)" to (sr.autoReply ?: ""))) { (n, t, a) ->
        if (n.isBlank()) return@dialog
        val trust = t.lowercase().takeIf { it in setOf("observe", "suggest", "ask", "auto") } ?: "ask"
        app.life.store.mutate { d -> d.copy(rules = d.rules.copy(senders = d.rules.senders.filterNot { it.name == sr.name } + SenderRule(n, trust, a.ifBlank { null }))) }
        refresh()
    }

    private fun voice() {
        title("Voice", "All voices run offline on your phone. Download a pack once, then tap any voice in it to use it.")
        app.voiceStatus.value.takeIf { it.isNotBlank() }?.let { note(it) }
        app.voice.bluetoothHint()?.let { note("🎧 $it") }
        card {
            addView(label("Can you hear me?", 15.5f, bold = true))
            addView(label("Plays a sentence, then tells you what worked and what didn't.", 12f, Ui.MUTED))
            actions("▶ Test voice" to {
                // Wait until the voice has finished loading, then speak, then report.
                app.loadVoice { _ ->
                    runOnUiThread {
                        app.voice.speak("Good evening, ${s.addressAs}. If you can hear this, my voice is working.")
                        window.decorView.postDelayed({ android.app.AlertDialog.Builder(this@PanelActivity).setTitle("Voice check").setMessage(app.voiceStatus.value + "\n\n" + app.voice.diagnose()).setPositiveButton("OK", null).show() }, 6000)
                    }
                }
            })
        }
        spinner("Voice engine", listOf("neural" to "Neural voices (best)", "system" to "Phone's own voice (no download)"), s.voiceEngine) { v -> update { it.copy(voiceEngine = v) }; app.loadVoice(); refresh() }
        val st = app.voicePacks.state.value
        if (st is VoicePackManager.State.Downloading) note("Downloading… ${st.percent}%")
        if (st is VoicePackManager.State.Unpacking) note("Unpacking…")
        if (st is VoicePackManager.State.Error) note("⚠ ${st.message}")
        app.cinematic.lastError?.let { note("Voice engine: $it — using the phone's voice instead.") }
        fun use(id: String, test: Boolean) {
            update { it.copy(voicePack = id, voiceEngine = "neural") }
            app.loadVoice(); refresh()
            if (test) window.decorView.postDelayed({ app.voice.speak("Good evening, ${s.addressAs}. All systems are ready, and I'm at your service.") }, 700)
        }
        for ((folder, voices) in VoicePackManager.groups()) card {
            val first = voices.first()
            val inst = app.voicePacks.installed(first.id)
            val packName = if (folder.startsWith("kokoro")) "Studio pack — ${voices.size} voices" else if (voices.size > 1) "Semaine pack — ${voices.size} voices" else first.title
            addView(label(packName, 15.5f, bold = true))
            addView(label(first.size + if (inst) " · downloaded" else "", 12f, Ui.MUTED))
            if (!inst) actions("Download" to { app.voicePacks.download(first.id, s.wifiOnlyDownloads) { runOnUiThread { use(first.id, true) } } })
            if (inst || voices.size > 1) for (v in voices) {
                val selected = s.voicePack == v.id
                val row = LinearLayout(this@PanelActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
                row.addView(label((if (selected) "● " else "○ ") + v.title, 14f, if (selected) Ui.OK else Ui.TEXT), LinearLayout.LayoutParams(0, -2, 1f))
                if (inst) row.addView(button("▶ Use", if (selected) Ui.OK else Ui.BG) { use(v.id, true) }.apply { textSize = 12f })
                addView(row, Ui.matchWrap())
            }
            if (inst) actions("Delete pack" to { app.voicePacks.delete(first.id); refresh() })
        }
        toggle("Download voice packs on Wi-Fi only", s.wifiOnlyDownloads) { v -> update { it.copy(wifiOnlyDownloads = v) } }
        slider("Speed", 60, 160, (s.speechRate * 100).toInt(), { "${it}%" }) { v -> update { it.copy(speechRate = v / 100f) } }
        slider("Pitch", 70, 130, (s.voicePitch * 100).toInt(), { "${it}%" }) { v -> update { it.copy(voicePitch = v / 100f) } }
        toggle("\"AI presence\" effect (subtle reverb + warmth)", s.voiceEffects) { v -> update { it.copy(voiceEffects = v) } }
        toggle("Chime when listening starts", s.earcons) { v -> update { it.copy(earcons = v) } }
        toggle("Speak replies", s.speakResponses) { v -> update { it.copy(speakResponses = v) } }
        toggle("Say \"On it\" for longer tasks", s.spokenAcks) { v -> update { it.copy(spokenAcks = v) } }
    }

    private fun bubble() {
        title("Bubble", "Docked half-hidden at the edge. Tap = talk · Double-tap = card · Long-press = menu · Drag = move / drop at bottom to close.")
        spinner("Side", listOf("right" to "Right edge", "left" to "Left edge"), s.bubbleSide) { v -> update { it.copy(bubbleSide = v) }; restartBubble() }
        slider("Size", 40, 72, s.bubbleSize, { "${it}dp" }) { v -> update { it.copy(bubbleSize = v) }; restartBubble() }
        slider("Opacity when idle", 30, 100, (s.bubbleOpacity * 100).toInt(), { "${it}%" }) { v -> update { it.copy(bubbleOpacity = v / 100f) }; restartBubble() }
        toggle("Hide during full-screen video and games", s.bubbleHideFullscreen) { v -> update { it.copy(bubbleHideFullscreen = v) } }
        box.addView(button(if (OverlayService.running) "Hide bubble" else "Show bubble") { if (OverlayService.running) OverlayService.stop(this) else OverlayService.start(this); window.decorView.postDelayed({ refresh() }, 400) }, Ui.matchWrap().apply { topMargin = dp(12) })
    }

    private fun restartBubble() { if (OverlayService.running) { OverlayService.stop(this); window.decorView.postDelayed({ OverlayService.start(this) }, 300) } }

    private fun study() {
        title("Study & habits", "Hours are logged from Study mode (\"study mode for taxation\").")
        val d = app.life.data
        header("STREAKS")
        listOf("study", "gym", "writing").forEach { h -> note("${h.replaceFirstChar { it.uppercase() }}: ${Briefs.streak(d.habitDays[h] ?: emptyList(), now)} days") }
        note("This week: ${Briefs.minutesOn(d.sessions, "study", now - 7 * 86_400_000L, now) / 60}h studied")
        header("CA INTER SUBJECTS")
        d.subjects.forEach { sj -> card {
            val cards = d.flashcards.filter { it.subject == sj.name }
            addView(label(sj.name, 14.5f, bold = true))
            addView(label("${sj.minutes / 60}h ${sj.minutes % 60}m · ${cards.size} flashcards (${cards.count { it.dueMs <= now }} due)" + if (sj.chapters.isNotEmpty()) " · ${sj.chapters.size} chapters" else "", 12f, Ui.MUTED))
            actions(
                "Add flashcard" to { dialog("Flashcard — ${sj.name}", listOf("Question" to "", "Answer" to "")) { (q, a) -> if (q.isNotBlank() && a.isNotBlank()) app.life.store.mutate { x -> x.copy(flashcards = x.flashcards + ai.nexus.core.life.Flashcard(subject = sj.name, question = q, answer = a)) }; refresh() } },
                "Chapters" to { dialog("Chapters (one per line → use ';')", listOf("Chapters" to sj.chapters.joinToString("; "))) { (c) -> app.life.store.mutate { x -> x.copy(subjects = x.subjects.map { if (it.name == sj.name) it.copy(chapters = c.split(";").map { y -> y.trim() }.filter { y -> y.isNotBlank() }) else it }) }; refresh() } },
                "Quiz me" to { app.ask("quiz me on ${sj.name}"); finish() },
            )
        } }
        header("INBOX (voice notes)")
        d.inbox.groupBy { it.category }.forEach { (c, items) -> note("$c (${items.size}): " + items.takeLast(3).joinToString(" · ") { it.text.take(40) }) }
        header("EXPENSES (30 days)")
        val e = d.expenses.filter { it.ms > now - 30 * 86_400_000L }
        note(if (e.isEmpty()) "Nothing logged. Say \"spent 200 on lunch\"." else "₹${e.sumOf { it.amount }.toLong()} total · " + e.groupBy { it.category }.entries.joinToString(" · ") { "${it.key} ₹${it.value.sumOf { x -> x.amount }.toLong()}" })
    }

    private fun diagnostics() {
        title("Diagnostics", "Every action NEXUS took, how long it took, and why anything failed.")
        val all = app.life.data.diagnostics
        val fails = all.count { it.result != "Success" }
        note("${all.size} actions logged · $fails not successful · avg ${if (all.isEmpty()) 0 else all.map { it.durationMs }.average().toLong()} ms per step")
        box.addView(button("Copy failure report", Ui.CARD) {
            copy(all.filter { it.result != "Success" }.takeLast(30).joinToString("\n\n") { "${fmtTime(it.ms)} \"${it.request}\" → ${it.step}: ${it.result} ${it.detail}\nScreen: ${it.screen}" })
        }, Ui.matchWrap().apply { topMargin = dp(6) })
        val crash = java.io.File(filesDir, "crash.txt")
        if (crash.exists()) box.addView(button("View crash log", Ui.CARD) { show("Crash log", crash.readText().takeLast(6000)) }, Ui.matchWrap().apply { topMargin = dp(6) })
        all.takeLast(80).reversed().forEach { e -> card {
            addView(label("${if (e.result == "Success") "✓" else "✗"} ${e.step}", 13.5f, bold = true))
            addView(label("${fmtTime(e.ms)} · ${e.durationMs} ms · ${e.app.substringAfterLast('.')}\n\"${e.request}\"${if (e.detail.isNotBlank()) "\n${e.detail}" else ""}", 12f, Ui.MUTED))
            if (e.result != "Success") actions("Re-teach" to { app.nexus.teach(e.request); finish() }, "Screen" to { show("What NEXUS saw", e.screen) })
        } }
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun permissions() {
        title("Permissions", "Green = ready. Tap Fix to open the right Android setting.")
        val pm = getSystemService(PowerManager::class.java)
        val nm = getSystemService(NotificationManager::class.java)
        val alarms = if (Build.VERSION.SDK_INT >= 31) getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true else true
        val acc = NexusAccessibilityService.instance != null || (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains(ComponentName(this, NexusAccessibilityService::class.java).flattenToString())
        val items = listOf<Pair<Triple<String, String, Boolean>, () -> Unit>>(
            Triple("Phone control (Accessibility)", "To see and operate the screen.", acc) to { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            Triple("Microphone", "To hear you.", granted(Manifest.permission.RECORD_AUDIO)) to { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) },
            Triple("Display over other apps", "For the bubble.", Settings.canDrawOverlays(this)) to { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) },
            Triple("Notification access", "To understand messages and control media.", (Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "").contains(packageName)) to { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            Triple("Show notifications", "For reminders and briefs.", Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)) to { if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2) },
            Triple("Exact alarms", "So reminders fire on time.", alarms) to { if (Build.VERSION.SDK_INT >= 31) startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) },
            Triple("Unrestricted battery", "So Android doesn't stop NEXUS in the background.", pm?.isIgnoringBatteryOptimizations(packageName) == true) to {
                runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }.onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            },
            Triple("Contacts, calls & SMS", "To call and message by name.", granted(Manifest.permission.READ_CONTACTS) && granted(Manifest.permission.CALL_PHONE)) to { requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS, Manifest.permission.ANSWER_PHONE_CALLS), 3) },
            Triple("Do Not Disturb access", "For Study/Sleep modes and silent mode.", nm?.isNotificationPolicyAccessGranted == true) to { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
            Triple("Modify system settings", "For brightness and auto-rotate.", Settings.System.canWrite(this)) to { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))) },
            Triple("Photos & media", "To find your files.", if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_IMAGES) else granted(Manifest.permission.READ_EXTERNAL_STORAGE)) to {
                requestPermissions(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 4)
            },
        )
        val ok = items.count { it.first.third }
        note("$ok of ${items.size} ready.")
        for ((info, fix) in items) card {
            addView(label((if (info.third) "✅ " else "❌ ") + info.first, 14.5f, bold = true))
            addView(label(info.second, 12f, Ui.MUTED))
            if (!info.third) actions("Fix" to fix)
        }
        note("If \"Phone control\" is greyed out: Settings → Apps → NEXUS → ⋮ → Allow restricted settings.")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults); refresh()
    }

    private fun privacy() {
        title("Data & privacy", "Everything stays on this phone, encrypted with a key in the Android Keystore.")
        header("WHAT IS STORED")
        note("• Memory: facts, people, preferences, aliases you taught (until you delete them)\n• Tasks, modes, skills, study log, expenses\n• Notifications: only from watched apps; raw text kept 7 days, then only the summary\n• Diagnostics: the last 600 actions\n• Never stored: passwords, OTPs, card/bank numbers, ID numbers, payment messages")
        header("BACKUP")
        box.addView(button("Export everything (share as text)", Ui.CARD) {
            val all = "{\"memory\":${app.memory.exportJson()},\"life\":${app.life.store.export()}}"
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, all), "Export NEXUS data"))
        }, Ui.matchWrap().apply { topMargin = dp(6) })
        box.addView(button("Import (paste exported text)", Ui.CARD) {
            dialog("Import", listOf("Paste the exported text" to "")) { (t) ->
                runCatching {
                    val o = org.json.JSONObject(t)
                    o.optJSONObject("memory")?.let { app.memory.importJson(it.toString()) }
                    o.optJSONObject("life")?.let { app.life.store.import(it.toString()) }
                    Toast.makeText(this, "Imported", Toast.LENGTH_SHORT).show()
                }.onFailure { Toast.makeText(this, "That doesn't look like a NEXUS export.", Toast.LENGTH_LONG).show() }
            }
        }, Ui.matchWrap().apply { topMargin = dp(6) })
        header("ERASE")
        listOf(
            "Clear conversation" to { app.clearChat() },
            "Erase memory" to { app.memory.clearAll() },
            "Erase tasks, skills, modes & logs" to { app.life.store.wipe() },
            "Erase everything" to { app.memory.clearAll(); app.life.store.wipe(); app.clearChat() },
        ).forEach { (t, f) -> box.addView(button(t, Ui.WARN) { AlertDialog.Builder(this).setMessage("$t? This can't be undone.").setPositiveButton("Erase") { _, _ -> f(); refresh() }.setNegativeButton("Cancel", null).show() }, Ui.matchWrap().apply { topMargin = dp(6) }) }
    }

    private fun general() {
        title("General")
        field("Your name", s.name) { v -> update { it.copy(name = v.ifBlank { null }) } }
        field("Call me", s.addressAs) { v -> update { it.copy(addressAs = v.ifBlank { "Boss" }) } }
        toggle("When stuck, stop and ask me to teach it", s.askWhenStuck) { v -> update { it.copy(askWhenStuck = v) } }
        spinner("Remember facts I mention in passing", listOf("ask" to "Ask me first", "always" to "Always save", "off" to "Don't"), s.autoCapture) { v -> update { it.copy(autoCapture = v) } }
        toggle("Morning brief & evening review", s.briefsEnabled) { v -> update { it.copy(briefsEnabled = v) }; ReminderScheduler.scheduleBriefs(this) }
        field("Morning brief time (HH:MM)", s.morningBrief) { v -> if (Regex("\\d{1,2}:\\d{2}").matches(v)) { update { it.copy(morningBrief = v) }; ReminderScheduler.scheduleBriefs(this) } }
        field("Evening review time (HH:MM)", s.eveningReview) { v -> if (Regex("\\d{1,2}:\\d{2}").matches(v)) { update { it.copy(eveningReview = v) }; ReminderScheduler.scheduleBriefs(this) } }
        field("Quiet hours start (HH:MM)", s.quietStart) { v -> if (Regex("\\d{1,2}:\\d{2}").matches(v)) update { it.copy(quietStart = v) } }
        field("Quiet hours end (HH:MM)", s.quietEnd) { v -> if (Regex("\\d{1,2}:\\d{2}").matches(v)) update { it.copy(quietEnd = v) } }
        toggle("Ask before sending any message", s.confirmBeforeSending) { v -> update { it.copy(confirmBeforeSending = v) } }
        toggle("Return to the app I was in after background tasks", s.returnAfterTasks) { v -> update { it.copy(returnAfterTasks = v) } }
    }
}
