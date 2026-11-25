package ai.nexus.core

import ai.nexus.core.agent.Nexus
import ai.nexus.core.agent.NexusEvent
import ai.nexus.core.device.Contact
import ai.nexus.core.device.SysResult
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

/**
 * A phone with WhatsApp (chat list, chats, groups, call buttons, the "start call?" prompt, the call screen),
 * ChatGPT / Claude / Gemini (new-chat button, composer, send) and Chrome.
 */
class CatAPhone : FakePhone() {
    // WhatsApp
    var waScreen = "list"            // list | chat | prompt | call
    var waChat: String? = null
    var waSearch = ""
    var waDraft = ""
    val waSent = ArrayList<String>()
    var callTo: String? = null
    var callVideo = false
    var promptVideo = false
    /** Contact ids that have WhatsApp call entries (like a real phone after WhatsApp syncs). */
    val waEntries = HashSet<String>()
    var askPrompt = false            // WhatsApp shows "Start call?" before calling
    val chats = listOf("Rahul Sharma", "College Group", "Family Group", "Mom")

    // AI app
    var aiDraft = ""
    val aiSent = ArrayList<String>()
    var aiNewChats = 0
    val aiPkgs = setOf("com.openai.chatgpt", "com.anthropic.claude", "com.google.android.apps.bard")

    var alarm: Pair<Int, Int>? = null
    var timer: Int? = null
    var now = 0L

    override fun nowMs() = now
    override suspend fun installedApps() = super.installedApps() + listOf(
        ai.nexus.core.device.InstalledApp("ChatGPT", "com.openai.chatgpt"), ai.nexus.core.device.InstalledApp("Claude", "com.anthropic.claude"),
        ai.nexus.core.device.InstalledApp("Gemini", "com.google.android.apps.bard"), ai.nexus.core.device.InstalledApp("Chrome", "com.android.chrome"),
    )

    override suspend fun whatsappCall(contactId: String, number: String?, video: Boolean): SysResult? {
        log += "wacall:$contactId:$video"
        if (contactId !in waEntries) return null
        foreground = "com.whatsapp"; callTo = contactsDb.first { it.id == contactId }.name; callVideo = video
        waScreen = if (askPrompt) { promptVideo = video; "prompt" } else "call"
        return SysResult.Done("")
    }
    override suspend fun setAlarm(hour: Int, minute: Int, label: String?): SysResult { alarm = hour to minute; return SysResult.Done("") }
    override suspend fun setTimer(seconds: Int, label: String?): SysResult { timer = seconds; return SysResult.Done("") }

    override fun onBack() { if (foreground == "com.whatsapp") { waScreen = "list"; waChat = null } }

    override fun onUri(uri: String, pkg: String?): Boolean {
        if (uri.startsWith("https://wa.me/")) {
            val num = uri.removePrefix("https://wa.me/").substringBefore('?')
            val c = contactsDb.firstOrNull { it.numbers.any { n -> n.filter(Char::isDigit).takeLast(10) == num.takeLast(10) } } ?: return false
            foreground = "com.whatsapp"; waScreen = "chat"; waChat = c.name
            waDraft = uri.substringAfter("?text=", "").let { java.net.URLDecoder.decode(it, "UTF-8") }
            return true
        }
        if (uri.startsWith("https://www.google.com/search")) { foreground = pkg ?: "com.android.chrome"; return true }
        return false
    }

    override fun render(b: UiBuilder): Pair<String, UiNode> = when (foreground) {
        "com.whatsapp" -> foreground to whatsapp(b)
        in aiPkgs -> foreground to ai(b)
        else -> foreground to b.node(cls = "android.widget.FrameLayout", text = "Home screen")
    }

