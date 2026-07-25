package com.vishnu.jarvis

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

// Must be turned on once in Settings > Notification access > Jarvis.
// Summarizing here is plain grouping/counting logic - no AI involved.
class NotificationCaptureService : NotificationListenerService() {

    companion object {
        private var active: MutableList<StatusBarNotification> = mutableListOf()

        // Groups notifications by app and lists the most recent title per app.
        // Good enough for "what did I miss" without any model reasoning about it.
        fun summarize(): String {
            if (active.isEmpty()) return "No active notifications."
            val byApp = active.groupBy { it.packageName }
            return byApp.entries.joinToString("\n") { (pkg, notifs) ->
                val appName = pkg.substringAfterLast(".")
                val latestTitle = notifs.lastOrNull()
                    ?.notification?.extras?.getCharSequence("android.title")
                    ?: "notification"
                "$appName (${notifs.size}): $latestTitle"
            }
        }

        // Lists sender + message text for each WhatsApp notification currently
        // showing - this is what answers "any new WhatsApp messages?".
        fun whatsAppMessages(): String {
            val fromWhatsApp = active.filter { it.packageName == "com.whatsapp" }
            if (fromWhatsApp.isEmpty()) return "No new WhatsApp messages."
            return fromWhatsApp.joinToString("\n") { sbn ->
                val sender = sbn.notification.extras.getCharSequence("android.title") ?: "Unknown"
                val text = sbn.notification.extras.getCharSequence("android.text") ?: ""
                "$sender: $text"
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        active = activeNotifications?.toMutableList() ?: mutableListOf()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        active.removeAll { it.key == sbn.key }
        active.add(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        active.removeAll { it.key == sbn.key }
    }
}
