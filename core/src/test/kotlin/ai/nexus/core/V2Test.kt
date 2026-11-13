package ai.nexus.core

import ai.nexus.core.agent.Nexus
import ai.nexus.core.agent.NexusEvent
import ai.nexus.core.life.Briefs
import ai.nexus.core.life.Inbox
import ai.nexus.core.life.LifeEngine
import ai.nexus.core.life.LifeStore
import ai.nexus.core.life.NotificationIntel
import ai.nexus.core.life.RecipeStep
import ai.nexus.core.life.Selector
import ai.nexus.core.life.SkillMatcher
import ai.nexus.core.life.TaskItem
import ai.nexus.core.memory.InMemoryStorage
import ai.nexus.core.memory.LongTermMemory
import ai.nexus.core.nlu.Command
import ai.nexus.core.nlu.IntentParser
import ai.nexus.core.voice.VocabularyCorrector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Calendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class V2Test {
    private fun setup(phone: ai.nexus.core.fake.FakePhone): Triple<Nexus, MutableList<String>, MutableList<NexusEvent>> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val memory = LongTermMemory(InMemoryStorage())
        val life = LifeEngine(LifeStore(InMemoryStorage()), memory)
        val n = Nexus(phone, phone, memory, brain = null, scope = scope, life = life)
        val out = java.util.Collections.synchronizedList(ArrayList<String>())
        val events = java.util.Collections.synchronizedList(ArrayList<NexusEvent>())
        scope.launch { n.events.collect { events += it; if (it is NexusEvent.Reply) out += it.text } }
        return Triple(n, out, events)
    }
    private fun run(n: Nexus, s: String) = runBlocking { withTimeout(15_000) { n.process(s) }; kotlinx.coroutines.delay(150) }

    @Test fun phase3_rememberAndRecall() {
        val (n, out, _) = setup(ScreenPhone())
        run(n, "remember my gym time is 6 PM")
        run(n, "what do you know about gym")
        assertTrue(out.any { it.contains("6 PM", true) }, out.toString())
    }

    @Test fun phase4_cellMessageBecomesTaskWithDeadlineAndDraft() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 13, 10, 0) }.timeInMillis // a Friday morning
        val i = NotificationIntel.analyze("com.whatsapp", "Editorial Cell: Priya", "Nikhil, please prepare the article on drug awareness by Friday 6 PM.", "Nikhil", now)
        assertTrue(i.actionable); assertEquals("deadline", i.type)
        assertEquals("Prepare the article on drug awareness", i.title)
        val due = Calendar.getInstance().apply { timeInMillis = i.dueMs!! }
        assertEquals(18, due.get(Calendar.HOUR_OF_DAY))
        assertTrue(i.dueMs!! > now)
        assertTrue(NotificationIntel.outline(i.title).contains("Hook"))
        assertTrue(NotificationIntel.reminderTimes(i.dueMs!!, listOf(1440, 180, 60, 15), now).isNotEmpty())
        assertTrue(NotificationIntel.isSensitive("com.whatsapp", "HDFC", "Your OTP is 482913"))
        assertTrue(!NotificationIntel.analyze("com.whatsapp", "Group: Aman", "lol nice pic", "Nikhil", now).actionable)
    }

    @Test fun phase5_teachOnceThenItDoesItItself() {
        val phone = ScreenPhone().apply { foreground = "com.whatsapp" }
        val (n, out, events) = setup(phone)
        run(n, "tap the archived button")                       // nothing called that on screen
        assertNotNull(n.stuck, "should stop and ask to be taught. out=$out")
        assertTrue(events.any { it is NexusEvent.Stuck })
        n.stuckTeach()
        assertTrue(n.isTeaching)
        n.recordStep(RecipeStep("tap", "com.whatsapp", Selector(text = "Rahul Sharma", viewId = "app:id/conversations_row_contact_name", xPct = 0.4f, yPct = 0.2f)))
        run(n, "done")
        assertTrue(!n.isTeaching)
        assertEquals(1, n.life!!.data.skills.size)
        run(n, "tap the archived button")                       // same request again: uses the learned skill
        assertEquals("Rahul Sharma", phone.waChat, "out=$out")
        assertTrue(n.life!!.data.skills.single().successes >= 1)
    }

    @Test fun phase6_studyModeAppliesActionsAndLogs() {
        val phone = ScreenPhone()
        val (n, out, _) = setup(phone)
        run(n, "study mode for taxation")
        assertEquals("Study", n.life!!.data.activeMode)
        assertEquals("Taxation", n.life!!.data.activeSubject)
        assertTrue(phone.log.any { it.startsWith("toggle:DO_NOT_DISTURB:true") }, phone.log.toString())
        assertTrue(n.life!!.isBlocked("com.instagram.android"))
        run(n, "allow instagram for 10 minutes")
        assertTrue(!n.life!!.isBlocked("com.instagram.android"))
        run(n, "stop study mode")
        assertEquals(null, n.life!!.data.activeMode)
        assertTrue(phone.log.any { it.startsWith("toggle:DO_NOT_DISTURB:false") })
        assertTrue(out.any { it.contains("logged") }, out.toString())
    }

    @Test fun tasksCaptureExpensesQuiz() {
        val (n, out, _) = setup(ScreenPhone())
        run(n, "add task: submit audit notes by tomorrow 5 pm")
        run(n, "note: idea for a script about exam stress")
        run(n, "spent 250 on lunch")
        run(n, "what should I do now")
        assertTrue(out.any { it.startsWith("Do this now: Submit audit notes") }, out.toString())
        run(n, "mark audit notes as done")
        assertTrue(n.life!!.data.tasks.first { it.title.startsWith("Submit") }.done)
        assertEquals("Writing ideas", n.life!!.data.inbox.first().category)
        assertEquals(250.0, n.life!!.data.expenses.single().amount)
        run(n, "add flashcard for taxation: GST full form | Goods and Services Tax")
        run(n, "quiz me on tax")
        assertTrue(out.any { it.contains("GST full form") }, out.toString())
        run(n, "goods and services tax")
        assertTrue(out.any { it.startsWith("Correct") }, out.toString())
        run(n, "morning brief")
        assertTrue(out.any { it.startsWith("Good morning, Boss") }, out.toString())
    }

    @Test fun skillParameters() {
        val sk = SkillMatcher.build("send report", "send report to rahul on telegram", listOf(
            RecipeStep("open_app", "org.telegram.messenger"),
            RecipeStep("tap", "org.telegram.messenger", Selector(text = "Rahul")),
            RecipeStep("type", "org.telegram.messenger", text = "report"),
        ))
        val hit = SkillMatcher.match("send notes to priya on telegram", listOf(sk))
        assertNotNull(hit, "triggers=${sk.triggers}")
        assertEquals("priya", hit.slots["name"]); assertEquals("notes", hit.slots["message"])
    }

    @Test fun speechCorrection() {
        val v = VocabularyCorrector()
        v.update(listOf("Rahul", "WhatsApp", "Priya Sharma"), mapOf("aponar" to "open"))
        assertEquals("call Rahul", v.correct("call rahool"))
        assertEquals("message Priya Sharma", v.correct("message pria sharma"))
        assertEquals("open settings", v.correct("aponar settings"))
        assertEquals("open the next reel", v.correct("open the next reel"))
    }

    @Test fun parsing() {
        val p = IntentParser()
        assertEquals(listOf<Command>(Command.StartMode("study", "taxation")), p.parse("start study mode for taxation").steps.map { it.command })
        assertEquals(listOf<Command>(Command.StopMode("gym")), p.parse("stop gym mode").steps.map { it.command })
        assertEquals(listOf<Command>(Command.MeantInstead("Rahul Sharma")), p.parse("no, I meant Rahul Sharma").steps.map { it.command })
        assertEquals(listOf<Command>(Command.PreferApp(IntentParser.SAME_CONTACT, "whatsapp")), p.parse("always use WhatsApp for him").steps.map { it.command })
        assertEquals(listOf<Command>(Command.WhatNow), p.parse("what should I do now").steps.map { it.command })
        assertEquals(Command.Ringer("silent"), p.parse("silent mode").steps.single().command)
        assertEquals("Expenses", Inbox.categorize("spent 300 rs on books"))
        assertEquals(1, Briefs.rank(listOf(TaskItem(title = "a", priority = 3), TaskItem(title = "b", priority = 1, dueMs = System.currentTimeMillis() + 3600_000)), null, System.currentTimeMillis()).indexOfFirst { it.title == "a" })
    }

    @Test fun offlineBrainAnswersQuestionsWithoutAnyApiKey() {
        val (n, out, _) = setup(ScreenPhone())
        var prompts = 0
        n.phoneBrain = ai.nexus.core.agent.PhoneBrain(ai.nexus.core.agent.TextGenerator { p ->
            prompts++
            if (p.contains("British butler")) "GST is a single tax on goods and services across India, Boss.<|im_end|>" else "unknown"
        })
        run(n, "explain GST in simple words")
        assertTrue(out.any { it.contains("single tax") }, out.toString())
        assertTrue(out.none { it.contains("API", true) }, out.toString())
        run(n, "can you talk to me?")
        assertTrue(out.last().contains("right here"), out.toString())
    }

    @Test fun withoutBrainItSaysHowToGetOneNotApiKey() {
        val (n, out, _) = setup(ScreenPhone())
        run(n, "why is the sky blue?")
        assertTrue(out.any { it.contains("Download brain") }, out.toString())
    }
}