    private fun whatsapp(b: UiBuilder): UiNode = when (waScreen) {
        "call" -> b.node(b = Bounds(0, 0, 1080, 2400), children = listOf(
            b.node(text = callTo, id = "name", b = Bounds(100, 300, 980, 400)),
            b.node(text = "Ringing", id = "call_status", b = Bounds(100, 420, 980, 480)),
            b.node(desc = "End call", id = "end_call_btn", b = Bounds(440, 2000, 640, 2200), onClick = { waScreen = "list"; callTo = null }),
        ))
        "prompt" -> b.node(b = Bounds(0, 0, 1080, 2400), children = (if (waChat != null) listOf(
            // a bottom sheet over the chat: the chat's header and its call icons are still on screen
            b.node(text = waChat, id = "conversation_contact_name", b = Bounds(150, 50, 700, 150)),
            b.node(desc = "Video call", id = "menuitem_video_call", b = Bounds(760, 50, 860, 150), onClick = {}),
            b.node(desc = "Voice call", id = "menuitem_voip_call", b = Bounds(880, 50, 980, 150), onClick = {}),
        ) else emptyList()) + listOf(
            b.node(text = if (promptVideo) "Start video call?" else "Start voice call?", b = Bounds(100, 900, 980, 1000)),
            b.node(cls = "android.widget.Button", text = "Cancel", b = Bounds(100, 1100, 500, 1200), onClick = { waScreen = if (waChat != null) "chat" else "list"; callTo = null }),
            b.node(cls = "android.widget.Button", text = "Call", b = Bounds(580, 1100, 980, 1200), onClick = { waScreen = "call" }),
        ))
        "chat" -> b.node(b = Bounds(0, 0, 1080, 2400), children = listOf(
            b.node(text = waChat, id = "conversation_contact_name", b = Bounds(150, 50, 700, 150)),
            b.node(desc = "Video call", id = "menuitem_video_call", b = Bounds(760, 50, 860, 150), onClick = { callTo = waChat; callVideo = true; promptVideo = true; waScreen = if (askPrompt || waChat!!.contains("Group")) "prompt" else "call" }),
            b.node(desc = "Voice call", id = "menuitem_voip_call", b = Bounds(880, 50, 980, 150), onClick = { callTo = waChat; callVideo = false; promptVideo = false; waScreen = if (askPrompt || waChat!!.contains("Group")) "prompt" else "call" }),
            b.node(id = "list", b = Bounds(0, 200, 1080, 2100), children = waSent.mapIndexed { i, m -> b.node(text = m, id = "message_text", b = Bounds(300, 300 + i * 100, 1000, 380)) }),
            b.node(cls = "android.widget.EditText", text = waDraft.ifEmpty { null }, hint = "Message", id = "entry", b = Bounds(40, 2150, 900, 2250), onText = { waDraft = it }),
            b.node(desc = "Send", id = "send", b = Bounds(920, 2150, 1040, 2250), onClick = { if (waDraft.isNotEmpty()) { waSent += waDraft; waDraft = "" } }),
        ))
        else -> b.node(b = Bounds(0, 0, 1080, 2400), children = listOf(
            b.node(desc = "Search", id = "menuitem_search", b = Bounds(900, 50, 1000, 150), onClick = { waSearch = " " }),
        ) + (if (waSearch.isNotEmpty()) listOf(b.node(cls = "android.widget.EditText", text = waSearch.trim().ifEmpty { null }, id = "search_input", b = Bounds(40, 50, 880, 150), onText = { waSearch = it })) else emptyList()) +
            chats.filter { waSearch.isBlank() || it.contains(waSearch.trim(), true) }.mapIndexed { i, n ->
                b.node(text = n, id = "conversations_row_contact_name", b = Bounds(150, 300 + i * 150, 900, 380), onClick = { waChat = n; waScreen = "chat"; waSearch = "" })
            })
    }

    private fun ai(b: UiBuilder): UiNode = b.node(b = Bounds(0, 0, 1080, 2400), children = listOf(
        b.node(desc = "New chat", b = Bounds(950, 50, 1050, 150), onClick = { aiNewChats++; aiSent.clear() }),
        b.node(id = "messages", b = Bounds(0, 200, 1080, 2000), children = aiSent.mapIndexed { i, m -> b.node(text = m, b = Bounds(100, 300 + i * 100, 1000, 380)) }),
        b.node(cls = "android.widget.EditText", text = aiDraft.ifEmpty { null }, hint = "Ask anything", b = Bounds(40, 2150, 900, 2250), onText = { aiDraft = it }),
    ) + (if (aiDraft.isNotEmpty()) listOf(b.node(desc = "Send prompt", b = Bounds(920, 2150, 1040, 2250), onClick = { aiSent += aiDraft; aiDraft = "" })) else emptyList()))
}

