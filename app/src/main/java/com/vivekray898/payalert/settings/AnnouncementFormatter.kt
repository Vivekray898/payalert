package com.vivekray898.payalert.settings

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * Turns a parsed payment into the sentence that is spoken aloud.
 *
 * Templates support the placeholders `{amount}`, `{merchant}`, `{bank}` and `{kind}`.
 * Pure and dependency-free so the wording is unit-tested.
 */
object AnnouncementFormatter {

    const val DEFAULT_CREDIT_TEMPLATE = "Payment received {amount} in your bank account."
    const val DEFAULT_DEBIT_TEMPLATE = "Payment sent. {amount} to {merchant}."

    /** Assumed when an event carries no currency of its own. */
    const val DEFAULT_CURRENCY = "INR"

    private const val RUPEE = "rupees"
    private const val PAISE = "paise"

    /** Spoken in place of an amount that could not be represented as a number. */
    const val UNSPECIFIED_AMOUNT = "an unspecified amount"

    fun defaultTemplate(kind: String): String =
        if (kind == "credit") DEFAULT_CREDIT_TEMPLATE else DEFAULT_DEBIT_TEMPLATE

    fun format(
        template: String,
        amount: Double,
        merchant: String?,
        bankOrUpi: String?,
        kind: String,
        currency: String = DEFAULT_CURRENCY
    ): String {
        val effective = template.ifBlank { defaultTemplate(kind) }
        val merchantText = merchant?.takeIf { it.isNotBlank() }
            ?: if (kind == "credit") "an unknown sender" else "an unknown payee"
        val bankText = bankOrUpi?.takeIf { it.isNotBlank() } ?: "your bank"
        val kindText = if (kind == "credit") "credit" else "debit"

        return effective
            .replace("{amount}", amountText(amount, currency))
            .replace("{merchant}", merchantText)
            .replace("{bank}", bankText)
            .replace("{kind}", kindText)
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * The amount written the way it is said aloud.
     *
     * A decimal such as `777.00` is read out as digits — "seven seven seven dot zero zero" — so no
     * decimal separator is ever produced here. A whole amount becomes "777 rupees", and any
     * fraction is spoken as its own minor unit ("777 rupees 50 paise"). Digits stay grouped so the
     * engine reads one number instead of spelling it.
     */
    fun amountText(amount: Double, currency: String = DEFAULT_CURRENCY): String {
        // A non-finite amount cannot be spoken as a number, and BigDecimal.valueOf rejects NaN and
        // the infinities outright. A parser that overflows on an absurdly long digit string must not
        // be able to turn an announcement into a thrown exception, so this returns wording instead.
        if (!amount.isFinite()) return UNSPECIFIED_AMOUNT
        val rounded = BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP)
        val whole = String.format(Locale.US, "%,d", rounded.toBigInteger())
        val fraction = rounded.remainder(BigDecimal.ONE).movePointRight(2).abs().toInt()
        val minorUnit = if (isInr(currency)) PAISE else null
        return when {
            fraction == 0 -> "$whole ${majorUnit(currency)}"
            minorUnit != null -> "$whole ${majorUnit(currency)} $fraction $minorUnit"
            // No minor-unit name is known for this currency, so the fraction is joined with a word
            // rather than a decimal separator.
            else -> "$whole ${majorUnit(currency)} and $fraction"
        }
    }

    /** Spoken name of the currency's major unit; an unrecognised code is spoken as itself. */
    private fun majorUnit(currency: String): String =
        if (isInr(currency)) RUPEE else currency.trim().uppercase(Locale.ROOT)

    private fun isInr(currency: String): Boolean =
        currency.trim().uppercase(Locale.ROOT).let { it.isEmpty() || it == "INR" || it == "\u20B9" }
}
