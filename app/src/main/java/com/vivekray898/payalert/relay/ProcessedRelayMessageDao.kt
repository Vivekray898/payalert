package com.vivekray898.payalert.relay

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ProcessedRelayMessageDao {

    /** Insert a processed message ID; ignores duplicates. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(processed: ProcessedRelayMessage): Long

    /** Check if a message ID has already been processed. */
    @Query("SELECT 1 FROM processed_relay_messages WHERE message_id = :messageId LIMIT 1")
    suspend fun exists(messageId: String): Boolean

    /** Delete processed messages older than the given timestamp. */
    @Query("DELETE FROM processed_relay_messages WHERE processed_at < :beforeTimestamp")
    suspend fun deleteBefore(beforeTimestamp: Long): Int

    /** Delete all processed messages. Used when leaving the team. */
    @Query("DELETE FROM processed_relay_messages")
    suspend fun deleteAll(): Int
}
