package ai.nexus.core

import ai.nexus.core.agent.Nexus
import ai.nexus.core.agent.NexusEvent
import ai.nexus.core.life.Briefs
import ai.nexus.core.life.Inbox
import ai.nexus.core.life.LifeEngine
import ai.nexus.core.life.LifeStore
import ai.nexus.core.life.NotificationIntel
import ai.nexus.core.life.RecipeStep
import ai.nexus.core.life.pretty
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

    private fun one(text: String): Command = IntentParser().parse(text).steps.single().command

    @Test fun openEndedAndTimedRequestsAreUnderstood() {
        assertEquals(Command.AutoScroll(ai.nexus.core.device.Direction.UP, 2), one("keep scrolling up"))
        assertEquals(Command.AutoScroll(ai.nexus.core.device.Direction.DOWN, 1), one("keep scrolling down slowly"))
        assertEquals(Command.AutoScroll(ai.nexus.core.device.Direction.DOWN, 1), one("scroll down slowly"))
        assertEquals(ai.nexus.core.device.Direction.UP, (one("upar scroll karte raho") as Command.AutoScroll).direction)
        assertEquals(Command.RepeatEvery(15, "next"), one("scroll the reel after 15 seconds"))
        assertEquals(10, (one("next reel every 10 seconds") as Command.RepeatEvery).seconds)
        assertEquals(Command.After(10, "turn on the torch"), one("after 10 seconds turn on the torch"))
        assertEquals(300, (one("call mom in 5 minutes") as Command.After).seconds)
        assertTrue(one("message rahul saying i will be there in 10 minutes") is Command.SendMessage)
        assertTrue(IntentParser().parse("remind me to drink water in 30 minutes").steps.none { it.command is Command.After })
        val s1 = one("schedule a message to rahul tomorrow at 9 pm saying happy birthday") as Command.ScheduleAt
        assertEquals("tomorrow at 9 pm", s1.whenText); assertTrue(s1.command.contains("happy birthday") && s1.command.contains("rahul"), s1.command)
        assertTrue(one("at 9 pm message rahul saying happy birthday") is Command.ScheduleAt)
        assertTrue(one("message rahul at 9 pm saying happy birthday") is Command.ScheduleAt)
        assertTrue(one("kal subah 9 baje rahul ko message karo ki happy birthday") is Command.ScheduleAt)
        assertEquals(Command.LoopSpeed(true), one("faster"))
        assertEquals(Command.ListScheduled, one("what have i scheduled"))
    }

    @Test fun timeExpressionsResolveToTheFuture() {
        val now = Calendar.getInstance().apply { set(2026, 10, 13, 15, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        fun at(e: String) = Calendar.getInstance().apply { timeInMillis = ai.nexus.core.nlu.Timing.resolve(e, now)!! }
        at("tomorrow at 9 pm").let { assertEquals(14, it.get(Calendar.DAY_OF_MONTH)); assertEquals(21, it.get(Calendar.HOUR_OF_DAY)) }
        at("at 9").let { assertEquals(13, it.get(Calendar.DAY_OF_MONTH)); assertEquals(21, it.get(Calendar.HOUR_OF_DAY)) }
        at("kal subah 9 baje").let { assertEquals(14, it.get(Calendar.DAY_OF_MONTH)); assertEquals(9, it.get(Calendar.HOUR_OF_DAY)) }
        at("tonight").let { assertEquals(13, it.get(Calendar.DAY_OF_MONTH)); assertEquals(21, it.get(Calendar.HOUR_OF_DAY)) }
        at("raat 2 baje").let { assertEquals(14, it.get(Calendar.DAY_OF_MONTH)); assertEquals(2, it.get(Calendar.HOUR_OF_DAY)) }
    }

    @Test fun keepScrollingRunsUntilStop() {
        val phone = ScreenPhone()
        val (n, _, _) = setup(phone)
        run(n, "keep scrolling down")
        assertNotNull(n.loop.value)
        runBlocking { kotlinx.coroutines.delay(2600) }
        run(n, "stop")
        val count = phone.log.count { it.startsWith("swipe:") }
        assertTrue(count >= 1, phone.log.toString())
        runBlocking { kotlinx.coroutines.delay(1500) }
        assertEquals(count, phone.log.count { it.startsWith("swipe:") }, "no swipes after stop")
        assertEquals(null, n.loop.value)
    }

    @Test fun scheduleListAndCancel() {
        val (n, out, _) = setup(ScreenPhone())
        val armed = ArrayList<String>()
        n.onSchedule = { armed += it.id }
        run(n, "at 9 pm message rahul saying happy birthday")
        assertEquals(1, n.life!!.data.scheduled.size)
        assertEquals(1, armed.size)
        run(n, "what have i scheduled")
        assertTrue(out.last().contains("happy birthday"), out.toString())
        run(n, "cancel the scheduled message to rahul")
        assertTrue(n.life!!.data.scheduled.isEmpty())
    }

    @Test fun taskIsAnnouncedStartToFinish() {
        val (n, out, _) = setup(ScreenPhone())
        run(n, "open YouTube")
        assertTrue(out.first().startsWith("On it") || out.first().startsWith("Right away") || out.first().startsWith("Certainly"), out.toString())
        assertTrue(out.last().startsWith("Opened"), out.toString())
        out.clear()
        run(n, "turn on the torch and set volume to 40")
        assertTrue(out.first().contains("Boss"), out.toString())
        assertTrue(out.last().startsWith("All done"), out.toString())
    }

    @Test fun greetingsAreAnsweredOutLoud() {
        val (n, out, events) = setup(ScreenPhone())
        run(n, "hello")
        assertTrue(out.isNotEmpty(), "must reply")
        assertTrue(events.filterIsInstance<NexusEvent.Reply>().all { it.speak }, "replies must be marked to be spoken")
    }

    @Test fun namedTeachingSavesOnDoneAndCancelForgets() {
        val (n, out, _) = setup(ScreenPhone())
        n.teach("send good morning to Editorial Team")
        assertTrue(n.isTeaching); assertEquals("send good morning to Editorial Team", n.teachingName)
        n.recordStep(RecipeStep("open_app", "com.whatsapp", Selector(text = "WhatsApp")))
        n.recordStep(RecipeStep("tap", "com.whatsapp", Selector(text = "Editorial Team")))
        n.recordStep(RecipeStep("type", "com.whatsapp", text = "good morning"))
        assertEquals(3, n.teachSteps.value)
        n.finishTeaching()                       // no name prompt: it already has one
        runBlocking { kotlinx.coroutines.delay(600) }
        assertEquals(1, n.life!!.data.skills.size)
        assertTrue(out.any { it.startsWith("Learned") }, out.toString())
        assertTrue(n.life!!.data.skills[0].steps.map { it.pretty() }.any { it.contains("Editorial Team") })
        // cancel throws the recording away
        n.teach("something else"); n.recordStep(RecipeStep("tap", "x", Selector(text = "Y")))
        n.cancelTeaching()
        assertTrue(!n.isTeaching); assertEquals(0, n.teachSteps.value); assertEquals(1, n.life!!.data.skills.size)
    }
}
