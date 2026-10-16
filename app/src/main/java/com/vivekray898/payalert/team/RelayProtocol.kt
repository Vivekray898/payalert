package com.vivekray898.payalert.team

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.vivekray898.payalert.relay.RelayInboxMessage
import com.vivekray898.payalert.relay.RelayKeyNotBoundException
import com.vivekray898.payalert.relay.RelayPeerKeyInfo
import com.vivekray898.payalert.relay.RelayTransportException

/**
 * Encoding and error mapping for the encrypted relay RPCs.
 *
 * PostgREST moves `bytea` as a `\x`-prefixed hex string, so envelopes and public keys are converted
 * here and nowhere else. Server error text is mapped to fixed, caller-facing messages: no server
 * string is shown verbatim and no envelope content is ever parsed for display.
 */
internal object RelayProtocol {

    private const val INVALID_RESPONSE =
        "The pairing service returned an unexpected response. Please try again."

    /** `bytea` as PostgREST expects it in a JSON body. */
    fun bytea(bytes: ByteArray): String {
        val builder = StringBuilder(2 + bytes.size * 2)
        builder.append('\\').append('x')
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            builder.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
        }
        return builder.toString()
    }

    /** Parses the `\x`-prefixed hex form PostgREST returns for `bytea` columns. */
    fun decodeBytea(value: String): ByteArray {
        val prefix = value.indexOf('x')
        if (prefix < 0 || !value.startsWith("\\")) throw RelayTransportException(INVALID_RESPONSE)
        return decodeHex(value.substring(prefix + 1))
    }

    /** Parses a plain hex string, as the relay RPCs return public keys. */
    fun decodeHex(value: String): ByteArray {
        if (value.length % 2 != 0) throw RelayTransportException(INVALID_RESPONSE)
        val output = ByteArray(value.length / 2)
        for (index in output.indices) {
            val high = hexDigit(value[index * 2])
            val low = hexDigit(value[index * 2 + 1])
            output[index] = ((high shl 4) or low).toByte()
        }
        return output
    }

    /** The bound owner key as seen by the employee device, or null when nothing is bound. */
    fun peerKey(body: String): RelayPeerKeyInfo? {
        // PostgREST serialises a SQL NULL result as the literal null, which means "nothing bound".
        if (body.isBlank() || body.trim() == "null") return null
        val root = objectOf(body)
        val deviceId = root.stringOrNull("owner_device_id") ?: return null
        val keyId = root.stringOrNull("owner_key_id") ?: return null
        val publicKey = root.stringOrNull("owner_public_key_hex") ?: return null
        return RelayPeerKeyInfo(
            deviceId = deviceId,
            keyId = keyId,
            publicKey = String(decodeHex(publicKey), Charsets.UTF_8)
        )
    }

    /** The employee key the owner just bound, with the employee's registered public key. */
    fun boundPeerKey(body: String, employeeDeviceId: String): RelayPeerKeyInfo {
        val root = objectOf(body)
        val keyId = root.stringOrNull("employee_key_id")
            ?: throw RelayTransportException(INVALID_RESPONSE)
        val publicKey = root.stringOrNull("peer_public_key_hex")
            ?: throw RelayTransportException(INVALID_RESPONSE)
        return RelayPeerKeyInfo(
            deviceId = employeeDeviceId,
            keyId = keyId,
            publicKey = String(decodeHex(publicKey), Charsets.UTF_8)
        )
    }

    fun inbox(body: String): List<RelayInboxMessage> {
        val parsed = try {
            JsonParser.parseString(body)
        } catch (_: Exception) {
            throw RelayTransportException(INVALID_RESPONSE)
        }
        if (parsed.isJsonNull) return emptyList()
        if (!parsed.isJsonArray) throw RelayTransportException(INVALID_RESPONSE)
        return parsed.asJsonArray.map { element ->
            val row = element.takeIf { it.isJsonObject }?.asJsonObject
                ?: throw RelayTransportException(INVALID_RESPONSE)
            RelayInboxMessage(
                serverMessageId = row.stringOrNull("message_id")
                    ?: throw RelayTransportException(INVALID_RESPONSE),
                messageId = row.stringOrNull("client_message_id")
                    ?: throw RelayTransportException(INVALID_RESPONSE),
                envelope = String(
                    decodeBytea(
                        row.stringOrNull("encrypted_envelope")
                            ?: throw RelayTransportException(INVALID_RESPONSE)
                    ),
                    Charsets.UTF_8
                ),
                leaseToken = row.stringOrNull("lease_token")
                    ?: throw RelayTransportException(INVALID_RESPONSE)
            )
        }
    }

    fun boolean(body: String): Boolean = body.trim().equals("true", ignoreCase = true)

    fun integer(body: String): Int = body.trim().toIntOrNull() ?: 0

    /**
     * Maps a failed relay response to a caller-facing failure.
     *
     * A missing or stale key binding is separated from a transport problem because the two need
     * different UI: one asks the user to re-pair, the other asks them to retry.
     */
    fun failure(response: TeamHttpResponse): RelayTransportException? {
        if (response.status in 200..299) return null
        val message = try {
            JsonParser.parseString(response.body).takeIf { it.isJsonObject }?.asJsonObject
                ?.stringOrNull("message")
        } catch (_: Exception) {
            null
        }
        return when (message) {
            "key_not_bound", "employee_key_not_current", "owner_key_not_registered",
            "pairing_not_authorized", "key_id_reuse" ->
                RelayKeyNotBoundException(
                    "This pairing needs to be refreshed before payments can be forwarded."
                )

            "authentication_required", "device_not_owned", "employee_device_not_owned" ->
                RelayTransportException("Sign in again to keep your paired device in sync.")

            "invalid_key", "invalid_relay_enqueue", "invalid_relay_fetch", "invalid_relay_ack",
            "invalid_peer_binding", "invalid_peer_lookup", "invalid_cleanup_limit" ->
                RelayTransportException("The pairing service rejected a relay request.")

            else -> RelayTransportException(TeamProtocol.httpMessage(response.status))
        }
    }

    private fun objectOf(body: String): JsonObject {
        val parsed = try {
            JsonParser.parseString(body)
        } catch (_: Exception) {
            throw RelayTransportException(INVALID_RESPONSE)
        }
        return parsed.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw RelayTransportException(INVALID_RESPONSE)
    }

    private fun hexDigit(character: Char): Int = when (character) {
        in '0'..'9' -> character - '0'
        in 'a'..'f' -> character - 'a' + 10
        in 'A'..'F' -> character - 'A' + 10
        else -> throw RelayTransportException(INVALID_RESPONSE)
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
