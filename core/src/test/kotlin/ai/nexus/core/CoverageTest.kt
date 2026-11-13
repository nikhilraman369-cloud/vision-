package ai.nexus.core

import ai.nexus.core.nlu.Command
import ai.nexus.core.nlu.IntentParser
import kotlin.test.Test
import kotlin.test.assertTrue

/** Everyday sentences that must be understood by the rules (no AI). */
class CoverageTest {
    private val p = IntentParser()

    val sentences = listOf(
        // apps & navigation
        "open youtube", "yt kholo", "launch spotify", "chrome open karo", "go to home screen", "home jao", "show recent apps",
        "switch to the previous app", "close this popup", "go back twice", "take a screenshot", "screenshot lo", "lock the phone",
        "open settings", "open wifi settings", "open battery settings", "open camera", "open gallery", "open play store",
        // screen
        "what's on my screen", "screen pe kya hai", "read this page", "read it out loud", "scroll down", "scroll up a bit", "neeche scroll karo",
        "scroll to the bottom", "swipe left", "tap on subscribe", "click the share button", "press next", "long press the photo",
        "select the first option", "open the third result", "teesra wala kholo", "find the search button", "what does this button do",
        // typing
        "type hello how are you", "likho main aa raha hoon", "send ok", "send it", "reply saying sure", "comment nice video",
        "comment karo bahut badhiya", "write amazing on this video", "type I'll be there in 10 minutes and send", "copy this", "paste it here",
        // youtube
        "search filmmaking on youtube", "youtube pe comedy video dhundo", "play the first video", "open the first short", "pehla short chalao",
        "go to shorts", "next short", "skip this video", "like this video", "dislike it", "subscribe to this channel", "save to watch later",
        "open comments", "pause", "resume the video", "skip forward 30 seconds", "rewind 10 seconds", "play the next video",
        // instagram
        "open instagram and go to reels", "insta pe reels dikha", "next reel", "agli reel", "previous reel", "like it", "save this reel",
        "unlike", "follow this account", "unfollow", "open comments and write wow", "open my messages", "message rahul on instagram saying hi",
        "search nasa on instagram", "open this person's profile", "share this reel",
        // whatsapp / messaging / calls
        "open whatsapp", "open the family group", "editorial wala group kholo", "open chat with rahul", "message rahul saying I'll be late",
        "rahul ko bolo ki main late hoon", "send rahul a text on whatsapp saying ok", "whatsapp ankit that I reached", "read my latest messages",
        "what did rahul say", "reply to rahul saying thanks", "call rahul", "rahul ko call karo", "call mom", "call the second number",
        "video call rahul on whatsapp", "end the call", "find ankit's number", "send an sms to rahul saying call me", "read my notifications",
        "any new notifications", "open the latest notification", "clear all notifications",
        // phone controls
        "turn on the flashlight", "torch band karo", "turn off wifi", "bluetooth on", "enable do not disturb", "silent mode on",
        "vibrate mode", "ring mode", "volume 60", "volume up", "mute", "awaaz kam karo", "brightness 20 percent", "brightness badhao",
        "dark mode on", "auto rotate off", "open hotspot settings", "battery kitni hai", "what time is it",
        // music & media
        "play believer", "play skyfall on spotify", "kesariya bajao", "pause the music", "next song", "previous track", "play my favourite song",
        // files, email, web
        "open my latest photo", "find the pdf i downloaded yesterday", "share this file", "open gmail", "compose an email to ankit about the report",
        "search for best phones under 20000", "open google.com", "open chrome and search weather in delhi", "navigate to connaught place",
        "take me to the airport", "directions to home",
        // productivity
        "set an alarm for 6:30", "wake me up at 7", "subah 7 baje alarm lagao", "timer 5 minutes", "remind me to drink water in 20 minutes",
        "remind me to call mom at 6 pm", "note buy milk", "read my notes", "what is 15% of 2400", "25 times 4", "add meeting with rahul tomorrow at 5 pm",
        "create an event dentist on friday at 10 am",
        // memory / teaching / control
        "remember that mom means sunita sharma", "when i say good night turn off wifi and set brightness to 10", "watch me", "done call it morning",
        "do the same thing again", "stop", "wait", "continue", "that's not what i meant", "open the second one instead", "hide the bubble",
        "forget everything", "take a photo", "take a selfie", "call back", "redial",
    )

    @Test fun everydaySentencesAreUnderstoodWithoutAi() {
        val gaps = sentences.mapNotNull { s ->
            val steps = p.parse(s).steps
            val bad = steps.isEmpty() || steps.any { it.command is Command.Agentic || (it.command is Command.Chat && !ai.nexus.core.agent.OfflineChat.handles(s)) }
            if (bad) "$s -> $steps" else null
        }
        println("COVERAGE gaps=${gaps.size}/${sentences.size}\n" + gaps.joinToString("\n"))
        assertTrue(gaps.isEmpty(), "Not understood:\n" + gaps.joinToString("\n"))
    }
}
