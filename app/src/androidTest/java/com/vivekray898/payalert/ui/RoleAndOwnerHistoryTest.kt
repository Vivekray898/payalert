package com.vivekray898.payalert.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.vivekray898.payalert.relay.ReceivedAnnouncement
import com.vivekray898.payalert.relay.RelayDeliveryStatus
import com.vivekray898.payalert.ui.components.RoleIndicator
import com.vivekray898.payalert.ui.theme.PayAlertTheme
import com.vivekray898.payalert.ui.transactions.ReceivedAnnouncementRow
import com.vivekray898.payalert.ui.transactions.visibleReceivedAnnouncements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RoleAndOwnerHistoryTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun roleIndicatorRendersOwnerAndEmployeeLabels() {
        compose.setContent {
            PayAlertTheme {
                Row {
                    RoleIndicator("owner")
                    RoleIndicator("employee")
                }
            }
        }

        compose.onNodeWithText("Owner").assertExists()
        compose.onNodeWithText("Employee").assertExists()
    }

    @Test
    fun receivedHistoryRowShowsDecryptedPaymentWithoutOpaqueEnvelope() {
        compose.setContent {
            PayAlertTheme {
                ReceivedAnnouncementRow(
                    ReceivedAnnouncement(
                        messageId = "message-1",
                        ownerId = "owner-1",
                        employeeId = "employee-1",
                        envelope = "ciphertext-that-must-not-render",
                        receivedAt = 1_000,
                        expiresAt = 2_000,
                        status = RelayDeliveryStatus.PROCESSED,
                        amount = 500.0,
                        merchant = "Ramesh",
                        ownerLabel = "Counter phone"
                    )
                )
            }
        }

        compose.onNodeWithText("From owner").assertExists()
        compose.onNodeWithText("Ramesh").assertExists()
        compose.onNodeWithText("+500.00").assertExists()
        compose.onNodeWithText("From Counter phone").assertExists()
        compose.onNodeWithText("ciphertext-that-must-not-render").assertDoesNotExist()
    }

    /** Only rows that can still be read belong in History; expired ones disappear. */
    @Test
    fun expiredAndUnreadableAnnouncementsStayOutOfHistory() {
        val now = 1_000L
        val rows = listOf(
            announcement("live", receivedAt = 900, expiresAt = 2_000, amount = 10.0),
            announcement("expired", receivedAt = 500, expiresAt = 900, amount = 20.0),
            announcement("unreadable", receivedAt = 950, expiresAt = 2_000, decryptFailed = true)
        )

        val visible = rows.visibleReceivedAnnouncements(now)

        assertEquals(listOf("live"), visible.map { it.messageId })
        assertTrue(rows.visibleReceivedAnnouncements(3_000).isEmpty())
    }

    private fun announcement(
        messageId: String,
        receivedAt: Long,
        expiresAt: Long,
        amount: Double = 0.0,
        decryptFailed: Boolean = false
    ) = ReceivedAnnouncement(
        messageId = messageId,
        ownerId = "owner-1",
        employeeId = "employee-1",
        envelope = "",
        receivedAt = receivedAt,
        expiresAt = expiresAt,
        status = RelayDeliveryStatus.PROCESSED,
        amount = amount,
        decryptFailed = decryptFailed
    )
}
