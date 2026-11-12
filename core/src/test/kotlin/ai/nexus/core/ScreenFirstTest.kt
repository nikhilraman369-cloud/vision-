package ai.nexus.core

import ai.nexus.core.agent.Calc
import ai.nexus.core.agent.Nexus
import ai.nexus.core.agent.NexusEvent
import ai.nexus.core.agent.PhoneBrain
import ai.nexus.core.agent.TextGenerator
import ai.nexus.core.device.SysResult
import ai.nexus.core.fake.FakePhone
import ai.nexus.core.fake.UiBuilder
import ai.nexus.core.memory.InMemoryStorage
import ai.nexus.core.memory.LongTermMemory
import ai.nexus.core.memory.MemoryKind
import ai.nexus.core.nlu.Command
import ai.nexus.core.nlu.CommandDsl
import ai.nexus.core.nlu.IntentParser
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

/** A phone with a WhatsApp chat list, a generic AI-chat app and YouTube Music suggestions. */
class ScreenPhone : FakePhone() {
    var waChat: String? = null
    var aiDraft = ""
    val aiSent = ArrayList<String>()
    var playing: String? = null
    val replies = ArrayList<String>()

    override fun render(b: UiBuilder): Pair<String, UiNode> = when (foreground) {
        "com.whatsapp" -> foreground to if (waChat == null) b.node(b = Bounds(0, 0, 1080, 2400), children = listOf(
            b.node(desc = "Search", id = "menuitem_search", b = Bounds(900, 60, 1000, 160), onClick = {}),
            b.node(id = "conversations", scrollable = true, b = Bounds(0, 200, 1080, 2200), children = listOf("Editorial Team", "Family ❤️", "Rahul Sharma").mapIndexed { i, n ->
                b.node(b = Bounds(0, 200 + i * 160, 1080, 360 + i * 160), onClick = { waChat = n }, children = listOf(
                    b.node(text = n, id = "conversations_row_contact_name", b = Bounds(160, 220 + i * 160, 800, 280 + i * 160)),
                ))
            }),
        )) else b.node(b = Bounds(0, 0, 1080, 2400), children = listOf(
            b.node(text = waChat, id = "conversation_contact_name", b = Bounds(160, 60, 800, 140)),
            b.node(cls = "android.widget.EditText", hint = "Message", id = "entry", b = Bounds(20, 2200, 900, 2300), onText = { aiDraft = it }),
            b.node(desc = "Send", id = "send", b = Bounds(920, 2200, 1060, 2300), onClick = { aiSent += aiDraft; aiDraft = "" }),
        ))
        "com.anthropic.claude" -> foreground to b.node(b = Bounds(0, 0, 1080, 2400), children = aiSent.mapIndexed { i, t ->
            b.node(text = t, b = Bounds(40, 300 + i * 100, 1000, 380 + i * 100))
        } + listOf(
            b.node(cls = "android.widget.EditText", text = aiDraft.ifEmpty { null }, hint = "Chat with Claude", b = Bounds(20, 2200, 900, 2300), onText = { aiDraft = it }),
            b.node(desc = "Send message", b = Bounds(920, 2200, 1060, 2300), onClick = { if (aiDraft.isNotEmpty()) { aiSent += aiDraft; aiDraft = "" } }),
        ))
        "com.google.android.apps.youtube.music" -> foreground to b.node(b = Bounds(0, 0, 1080, 2400), children = listOf(
            b.node(text = "Quick picks", b = Bounds(40, 200, 600, 260)),
        ) + listOf("Believer", "Thunder", "Kesariya").mapIndexed { i, t ->
            b.node(b = Bounds(0, 300 + i * 160, 1080, 450 + i * 160), onClick = { playing = t }, children = listOf(
                b.node(text = t, b = Bounds(160, 320 + i * 160, 800, 380 + i * 160)),
                b.node(text = "Song • Artist $i", b = Bounds(160, 390 + i * 160, 800, 430 + i * 160)),
            ))
        })
        else -> foreground to b.node(text = "Home screen")
    }

    override suspend fun replyToNotification(fromName: String, text: String): SysResult? {
        if (fromName.equals("Ankit", true)) { replies += "$fromName:$text"; return SysResult.Done("") }
        return null
    }
}

