package com.vivekray898.payalert.relay

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vivekray898.payalert.data.PayAlertDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RelayPersistenceTest {
    private lateinit var database: PayAlertDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PayAlertDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun receivedMessageIdDeduplicatesAndExpirySweepRemovesOnlyExpiredRows() =
        runBlocking {
            val dao = database.receivedAnnouncementDao()
            val now = 1_700_000_000_000L
            val expired = ReceivedAnnouncement(
                messageId = "message-expired",
                ownerId = "owner",
                employeeId = "employee",
                envelope = "opaque-expired",
                receivedAt = now - RelayCleanupPolicy.RECEIVED_ANNOUNCEMENT_TTL_MILLIS - 1L,
                expiresAt = now - 1L
            )
            val live = ReceivedAnnouncement(
                messageId = "message-live",
                ownerId = "owner",
                employeeId = "employee",
                envelope = "opaque-live",
                receivedAt = now,
                expiresAt = now + 1L
            )

            dao.insert(expired)
            dao.insert(live)
            dao.insert(live.copy(envelope = "duplicate-must-be-ignored"))

            assertEquals("opaque-live", dao.byMessageId("message-live")?.envelope)
            assertEquals(1, dao.deleteExpired(now))
            assertEquals(null, dao.byMessageId("message-expired"))
            assertNotNull(dao.byMessageId("message-live"))
        }

    @Test
    fun unreadableEnvelopesAreRetainedUntilTheyExpireAndCanBePurgedByOwner() = runBlocking {
        val dao = database.receivedAnnouncementDao()
        val now = 1_700_000_000_000L

        dao.insert(
            ReceivedAnnouncement(
                messageId = "message-unreadable",
                ownerId = "owner-1",
                employeeId = "employee",
                envelope = "retained-ciphertext",
                receivedAt = now,
                status = RelayDeliveryStatus.FAILED,
                decryptFailed = true
            )
        )
        dao.insert(
            ReceivedAnnouncement(
                messageId = "message-read",
                ownerId = "owner-1",
                employeeId = "employee",
                envelope = "",
                receivedAt = now,
                amount = 500.0,
                status = RelayDeliveryStatus.PENDING
            )
        )

        // Only the retained ciphertext is offered for a later recovery attempt.
        assertEquals(
            listOf("message-unreadable"),
            dao.unreadable(now, limit = 10).map { it.messageId }
        )
        assertEquals(1, dao.decryptFailureCount(now))

        // Announcing is claimed once, so a redelivered message cannot speak twice.
        assertEquals(1, dao.claimForAnnouncement("message-read", now, now))
        assertEquals(0, dao.claimForAnnouncement("message-read", now, now))

        // Leaving the team removes everything that came from that owner.
        assertEquals(2, dao.deleteForOwner("owner-1"))
        assertEquals(0, dao.decryptFailureCount(now))
        assertEquals(null, dao.byMessageId("message-unreadable"))
    }

    @Test
    fun peerKeyCacheKeepsOneKeyPerDevice() = runBlocking {
        val dao = database.relayPeerKeyDao()

        dao.upsert(RelayPeerKey("device-1", "key-1", "x25519-v1.first", 1L))
        dao.upsert(RelayPeerKey("device-1", "key-2", "x25519-v1.second", 2L))
        dao.upsert(RelayPeerKey("device-2", "key-1", "x25519-v1.other", 3L))

        assertEquals("key-2", dao.byDeviceId("device-1")?.keyId)
        assertEquals(1, dao.delete("device-1"))
        assertEquals(null, dao.byDeviceId("device-1"))
        assertEquals(1, dao.clear())
    }

    @Test
    fun ownerQueueDeduplicatesTracksAttemptsAndExposesDiagnostics() = runBlocking {
        val dao = database.ownerOutboundQueueDao()
        val now = 1_700_000_000_000L
        val message = OwnerOutboundMessage(
            messageId = "message-owner-1",
            ownerId = "owner",
            employeeId = "employee",
            envelope = "opaque-owner-message",
            createdAt = now,
            nextAttemptAt = now - 1L
        )

        dao.insert(message)
        dao.insert(message.copy(envelope = "duplicate-must-be-ignored"))

        assertEquals("opaque-owner-message", dao.byMessageId(message.messageId)?.envelope)
        assertEquals(listOf(message.messageId), dao.due(now, limit = 10).map { it.messageId })
        assertEquals(
            1,
            dao.recordAttempt(
                messageId = message.messageId,
                status = RelayDeliveryStatus.FAILED,
                attemptedAt = now,
                nextAttemptAt = now + 1_000L,
                error = "temporary",
                updatedAt = now
            )
        )
        assertEquals(1, dao.diagnostics().single().rowCount)
        assertEquals(RelayDeliveryStatus.FAILED, dao.diagnostics().single().status)
        assertEquals(1, dao.byMessageId(message.messageId)?.attemptCount)
    }
}
