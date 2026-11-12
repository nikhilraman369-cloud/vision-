package ai.nexus.core.device

import ai.nexus.core.screen.ScreenSnapshot
import ai.nexus.core.screen.UiNode

enum class GlobalAction { BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, LOCK_SCREEN, SCREENSHOT, SPLIT_SCREEN }
enum class Direction { UP, DOWN, LEFT, RIGHT }

data class InstalledApp(val label: String, val packageName: String)

/**
 * Everything NEXUS can do *on the screen*. Implemented on Android by the
 * accessibility service; implemented in tests by a scripted fake.
 *
 * All operations return true if the platform accepted the action. Accepting
 * is not the same as succeeding – the agent verifies outcomes by observing
 * the screen afterwards.
 */
interface DeviceController {
    /** Current screen, or null if the accessibility service is not connected. */
    suspend fun snapshot(): ScreenSnapshot?

    /**
     * Suspends until the screen differs from [previousFingerprint] and has
     * settled, or until [timeoutMs]. Event-driven on Android (window change
     * events), so it returns as soon as the next screen is ready instead of
     * sleeping a fixed time.
     */
    suspend fun awaitChange(previousFingerprint: Int?, timeoutMs: Long = 2500): ScreenSnapshot?

    suspend fun click(node: UiNode): Boolean
    suspend fun longClick(node: UiNode): Boolean
    suspend fun tapAt(x: Int, y: Int): Boolean
    suspend fun doubleTapAt(x: Int, y: Int): Boolean
    suspend fun longPressAt(x: Int, y: Int, durationMs: Long = 700): Boolean

    /** Finger swipe across the screen. UP = finger moves up = content goes to the next item. */
    suspend fun swipe(direction: Direction, distanceFraction: Float = 0.6f, durationMs: Long = 250): Boolean

    /** Scroll a specific container (or the main one when null). DOWN = reveal content below. */
    suspend fun scroll(node: UiNode?, direction: Direction): Boolean

    /** Replace the text of an editable field (focusing it first). */
    suspend fun setText(node: UiNode, text: String): Boolean
    suspend fun pressImeAction(node: UiNode?): Boolean
    suspend fun copy(node: UiNode): Boolean
    suspend fun paste(node: UiNode): Boolean
    suspend fun setClipboard(text: String): Boolean

    suspend fun global(action: GlobalAction): Boolean

    suspend fun launchApp(packageName: String): Boolean
    suspend fun openUri(uri: String, packageName: String? = null): Boolean
    suspend fun installedApps(): List<InstalledApp>

    /** Screenshot as PNG/JPEG bytes, when allowed (Android 11+). Used for visual understanding. */
    suspend fun screenshot(): ByteArray? = null
}
