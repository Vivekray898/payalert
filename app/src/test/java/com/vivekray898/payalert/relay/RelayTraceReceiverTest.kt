package com.vivekray898.payalert.relay

import com.vivekray898.payalert.role.LocalDeviceRole
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayTraceReceiverTest {
    @Test
    fun `fetch clocks bracket the request and the client UUID reaches the announcement callback`() = runTest {
        val fakes = Fakes()
        fakes.prepare()
        fakes.network.inbox += fakes.message()

        val result = fakes.receiver().receive(CONTEXT)

        assertEquals(1, result.announced)
        assertEquals(
            listOf("fetch_start", "fetch_complete", "envelope_fetched", "decrypt_start",
                "decrypt_complete", "store_start", "store_complete", "announcement_requested"),
            fakes.stages()
        )
        val start = fakes.events.first()
        val complete = fakes.events[1]
        assertEquals(NOW.toString(), start["epoch_ms"])
        assertEquals((NOW + 600L).toString(), complete["epoch_ms"])
        assertEquals("100", start["elapsedRealtime_ms"])
        assertEquals("700", complete["elapsedRealtime_ms"])
        assertEquals(start["fetch_id"], complete["fetch_id"])
        val fetched = fakes.events[2]
        assertEquals(start["fetch_id"], fetched["fetch_id"])
        assertEquals(SERVER, fetched["server_message_id"])
        assertEquals(CLIENT, fetched["client_message_id"])
        assertEquals(CLIENT, fakes.announcements.single().first)
        assertEquals(fakes.notifications.single(), fakes.announcements.single())
        assertEquals(RelayDeliveryStatus.PROCESSED, fakes.received.rows.getValue(CLIENT).status)
        assertEquals(listOf(SERVER to LEASE), fakes.network.acks)
        assertTrue(fakes.legacyAnnouncements.isEmpty())
        assertFalse(fakes.stages().any { it.startsWith("tts_") })
        fakes.assertPrivate()
    }

    @Test
    fun `redelivery emits fetch markers but neither a second request nor a second announcement`() = runTest {
        val fakes = Fakes()
        fakes.prepare()
        fakes.network.inbox += fakes.message()
        val receiver = fakes.receiver()

        receiver.receive(CONTEXT)
        receiver.receive(CONTEXT)

        assertEquals(1, fakes.announcements.size)
        assertEquals(1, fakes.stages().count { it == "announcement_requested" })
        assertEquals(1, fakes.stages().count { it == "decrypt_start" })
        assertEquals(2, fakes.stages().count { it == "fetch_complete" })
        assertEquals(2, fakes.network.acks.size)
    }

    @Test
    fun `omitting the optional callback still invokes the legacy announce function`() = runTest {
        val fakes = Fakes()
        fakes.prepare()
        fakes.network.inbox += fakes.message()
        val receiver = EmployeeReceiver(
            fakes.transport, fakes.identity, fakes.peerKeys, fakes.received,
            announcementTemplate = { "" }, announce = fakes.legacyAnnouncements::add,
            clock = { fakes.epoch }, trace = fakes.trace
        )

        receiver.receive(CONTEXT)

        assertEquals(1, fakes.legacyAnnouncements.size)
        assertTrue(fakes.announcements.isEmpty())
        assertEquals(1, fakes.stages().count { it == "announcement_requested" })
    }

    @Test
    fun `unreadable envelope records failure and storage without announcing payment text`() = runTest {
        val fakes = Fakes()
        fakes.prepare()
        fakes.network.inbox += fakes.message().copy(envelope = "secret-invalid-ciphertext")

        val result = fakes.receiver().receive(CONTEXT)

        assertTrue(result.needsRepair)
        assertTrue(fakes.stages().contains("decrypt_failed"))
        assertTrue(fakes.stages().contains("store_complete"))
        assertFalse(fakes.stages().contains("decrypt_complete"))
        assertFalse(fakes.stages().contains("announcement_requested"))
        assertTrue(fakes.announcements.isEmpty())
        assertTrue(fakes.notifications.isEmpty())
        assertTrue(fakes.received.rows.getValue(CLIENT).decryptFailed)
        fakes.assertPrivate()
    }

    @Test
    fun `retained recovery uses the same client UUID and trace stages`() = runTest {
        val fakes = Fakes()
        fakes.prepare()
        val stale = RelayCrypto.generateKeyPair("stale-key-secret")
        val staleInfo = RelayPeerKeyInfo(OWNER, stale.keyId, stale.publicKey.encoded)
        fakes.peerKeys.store(staleInfo)
        fakes.network.peerKeys[OWNER] = staleInfo
        fakes.network.inbox += fakes.message()
        val receiver = fakes.receiver()
        receiver.receive(CONTEXT)
        assertTrue(fakes.announcements.isEmpty())

        fakes.network.peerKeys[OWNER] = fakes.ownerInfo
        fakes.network.inbox.clear()
        fakes.events.clear()
        val result = receiver.receive(CONTEXT)

        assertEquals(1, result.announced)
        assertEquals(CLIENT, fakes.announcements.single().first)
        assertEquals(1, fakes.stages().count { it == "announcement_requested" })
        assertTrue(fakes.events.filter { it["stage"] in setOf("decrypt_complete", "store_complete", "announcement_requested") }
            .all { it["client_message_id"] == CLIENT })
        assertEquals("", fakes.received.rows.getValue(CLIENT).envelope)
        fakes.assertPrivate()
    }

    @Test
    fun `fetch failure propagates unchanged without a false completion or exception content`() = runTest {
        val fakes = Fakes()
        fakes.prepare()
        val failure = RelayTransportException("secret-backend-error")
        fakes.network.fetchFailure = failure

        try {
            fakes.receiver().receive(CONTEXT)
            throw AssertionError("Expected fetch failure")
        } catch (error: RelayTransportException) {
            assertSame(failure, error)
        }

        assertEquals(listOf("fetch_start", "fetch_failed"), fakes.stages())
        assertEquals(fakes.events.first()["fetch_id"], fakes.events.last()["fetch_id"])
        assertTrue(fakes.received.rows.isEmpty())
        fakes.assertPrivate()
    }

    @Test
    fun `fetch cancellation stays cancellation and cannot look like success`() = runTest {
        val fakes = Fakes()
        fakes.prepare()
        val cancellation = CancellationException("secret-cancellation")
        fakes.network.fetchFailure = cancellation

        try {
            fakes.receiver().receive(CONTEXT)
            throw AssertionError("Expected cancellation")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }

        assertEquals(listOf("fetch_start", "fetch_cancelled"), fakes.stages())
        fakes.assertPrivate()
    }

    @Test
    fun `empty fetch has a real completion without fabricated message identifiers`() = runTest {
        val fakes = Fakes()
        fakes.prepare()

        assertEquals(RelayReceiveResult(), fakes.receiver().receive(CONTEXT))

        assertEquals(listOf("fetch_start", "fetch_complete"), fakes.stages())
        assertTrue(fakes.events.all { "client_message_id" !in it && "server_message_id" !in it })
    }

    private class Fakes {
        var epoch = NOW
        var elapsed = 100L
        val events = mutableListOf<Map<String, String>>()
        val trace = RelayTrace({ epoch }, { elapsed }, UUID.randomUUID()) { line ->
            events += line.removePrefix("relay_trace ").split(' ')
                .associate { it.substringBefore('=') to it.substringAfter('=') }
        }
        val network = FakeRelayTransport()
        val transport = object : RelayTransport by network {
            override suspend fun fetch(limit: Int): List<RelayInboxMessage> {
                assertEquals("fetch_start", events.last()["stage"])
                epoch += 600L
                elapsed += 600L
                return network.fetch(limit)
            }
        }
        val storage = InMemoryRelayPrivateKeyStorage()
        val identity = RelayIdentity(storage, transport) { "employee-key-secret" }
        val peerKeys = RelayPeerKeyCache(FakeRelayPeerKeyDao()) { epoch }
        val ownerKeys = RelayCrypto.generateKeyPair("owner-key-secret")
        val employeeKeys = RelayCrypto.generateKeyPair("employee-key-secret")
        val ownerInfo = RelayPeerKeyInfo(OWNER, ownerKeys.keyId, ownerKeys.publicKey.encoded)
        val received = FakeReceivedAnnouncementDao()
        val announcements = mutableListOf<Pair<String, String>>()
        val notifications = mutableListOf<Pair<String, String>>()
        val legacyAnnouncements = mutableListOf<String>()

        suspend fun prepare() {
            storage.write(employeeKeys)
            peerKeys.store(ownerInfo)
            network.peerKeys[OWNER] = ownerInfo
        }

        fun receiver() = EmployeeReceiver(
            transport, identity, peerKeys, received,
            announcementTemplate = { "" }, announce = legacyAnnouncements::add,
            clock = { epoch },
            notify = { id, text -> notifications += id to text },
            announceRelay = { id, text ->
                assertEquals("announcement_requested", events.last()["stage"])
                assertEquals(RelayDeliveryStatus.PROCESSED, received.rows.getValue(id).status)
                assertEquals(id to text, notifications.last())
                announcements += id to text
            },
            trace = trace
        )

        fun message() = RelayInboxMessage(
            SERVER, CLIENT,
            RelayCrypto.encrypt(
                ownerId = OWNER, employeeId = EMPLOYEE, messageId = CLIENT,
                plaintext = RelayPayloadCodec.encode(RelayPaymentPayload(
                    kind = RelayPaymentPayload.CREDIT, amount = 987.65, currency = "INR",
                    merchant = "secret-merchant", bankOrUpi = "secret-bank", utr = "secret-utr",
                    eventAt = NOW - 1_000L
                )),
                sender = ownerKeys, recipient = employeeKeys.publicKey
            ),
            LEASE
        )

        fun stages() = events.map { it.getValue("stage") }

        fun assertPrivate() {
            val output = events.toString()
            listOf("secret", OWNER, EMPLOYEE, LEASE, "987.65", "Payment", "INR", "Exception")
                .forEach { assertFalse("Unexpected private value: $it", output.contains(it)) }
        }
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val CLIENT = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        const val SERVER = "11111111-2222-4333-8444-555555555555"
        const val OWNER = "owner-device-secret"
        const val EMPLOYEE = "employee-device-secret"
        const val LEASE = "lease-token-secret"
        val CONTEXT = RelayPairingContext(
            LocalDeviceRole.EMPLOYEE, EMPLOYEE, "secret-till",
            RelayRoute("pairing-secret", OWNER, "secret-owner-label"), emptyList()
        )
    }
}
