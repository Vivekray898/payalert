package com.vivekray898.payalert.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CashbookDao {

    @Insert
    suspend fun insert(entry: CashbookEntry): Long

    /**
     * Restore-path insert: a row that already exists is reported by returning -1 instead of
     * aborting the whole import with a constraint error.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entry: CashbookEntry): Long

    @Update
    suspend fun update(entry: CashbookEntry)

    @Query("DELETE FROM cashbook_entries WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM cashbook_entries ORDER BY occurred_at DESC")
    fun observeAll(): Flow<List<CashbookEntry>>

    @Query("SELECT * FROM cashbook_entries WHERE id = :id")
    suspend fun byId(id: Long): CashbookEntry?

    @Query("SELECT * FROM cashbook_entries ORDER BY occurred_at DESC")
    suspend fun all(): List<CashbookEntry>

    @Query("SELECT * FROM cashbook_entries WHERE occurred_at >= :from AND occurred_at <= :to ORDER BY occurred_at DESC")
    fun observeInRange(from: Long, to: Long): Flow<List<CashbookEntry>>

    @Query("SELECT * FROM cashbook_entries WHERE occurred_at >= :from AND occurred_at <= :to ORDER BY occurred_at DESC")
    suspend fun inRange(from: Long, to: Long): List<CashbookEntry>

    @Query("SELECT DISTINCT category FROM cashbook_entries WHERE category IS NOT NULL AND category != '' ORDER BY category")
    fun observeCategories(): Flow<List<String>>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM cashbook_entries WHERE kind = 'credit'")
    fun observeCreditTotal(): Flow<Double>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM cashbook_entries WHERE kind = 'debit'")
    fun observeDebitTotal(): Flow<Double>

    @Query("DELETE FROM cashbook_entries")
    suspend fun clearAll()
}
