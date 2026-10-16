package com.vivekray898.payalert.relay

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Registers the durable periodic cleanup; KEEP avoids replacing a scheduled worker on every open. */
object RelayCleanupScheduler {
    const val WORK_NAME = "payalert_relay_received_cleanup"

    fun schedule(context: Context): Operation =
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RelayReceivedCleanupWorker>(
                RelayCleanupPolicy.RECEIVED_ANNOUNCEMENT_TTL_HOURS,
                TimeUnit.HOURS
            )
                .setInitialDelay(
                    RelayCleanupPolicy.RECEIVED_ANNOUNCEMENT_TTL_HOURS,
                    TimeUnit.HOURS
                )
                .build()
        )
}
