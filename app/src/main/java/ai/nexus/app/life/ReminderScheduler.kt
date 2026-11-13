package ai.nexus.app.life

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import ai.nexus.app.NexusApp
import ai.nexus.app.service.NexusNotificationListener
import ai.nexus.app.ui.MainActivity
import ai.nexus.app.ui.PanelActivity
import ai.nexus.core.life.Briefs
import ai.nexus.core.life.NotificationIntel
import ai.nexus.core.life.TaskItem
import java.util.Calendar

/** Exact-alarm reminders (escalating before deadlines) and the daily briefs. */
object ReminderScheduler {
    private fun am(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    private fun at(ctx: Context, whenMs: Long, pi: PendingIntent) {
        val a = am(ctx) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT < 31 || a.canScheduleExactAlarms()) a.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
            else a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
        }
    }

    fun scheduleTask(ctx: Context, t: TaskItem, offsetsMin: List<Int>) {
        val due = t.dueMs ?: return
        val now = System.currentTimeMillis()
        for (time in NotificationIntel.reminderTimes(due, offsetsMin, now) + listOf(due).filter { it > now }) {
            if (time in t.firedReminders) continue
            val i = Intent(ctx, LifeReceiver::class.java).setAction(LifeReceiver.ACTION_REMIND).putExtra("task", t.id).putExtra("at", time)
            at(ctx, time, PendingIntent.getBroadcast(ctx, (t.id + time).hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
    }

    fun scheduleAll(ctx: Context) {
        val app = NexusApp.get(ctx)
        val life = app.life
        life.store.openTasks().forEach { scheduleTask(ctx, it, life.data.rules.reminderOffsetsMin) }
        scheduleBriefs(ctx)
        scheduleModes(ctx)
        life.data.scheduled.filter { !it.done && it.atMs > System.currentTimeMillis() - 10 * 60_000 }.forEach { scheduleAction(ctx, it) }
    }

    private fun actionIntent(ctx: Context, id: String) = PendingIntent.getBroadcast(ctx, ("run$id").hashCode(),
        Intent(ctx, LifeReceiver::class.java).setAction(LifeReceiver.ACTION_RUN).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** "At 9 pm message Rahul …": an exact alarm that runs it. */
    fun scheduleAction(ctx: Context, a: ai.nexus.core.life.ScheduledAction) = at(ctx, maxOf(a.atMs, System.currentTimeMillis() + 1000), actionIntent(ctx, a.id))

    fun cancelAction(ctx: Context, id: String) { am(ctx)?.cancel(actionIntent(ctx, id)) }

    /** Phone is locked with a PIN: ask to unlock, then it runs. */
    fun notifyRunWhenUnlocked(ctx: Context, id: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "NEXUS reminders", NotificationManager.IMPORTANCE_HIGH))
        val run = PendingIntent.getActivity(ctx, ("now$id").hashCode(), Intent(ctx, MainActivity::class.java).putExtra("runScheduled", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(id.hashCode(), android.app.Notification.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Scheduled: ready to do").setContentText("$text — unlock and tap to do it now")
            .setContentIntent(run).addAction(android.app.Notification.Action.Builder(null, "Do it now", run).build())
            .setAutoCancel(true).build())
    }

    /** Modes with a daily start time switch on by themselves. */
    fun scheduleModes(ctx: Context) {
        for (m in NexusApp.get(ctx).life.data.modes) {
            val pi = PendingIntent.getBroadcast(ctx, ("mode" + m.id).hashCode(), Intent(ctx, LifeReceiver::class.java).setAction(LifeReceiver.ACTION_MODE).putExtra("mode", m.name),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val st = m.scheduleTime
            if (st != null) at(ctx, nextAt(st), pi) else am(ctx)?.cancel(pi)
        }
    }

    private fun nextAt(hhmm: String): Long {
        val (h, m) = hhmm.split(":").let { (it.getOrNull(0)?.toIntOrNull() ?: 7) to (it.getOrNull(1)?.toIntOrNull() ?: 30) }
        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    fun scheduleBriefs(ctx: Context) {
        val s = NexusApp.get(ctx).memory.settings
        for ((kind, time) in listOf("morning" to s.morningBrief, "evening" to s.eveningReview)) {
            val pi = PendingIntent.getBroadcast(ctx, kind.hashCode(), Intent(ctx, LifeReceiver::class.java).setAction(LifeReceiver.ACTION_BRIEF).putExtra("kind", kind),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            if (s.briefsEnabled) at(ctx, nextAt(time), pi) else am(ctx)?.cancel(pi)
        }
    }

    const val CHANNEL = "nexus_reminders"

    fun notify(ctx: Context, id: Int, title: String, text: String, section: String = "tasks") {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "NEXUS reminders", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(ctx, id, Intent(ctx, PanelActivity::class.java).putExtra("section", section), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(id, android.app.Notification.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title).setContentText(text).setStyle(android.app.Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build())
    }
}

/** Reminders, briefs, reply approvals, and boot re-scheduling. */
class LifeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = NexusApp.get(ctx)
        val life = app.life
        val boss = app.memory.settings.addressAs.ifBlank { "Boss" }
        when (intent.action) {
            ACTION_REMIND -> {
                val t = life.data.tasks.firstOrNull { it.id == intent.getStringExtra("task") } ?: return
                if (t.done) return
                val at = intent.getLongExtra("at", 0)
                life.store.updateTask(t.id) { it.copy(firedReminders = it.firedReminders + at) }
                val left = (t.dueMs ?: at) - System.currentTimeMillis()
                val whenText = when { left <= 60_000 -> "now"; left < 3600_000 -> "in ${left / 60_000} minutes"; left < 86_400_000 -> "in ${left / 3600_000} hours"; else -> "tomorrow" }
                val msg = "$boss, \"${t.title}\" is due $whenText."
                ReminderScheduler.notify(ctx, (t.id + at).hashCode(), "Due $whenText", t.title)
                val urgency = if (left < 3600_000) 1 else 2
                if (NotificationBrain.canSpeakNow(ctx, app, urgency)) app.speakProactive(msg)
            }
            ACTION_BRIEF -> {
                val kind = intent.getStringExtra("kind") ?: "morning"
                val text = if (kind == "evening") Briefs.evening(life.data, boss, System.currentTimeMillis()) else Briefs.morning(life.data, boss, System.currentTimeMillis())
                ReminderScheduler.notify(ctx, kind.hashCode(), if (kind == "evening") "Evening review" else "Morning brief", text, "home")
                if (NotificationBrain.canSpeakNow(ctx, app, 2)) app.speakProactive(text)
                ReminderScheduler.scheduleBriefs(ctx) // tomorrow
            }
            ACTION_RUN -> intent.getStringExtra("id")?.let { app.runScheduledAction(it) }
            ACTION_MODE -> {
                val name = intent.getStringExtra("mode") ?: return
                val m = life.data.modes.firstOrNull { it.name == name } ?: return
                val dow = (java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7 + 1 // Mon=1
                if (m.scheduleDays.contains(dow.toString()) && life.data.activeMode != name) app.ask("$name mode")
                ReminderScheduler.scheduleModes(ctx)
            }
            ACTION_SEND_REPLY -> {
                val r = life.data.notifications.firstOrNull { it.id == intent.getStringExtra("id") } ?: return
                val ok = r.replyKey != null && r.draftReply != null && NexusNotificationListener.replyByKey(ctx, r.replyKey!!, r.draftReply!!)
                NotificationBrain.markReply(app, r.id, if (ok) "sent" else "pending")
                ctx.getSystemService(NotificationManager::class.java)?.cancel(r.id.hashCode())
            }
            ACTION_DISMISS_REPLY -> {
                val id = intent.getStringExtra("id") ?: return
                NotificationBrain.markReply(app, id, "dismissed")
                ctx.getSystemService(NotificationManager::class.java)?.cancel(id.hashCode())
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> {
                ReminderScheduler.scheduleAll(ctx)
                // Android doesn't let apps start the microphone at boot: offer a one-tap start.
                val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
                nm.createNotificationChannel(NotificationChannel(ReminderScheduler.CHANNEL, "NEXUS reminders", NotificationManager.IMPORTANCE_HIGH))
                val open = PendingIntent.getActivity(ctx, 77, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                nm.notify(77, android.app.Notification.Builder(ctx, ReminderScheduler.CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("NEXUS is ready").setContentText("Tap to bring back the bubble.").setContentIntent(open).setAutoCancel(true).build())
            }
        }
    }

    companion object {
        const val ACTION_REMIND = "ai.nexus.REMIND"
        const val ACTION_BRIEF = "ai.nexus.BRIEF"
        const val ACTION_MODE = "ai.nexus.MODE"
        const val ACTION_RUN = "ai.nexus.RUN"
        const val ACTION_SEND_REPLY = "ai.nexus.SEND_REPLY"
        const val ACTION_DISMISS_REPLY = "ai.nexus.DISMISS_REPLY"
    }
}
