package ai.nexus.core

import ai.nexus.core.device.Direction
import ai.nexus.core.device.GlobalAction
import ai.nexus.core.device.MediaKey
import ai.nexus.core.device.SystemSetting
import ai.nexus.core.nlu.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntentParserTest {
    private val p = IntentParser()
    private fun cmds(s: String) = p.parse(s).steps.map { it.command }

    @Test fun openAppVariants() {
        val expected = listOf(Command.OpenApp("Instagram"))
        for (s in listOf(
            "Open Instagram.", "Please open Instagram.", "Open Instagram please.", "Can you open Instagram for me?",
            "Take me to Instagram.", "Hey Nexus, I want to check Instagram.", "Instagram kholo", "Instagram open karo",
            "इंस्टाग्राम खोलो", "insta gram kholo", "Instagram",
        )) {
            val c = cmds(s)
            assertEquals(1, c.size, "for '$s': $c")
            val app = (c[0] as? Command.OpenApp)?.app
            assertTrue(app != null && Fuzzy.normalize(app) == "instagram", "for '$s': $c vs $expected")
        }
    }

    @Test fun backVariants() {
        for (s in listOf("take me back", "go back please", "return to the previous page", "ab pichhe jao", "wapas jao", "पीछे जाओ", "back")) {
            assertEquals(listOf<Command>(Command.Navigate(GlobalAction.BACK)), cmds(s), s)
        }
    }

    @Test fun instagramWorkflow() {
        val c = cmds("Hey Nexus, open Instagram, go to Reels, find the next Reel, like it, save it, open the comments and write nice video.")
        assertEquals(
            listOf(
                Command.OpenApp("Instagram"), Command.GoTo(Section.REELS), Command.NextItem,
                Command.React(Reaction.LIKE), Command.React(Reaction.SAVE), Command.OpenComments,
                Command.Write("nice video", null),
            ),
            c,
        )
    }

    @Test fun youtubeWorkflow() {
        val c = cmds("Open YouTube, search for filmmaking, play the first video, like it, open comments, write amazing video and go back.")
        assertEquals(
            listOf(
                Command.OpenApp("YouTube"), Command.Search("filmmaking"), Command.OpenResult(1, "video"),
                Command.React(Reaction.LIKE), Command.OpenComments, Command.Write("amazing video", null),
                Command.Navigate(GlobalAction.BACK),
            ),
            c,
        )
    }

    @Test fun whatsappWorkflow() {
        val c = cmds("Open WhatsApp, find Rahul and send him a message saying I'll reach in ten minutes.")
        assertEquals(
            listOf(
                Command.OpenApp("WhatsApp"), Command.OpenChat("Rahul", "WhatsApp"),
                Command.SendMessage(IntentParser.SAME_CONTACT, "I'll reach in ten minutes", null, true),
            ),
            c,
        )
    }

    @Test fun messagePayloadIsNotSplit() {
        val c = cmds("message Rahul saying I'll come and call you later")
        assertEquals(listOf<Command>(Command.SendMessage("Rahul", "I'll come and call you later", null, true)), c)
    }

    @Test fun draftVsSend() {
        val c = cmds("type hello how are you but don't send it yet")
        assertEquals(listOf<Command>(Command.Write("hello how are you", false)), c)
        val d = cmds("Rahul ko message bhejo ki main das minute mein pahunch jaunga")
        assertEquals(listOf<Command>(Command.SendMessage("Rahul", "main das minute mein pahunch jaunga", null, true)), d)
    }

    @Test fun calls() {
        assertEquals(listOf<Command>(Command.Call("Rahul")), cmds("Call Rahul"))
        assertEquals(listOf<Command>(Command.Call("Rahul")), cmds("Rahul ko call karo"))
        assertEquals(listOf<Command>(Command.FindContact("Ankit")), cmds("Find Ankit's number"))
        assertEquals(listOf<Command>(Command.Call(IntentParser.SAME_CONTACT, numberIndex = 2)), cmds("Call the second number for this contact"))
        assertEquals(listOf<Command>(Command.OpenApp("phone")), cmds("Open the dialler"))
    }

    @Test fun systemControls() {
        assertEquals(listOf<Command>(Command.SetToggle(SystemSetting.WIFI, false)), cmds("turn off wifi"))
        assertEquals(listOf<Command>(Command.SetToggle(SystemSetting.FLASHLIGHT, true)), cmds("torch jalao"))
        assertEquals(listOf<Command>(Command.SetToggle(SystemSetting.BLUETOOTH, true)), cmds("bluetooth on karo"))
        assertEquals(listOf<Command>(Command.SetLevel(Level.VOLUME, 50, null)), cmds("set volume to 50%"))
        assertEquals(listOf<Command>(Command.SetLevel(Level.BRIGHTNESS, null, 15)), cmds("increase brightness"))
    }

    @Test fun media() {
        assertEquals(listOf<Command>(Command.OpenApp("Spotify"), Command.PlayMedia("Skyfall", null)), cmds("Open Spotify and play Skyfall"))
        assertEquals(listOf<Command>(Command.Media(MediaKey.PAUSE)), cmds("Pause the music"))
        assertEquals(listOf<Command>(Command.Media(MediaKey.NEXT)), cmds("Play the next track"))
    }

    @Test fun screen() {
        assertEquals(listOf<Command>(Command.DescribeScreen), cmds("What is currently on my screen?"))
        assertEquals(listOf<Command>(Command.ReadScreen), cmds("Read this page"))
        assertEquals(listOf<Command>(Command.FindOnScreen("search option")), cmds("Find the search option"))
        assertEquals(listOf<Command>(Command.OpenResult(2)), cmds("Open the second result"))
        assertEquals(listOf<Command>(Command.Scroll(Direction.DOWN)), cmds("Scroll down"))
        assertEquals(listOf<Command>(Command.NextItem), cmds("scroll to the next Reel"))
        assertEquals(listOf<Command>(Command.GoTo(Section.REELS)), cmds("play reels"))
    }

    @Test fun controlAndCorrections() {
        assertEquals(listOf<Command>(Command.ControlTask(Control.STOP)), cmds("Stop."))
        assertEquals(listOf<Command>(Command.ControlTask(Control.STOP)), cmds("Cancel that"))
        assertEquals(listOf<Command>(Command.ControlTask(Control.PAUSE)), cmds("Wait"))
        assertEquals(listOf<Command>(Command.ControlTask(Control.RESUME)), cmds("Continue"))
        assertEquals(listOf<Command>(Command.ControlTask(Control.WRONG)), cmds("That's not what I meant"))
        assertEquals(listOf<Command>(Command.Correction(Command.OpenResult(2))), cmds("Open the second result instead"))
        assertEquals(listOf<Command>(Command.Repeat), cmds("do the same thing again"))
    }

    @Test fun chatVsCommand() {
        assertTrue(cmds("What's the weather like?").single() is Command.Chat)
        assertTrue(cmds("Open the weather application").single() is Command.OpenApp)
        assertTrue(cmds("Find the weather forecast and tell me whether I should carry an umbrella").single() is Command.Agentic)
        assertTrue(cmds("Explain how black holes form").single() is Command.Chat)
    }

    @Test fun filesAndEmail() {
        assertEquals(listOf<Command>(Command.FindFile("yesterday", "application/pdf", true)), cmds("Find the PDF I downloaded yesterday"))
        assertEquals(listOf<Command>(Command.FindFile("", "application/", true)), cmds("Open my latest document"))
        assertEquals(listOf<Command>(Command.OpenApp("Gmail")), cmds("Open Gmail"))
    }

    @Test fun followUps() {
        assertEquals(listOf<Command>(Command.React(Reaction.LIKE)), cmds("Like this."))
        assertEquals(listOf<Command>(Command.React(Reaction.SAVE)), cmds("Save it."))
        assertEquals(listOf<Command>(Command.NextItem), cmds("Find another one."))
        assertEquals(listOf<Command>(Command.OpenComments, Command.Write("amazing video", true)), cmds("Write a comment saying amazing video"))
        assertEquals(listOf<Command>(Command.Write("amazing video", null)), cmds("Write amazing video."))
        assertEquals(listOf<Command>(Command.React(Reaction.FOLLOW)), cmds("Follow this account"))
        assertEquals(listOf<Command>(Command.React(Reaction.SUBSCRIBE)), cmds("subscribe to this channel"))
    }
}
