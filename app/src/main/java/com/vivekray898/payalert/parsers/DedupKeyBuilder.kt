package com.vivekray898.payalert.parsers

import java.util.Locale

/**
 * Builds a stable, content-based key for a parsed payment.
 *
 * The same real-world payment often arrives twice — once as a bank SMS and once as
 * a UPI notification — with different wording. Keying on the reference number when
 * one exists, and otherwise on (amount, direction, merchant, time window), collapses
 * those copies without depending on which detector saw it first.
 */
object DedupKeyBuilder {

    const val DEFAULT_WINDOW_MINUTES = 5

    fun build(
        kind: String,
        amount: Double,
        utr: String?,
        merchant: String?,
        receivedAt: Long,
        windowMinutes: Int = DEFAULT_WINDOW_MINUTES
    ): String {
        val amountText = String.format(Locale.US, "%.2f", amount)
        val reference = utr?.trim()?.uppercase(Locale.US)
        if (!reference.isNullOrEmpty()) {
            return "utr:$reference|$kind|$amountText"
        }
        val windowMillis = windowMinutes.coerceAtLeast(1) * 60_000L
        val bucket = receivedAt / windowMillis
        return "amt:$amountText|$kind|${normalize(merchant)}|$bucket"
    }

    private fun normalize(value: String?): String =
        value
            ?.lowercase(Locale.US)
            ?.replace(Regex("[^a-z0-9]"), "")
            ?.take(24)
            .orEmpty()
}
