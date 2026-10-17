package com.vivekray898.payalert.services

/**
 * Selects the transaction content from an Android notification.
 *
 * Notification titles often contain app-owned labels such as "PhonePe Payment from PhonePe".
 * They are useful for display, but they are not reliable payment-party data. Prefer the
 * text-bearing content fields, which contain the actual payer/payee, and use the title only when
 * the notification has no content body at all.
 */
object NotificationTextExtractor {

    fun choose(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        summaryText: String?
    ): String? {
        val content = listOf(bigText, text, subText, summaryText)
            .mapNotNull(::clean)
            .distinct()
            .firstOrNull()

        return content ?: clean(title)
    }

    private fun clean(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() }
}
