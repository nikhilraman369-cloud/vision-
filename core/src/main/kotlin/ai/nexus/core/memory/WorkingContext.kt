package ai.nexus.core.memory

import ai.nexus.core.device.Contact
import ai.nexus.core.nlu.Command
import ai.nexus.core.screen.ScreenKind

/** A question NEXUS asked and is waiting on ("Which Rahul?"). */
data class PendingChoice(
    val question: String,
    val options: List<String>,
    val onChoose: suspend (index: Int) -> Unit,
)

/**
 * Short-term memory of what is happening right now: the current app and
 * screen, what was just done, what "it", "him" and "the same video" refer to.
 */
class WorkingContext {
    var currentPackage: String? = null
    var currentScreen: ScreenKind = ScreenKind.UNKNOWN
    var previousScreen: ScreenKind = ScreenKind.UNKNOWN
    val appHistory = ArrayDeque<String>()

    var lastSearchQuery: String? = null
    var lastSearchApp: String? = null
    /** Description of the item being looked at ("Reel by @nasa", "video: How to film"). */
    var currentItem: String? = null
    var currentContact: Contact? = null
    var currentContactName: String? = null
    var currentChatApp: String? = null

    /** Commands of the last completed request, for "do the same thing again". */
    var lastRequestCommands: List<Command> = emptyList()
    var lastCommand: Command? = null
    var lastUtterance: String? = null

    var pendingChoice: PendingChoice? = null

    /** Rolling conversation for the chat model. */
    val conversation = ArrayDeque<Turn>()

    fun noteScreen(pkg: String, kind: ScreenKind) {
        if (pkg != currentPackage) {
            currentPackage?.let { prev ->
                appHistory.remove(prev)
                appHistory.addLast(prev)
                while (appHistory.size > 15) appHistory.removeFirst()
            }
            currentPackage = pkg
        }
        if (kind != currentScreen) {
            previousScreen = currentScreen
            currentScreen = kind
        }
    }

    fun previousApp(): String? = appHistory.lastOrNull { it != currentPackage }

    fun addTurn(role: String, text: String) {
        conversation.addLast(Turn(role, text))
        while (conversation.size > 30) conversation.removeFirst()
    }

    fun toPromptText(): String = buildString {
        append("current app: ").append(currentPackage ?: "unknown").append(" (").append(currentScreen).append(")\n")
        if (previousScreen != ScreenKind.UNKNOWN) append("previous screen: ").append(previousScreen).append('\n')
        lastSearchQuery?.let { append("last search: \"").append(it).append("\" in ").append(lastSearchApp ?: "?").append('\n') }
        currentItem?.let { append("current item: ").append(it).append('\n') }
        (currentContact?.name ?: currentContactName)?.let { append("contact being discussed: ").append(it).append('\n') }
        lastCommand?.let { append("previous action: ").append(it).append('\n') }
        if (appHistory.isNotEmpty()) append("recent apps: ").append(appHistory.takeLast(5).joinToString()).append('\n')
    }
}
