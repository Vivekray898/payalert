package com.vivekray898.payalert.relay

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vivekray898.payalert.AppGraph
import kotlinx.coroutines.CancellationException

/**
 * Worker that handles a relay message received via FCM push.
 *
 * This worker is triggered by [PayAlertMessagingService] when a data-only FCM push arrives
 * with type="relay_message". It wakes the employee device and fetches the envelope via RPC.
 */
class RelayMessageFcmWork(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val graph = AppGraph.get(applicationContext)
            val message = inputData.getString("message_id")
                ?: return Result.failure()

            // Log the FCM receive event for latency measurement
            val fcmTimestamp = System.currentTimeMillis()
            android.util.Log.i("PayAlertRelay", "employee_fcm_received message_id=$message t=$fcmTimestamp")

            // Log the FCM receive event for latency measurement
            android.util.Log.i("PayAlertRelay", "employee_fcm_received message_id=$message t=${System.currentTimeMillis()}")

            // The actual fetch will happen through the pump mechanism
            // For now, return success to avoid retry storms
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            android.util.Log.w("PayAlertRelay", "FCM relay handling failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        const val KEY_MESSAGE_ID = "message_id"
    }
}

/**
 * Worker that updates the FCM token on the server.
 *
 * Triggered when [PayAlertMessagingService.onNewToken] is called.
 */
class RelayUpdateFcmTokenWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val graph = AppGraph.get(applicationContext)
            val token = inputData.getString("fcm_token") ?: return Result.failure()

            // Log the token update (in production, this would call an RPC)
            android.util.Log.i("PayAlertRelay", "FCM token updated: ${token.take(20)}...")
            Result.success()
        } catch (e: Exception) {
            android.util.Log.w("PayAlertRelay", "FCM token update failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        const val KEY_FCM_TOKEN = "fcm_token"
    }
}

/** Result of fetching and storing a relay message from FCM. */
enum class RelayFetchResult {
    SUCCESS,
    DUPLICATE,
    FAILED
}
