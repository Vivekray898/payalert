package com.vivekray898.payalert.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per processed event key. Persisted so duplicate suppression survives
 * process death and device restarts.
 */
@Entity(tableName = "dedup_index")
data class DedupEntry(
    @PrimaryKey
    @ColumnInfo(name = "dedup_key")
    val dedupKey: String,

    @ColumnInfo(name = "seen_at")
    val seenAt: Long
)
