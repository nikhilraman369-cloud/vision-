package ai.nexus.core.fake

import ai.nexus.core.device.*
import ai.nexus.core.screen.Bounds
import ai.nexus.core.screen.ScreenSnapshot
import ai.nexus.core.screen.UiNode
import kotlinx.coroutines.yield

/** Builds a UiNode tree and remembers what each node does when tapped/typed. */
class UiBuilder {
    private var nextId = 1
    val clicks = HashMap<Int, () -> Unit>()
    val texts = HashMap<Int, (String) -> Unit>()

    fun node(
        cls: String = "android.view.View", text: String? = null, desc: String? = null, id: String? = null, hint: String? = null,
        b: Bounds = Bounds(0, 0, 100, 100), clickable: Boolean = false, scrollable: Boolean = false, editable: Boolean = false,
        selected: Boolean = false, focused: Boolean = false,
        onClick: (() -> Unit)? = null, onText: ((String) -> Unit)? = null, children: List<UiNode> = emptyList(),
    ): UiNode {
        val nid = nextId++
        onClick?.let { clicks[nid] = it }
        onText?.let { texts[nid] = it }
        return UiNode(
            id = nid, className = cls, text = text, contentDescription = desc, hintText = hint,
            viewId = id?.let { "app:id/$it" }, bounds = b, clickable = clickable || onClick != null,
            scrollable = scrollable, editable = editable || onText != null, selected = selected, focused = focused, children = children,
        )
    }
}

/**
 * A scripted phone: subclasses render the current screen from their state and
 * mutate state when nodes are tapped. Lets the agent run end-to-end in tests.
 */
abstract class FakePhone : DeviceController, SystemActions {
    val log = ArrayList<String>()
    var foreground: String = "com.android.launcher3"
    protected var ui = UiBuilder()
    private var last: ScreenSnapshot? = null

    abstract fun render(b: UiBuilder): Pair<String, UiNode>
    open fun onSwipe(d: Direction) {}
    open fun onBack() {}
    open fun onUri(uri: String, pkg: String?): Boolean = false

    private fun build(): ScreenSnapshot {
        ui = UiBuilder()
        val (pkg, root) = render(ui)
        return ScreenSnapshot(pkg, root, keyboardVisible = false).also { last = it }
    }

    override suspend fun snapshot(): ScreenSnapshot = build()
    override suspend fun awaitChange(previousFingerprint: Int?, timeoutMs: Long): ScreenSnapshot? {
        yield()
        val s = build()
        return if (s.fingerprint != previousFingerprint) s else null
    }

    private fun act(n: UiNode): Boolean {
        val s = last ?: build()
        val action = ui.clicks[n.id] ?: ui.clicks[s.clickableTarget(n).id] ?: return false
        log += "click:${n.label}"
        action()
        return true
    }

    override suspend fun click(node: UiNode) = act(node)
    override suspend fun longClick(node: UiNode) = act(node)
    override suspend fun tapAt(x: Int, y: Int) = false
    override suspend fun doubleTapAt(x: Int, y: Int) = false
    override suspend fun longPressAt(x: Int, y: Int, durationMs: Long) = false
    override suspend fun swipe(direction: Direction, distanceFraction: Float, durationMs: Long): Boolean { log += "swipe:$direction"; onSwipe(direction); return true }
    override suspend fun scroll(node: UiNode?, direction: Direction): Boolean { log += "scroll:$direction"; return false }
    override suspend fun setText(node: UiNode, text: String): Boolean {
        val f = ui.texts[node.id] ?: return false
        log += "type:$text"; f(text); return true
    }
    override suspend fun pressImeAction(node: UiNode?): Boolean { log += "ime"; return false }
    override suspend fun copy(node: UiNode) = false
    override suspend fun paste(node: UiNode) = false
    override suspend fun setClipboard(text: String) = true
    override suspend fun global(action: GlobalAction): Boolean { log += "global:$action"; if (action == GlobalAction.BACK) onBack(); if (action == GlobalAction.HOME) foreground = "com.android.launcher3"; return true }
    override suspend fun launchApp(packageName: String): Boolean { log += "launch:$packageName"; foreground = packageName; return true }
    override suspend fun openUri(uri: String, packageName: String?): Boolean { log += "uri:$uri"; return onUri(uri, packageName) }
    override suspend fun installedApps() = listOf(
        InstalledApp("Instagram", "com.instagram.android"), InstalledApp("YouTube", "com.google.android.youtube"),
        InstalledApp("WhatsApp", "com.whatsapp"), InstalledApp("Phone", "com.google.android.dialer"), InstalledApp("Spotify", "com.spotify.music"),
    )

