package com.vivekray898.payalert.relay

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The most recently accepted public key of a paired peer device.
 *
 * Only public keys are cached. They are encrypted-at-rest nowhere because they are already public
 * metadata, but they are kept out of logs and never mixed with the local private key.
 *
 * The cached key id is compared against the key id bound by the server before each send, so a
 * rotation on either side is detected and re-bound instead of silently encrypting to a stale key.
 */
@Entity(
    tableName = "relay_peer_keys",
    indices = [Index(value = ["updated_at"], name = "idx_relay_peer_keys_updated_at")]
)
data class RelayPeerKey(
    @PrimaryKey
    @ColumnInfo(name = "device_id")
    val deviceId: String,

    @ColumnInfo(name = "key_id")
    val keyId: String,

    /** Versioned base64url X25519 public key produced by [RelayCrypto.encodePublicKey]. */
    @ColumnInfo(name = "public_key")
    val publicKey: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
