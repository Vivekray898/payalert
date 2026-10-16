package com.vivekray898.payalert.relay

import androidx.room.ColumnInfo

/** Read-only aggregate used by a future diagnostics surface without exposing payloads. */
data class RelayQueueDiagnostics(
    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "row_count")
    val rowCount: Int,

    @ColumnInfo(name = "oldest_created_at")
    val oldestCreatedAt: Long?,

    @ColumnInfo(name = "latest_updated_at")
    val latestUpdatedAt: Long?
)
