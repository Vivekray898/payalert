package com.vivekray898.payalert.dedup

import android.content.Context
import android.arch.persistence.room.Database
import android.arch.persistence.room.migration.Migration
import android.arch.persistence.room.Room
import android.arch.persistence.room.RoomDatabase
import com.vivekray898.payalert.data.model.DedupEntry

/**
 * Room-backed persistent dedup index.
 * Schema version 1 has one table: dedup_index(dedup_key TEXT PRIMARY KEY, seen_at TEXT).
 */
@Database(
    entities = [DedupEntry::class],
    version = 1,
    exportSchema = false,
    migrations = [Migration(1L) { from, to ->
        // No-op: table creation is handled by @Entity on first run.
    }]
)
abstract class DedupDatabase : RoomDatabase() {

    abstract fun dedupDao(): DedupDao

    companion object {
        @Volatile
        private var INSTANCE: DedupDatabase? = null

        fun getInstance(context: Context): DedupDatabase {
            return INSTANCE ?: synchronized(this) {
                val db = Room.databaseBuilder(
                    context.applicationContext,
                    DedupDatabase::class.java,
                    "payalert_dedup"
                .build()
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = db
                db
            }
        }
    }
}

/** Simple entity for the dedup key + timestamp. */
@Entity(tableName = "dedup_index", primaryKey = ["dedup_key"])
data class DedupEntry(
    @ColumnInfo(name = "dedup_key") val dedupKey: String,
    @ColumnInfo(name = "seen_at") val seenAt: String
)

@Dao
interface DedupDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: DedupEntry)

    @Query("SELECT * FROM dedup_index WHERE dedup_key = :key")
    suspend fun isSeen(key: String): Boolean

    @Query("DELETE FROM dedup_index")
    suspend fun clearAll()
}