class CategoryATest {
    private fun setup(phone: CatAPhone = CatAPhone()): Triple<CatAPhone, Nexus, MutableList<String>> {
        phone.contactsDb = listOf(
            Contact("Rahul Sharma", listOf("+919876543210"), "11"),
            Contact("Mom", listOf("9812345678"), "12"),
            Contact("Priya", listOf("9900112233"), "13"),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val nexus = Nexus(phone, phone, LongTermMemory(InMemoryStorage()), brain = null, scope = scope)
        nexus.refreshVocabulary(phone.contactsDb.map { it.name }, phone.contactsDb.map { it.name })
        val replies = java.util.Collections.synchronizedList(ArrayList<String>())
        scope.launch { nexus.events.collect { if (it is NexusEvent.Reply) replies += it.text } }
        return Triple(phone, nexus, replies)
    }

    private fun run(n: Nexus, s: String) = runBlocking { withTimeout(15_000) { n.process(s) }; kotlinx.coroutines.delay(150) }

    // ---------------------------------------------------------------- WhatsApp calls

    @Test fun whatsappVideoCallToContactUsesWhatsAppEntry() {
        val (p, n, r) = setup(); p.waEntries += "12"
        run(n, "whatsapp video call mom")
        assertEquals("Mom", p.callTo, "log=${p.log} replies=$r"); assertTrue(p.callVideo)
        assertEquals("call", p.waScreen)
        assertTrue(p.log.none { it.startsWith("click:") }, "no screen tapping needed: ${p.log}")
        assertTrue(r.any { it.contains("Calling Mom on WhatsApp") }, r.toString())
    }

    @Test fun whatsappVoiceCallAcceptsStartCallPrompt() {
        val (p, n, r) = setup(); p.waEntries += "11"; p.askPrompt = true
        run(n, "rahul ko whatsapp call karo")
        assertEquals("call", p.waScreen, "log=${p.log} replies=$r"); assertEquals("Rahul Sharma", p.callTo); assertTrue(!p.callVideo)
        assertTrue(p.log.contains("click:Call") && !p.log.contains("click:Cancel"), p.log.toString())
    }

    @Test fun whatsappCallWithoutEntryUsesChatButton() {
        val (p, n, r) = setup()   // no WhatsApp entries synced
        run(n, "call priya on whatsapp")
        assertEquals("Priya", p.callTo, "log=${p.log} replies=$r")
        assertTrue(p.log.any { it.startsWith("uri:https://wa.me/919900112233") }, p.log.toString())
        assertTrue(p.log.contains("click:Voice call"), p.log.toString())
        assertEquals("call", p.waScreen)
    }

    @Test fun whatsappGroupVideoCallFindsGroupAndConfirms() {
        val (p, n, r) = setup()
        run(n, "video call college group")
        assertEquals("College Group", p.callTo, "log=${p.log} replies=$r"); assertTrue(p.callVideo)
        assertTrue(p.log.contains("click:Video call") && p.log.contains("click:Call"), p.log.toString())
        assertEquals("call", p.waScreen)
    }

    @Test fun plainCallStaysAPhoneCall() {
        val (p, n, _) = setup(); p.waEntries += "12"
        run(n, "call mom")
        assertTrue(p.log.contains("call:9812345678"), p.log.toString())
        assertTrue(p.log.none { it.startsWith("wacall") }, p.log.toString())
    }

    // ---------------------------------------------------------------- WhatsApp messages

    @Test fun messageSplitsKnownContactFromText() {
        val (p, n, r) = setup()
        run(n, "message rahul hi kaise ho")
        assertEquals(listOf("hi kaise ho"), p.waSent, "log=${p.log} replies=$r")
        assertEquals("Rahul Sharma", p.waChat)
    }

    @Test fun hindiMessageToContact() {
        val (p, n, r) = setup()
        run(n, "mom ko bolo ki khana bana do")
        assertEquals(listOf("khana bana do"), p.waSent, "log=${p.log} replies=$r")
        assertEquals("Mom", p.waChat)
    }

    @Test fun messageToGroupFindsTheGroupChat() {
        val (p, n, r) = setup(); p.foreground = "com.whatsapp"
        run(n, "college group mein bhejo class cancelled")
        assertEquals("College Group", p.waChat, "log=${p.log} replies=$r")
        assertEquals(listOf("class cancelled"), p.waSent)
    }

    @Test fun draftIsNotSent() {
        val (p, n, r) = setup()
        run(n, "type hello to rahul but don't send")
        assertTrue(p.waSent.isEmpty(), "log=${p.log} replies=$r"); assertEquals("hello", p.waDraft)
    }

    // ---------------------------------------------------------------- AI apps

    @Test fun askChatGptFromAnywhereStartsNewChatAndSends() {
        val (p, n, r) = setup()
        run(n, "ask chatgpt what is quantum computing and why is it hard")
        assertEquals("com.openai.chatgpt", p.foreground, "log=${p.log}")
        assertEquals(1, p.aiNewChats, "log=${p.log}")
        assertEquals(listOf("what is quantum computing and why is it hard"), p.aiSent, "log=${p.log} replies=$r")
        assertTrue(r.any { it.contains("Asked ChatGPT") }, r.toString())
    }

    @Test fun askWhileAlreadyInAppContinuesTheConversation() {
        val (p, n, _) = setup(); p.foreground = "com.anthropic.claude"; p.aiSent += "earlier question"
        run(n, "ask claude to make it shorter")
        assertEquals(0, p.aiNewChats, "log=${p.log}")
        assertEquals(listOf("earlier question", "make it shorter"), p.aiSent)
    }

    @Test fun hindiAskGemini() {
        val (p, n, r) = setup()
        run(n, "gemini se pucho aaj ka weather kaisa hai")
        assertEquals("com.google.android.apps.bard", p.foreground, "log=${p.log}")
        assertEquals(listOf("aaj ka weather kaisa hai"), p.aiSent, "log=${p.log} replies=$r")
    }

    // ---------------------------------------------------------------- search, alarms, timers

    @Test fun googleSearchOpensInChrome() {
        val (p, n, _) = setup()
        run(n, "google karo best phones under 20000")
        assertTrue(p.log.any { it == "uri:https://www.google.com/search?q=best+phones+under+20000" }, p.log.toString())
        assertEquals("com.android.chrome", p.foreground)
    }

    @Test fun alarmWithoutAmPmRingsAtTheNextOne() {
        val (p, n, r) = setup()
        val cal = java.util.Calendar.getInstance().apply { set(2026, 10, 25, 15, 0, 0) }   // 3 PM
        p.now = cal.timeInMillis
        run(n, "alarm laga do 7 baje")
        assertEquals(19 to 0, p.alarm, r.toString())
        assertTrue(r.any { it.contains("7:00 PM") }, r.toString())
        p.now = java.util.Calendar.getInstance().apply { set(2026, 10, 25, 21, 0, 0) }.timeInMillis   // 9 PM
        run(n, "7 baje ka alarm")
        assertEquals(7 to 0, p.alarm)
        run(n, "shaam 5 baje alarm laga do")
        assertEquals(17 to 0, p.alarm)
    }

    @Test fun hindiTimerAndReminder() {
        val (p, n, _) = setup()
        run(n, "5 minute ka timer lagao"); assertEquals(300, p.timer)
        run(n, "mujhe 10 minute mein yaad dilana pani peena"); assertEquals(600, p.timer)
    }

    @Test fun torchCameraVolume() {
        val (p, n, _) = setup()
        run(n, "torch jalao"); assertTrue(p.log.contains("toggle:FLASHLIGHT:true"), p.log.toString())
        run(n, "torch band karo"); assertTrue(p.log.contains("toggle:FLASHLIGHT:false"), p.log.toString())
    }
}
