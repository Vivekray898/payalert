package com.vivekray898.payalert.data.dao

import android.arch.persistence.room.Dao
import android.arch.persistence.room.Insert
import android.arch.persistence.room.OnConflictStrategy
import android.arch.persistence.room.Query
import com.vivekray898.payalert.data.model.PaymentEvent

@Dao
interface PaymentEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: PaymentEvent)

    @Query("SELECT * FROM payment_events WHERE synced = 0")
    suspend fun getUnsynced(): List<PaymentEvent>

    @Query("SELECT * FROM payment_events WHERE id = :id")
    suspend fun getById(id: String): PaymentEvent?

    @Query("UPDATE payment_events SET synced = 1 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("SELECT * FROM payment_events ORDER BY received_at DESC LIMIT 50")
    suspend fun getRecent(): List<PaymentEvent>

    @Query("SELECT COUNT(*) FROM payment_events WHERE received_at >= date_sub(curdate(), interval 30 day)")
    suspend fun countLast30Days(): Int
}