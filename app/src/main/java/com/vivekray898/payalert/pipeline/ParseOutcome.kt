package com.vivekray898.payalert.pipeline

import com.vivekray898.payalert.data.PaymentEvent

/**
 * What the pipeline did with one piece of text. Surfaced in the diagnostics screen
 * so the user can see exactly why a message was (or was not) announced.
 */
data class ParseOutcome(
    /** Reason code: accepted, not_monitored, no_match, below_minimum, kind_disabled, duplicate. */
    val reason: String,
    val accepted: Boolean,
    val at: Long,
    val source: String,
    val sourceDetail: String?,
    val amount: Double? = null,
    val kind: String? = null,
    val merchant: String? = null,
    val dedupKey: String? = null,
    val parserName: String? = null,
    val announcement: String? = null,
    val event: PaymentEvent? = null
) {
    companion object {
        const val ACCEPTED = "accepted"
        const val NOT_MONITORED = "not_monitored"
        const val NO_MATCH = "no_match"
        const val BELOW_MINIMUM = "below_minimum"
        const val KIND_DISABLED = "kind_disabled"
        const val SOURCE_DISABLED = "source_disabled"
        const val DUPLICATE = "duplicate"
    }
}
