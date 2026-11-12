package ai.nexus.app.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity
import ai.nexus.app.NexusApp
import ai.nexus.app.ui.Ui.button
import ai.nexus.app.ui.Ui.dp
import ai.nexus.app.ui.Ui.label
import ai.nexus.app.ui.Ui.vbox
import ai.nexus.core.memory.MemoryKind

/** Inspect, edit, delete or wipe everything NEXUS remembers — or turn memory off. */
class MemoryActivity : AppCompatActivity() {
    private val app by lazy { NexusApp.get(this) }
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = vbox(16).apply { setBackgroundColor(Ui.BG) }
        box.addView(label("Memory", 22f, bold = true))
        box.addView(label("What NEXUS has learned about you. It never stores passwords, OTPs or card numbers. Say \"remember that…\" or \"forget…\" any time.", 13f, Ui.MUTED))
        box.addView(Switch(this).apply {
            text = "Memory enabled"; isChecked = app.memory.enabled; setTextColor(Ui.TEXT)
            setOnCheckedChangeListener { _, v -> app.memory.setEnabled(v) }
        }, Ui.matchWrap().apply { topMargin = dp(10) })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("Add") { edit(null, "", MemoryKind.FACT) })
        actions.addView(LinearLayout(this), LinearLayout.LayoutParams(dp(8), 1))
        actions.addView(button("Clear conversation", Ui.CARD) { app.clearChat() })
        actions.addView(LinearLayout(this), LinearLayout.LayoutParams(dp(8), 1))
        actions.addView(button("Forget everything", Ui.WARN) {
            AlertDialog.Builder(this).setMessage("Erase all memories, aliases, shortcuts and history?")
                .setPositiveButton("Erase") { _, _ -> app.memory.clearAll(); app.clearChat(); refresh() }
                .setNegativeButton("Cancel", null).show()
        })
        box.addView(actions, Ui.matchWrap().apply { topMargin = dp(10) })
        list = vbox(0)
        box.addView(list, Ui.matchWrap().apply { topMargin = dp(12) })
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(box) })
        refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        val items = app.memory.items
        if (items.isEmpty()) list.addView(label("Nothing stored yet.", 14f, Ui.MUTED))
        for ((kind, group) in items.groupBy { it.kind }) {
            list.addView(label(kind.name.replace('_', ' '), 12f, Ui.ACCENT, bold = true), Ui.matchWrap().apply { topMargin = dp(12) })
            for (item in group) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = Ui.rounded(Ui.CARD, dp(10).toFloat()); setPadding(dp(12), dp(8), dp(6), dp(8))
                }
                row.addView(label((item.key?.let { "$it → " } ?: "") + item.value, 14.5f), LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(button("Edit", Ui.CARD) { edit(item.id, item.value, item.kind) })
                row.addView(button("✕", Ui.CARD) { app.memory.forget(item.id); refresh() })
                list.addView(row, Ui.matchWrap().apply { topMargin = dp(6) })
            }
        }
        val usage = app.memory.topApps(5)
        if (usage.isNotEmpty()) {
            list.addView(label("MOST USED APPS", 12f, Ui.ACCENT, bold = true), Ui.matchWrap().apply { topMargin = dp(16) })
            list.addView(label(usage.joinToString("\n"), 13f, Ui.MUTED))
        }
    }

    private fun edit(id: String?, value: String, kind: MemoryKind) {
        val f = EditText(this).apply { setText(value) }
        AlertDialog.Builder(this).setTitle(if (id == null) "Remember" else "Edit").setView(f)
            .setPositiveButton("Save") { _, _ ->
                val v = f.text.toString().trim()
                if (v.isNotEmpty()) { if (id == null) app.memory.remember(kind, null, v) else app.memory.update(id, v) }
                refresh()
            }.setNegativeButton("Cancel", null).show()
    }
}