class ScreenFirstTest {
    private fun setup(phone: FakePhone): Pair<Nexus, MutableList<String>> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val nexus = Nexus(phone, phone, LongTermMemory(InMemoryStorage()), brain = null, scope = scope)
        val out = java.util.Collections.synchronizedList(ArrayList<String>())
        scope.launch { nexus.events.collect { if (it is NexusEvent.Reply) out += it.text; if (it is NexusEvent.Ack) out += "ack:" + it.text } }
        return nexus to out
    }
    private fun run(n: Nexus, s: String) = runBlocking { withTimeout(10_000) { n.process(s) }; kotlinx.coroutines.delay(150) }

    @Test fun opensGroupVisibleOnScreenInsteadOfAnApp() {
        val phone = ScreenPhone().apply { foreground = "com.whatsapp" }
        val (nexus, out) = setup(phone)
        run(nexus, "open the editorial group")
        assertEquals("Editorial Team", phone.waChat, "log=${phone.log} out=$out")
        assertTrue(phone.log.none { it.startsWith("launch:") })
    }

    @Test fun hinglishGroupThenSendToIt() {
        val phone = ScreenPhone().apply { foreground = "com.whatsapp" }
        val (nexus, _) = setup(phone)
        run(nexus, "family wala group kholo")
        assertEquals("Family ❤️", phone.waChat)
        run(nexus, "send good night everyone")
        assertEquals(listOf("good night everyone"), phone.aiSent, phone.log.toString())
    }

    @Test fun typesAndSendsInAnyChatApp() {
        val phone = ScreenPhone().apply { foreground = "com.anthropic.claude" }
        val (nexus, _) = setup(phone)
        run(nexus, "type do it later")
        assertEquals("do it later", phone.aiDraft)
        assertTrue(phone.aiSent.isEmpty(), "type must not send")
        run(nexus, "send summarise this for me")
        assertEquals(listOf("summarise this for me"), phone.aiSent, phone.log.toString())
    }

    @Test fun playsSongVisibleOnScreen() {
        val phone = ScreenPhone().apply { foreground = "com.google.android.apps.youtube.music" }
        val (nexus, _) = setup(phone)
        run(nexus, "play thunder")
        assertEquals("Thunder", phone.playing)
        assertTrue(phone.log.none { it.startsWith("play:") }, "should tap, not search: ${phone.log}")
        run(nexus, "kesariya chalao")
        assertEquals("Kesariya", phone.playing)
    }

    @Test fun repliesFromNotificationWhenPossible() {
        val phone = ScreenPhone()
        val (nexus, _) = setup(phone)
        run(nexus, "message Ankit saying on my way")
        assertEquals(listOf("Ankit:on my way"), phone.replies)
        assertTrue(phone.log.none { it.startsWith("uri:") || it.startsWith("launch:") })
    }

    @Test fun phoneBrainTranslatesRoughCommandAndLearnsIt() {
        val phone = ScreenPhone().apply { foreground = "com.google.android.apps.youtube.music" }
        val (nexus, _) = setup(phone)
        var calls = 0
        nexus.phoneBrain = PhoneBrain(TextGenerator { calls++; "tap Believer" })
        run(nexus, "kuch imagine dragons type ka mood hai")
        assertEquals("Believer", phone.playing)
        assertEquals(1, calls)
        phone.playing = null
        run(nexus, "kuch imagine dragons type ka mood hai")
        assertEquals("Believer", phone.playing)
        assertEquals(1, calls, "second time must come from the learned cache, not the model")
        assertTrue(nexus.memory.items.any { it.kind == MemoryKind.LEARNED })
    }

    @Test fun watchMeRecordsAndReplaysRoutine() {
        val phone = ScreenPhone()
        val (nexus, out) = setup(phone)
        run(nexus, "watch me")
        assertTrue(nexus.isTeaching)
        phone.foreground = "com.google.android.apps.youtube.music"
        nexus.recordAppSwitch("com.google.android.apps.youtube.music", "YT Music")
        nexus.recordUserAction("com.google.android.apps.youtube.music", "Kesariya", null)
        phone.playing = null
        run(nexus, "done, call it my song")
        assertTrue(!nexus.isTeaching)
        phone.foreground = "com.android.launcher3"
        run(nexus, "my song")
        assertEquals("Kesariya", phone.playing, "out=$out log=${phone.log}")
    }

    @Test fun calculatorNotesAndTimers() {
        val phone = ScreenPhone()
        val (nexus, out) = setup(phone)
        run(nexus, "what is 15% of 2400")
        assertTrue(out.any { it.endsWith("= 360") }, out.toString())
        run(nexus, "note buy milk")
        run(nexus, "read my notes")
        runBlocking { withTimeout(2000) { while (out.none { it.contains("buy milk") }) kotlinx.coroutines.delay(20) } }
        assertEquals(12.0, Calc.evaluate("3 times 4"))
    }
}

class NewPhrasingTest {
    private val p = IntentParser()
    private fun cmds(s: String) = p.parse(s).steps.map { it.command }

    @Test fun sendAndType() {
        assertEquals(listOf<Command>(Command.Write("do it later", true)), cmds("send do it later"))
        assertEquals(listOf<Command>(Command.Write("do it later", null)), cmds("type do it later"))
        assertEquals(listOf<Command>(Command.Submit), cmds("send it"))
        assertEquals(listOf<Command>(Command.OpenComments, Command.Write("hello", true)), cmds("comment hello on this video"))
        assertEquals(listOf<Command>(Command.SendMessage(IntentParser.SAME_CONTACT, "hello", null, true)), cmds("send him hello"))
    }

    @Test fun alarmsTimersReminders() {
        assertEquals(listOf<Command>(Command.SetAlarm(7, 30, null)), cmds("set an alarm for 7:30 am"))
        assertEquals(listOf<Command>(Command.SetAlarm(18, 0, "call mom")), cmds("remind me to call mom at 6 pm"))
        assertEquals(listOf<Command>(Command.SetTimer(600, null)), cmds("set a timer for 10 minutes"))
        assertEquals(listOf<Command>(Command.SetTimer(1200, "drink water")), cmds("remind me to drink water in 20 minutes"))
        assertEquals(listOf<Command>(Command.SetAlarm(6, 0, null)), cmds("subah 6 baje ka alarm lagao"))
    }

    @Test fun misc() {
        assertEquals(listOf<Command>(Command.Note("buy milk")), cmds("take a note buy milk"))
        assertEquals(listOf<Command>(Command.Calculate("25 times 4")), cmds("what is 25 times 4"))
        assertEquals(listOf<Command>(Command.TeachStart), cmds("watch me"))
        assertEquals(listOf<Command>(Command.TeachStop("morning")), cmds("done, call it morning"))
        assertEquals(listOf<Command>(Command.HideBubble), cmds("hide the bubble"))
    }

    @Test fun dslRoundTrip() {
        val c = listOf(Command.OpenApp("Instagram"), Command.Tap("Editorial Team"), Command.Write("hi", true), Command.SetAlarm(7, 5, "gym"))
        assertEquals(c, CommandDsl.parse(CommandDsl.toLines(c)))
    }
}
