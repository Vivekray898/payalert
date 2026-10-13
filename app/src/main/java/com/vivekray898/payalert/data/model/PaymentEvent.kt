package com.vivekray898.payalert.data.model

import android.arch.persistence.room.ColumnInfo
import android.arch.persistence.room.Entity
import android.arch.persistence.room.PrimaryKey
import java.sql.Timestamp

@Entity(tableName = "payment_events")
data class PaymentEvent(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "bank_or_upi") val bankOrUpi: String?,
    @ColumnInfo(name = "amount") val amount: Double,
    @ColumnInfo(name = "currency") val currency: String,
    @ColumnInfo(name = "merchant") val merchant: String?,
    @ColumnInfo(name = "utr") val utr: String?,
    @ColumnInfo(name = "tx_id") val txId: String?,
    @ColumnInfo(name = "received_at") val receivedAt: Timestamp,
    @ColumnInfo(name = "dedup_key") val dedupKey: String,
    @ColumnInfo(name = "raw_sender") val rawSender: String?,
    @ColumnInfo(name = "raw_body") val rawBody: String?,
    @ColumnInfo(name = "category") val category: String?,
    @ColumnInfo(name = "synced") val synced: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Timestamp,
    @ColumnInfo(name = "updated_at") val updatedAt: Timestamp
)