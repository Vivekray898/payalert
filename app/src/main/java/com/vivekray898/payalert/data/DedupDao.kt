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

    /**
     * Tier-1 exact key. A same-app re-post or a repeated SMS hits this row, whose
     * primary key is the exact key itself.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM dedup_index WHERE dedup_key = :key)")
    suspend fun contains(key: String): Boolean

    @Query("SELECT COUNT(*) FROM dedup_index")
    fun observeCount(): Flow<Int>

    /**
     * Tier-2 semantic key. Stored as its own row (dedup_key = semantic key) so the
     * primary-key constraint guards it atomically, while the 10-minute prune can
     * target semantic rows without touching exact-only rows.
     */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM dedup_index " +
            "WHERE semantic_key = :key AND (source IS NULL OR source != :source))"
    )
    suspend fun containsSemanticFromOtherSource(key: String, source: String): Boolean

    /**
     * Persist a semantic key. INSERT OR IGNORE keeps the primary key as the guard:
     * a concurrent duplicate is silently dropped rather than surfacing as an error.
     */
    @Query(
        "INSERT OR IGNORE INTO dedup_index(dedup_key, semantic_key, seen_at, source) " +
            "VALUES(:key, :key, :seenAt, :source)"
    )
    suspend fun insertSemantic(key: String, seenAt: Long, source: String)

    /** Prune exact keys older than the 3-day retention window. */
    @Query("DELETE FROM dedup_index WHERE seen_at < :before")
    suspend fun pruneBefore(before: Long)

    /**
     * Prune semantic keys aggressively. They only need to survive one 5-minute
     * notification window plus a cushion, so 10 minutes is enough.
     *
     * The predicate is `dedup_key = semantic_key` — i.e. rows that ARE a semantic key.
     * Accepted-payment exact rows also carry their semantic key in the column, so the
     * earlier `semantic_key IS NOT NULL` predicate deleted exact keys after 10 minutes
     * and defeated their 3-day retention window.
     */
    @Query(
        "DELETE FROM dedup_index WHERE dedup_key = semantic_key AND seen_at < :cutoff"
    )
    suspend fun pruneSemanticBefore(cutoff: Long)

    @Query("DELETE FROM dedup_index")
    suspend fun clearAll()
}
