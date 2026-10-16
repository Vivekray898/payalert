package com.vivekray898.payalert.team

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.vivekray898.payalert.BuildConfig
import com.vivekray898.payalert.relay.RelayInboxMessage
import com.vivekray898.payalert.relay.RelayPeerKeyInfo
import com.vivekray898.payalert.relay.RelayTransport
import com.vivekray898.payalert.relay.RelayTransportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/**
 * Google sign-in is opened by the caller in the external browser, never a WebView.
 * Local sign-out revokes auth but deliberately does NOT remove server-side team pairings.
 * Disconnect a pairing explicitly with removeConnection; payment data always stays local.
 *
 * It also implements [RelayTransport] on the same authenticated session, so the rotating refresh
 * token stays under one mutex and the relay cannot race a sign-in or a refresh.
 */
class SupabaseTeamRepository internal constructor(
    private val config: TeamConfig?,
    private val storage: TeamSessionStorage,
    private val transport: TeamTransport,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis
) : TeamRepository, RelayTransport {
    constructor(context: Context) : this(
        TeamConfig.parse(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY),
        TeamSessionStore(context), HttpsTeamTransport()
    )

    override val configured: Boolean = config != null
    private val mutableAccount = MutableStateFlow<TeamAccount?>(null)
    override val account: StateFlow<TeamAccount?> = mutableAccount.asStateFlow()
    private val mutableAuthError = MutableStateFlow<String?>(null)
    override val authError: StateFlow<String?> = mutableAuthError.asStateFlow()
    private val mutex = Mutex()
    private val gson = Gson()

    /**
     * Relay RPC bodies must carry a nullable argument as an explicit JSON null.
     *
     * Gson drops null map entries unless serializing nulls is enabled - including an explicit
     * [com.google.gson.JsonNull], because the writer discards any deferred null. The relay RPCs take
     * several arguments with no SQL default (relay_bind_peer_key declares no default for
     * p_employee_key_id), so an omitted argument makes PostgREST reject the call before the function
     * runs and the owner can never bind its employee's key. Only relay calls use this instance.
     */
    private val relayGson = GsonBuilder().serializeNulls().create()
    private var auth = TeamAuthState()

    init {
        // Reading encrypted auth restores the account without requiring connectivity.
        try {
            val saved = storage.readAuth()
            auth = TeamAuthState(
                saved.session?.takeIf { configured && it.origin == config?.origin },
                saved.pending?.takeIf { configured && it.origin == config?.origin }
            )
            if (auth != saved) storage.writeAuth(auth)
            mutableAccount.value = auth.session?.account
        } catch (_: Exception) {
            mutableAuthError.value = "Saved sign-in could not be restored. Please sign in again."
            try { storage.clearAuth() } catch (_: Exception) { /* Safe error already exposed. */ }
        }
    }

    override suspend fun signInUrl(): String = onIo {
        val settings = requireConfig()
        val pending = TeamOAuth.transaction(settings.origin, now())
        saveAuth(auth.copy(pending = pending))
        mutableAuthError.value = null
        TeamOAuth.authorizeUrl(settings, pending)
    }

    override suspend fun completeSignIn(callbackUrl: String) {
        try {
            onIo {
                val settings = requireConfig()
                // Consume before validation/exchange. A callback can never replay a verifier,
                // including after process death, errors, cancellation or a lost token response.
                val pending = auth.pending
                saveAuth(auth.copy(pending = null))
                val code = TeamOAuth.callbackCode(callbackUrl, pending, settings.origin, now())
                val response = request("/auth/v1/token?grant_type=pkce", mapOf(
                    "auth_code" to code, "code_verifier" to requireNotNull(pending).verifier
                ))
                TeamProtocol.requireSuccess(response)
                val session = TeamProtocol.session(response.body, settings.origin, now())
                storage.deviceId(settings.origin, session.account.userId)
                saveAuth(TeamAuthState(session = session))
                mutableAuthError.value = null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableAuthError.value = safeMessage(error, "Google sign-in could not be completed. Please try again.")
        }
    }

    override suspend fun signOut() {
        // NonCancellable guarantees local cleanup even if the UI scope disappears during revoke.
        withContext(ioDispatcher + NonCancellable) {
            mutex.withLock {
                try {
                    if (configured && auth.session != null) {
                        val session = validSession()
                        request("/auth/v1/logout?scope=local", emptyMap<String, String>(), session.accessToken)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Best-effort remote revoke. No mutation retries and no API payload exposure.
                } finally {
                    auth = TeamAuthState()
                    mutableAccount.value = null
                    mutableAuthError.value = null
                    try {
                        storage.clearAuth()
                    } catch (_: Exception) {
                        mutableAuthError.value = "Sign-out could not clear secure storage. Please try signing out again."
                    }
                }
            }
        }
        currentCoroutineContext().ensureActive()
    }

    override suspend fun loadTeam(deviceName: String): TeamSnapshot = onIo {
        val name = deviceName.trim().filterNot { it.isISOControl() }.take(100)
        if (name.isBlank()) throw TeamClientException("Enter a name for this device.")
        TeamProtocol.snapshot(rpc("team_register_device") { id ->
            mapOf("p_device_id" to id, "p_device_name" to name)
        })
    }

    override suspend fun createInvite(): TeamInvite = onIo {
        TeamProtocol.invite(rpc("team_create_invite") { mapOf("p_device_id" to it) })
    }

    override suspend fun cancelInvite() = onIo {
        rpc("team_cancel_invite") { mapOf("p_device_id" to it) }
        Unit
    }

    override suspend fun joinTeam(code: String) = onIo {
        if (!PairingCode.isValid(code)) throw TeamClientException("Enter the 12-character pairing code.")
        TeamProtocol.requireJoinSuccess(rpc("team_join") {
            mapOf("p_device_id" to it, "p_code" to PairingCode.normalize(code))
        })
    }

    override suspend fun removeConnection(pairingId: String) = onIo {
        if (!isUuid(pairingId)) throw TeamClientException("Select a valid team connection.")
        rpc("team_remove_connection") { mapOf("p_device_id" to it, "p_pairing_id" to pairingId) }
        Unit
    }

    private suspend fun rpc(name: String, parameters: (String) -> Map<String, String>): String {
        val session = validSession()
        val id = storage.deviceId(requireConfig().origin, session.account.userId)
        val response = request("/rest/v1/rpc/$name", parameters(id), session.accessToken)
        TeamProtocol.requireSuccess(response)
        return response.body
    }

    /**
     * Relay calls share the authenticated session but map failures through the relay vocabulary,
     * so a stale key binding is distinguishable from an unreachable service.
     */
    private suspend fun relayRpc(name: String, parameters: (String) -> Map<String, Any?>): String {
        val session = validSession()
        val id = storage.deviceId(requireConfig().origin, session.account.userId)
        val response = request("/rest/v1/rpc/$name", parameters(id), session.accessToken, relayGson)
        RelayProtocol.failure(response)?.let { throw it }
        return response.body
    }

    override suspend fun registerKey(keyId: String, publicKey: String) = onIo {
        requireUuid(keyId)
        if (publicKey.isBlank() || publicKey.length > MAX_PUBLIC_KEY_CHARS) throw invalidRelay()
        relayRpc(RELAY_REGISTER_KEY) { id ->
            mapOf(
                "p_device_id" to id,
                "p_key_id" to keyId,
                "p_public_key" to RelayProtocol.bytea(publicKey.toByteArray(UTF_8))
            )
        }
        Unit
    }

    override suspend fun bindPeerKey(
        employeeDeviceId: String,
        employeeKeyId: String?
    ): RelayPeerKeyInfo = onIo {
        requireUuid(employeeDeviceId)
        employeeKeyId?.let { requireUuid(it) }
        val body = relayRpc(RELAY_BIND_PEER_KEY) { id ->
            mapOf(
                "p_owner_device_id" to id,
                "p_employee_device_id" to employeeDeviceId,
                // relay_bind_peer_key declares no default for p_employee_key_id, so the argument has
                // to be present. relayGson sends it as an explicit JSON null, which the function
                // documents as "bind whichever key is current".
                "p_employee_key_id" to employeeKeyId
            )
        }
        RelayProtocol.boundPeerKey(body, employeeDeviceId)
    }

    override suspend fun peerKey(): RelayPeerKeyInfo? = onIo {
        val body = relayRpc(RELAY_PEER_KEY) { id -> mapOf("p_employee_device_id" to id) }
        if (body.isBlank() || body.trim() == "null") null else RelayProtocol.peerKey(body)
    }

    override suspend fun enqueue(
        employeeDeviceId: String,
        clientMessageId: String,
        envelope: String,
        ttlSeconds: Long
    ) = onIo {
        requireUuid(employeeDeviceId)
        requireUuid(clientMessageId)
        if (envelope.isBlank() || envelope.length > MAX_ENVELOPE_CHARS) throw invalidRelay()
        if (ttlSeconds !in 1..MAX_TTL_SECONDS) throw invalidRelay()
        relayRpc(RELAY_ENQUEUE) { id ->
            mapOf(
                "p_owner_device_id" to id,
                "p_employee_device_id" to employeeDeviceId,
                "p_client_message_id" to clientMessageId,
                "p_encrypted_envelope" to RelayProtocol.bytea(envelope.toByteArray(UTF_8)),
                "p_ttl_seconds" to ttlSeconds.toInt()
            )
        }
        Unit
    }

    override suspend fun fetch(limit: Int): List<RelayInboxMessage> = onIo {
        RelayProtocol.inbox(
            relayRpc(RELAY_FETCH) { id ->
                mapOf(
                    "p_employee_device_id" to id,
                    "p_limit" to limit.coerceIn(1, MAX_FETCH_LIMIT),
                    "p_lease_seconds" to LEASE_SECONDS
                )
            }
        )
    }

    override suspend fun ack(serverMessageId: String, leaseToken: String): Boolean = onIo {
        requireUuid(serverMessageId)
        requireUuid(leaseToken)
        RelayProtocol.boolean(
            relayRpc(RELAY_ACK) { id ->
                mapOf(
                    "p_employee_device_id" to id,
                    "p_message_id" to serverMessageId,
                    "p_lease_token" to leaseToken
                )
            }
        )
    }

    override suspend fun cleanupExpired(): Int = onIo {
        RelayProtocol.integer(
            relayRpc(RELAY_CLEANUP) { mapOf("p_limit" to CLEANUP_LIMIT) }
        )
    }

    private fun requireUuid(value: String) {
        if (!isUuid(value)) throw invalidRelay()
    }

    private fun invalidRelay() = RelayTransportException("A relay request was not valid.")

    /** Called under the repository mutex: rotating refresh tokens are never used concurrently. */
    private suspend fun validSession(): TeamSession {
        val settings = requireConfig()
        val existing = auth.session ?: throw TeamClientException("Sign in with Google to manage your team.")
        if (!TeamProtocol.needsRefresh(existing, now())) return existing
        currentCoroutineContext().ensureActive()
        // Once GoTrue rotates the refresh token, persist its replacement even if the caller
        // is cancelled during the blocking exchange. Cancellation still reaches the caller.
        return withContext(NonCancellable) {
            val response = request("/auth/v1/token?grant_type=refresh_token", mapOf("refresh_token" to existing.refreshToken))
            if (TeamProtocol.invalidRefresh(response)) {
                auth = auth.copy(session = null, pending = null)
                mutableAccount.value = null
                mutableAuthError.value = "Your session has expired. Please sign in with Google again."
                storage.clearAuth()
                throw TeamClientException(requireNotNull(mutableAuthError.value))
            }
            TeamProtocol.requireSuccess(response)
            val refreshed = TeamProtocol.session(response.body, settings.origin, now())
            if (refreshed.account.userId != existing.account.userId) {
                throw TeamClientException("Your session could not be verified. Please sign in again.")
            }
            saveAuth(auth.copy(session = refreshed))
            refreshed
        }
    }

    private suspend fun request(
        path: String,
        body: Any,
        bearer: String? = null,
        json: Gson = gson
    ): TeamHttpResponse {
        val settings = requireConfig()
        currentCoroutineContext().ensureActive()
        val headers = mutableMapOf("apikey" to settings.publishableKey)
        if (bearer != null) {
            if (!bearer.matches(Regex("[A-Za-z0-9._~-]+"))) {
                throw TeamClientException("Your session could not be verified. Please sign in again.")
            }
            headers["Authorization"] = "Bearer $bearer"
        }
        val response = transport.execute(TeamHttpRequest(settings.origin + path, headers, json.toJson(body)))
        currentCoroutineContext().ensureActive()
        return response
    }

    private fun saveAuth(state: TeamAuthState) {
        storage.writeAuth(state)
        auth = state
        mutableAccount.value = state.session?.account
    }

    private fun requireConfig(): TeamConfig = config
        ?: throw TeamClientException("Team pairing is not configured in this build.")

    private suspend fun <T> onIo(block: suspend () -> T): T = withContext(ioDispatcher) {
        mutex.withLock {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RelayTransportException) {
                // Relay failures carry their own caller-facing meaning; keep it intact.
                throw error
            } catch (error: Exception) {
                throw TeamClientException(safeMessage(error, "Unable to reach the team service. Check your connection and try again."))
            }
        }
    }

    private fun safeMessage(error: Exception, fallback: String): String =
        if (error is TeamClientException) error.message ?: fallback else fallback

    private fun isUuid(value: String): Boolean = try {
        UUID.fromString(value).toString().equals(value, ignoreCase = true)
    } catch (_: IllegalArgumentException) { false }

    private companion object {
        const val RELAY_REGISTER_KEY = "relay_register_key"
        const val RELAY_BIND_PEER_KEY = "relay_bind_peer_key"
        const val RELAY_PEER_KEY = "relay_peer_key"
        const val RELAY_ENQUEUE = "relay_enqueue"
        const val RELAY_FETCH = "relay_fetch"
        const val RELAY_ACK = "relay_ack"
        const val RELAY_CLEANUP = "relay_cleanup_expired"

        /** Short enough that a crashed receiver re-claims quickly instead of waiting on a stale lease. */
        const val LEASE_SECONDS = 60
        const val MAX_FETCH_LIMIT = 100
        const val CLEANUP_LIMIT = 500
        const val MAX_TTL_SECONDS = 604_800L
        const val MAX_PUBLIC_KEY_CHARS = 4_096
        const val MAX_ENVELOPE_CHARS = 1_048_576
    }
}

/** Blocking transport. Every invocation is inside Dispatchers.IO; redirects are never followed. */
internal class HttpsTeamTransport(
    private val openConnection: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection }
) : TeamTransport {
    override fun execute(request: TeamHttpRequest): TeamHttpResponse {
        var connection: HttpsURLConnection? = null
        try {
            val url = URL(request.url)
            if (url.protocol != "https" || url.userInfo != null) {
                throw TeamClientException("The team service requires a secure HTTPS configuration.")
            }
            connection = openConnection(url)
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.useCaches = false
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val bytes = request.body.toByteArray(UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            // Don't read a redirect response or accidentally follow Location with a bearer token.
            if (status in 300..399) return TeamHttpResponse(status, "")
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 1_048_576) {
                        throw TeamClientException("The team service returned an unexpected response. Please try again later.")
                    }
                    output.write(buffer, 0, count)
                }
                String(output.toByteArray(), UTF_8)
            }.orEmpty()
            return TeamHttpResponse(status, body)
        } catch (_: IOException) {
            throw TeamClientException("Unable to reach the team service. Check your connection and try again.")
        } finally {
            connection?.disconnect()
        }
    }
}
