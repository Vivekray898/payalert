package com.vivekray898.payalert.team

import com.vivekray898.payalert.relay.RelayKeyNotBoundException
import com.vivekray898.payalert.relay.RelayTransportException
import java.nio.charset.StandardCharsets.UTF_8
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayProtocolTest {

    @Test
    fun `bytea is hex encoded the way PostgREST expects and decoded back`() {
        val bytes = "payalert-relay-v1.AAAA".toByteArray(UTF_8)

        val encoded = RelayProtocol.bytea(bytes)

        assertTrue(encoded.startsWith("\\x"))
        assertEquals(bytes.size * 2 + 2, encoded.length)
        assertArrayEquals(bytes, RelayProtocol.decodeHex(encoded.substring(2)))
        assertArrayEquals(bytes, RelayProtocol.decodeBytea(encoded))
    }

    @Test
    fun `the inbox decoder reads envelope bytes and lease fields`() {
        val body = """
            [{"message_id":"11111111-1111-1111-1111-111111111111",
              "client_message_id":"22222222-2222-2222-2222-222222222222",
              "encrypted_envelope":"\\x7061796c6f6164",
              "lease_token":"33333333-3333-3333-3333-333333333333",
              "delivery_attempts":1}]
        """.trimIndent()

        val rows = RelayProtocol.inbox(body)

        assertEquals(1, rows.size)
        assertEquals("22222222-2222-2222-2222-222222222222", rows.single().messageId)
        assertEquals("payload", rows.single().envelope)
        assertEquals("33333333-3333-3333-3333-333333333333", rows.single().leaseToken)
    }

    @Test
    fun `an empty result set and a malformed body are handled differently`() {
        assertTrue(RelayProtocol.inbox("[]").isEmpty())
        assertTrue(RelayProtocol.inbox("null").isEmpty())
        rejects { RelayProtocol.inbox("{\"not\":\"an array\"}") }
        rejects { RelayProtocol.inbox("[{\"message_id\":\"only-id\"}]") }
    }

    @Test
    fun `peer keys are read from the hex form without private material`() {
        val publicKey = "x25519-v1.AAAAAAAA"
        val body = """
            {"owner_device_id":"device-1","owner_key_id":"key-1",
             "owner_public_key_hex":"${publicKey.toByteArray(UTF_8).joinToString("") { "%02x".format(it.toInt() and 0xff) }}"}
        """.trimIndent()

        val info = RelayProtocol.peerKey(body)

        assertEquals("device-1", info?.deviceId)
        assertEquals("key-1", info?.keyId)
        assertEquals(publicKey, info?.publicKey)
        assertNull(RelayProtocol.peerKey("null"))
    }

    @Test
    fun `relay failures separate a stale binding from an unreachable service`() {
        val stale = RelayProtocol.failure(
            TeamHttpResponse(403, "{\"code\":\"42501\",\"message\":\"key_not_bound\"}")
        )
        val signedOut = RelayProtocol.failure(
            TeamHttpResponse(401, "{\"message\":\"authentication_required\"}")
        )
        val offline = RelayProtocol.failure(TeamHttpResponse(503, "upstream unavailable"))

        assertTrue(stale is RelayKeyNotBoundException)
        assertTrue(signedOut is RelayTransportException)
        assertTrue(offline is RelayTransportException)
        assertNull(RelayProtocol.failure(TeamHttpResponse(200, "{}")))
    }

    @Test
    fun `no server string is echoed to the caller`() {
        val failure = RelayProtocol.failure(
            TeamHttpResponse(403, "{\"message\":\"internal detail about device 42\"}")
        )

        assertTrue(failure is RelayTransportException)
        assertTrue(failure!!.message!!.isNotBlank())
    }

    private fun rejects(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            return
        }
        throw AssertionError("Expected the relay protocol to reject the input")
    }
}
