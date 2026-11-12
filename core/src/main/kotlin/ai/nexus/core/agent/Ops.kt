package ai.nexus.core.agent

import ai.nexus.core.device.DeviceController
import ai.nexus.core.device.Direction
import ai.nexus.core.memory.WorkingContext
import ai.nexus.core.nlu.Fuzzy
import ai.nexus.core.nlu.Reaction
import ai.nexus.core.screen.ElementFinder
import ai.nexus.core.screen.ScreenClassifier
import ai.nexus.core.screen.ScreenKind
import ai.nexus.core.screen.ScreenSnapshot
import ai.nexus.core.screen.UiNode
import kotlinx.coroutines.delay

/**
 * Observe → act → verify primitives shared by all skills. Every action
 * waits for the screen to react (event-driven, not fixed sleeps) and every
 * state-changing action re-reads the screen to confirm what happened.
 */
class Ops(val device: DeviceController, val ctx: WorkingContext) {

    private var cached: ScreenSnapshot? = null

    /** Fresh observation; updates the working context. */
    suspend fun screen(): ScreenSnapshot? {
        val s = device.snapshot() ?: return null
        observe(s)
        return s
    }

    /** Last observation if nothing has changed since, otherwise a fresh one. */
    suspend fun current(): ScreenSnapshot? = cached ?: screen()

    fun observe(s: ScreenSnapshot): ScreenKind {
        cached = s
        val kind = ScreenClassifier.classify(s)
        ctx.noteScreen(s.packageName, kind)
        return kind
    }

    fun invalidate() { cached = null }

    fun kind(s: ScreenSnapshot): ScreenKind = ScreenClassifier.classify(s)

    suspend fun awaitChange(before: ScreenSnapshot?, timeoutMs: Long = 2500): ScreenSnapshot? {
        val after = device.awaitChange(before?.fingerprint, timeoutMs)
        if (after != null) observe(after) else invalidate()
        return after
    }