    // ---- SystemActions
    var contactsDb: List<Contact> = emptyList()
    override suspend fun contacts(query: String) = contactsDb.filter { it.name.contains(query, true) }
    override suspend fun call(number: String, direct: Boolean): SysResult { log += "call:$number"; return SysResult.Done("") }
    override suspend fun openDialer(number: String?) = SysResult.Done("")
    override suspend fun endCall() = SysResult.Done("")
    override suspend fun sendSms(number: String, text: String): SysResult { log += "sms:$number:$text"; return SysResult.Done("") }
    override suspend fun composeSms(number: String, text: String) = SysResult.Done("")
    override suspend fun setToggle(setting: SystemSetting, on: Boolean?): SysResult { log += "toggle:$setting:$on"; return SysResult.Done("") }
    override suspend fun setVolume(percent: Int, relative: Boolean) = SysResult.Done("")
    override suspend fun setBrightness(percent: Int, relative: Boolean) = SysResult.Done("")
    override suspend fun mediaKey(key: MediaKey): SysResult { log += "media:$key"; return SysResult.Done("") }
    override suspend fun playFromSearch(query: String, packageName: String?): SysResult { log += "play:$query:$packageName"; return SysResult.Done("") }
    override suspend fun openSettings(screen: SettingsScreen, packageName: String?) = SysResult.Done("")
    override suspend fun recentNotifications(limit: Int) = emptyList<PhoneNotification>()
    override suspend fun openNotification(key: String) = SysResult.Done("")
    override suspend fun searchFiles(query: String, mimePrefix: String?, limit: Int) = emptyList<FileItem>()
    override suspend fun openFile(file: FileItem) = SysResult.Done("")
    override suspend fun shareFile(file: FileItem) = SysResult.Done("")
    override suspend fun composeEmail(to: String?, subject: String?, body: String?) = SysResult.Done("")
    override suspend fun batteryPercent() = 80
}

/** Launcher + a simplified Instagram (feed, Reels, comments) + YouTube (results, watch). */
open class SocialPhone : FakePhone() {
    // Instagram state
    var igTab = "feed"
    var reel = 0
    val liked = HashSet<Int>()
    val saved = HashSet<Int>()
    var commentsOpen = false
    var draft = ""
    val comments = HashMap<Int, MutableList<String>>()
    /** When true, tapping Like does nothing visible (to test verification). */
    var brokenLike = false

    // YouTube state
    var ytScreen = "home"
    var ytQuery = ""
    var ytVideo: String? = null
    var ytLiked = false

    override fun onSwipe(d: Direction) {
        if (foreground == "com.instagram.android" && igTab == "reels" && !commentsOpen) {
            if (d == Direction.UP) reel++ else if (reel > 0) reel--
        }
    }

    override fun onBack() {
        when (foreground) {
            "com.instagram.android" -> if (commentsOpen) commentsOpen = false
            "com.google.android.youtube" -> if (ytScreen == "watch") ytScreen = "results" else ytScreen = "home"
        }
    }

    override fun onUri(uri: String, pkg: String?): Boolean {
        if (uri.startsWith("https://www.youtube.com/results")) {
            foreground = "com.google.android.youtube"; ytScreen = "results"
            ytQuery = java.net.URLDecoder.decode(uri.substringAfter("search_query="), "UTF-8"); return true
        }
        return false
    }

    override fun render(b: UiBuilder): Pair<String, UiNode> = when (foreground) {
        "com.instagram.android" -> foreground to instagram(b)
        "com.google.android.youtube" -> foreground to youtube(b)
        else -> foreground to b.node(cls = "android.widget.FrameLayout", text = "Home screen")
    }

    private fun tabs(b: UiBuilder) = b.node(
        id = "tab_bar", b = Bounds(0, 2200, 1080, 2400),
        children = listOf(
            b.node(desc = "Home", id = "feed_tab", b = Bounds(0, 2200, 200, 2400), selected = igTab == "feed", onClick = { igTab = "feed" }),
            b.node(desc = "Reels", id = "clips_tab", b = Bounds(400, 2200, 600, 2400), selected = igTab == "reels", onClick = { igTab = "reels" }),
        ),
    )

