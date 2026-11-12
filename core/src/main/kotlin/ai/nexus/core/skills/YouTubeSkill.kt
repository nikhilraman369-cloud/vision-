package ai.nexus.core.skills

import ai.nexus.core.agent.Outcome
import ai.nexus.core.device.Direction
import ai.nexus.core.nlu.Command
import ai.nexus.core.nlu.Reaction
import ai.nexus.core.nlu.Section
import ai.nexus.core.screen.ElementFinder
import ai.nexus.core.screen.Packages
import ai.nexus.core.screen.ScreenKind
import ai.nexus.core.screen.ScreenSnapshot
import ai.nexus.core.screen.UiNode
import java.net.URLEncoder

/** YouTube: home, search results, watch page, Shorts and comments are handled differently. */
class YouTubeSkill : AppSkill {
    override val packages = setOf(Packages.YOUTUBE)

    override suspend fun handle(cmd: Command, env: SkillEnv): Outcome? {
        val ops = env.ops
        val s = ops.current() ?: return null
        val kind = ops.kind(s)
        return when (cmd) {
            is Command.Search -> search(cmd.query, env)
            is Command.OpenResult -> openResult(cmd, s, kind, env)
            is Command.GoTo -> when (cmd.section) {
                Section.SHORTS -> tab(env, "Shorts")
                Section.HOME -> tab(env, "Home")
                Section.SUBSCRIPTIONS -> tab(env, "Subscriptions")
                Section.LIBRARY, Section.HISTORY -> tab(env, "You", "Library")
                else -> null
            }
            Command.NextItem -> when (kind) {
                ScreenKind.YT_SHORTS -> swipeNext(env, Direction.UP)
                ScreenKind.YT_WATCH -> {
                    ops.tapLabel("Next video", "Play next")?.let { Outcome.Success() }
                        ?: openUpNext(s, env)
                }
                else -> null
            }
            Command.PreviousItem -> if (kind == ScreenKind.YT_SHORTS) swipeNext(env, Direction.DOWN) else null
            Command.OpenComments -> openComments(s, kind, env)
            is Command.React -> if (cmd.reaction == Reaction.SAVE) save(env) else null
            else -> null
        }
    }

    private suspend fun search(query: String, env: SkillEnv): Outcome {
        val ops = env.ops
        val url = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8")
        if (env.device.openUri(url, Packages.YOUTUBE)) {
            val res = ops.waitFor(5000) { it.packageName == Packages.YOUTUBE && (it.containsText(query) || ops.kind(it) == ScreenKind.YT_RESULTS) }
            if (res != null) {
                env.ctx.lastSearchQuery = query; env.ctx.lastSearchApp = "YouTube"
                return Outcome.Success()
            }
        }
        // Fall back to the UI: search button → field → type → enter.
        ops.tapLabel("Search", "Search YouTube") ?: return Outcome.NeedsAgent("I couldn't open YouTube search.")
        val typed = ops.typeText(query)
        if (typed !is Outcome.Success) return typed
        env.device.pressImeAction(null)
        ops.waitFor(4000) { ops.kind(it) == ScreenKind.YT_RESULTS && !it.keyboardVisible }
        env.ctx.lastSearchQuery = query; env.ctx.lastSearchApp = "YouTube"
        return Outcome.Success()
    }

    private fun isVideoCard(n: UiNode): Boolean {
        val t = n.allText().lowercase()
        if (t.contains("sponsored") || t.startsWith("ad ") || t.contains(" ad ·") || t.contains("· ad")) return false
        return t.contains("views") || t.contains("view") || Regex("\\b\\d{1,2}:\\d{2}\\b").containsMatchIn(t) || t.contains("watching")
    }

    private suspend fun openResult(cmd: Command.OpenResult, s: ScreenSnapshot, kind: ScreenKind, env: SkillEnv): Outcome? {
        if (kind != ScreenKind.YT_RESULTS && kind != ScreenKind.YT_HOME && kind != ScreenKind.YT_SEARCH) return null
        val f = ElementFinder(s)
        val wantShort = cmd.kind?.startsWith("short") == true
        val candidates = f.listItems().filter { if (wantShort) it.allText().contains("short", true) else isVideoCard(it) }
        val target = (if (cmd.ordinal < 0) candidates.lastOrNull() else candidates.getOrNull(cmd.ordinal - 1))
            ?: return Outcome.NeedsAgent("I couldn't find result number ${cmd.ordinal}.")
        env.ctx.currentItem = "video: " + target.allText().take(80)
        val after = env.ops.tap(target, s) ?: return Outcome.Failed("The video didn't open.")
        val opened = env.ops.waitFor(5000) { ops -> env.ops.kind(ops) in setOf(ScreenKind.YT_WATCH, ScreenKind.YT_SHORTS) } ?: after
        return if (env.ops.kind(opened) in setOf(ScreenKind.YT_WATCH, ScreenKind.YT_SHORTS)) Outcome.Success()
        else Outcome.Uncertain("I tapped the video, but I'm not sure the player opened.")
    }

