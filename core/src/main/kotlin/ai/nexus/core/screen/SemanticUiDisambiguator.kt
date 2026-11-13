package ai.nexus.core.screen

import ai.nexus.core.nlu.Fuzzy

/** What the user is trying to do with a screen element. */
enum class UiIntent { PRIMARY_INPUT, ITEM_ACTION, NAVIGATION }

/** Kind of text box wanted for PRIMARY_INPUT. */
enum class InputPurpose { COMMENT, SEARCH, MESSAGE, ANY }

data class ScoredNode(val node: UiNode, val score: Double, val why: String)

/**
 * Picks the element the user *means* when several could match — e.g. the
 * main "Add a comment…" box at the bottom of a video, not the "Reply" box
 * of somebody's comment inside the list. Every candidate gets a numeric
 * score from:
 *
 *  1. resource-id keywords (comment_composer, search_src_text, entry, send…)
 *  2. structure: nested inside a list item (penalised for primary actions)
 *     vs. anchored to the screen's top/bottom bars (boosted)
 *  3. vertical position (inputs for comments/chats sit at the bottom,
 *     search boxes at the top)
 *  4. properties: editable, focused, clickable, hint/label/description text
 *
 * Plus any selector NEXUS learned worked last time in this app.
 */
class SemanticUiDisambiguator(private val s: ScreenSnapshot) {

    private fun id(n: UiNode) = (n.shortViewId ?: "").lowercase()
    private fun words(n: UiNode) = Fuzzy.normalize(listOfNotNull(n.text, n.hintText, n.contentDescription).joinToString(" "))

    private val listClasses = listOf("RecyclerView", "ListView", "GridView", "LazyColumn", "ScrollView", "ViewPager")

    /** True if the node lives inside one row of a scrolling list (not the list itself). */
    fun insideListItem(n: UiNode): Boolean {
        val anc = s.ancestors(n).toList()
        val listIdx = anc.indexOfFirst { a -> a.scrollable || listClasses.any { a.simpleClass.contains(it) } }
        return listIdx > 0 // at least one container between the node and the list = it's inside a row
    }

    private fun inBottomBar(n: UiNode) = n.bounds.top > s.screenHeight * 0.72
    private fun inTopBar(n: UiNode) = n.bounds.bottom < s.screenHeight * 0.22

    private val purposeIds = mapOf(
        InputPurpose.COMMENT to listOf("comment_composer", "comment_thread_edittext", "comment_input", "comment_box", "add_comment", "comment_edit", "composer"),
        InputPurpose.SEARCH to listOf("search_src_text", "search_edit_text", "search_box", "search_bar", "search_query", "query", "search_input", "url_bar"),
        InputPurpose.MESSAGE to listOf("entry", "composer", "message_input", "chat_input", "msg_input", "compose", "row_thread_composer", "edittext"),
        InputPurpose.ANY to listOf("input", "edit", "composer", "entry", "text_field", "edittext"),
    )
    private val purposeWords = mapOf(
        InputPurpose.COMMENT to listOf("add a comment", "comment", "add comment", "write a comment", "comment as"),
        InputPurpose.SEARCH to listOf("search", "search or type", "find"),
        InputPurpose.MESSAGE to listOf("message", "type a message", "write a message", "chat", "reply"),
        InputPurpose.ANY to listOf("type", "write", "enter", "message", "comment", "search"),
    )

    /** Best text box (or the "Add a comment…" opener that becomes one) for [purpose]. */
    fun bestInput(purpose: InputPurpose, userSaidReply: Boolean = false, learnedViewId: String? = null): ScoredNode? {
        val ranked = s.nodes.filter { it.bounds.area > 0 && it.enabled }.mapNotNull { n -> scoreInput(n, purpose, userSaidReply, learnedViewId) }
        return ranked.maxByOrNull { it.score }?.takeIf { it.score >= 25 }
    }

