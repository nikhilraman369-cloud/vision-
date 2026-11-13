package ai.nexus.app.device

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import ai.nexus.app.service.NexusAccessibilityService
import ai.nexus.core.device.DeviceController
import ai.nexus.core.device.Direction
import ai.nexus.core.device.GlobalAction
import ai.nexus.core.device.InstalledApp
import ai.nexus.core.screen.ScreenSnapshot
import ai.nexus.core.screen.UiNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [DeviceController] backed by the accessibility service and Android intents. */
class AndroidDevice(private val context: Context) : DeviceController {
    private val svc get() = NexusAccessibilityService.instance

    /** Served from the accessibility service's predictive cache when the screen hasn't changed. */
    override suspend fun snapshot(): ScreenSnapshot? = withContext(Dispatchers.Default) { svc?.currentSnapshot() }

    override suspend fun awaitChange(previousFingerprint: Int?, timeoutMs: Long): ScreenSnapshot? =
        withContext(Dispatchers.Main) { svc?.awaitChange(previousFingerprint, timeoutMs) }

    override suspend fun click(node: UiNode): Boolean = withContext(Dispatchers.Main) {
        val s = svc ?: return@withContext false
        s.clickNode(node) || s.tap(node.bounds.centerX, node.bounds.centerY)
    }

    override suspend fun longClick(node: UiNode): Boolean = withContext(Dispatchers.Main) {
        val s = svc ?: return@withContext false
        s.longClickNode(node) || s.tap(node.bounds.centerX, node.bounds.centerY, 700)
    }

    override suspend fun tapAt(x: Int, y: Int) = withContext(Dispatchers.Main) { svc?.tap(x, y) ?: false }
    override suspend fun doubleTapAt(x: Int, y: Int) = withContext(Dispatchers.Main) {
        val s = svc ?: return@withContext false
        s.tap(x, y) && run { kotlinx.coroutines.delay(90); s.tap(x, y) }
    }
    override suspend fun longPressAt(x: Int, y: Int, durationMs: Long) = withContext(Dispatchers.Main) { svc?.tap(x, y, durationMs) ?: false }

    override suspend fun swipe(direction: Direction, distanceFraction: Float, durationMs: Long) =
        withContext(Dispatchers.Main) { svc?.swipe(direction, distanceFraction, durationMs) ?: false }

    override suspend fun scroll(node: UiNode?, direction: Direction) = withContext(Dispatchers.Main) { svc?.scrollNode(node, direction) ?: false }
    override suspend fun setText(node: UiNode, text: String) = withContext(Dispatchers.Main) { svc?.setNodeText(node, text) ?: false }
    override suspend fun pressImeAction(node: UiNode?) = withContext(Dispatchers.Main) { svc?.imeEnter(node) ?: false }
    override suspend fun copy(node: UiNode) = withContext(Dispatchers.Main) { svc?.copyNode(node) ?: false }
    override suspend fun paste(node: UiNode) = withContext(Dispatchers.Main) { svc?.pasteNode(node) ?: false }

    override suspend fun setClipboard(text: String): Boolean = withContext(Dispatchers.Main) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return@withContext false
        cm.setPrimaryClip(ClipData.newPlainText("NEXUS", text)); true
    }

    override suspend fun global(action: GlobalAction) = withContext(Dispatchers.Main) { svc?.global(action) ?: false }

    override suspend fun launchApp(packageName: String): Boolean = withContext(Dispatchers.Main) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return@withContext false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { context.startActivity(intent) }.isSuccess
    }

    override suspend fun openUri(uri: String, packageName: String?): Boolean = withContext(Dispatchers.Main) {
        fun start(pkg: String?): Boolean {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (pkg != null) i.setPackage(pkg)
            return runCatching { context.startActivity(i) }.isSuccess
        }
        (packageName != null && start(packageName)) || start(null)
    }

    override suspend fun installedApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(main, 0).map { ri ->
            InstalledApp(ri.loadLabel(pm).toString(), ri.activityInfo.packageName)
        }.distinctBy { it.packageName }
    }

    override suspend fun screenshot(): ByteArray? = withContext(Dispatchers.Main) { svc?.screenshotJpeg() }
}
