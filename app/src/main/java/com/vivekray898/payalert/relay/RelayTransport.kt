package com.vivekray898.payalert.relay

/** A safe, caller-facing relay transport failure. Messages never carry payload content. */
open class RelayTransportException(message: String) : Exception(message)

/** Raised when the server has no usable key binding for the peer, so a re-pair is required. */
class RelayKeyNotBoundException(message: String) : RelayTransportException(message)

/** A peer device's current registered public key. */
data class RelayPeerKeyInfo(
    val deviceId: String,
    val keyId: String,
    /** Versioned base64url encoding accepted by [RelayCrypto.parsePublicKey]. */
    val publicKey: String
)

/** One leased envelope waiting on the employee device. */
data class RelayInboxMessage(
    /** Server row id, needed only to acknowledge the lease. */
    val serverMessageId: String,
    /** Sender's idempotency key; also the relay message id used inside the envelope. */
    val messageId: String,
    val envelope: String,
    val leaseToken: String
)

/**
 * The short-lived relay the clients use to move opaque envelopes.
 *
 * The transport is deliberately payload-blind: it accepts and returns encoded envelopes only, so
 * no caller can accidentally send a decrypted payment detail to the server. Implementations must
 * never log envelope contents or key material.
 */
interface RelayTransport {

    /** False when the build has no Supabase configuration; every call is then skipped. */
    val configured: Boolean

    /**
     * Publishes this installation's current public key. Idempotent for an unchanged key, so it is
     * safe to call on every app open. Private key material is never passed in or returned.
     */
    suspend fun registerKey(keyId: String, publicKey: String)

    /**
     * Owner only. Binds the employee's current public key to the pairing and returns it so the
     * owner can encrypt. Rotating either device's key invalidates the binding.
     */
    suspend fun bindPeerKey(employeeDeviceId: String, employeeKeyId: String? = null): RelayPeerKeyInfo

    /**
     * Employee only. Returns the bound owner key, or null when no current binding exists (which
     * means a re-pair is required before anything can be decrypted).
     */
    suspend fun peerKey(): RelayPeerKeyInfo?

    /** Owner only. Idempotent per `(pairing, clientMessageId)`. */
    suspend fun enqueue(
        employeeDeviceId: String,
        clientMessageId: String,
        envelope: String,
        ttlSeconds: Long
    )

    /** Employee only. Claims up to [limit] unexpired, unleased envelopes. */
    suspend fun fetch(limit: Int): List<RelayInboxMessage>

    /** Employee only. Deletes the row only while the matching lease is still live. */
    suspend fun ack(serverMessageId: String, leaseToken: String): Boolean

    /** Removes expired envelopes so the relay stays short-lived. Returns the deleted count. */
    suspend fun cleanupExpired(): Int
}

/** A transport for builds with no team configuration and for tests that must not reach a network. */
object UnconfiguredRelayTransport : RelayTransport {
    override val configured: Boolean = false
    override suspend fun registerKey(keyId: String, publicKey: String) = Unit
    override suspend fun bindPeerKey(employeeDeviceId: String, employeeKeyId: String?): RelayPeerKeyInfo =
        throw RelayKeyNotBoundException("Team pairing is not configured in this build.")

    override suspend fun peerKey(): RelayPeerKeyInfo? = null
    override suspend fun enqueue(
        employeeDeviceId: String,
        clientMessageId: String,
        envelope: String,
        ttlSeconds: Long
    ) = Unit

    override suspend fun fetch(limit: Int): List<RelayInboxMessage> = emptyList()
    override suspend fun ack(serverMessageId: String, leaseToken: String): Boolean = false
    override suspend fun cleanupExpired(): Int = 0
}
