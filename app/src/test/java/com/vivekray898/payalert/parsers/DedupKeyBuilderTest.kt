package com.vivekray898.payalert.parsers

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DedupKeyBuilderTest {

    private val baseTime = 1_700_000_000_000L

    // ===== Exact key tests =====

    @Test
    fun `same payment from sms and notification collapses to one exact key`() = runTest {
        val fromSms = DedupKeyBuilder.exactKey(
            kind = "credit",
            amount = 250.0,
            utr = null,
            merchant = "Rahul Sharma",
            receivedAt = baseTime
        )
        val fromNotification = DedupKeyBuilder.exactKey(
            kind = "credit",
            amount = 250.0,
            utr = null,
            merchant = "rahul sharma",
            receivedAt = baseTime + 60_000L
        )

        assertEquals(fromSms, fromNotification)
    }

    @Test
    fun `different amounts do not collapse`() = runTest {
        val first = DedupKeyBuilder.exactKey("credit", 250.0, null, "ACME", baseTime)
        val second = DedupKeyBuilder.exactKey("credit", 260.0, null, "ACME", baseTime)

        assertNotEquals(first, second)
    }

    @Test
    fun `different directions do not collapse`() = runTest {
        val credit = DedupKeyBuilder.exactKey("credit", 250.0, null, "ACME", baseTime)
        val debit = DedupKeyBuilder.exactKey("debit", 250.0, null, "ACME", baseTime)

        assertNotEquals(credit, debit)
    }

    @Test
    fun `reference number dominates merchant and time`() = runTest {
        val first = DedupKeyBuilder.exactKey("debit", 499.0, "412345678901", "Store A", baseTime)
        val second = DedupKeyBuilder.exactKey(
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
    fun `events outside the window do not collapse`() = runTest {
        val first = DedupKeyBuilder.exactKey("credit", 250.0, null, "ACME", baseTime)
        val second = DedupKeyBuilder.exactKey(
            "credit",
            250.0,
            null,
            "ACME",
            baseTime + 30 * 60_000L
        )

        assertNotEquals(first, second)
    }

    // ===== Semantic key tests =====

    @Test
    fun `semantic key - three UPI apps same payment collapses`() = runTest {
        val phonepe = DedupKeyBuilder.semanticKey(
            kind = "credit",
            amount = 500.0,
            currency = "INR",
            merchant = "zomato@ybl",
            account = null,
            receivedAt = baseTime
        )
        val paytm = DedupKeyBuilder.semanticKey(
            kind = "credit",
            amount = 500.0,
            currency = "INR",
            merchant = "Zomato@ybl",
            account = null,
            receivedAt = baseTime + 80
        )
        val gpay = DedupKeyBuilder.semanticKey(
            kind = "credit",
            amount = 500.0,
            currency = "INR",
            merchant = "ZOMATO PAID",
            account = null,
            receivedAt = baseTime + 300
        )

        assertEquals(phonepe, paytm)
        assertEquals(phonepe, gpay)
    }

    @Test
    fun `semantic key - different directions do not collapse`() = runTest {
        val credit = DedupKeyBuilder.semanticKey(
            kind = "credit",
            amount = 500.0,
            currency = "INR",
            merchant = "zomato@ybl",
            account = null,
            receivedAt = baseTime
        )
        val debit = DedupKeyBuilder.semanticKey(
            kind = "debit",
            amount = 500.0,
            currency = "INR",
            merchant = "zomato@ybl",
            account = null,
            receivedAt = baseTime
        )

        assertNotEquals(credit, debit)
    }

    @Test
    fun `semantic key - different merchant does not collapse`() = runTest {
        val first = DedupKeyBuilder.semanticKey(
            kind = "credit",
            amount = 500.0,
            currency = "INR",
            merchant = "zomato@ybl",
            account = null,
            receivedAt = baseTime
        )
        val second = DedupKeyBuilder.semanticKey(
            kind = "credit",
            amount = 500.0,
            currency = "INR",
            merchant = "swiggy@ybl",
            account = null,
            receivedAt = baseTime
        )

        assertNotEquals(first, second)
    }

    @Test
    fun `semantic key - 6 minutes apart does not collapse`() = runTest {
        val first = DedupKeyBuilder.semanticKey(
            kind = "credit",
            amount = 500.0,
            currency = "INR",
            merchant = "zomato@ybl",
            account = null,
            receivedAt = baseTime
        )
        val second = DedupKeyBuilder.semanticKey(
            kind = "credit",
            amount = 500.0,
            currency = "INR",
            merchant = "zomato@ybl",
            account = null,
            receivedAt = baseTime + 6 * 60_000L // 6 minutes = different bucket
        )

        assertNotEquals(first, second)
    }

    @Test
    fun `semantic key - 4 minutes apart collapses`() = runTest {
        // Use a time that aligns with the 5-minute bucket boundary
        val alignedTime = 1_700_000_000_000L - (1_700_000_000_000L % 300_000L)
        val first = DedupKeyBuilder.semanticKey(
            kind = "credit",
            amount = 500.0,
            currency = "INR",
            merchant = "zomato@ybl",
            account = null,
            receivedAt = alignedTime
        )
        val second = DedupKeyBuilder.semanticKey(
            kind = "credit",
            amount = 500.0,
            currency = "INR",
            merchant = "zomato@ybl",
            account = null,
            receivedAt = alignedTime + 4 * 60_000L // 4 minutes = same bucket
        )

        assertEquals(first, second)
    }

    @Test
    fun `semantic key - null account produces different key from non-null`() = runTest {
        // When account is null, it uses "-" as placeholder, which is different from a real account
        val first = DedupKeyBuilder.semanticKey(
            kind = "credit",
            amount = 500.0,
            currency = "INR",
            merchant = "zomato@ybl",
            account = "XX1234",
            receivedAt = baseTime
        )
        val second = DedupKeyBuilder.semanticKey(
            kind = "credit",
            amount = 500.0,
            currency = "INR",
            merchant = "zomato@ybl",
            account = null,
            receivedAt = baseTime
        )

        // null account uses "-" which differs from actual account
        assertNotEquals(first, second)
    }

    @Test
    fun `semantic key - both accounts non-null and differ no collapse`() = runTest {
        val first = DedupKeyBuilder.semanticKey(
            kind = "credit",
            amount = 500.0,
            currency = "INR",
            merchant = "zomato@ybl",
            account = "XX1234",
            receivedAt = baseTime
        )
        val second = DedupKeyBuilder.semanticKey(
            kind = "credit",
            amount = 500.0,
            currency = "INR",
            merchant = "zomato@ybl",
            account = "XX4321",
            receivedAt = baseTime
        )

        assertNotEquals(first, second)
    }

    @Test
    fun `merchant normalization strips VPA suffix`() = runTest {
        val normalized = DedupKeyBuilder.normalizeMerchant("Zomato@ybl")
        assertEquals("zomato", normalized)
    }

    @Test
    fun `merchant normalization removes stop words`() = runTest {
        val normalized1 = DedupKeyBuilder.normalizeMerchant("ZOMATO PAID")
        val normalized2 = DedupKeyBuilder.normalizeMerchant("zomato received")
        val normalized3 = DedupKeyBuilder.normalizeMerchant("UPI ZOMATO SENT RS 500 INR")

        assertEquals("zomato", normalized1)
        assertEquals("zomato", normalized2)
        assertEquals("zomato 500", normalized3) // 500 is not a stop word
    }

    @Test
    fun `merchant normalization collapses whitespace`() = runTest {
        val normalized = DedupKeyBuilder.normalizeMerchant("  zomato   @ybl  ")
        assertEquals("zomato", normalized)
    }

    @Test
    fun `merchant normalization empty string stays empty`() = runTest {
        val normalized = DedupKeyBuilder.normalizeMerchant("")
        assertEquals("", normalized)
    }

    @Test
    fun `merchant normalization null stays empty`() = runTest {
        val normalized = DedupKeyBuilder.normalizeMerchant(null)
        assertEquals("", normalized)
    }

    @Test
    fun `semantic key amount rounded with HALF_UP`() = runTest {
        val first = DedupKeyBuilder.semanticKey(
            kind = "credit",
            amount = 500.005, // rounds to 500.01
            currency = "INR",
            merchant = "zomato@ybl",
            account = null,
            receivedAt = baseTime
        )
        val second = DedupKeyBuilder.semanticKey(
            kind = "credit",
            amount = 500.004, // rounds to 500.00
            currency = "INR",
            merchant = "zomato@ybl",
            account = null,
            receivedAt = baseTime
        )

        assertNotEquals(first, second)
        assertTrue(first.contains("500.01"))
        assertTrue(second.contains("500.00"))
    }

    @Test
    fun `semantic key - three different packages produce same key`() = runTest {
        // Three different UPI apps, three timestamps within 500ms, three different raw texts
        // Same ₹500 credit to zomato@ybl, INR, null account
        val key1 = DedupKeyBuilder.semanticKey(
            kind = "credit",
            amount = 500.0,
            currency = "INR",
            merchant = "zomato@ybl",
            account = null,
            receivedAt = baseTime
        )
        val key2 = DedupKeyBuilder.semanticKey(
            kind = "credit",
            amount = 500.0,
            currency = "INR",
            merchant = "Zomato@ybl",
            account = null,
            receivedAt = baseTime + 200
        )
        val key3 = DedupKeyBuilder.semanticKey(
            kind = "credit",
            amount = 500.0,
            currency = "INR",
            merchant = "ZOMATO PAID",
            account = null,
            receivedAt = baseTime + 450
        )

        assertEquals(key1, key2)
        assertEquals(key1, key3)
    }

    @Test
    fun `amount formatting various representations produce same rounded amount`() = runTest {
        // ₹500, Rs.500, Rs 500.00, INR 500 all produce 500.00 after rounding
        val key1 = DedupKeyBuilder.semanticKey("credit", 500.0, "INR", "merchant", null, baseTime)
        val key2 = DedupKeyBuilder.semanticKey("credit", 500.00, "INR", "merchant", null, baseTime)
        val key3 = DedupKeyBuilder.semanticKey("credit", 500.004, "INR", "merchant", null, baseTime)

        assertEquals(key1, key2)
        assertEquals(key1, key3)
        assertTrue(key1.contains("500.00"))
    }
}