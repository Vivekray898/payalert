package com.vivekray898.payalert.relay

import android.util.Log
import com.vivekray898.payalert.data.PaymentEvent
import java.nio.charset.StandardCharsets.UTF_8

/**
 * Owner-side relay: turns an accepted local payment into encrypted envelopes for every paired
 * employee device and delivers them with backoff.
 *
 * Three properties matter and are enforced here rather than in the UI:
 *  - only credits are ever forwarded ([RelayPaymentPayload.from] returns null for a debit);
 *  - the same event is never enqueued twice, because the message id is derived from the event's
 *    dedup key and the route, and insertion ignores an existing row;
 *  - nothing is transmitted as plaintext; the queue stores an envelope and the payload is only
 *    materialised to (re-)encrypt.
 */
class OwnerForwarder(
    private val transport: RelayTransport,
    private val identity: RelayIdentity,
    private val queue: OwnerOutboundQueueDao,
    private val peerKeys: RelayPeerKeyCache,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Raised when a peer key can no longer be resolved, which needs a re-pair. */
    private val onKeyNotBound: () -> Unit = {},
    /** Raised after a failed delivery attempt so diagnostics stay current. */
    private val onAttemptFailed: () -> Unit = {}
) {

    /**
     * Queues [event] for every employee route and returns how many rows were newly queued.
     *
     * Called from the detection path, so it never throws: a relay problem must not affect local
     * announcement, storage, or the cashbook.
     */
    suspend fun offer(context: RelayPairingContext, event: PaymentEvent): Int {
        if (!transport.configured || !context.canForward) return 0
        val payload = RelayPaymentPayload.from(event) ?: return 0
        val bytes = try {
            RelayPayloadCodec.encode(payload)
        } catch (_: Exception) {
            return 0
        }
        val keyPair = try {
            identity.publish() ?: return 0
        } catch (_: Exception) {
            return 0
        }
        val now = clock()
        val text = String(bytes, UTF_8)
        var queued = 0
        for (employee in context.employees) {
            val messageId = RelayMessageId.forEvent(
                context.localDeviceId, employee.deviceId, event.dedupKey
            )
            if (queue.byMessageId(messageId) != null) continue
            // A peer key that cannot be resolved yet is not fatal: the payload is queued and the
            // send attempt re-encrypts, so an owner that is briefly offline still forwards.
            val envelope = encrypt(context, employee.deviceId, messageId, bytes, keyPair).orEmpty()
            queue.insert(
                OwnerOutboundMessage(
                    messageId = messageId,
                    ownerId = context.localDeviceId,
                    employeeId = employee.deviceId,
                    envelope = envelope,
                    payload = text,
                    createdAt = now,
                    status = RelayDeliveryStatus.PENDING,
                    updatedAt = now
                )
            )
            queued++

            // Log the enqueue event for latency measurement
            android.util.Log.i(RELAY_TAG, "owner_enqueued message_id=$messageId t=$now")
        }
        return queued
    }

    /**
     * Attempts every due queued message.
     *
     * Rows are re-encrypted on each attempt, so an employee key rotation between the first attempt
     * and a retry is recovered by re-binding and encrypting to the new key instead of dropping the
     * payment.
     */
    suspend fun flush(context: RelayPairingContext): Int {
        if (!transport.configured || !context.canForward) return 0
        val keyPair = try {
            identity.publish() ?: return 0
        } catch (_: Exception) {
            return 0
        }
        val now = clock()
        val due = queue.due(now, MAX_PER_FLUSH)
        if (due.isEmpty()) return 0
        val routes = context.employees.associateBy { it.deviceId }
        var delivered = 0
        for (row in due) {
            if (routes[row.employeeId] == null) {
                // The employee was removed while this message was queued; drop it quietly.
                queue.recordAttempt(
                    row.messageId,
                    RelayDeliveryStatus.EXPIRED,
                    now,
                    null,
                    null,
                    now
                )
                continue
            }
            val payload = decodeOrNull(row.payload)
            if (payload == null) {
                queue.recordAttempt(
                    row.messageId,
                    RelayDeliveryStatus.EXPIRED,
                    now,
                    null,
                    SAFE_PAYLOAD_ERROR,
                    now
                )
                continue
            }
            val bytes = try {
                RelayPayloadCodec.encode(payload)
            } catch (_: Exception) {
                queue.recordAttempt(
                    row.messageId,
                    RelayDeliveryStatus.EXPIRED,
                    now,
                    null,
                    SAFE_PAYLOAD_ERROR,
                    now
                )
                continue
            }
            var result = sendOnce(context, row, bytes, keyPair)
            if (result == SendResult.KEY_NOT_BOUND) {
                // The peer rotated, or the binding was dropped. Discard the cached key and try once
                // more immediately, so a rotation costs one retry instead of the whole backoff.
                peerKeys.forget(row.employeeId)
                result = sendOnce(context, row, bytes, keyPair)
            }
            when (result) {
                SendResult.DELIVERED -> {
                    queue.markDelivered(row.messageId, now, now)
                    delivered++
                }

                else -> {
                    if (result == SendResult.KEY_NOT_BOUND) onKeyNotBound()
                    val attempts = row.attemptCount + 1
                    queue.recordAttempt(
                        row.messageId,
                        if (RelayRetryPolicy.canRetry(attempts)) {
                            RelayDeliveryStatus.FAILED
                        } else {
                            RelayDeliveryStatus.EXPIRED
                        },
                        now,
                        RelayRetryPolicy.nextAttemptAt(now, attempts),
                        if (result == SendResult.KEY_NOT_BOUND) SAFE_KEY_ERROR else SAFE_DELIVERY_ERROR,
                        now
                    )
                    onAttemptFailed()
                }
            }
        }
        return delivered
    }

    /** Drops everything not yet delivered. Used when the pairing ends. */
    suspend fun dropPending(): Int = queue.deletePending()

    suspend fun dropPendingFor(employeeDeviceId: String): Int =
        queue.deletePendingFor(employeeDeviceId)

    /** Removes queued work for devices that are no longer paired. */
    suspend fun dropPendingExcept(deviceIds: List<String>): Int =
        if (deviceIds.isEmpty()) queue.deletePending() else queue.deletePendingExcept(deviceIds)

    /** One encryption + send attempt. Never throws for a peer-key problem; reports it instead. */
    private suspend fun sendOnce(
        context: RelayPairingContext,
        row: OwnerOutboundMessage,
        payload: ByteArray,
        keyPair: RelayKeyPair
    ): SendResult {
        val envelope = encrypt(context, row.employeeId, row.messageId, payload, keyPair)
            ?: return SendResult.KEY_NOT_BOUND
        return try {
            transport.enqueue(
                employeeDeviceId = row.employeeId,
                clientMessageId = row.messageId,
                envelope = envelope,
                ttlSeconds = ENVELOPE_TTL_SECONDS
            )
            // The server accepted the envelope. Together with the employee's
            // "employee_fetched" line for the same message id, this brackets the
            // part of the delay that the relay server is responsible for.
            android.util.Log.i(
                RELAY_TAG,
                "owner_sent message_id=${row.messageId} t=${System.currentTimeMillis()}"
            )
            SendResult.DELIVERED
        } catch (notBound: RelayKeyNotBoundException) {
            SendResult.KEY_NOT_BOUND
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SendResult.FAILED
        }
    }

    private enum class SendResult { DELIVERED, KEY_NOT_BOUND, FAILED }

    private suspend fun encrypt(
        context: RelayPairingContext,
        employeeDeviceId: String,
        messageId: String,
        payload: ByteArray,
        keyPair: RelayKeyPair
    ): String? {
        val peer = peerKeys.resolve(employeeDeviceId) {
            try {
                transport.bindPeerKey(employeeDeviceId)
            } catch (_: Exception) {
                null
            }
        } ?: return null
        val publicKey = try {
            RelayCrypto.parsePublicKey(peer.publicKey)
        } catch (_: Exception) {
            peerKeys.forget(employeeDeviceId)
            return null
        }
        return try {
            RelayCrypto.encrypt(
                ownerId = context.localDeviceId,
                employeeId = employeeDeviceId,
                messageId = messageId,
                plaintext = payload,
                sender = keyPair,
                recipient = publicKey
            )
        } catch (_: Exception) {
            Log.w(TAG, "Relay envelope could not be created")
            null
        }
    }

    private fun decodeOrNull(text: String): RelayPaymentPayload? = try {
        if (text.isBlank()) null else RelayPayloadCodec.decode(text.toByteArray(UTF_8))
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val TAG = "PayAlert"

        /** Log tag for the latency trail shared with the employee device. */
        private const val RELAY_TAG = "PayAlertRelay"
        private const val MAX_PER_FLUSH = 20

        /** Matches the server default so an undelivered envelope expires on both sides together. */
        const val ENVELOPE_TTL_SECONDS = 86_400L

        private const val SAFE_DELIVERY_ERROR = "delivery_failed"
        private const val SAFE_KEY_ERROR = "key_not_bound"
        private const val SAFE_PAYLOAD_ERROR = "payload_unavailable"
    }
}