    /** Wait until [predicate] holds (re-checking on every screen change) or timeout. */
    suspend fun waitFor(timeoutMs: Long = 4000, predicate: (ScreenSnapshot) -> Boolean): ScreenSnapshot? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var s = screen()
        while (true) {
            if (s != null && predicate(s)) return s
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            s = device.awaitChange(s?.fingerprint, minOf(left, 1200)) ?: device.snapshot()
            s?.let { observe(it) }
        }
    }

    suspend fun tap(node: UiNode, before: ScreenSnapshot? = cached): ScreenSnapshot? {
        val target = before?.clickableTarget(node) ?: node
        val ok = device.click(target) || device.tapAt(target.bounds.centerX, target.bounds.centerY)
        if (!ok) return null
        return awaitChange(before) ?: screen()
    }

    suspend fun tapLabel(vararg labels: String, timeoutMs: Long = 2500): ScreenSnapshot? {
        val s = current() ?: return null
        val n = ElementFinder(s).byLabel(*labels) ?: return null
        return tap(n, s)
    }

    suspend fun swipeAndWait(direction: Direction): ScreenSnapshot? {
        val before = current()
        if (!device.swipe(direction)) return null
        return awaitChange(before, 2000) ?: screen()
    }

    suspend fun scrollAndWait(direction: Direction, container: UiNode? = null): Pair<Boolean, ScreenSnapshot?> {
        val before = current()
        val target = container ?: before?.let { ElementFinder(it).largestScrollable() }
        val ok = device.scroll(target, direction) || device.swipe(
            when (direction) { Direction.DOWN -> Direction.UP; Direction.UP -> Direction.DOWN; Direction.LEFT -> Direction.RIGHT; Direction.RIGHT -> Direction.LEFT },
            0.5f,
        )
        val after = awaitChange(before, 1500) ?: screen()
        val changed = after != null && after.fingerprint != before?.fingerprint
        return (ok && changed) to after
    }

    /**
     * Type into a field and confirm the text landed. Prefers the given
     * field, then the focused field, then the lowest editable field.
     */
    suspend fun typeText(text: String, field: UiNode? = null): Outcome {
        val s = current() ?: return Outcome.Failed("I can't see the screen.")
        val target = field ?: ElementFinder(s).firstEditable()
            ?: return Outcome.Failed("There's no text box on this screen.")
        if (!target.focused) device.click(target)
        if (!device.setText(target, text)) return Outcome.Failed("The text box didn't accept the text.")
        val after = waitFor(1500) { snap -> snap.editableFields().any { f -> f.text?.contains(text.take(20)) == true } }
            ?: return Outcome.Uncertain("I typed it, but couldn't confirm the text appeared.")
        observe(after)
        return Outcome.Success()
    }

    /** Find and press a send/post button; confirm the field cleared or the text appeared above it. */
    suspend fun submit(text: String?, labels: List<String> = SEND_LABELS, viewIds: List<String> = emptyList()): Outcome {
        val s = current() ?: return Outcome.Failed("I can't see the screen.")
        val f = ElementFinder(s)
        val btn = viewIds.firstNotNullOfOrNull { f.byViewId(it) } ?: f.byLabel(*labels.toTypedArray(), requireClickable = true, minScore = 0.85)
        val field = f.firstEditable()
        if (btn == null) {
            if (!device.pressImeAction(field)) return Outcome.Failed("I couldn't find a send button.")
        } else {
            tap(btn, s) ?: return Outcome.Failed("The send button didn't respond.")
        }
        val sentText = text?.take(20)
        val after = waitFor(3000) { snap ->
            val fields = snap.editableFields()
            val fieldCleared = sentText != null && fields.none { it.text?.contains(sentText) == true }
            val shown = sentText != null && snap.nodes.any { !it.editable && it.text?.contains(sentText) == true }
            fieldCleared || shown
        }
        return if (after != null) Outcome.Success() else Outcome.Uncertain("I pressed send, but couldn't confirm it went through.")
    }

    // ------------------------------------------------------------- reactions

    data class ReactionSpec(val inactive: List<String>, val active: List<String>, val viewIds: List<String> = emptyList())

    fun specFor(r: Reaction): ReactionSpec = when (r) {
        Reaction.LIKE -> ReactionSpec(listOf("like", "like this video", "heart", "like button", "i like this"), listOf("liked", "unlike", "remove like", "you liked"), listOf("like_button", "row_feed_button_like", "like_button_container"))
        Reaction.DISLIKE -> ReactionSpec(listOf("dislike", "dislike this video"), listOf("disliked", "remove dislike"), listOf("dislike_button"))
        Reaction.SAVE -> ReactionSpec(listOf("save", "add to saved", "bookmark", "save to watch later", "save to playlist", "add to collection"), listOf("saved", "remove from saved", "unsave", "remove"), listOf("row_feed_button_save", "save_button", "clips_save_button"))
        Reaction.SUBSCRIBE -> ReactionSpec(listOf("subscribe"), listOf("subscribed", "unsubscribe"), listOf("subscribe_button"))
        Reaction.FOLLOW -> ReactionSpec(listOf("follow", "follow back"), listOf("following", "unfollow", "requested"), listOf("profile_header_follow_button", "follow_button", "clips_follow_button"))
    }

    /** true = active (liked/saved/following), false = inactive, null = can't tell. */
    fun reactionState(node: UiNode, spec: ReactionSpec): Boolean? {
        val label = Fuzzy.normalize(listOfNotNull(node.contentDescription, node.text).joinToString(" "))
        if (spec.active.any { a -> label == a || label.startsWith("$a ") || label.contains(" $a") && a.contains(' ') }) return true
        if (node.checkable) return node.checked
        if (node.selected) return true
        if (spec.inactive.any { i -> label == i || label.startsWith("$i ") }) return false
        return null
    }

    fun findReactionButton(s: ScreenSnapshot, spec: ReactionSpec): UiNode? {
        val f = ElementFinder(s)
        spec.viewIds.firstNotNullOfOrNull { f.byViewId(it) }?.let { return it }
        // Exact-word matches only, so "Follow" never grabs "Following" by accident (state is checked separately).
        val all = spec.inactive + spec.active
        return s.nodes.filter { it.bounds.area > 0 }.firstOrNull { n ->
            val label = Fuzzy.normalize(listOfNotNull(n.contentDescription, n.text).joinToString(" "))
            all.any { w -> label == w || label.startsWith("$w ") }
        }
    }

    suspend fun setReaction(r: Reaction, on: Boolean, nameForUser: String): Outcome {
        var s = current() ?: return Outcome.Failed("I can't see the screen.")
        val spec = specFor(r)
        val btn = findReactionButton(s, spec) ?: run {
            // The screen may have been mid-animation; look once more.
            s = screen() ?: return Outcome.Failed("I can't see the screen.")
            findReactionButton(s, spec)
        } ?: return Outcome.NeedsAgent("I couldn't find the $nameForUser button here.")

        val before = reactionState(btn, spec)
        if (before == on) return Outcome.Success("It's already ${pastTense(r, on)}.")
        val after = tap(btn, s) ?: return Outcome.Failed("The $nameForUser button didn't respond.")
        // Re-inspect: never repeat the tap blindly – a second tap would undo it.
        val nowBtn = findReactionButton(after, spec) ?: findReactionButton(screen() ?: after, spec)
        val state = nowBtn?.let { reactionState(it, spec) }
        return when {
            state == on -> Outcome.Success()
            state == null && after.fingerprint != s.fingerprint -> Outcome.Uncertain("I tapped $nameForUser, but couldn't confirm the new state.")
            state == null -> Outcome.Uncertain("I tapped $nameForUser, but nothing visibly changed.")
            else -> Outcome.Uncertain("I tapped $nameForUser, but it still looks ${pastTense(r, !on)}.")
        }
    }

    private fun pastTense(r: Reaction, on: Boolean): String {
        val base = when (r) { Reaction.LIKE -> "liked"; Reaction.DISLIKE -> "disliked"; Reaction.SAVE -> "saved"; Reaction.SUBSCRIBE -> "subscribed"; Reaction.FOLLOW -> "followed" }
        return if (on) base else "not $base"
    }

    suspend fun pause(ms: Long) = delay(ms)

    companion object {
        val SEND_LABELS = listOf("send", "post", "reply", "submit", "send message", "send comment", "post comment", "comment", "done")
    }
}
