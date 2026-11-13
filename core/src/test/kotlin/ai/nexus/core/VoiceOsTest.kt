package ai.nexus.core

import ai.nexus.core.agent.Nexus
import ai.nexus.core.agent.NexusEvent
import ai.nexus.core.fake.FakePhone
import ai.nexus.core.fake.UiBuilder
import ai.nexus.core.memory.InMemoryStorage
import ai.nexus.core.memory.LongTermMemory
import ai.nexus.core.nlu.Command
import ai.nexus.core.nlu.IntentParser
import ai.nexus.core.screen.Bounds
import ai.nexus.core.screen.InputPurpose
import ai.nexus.core.screen.ScreenSnapshot
import ai.nexus.core.screen.SemanticUiDisambiguator
import ai.nexus.core.screen.UiNode
import ai.nexus.core.device.SysResult
import ai.nexus.core.voice.HandsFreeState
import ai.nexus.core.voice.HandsFreeStateMachine
import ai.nexus.core.voice.PhoneticWakeWordEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WakeWordTest {
    private val e = PhoneticWakeWordEngine()

    @Test fun acceptsMishearings() {
        for (s in listOf("hey nexus", "Hey Naxu", "hay nexis", "he next us", "naxus", "nexus", "hey nexas", "okay nexus", "hey lexus", "hi nexus"))
            assertNotNull(e.match(s), "should wake on '$s'")
    }

    @Test fun ignoresOrdinaryCommands() {
        for (s in listOf("next reel", "open instagram", "next", "send it", "text rahul", "hey how are you", "news", "play music", "nest thermostat"))
            assertNull(e.match(s), "must not wake on '$s'")
    }

    @Test fun commandInSameBreath() {
        assertEquals("open instagram", e.match("hey nexus open instagram")!!.remainder)
        assertEquals("open instagram", e.match("he next us open instagram")!!.remainder)
    }

    @Test fun stateMachineWindow() {
        var now = 0L
        val cmds = ArrayList<String>(); var sleeps = 0; var wakes = 0
        val sm = HandsFreeStateMachine(clock = { now }, listener = object : HandsFreeStateMachine.Listener {
            override fun onWake() { wakes++ }
            override fun onCommand(text: String) { cmds += text }
            override fun onSleep() { sleeps++ }
        })
        sm.onFinal("open youtube")                    // asleep: ignored
        assertTrue(cmds.isEmpty())
        sm.onPartial("hey nex")                       // nothing yet
        sm.onPartial("hey nexus open")                // wakes on the partial
        assertEquals(HandsFreeState.ACTIVE_COMMAND_WINDOW, sm.state)
        sm.onFinal("hey nexus open instagram")
        assertEquals(listOf("open instagram"), cmds)
        now += 4000; sm.tick()
        sm.onFinal("scroll down")                     // within 6.5 s: no wake word needed
        assertEquals(listOf("open instagram", "scroll down"), cmds)
        now += 6000; sm.tick()
        assertEquals(HandsFreeState.ACTIVE_COMMAND_WINDOW, sm.state, "timer resets after each command")
        now += 600; sm.tick()
        assertEquals(HandsFreeState.SLEEP_WAKEWORD, sm.state)
        assertEquals(1, sleeps); assertEquals(1, wakes)
        sm.pause(); sm.onFinal("hey nexus open camera"); sm.resume()  // NEXUS talking: never obey itself
        assertEquals(2, cmds.size)
    }
}

class DisambiguatorTest {
    private var id = 0
    private fun n(text: String? = null, hint: String? = null, desc: String? = null, vid: String? = null, b: Bounds, editable: Boolean = false, clickable: Boolean = false, scroll: Boolean = false, cls: String = "android.view.View", kids: List<UiNode> = emptyList()) =
        UiNode(++id, cls, text, desc, hint, vid?.let { "app:id/$it" }, b, clickable = clickable, editable = editable, scrollable = scroll, children = kids)

    @Test fun mainCommentBoxNotReplyBox() {
        val replyBox = n(hint = "Reply to @ana", vid = "reply_edittext", b = Bounds(60, 900, 1000, 980), editable = true, cls = "android.widget.EditText")
        val row = n(b = Bounds(0, 800, 1080, 1000), kids = listOf(n(text = "great shot!", b = Bounds(60, 820, 900, 880)), replyBox))
        val list = n(vid = "comments_list", b = Bounds(0, 300, 1080, 2100), scroll = true, cls = "androidx.recyclerview.widget.RecyclerView", kids = listOf(row))
        val main = n(hint = "Add a comment…", vid = "layout_comment_thread_edittext", b = Bounds(40, 2150, 900, 2250), editable = true, cls = "android.widget.EditText")
        val post = n(text = "Post", vid = "layout_comment_thread_post_button", b = Bounds(920, 2150, 1060, 2250), clickable = true)
        val root = n(b = Bounds(0, 0, 1080, 2400), kids = listOf(list, main, post))
        val s = ScreenSnapshot("com.instagram.android", root)
        val d = SemanticUiDisambiguator(s)
        assertEquals(main.id, d.bestInput(InputPurpose.COMMENT)!!.node.id)
        assertEquals(post.id, d.submitFor(main)!!.id)
    }

