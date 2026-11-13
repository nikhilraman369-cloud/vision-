package ai.nexus.app.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ai.nexus.app.NexusApp
import ai.nexus.app.service.NexusAccessibilityService
import ai.nexus.app.service.OverlayService
import ai.nexus.app.ui.Ui.button
import ai.nexus.app.ui.Ui.dp
import ai.nexus.app.ui.Ui.label
import ai.nexus.app.ui.Ui.vbox
import ai.nexus.core.life.Skill
import ai.nexus.core.life.pretty
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything about teaching in one place:
 *  1. Teach a new task: name it, show it once.
 *  2. Library: every task NEXUS learned, with run / rename / phrases / re-teach / steps / delete.
 */
class TeachActivity : AppCompatActivity() {
    private val app by lazy { NexusApp.get(this) }
    private lateinit var box: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        box = vbox(18).apply { setBackgroundColor(Ui.BG) }
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; setBackgroundColor(Ui.BG); addView(box) })
        lifecycleScope.launch { app.teaching.collect { refresh() } }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }
    override fun onResume() { super.onResume(); refresh() }

    /** "Done" from the notification or the recording bar: save now if it has a name, else ask for one. */
    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra("finish", false) != true) return
        i.removeExtra("finish")
        if (!app.nexus.isTeaching) return
        if (app.nexus.teachingName != null) { app.nexus.finishTeaching(); Toast.makeText(this, "Saving…", Toast.LENGTH_SHORT).show(); return }
        val e = EditText(this).apply { hint = "e.g. send good morning to the editorial group"; setSingleLine() }
        AlertDialog.Builder(this).setTitle("Name this task").setMessage("Type what you'll say to make me do it.").setView(e)
            .setPositiveButton("Save") { _, _ -> val n = e.text.toString().trim(); if (n.isNotEmpty()) app.nexus.finishTeaching(n) else Toast.makeText(this, "Give it a name first.", Toast.LENGTH_SHORT).show() }
            .setNegativeButton("Keep recording", null)
            .setNeutralButton("Discard") { _, _ -> app.nexus.cancelTeaching() }
            .show()
    }

    private fun card(build: LinearLayout.() -> Unit) {
        val c = vbox(14).apply { background = Ui.rounded(Ui.CARD, dp(16).toFloat()); build() }
        box.addView(c, Ui.matchWrap().apply { topMargin = dp(12) })
    }

    private fun LinearLayout.actions(vararg a: Pair<String, () -> Unit>) {
        val row = LinearLayout(this@TeachActivity).apply { orientation = LinearLayout.HORIZONTAL }
        a.forEach { (t, f) -> row.addView(button(t, Ui.BG) { f() }.apply { textSize = 12.5f }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) }) }
        addView(HorizontalScrollView(this@TeachActivity).apply { isHorizontalScrollBarEnabled = false; addView(row) }, Ui.matchWrap().apply { topMargin = dp(8) })
    }

    private fun refresh() {
        box.removeAllViews()
        box.addView(label("Teach NEXUS", 26f, bold = true))
        box.addView(label("Show me a task once. I'll remember it and do it myself, even with different names or messages.", 13.5f, Ui.MUTED))

        // ---- recording in progress
        if (app.nexus.isTeaching) {
            card {
                addView(label("● Recording${app.nexus.teachingName?.let { ": “$it”" } ?: ""}", 17f, 0xFFFF6B6B.toInt(), bold = true))
                addView(label("${app.nexus.teachSteps.value} steps so far. Go and do the task on your phone. Use the Done button on the red bar (or in the notification) when finished.", 13f, Ui.MUTED))
                actions("Done ✓" to { finishNow() }, "Cancel" to { app.nexus.cancelTeaching(); refresh() })
            }
        } else {
            // ---- start a new one
            card {
                addView(label("＋ Teach a new task", 17f, bold = true))
                addView(label("Type what you'll say to make me do it. Example: “send good morning to Editorial Team”. Then do it yourself once.", 13f, Ui.MUTED))
                val name = EditText(this@TeachActivity).apply { hint = "What will you say?"; setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED); setSingleLine() }
                addView(name, Ui.matchWrap().apply { topMargin = dp(8) })
                addView(button("● Start recording") { startTeaching(name.text.toString().trim()) }, Ui.matchWrap().apply { topMargin = dp(8) })
            }
            card {
                addView(label("How it works", 15f, bold = true))
                addView(label("1. Name it and tap Start. NEXUS takes you home.\n2. Do the task yourself: open the app, tap, type. Passwords and OTPs are never recorded.\n3. Tap Done on the red bar. That's it.\n\nNext time just say the name. If you taught it with a name or message inside (like “send hi to Rahul”), you can swap those: “send I'm late to Priya”.", 12.5f, Ui.MUTED))
            }
            card {
                addView(label("What about screen recordings?", 15f, bold = true))
                addView(label("A video file can't be turned into reliable taps on the phone, because NEXUS needs to know exactly which buttons you pressed. Recording live here does that precisely, so use “Start recording” instead.", 12.5f, Ui.MUTED))
            }
        }

        // ---- library
        val skills = app.life.data.skills.sortedByDescending { it.lastUsedMs ?: it.createdMs }
        box.addView(label("WHAT NEXUS KNOWS HOW TO DO (${skills.size})", 13f, Ui.ACCENT, bold = true), Ui.matchWrap().apply { topMargin = dp(20) })
        if (skills.isEmpty()) box.addView(label("Nothing yet. Teach me something above, or I'll ask when I get stuck.", 13f, Ui.MUTED))
        skills.forEach { skillCard(it) }
    }

    private fun skillCard(sk: Skill) = card {
        addView(label(sk.name + if (!sk.enabled) "  (off)" else "", 16f, bold = true))
        addView(label("Say: " + sk.triggers.joinToString("  |  "), 12.5f))
        addView(label(sk.steps.joinToString("  →  ") { it.pretty() }.take(160), 12f, Ui.MUTED), Ui.matchWrap().apply { topMargin = dp(2) })
        val last = sk.lastUsedMs?.let { "used " + SimpleDateFormat("d MMM", Locale.ENGLISH).format(Date(it)) } ?: "not used yet"
        addView(label("${sk.steps.size} steps · ${sk.successRate}% worked · $last" + (if (sk.weak) " · ⚠ often fails, re-teach it" else ""), 11.5f, Ui.MUTED))
        actions(
            "▶ Run" to { app.ask(sk.triggers.first().replace(Regex("\\s*\\{\\w+}"), "").trim()); finish() },
            "Rename" to { prompt("Rename", sk.name) { n -> app.life.store.updateSkill(sk.id) { it.copy(name = n) }; refresh() } },
            "Phrases" to { prompt("Ways to say it (comma separated; {name} {message} are fill-ins)", sk.triggers.joinToString(", ")) { t ->
                val list = t.split(",").map { it.trim() }.filter { it.isNotBlank() }
                if (list.isNotEmpty()) app.life.store.updateSkill(sk.id) { it.copy(triggers = list) }; refresh() } },
            "Steps" to { AlertDialog.Builder(this@TeachActivity).setTitle(sk.name).setMessage(sk.steps.mapIndexed { i, st -> "${i + 1}. ${st.pretty()}" }.joinToString("\n")).setPositiveButton("OK", null).show() },
            "Re-teach" to { startTeaching(sk.triggers.first()) },
            (if (sk.enabled) "Turn off" else "Turn on") to { app.life.store.updateSkill(sk.id) { it.copy(enabled = !it.enabled) }; refresh() },
            *(if (sk.history.isNotEmpty()) arrayOf<Pair<String, () -> Unit>>("Undo last teach" to { app.life.store.updateSkill(sk.id) { it.copy(steps = it.history.last(), history = it.history.dropLast(1), version = it.version - 1, failures = 0) }; refresh() }) else emptyArray()),
            "Delete" to { AlertDialog.Builder(this@TeachActivity).setMessage("Delete “${sk.name}”?").setPositiveButton("Delete") { _, _ -> app.life.store.deleteSkill(sk.id); refresh() }.setNegativeButton("Cancel", null).show() },
        )
    }

    private fun prompt(title: String, initial: String, onOk: (String) -> Unit) {
        val e = EditText(this).apply { setText(initial); setTextColor(Ui.TEXT) }
        AlertDialog.Builder(this).setTitle(title).setView(e).setPositiveButton("Save") { _, _ -> e.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onOk) }.setNegativeButton("Cancel", null).show()
    }

    private fun finishNow() {
        if (app.nexus.teachingName != null) { app.nexus.finishTeaching(); Toast.makeText(this, "Saving…", Toast.LENGTH_SHORT).show() }
        else handleIntent(Intent().putExtra("finish", true))
    }

    private fun startTeaching(phrase: String) {
        if (phrase.isBlank()) { Toast.makeText(this, "Type what you'll say first.", Toast.LENGTH_SHORT).show(); return }
        // NEXUS records taps through "Phone control"; without it there is nothing to record.
        if (NexusAccessibilityService.instance == null) {
            Toast.makeText(this, "Turn on NEXUS under Accessibility first (Phone control).", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
        }
        if (Settings.canDrawOverlays(this)) OverlayService.start(this)   // the red Done bar lives in the bubble service
        else Toast.makeText(this, "Tip: allow “Display over other apps” for the on-screen Done bar. Use the notification's Done meanwhile.", Toast.LENGTH_LONG).show()
        app.nexus.teach(phrase)
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
