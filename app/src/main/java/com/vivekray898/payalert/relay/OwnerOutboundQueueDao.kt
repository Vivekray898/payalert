package com.vivekray898.payalert.relay

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OwnerOutboundQueueDao {

    /** INSERT OR IGNORE makes enqueue safe when a transport retries the same message ID. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: OwnerOutboundMessage)

    @Query("SELECT * FROM owner_outbound_queue WHERE message_id = :messageId")
    suspend fun byMessageId(messageId: String): OwnerOutboundMessage?

    /** Messages ready for a transport attempt, oldest first. */
    @Query(
        """
        SELECT * FROM owner_outbound_queue
        WHERE status IN ('pending', 'failed')
          AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
          AND (expires_at IS NULL OR expires_at > :now)
        ORDER BY created_at ASC
        LIMIT :limit
        """
    )
    suspend fun due(now: Long, limit: Int): List<OwnerOutboundMessage>

    @Query("SELECT * FROM owner_outbound_queue WHERE status = :status ORDER BY created_at ASC")
    suspend fun byStatus(status: String): List<OwnerOutboundMessage>

    @Query("SELECT * FROM owner_outbound_queue ORDER BY created_at ASC")
    suspend fun all(): List<OwnerOutboundMessage>

    /** Records an attempt and leaves the caller in control of retry/backoff scheduling. */
    @Query(
        """
        UPDATE owner_outbound_queue
        SET status = :status,
            attempt_count = attempt_count + 1,
            last_attempt_at = :attemptedAt,
            next_attempt_at = :nextAttemptAt,
            last_error = :error,
            updated_at = :updatedAt
        WHERE message_id = :messageId
        """
    )
    suspend fun recordAttempt(
        messageId: String,
        status: String,
        attemptedAt: Long,
        nextAttemptAt: Long?,
        error: String?,
        updatedAt: Long
    ): Int

    @Query(
        """
        UPDATE owner_outbound_queue
        SET status = '${RelayDeliveryStatus.DELIVERED}',
            delivered_at = :deliveredAt,
            next_attempt_at = NULL,
            last_error = NULL,
            updated_at = :updatedAt
        WHERE message_id = :messageId
        """
    )
    suspend fun markDelivered(messageId: String, deliveredAt: Long, updatedAt: Long): Int

    /** Drops everything not yet accepted by the server, e.g. when a pairing ends. */
    @Query("DELETE FROM owner_outbound_queue WHERE status IN ('pending', 'failed')")
    suspend fun deletePending(): Int

    @Query(
        "DELETE FROM owner_outbound_queue " +
            "WHERE status IN ('pending', 'failed') AND employee_id = :employeeId"
    )
    suspend fun deletePendingFor(employeeId: String): Int

    /** Drops queued work for employees that are no longer paired. */
    @Query(
        "DELETE FROM owner_outbound_queue " +
            "WHERE status IN ('pending', 'failed') AND employee_id NOT IN (:deviceIds)"
    )
    suspend fun deletePendingExcept(deviceIds: List<String>): Int

    @Query("SELECT MAX(updated_at) FROM owner_outbound_queue WHERE status = '${RelayDeliveryStatus.DELIVERED}'")
    fun observeLastDeliveredAt(): Flow<Long?>

    @Query("SELECT COUNT(*) FROM owner_outbound_queue")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM owner_outbound_queue WHERE status = :status")
    fun observeCount(status: String): Flow<Int>

    @Query(
        """
        SELECT status,
               COUNT(*) AS row_count,
               MIN(created_at) AS oldest_created_at,
               MAX(updated_at) AS latest_updated_at
        FROM owner_outbound_queue
        GROUP BY status
        ORDER BY status
        """
    )
    fun observeDiagnostics(): Flow<List<RelayQueueDiagnostics>>

    @Query(
        """
        SELECT status,
               COUNT(*) AS row_count,
               MIN(created_at) AS oldest_created_at,
               MAX(updated_at) AS latest_updated_at
        FROM owner_outbound_queue
        GROUP BY status
        ORDER BY status
        """
    )
    suspend fun diagnostics(): List<RelayQueueDiagnostics>
}
