package ai.nexus.core.skills

import ai.nexus.core.agent.Outcome
import ai.nexus.core.device.Direction
import ai.nexus.core.device.GlobalAction
import ai.nexus.core.nlu.Command
import ai.nexus.core.nlu.Reaction
import ai.nexus.core.nlu.Section
import ai.nexus.core.screen.ElementFinder
import ai.nexus.core.screen.ScreenKind
import ai.nexus.core.screen.ScreenSnapshot
import ai.nexus.core.screen.UiNode

/**
 * Works in any app by reading its interface: labels, roles, lists and
 * fields. Dedicated skills improve reliability; this keeps everything else
 * usable.
 */
class GenericSkill : AppSkill {
    override val packages: Set<String> = emptySet()

    override suspend fun handle(cmd: Command, env: SkillEnv): Outcome? {
        val ops = env.ops
        val s = ops.current() ?: return Outcome.Failed("I can't see the screen. Is the NEXUS accessibility service turned on?")
        val f = ElementFinder(s)
        return when (cmd) {
            is Command.Navigate -> {
                if (!env.device.global(cmd.action)) return Outcome.Failed("Android didn't allow that.")
                ops.awaitChange(s, 1500)
                Outcome.Success()
            }
            is Command.Scroll -> {
                var moved = false
                repeat(cmd.times) {
                    val (ok, _) = ops.scrollAndWait(cmd.direction)
                    if (ok) moved = true else return if (moved) Outcome.Success("Reached the end.") else Outcome.Uncertain("The screen didn't scroll — maybe it's already at the end.")
                }
                Outcome.Success()
            }
            Command.NextItem, Command.PreviousItem -> {
                val next = cmd == Command.NextItem
                val label = if (next) listOf("Next", "Next video", "Next page", "Skip") else listOf("Previous", "Back")
                f.byLabel(*label.toTypedArray(), requireClickable = true, minScore = 0.9)?.let { ops.tap(it, s); return Outcome.Success() }
                val scrollable = f.largestScrollable()
                val horizontal = scrollable != null && scrollable.bounds.width > scrollable.bounds.height * 1.3
                val (ok, _) = if (horizontal) ops.scrollAndWait(if (next) Direction.RIGHT else Direction.LEFT)
                else ops.scrollAndWait(if (next) Direction.DOWN else Direction.UP)
                if (ok) Outcome.Success() else Outcome.Uncertain("I tried to move to the ${if (next) "next" else "previous"} item, but nothing changed.")
            }
            is Command.React -> ops.setReaction(cmd.reaction, cmd.on, cmd.reaction.name.lowercase())
            Command.OpenComments -> {
                if (s.nodes.any { it.editable && (it.hintText ?: it.text ?: "").contains("comment", true) }) return Outcome.Success("Comments are already open.")
                val btn = f.byLabel("Comments", "Comment", "View comments", "View all comments", minScore = 0.8)
                    ?: return Outcome.NeedsAgent("I couldn't find comments on this screen.")
                ops.tap(btn, s) ?: return Outcome.Failed("Comments didn't open.")
                Outcome.Success()
            }
            Command.CloseOverlay -> {
                f.byLabel("Close", "Dismiss", "Not now", "Cancel", "Skip", "No thanks", requireClickable = true, minScore = 0.9)?.let { ops.tap(it, s); return Outcome.Success() }
                env.device.global(GlobalAction.BACK); ops.awaitChange(s, 1200)
                Outcome.Success()
            }
            is Command.Write -> write(cmd, s, env)
            Command.Submit -> ops.submit(null)
            is Command.Search -> search(cmd.query, s, env)
            is Command.OpenResult -> openResult(cmd, s, env)
            is Command.GoTo -> goTo(cmd.section, s, env)
            is Command.Tap -> tap(cmd, s, env)
            is Command.FindOnScreen -> {
                val n = f.byLabel(cmd.label, minScore = 0.7) ?: f.byLabel(cmd.label.substringBeforeLast(' '), minScore = 0.75)
                    ?: return Outcome.Failed("I don't see ${cmd.label} on this screen.")
                Outcome.Success("It's ${where(n, s)}: \"${n.label}\".")
            }
            Command.ReadScreen -> {
                val lines = s.readableText()
                if (lines.isEmpty()) Outcome.NeedsAgent("There's no readable text here.")
                else Outcome.Success(lines.joinToString(". ").take(1800))
            }
            Command.Share -> {
                val btn = f.byLabel("Share", "Send", "Share via", requireClickable = true, minScore = 0.85) ?: return Outcome.NeedsAgent("I couldn't find a share button.")
                ops.tap(btn, s) ?: return Outcome.Failed("Share didn't open.")
                Outcome.Success("The share options are open. Who or where should I share it to?")
            }
            Command.Copy -> {
                val target = f.byLabel("Copy", "Copy link", "Copy text", requireClickable = true, minScore = 0.85)
                if (target != null) { ops.tap(target, s); Outcome.Success("Copied.") }
                else {
                    val field = s.focusedField() ?: s.nodes.firstOrNull { it.selected && !it.text.isNullOrBlank() }
                    if (field != null && env.device.copy(field)) Outcome.Success("Copied.")
                    else Outcome.NeedsAgent("I couldn't find anything to copy.")
                }
            }
            Command.Paste -> {
                val field = f.firstEditable() ?: return Outcome.Failed("There's no text box to paste into.")
                if (env.device.paste(field)) Outcome.Success() else Outcome.Failed("Paste didn't work here.")
            }
            is Command.OpenChat -> openByName(cmd.contact ?: return Outcome.Asked("Whose chat?"), s, env)
            is Command.SendMessage -> {
                if (cmd.contact != null && cmd.contact != ai.nexus.core.nlu.IntentParser.SAME_CONTACT) {
                    val r = openByName(cmd.contact, s, env)
                    if (r !is Outcome.Success) return r
                }
                val text = cmd.text ?: return Outcome.Asked("What should the message say?")
                val typed = ops.typeText(text)
                if (typed !is Outcome.Success || !cmd.send) typed else ops.submit(text)
            }
            else -> null
        }
    }

