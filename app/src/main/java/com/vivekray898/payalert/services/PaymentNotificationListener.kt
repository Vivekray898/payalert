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
        if (!graph.settings.state.value.allowsNotificationPackage(sbn.packageName)) {
            Log.d(TAG, "Ignoring notification from disabled payment app ${sbn.packageName}")
            return
        }
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
                now = sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis(),
                eventId = "notification:${sbn.key}"
            )
            Log.i(TAG, "Notification pipeline result: ${outcome.reason}")
        }
    }

    /** Prefer transaction content over the app-owned notification title. */
    private fun extractText(extras: Bundle?): String? {
        if (extras == null) return null
        return NotificationTextExtractor.choose(
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()
        )
    }

    private companion object {
        const val SOURCE = "notification"
        const val TAG = "PayAlert"
    }
}
