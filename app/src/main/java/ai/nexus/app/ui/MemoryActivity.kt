package ai.nexus.app.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ai.nexus.app.NexusApp
import ai.nexus.app.ui.Ui.button
import ai.nexus.app.ui.Ui.dp
import ai.nexus.app.ui.Ui.label
import ai.nexus.app.ui.Ui.vbox
import ai.nexus.core.memory.MemoryItem
import ai.nexus.core.memory.MemoryKind
import ai.nexus.core.nlu.Fuzzy

/** Search, filter, add, edit, pin, merge, delete, export and import what NEXUS knows about you. */
class MemoryActivity : AppCompatActivity() {
    private val app by lazy { NexusApp.get(this) }
    private lateinit var list: LinearLayout
    private var query = ""
    private var filter: MemoryKind? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = vbox(16).apply { setBackgroundColor(Ui.BG) }
        box.addView(label("Memory", 22f, bold = true))
        box.addView(label("What NEXUS knows about you. Say \"remember that…\", \"forget…\" or \"what do you know about…\". Passwords, OTPs and card numbers are never stored.", 13f, Ui.MUTED))
        box.addView(Switch(this).apply { text = "Memory enabled"; isChecked = app.memory.enabled; setTextColor(Ui.TEXT); setOnCheckedChangeListener { _, v -> app.memory.setEnabled(v) } }, Ui.matchWrap().apply { topMargin = dp(8) })
        box.addView(EditText(this).apply {
            hint = "Search memory…"; setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(e: Editable?) { query = e.toString(); refresh() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }, Ui.matchWrap())
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        (listOf<MemoryKind?>(null) + MemoryKind.entries).forEach { k ->
            chips.addView(button(k?.name?.lowercase()?.replace('_', ' ') ?: "all", Ui.CARD) { filter = k; refresh() }.apply { textSize = 12f }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(4) })
        }
        box.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("Add") { edit(null) })
        actions.addView(LinearLayout(this), LinearLayout.LayoutParams(dp(6), 1))
        actions.addView(button("Merge duplicates", Ui.CARD) { Toast.makeText(this, "Merged ${app.memory.mergeDuplicates()}", Toast.LENGTH_SHORT).show(); refresh() })
        actions.addView(LinearLayout(this), LinearLayout.LayoutParams(dp(6), 1))
        actions.addView(button("Export", Ui.CARD) { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, app.memory.exportJson()), "Export memory")) })
        actions.addView(LinearLayout(this), LinearLayout.LayoutParams(dp(6), 1))
        actions.addView(button("Import", Ui.CARD) { importDialog() })
        actions.addView(LinearLayout(this), LinearLayout.LayoutParams(dp(6), 1))
        actions.addView(button("Forget all", Ui.WARN) {
            AlertDialog.Builder(this).setMessage("Erase all memories, aliases, shortcuts and history? Pinned items too.")
                .setPositiveButton("Erase") { _, _ -> app.memory.clearAll(); app.clearChat(); refresh() }.setNegativeButton("Cancel", null).show()
        })
        box.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(actions) }, Ui.matchWrap().apply { topMargin = dp(8) })
        list = vbox(0)
        box.addView(list, Ui.matchWrap().apply { topMargin = dp(10) })
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; setBackgroundColor(Ui.BG); addView(box) })
        refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        val used = app.memory.lastUsed
        if (used.isNotEmpty()) {
            list.addView(label("USED IN THE LAST ANSWER", 12f, Ui.ACCENT, bold = true))
            used.forEach { list.addView(label("• " + (it.key?.let { k -> "$k → " } ?: "") + it.value, 12.5f, Ui.MUTED)) }
        }
        val q = Fuzzy.normalize(query)
        val items = app.memory.items.filter { filter == null || it.kind == filter }
            .filter { q.isBlank() || Fuzzy.normalize("${it.key ?: ""} ${it.value}").contains(q) }
            .sortedWith(compareByDescending<MemoryItem> { it.pinned }.thenByDescending { it.updatedMs })
        if (items.isEmpty()) list.addView(label("Nothing here yet.", 14f, Ui.MUTED))
        for ((kind, group) in items.groupBy { it.kind }) {
            list.addView(label(kind.name.replace('_', ' '), 12f, Ui.ACCENT, bold = true), Ui.matchWrap().apply { topMargin = dp(12) })
            for (item in group) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = Ui.rounded(Ui.CARD, dp(10).toFloat()); setPadding(dp(12), dp(8), dp(6), dp(8))
                }
                row.addView(label((if (item.pinned) "📌 " else "") + (item.key?.let { "$it → " } ?: "") + item.value + "\n" + "${item.source} · importance ${item.importance}", 14f), LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(button(if (item.pinned) "Unpin" else "Pin", Ui.BG) { app.memory.setPinned(item.id, !item.pinned); refresh() }.apply { textSize = 12f })
                row.addView(button("Edit", Ui.BG) { edit(item) }.apply { textSize = 12f })
                row.addView(button("✕", Ui.BG) { app.memory.forget(item.id); refresh() }.apply { textSize = 12f })
                list.addView(row, Ui.matchWrap().apply { topMargin = dp(6) })
            }
        }
    }

    private fun edit(item: MemoryItem?) {
        val form = vbox(8)
        val key = EditText(this).apply { hint = "Topic (optional), e.g. mom, gym time"; setText(item?.key ?: "") }
        val value = EditText(this).apply { hint = "What to remember"; setText(item?.value ?: "") }
        form.addView(key); form.addView(value)
        AlertDialog.Builder(this).setTitle(if (item == null) "Remember" else "Edit").setView(form)
            .setPositiveButton("Save") { _, _ ->
                val v = value.text.toString().trim()
                if (v.isEmpty()) return@setPositiveButton
                if (item == null) app.memory.add(MemoryItem(kind = MemoryKind.FACT, key = key.text.toString().trim().ifBlank { null }, value = v, importance = 4))
                else app.memory.update(item.id, v)
                refresh()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun importDialog() {
        val e = EditText(this).apply { hint = "Paste exported memory JSON" }
        AlertDialog.Builder(this).setTitle("Import memory").setView(e).setPositiveButton("Import") { _, _ ->
            runCatching { app.memory.importJson(e.text.toString()); refresh() }.onFailure { Toast.makeText(this, "That isn't a NEXUS memory export.", Toast.LENGTH_LONG).show() }
        }.setNegativeButton("Cancel", null).show()
    }
}
