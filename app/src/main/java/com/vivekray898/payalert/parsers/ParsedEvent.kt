package com.vivekray898.payalert.parsers

/**
 * Result of parsing one SMS body or notification text.
 *
 * Deliberately free of any Android dependency so it can be unit-tested on the JVM.
 */
data class ParsedEvent(
    /** "credit" (money in) or "debit" (money out). */
    val kind: String,
    val amount: Double,
    val currency: String,
    /** Bank or payment-app identifier, e.g. "HDFC Bank" or "PhonePe". */
    val bankOrUpi: String?,
    val merchant: String?,
    /** UPI / NEFT / IMPS reference when present. */
    val utr: String?,
    /** Masked account or card, e.g. "XX4321". */
    val account: String?,
    /** Available balance quoted by the alert, when present. */
    val balance: Double?,
    val category: String?,
    /** The normalized text this was parsed from. */
    val rawText: String,
    /** Identifier of the rule set that matched, surfaced in diagnostics. */
    val parserName: String
)
