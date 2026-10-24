package com.vivekray898.payalert.relay

import android.util.Log
import com.vivekray898.payalert.relay.RelayTrace.Stage
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** What one receive pass did, for diagnostics and the re-pair banner. */
data class RelayReceiveResult(
    val imported: Int = 0,
    val announced: Int = 0,
    val unreadable: Int = 0,
    val acknowledged: Int = 0,
    val needsRepair: Boolean = false
)

/**
 * Employee-side relay: claims forwarded envelopes, authenticates and decrypts them, stores the
 * payment locally, and announces it with an owner prefix.
 *
 * Failure handling is deliberately one-directional: an envelope that fails authentication is never
 * interpreted and never retried as plaintext. Its ciphertext is retained locally so a later
 * re-pairing can recover it, the row carries no payment detail, and the caller is told that a
 * re-pair is required. The server row is released either way, because the relay is a short-lived
 * hop and not a history store.
 */
class EmployeeReceiver(
    private val transport: RelayTransport,
    private val identity: RelayIdentity,
    private val peerKeys: RelayPeerKeyCache,
    private val received: ReceivedAnnouncementDao,
    private val announcementTemplate: () -> String,
    private val announce: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Local, already-authenticated user-visible notification; never called for bad ciphertext. */
    private val notify: (messageId: String, text: String) -> Unit = { _, _ -> },
    /** The stable client UUID follows the speech request; legacy callers still use [announce]. */
    private val announceRelay: (messageId: String, text: String) -> Unit = { _, text -> announce(text) },
    private val trace: RelayTrace = RelayTrace.process
) {

    /**
     * One receive pass: recover locally retained envelopes, then claim and process new ones.
     *
     * Throws only for a transport-level failure so the caller can back off; every per-message
     * problem is contained here.
     */
    suspend fun receive(context: RelayPairingContext): RelayReceiveResult {
        val owner = context.owner
        if (!transport.configured || !context.canReceive || owner == null) return RelayReceiveResult()
        val keyPair = identity.publish() ?: return RelayReceiveResult(needsRepair = true)
        var ownerKey = resolveOwnerKey(owner.deviceId)
            ?: return RelayReceiveResult(needsRepair = true)

        var refreshedOwnerKey = false
        suspend fun refreshOwnerKey(): RelayPublicKey? {
            if (refreshedOwnerKey) return ownerKey
            refreshedOwnerKey = true
            return resolveOwnerKey(owner.deviceId, forceRefresh = true)?.also { ownerKey = it }
        }

        var recovered = recoverRetained(context, keyPair, ownerKey)
        if (recovered.needsRepair) {
            // A key rotation invalidates the server binding while this device can still have the
            // old public key cached. Refresh once before asking the user to pair again.
            refreshOwnerKey()?.let { recovered = recoverRetained(context, keyPair, it) }
        }
        val now = clock()
        // A batch has no message IDs until fetch returns. Link its clocks to each returned envelope.
        val fetchId = UUID.randomUUID()
        trace.record(Stage.FETCH_START, fetchId = fetchId)
        val messages = try {
            transport.fetch(FETCH_LIMIT)
        } catch (cancelled: CancellationException) {
            trace.record(Stage.FETCH_CANCELLED, fetchId = fetchId)
            throw cancelled
        } catch (error: Exception) {
            trace.record(Stage.FETCH_FAILED, fetchId = fetchId)
            throw error
        }
        trace.record(Stage.FETCH_COMPLETE, fetchId = fetchId)
        var imported = 0
        var announced = 0
        var unreadable = 0
        var acknowledged = 0
        var needsRepair = false
        // Acknowledgements are collected and sent once the whole batch has been announced. An ack
        // is a network round trip, and doing it inline put one round trip between the
        // announcement of every message and the announcement of the next one in the batch.
        val pendingAcks = ArrayList<Pair<String, String>>(messages.size)
        for (message in messages) {
            trace.record(
                Stage.ENVELOPE_FETCHED,
                clientMessageId = message.messageId,
                serverMessageId = message.serverMessageId,
                fetchId = fetchId
            )
            if (received.byMessageId(message.messageId) == null) {
                var payload = decrypt(message, keyPair, ownerKey, context)
                if (payload == null) {
                    // Do not turn a stale cached key into a permanent re-pair loop. A single fresh
                    // server lookup is enough to recover after the owner reinstalls or rotates its
                    // relay identity, while malformed ciphertext still remains unreadable.
                    refreshOwnerKey()?.let { payload = decrypt(message, keyPair, it, context) }
                }
                if (payload == null) {
                    // Keep the ciphertext so a re-pair can still recover this payment, but store no
                    // payment detail and never guess at the contents.
                    trace.record(Stage.STORE_START, clientMessageId = message.messageId)
                    received.insert(
                        ReceivedAnnouncement(
                            messageId = message.messageId,
                            ownerId = owner.deviceId,
                            employeeId = context.localDeviceId,
                            envelope = message.envelope,
                            receivedAt = now,
                            status = RelayDeliveryStatus.FAILED,
                            ownerLabel = owner.label,
                            decryptFailed = true,
                            attemptCount = 1,
                            lastAttemptAt = now,
                            lastError = SAFE_DECRYPT_ERROR,
                            updatedAt = now
                        )
                    )
                    trace.record(Stage.STORE_COMPLETE, clientMessageId = message.messageId)
                    unreadable++
                    needsRepair = true
                } else {
                    trace.record(Stage.STORE_START, clientMessageId = message.messageId)
                    received.insert(pending(message, context, owner, payload, now))
                    trace.record(Stage.STORE_COMPLETE, clientMessageId = message.messageId)
                    imported++
                    if (received.claimForAnnouncement(message.messageId, now, now) == 1) {
                        val announceText = RelayAnnouncement.format(announcementTemplate(), payload)
                        notify(message.messageId, announceText)
                        trace.record(Stage.ANNOUNCEMENT_REQUESTED, clientMessageId = message.messageId)
                        announceRelay(message.messageId, announceText)
                        announced++
                    }
                }
            }
            // The acknowledgement is queued, not sent. See the batch release below.
            pendingAcks += message.serverMessageId to message.leaseToken
        }
        // One broken envelope must not strand the rest of the batch. The acknowledgements run
        // after every message in the batch has already been announced, so a slow ack can no
        // longer delay the announcement of a later message.
        for ((serverMessageId, leaseToken) in pendingAcks) {
            if (transport.ack(serverMessageId, leaseToken)) acknowledged++
        }
        return RelayReceiveResult(
            imported = imported + recovered.imported,
            announced = announced + recovered.announced,
            unreadable = unreadable,
            acknowledged = acknowledged,
            needsRepair = needsRepair || recovered.needsRepair
        )
    }

    /** Retries envelopes retained after an earlier failed decryption, e.g. after a re-pair. */
    private suspend fun recoverRetained(
        context: RelayPairingContext,
        keyPair: RelayKeyPair,
        ownerKey: RelayPublicKey
    ): RelayReceiveResult {
        val owner = context.owner ?: return RelayReceiveResult()
        val now = clock()
        var imported = 0
        var announced = 0
        var hadFailures = false
        for (row in received.unreadable(now, RECOVER_LIMIT)) {
            val payload = decryptFields(row.envelope, keyPair, ownerKey, context, row.messageId)
            if (payload == null) {
                hadFailures = true
                continue
            }
            trace.record(Stage.STORE_START, clientMessageId = row.messageId)
            received.delete(row.messageId)
            received.insert(
                ReceivedAnnouncement(
                    messageId = row.messageId,
                    ownerId = row.ownerId,
                    employeeId = row.employeeId,
                    envelope = "",
                    receivedAt = row.receivedAt,
                    expiresAt = row.expiresAt,
                    status = RelayDeliveryStatus.PENDING,
                    amount = payload.amount,
                    currency = payload.currency,
                    merchant = payload.merchant,
                    bankOrUpi = payload.bankOrUpi,
                    eventAt = payload.eventAt,
                    ownerLabel = row.ownerLabel ?: owner.label,
                    updatedAt = now
                )
            )
            trace.record(Stage.STORE_COMPLETE, clientMessageId = row.messageId)
            imported++
            if (received.claimForAnnouncement(row.messageId, now, now) == 1) {
                val announceText = RelayAnnouncement.format(announcementTemplate(), payload)
                notify(row.messageId, announceText)
                trace.record(Stage.ANNOUNCEMENT_REQUESTED, clientMessageId = row.messageId)
                announceRelay(row.messageId, announceText)
                announced++
            }
        }
        return RelayReceiveResult(
            imported = imported,
            announced = announced,
            needsRepair = hadFailures
        )
    }

    private fun pending(
        message: RelayInboxMessage,
        context: RelayPairingContext,
        owner: RelayRoute,
        payload: RelayPaymentPayload,
        now: Long
    ) = ReceivedAnnouncement(
        messageId = message.messageId,
        ownerId = owner.deviceId,
        employeeId = context.localDeviceId,
        envelope = "",
        receivedAt = now,
        status = RelayDeliveryStatus.PENDING,
        amount = payload.amount,
        currency = payload.currency,
        merchant = payload.merchant,
        bankOrUpi = payload.bankOrUpi,
        eventAt = payload.eventAt,
        ownerLabel = owner.label,
        updatedAt = now
    )

    private suspend fun resolveOwnerKey(
        ownerDeviceId: String,
        forceRefresh: Boolean = false
    ): RelayPublicKey? {
        val fetch: suspend () -> RelayPeerKeyInfo? = { transport.peerKey() }
        val info = if (forceRefresh) {
            peerKeys.refresh(ownerDeviceId, fetch)
        } else {
            peerKeys.resolve(ownerDeviceId, fetch)
        } ?: return null
        val parsed = try {
            RelayCrypto.parsePublicKey(info.publicKey)
        } catch (_: Exception) {
            peerKeys.forget(ownerDeviceId)
            return null
        }
        // A key id that does not match the cached metadata means the cached key is stale.
        return if (parsed.keyId == info.keyId) parsed else null
    }

    private fun decrypt(
        message: RelayInboxMessage,
        keyPair: RelayKeyPair,
        ownerKey: RelayPublicKey,
        context: RelayPairingContext
    ): RelayPaymentPayload? =
        decryptFields(message.envelope, keyPair, ownerKey, context, message.messageId)

    private fun decryptFields(
        envelope: String,
        keyPair: RelayKeyPair,
        ownerKey: RelayPublicKey,
        context: RelayPairingContext,
        messageId: String
    ): RelayPaymentPayload? {
        val ownerDeviceId = context.owner?.deviceId ?: return null
        trace.record(Stage.DECRYPT_START, clientMessageId = messageId)
        val bytes = try {
            RelayCrypto.decrypt(
                encodedEnvelope = envelope,
                recipient = keyPair,
                senderPublicKey = ownerKey,
                expectedOwnerId = ownerDeviceId,
                expectedEmployeeId = context.localDeviceId,
                expectedMessageId = messageId
            )
        } catch (_: Exception) {
            trace.record(Stage.DECRYPT_FAILED, clientMessageId = messageId)
            Log.w(TAG, "Relay envelope could not be authenticated")
            return null
        }
        return try {
            RelayPayloadCodec.decode(bytes).also {
                trace.record(Stage.DECRYPT_COMPLETE, clientMessageId = messageId)
            }
        } catch (_: Exception) {
            trace.record(Stage.DECRYPT_FAILED, clientMessageId = messageId)
            Log.w(TAG, "Relay payload could not be read")
            null
        }
    }

    companion object {
        private const val TAG = "PayAlert"

        private const val FETCH_LIMIT = 20
        private const val RECOVER_LIMIT = 20
        private const val SAFE_DECRYPT_ERROR = "decrypt_failed"
    }
}
