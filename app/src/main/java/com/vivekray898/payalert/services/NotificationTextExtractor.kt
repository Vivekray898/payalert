package com.vivekray898.payalert.services

/**
 * Selects the transaction content from an Android notification.
 *
 * Notification titles often contain app-owned labels such as "PhonePe Payment from PhonePe".
 * They are useful for display, but they are not reliable payment-party data. Prefer the
 * text-bearing content fields, which usually contain the actual payer/payee. Some payment apps put
 * the transaction in the title and leave a generic action such as "Tap to view" in the body, so
 * callers may provide a parser-backed candidate predicate before the first body field is selected.
 */
object NotificationTextExtractor {

    fun choose(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        summaryText: String?,
        isPaymentCandidate: (String) -> Boolean = { false }
    ): String? {
        val candidates = listOf(bigText, text, subText, summaryText, title)
            .mapNotNull(::clean)
            .distinct()

        // Keep body fields ahead of the title when both contain payment text: app-owned titles
        // often include misleading words such as "from PhonePe". If the body only has a generic
        // action label, the parser-backed predicate lets a transaction-bearing title win instead.
        return candidates.firstOrNull(isPaymentCandidate) ?: candidates.firstOrNull()
    }

    private fun clean(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() }
}
