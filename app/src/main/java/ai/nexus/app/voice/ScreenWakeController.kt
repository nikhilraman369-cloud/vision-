package ai.nexus.app.voice

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.WindowManager
import ai.nexus.app.NexusApp
import ai.nexus.core.nlu.Command

/**
 * Pocket / lock-screen support. Commands that work without the screen (music,
 * replies from notifications, alarms, calls, toggles…) run straight away with
 * the phone locked. Commands that need to see apps wake the screen first and
 * ask Android to dismiss the lock screen (a PIN/fingerprint lock still needs you).
 */
object ScreenWakeController {
    private fun headless(c: Command) = c is Command.PlayMedia || c is Command.Media || c is Command.SetAlarm || c is Command.SetTimer ||
        c is Command.SetToggle || c is Command.SetLevel || c is Command.Ringer || c is Command.Call || c is Command.EndCall ||
        c is Command.Note || c is Command.ReadNotes || c is Command.Calculate || c is Command.ReadNotifications || c is Command.Seek ||
        c is Command.ControlTask || c is Command.Chat || c is Command.Remember || (c is Command.SendMessage && c.contact != null)

    fun prepareFor(context: Context, app: NexusApp, text: String, then: () -> Unit) {
        val pm = context.getSystemService(PowerManager::class.java)
        val interactive = pm?.isInteractive ?: true
        val steps = runCatching { app.nexus.parser.parse(text).steps.map { it.command } }.getOrDefault(emptyList())
        if (interactive || (steps.isNotEmpty() && steps.all(::headless))) { then(); return }
        runCatching {
            context.startActivity(Intent(context, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }
        // Give the screen a moment to turn on and the lock screen to go away, then run.
        Handler(Looper.getMainLooper()).postDelayed(then, 700)
    }
}

/** Invisible activity that turns the screen on and requests the lock screen to be dismissed. */
class WakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val km = getSystemService(KeyguardManager::class.java)
        km?.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { finish() }
            override fun onDismissCancelled() { finish() }
            override fun onDismissError() { finish() }
        })
        Handler(Looper.getMainLooper()).postDelayed({ if (!isFinishing) finish() }, 2500)
        NexusApp.get(this) // keep the app process warm
    }
}
