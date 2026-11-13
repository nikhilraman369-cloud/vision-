package ai.nexus.app.life

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import ai.nexus.app.NexusApp
import ai.nexus.app.service.NexusNotificationListener
import ai.nexus.app.ui.PanelActivity
import ai.nexus.core.life.Briefs
import ai.nexus.core.life.NotificationIntel
import ai.nexus.core.life.NotificationRecord
import ai.nexus.core.life.TaskItem
import ai.nexus.core.nlu.Fuzzy
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Calendar

/**
 * Notification intelligence: watches whitelisted apps, understands what each
 * message means for you (assigned work, deadline, event, question), creates
 * tasks and reminders, prepares a draft, and tells you at a good moment.
 * Replies are only sent after your approval unless you trust that sender.
 */
object NotificationBrain {
    private const val CHANNEL = "nexus_insights"
    private val recent = ArrayDeque<String>()

    fun onPosted(ctx: Context, pkg: String, appLabel: String, title: String, text: String, key: String, hasReply: Boolean) {
        val app = NexusApp.get(ctx)
        val settings = app.memory.settings
        val life = app.life
        val rules = life.data.rules
        if (!settings.notificationWatching || !rules.enabled || pkg !in rules.watchedApps) return
        if (rules.ignoreSenders.any { title.contains(it, true) }) return
        if (text.isBlank()) return
        // De-duplicate repeats (apps re-post the same message).
        val sig = "$pkg|$title|$text"
        synchronized(recent) { if (sig in recent) return; recent.addLast(sig); while (recent.size > 60) recent.removeFirst() }

        app.scope.launch {
            val now = System.currentTimeMillis()
            val name = settings.name ?: "Nikhil"
            var insight = NotificationIntel.analyze(pkg, title, text, name, now)
            // Optional LLM refinement (strict JSON) when a cloud provider is configured.
            app.llmJson(insightPrompt(appLabel, title, text, name, now))?.let { j -> insight = refine(insight, j, now) }
            if (!insight.actionable && insight.type == "info" && rules.keywords.none { text.contains(it, true) }) {
                life.store.mutate { d -> d.copy(notifications = (d.notifications + NotificationRecord(app = appLabel, title = title, text = text, summary = text.take(120))).takeLast(500)) }
                return@launch
            }
            var taskId: String? = null
            if (insight.type == "deadline" || insight.type == "task" || insight.type == "event") {
                val writing = Regex("article|write|script|draft|essay|post|report|content|editorial|blog|caption|speech", RegexOption.IGNORE_CASE).containsMatchIn(insight.title)
                val draft = if (writing) app.llmText(draftPrompt(insight.title, text)) ?: NotificationIntel.outline(insight.title) else null
                val task = life.store.addTask(TaskItem(title = insight.title, details = text, source = "notification:$appLabel", assignedBy = insight.assignedBy,
                    dueMs = insight.dueMs, priority = insight.urgency, draft = draft, category = if (writing) "Writing ideas" else "General", sourceNotificationId = key))
                taskId = task.id
                ReminderScheduler.scheduleTask(ctx, task, rules.reminderOffsetsMin)
            }
            val sender = rules.senders.firstOrNull { title.contains(it.name, true) }
            val record = NotificationRecord(app = appLabel, title = title, text = text, actionable = insight.actionable, type = insight.type,
                summary = insight.title, taskId = taskId, draftReply = insight.draftReply.takeIf { hasReply }, replyKey = key.takeIf { hasReply },
                replyState = if (hasReply && insight.draftReply != null) "pending" else null)
            life.store.mutate { d -> d.copy(notifications = (d.notifications + record).takeLast(500)) }

            // Auto-reply only for senders you explicitly trust.
            if (hasReply && sender?.trust == "auto" && sender.autoReply != null) {
                if (NexusNotificationListener.replyByKey(ctx, key, sender.autoReply!!)) markReply(app, record.id, "sent")
            }
            val boss = settings.addressAs.ifBlank { "Boss" }
            val due = insight.dueMs?.let { ", due " + Briefs.whenText(it, now) } ?: ""
            val who = insight.assignedBy?.let { "$it on $appLabel" } ?: appLabel
            val spoken = when (insight.type) {
                "deadline", "task" -> "$boss, $who assigned you: ${insight.title}$due. Task created and reminders set" + (if (taskId != null && life.data.tasks.firstOrNull { it.id == taskId }?.draft != null) ", and I've prepared an outline." else ".")
                "event" -> "$boss, $who: ${insight.title}$due. I'll remind you."
                "question" -> "$boss, $who asked: ${text.take(100)}"
                else -> "$boss, from $who: ${text.take(100)}"
            }
            postCard(ctx, record, spoken)
            if (rules.announce && canSpeakNow(ctx, app, insight.urgency)) {
                app.speakProactive(spoken)
                if (record.replyState == "pending" && sender?.trust != "observe") {
                    app.nexus.prompt("Shall I reply \"${record.draftReply}\"?", listOf("yes", "no")) { idx ->
                        if (idx == 0 && NexusNotificationListener.replyByKey(ctx, key, record.draftReply!!)) { markReply(app, record.id, "sent"); app.speakProactive("Replied.") }
                        else markReply(app, record.id, "dismissed")
                    }
                }
                life.store.mutate { d -> d.copy(notifications = d.notifications.map { if (it.id == record.id) it.copy(announced = true) else it }) }
            }
        }
    }

