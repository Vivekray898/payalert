package com.vivekray898.payalert.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PaymentEventDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: PaymentEvent): Long

    @Query("SELECT * FROM payment_events ORDER BY received_at DESC")
    fun observeAll(): Flow<List<PaymentEvent>>

    /**
     * History query backing the transactions screen: direction, account, date range and a
     * free-text term are all applied in SQL so large histories stay cheap to render.
     */
    @Query(
        """
        SELECT * FROM payment_events
        WHERE (:kind IS NULL OR kind = :kind)
          AND (:account IS NULL OR account = :account)
          AND received_at >= :from AND received_at <= :to
          AND (:term = ''
               OR merchant LIKE '%' || :term || '%'
               OR raw_text LIKE '%' || :term || '%'
               OR utr LIKE '%' || :term || '%'
               OR bank_or_upi LIKE '%' || :term || '%')
        ORDER BY received_at DESC
        """
    )
    fun observeFiltered(
        kind: String?,
        account: String?,
        term: String,
        from: Long,
        to: Long
    ): Flow<List<PaymentEvent>>

    @Query(
        """
        SELECT * FROM payment_events
        WHERE (:kind IS NULL OR kind = :kind)
          AND (:account IS NULL OR account = :account)
          AND received_at >= :from AND received_at <= :to
          AND (:term = ''
               OR merchant LIKE '%' || :term || '%'
               OR raw_text LIKE '%' || :term || '%'
               OR utr LIKE '%' || :term || '%'
               OR bank_or_upi LIKE '%' || :term || '%')
        ORDER BY received_at DESC
        """
    )
    suspend fun filtered(
        kind: String?,
        account: String?,
        term: String,
        from: Long,
        to: Long
    ): List<PaymentEvent>

    @Query("SELECT DISTINCT account FROM payment_events WHERE account IS NOT NULL AND account != '' ORDER BY account")
    fun observeAccounts(): Flow<List<String>>

    @Query("SELECT * FROM payment_events ORDER BY received_at DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<PaymentEvent>

    @Query("SELECT * FROM payment_events WHERE received_at >= :from AND received_at <= :to ORDER BY received_at DESC")
    fun observeInRange(from: Long, to: Long): Flow<List<PaymentEvent>>

    @Query("SELECT * FROM payment_events WHERE received_at >= :from AND received_at <= :to ORDER BY received_at DESC")
    suspend fun inRange(from: Long, to: Long): List<PaymentEvent>

    @Query("SELECT * FROM payment_events ORDER BY received_at DESC")
    suspend fun all(): List<PaymentEvent>

    @Query("SELECT * FROM payment_events WHERE id = :id")
    suspend fun byId(id: Long): PaymentEvent?

    @Query("SELECT COUNT(*) FROM payment_events")
    fun observeCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM payment_events WHERE kind = 'credit'")
    fun observeCreditTotal(): Flow<Double>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM payment_events WHERE kind = 'debit'")
    fun observeDebitTotal(): Flow<Double>

    @Query("SELECT * FROM payment_events WHERE synced = 0 ORDER BY received_at ASC")
    suspend fun unsynced(): List<PaymentEvent>

    @Query("UPDATE payment_events SET synced = 1 WHERE id = :id")
    suspend fun markSynced(id: Long)

    @Query("DELETE FROM payment_events")
    suspend fun clearAll()
}
