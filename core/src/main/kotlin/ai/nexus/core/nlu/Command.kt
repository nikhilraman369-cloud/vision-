package ai.nexus.core.nlu

import ai.nexus.core.device.Direction
import ai.nexus.core.device.GlobalAction
import ai.nexus.core.device.MediaKey
import ai.nexus.core.device.SettingsScreen
import ai.nexus.core.device.SystemSetting

/** A reaction/state the user can turn on or off for the current item. */
enum class Reaction { LIKE, DISLIKE, SAVE, SUBSCRIBE, FOLLOW }

/** Where in an app to go. */
enum class Section { HOME, REELS, SHORTS, MESSAGES, PROFILE, SEARCH, EXPLORE, SUBSCRIPTIONS, LIBRARY, NOTIFICATIONS, STORIES, CHATS, CALLS, STATUS, INBOX, SENT, DRAFTS, HISTORY, DOWNLOADS, SETTINGS }

enum class Control { STOP, CANCEL, PAUSE, RESUME, REPEAT, UNDO_LAST, WRONG }

enum class Level { VOLUME, BRIGHTNESS }

/**
 * One understood step. A request like "open YouTube, search filmmaking and
 * like the first video" becomes [OpenApp, Search, OpenResult, React].
 *
 * Object references ("it", "him", "this video") are left as null fields and
 * filled in from conversation context by the planner.
 */
sealed interface Command {
    data class OpenApp(val app: String) : Command
    data class Navigate(val action: GlobalAction, val times: Int = 1) : Command
    data class Scroll(val direction: Direction, val times: Int = 1) : Command
    data object NextItem : Command
    data object PreviousItem : Command
    data class React(val reaction: Reaction, val on: Boolean = true) : Command
    data object OpenComments : Command
    data object CloseOverlay : Command
    /** Type text. [submit] = post/send it; null = decide from context (comments get posted). */
    data class Write(val text: String, val submit: Boolean? = null) : Command
    data object Submit : Command
    data class Search(val query: String, val app: String? = null) : Command
    data class OpenResult(val ordinal: Int, val kind: String? = null) : Command
    data class GoTo(val section: Section, val app: String? = null) : Command
    data class Media(val key: MediaKey) : Command
    data class PlayMedia(val query: String, val app: String? = null) : Command
    data class Seek(val seconds: Int, val relative: Boolean) : Command
    data class OpenChat(val contact: String?, val app: String? = null) : Command
    /** [send] false = draft only ("type this but don't send"). */
    data class SendMessage(val contact: String?, val text: String?, val app: String? = null, val send: Boolean = true) : Command
    data class ReadMessages(val contact: String?, val app: String? = null) : Command
    data class Call(val contact: String?, val number: String? = null, val numberIndex: Int? = null) : Command
    data object EndCall : Command
    data class FindContact(val name: String) : Command
    data class SetToggle(val setting: SystemSetting, val on: Boolean?) : Command
    data class SetLevel(val level: Level, val percent: Int?, val delta: Int?) : Command
    data class OpenSettings(val screen: SettingsScreen, val app: String? = null) : Command
    data class Tap(val label: String, val long: Boolean = false, val ordinal: Int? = null) : Command
    data object ReadScreen : Command
    data object DescribeScreen : Command
    data class ExplainElement(val label: String) : Command
    data class FindOnScreen(val label: String) : Command
    data object Share : Command
    data object Copy : Command
    data object Paste : Command
    data class ReadNotifications(val app: String? = null) : Command
    data class FindFile(val query: String, val mimePrefix: String? = null, val newest: Boolean = false) : Command
    data class OpenUrl(val url: String) : Command
    data class Email(val to: String?, val subject: String?, val body: String?, val send: Boolean = false, val reply: Boolean = false) : Command
    data class Remember(val key: String?, val value: String) : Command
    data class Forget(val what: String?) : Command
    data class ControlTask(val control: Control) : Command
    /** "Open the second result instead" – replaces the last step. */
    data class Correction(val replacement: Command?) : Command
    /** "Do the same thing again". */
    data object Repeat : Command
    data class Shortcut(val name: String) : Command
    data class SetAlarm(val hour: Int, val minute: Int, val label: String? = null) : Command
    data class SetTimer(val seconds: Int, val label: String? = null) : Command
    data class Note(val text: String) : Command
    data object ReadNotes : Command
    data object ClearNotes : Command
    data class Calculate(val expression: String) : Command
    /** "Watch me": start recording the user's own taps as a reusable routine. */
    data object TeachStart : Command
    data class TeachStop(val name: String?) : Command
    data object HideBubble : Command
    /** silent / vibrate / normal ringer. */
    data class Ringer(val mode: String) : Command
    data class Directions(val place: String, val mode: String? = null) : Command
    data class CalendarEvent(val title: String, val dayText: String?, val hour: Int?, val minute: Int?) : Command
    data class TakePhoto(val front: Boolean = false, val video: Boolean = false) : Command
    data object ClearNotifications : Command
    /** Tap a screen area when buttons have no labels: row 0..2 (top/middle/bottom), col 0..2 (left/center/right). */
    data class TapRegion(val row: Int, val col: Int, val double: Boolean = false, val long: Boolean = false) : Command
    /** "Send this to Arjun": share what's on screen to a contact. */
    data class ShareTo(val contact: String, val app: String? = null) : Command
    data object OpenLatestNotification : Command
    // ---- life / productivity
    data class StartMode(val name: String, val subject: String? = null) : Command
    data class StopMode(val name: String? = null) : Command
    data class Capture(val text: String) : Command
    data class AddExpense(val text: String) : Command
    data class AddTask(val title: String, val dueText: String? = null) : Command
    data object ListTasks : Command
    data class CompleteTask(val title: String?) : Command
    data object WhatNow : Command
    /** "morning" | "evening" | "weekly" | "digest" */
    data class Brief(val kind: String) : Command
    data class QuizMe(val subject: String? = null) : Command
    data class AddFlashcard(val subject: String?, val question: String, val answer: String) : Command
    data class AllowApp(val app: String, val minutes: Int) : Command
    data class WhatDoYouKnow(val topic: String) : Command
    /** "No, I meant Rahul Sharma" */
    data class MeantInstead(val text: String) : Command
    /** "Always use WhatsApp for him" */
    data class PreferApp(val contact: String, val app: String) : Command
    /** "pause notification watching", "mute voice", "not now" */
    data class Toggle(val what: String, val on: Boolean) : Command
    /** A question or conversation – answered, not executed. */
    data class Chat(val text: String) : Command
    /** A phone task the rule-based parser could not break down – handed to the LLM agent. */
    data class Agentic(val goal: String) : Command
}

data class ParsedStep(val command: Command, val source: String, val confidence: Double)

data class ParsedRequest(val original: String, val normalized: String, val steps: List<ParsedStep>) {
    val isControl: Boolean get() = steps.size == 1 && (steps[0].command is Command.ControlTask)
    val needsLlm: Boolean get() = steps.any { it.command is Command.Agentic || it.confidence < 0.55 }
    val isChat: Boolean get() = steps.isNotEmpty() && steps.all { it.command is Command.Chat }
}
