package com.vivekray898.payalert.relay

import com.vivekray898.payalert.role.LocalDeviceRole
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmployeeReceiverTest {

    @Test
    fun `a forwarded credit is stored, announced once and acknowledged`() = runTest {
        val fakes = Fakes()
        fakes.prepare()
        fakes.transport.inbox += fakes.inboxMessage("msg-1", amount = 750.0, merchant = "Suresh")

        val result = fakes.receiver.receive(context())

        assertEquals(1, result.imported)
        assertEquals(1, result.announced)
        assertFalse(result.needsRepair)
        assertEquals(
            listOf("Payment received. 750 rupees from Suresh."),
            fakes.announcements
        )
        assertEquals(
            listOf("msg-1" to "Payment received. 750 rupees from Suresh."),
            fakes.notifications
        )
        val row = fakes.received.rows.getValue("msg-1")
        assertEquals(750.0, requireNotNull(row.amount), 0.001)
        assertEquals("Suresh", row.merchant)
        assertEquals(RelayDeliveryStatus.PROCESSED, row.status)
        // The 24-hour window starts at receipt on this device.
        assertEquals(RelayCleanupPolicy.receivedExpiresAt(fakes.now), row.expiresAt)
        assertEquals("Retention must delete this row after 24 hours", 1, fakes.received.deleteExpired(row.expiresAt))
        assertTrue(fakes.received.rows.isEmpty())
    }

    @Test
    fun `the ciphertext is not retained once a message is read`() = runTest {
        val fakes = Fakes()
        fakes.prepare()
        fakes.transport.inbox += fakes.inboxMessage("msg-2", amount = 100.0, merchant = "Shop")

        fakes.receiver.receive(context())

        assertEquals("", fakes.received.rows.getValue("msg-2").envelope)
    }

    @Test
    fun `a redelivered message is neither stored nor announced twice`() = runTest {
        val fakes = Fakes()
        fakes.prepare()
        fakes.transport.inbox += fakes.inboxMessage("msg-3", amount = 250.0, merchant = "Amit")

        fakes.receiver.receive(context())
        val second = fakes.receiver.receive(context())

        assertEquals(0, second.imported)
        assertEquals(0, second.announced)
        assertEquals(1, fakes.announcements.size)
        assertEquals(1, fakes.notifications.size)
        assertEquals(1, fakes.received.rows.size)
        // The lease is released on every pass, so a redelivery cannot accumulate on the server.
        assertEquals(2, fakes.transport.acks.size)
    }

    @Test
    fun `an envelope that fails authentication is dropped without inventing a payment`() = runTest {
        val fakes = Fakes()
        fakes.prepare()
        val impostor = RelayCrypto.generateKeyPair("impostor-key")
        fakes.transport.inbox += fakes.inboxMessage(
            "msg-4",
            amount = 900.0,
            merchant = "Guessed",
            sender = impostor
        )

        val result = fakes.receiver.receive(context())

        assertTrue(result.needsRepair)
        assertEquals(0, result.imported)
        assertEquals(0, result.announced)
        assertTrue(fakes.announcements.isEmpty())
        assertTrue(fakes.notifications.isEmpty())
        val row = fakes.received.rows.getValue("msg-4")
        assertTrue(row.decryptFailed)
        assertNull(row.amount)
        assertNull(row.merchant)
        assertEquals(RelayDeliveryStatus.FAILED, row.status)
        // The ciphertext is kept for a later recovery, and the relay row is released either way.
        assertTrue(row.envelope.isNotEmpty())
        assertEquals(1, fakes.transport.acks.size)
    }

    @Test
    fun `a retained envelope is recovered after the pairing is repaired`() = runTest {
        val fakes = Fakes()
        fakes.prepare()
        // The cached owner key is stale, so the first pass cannot authenticate the envelope even
        // though it was encrypted by the real owner key.
        fakes.peerKeys.store(
            RelayPeerKeyInfo(OWNER, fakes.ownerKeys.keyId, fakes.ownerKeys.publicKey.encoded)
        )
        val misregistered = RelayCrypto.generateKeyPair(fakes.ownerKeys.keyId)
        fakes.peerKeys.store(
            RelayPeerKeyInfo(OWNER, fakes.ownerKeys.keyId, misregistered.publicKey.encoded)
        )
        fakes.transport.inbox += fakes.inboxMessage("msg-5", amount = 640.0, merchant = "Ravi")
        fakes.receiver.receive(context())
        assertTrue(fakes.announcements.isEmpty())

        // The correct owner key becomes available again, e.g. after the devices re-pair.
        fakes.peerKeys.store(
            RelayPeerKeyInfo(OWNER, fakes.ownerKeys.keyId, fakes.ownerKeys.publicKey.encoded)
        )
        fakes.transport.inbox.clear()
        val result = fakes.receiver.receive(context())

        assertEquals(1, result.imported)
        assertEquals(1, result.announced)
        assertEquals("Payment received. 640 rupees from Ravi.", fakes.announcements.single())
        val row = fakes.received.rows.getValue("msg-5")
        assertEquals(640.0, requireNotNull(row.amount), 0.001)
        assertFalse(row.decryptFailed)
        assertEquals("", row.envelope)
    }

    @Test
    fun `one unreadable envelope does not strand the rest of the batch`() = runTest {
        val fakes = Fakes()
        fakes.prepare()
        val impostor = RelayCrypto.generateKeyPair("impostor-key")
        fakes.transport.inbox += fakes.inboxMessage("msg-6", amount = 10.0, merchant = "Bad", sender = impostor)
        fakes.transport.inbox += fakes.inboxMessage("msg-7", amount = 20.0, merchant = "Good")

        val result = fakes.receiver.receive(context())

        assertEquals(1, result.imported)
        assertEquals(1, result.announced)
        assertTrue(result.needsRepair)
        assertEquals("Payment received. 20 rupees from Good.", fakes.announcements.single())
    }

    @Test
    fun `an owner device or an unconfigured build never receives`() = runTest {
        val owner = Fakes()
        owner.prepare()
        owner.transport.inbox += owner.inboxMessage("msg-8", amount = 5.0, merchant = "Nobody")
        val ownerContext = context().copy(role = LocalDeviceRole.OWNER, owner = null)

        assertEquals(RelayReceiveResult(), owner.receiver.receive(ownerContext))

        val unconfigured = Fakes(configured = false)
        unconfigured.prepare()
        unconfigured.transport.inbox += unconfigured.inboxMessage("msg-9", amount = 5.0, merchant = "Nobody")
        assertEquals(RelayReceiveResult(), unconfigured.receiver.receive(context()))
        assertTrue(unconfigured.announcements.isEmpty())
    }

    @Test
    fun `an owner with no bound key asks for a re-pair instead of guessing`() = runTest {
        val fakes = Fakes()
        fakes.prepare()
        fakes.peerKeyDao.clear()
        fakes.transport.peerKeys.clear()
        fakes.transport.inbox += fakes.inboxMessage("msg-10", amount = 5.0, merchant = "Nobody")

        val result = fakes.receiver.receive(context())

        assertTrue(result.needsRepair)
        assertEquals(0, result.announced)
        assertTrue(fakes.received.rows.isEmpty())
    }

    private fun context() = RelayPairingContext(
        role = LocalDeviceRole.EMPLOYEE,
        localDeviceId = EMPLOYEE,
        localLabel = "Till",
        owner = RelayRoute("pairing-1", OWNER, "Owner device"),
        employees = emptyList()
    )

    private inner class Fakes(configured: Boolean = true) {
        val storage = InMemoryRelayPrivateKeyStorage()
        val transport = FakeRelayTransport(configured = configured)
        val received = FakeReceivedAnnouncementDao()
        val peerKeyDao = FakeRelayPeerKeyDao()
        var now = NOW
        val peerKeys = RelayPeerKeyCache(peerKeyDao) { now }
        val identity = RelayIdentity(storage, transport) { EMPLOYEE_KEY }
        val ownerKeys = RelayCrypto.generateKeyPair(OWNER_KEY)
        val employeeKeys = RelayCrypto.generateKeyPair(EMPLOYEE_KEY)
        val announcements = mutableListOf<String>()
        val notifications = mutableListOf<Pair<String, String>>()

        val receiver = EmployeeReceiver(
            transport = transport,
            identity = identity,
            peerKeys = peerKeys,
            received = received,
            announcementTemplate = { "" },
            announce = { announcements += it },
            clock = { now },
            notify = { messageId, text -> notifications += messageId to text }
        )

        suspend fun prepare() {
            storage.write(employeeKeys)
            peerKeys.store(RelayPeerKeyInfo(OWNER, ownerKeys.keyId, ownerKeys.publicKey.encoded))
        }

        fun inboxMessage(
            messageId: String,
            amount: Double,
            merchant: String,
            sender: RelayKeyPair = ownerKeys
        ): RelayInboxMessage = RelayInboxMessage(
            serverMessageId = "server-$messageId",
            messageId = messageId,
            envelope = RelayCrypto.encrypt(
                ownerId = OWNER,
                employeeId = EMPLOYEE,
                messageId = messageId,
                plaintext = RelayPayloadCodec.encode(
                    RelayPaymentPayload(
                        kind = RelayPaymentPayload.CREDIT,
                        amount = amount,
                        currency = "INR",
                        merchant = merchant,
                        bankOrUpi = null,
                        utr = null,
                        eventAt = NOW - 1_000
                    )
                ),
                sender = sender,
                recipient = employeeKeys.publicKey
            ),
            leaseToken = "lease-$messageId"
        )
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val OWNER = "owner-device-0001"
        const val EMPLOYEE = "employee-device-0002"
        const val OWNER_KEY = "owner-key-1"
        const val EMPLOYEE_KEY = "employee-key-1"
    }
}
