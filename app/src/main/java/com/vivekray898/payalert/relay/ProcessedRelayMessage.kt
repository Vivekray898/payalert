package com.vivekray898.payalert.relay

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tracks message IDs that have been processed on this employee device to prevent
 * duplicate announcements when both Realtime WebSocket and FCM push deliver the same row.
 *
 * Rows older than 7 days are pruned by [RelayCleanupScheduler].
 *
 * NOTE: Room does not auto-convert camelCase field names to snake_case column names for
 * entity fields, so every column name is stated explicitly with [ColumnInfo]. The v7
 * migration creates `message_id` / `processed_at`; without these annotations Room's
 * runtime schema validator reports a mismatch and the app crashes on open.
 */
@Entity(
    tableName = "processed_relay_messages",
    indices = [
        Index(value = ["processed_at"], name = "idx_processed_relay_processed_at")
    ]
)
data class ProcessedRelayMessage(
    @ColumnInfo(name = "message_id")
    @PrimaryKey val messageId: String,

    @ColumnInfo(name = "processed_at")
    val processedAt: Long
)
