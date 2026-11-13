package ai.nexus.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import ai.nexus.app.NexusApp
import ai.nexus.core.device.Direction
import ai.nexus.core.device.GlobalAction
import ai.nexus.core.screen.Bounds
import ai.nexus.core.screen.ScreenSnapshot
import ai.nexus.core.screen.UiNode
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * The eyes and hands of NEXUS. Reads the active window as a [UiNode] tree and
 * performs taps, gestures, typing and global actions. Screen changes are
 * signalled by accessibility events, so the agent reacts as soon as a new
 * screen settles instead of sleeping for a fixed time.
 */
class NexusAccessibilityService : AccessibilityService() {

    /** Bumped on every relevant accessibility event. */
    val changes = MutableStateFlow(0L)
    @Volatile var lastEventAt = 0L
    @Volatile var foregroundPackage: String? = null

    /** Live platform nodes of the latest snapshot, by UiNode id. */
    @Volatile private var liveNodes: Map<Int, AccessibilityNodeInfo> = emptyMap()

    // ---------------- Predictive UI cache ----------------
    // Ids are unique across snapshots, and the last few snapshots' nodes stay
    // reachable, so a node from any recent snapshot always maps to its own view.
    private val idCounter = java.util.concurrent.atomic.AtomicInteger(1)
    private val nodeMaps = java.util.concurrent.ConcurrentHashMap<Int, AccessibilityNodeInfo>()
    private val snapshotRanges = java.util.ArrayDeque<IntRange>()
    @Volatile private var cached: Pair<ScreenSnapshot, Long>? = null
    private val cacheThread = android.os.HandlerThread("nexus-ui-cache").apply { start() }
    private val cacheHandler = android.os.Handler(cacheThread.looper)
    private val refreshCache = Runnable {
        val v = changes.value
        runCatching { takeSnapshot() }.getOrNull()?.let { cached = it to v }
    }

    /** The current screen: instant from the pre-built cache if nothing changed since, else read now. */
    fun currentSnapshot(): ScreenSnapshot? {
        cached?.let { (snap, v) -> if (v == changes.value) return snap }
        val v = changes.value
        return takeSnapshot()?.also { cached = it to v }
    }

    override fun onServiceConnected() {
        instance = this
        NexusApp.get(this).onAccessibilityConnected()
        startListeningPill()
    }


    // ------------------------------------------------ volume up + down = "listen"
    //
    // Single presses are NEVER touched: they go straight to the phone, so volume works exactly as normal.
    // Only when the second key joins within a moment do we treat it as the "listen" shortcut: that
    // press is swallowed and the one step the first key already changed is put back.
    private var upAt = 0L
    private var downAt = 0L
    private var comboActive = false
    private var musicBefore = -1
    private var ringBefore = -1

