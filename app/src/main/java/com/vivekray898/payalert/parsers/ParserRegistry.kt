package com.vivekray898.payalert.parsers

import java.util.Locale

/**
 * Config-driven parser for Indian bank SMS alerts and UPI/bank notification text.
 *
 * Every rule comes from [ParserConfig], which is loaded from `assets/parser_config.json`.
 * This class contains no bank-specific literals, so adding a bank is a data change.
 *
 * All methods are pure (no Android types), so the whole rule set is unit-testable.
 */
class ParserRegistry(private val config: ParserConfig) {

    private val amountRegexes = config.patterns.amount.map(::Regex) + foreignAmountRegexes()
    private val referenceRegexes = config.patterns.reference.map(::Regex)
    private val accountRegexes = config.patterns.account.map(::Regex)
    private val balanceRegexes = config.patterns.balance.map(::Regex)
    private val creditMerchantRegexes = config.merchant.credit.map(::Regex)
    private val debitMerchantRegexes = config.merchant.debit.map(::Regex)
    private val upiNarrationRegex = config.merchant.upiNarration.takeIf { it.isNotBlank() }?.let(::Regex)
    private val vpaRegex = config.merchant.vpa.takeIf { it.isNotBlank() }?.let(::Regex)
    private val stopWords = config.merchant.stopWords.map { it.lowercase(Locale.US) }.toSet()
    private val ignoredUpiHandles = config.merchant.upiHandlesToIgnore.map { it.uppercase(Locale.US) }.toSet()
    private val skipCategories = config.skip.categories()
    private val notificationPackages = (
        config.notificationPackages + config.wallets.flatMap { it.packages }
        ).map { it.lowercase(Locale.US) }.toSet()
    private val bankSenders = config.banks
        .flatMap { bank -> bank.senders.map { it.uppercase(Locale.US) to bank } }
        .sortedByDescending { it.first.length }

    /**
     * Amount patterns for non-default currencies, derived from the configured currency
     * table so a foreign-currency alert is still parsed without hardcoding any code.
     */
    private fun foreignAmountRegexes(): List<Regex> {
        val codes = config.currency.keys.filterNot { it.equals(config.defaultCurrency, ignoreCase = true) }
        if (codes.isEmpty()) return emptyList()
        val alternation = codes.joinToString("|") { Regex.escape(it) }
        return listOf(
            Regex("(?i)\\b(?:$alternation)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)"),
            Regex("(?i)([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*(?:$alternation)\\b")
        )
    }

    /** Parse one message. Returns null when the text is not a settled payment alert. */
    fun parse(text: String, senderOrPackage: String? = null): ParsedEvent? {
        if (text.isBlank()) return null
        val body = normalize(text)
        if (body.length < 4) return null
        if (skipCategory(body) != null) return null

        val kind = detectKind(body) ?: return null
        val amount = extractAmount(body) ?: return null
        if (amount <= 0.0) return null

        return ParsedEvent(
            kind = kind,
            amount = amount,
            currency = detectCurrency(body),
            bankOrUpi = inferSource(body, senderOrPackage),
            merchant = extractMerchant(body, kind),
            utr = extractReference(body),
            account = extractAccount(body),
            balance = extractBalance(body),
            category = if (kind == "credit") "income" else "expense",
            rawText = body,
            parserName = PARSER_NAME
        )
    }

    fun normalize(text: String): String =
        normalizeDigits(text)
            .replace(ZERO_WIDTH, "")
            .replace('\n', ' ')
            .replace(WHITESPACE, " ")
            .trim()

    /**
     * Notification admission is intentionally separate from generic SMS parsing. A bank SMS can
     * come from an unknown sender, but an arbitrary notification must not become a transaction
     * merely because it contains a number and the word "paid".
     */
    fun isLikelyNotificationSource(packageName: String?, text: String): Boolean {
        val packageId = packageName?.trim()?.lowercase(Locale.US).orEmpty()
        if (packageId.isBlank() || text.isBlank()) return false
        if (packageId in notificationPackages) return true
        return inferSource(normalize(text), null) != null
    }

    // ------------------------------------------------------------------
    // Skip rules — messages that mention money but are not a settled payment
    // ------------------------------------------------------------------

    /** Returns the skip category that matched, or null when the message is a real payment. */
    fun skipCategory(body: String): String? {
        val lower = body.lowercase(Locale.US)
        for ((category, keywords) in skipCategories) {
            if (keywords.any { lower.contains(it.lowercase(Locale.US)) }) return category
        }
        return null
    }

    // ------------------------------------------------------------------
    // Direction
    // ------------------------------------------------------------------

    fun detectKind(body: String): String? {
        val lower = body.lowercase(Locale.US)

        // Recipient-facing phrases must win over generic tokens such as "sent" and "paid".
        // Without this guard, "Rahul sent you Rs 500" is indistinguishable from an outgoing
        // payment because the word "sent" is also a debit token.
        if (CREDIT_CONTEXT.any { it.containsMatchIn(lower) }) return "credit"
        if (DEBIT_CONTEXT.any { it.containsMatchIn(lower) }) return "debit"

        val hasDebit = config.direction.debit.any { matches(lower, it) }
        val hasCredit = config.direction.credit.any { matches(lower, it) }
        return when {
            hasDebit && !hasCredit -> "debit"
            hasCredit && !hasDebit -> "credit"
            hasDebit && hasCredit ->
                if (config.direction.debitWins.any { matches(lower, it) }) "debit" else "credit"
            else -> null
        }
    }