    fun markReply(app: NexusApp, id: String, state: String) =
        app.life.store.mutate { d -> d.copy(notifications = d.notifications.map { if (it.id == id) it.copy(replyState = state) else it }) }

    /** Never mid-call, never in Sleep mode or quiet hours (unless urgent), never when muted. */
    fun canSpeakNow(ctx: Context, app: NexusApp, urgency: Int): Boolean {
        val s = app.memory.settings
        if (s.voiceMuted) return false
        val am = ctx.getSystemService(AudioManager::class.java)
        if (am != null && (am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION)) return false
        val mode = app.life.data.activeMode
        if (mode.equals("Sleep", true)) return false
        if (urgency > 1 && (mode.equals("Study", true) || mode.equals("Writing", true) || inQuietHours(s.quietStart, s.quietEnd))) return false
        return true
    }

    fun inQuietHours(start: String, end: String, now: Long = System.currentTimeMillis()): Boolean {
        fun mins(t: String) = t.split(":").let { (it[0].toIntOrNull() ?: 0) * 60 + (it.getOrNull(1)?.toIntOrNull() ?: 0) }
        val c = Calendar.getInstance().apply { timeInMillis = now }
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val a = mins(start); val b = mins(end)
        return if (a <= b) m in a until b else m >= a || m < b
    }

    private fun postCard(ctx: Context, r: NotificationRecord, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "NEXUS insights", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, r.id.hashCode(), Intent(ctx, PanelActivity::class.java).putExtra("section", "tasks"), PendingIntent.FLAG_IMMUTABLE)
        val b = android.app.Notification.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("NEXUS").setContentText(text).setStyle(android.app.Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true)
        if (r.replyState == "pending" && r.draftReply != null) {
            val send = PendingIntent.getBroadcast(ctx, r.id.hashCode() + 1, Intent(ctx, LifeReceiver::class.java).setAction(LifeReceiver.ACTION_SEND_REPLY).putExtra("id", r.id), PendingIntent.FLAG_IMMUTABLE)
            val dismiss = PendingIntent.getBroadcast(ctx, r.id.hashCode() + 2, Intent(ctx, LifeReceiver::class.java).setAction(LifeReceiver.ACTION_DISMISS_REPLY).putExtra("id", r.id), PendingIntent.FLAG_IMMUTABLE)
            b.addAction(android.app.Notification.Action.Builder(null, "Reply: ${r.draftReply!!.take(28)}", send).build())
            b.addAction(android.app.Notification.Action.Builder(null, "Don't reply", dismiss).build())
        }
        nm.notify(r.id.hashCode(), b.build())
    }

    private fun insightPrompt(app: String, sender: String, text: String, me: String, now: Long) = """
        You read one phone notification for $me and return ONLY a JSON object, no prose:
        {"actionable":bool,"type":"task|deadline|event|question|info","title":"short task title","assigned_by":"name or null",
         "due":"YYYY-MM-DD HH:MM or null","urgency":1-3,"suggested_action":"...","draft_reply":"short reply or null"}
        Now: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm EEEE", java.util.Locale.ENGLISH).format(java.util.Date(now))}
        App: $app. Sender: $sender. Message: $text
    """.trimIndent()

    private fun draftPrompt(title: String, context: String) =
        "Write a crisp first-draft outline (headline, hook, 4-6 key points, a closing call to action) for: $title. Context from the message: $context. Plain text, under 220 words."

    private fun refine(base: ai.nexus.core.life.Insight, j: JSONObject, now: Long): ai.nexus.core.life.Insight = runCatching {
        val due = j.optString("due").takeIf { it.isNotBlank() && it != "null" }?.let {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ENGLISH).parse(it)?.time
        }
        base.copy(
            actionable = j.optBoolean("actionable", base.actionable),
            type = j.optString("type", base.type).ifBlank { base.type },
            title = j.optString("title").takeIf { it.isNotBlank() && it != "null" } ?: base.title,
            assignedBy = j.optString("assigned_by").takeIf { it.isNotBlank() && it != "null" } ?: base.assignedBy,
            dueMs = due ?: base.dueMs,
            urgency = j.optInt("urgency", base.urgency).coerceIn(1, 3),
            suggestedAction = j.optString("suggested_action", base.suggestedAction),
            draftReply = j.optString("draft_reply").takeIf { it.isNotBlank() && it != "null" } ?: base.draftReply,
        )
    }.getOrDefault(base)

    @Suppress("unused") private fun similar(a: String, b: String) = Fuzzy.similarity(a, b)
}
