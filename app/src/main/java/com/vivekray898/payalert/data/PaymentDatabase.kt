package com.vivekray898.payalert.data

import android.arch.persistence.room.Database
import android.arch.persistence.room.Room
import android.arch.persistence.room.RoomDatabase
import com.vivekray898.payalert.data.dao.PaymentEventDao
import com.vivekray898.payalert.data.model.PaymentEvent
import com.vivekray898.payalert.sync.SupabaseSyncClient

@Database(
    entities = [PaymentEvent::class],
    version = 1,
    exportSchema = false
)
abstract class PaymentDatabase : RoomDatabase() {

    abstract fun paymentEventDao(): PaymentEventDao

    // Singleton holder
    companion object {
        @Volatile
        private var INSTANCE: PaymentDatabase? = null

        fun getInstance(context: Context): PaymentDatabase {
            // Use applicationContext to avoid leaks
            return INSTANCE ?: synchronized(this) {
                val db = Room.databaseBuilder(
                    context.applicationContext,
                    PaymentDatabase::class.java,
                    "payalert_db"
                .build()
                // Enable migration/factory fallback if needed
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = db
                db
            }
        }
    }
}