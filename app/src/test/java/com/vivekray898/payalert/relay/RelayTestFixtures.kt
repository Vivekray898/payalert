package com.vivekray898.payalert.relay

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** In-memory private key storage; the real one is Android Keystore backed. */
internal class InMemoryRelayPrivateKeyStorage : RelayPrivateKeyStorage {
    var stored: RelayKeyPair? = null
    var writes = 0

    override fun read(): RelayKeyPair? = stored

    override fun write(keyPair: RelayKeyPair) {
        stored = keyPair
        writes++
    }

    override fun clear() {
        stored = null
    }
}

internal class FakeRelayPeerKeyDao : RelayPeerKeyDao {
    val rows = linkedMapOf<String, RelayPeerKey>()

    override suspend fun byDeviceId(deviceId: String): RelayPeerKey? = rows[deviceId]

    override suspend fun upsert(peerKey: RelayPeerKey) {
        rows[peerKey.deviceId] = peerKey
    }

    override suspend fun delete(deviceId: String): Int =
        if (rows.remove(deviceId) != null) 1 else 0

    override suspend fun clear(): Int {
        val size = rows.size
        rows.clear()
        return size
    }
}

internal class FakeOwnerOutboundQueueDao : OwnerOutboundQueueDao {
    val rows = linkedMapOf<String, OwnerOutboundMessage>()

    override suspend fun insert(message: OwnerOutboundMessage) {
        rows.putIfAbsent(message.messageId, message)
    }

    override suspend fun byMessageId(messageId: String): OwnerOutboundMessage? = rows[messageId]

    override suspend fun due(now: Long, limit: Int): List<OwnerOutboundMessage> = rows.values
        .filter { it.status == RelayDeliveryStatus.PENDING || it.status == RelayDeliveryStatus.FAILED }
        .filter { it.nextAttemptAt == null || it.nextAttemptAt <= now }
        .filter { it.expiresAt == null || it.expiresAt > now }
        .sortedBy { it.createdAt }
        .take(limit)

    override suspend fun byStatus(status: String): List<OwnerOutboundMessage> =
        rows.values.filter { it.status == status }.sortedBy { it.createdAt }

    override suspend fun all(): List<OwnerOutboundMessage> = rows.values.sortedBy { it.createdAt }

    override suspend fun recordAttempt(
        messageId: String,
        status: String,
        attemptedAt: Long,
        nextAttemptAt: Long?,
        error: String?,
        updatedAt: Long
    ): Int {
        val row = rows[messageId] ?: return 0
        rows[messageId] = row.copy(
            status = status,
            attemptCount = row.attemptCount + 1,
            lastAttemptAt = attemptedAt,
            nextAttemptAt = nextAttemptAt,
            lastError = error,
            updatedAt = updatedAt
        )
        return 1
    }

    override suspend fun markDelivered(messageId: String, deliveredAt: Long, updatedAt: Long): Int {
        val row = rows[messageId] ?: return 0
        rows[messageId] = row.copy(
            status = RelayDeliveryStatus.DELIVERED,
            deliveredAt = deliveredAt,
            nextAttemptAt = null,
            lastError = null,
            updatedAt = updatedAt
        )
        return 1
    }

    override suspend fun deletePending(): Int {
        val doomed = rows.values.filter {
            it.status == RelayDeliveryStatus.PENDING || it.status == RelayDeliveryStatus.FAILED
        }
        doomed.forEach { rows.remove(it.messageId) }
        return doomed.size
    }

    override suspend fun deletePendingFor(employeeId: String): Int {
        val doomed = rows.values.filter {
            it.employeeId == employeeId &&
                (it.status == RelayDeliveryStatus.PENDING || it.status == RelayDeliveryStatus.FAILED)
        }
        doomed.forEach { rows.remove(it.messageId) }
        return doomed.size
    }

    override suspend fun deletePendingExcept(deviceIds: List<String>): Int {
        val doomed = rows.values.filter {
            it.employeeId !in deviceIds &&
                (it.status == RelayDeliveryStatus.PENDING || it.status == RelayDeliveryStatus.FAILED)
        }
        doomed.forEach { rows.remove(it.messageId) }
        return doomed.size
    }

    override fun observeLastDeliveredAt(): Flow<Long?> =
        flowOf(rows.values.filter { it.status == RelayDeliveryStatus.DELIVERED }
            .maxOfOrNull { it.updatedAt })

    override fun observeCount(): Flow<Int> = flowOf(rows.size)

    override fun observeCount(status: String): Flow<Int> =
        flowOf(rows.values.count { it.status == status })

    override fun observeDiagnostics(): Flow<List<RelayQueueDiagnostics>> = flowOf(diagnosticsList())

    override suspend fun diagnostics(): List<RelayQueueDiagnostics> = diagnosticsList()

    private fun diagnosticsList(): List<RelayQueueDiagnostics> = rows.values
        .groupBy { it.status }
        .map { (status, group) ->
            RelayQueueDiagnostics(
                status = status,
                rowCount = group.size,
                oldestCreatedAt = group.minOf { it.createdAt },
                latestUpdatedAt = group.maxOf { it.updatedAt }
            )
        }
        .sortedBy { it.status }
}

internal class FakeReceivedAnnouncementDao : ReceivedAnnouncementDao {
    val rows = linkedMapOf<String, ReceivedAnnouncement>()

    override suspend fun insert(announcement: ReceivedAnnouncement) {
        rows.putIfAbsent(announcement.messageId, announcement)
    }

    override suspend fun byMessageId(messageId: String): ReceivedAnnouncement? = rows[messageId]

