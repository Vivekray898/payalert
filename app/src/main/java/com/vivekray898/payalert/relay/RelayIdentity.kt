package com.vivekray898.payalert.relay

import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

/**
 * Owns this installation's X25519 relay identity.
 *
 * The private half never leaves the device: it is generated here, handed to
 * [AndroidKeystoreRelayPrivateKeyStorage], and only ever materialised in memory for a relay
 * operation. Only the public half is published.
 */
class RelayIdentity(
    private val storage: RelayPrivateKeyStorage,
    private val transport: RelayTransport,
    private val newKeyId: () -> String = { UUID.randomUUID().toString() }
) {

    /** The stored identity, or null when none is stored or the stored one cannot be read. */
    fun current(): RelayKeyPair? = try {
        storage.read()
    } catch (_: RelayCryptoException) {
        null
    }

    /**
     * The stored identity, generating a fresh one when none is usable.
     *
     * A key that the platform can no longer unwrap is replaced rather than blocking detection:
     * the relayable window is 24 hours, so an older identity buys almost nothing and a permanent
     * failure would be worse.
     */
    fun loadOrCreate(): RelayKeyPair = current() ?: generate()

    fun generate(): RelayKeyPair =
        RelayCrypto.generateKeyPair(newKeyId()).also { storage.write(it) }

    /** Replaces the identity. Called when a pairing ends so the next pairing starts fresh. */
    fun rotate(): RelayKeyPair {
        storage.clear()
        return generate()
    }

    fun clear() = storage.clear()

    /** Publishes the current public key. Returns null when the build has no team configuration. */
    suspend fun publish(): RelayKeyPair? {
        if (!transport.configured) return null
        val keyPair = loadOrCreate()
        transport.registerKey(keyPair.keyId, keyPair.publicKey.encoded)
        return keyPair
    }
}

/**
 * The most recent public key seen for each paired peer.
 *
 * Caching avoids a binding round trip per payment while still detecting rotation: the cached key id
 * is compared with the one the server reports before a send, and a mismatch forces a re-bind and a
 * re-encryption instead of encrypting to a key the peer has already retired.
 */
class RelayPeerKeyCache(
    private val dao: RelayPeerKeyDao,
    private val clock: () -> Long = System::currentTimeMillis
) {

    suspend fun cached(deviceId: String): RelayPeerKeyInfo? {
        val stored = dao.byDeviceId(deviceId) ?: return null
        return try {
            val parsed = RelayCrypto.parsePublicKey(stored.publicKey)
            if (parsed.keyId != stored.keyId) return null
            RelayPeerKeyInfo(stored.deviceId, stored.keyId, stored.publicKey)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun store(info: RelayPeerKeyInfo) {
        dao.upsert(
            RelayPeerKey(
                deviceId = info.deviceId,
                keyId = info.keyId,
                publicKey = info.publicKey,
                updatedAt = clock()
            )
        )
    }

    suspend fun forget(deviceId: String) {
        dao.delete(deviceId)
    }

    suspend fun clear() {
        dao.clear()
    }

    /** Resolves a peer key, using the cache and consulting [fetch] when the cached key is gone. */
    suspend fun resolve(
        deviceId: String,
        fetch: suspend () -> RelayPeerKeyInfo?
    ): RelayPeerKeyInfo? {
        cached(deviceId)?.let { return it }
        val fetched = fetch() ?: return null
        store(fetched)
        return fetched
    }

    /** Drops the cached key and resolves it again, after a binding change was detected. */
    suspend fun refresh(
        deviceId: String,
        fetch: suspend () -> RelayPeerKeyInfo?
    ): RelayPeerKeyInfo? {
        forget(deviceId)
        return resolve(deviceId, fetch)
    }
}

/**
 * Stable, deterministic relay message id for one payment event on one owner -> employee route.
 *
 * Determinism is what makes the queue, the server's idempotency key, and the employee's primary key
 * agree: the same payment detected twice, or a delivery retried after a restart, reuses one id and
 * is therefore accepted once and announced once.
 */
object RelayMessageId {
    fun forEvent(ownerDeviceId: String, employeeDeviceId: String, dedupKey: String): String =
        UUID.nameUUIDFromBytes(
            "payalert-relay-v1\n$ownerDeviceId\n$employeeDeviceId\n$dedupKey".toByteArray(UTF_8)
        ).toString()
}
