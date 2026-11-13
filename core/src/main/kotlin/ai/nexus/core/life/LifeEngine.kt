package ai.nexus.core.life

import ai.nexus.core.agent.Outcome
import ai.nexus.core.memory.LongTermMemory
import ai.nexus.core.memory.MemoryKind
import ai.nexus.core.nlu.Command
import ai.nexus.core.nlu.Fuzzy

/** Result of a life command: what to say, plus phone actions (action lines) to run. */
data class LifeResult(val outcome: Outcome, val runLines: List<String> = emptyList(), val askQuiz: Flashcard? = null)

/**
 * Brain for the "life" features: modes, tasks and deadlines, the voice
 * capture inbox, expenses, study tracking with spaced-repetition quizzes,
 * habits, briefs and "what should I do now". Pure logic over [LifeStore];
 * phone actions are returned as action lines for the engine to execute.
 */
class LifeEngine(val store: LifeStore, private val memory: LongTermMemory, private val clock: () -> Long = System::currentTimeMillis) {
    private val boss get() = memory.settings.addressAs.ifBlank { "Boss" }
    val data get() = store.data

    fun activeMode(): Mode? = data.activeMode?.let { n -> data.modes.firstOrNull { it.name.equals(n, true) } }

    /** Is [pkg] blocked by the active mode right now (and not temporarily allowed)? */
    fun isBlocked(pkg: String): Boolean {
        val m = activeMode() ?: return false
        if (pkg !in m.blockedApps) return false
        return (data.overrides[pkg] ?: 0L) < clock()
    }

    fun handle(cmd: Command): LifeResult? {
        val now = clock()
        return when (cmd) {
            is Command.StartMode -> startMode(cmd.name, cmd.subject, now)
            is Command.StopMode -> stopMode(cmd.name, now)
            is Command.Capture -> capture(cmd.text, now)
            is Command.Note -> capture(cmd.text, now)
            is Command.AddExpense -> {
                val e = Inbox.parseExpense(cmd.text) ?: return LifeResult(Outcome.Failed("I couldn't catch the amount, $boss.", blocking = false))
                store.mutate { d -> d.copy(expenses = d.expenses + e) }
                val monthStart = now - 30L * 86_400_000
                val total = store.data.expenses.filter { it.ms >= monthStart }.sumOf { it.amount }
                LifeResult(Outcome.Success("Logged ₹${fmt(e.amount)} for ${e.note.ifBlank { e.category }}. ₹${fmt(total)} in the last 30 days."))
            }
            is Command.AddTask -> {
                val due = cmd.dueText?.let { NotificationIntel.extractDue("by $it", now) } ?: NotificationIntel.extractDue(cmd.title, now)
                val t = store.addTask(TaskItem(title = cmd.title.replaceFirstChar { it.uppercase() }, dueMs = due, category = Inbox.categorize(cmd.title).let { if (it == "Expenses" || it == "General") "General" else it }))
                LifeResult(Outcome.Success("Added: ${t.title}${due?.let { ", due " + Briefs.whenText(it, now) } ?: ""}."))
            }
            Command.ListTasks -> {
                val open = Briefs.rank(store.openTasks(), data.activeMode, now)
                LifeResult(Outcome.Success(if (open.isEmpty()) "No open tasks, $boss." else "You have ${open.size}: " +
                    open.take(5).joinToString("; ") { it.title + (it.dueMs?.let { d -> ", due " + Briefs.whenText(d, now) } ?: "") } + "."))
            }
            is Command.CompleteTask -> {
                val open = store.openTasks()
                val t = cmd.title?.let { q -> open.maxByOrNull { Fuzzy.similarity(it.title, q).let { s -> if (Fuzzy.normalize(it.title).contains(Fuzzy.normalize(q))) maxOf(s, 0.9) else s } } }
                    ?.takeIf { Fuzzy.similarity(it.title, cmd.title).let { s -> s > 0.45 || Fuzzy.normalize(it.title).contains(Fuzzy.normalize(cmd.title)) } }
                    ?: return LifeResult(Outcome.Failed("I couldn't find that task, $boss.", blocking = false))
                store.updateTask(t.id) { it.copy(done = true, doneMs = now) }
                if (t.category == "Writing ideas" || t.title.contains("article", true) || t.title.contains("script", true)) store.markHabit("writing", dayKey(now))
                LifeResult(Outcome.Success("Marked done: ${t.title}. Nice work, $boss."))
            }
            Command.WhatNow -> {
                val top = Briefs.rank(store.openTasks(), data.activeMode, now).firstOrNull()
                    ?: return LifeResult(Outcome.Success("Nothing pending, $boss. Revise a CA Inter chapter or rest."))
                val dueCards = data.flashcards.count { it.dueMs <= now }
                LifeResult(Outcome.Success("Do this now: ${top.title}" + (top.dueMs?.let { ", due " + Briefs.whenText(it, now) } ?: "") +
                    (if (dueCards > 0) ". Then $dueCards revision cards." else ".")))
            }
            is Command.Brief -> LifeResult(Outcome.Success(when (cmd.kind) {
                "evening" -> Briefs.evening(data, boss, now)
                "weekly" -> Briefs.weekly(data, now)
                "digest" -> digest(now)
                else -> Briefs.morning(data, boss, now)
            }))
            is Command.AddFlashcard -> {
                val subj = Study.resolve(cmd.subject, data.subjects) ?: data.activeSubject ?: "General"
                store.mutate { d -> d.copy(flashcards = d.flashcards + Flashcard(subject = subj, question = cmd.question, answer = cmd.answer)) }
                LifeResult(Outcome.Success("Flashcard added to $subj."))
            }
            is Command.QuizMe -> {
                val subj = Study.resolve(cmd.subject, data.subjects)
                val pool = data.flashcards.filter { subj == null || it.subject == subj }
                val card = pool.filter { it.dueMs <= now }.minByOrNull { it.dueMs } ?: pool.minByOrNull { it.dueMs }
                    ?: return LifeResult(Outcome.Success("No flashcards${subj?.let { " for $it" } ?: ""} yet. Say \"add flashcard: question | answer\", $boss."))
                LifeResult(Outcome.Success(null), askQuiz = card)
            }
            is Command.AllowApp -> {
                val pkg = data.modes.flatMap { it.blockedApps }.firstOrNull { it.contains(Fuzzy.normalize(cmd.app).replace(" ", ""), true) || Fuzzy.normalize(cmd.app).contains(it.substringAfterLast('.')) }
                    ?: return LifeResult(Outcome.Success("${cmd.app} isn't blocked."))
                store.mutate { d -> d.copy(overrides = d.overrides + (pkg to now + cmd.minutes * 60_000L)) }
                LifeResult(Outcome.Success("Okay $boss, ${cmd.app} is allowed for ${cmd.minutes} minutes."))
            }
            else -> null
        }
    }

