package com.vivekray898.payalert.services

import android.app.Notification
import android.os.Bundle
import android.util.Log
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.vivekray898.payalert.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Reads notifications posted by UPI and banking apps and feeds their text into the
 * shared pipeline. This is the primary detection path (UPI apps frequently do not
 * emit an SMS for a payment).
 */
class PaymentNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        AppGraph.get(this).setListenerConnected(true)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        AppGraph.get(this).setListenerConnected(false)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification: Notification = sbn?.notification ?: return
        if (sbn.packageName == packageName) return

        val text = extractText(notification.extras)
        if (text.isNullOrBlank()) {
            Log.d(TAG, "Notification from ${sbn.packageName} carried no readable text")
            return
        }
        val sourceDetail = sbn.packageName
        Log.d(TAG, "Notification from $sourceDetail: $text")
        scope.launch {
            val outcome = AppGraph.get(applicationContext).pipeline.handle(text, SOURCE, sourceDetail)
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
