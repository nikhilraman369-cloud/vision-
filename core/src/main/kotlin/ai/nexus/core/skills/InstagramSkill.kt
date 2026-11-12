package ai.nexus.core.skills

import ai.nexus.core.agent.Outcome
import ai.nexus.core.device.Direction
import ai.nexus.core.device.GlobalAction
import ai.nexus.core.nlu.Command
import ai.nexus.core.nlu.Fuzzy
import ai.nexus.core.nlu.IntentParser
import ai.nexus.core.nlu.Reaction
import ai.nexus.core.nlu.Section
import ai.nexus.core.screen.ElementFinder
import ai.nexus.core.screen.Packages
import ai.nexus.core.screen.ScreenKind

/** Instagram: feed, Reels, stories, comments, search, profiles and DMs. */
class InstagramSkill : AppSkill {
    override val packages = setOf(Packages.INSTAGRAM)

    override suspend fun handle(cmd: Command, env: SkillEnv): Outcome? {
        val ops = env.ops
        val s = ops.current() ?: return null
        val kind = ops.kind(s)
        return when (cmd) {
            is Command.GoTo -> when (cmd.section) {
                Section.REELS -> tab(env, ScreenKind.IG_REELS, listOf("clips_tab"), "Reels")
                Section.HOME -> tab(env, ScreenKind.IG_FEED, listOf("feed_tab"), "Home")
                Section.PROFILE -> tab(env, ScreenKind.IG_PROFILE, listOf("profile_tab"), "Profile")
                Section.EXPLORE, Section.SEARCH -> tab(env, ScreenKind.IG_SEARCH, listOf("search_tab"), "Search and explore", "Search")
                Section.MESSAGES, Section.INBOX, Section.CHATS -> openInbox(env)
                else -> null
            }
            Command.NextItem -> when (kind) {
                ScreenKind.IG_REELS -> swipe(env, Direction.UP, "Reel")
                ScreenKind.IG_STORY -> { env.device.tapAt(s.screenWidth * 9 / 10, s.screenHeight / 2); Outcome.Success() }
                ScreenKind.IG_FEED -> { ops.scrollAndWait(Direction.DOWN); Outcome.Success() }
                else -> null
            }
            Command.PreviousItem -> when (kind) {
                ScreenKind.IG_REELS -> swipe(env, Direction.DOWN, "Reel")
                ScreenKind.IG_STORY -> { env.device.tapAt(s.screenWidth / 10, s.screenHeight / 2); Outcome.Success() }
                else -> null
            }
            is Command.React -> when {
                cmd.reaction == Reaction.SAVE && kind == ScreenKind.IG_REELS -> saveReel(env, cmd.on)
                else -> null
            }
            Command.OpenComments -> {
                if (kind == ScreenKind.IG_COMMENTS) return Outcome.Success("Comments are already open.")
                val btn = ElementFinder(s).byViewId("row_feed_button_comment", "comment_button")
                    ?: ElementFinder(s).byLabel("Comment", "Comments", minScore = 0.85)
                    ?: return Outcome.NeedsAgent("I couldn't find the comment button.")
                ops.tap(btn, s) ?: return Outcome.Failed("Comments didn't open.")
                val ok = ops.waitFor(3000) { ops.kind(it) == ScreenKind.IG_COMMENTS || it.nodes.any { n -> n.editable && (n.hintText ?: n.text ?: "").contains("comment", true) } }
                if (ok != null) Outcome.Success() else Outcome.Uncertain("I tapped comments, but couldn't confirm they opened.")
            }
            is Command.Search -> search(cmd.query, env)
            is Command.OpenChat -> openChat(cmd.contact, env)
            is Command.SendMessage -> {
                if (cmd.contact != null && cmd.contact != IntentParser.SAME_CONTACT) {
                    val r = openChat(cmd.contact, env)
                    if (r !is Outcome.Success) return r
                } else if (kind != ScreenKind.IG_DM_THREAD) return Outcome.Failed("Who should I message on Instagram?")
                val text = cmd.text ?: return Outcome.Asked("What should I say?")
                val typed = ops.typeText(text, ElementFinder(ops.current()!!).byViewId("row_thread_composer_edittext"))
                if (typed !is Outcome.Success || !cmd.send) return typed
                ops.submit(text, listOf("Send"), listOf("row_thread_composer_send_button_container", "row_thread_composer_button_send"))
            }
            else -> null
        }
    }

    private suspend fun tab(env: SkillEnv, expect: ScreenKind, ids: List<String>, vararg labels: String): Outcome {
        val ops = env.ops
        var s = ops.current() ?: return Outcome.Failed("I can't see the screen.")
        if (ops.kind(s) == expect) return Outcome.Success()
        var node = ids.firstNotNullOfOrNull { ElementFinder(s).byViewId(it) } ?: ElementFinder(s).byLabel(*labels, requireClickable = true, minScore = 0.85)
        var tries = 0
        while (node == null && tries < 3) {
            // Tab bar is hidden inside comments, DMs and profiles – back out until it shows.
            env.device.global(GlobalAction.BACK)
            s = ops.awaitChange(s) ?: ops.screen() ?: break
            node = ids.firstNotNullOfOrNull { ElementFinder(s).byViewId(it) } ?: ElementFinder(s).byLabel(*labels, requireClickable = true, minScore = 0.85)
            tries++
        }
        node ?: return Outcome.NeedsAgent("I couldn't find the ${labels.first()} tab.")
        ops.tap(node, s) ?: return Outcome.Failed("The ${labels.first()} tab didn't open.")
        val ok = ops.waitFor(3000) { ops.kind(it) == expect }
        return if (ok != null) Outcome.Success() else Outcome.Uncertain("I tapped ${labels.first()}, but the screen looks different than expected.")
    }