    override fun onKeyEvent(e: android.view.KeyEvent): Boolean {
        val code = e.keyCode
        if (code != android.view.KeyEvent.KEYCODE_VOLUME_UP && code != android.view.KeyEvent.KEYCODE_VOLUME_DOWN) return false
        val app = NexusApp.get(this)
        if (!app.memory.settings.volumeKeys) return false
        val am = getSystemService(android.media.AudioManager::class.java)
        if (am != null && am.mode != android.media.AudioManager.MODE_NORMAL) return false
        if (foregroundPackage?.contains("camera", ignoreCase = true) == true) return false

        val isUp = code == android.view.KeyEvent.KEYCODE_VOLUME_UP
        val now = android.os.SystemClock.uptimeMillis()
        if (e.action == android.view.KeyEvent.ACTION_DOWN) {
            if (e.repeatCount > 0) return comboActive
            if (isUp) upAt = now else downAt = now
            val otherAt = if (isUp) downAt else upAt
            if (otherAt != 0L && now - otherAt <= COMBO_MS && !comboActive) {
                comboActive = true
                runCatching { if (musicBefore >= 0) am?.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, musicBefore, 0) }
                runCatching { if (ringBefore >= 0) am?.setStreamVolume(android.media.AudioManager.STREAM_RING, ringBefore, 0) }
                app.quickListen()
                return true
            }
            if (comboActive) return true
            // First key: remember the volumes (in case it turns out to be the shortcut), then let it through.
            musicBefore = am?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: -1
            ringBefore = am?.getStreamVolume(android.media.AudioManager.STREAM_RING) ?: -1
            return false
        }
        if (e.action == android.view.KeyEvent.ACTION_UP) {
            if (isUp) upAt = 0 else downAt = 0
            val swallow = comboActive
            if (upAt == 0L && downAt == 0L) comboActive = false
            return swallow
        }
        return false
    }

    // ------------------------------------------------ "Listening…" popup (works on any screen, even the lock screen)
    private val pillScope = kotlinx.coroutines.MainScope()
    private var pill: android.widget.TextView? = null

    private fun startListeningPill() {
        val app = NexusApp.get(this)
        pillScope.launch {
            kotlinx.coroutines.flow.combine(app.voice.state, app.voice.partial, app.nexus.state, app.nexus.loop) { v, p, a, l -> listOf(v, p, a, l) }.collect { (v0, p0, a0, l0) ->
                val v = v0 as ai.nexus.app.voice.VoiceState; val p = p0 as String; val a = a0 as ai.nexus.core.agent.AgentState; val loop = l0 as String?
                val text = when {
                    v == ai.nexus.app.voice.VoiceState.HEARING -> "🎙  " + p.ifBlank { "Listening…" }
                    v == ai.nexus.app.voice.VoiceState.LISTENING -> "🎙  Listening… " + p
                    app.voice.inConversation() && a == ai.nexus.core.agent.AgentState.THINKING -> "✦  Thinking…"
                    loop != null -> "⟳  $loop · tap to stop"
                    else -> null
                }
                showPill(text?.trim())
            }
        }
    }

    private fun showPill(text: String?) {
        val wm = getSystemService(android.view.WindowManager::class.java) ?: return
        if (text == null) {
            pillWanted = null
            // Small delay so a quick restart between listening windows doesn't make it blink.
            pill?.postDelayed({ if (pillWanted == null) pill?.let { p -> runCatching { wm.removeView(p) }; pill = null } }, 400)
            return
        }
        pillWanted = text
        val dp = resources.displayMetrics.density
        val p = pill ?: android.widget.TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt()); textSize = 15f
            setPadding((18 * dp).toInt(), (10 * dp).toInt(), (18 * dp).toInt(), (10 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 24 * dp; setColor(0xF0151526.toInt()); setStroke((2 * dp).toInt(), 0xFF00C2A8.toInt()) }
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.START
            setOnClickListener { val a = NexusApp.get(this@NexusAccessibilityService); if (a.nexus.loop.value != null) a.nexus.stopLoop() else a.voice.stopListening() }
            val lp = android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.WRAP_CONTENT, android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL; y = (48 * dp).toInt() }
            runCatching { wm.addView(this, lp) }.onFailure { return }
            pill = this
        }
        p.text = text
    }
    @Volatile private var pillWanted: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName == packageName) return // ignore our own overlay
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) event.packageName?.toString()
            ?.takeIf { it != "com.vivo.card" && it != "com.android.systemui" }?.let { foregroundPackage = it }
        recordForTeaching(event)
        guardDistractions(event)
        // Re-index the screen 100 ms after it stops changing, so commands find nodes instantly.
        cacheHandler.removeCallbacks(refreshCache)
        cacheHandler.postDelayed(refreshCache, 100)
        lastEventAt = System.currentTimeMillis()
        changes.value = changes.value + 1
    }

    private var lastNudge = 0L
    private var nudges = 0

    /** Study / Writing modes: opening a blocked app gets a short nudge and sends you back. */
    private fun guardDistractions(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        val app = NexusApp.get(this)
        if (!app.life.isBlocked(pkg)) return
        val now = System.currentTimeMillis()
        if (now - lastNudge < 4000) return
        nudges = if (now - lastNudge > 30 * 60_000) 1 else nudges + 1
        lastNudge = now
        val boss = app.memory.settings.addressAs
        val mode = app.life.data.activeMode ?: "focus"
        val msg = when (nudges) {
            1 -> "$boss, you're in $mode mode. Back to it."
            2 -> "$boss, that's the second time. Your task is waiting."
            else -> "$boss, if you really need it, say \"allow ${pkg.substringAfterLast('.')} for 10 minutes\" and tell me why."
        }
        performGlobalAction(GLOBAL_ACTION_HOME)
        app.speakProactive(msg)
    }

    /** While the user says "watch me", turn their taps and typing into a routine. */
    private fun recordForTeaching(e: AccessibilityEvent) {
        val nexus = NexusApp.get(this).nexus
        if (!nexus.isTeaching) return
        val pkg = e.packageName?.toString() ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
                if (packageManager.getLaunchIntentForPackage(pkg) != null) nexus.recordAppSwitch(pkg, label)
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                val src = e.source
                if (pkg in ai.nexus.core.screen.Packages.LAUNCHERS) {
                    nexus.recordUserAction(pkg, e.text?.joinToString(" ")?.takeIf { it.isNotBlank() } ?: src?.let { it.text?.toString() ?: it.contentDescription?.toString() }, null)
                    return
                }
                // Robust selectors: id, text, description, class and position on screen.
                val r = Rect(); src?.getBoundsInScreen(r)
                val dm = resources.displayMetrics
                val text = (src?.text?.toString() ?: e.text?.joinToString(" "))?.takeIf { it.isNotBlank() } ?: src?.let { firstLabel(it) }
                val sel = ai.nexus.core.life.Selector(
                    viewId = src?.viewIdResourceName, text = text?.take(60), desc = src?.contentDescription?.toString()?.take(60),
                    cls = src?.className?.toString(),
                    xPct = if (src != null) r.centerX().toFloat() / dm.widthPixels else null, yPct = if (src != null) r.centerY().toFloat() / dm.heightPixels else null,
                )
                nexus.recordStep(ai.nexus.core.life.RecipeStep(if (e.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) "long_press" else "tap", pkg, sel))
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                if (e.isPassword || e.source?.isPassword == true) return // never record passwords
                val typed = e.text?.joinToString("")?.takeIf { it.isNotBlank() } ?: return
                nexus.recordStep(ai.nexus.core.life.RecipeStep("type", pkg, text = typed))
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val rec = e.scrollDeltaY
                if (rec != 0) nexus.recordStep(ai.nexus.core.life.RecipeStep("scroll", pkg, direction = if (rec > 0) "down" else "up"))
            }
        }
    }

    private fun firstLabel(n: AccessibilityNodeInfo, depth: Int = 0): String? {
        if (depth > 3) return null
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            (c.text?.toString() ?: c.contentDescription?.toString())?.takeIf { it.isNotBlank() }?.let { return it }
            firstLabel(c, depth + 1)?.let { return it }
        }
        return null
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        pillScope.cancel()
        runCatching { pill?.let { getSystemService(android.view.WindowManager::class.java)?.removeView(it) } }; pill = null
        cacheThread.quitSafely()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ reading

    /** System overlays that sit on top of apps but are never what the user means. */
    private fun isOverlayJunk(root: AccessibilityNodeInfo): Boolean {
        val pkg = root.packageName?.toString() ?: return true
        if (pkg == packageName || pkg == "com.vivo.card" || pkg == "com.android.systemui" || pkg.contains("gesture") || pkg.contains("navigationbar")) return true
        val r = Rect(); root.getBoundsInScreen(r)
        val dm = resources.displayMetrics
        // Tiny or off-screen windows (gesture bars, edge panels).
        return r.width() < dm.widthPixels / 3 || r.height() < dm.heightPixels / 4 || (root.childCount == 0 && root.text == null)
    }

    private fun appRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.takeIf { !isOverlayJunk(it) }?.let { return it }
        val wins = runCatching { windows }.getOrDefault(emptyList())
        val roots = wins.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedWith(compareByDescending<AccessibilityWindowInfo> { it.isActive }.thenByDescending { it.isFocused })
            .mapNotNull { it.root }.filter { !isOverlayJunk(it) }
        // Prefer the app we know is in front, else the biggest real window.
        return roots.firstOrNull { it.packageName?.toString() == foregroundPackage } ?: roots.maxByOrNull { val r = Rect(); it.getBoundsInScreen(r); r.width().toLong() * r.height() }
    }

    @Synchronized fun takeSnapshot(): ScreenSnapshot? {
        val root = appRoot() ?: return null
        val map = HashMap<Int, AccessibilityNodeInfo>()
        val first = idCounter.get()
        var next = first
        val rect = Rect()
        fun convert(n: AccessibilityNodeInfo, depth: Int): UiNode {
            val id = next++
            map[id] = n
            n.getBoundsInScreen(rect)
            val kids = ArrayList<UiNode>()
            if (depth < 60) {
                for (i in 0 until n.childCount) {
                    val c = n.getChild(i) ?: continue
                    if (kids.size > 300) break
                    kids += convert(c, depth + 1)
                }
            }
            return UiNode(
                id = id,
                className = n.className?.toString() ?: "",
                text = n.text?.toString(),
                contentDescription = n.contentDescription?.toString(),
                hintText = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString() else null,
                viewId = n.viewIdResourceName,
                bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
                clickable = n.isClickable,
                longClickable = n.isLongClickable,
                scrollable = n.isScrollable,
                editable = n.isEditable,
                checkable = n.isCheckable,
                checked = n.isChecked,
                selected = n.isSelected,
                focused = n.isFocused,
                enabled = n.isEnabled,
                visible = n.isVisibleToUser,
                children = kids,
            )
        }
        val tree = convert(root, 0)
        idCounter.set(next)
        nodeMaps.putAll(map)
        snapshotRanges.addLast(first until next)
        while (snapshotRanges.size > 6) snapshotRanges.pollFirst()?.forEach { nodeMaps.remove(it) }
        liveNodes = nodeMaps
        val dm = resources.displayMetrics
        val keyboard = runCatching { windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }.getOrDefault(false)
        val pkg = root.packageName?.toString() ?: foregroundPackage ?: "unknown"
        return ScreenSnapshot(pkg, tree, activityName = null, screenWidth = dm.widthPixels, screenHeight = dm.heightPixels, keyboardVisible = keyboard)
    }

    /** Wait for the screen to change and settle (no events for a short quiet period). */
    suspend fun awaitChange(previousFingerprint: Int?, timeoutMs: Long): ScreenSnapshot? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val seen = changes.value
            withTimeoutOrNull(deadline - System.currentTimeMillis()) { changes.first { it != seen } } ?: break
            // Debounce: let animations and list population finish.
            val settleUntil = System.currentTimeMillis() + 600
            while (System.currentTimeMillis() < settleUntil && System.currentTimeMillis() - lastEventAt < QUIET_MS) {
                kotlinx.coroutines.delay(30)
            }
            val s = currentSnapshot() ?: continue
            if (previousFingerprint == null || s.fingerprint != previousFingerprint) return s
        }
        return null
    }

    // ------------------------------------------------------------ acting

    fun live(node: UiNode): AccessibilityNodeInfo? = liveNodes[node.id]

    fun clickNode(node: UiNode): Boolean {
        val n = live(node) ?: return false
        var cur: AccessibilityNodeInfo? = n
        while (cur != null) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
        }
        return false
    }

    fun longClickNode(node: UiNode): Boolean {
        var cur: AccessibilityNodeInfo? = live(node)
        while (cur != null) {
            if (cur.isLongClickable && cur.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true
            cur = cur.parent
        }
        return false
    }

    fun scrollNode(node: UiNode?, direction: Direction): Boolean {
        val latest = snapshotRanges.peekLast()
        val target = node?.let { live(it) } ?: liveNodes.entries.filter { (k, v) -> latest != null && k in latest && v.isScrollable }.map { it.value }.maxByOrNull { n ->
            val r = Rect(); n.getBoundsInScreen(r); r.width().toLong() * r.height()
        } ?: return false
        val specific = when (direction) {
            Direction.DOWN -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
            Direction.UP -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
            Direction.LEFT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
            Direction.RIGHT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
        }
        if (target.actionList.contains(specific) && target.performAction(specific.id)) return true
        val generic = if (direction == Direction.DOWN || direction == Direction.RIGHT) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return target.performAction(generic)
    }

    fun setNodeText(node: UiNode, text: String): Boolean {
        val n = live(node) ?: return false
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun imeEnter(node: UiNode?): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val n = node?.let { live(it) } ?: findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    fun copyNode(node: UiNode) = live(node)?.performAction(AccessibilityNodeInfo.ACTION_COPY) == true
    fun pasteNode(node: UiNode) = live(node)?.performAction(AccessibilityNodeInfo.ACTION_PASTE) == true

    fun global(action: GlobalAction): Boolean = performGlobalAction(
        when (action) {
            GlobalAction.BACK -> GLOBAL_ACTION_BACK
            GlobalAction.HOME -> GLOBAL_ACTION_HOME
            GlobalAction.RECENTS -> GLOBAL_ACTION_RECENTS
            GlobalAction.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
            GlobalAction.QUICK_SETTINGS -> GLOBAL_ACTION_QUICK_SETTINGS
            GlobalAction.LOCK_SCREEN -> GLOBAL_ACTION_LOCK_SCREEN
            GlobalAction.SCREENSHOT -> GLOBAL_ACTION_TAKE_SCREENSHOT
            GlobalAction.SPLIT_SCREEN -> GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
        },
    )

    suspend fun gesture(path: Path, durationMs: Long): Boolean = suspendCancellableCoroutine { cont ->
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
        }, null)
        if (!ok && cont.isActive) cont.resume(false)
    }

    suspend fun tap(x: Int, y: Int, durationMs: Long = 50) = gesture(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, durationMs)

    suspend fun swipe(direction: Direction, fraction: Float, durationMs: Long): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        val d = fraction.coerceIn(0.1f, 0.9f)
        val p = Path()
        when (direction) {
            Direction.UP -> { p.moveTo(w / 2, h * (0.5f + d / 2)); p.lineTo(w / 2, h * (0.5f - d / 2)) }
            Direction.DOWN -> { p.moveTo(w / 2, h * (0.5f - d / 2)); p.lineTo(w / 2, h * (0.5f + d / 2)) }
            Direction.LEFT -> { p.moveTo(w * (0.5f + d / 2), h / 2); p.lineTo(w * (0.5f - d / 2), h / 2) }
            Direction.RIGHT -> { p.moveTo(w * (0.5f - d / 2), h / 2); p.lineTo(w * (0.5f + d / 2), h / 2) }
        }
        return gesture(p, durationMs)
    }

    /** JPEG screenshot (Android 11+), downscaled to keep uploads small. */
    suspend fun screenshotJpeg(): ByteArray? {
        if (Build.VERSION.SDK_INT < 30) return null
        return suspendCancellableCoroutine { cont ->
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bmp = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    result.hardwareBuffer.close()
                    if (bmp == null) { if (cont.isActive) cont.resume(null); return }
                    val scale = 720f / maxOf(bmp.width, 1)
                    val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
                    val out = ByteArrayOutputStream()
                    small.compress(Bitmap.CompressFormat.JPEG, 70, out)
                    if (cont.isActive) cont.resume(out.toByteArray())
                }
                override fun onFailure(errorCode: Int) { if (cont.isActive) cont.resume(null) }
            })
        }
    }

    companion object {
        private const val COMBO_MS = 300L
        @Volatile var instance: NexusAccessibilityService? = null
        private const val QUIET_MS = 120L
    }
}
