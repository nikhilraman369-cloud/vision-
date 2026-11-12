package ai.nexus.core

import ai.nexus.core.agent.Nexus
import ai.nexus.core.agent.NexusEvent
import ai.nexus.core.device.Contact
import ai.nexus.core.fake.SocialPhone
import ai.nexus.core.memory.InMemoryStorage
import ai.nexus.core.memory.LongTermMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NexusAgentTest {
    private fun setup(phone: SocialPhone = SocialPhone()): Triple<SocialPhone, Nexus, MutableList<String>> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val nexus = Nexus(phone, phone, LongTermMemory(InMemoryStorage()), brain = null, scope = scope)
        val replies = java.util.Collections.synchronizedList(ArrayList<String>())
        scope.launch { nexus.events.collect { if (it is NexusEvent.Reply) replies += it.text } }
        return Triple(phone, nexus, replies)
    }

    private fun run(n: Nexus, s: String) = runBlocking { withTimeout(10_000) { n.process(s) }; kotlinx.coroutines.delay(150) }

    @Test fun instagramReelWorkflowEndToEnd() {
        val (phone, nexus, replies) = setup()
        run(nexus, "Hey Nexus, open Instagram, go to Reels, find the next Reel, like it, save it, open the comments and write nice video.")
        assertEquals("reels", phone.igTab)
        assertEquals(1, phone.reel)
        assertTrue(1 in phone.liked, "liked: ${phone.liked} log=${phone.log}")
        assertTrue(1 in phone.saved)
        assertEquals<List<String>?>(listOf("nice video"), phone.comments[1]?.toList(), "log=${phone.log} replies=$replies")
        assertTrue(phone.liked.size == 1 && 0 !in phone.liked, "only reel 1 liked")
    }

    @Test fun followUpCommandsKeepContext() {
        val (phone, nexus, _) = setup()
        run(nexus, "Open Instagram")
        run(nexus, "Go to Reels")
        run(nexus, "Find another one")
        run(nexus, "Like this")
        run(nexus, "Save it")
        assertEquals(1, phone.reel)
        assertEquals(setOf(1), phone.liked)
        assertEquals(setOf(1), phone.saved)
    }

    @Test fun alreadyLikedIsNotTappedAgain() {
        val (phone, nexus, replies) = setup()
        phone.foreground = "com.instagram.android"; phone.igTab = "reels"; phone.liked += 0
        run(nexus, "like it")
        assertEquals(setOf(0), phone.liked)
        assertTrue(phone.log.none { it.startsWith("click:") }, phone.log.toString())
        assertTrue(replies.any { it.contains("already liked") }, replies.toString())
    }

    @Test fun unverifiableLikeIsReportedAndWorkflowContinues() {
        val (phone, nexus, replies) = setup()
        phone.foreground = "com.instagram.android"; phone.igTab = "reels"; phone.brokenLike = true
        run(nexus, "like it and save it")
        assertEquals(1, phone.log.count { it == "click:Like" }, "must not blindly re-tap: ${phone.log}")
        assertTrue(0 in phone.saved, "save should still happen")
        assertTrue(replies.any { it.contains("couldn't confirm") || it.contains("nothing visibly changed") || it.contains("still looks") }, replies.toString())
    }

    @Test fun youtubeSearchSkipsAdAndLikes() {
        val (phone, nexus, _) = setup()
        run(nexus, "Open YouTube, search for filmmaking, play the first video and like it")
        assertEquals("filmmaking", phone.ytQuery)
        assertEquals("v1", phone.ytVideo)
        assertTrue(phone.ytLiked)
    }

    @Test fun ambiguousContactAsksThenCallsChosenNumber() {
        val (phone, nexus, replies) = setup()
        phone.contactsDb = listOf(Contact("Rahul Sharma", listOf("+919800000001")), Contact("Rahul Verma", listOf("+919800000002")))
        run(nexus, "Call Rahul")
        assertTrue(replies.any { it.contains("Which one") }, replies.toString())
        run(nexus, "the second one")
        assertTrue("call:+919800000002" in phone.log, phone.log.toString())
    }

    @Test fun contactAliasFromMemory() {
        val (phone, nexus, _) = setup()
        phone.contactsDb = listOf(Contact("Sunita Sharma", listOf("+919800000009")))
        run(nexus, "remember that mom means Sunita Sharma")
        run(nexus, "call mom")
        assertTrue("call:+919800000009" in phone.log, phone.log.toString())
    }

    @Test fun repeatRunsTheSameStepsAgain() {
        val (phone, nexus, _) = setup()
        phone.foreground = "com.instagram.android"; phone.igTab = "reels"
        run(nexus, "next reel and like it")
        run(nexus, "do the same thing again")
        assertEquals(2, phone.reel)
        assertEquals(setOf(1, 2), phone.liked)
    }

    @Test fun stopCancelsRunningTaskMidWay() = runBlocking {
        val phone = object : SocialPhone() {
            override suspend fun awaitChange(previousFingerprint: Int?, timeoutMs: Long): ai.nexus.core.screen.ScreenSnapshot? {
                kotlinx.coroutines.delay(150); return super.awaitChange(previousFingerprint, timeoutMs)
            }
        }
        val (_, nexus, replies) = setup(phone)
        phone.foreground = "com.instagram.android"; phone.igTab = "reels"
        nexus.submit("next reel, next reel, next reel, next reel, next reel, next reel")
        kotlinx.coroutines.delay(400)
        nexus.submit("Stop").join()
        val reached = phone.reel
        kotlinx.coroutines.delay(1200)
        assertTrue(reached < 6, "should stop early, reached $reached")
        assertEquals(reached, phone.reel, "nothing should run after stop")
        assertTrue(replies.contains("Stopped."), replies.toString())
    }

    @Test fun pauseAndResume() = runBlocking {
        val phone = object : SocialPhone() {
            override suspend fun awaitChange(previousFingerprint: Int?, timeoutMs: Long): ai.nexus.core.screen.ScreenSnapshot? {
                kotlinx.coroutines.delay(120); return super.awaitChange(previousFingerprint, timeoutMs)
            }
        }
        val (_, nexus, _) = setup(phone)
        phone.foreground = "com.instagram.android"; phone.igTab = "reels"
        val job = nexus.submit("next reel, next reel, next reel, next reel")
        kotlinx.coroutines.delay(200)
        nexus.submit("wait").join()
        val atPause = phone.reel
        kotlinx.coroutines.delay(800)
        assertTrue(phone.reel <= atPause + 1, "paused: ${phone.reel} vs $atPause")
        nexus.submit("continue").join()
        withTimeout(10_000) { while (phone.reel < 4) kotlinx.coroutines.delay(50) }
        assertEquals(4, phone.reel)
        job.cancel()
    }

    @Test fun offlineChatWithoutBrain() {
        val (_, nexus, replies) = setup()
        run(nexus, "hello")
        assertTrue(replies.any { it.startsWith("Hey") }, replies.toString())
    }
}