    private fun where(n: UiNode, s: ScreenSnapshot): String {
        val v = when { n.bounds.centerY < s.screenHeight / 3 -> "at the top"; n.bounds.centerY > s.screenHeight * 2 / 3 -> "at the bottom"; else -> "in the middle" }
        val h = when { n.bounds.centerX < s.screenWidth / 3 -> " left"; n.bounds.centerX > s.screenWidth * 2 / 3 -> " right"; else -> "" }
        return v + h
    }

    private suspend fun write(cmd: Command.Write, s: ScreenSnapshot, env: SkillEnv): Outcome {
        val ops = env.ops
        val f = ElementFinder(s)
        // Prefer a comment/message composer, else focused field, else the lowest field.
        var field = s.editableFields().firstOrNull { (it.hintText ?: it.text ?: "").contains("comment", true) || (it.hintText ?: "").contains("message", true) }
            ?: f.firstEditable()
        if (field == null) {
            // Many apps show a fake "Add a comment…" text that turns into a field when tapped.
            val opener = f.byLabel("Add a comment", "Add a comment…", "Write a comment", "Message", "Reply", "Type a message", minScore = 0.75)
                ?: return Outcome.NeedsAgent("There's no text box to type in.")
            val after = ops.tap(opener, s) ?: return Outcome.Failed("The text box didn't open.")
            field = ElementFinder(after).firstEditable() ?: return Outcome.NeedsAgent("There's no text box to type in.")
        }
        val typed = ops.typeText(cmd.text, field)
        if (typed is Outcome.Failed) return typed
        val kind = env.ctx.currentScreen
        val isComment = (field.hintText ?: "").contains("comment", true) || kind == ScreenKind.IG_COMMENTS || kind == ScreenKind.YT_COMMENTS
        val submit = cmd.submit ?: isComment
        if (!submit) return if (typed is Outcome.Success) Outcome.Success("Typed it. Say \"send\" when you want it sent.") else typed
        return ops.submit(cmd.text, listOf("Post", "Send", "Reply", "Comment", "Send comment", "Post comment"), listOf("layout_comment_thread_post_button", "layout_comment_thread_post_button_click_area"))
    }

