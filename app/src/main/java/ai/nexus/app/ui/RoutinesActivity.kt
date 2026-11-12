package ai.nexus.app.ui

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ai.nexus.app.NexusApp
import ai.nexus.app.ui.Ui.button
import ai.nexus.app.ui.Ui.dp
import ai.nexus.app.ui.Ui.label
import ai.nexus.app.ui.Ui.vbox
import ai.nexus.core.memory.MemoryKind
import ai.nexus.core.nlu.CommandDsl
import ai.nexus.core.nlu.TextNormalizer

/**
 * Your repeated tasks. Make one by typing the steps in your own words, or by
 * saying "watch me" and doing it once. Run it by saying its name or tapping Run.
 */
class RoutinesActivity : AppCompatActivity() {
    private val app by lazy { NexusApp.get(this) }
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = vbox(16).apply { setBackgroundColor(Ui.BG) }
        box.addView(label("Routines", 22f, bold = true))
        box.addView(label("Teach NEXUS your daily tasks once, then just say the name.\n\n• Type it: tap New routine and write each step on its own line.\n• Show it: say \"watch me\", do the task, then say \"done, call it <name>\".", 13.5f, Ui.MUTED))
        box.addView(button("＋ New routine") { create() }, Ui.matchWrap().apply { topMargin = dp(12) })
        box.addView(button("Teach by showing (watch me)", Ui.CARD) {
            app.ask("watch me"); Toast.makeText(this, "Do the task now. Then tap the bubble and say: done, call it …", Toast.LENGTH_LONG).show(); finish()
        }, Ui.matchWrap().apply { topMargin = dp(6) })
        list = vbox(0)
        box.addView(list, Ui.matchWrap().apply { topMargin = dp(12) })
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(box) })
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        list.removeAllViews()
        val items = app.memory.items.filter { it.kind == MemoryKind.ROUTINE || it.kind == MemoryKind.SHORTCUT }
        if (items.isEmpty()) list.addView(label("No routines yet. Example:\nName: good night\nSteps:\nturn off wifi\nbrightness 10\nset an alarm for 6:30 am", 14f, Ui.MUTED))
        for (item in items) {
            val steps = if (item.kind == MemoryKind.ROUTINE) item.value.lines().joinToString("\n") { "• $it" }
            else item.value.split(", then ").joinToString("\n") { "• $it" }
            val card = vbox(12).apply { background = Ui.rounded(Ui.CARD, dp(12).toFloat()) }
            card.addView(label(item.key ?: "routine", 16f, bold = true))
            card.addView(label(steps, 13f, Ui.MUTED))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            row.addView(button("▶ Run") { app.ask(item.key ?: ""); finish() })
            row.addView(LinearLayout(this), LinearLayout.LayoutParams(dp(8), 1))
            row.addView(button("Delete", Ui.WARN) { app.memory.forget(item.id); refresh() })
            card.addView(row, Ui.matchWrap().apply { topMargin = dp(6) })
            list.addView(card, Ui.matchWrap().apply { topMargin = dp(8) })
        }
    }

    private fun create() {
        val form = vbox(8)
        val name = EditText(this).apply { hint = "Name (what you'll say), e.g. good morning" }
        val steps = EditText(this).apply {
            hint = "Steps, one per line, in your own words:\nopen whatsapp\nopen the family group\nsend good morning"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 5; gravity = Gravity.TOP
        }
        form.addView(name); form.addView(steps)
        AlertDialog.Builder(this).setTitle("New routine").setView(form)
            .setPositiveButton("Save") { _, _ ->
                val n = name.text.toString().trim(); val lines = steps.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (n.isEmpty() || lines.isEmpty()) return@setPositiveButton
                // Steps NEXUS fully understands are stored as actions; anything else is kept as words.
                val parsed = lines.map { l -> app.nexus.parser.parse(l).steps.map { it.command } }
                val exact = parsed.all { cmds -> cmds.isNotEmpty() && cmds.all { CommandDsl.toLine(it) != null } }
                if (exact) app.memory.remember(MemoryKind.ROUTINE, n.lowercase(), CommandDsl.toLines(parsed.flatten()))
                else app.memory.remember(MemoryKind.SHORTCUT, TextNormalizer.normalize(n), lines.joinToString(", then "))
                refresh()
            }.setNegativeButton("Cancel", null).show()
    }
}
