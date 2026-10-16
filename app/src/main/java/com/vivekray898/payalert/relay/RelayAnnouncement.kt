package com.vivekray898.payalert.relay

import com.vivekray898.payalert.settings.AnnouncementFormatter

/**
 * Wording for a payment announced on an employee device.
 *
 * The sentence is exactly what the owner's own device says for the same payment: the user's
 * template and settings apply unchanged and nothing is announced before it. No source label is
 * spoken, because a device name is read out as an unhelpful string of letters and digits; which
 * device a payment came from is shown on the team and history screens instead, where a long name is
 * readable rather than read aloud.
 */
object RelayAnnouncement {

    fun format(template: String, payload: RelayPaymentPayload): String =
        AnnouncementFormatter.format(
            template = template,
            amount = payload.amount,
            merchant = payload.merchant,
            bankOrUpi = payload.bankOrUpi,
            kind = payload.kind,
            currency = payload.currency
        )
}
