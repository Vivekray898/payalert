package com.vivekray898.payalert.team

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class TeamClientProtocolTest {
    private val origin = "https://project.supabase.co"
    private val config = requireNotNull(TeamConfig.parse(origin, "sb_publishable_public_test_key"))
    private val now = 1_800_000_000_000L
    private val pending = TeamPkce(origin, "v".repeat(43), now)

    @Test fun `PKCE matches RFC 7636 S256 vector`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            TeamOAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val first = TeamOAuth.transaction(origin, now)
        val second = TeamOAuth.transaction(origin, now)
        assertTrue(first.verifier.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertNotEquals(first.verifier, second.verifier)
    }

    @Test fun `authorization uses only Google exact redirect and S256 challenge`() {
        val uri = URI(TeamOAuth.authorizeUrl(config, pending))
        assertEquals("$origin/auth/v1/authorize", "${uri.scheme}://${uri.host}${uri.path}")
        val parameters = uri.rawQuery.split('&').associate {
            val pair = it.split('=', limit = 2)
            pair[0] to URLDecoder.decode(pair[1], "UTF-8")
        }
        assertEquals(mapOf("provider" to "google", "redirect_to" to TeamOAuth.CALLBACK,
            "code_challenge" to TeamOAuth.challenge(pending.verifier), "code_challenge_method" to "s256"), parameters)
        assertFalse(uri.toString().contains(pending.verifier))
    }

    @Test fun `callback requires pending same-project unexpired single transaction`() {
        val callback = "${TeamOAuth.CALLBACK}?code=one-time-code"
        assertEquals("one-time-code", TeamOAuth.callbackCode(callback, pending, origin, now))
        assertEquals("one-time-code", TeamOAuth.callbackCode(callback, pending, origin,
            now + TeamOAuth.TRANSACTION_LIFETIME_MILLIS - 1))
        rejects { TeamOAuth.callbackCode(callback, null, origin, now) }
        rejects { TeamOAuth.callbackCode(callback, pending, "https://other.supabase.co", now) }
        rejects { TeamOAuth.callbackCode(callback, pending, origin, now - 1) }
        rejects { TeamOAuth.callbackCode(callback, pending, origin, now + TeamOAuth.TRANSACTION_LIFETIME_MILLIS) }
        rejects { TeamOAuth.callbackCode(callback, pending.copy(verifier = "bad"), origin, now) }
    }

    @Test fun `callback rejects impostors fragments OAuth errors ambiguous and malformed query`() {
        listOf(
            "https://auth/callback?code=a", "COM.vivekray898.payalert://auth/callback?code=a",
            "com.vivekray898.payalert://auth.evil/callback?code=a",
            "com.vivekray898.payalert://user@auth/callback?code=a",
            "com.vivekray898.payalert://auth:443/callback?code=a",
            "com.vivekray898.payalert://auth/other?code=a", "${TeamOAuth.CALLBACK}/?code=a",
            "com.vivekray898.payalert://auth/%63allback?code=a",
            "${TeamOAuth.CALLBACK}?code=a#access_token=secret", "${TeamOAuth.CALLBACK}?code=a#",
            "${TeamOAuth.CALLBACK}?error=secret", "${TeamOAuth.CALLBACK}?code=a&error_description=secret",
            "${TeamOAuth.CALLBACK}?code=a&code=b", "${TeamOAuth.CALLBACK}?%63ode=a&code=b",
            "${TeamOAuth.CALLBACK}?code=", "${TeamOAuth.CALLBACK}?code=a&access_token=secret",
            "${TeamOAuth.CALLBACK}?code=a%0d%0a", "${TeamOAuth.CALLBACK}?code=%zz", TeamOAuth.CALLBACK
        ).forEach { url -> rejects { TeamOAuth.callbackCode(url, pending, origin, now) } }
    }

    @Test fun `configuration requires HTTPS public key and an unambiguous origin`() {
        assertNotNull(TeamConfig.parse("https://PROJECT.supabase.co/", "sb_publishable_public_test_key"))
        assertEquals(origin, TeamConfig.parse("https://PROJECT.supabase.co:443/", "sb_publishable_public_test_key")?.origin)
        listOf("", "http://project.supabase.co", "https://u:p@project.supabase.co",
            "$origin/path", "$origin?key=x", "$origin#x", "https://project.supabase.co:80", "https:///missing"
        ).forEach { assertNull(TeamConfig.parse(it, "sb_publishable_public_test_key")) }
        listOf("", "sb_secret_do_not_embed_this", "service_role", "sb_publishable_x\r\nheader: bad")
            .forEach { assertNull(TeamConfig.parse(origin, it)) }
        fun jwt(role: String) = "eyJhbGciOiJIUzI1NiJ9." +
            TeamEncoding.encode("{\"role\":\"$role\"}".toByteArray()) + ".signature"
        assertNotNull(TeamConfig.parse(origin, jwt("anon")))
        assertNull(TeamConfig.parse(origin, jwt("service_role")))
        assertNull(TeamConfig.parse(origin, jwt("authenticated")))
    }

    @Test fun `snapshot parses exact RPC device employee and nullable owner fields`() {
        val connection = """{"pairing_id":"pair","device_id":"other","device_name":"Cashier"}"""
        val snapshot = TeamProtocol.snapshot("""{"device":{"id":"mine","name":"Owner"},"employees":[$connection],"owner":null}""")
        assertEquals(TeamDevice("mine", "Owner"), snapshot.device)
        assertEquals(listOf(TeamConnection("pair", "other", "Cashier")), snapshot.employees)
        assertNull(snapshot.owner)
        assertEquals(TeamConnection("pair", "other", "Cashier"), TeamProtocol.snapshot(
            """{"device":{"id":"mine","name":"Cashier"},"employees":[],"owner":$connection}""").owner)
        rejects { TeamProtocol.snapshot("""{"device":{"id":"mine","name":"Name"},"employees":[]}""") }
        rejects { TeamProtocol.snapshot("[]") }
        rejects { TeamProtocol.snapshot("""{"device":null,"employees":[],"owner":null}""") }
    }

    @Test fun `invite requires six digits and valid ISO timestamp`() {
        val invite = TeamProtocol.invite("""{"code":"012345","expires_at":"2027-01-02T03:04:05.123456+00:00"}""")
        assertEquals("012345", invite.code)
        assertEquals("2027-01-02T03:04:05.123456+00:00", invite.expiresAt)
        rejects { TeamProtocol.invite("""{"code":"01234","expires_at":"2027-01-02T03:04:05Z"}""") }
        rejects { TeamProtocol.invite("""{"code":"0123456","expires_at":"2027-01-02T03:04:05Z"}""") }
        rejects { TeamProtocol.invite("""{"code":"ABCDEF12","expires_at":"2027-01-02T03:04:05Z"}""") }
        rejects { TeamProtocol.invite("""{"code":"012345","expires_at":"2027-02-30T03:04:05Z"}""") }
        rejects { TeamProtocol.invite("""{"code":"012345","expires_at":"tomorrow"}""") }
    }

    @Test fun `invite rejects non-numeric and wrong-length codes`() {
        rejects { TeamProtocol.invite("""{"code":"00000","expires_at":"2027-01-02T03:04:05Z"}""") }
        rejects { TeamProtocol.invite("""{"code":"0000000","expires_at":"2027-01-02T03:04:05Z"}""") }
        rejects { TeamProtocol.invite("""{"code":"ABCDEF","expires_at":"2027-01-02T03:04:05Z"}""") }
        rejects { TeamProtocol.invite("""{"code":"  123456  ","expires_at":"2027-01-02T03:04:05Z"}""") }
        rejects { TeamProtocol.invite("""{"code":"","expires_at":"2027-01-02T03:04:05Z"}""") }
    }

    @Test fun `errors never expose API details and successful HTTP join can still fail`() {
        val raw = "database secret and bearer token"
        val error = rejects { TeamProtocol.requireJoinSuccess("""{"error":"$raw"}""") }
        assertFalse(error.message.orEmpty().contains(raw))
        TeamProtocol.requireJoinSuccess("null")
        TeamProtocol.requireJoinSuccess("{}")
        val failure = rejects { TeamProtocol.requireSuccess(TeamHttpResponse(500, "{\"message\":\"$raw\"}")) }
        assertEquals(TeamProtocol.httpMessage(500), failure.message)
        assertNull(failure.cause)
        assertTrue(TeamProtocol.httpMessage(429).contains("wait"))
        assertTrue(TeamProtocol.httpMessage(302).contains("redirected"))
        rejects { TeamProtocol.json("<html>$raw</html>") }
    }

    @Test fun `only definitive invalid refresh errors invalidate a session`() {
        listOf("refresh_token_not_found", "refresh_token_already_used", "refresh_token_expired", "session_not_found")
            .forEach { assertTrue(TeamProtocol.invalidRefresh(TeamHttpResponse(400, """{"code":"$it"}"""))) }
        assertTrue(TeamProtocol.invalidRefresh(TeamHttpResponse(400,
            """{"error":"invalid_grant","error_description":"Invalid Refresh Token: Refresh Token Not Found"}""")))
        listOf(TeamHttpResponse(503, """{"code":"refresh_token_not_found"}"""),
            TeamHttpResponse(401, """{"message":"Invalid API key"}"""),
            TeamHttpResponse(400, """{"error":"invalid_grant"}"""), TeamHttpResponse(400, "not JSON"),
            TeamHttpResponse(429, "{}"), TeamHttpResponse(500, "{}"))
            .forEach { assertFalse(TeamProtocol.invalidRefresh(it)) }
    }

    @Test fun `token parsing and refresh window use server expiry`() {
        val token = """{"access_token":"access.jwt","refresh_token":"private","expires_in":3600,"user":{"id":"user","email":"test@example.com","user_metadata":{"full_name":"Google Name"}}}"""
        val session = TeamProtocol.session(token, origin, now)
        assertEquals(TeamAccount("user", "Google Name"), session.account)
        assertEquals(now + 3_600_000, session.expiresAtMillis)
        assertFalse(TeamProtocol.needsRefresh(session, now + 3_539_999))
        assertTrue(TeamProtocol.needsRefresh(session, now + 3_540_000))
        val absolute = JsonParser.parseString(token).asJsonObject.apply { addProperty("expires_at", now / 1000 + 100) }
        assertEquals(now + 100_000, TeamProtocol.session(absolute.toString(), origin, now).expiresAtMillis)
        rejects { TeamProtocol.session("{}", origin, now) }
        rejects { TeamProtocol.session(token.replace("3600", "0"), origin, now) }
        rejects { TeamProtocol.session(token.replace("access.jwt", "bad\\r\\nheader"), origin, now) }
    }

    @Test fun `pairing errors explain expiry self pairing role conflicts and rate limit`() {
        val expectations = mapOf(
            "invalid_invite" to "Ask the owner for a new code",
            "self_pairing" to "cannot pair a device with itself",
            "already_paired" to "Leave that team",
            "device_is_owner" to "cancel your own pairing code",
            "owner_is_employee" to "cannot invite",
            "rate_limited" to "Wait 10 minutes"
        )
        expectations.forEach { (code, recovery) ->
            assertTrue(rejects { TeamProtocol.requireJoinSuccess("""{"error":"$code"}""") }
                .message.orEmpty().contains(recovery))
        }
    }

    private fun rejects(block: () -> Unit): TeamClientException {
        try { block() } catch (expected: TeamClientException) { return expected }
        throw AssertionError("Expected a safe client error")
    }
}
