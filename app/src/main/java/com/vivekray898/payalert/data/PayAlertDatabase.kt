package com.vivekray898.payalert.data

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.vivekray898.payalert.relay.OwnerOutboundMessage
import com.vivekray898.payalert.relay.OwnerOutboundQueueDao
import com.vivekray898.payalert.relay.ProcessedRelayMessage
import com.vivekray898.payalert.relay.ProcessedRelayMessageDao
import com.vivekray898.payalert.relay.ReceivedAnnouncement
import com.vivekray898.payalert.relay.ReceivedAnnouncementDao
import com.vivekray898.payalert.relay.RelayPeerKey
import com.vivekray898.payalert.relay.RelayPeerKeyDao

@Database(
    entities = [
        PaymentEvent::class,
        CashbookEntry::class,
        DedupEntry::class,
        UpcomingPayment::class,
        OwnerOutboundMessage::class,
        ReceivedAnnouncement::class,
        RelayPeerKey::class,
        ProcessedRelayMessage::class
    ],
    version = 8,
    exportSchema = false
)
abstract class PayAlertDatabase : RoomDatabase() {

    abstract fun paymentEventDao(): PaymentEventDao
    abstract fun cashbookDao(): CashbookDao
    abstract fun dedupDao(): DedupDao
    abstract fun upcomingPaymentDao(): UpcomingPaymentDao
    abstract fun ownerOutboundQueueDao(): OwnerOutboundQueueDao
    abstract fun receivedAnnouncementDao(): ReceivedAnnouncementDao
    abstract fun relayPeerKeyDao(): RelayPeerKeyDao
    abstract fun processedRelayMessageDao(): ProcessedRelayMessageDao

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
                    .addMigrations(
                        SEMANTIC_DEDUP_MIGRATION,
                        RELAY_PERSISTENCE_MIGRATION,
                        RELAY_DISPLAY_MIGRATION,
                        RELAY_PROCESSED_MIGRATION,
                        SOURCE_AWARE_DEDUP_MIGRATION
                    )
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
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE dedup_index ADD COLUMN semantic_key TEXT"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_dedup_semantic ON dedup_index(semantic_key) WHERE semantic_key IS NOT NULL"
                )
            }
        }

        /**
         * Adds the local relay queues without rebuilding any existing table. Message IDs are
         * primary keys so a repeated delivery is ignored atomically by the DAOs.
         */
        val RELAY_PERSISTENCE_MIGRATION = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS owner_outbound_queue (
                        message_id TEXT NOT NULL,
                        owner_id TEXT NOT NULL,
                        employee_id TEXT NOT NULL,
                        envelope TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        expires_at INTEGER,
                        status TEXT NOT NULL,
                        attempt_count INTEGER NOT NULL,
                        last_attempt_at INTEGER,
                        next_attempt_at INTEGER,
                        last_error TEXT,
                        delivered_at INTEGER,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY(message_id)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_owner_outbound_status_next_attempt " +
                        "ON owner_outbound_queue(status, next_attempt_at)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_owner_outbound_employee_status " +
                        "ON owner_outbound_queue(employee_id, status)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_owner_outbound_expires_at " +
                        "ON owner_outbound_queue(expires_at)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS received_announcements (
                        message_id TEXT NOT NULL,
                        owner_id TEXT NOT NULL,
                        employee_id TEXT NOT NULL,
                        envelope TEXT NOT NULL,
                        received_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        attempt_count INTEGER NOT NULL,
                        last_attempt_at INTEGER,
                        last_error TEXT,
                        processed_at INTEGER,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY(message_id)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_received_announcements_status " +
                        "ON received_announcements(status)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_received_announcements_expires_at " +
                        "ON received_announcements(expires_at)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_received_announcements_owner_received " +
                        "ON received_announcements(owner_id, received_at)"
                )
            }
        }

        /**
         * Adds the local-only decrypted display fields for received announcements and the peer
         * public key cache. Existing expiry and queue rows are preserved.
         */
        val RELAY_DISPLAY_MIGRATION = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE owner_outbound_queue ADD COLUMN payload TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL("ALTER TABLE received_announcements ADD COLUMN amount REAL")
                db.execSQL("ALTER TABLE received_announcements ADD COLUMN currency TEXT")
                db.execSQL("ALTER TABLE received_announcements ADD COLUMN merchant TEXT")
                db.execSQL("ALTER TABLE received_announcements ADD COLUMN bank_or_upi TEXT")
                db.execSQL("ALTER TABLE received_announcements ADD COLUMN event_at INTEGER")
                db.execSQL("ALTER TABLE received_announcements ADD COLUMN owner_label TEXT")
                db.execSQL(
                    "ALTER TABLE received_announcements " +
                        "ADD COLUMN decrypt_failed INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS relay_peer_keys (
                        device_id TEXT NOT NULL,
                        key_id TEXT NOT NULL,
                        public_key TEXT NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY(device_id)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_relay_peer_keys_updated_at " +
                        "ON relay_peer_keys(updated_at)"
                )
            }
        }

        /**
         * Adds the processed_relay_messages table for cross-channel deduplication.
         * This prevents duplicate announcements when both WebSocket Realtime and FCM
         * push deliver the same relay message.
         */
        val RELAY_PROCESSED_MIGRATION = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS processed_relay_messages (
                        message_id TEXT NOT NULL,
                        processed_at INTEGER NOT NULL,
                        PRIMARY KEY(message_id)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_processed_relay_processed_at " +
                        "ON processed_relay_messages(processed_at)"
                )
            }
        }

        /** Records which detector first observed each semantic key. */
        val SOURCE_AWARE_DEDUP_MIGRATION = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE dedup_index ADD COLUMN source TEXT")
            }
        }
    }
}
