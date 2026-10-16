package com.vivekray898.payalert.team

import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SupabaseTeamRepositoryTest {
    private val origin = "https://project.supabase.co"
    private val key = "sb_publishable_public_test_key"
    private val config = requireNotNull(TeamConfig.parse(origin, key))
    private val now = 1_800_000_000_000L
    private val account = TeamAccount("google-user", "Google Name")
    private val session = TeamSession(origin, "access.old", "refresh.old", now + 3_600_000, account)
    private val snapshot = """{"device":{"id":"device","name":"Counter"},"employees":[],"owner":null}"""
    private val token = """{"access_token":"access.new","refresh_token":"refresh.new","expires_in":3600,"user":{"id":"google-user","user_metadata":{"full_name":"Google Name"}}}"""

    @Test fun `empty configuration disables all network and reports safe callback error`() = runTest {
        val network = RecordingTransport()
        val store = MemoryStore(TeamAuthState(session))
        val repository = SupabaseTeamRepository(null, store, network, StandardTestDispatcher(testScheduler)) { now }
        assertFalse(repository.configured)
        assertNull(repository.account.value)
        expectFailure { repository.signInUrl() }
        repository.completeSignIn("${TeamOAuth.CALLBACK}?code=private")
        assertTrue(repository.authError.value.orEmpty().contains("not configured"))
        expectFailure { repository.loadTeam("Counter") }
        repository.signOut()
        assertEquals(0, network.requests.size)
        assertEquals(TeamAuthState(), store.auth)
    }

    @Test fun `PKCE survives restart exchanges exact body and cannot replay`() = runTest {
        val store = MemoryStore()
        val network = RecordingTransport { TeamHttpResponse(200, token) }
        fun repository() = SupabaseTeamRepository(config, store, network, StandardTestDispatcher(testScheduler)) { now }
        val first = repository()
        val url = first.signInUrl()
        val verifier = requireNotNull(store.auth.pending).verifier
        assertFalse(url.contains(verifier))
        val restored = repository()
        restored.completeSignIn("${TeamOAuth.CALLBACK}?code=auth-code")
        assertNull(restored.authError.value)
        assertEquals(account, restored.account.value)
        assertEquals(account, repository().account.value)
        assertNull(store.auth.pending)
        val request = network.requests.single()
        assertEquals("$origin/auth/v1/token?grant_type=pkce", request.url)
        assertEquals(mapOf("auth_code" to "auth-code", "code_verifier" to verifier), parameters(request))
        assertEquals(mapOf("apikey" to key), request.headers)
        assertEquals(1, store.installations.size)
        restored.completeSignIn("${TeamOAuth.CALLBACK}?code=auth-code")
        assertNotNull(restored.authError.value)
        assertEquals(1, network.requests.size)
        assertEquals(account, restored.account.value)
    }

    @Test fun `expired error and fragment callbacks consume transaction without networking`() = runTest {
        listOf("${TeamOAuth.CALLBACK}?error=raw-private-data", "${TeamOAuth.CALLBACK}?code=a#access_token=secret",
            "com.vivekray898.payalert://auth/wrong?code=a").forEach { callback ->
            val store = MemoryStore(TeamAuthState(pending = TeamPkce(origin, "v".repeat(43), now)))
            val network = RecordingTransport()
            val repository = SupabaseTeamRepository(config, store, network, StandardTestDispatcher(testScheduler)) { now }
            repository.completeSignIn(callback)
            assertNull(store.auth.pending)
            assertNotNull(repository.authError.value)
            assertFalse(repository.authError.value.orEmpty().contains("raw-private-data"))
            assertEquals(0, network.requests.size)
        }
        val store = MemoryStore(TeamAuthState(pending = TeamPkce(origin, "v".repeat(43), now - 600_000)))
        val network = RecordingTransport()
        val repository = SupabaseTeamRepository(config, store, network, StandardTestDispatcher(testScheduler)) { now }
        repository.completeSignIn("${TeamOAuth.CALLBACK}?code=a")
        assertNotNull(repository.authError.value)
        assertEquals(0, network.requests.size)
    }

    @Test fun `callback cancellation propagates with verifier consumed and no raw auth error`() = runTest {
        val store = MemoryStore()
        val network = RecordingTransport { throw CancellationException("cancelled") }
        val repository = SupabaseTeamRepository(config, store, network, StandardTestDispatcher(testScheduler)) { now }
        repository.signInUrl()
        try {
            repository.completeSignIn("${TeamOAuth.CALLBACK}?code=a")
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { /* expected */ }
        assertNull(store.auth.pending)
        assertNull(repository.authError.value)
    }

    @Test fun `all five RPCs send exact JSON public apikey and authenticated bearer`() = runTest {
        val store = MemoryStore(TeamAuthState(session))
        val network = RecordingTransport { request ->
            TeamHttpResponse(200, when (request.url.substringAfterLast('/')) {
                "team_register_device" -> snapshot
                "team_create_invite" -> """{"code":"ABCDEF012345","expires_at":"2027-01-02T03:04:05Z"}"""
                else -> "null"
            })
        }
        val repository = SupabaseTeamRepository(config, store, network, StandardTestDispatcher(testScheduler)) { now }
        assertEquals("Counter", repository.loadTeam(" Counter ").device.name)
        assertEquals("ABCDEF012345", repository.createInvite().code)
        repository.cancelInvite()
        repository.joinTeam(" abcdef012345 ")
        val pairing = UUID.randomUUID().toString()
        repository.removeConnection(pairing)
        val id = store.installations.values.single()
        val expected = listOf(
            "team_register_device" to mapOf("p_device_id" to id, "p_device_name" to "Counter"),
            "team_create_invite" to mapOf("p_device_id" to id),
            "team_cancel_invite" to mapOf("p_device_id" to id),
            "team_join" to mapOf("p_device_id" to id, "p_code" to "ABCDEF012345"),
            "team_remove_connection" to mapOf("p_device_id" to id, "p_pairing_id" to pairing)
        )
        assertEquals(expected, network.requests.map { it.url.substringAfterLast('/') to parameters(it) })
        network.requests.forEach {
            assertTrue(it.url.startsWith("$origin/rest/v1/rpc/"))
            assertEquals(mapOf("apikey" to key, "Authorization" to "Bearer access.old"), it.headers)
        }
    }

    @Test fun `join detects HTTP 200 error invalid input and never retries mutation`() = runTest {
        val network = RecordingTransport { TeamHttpResponse(200, """{"error":"secret SQL details"}""") }
        val store = MemoryStore(TeamAuthState(session))
        val repository = SupabaseTeamRepository(config, store, network, StandardTestDispatcher(testScheduler)) { now }
        expectFailure { repository.joinTeam("bad") }
        expectFailure { repository.removeConnection("bad") }
        assertEquals(0, network.requests.size)
        val error = expectFailure { repository.joinTeam("ABCDEF012345") }
        assertFalse(error.message.orEmpty().contains("secret SQL"))
        assertEquals(1, network.requests.size)
        network.handler = { throw IOException("secret URL and token") }
        val offline = expectFailure { repository.cancelInvite() }
        assertFalse(offline.message.orEmpty().contains("secret URL"))
        assertEquals(2, network.requests.size)
        assertEquals(account, repository.account.value)
    }

    @Test fun `concurrent API calls share a serialized refresh and persist rotated token`() = runTest {
        val store = MemoryStore(TeamAuthState(session.copy(expiresAtMillis = now + 30_000)))
        val refreshEntered = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        val network = RecordingTransport { request ->
            if (request.url.contains("grant_type=refresh_token")) {
                refreshEntered.countDown()
                check(releaseRefresh.await(5, TimeUnit.SECONDS))
                TeamHttpResponse(200, token)
            } else TeamHttpResponse(200, snapshot)
        }
        val workers = Executors.newFixedThreadPool(5)
        workers.asCoroutineDispatcher().use { dispatcher ->
            val repository = SupabaseTeamRepository(config, store, network, dispatcher) { now }
            val startTogether = CyclicBarrier(5)
            val calls = (1..5).map {
                async(dispatcher) {
                    startTogether.await(5, TimeUnit.SECONDS)
                    repository.loadTeam("Counter")
                }
            }
            assertTrue(refreshEntered.await(5, TimeUnit.SECONDS))
            // This queued task can run only after a caller frees a worker by suspending
            // on the mutex. Without serialization all five block in token refresh.
            workers.submit { releaseRefresh.countDown() }
            calls.awaitAll()
        }
        assertEquals(1, network.requests.count { it.url.contains("grant_type=refresh_token") })
        assertEquals(mapOf("refresh_token" to "refresh.old"), parameters(network.requests.first()))
        assertEquals("refresh.new", store.auth.session?.refreshToken)
        network.requests.drop(1).forEach { assertEquals("Bearer access.new", it.headers["Authorization"]) }
    }

    @Test fun `offline transient and configuration refresh failures preserve restored session`() = runTest {
        val cases: List<(TeamHttpRequest) -> TeamHttpResponse> = listOf(
            { throw IOException("offline private details") },
            { TeamHttpResponse(503, "{}") }, { TeamHttpResponse(429, "{}") },
            { TeamHttpResponse(401, """{"message":"Invalid API key"}""") },
            { TeamHttpResponse(400, """{"error":"invalid_grant"}""") }
        )
        cases.forEach { handler ->
            val expired = session.copy(expiresAtMillis = now - 1)
            val store = MemoryStore(TeamAuthState(expired))
            val network = RecordingTransport(handler)
            val repository = SupabaseTeamRepository(config, store, network, StandardTestDispatcher(testScheduler)) { now }
            assertEquals(account, repository.account.value)
            expectFailure { repository.loadTeam("Counter") }
            assertEquals(expired, store.auth.session)
            assertEquals(account, repository.account.value)
            assertEquals(1, network.requests.size)
        }
    }

    @Test fun `cancellation after refresh response still saves the rotated refresh token`() = runTest {
        val store = MemoryStore(TeamAuthState(session.copy(expiresAtMillis = now - 1)))
        lateinit var caller: Deferred<TeamSnapshot>
        val network = RecordingTransport {
            caller.cancel()
            TeamHttpResponse(200, token)
        }
        val repository = SupabaseTeamRepository(config, store, network, StandardTestDispatcher(testScheduler)) { now }
        caller = async { repository.loadTeam("Counter") }
        try {
            caller.await()
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { /* expected */ }
        assertEquals("refresh.new", store.auth.session?.refreshToken)
        assertEquals(1, network.requests.size)
    }

    @Test fun `definitive invalid refresh clears private auth and publishes reauthentication error`() = runTest {
        val store = MemoryStore(TeamAuthState(session.copy(expiresAtMillis = now - 1)))
        store.deviceId(origin, account.userId)
        val network = RecordingTransport { TeamHttpResponse(400, """{"code":"refresh_token_not_found"}""") }
        val repository = SupabaseTeamRepository(config, store, network, StandardTestDispatcher(testScheduler)) { now }
        expectFailure { repository.createInvite() }
        assertNull(repository.account.value)
        assertNotNull(repository.authError.value)
        assertEquals(TeamAuthState(), store.auth)
        assertEquals(1, store.installations.size)
        expectFailure { repository.createInvite() }
        assertEquals(1, network.requests.size)
    }

    @Test fun `logout attempts local remote revoke then keeps installation id across sign-in`() = runTest {
        val store = MemoryStore(TeamAuthState(session))
        val id = store.deviceId(origin, account.userId)
        val other = store.deviceId(origin, "other-account")
        assertNotEquals(id, other)
        val network = RecordingTransport { throw IOException("offline") }
        val repository = SupabaseTeamRepository(config, store, network, StandardTestDispatcher(testScheduler)) { now }
        repository.signInUrl()
        repository.signOut()
        assertEquals("$origin/auth/v1/logout?scope=local", network.requests.single().url)
        assertEquals("Bearer access.old", network.requests.single().headers["Authorization"])
        assertEquals(TeamAuthState(), store.auth)
        assertNull(repository.account.value)
        assertEquals(id, store.deviceId(origin, account.userId))
        network.handler = { TeamHttpResponse(200, token) }
        repository.signInUrl()
        repository.completeSignIn("${TeamOAuth.CALLBACK}?code=new-login")
        assertEquals(account, repository.account.value)
        assertEquals(id, store.deviceId(origin, account.userId))
        assertFalse(network.requests.any { it.url.contains("team_remove_connection") })
    }

    @Test fun `session from another project is not restored or sent to a changed origin`() = runTest {
        val store = MemoryStore(TeamAuthState(session.copy(origin = "https://other.supabase.co")))
        val network = RecordingTransport()
        val repository = SupabaseTeamRepository(config, store, network, StandardTestDispatcher(testScheduler)) { now }
        assertNull(repository.account.value)
        expectFailure { repository.loadTeam("Counter") }
        assertEquals(0, network.requests.size)
    }

    private fun parameters(request: TeamHttpRequest): Map<String, String> =
        JsonParser.parseString(request.body).asJsonObject.entrySet().associate { it.key to it.value.asString }

    private suspend fun expectFailure(block: suspend () -> Unit): TeamClientException {
        try { block() } catch (expected: TeamClientException) { return expected }
        throw AssertionError("Expected a safe client error")
    }

    private class MemoryStore(var auth: TeamAuthState = TeamAuthState()) : TeamSessionStorage {
        val installations = mutableMapOf<String, String>()
        override fun readAuth() = auth
        override fun writeAuth(state: TeamAuthState) { auth = state }
        override fun clearAuth() { auth = TeamAuthState() }
        override fun deviceId(origin: String, userId: String): String =
            installations.getOrPut("$origin/$userId") { UUID.randomUUID().toString() }
    }

    private class RecordingTransport(
        var handler: (TeamHttpRequest) -> TeamHttpResponse = { TeamHttpResponse(200, "null") }
    ) : TeamTransport {
        val requests: MutableList<TeamHttpRequest> = Collections.synchronizedList(mutableListOf())
        override fun execute(request: TeamHttpRequest): TeamHttpResponse {
            requests.add(request)
            return handler(request)
        }
    }
}