    private suspend fun swipe(env: SkillEnv, dir: Direction, what: String): Outcome {
        val before = env.ops.current()
        val after = env.ops.swipeAndWait(dir)
        if (after == null || after.fingerprint == before?.fingerprint) return Outcome.Uncertain("I swiped, but the $what didn't change.")
        env.ctx.currentItem = "$what: " + (after.nodes.firstOrNull { it.shortViewId?.contains("username") == true }?.label ?: "")
        return Outcome.Success()
    }

    private suspend fun saveReel(env: SkillEnv, on: Boolean): Outcome? {
        val ops = env.ops
        // Newer Reels UIs keep Save in the ⋯ menu; older ones have a bookmark button.
        val direct = ops.setReaction(Reaction.SAVE, on, "save")
        if (direct !is Outcome.NeedsAgent) return direct
        val s = ops.current() ?: return null
        val more = ElementFinder(s).byLabel("More", "More options", "Options", minScore = 0.85) ?: return direct
        val menu = ops.tap(more, s) ?: return direct
        val item = ElementFinder(menu).byLabel(if (on) "Save" else "Unsave", "Remove from saved", minScore = 0.85) ?: run {
            env.device.global(GlobalAction.BACK)
            return Outcome.Success("It's already ${if (on) "saved" else "not saved"}.")
        }
        ops.tap(item, menu) ?: return Outcome.Failed("The save option didn't respond.")
        return Outcome.Success()
    }

    private suspend fun search(query: String, env: SkillEnv): Outcome {
        val ops = env.ops
        val t = tab(env, ScreenKind.IG_SEARCH, listOf("search_tab"), "Search and explore", "Search")
        if (t is Outcome.Failed) return t
        val s = ops.current() ?: return Outcome.Failed("I can't see the screen.")
        val field = ElementFinder(s).byViewId("action_bar_search_edit_text") ?: ElementFinder(s).firstEditable()
            ?: ElementFinder(s).byLabel("Search", minScore = 0.85)?.let { ops.tap(it, s); ElementFinder(ops.current()!!).firstEditable() }
            ?: return Outcome.NeedsAgent("I couldn't find Instagram's search box.")
        val typed = ops.typeText(query, field)
        if (typed is Outcome.Failed) return typed
        ops.waitFor(3000) { snap -> ElementFinder(snap).listItems().isNotEmpty() }
        env.ctx.lastSearchQuery = query; env.ctx.lastSearchApp = "Instagram"
        return Outcome.Success()
    }

    private suspend fun openInbox(env: SkillEnv): Outcome {
        val ops = env.ops
        val s = ops.current() ?: return Outcome.Failed("I can't see the screen.")
        if (ops.kind(s) == ScreenKind.IG_DM_INBOX) return Outcome.Success()
        val f = ElementFinder(s)
        val btn = f.byViewId("action_bar_inbox_button", "direct_tab") ?: f.byLabel("Messenger", "Message", "Direct", "Messages", requireClickable = true, minScore = 0.85)
        if (btn == null) {
            val home = tab(env, ScreenKind.IG_FEED, listOf("feed_tab"), "Home")
            if (home is Outcome.Failed) return home
            val s2 = ops.current() ?: return Outcome.Failed("I can't see the screen.")
            val b2 = ElementFinder(s2).byViewId("action_bar_inbox_button") ?: ElementFinder(s2).byLabel("Messenger", "Message", "Direct", minScore = 0.85)
                ?: return Outcome.NeedsAgent("I couldn't find the messages button.")
            ops.tap(b2, s2)
        } else ops.tap(btn, s)
        val ok = ops.waitFor(3000) { ops.kind(it) == ScreenKind.IG_DM_INBOX }
        return if (ok != null) Outcome.Success() else Outcome.Uncertain("I tapped messages, but couldn't confirm the inbox opened.")
    }

    private suspend fun openChat(name: String?, env: SkillEnv): Outcome {
        val who = (if (name == IntentParser.SAME_CONTACT) env.ctx.currentContactName else name) ?: return Outcome.Asked("Whose chat should I open?")
        val inbox = openInbox(env)
        if (inbox is Outcome.Failed) return inbox
        val ops = env.ops
        var s = ops.current() ?: return Outcome.Failed("I can't see the screen.")
        // Visible already?
        val row = s.nodes.firstOrNull { n -> !n.editable && n.text != null && Fuzzy.similarity(n.text, who) > 0.8 || n.text?.contains(who, true) == true && !n.editable }
        if (row == null) {
            val search = ElementFinder(s).byLabel("Search", requireClickable = true, minScore = 0.85)
                ?: return Outcome.NeedsAgent("I couldn't find search in your messages.")
            s = ops.tap(search, s) ?: return Outcome.Failed("Search didn't open.")
            val typed = ops.typeText(who)
            if (typed is Outcome.Failed) return typed
            s = ops.waitFor(3000) { snap -> snap.nodes.any { !it.editable && it.text?.contains(who, true) == true } }
                ?: return Outcome.Failed("I couldn't find $who in your Instagram messages.")
        }
        val target = s.nodes.firstOrNull { !it.editable && it.text?.contains(who, true) == true } ?: return Outcome.Failed("I couldn't find $who.")
        ops.tap(target, s) ?: return Outcome.Failed("The chat didn't open.")
        val ok = ops.waitFor(3000) { ops.kind(it) == ScreenKind.IG_DM_THREAD }
        env.ctx.currentContactName = who; env.ctx.currentChatApp = Packages.INSTAGRAM
        return if (ok != null) Outcome.Success() else Outcome.Uncertain("I opened something, but I'm not sure it's the chat with $who.")
    }
}
