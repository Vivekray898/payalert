package com.vivekray898.payalert.relay

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The relay publishes this device's public key on every forward and on every receive pass.
 *
 * Each publish used to be a full round trip to the relay server, which put three of them on the
 * path of a single forwarded payment. The published key is now remembered, so the server is only
 * contacted when the key actually changed or when the re-publish interval has passed.
 */
class RelayIdentityTest {

    @Test
    fun `the key is published to the server once while it is unchanged`() = runTest {
        val transport = FakeRelayTransport()
        val identity = RelayIdentity(InMemoryRelayPrivateKeyStorage(), transport, clock = { NOW })

        val first = identity.publish()
        val second = identity.publish()
        val third = identity.publish()

        assertNotNull(first)
        assertEquals(first?.keyId, second?.keyId)
        assertEquals(first?.keyId, third?.keyId)
        assertEquals(1, transport.registeredKeys.size)
        assertEquals(first?.keyId, transport.registeredKeys.single().first)
    }

    @Test
    fun `a rotated key is published again`() = runTest {
        val transport = FakeRelayTransport()
        val storage = InMemoryRelayPrivateKeyStorage()
        val identity = RelayIdentity(storage, transport, clock = { NOW }, newKeyId = { "key-1" })

        val first = identity.publish()
        identity.rotate()
        val second = identity.publish()

        assertEquals("key-1", first?.keyId)
        assertEquals("key-1", second?.keyId)
        assertEquals(2, transport.registeredKeys.size)
    }

    @Test
    fun `a cleared identity is published again`() = runTest {
        val transport = FakeRelayTransport()
        val identity = RelayIdentity(
            InMemoryRelayPrivateKeyStorage(),
            transport,
            clock = { NOW },
            newKeyId = { "key-1" }
        )

        identity.publish()
        identity.clear()
        identity.publish()

        assertEquals(2, transport.registeredKeys.size)
    }

    @Test
    fun `the key is published again once the re-publish interval has passed`() = runTest {
        val transport = FakeRelayTransport()
        var now = NOW
        val identity = RelayIdentity(
            InMemoryRelayPrivateKeyStorage(),
            transport,
            clock = { now },
            newKeyId = { "key-1" }
        )

        identity.publish()
        now += 60_000L
        identity.publish()
        assertEquals(1, transport.registeredKeys.size)

        // Past the interval the binding is re-registered, so a server that lost the key recovers
        // without waiting for a key change that may never come.
        now += 10L * 60_000L
        identity.publish()
        assertEquals(2, transport.registeredKeys.size)
    }

    @Test
    fun `an unconfigured build publishes nothing`() = runTest {
        val transport = FakeRelayTransport(configured = false)
        val identity = RelayIdentity(InMemoryRelayPrivateKeyStorage(), transport, clock = { NOW })

        assertNull(identity.publish())
        assertTrue(transport.registeredKeys.isEmpty())
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
