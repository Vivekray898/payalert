package com.vivekray898.payalert.services

import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.vivekray898.payalert.relay.RelayFcmContract
import com.vivekray898.payalert.relay.RelayMessageFcmWork
import com.vivekray898.payalert.relay.RelayUpdateFcmTokenWorker

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
        enqueueTokenUpdate(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        Log.d(TAG, "FCM message received: ${message.data}")
        val type = message.data[RelayFcmContract.TYPE_KEY]
        when (type) {
            RelayFcmContract.RELAY_MESSAGE_TYPE -> {
                val messageId = RelayFcmContract.relayMessageId(message.data)
                if (messageId != null) {
                    Log.i(TAG, "relay_message received: $messageId")
                    // This visible high-importance notification is posted before
                    // the network fetch so the device gets immediate user-visible
                    // feedback even while the authenticated RPC is starting.
                    RelayAnnouncementNotifier(this).showWakeUp(messageId)
                    handleRelayMessage(messageId)
                } else {
                    Log.w(TAG, "relay_message without message_id")
                }
            }
            RelayFcmContract.PAIRING_CHANGED_TYPE -> {
                Log.i(TAG, "pairing_changed received")
                handlePairingChanged()
            }
            else -> Log.d(TAG, "Unknown FCM message type: $type")
        }
    }

    private fun handleRelayMessage(messageId: String) {
        // Hand off to expedited work so the FCM callback remains short while the
        // relay fetch runs inside WorkManager's background execution window.
        val work = OneTimeWorkRequestBuilder<RelayMessageFcmWork>()
            .setInputData(workDataOf(RelayMessageFcmWork.KEY_MESSAGE_ID to messageId))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag("relay_fcm")
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            "relay_fcm_$messageId",
            androidx.work.ExistingWorkPolicy.KEEP,
            work
        )
    }

    private fun handlePairingChanged() {
        androidx.work.OneTimeWorkRequestBuilder<RelayMessageFcmWork>()
            .setInputData(workDataOf(RelayMessageFcmWork.KEY_MESSAGE_ID to "pairing_changed"))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
            .also { request ->
                WorkManager.getInstance(this).enqueueUniqueWork(
                    "relay_pairing_changed",
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    request
                )
            }
        Log.i(TAG, "Pairing refresh scheduled")
    }

    companion object {
        private const val TAG = "PayAlertFcm"

        /** Refreshes the current token after sign-in/pairing, not only on rotation. */
        fun refreshToken(context: android.content.Context) {
            runCatching {
                FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                    enqueueTokenUpdate(context, token)
                }.addOnFailureListener { error ->
                    Log.w(TAG, "FCM token lookup failed: ${error.message}")
                }
            }.onFailure { error ->
                Log.w(TAG, "FCM token service unavailable: ${error.message}")
            }
        }

        private fun enqueueTokenUpdate(context: android.content.Context, token: String) {
            val trimmed = token.trim()
            if (trimmed.isEmpty()) return
            val work = OneTimeWorkRequestBuilder<RelayUpdateFcmTokenWorker>()
                .setInputData(workDataOf(RelayUpdateFcmTokenWorker.KEY_FCM_TOKEN to trimmed))
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "relay_update_fcm_token",
                androidx.work.ExistingWorkPolicy.REPLACE,
                work
            )
        }
    }
}
