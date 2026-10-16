package com.vivekray898.payalert.ui.transactions

import com.vivekray898.payalert.relay.RelayDeliveryStatus
import com.vivekray898.payalert.relay.ReceivedAnnouncement

/**
 * Rows that are safe to show in History right now.
 *
 * Expiry is evaluated at read time instead of relying on the background cleanup worker, so an
 * expired row is gone from the UI even when cleanup has not run yet. Rows whose envelope could not
 * be authenticated are excluded as well: they carry no payment detail, and the re-pair banner is
 * the right place to surface them rather than an empty History entry.
 */
internal fun List<ReceivedAnnouncement>.visibleReceivedAnnouncements(now: Long): List<ReceivedAnnouncement> =
    asSequence()
        .filter { it.expiresAt > now }
        .filterNot { it.decryptFailed }
        .filterNot { it.status == RelayDeliveryStatus.EXPIRED }
        .sortedWith(compareByDescending<ReceivedAnnouncement> { it.receivedAt }.thenBy { it.messageId })
        .toList()

/** Totals for History are deliberately derived only from local payment events. */
internal fun paymentEventTotals(events: List<com.vivekray898.payalert.data.PaymentEvent>): Pair<Double, Double> =
    events.filter { it.kind == "credit" }.sumOf { it.amount } to
        events.filter { it.kind == "debit" }.sumOf { it.amount }

/**
 * The description line for a received announcement.
 *
 * Only fields that were actually decrypted are used, so an incomplete row degrades to a generic
 * label instead of inventing an amount.
 */
internal fun receivedAnnouncementDescription(announcement: ReceivedAnnouncement): String {
    val merchant = announcement.merchant?.takeIf { it.isNotBlank() }
    val bank = announcement.bankOrUpi?.takeIf { it.isNotBlank() }
    return merchant ?: bank ?: "Received payment"
}

/** True when the row has a decrypted amount to show. */
internal fun ReceivedAnnouncement.hasAmount(): Boolean = amount != null && amount > 0.0
