package com.vivekray898.payalert.ui.transactions

import com.vivekray898.payalert.data.PaymentEvent
import com.vivekray898.payalert.relay.RelayDeliveryStatus
import com.vivekray898.payalert.relay.ReceivedAnnouncement
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceivedAnnouncementHistoryTest {

    @Test
    fun `visible announcements exclude expired and explicitly expired rows`() {
        val rows = listOf(
            announcement("old", receivedAt = 10, expiresAt = 99),
            announcement("boundary", receivedAt = 20, expiresAt = 100),
            announcement("middle", receivedAt = 25, expiresAt = 300),
            announcement("new", receivedAt = 30, expiresAt = 101),
            announcement("status-expired", receivedAt = 40, expiresAt = 200, status = RelayDeliveryStatus.EXPIRED)
        )

        assertEquals(
            listOf("new", "middle"),
            rows.visibleReceivedAnnouncements(now = 100).map { it.messageId }
        )
    }

    @Test
    fun `payment totals only include payment events`() {
        val totals = paymentEventTotals(
            listOf(
                event("credit", 500.0),
                event("debit", 125.0)
            )
        )

        assertEquals(500.0, totals.first, 0.001)
        assertEquals(125.0, totals.second, 0.001)
    }

    private fun announcement(
        messageId: String,
        receivedAt: Long,
        expiresAt: Long,
        status: String = RelayDeliveryStatus.PROCESSED
    ) = ReceivedAnnouncement(
        messageId = messageId,
        ownerId = "owner-1",
        employeeId = "employee-1",
        envelope = "opaque-envelope-$messageId",
        receivedAt = receivedAt,
        expiresAt = expiresAt,
        status = status
    )

    private fun event(kind: String, amount: Double) = PaymentEvent(
        kind = kind,
        amount = amount,
        source = "sms",
        dedupKey = "key-$kind-$amount",
        receivedAt = 100
    )
}