    private fun instagram(b: UiBuilder): UiNode {
        val kids = ArrayList<UiNode>()
        if (igTab == "reels") {
            val r = reel
            kids += b.node(id = "clips_viewer_view_pager", b = Bounds(0, 0, 1080, 2200), scrollable = true, children = listOf(
                b.node(text = "@creator$r", id = "clips_author_username", b = Bounds(40, 1900, 400, 1950)),
                b.node(desc = if (r in liked) "Unlike" else "Like", id = "like_button", b = Bounds(980, 1400, 1060, 1480),
                    onClick = { if (!brokenLike) { if (r in liked) liked.remove(r) else liked.add(r) } }),
                b.node(desc = "Comment", id = "comment_button", b = Bounds(980, 1520, 1060, 1600), onClick = { commentsOpen = true }),
                b.node(desc = if (r in saved) "Remove from saved" else "Save", id = "clips_save_button", b = Bounds(980, 1640, 1060, 1720),
                    onClick = { if (r in saved) saved.remove(r) else saved.add(r) }),
            ))
            if (commentsOpen) {
                kids += b.node(id = "comment_sheet", b = Bounds(0, 1000, 1080, 2200), children = (comments[r] ?: emptyList<String>()).mapIndexed { i, c ->
                    b.node(text = c, id = "row_comment_textview_comment", b = Bounds(40, 1100 + i * 80, 1000, 1170))
                } + listOf(
                    b.node(cls = "android.widget.EditText", text = draft.ifEmpty { null }, hint = "Add a comment…", id = "layout_comment_thread_edittext",
                        b = Bounds(40, 2050, 900, 2150), onText = { draft = it }),
                    b.node(text = "Post", id = "layout_comment_thread_post_button", b = Bounds(920, 2050, 1060, 2150),
                        onClick = { if (draft.isNotEmpty()) { comments.getOrPut(r) { mutableListOf() }.add(draft); draft = "" } }),
                ))
            } else kids += tabs(b)
        } else {
            kids += b.node(id = "feed_list", b = Bounds(0, 0, 1080, 2200), scrollable = true, children = listOf(b.node(text = "Your feed", b = Bounds(0, 100, 1080, 200))))
            kids += tabs(b)
        }
        return b.node(cls = "android.widget.FrameLayout", b = Bounds(0, 0, 1080, 2400), children = kids)
    }

    private fun youtube(b: UiBuilder): UiNode = when (ytScreen) {
        "results" -> b.node(b = Bounds(0, 0, 1080, 2400), children = listOf(
            b.node(cls = "android.widget.EditText", text = ytQuery, id = "search_query", b = Bounds(100, 50, 900, 150)),
            b.node(id = "results", scrollable = true, b = Bounds(0, 200, 1080, 2400), children = listOf(
                b.node(b = Bounds(0, 200, 1080, 700), onClick = { ytVideo = "ad"; ytScreen = "watch" }, children = listOf(b.node(text = "Buy cameras now · Sponsored", b = Bounds(20, 600, 1000, 680)))),
                b.node(b = Bounds(0, 700, 1080, 1200), onClick = { ytVideo = "v1"; ytScreen = "watch" }, children = listOf(b.node(text = "Filmmaking basics · 1.2M views · 12:03", b = Bounds(20, 1100, 1000, 1180)))),
                b.node(b = Bounds(0, 1200, 1080, 1700), onClick = { ytVideo = "v2"; ytScreen = "watch" }, children = listOf(b.node(text = "Cinematic shots · 300K views · 8:10", b = Bounds(20, 1600, 1000, 1680)))),
            )),
        ))
        "watch" -> b.node(b = Bounds(0, 0, 1080, 2400), children = listOf(
            b.node(id = "watch_player", b = Bounds(0, 0, 1080, 600)),
            b.node(text = "Now playing $ytVideo", b = Bounds(20, 620, 1000, 700)),
            b.node(desc = if (ytLiked) "Unlike" else "like this video along with 5K other people", id = "like_button", b = Bounds(20, 720, 200, 800), onClick = { ytLiked = !ytLiked }),
        ))
        else -> b.node(b = Bounds(0, 0, 1080, 2400), children = listOf(b.node(desc = "Search", b = Bounds(900, 50, 1000, 150), onClick = { ytScreen = "results" })))
    }
}
