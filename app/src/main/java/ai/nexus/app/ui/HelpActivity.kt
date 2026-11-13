package ai.nexus.app.ui

import android.os.Bundle
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import ai.nexus.app.NexusApp
import ai.nexus.app.ui.Ui.button
import ai.nexus.app.ui.Ui.dp
import ai.nexus.app.ui.Ui.label
import ai.nexus.app.ui.Ui.vbox

/** "What can I say?" – tap any example to run it. */
class HelpActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = NexusApp.get(this)
        val box = vbox(16).apply { setBackgroundColor(Ui.BG) }
        box.addView(label("What can I say?", 22f, bold = true))
        box.addView(label("Speak naturally — English, Hindi or Hinglish. NEXUS looks at your screen first, so you can name anything you see. Tap an example to try it.", 13.5f, Ui.MUTED))
        for ((title, examples) in SECTIONS) {
            box.addView(label(title, 13f, Ui.ACCENT, bold = true), Ui.matchWrap().apply { topMargin = dp(16) })
            for (e in examples) box.addView(button(e, Ui.CARD) { app.ask(e); finish() }.apply { textSize = 13.5f; isAllCaps = false }, Ui.matchWrap().apply { topMargin = dp(4) })
        }
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; setBackgroundColor(Ui.BG); addView(box) })
    }

    companion object {
        val SECTIONS = ai.nexus.core.agent.CommandCatalog.sections
    }
}
