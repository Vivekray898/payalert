package com.vivekray898.payalert.services

import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.vivekray898.payalert.AppGraph
import com.vivekray898.payalert.relay.RelayMessageFcmWork
import com.vivekray898.payalert.relay.RelayUpdateFcmTokenWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Firebase Cloud Messaging service that wakes the employee app when a relay message is delivered.
 *
 * This service handles two message types:
 * - type="relay_message": data-only push with message_id. Wakes the app and triggers employee receive.
 * - type="pairing_changed": data-only push that triggers a pairing refresh.
 *
 * The service never includes payload content in FCM — only message_id is delivered via push.
 * The actual envelope is fetched via authenticated RPC after wake-up.
 */
class PayAlertMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "FCM token refreshed: ${token.take(20)}...")
        // Schedule a one-time work to update the server with the new token.
        val work = OneTimeWorkRequestBuilder<RelayUpdateFcmTokenWorker>()
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            "relay_update_fcm_token",
            androidx.work.ExistingWorkPolicy.REPLACE,
            work
        )
    }

    override fun onMessageReceived(message: RemoteMessage) {
        Log.d(TAG, "FCM message received: ${message.data}")
        val type = message.data["type"]
        when (type) {
            "relay_message" -> {
                val messageId = message.data["message_id"]
                if (messageId != null) {
                    Log.i(TAG, "relay_message received: $messageId")
                    handleRelayMessage(messageId)
                } else {
                    Log.w(TAG, "relay_message without message_id")
                }
            }
            "pairing_changed" -> {
                Log.i(TAG, "pairing_changed received")
                handlePairingChanged()
            }
            else -> Log.d(TAG, "Unknown FCM message type: $type")
        }
    }

    private fun handleRelayMessage(messageId: String) {
        // Hand off to a one-shot worker to avoid blocking the FCM callback.
        val work = OneTimeWorkRequestBuilder<RelayMessageFcmWork>()
            .addTag("relay_fcm")
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            "relay_fcm_$messageId",
            androidx.work.ExistingWorkPolicy.KEEP,
            work
        )
    }

    private fun handlePairingChanged() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val graph = AppGraph.get(this@PayAlertMessagingService)
                graph.teamController.refresh()
                Log.i(TAG, "Pairing refreshed after FCM notification")
            } catch (e: Exception) {
                Log.w(TAG, "Pairing refresh failed: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "PayAlertFcm"
    }
}
