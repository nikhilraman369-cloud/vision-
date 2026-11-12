package ai.nexus.core.screen

/**
 * What kind of screen is showing. Apps lay out the same features very
 * differently on different screens (a YouTube Short is not a YouTube watch
 * page; Instagram Reels are not the feed), so skills branch on this.
 */
enum class ScreenKind {
    UNKNOWN, LAUNCHER, DIALOG, LOADING,
    YT_HOME, YT_SEARCH, YT_RESULTS, YT_WATCH, YT_SHORTS, YT_COMMENTS,
    IG_FEED, IG_REELS, IG_STORY, IG_COMMENTS, IG_SEARCH, IG_PROFILE, IG_DM_INBOX, IG_DM_THREAD,
    WA_CHAT_LIST, WA_CHAT, WA_SEARCH,
    BROWSER, SEARCH_RESULTS_WEB, MUSIC_PLAYER, DIALER, MESSAGES_LIST, MESSAGES_THREAD, EMAIL_LIST, EMAIL_COMPOSE, EMAIL_READ,
    SETTINGS, FILES,
}

object Packages {
    const val YOUTUBE = "com.google.android.youtube"
    const val INSTAGRAM = "com.instagram.android"
    const val WHATSAPP = "com.whatsapp"
    const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    const val CHROME = "com.android.chrome"
    const val GMAIL = "com.google.android.gm"
    const val SPOTIFY = "com.spotify.music"
    const val YT_MUSIC = "com.google.android.apps.youtube.music"
    const val SETTINGS = "com.android.settings"
    const val SYSTEM_UI = "com.android.systemui"
    val LAUNCHERS = setOf(
        "com.google.android.apps.nexuslauncher", "com.android.launcher3", "com.sec.android.app.launcher",
        "com.miui.home", "com.oppo.launcher", "net.oneplus.launcher", "com.huawei.android.launcher",
    )
    val DIALERS = setOf("com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer")
    val SMS = setOf("com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging")
    val FILES = setOf("com.google.android.apps.nbu.files", "com.android.documentsui", "com.sec.android.app.myfiles", "com.mi.android.globalFileexplorer")
}

object ScreenClassifier {

    fun classify(s: ScreenSnapshot): ScreenKind = when (s.packageName) {
        Packages.YOUTUBE -> youtube(s)
        Packages.INSTAGRAM -> instagram(s)
        Packages.WHATSAPP, Packages.WHATSAPP_BUSINESS -> whatsapp(s)
        Packages.CHROME, "org.mozilla.firefox", "com.brave.browser", "com.microsoft.emmx" -> ScreenKind.BROWSER
        Packages.SPOTIFY, Packages.YT_MUSIC -> ScreenKind.MUSIC_PLAYER
        Packages.GMAIL -> gmail(s)
        Packages.SETTINGS -> ScreenKind.SETTINGS
        in Packages.LAUNCHERS -> ScreenKind.LAUNCHER
        in Packages.DIALERS -> ScreenKind.DIALER
        in Packages.SMS -> if (s.editableFields().isNotEmpty() && s.hasViewId("compose_message_text", "embedded_text_editor")) ScreenKind.MESSAGES_THREAD else ScreenKind.MESSAGES_LIST
        in Packages.FILES -> ScreenKind.FILES
        else -> generic(s)
    }

    private fun generic(s: ScreenSnapshot): ScreenKind {
        if (s.nodes.size <= 3 && s.nodes.any { it.simpleClass.contains("ProgressBar") }) return ScreenKind.LOADING
        if (s.hasViewId("alertTitle", "message", "button1") && s.nodes.size < 25) return ScreenKind.DIALOG
        return ScreenKind.UNKNOWN
    }

    private fun youtube(s: ScreenSnapshot): ScreenKind = when {
        s.hasViewId("reel_player_page_container", "reel_recycler", "reel_watch_player", "shorts_player") ||
            s.nodes.any { it.contentDescription?.startsWith("Shorts", true) == true && it.selected } &&
            s.nodes.any { it.shortViewId?.startsWith("reel_") == true } -> ScreenKind.YT_SHORTS
        s.hasViewId("comments_entry_point_header", "engagement_panel") &&
            s.containsText("Comments") && s.nodes.any { it.hintText?.contains("comment", true) == true || it.text?.contains("Add a comment", true) == true } -> ScreenKind.YT_COMMENTS
        s.hasViewId("watch_player", "player_view", "watch_while_layout", "watch_list") -> ScreenKind.YT_WATCH
        s.hasViewId("search_edit_text") && s.keyboardVisible -> ScreenKind.YT_SEARCH
        s.hasViewId("search_edit_text", "search_query", "results") || s.nodes.any { it.shortViewId == "search_query" } -> ScreenKind.YT_RESULTS
        else -> ScreenKind.YT_HOME
    }

    private fun instagram(s: ScreenSnapshot): ScreenKind = when {
        s.hasViewId("reel_viewer_root", "reel_viewer_media_layout") -> ScreenKind.IG_STORY
        s.hasViewId("layout_comment_thread_edittext", "comment_composer_text_view") -> ScreenKind.IG_COMMENTS
        s.hasViewId("row_thread_composer_edittext", "direct_thread_composer") -> ScreenKind.IG_DM_THREAD
        s.hasViewId("inbox_refreshable_thread_list_recyclerview", "direct_inbox_container") -> ScreenKind.IG_DM_INBOX
        s.hasViewId("clips_viewer_view_pager", "clips_video_container", "clips_viewer_fragment_container") -> ScreenKind.IG_REELS
        s.hasViewId("action_bar_search_edit_text") -> ScreenKind.IG_SEARCH
        s.hasViewId("profile_header_container", "row_profile_header") -> ScreenKind.IG_PROFILE
        s.nodes.any { it.selected && it.contentDescription.equals("Reels", true) } -> ScreenKind.IG_REELS
        else -> ScreenKind.IG_FEED
    }

    private fun whatsapp(s: ScreenSnapshot): ScreenKind = when {
        s.hasViewId("entry", "conversation_entry") -> ScreenKind.WA_CHAT
        s.hasViewId("search_input", "search_src_text") -> ScreenKind.WA_SEARCH
        else -> ScreenKind.WA_CHAT_LIST
    }

    private fun gmail(s: ScreenSnapshot): ScreenKind = when {
        s.hasViewId("compose_area", "subject", "composearea_tap_trap_bottom") -> ScreenKind.EMAIL_COMPOSE
        s.hasViewId("conversation_container", "message_header") -> ScreenKind.EMAIL_READ
        else -> ScreenKind.EMAIL_LIST
    }
}