    /** Whole words for single tokens, so "sent" cannot fire inside "consent". */
    private fun matches(lowerBody: String, keyword: String): Boolean =
        if (keyword.contains(' ')) {
            lowerBody.contains(keyword)
        } else {
            Regex("\\b" + Regex.escape(keyword) + "\\b").containsMatchIn(lowerBody)
        }

    // ------------------------------------------------------------------
    // Field extraction
    // ------------------------------------------------------------------

    fun extractAmount(body: String): Double? =
        firstGroup(amountRegexes, body)?.replace(",", "")?.toDoubleOrNull()

    fun extractReference(body: String): String? = firstGroup(referenceRegexes, body)?.trim()

    fun extractAccount(body: String): String? =
        firstGroup(accountRegexes, body)?.replace(" ", "")?.uppercase(Locale.US)

    fun extractBalance(body: String): Double? =
        firstGroup(balanceRegexes, body)?.replace(",", "")?.toDoubleOrNull()

    private fun firstGroup(regexes: List<Regex>, body: String): String? {
        for (regex in regexes) {
            val match = regex.find(body) ?: continue
            val value = match.groupValues.getOrNull(1)
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    fun extractMerchant(body: String, kind: String): String? {
        val regexes = if (kind == "credit") creditMerchantRegexes else debitMerchantRegexes
        for (regex in regexes) {
            val raw = regex.find(body)?.groupValues?.getOrNull(1) ?: continue
            cleanMerchant(raw)?.let { return it }
        }
        return extractUpiHandle(body)
    }

    /** UPI narrations look like "UPI/PAYTM/1234/ACME" or "VPA acme@ybl". */
    private fun extractUpiHandle(body: String): String? {
        val narration = upiNarrationRegex?.find(body)?.groupValues?.getOrNull(1)
        if (!narration.isNullOrBlank() && narration.uppercase(Locale.US) !in ignoredUpiHandles) {
            return narration
        }
        return vpaRegex?.find(body)?.groupValues?.getOrNull(1)
    }

    private fun cleanMerchant(raw: String): String? {
        val trimmed = raw
            .trim()
            .trim('.', ',', ';', ':', '-', ' ')
            .replace(LEADING_LABEL, "")
            .trim()
            .replace(WHITESPACE, " ")
        if (trimmed.length < 2) return null
        if (trimmed.lowercase(Locale.US) in stopWords) return null
        if (trimmed.all { it.isDigit() }) return null
        return trimmed.take(60)
    }

    fun detectCurrency(body: String): String {
        val lower = body.lowercase(Locale.US)
        for ((code, markers) in config.currency) {
            if (markers.any { lower.contains(it.lowercase(Locale.US)) }) return code
        }
        return config.defaultCurrency
    }

    // ------------------------------------------------------------------
    // Source (bank / payment app) inference
    // ------------------------------------------------------------------

    fun inferSource(body: String, senderOrPackage: String?): String? {
        val packageName = senderOrPackage?.trim().orEmpty()
        if (packageName.isNotEmpty()) {
            config.wallets.firstOrNull { wallet ->
                wallet.packages.any { it.equals(packageName, ignoreCase = true) }
            }?.let { return it.name }
        }

        val sender = packageName.uppercase(Locale.US).replace(NON_ALPHANUMERIC, "")
        if (sender.isNotEmpty()) {
            bankSenders.firstOrNull { (prefix, _) -> sender.contains(prefix) }?.let { return it.second.name }
        }

        val lower = body.lowercase(Locale.US)
        config.banks.forEach { bank ->
            if (bank.keywords.any { lower.contains(it.lowercase(Locale.US)) }) return bank.name
        }
        config.wallets.forEach { wallet ->
            if (wallet.keywords.any { lower.contains(it.lowercase(Locale.US)) }) return wallet.name
        }
        return null
    }

    private companion object {
        const val PARSER_NAME = "config-v1"
        val WHITESPACE = Regex("\\s+")
        val ZERO_WIDTH = Regex("[\\u200B\\u200C\\u200D\\uFEFF]")
        val NON_ALPHANUMERIC = Regex("[^A-Z0-9]")
        val LEADING_LABEL = Regex("(?i)^(?:vpa|upi|to|from)[\\s/:]+")
        val CREDIT_CONTEXT = listOf(
            Regex("(?i)\\b(?:paid|sent|transferred)\\s+you\\b"),
            Regex("(?i)\\b(?:sent|transferred)\\b.{0,40}\\bto\\s+you\\b"),
            Regex("(?i)\\bto\\s+you\\b.{0,30}\\b(?:paid|sent|transferred)\\b")
        )
        val DEBIT_CONTEXT = listOf(
            Regex("(?i)\\byou\\s+(?:paid|sent|transferred)\\b"),
            Regex("(?i)\\b(?:paid|sent|transferred)\\b.{0,40}\\b(?:to|for)\\b")
        )

        fun normalizeDigits(text: String): String = buildString(text.length) {
            text.forEach { character ->
                val digit = Character.digit(character, 10)
                if (digit >= 0 && character !in '0'..'9') append(('0'.code + digit).toChar())
                else append(character)
            }
        }
    }
}
