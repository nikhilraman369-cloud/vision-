package ai.nexus.core

import ai.nexus.core.agent.Nexus
import ai.nexus.core.agent.NexusEvent
import ai.nexus.core.fake.FakePhone
import ai.nexus.core.fake.UiBuilder
import ai.nexus.core.memory.InMemoryStorage
import ai.nexus.core.memory.LongTermMemory
import ai.nexus.core.screen.Bounds
import ai.nexus.core.screen.UiNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A chat list like WhatsApp's: a full-width search bar on top, then eight chats. */
class ChatListPhone : FakePhone() {
    val chats = listOf("Editorial Team", "Family ❤️", "Rahul Sharma", "Priya", "College Gang", "Mom", "Papa", "Ankit")
    var open: String? = null
    var draft = ""
    val sent = ArrayList<String>()
    var offset = 0
    override suspend fun scroll(node: UiNode?, direction: ai.nexus.core.device.Direction): Boolean { log += "scroll:$direction"; offset++; return true }

    init { foreground = "com.whatsapp" }

    override fun render(b: UiBuilder): Pair<String, UiNode> = foreground to (if (open == null) b.node(b = Bounds(0, 0, 1080, 2400), children = listOf(
        b.node(text = "Ask Meta AI or Search", id = "my_search_bar", b = Bounds(20, 200, 1060, 330), onClick = {}),
        b.node(text = "scrolled $offset", b = Bounds(20, 2320, 400, 2380)),
        b.node(id = "conversations", scrollable = true, b = Bounds(0, 340, 1080, 2300), children = chats.mapIndexed { i, n ->
            b.node(b = Bounds(0, 340 + i * 220, 1080, 540 + i * 220), onClick = { open = n }, children = listOf(
                b.node(text = n, id = "conversations_row_contact_name", b = Bounds(160, 360 + i * 220, 800, 430 + i * 220)),
                b.node(text = "last message $i", b = Bounds(160, 440 + i * 220, 800, 500 + i * 220)),
            ))
        }),
    )) else b.node(b = Bounds(0, 0, 1080, 2400), children = listOf(
        b.node(text = open, id = "conversation_contact_name", b = Bounds(160, 60, 800, 140)),
        b.node(cls = "android.widget.EditText", hint = "Message", id = "entry", b = Bounds(20, 2200, 900, 2300), onText = { draft = it }),
        b.node(desc = "Send", id = "send", b = Bounds(920, 2200, 1060, 2300), onClick = { sent += draft; draft = "" }),
    )))

    override suspend fun global(action: ai.nexus.core.device.GlobalAction): Boolean { if (action == ai.nexus.core.device.GlobalAction.BACK) open = null; return super.global(action) }
}

class ScreenUnderstandingTest {
    private fun setup(phone: FakePhone): Pair<Nexus, MutableList<String>> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val nexus = Nexus(phone, phone, LongTermMemory(InMemoryStorage()), brain = null, scope = scope)
        val out = java.util.Collections.synchronizedList(ArrayList<String>())
        scope.launch { nexus.events.collect { if (it is NexusEvent.Reply) out += it.text } }
        return nexus to out
    }
    private fun run(n: Nexus, s: String) = runBlocking { withTimeout(15_000) { n.process(s) }; kotlinx.coroutines.delay(150) }

    @Test fun topGroupThenTypeSkipsTheSearchBar() {
        val p = ChatListPhone(); val (n, out) = setup(p)
        run(n, "open the group which is at the top and then type okay, I'll do it later")
        assertEquals("Editorial Team", p.open, out.toString())
        assertEquals("okay, I'll do it later", p.draft, p.log.toString())
        assertTrue(p.sent.isEmpty(), "type must not send")
    }

    @Test fun ordinalsInEnglishAndHinglish() {
        val cases = listOf(
            "click on the sixth contact" to "Mom", "teesri chat kholo" to "Rahul Sharma", "open the last chat" to "Ankit",
            "open the second last chat" to "Papa", "sabse upar wala chat kholo" to "Editorial Team", "open number 4" to "Priya",
            "5th wala kholo" to "College Gang", "open the 2nd conversation" to "Family ❤️",
        )
        for ((say, expect) in cases) {
            val p = ChatListPhone(); val (n, out) = setup(p)
            run(n, say)
            assertEquals(expect, p.open, "\"$say\" → out=$out log=${p.log}")
        }
    }

    @Test fun repeatsActionsTheRightNumberOfTimes() {
        val p = ChatListPhone(); val (n, _) = setup(p)
        run(n, "teen baar scroll karo")
        assertEquals(3, p.log.count { it.startsWith("scroll:") || it.startsWith("swipe:") }, p.log.toString())
        p.open = "Mom"; p.log.clear()
        run(n, "2 baar back jao")
        assertEquals(2, p.log.count { it == "global:BACK" }, p.log.toString())
    }

    @Test fun unknownWordsThatNameSomethingOnScreenTapIt() {
        val p = ChatListPhone(); val (n, out) = setup(p)
        run(n, "bhai college gang wala kholo na")
        assertEquals("College Gang", p.open, out.toString())
    }

    @Test fun neverSaysItDoesNotUnderstand() {
        val p = ChatListPhone(); val (n, out) = setup(p)
        run(n, "do the thing with the blue stuff")
        assertTrue(out.none { it.contains("don't understand", true) || it.contains("didn't understand", true) || it.contains("don't know that one", true) }, out.toString())
        assertTrue(out.any { it.contains("Which one should I tap") }, out.toString())
    }
}
