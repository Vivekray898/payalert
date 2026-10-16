package com.vivekray898.payalert.relay

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

/** A safe, caller-facing failure for cryptographic authentication/storage operations. */
class RelayCryptoException(message: String) : Exception(message)

/** An X25519 private key held in memory only for the duration of a relay operation. */
class RelayPrivateKey internal constructor(
    val keyId: String,
    materialInput: ByteArray
) {
    private val material = materialInput.copyOf()

    init {
        RelayCrypto.validateKeyId(keyId)
        require(material.size == RelayCrypto.X25519_KEY_BYTES) { "Invalid relay key" }
    }

    /** The key material is never returned by this API. */
    internal fun materialCopy(): ByteArray = material.copyOf()
}

/** An encoded X25519 public key with a stable server-side key identifier. */
class RelayPublicKey internal constructor(
    val keyId: String,
    materialInput: ByteArray
) {
    private val material = materialInput.copyOf()

    init {
        RelayCrypto.validateKeyId(keyId)
        require(material.size == RelayCrypto.X25519_KEY_BYTES) { "Invalid relay key" }
        require(!RelayCrypto.isAllZero(material)) { "Invalid relay key" }
    }

    /** Versioned, canonical base64url key encoding for transport/storage. */
    val encoded: String
        get() = RelayCrypto.encodePublicKey(this)

    internal fun materialCopy(): ByteArray = material.copyOf()
}

/** A paired private/public relay identity. */
class RelayKeyPair internal constructor(
    val keyId: String,
    val privateKey: RelayPrivateKey,
    val publicKey: RelayPublicKey
)

/**
 * Parsed relay envelope. Metadata is encoded inside the opaque envelope and is also included in
 * AES-GCM AAD. Mutating a returned byte array therefore cannot mutate the parsed envelope.
 */
class RelayEnvelope internal constructor(
    val ownerId: String,
    val employeeId: String,
    val messageId: String,
    val senderKeyId: String,
    val recipientKeyId: String,
    nonce: ByteArray,
    ciphertext: ByteArray
) {
    private val nonceBytes = nonce.copyOf()
    private val ciphertextBytes = ciphertext.copyOf()

    val nonce: ByteArray
        get() = nonceBytes.copyOf()

    val ciphertext: ByteArray
        get() = ciphertextBytes.copyOf()

    /** Re-encodes this already parsed envelope without exposing plaintext metadata. */
    fun encode(): String = RelayCrypto.encodeEnvelope(this)

    internal fun nonceCopy(): ByteArray = nonceBytes.copyOf()
    internal fun ciphertextCopy(): ByteArray = ciphertextBytes.copyOf()
}

/**
 * Protocol-only relay cryptography.
 *
 * The protocol uses a device's static X25519 identity key for ECDH, HKDF-SHA256 to derive a
 * per-message AES-256 key, and AES-256-GCM with a fresh 96-bit nonce. The owner, employee, and
 * client message IDs are length-framed in both HKDF info and GCM AAD; delimiter tricks and ID
 * substitution therefore cannot produce the same key or pass authentication.
 */
object RelayCrypto {
    const val X25519_KEY_BYTES = 32
    const val GCM_NONCE_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val AES_KEY_BYTES = 32
    private const val VERSION = 1
    private const val MAX_IDENTIFIER_BYTES = 256
    private const val MAX_KEY_ID_BYTES = 128
    private const val MAX_PLAINTEXT_BYTES = 1_048_576
    private const val MAX_ENVELOPE_BYTES = MAX_PLAINTEXT_BYTES + 16 + 2048
    private const val MAX_ENCODED_ENVELOPE_CHARS = 2_000_000
    private const val KEY_PREFIX = "x25519-v1."
    private const val ENVELOPE_PREFIX = "payalert-relay-v1."
    private const val AAD_DOMAIN = "PayAlert relay AAD"
    private const val HKDF_DOMAIN = "PayAlert relay HKDF-SHA256"
    private const val STORAGE_DOMAIN = "PayAlert relay private-key storage"
    private const val ALGORITHM_X25519_HKDF_AES_GCM = 1
    private val random = SecureRandom()
    private val keyMagic = "RKEY".toByteArray(UTF_8)
    private val envelopeMagic = "RLYE".toByteArray(UTF_8)
    private val aadMagic = "RLYA".toByteArray(UTF_8)
    private val storageMagic = "RLYS".toByteArray(UTF_8)
    private val hkdfSalt = sha256(HKDF_DOMAIN.toByteArray(UTF_8))

