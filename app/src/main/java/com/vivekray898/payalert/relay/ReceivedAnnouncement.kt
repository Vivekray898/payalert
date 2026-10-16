package com.vivekray898.payalert.relay

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * A decrypted announcement received on an employee device.
 *
 * [messageId] is the sender's client message id and the primary key, which makes a repeated
 * delivery idempotent without depending on the server's row id.
 *
 * Rows live for exactly 24 hours from receipt: [expiresAt] is derived at construction time so the
 * retention rule has a single definition, and reads compare against the current clock as well as
 * relying on the scheduled sweep.
 *
 * The decrypted display fields are local-only. [envelope] holds the sender's ciphertext until the
 * message has been processed and acknowledged, after which it is cleared: no plaintext payment
 * detail is ever sent back to the server, and no ciphertext is retained once it is no longer
 * useful.
 */
@Entity(
    tableName = "received_announcements",
    primaryKeys = ["message_id"],
    indices = [
        Index(value = ["status"], name = "idx_received_announcements_status"),
        Index(value = ["expires_at"], name = "idx_received_announcements_expires_at"),
        Index(
            value = ["owner_id", "received_at"],
            name = "idx_received_announcements_owner_received"
        )
    ]
)
data class ReceivedAnnouncement(
    @ColumnInfo(name = "message_id")
    val messageId: String,

    @ColumnInfo(name = "owner_id")
    val ownerId: String,

    @ColumnInfo(name = "employee_id")
    val employeeId: String,

    @ColumnInfo(name = "envelope")
    val envelope: String,

    @ColumnInfo(name = "received_at")
    val receivedAt: Long,

    @ColumnInfo(name = "expires_at")
    val expiresAt: Long = RelayCleanupPolicy.receivedExpiresAt(receivedAt),

    @ColumnInfo(name = "status")
    val status: String = RelayDeliveryStatus.PENDING,

    @ColumnInfo(name = "amount")
    val amount: Double? = null,

    @ColumnInfo(name = "currency")
    val currency: String? = null,

    @ColumnInfo(name = "merchant")
    val merchant: String? = null,

    @ColumnInfo(name = "bank_or_upi")
    val bankOrUpi: String? = null,

    /** When the owner device detected the payment, not when the employee received it. */
    @ColumnInfo(name = "event_at")
    val eventAt: Long? = null,

    /** Owner device name shown in History, e.g. "Counter phone". */
    @ColumnInfo(name = "owner_label")
    val ownerLabel: String? = null,

    /**
     * True when the envelope could not be authenticated. The row carries no payment detail and
     * exists only so the UI can ask the user to re-pair.
     */
    @ColumnInfo(name = "decrypt_failed")
    val decryptFailed: Boolean = false,

    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,

    @ColumnInfo(name = "last_attempt_at")
    val lastAttemptAt: Long? = null,

    @ColumnInfo(name = "last_error")
    val lastError: String? = null,

    @ColumnInfo(name = "processed_at")
    val processedAt: Long? = null,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = receivedAt
)
