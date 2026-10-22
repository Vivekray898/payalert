package com.vivekray898.payalert.relay

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vivekray898.payalert.AppGraph
import com.vivekray898.payalert.role.LocalDeviceRole
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

            // A push carries only a wake-up id. Refreshing the pairing first is
            // important for a cold process: the relay engine has no context until
            // the authenticated team snapshot has been read.
            val delivered = graph.runRelayPump()
            if (!delivered && graph.team.configured && graph.team.account.value != null &&
                graph.settings.localDeviceRoleState.currentRole == LocalDeviceRole.EMPLOYEE
            ) {
                Result.retry()
            } else {
                Result.success()
            }
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
            val token = inputData.getString("fcm_token") ?: return Result.failure()

            val graph = AppGraph.get(applicationContext)
            if (!graph.team.configured || graph.team.account.value == null) {
                // The token will be requested again when the signed-in pairing is
                // opened. Do not retry forever on a local-only installation.
                android.util.Log.i("PayAlertRelay", "FCM token deferred until team sign-in")
                return Result.success()
            }
            graph.updateFcmToken(token)
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
