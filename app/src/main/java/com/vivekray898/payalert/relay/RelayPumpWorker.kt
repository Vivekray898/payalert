package com.vivekray898.payalert.relay

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vivekray898.payalert.services.PaymentMonitorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Periodic relay pump.
 *
 * A paired device cannot rely on the app being open, and there is no push channel, so a
 * network-constrained periodic job claims new envelopes and flushes queued ones. The foreground
 * loop covers the case where the app is open, where 15 minutes would be far too slow.
 */
class RelayPumpWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = try {
        // The foreground monitor/employee relay already owns the fast loop. Do not wake a second
        // scheduler every fifteen minutes while one of those services is alive.
        if (PaymentMonitorService.isRunning || EmployeeRealtimeService.isRunning) {
            Result.success()
        } else {
            val graph = com.vivekray898.payalert.AppGraph.get(applicationContext)
            val delivered = graph.runRelayPump()
            if (!delivered && graph.team.configured && graph.team.account.value != null) {
                Result.retry()
            } else {
                Result.success()
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Offline is the common case; the next run retries without a backoff storm.
        Result.success()
    }
}

/** Registers the periodic pump. KEEP avoids replacing a scheduled job on every app open. */
object RelayPumpScheduler {
    const val WORK_NAME = "payalert_relay_pump"

    /** WorkManager's floor for periodic work; the foreground loop covers shorter intervals. */
    private const val INTERVAL_MINUTES = 15L

    fun schedule(context: Context) = WorkManager.getInstance(context.applicationContext)
        .enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RelayPumpWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
        )
}
