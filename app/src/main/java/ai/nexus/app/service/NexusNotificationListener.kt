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
        val n = PhoneNotification(sbn.packageName, label, title, text, sbn.postTime, sbn.key)
        synchronized(store) {
            store.removeAll { it.first.key == sbn.key }
            store.addFirst(n to sbn.notification.contentIntent)
            while (store.size > 50) store.removeLast()
        }
    }

    companion object {
        @Volatile var connected = false
        private val store = ArrayDeque<Pair<PhoneNotification, PendingIntent?>>()

        fun recent(limit: Int): List<PhoneNotification> = synchronized(store) { store.take(limit).map { it.first } }
        fun intentFor(key: String): PendingIntent? = synchronized(store) { store.firstOrNull { it.first.key == key }?.second }
        fun component(ctx: Context) = ComponentName(ctx, NexusNotificationListener::class.java)
    }
}
