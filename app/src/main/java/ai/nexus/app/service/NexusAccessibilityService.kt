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

    override fun onServiceConnected() {
        instance = this
        NexusApp.get(this).onAccessibilityConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName == packageName) return // ignore our own overlay
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) event.packageName?.toString()
            ?.takeIf { it != "com.vivo.card" && it != "com.android.systemui" }?.let { foregroundPackage = it }
        recordForTeaching(event)
        lastEventAt = System.currentTimeMillis()
        changes.value = changes.value + 1
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
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val src = e.source
                val label = e.text?.joinToString(" ")?.takeIf { it.isNotBlank() }
                    ?: e.contentDescription?.toString()
                    ?: src?.let { n -> n.text?.toString() ?: n.contentDescription?.toString() ?: firstLabel(n) }
                nexus.recordUserAction(pkg, label, null)
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val typed = e.text?.joinToString("")?.takeIf { it.isNotBlank() } ?: return
                nexus.recordUserAction(pkg, null, typed)
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

    fun takeSnapshot(): ScreenSnapshot? {
        val root = appRoot() ?: return null
        val map = HashMap<Int, AccessibilityNodeInfo>()
        var next = 1
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
        liveNodes = map
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
            val s = takeSnapshot() ?: continue
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
        val target = node?.let { live(it) } ?: liveNodes.values.filter { it.isScrollable }.maxByOrNull { n ->
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
        @Volatile var instance: NexusAccessibilityService? = null
        private const val QUIET_MS = 120L
    }
}
