package com.vivekray898.payalert.parsers

/**
 * Normalized parser configuration, loaded from `assets/parser_config.json`.
 *
 * PayAlert's rules are data, not code, so bank coverage can grow without touching the
 * parser. The schema is PayAlert's own; the *scope* of coverage (which banks and wallets
 * exist in the Indian market) was informed by inspecting the reference app's shipped
 * rule file, but no rule, pattern or keyword was copied from it.
 */
data class ParserConfig(
    val schemaVersion: Int,
    val defaultCurrency: String,
    val coordinator: CoordinatorConfig,
    val patterns: PatternConfig,
    val skip: SkipConfig,
    val direction: DirectionConfig,
    val merchant: MerchantConfig,
    val currency: Map<String, List<String>>,
    val banks: List<BankRule>,
    val wallets: List<WalletRule>,
    /** Notification packages known to publish payment or SMS-mirror alerts. */
    val notificationPackages: List<String> = emptyList()
) {
    val catchAllBank: BankRule? get() = banks.firstOrNull { it.catchAll }
}

data class CoordinatorConfig(
    val dedupWindowMinutes: Int,
    val amountBucketMinutes: Int,
    val staleGraceHours: Int
)

data class PatternConfig(
    val amount: List<String>,
    val reference: List<String>,
    val account: List<String>,
    val balance: List<String>
)

data class SkipConfig(
    val otp: List<String>,
    val dueReminder: List<String>,
    val promo: List<String>,
    val delivery: List<String>,
    val request: List<String>,
    val declined: List<String>
) {
    /** Category name -> keywords, in evaluation order. */
    fun categories(): List<Pair<String, List<String>>> = listOf(
        "setup" to otp,
        "due_reminder" to dueReminder,
        "promo" to promo,
        "delivery" to delivery,
        "request" to request,
        "declined" to declined
    )
}

data class DirectionConfig(
    val debit: List<String>,
    val credit: List<String>,
    val debitWins: List<String>
)

data class MerchantConfig(
    val credit: List<String>,
    val debit: List<String>,
    val stopWords: List<String>,
    val upiNarration: String,
    val vpa: String,
    val upiHandlesToIgnore: List<String>
)

data class BankRule(
    val id: String,
    val name: String,
    val senders: List<String>,
    val keywords: List<String>,
    val catchAll: Boolean
)

data class WalletRule(
    val id: String,
    val name: String,
    val packages: List<String>,
    val keywords: List<String>
)
