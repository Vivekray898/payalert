package com.vivekray898.payalert.relay

import android.util.Log

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
    private val notify: (messageId: String, text: String) -> Unit = { _, _ -> }
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
        val ownerKey = resolveOwnerKey(owner.deviceId)
            ?: return RelayReceiveResult(needsRepair = true)

        val recovered = recoverRetained(context, keyPair, ownerKey)
        val now = clock()
        val messages = transport.fetch(FETCH_LIMIT)
        var imported = 0
        var announced = 0
        var unreadable = 0
        var acknowledged = 0
        var needsRepair = false
        for (message in messages) {
            if (received.byMessageId(message.messageId) == null) {
                // Log when we receive a relay message for latency measurement
                android.util.Log.i("PayAlertRelay", "employee_ws_received message_id=${message.messageId} t=${now}")

                val payload = decrypt(message, keyPair, ownerKey, context)
                if (payload == null) {
                    // Keep the ciphertext so a re-pair can still recover this payment, but store no
                    // payment detail and never guess at the contents.
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
                    unreadable++
                    needsRepair = true
                } else {
                    received.insert(pending(message, context, owner, payload, now))
                    imported++
                    if (received.claimForAnnouncement(message.messageId, now, now) == 1) {
                        // Log the announcement for latency measurement
                        val announceText = RelayAnnouncement.format(announcementTemplate(), payload)
                        android.util.Log.i("PayAlertRelay", "employee_announced message_id=${message.messageId} t=${now} text=$announceText")
                        notify(message.messageId, announceText)
                        announce(announceText)
                        announced++
                    }
                }
            }
            // One broken envelope must not strand the rest of the batch.
            if (transport.ack(message.serverMessageId, message.leaseToken)) acknowledged++
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
        for (row in received.unreadable(now, RECOVER_LIMIT)) {
            val payload = decryptFields(row.envelope, keyPair, ownerKey, context, row.messageId) ?: continue
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
            imported++
            if (received.claimForAnnouncement(row.messageId, now, now) == 1) {
                val announceText = RelayAnnouncement.format(announcementTemplate(), payload)
                notify(row.messageId, announceText)
                announce(announceText)
                announced++
            }
        }
        return RelayReceiveResult(imported = imported, announced = announced)
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

    private suspend fun resolveOwnerKey(ownerDeviceId: String): RelayPublicKey? {
        val info = peerKeys.resolve(ownerDeviceId) {
            try {
                transport.peerKey()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
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
            Log.w(TAG, "Relay envelope could not be authenticated")
            return null
        }
        return try {
            RelayPayloadCodec.decode(bytes)
        } catch (_: Exception) {
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
