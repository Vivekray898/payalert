package com.vivekray898.payalert.relay

/** Time policy shared by received-announcement inserts, app-open cleanup, and the worker. */
object RelayCleanupPolicy {
    const val RECEIVED_ANNOUNCEMENT_TTL_HOURS = 24L
    const val RECEIVED_ANNOUNCEMENT_TTL_MILLIS =
        RECEIVED_ANNOUNCEMENT_TTL_HOURS * 60L * 60L * 1_000L

    fun receivedExpiresAt(receivedAt: Long): Long =
        Math.addExact(receivedAt, RECEIVED_ANNOUNCEMENT_TTL_MILLIS)
}