    private suspend fun openUpNext(s: ScreenSnapshot, env: SkillEnv): Outcome? {
        val items = ElementFinder(s).listItems().filter(::isVideoCard)
        val next = items.firstOrNull { it.bounds.top > s.screenHeight / 3 } ?: return null
        env.ops.tap(next, s) ?: return Outcome.Failed("The next video didn't open.")
        return Outcome.Success()
    }

    private suspend fun swipeNext(env: SkillEnv, dir: Direction): Outcome {
        val before = env.ops.current()
        val after = env.ops.swipeAndWait(dir)
        return if (after != null && after.fingerprint != before?.fingerprint) Outcome.Success()
        else Outcome.Uncertain("I swiped, but the Short didn't seem to change.")
    }

    private suspend fun tab(env: SkillEnv, vararg labels: String): Outcome? {
        val s = env.ops.current() ?: return null
        val f = ElementFinder(s)
        val node = f.byLabel(*labels, requireClickable = true, minScore = 0.85) ?: run {
            // Tabs are hidden on the watch page – go back to the main screen first.
            env.device.global(ai.nexus.core.device.GlobalAction.BACK)
            env.ops.awaitChange(s)
            env.ops.current()?.let { ElementFinder(it).byLabel(*labels, requireClickable = true, minScore = 0.85) }
        } ?: return Outcome.NeedsAgent("I couldn't find the ${labels.first()} tab.")
        env.ops.tap(node) ?: return Outcome.Failed("The ${labels.first()} tab didn't open.")
        return Outcome.Success()
    }

    private suspend fun openComments(s: ScreenSnapshot, kind: ScreenKind, env: SkillEnv): Outcome? {
        if (kind == ScreenKind.YT_COMMENTS) return Outcome.Success("Comments are already open.")
        val f = ElementFinder(s)
        val node = when (kind) {
            ScreenKind.YT_SHORTS -> f.byViewIdContains("comment") ?: f.byLabel("Comments", "View comments", "comment", minScore = 0.8)
            else -> f.byViewId("comments_entry_point_header") ?: f.byLabel("Comments", minScore = 0.85)
                ?: run {
                    // The comments teaser sits below the description – scroll it into view once.
                    env.ops.scrollAndWait(Direction.DOWN)
                    env.ops.current()?.let { ElementFinder(it).byLabel("Comments", minScore = 0.85) }
                }
        } ?: return Outcome.NeedsAgent("I couldn't find the comments button.")
        env.ops.tap(node, env.ops.current()) ?: return Outcome.Failed("Comments didn't open.")
        val ok = env.ops.waitFor(3000) { it.nodes.any { n -> n.text?.contains("Add a comment", true) == true || n.hintText?.contains("comment", true) == true || n.contentDescription?.contains("Add a comment", true) == true } }
        return if (ok != null) Outcome.Success() else Outcome.Uncertain("I opened something, but couldn't confirm it's the comments.")
    }

    private suspend fun save(env: SkillEnv): Outcome? {
        // YouTube's Save lives in the action row (watch page) or the ⋮ menu (Shorts).
        env.ops.tapLabel("Save", "Save to playlist")?.let { after ->
            // A playlist sheet appears; "Watch later" is the default choice.
            ElementFinder(after).byLabel("Watch later", minScore = 0.85)?.let { wl -> env.ops.tap(wl, after) }
            return Outcome.Success("Saved to Watch later.")
        }
        val more = env.ops.tapLabel("More actions", "More options", "More") ?: return null
        val save = ElementFinder(more).byLabel("Save to playlist", "Save", minScore = 0.8) ?: return null
        val sheet = env.ops.tap(save, more) ?: return Outcome.Failed("The save option didn't respond.")
        ElementFinder(sheet).byLabel("Watch later", minScore = 0.85)?.let { env.ops.tap(it, sheet) }
        return Outcome.Success("Saved to Watch later.")
    }
}
