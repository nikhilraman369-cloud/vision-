package ai.nexus.core.nlu

import ai.nexus.core.device.Direction
import ai.nexus.core.device.GlobalAction
import ai.nexus.core.device.MediaKey
import ai.nexus.core.device.SystemSetting

/**
 * A tiny line-based action language. It is what the on-device brain writes
 * (short output = fast), what learned phrases are cached as, and what
 * "watch me" routines are stored as. One action per line:
 *
 *   open_app YouTube        tap Editorial          type do it later
 *   type_send hello         send                   search filmmaking
 *   open_result 1           goto reels             next / previous
 *   like / unlike / save / unsave / follow / unfollow / subscribe / unsubscribe
 *   comments                comment nice video     play Believer
 *   back / home / recents / notifications          scroll down
 *   message Rahul | I'll be late                   call Rahul
 *   toggle wifi on          volume 50 / volume up  brightness down
 *   pause / resume / next_track / previous_track
 *   read_screen / describe_screen / read_notifications
 *   alarm 7:30 | gym        timer 300 | tea        note buy milk
 *   chat <question>         unknown
 */
object CommandDsl {

    fun toLines(commands: List<Command>): String = commands.mapNotNull(::toLine).joinToString("\n")

    fun toLine(c: Command): String? = when (c) {
        is Command.AutoScroll -> "autoscroll ${c.direction.name.lowercase()} ${when { c.speed <= 1 -> "slow"; c.speed >= 3 -> "fast"; else -> "normal" }}"
        is Command.RepeatEvery -> "every ${c.seconds}: ${c.command}"
        is Command.After -> "after ${c.seconds}: ${c.command}"
        is Command.ScheduleAt -> "at ${c.whenText}: ${c.command}"
        is Command.OpenApp -> "open_app ${c.app}"
        is Command.Tap -> (if (c.long) "long_press " else "tap ") + c.label
        is Command.Write -> when (c.submit) { true -> "type_send ${c.text}"; else -> "type ${c.text}" }
        Command.Submit -> "send"
        is Command.Search -> "search ${c.query}"
        is Command.OpenResult -> "open_result ${c.ordinal}"
        is Command.GoTo -> "goto ${c.section.name.lowercase()}"
        Command.NextItem -> "next"
        Command.PreviousItem -> "previous"
        is Command.React -> (if (c.on) "" else "un") + when (c.reaction) {
            Reaction.LIKE -> "like"; Reaction.SAVE -> "save"; Reaction.FOLLOW -> "follow"
            Reaction.SUBSCRIBE -> "subscribe"; Reaction.DISLIKE -> "dislike"
        }
        Command.OpenComments -> "comments"
        is Command.PlayMedia -> "play ${c.query}"
        is Command.Navigate -> c.action.name.lowercase()
        is Command.Scroll -> "scroll ${c.direction.name.lowercase()}"
        is Command.SendMessage -> "message ${c.contact ?: ""} | ${c.text ?: ""}"
        is Command.Call -> "call ${c.contact ?: c.number ?: ""}"
        is Command.SetToggle -> "toggle ${c.setting.name.lowercase()} ${if (c.on == false) "off" else "on"}"
        is Command.Media -> when (c.key) { MediaKey.PAUSE, MediaKey.STOP -> "pause"; MediaKey.NEXT -> "next_track"; MediaKey.PREVIOUS -> "previous_track"; else -> "resume" }
        Command.ReadScreen -> "read_screen"
        Command.DescribeScreen -> "describe_screen"
        is Command.ReadNotifications -> "read_notifications"
        is Command.OpenChat -> "open_chat ${c.contact ?: ""}"
        is Command.SetAlarm -> "alarm ${c.hour}:${"%02d".format(c.minute)}" + (c.label?.let { " | $it" } ?: "")
        is Command.SetTimer -> "timer ${c.seconds}" + (c.label?.let { " | $it" } ?: "")
        is Command.Note -> "note ${c.text}"
        is Command.SetLevel -> (if (c.level == Level.VOLUME) "volume " else "brightness ") +
            (c.percent?.toString() ?: if ((c.delta ?: 0) >= 0) "up" else "down")
        else -> null
    }

    private val sectionNames = Section.entries.associateBy { it.name.lowercase() }

