package ai.nexus.app.service

import android.animation.ValueAnimator
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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import ai.nexus.app.NexusApp
import ai.nexus.app.ui.MainActivity
import ai.nexus.app.ui.PanelActivity
import ai.nexus.app.ui.TeachActivity
import ai.nexus.app.voice.NexusVoiceService
import ai.nexus.app.voice.VoiceState
import ai.nexus.core.agent.AgentState
import ai.nexus.core.life.Briefs
import ai.nexus.core.voice.HandsFreeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The NEXUS bubble: docked half-hidden at the screen edge, never in the way.
 *  - Tap: slides out a compact command card (orb, live transcript, last reply,
 *    top tasks, next deadline, mode, quick chips). Collapses by itself.
 *  - Double-tap: continuous conversation.  - Long-press: quick menu.
 *  - Drag: move up/down or to the other edge; drop at the bottom to close.
 *  - When NEXUS is stuck, the card shows "Boss, I'm stuck" with Teach me / Skip.
 */
class OverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager
    private lateinit var app: NexusApp
    private var dp = 1f
    private var screenW = 1080
    private var screenH = 2400

    private var bubble: OrbView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var card: LinearLayout? = null
    private var cardLp: WindowManager.LayoutParams? = null
    private var closeTarget: View? = null
    private var doneTeach: View? = null
    private var fullscreenProbe: View? = null
    private var collapseJob: Job? = null
    private var orbAnim: ValueAnimator? = null
    @Volatile private var handsFreeActive = false
    private var cardMode = "main" // main | menu | modes | stuck

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        app = NexusApp.get(this)
        startForegroundCompat()
        wm = getSystemService(WindowManager::class.java)!!
        dp = resources.displayMetrics.density
        screenW = resources.displayMetrics.widthPixels; screenH = resources.displayMetrics.heightPixels
        if (Settings.canDrawOverlays(this)) { addBubble(); addFullscreenProbe() }
        scope.launch {
            combine(app.voice.state, app.nexus.state, NexusVoiceService.state) { v, a, h -> Triple(v, a, h) }.collect { (v, a, h) ->
                handsFreeActive = h == HandsFreeState.ACTIVE_COMMAND_WINDOW
                renderBubble(v, a)
                if (card != null && cardMode == "main") renderCard()
            }
        }
        scope.launch { app.chat.collect { if (card != null && cardMode == "main") renderCard() } }
        scope.launch { app.voice.partial.collect { if (card != null && cardMode == "main") renderCard() } }
        scope.launch { app.stuck.collect { st -> if (st != null) { cardMode = "stuck"; showCard(autoCollapse = false) } else if (cardMode == "stuck") hideCard() } }
        scope.launch { app.nexus.teachSteps.collect { updateTeachLabel() } }
        scope.launch { app.teaching.collect { t -> if (t) showDoneTeaching() else hideDoneTeaching(); renderBubble(app.voice.state.value, app.nexus.state.value) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_LISTEN) app.talk()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        orbAnim?.cancel()
        listOf(bubble, card, closeTarget, doneTeach, fullscreenProbe).forEach { v -> v?.let { runCatching { wm.removeView(it) } } }
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
            .setContentText("Microphone is used only while the bubble glows green.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Talk", listen).build())
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .setOngoing(true).build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(NOTIF_ID, n)
        }.onFailure { stopSelf() }
    }

    private fun overlayParams(w: Int, h: Int, extraFlags: Int = 0) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or extraFlags,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    // ---------------------------------------------------------------- bubble

    /** Window size: a little bigger than the orb itself so the ripples and rings have room. */
    private val size get() = (app.memory.settings.bubbleSize * dp * 1.3f).toInt()

    private fun dockX(side: String) = if (side == "left") -(size * 0.38f).toInt() else screenW - (size * 0.62f).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        val s = app.memory.settings
        val b = OrbView(this).apply {
            alpha = s.bubbleOpacity
            contentDescription = "NEXUS. Tap for the command card, double tap to talk, long press for the menu."
        }
        val lp = overlayParams(size, size).apply { x = dockX(s.bubbleSide); y = (s.bubbleY * screenH).toInt().coerceIn((60 * dp).toInt(), screenH - (160 * dp).toInt()) }
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            // Tap = talk (listens, answers, then listens 6 s more for a follow-up). Tap again = stop.
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val wasListening = app.voice.listening
                app.talk()
                if (!wasListening && card == null) { cardMode = "main"; showCard() } else if (wasListening) hideCard()
                return true
            }
            // Double-tap = open the card (tasks, mode, last reply) without listening.
            override fun onDoubleTap(e: MotionEvent): Boolean { if (card == null) { cardMode = "main"; showCard() } else hideCard(); return true }
            override fun onLongPress(e: MotionEvent) { cardMode = "menu"; showCard() }
        })
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f; var dragging = false
        b.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { startX = lp.x; startY = lp.y; touchX = ev.rawX; touchY = ev.rawY; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - touchX; val dy = ev.rawY - touchY
                    if (dragging || dx * dx + dy * dy > 300) {
                        if (!dragging) { dragging = true; slideAnim?.cancel(); hideCard(); showCloseTarget(true) }
                        lp.x = (startX + dx).toInt(); lp.y = (startY + dy).toInt()
                        closeTarget?.alpha = if (ev.rawY > screenH * 0.85f) 1f else 0.55f
                        runCatching { wm.updateViewLayout(b, lp) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                    showCloseTarget(false)
                    if (ev.rawY > screenH * 0.85f) { stopSelf(); return@setOnTouchListener true }
                    // Snap to the nearest edge, half hidden; remember where.
                    val side = if (ev.rawX < screenW / 2) "left" else "right"
                    lp.x = dockX(side); bubbleOut = false
                    lp.y = lp.y.coerceIn((40 * dp).toInt(), screenH - (140 * dp).toInt())
                    runCatching { wm.updateViewLayout(b, lp) }
                    app.memory.updateSettings { it.copy(bubbleSide = side, bubbleY = lp.y.toFloat() / screenH) }
                }
            }
            if (!dragging) gestures.onTouchEvent(ev)
            true
        }
        wm.addView(b, lp)
        bubble = b; bubbleLp = lp
    }

    private fun renderBubble(v: VoiceState, a: AgentState) {
        val teaching = app.teaching.value
        val stuck = app.stuck.value != null
        val mode = when {
            stuck -> OrbView.Mode.STUCK
            v == VoiceState.HEARING -> OrbView.Mode.HEARING
            v == VoiceState.LISTENING -> OrbView.Mode.LISTENING
            v == VoiceState.SPEAKING -> OrbView.Mode.SPEAKING
            a == AgentState.THINKING -> OrbView.Mode.THINKING
            a == AgentState.EXECUTING -> OrbView.Mode.WORKING
            a == AgentState.PAUSED || a == AgentState.WAITING_FOR_USER -> OrbView.Mode.WAITING
            else -> OrbView.Mode.IDLE
        }
        val orb = bubble ?: return
        orb.mode = mode
        orb.teaching = teaching
        orb.label = if (stuck) "?" else "N"
        orb.alpha = if (mode == OrbView.Mode.IDLE && !teaching) app.memory.settings.bubbleOpacity else 1f
        // While NEXUS is doing something, slide the orb fully onto the screen so you can see it; tuck it back when idle.
        slideBubble(fullyOut = mode != OrbView.Mode.IDLE || teaching)
    }

    private var slideAnim: ValueAnimator? = null
    private var bubbleOut = false

    private fun slideBubble(fullyOut: Boolean) {
        val b = bubble ?: return; val lp = bubbleLp ?: return
        if (fullyOut == bubbleOut || b.isPressed) return
        bubbleOut = fullyOut
        val side = app.memory.settings.bubbleSide
        val target = if (!fullyOut) dockX(side) else if (side == "left") (4 * dp).toInt() else screenW - size - (4 * dp).toInt()
        slideAnim?.cancel()
        slideAnim = ValueAnimator.ofInt(lp.x, target).apply {
            duration = 260
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { lp.x = it.animatedValue as Int; runCatching { wm.updateViewLayout(b, lp) } }
            start()
        }
    }

    private fun showCloseTarget(show: Boolean) {
        if (show && closeTarget == null) {
            val t = TextView(this).apply {
                text = "✕  Drop here to close"; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                setPadding((18 * dp).toInt(), (12 * dp).toInt(), (18 * dp).toInt(), (12 * dp).toInt())
                background = GradientDrawable().apply { cornerRadius = 30 * dp; setColor(0xE6D63031.toInt()) }
            }
            val lp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                .apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = (70 * dp).toInt() }
            runCatching { wm.addView(t, lp) }; closeTarget = t
        } else if (!show) { closeTarget?.let { runCatching { wm.removeView(it) } }; closeTarget = null }
    }

    /** Hide the bubble while a video or game is full screen. */
    @Suppress("DEPRECATION")
    private fun addFullscreenProbe() {
        val v = View(this)
        val lp = overlayParams(1, 1, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        v.setOnSystemUiVisibilityChangeListener { vis ->
            if (!app.memory.settings.bubbleHideFullscreen) return@setOnSystemUiVisibilityChangeListener
            val full = vis and View.SYSTEM_UI_FLAG_FULLSCREEN != 0
            bubble?.visibility = if (full) View.GONE else View.VISIBLE
            if (full) hideCard()
        }
        runCatching { wm.addView(v, lp) }; fullscreenProbe = v
    }

    // ---------------------------------------------------------------- card

    private fun chip(parent: ViewGroup, label: String, color: Int = 0xFF2B2B40.toInt(), action: () -> Unit) {
        parent.addView(TextView(this).apply {
            text = label; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding((12 * dp).toInt(), (7 * dp).toInt(), (12 * dp).toInt(), (7 * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = 16 * dp; setColor(color) }
            setOnClickListener { touch(); action() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = (6 * dp).toInt(); topMargin = (6 * dp).toInt() })
    }

    private fun text(parent: ViewGroup, t: String, sizeSp: Float = 13f, color: Int = 0xFFD8D8E8.toInt(), bold: Boolean = false, maxLines: Int = 3) {
        if (t.isBlank()) return
        parent.addView(TextView(this).apply {
            text = t; textSize = sizeSp; setTextColor(color); this.maxLines = maxLines; ellipsize = android.text.TextUtils.TruncateAt.END
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (4 * dp).toInt() })
    }

    private fun showCard(autoCollapse: Boolean = true) {
        if (card == null) {
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
                background = GradientDrawable().apply { cornerRadius = 22 * dp; setColor(0xF2101018.toInt()); setStroke((1 * dp).toInt(), 0x556C5CE7) }
                elevation = 12 * dp
                setOnTouchListener { _, ev -> if (ev.action == MotionEvent.ACTION_OUTSIDE && cardMode != "stuck") hideCard(); false }
            }
            val w = (300 * dp).toInt().coerceAtMost(screenW - (24 * dp).toInt())
            val left = app.memory.settings.bubbleSide == "left"
            val lp = overlayParams(w, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH).apply {
                x = if (left) (size * 0.7f).toInt() else screenW - w - (size * 0.7f).toInt()
                y = ((bubbleLp?.y ?: 300) - (60 * dp).toInt()).coerceIn((40 * dp).toInt(), screenH - (480 * dp).toInt())
            }
            runCatching { wm.addView(c, lp) }
            card = c; cardLp = lp
            c.translationX = if (left) -60 * dp else 60 * dp; c.alpha = 0f
            c.animate().translationX(0f).alpha(1f).setDuration(160).start()
        }
        renderCard()
        if (autoCollapse) touch() else collapseJob?.cancel()
    }

    /** Restart the auto-collapse countdown. */
    private fun touch() {
        collapseJob?.cancel()
        if (cardMode == "stuck") return
        collapseJob = scope.launch { delay(7000); while (app.voice.inConversation() || app.voice.isSpeaking) delay(1000); hideCard() }
    }

    private fun hideCard() {
        collapseJob?.cancel(); orbAnim?.cancel()
        card?.let { runCatching { wm.removeView(it) } }
        card = null
    }

    private fun renderCard() {
        val c = card ?: return
        c.removeAllViews()
        val boss = app.memory.settings.addressAs
        when (cardMode) {
            "stuck" -> {
                val st = app.stuck.value ?: run { hideCard(); return }
                text(c, "$boss, I'm stuck at this step", 15f, Color.WHITE, bold = true)
                text(c, "\"${st.step}\" in ${st.app}", 13f)
                text(c, "Show me once and I'll do it myself next time.", 12f, 0xFF9A9AB0.toInt())
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                chip(row, "Teach me", 0xFF6C5CE7.toInt()) { app.nexus.stuckTeach(); app.clearStuck(); hideCard() }
                chip(row, "Type instruction") { openMain("instruct"); app.clearStuck(); hideCard() }
                chip(row, "Skip") { app.nexus.stuckSkip(); app.clearStuck(); hideCard() }
                chip(row, "Cancel", 0xFF8A2E2E.toInt()) { app.nexus.stuckCancel(); app.clearStuck(); hideCard() }
                c.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
            }
            "menu" -> {
                text(c, "NEXUS", 15f, Color.WHITE, bold = true)
                val s = app.memory.settings
                val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                chip(grid, if (s.voiceMuted) "🔊 Unmute voice" else "🔇 Mute voice") { app.ask(if (s.voiceMuted) "unmute voice" else "mute voice"); hideCard() }
                chip(grid, if (s.notificationWatching) "⏸ Pause notification watching" else "▶ Resume notification watching") { app.ask(if (s.notificationWatching) "pause notifications" else "resume notifications"); hideCard() }
                chip(grid, "■ Stop speaking") { app.voice.shutUp(); hideCard() }
                chip(grid, "☰ Open NEXUS") { openMain(null); hideCard() }
                chip(grid, "✕ Hide bubble", 0xFF8A2E2E.toInt()) { stopSelf() }
                c.addView(grid)
            }
            "modes" -> {
                text(c, "Modes", 15f, Color.WHITE, bold = true)
                val active = app.life.data.activeMode
                val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                app.life.data.modes.forEach { m -> chip(row, (if (m.name == active) "● " else "") + m.name, if (m.name == active) 0xFF00B894.toInt() else 0xFF2B2B40.toInt()) { app.ask(if (m.name == active) "stop ${m.name} mode" else "${m.name} mode"); hideCard() } }
                c.addView(row)
            }
            else -> {
                // Header: animated orb + state
                val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val orb = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(stateColor()) } }
                head.addView(FrameLayout(this).apply { addView(orb, FrameLayout.LayoutParams((22 * dp).toInt(), (22 * dp).toInt(), Gravity.CENTER)) }, LinearLayout.LayoutParams((30 * dp).toInt(), (30 * dp).toInt()))
                head.addView(TextView(this).apply { text = "  " + stateText(); textSize = 15f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD })
                c.addView(head)
                orbAnim?.cancel()
                orbAnim = ValueAnimator.ofFloat(0.8f, 1.15f).apply {
                    duration = if (app.nexus.state.value == AgentState.IDLE) 1400 else 500
                    repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { orb.scaleX = it.animatedValue as Float; orb.scaleY = it.animatedValue as Float }
                    start()
                }
                val partial = app.voice.partial.value
                val lines = app.chat.value
                val lastUser = lines.lastOrNull { it.fromUser }?.text
                val lastReply = lines.lastOrNull { !it.fromUser && !it.detail }?.text
                text(c, if (partial.isNotBlank()) "“$partial”" else lastUser?.let { "You: $it" } ?: "Tap Mic and speak, $boss.", 13f, 0xFFB8B4FF.toInt(), maxLines = 2)
                lastReply?.let { text(c, it, 13.5f, Color.WHITE, maxLines = 4) }
                val life = app.life.data
                val now = System.currentTimeMillis()
                val meta = listOfNotNull(
                    life.activeMode?.let { "Mode: $it" },
                    life.tasks.filter { !it.done && it.dueMs != null }.minByOrNull { it.dueMs!! }?.let { "Next: ${it.title.take(28)} · ${Briefs.whenText(it.dueMs!!, now)}" },
                ).joinToString("   ")
                text(c, meta, 11.5f, 0xFF9A9AB0.toInt(), maxLines = 2)
                Briefs.rank(life.tasks, life.activeMode, now).take(3).forEachIndexed { i, t -> text(c, "${i + 1}. ${t.title}", 12.5f, 0xFFD8D8E8.toInt(), maxLines = 1) }
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                chip(row, "🎤 Mic", 0xFF6C5CE7.toInt()) { app.talk() }
                chip(row, "✎ Note") { app.talkWithPrefix("note: ") }
                chip(row, "◐ Mode") { cardMode = "modes"; renderCard() }
                chip(row, "🎓 Teach") { startActivity(Intent(this, TeachActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); hideCard() }
                chip(row, "☑ Tasks") { startActivity(Intent(this, PanelActivity::class.java).putExtra("section", "tasks").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); hideCard() }
                chip(row, "■ Stop", 0xFF8A2E2E.toInt()) { app.voice.shutUp(); app.ask("stop") }
                c.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
            }
        }
    }

    private fun stateColor(): Int {
        val v = app.voice.state.value; val a = app.nexus.state.value
        return when {
            v == VoiceState.LISTENING || v == VoiceState.HEARING || handsFreeActive -> LISTEN
            v == VoiceState.SPEAKING -> SPEAK
            a == AgentState.THINKING -> THINK
            a == AgentState.EXECUTING -> WORK
            else -> IDLE
        }
    }

    private fun stateText(): String {
        val v = app.voice.state.value; val a = app.nexus.state.value
        return when {
            v == VoiceState.HEARING -> "Listening…"
            v == VoiceState.LISTENING || handsFreeActive -> "Listening"
            v == VoiceState.SPEAKING -> "Speaking"
            a == AgentState.THINKING -> "Thinking"
            a == AgentState.EXECUTING -> "Working"
            a == AgentState.WAITING_FOR_USER -> "Waiting for you"
            else -> "Ready"
        }
    }

    private fun openMain(extra: String?) {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { extra?.let { putExtra("action", it) } })
    }

    // ---------------------------------------------------------------- teaching

    private var teachLabel: TextView? = null

    private fun updateTeachLabel() {
        val n = app.nexus.teachSteps.value
        teachLabel?.text = "●  " + (app.nexus.teachingName?.take(24) ?: "Teaching") + "  ·  $n step" + (if (n == 1) "" else "s")
    }

    /** Red recording bar: what's being taught, how many steps so far, and Done / Cancel. */
    private fun showDoneTeaching() {
        if (doneTeach != null) return
        fun pill(t: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
            text = t; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = 18 * dp; setColor(color) }
            setOnClickListener { onClick() }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = 28 * dp; setColor(0xF0D63031.toInt()) }
        }
        val label = TextView(this).apply { textSize = 13.5f; setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD }
        teachLabel = label
        bar.addView(label, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = (10 * dp).toInt() })
        bar.addView(pill("Done ✓", 0xFF00B894.toInt()) { app.finishTeachingUi(this) }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = (6 * dp).toInt() })
        bar.addView(pill("✕", 0x66000000) { app.nexus.cancelTeaching() })
        val lp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (36 * dp).toInt() }
        runCatching { wm.addView(bar, lp) }; doneTeach = bar
        updateTeachLabel()
    }

    private fun hideDoneTeaching() { doneTeach?.let { runCatching { wm.removeView(it) } }; doneTeach = null; teachLabel = null }

    companion object {
        @Volatile var running = false
        private const val CHANNEL = "nexus"
        private const val NOTIF_ID = 7
        const val ACTION_STOP = "ai.nexus.STOP"
        const val ACTION_LISTEN = "ai.nexus.LISTEN"
        private const val IDLE = 0xFF6C5CE7.toInt()
        private const val LISTEN = 0xFF00B894.toInt()
        private const val SPEAK = 0xFF0984E3.toInt()
        private const val THINK = 0xFFFDCB6E.toInt()
        private const val WORK = 0xFFA29BFE.toInt()
        private const val PAUSE = 0xFFE17055.toInt()
        private const val STUCK = 0xFFD63031.toInt()

        fun start(c: Context) { runCatching { c.startForegroundService(Intent(c, OverlayService::class.java)) } }
        fun stop(c: Context) = c.stopService(Intent(c, OverlayService::class.java))
    }
}