    fun scoreInput(n: UiNode, purpose: InputPurpose, userSaidReply: Boolean, learnedViewId: String? = null): ScoredNode? {
        val w = words(n); val vid = id(n)
        val opener = !n.editable && purposeWords.getValue(purpose).any { p -> w.startsWith(p) || w.contains(" $p") } &&
            (n.clickable || s.clickableTarget(n).clickable || n.focusable())
        if (!n.editable && !opener) return null
        var score = 0.0; val why = StringBuilder()
        fun add(v: Double, r: String) { score += v; why.append(r).append(if (v >= 0) "+" else "").append(v.toInt()).append(' ') }
        add(if (n.editable) 40.0 else 22.0, if (n.editable) "editable" else "opener")
        if (learnedViewId != null && n.viewId == learnedViewId) add(60.0, "learned")
        if (purposeIds.getValue(purpose).any { vid.contains(it) }) add(30.0, "id")
        else if (purposeIds.getValue(InputPurpose.ANY).any { vid.contains(it) }) add(8.0, "id~")
        if (purposeWords.getValue(purpose).any { w.contains(it) }) add(20.0, "label")
        val replyish = vid.contains("reply") || w.startsWith("reply") || w.contains("replying to")
        if (purpose == InputPurpose.COMMENT && replyish && !userSaidReply) add(-50.0, "reply")
        if (insideListItem(n)) add(if (purpose == InputPurpose.SEARCH) -10.0 else -25.0, "in-list")
        when (purpose) {
            InputPurpose.COMMENT, InputPurpose.MESSAGE -> if (inBottomBar(n)) add(15.0, "bottom") else if (inTopBar(n)) add(-10.0, "top")
            InputPurpose.SEARCH -> if (inTopBar(n)) add(15.0, "top")
            InputPurpose.ANY -> if (inBottomBar(n) || inTopBar(n)) add(6.0, "bar")
        }
        if (n.focused) add(10.0, "focused")
        if (n.bounds.width > s.screenWidth * 0.45) add(5.0, "wide")
        return ScoredNode(n, score, why.toString().trim())
    }

    private fun UiNode.focusable() = editable || clickable

    /**
     * The Send/Post button that belongs to [field]: same row (vertical overlap),
     * to its right or just below, and labelled or id'd like a submit button.
     */
    fun submitFor(field: UiNode?, learnedViewId: String? = null): UiNode? {
        val labels = listOf("send", "post", "reply", "submit", "send message", "post comment", "send comment", "comment", "done", "search", "go")
        val ids = listOf("send", "post", "submit", "button_send", "send_button", "post_button", "send_container", "comment_post")
        val cands = s.nodes.filter { it.bounds.area > 0 && !it.editable && it.enabled }.mapNotNull { n ->
            val t = s.clickableTarget(n)
            if (!t.clickable) return@mapNotNull null
            val w = words(n); val vid = id(n)
            var sc = 0.0
            if (learnedViewId != null && n.viewId == learnedViewId) sc += 80
            if (ids.any { vid.contains(it) }) sc += 40
            if (labels.any { l -> w == l || w.startsWith("$l ") }) sc += 35
            if (sc == 0.0) return@mapNotNull null
            if (vid.contains("reply") && field != null && !(field.shortViewId ?: "").contains("reply")) sc -= 20
            if (field != null) {
                val sameRow = n.bounds.top < field.bounds.bottom + 40 && n.bounds.bottom > field.bounds.top - 40
                if (sameRow) sc += 30
                if (n.bounds.left >= field.bounds.right - 20) sc += 10
                val dy = kotlin.math.abs(n.bounds.centerY - field.bounds.centerY)
                sc -= dy / 60.0
            }
            if (insideListItem(n)) sc -= 25
            ScoredNode(t, sc, "")
        }
        return cands.maxByOrNull { it.score }?.takeIf { it.score >= 30 }?.node
    }

    /** Navigation tabs: prefer items in the top or bottom bars over same-named items in content. */
    fun bestNavigation(labels: List<String>): UiNode? {
        val ranked = ElementFinder(s).rankByLabel(labels, requireClickable = true)
        return ranked.maxByOrNull { m ->
            var sc = m.score * 100
            if (inBottomBar(m.node) || inTopBar(m.node)) sc += 25
            if (insideListItem(m.node)) sc -= 30
            if (m.node.selected) sc += 5
            sc
        }?.takeIf { it.score >= 0.75 }?.node
    }
}