    /** Generates a fresh X25519 identity key pair and a UUID key identifier. */
    @JvmOverloads
    fun generateKeyPair(keyId: String = UUID.randomUUID().toString()): RelayKeyPair {
        validateKeyId(keyId)
        val generator = X25519KeyPairGenerator().apply {
            init(X25519KeyGenerationParameters(random))
        }
        val generated = generator.generateKeyPair()
        val privateKey = generated.private as X25519PrivateKeyParameters
        val publicKey = generated.public as X25519PublicKeyParameters
        return RelayKeyPair(
            keyId,
            RelayPrivateKey(keyId, privateKey.encoded),
            RelayPublicKey(keyId, publicKey.encoded)
        )
    }

    /** Encodes a public key as a versioned, canonical base64url value. */
    fun encodePublicKey(key: RelayPublicKey): String {
        val out = ByteArrayOutputStream()
        out.write(keyMagic, 0, keyMagic.size)
        out.write(VERSION)
        out.write(ALGORITHM_X25519_HKDF_AES_GCM)
        writeString(out, key.keyId, MAX_KEY_ID_BYTES)
        val material = key.materialCopy()
        out.write(material, 0, material.size)
        material.fill(0)
        return KEY_PREFIX + Base64Url.encode(out.toByteArray())
    }

    /** Parses and fully validates a public key encoding before it can be used for ECDH. */
    fun parsePublicKey(encoded: String): RelayPublicKey {
        require(encoded.length in (KEY_PREFIX.length + 1)..8192) { "Invalid relay key" }
        require(encoded.startsWith(KEY_PREFIX)) { "Invalid relay key" }
        val bytes = Base64Url.decode(encoded.substring(KEY_PREFIX.length))
        val cursor = ByteCursor(bytes)
        cursor.expect(keyMagic)
        require(cursor.readUnsignedByte() == VERSION) { "Unsupported relay key" }
        require(cursor.readUnsignedByte() == ALGORITHM_X25519_HKDF_AES_GCM) { "Unsupported relay key" }
        val keyId = cursor.readString(MAX_KEY_ID_BYTES)
        val material = cursor.readBytes(X25519_KEY_BYTES)
        require(cursor.remaining == 0) { "Invalid relay key" }
        return RelayPublicKey(keyId, material)
    }

    /**
     * Derives the per-message AES-256 key from either side of the same X25519 exchange.
     * Callers should discard the returned key after use; this method never logs key material.
     */
    fun deriveAesKey(
        localPrivateKey: RelayPrivateKey,
        peerPublicKey: RelayPublicKey,
        ownerId: String,
        employeeId: String,
        messageId: String
    ): SecretKey {
        validateAadIds(ownerId, employeeId, messageId)
        val privateMaterial = localPrivateKey.materialCopy()
        val peerMaterial = peerPublicKey.materialCopy()
        val sharedSecret = ByteArray(X25519_KEY_BYTES)
        try {
            X25519PrivateKeyParameters(privateMaterial).generateSecret(
                X25519PublicKeyParameters(peerMaterial), sharedSecret, 0
            )
            require(!isAllZero(sharedSecret)) { "Invalid relay shared secret" }
            val aad = associatedData(ownerId, employeeId, messageId)
            val info = concat("RLYH".toByteArray(UTF_8), byteArrayOf(VERSION.toByte()), aad)
            val prk = hmacSha256(hkdfSalt, sharedSecret)
            val okm = hmacSha256(prk, info + byteArrayOf(1))
            return SecretKeySpec(okm.copyOf(AES_KEY_BYTES), "AES").also {
                okm.fill(0)
                prk.fill(0)
                info.fill(0)
                aad.fill(0)
            }
        } finally {
            privateMaterial.fill(0)
            peerMaterial.fill(0)
            sharedSecret.fill(0)
        }
    }

