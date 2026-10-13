package com.vivekray898.payalert.parsers

import android.content.Context
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Reads `parser_config.json` into [ParserConfig].
 *
 * Parsing is done field-by-field from a JSON tree rather than by reflection, so missing or
 * partial keys cannot silently produce nulls, and the same code path runs in unit tests.
 */
object ParserConfigLoader {

    const val ASSET_NAME = "parser_config.json"

    fun loadFromAsset(context: Context, assetName: String = ASSET_NAME): ParserConfig =
        context.assets.open(assetName).bufferedReader().use { reader -> load(reader.readText()) }

    fun load(json: String): ParserConfig {
        val root = JsonParser.parseString(json).asJsonObject

        return ParserConfig(
            schemaVersion = root.int("schemaVersion", 1),
            defaultCurrency = root.string("defaultCurrency", "INR"),
            coordinator = root.obj("coordinator")?.let {
                CoordinatorConfig(
                    dedupWindowMinutes = it.int("dedupWindowMinutes", 5),
                    amountBucketMinutes = it.int("amountBucketMinutes", 5),
                    staleGraceHours = it.int("staleGraceHours", 48)
                )
            } ?: CoordinatorConfig(5, 5, 48),
            patterns = root.obj("patterns")?.let {
                PatternConfig(
                    amount = it.strings("amount"),
                    reference = it.strings("reference"),
                    account = it.strings("account"),
                    balance = it.strings("balance")
                )
            } ?: PatternConfig(emptyList(), emptyList(), emptyList(), emptyList()),
            skip = root.obj("skip")?.let {
                SkipConfig(
                    otp = it.strings("otp"),
                    dueReminder = it.strings("dueReminder"),
                    promo = it.strings("promo"),
                    delivery = it.strings("delivery"),
                    request = it.strings("request"),
                    declined = it.strings("declined")
                )
            } ?: SkipConfig(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
            direction = root.obj("direction")?.let {
                DirectionConfig(
                    debit = it.strings("debit"),
                    credit = it.strings("credit"),
                    debitWins = it.strings("debitWins")
                )
            } ?: DirectionConfig(emptyList(), emptyList(), emptyList()),
            merchant = root.obj("merchant")?.let {
                MerchantConfig(
                    credit = it.strings("credit"),
                    debit = it.strings("debit"),
                    stopWords = it.strings("stopWords"),
                    upiNarration = it.string("upiNarration", ""),
                    vpa = it.string("vpa", ""),
                    upiHandlesToIgnore = it.strings("upiHandlesToIgnore")
                )
            } ?: MerchantConfig(emptyList(), emptyList(), emptyList(), "", "", emptyList()),
            currency = root.obj("currency")?.entrySet()?.associate { (key, value) ->
                key to value.asStringList()
            } ?: emptyMap(),
            banks = root.array("banks").map { element ->
                val bank = element.asJsonObject
                BankRule(
                    id = bank.string("id", ""),
                    name = bank.string("name", bank.string("id", "")),
                    senders = bank.strings("senders"),
                    keywords = bank.strings("keywords"),
                    catchAll = bank.bool("catchAll", false)
                )
            },
            wallets = root.array("wallets").map { element ->
                val wallet = element.asJsonObject
                WalletRule(
                    id = wallet.string("id", ""),
                    name = wallet.string("name", wallet.string("id", "")),
                    packages = wallet.strings("packages"),
                    keywords = wallet.strings("keywords")
                )
            }
        )
    }

    // ---- small JSON helpers -------------------------------------------------

    private fun JsonObject.int(key: String, fallback: Int): Int =
        get(key)?.takeIf { it.isJsonPrimitive }?.asInt ?: fallback

    private fun JsonObject.string(key: String, fallback: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: fallback

    private fun JsonObject.bool(key: String, fallback: Boolean): Boolean =
        get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: fallback

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.array(key: String): List<JsonElement> =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList() ?: emptyList()

    private fun JsonObject.strings(key: String): List<String> =
        get(key)?.asStringList() ?: emptyList()

    private fun JsonElement.asStringList(): List<String> =
        if (isJsonArray) asJsonArray.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
        else emptyList()
}
