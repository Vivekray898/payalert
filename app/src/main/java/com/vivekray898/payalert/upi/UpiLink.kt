package com.vivekray898.payalert.upi

import java.net.URLEncoder
import java.util.Locale

/**
 * Builds a standard UPI collect link (`upi://pay?...`), the payload behind the payment QR.
 *
 * Pure and dependency-free so the parameter escaping and the optional-amount behaviour are
 * unit-tested rather than eyeballed.
 */
object UpiLink {

    const val DEFAULT_CURRENCY = "INR"

    fun build(
        upiId: String,
        payeeName: String,
        amount: Double? = null,
        note: String? = null
    ): String {
        val id = upiId.trim()
        val builder = StringBuilder("upi://pay?pa=")
            .append(encode(id))
            .append("&pn=")
            .append(encode(payeeName.trim().ifBlank { id }))
            .append("&cu=")
            .append(DEFAULT_CURRENCY)

        if (amount != null && amount > 0.0) {
            builder.append("&am=").append(String.format(Locale.US, "%.2f", amount))
        }
        if (!note.isNullOrBlank()) {
            builder.append("&tn=").append(encode(note.trim()))
        }
        return builder.toString()
    }

    /** URLEncoder is form-encoding; UPI links expect %20 rather than '+'. */
    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
