package com.vivekray898.payalert.data.model

import android.arch.persistence.room.ColumnInfo
import android.arch.persistence.room.Entity
import android.arch.persistence.room.PrimaryKey

@Entity(tableName = "dedup_index", primaryKey = ["dedup_key"])
data class DedupEntry(
    @ColumnInfo(name = "dedup_key") val dedupKey: String,
    @ColumnInfo(name = "seen_at") val seenAt: String
)