package ai.nexus.core.life

import ai.nexus.core.memory.MemoryStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString().substring(0, 12)

@Serializable
data class TaskItem(
    val id: String = newId(),
    val title: String,
    val details: String = "",
    /** "voice", "manual", "notification:<app>" */
    val source: String = "voice",
    val assignedBy: String? = null,
    val dueMs: Long? = null,
    /** 1 = highest … 3 = low */
    val priority: Int = 2,
    val done: Boolean = false,
    val doneMs: Long? = null,
    val draft: String? = null,
    val category: String = "General",
    /** Reminder times already fired (epoch ms). */
    val firedReminders: List<Long> = emptyList(),
    val createdMs: Long = System.currentTimeMillis(),
    val sourceNotificationId: String? = null,
)

@Serializable
data class InboxItem(val id: String = newId(), val category: String, val text: String, val ms: Long = System.currentTimeMillis())

@Serializable
data class Expense(val id: String = newId(), val amount: Double, val category: String, val note: String, val ms: Long = System.currentTimeMillis())

/** One selector set for a recorded UI element: tried in order. */
@Serializable
data class Selector(
    val viewId: String? = null,
    val text: String? = null,
    val desc: String? = null,
    val cls: String? = null,
    /** Position as a fraction of the screen (0..1), the last-resort fallback. */
    val xPct: Float? = null,
    val yPct: Float? = null,
)

@Serializable
data class RecipeStep(
    /** "open_app", "tap", "long_press", "type", "scroll", "back", "home", "wait" */
    val action: String,
    val pkg: String? = null,
    val selector: Selector? = null,
    /** Text to type; may contain {slot} placeholders. */
    val text: String? = null,
    val direction: String? = null,
)

@Serializable
data class Skill(
    val id: String = newId(),
    val name: String,
    /** Trigger phrases; {slot} placeholders capture parameters. */
    val triggers: List<String>,
    val app: String? = null,
    val steps: List<RecipeStep>,
    val successes: Int = 0,
    val failures: Int = 0,
    val enabled: Boolean = true,
    val version: Int = 1,
    /** Older versions of the steps, for rollback. */
    val history: List<List<RecipeStep>> = emptyList(),
    val lowConfidence: Boolean = false,
    val createdMs: Long = System.currentTimeMillis(),
    val lastUsedMs: Long? = null,
) {
    val weak: Boolean get() = failures >= 2 && failures > successes
    val successRate: Int get() = if (successes + failures == 0) 100 else successes * 100 / (successes + failures)
}

/** A step in plain words, for the "what did I teach it" screens. */
fun RecipeStep.pretty(): String = when (action) {
    "open_app" -> "Open " + (selector?.text ?: pkg?.substringAfterLast('.') ?: "the app")
    "tap" -> "Tap \u201c" + (selector?.text ?: selector?.desc ?: selector?.viewId?.substringAfterLast('/') ?: "button") + "\u201d"
    "long_press" -> "Press and hold \u201c" + (selector?.text ?: selector?.desc ?: "item") + "\u201d"
    "type" -> "Type \u201c" + (text ?: "") + "\u201d"
    "scroll" -> "Scroll " + (direction ?: "down")
    "back" -> "Go back"
    else -> action
}

@Serializable
data class Mode(
    val id: String = newId(),
    val name: String,
    /** Action lines (CommandDsl) run when the mode starts, e.g. "toggle dnd on". */
    val onStart: List<String> = emptyList(),
    val onStop: List<String> = emptyList(),
    val blockedApps: List<String> = emptyList(),
    val allowedContacts: List<String> = emptyList(),
    val focusMinutes: Int? = null,
    val breakMinutes: Int? = null,
    /** Activity log key, e.g. "study", "gym", "writing". */
    val logAs: String? = null,
    /** Optional daily start "HH:MM" and days "1234567" (Mon=1). */
    val scheduleTime: String? = null,
    val scheduleDays: String = "1234567",
    val quiet: Boolean = true,
    val builtIn: Boolean = false,
)

@Serializable
data class ModeSession(val mode: String, val startMs: Long, val endMs: Long? = null, val subject: String? = null)

@Serializable
data class Subject(val name: String, val chapters: List<String> = emptyList(), val minutes: Int = 0)

@Serializable
data class Flashcard(
    val id: String = newId(), val subject: String, val question: String, val answer: String,
    /** Spaced repetition: next review time and interval in days. */
    val dueMs: Long = System.currentTimeMillis(), val intervalDays: Int = 1, val ease: Double = 2.3,
)

@Serializable
data class NotificationRecord(
    val id: String = newId(),
    val app: String,
    val title: String,
    val text: String,
    val receivedMs: Long = System.currentTimeMillis(),
    val actionable: Boolean = false,
    val type: String = "info",
    val summary: String = "",
    val taskId: String? = null,
    val draftReply: String? = null,
    val replyKey: String? = null,
    /** "pending" | "approved" | "sent" | "dismissed" */
    val replyState: String? = null,
    val announced: Boolean = false,
    val userCorrected: Boolean = false,
)

