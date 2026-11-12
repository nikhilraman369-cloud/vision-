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

    private val videoDesc = Regex("\\b(?:views?|minutes?|seconds?|hours?|play video|watching|play short|\\d{1,2}:\\d{2})\\b", RegexOption.IGNORE_CASE)

    /** Video / Short cards on screen, top to bottom, ads removed. */
    fun cards(s: ScreenSnapshot, shorts: Boolean): List<UiNode> {
        val h = s.screenHeight
        // YouTube describes each card in one contentDescription ("Title - 12 minutes - Channel - 1M views - play video").
        val described = s.nodes.filter { n ->
            val d = n.contentDescription ?: return@filter false
            n.bounds.area > 0 && n.bounds.centerY > h * 0.08 && videoDesc.containsMatchIn(d) &&
                !d.contains("sponsored", true) && !d.startsWith("Ad ", true) &&
                (if (shorts) d.contains("short", true) || n.bounds.height > n.bounds.width else !d.contains("play short", true))
        }
        val outer = described.filter { a -> described.none { b -> b.id != a.id && b.bounds.contains(a.bounds) && b.bounds != a.bounds } }
        if (outer.isNotEmpty()) return outer.sortedWith(compareBy({ it.bounds.top / 40 }, { it.bounds.left }))
        return ElementFinder(s).listItems().filter { if (shorts) it.allText().contains("short", true) || it.bounds.height > it.bounds.width else isVideoCard(it) }
    }

    private suspend fun openResult(cmd: Command.OpenResult, s: ScreenSnapshot, kind: ScreenKind, env: SkillEnv): Outcome? {
        if (kind == ScreenKind.YT_WATCH && cmd.kind == null) return null
        val wantShort = cmd.kind?.startsWith("short") == true
        var snap = s
        var list = cards(snap, wantShort)
        // Not enough on screen yet (e.g. "the 5th video")? Scroll a little and look again.
        var tries = 0
        while ((if (cmd.ordinal < 0) list.isEmpty() else list.size < cmd.ordinal) && tries < 3) {
            env.ops.scrollAndWait(ai.nexus.core.device.Direction.DOWN)
            snap = env.ops.current() ?: break
            list = (list + cards(snap, wantShort)).distinctBy { it.contentDescription ?: it.allText() }
            tries++
        }
        val target = (if (cmd.ordinal < 0) list.lastOrNull() else list.getOrNull(cmd.ordinal - 1))
            ?: return Outcome.NeedsAgent("I couldn't find ${if (wantShort) "Short" else "video"} number ${cmd.ordinal}.")
        val fresh = env.ops.current() ?: snap
        val node = fresh.nodes.firstOrNull { it.contentDescription != null && it.contentDescription == target.contentDescription } ?: target
        env.ctx.currentItem = "video: " + (target.contentDescription ?: target.allText()).take(80)
        env.ops.tap(node, fresh) ?: return Outcome.Failed("The video didn't open.")
        val opened = env.ops.waitFor(5000) { ops -> env.ops.kind(ops) in setOf(ScreenKind.YT_WATCH, ScreenKind.YT_SHORTS) || ops.fingerprint != fresh.fingerprint }
        return if (opened != null) Outcome.Success() else Outcome.Uncertain("I tapped the video, but I'm not sure the player opened.")
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
