package com.vivekray898.payalert.team

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale

/** Only fixed, user-facing messages cross the client boundary. Never attach a response/cause. */
internal class TeamClientException(message: String) : Exception(message)

internal class TeamConfig private constructor(val origin: String, val publishableKey: String) {
    companion object {
        fun parse(url: String, key: String): TeamConfig? = try {
            val uri = URI(url.trim())
            val publicKey = key.trim()
            if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
                uri.port !in listOf(-1, 443) || uri.rawQuery != null || uri.rawFragment != null ||
                uri.rawPath !in listOf("", "/") || !isPublicKey(publicKey)
            ) null else TeamConfig("https://${uri.host.lowercase(Locale.ROOT)}", publicKey)
        } catch (_: Exception) {
            null
        }

        private fun isPublicKey(key: String): Boolean {
            if (key.length !in 20..4096) return false
            if (key.matches(Regex("sb_publishable_[A-Za-z0-9_-]+"))) return true
            // Legacy anon JWTs remain public keys; service_role/secret keys are never accepted.
            val parts = key.split('.')
            if (parts.size != 3 || parts.any { !it.matches(Regex("[A-Za-z0-9_-]+")) }) return false
            return try {
                JsonParser.parseString(String(TeamEncoding.decode(parts[1]), UTF_8))
                    .asJsonObject.stringOrNull("role") == "anon"
            } catch (_: Exception) {
                false
            }
        }
    }
}

/** Small base64url codec, also available on Android 24/25 (java.util.Base64 needs API 26). */
internal object TeamEncoding {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    fun encode(bytes: ByteArray): String = buildString {
        var bits = 0
        var count = 0
        for (byte in bytes) {
            bits = (bits shl 8) or (byte.toInt() and 255)
            count += 8
            while (count >= 6) {
                count -= 6
                append(ALPHABET[(bits ushr count) and 63])
            }
        }
        if (count > 0) append(ALPHABET[(bits shl (6 - count)) and 63])
    }

    fun decode(value: String): ByteArray {
        require(value.length % 4 != 1)
        val output = ArrayList<Byte>()
        var bits = 0
        var count = 0
        value.forEach {
            val index = ALPHABET.indexOf(it)
            require(index >= 0)
            bits = (bits shl 6) or index
            count += 6
            if (count >= 8) {
                count -= 8
                output.add((bits ushr count).toByte())
            }
        }
        return output.toByteArray()
    }
}

internal data class TeamPkce(val origin: String, val verifier: String, val createdAtMillis: Long)
internal data class TeamSession(
    val origin: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
    val account: TeamAccount
)
internal data class TeamAuthState(val session: TeamSession? = null, val pending: TeamPkce? = null)

internal object TeamOAuth {
    const val CALLBACK = "com.vivekray898.payalert://auth/callback"
    const val TRANSACTION_LIFETIME_MILLIS = 10 * 60 * 1000L
    private val random = SecureRandom()

    fun transaction(origin: String, now: Long): TeamPkce =
        TeamPkce(origin, TeamEncoding.encode(ByteArray(32).also(random::nextBytes)), now)

