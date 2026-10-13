package com.vivekray898.payalert.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface UpcomingPaymentDao {

    @Insert
    suspend fun insert(entry: UpcomingPayment): Long

    /**
     * Restore-path insert: an existing row is reported by returning -1 instead of aborting
     * the whole import with a constraint error.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entry: UpcomingPayment): Long

    @Update
    suspend fun update(entry: UpcomingPayment)

    @Query("DELETE FROM upcoming_payments WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM upcoming_payments ORDER BY due_at ASC")
    fun observeAll(): Flow<List<UpcomingPayment>>

    @Query("SELECT * FROM upcoming_payments WHERE is_paid = 0 ORDER BY due_at ASC")
    suspend fun pending(): List<UpcomingPayment>

    @Query("SELECT * FROM upcoming_payments ORDER BY due_at ASC")
    suspend fun all(): List<UpcomingPayment>

    @Query("SELECT * FROM upcoming_payments WHERE id = :id")
    suspend fun byId(id: Long): UpcomingPayment?

    @Query("SELECT * FROM upcoming_payments WHERE is_paid = 0 AND due_at >= :now ORDER BY due_at ASC")
    suspend fun scheduledAfter(now: Long): List<UpcomingPayment>

    @Query("UPDATE upcoming_payments SET is_paid = 1 WHERE id = :id")
    suspend fun markPaid(id: Long)

    @Query("DELETE FROM upcoming_payments")
    suspend fun clearAll()
}
