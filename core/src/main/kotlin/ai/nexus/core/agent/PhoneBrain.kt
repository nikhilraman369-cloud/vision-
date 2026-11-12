package ai.nexus.core.agent

import ai.nexus.core.nlu.Command
import ai.nexus.core.nlu.CommandDsl
import ai.nexus.core.screen.ScreenClassifier
import ai.nexus.core.screen.ScreenSnapshot

/** Any text-completion engine: the on-device model on Android, a fake in tests. */
fun interface TextGenerator {
    suspend fun generate(prompt: String): String
}

/**
 * The free, offline "brain". A small on-device model translates rough or
 * unusual commands into NEXUS action lines (short output = fast), using
 * what is visible on screen. It is only consulted when the built-in rules
 * don't understand a request; the result is cached so the same phrase is
 * instant next time.
 */
class PhoneBrain(private val gen: TextGenerator) {

    /** Visible, meaningful labels – the model picks from these for "tap". */
    fun screenSummary(s: ScreenSnapshot?, max: Int = 28): String {
        if (s == null) return "(screen unknown)"
        val kind = ScreenClassifier.classify(s)
        val labels = s.nodes.asSequence()
            .filter { it.bounds.area > 0 && it.visible && (it.clickable || s.clickableTarget(it).clickable || it.editable) }
            .map { n -> (if (n.editable) "[field] " else "") + n.label.replace('\n', ' ').take(40) }
            .filter { it.isNotBlank() }
            .distinct().take(max).toList()
        return "app: ${s.packageName.substringAfterLast('.')} ($kind)\nvisible: " + labels.joinToString(" ; ")
    }

    private fun prompt(utterance: String, screen: String, task: String) = buildString {
        append("<|im_start|>system\n")
        append(SYSTEM)
        append("<|im_end|>\n<|im_start|>user\n")
        append(screen).append("\n").append(task).append(": ").append(utterance)
        append("<|im_end|>\n<|im_start|>assistant\n")
    }

    /** Rough command → actions. Null when the model couldn't produce anything usable. */
    suspend fun translate(utterance: String, screen: ScreenSnapshot?): List<Command>? {
        val out = gen.generate(prompt(utterance, screenSummary(screen), "command"))
        val cmds = CommandDsl.parse(out.substringBefore("<|im_end|>").lines().take(8).joinToString("\n"))
        return cmds.takeIf { it.isNotEmpty() }
    }

    /** Step-by-step screen agent for unfamiliar screens: one action per call. */
    fun startAgent(goal: String, first: ScreenSnapshot?): AgentSession = object : AgentSession {
        private val done = ArrayList<String>()
        private var screen = first

        override suspend fun next(feedback: ActionFeedback?): AgentAction {
            feedback?.screen?.let { screen = it }
            if (done.size >= 6) return AgentAction.Fail("I couldn't finish that on this screen.")
            val history = if (done.isEmpty()) "" else "\nalready done: " + done.joinToString(", ")
            val out = gen.generate(prompt(goal, screenSummary(screen) + history, "next single action for goal (or 'done')"))
            val line = out.substringBefore("<|im_end|>").lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return AgentAction.Fail("No idea what to do next.")
            if (line.lowercase().startsWith("done")) return AgentAction.Done("")
            if (line.lowercase().startsWith("unknown")) return AgentAction.Fail("I'm not sure how to do that here.")
            done += line
            val cmd = CommandDsl.parseLine(line) ?: return AgentAction.Fail("I'm not sure how to do that here.")
            val s = screen
            return when (cmd) {
                is Command.Tap -> s?.let { snap ->
                    ai.nexus.core.screen.ElementFinder(snap).byLabel(cmd.label, minScore = 0.6)?.let { AgentAction.Tap(it.id) }
                } ?: AgentAction.Fail("I can't see \"${cmd.label}\" on screen.")
                is Command.Write -> AgentAction.Type(null, cmd.text, cmd.submit == true)
                is Command.OpenApp -> AgentAction.OpenApp(cmd.app)
                is Command.Scroll -> AgentAction.Scroll(null, cmd.direction)
                Command.NextItem -> AgentAction.Swipe(ai.nexus.core.device.Direction.UP)
                is Command.Navigate -> if (cmd.action == ai.nexus.core.device.GlobalAction.HOME) AgentAction.Home else AgentAction.Back
                else -> AgentAction.Fail("I'm not sure how to do that here.")
            }
        }
    }

    companion object {
        val SYSTEM = """
Turn the phone command (English/Hindi/Hinglish, may be rough) into action lines, one per line, nothing else.
Actions: open_app NAME, tap LABEL, type TEXT, type_send TEXT, send, search Q, open_result N, goto reels|shorts|home|messages|profile, next, previous, like, save, follow, subscribe, comments, comment TEXT, play SONG, pause, resume, next_track, back, home, scroll down|up, message NAME | TEXT, call NAME, toggle wifi|bluetooth|flashlight on|off, volume N|up|down, brightness N|up|down, read_screen, alarm H:MM, timer SECONDS, note TEXT, unknown
If the user names something in "visible", use tap with that exact label. Keep typed text exactly.
Example: visible: Editorial Team ; Family | command: editorial wala group kholo
tap Editorial Team
Example: visible: Believer ; Thunder | command: thunder chala de
tap Thunder
Example: visible: Phone ; Camera | command: insta pe reels dikha aur like kar
open_app Instagram
goto reels
like
""".trimIndent()
    }
}
