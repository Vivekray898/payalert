package com.vivekray898.payalert.services

import android.app.Notification
import android.os.Bundle
import android.util.Log
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.vivekray898.payalert.AppGraph
import com.vivekray898.payalert.role.PersistedLocalDeviceRoleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads notifications posted by UPI and banking apps and feeds their text into the
 * shared pipeline. This is the primary detection path (UPI apps frequently do not
 * emit an SMS for a payment).
 */
class PaymentNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connected = AtomicBoolean(false)

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (!connected.compareAndSet(false, true)) {
            Log.w(TAG, "onListenerConnected called while already connected; ignoring duplicate bind")
            return
        }
        AppGraph.get(this).setListenerConnected(true)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        connected.set(false)
        AppGraph.get(this).setListenerConnected(false)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val roleState = PersistedLocalDeviceRoleState(applicationContext)
        // Do not even extract notification text on an employee device. The role is read from
        // SharedPreferences for every callback so a confirmed pairing change takes effect now.
        if (!roleState.allowsLocalDetection) return

        val notification: Notification = sbn?.notification ?: return
        if (sbn.packageName == packageName) return

        val text = extractText(notification.extras)
        if (text.isNullOrBlank()) {
            Log.d(TAG, "Notification from ${sbn.packageName} carried no readable text")
            return
        }
        val graph = AppGraph.get(applicationContext)
        if (!graph.parser.isLikelyNotificationSource(sbn.packageName, text)) {
            Log.d(TAG, "Ignoring notification from unsupported source ${sbn.packageName}")
            return
        }
        val sourceDetail = sbn.packageName
        Log.d(TAG, "Notification from $sourceDetail: $text")
        scope.launch {
            if (!roleState.allowsLocalDetection) return@launch
            val outcome = graph.pipeline.handle(
                text = text,
                source = SOURCE,
                sourceDetail = sourceDetail,
                eventId = "notification:${sbn.key}"
            )
            Log.i(TAG, "Notification pipeline result: ${outcome.reason}")
        }
    }

    /** Combine every text-bearing field so bank apps that only fill bigText still parse. */
    private fun extractText(extras: Bundle?): String? {
        if (extras == null) return null
        val parts = listOf(
            Notification.EXTRA_TITLE,
            Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_SUMMARY_TEXT
        ).mapNotNull { key ->
            extras.getCharSequence(key)?.toString()?.takeIf { it.isNotBlank() }
        }
        if (parts.isEmpty()) return null
        return parts.joinToString(" ")
    }

    private companion object {
        const val SOURCE = "notification"
        const val TAG = "PayAlert"
    }
}
