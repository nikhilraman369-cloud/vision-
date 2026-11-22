package ai.nexus.core

import ai.nexus.core.nlu.Command
import ai.nexus.core.nlu.IntentParser
import kotlin.test.Test
import kotlin.test.fail

/**
 * Hundreds of real-life phone commands (English, Hindi, Hinglish, rough speech). Every one must be
 * understood as concrete phone actions, and produce the expected action.
 */
class UnderstandingCorpusTest {
    data class Case(val phrase: String, val expect: String, val line: Int)

    private fun cases(file: String = "understanding_corpus.txt"): List<Case> = javaClass.classLoader.getResource(file)!!.readText().lines()
        .mapIndexedNotNull { i, l -> l.trim().takeIf { it.isNotEmpty() && !it.startsWith("#") && it.contains("=>") }?.let { Case(it.substringBefore("=>").trim(), it.substringAfter("=>").trim(), i + 1) } }

    private fun matches(steps: String, expect: String): Boolean = expect.split("||").any { alt ->
        var from = 0
        alt.split("&&").map { it.trim() }.all { part -> val i = steps.indexOf(part, from, ignoreCase = true); if (i < 0) false else { from = i + part.length; true } }
    }

    @Test fun everyCommandIsUnderstood() = check("understanding_corpus.txt")
    @Test fun heldOutCommandsAreUnderstood() = check("understanding_corpus_2.txt")
    @Test fun roughCasualSpeechIsUnderstood() = check("understanding_corpus_3.txt")

    private fun check(file: String) {
        val parser = IntentParser()
        val failures = ArrayList<String>()
        val all = cases(file)
        for (c in all) {
            val steps = parser.parse(c.phrase).steps.map { it.command }
            val shown = steps.joinToString(" | ")
            val vague = steps.isEmpty() || steps.any { it is Command.Chat || it is Command.Agentic }
            if (vague || !matches(shown, c.expect)) failures += "line ${c.line}: \"${c.phrase}\"\n    expected: ${c.expect}\n    got:      $shown"
        }
        println("UNDERSTOOD ${all.size - failures.size}/${all.size}")
        if (failures.isNotEmpty()) {
            java.io.File("build/understanding-failures-$file").writeText(failures.joinToString("\n"))
            fail("${failures.size} of ${all.size} not understood:\n" + failures.joinToString("\n"))
        }
    }
}
