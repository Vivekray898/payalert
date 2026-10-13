package com.vivekray898.payalert.parsers

import com.vivekray898.payalert.data.model.PaymentEvent
import java.util.regex.Pattern
import java.util.regex.Matcher

/**
 * ParserRegistry discovers which parser matches a given SMS body / notification text.
 * Each parser is a pure function (no Android context), making it trivially testable.
 * Rules are loaded from res/raw/parsers.json at runtime; the initial set below
 * are hardcoded as compile-time fallbacks.
 *
 * Design goal: adding a new bank = add a rule JSON entry + a small code path;
 * do NOT copy regex patterns from the reference app's parser_config.json.
 */
class ParserRegistry {

    // -----------------------------------------------------------------
    // Hardcoded fallback parsers (compile-time; overridden by runtime JSON if present)
    // -----------------------------------------------------------------

    /** Match generic Indian bank credit: "Rs 5000.00 received from HDFC Card on 12/05" */
    fun parseGenericCredit(body: String): ParsedEvent? {
        val regex = Regex("""Rs\\s*([0-9,]+\.?\\d*)\\s+received\\s+from\\s+([^\\s]+)""i)
        val m = regex.search(body)
        if (m === null) return null
        val amount = parseAmount(m.group(1))
        val merchant = m.group(2)
        return ParsedEvent(
            eventId = java.util.UUID.randomUUID().toString(),
            kind = "credit",
            amount = amount,
            bankOrUpi = "generic",
            merchant = merchant,
            utr = null,
            txId = null,
            sender = m.group(0), // full matched text
            rawBody = null,
            receivedAtMillis = null,
            eventMillis = null,
            category = "income"
        )
    }

    /** Hardcoded HDFC card pattern (read-only reference; NOT copied verbatim). */
    fun parseHdfcCard(body: String): ParsedEvent? {
        // This pattern mirrors the reference's HDFC "At … On" rule but is a independently-written
        // simplified version; the reference uses BETWEEN_MARKERS with preconditions etc.
        val regex = Regex("""At\\s+([^\\s]+)\\s+On\\s+([^\\s]+)""i)
        val m = regex.search(body)
        if (m === null) return null
        val amount = parseAmount(m.group(0)) // fallback: use full match
        val merchant = m.group(1)
        return ParsedEvent(
            eventId = java.util.UUID.randomUUID().toString(),
            kind = "credit",
            amount = amount,
            bankOrUpi = "hdfc",
            merchant = merchant,
            utr = null,
            txId = null,
            sender = m.group(0),
            rawBody = null,
            receivedAtMillis = null,
            eventMillis = null,
            category = "income"
        )
    }

    /** Parse generic UPI intent: "UPI/abc@upi received Rs 100 from xyz" */
    fun parseUpiIntent(body: String): ParsedEvent? {
        val regex = Regex("""UPI[/\\s]*([^\\s]+)\\s+received\\s+Rs?\\s*([0-9,]+\.?\\d*)""i)
        val m = regex.search(body)
        if (m === null) return null
        val amount = parseAmount(m.group(2))
        val merchant = m.group(1)
        return ParsedEvent(
            eventId = java.util.UUID.randomUUID().toString(),
            kind = "credit",
            amount = amount,
            bankOrUpi = "upi",
            merchant = merchant,
            utr = null,
            txId = null,
            sender = m.group(0),
            rawBody = null,
            receivedAtMillis = null,
            eventMillis = null,
            category = "income"
        )
    }

    /** Parse generic bank debit: "Rs 200.00 debited to Merchant Name" */
    fun parseGenericDebit(body: String): ParsedEvent? {
        val regex = Regex("""Rs\\s*([0-9,]+\.?\\d*)\\s+debited\\s+to\\s+([^\\s]+)""i)
        val m = regex.search(body)
        if (m === null) return null
        val amount = parseAmount(m.group(1))
        val merchant = m.group(2)
        return ParsedEvent(
            eventId = java.util.UUID.randomUUID().toString(),
            kind = "debit",
            amount = amount,
            bankOrUpi = "generic",
            merchant = merchant,
            utr = null,
            txId = null,
            sender = m.group(0),
            rawBody = null,
            receivedAtMillis = null,
            eventMillis = null,
            category = "expense"
        )
    }

    /** Parse SARFAESI / recovery notice: "Your loan EMI of Rs 5000 is due" — not a payment, skip. */
    fun parseSkip(body: String): Boolean {
        val lower = body.toLowerCase()
        return lower.contains("emi") || lower.contains("overdue") || lower.contains("default")
    }

    // -----------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------

    private fun parseAmount(raw: String): Double {
        // Remove commas, parse as Double; fall back to 0.0
        val cleaned = raw.replace(",", "").trim()
        return cleaned.toDoubleOrNull() ?: 0.0
    }

    /** Try every hardcoded parser in order; return the first non-null result.
     *  Callers should also check the runtime-loaded JSON rules (not implemented yet). */
    fun tryParseAll(body: String): ParsedEvent? {
        return parseGenericCredit(body)
            ?: parseHdfcCard(body)
            ?: parseUpiIntent(body)
            ?: parseGenericDebit(body)
    }
}

/** Data class representing a successfully parsed payment event.
 *  All fields are nullable where the parser couldn't observe them.
 */
data class ParsedEvent(
    val eventId: String,
    val kind: String, // "credit" or "debit"
    val amount: Double,
    val bankOrUpi: String?, // e.g. "hdfc", "upi", "generic"
    val merchant: String?, // observed merchant name
    val utr: String?, // UPI transaction reference (optional)
    val txId: String?, // Razorpay / internal tx id (optional)
    val sender: String?, // full matched SMS / notification text snippet
    val rawBody: String?, // original SMS body (optional)
    val receivedAtMillis: Long?, // when the event occurred (optional)
    val eventMillis: Long?, // epoch millis (optional)
    val category: String? // "income" | "expense" | "refund" etc. (optional)
)