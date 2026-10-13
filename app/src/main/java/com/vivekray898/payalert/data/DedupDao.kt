package com.vivekray898.payalert.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DedupDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: DedupEntry)

    @Query("SELECT EXISTS(SELECT 1 FROM dedup_index WHERE dedup_key = :key)")
    suspend fun contains(key: String): Boolean

    @Query("SELECT COUNT(*) FROM dedup_index")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM dedup_index WHERE seen_at < :before")
    suspend fun pruneBefore(before: Long)

    @Query("DELETE FROM dedup_index")
    suspend fun clearAll()
}
