package ai.nexus.core.screen

/**
 * One observation of the phone screen.
 */
data class ScreenSnapshot(
    val packageName: String,
    val root: UiNode,
    val activityName: String? = null,
    val screenWidth: Int = 1080,
    val screenHeight: Int = 2400,
    val keyboardVisible: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis(),
    /** Optional text recognised from a screenshot (OCR), when accessibility data is poor. */
    val ocrText: String? = null,
) {
    val nodes: List<UiNode> by lazy { root.flatten().filter { it.visible }.toList() }

    private val parents: Map<Int, UiNode> by lazy {
        val map = HashMap<Int, UiNode>()
        fun walk(n: UiNode) { for (c in n.children) { map[c.id] = n; walk(c) } }
        walk(root)
        map
    }

    fun parentOf(node: UiNode): UiNode? = parents[node.id]

    fun nodeById(id: Int): UiNode? = nodes.firstOrNull { it.id == id }

    fun ancestors(node: UiNode): Sequence<UiNode> = generateSequence(parentOf(node)) { parentOf(it) }

    /** The nearest clickable node at or above [node] – what a tap should target. */
    fun clickableTarget(node: UiNode): UiNode =
        if (node.clickable) node else ancestors(node).firstOrNull { it.clickable } ?: node

    /**
     * Stable fingerprint of what is meaningfully on screen. Used to tell
     * whether the screen changed after an action without diffing trees, and
     * to skip re-analysing a screen that has not changed.
     */
    val fingerprint: Int by lazy {
        var h = packageName.hashCode()
        for (n in nodes) {
            if (n.label.isBlank() && !n.isActionable) continue
            h = 31 * h + n.label.hashCode()
            h = 31 * h + (n.viewId?.hashCode() ?: 0)
            h = 31 * h + (if (n.checked) 1 else 0) + (if (n.selected) 2 else 0)
        }
        h
    }

    fun containsText(needle: String, ignoreCase: Boolean = true): Boolean =
        nodes.any { n ->
            (n.text?.contains(needle, ignoreCase) == true) ||
                (n.contentDescription?.contains(needle, ignoreCase) == true)
        } || (ocrText?.contains(needle, ignoreCase) == true)

    fun hasViewId(vararg shortIds: String): Boolean =
        nodes.any { n -> n.shortViewId != null && shortIds.any { n.shortViewId == it } }

    fun editableFields(): List<UiNode> = nodes.filter { it.editable && it.enabled }

    fun focusedField(): UiNode? = nodes.firstOrNull { it.editable && it.focused }

    /** All readable text on screen, top-to-bottom, for "read this page". */
    fun readableText(): List<String> = nodes
        .filter { !it.text.isNullOrBlank() }
        .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        .map { it.text!!.trim() }
        .distinct()

    /**
     * Compact, line-per-element description for a language model: only
     * elements that carry meaning (text or an action). Each line starts with
     * the element id the model can refer to when choosing an action.
     */
    fun toPromptText(maxElements: Int = 120): String {
        val sb = StringBuilder()
        sb.append("app=").append(packageName)
        activityName?.let { sb.append(" activity=").append(it.substringAfterLast('.')) }
        if (keyboardVisible) sb.append(" keyboard=open")
        sb.append(" screen=").append(screenWidth).append('x').append(screenHeight).append('\n')
        var count = 0
        for (n in nodes) {
            if (count >= maxElements) { sb.append("… (more elements not shown)\n"); break }
            val label = n.label.replace('\n', ' ').take(80)
            if (label.isBlank() && !n.isActionable) continue
            if (n.bounds.area == 0L) continue
            sb.append('[').append(n.id).append("] ").append(n.role)
            if (label.isNotBlank()) sb.append(" \"").append(label).append('"')
            n.shortViewId?.let { if (it.replace('_', ' ') != label) sb.append(" #").append(it) }
            val flags = buildList {
                if (n.clickable) add("tap")
                if (n.longClickable) add("long")
                if (n.scrollable) add("scroll")
                if (n.editable) add("edit")
                if (n.checkable) add(if (n.checked) "on" else "off")
                if (n.selected) add("selected")
                if (n.focused) add("focused")
                if (!n.enabled) add("disabled")
            }
            if (flags.isNotEmpty()) sb.append(" {").append(flags.joinToString(",")).append('}')
            sb.append(" @").append(n.bounds.centerX).append(',').append(n.bounds.centerY)
            sb.append('\n')
            count++
        }
        ocrText?.takeIf { it.isNotBlank() }?.let { sb.append("ocr: ").append(it.take(1500)).append('\n') }
        return sb.toString()
    }
}
