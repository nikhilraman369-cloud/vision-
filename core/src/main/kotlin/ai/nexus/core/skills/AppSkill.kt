package ai.nexus.core.skills

import ai.nexus.core.agent.AppDirectory
import ai.nexus.core.agent.Ops
import ai.nexus.core.agent.Outcome
import ai.nexus.core.device.Contact
import ai.nexus.core.device.SystemActions
import ai.nexus.core.memory.LongTermMemory
import ai.nexus.core.memory.WorkingContext
import ai.nexus.core.nlu.Command

class SkillEnv(
    val ops: Ops,
    val system: SystemActions,
    val ctx: WorkingContext,
    val apps: AppDirectory,
    val memory: LongTermMemory,
    /** Resolves names/aliases to phone contacts (asks the user when ambiguous). */
    val contacts: suspend (String) -> ContactLookup = { ContactLookup.NotFound },
) {
    val device get() = ops.device
}

/**
 * App-specific know-how. A skill returns null for commands it has nothing
 * special to say about; those fall through to [GenericSkill], which works on
 * any app by reading its interface.
 */
interface AppSkill {
    val packages: Set<String>
    suspend fun handle(cmd: Command, env: SkillEnv): Outcome?
}

sealed interface ContactLookup {
    data class Found(val contact: Contact) : ContactLookup
    data class Ambiguous(val options: List<Contact>) : ContactLookup
    data object NotFound : ContactLookup
}
