package com.vivekray898.payalert.ui

import com.vivekray898.payalert.data.PaymentEvent

/**
 * Human-readable origin for a detected event, e.g. `via PhonePe` or `via SMS`.
 *
 * The stored `source` is the detector ("sms" / "notification") and `sourceDetail` is the raw
 * SMS sender id or notification package name. Raw package names must never reach the UI.
 */
object SourceLabel {

    /** Known notification package names -> the app name a user would recognise. */
    private val packages = mapOf(
        "com.phonepe.app" to "PhonePe",
        "com.google.android.apps.nbu.paisa.user" to "Google Pay",
        "net.one97.paytm.app" to "Paytm",
        "net.one97.paytm" to "Paytm",
        "com.amazon.mShop.android.shopping" to "Amazon Pay",
        "in.org.npci.upiapp" to "BHIM",
        "com.dreamplug.androidapp" to "CRED",
        "com.dbs.in.dbsdigibank" to "DBS digibank",
        "com.msf.kbank.mobile" to "Kotak",
        "com.csam.icici.bank.imobile" to "iMobile",
        "com.sbi.lot" to "YONO SBI",
        "com.snapwork.hdfc" to "HDFC Bank",
        "com.axis.mobile" to "Axis Mobile",
        "com.idfcfirstbank.ct" to "IDFC FIRST",
        "com.whatsapp" to "WhatsApp",
        "com.android.shell" to "System"
    )

    /** GSM sender-id prefixes used by Indian banks, e.g. `VM-HDFCBK` -> HDFC Bank. */
    private val senderIds = mapOf(
        "HDFCBK" to "HDFC Bank",
        "ICICIB" to "ICICI Bank",
        "SBIINB" to "SBI",
        "AXISBK" to "Axis Bank",
        "KOTAKB" to "Kotak",
        "PAYTM" to "Paytm",
        "PHONEPE" to "PhonePe",
        "YESBNK" to "Yes Bank",
        "IDFCFB" to "IDFC FIRST"
    )

    fun displaySource(event: PaymentEvent): String {
        val detail = event.sourceDetail?.trim().orEmpty()
        return when {
            event.source.equals("sms", ignoreCase = true) ->
                "via SMS" + senderName(detail)?.let { " · $it" }.orEmpty()

            detail.isEmpty() -> "via Notification"
            else -> "via " + (packages[detail] ?: prettyPackage(detail))
        }
    }

    private fun senderName(senderId: String): String? {
        if (senderId.isEmpty()) return null
        val tail = senderId.substringAfterLast('-').substringAfterLast(' ').uppercase()
        return senderIds[tail]
    }

    /** Last-resort rendering: `com.acme.pay` -> `Pay`. */
    private fun prettyPackage(pkg: String): String {
        val leaf = pkg.substringAfterLast('.')
        if (leaf.isBlank()) return pkg
        return leaf.replaceFirstChar { it.uppercase() }
    }
}
