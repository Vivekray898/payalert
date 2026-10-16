package com.vivekray898.payalert.relay

import android.util.Log
import com.vivekray898.payalert.data.PaymentEvent
import com.vivekray898.payalert.role.LocalDeviceRole
import com.vivekray898.payalert.team.TeamSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Forwarding state shown in Settings and on the team screen. Never contains payload content. */
data class RelayStatus(
    val role: LocalDeviceRole = LocalDeviceRole.OWNER,
    val paired: Boolean = false,
    val pendingDeliveries: Int = 0,
    val failedDeliveries: Int = 0,
    val lastForwardedAt: Long? = null,
    val receivedCount: Int = 0,
    val needsRepair: Boolean = false,
    val lastError: String? = null
)

/**
 * Coordinates the encrypted relay.
 *
 * The engine holds the current pairing context, publishes this device's identity, reacts to a
 * pairing ending, and runs the foreground delivery loop. It is the only component that knows both
 * the pairing metadata and the relay queues; the forwarder and receiver stay single-purpose.
 *
 * Nothing here announces or stores on the local detection path: [onStored] is called after a
 * payment was already stored and announced by [com.vivekray898.payalert.pipeline.PaymentPipeline],
 * so a relay failure can never affect local behaviour.
 */
class RelayEngine(
    private val transport: RelayTransport,
    private val identity: RelayIdentity,
    private val peerKeys: RelayPeerKeyCache,
    private val forwarder: OwnerForwarder,
    private val receiver: EmployeeReceiver,
    private val outbound: OwnerOutboundQueueDao,
    private val received: ReceivedAnnouncementDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope
) {

    private val _status = MutableStateFlow(RelayStatus())
    val status: StateFlow<RelayStatus> = _status.asStateFlow()

    private val mutex = Mutex()

    /**
     * Serialises delivery. The foreground loop and the periodic worker can fire together, and a
     * double pass would only waste RPCs: claiming, acknowledging and announcing are already
     * idempotent, but there is no reason to pay for them twice.
     */
    private val deliveryMutex = Mutex()
    private var context: RelayPairingContext? = null
    private var loop: Job? = null

    /** Current pairing context, or null when unpaired. Read by screens that show forwarding state. */
    fun pairingContext(): RelayPairingContext? = context

    /**
     * Applies a fresh team snapshot.
     *
     * A pairing that ends is handled here: queued work is dropped, the peer key is forgotten, a
     * removed owner's announcements are purged, and the identity is rotated so a future pairing
     * derives a new shared secret.
     */
    suspend fun onPairingChanged(snapshot: TeamSnapshot?, role: LocalDeviceRole) {
        mutex.withLock {
            val next = snapshot?.let { RelayPairingContext.from(it, role) }
            val previous = context
            context = next
            if (next == null) {
                updateStatus { copy(paired = false, role = role) }
                if (previous != null) endPairing(previous)
                return
            }
            if (previous != null) {
                val leftTeam = previous.role == LocalDeviceRole.EMPLOYEE &&
                    next.role == LocalDeviceRole.OWNER
                val removedOwner = previous.owner?.deviceId
                if (leftTeam && removedOwner != null) {
                    purgeOwner(removedOwner)
                    endPairing(previous)
                } else if (next.role == LocalDeviceRole.OWNER) {
                    forgetUnpairedEmployees(previous, next)
                }
            }
            if (next.canForward || next.canReceive) {
                try {
                    identity.publish()
                    updateStatus { copy(needsRepair = false, lastError = null) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    updateStatus { copy(lastError = SAFE_OFFLINE_ERROR) }
                }
            }
            updateStatus { copy(paired = next.canForward || next.canReceive, role = next.role) }
            refreshStatus()
        }
    }

    /** Called after a payment is stored locally. Only the owner forwards, and only credits. */
    fun onStored(event: PaymentEvent) {
        if (event.kind != RelayPaymentPayload.CREDIT) return
        val current = context ?: return
        if (!current.canForward || !transport.configured) return
        scope.launch {
            try {
                val queued = forwarder.offer(current, event)
                if (queued > 0) {
                    forwarder.flush(current)
                    refreshStatus()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Log.w(TAG, "Relay forwarding failed")
                updateStatus { copy(lastError = SAFE_DELIVERY_ERROR) }
            }
        }
    }

    /** One delivery pass; also run on app open so a queued payment is not stranded. */
    suspend fun pump() {
        val current = context ?: return
        if (!transport.configured) return
        if (deliveryMutex.isLocked) return
        deliveryMutex.withLock {
            pumpLocked(current)
        }
    }

    private suspend fun pumpLocked(current: RelayPairingContext) {
        try {
            if (current.canForward) {
                forwarder.flush(current)
            }
            if (current.canReceive) {
                val result = receiver.receive(current)
                if (result.needsRepair) {
                    updateStatus { copy(needsRepair = true) }
                }
            }
            transport.cleanupExpired()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            updateStatus { copy(lastError = SAFE_OFFLINE_ERROR) }
        }
        refreshStatus()
    }

    /** Starts the foreground delivery loop. Safe to call more than once. */
    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                pump()
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
    }

    /** Recomputes the counters a screen displays. */
    suspend fun refreshStatus() {
        val deliveredAt = try {
            outbound.diagnostics().firstOrNull { it.status == RelayDeliveryStatus.DELIVERED }
                ?.latestUpdatedAt
        } catch (_: Exception) {
            null
        }
        val pending = countStatus(RelayDeliveryStatus.PENDING) +
            countStatus(RelayDeliveryStatus.IN_FLIGHT)
        val failed = countStatus(RelayDeliveryStatus.FAILED)
        val receivedCount = try {
            received.all().count { it.expiresAt > clock() && !it.decryptFailed }
        } catch (_: Exception) {
            0
        }
        val decryptFailures = try {
            received.decryptFailureCount(clock())
        } catch (_: Exception) {
            0
        }
        updateStatus {
            copy(
                pendingDeliveries = pending,
                failedDeliveries = failed,
                lastForwardedAt = deliveredAt,
                receivedCount = receivedCount,
                needsRepair = needsRepair || decryptFailures > 0,
                // Derived from the queue rather than latched, so the message disappears once the
                // retry succeeds and cannot go stale.
                lastError = if (failed > 0) SAFE_DELIVERY_ERROR else null
            )
        }
    }

    /** Clears the re-pair latch once the user has acted on it. */
    fun clearError() = updateStatus { copy(needsRepair = false) }

    private suspend fun countStatus(status: String): Int = try {
        outbound.byStatus(status).size
    } catch (_: Exception) {
        0
    }

    private suspend fun purgeOwner(ownerDeviceId: String) {
        try {
            received.deleteForOwner(ownerDeviceId)
        } catch (_: Exception) {
            // Nothing to purge.
        }
        try {
            peerKeys.forget(ownerDeviceId)
        } catch (_: Exception) {
            // Cache is best effort.
        }
    }

    private suspend fun forgetUnpairedEmployees(
        previous: RelayPairingContext,
        next: RelayPairingContext
    ) {
        val currentIds = next.employees.map { it.deviceId }.toSet()
        val removed = previous.employees.map { it.deviceId }.filterNot { it in currentIds }
        if (removed.isEmpty()) return
        if (next.employees.isEmpty()) {
            // The last employee was removed; nothing queued can ever be delivered.
            try {
                forwarder.dropPending()
            } catch (_: Exception) {
                // Queue cleanup is best effort.
            }
        } else {
            try {
                forwarder.dropPendingExcept(next.employees.map { it.deviceId })
            } catch (_: Exception) {
                // Queue cleanup is best effort.
            }
        }
        removed.forEach { deviceId ->
            try {
                peerKeys.forget(deviceId)
            } catch (_: Exception) {
                // Cache is best effort.
            }
        }
    }

    private suspend fun endPairing(previous: RelayPairingContext) {
        try {
            forwarder.dropPending()
        } catch (_: Exception) {
            // Queue cleanup is best effort.
        }
        if (previous.role == LocalDeviceRole.EMPLOYEE && previous.owner != null) {
            purgeOwner(previous.owner.deviceId)
        }
        try {
            identity.rotate()
        } catch (_: Exception) {
            Log.w(TAG, "Relay identity could not be rotated")
        }
    }

    private fun updateStatus(transform: RelayStatus.() -> RelayStatus) {
        _status.value = _status.value.transform()
    }

    companion object {
        private const val TAG = "PayAlert"
        private const val POLL_INTERVAL_MILLIS = 30_000L
        private const val SAFE_OFFLINE_ERROR = "relay_unreachable"
        private const val SAFE_DELIVERY_ERROR = "forwarding_failed"
    }
}
