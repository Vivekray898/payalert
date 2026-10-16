package com.vivekray898.payalert.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayRetryPolicyTest {

    @Test
    fun `backoff grows with each failed attempt`() {
        val delays = (1 until RelayRetryPolicy.MAX_ATTEMPTS)
            .map { RelayRetryPolicy.delayForAttempt(it) }

        assertEquals(30_000L, delays.first())
        assertTrue(delays.zipWithNext().all { (slower, later) -> later > slower })
        assertEquals(6L * 60L * 60_000L, delays.last())
    }

    @Test
    fun `the schedule is bounded so a long outage cannot shift the delay`() {
        assertEquals(
            RelayRetryPolicy.delayForAttempt(RelayRetryPolicy.MAX_ATTEMPTS + 5),
            RelayRetryPolicy.delayForAttempt(RelayRetryPolicy.MAX_ATTEMPTS - 1)
        )
    }

    @Test
    fun `queued messages stop being retried after the attempt limit`() {
        assertTrue(RelayRetryPolicy.canRetry(RelayRetryPolicy.MAX_ATTEMPTS - 1))
        assertFalse(RelayRetryPolicy.canRetry(RelayRetryPolicy.MAX_ATTEMPTS))
        assertEquals(1_030_000L, RelayRetryPolicy.nextAttemptAt(1_000_000L, 1))
        assertNull(RelayRetryPolicy.nextAttemptAt(1_000_000L, RelayRetryPolicy.MAX_ATTEMPTS))
    }
}
