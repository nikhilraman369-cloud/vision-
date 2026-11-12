package ai.nexus.core.screen

import ai.nexus.core.nlu.Fuzzy

/**
 * Locates UI elements by meaning rather than exact ids: by visible label,
 * content description, view id, role and position. Every app skill and the
 * generic "tap X" handling go through here.
 */
class ElementFinder(private val screen: ScreenSnapshot) {

    data class Match(val node: UiNode, val score: Double)

    /**
     * Best element whose label/description/id matches any of [labels].
     * Exact matches beat word matches, which beat fuzzy matches. Clickable
     * elements and elements that are on screen win ties.
     */
    fun byLabel(
        vararg labels: String,
        requireClickable: Boolean = false,
        role: String? = null,
        minScore: Double = 0.6,
    ): UiNode? = rankByLabel(labels.toList(), requireClickable, role).firstOrNull { it.score >= minScore }?.node

    fun rankByLabel(labels: List<String>, requireClickable: Boolean = false, role: String? = null): List<Match> {
        val wanted = labels.map { Fuzzy.normalize(it) }.filter { it.isNotBlank() }
        if (wanted.isEmpty()) return emptyList()
        val out = ArrayList<Match>()
        for (n in screen.nodes) {
            if (n.bounds.area == 0L) continue
            val target = screen.clickableTarget(n)
            if (requireClickable && !target.clickable && !n.editable) continue
            if (role != null && n.role != role && target.role != role) continue
            val candidates = listOfNotNull(n.text, n.contentDescription, n.hintText, n.shortViewId?.replace('_', ' '))
            var best = 0.0
            for (c in candidates) {
                val cn = Fuzzy.normalize(c)
                if (cn.isBlank()) continue
                for (w in wanted) best = maxOf(best, score(w, cn))
            }
            if (best <= 0.0) continue
            if (target.clickable) best += 0.05
            if (!n.enabled) best -= 0.2
            out += Match(target, best)
        }
        return out.sortedByDescending { it.score }.distinctBy { it.node.id }
    }

    private fun score(wanted: String, candidate: String): Double {
        if (candidate == wanted) return 1.0
        // Labels like "Like", "Like 1.2K" or "Liked" – prefix/word match.
        val words = candidate.split(' ')
        if (words.contains(wanted)) return 0.9 - (words.size - 1) * 0.02
        if (candidate.startsWith("$wanted ")) return 0.88
        if (wanted.contains(' ') && candidate.contains(wanted)) return 0.85
        if (candidate.length <= 40) {
            val sim = Fuzzy.similarity(wanted, candidate)
            if (sim >= 0.8) return sim * 0.85
        }
        // Long text that merely mentions the word is a weak match.
        if (Fuzzy.containsWord(candidate, wanted)) return 0.5
        return 0.0
    }

    fun byViewId(vararg shortIds: String): UiNode? {
        for (id in shortIds) {
            screen.nodes.firstOrNull { it.shortViewId == id && it.bounds.area > 0 }?.let { return it }
        }
        return null
    }

    fun byViewIdContains(vararg parts: String): UiNode? =
        screen.nodes.firstOrNull { n -> n.bounds.area > 0 && parts.any { p -> n.shortViewId?.contains(p) == true } }

    fun firstEditable(): UiNode? =
        screen.focusedField() ?: screen.editableFields().maxByOrNull { it.bounds.top }

    fun largestScrollable(): UiNode? = screen.nodes.filter { it.scrollable }.maxByOrNull { it.bounds.area }

    /**
     * Result-like items on screen in reading order: search results, feed
     * cards, chats, songs. Works even when apps don't mark cards as
     * clickable (YouTube, Instagram): any wide, labelled block in the content
     * area counts. Nested parts of the same card are merged.
     */
    fun listItems(minHeightPx: Int = 80): List<UiNode> {
        val h = screen.screenHeight; val w = screen.screenWidth
        val top = h * 0.08; val bottom = h * 0.94
        val minH = maxOf(minHeightPx, (h * 0.035).toInt())
        val raw = screen.nodes.asSequence()
            .filter { n -> !n.editable && n.visible && n.bounds.height >= minH && n.bounds.width >= w * 0.35 }
            .filter { n -> n.bounds.centerY > top && n.bounds.centerY < bottom }
            .filter { n -> n.bounds.area < w.toLong() * h * 0.7 }
            .filter { n -> n.clickable || !n.contentDescription.isNullOrBlank() || screen.clickableTarget(n).let { t -> t.clickable && t.bounds.area < w.toLong() * h * 0.7 } }
            .filter { n -> n.allText().isNotBlank() }
            .map { n -> screen.clickableTarget(n).takeIf { t -> t.bounds.area < w.toLong() * h * 0.7 && t.bounds.height >= minH } ?: n }
            .distinctBy { it.id }
            .toList()
        // Keep outermost blocks only (a card and its inner title are one result).
        val outer = raw.filter { item -> raw.none { o -> o.id != item.id && o.bounds.contains(item.bounds) && o.bounds != item.bounds } }
        return outer.sortedWith(compareBy({ it.bounds.top / 40 }, { it.bounds.left }))
    }

    /** Ordinal is 1-based; negative counts from the end (-1 = last). */
    fun nthListItem(ordinal: Int, filter: (UiNode) -> Boolean = { true }): UiNode? {
        val items = listItems().filter(filter)
        if (items.isEmpty()) return null
        val idx = if (ordinal < 0) items.size + ordinal else ordinal - 1
        return items.getOrNull(idx)
    }
}
