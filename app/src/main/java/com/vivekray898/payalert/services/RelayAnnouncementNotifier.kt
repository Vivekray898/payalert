package com.vivekray898.payalert.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.vivekray898.payalert.R

/**
 * Posts the employee-side notification for a relayed payment.
 *
 * A high-priority, visible notification is deliberately posted as soon as FCM
 * wakes the process. The temporary entry is then replaced with the authenticated
 * local announcement after the encrypted envelope is fetched and decrypted.
 */
class RelayAnnouncementNotifier(context: Context) {
    private val appContext = context.applicationContext
    private val notifications = NotificationManagerCompat.from(appContext)

    fun showWakeUp(messageId: String) {
        wakeDisplayBriefly()
        post(
            messageId = messageId,
            title = appContext.getString(R.string.relay_wakeup_notification_title),
            text = appContext.getString(R.string.relay_wakeup_notification_text),
            timeoutAfter = WAKE_UP_TIMEOUT_MILLIS
        )
    }

    fun showAnnouncement(messageId: String, text: String) {
        post(
            messageId = messageId,
            title = appContext.getString(R.string.relay_announcement_notification_title),
            text = text,
            timeoutAfter = null
        )
    }

    private fun post(
        messageId: String,
        title: String,
        text: String,
        timeoutAfter: Long?
    ) {
        createChannel(appContext)
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
        timeoutAfter?.let(builder::setTimeoutAfter)

        try {
            notifications.notify(notificationId(messageId), builder.build())
        } catch (security: SecurityException) {
            // Android 13+ can deny POST_NOTIFICATIONS. TTS still runs, and the
            // user can enable this dedicated channel from system settings.
            Log.w(TAG, "employee announcement notification is not permitted", security)
        }
    }

    /**
     * A high-importance notification wakes the app/CPU, but some OEM lock screens
     * leave the display asleep. Keep the display awake only for the short handoff
     * to the authenticated relay worker; do not hold a wake lock during polling.
     */
    private fun wakeDisplayBriefly() {
        val power = appContext.getSystemService(PowerManager::class.java) ?: return
        @Suppress("DEPRECATION")
        val wakeLock = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "$TAG:employeeWake"
        ).apply {
            setReferenceCounted(false)
        }
        try {
            wakeLock.acquire(SCREEN_WAKE_TIMEOUT_MILLIS)
        } catch (security: SecurityException) {
            Log.w(TAG, "employee display wake was not permitted", security)
        }
    }

    companion object {
        const val CHANNEL_ID = "payalert_employee_announcements"

        private const val TAG = "PayAlertRelay"
        private const val WAKE_UP_TIMEOUT_MILLIS = 15_000L
        private const val SCREEN_WAKE_TIMEOUT_MILLIS = 5_000L

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.relay_announcement_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.relay_announcement_channel_description)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }

        /** A repeated FCM delivery updates the same notification instead of alerting twice. */
        private fun notificationId(messageId: String): Int =
            (messageId.hashCode() and Int.MAX_VALUE).coerceAtLeast(1)
    }
}
