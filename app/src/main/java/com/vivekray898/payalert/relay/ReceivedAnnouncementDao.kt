package com.vivekray898.payalert.relay

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReceivedAnnouncementDao {

    /** INSERT OR IGNORE makes duplicate delivery of a message id idempotent. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(announcement: ReceivedAnnouncement)

    @Query("SELECT * FROM received_announcements WHERE message_id = :messageId")
    suspend fun byMessageId(messageId: String): ReceivedAnnouncement?

    @Query(
        """
        SELECT * FROM received_announcements
        WHERE status IN ('pending', 'failed')
          AND expires_at > :now
        ORDER BY received_at ASC
        LIMIT :limit
        """
    )
    suspend fun pending(now: Long, limit: Int): List<ReceivedAnnouncement>

    /** Rows whose envelope was retained after a failed decryption, for a later recovery attempt. */
    @Query(
        """
        SELECT * FROM received_announcements
        WHERE decrypt_failed = 1 AND envelope != '' AND expires_at > :now
        ORDER BY received_at ASC
        LIMIT :limit
        """
    )
    suspend fun unreadable(now: Long, limit: Int): List<ReceivedAnnouncement>

    @Query("SELECT * FROM received_announcements WHERE status = :status ORDER BY received_at ASC")
    suspend fun byStatus(status: String): List<ReceivedAnnouncement>

    @Query("SELECT * FROM received_announcements ORDER BY received_at DESC")
    suspend fun all(): List<ReceivedAnnouncement>

    @Query("DELETE FROM received_announcements WHERE message_id = :messageId")
    suspend fun delete(messageId: String): Int

    @Query(
        """
        UPDATE received_announcements
        SET status = :status,
            attempt_count = attempt_count + 1,
            last_attempt_at = :attemptedAt,
            last_error = :error,
            updated_at = :updatedAt
        WHERE message_id = :messageId
        """
    )
    suspend fun recordAttempt(
        messageId: String,
        status: String,
        attemptedAt: Long,
        error: String?,
        updatedAt: Long
    ): Int

    @Query(
        """
        UPDATE received_announcements
        SET status = '${RelayDeliveryStatus.PROCESSED}',
            processed_at = :processedAt,
            last_error = NULL,
            updated_at = :updatedAt
        WHERE message_id = :messageId
        """
    )
    suspend fun markProcessed(messageId: String, processedAt: Long, updatedAt: Long): Int

    /**
     * Claims a row for announcement. The claim is made before speaking so a row is announced
     * exactly once even if the process dies in between, and a re-delivered message is silent.
     */
    @Query(
        """
        UPDATE received_announcements
        SET status = '${RelayDeliveryStatus.PROCESSED}',
            processed_at = :processedAt,
            last_error = NULL,
            updated_at = :updatedAt
        WHERE message_id = :messageId AND status = '${RelayDeliveryStatus.PENDING}'
        """
    )
    suspend fun claimForAnnouncement(messageId: String, processedAt: Long, updatedAt: Long): Int

    /** Deletes all expired rows, including rows that were never successfully processed. */
    @Query("DELETE FROM received_announcements WHERE expires_at <= :now")
    suspend fun deleteExpired(now: Long): Int

    /**
     * Purges everything received from one owner. Used when the pairing ends, so a removed owner
     * leaves no announcement, ciphertext, or display row behind on this device.
     */
    @Query("DELETE FROM received_announcements WHERE owner_id = :ownerId")
    suspend fun deleteForOwner(ownerId: String): Int

    /** Requires a re-pair banner while an unreadable row is still within its retention window. */
    @Query("SELECT COUNT(*) FROM received_announcements WHERE decrypt_failed = 1 AND expires_at > :now")
    suspend fun decryptFailureCount(now: Long): Int

    @Query("SELECT COUNT(*) FROM received_announcements")
    fun observeCount(): Flow<Int>

    @Query(
        """
        SELECT status,
               COUNT(*) AS row_count,
               MIN(received_at) AS oldest_created_at,
               MAX(updated_at) AS latest_updated_at
        FROM received_announcements
        GROUP BY status
        ORDER BY status
        """
    )
    fun observeDiagnostics(): Flow<List<RelayQueueDiagnostics>>

    @Query(
        """
        SELECT status,
               COUNT(*) AS row_count,
               MIN(received_at) AS oldest_created_at,
               MAX(updated_at) AS latest_updated_at
        FROM received_announcements
        GROUP BY status
        ORDER BY status
        """
    )
    suspend fun diagnostics(): List<RelayQueueDiagnostics>
}
