package com.vivekray898.payalert.relay

import com.vivekray898.payalert.data.PaymentEvent
import com.vivekray898.payalert.role.LocalDeviceRole
import com.vivekray898.payalert.team.TeamConnection
import com.vivekray898.payalert.team.TeamDevice
import com.vivekray898.payalert.team.TeamSnapshot
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayEngineTest {

    @Test
    fun `an owner forwards a credit but never a debit`() = runTest {
        val fakes = Fakes(scope = this)
        fakes.prepare()
        fakes.engine.onPairingChanged(ownerSnapshot(), LocalDeviceRole.OWNER)

        fakes.engine.onStored(event("debit"))
        advanceUntilIdle()
        assertTrue(fakes.transport.enqueued.isEmpty())

        fakes.engine.onStored(event("credit"))
        advanceUntilIdle()
        assertEquals(1, fakes.transport.enqueued.size)
        assertEquals(EMPLOYEE, fakes.transport.enqueued.single().first)
    }

    @Test
    fun `an unpaired device forwards nothing`() = runTest {
        val fakes = Fakes(scope = this)
        fakes.prepare()

        fakes.engine.onStored(event("credit"))
        advanceUntilIdle()

        assertTrue(fakes.transport.enqueued.isEmpty())
        assertTrue(fakes.queue.rows.isEmpty())
        assertNull(fakes.engine.pairingContext())
    }

    @Test
    fun `leaving a team purges the owner's announcements and rotates the key`() = runTest {
        val fakes = Fakes(scope = this)
        fakes.prepare()
        fakes.engine.onPairingChanged(employeeSnapshot(), LocalDeviceRole.EMPLOYEE)
        val keyBefore = requireNotNull(fakes.storage.stored).keyId
        fakes.received.insert(
            ReceivedAnnouncement(
                messageId = "m1",
                ownerId = OWNER,
                employeeId = EMPLOYEE,
                envelope = "",
                receivedAt = NOW,
                amount = 500.0
            )
        )

        fakes.engine.onPairingChanged(unpairedSnapshot(), LocalDeviceRole.OWNER)

        assertTrue(fakes.received.rows.isEmpty())
        assertNotEquals(keyBefore, requireNotNull(fakes.storage.stored).keyId)
        assertNull(fakes.peerKeys.cached(OWNER))
        assertFalse(fakes.engine.status.value.needsRepair)
        assertFalse(fakes.engine.status.value.paired)
    }

    @Test
    fun `removing the last employee drops queued work for that device`() = runTest {
        val fakes = Fakes(scope = this)
        fakes.prepare()
        fakes.engine.onPairingChanged(ownerSnapshot(), LocalDeviceRole.OWNER)
        fakes.forwarder.offer(
            requireNotNull(fakes.engine.pairingContext()),
            event("credit")
        )
        assertEquals(1, fakes.queue.rows.size)

        fakes.engine.onPairingChanged(
            ownerSnapshot().copy(employees = emptyList()),
            LocalDeviceRole.OWNER
        )

        assertTrue(fakes.queue.rows.isEmpty())
    }

    @Test
    fun `a failed delivery marks the relay status without touching local detection`() = runTest {
        val fakes = Fakes(scope = this)
        fakes.prepare()
        fakes.engine.onPairingChanged(ownerSnapshot(), LocalDeviceRole.OWNER)
        fakes.transport.enqueueFailure = RelayTransportException("offline")

        fakes.engine.onStored(event("credit"))
        advanceUntilIdle()

        assertEquals("forwarding_failed", fakes.engine.status.value.lastError)
        assertEquals(1, fakes.queue.rows.values.single().attemptCount)
    }

    private fun ownerSnapshot() = TeamSnapshot(
        device = TeamDevice(OWNER, "Counter phone"),
        employees = listOf(TeamConnection("pairing-1", EMPLOYEE, "Till")),
        owner = null
    )

    private fun employeeSnapshot() = TeamSnapshot(
        device = TeamDevice(EMPLOYEE, "Till"),
        employees = emptyList(),
        owner = TeamConnection("pairing-1", OWNER, "Counter phone")
    )

    /** What an employee device sees after being removed, or after leaving the team. */
    private fun unpairedSnapshot() = TeamSnapshot(device = TeamDevice(EMPLOYEE, "Till"))

    private fun event(kind: String) = PaymentEvent(
        kind = kind,
        amount = 500.0,
        merchant = "Ramesh",
        bankOrUpi = "HDFC",
        source = "notification",
        sourceDetail = "com.phonepe.app",
        rawText = "alert",
        dedupKey = "dedup-$kind",
        receivedAt = NOW
    )

    private inner class Fakes(scope: kotlinx.coroutines.CoroutineScope) {
        var now = NOW
        val storage = InMemoryRelayPrivateKeyStorage()
        val transport = FakeRelayTransport()
        val queue = FakeOwnerOutboundQueueDao()
        val received = FakeReceivedAnnouncementDao()
        val peerKeyDao = FakeRelayPeerKeyDao()
        val peerKeys = RelayPeerKeyCache(peerKeyDao) { now }
        val identity = RelayIdentity(storage, transport) { "device-key" }
        val ownerKeys = RelayCrypto.generateKeyPair("owner-key-1")
        val employeeKeys = RelayCrypto.generateKeyPair("employee-key-1")
        val announcements = mutableListOf<String>()

        val forwarder = OwnerForwarder(transport, identity, queue, peerKeys, clock = { now })
        val receiver = EmployeeReceiver(
            transport = transport,
            identity = identity,
            peerKeys = peerKeys,
            received = received,
            announcementTemplate = { "" },
            announce = { announcements += it },
            clock = { now }
        )
        val engine = RelayEngine(
            transport = transport,
            identity = identity,
            peerKeys = peerKeys,
            forwarder = forwarder,
            receiver = receiver,
            outbound = queue,
            received = received,
            clock = { now },
            scope = scope
        )

        suspend fun prepare() {
            storage.write(ownerKeys)
            peerKeys.store(
                RelayPeerKeyInfo(EMPLOYEE, employeeKeys.keyId, employeeKeys.publicKey.encoded)
            )
            transport.peerKeys[EMPLOYEE] = RelayPeerKeyInfo(
                EMPLOYEE, employeeKeys.keyId, employeeKeys.publicKey.encoded
            )
        }
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val OWNER = "owner-device-0001"
        const val EMPLOYEE = "employee-device-0002"
    }
}
