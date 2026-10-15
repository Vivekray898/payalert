package com.vivekray898.payalert.data

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PaymentEvent::class,
        CashbookEntry::class,
        DedupEntry::class,
        UpcomingPayment::class
    ],
    version = 4,
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
                    .addMigrations(SEMANTIC_DEDUP_MIGRATION)
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { instance = it }
            }

        /**
         * Adds the semantic dedup key to the existing dedup_index table so cross-app
         * duplicate suppression can run without losing existing exact keys.
         *
         * The partial index is created with a WHERE clause so rows that never carry a
         * semantic key (exact-only entries) stay outside it.
         */
        val SEMANTIC_DEDUP_MIGRATION = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE dedup_index ADD COLUMN semantic_key TEXT"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_dedup_semantic ON dedup_index(semantic_key) WHERE semantic_key IS NOT NULL"
                )
            }
        }
    }
}
