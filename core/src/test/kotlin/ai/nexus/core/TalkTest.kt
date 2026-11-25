package ai.nexus.core

import ai.nexus.core.agent.Nexus
import ai.nexus.core.agent.NexusEvent
import ai.nexus.core.agent.Talker
import ai.nexus.core.fake.SocialPhone
import ai.nexus.core.memory.InMemoryStorage
import ai.nexus.core.memory.LongTermMemory
import ai.nexus.core.voice.SpeechText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Category C: online conversation streams sentence by sentence; commands never go to it. */
class TalkTest {
    class FakeTalker(private val chunks: List<String>, private val fail: Boolean = false) : Talker {
        val asked = ArrayList<String>()
        var lastHistory: List<Pair<String, String>> = emptyList()
        override suspend fun talk(history: List<Pair<String, String>>, userText: String, context: String, onSentence: (String) -> Unit): String {
            asked += userText; lastHistory = history
            if (fail) error("network down")
            var buf = ""
            for (c in chunks) {
                delay(5)
                val (done, rest) = SpeechText.takeSentences(buf + c); buf = rest
                done.forEach(onSentence)
            }
            if (buf.isNotBlank()) onSentence(buf.trim())
            return chunks.joinToString("")
        }
    }

    private fun setup(t: Talker): Triple<SocialPhone, Nexus, MutableList<NexusEvent.Reply>> {
        val phone = SocialPhone()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val n = Nexus(phone, phone, LongTermMemory(InMemoryStorage()), brain = null, scope = scope)
        n.talker = t
        val replies = java.util.Collections.synchronizedList(ArrayList<NexusEvent.Reply>())
        scope.launch { n.events.collect { if (it is NexusEvent.Reply) replies += it } }
        return Triple(phone, n, replies)
    }
    private fun run(n: Nexus, s: String) = runBlocking { withTimeout(10_000) { n.process(s) }; delay(150) }

    @Test fun questionsStreamToTheTalkerSentenceBySentence() {
        val t = FakeTalker(listOf("Paris is the cap", "ital of France. It's famous for the Eif", "fel Tower! Want more?"))
        val (_, n, r) = setup(t)
        run(n, "what is the capital of France")
        assertEquals(listOf("what is the capital of France"), t.asked)
        assertEquals(listOf("Paris is the capital of France.", "It's famous for the Eiffel Tower!", "Want more?"), r.map { it.text })
        assertTrue(!r[0].continued && r[1].continued && r[2].continued)
    }

    @Test fun conversationRemembersTheLastTurns() {
        val t = FakeTalker(listOf("Sure."))
        val (_, n, _) = setup(t)
        run(n, "tell me a joke")
        run(n, "tell me another joke")
        assertTrue(t.lastHistory.any { it.first == "user" && it.second == "tell me a joke" }, t.lastHistory.toString())
        assertTrue(t.lastHistory.any { it.first == "assistant" && it.second == "Sure." }, t.lastHistory.toString())
    }

    @Test fun commandsNeverGoToTheTalker() {
        val t = FakeTalker(listOf("nope"))
        val (p, n, _) = setup(t)
        run(n, "turn on the flashlight")
        run(n, "set a timer for 5 minutes")
        assertTrue(t.asked.isEmpty(), t.asked.toString())
        assertTrue(p.log.contains("toggle:FLASHLIGHT:true"))
    }

    @Test fun talkerFailureFallsBackWithoutSilence() {
        val (_, n, r) = setup(FakeTalker(emptyList(), fail = true))
        run(n, "what is the meaning of life")
        assertTrue(r.isNotEmpty(), "something must still be said")
    }

    @Test fun speakableTextAndSentenceSplitting() {
        assertEquals("Here is the plan: rest well.", SpeechText.speakable("**Here** is the plan: rest well. 😊"))
        val (s, rest) = SpeechText.takeSentences("Dr. Rao said 3.5 is fine. Then go")
        assertEquals(listOf("Dr. Rao said 3.5 is fine."), s); assertEquals(" Then go", rest)
        assertEquals(listOf("First line", "Second."), SpeechText.takeSentences("First line\nSecond. ").first)
    }
}
