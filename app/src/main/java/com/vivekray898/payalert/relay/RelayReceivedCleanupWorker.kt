package com.vivekray898.payalert.relay

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vivekray898.payalert.data.PayAlertDatabase
import kotlinx.coroutines.CancellationException

/** Background safety net for received-announcement retention. */
class RelayReceivedCleanupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = try {
        val deleted = RelayCleanup.sweepOnAppOpen(
            PayAlertDatabase.get(applicationContext),
            System.currentTimeMillis()
        )
        Result.success(androidx.work.workDataOf(DELETED_COUNT_KEY to deleted))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Result.retry()
    }

    private companion object {
        const val DELETED_COUNT_KEY = "deleted_received_announcements"
    }
}
