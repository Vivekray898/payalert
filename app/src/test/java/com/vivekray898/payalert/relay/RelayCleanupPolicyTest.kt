package com.vivekray898.payalert.relay

import org.junit.Assert.assertEquals
import org.junit.Test

class RelayCleanupPolicyTest {

    @Test
    fun `received announcements expire exactly 24 hours after receipt`() {
        val receivedAt = 1_700_000_000_000L

        assertEquals(
            receivedAt + 24L * 60L * 60L * 1_000L,
            RelayCleanupPolicy.receivedExpiresAt(receivedAt)
        )
        assertEquals(24L, RelayCleanupPolicy.RECEIVED_ANNOUNCEMENT_TTL_HOURS)
    }

    @Test(expected = ArithmeticException::class)
    fun `expiry calculation rejects timestamp overflow`() {
        RelayCleanupPolicy.receivedExpiresAt(Long.MAX_VALUE)
    }
}
