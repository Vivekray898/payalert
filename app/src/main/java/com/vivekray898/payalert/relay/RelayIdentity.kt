package com.vivekray898.payalert.relay

import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val clock: () -> Long = System::currentTimeMillis,
    private val newKeyId: () -> String = { UUID.randomUUID().toString() }
) {

    /**
     * The key id the server last accepted, and when it was published.
     *
     * Publishing is idempotent on the server, but it is still a full network round trip, and the
     * relay publishes on every forward attempt and on every receive pass. Remembering the
     * published key turns those into a local check, which is what keeps the relay's per-payment
     * cost down to a single round trip instead of three.
     *
     * The re-publish interval keeps the binding recoverable: if the server loses the key (a
     * restored database, a rotated binding), the next publish after the interval registers it
     * again instead of waiting for a key change that may never come.
     */
    private val publishLock = Mutex()

    @Volatile
    private var publishedKeyId: String? = null

    @Volatile
    private var publishedKey: String? = null

    @Volatile
    private var publishedAt: Long = 0L

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
        forgetPublished()
        return generate()
    }

    fun clear() {
        storage.clear()
        forgetPublished()
    }

    /**
     * Publishes the current public key. Returns null when the build has no team configuration.
     *
     * The server call is skipped while the published key is still current, so the common case
     * costs no network at all.
     */
    suspend fun publish(): RelayKeyPair? {
        if (!transport.configured) return null
        return publishLock.withLock {
            val keyPair = loadOrCreate()
            val encoded = keyPair.publicKey.encoded
            val now = clock()
            if (!isPublished(keyPair.keyId, encoded, now)) {
                transport.registerKey(keyPair.keyId, encoded)
                publishedKeyId = keyPair.keyId
                publishedKey = encoded
                publishedAt = now
            }
            keyPair
        }
    }

    /**
     * Whether the server already holds this exact key and the binding may still be trusted.
     *
     * Both the key id and the key material are compared: a rotation can reuse an id, and the
     * server must never be left holding a key that no longer matches this device's private half.
     */
    private fun isPublished(keyId: String, encoded: String, now: Long): Boolean =
        publishedKeyId == keyId &&
            publishedKey == encoded &&
            now - publishedAt < REPUBLISH_INTERVAL_MILLIS

    /** Drops the memo so the next publish talks to the server again. */
    private fun forgetPublished() {
        publishedKeyId = null
        publishedKey = null
        publishedAt = 0L
    }

    private companion object {
        /** How long a published key is trusted before it is registered again. */
        const val REPUBLISH_INTERVAL_MILLIS = 5L * 60_000L
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
