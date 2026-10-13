package com.vivekray898.payalert.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single detected payment (credit or debit) captured from an SMS or a notification.
 *
 * Timestamps are epoch milliseconds (Room-friendly) rather than java.sql.Timestamp.
 */
@Entity(
    tableName = "payment_events",
    indices = [
        Index(value = ["received_at"]),
        Index(value = ["dedup_key"], unique = true),
        Index(value = ["kind"])
    ]
)
data class PaymentEvent(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    /** "credit" (money in) or "debit" (money out). */
    @ColumnInfo(name = "kind")
    val kind: String,

    @ColumnInfo(name = "amount")
    val amount: Double,

    @ColumnInfo(name = "currency")
    val currency: String = "INR",

    /** Bank or payment-app identifier inferred from the sender, e.g. "HDFC", "PhonePe". */
    @ColumnInfo(name = "bank_or_upi")
    val bankOrUpi: String? = null,

    @ColumnInfo(name = "merchant")
    val merchant: String? = null,

    /** UPI / NEFT / IMPS reference number when present. */
    @ColumnInfo(name = "utr")
    val utr: String? = null,

    /** Masked account or card, e.g. "XX4321". */
    @ColumnInfo(name = "account")
    val account: String? = null,

    /** Available balance quoted by the alert, when present. */
    @ColumnInfo(name = "balance")
    val balance: Double? = null,

    @ColumnInfo(name = "category")
    val category: String? = null,

    /** Which detector produced the event: "sms" or "notification". */
    @ColumnInfo(name = "source")
    val source: String,

    /** SMS sender id or notification package name. */
    @ColumnInfo(name = "source_detail")
    val sourceDetail: String? = null,

    @ColumnInfo(name = "raw_text")
    val rawText: String? = null,

    /** Stable content-based key used to suppress duplicates across both detectors. */
    @ColumnInfo(name = "dedup_key")
    val dedupKey: String,

    @ColumnInfo(name = "received_at")
    val receivedAt: Long,

    /** Reserved for the optional cloud sync path; always false while offline-only. */
    @ColumnInfo(name = "synced")
    val synced: Boolean = false
)