    fun challenge(verifier: String): String =
        TeamEncoding.encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(UTF_8)))

    fun authorizeUrl(config: TeamConfig, pending: TeamPkce): String =
        "${config.origin}/auth/v1/authorize?provider=google&redirect_to=${encode(CALLBACK)}" +
            "&code_challenge=${challenge(pending.verifier)}&code_challenge_method=s256"

    /** The pending verifier binds the transaction at GoTrue's PKCE exchange, not a caller's state. */
    fun callbackCode(url: String, pending: TeamPkce?, origin: String, now: Long): String {
        fun invalid(): Nothing = throw TeamClientException("Sign-in link is invalid or expired. Start Google sign-in again.")
        if (pending == null || pending.origin != origin || pending.createdAtMillis > now ||
            now - pending.createdAtMillis >= TRANSACTION_LIFETIME_MILLIS ||
            !pending.verifier.matches(Regex("[A-Za-z0-9_-]{43,128}")) || url.length > 8192
        ) invalid()
        try {
            val uri = URI(url)
            if (uri.scheme != "com.vivekray898.payalert" || uri.rawAuthority != "auth" ||
                uri.rawPath != "/callback" || uri.rawFragment != null || uri.isOpaque
            ) invalid()
            val query = uri.rawQuery ?: invalid()
            val parameters = linkedMapOf<String, String>()
            query.split('&').forEach { part ->
                val pair = part.split('=', limit = 2)
                val name = URLDecoder.decode(pair[0], "UTF-8")
                if (pair.size != 2 || parameters.containsKey(name)) invalid()
                parameters[name] = URLDecoder.decode(pair[1], "UTF-8")
            }
            if (parameters.keys.any { it.startsWith("error", ignoreCase = true) } ||
                parameters.keys.any { it !in setOf("code", "state") }
            ) invalid()
            val code = parameters["code"] ?: invalid()
            if (!code.matches(Regex("[A-Za-z0-9._~-]{1,2048}"))) invalid()
            return code
        } catch (error: TeamClientException) {
            throw error
        } catch (_: Exception) {
            invalid()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

internal data class TeamHttpRequest(val url: String, val headers: Map<String, String>, val body: String)
internal data class TeamHttpResponse(val status: Int, val body: String)
internal fun interface TeamTransport {
    fun execute(request: TeamHttpRequest): TeamHttpResponse
}

internal object TeamProtocol {
    private const val INVALID_RESPONSE = "The team service returned an unexpected response. Please try again later."

    fun json(body: String): JsonObject = try {
        JsonParser.parseString(body).asJsonObject
    } catch (_: Exception) {
        throw TeamClientException(INVALID_RESPONSE)
    }

    fun requireSuccess(response: TeamHttpResponse) {
        if (response.status !in 200..299) throw TeamClientException(httpMessage(response.status))
    }

    fun httpMessage(status: Int): String = when (status) {
        401 -> "Your session could not be verified. Please sign in again."
        403 -> "This action is not allowed for your account."
        408, 504 -> "The team service timed out. Please try again."
        429 -> "Too many attempts. Please wait and try again."
        in 300..399 -> "The team service redirected the request. Check the Supabase configuration."
        in 500..599 -> "The team service is unavailable. Please try again later."
        else -> "The team request could not be completed. Check your details and try again."
    }

    fun invalidRefresh(response: TeamHttpResponse): Boolean {
        if (response.status !in setOf(400, 401)) return false
        val body = try { json(response.body) } catch (_: TeamClientException) { return false }
        val code = body.stringOrNull("error_code") ?: body.stringOrNull("code")
        if (code in setOf("refresh_token_not_found", "refresh_token_already_used", "refresh_token_expired",
                "invalid_refresh_token", "session_not_found", "session_expired")) return true
        // Older GoTrue versions identify invalid refresh tokens with this exact error family.
        return body.stringOrNull("error") == "invalid_grant" &&
            body.stringOrNull("error_description") in setOf(
                "Invalid Refresh Token: Refresh Token Not Found",
                "Invalid Refresh Token: Already Used", "Invalid Refresh Token: Refresh Token Expired"
            )
    }

    fun needsRefresh(session: TeamSession, now: Long): Boolean = session.expiresAtMillis <= now + 60_000L

    fun session(body: String, origin: String, now: Long): TeamSession = parse {
        val json = json(body)
        val user = json.getAsJsonObject("user")
        val userId = user.requiredString("id")
        val access = json.requiredString("access_token")
        val refresh = json.requiredString("refresh_token")
        require(access.matches(Regex("[A-Za-z0-9._~-]+")) && access.length <= 16384 && refresh.length <= 16384)
        val seconds = json.get("expires_at")?.asLong
            ?: Math.addExact(now / 1000, json.get("expires_in").asLong.also { require(it in 1..604800) })
        val expiry = Math.multiplyExact(seconds, 1000L)
        require(expiry > now)
        val metadata = user.get("user_metadata")?.takeIf { it.isJsonObject }?.asJsonObject
        val name = metadata?.stringOrNull("full_name") ?: metadata?.stringOrNull("name")
            ?: user.stringOrNull("email") ?: "Google account"
        TeamSession(origin, access, refresh, expiry,
            TeamAccount(userId, name.filterNot { it.isISOControl() }.take(160)))
    }

    fun snapshot(body: String): TeamSnapshot = parse {
        val root = json(body)
        val device = root.getAsJsonObject("device")
        val employees = root.getAsJsonArray("employees").map { connection(it.asJsonObject) }
        require(root.has("owner"))
        val owner = root.get("owner").takeUnless { it.isJsonNull }?.asJsonObject?.let(::connection)
        TeamSnapshot(TeamDevice(device.requiredString("id"), device.requiredString("name")), employees, owner)
    }

    fun invite(body: String): TeamInvite = parse {
        val root = json(body)
        val code = root.requiredString("code")
        require(code.matches(Regex("[A-F0-9]{12}")))
        val expiresAt = root.requiredString("expires_at")
        require(expiresAt.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:\\d{2})")))
        val normalized = expiresAt.replace(Regex("\\.\\d+(?=Z|[+-])"), "")
        val position = ParsePosition(0)
        val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).apply { isLenient = false }
            .parse(normalized, position)
        require(date != null && position.index == normalized.length)
        TeamInvite(code, expiresAt)
    }

    fun requireJoinSuccess(body: String) {
        if (body.isBlank() || body.trim() == "null") return
        val root = json(body)
        if (root.has("error")) {
            val message = when (root.stringOrNull("error")) {
                "invalid_invite" -> "This pairing code is invalid, expired, or already used. Ask the owner for a new code."
                "self_pairing" -> "You cannot pair a device with itself. Enter this code on the employee's phone."
                "already_paired" -> "This device is already connected to an owner. Leave that team before joining another."
                "device_is_owner" -> "Remove employee connections and cancel your own pairing code before joining another owner."
                "owner_is_employee" -> "That device is connected as an employee and cannot invite other devices."
                "rate_limited" -> "Too many pairing attempts. Wait 10 minutes, then ask the owner for a fresh code."
                else -> "Unable to join this team. Refresh devices, then check the pairing code and try again."
            }
            throw TeamClientException(message)
        }
    }

    private fun connection(json: JsonObject): TeamConnection = TeamConnection(
        json.requiredString("pairing_id"), json.requiredString("device_id"), json.requiredString("device_name")
    )

    private fun <T> parse(block: () -> T): T = try {
        block()
    } catch (_: Exception) {
        throw TeamClientException(INVALID_RESPONSE)
    }
}

internal fun JsonObject.stringOrNull(name: String): String? = get(name)?.let {
    if (it.isJsonPrimitive && it.asJsonPrimitive.isString) it.asString.takeIf(String::isNotBlank) else null
}

private fun JsonObject.requiredString(name: String): String =
    requireNotNull(stringOrNull(name)).also { require(it.length <= 16384) }
