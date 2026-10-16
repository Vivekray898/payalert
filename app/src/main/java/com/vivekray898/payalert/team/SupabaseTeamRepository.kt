package com.vivekray898.payalert.team

import android.content.Context
import com.google.gson.Gson
import com.vivekray898.payalert.BuildConfig
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
 */
class SupabaseTeamRepository internal constructor(
    private val config: TeamConfig?,
    private val storage: TeamSessionStorage,
    private val transport: TeamTransport,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis
) : TeamRepository {
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

    private suspend fun request(path: String, body: Any, bearer: String? = null): TeamHttpResponse {
        val settings = requireConfig()
        currentCoroutineContext().ensureActive()
        val headers = mutableMapOf("apikey" to settings.publishableKey)
        if (bearer != null) {
            if (!bearer.matches(Regex("[A-Za-z0-9._~-]+"))) {
                throw TeamClientException("Your session could not be verified. Please sign in again.")
            }
            headers["Authorization"] = "Bearer $bearer"
        }
        val response = transport.execute(TeamHttpRequest(settings.origin + path, headers, gson.toJson(body)))
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
