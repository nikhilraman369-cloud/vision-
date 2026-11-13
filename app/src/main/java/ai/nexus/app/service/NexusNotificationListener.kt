package ai.nexus.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import ai.nexus.core.device.PhoneNotification

/** Keeps recent notifications in memory (never on disk) so NEXUS can read them out. */
class NexusNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() { connected = true; activeNotifications?.forEach { add(it) } }
    override fun onListenerDisconnected() { connected = false }
    override fun onNotificationPosted(sbn: StatusBarNotification) = add(sbn)
    override fun onNotificationRemoved(sbn: StatusBarNotification) { synchronized(store) { store.removeAll { it.first.key == sbn.key } } }

    private fun add(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.isOngoing) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        // Never read or keep OTPs, bank or payment messages.
        if (ai.nexus.core.life.NotificationIntel.isSensitive(sbn.packageName, title, text)) return
        val n = PhoneNotification(sbn.packageName, label, title, text, sbn.postTime, sbn.key)
        synchronized(store) {
            store.removeAll { it.first.key == sbn.key }
            store.addFirst(n to sbn.notification.contentIntent)
            replyActions.remove(sbn.key)
            sbn.notification.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }?.let { replyActions[sbn.key] = it }
            while (store.size > 50) store.removeLast()
        }
        // Understand it: assigned work → task, deadline, reminders, draft; questions → draft reply.
        if (!sbn.notification.flags.and(Notification.FLAG_GROUP_SUMMARY).let { it != 0 }) {
            val hasReply = sbn.notification.actions?.any { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true } == true
            runCatching { ai.nexus.app.life.NotificationBrain.onPosted(applicationContext, sbn.packageName, label, title, text, sbn.key, hasReply) }
        }
    }

    companion object {
        @Volatile var connected = false
        private val store = ArrayDeque<Pair<PhoneNotification, PendingIntent?>>()

        private val replyActions = HashMap<String, Notification.Action>()

        /** The newest notification from [name] that has a reply button. */
        fun replyTarget(name: String): Pair<PhoneNotification, Notification.Action>? = synchronized(store) {
            val n = name.lowercase().trim()
            store.map { it.first }.filter { replyActions.containsKey(it.key) }
                .firstOrNull { p -> p.title.lowercase().let { t -> t == n || t.startsWith("$n ") || t.contains(n) || n.contains(t) && t.length > 2 } }
                ?.let { it to replyActions.getValue(it.key) }
        }

        /** Send [text] through the reply button of the notification with [key] (no app opened). */
        fun replyByKey(ctx: Context, key: String, text: String): Boolean {
            val action = synchronized(store) { replyActions[key] } ?: return false
            val inputs = action.remoteInputs ?: return false
            return runCatching {
                val results = android.os.Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
                val fill = android.content.Intent().addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND)
                android.app.RemoteInput.addResultsToIntent(inputs, fill, results)
                action.actionIntent.send(ctx, 0, fill)
                true
            }.getOrDefault(false)
        }

        fun recent(limit: Int): List<PhoneNotification> = synchronized(store) { store.take(limit).map { it.first } }
        fun intentFor(key: String): PendingIntent? = synchronized(store) { store.firstOrNull { it.first.key == key }?.second }
        fun component(ctx: Context) = ComponentName(ctx, NexusNotificationListener::class.java)
    }
}