@Serializable
data class SenderRule(
    val name: String,
    /** "observe" | "suggest" | "ask" | "auto" */
    val trust: String = "ask",
    val autoReply: String? = null,
)

@Serializable
data class NotificationRules(
    val enabled: Boolean = true,
    val watchedApps: List<String> = listOf("com.whatsapp", "com.whatsapp.w4b", "com.google.android.gm", "org.telegram.messenger", "com.google.android.calendar"),
    val keywords: List<String> = listOf("deadline", "submit", "prepare", "article", "meeting", "urgent", "by tomorrow", "assignment"),
    val ignoreSenders: List<String> = emptyList(),
    val senders: List<SenderRule> = emptyList(),
    val announce: Boolean = true,
    val reminderOffsetsMin: List<Int> = listOf(24 * 60, 180, 60, 15),
)

@Serializable
data class DiagEntry(
    val ms: Long = System.currentTimeMillis(),
    val request: String,
    val step: String,
    val app: String,
    val result: String,
    val detail: String = "",
    val durationMs: Long = 0,
    val screen: String = "",
)

@Serializable
data class LifeData(
    val tasks: List<TaskItem> = emptyList(),
    val inbox: List<InboxItem> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val skills: List<Skill> = emptyList(),
    val modes: List<Mode> = Modes.defaults(),
    val activeMode: String? = null,
    val activeSubject: String? = null,
    val sessions: List<ModeSession> = emptyList(),
    val subjects: List<Subject> = Study.caInterSubjects(),
    val flashcards: List<Flashcard> = emptyList(),
    /** habit → days (yyyymmdd) done */
    val habitDays: Map<String, List<Int>> = emptyMap(),
    val notifications: List<NotificationRecord> = emptyList(),
    val rules: NotificationRules = NotificationRules(),
    val diagnostics: List<DiagEntry> = emptyList(),
    /** Temporarily allowed blocked apps: package → until ms. */
    val overrides: Map<String, Long> = emptyMap(),
    val lastBriefDay: Int = 0,
    val scheduled: List<ScheduledAction> = emptyList(),
)

/** Something to do later: "at 9 pm message Rahul saying happy birthday". [text] is run as if you said it. */
@Serializable
data class ScheduledAction(
    val id: String = newId(),
    val text: String,
    val atMs: Long,
    val createdMs: Long = System.currentTimeMillis(),
    val done: Boolean = false,
)

/** Persistent store for tasks, modes, skills, study, habits, notifications and diagnostics. */
class LifeStore(private val storage: MemoryStorage) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    @Volatile var data: LifeData = load()
        private set

    private fun load(): LifeData = try {
        storage.read()?.let { json.decodeFromString(LifeData.serializer(), it) } ?: LifeData()
    } catch (e: Exception) { LifeData() }

    fun mutate(block: (LifeData) -> LifeData) = synchronized(lock) {
        data = block(data)
        storage.write(json.encodeToString(LifeData.serializer(), data))
    }

    fun export(): String = json.encodeToString(LifeData.serializer(), data)
    fun import(text: String) = mutate { json.decodeFromString(LifeData.serializer(), text) }
    fun wipe() = mutate { LifeData() }

    // ---------------- tasks
    fun addTask(t: TaskItem): TaskItem { mutate { d -> d.copy(tasks = d.tasks + t) }; return t }
    fun updateTask(id: String, f: (TaskItem) -> TaskItem) = mutate { d -> d.copy(tasks = d.tasks.map { if (it.id == id) f(it) else it }) }
    fun deleteTask(id: String) = mutate { d -> d.copy(tasks = d.tasks.filterNot { it.id == id }) }
    fun openTasks(): List<TaskItem> = data.tasks.filter { !it.done }

    // ---------------- diagnostics
    fun log(e: DiagEntry) = mutate { d -> d.copy(diagnostics = (d.diagnostics + e).takeLast(600)) }

    // ---------------- skills
    fun saveSkill(s: Skill) = mutate { d -> d.copy(skills = d.skills.filterNot { it.id == s.id } + s) }
    fun updateSkill(id: String, f: (Skill) -> Skill) = mutate { d -> d.copy(skills = d.skills.map { if (it.id == id) f(it) else it }) }
    fun deleteSkill(id: String) = mutate { d -> d.copy(skills = d.skills.filterNot { it.id == id }) }

    // ---------------- habits
    fun markHabit(habit: String, day: Int) = mutate { d ->
        val list = (d.habitDays[habit] ?: emptyList()).let { if (day in it) it else (it + day).takeLast(400) }
        d.copy(habitDays = d.habitDays + (habit to list))
    }

    /** Retention: raw notification text kept ~7 days, diagnostics capped. */
    fun cleanup(now: Long = System.currentTimeMillis()) = mutate { d ->
        val week = 7L * 24 * 3600_000
        d.copy(
            notifications = d.notifications.map { if (now - it.receivedMs > week && it.text.isNotEmpty()) it.copy(text = "") else it }.takeLast(500),
            overrides = d.overrides.filterValues { it > now },
        )
    }
}