    /** Encrypts plaintext into an opaque, versioned relay envelope. */
    fun encrypt(
        ownerId: String,
        employeeId: String,
        messageId: String,
        plaintext: ByteArray,
        sender: RelayKeyPair,
        recipient: RelayPublicKey
    ): String {
        validateAadIds(ownerId, employeeId, messageId)
        validatePlaintext(plaintext)
        require(sender.keyId == sender.privateKey.keyId && sender.keyId == sender.publicKey.keyId) {
            "Invalid relay key pair"
        }
        val key = deriveAesKey(sender.privateKey, recipient, ownerId, employeeId, messageId)
        val nonce = ByteArray(GCM_NONCE_BYTES).also(random::nextBytes)
        val aad = associatedData(ownerId, employeeId, messageId)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(aad)
            val ciphertext = cipher.doFinal(plaintext)
            RelayEnvelope(
                ownerId,
                employeeId,
                messageId,
                sender.keyId,
                recipient.keyId,
                nonce,
                ciphertext
            ).encode()
        } catch (_: GeneralSecurityException) {
            throw RelayCryptoException("Relay encryption is unavailable")
        } finally {
            aad.fill(0)
            nonce.fill(0)
            key.encoded?.fill(0)
        }
    }

    /** Decrypts an envelope using its encoded metadata, verifying GCM authentication first. */
    fun decrypt(
        encodedEnvelope: String,
        recipient: RelayKeyPair,
        senderPublicKey: RelayPublicKey
    ): ByteArray {
        val envelope = parseEnvelope(encodedEnvelope)
        return decrypt(envelope, recipient, senderPublicKey)
    }

    /**
     * Decrypts only when the caller's expected IDs exactly match the authenticated envelope
     * metadata. This overload is the recommended network-facing entry point.
     */
    fun decrypt(
        encodedEnvelope: String,
        recipient: RelayKeyPair,
        senderPublicKey: RelayPublicKey,
        expectedOwnerId: String,
        expectedEmployeeId: String,
        expectedMessageId: String
    ): ByteArray {
        val envelope = parseEnvelope(encodedEnvelope)
        validateAadIds(expectedOwnerId, expectedEmployeeId, expectedMessageId)
        require(
            envelope.ownerId == expectedOwnerId &&
                envelope.employeeId == expectedEmployeeId &&
                envelope.messageId == expectedMessageId
        ) { "Relay envelope IDs do not match" }
        return decrypt(envelope, recipient, senderPublicKey)
    }

    /** Parses the envelope framing; GCM authentication is performed by [decrypt]. */
    fun parseEnvelope(encoded: String): RelayEnvelope {
        require(encoded.length in (ENVELOPE_PREFIX.length + 1)..MAX_ENCODED_ENVELOPE_CHARS) {
            "Invalid relay envelope"
        }
        require(encoded.startsWith(ENVELOPE_PREFIX)) { "Invalid relay envelope" }
        val bytes = Base64Url.decode(encoded.substring(ENVELOPE_PREFIX.length))
        require(bytes.size <= MAX_ENVELOPE_BYTES) { "Relay envelope is too large" }
        val cursor = ByteCursor(bytes)
        cursor.expect(envelopeMagic)
        require(cursor.readUnsignedByte() == VERSION) { "Unsupported relay envelope" }
        require(cursor.readUnsignedByte() == ALGORITHM_X25519_HKDF_AES_GCM) {
            "Unsupported relay envelope"
        }
        val ownerId = cursor.readString(MAX_IDENTIFIER_BYTES)
        val employeeId = cursor.readString(MAX_IDENTIFIER_BYTES)
        val messageId = cursor.readString(MAX_IDENTIFIER_BYTES)
        val senderKeyId = cursor.readString(MAX_KEY_ID_BYTES)
        val recipientKeyId = cursor.readString(MAX_KEY_ID_BYTES)
        validateAadIds(ownerId, employeeId, messageId)
        validateKeyId(senderKeyId)
        validateKeyId(recipientKeyId)
        val nonce = cursor.readBytes(GCM_NONCE_BYTES)
        val ciphertextLength = cursor.readInt()
        require(ciphertextLength in 16..(MAX_PLAINTEXT_BYTES + 16)) { "Invalid relay envelope" }
        val ciphertext = cursor.readBytes(ciphertextLength)
        require(cursor.remaining == 0) { "Invalid relay envelope" }
        return RelayEnvelope(
            ownerId,
            employeeId,
            messageId,
            senderKeyId,
            recipientKeyId,
            nonce,
            ciphertext
        )
    }

    internal fun encodeEnvelope(envelope: RelayEnvelope): String {
        validateAadIds(envelope.ownerId, envelope.employeeId, envelope.messageId)
        validateKeyId(envelope.senderKeyId)
        validateKeyId(envelope.recipientKeyId)
        val nonce = envelope.nonceCopy()
        val ciphertext = envelope.ciphertextCopy()
        require(nonce.size == GCM_NONCE_BYTES) { "Invalid relay envelope" }
        require(ciphertext.size in 16..(MAX_PLAINTEXT_BYTES + 16)) { "Invalid relay envelope" }
        val out = ByteArrayOutputStream()
        out.write(envelopeMagic, 0, envelopeMagic.size)
        out.write(VERSION)
        out.write(ALGORITHM_X25519_HKDF_AES_GCM)
        writeString(out, envelope.ownerId, MAX_IDENTIFIER_BYTES)
        writeString(out, envelope.employeeId, MAX_IDENTIFIER_BYTES)
        writeString(out, envelope.messageId, MAX_IDENTIFIER_BYTES)
        writeString(out, envelope.senderKeyId, MAX_KEY_ID_BYTES)
        writeString(out, envelope.recipientKeyId, MAX_KEY_ID_BYTES)
        out.write(nonce, 0, nonce.size)
        writeInt(out, ciphertext.size)
        out.write(ciphertext, 0, ciphertext.size)
        nonce.fill(0)
        ciphertext.fill(0)
        return ENVELOPE_PREFIX + Base64Url.encode(out.toByteArray())
    }

    internal fun encodeStoredPrivateKey(keyPair: RelayKeyPair): ByteArray {
        require(keyPair.keyId == keyPair.privateKey.keyId && keyPair.keyId == keyPair.publicKey.keyId) {
            "Invalid relay key pair"
        }
        val privateMaterial = keyPair.privateKey.materialCopy()
        val derivedPublic = X25519PrivateKeyParameters(privateMaterial).generatePublicKey().encoded
        val storedPublic = keyPair.publicKey.materialCopy()
        try {
            require(derivedPublic.contentEquals(storedPublic)) { "Invalid relay key pair" }
            val out = ByteArrayOutputStream()
            out.write(storageMagic, 0, storageMagic.size)
            out.write(VERSION)
            writeString(out, keyPair.keyId, MAX_KEY_ID_BYTES)
            out.write(privateMaterial, 0, privateMaterial.size)
            return out.toByteArray()
        } finally {
            privateMaterial.fill(0)
            derivedPublic.fill(0)
            storedPublic.fill(0)
        }
    }

    internal fun decodeStoredPrivateKey(bytes: ByteArray): RelayKeyPair {
        require(bytes.size in 4..4096) { "Invalid relay key" }
        val cursor = ByteCursor(bytes)
        cursor.expect(storageMagic)
        require(cursor.readUnsignedByte() == VERSION) { "Unsupported relay key" }
        val keyId = cursor.readString(MAX_KEY_ID_BYTES)
        val privateMaterial = cursor.readBytes(X25519_KEY_BYTES)
        require(cursor.remaining == 0) { "Invalid relay key" }
        return try {
            val publicMaterial = X25519PrivateKeyParameters(privateMaterial).generatePublicKey().encoded
            RelayKeyPair(
                keyId,
                RelayPrivateKey(keyId, privateMaterial),
                RelayPublicKey(keyId, publicMaterial)
            )
        } finally {
            privateMaterial.fill(0)
        }
    }

    internal fun storageAad(alias: String): ByteArray =
        concat(STORAGE_DOMAIN.toByteArray(UTF_8), byteArrayOf(0), alias.toByteArray(UTF_8))

    internal fun validateKeyId(value: String) {
        validateIdentifier(value, MAX_KEY_ID_BYTES)
    }

    internal fun isAllZero(bytes: ByteArray): Boolean {
        var combined = 0
        bytes.forEach { combined = combined or (it.toInt() and 0xff) }
        return combined == 0
    }

    private fun decrypt(
        envelope: RelayEnvelope,
        recipient: RelayKeyPair,
        senderPublicKey: RelayPublicKey
    ): ByteArray {
        require(envelope.recipientKeyId == recipient.keyId) { "Relay recipient key mismatch" }
        require(envelope.senderKeyId == senderPublicKey.keyId) { "Relay sender key mismatch" }
        val key = deriveAesKey(
            recipient.privateKey,
            senderPublicKey,
            envelope.ownerId,
            envelope.employeeId,
            envelope.messageId
        )
        val aad = associatedData(envelope.ownerId, envelope.employeeId, envelope.messageId)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, envelope.nonceCopy()))
            cipher.updateAAD(aad)
            cipher.doFinal(envelope.ciphertextCopy()).also { validatePlaintext(it) }
        } catch (_: GeneralSecurityException) {
            throw RelayCryptoException("Relay envelope authentication failed")
        } finally {
            aad.fill(0)
            key.encoded?.fill(0)
        }
    }

    private fun associatedData(ownerId: String, employeeId: String, messageId: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(aadMagic, 0, aadMagic.size)
        out.write(VERSION)
        writeString(out, ownerId, MAX_IDENTIFIER_BYTES)
        writeString(out, employeeId, MAX_IDENTIFIER_BYTES)
        writeString(out, messageId, MAX_IDENTIFIER_BYTES)
        return out.toByteArray()
    }

    private fun validateAadIds(ownerId: String, employeeId: String, messageId: String) {
        validateIdentifier(ownerId, MAX_IDENTIFIER_BYTES)
        validateIdentifier(employeeId, MAX_IDENTIFIER_BYTES)
        validateIdentifier(messageId, MAX_IDENTIFIER_BYTES)
        require(ownerId != employeeId) { "Invalid relay IDs" }
    }

    private fun validatePlaintext(plaintext: ByteArray) {
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "Relay plaintext is too large" }
    }

    private fun validateIdentifier(value: String, maxBytes: Int) {
        require(value.isNotEmpty() && value == value.trim()) { "Invalid relay identifier" }
        val bytes = value.toByteArray(UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= maxBytes) { "Invalid relay identifier" }
        require(String(bytes, UTF_8) == value) { "Invalid relay identifier" }
        require(value.none { it.isISOControl() }) { "Invalid relay identifier" }
    }

    private fun writeString(out: ByteArrayOutputStream, value: String, maxBytes: Int) {
        val bytes = value.toByteArray(UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= maxBytes && bytes.size <= 0xffff) {
            "Invalid relay identifier"
        }
        out.write(bytes.size ushr 8)
        out.write(bytes.size and 0xff)
        out.write(bytes, 0, bytes.size)
    }

    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        require(value >= 0)
        out.write(value ushr 24)
        out.write(value ushr 16)
        out.write(value ushr 8)
        out.write(value)
    }

    private fun hmacSha256(key: ByteArray, input: ByteArray): ByteArray = try {
        Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(key, "HmacSHA256"))
        }.doFinal(input)
    } catch (_: GeneralSecurityException) {
        throw RelayCryptoException("Relay key derivation is unavailable")
    }

    private fun sha256(input: ByteArray): ByteArray = try {
        java.security.MessageDigest.getInstance("SHA-256").digest(input)
    } catch (_: GeneralSecurityException) {
        throw RelayCryptoException("Relay hashing is unavailable")
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val size = parts.sumOf { it.size }
        val result = ByteArray(size)
        var offset = 0
        parts.forEach {
            it.copyInto(result, offset)
            offset += it.size
        }
        return result
    }

    private class ByteCursor(private val bytes: ByteArray) {
        private var offset = 0

        val remaining: Int
            get() = bytes.size - offset

        fun expect(expected: ByteArray) {
            require(remaining >= expected.size && bytes.copyOfRange(offset, offset + expected.size)
                .contentEquals(expected)) { "Invalid relay encoding" }
            offset += expected.size
        }

        fun readUnsignedByte(): Int {
            require(remaining >= 1) { "Invalid relay encoding" }
            return bytes[offset++].toInt() and 0xff
        }

        fun readInt(): Int {
            require(remaining >= 4) { "Invalid relay encoding" }
            val result = ((bytes[offset].toInt() and 0xff) shl 24) or
                ((bytes[offset + 1].toInt() and 0xff) shl 16) or
                ((bytes[offset + 2].toInt() and 0xff) shl 8) or
                (bytes[offset + 3].toInt() and 0xff)
            offset += 4
            return result
        }

        fun readBytes(length: Int): ByteArray {
            require(length >= 0 && length <= remaining) { "Invalid relay encoding" }
            return bytes.copyOfRange(offset, offset + length).also { offset += length }
        }

        fun readString(maxBytes: Int): String {
            val length = (readUnsignedByte() shl 8) or readUnsignedByte()
            val raw = readBytes(length)
            require(raw.isNotEmpty() && raw.size <= maxBytes) { "Invalid relay identifier" }
            val value = String(raw, UTF_8)
            require(value.toByteArray(UTF_8).contentEquals(raw)) { "Invalid relay identifier" }
            validateIdentifier(value, maxBytes)
            return value
        }
    }

    private object Base64Url {
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        fun encode(bytes: ByteArray): String = buildString((bytes.size * 8 + 5) / 6) {
            var buffer = 0
            var bits = 0
            bytes.forEach { byte ->
                buffer = (buffer shl 8) or (byte.toInt() and 0xff)
                bits += 8
                while (bits >= 6) {
                    bits -= 6
                    append(ALPHABET[(buffer ushr bits) and 63])
                }
                buffer = if (bits == 0) 0 else buffer and ((1 shl bits) - 1)
            }
            if (bits > 0) append(ALPHABET[(buffer shl (6 - bits)) and 63])
        }

        fun decode(value: String): ByteArray {
            require(value.isNotEmpty() && value.length % 4 != 1) { "Invalid relay encoding" }
            val output = ByteArrayOutputStream(value.length * 3 / 4)
            var buffer = 0
            var bits = 0
            value.forEach { character ->
                val index = ALPHABET.indexOf(character)
                require(index >= 0) { "Invalid relay encoding" }
                buffer = (buffer shl 6) or index
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    output.write((buffer ushr bits) and 0xff)
                    buffer = if (bits == 0) 0 else buffer and ((1 shl bits) - 1)
                }
            }
            val decoded = output.toByteArray()
            require(encode(decoded) == value) { "Non-canonical relay encoding" }
            return decoded
        }
    }
}

