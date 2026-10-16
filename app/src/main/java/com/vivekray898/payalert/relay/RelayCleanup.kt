package com.vivekray898.payalert.relay

import com.vivekray898.payalert.data.PayAlertDatabase

/** Explicit app-open hook; callers decide when the foreground lifecycle is considered open. */
object RelayCleanup {
    suspend fun sweepOnAppOpen(
        database: PayAlertDatabase,
        now: Long = System.currentTimeMillis()
    ): Int =
        database.receivedAnnouncementDao().deleteExpired(now)
}
