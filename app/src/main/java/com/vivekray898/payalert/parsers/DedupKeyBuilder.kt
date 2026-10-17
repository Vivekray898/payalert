package com.vivekray898.payalert.parsers

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
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
    const val SEMANTIC_WINDOW_MINUTES = 5
    const val SEMANTIC_LOOKBACK_MINUTES = 10
    const val SEMANTIC_LOOKBACK_MILLIS = SEMANTIC_LOOKBACK_MINUTES * 60_000L

    /** Tier-1 exact-source key (notification package + timestamp + raw-text hash). */
    fun exactKey(
        kind: String,
        amount: Double,
        utr: String?,
        merchant: String?,
        receivedAt: Long,
        windowMinutes: Int = DEFAULT_WINDOW_MINUTES,
        eventId: String? = null
    ): String = build(kind, amount, utr, merchant, receivedAt, windowMinutes, eventId)

    /**
     * Tier-2 semantic key built from the *parsed transaction* so that the same
     * underlying payment arriving from different UPI apps collapses to one key.
     *
     * Format: txn:<direction>:<amount>:<currency>:<account-or-"-">:<merchant-normalized>:<time-bucket>
     */
    fun semanticKey(
        kind: String,
        amount: Double,
        currency: String,
        merchant: String?,
        account: String?,
        receivedAt: Long
    ): String {
        return semanticKeyPrefix(kind, amount, currency, merchant, account) +
            (receivedAt / 300_000L)
    }

    /** Stable part used by the rolling semantic lookup; the final segment is an old bucket key. */
    fun semanticKeyPrefix(
        kind: String,
        amount: Double,
        currency: String,
        merchant: String?,
        account: String?
    ): String {
        val amountText = BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).toPlainString()
        val normalizedMerchant = normalizeMerchant(merchant)
        val accountPart = account?.takeIf { it.isNotBlank() } ?: "-"
        return "txn:$kind:$amountText:$currency:$accountPart:$normalizedMerchant:"
    }

    /**
     * Cross-detector bridge for notifications that disagree on the account or truncate a name.
     * It is deliberately less authoritative than [semanticKey] and is only used inside the
     * short semantic window by [PaymentPipeline].
     */
    fun merchantBridgeKey(
        kind: String,
        amount: Double,
        currency: String,
        merchant: String?,
        receivedAt: Long
    ): String {
        return merchantBridgeKeyPrefix(kind, amount, currency, merchant) +
            (receivedAt / 300_000L)
    }

    fun merchantBridgeKeyPrefix(
        kind: String,
        amount: Double,
        currency: String,
        merchant: String?
    ): String {
        val amountText = BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).toPlainString()
        val merchantPrefix = normalizeMerchant(merchant).take(MERCHANT_BRIDGE_LENGTH)
        return "txn-bridge:$kind:$amountText:$currency:$merchantPrefix:"
    }

    /** Normalize merchant for semantic key. */
    fun normalizeMerchant(merchant: String?): String =
        merchant
            ?.lowercase(Locale.US)
            ?.replace(Regex("@[^\\s]+"), "") // strip @... VPA suffix
            ?.replace(Regex("\\b(upi|paid|received|sent|rs|inr)\\b"), "") // remove stop-words
            ?.replace(Regex("[^a-z0-9]+"), " ") // non-alnum to space
            ?.trim()
            ?.replace(Regex("\\s+"), " ") // collapse whitespace
            ?.takeIf { it.isNotBlank() }
            .orEmpty()

    /** Legacy exact-source key builder (kept for backwards compatibility). */
    private fun build(
        kind: String,
        amount: Double,
        utr: String?,
        merchant: String?,
        receivedAt: Long,
        windowMinutes: Int,
        eventId: String?
    ): String {
        val amountText = String.format(Locale.US, "%.2f", amount)
        val reference = utr?.trim()?.uppercase(Locale.US)
        if (!reference.isNullOrEmpty()) {
            return "utr:$reference|$kind|$amountText"
        }
        if (!eventId.isNullOrBlank()) {
            return "event:${sha256(eventId)}"
        }
        val windowMillis = windowMinutes.coerceAtLeast(1) * 60_000L
        val bucket = receivedAt / windowMillis
        return "amt:$amountText|$kind|${normalizeLegacy(merchant)}|$bucket"
    }

    private fun normalizeLegacy(value: String?): String =
        value
            ?.lowercase(Locale.US)
            ?.replace(Regex("[^a-z0-9]"), "")
            ?.take(24)
            .orEmpty()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.US, byte) }

    private const val MERCHANT_BRIDGE_LENGTH = 20
}
