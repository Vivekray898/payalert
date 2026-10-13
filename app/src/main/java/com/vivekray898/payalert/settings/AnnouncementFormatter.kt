package com.vivekray898.payalert.settings

import java.util.Locale

/**
 * Turns a parsed payment into the sentence that is spoken aloud.
 *
 * Templates support the placeholders `{amount}`, `{merchant}`, `{bank}` and `{kind}`.
 * Pure and dependency-free so the wording is unit-tested.
 */
object AnnouncementFormatter {

    const val DEFAULT_CREDIT_TEMPLATE = "Payment received. {amount} from {merchant}."
    const val DEFAULT_DEBIT_TEMPLATE = "Payment sent. {amount} to {merchant}."

    fun defaultTemplate(kind: String): String =
        if (kind == "credit") DEFAULT_CREDIT_TEMPLATE else DEFAULT_DEBIT_TEMPLATE

    fun format(
        template: String,
        amount: Double,
        merchant: String?,
        bankOrUpi: String?,
        kind: String
    ): String {
        val effective = template.ifBlank { defaultTemplate(kind) }
        val merchantText = merchant?.takeIf { it.isNotBlank() }
            ?: if (kind == "credit") "an unknown sender" else "an unknown payee"
        val bankText = bankOrUpi?.takeIf { it.isNotBlank() } ?: "your bank"
        val kindText = if (kind == "credit") "credit" else "debit"

        return effective
            .replace("{amount}", amountText(amount))
            .replace("{merchant}", merchantText)
            .replace("{bank}", bankText)
            .replace("{kind}", kindText)
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** Grouped, always-two-decimals number — safe to hand to a speech engine. */
    fun amountText(amount: Double): String = String.format(Locale.US, "%,.2f", amount)
}
