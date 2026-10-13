package com.vivekray898.payalert.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PaymentEvent::class,
        CashbookEntry::class,
        DedupEntry::class,
        UpcomingPayment::class
    ],
    version = 3,
    exportSchema = false
)
abstract class PayAlertDatabase : RoomDatabase() {

    abstract fun paymentEventDao(): PaymentEventDao
    abstract fun cashbookDao(): CashbookDao
    abstract fun dedupDao(): DedupDao
    abstract fun upcomingPaymentDao(): UpcomingPaymentDao

    companion object {
        private const val DB_NAME = "payalert.db"

        @Volatile
        private var instance: PayAlertDatabase? = null

        fun get(context: Context): PayAlertDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PayAlertDatabase::class.java,
                    DB_NAME
                )
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { instance = it }
            }
    }
}
