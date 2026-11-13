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
        val SECTIONS = listOf(
            "ON ANY SCREEN" to listOf("What's on my screen?", "Read this page", "Tap Settings", "Open the second result", "Scroll down", "Go back", "Find the search button"),
            "TYPING IN ANY APP" to listOf("Type do it later", "Send see you tomorrow", "Copy this", "Paste"),
            "YOUTUBE" to listOf("YouTube pe filmmaking search karo", "Open the first video", "Open the first short", "Go to Shorts", "Next short", "Like it", "Comment amazing video", "Pause the video"),
            "INSTAGRAM" to listOf("Open Instagram and go to Reels", "Next reel", "Like it and save it", "Comment nice video", "Follow this account", "Open my messages"),
            "WHATSAPP & CALLS" to listOf("Open WhatsApp", "Open the family group", "Message Rahul saying I'll be late", "Rahul ko call karo", "Read my notifications"),
            "MUSIC" to listOf("Open YT Music", "Play Believer", "Next song", "Pause the music"),
            "PHONE" to listOf("Torch jalao", "Volume 40", "Brightness up", "Silent mode", "Vibrate mode", "Turn off wifi", "Do not disturb on", "Take a screenshot", "Take a selfie", "Clear all notifications"),
            "GETTING AROUND" to listOf("Navigate to Connaught Place", "Directions to home", "Add meeting with Rahul tomorrow at 5 pm"),
            "PRODUCTIVITY" to listOf("Wake me up at 6:30 am", "Timer 10 minutes", "Remind me to drink water in 30 minutes", "Note buy milk", "Read my notes", "What's 18% of 2450"),
            "TEACHING" to listOf("Watch me", "Remember that mom means Sunita Sharma", "When I say good night, turn off wifi and set brightness to 10", "Do the same thing again"),
            "CONTROL" to listOf("Stop", "Wait", "Continue", "That's not what I meant", "Hide the bubble"),
        )
    }
}
