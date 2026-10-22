package com.vivekray898.payalert.relay

import com.vivekray898.payalert.data.PaymentEvent
import java.nio.charset.StandardCharsets.UTF_8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayPayloadTest {

    @Test
    fun `only credit events become relay payloads`() {
        assertNotNull(RelayPaymentPayload.from(event(kind = "credit", amount = 500.0)))
        assertNull(RelayPaymentPayload.from(event(kind = "debit", amount = 500.0)))
    }

    @Test
    fun `payloads reject unusable amounts and drop the raw message`() {
        assertNull(RelayPaymentPayload.from(event(kind = "credit", amount = 0.0)))
        assertNull(RelayPaymentPayload.from(event(kind = "credit", amount = -5.0)))
        assertNull(RelayPaymentPayload.from(event(kind = "credit", amount = Double.NaN)))

        val payload = RelayPaymentPayload.from(
            event(kind = "credit", amount = 1250.5, rawText = "A/c XX4321 debited by 1250.50")
        )
        assertNotNull(payload)
        assertFalse(payload.toString().contains("XX4321"))
    }

    @Test
    fun `codec round trips every forwarded field`() {
        val payload = RelayPaymentPayload(
            kind = RelayPaymentPayload.CREDIT,
            amount = 1234.5,
            currency = "INR",
            merchant = "Sample Merchant",
            bankOrUpi = "HDFC",
            utr = "UTR123",
            eventAt = 1_700_000_000_000L
        )

        val decoded = RelayPayloadCodec.decode(RelayPayloadCodec.encode(payload))

        assertEquals(payload, decoded)
    }

    @Test
    fun `codec refuses to encode a debit and refuses to decode anything unexpected`() {
        rejects {
            RelayPayloadCodec.encode(
                RelayPaymentPayload(
                    kind = "debit",
                    amount = 10.0,
                    currency = "INR",
                    merchant = null,
                    bankOrUpi = null,
                    utr = null,
                    eventAt = 1L
                )
            )
        }
        // A debit smuggled in as JSON must not be announced on the employee device.
        rejects { RelayPayloadCodec.decode("{\"kind\":\"debit\",\"amount\":10,\"eventAt\":1}".toByteArray(UTF_8)) }
        rejects { RelayPayloadCodec.decode("{\"amount\":10,\"eventAt\":1}".toByteArray(UTF_8)) }
        rejects { RelayPayloadCodec.decode("{\"kind\":\"credit\",\"amount\":0,\"eventAt\":1}".toByteArray(UTF_8)) }
        rejects { RelayPayloadCodec.decode("not json".toByteArray(UTF_8)) }
        rejects { RelayPayloadCodec.decode(ByteArray(RelayPayloadCodec.MAX_PAYLOAD_BYTES + 1)) }
    }

    @Test
    fun `control characters are stripped from relayed text`() {
        val payload = RelayPaymentPayload.from(
            event(kind = "credit", amount = 10.0, merchant = "Shop\u0000\u0007keeper\u0001")
        )

        assertEquals("Shopkeeper", payload?.merchant)
    }

    @Test
    fun `message ids are stable per route and distinct across routes and events`() {
        val first = RelayMessageId.forEvent("owner-1", "employee-1", "dedup-a")
        val repeat = RelayMessageId.forEvent("owner-1", "employee-1", "dedup-a")
        val otherEvent = RelayMessageId.forEvent("owner-1", "employee-1", "dedup-b")
        val otherEmployee = RelayMessageId.forEvent("owner-1", "employee-2", "dedup-a")

        assertEquals(first, repeat)
        assertTrue(first != otherEvent && first != otherEmployee)
        assertEquals(first, java.util.UUID.fromString(first).toString())
    }

    @Test
    fun `announcement keeps the user template and speaks no source label`() {
        val payload = RelayPaymentPayload(
            kind = RelayPaymentPayload.CREDIT,
            amount = 500.0,
            currency = "INR",
            merchant = "Ramesh",
            bankOrUpi = null,
            utr = null,
            eventAt = 1L
        )

        val text = RelayAnnouncement.format(
            template = "Money received {amount} from {merchant}.",
            payload = payload
        )

        assertEquals("Money received 500 rupees from Ramesh.", text)
        // The employee hears the same sentence an owner device would say: nothing is announced
        // before the payment, so no device name is read out.
        assertEquals("Payment received 10 rupees in your bank account.",
            RelayAnnouncement.format("", payload.copy(amount = 10.0, merchant = null)))
    }

    private fun event(
        kind: String,
        amount: Double,
        merchant: String? = "Sample Merchant",
        rawText: String? = "payment alert"
    ) = PaymentEvent(
        kind = kind,
        amount = amount,
        merchant = merchant,
        bankOrUpi = "HDFC",
        utr = "UTR123",
        source = "notification",
        sourceDetail = "com.phonepe.app",
        rawText = rawText,
        dedupKey = "dedup-$kind-$amount",
        receivedAt = 1_700_000_000_000L
    )

    private fun rejects(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            return
        }
        throw AssertionError("Expected the payload codec to reject the input")
    }
}
