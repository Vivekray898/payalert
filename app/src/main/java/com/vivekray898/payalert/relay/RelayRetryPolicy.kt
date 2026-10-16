package com.vivekray898.payalert.relay

/**
 * Exponential backoff for relay delivery.
 *
 * Pure and clock-injectable so the schedule is unit-tested. The queue is persisted, so a device
 * that is offline for an hour resumes with the same delays rather than restarting them.
 */
object RelayRetryPolicy {

    /** After this many failed attempts a queued message stops being retried. */
    const val MAX_ATTEMPTS = 8

    private val DELAYS_MILLIS = longArrayOf(
        30_000L,          // 30s
        2L * 60_000L,     // 2m
        5L * 60_000L,     // 5m
        15L * 60_000L,    // 15m
        60L * 60_000L,    // 1h
        3L * 60L * 60_000L, // 3h
        6L * 60L * 60_000L  // 6h
    )

    /** Attempt number is the count *after* the attempt that just failed. */
    fun delayForAttempt(attemptCount: Int): Long {
        val index = (attemptCount - 1).coerceIn(0, DELAYS_MILLIS.lastIndex)
        return DELAYS_MILLIS[index]
    }

    fun canRetry(attemptCount: Int): Boolean = attemptCount < MAX_ATTEMPTS

    /** `now + backoff`, or null once the message has exhausted its attempts. */
    fun nextAttemptAt(now: Long, attemptCount: Int): Long? =
        if (canRetry(attemptCount)) Math.addExact(now, delayForAttempt(attemptCount)) else null
}
