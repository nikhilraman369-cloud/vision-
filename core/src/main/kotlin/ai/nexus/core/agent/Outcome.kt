package ai.nexus.core.agent

/** Result of one step, after verification. */
sealed interface Outcome {
    val message: String?

    /** Verified on screen. */
    data class Success(override val message: String? = null) : Outcome
    /** Action was attempted but the screen could not confirm it. Not repeated blindly. */
    data class Uncertain(override val message: String) : Outcome
    data class Failed(override val message: String, val blocking: Boolean = true) : Outcome
    /** Rules could not handle this screen – ask the LLM agent to work it out. */
    data class NeedsAgent(override val message: String) : Outcome
    /** NEXUS asked the user something and is waiting. */
    data class Asked(override val message: String) : Outcome
}

/** Events the UI / voice layer renders. */
sealed interface NexusEvent {
    data class StateChanged(val state: AgentState) : NexusEvent
    /** Something to show and (optionally) speak. */
    data class Reply(val text: String, val speak: Boolean = true) : NexusEvent
    data class StepStarted(val index: Int, val total: Int, val description: String) : NexusEvent
    data class StepFinished(val index: Int, val description: String, val outcome: Outcome) : NexusEvent
    data class Error(val message: String) : NexusEvent
}

enum class AgentState { IDLE, THINKING, EXECUTING, PAUSED, WAITING_FOR_USER }
