package com.vivekray898.payalert.relay

import com.vivekray898.payalert.data.PaymentEvent
import com.vivekray898.payalert.role.LocalDeviceRole
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerForwarderTest {

    private val ownerId = "owner-device-0001"
    private val employeeId = "employee-device-0002"
    private val secondEmployeeId = "employee-device-0003"

    @Test
    fun `debits are never queued even when the detector stores them`() = runTest {
        val fakes = Fakes()
        fakes.transport.peerKeys[employeeId] = fakes.employeePeer

        val queued = fakes.forwarder.offer(context(), creditEvent(amount = 500.0, kind = "debit"))

        assertEquals(0, queued)
        assertTrue(fakes.queue.rows.isEmpty())
        assertTrue(fakes.transport.enqueued.isEmpty())
    }

    @Test
    fun `a credit is queued once per employee and the envelope holds no plaintext`() = runTest {
        val fakes = Fakes()
        fakes.transport.peerKeys[employeeId] = fakes.employeePeer
        fakes.transport.peerKeys[secondEmployeeId] = fakes.employeePeer
        val event = creditEvent(amount = 500.0, merchant = "Ramesh")

        val both = context(twoEmployees())
        assertEquals(2, fakes.forwarder.offer(both, event))
        // The same event detected twice must not enqueue a second copy or a second announcement.
        assertEquals(0, fakes.forwarder.offer(both, event))
        assertEquals(2, fakes.queue.rows.size)

        val delivered = fakes.forwarder.flush(both)
        assertEquals(2, delivered)
        assertEquals(2, fakes.transport.enqueued.size)

        val envelope = fakes.transport.enqueued.first { it.first == employeeId }.third
        assertFalse(envelope.contains("500"))
        assertFalse(envelope.contains("Ramesh"))
        assertFalse(envelope.contains("UTR123"))

        // The employee can read it, and reads exactly the forwarded attributes.
        val first = fakes.transport.enqueued.first { it.first == employeeId }
        val decoded = RelayPayloadCodec.decode(
            RelayCrypto.decrypt(
                first.third,
                fakes.employeeKeys,
                fakes.ownerKeys.publicKey,
                ownerId,
                employeeId,
                first.second
            )
        )
        assertEquals(500.0, decoded.amount, 0.001)
        assertEquals("Ramesh", decoded.merchant)
    }

    @Test
    fun `a failed delivery is retried with backoff and succeeds later`() = runTest {
        val fakes = Fakes()
        fakes.transport.peerKeys[employeeId] = fakes.employeePeer
        fakes.transport.enqueueFailure = RelayTransportException("offline")
        fakes.forwarder.offer(context(), creditEvent())

        assertEquals(0, fakes.forwarder.flush(context()))
        fakes.transport.enqueueFailure = null
        val failed = fakes.queue.rows.getValue(singleMessageId(fakes))
        assertEquals(RelayDeliveryStatus.FAILED, failed.status)
        assertEquals(1, failed.attemptCount)
        assertEquals("delivery_failed", failed.lastError)
        assertNotNull(failed.nextAttemptAt)

        // A retry before the backoff window elapses is skipped entirely.
        assertEquals(0, fakes.forwarder.flush(context()))
        assertEquals(1, fakes.queue.rows.getValue(singleMessageId(fakes)).attemptCount)

        fakes.now = failed.nextAttemptAt!!
        assertEquals(1, fakes.forwarder.flush(context()))
        assertEquals(
            RelayDeliveryStatus.DELIVERED,
            fakes.queue.rows.getValue(singleMessageId(fakes)).status
        )
    }

    @Test
    fun `a rotated employee key is re-bound and the queued payment is re-encrypted`() = runTest {
        val fakes = Fakes()
        val rotated = RelayCrypto.generateKeyPair("${employeeId}-key-2")
        fakes.transport.peerKeys[employeeId] = fakes.employeePeer
        fakes.forwarder.offer(context(), creditEvent())

        // The employee rotated between the queue insert and the send, so the cached key is stale.
        assertEquals(
            fakes.employeePeer.keyId,
            requireNotNull(fakes.peerKeys.cached(employeeId)).keyId
        )
        fakes.transport.peerKeys[employeeId] = RelayPeerKeyInfo(
            deviceId = employeeId,
            keyId = rotated.keyId,
            publicKey = rotated.publicKey.encoded
        )

        assertEquals(1, fakes.forwarder.flush(context()))
        assertEquals(rotated.keyId, requireNotNull(fakes.peerKeys.cached(employeeId)).keyId)

        val decoded = RelayPayloadCodec.decode(
            RelayCrypto.decrypt(
                fakes.transport.enqueued.single().third,
                rotated,
                fakes.ownerKeys.publicKey,
                ownerId,
                employeeId,
                fakes.transport.enqueued.single().second
            )
        )
        assertEquals(500.0, decoded.amount, 0.001)
    }

    @Test
    fun `an unreachable peer key is reported and does not lose the queued payment silently`() = runTest {
        val fakes = Fakes()
        var needsRepair = false
        val forwarder = OwnerForwarder(
            transport = fakes.transport,
            identity = fakes.identity,
            queue = fakes.queue,
            peerKeys = fakes.peerKeys,
            clock = { NOW },
            onKeyNotBound = { needsRepair = true }
        )
        assertEquals(1, forwarder.offer(context(), creditEvent()))

        assertEquals(0, forwarder.flush(context()))
        assertTrue(needsRepair)
        assertEquals(RelayDeliveryStatus.FAILED, fakes.queue.rows.values.single().status)
        assertEquals("key_not_bound", fakes.queue.rows.values.single().lastError)
    }

    @Test
    fun `ending a pairing drops everything that was never delivered`() = runTest {
        val fakes = Fakes()
        fakes.transport.peerKeys[employeeId] = fakes.employeePeer
        fakes.forwarder.offer(context(), creditEvent())
        fakes.forwarder.offer(context(), creditEvent(dedupKey = "dedup-2"))

        assertEquals(2, fakes.forwarder.dropPending())
        assertTrue(fakes.queue.rows.isEmpty())
    }

    @Test
    fun `queued work for a removed employee is dropped while the rest is kept`() = runTest {
        val fakes = Fakes()
        fakes.transport.peerKeys[employeeId] = fakes.employeePeer
        fakes.transport.peerKeys[secondEmployeeId] = fakes.employeePeer
        fakes.forwarder.offer(context(twoEmployees()), creditEvent())

        fakes.forwarder.dropPendingExcept(listOf(employeeId))

        assertEquals(listOf(employeeId), fakes.queue.rows.values.map { it.employeeId }.distinct())
    }

    @Test
    fun `an unconfigured build queues nothing and touches no key`() = runTest {
        val fakes = Fakes(configured = false)

        assertEquals(0, fakes.forwarder.offer(context(), creditEvent()))
        assertEquals(0, fakes.forwarder.flush(context()))
        assertTrue(fakes.queue.rows.isEmpty())
        assertTrue(fakes.transport.registeredKeys.isEmpty())
    }

    private fun singleMessageId(fakes: Fakes) = fakes.queue.rows.keys.single()

    private fun context(employees: List<RelayRoute> = listOf(employeeRoute())) = RelayPairingContext(
        role = LocalDeviceRole.OWNER,
        localDeviceId = ownerId,
        localLabel = "Counter phone",
        owner = null,
        employees = employees
    )

    private fun employeeRoute() = RelayRoute("pairing-1", employeeId, "Till")

    private fun twoEmployees() = listOf(
        employeeRoute(),
        RelayRoute("pairing-2", secondEmployeeId, "Back office")
    )

    private fun creditEvent(
        amount: Double = 500.0,
        merchant: String = "Ramesh",
        kind: String = "credit",
        dedupKey: String = "dedup-1"
    ) = PaymentEvent(
        kind = kind,
        amount = amount,
        merchant = merchant,
        bankOrUpi = "HDFC",
        utr = "UTR123",
        source = "notification",
        sourceDetail = "com.phonepe.app",
        rawText = "Payment received",
        dedupKey = dedupKey,
        receivedAt = NOW
    )

    private class Fakes(configured: Boolean = true) {
        var now = NOW
        val storage = InMemoryRelayPrivateKeyStorage()
        val transport = FakeRelayTransport(configured = configured)
        val queue = FakeOwnerOutboundQueueDao()
        val peerKeyDao = FakeRelayPeerKeyDao()
        val peerKeys = RelayPeerKeyCache(peerKeyDao) { now }
        val identity = RelayIdentity(storage, transport) { "owner-key-1" }
        val ownerKeys = RelayCrypto.generateKeyPair("owner-key-1")
        val employeeKeys = RelayCrypto.generateKeyPair("employee-key-1")
        val employeePeer = RelayPeerKeyInfo(
            deviceId = "employee-device-0002",
            keyId = employeeKeys.keyId,
            publicKey = employeeKeys.publicKey.encoded
        )

        init {
            // The device already owns an identity; reuse it so the test can decrypt the envelope.
            storage.write(ownerKeys)
        }

        val forwarder = OwnerForwarder(
            transport = transport,
            identity = identity,
            queue = queue,
            peerKeys = peerKeys,
            clock = { now }
        )
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