    private suspend fun search(query: String, s: ScreenSnapshot, env: SkillEnv): Outcome {
        val ops = env.ops
        var f = ElementFinder(s)
        var field = s.editableFields().firstOrNull { (it.hintText ?: it.text ?: it.contentDescription ?: "").contains("search", true) }
        if (field == null) {
            val btn = f.byLabel("Search", "Search or type URL", "Search or type web address", requireClickable = true, minScore = 0.75)
                ?: return Outcome.NeedsAgent("I couldn't find a search option here.")
            val after = ops.tap(btn, s) ?: return Outcome.Failed("Search didn't open.")
            f = ElementFinder(after)
            field = f.firstEditable() ?: return Outcome.NeedsAgent("Search opened but I can't find the box.")
        }
        val typed = ops.typeText(query, field)
        if (typed is Outcome.Failed) return typed
        val before = ops.current()
        env.device.pressImeAction(field)
        ops.awaitChange(before, 4000)
        env.ctx.lastSearchQuery = query; env.ctx.lastSearchApp = env.ctx.currentPackage
        return Outcome.Success()
    }

    private suspend fun openResult(cmd: Command.OpenResult, s: ScreenSnapshot, env: SkillEnv): Outcome {
        val items = ElementFinder(s).listItems()
            .filterNot { it.allText().contains("sponsored", true) }
        val target = (if (cmd.ordinal < 0) items.lastOrNull() else items.getOrNull(cmd.ordinal - 1))
            ?: return Outcome.NeedsAgent("I couldn't find item ${cmd.ordinal} in a list here.")
        env.ctx.currentItem = target.allText().take(100)
        env.ops.tap(target, s) ?: return Outcome.Failed("It didn't open.")
        return Outcome.Success()
    }

    private suspend fun goTo(section: Section, s: ScreenSnapshot, env: SkillEnv): Outcome {
        val label = section.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
        val alts = when (section) {
            Section.MESSAGES -> listOf("Messages", "Chats", "Inbox", "Direct")
            Section.PROFILE -> listOf("Profile", "Account", "Me", "You")
            Section.HOME -> listOf("Home", "Feed")
            Section.LIBRARY -> listOf("Library", "You", "Saved")
            else -> listOf(label)
        }
        val n = ElementFinder(s).byLabel(*alts.toTypedArray(), requireClickable = true, minScore = 0.85)
            ?: return Outcome.NeedsAgent("I couldn't find $label here.")
        env.ops.tap(n, s) ?: return Outcome.Failed("$label didn't open.")
        return Outcome.Success()
    }

    private suspend fun tap(cmd: Command.Tap, s0: ScreenSnapshot, env: SkillEnv): Outcome {
        var s = s0
        var ranked = ElementFinder(s).rankByLabel(listOf(cmd.label)).filter { it.score >= 0.6 }
        // Not visible? Scroll down a few times looking for it.
        var tries = 0
        while (ranked.isEmpty() && tries < 3 && ElementFinder(s).largestScrollable() != null) {
            val (moved, after) = env.ops.scrollAndWait(Direction.DOWN)
            if (!moved || after == null) break
            s = after
            ranked = ElementFinder(s).rankByLabel(listOf(cmd.label)).filter { it.score >= 0.6 }
            tries++
        }
        val match = (cmd.ordinal?.let { ranked.getOrNull(it - 1) } ?: ranked.firstOrNull())
            ?: return Outcome.NeedsAgent("I don't see \"${cmd.label}\" on the screen.")
        val ok = if (cmd.long) env.device.longClick(match.node) || env.device.longPressAt(match.node.bounds.centerX, match.node.bounds.centerY)
        else env.ops.tap(match.node, s) != null
        if (cmd.long) env.ops.awaitChange(s, 1500)
        return if (ok) Outcome.Success() else Outcome.Failed("That didn't respond to a tap.")
    }

    private suspend fun openByName(name: String, s: ScreenSnapshot, env: SkillEnv): Outcome {
        val n = ElementFinder(s).byLabel(name, minScore = 0.75)
        if (n != null) { env.ops.tap(n, s); env.ctx.currentContactName = name; return Outcome.Success() }
        val r = search(name, s, env)
        if (r !is Outcome.Success) return r
        val after = env.ops.current() ?: return r
        val hit = ElementFinder(after).rankByLabel(listOf(name)).firstOrNull { !it.node.editable && it.score >= 0.75 }?.node
            ?: return Outcome.NeedsAgent("I searched for $name but couldn't pick the right result.")
        env.ops.tap(hit, after)
        env.ctx.currentContactName = name
        return Outcome.Success()
    }
}