    override suspend fun pending(now: Long, limit: Int): List<ReceivedAnnouncement> = rows.values
        .filter {
            (it.status == RelayDeliveryStatus.PENDING || it.status == RelayDeliveryStatus.FAILED) &&
                it.expiresAt > now
        }
        .sortedBy { it.receivedAt }
        .take(limit)

    override suspend fun unreadable(now: Long, limit: Int): List<ReceivedAnnouncement> = rows.values
        .filter { it.decryptFailed && it.envelope.isNotEmpty() && it.expiresAt > now }
        .sortedBy { it.receivedAt }
        .take(limit)

    override suspend fun byStatus(status: String): List<ReceivedAnnouncement> =
        rows.values.filter { it.status == status }.sortedBy { it.receivedAt }

    override suspend fun all(): List<ReceivedAnnouncement> = rows.values.sortedByDescending { it.receivedAt }

    override suspend fun delete(messageId: String): Int = if (rows.remove(messageId) != null) 1 else 0

    override suspend fun recordAttempt(
        messageId: String,
        status: String,
        attemptedAt: Long,
        error: String?,
        updatedAt: Long
    ): Int {
        val row = rows[messageId] ?: return 0
        rows[messageId] = row.copy(
            status = status,
            attemptCount = row.attemptCount + 1,
            lastAttemptAt = attemptedAt,
            lastError = error,
            updatedAt = updatedAt
        )
        return 1
    }

    override suspend fun markProcessed(messageId: String, processedAt: Long, updatedAt: Long): Int {
        val row = rows[messageId] ?: return 0
        rows[messageId] = row.copy(
            status = RelayDeliveryStatus.PROCESSED,
            processedAt = processedAt,
            lastError = null,
            updatedAt = updatedAt
        )
        return 1
    }

    override suspend fun claimForAnnouncement(messageId: String, processedAt: Long, updatedAt: Long): Int {
        val row = rows[messageId] ?: return 0
        if (row.status != RelayDeliveryStatus.PENDING) return 0
        rows[messageId] = row.copy(
            status = RelayDeliveryStatus.PROCESSED,
            processedAt = processedAt,
            lastError = null,
            updatedAt = updatedAt
        )
        return 1
    }

    override suspend fun deleteExpired(now: Long): Int {
        val doomed = rows.values.filter { it.expiresAt <= now }
        doomed.forEach { rows.remove(it.messageId) }
        return doomed.size
    }

    override suspend fun deleteForOwner(ownerId: String): Int {
        val doomed = rows.values.filter { it.ownerId == ownerId }
        doomed.forEach { rows.remove(it.messageId) }
        return doomed.size
    }

    override suspend fun decryptFailureCount(now: Long): Int =
        rows.values.count { it.decryptFailed && it.expiresAt > now }

    override fun observeCount(): Flow<Int> = flowOf(rows.size)

    override fun observeDiagnostics(): Flow<List<RelayQueueDiagnostics>> = flowOf(emptyList())

    override suspend fun diagnostics(): List<RelayQueueDiagnostics> = emptyList()
}

/** Scriptable relay transport. Records what was sent and when. */
internal class FakeRelayTransport(
    override var configured: Boolean = true
) : RelayTransport {
    val registeredKeys = mutableListOf<Pair<String, String>>()
    val enqueued = mutableListOf<Triple<String, String, String>>()
    val acks = mutableListOf<Pair<String, String>>()
    var peerKeys = linkedMapOf<String, RelayPeerKeyInfo>()
    var inbox = mutableListOf<RelayInboxMessage>()
    var registerFailure: Exception? = null
    var bindFailure: Exception? = null
    var peerKeyFailure: Exception? = null
    var enqueueFailure: Exception? = null
    var fetchFailure: Exception? = null
    var cleanupCount = 0

    override suspend fun registerKey(keyId: String, publicKey: String) {
        registerFailure?.let { throw it }
        registeredKeys += keyId to publicKey
    }

    override suspend fun bindPeerKey(employeeDeviceId: String, employeeKeyId: String?): RelayPeerKeyInfo {
        bindFailure?.let { throw it }
        return peerKeys[employeeDeviceId]
            ?: throw RelayKeyNotBoundException("no peer key for $employeeDeviceId")
    }

    override suspend fun peerKey(): RelayPeerKeyInfo? {
        peerKeyFailure?.let { throw it }
        return peerKeys.values.firstOrNull()
    }

    override suspend fun enqueue(
        employeeDeviceId: String,
        clientMessageId: String,
        envelope: String,
        ttlSeconds: Long
    ) {
        enqueueFailure?.let { throw it }
        // The real relay_enqueue refuses an envelope addressed to a retired key, because rotating a
        // device key deletes its peer bindings. Modelling that here keeps the rotation path honest.
        val bound = peerKeys[employeeDeviceId]
        if (bound == null) throw RelayKeyNotBoundException("key_not_bound")
        if (RelayCrypto.parseEnvelope(envelope).recipientKeyId != bound.keyId) {
            throw RelayKeyNotBoundException("employee_key_not_current")
        }
        enqueued += Triple(employeeDeviceId, clientMessageId, envelope)
    }

    override suspend fun fetch(limit: Int): List<RelayInboxMessage> {
        fetchFailure?.let { throw it }
        return inbox.take(limit)
    }

    override suspend fun ack(serverMessageId: String, leaseToken: String): Boolean {
        acks += serverMessageId to leaseToken
        return true
    }

    override suspend fun cleanupExpired(): Int {
        cleanupCount++
        return cleanupCount
    }
}
