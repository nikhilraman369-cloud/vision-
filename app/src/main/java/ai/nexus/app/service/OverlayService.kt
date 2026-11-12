package ai.nexus.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import ai.nexus.app.NexusApp
import ai.nexus.app.ui.MainActivity
import ai.nexus.app.voice.VoiceState
import ai.nexus.core.agent.AgentState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The floating NEXUS bubble, shown over other apps. Tap = talk, long-press =
 * stop the current task, double-tap = open NEXUS. Its colour shows whether
 * NEXUS is listening (green), thinking (amber), working (violet) or idle.
 * Runs as a microphone foreground service so listening works over other apps.
 */
class OverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager
    private var bubble: View? = null
    private var dot: TextView? = null
    private var caption: TextView? = null
    private var captionJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        startForegroundCompat()
        wm = getSystemService(WindowManager::class.java)!!
        if (Settings.canDrawOverlays(this)) addBubble()
        val app = NexusApp.get(this)
        scope.launch {
            combine(app.voice.state, app.nexus.state) { v, a -> v to a }.collect { (v, a) -> render(v, a) }
        }
        scope.launch {
            app.chat.collect { lines ->
                val last = lines.lastOrNull() ?: return@collect
                if (!last.fromUser && !last.detail) showCaption(last.text)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_LISTEN) NexusApp.get(this).voice.startListening()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        bubble?.let { runCatching { wm.removeView(it) } }
        closeTarget?.let { runCatching { wm.removeView(it) } }
        bubble = null; closeTarget = null
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)!!
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "NEXUS assistant", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val listen = PendingIntent.getService(this, 2, Intent(this, OverlayService::class.java).setAction(ACTION_LISTEN), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("NEXUS is ready")
            .setContentText("Microphone is used only while the bubble is green.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Talk", listen).build())
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIF_ID, n)
    }

    private var closeTarget: View? = null
    private var teachingNow = false

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        val dp = resources.displayMetrics.density
        val size = (56 * dp).toInt()
        val d = TextView(this).apply {
            text = "N"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(IDLE_COLOR); setStroke((2 * dp).toInt(), Color.WHITE) }
            elevation = 8 * dp
            contentDescription = "NEXUS assistant. Tap to talk, long press to stop, drag down to close."
        }
        // Always-visible close button on the bubble.
        val close = TextView(this).apply {
            text = "✕"; textSize = 11f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFD63031.toInt()); setStroke((1.5f * dp).toInt(), Color.WHITE) }
            elevation = 10 * dp
            contentDescription = "Close the NEXUS bubble"
            setOnClickListener { stopSelf() }
        }
        val badge = (20 * dp).toInt()
        val bubbleBox = android.widget.FrameLayout(this).apply {
            val pad = (6 * dp).toInt()
            setPadding(0, pad, pad, 0)
            addView(d, android.widget.FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.START))
            addView(close, android.widget.FrameLayout.LayoutParams(badge, badge, Gravity.TOP or Gravity.END).apply { topMargin = -pad; rightMargin = -pad })
        }
        val cap = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; maxLines = 3; visibility = View.GONE
            setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(0xE614141F.toInt()) }
            maxWidth = (240 * dp).toInt()
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(bubbleBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(cap, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = (8 * dp).toInt() })
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = (12 * dp).toInt(); y = (300 * dp).toInt() }

        // "Drop here to close" target shown while dragging.
        val target = TextView(this).apply {
            text = "✕  Drop here to close"; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding((18 * dp).toInt(), (12 * dp).toInt(), (18 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = 30 * dp; setColor(0xE6D63031.toInt()) }
            visibility = View.GONE
        }
        val tlp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = (80 * dp).toInt() }
        wm.addView(target, tlp)
        closeTarget = target

        val app = NexusApp.get(this)
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { app.voice.toggle(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                startActivity(Intent(this@OverlayService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true
            }
            override fun onLongPress(e: MotionEvent) { app.voice.shutUp(); app.ask("stop") }
        })
        val screenH = resources.displayMetrics.heightPixels
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f; var dragging = false
        d.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { startX = lp.x; startY = lp.y; touchX = ev.rawX; touchY = ev.rawY; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - touchX; val dy = ev.rawY - touchY
                    if (dragging || dx * dx + dy * dy > 400) {
                        dragging = true
                        target.visibility = View.VISIBLE
                        target.alpha = if (ev.rawY > screenH * 0.82f) 1f else 0.6f
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(root, lp) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    target.visibility = View.GONE
                    if (dragging && ev.rawY > screenH * 0.82f) { stopSelf(); return@setOnTouchListener true }
                }
            }
            if (!dragging) gestures.onTouchEvent(ev)
            true
        }
        wm.addView(root, lp)
        bubble = root; dot = d; caption = cap
        scope.launch { app.teaching.collect { teachingNow = it; render(app.voice.state.value, app.nexus.state.value) } }
    }

    private fun render(v: VoiceState, a: AgentState) {
        val color = when {
            v == VoiceState.LISTENING || v == VoiceState.HEARING -> LISTEN_COLOR
            v == VoiceState.SPEAKING -> SPEAK_COLOR
            a == AgentState.THINKING -> THINK_COLOR
            a == AgentState.EXECUTING -> WORK_COLOR
            a == AgentState.PAUSED || a == AgentState.WAITING_FOR_USER -> PAUSE_COLOR
            else -> IDLE_COLOR
        }
        val dp = resources.displayMetrics.density
        ((dot?.background) as? GradientDrawable)?.apply {
            setColor(color)
            // Red ring while NEXUS is watching you teach it a task.
            setStroke(((if (teachingNow) 4 else 2) * dp).toInt(), if (teachingNow) 0xFFFF3B30.toInt() else Color.WHITE)
        }
        dot?.text = when { v == VoiceState.HEARING -> "●"; a == AgentState.EXECUTING -> "⋯"; else -> "N" }
    }

    private fun showCaption(text: String) {
        val c = caption ?: return
        c.text = text; c.visibility = View.VISIBLE
        captionJob?.cancel()
        captionJob = scope.launch { delay(4500); c.visibility = View.GONE }
    }

    companion object {
        @Volatile var running = false
        private const val CHANNEL = "nexus"
        private const val NOTIF_ID = 7
        const val ACTION_STOP = "ai.nexus.STOP"
        const val ACTION_LISTEN = "ai.nexus.LISTEN"
        private const val IDLE_COLOR = 0xFF6C5CE7.toInt()
        private const val LISTEN_COLOR = 0xFF00B894.toInt()
        private const val SPEAK_COLOR = 0xFF0984E3.toInt()
        private const val THINK_COLOR = 0xFFFDCB6E.toInt()
        private const val WORK_COLOR = 0xFFA29BFE.toInt()
        private const val PAUSE_COLOR = 0xFFE17055.toInt()

        fun start(c: Context) = c.startForegroundService(Intent(c, OverlayService::class.java))
        fun stop(c: Context) = c.stopService(Intent(c, OverlayService::class.java))
    }
}
