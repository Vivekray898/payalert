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
    val seenAt: Long,

    /**
     * Tier-2 semantic key for cross-app duplicate suppression. Null for exact-only
     * entries (same-app re-posts, repeated SMS), which keeps the partial index small.
     */
    @ColumnInfo(name = "semantic_key")
    val semanticKey: String? = null
)