    fun parse(text: String): List<Command> = text.lines()
        .map { it.trim().trimStart('-', '*', '•', ' ').removeSurrounding("`").trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull(::parseLine)

    fun parseLine(raw: String): Command? {
        val line = raw.replace(Regex("^\\d+[.)]\\s*"), "")
        val verb = line.substringBefore(' ').lowercase().trim(':')
        val arg = line.substringAfter(' ', "").trim().trim('"', '\'')
        fun pipe(): Pair<String, String?> = arg.substringBefore('|').trim() to arg.substringAfter('|', "").trim().ifBlank { null }
        return when (verb) {
            "open_app", "open", "launch" -> arg.takeIf { it.isNotBlank() }?.let { Command.OpenApp(it) }
            "tap", "click" -> arg.takeIf { it.isNotBlank() }?.let { Command.Tap(it) }
            "long_press" -> arg.takeIf { it.isNotBlank() }?.let { Command.Tap(it, long = true) }
            "type", "write" -> arg.takeIf { it.isNotBlank() }?.let { Command.Write(it, false) }
            "type_send", "send_text" -> arg.takeIf { it.isNotBlank() }?.let { Command.Write(it, true) }
            "send", "submit", "post" -> if (arg.isBlank()) Command.Submit else Command.Write(arg, true)
            "search" -> arg.takeIf { it.isNotBlank() }?.let { Command.Search(it) }
            "open_result", "result" -> Command.OpenResult(arg.toIntOrNull() ?: Numbers.ordinal(arg) ?: 1)
            "goto", "go_to", "section" -> sectionNames[arg.lowercase()]?.let { Command.GoTo(it) }
            "next" -> Command.NextItem
            "autoscroll", "auto_scroll" -> arg.lowercase().let { a -> Command.AutoScroll(if (a.contains("up")) Direction.UP else Direction.DOWN, when { a.contains("slow") -> 1; a.contains("fast") -> 3; else -> 2 }) }
            "every" -> Regex("^(\\d+)\\s*[:|]\\s*(.+)$").find(arg)?.let { Command.RepeatEvery(it.groupValues[1].toInt().coerceAtLeast(2), it.groupValues[2]) }
            "after" -> Regex("^(\\d+)\\s*[:|]\\s*(.+)$").find(arg)?.let { Command.After(it.groupValues[1].toInt(), it.groupValues[2]) }
            "at" -> Regex("^(.+?)\\s*[:|]\\s+(.+)$").find(arg)?.let { Command.ScheduleAt(it.groupValues[1], it.groupValues[2]) }
            "previous", "prev" -> Command.PreviousItem
            "like" -> Command.React(Reaction.LIKE)
            "unlike" -> Command.React(Reaction.LIKE, false)
            "save" -> Command.React(Reaction.SAVE)
            "unsave" -> Command.React(Reaction.SAVE, false)
            "follow" -> Command.React(Reaction.FOLLOW)
            "unfollow" -> Command.React(Reaction.FOLLOW, false)
            "subscribe" -> Command.React(Reaction.SUBSCRIBE)
            "unsubscribe" -> Command.React(Reaction.SUBSCRIBE, false)
            "dislike" -> Command.React(Reaction.DISLIKE)
            "comments" -> Command.OpenComments
            "comment" -> if (arg.isBlank()) Command.OpenComments else Command.Write(arg, true)
            "play" -> arg.takeIf { it.isNotBlank() }?.let { Command.PlayMedia(it) } ?: Command.Media(MediaKey.PLAY)
            "pause" -> Command.Media(MediaKey.PAUSE)
            "resume" -> Command.Media(MediaKey.PLAY)
            "next_track" -> Command.Media(MediaKey.NEXT)
            "previous_track" -> Command.Media(MediaKey.PREVIOUS)
            "back" -> Command.Navigate(GlobalAction.BACK)
            "home" -> Command.Navigate(GlobalAction.HOME)
            "recents" -> Command.Navigate(GlobalAction.RECENTS)
            "notifications" -> Command.Navigate(GlobalAction.NOTIFICATIONS)
            "quick_settings" -> Command.Navigate(GlobalAction.QUICK_SETTINGS)
            "screenshot" -> Command.Navigate(GlobalAction.SCREENSHOT)
            "scroll" -> Command.Scroll(when (arg.lowercase()) { "up" -> Direction.UP; "left" -> Direction.LEFT; "right" -> Direction.RIGHT; else -> Direction.DOWN })
            "message" -> pipe().let { (who, msg) -> Command.SendMessage(who.ifBlank { null }, msg) }
            "open_chat" -> arg.takeIf { it.isNotBlank() }?.let { Command.OpenChat(it) }
            "call" -> arg.takeIf { it.isNotBlank() }?.let { Command.Call(it) }
            "toggle" -> {
                val parts = arg.split(' ')
                val on = parts.lastOrNull()?.lowercase() != "off"
                val name = parts.filterNot { it.lowercase() == "on" || it.lowercase() == "off" }.joinToString("_").uppercase()
                SystemSetting.entries.firstOrNull { it.name == name || it.name.replace("_", "") == name.replace("_", "") }
                    ?.let { Command.SetToggle(it, on) }
                    ?: if (name.startsWith("TORCH")) Command.SetToggle(SystemSetting.FLASHLIGHT, on) else null
            }
            "volume", "brightness" -> {
                val lvl = if (verb == "volume") Level.VOLUME else Level.BRIGHTNESS
                when (arg.lowercase()) {
                    "up" -> Command.SetLevel(lvl, null, 15)
                    "down" -> Command.SetLevel(lvl, null, -15)
                    "mute" -> Command.SetLevel(lvl, 0, null)
                    else -> arg.filter { it.isDigit() }.toIntOrNull()?.let { Command.SetLevel(lvl, it.coerceIn(0, 100), null) }
                }
            }
            "read_screen" -> Command.ReadScreen
            "describe_screen" -> Command.DescribeScreen
            "read_notifications" -> Command.ReadNotifications()
            "alarm" -> pipe().let { (t, label) ->
                Regex("(\\d{1,2})(?::(\\d{2}))?").find(t)?.let { m -> Command.SetAlarm(m.groupValues[1].toInt(), m.groupValues[2].toIntOrNull() ?: 0, label) }
            }
            "timer" -> pipe().let { (t, label) -> t.filter { it.isDigit() }.toIntOrNull()?.let { Command.SetTimer(it, label) } }
            "note" -> arg.takeIf { it.isNotBlank() }?.let { Command.Note(it) }
            "chat", "answer" -> Command.Chat(arg)
            else -> null
        }
    }
}
