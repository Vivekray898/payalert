package com.vivekray898.payalert.relay

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * An encrypted owner-to-employee message waiting for relay delivery.
 *
 * The opaque [envelope] is deliberately kept as a string. Relay cryptography and transport are
 * separate integration layers; this table only provides durable queue state and retry metadata.
 */
@Entity(
    tableName = "owner_outbound_queue",
    primaryKeys = ["message_id"],
    indices = [
        Index(
            value = ["status", "next_attempt_at"],
            name = "idx_owner_outbound_status_next_attempt"
        ),
        Index(
            value = ["employee_id", "status"],
            name = "idx_owner_outbound_employee_status"
        ),
        Index(value = ["expires_at"], name = "idx_owner_outbound_expires_at")
    ]
)
data class OwnerOutboundMessage(
    @ColumnInfo(name = "message_id")
    val messageId: String,

    @ColumnInfo(name = "owner_id")
    val ownerId: String,

    @ColumnInfo(name = "employee_id")
    val employeeId: String,

    @ColumnInfo(name = "envelope")
    val envelope: String,

    /**
     * The plaintext relay payload, kept so a delivery that failed against a rotated peer key can
     * be re-encrypted instead of dropped. This is the owner's own payment detail on the owner's
     * own device, the same data already held in payment_events; it is never transmitted as-is and
     * the row is deleted as soon as the envelope is accepted.
     */
    @ColumnInfo(name = "payload")
    val payload: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    /** Optional transport expiry; received announcements have a mandatory 24-hour expiry. */
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long? = null,

    @ColumnInfo(name = "status")
    val status: String = RelayDeliveryStatus.PENDING,

    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,

    @ColumnInfo(name = "last_attempt_at")
    val lastAttemptAt: Long? = null,

    @ColumnInfo(name = "next_attempt_at")
    val nextAttemptAt: Long? = null,

    @ColumnInfo(name = "last_error")
    val lastError: String? = null,

    @ColumnInfo(name = "delivered_at")
    val deliveredAt: Long? = null,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = createdAt
)
