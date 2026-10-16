// language: Kotlin, file: app/src/main/java/com/vivo/wirelink/NotificationRelay.kt
package com.vivo.wirelink

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

/**
 * NotificationRelay — listens to every incoming notification on the device
 * (WhatsApp, Instagram, SMS, whatever) and pushes each one onto LinkBus.
 *
 * KeeperService picks up LinkBus events and ships them to the backend.
 *
 * Requires the user to manually enable Notification Access:
 *   Settings -> Apps -> Special access -> Notification access -> vivo WireLink
 */
class NotificationRelay : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val pkg = sbn.packageName ?: return

        // ignore our own notifications, the OS UI, and anything without content
        if (pkg == packageName) return
        if (pkg == "android") return
        if (pkg.startsWith("com.android.systemui")) return

        val extras = sbn.notification?.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text  = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()

        // prefer the big text when present (expanded form has more detail)
        val body = if (bigText.isNotEmpty()) bigText else text

        if (title.isEmpty() && body.isEmpty()) return

        val appLabel = try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (t: Throwable) {
            pkg
        }

        val frame = JSONObject().apply {
            put("app", appLabel)
            put("pkg", pkg)
            put("title", title)
            put("text", body)
            put("ts", sbn.postTime)
        }

        LinkBus.push(frame)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // hook ready
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // OS will rebind if permission is still granted
    }
}
