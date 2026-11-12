package ai.nexus.core.agent

import ai.nexus.core.device.Direction
import ai.nexus.core.memory.Turn
import ai.nexus.core.screen.ScreenSnapshot

/** One action the LLM agent decided on. */
sealed interface AgentAction {
    data class Tap(val elementId: Int) : AgentAction
    data class TapAt(val x: Int, val y: Int) : AgentAction
    data class LongPress(val elementId: Int) : AgentAction
    data class Type(val elementId: Int?, val text: String, val submit: Boolean) : AgentAction
    data class Scroll(val elementId: Int?, val direction: Direction) : AgentAction
    data class Swipe(val direction: Direction) : AgentAction
    data object Back : AgentAction
    data object Home : AgentAction
    data class OpenApp(val name: String) : AgentAction
    data class OpenUrl(val url: String) : AgentAction
    data class Wait(val ms: Long) : AgentAction
    /** Tell the user something mid-task (e.g. the information they asked for). */
    data class Say(val text: String) : AgentAction
    data class AskUser(val question: String) : AgentAction
    data class Done(val summary: String) : AgentAction
    data class Fail(val reason: String) : AgentAction
}

/** What happened after an agent action, fed back to the model. */
data class ActionFeedback(val description: String, val screenChanged: Boolean, val screen: ScreenSnapshot?, val screenshot: ByteArray? = null)

/** A running multi-turn agent conversation for one goal. */
interface AgentSession {
    suspend fun next(feedback: ActionFeedback?): AgentAction
}

/**
 * The reasoning engine. Implemented with Claude ([ai.nexus.core.llm.ClaudeBrain]);
 * NEXUS still works without it using its built-in rules.
 */
interface Brain {
    /** Conversational reply. [context] = memory + what is on screen + current task. */
    suspend fun chat(history: List<Turn>, userText: String, context: String): String

    /** Start operating the phone towards [goal]. */
    fun startAgent(goal: String, context: String, firstScreen: ScreenSnapshot?, screenshot: ByteArray? = null): AgentSession

    /** One-shot question about the current screen ("what does this button do?"). */
    suspend fun describeScreen(question: String, screen: ScreenSnapshot, screenshot: ByteArray?, context: String): String
}