/**
 * Persistent key abstraction. Implementations must keep private material out of logs and should
 * fail closed if the stored value cannot be authenticated or parsed.
 */
interface RelayPrivateKeyStorage {
    fun read(): RelayKeyPair?
    fun write(keyPair: RelayKeyPair)
    fun clear()
}

/**
 * Android Keystore-backed storage for the X25519 identity key.
 *
 * API 24 does not consistently expose X25519 as a KeyPairGenerator algorithm. The X25519 private
 * bytes are therefore encrypted with a non-exportable Android Keystore AES-256-GCM key. Only the
 * authenticated ciphertext is stored in SharedPreferences; the X25519 key is materialized in
 * memory only when a caller requests it.
 */
class AndroidKeystoreRelayPrivateKeyStorage(
    context: Context,
    private val alias: String = DEFAULT_KEYSTORE_ALIAS
) : RelayPrivateKeyStorage {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    init {
        require(alias.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid relay storage alias" }
    }

    @Synchronized
    override fun read(): RelayKeyPair? = try {
        val stored = preferences.getString(ENTRY_NAME, null) ?: return null
        RelayCrypto.decodeStoredPrivateKey(unprotect(Base64UrlStorage.decode(stored)))
    } catch (_: Exception) {
        throw RelayCryptoException("Local relay key is unavailable")
    }

    @Synchronized
    override fun write(keyPair: RelayKeyPair) {
        try {
            val encoded = RelayCrypto.encodeStoredPrivateKey(keyPair)
            val protected = protect(encoded)
            encoded.fill(0)
            check(preferences.edit().putString(ENTRY_NAME, Base64UrlStorage.encode(protected)).commit())
            protected.fill(0)
        } catch (_: RelayCryptoException) {
            throw RelayCryptoException("Local relay key could not be stored")
        } catch (_: Exception) {
            throw RelayCryptoException("Local relay key could not be stored")
        }
    }

    @Synchronized
    override fun clear() {
        try {
            check(preferences.edit().remove(ENTRY_NAME).commit())
            val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (store.containsAlias(alias)) store.deleteEntry(alias)
        } catch (_: Exception) {
            throw RelayCryptoException("Local relay key could not be cleared")
        }
    }

    private fun protect(plain: ByteArray): ByteArray {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            // The wrapping key requires randomized encryption, so Android rejects a caller-supplied
            // IV here (keystore2 CALLER_NONCE_PROHIBITED). The nonce therefore comes from the
            // Keystore and is stored next to the ciphertext for [unprotect]. Encryption without an
            // explicit spec is the only accepted form; decryption still takes the stored nonce.
            cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
            val nonce = requireNotNull(cipher.iv) { "Invalid relay storage" }
            require(nonce.size == RelayCrypto.GCM_NONCE_BYTES) { "Invalid relay storage" }
            cipher.updateAAD(RelayCrypto.storageAad(alias))
            val ciphertext = cipher.doFinal(plain)
            ByteArrayOutputStream().apply {
                write(STORAGE_VERSION)
                write(nonce, 0, nonce.size)
                write(ciphertext, 0, ciphertext.size)
            }.toByteArray()
        } catch (_: GeneralSecurityException) {
            throw RelayCryptoException("Local relay key could not be stored")
        }
    }

    private fun unprotect(stored: ByteArray): ByteArray {
        require(stored.size > RelayCrypto.GCM_NONCE_BYTES + 16) { "Invalid relay storage" }
        require(stored[0].toInt() == STORAGE_VERSION) { "Unsupported relay storage" }
        val nonce = stored.copyOfRange(1, 1 + RelayCrypto.GCM_NONCE_BYTES)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                keystoreKey(),
                GCMParameterSpec(128, nonce)
            )
            cipher.updateAAD(RelayCrypto.storageAad(alias))
            cipher.doFinal(stored.copyOfRange(1 + RelayCrypto.GCM_NONCE_BYTES, stored.size))
        } catch (_: GeneralSecurityException) {
            throw RelayCryptoException("Local relay key is unavailable")
        } finally {
            nonce.fill(0)
        }
    }

    private fun keystoreKey(): SecretKey = synchronized(KEYSTORE_LOCK) {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
                init(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setKeySize(256)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build()
                )
            }.generateKey()
    }

    private object Base64UrlStorage {
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        fun encode(bytes: ByteArray): String = buildString {
            var buffer = 0
            var bits = 0
            bytes.forEach {
                buffer = (buffer shl 8) or (it.toInt() and 0xff)
                bits += 8
                while (bits >= 6) {
                    bits -= 6
                    append(ALPHABET[(buffer ushr bits) and 63])
                }
                buffer = if (bits == 0) 0 else buffer and ((1 shl bits) - 1)
            }
            if (bits > 0) append(ALPHABET[(buffer shl (6 - bits)) and 63])
        }

        fun decode(value: String): ByteArray {
            require(value.isNotEmpty() && value.length <= 4096 && value.length % 4 != 1) {
                "Invalid relay storage"
            }
            val output = ByteArrayOutputStream(value.length * 3 / 4)
            var buffer = 0
            var bits = 0
            value.forEach {
                val index = ALPHABET.indexOf(it)
                require(index >= 0) { "Invalid relay storage" }
                buffer = (buffer shl 6) or index
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    output.write((buffer ushr bits) and 0xff)
                    buffer = if (bits == 0) 0 else buffer and ((1 shl bits) - 1)
                }
            }
            val decoded = output.toByteArray()
            require(encode(decoded) == value) { "Invalid relay storage" }
            return decoded
        }
    }

    private companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val PREFERENCES_NAME = "relay_private"
        private const val ENTRY_NAME = "x25519_identity"
        private const val STORAGE_VERSION = 1
        private const val DEFAULT_KEYSTORE_ALIAS = "payalert.relay.x25519.wrap.v1"
        private val KEYSTORE_LOCK = Any()
    }
}
