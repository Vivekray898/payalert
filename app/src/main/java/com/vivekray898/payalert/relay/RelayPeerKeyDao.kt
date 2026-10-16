package com.vivekray898.payalert.relay

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RelayPeerKeyDao {

    @Query("SELECT * FROM relay_peer_keys WHERE device_id = :deviceId")
    suspend fun byDeviceId(deviceId: String): RelayPeerKey?

    /** One row per device: a re-bound or rotated key replaces the cached one. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(peerKey: RelayPeerKey)

    @Query("DELETE FROM relay_peer_keys WHERE device_id = :deviceId")
    suspend fun delete(deviceId: String): Int

    @Query("DELETE FROM relay_peer_keys")
    suspend fun clear(): Int
}
