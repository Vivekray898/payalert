package com.vivekray898.payalert.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A payment the user expects to make (or receive) later — the "upcoming payments"
 * / passbook list, and the source of reminder notifications.
 */
@Entity(
    tableName = "upcoming_payments",
    indices = [Index(value = ["due_at"])]
)
data class UpcomingPayment(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "label")
    val label: String,

    @ColumnInfo(name = "amount")
    val amount: Double,

    /** Expected direction: "debit" (a bill to pay) or "credit" (expected income). */
    @ColumnInfo(name = "kind")
    val kind: String,

    @ColumnInfo(name = "category")
    val category: String? = null,

    @ColumnInfo(name = "note")
    val note: String? = null,

    @ColumnInfo(name = "due_at")
    val dueAt: Long,

    /** When true the reminder repeats every month. */
    @ColumnInfo(name = "repeat_monthly")
    val repeatMonthly: Boolean = false,

    @ColumnInfo(name = "is_paid")
    val isPaid: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long
)