    @Test fun searchBoxAtTop() {
        val top = n(hint = "Search", vid = "search_src_text", b = Bounds(100, 60, 980, 160), editable = true)
        val bottom = n(hint = "Message", vid = "entry", b = Bounds(20, 2200, 900, 2300), editable = true)
        val s = ScreenSnapshot("x", n(b = Bounds(0, 0, 1080, 2400), kids = listOf(top, bottom)))
        assertEquals(top.id, SemanticUiDisambiguator(s).bestInput(InputPurpose.SEARCH)!!.node.id)
        assertEquals(bottom.id, SemanticUiDisambiguator(s).bestInput(InputPurpose.MESSAGE)!!.node.id)
    }

    @Test fun gridAndShareParsing() {
        val p = IntentParser()
        assertEquals(listOf<Command>(Command.TapRegion(0, 2)), p.parse("tap top right").steps.map { it.command })
        assertEquals(listOf<Command>(Command.TapRegion(1, 1, double = true)), p.parse("double tap center").steps.map { it.command })
        assertEquals(listOf<Command>(Command.ShareTo("Arjun")), p.parse("send this to Arjun").steps.map { it.command })
        assertEquals(listOf<Command>(Command.ShareTo("Rahul", "WhatsApp")), p.parse("share this video with Rahul on WhatsApp").steps.map { it.command })
    }
}

/** YT Music that loads slowly, and where headless "play from search" silently does nothing. */
class SlowMusicPhone : FakePhone() {
    var query = ""; var loadingUntil = 0L; var playing: String? = null; var resultsShown = false; var searchOpen = false
    init { foreground = "com.google.android.apps.youtube.music" }
    override suspend fun playFromSearch(query: String, packageName: String?): SysResult { log += "headless:$query"; return SysResult.Done("") }
    override suspend fun isMusicActive(): Boolean = playing != null
    override suspend fun pressImeAction(node: UiNode?): Boolean { resultsShown = true; loadingUntil = System.currentTimeMillis() + 700; return true }
    override fun render(b: UiBuilder): Pair<String, UiNode> {
        val kids = ArrayList<UiNode>()
        kids += b.node(desc = "Search", b = Bounds(900, 60, 1000, 160), onClick = { searchOpen = true })
        if (searchOpen) kids += b.node(cls = "android.widget.EditText", text = query.ifEmpty { null }, hint = "Search songs", id = "search_edit_text", b = Bounds(100, 60, 880, 160), onText = { query = it })
        if (resultsShown) {
            if (System.currentTimeMillis() < loadingUntil) kids += b.node(cls = "android.widget.ProgressBar", b = Bounds(500, 1000, 580, 1080))
            else kids += b.node(id = "results", scrollable = true, b = Bounds(0, 300, 1080, 2300), children = listOf(
                b.node(b = Bounds(0, 320, 1080, 480), onClick = { playing = "Bloody Mary (video)" }, children = listOf(b.node(text = "Bloody Mary • Video • Lady Gaga", b = Bounds(160, 340, 900, 400)))),
                b.node(b = Bounds(0, 500, 1080, 660), onClick = { playing = "Bloody Mary" }, children = listOf(b.node(text = "Bloody Mary • Song • Lady Gaga", b = Bounds(160, 520, 900, 580)))),
            ))
        }
        return foreground to b.node(b = Bounds(0, 0, 1080, 2400), children = kids)
    }
}

class PipelineTest {
    @Test fun headlessPlaybackFailsThenUiMacroWaitsForLoadingAndPlaysTheSong() {
        val p = SlowMusicPhone()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val n = Nexus(p, p, LongTermMemory(InMemoryStorage()), brain = null, scope = scope)
        val out = java.util.Collections.synchronizedList(ArrayList<String>())
        scope.launch { n.events.collect { if (it is NexusEvent.Reply) out += it.text } }
        runBlocking { withTimeout(20_000) { n.process("play bloody mary on yt music") } }
        assertTrue(p.log.any { it.startsWith("headless:") }, "tries headless first")
        assertEquals("Bloody Mary", p.playing, "picks the Song row once loading finished. log=${p.log} out=$out")
    }
}