    private fun fmt(v: Double) = if (v == Math.rint(v)) v.toLong().toString() else "%.2f".format(v)

    private fun startMode(name: String, subjectText: String?, now: Long): LifeResult {
        val mode = data.modes.firstOrNull { it.name.equals(name, true) } ?: data.modes.maxByOrNull { Fuzzy.similarity(it.name, name) }?.takeIf { Fuzzy.similarity(it.name, name) > 0.7 }
            ?: return LifeResult(Outcome.Failed("There's no $name mode yet. You can create it in the app, $boss.", blocking = false))
        val stopPrev = activeMode()?.takeIf { it.id != mode.id }?.let { stopMode(it.name, now).runLines } ?: emptyList()
        val subject = Study.resolve(subjectText, data.subjects)
        store.mutate { d -> d.copy(activeMode = mode.name, activeSubject = subject, sessions = (d.sessions + ModeSession(mode.logAs ?: mode.name.lowercase(), now, subject = subject)).takeLast(2000)) }
        mode.logAs?.let { if (it in setOf("study", "gym", "writing")) store.markHabit(it, dayKey(now)) }
        val extra = when {
            mode.focusMinutes != null && mode.breakMinutes != null -> " ${mode.focusMinutes}-${mode.breakMinutes} focus timer running."
            mode.focusMinutes != null -> " ${mode.focusMinutes}-minute timer running."
            else -> ""
        }
        return LifeResult(Outcome.Success("${mode.name} mode on${subject?.let { " for $it" } ?: ""}.$extra"), stopPrev + mode.onStart)
    }

    private fun stopMode(name: String?, now: Long): LifeResult {
        val mode = activeMode() ?: return LifeResult(Outcome.Success("No mode is on, $boss."))
        if (name != null && !mode.name.equals(name, true) && Fuzzy.similarity(mode.name, name) < 0.7) return LifeResult(Outcome.Success("${mode.name} mode is the one that's on."))
        var minutes = 0
        store.mutate { d ->
            val sessions = d.sessions.toMutableList()
            val i = sessions.indexOfLast { it.endMs == null }
            if (i >= 0) { minutes = ((now - sessions[i].startMs) / 60_000).toInt(); sessions[i] = sessions[i].copy(endMs = now) }
            val subj = d.activeSubject
            val subjects = if (subj != null && mode.logAs == "study") d.subjects.map { if (it.name == subj) it.copy(minutes = it.minutes + minutes) else it } else d.subjects
            d.copy(activeMode = null, activeSubject = null, sessions = sessions, subjects = subjects)
        }
        return LifeResult(Outcome.Success("${mode.name} mode off. ${minutes / 60}h ${minutes % 60}m logged."), mode.onStop)
    }

    private fun capture(text: String, now: Long): LifeResult {
        val cat = Inbox.categorize(text)
        if (cat == "Expenses") Inbox.parseExpense(text)?.let { e -> store.mutate { d -> d.copy(expenses = d.expenses + e) } }
        store.mutate { d -> d.copy(inbox = (d.inbox + InboxItem(category = cat, text = text, ms = now)).takeLast(1000)) }
        if (cat == "Tasks") store.addTask(TaskItem(title = text.replaceFirstChar { it.uppercase() }, dueMs = NotificationIntel.extractDue(text, now), source = "voice"))
        memory.remember(MemoryKind.NOTE, null, text)
        return LifeResult(Outcome.Success("Noted under $cat."))
    }

    private fun digest(now: Long): String {
        val since = now - 24 * 3600_000L
        val items = data.notifications.filter { it.receivedMs >= since && (it.actionable || it.type != "info") }
        if (items.isEmpty()) return "Nothing important in the last day, $boss."
        return "In the last day: " + items.take(6).joinToString("; ") { "${it.title}: ${it.summary.ifBlank { it.text.take(60) }}" } + "."
    }

    /** Answer a quiz card. */
    fun answerQuiz(card: Flashcard, answer: String): String {
        val ok = Fuzzy.similarity(answer, card.answer) > 0.6 || Fuzzy.normalize(card.answer).split(' ').filter { it.length > 3 }
            .let { words -> words.isNotEmpty() && words.count { Fuzzy.normalize(answer).contains(it) } >= (words.size + 1) / 2 }
        val updated = Study.review(card, ok, clock())
        store.mutate { d -> d.copy(flashcards = d.flashcards.map { if (it.id == card.id) updated else it }) }
        return if (ok) "Correct, $boss. Next review in ${updated.intervalDays} days." else "Not quite. The answer is: ${card.answer}."
    }
}
