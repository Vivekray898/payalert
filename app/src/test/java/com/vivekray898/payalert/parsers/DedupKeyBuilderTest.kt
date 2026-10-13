package com.vivekray898.payalert.parsers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DedupKeyBuilderTest {

    private val baseTime = 1_700_000_000_000L

    @Test
    fun `same payment from sms and notification collapses to one key`() {
        val fromSms = DedupKeyBuilder.build(
            kind = "credit",
            amount = 250.0,
            utr = null,
            merchant = "Rahul Sharma",
            receivedAt = baseTime
        )
        val fromNotification = DedupKeyBuilder.build(
            kind = "credit",
            amount = 250.0,
            utr = null,
            merchant = "rahul sharma",
            receivedAt = baseTime + 60_000L
        )

        assertEquals(fromSms, fromNotification)
    }

    @Test
    fun `different amounts do not collapse`() {
        val first = DedupKeyBuilder.build("credit", 250.0, null, "ACME", baseTime)
        val second = DedupKeyBuilder.build("credit", 260.0, null, "ACME", baseTime)

        assertNotEquals(first, second)
    }

    @Test
    fun `different directions do not collapse`() {
        val credit = DedupKeyBuilder.build("credit", 250.0, null, "ACME", baseTime)
        val debit = DedupKeyBuilder.build("debit", 250.0, null, "ACME", baseTime)

        assertNotEquals(credit, debit)
    }

    @Test
    fun `reference number dominates merchant and time`() {
        val first = DedupKeyBuilder.build("debit", 499.0, "412345678901", "Store A", baseTime)
        val second = DedupKeyBuilder.build(
            "debit",
            499.0,
            "412345678901",
            "Store B",
            baseTime + 30 * 60_000L
        )

        assertEquals(first, second)
        assertEquals("utr:412345678901|debit|499.00", first)
    }

    @Test
    fun `events outside the window do not collapse`() {
        val first = DedupKeyBuilder.build("credit", 250.0, null, "ACME", baseTime)
        val second = DedupKeyBuilder.build(
            "credit",
            250.0,
            null,
            "ACME",
            baseTime + 30 * 60_000L
        )

        assertNotEquals(first, second)
    }
}
