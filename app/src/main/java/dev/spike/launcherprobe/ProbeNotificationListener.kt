package dev.spike.launcherprobe

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Proves the launcher could read and re-render notifications for its own shade replacement.
 * Once access is granted, getActiveNotifications() returns the same StatusBarNotifications the
 * stock shade shows. This probe only counts them; it doesn't display anything.
 */
class ProbeNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        connected = true
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) { /* no-op for the probe */ }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) { /* no-op for the probe */ }

    companion object {
        @Volatile
        var connected = false
    }
}
