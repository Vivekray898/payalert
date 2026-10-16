package com.vivekray898.payalert.relay

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.vivekray898.payalert.data.PaymentEvent
import java.nio.charset.StandardCharsets.UTF_8

/**
 * The plaintext payment attributes that cross the owner -> employee relay.
 *
 * This is the only shape that is ever encrypted, so it deliberately excludes the raw SMS or
 * notification body, the account number, and anything else that is not needed to announce a
 * received payment and show it in History.
 */
data class RelayPaymentPayload(
    val kind: String,
    val amount: Double,
    val currency: String,
    val merchant: String?,
    val bankOrUpi: String?,
    val utr: String?,
    val eventAt: Long
) {
    companion object {
        /** Only credits are ever forwarded; a debit is never encoded for the relay. */
        fun from(event: PaymentEvent): RelayPaymentPayload? {
            if (event.kind != CREDIT) return null
            if (!event.amount.isFinite() || event.amount <= 0.0) return null
            return RelayPaymentPayload(
                kind = CREDIT,
                amount = event.amount,
                currency = event.currency.take(MAX_TEXT).ifBlank { DEFAULT_CURRENCY },
                merchant = event.merchant?.sanitized(),
                bankOrUpi = event.bankOrUpi?.sanitized(),
                utr = event.utr?.sanitized(),
                eventAt = event.receivedAt
            )
        }

        const val CREDIT = "credit"
        const val DEFAULT_CURRENCY = "INR"
        private const val MAX_TEXT = 120

        private fun String.sanitized(): String? =
            filterNot { it.isISOControl() }.trim().take(MAX_TEXT).takeIf { it.isNotBlank() }
    }
}

/** Raised for a malformed or unexpectedly large payload; never carries payload content. */
class RelayPayloadException(message: String) : Exception(message)

/**
 * JSON codec for [RelayPaymentPayload].
 *
 * The codec is strict on decode: an unknown kind, a non-finite or non-positive amount, or a value
 * that is longer than the sender should ever produce is rejected instead of being announced.
 */
object RelayPayloadCodec {
    const val MAX_PAYLOAD_BYTES = 4096

    private const val KIND = "kind"
    private const val AMOUNT = "amount"
    private const val CURRENCY = "currency"
    private const val MERCHANT = "merchant"
    private const val BANK = "bank"
    private const val UTR = "utr"
    private const val EVENT_AT = "eventAt"
    private const val MAX_TEXT = 120

    private val gson = Gson()

    fun encode(payload: RelayPaymentPayload): ByteArray {
        require(payload.kind == RelayPaymentPayload.CREDIT) { "Only credits may be relayed" }
        require(payload.amount.isFinite() && payload.amount > 0.0) { "Invalid relay amount" }
        val json = JsonObject().apply {
            addProperty(KIND, payload.kind)
            addProperty(AMOUNT, payload.amount)
            addProperty(CURRENCY, payload.currency.take(MAX_TEXT))
            addProperty(EVENT_AT, payload.eventAt)
            payload.merchant?.takeIf { it.isNotBlank() }?.let { addProperty(MERCHANT, it.take(MAX_TEXT)) }
            payload.bankOrUpi?.takeIf { it.isNotBlank() }?.let { addProperty(BANK, it.take(MAX_TEXT)) }
            payload.utr?.takeIf { it.isNotBlank() }?.let { addProperty(UTR, it.take(MAX_TEXT)) }
        }
        val bytes = gson.toJson(json).toByteArray(UTF_8)
        require(bytes.size <= MAX_PAYLOAD_BYTES) { "Relay payload is too large" }
        return bytes
    }

    fun decode(bytes: ByteArray): RelayPaymentPayload {
        if (bytes.isEmpty() || bytes.size > MAX_PAYLOAD_BYTES) {
            throw RelayPayloadException("Relay payload is invalid")
        }
        val root = try {
            JsonParser.parseString(String(bytes, UTF_8)).asJsonObject
        } catch (_: Exception) {
            throw RelayPayloadException("Relay payload is invalid")
        }
        val kind = root.string(KIND) ?: throw RelayPayloadException("Relay payload is invalid")
        if (kind != RelayPaymentPayload.CREDIT) {
            throw RelayPayloadException("Relay payload is invalid")
        }
        val amount = root.number(AMOUNT) ?: throw RelayPayloadException("Relay payload is invalid")
        if (!amount.isFinite() || amount <= 0.0) {
            throw RelayPayloadException("Relay payload is invalid")
        }
        val eventAt = root.number(EVENT_AT)?.toLong() ?: throw RelayPayloadException("Relay payload is invalid")
        return RelayPaymentPayload(
            kind = kind,
            amount = amount,
            currency = root.string(CURRENCY)?.take(MAX_TEXT)
                ?: RelayPaymentPayload.DEFAULT_CURRENCY,
            merchant = root.string(MERCHANT)?.take(MAX_TEXT),
            bankOrUpi = root.string(BANK)?.take(MAX_TEXT),
            utr = root.string(UTR)?.take(MAX_TEXT),
            eventAt = eventAt
        )
    }

    private fun JsonObject.string(name: String): String? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        val value = element.asString.filterNot { it.isISOControl() }.trim()
        return value.takeIf { it.isNotEmpty() && it.length <= MAX_TEXT }
    }

    private fun JsonObject.number(name: String): Double? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return null
        return element.asDouble
    }
}
