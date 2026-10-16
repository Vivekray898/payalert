package com.vivekray898.payalert.relay

import java.nio.charset.StandardCharsets.UTF_8
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayCryptoTest {
    private val ownerId = "owner-device-01"
    private val employeeId = "employee-device-02"
    private val messageId = "message-0001"

    @Test
    fun `both X25519 sides derive the same HKDF AES key`() {
        val owner = RelayCrypto.generateKeyPair("owner-key")
        val employee = RelayCrypto.generateKeyPair("employee-key")

        val ownerDerived = RelayCrypto.deriveAesKey(
            owner.privateKey, employee.publicKey, ownerId, employeeId, messageId
        )
        val employeeDerived = RelayCrypto.deriveAesKey(
            employee.privateKey, owner.publicKey, ownerId, employeeId, messageId
        )

        assertArrayEquals(ownerDerived.encoded, employeeDerived.encoded)
        assertEquals(32, ownerDerived.encoded.size)
    }

    @Test
    fun `AES GCM round trip uses a fresh twelve byte nonce`() {
        val owner = RelayCrypto.generateKeyPair("owner-key")
        val employee = RelayCrypto.generateKeyPair("employee-key")
        val plaintext = "opaque payment event".toByteArray(UTF_8)

        val first = RelayCrypto.encrypt(ownerId, employeeId, messageId, plaintext, owner, employee.publicKey)
        val second = RelayCrypto.encrypt(ownerId, employeeId, "message-0002", plaintext, owner, employee.publicKey)
        val parsed = RelayCrypto.parseEnvelope(first)

        assertEquals(12, parsed.nonce.size)
        assertNotEquals(first, second)
        assertArrayEquals(
            plaintext,
            RelayCrypto.decrypt(first, employee, owner.publicKey, ownerId, employeeId, messageId)
        )
    }

    @Test
    fun `AAD mismatch and key identity mismatch are rejected`() {
        val owner = RelayCrypto.generateKeyPair("owner-key")
        val employee = RelayCrypto.generateKeyPair("employee-key")
        val envelope = RelayCrypto.encrypt(
            ownerId, employeeId, messageId, byteArrayOf(1, 2, 3), owner, employee.publicKey
        )

        rejects { RelayCrypto.decrypt(envelope, employee, owner.publicKey, ownerId, employeeId, "other") }
        rejects { RelayCrypto.decrypt(envelope, employee, owner.publicKey, "other", employeeId, messageId) }
        rejects { RelayCrypto.decrypt(envelope, employee, RelayCrypto.generateKeyPair("wrong-key").publicKey) }

        val tampered = envelope.dropLast(1) + if (envelope.last() == 'A') 'B' else 'A'
        rejects { RelayCrypto.decrypt(tampered, employee, owner.publicKey) }
    }

    @Test
    fun `key and envelope parsers reject malformed or ambiguous input`() {
        val keyPair = RelayCrypto.generateKeyPair("key-1")
        val encodedKey = keyPair.publicKey.encoded
        assertEquals(keyPair.keyId, RelayCrypto.parsePublicKey(encodedKey).keyId)

        rejects { RelayCrypto.parsePublicKey("x25519-v1.A") }
        rejects { RelayCrypto.parsePublicKey(encodedKey + "=") }
        rejects { RelayCrypto.generateKeyPair(" ") }
        rejects {
            RelayCrypto.encrypt("", employeeId, messageId, byteArrayOf(), keyPair, keyPair.publicKey)
        }
        rejects {
            RelayCrypto.encrypt(ownerId, ownerId, messageId, byteArrayOf(), keyPair, keyPair.publicKey)
        }
        rejects { RelayCrypto.parseEnvelope("payalert-relay-v1.A") }
    }

    @Test
    fun `encoded envelope contains no plaintext payload or metadata`() {
        val owner = RelayCrypto.generateKeyPair("owner-key")
        val employee = RelayCrypto.generateKeyPair("employee-key")
        val secret = "account number 1234567890".toByteArray(UTF_8)
        val envelope = RelayCrypto.encrypt(ownerId, employeeId, messageId, secret, owner, employee.publicKey)

        assertFalse(envelope.contains(String(secret, UTF_8)))
        assertFalse(envelope.contains(ownerId))
        assertFalse(envelope.contains(employeeId))
        assertFalse(envelope.contains(messageId))
        assertTrue(RelayCrypto.decrypt(envelope, employee, owner.publicKey).contentEquals(secret))
    }

    private fun rejects(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            return
        }
        throw AssertionError("Expected relay operation to reject input")
    }
}
