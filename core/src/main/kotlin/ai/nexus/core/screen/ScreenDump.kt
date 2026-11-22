package ai.nexus.core.screen

/**
 * A compact, readable dump of a screen's structure (ids, kinds, texts, positions, what can be tapped),
 * used to teach NEXUS an app from its real screens. Text inside editable fields is never included.
 */
object ScreenDump {
    /** Same for two screens of the same *kind* (e.g. two different chats), so we keep one of each. */
    fun structureKey(s: ScreenSnapshot): Int {
        val ids = s.nodes.mapNotNull { it.shortViewId }.toSortedSet()
        return (s.packageName + "|" + ids.joinToString(",")).hashCode()
    }

    fun format(s: ScreenSnapshot, note: String = ""): String = buildString {
        append("=== ").append(s.packageName).append(" | ").append(s.activityName ?: "?").append(" | ").append(s.screenWidth).append('x').append(s.screenHeight)
        append(" | kind=").append(ScreenClassifier.classify(s))
        if (note.isNotBlank()) append(" | ").append(note)
        append(" ===\n")
        fun walk(n: UiNode, depth: Int) {
            val keep = n.viewId != null || !n.text.isNullOrBlank() || !n.contentDescription.isNullOrBlank() || n.clickable || n.scrollable || n.editable
            val nextDepth = if (keep) depth + 1 else depth
            if (keep && n.bounds.area > 0) {
                append("  ".repeat(depth.coerceAtMost(12)))
                append(n.className.substringAfterLast('.'))
                n.shortViewId?.let { append(" #").append(it) }
                if (!n.editable) n.text?.takeIf { it.isNotBlank() }?.let { append(" \"").append(it.replace('\n', ' ').take(60)).append('"') }
                n.contentDescription?.takeIf { it.isNotBlank() }?.let { append(" desc=\"").append(it.replace('\n', ' ').take(60)).append('"') }
                n.hintText?.takeIf { it.isNotBlank() }?.let { append(" hint=\"").append(it.take(40)).append('"') }
                append(" [").append(n.bounds.left).append(',').append(n.bounds.top).append(',').append(n.bounds.right).append(',').append(n.bounds.bottom).append(']')
                if (n.clickable) append(" TAP")
                if (n.longClickable) append(" LONG")
                if (n.scrollable) append(" SCROLL")
                if (n.editable) append(" EDIT")
                if (n.checkable) append(if (n.checked) " ON" else " OFF")
                if (n.selected) append(" SEL")
                append('\n')
            }
            n.children.forEach { walk(it, nextDepth) }
        }
        walk(s.root, 0)
    }
}
