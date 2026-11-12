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
     * Result-like items of the main list in reading order, e.g. search
     * results or a feed. Picks the largest scrollable container and returns
     * its clickable, labelled descendants that look like content items.
     */
    fun listItems(minHeightPx: Int = 80): List<UiNode> {
        val container = largestScrollable() ?: screen.root
        val items = container.flatten()
            .filter { it.id != container.id && it.clickable && it.bounds.height >= minHeightPx && it.bounds.area > 0 }
            .filter { it.allText().isNotBlank() }
            .toList()
        // Keep outermost items only (a card and its inner title are the same result).
        val outer = items.filter { item -> items.none { other -> other.id != item.id && other.bounds.contains(item.bounds) && other.bounds != item.bounds } }
        return outer.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    /** Ordinal is 1-based; negative counts from the end (-1 = last). */
    fun nthListItem(ordinal: Int, filter: (UiNode) -> Boolean = { true }): UiNode? {
        val items = listItems().filter(filter)
        if (items.isEmpty()) return null
        val idx = if (ordinal < 0) items.size + ordinal else ordinal - 1
        return items.getOrNull(idx)
    }
}
