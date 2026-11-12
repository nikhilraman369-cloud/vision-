package ai.nexus.core

import ai.nexus.core.agent.Nexus
import ai.nexus.core.agent.NexusEvent
import ai.nexus.core.agent.PhoneBrain
import ai.nexus.core.agent.TextGenerator
import ai.nexus.core.device.SystemSetting
import ai.nexus.core.fake.FakePhone
import ai.nexus.core.fake.SocialPhone
import ai.nexus.core.fake.UiBuilder
import ai.nexus.core.memory.InMemoryStorage
import ai.nexus.core.memory.LongTermMemory
import ai.nexus.core.nlu.Command
import ai.nexus.core.nlu.IntentParser
import ai.nexus.core.nlu.Section
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

/** YouTube results the way the real app exposes them: one contentDescription per card, nothing marked clickable. */
class RealYouTubePhone : FakePhone() {
    var opened: String? = null
    init { foreground = "com.google.android.youtube" }
    override fun render(b: UiBuilder): Pair<String, UiNode> = foreground to b.node(b = Bounds(0, 0, 1080, 2400), children = listOf(
        b.node(cls = "android.widget.EditText", text = "filmmaking", id = "search_query", b = Bounds(100, 50, 900, 150)),
        b.node(id = "results", scrollable = true, b = Bounds(0, 200, 1080, 2400), children = listOf(
            b.node(desc = "Cameras - Sponsored - Shop now", b = Bounds(0, 220, 1080, 700), onClick = { opened = "ad" }),
            b.node(desc = "Filmmaking for beginners - 14 minutes - Go to channel - Studio - 2.1M views - 1 year ago - play video", b = Bounds(0, 720, 1080, 1250), onClick = { opened = "v1" }),
            b.node(desc = "Shorts", b = Bounds(0, 1270, 1080, 1320)),
            b.node(desc = "Cinematic trick, 900K views - play Short", b = Bounds(0, 1330, 530, 2000), onClick = { opened = "s1" }),
            b.node(desc = "Drone shot hack, 1.1M views - play Short", b = Bounds(550, 1330, 1080, 2000), onClick = { opened = "s2" }),
            b.node(desc = "Lighting 101 - 9 minutes - Go to channel - Lux - 300K views - 2 years ago - play video", b = Bounds(0, 2020, 1080, 2390), onClick = { opened = "v2" }),
        )),
    ))
}

class GoldTest {
    private fun setup(phone: FakePhone): Pair<Nexus, MutableList<String>> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val nexus = Nexus(phone, phone, LongTermMemory(InMemoryStorage()), brain = null, scope = scope)
        val out = java.util.Collections.synchronizedList(ArrayList<String>())
        scope.launch { nexus.events.collect { if (it is NexusEvent.Reply) out += it.text } }
        return nexus to out
    }
    private fun run(n: Nexus, s: String) = runBlocking { withTimeout(10_000) { n.process(s) }; kotlinx.coroutines.delay(100) }

    @Test fun firstVideoSkipsAdOnRealLayout() {
        val p = RealYouTubePhone(); val (n, out) = setup(p)
        run(n, "open the first video")
        assertEquals("v1", p.opened, "out=$out log=${p.log}")
    }

    @Test fun secondVideoAndFirstShort() {
        val p = RealYouTubePhone(); val (n, _) = setup(p)
        run(n, "play the second video"); assertEquals("v2", p.opened)
        run(n, "open the first short"); assertEquals("s1", p.opened)
        run(n, "doosra short chalao"); assertEquals("s2", p.opened)
    }

    @Test fun hinglishWordOrder() {
        val p = IntentParser()
        assertEquals(listOf<Command>(Command.OpenApp("instagram"), Command.GoTo(Section.REELS, "instagram")), p.parse("insta pe reels dikha yaar").steps.map { it.command })
        assertEquals(listOf<Command>(Command.SetToggle(SystemSetting.WIFI, false)), p.parse("wifi band kar do").steps.map { it.command })
        assertEquals(listOf<Command>(Command.OpenResult(1, "video")), p.parse("pehla video chalao").steps.map { it.command })
    }

    @Test fun phoneBrainOnlyGetsTheUnclearPart() {
        val p = SocialPhone(); val (n, _) = setup(p)
        var asked = ""
        n.phoneBrain = PhoneBrain(TextGenerator { prompt -> asked = prompt; "like" })
        run(n, "open Instagram, go to Reels, kuch mast wala karo")
        assertEquals("reels", p.igTab, "rule-understood steps must still run")
        assertTrue(asked.contains("kuch mast wala karo") && !asked.contains("go to Reels"), asked.takeLast(200))
        assertEquals(setOf(0), p.liked)
    }

    @Test fun brokenBrainDoesNotBreakNormalCommands() {
        val p = SocialPhone(); val (n, _) = setup(p)
        n.phoneBrain = PhoneBrain(TextGenerator { "garbage output !!" })
        run(n, "open Instagram, go to Reels and like it")
        assertEquals("reels", p.igTab); assertEquals(setOf(0), p.liked)
    }
}
