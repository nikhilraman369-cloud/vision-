package ai.nexus.core.screen

import kotlinx.serialization.Serializable

@Serializable
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val area: Long get() = width.toLong().coerceAtLeast(0) * height.toLong().coerceAtLeast(0)
    fun contains(other: Bounds) =
        other.left >= left && other.right <= right && other.top >= top && other.bottom <= bottom

    companion object {
        val EMPTY = Bounds(0, 0, 0, 0)
    }
}

/**
 * Platform-independent view of one accessibility node. The Android layer
 * converts AccessibilityNodeInfo trees into this shape; everything above it
 * (finders, classifiers, the agent and the LLM prompt) works on UiNode only.
 *
 * [id] is unique inside one [ScreenSnapshot] and lets the device layer map
 * a node back to the live platform object to act on it.
 */
@Serializable
data class UiNode(
    val id: Int,
    val className: String = "",
    val text: String? = null,
    val contentDescription: String? = null,
    val hintText: String? = null,
    val viewId: String? = null,
    val bounds: Bounds = Bounds.EMPTY,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val scrollable: Boolean = false,
    val editable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val focused: Boolean = false,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    val children: List<UiNode> = emptyList(),
) {
    /** Short resource name, e.g. "like_button" for "com.app:id/like_button". */
    val shortViewId: String? get() = viewId?.substringAfter(":id/")?.takeIf { it.isNotBlank() }

    /** Human-readable label: text, then description, then hint, then the view id. */
    val label: String
        get() = listOfNotNull(text, contentDescription, hintText)
            .firstOrNull { it.isNotBlank() }
            ?: shortViewId?.replace('_', ' ')
            ?: ""

    val isActionable: Boolean get() = clickable || longClickable || editable || checkable || scrollable

    val simpleClass: String get() = className.substringAfterLast('.')

    val role: String
        get() = when {
            editable -> "field"
            simpleClass.contains("Switch") || simpleClass.contains("Toggle") -> "switch"
            checkable -> "checkbox"
            simpleClass.contains("Tab") -> "tab"
            simpleClass.contains("Image") && clickable -> "icon-button"
            simpleClass.contains("Button") -> "button"
            scrollable -> "list"
            clickable -> "clickable"
            else -> "text"
        }

    fun flatten(): Sequence<UiNode> = sequence {
        yield(this@UiNode)
        for (c in children) yieldAll(c.flatten())
    }

    /** All text visible inside this node (itself and descendants). */
    fun allText(): String = flatten()
        .mapNotNull { (it.text ?: it.contentDescription)?.takeIf { s -> s.isNotBlank() } }
        .distinct()
        .joinToString(" ")
}
