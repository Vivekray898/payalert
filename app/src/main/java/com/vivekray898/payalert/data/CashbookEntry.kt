package com.vivekray898.payalert.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A manually maintained cashbook entry. Independent of auto-detected payment events:
 * the cashbook is the user's own ledger of money in/out.
 */
@Entity(
    tableName = "cashbook_entries",
    indices = [Index(value = ["occurred_at"])]
)
data class CashbookEntry(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "label")
    val label: String,

    /** "credit" (money in) or "debit" (money out). */
    @ColumnInfo(name = "kind")
    val kind: String,

    @ColumnInfo(name = "amount")
    val amount: Double,

    @ColumnInfo(name = "category")
    val category: String? = null,

    @ColumnInfo(name = "note")
    val note: String? = null,

    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long,

    @ColumnInfo(name = "created_at")
    val createdAt: Long
)
