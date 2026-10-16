package com.vivekray898.payalert.team

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class HttpsTeamTransportTest {
    private val url = "https://project.supabase.co/rest/v1/rpc/team_join"
    private val request = TeamHttpRequest(url,
        mapOf("apikey" to "public-key", "Authorization" to "Bearer private-token"), "{\"p_code\":\"ABCDEF012345\"}")

    @Test fun `redirects never follow location or read body and connection disconnects`() {
        val connection = FakeConnection(URL(url), 307, "private response")
        val response = HttpsTeamTransport { connection }.execute(request)
        assertEquals(307, response.status)
        assertEquals("", response.body)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.inputRead)
        assertTrue(connection.disconnected)
    }

    @Test fun `POST sends exact UTF8 body with finite timeouts and closes response`() {
        val connection = FakeConnection(URL(url), 200, "{\"ok\":true}")
        val response = HttpsTeamTransport { connection }.execute(request)
        assertEquals("{\"ok\":true}", response.body)
        assertEquals(request.body, connection.output.toString("UTF-8"))
        assertEquals("POST", connection.requestMethod)
        assertEquals("Bearer private-token", connection.getRequestProperty("Authorization"))
        assertEquals("public-key", connection.getRequestProperty("apikey"))
        assertEquals(15_000, connection.connectTimeout)
        assertEquals(20_000, connection.readTimeout)
        assertFalse(connection.useCaches)
        assertTrue(connection.inputClosed)
        assertTrue(connection.disconnected)
    }

    @Test fun `oversized and IO failures close connection without exposing raw details`() {
        val oversized = FakeConnection(URL(url), 200, "x".repeat(1_048_577))
        val error = rejects { HttpsTeamTransport { oversized }.execute(request) }
        assertTrue(error.message.orEmpty().contains("unexpected response"))
        assertTrue(oversized.inputClosed)
        assertTrue(oversized.disconnected)
        val failing = FakeConnection(URL(url), 200, "")
        failing.failRead = true
        val offline = rejects { HttpsTeamTransport { failing }.execute(request) }
        assertFalse(offline.message.orEmpty().contains("private-token"))
        assertNull(offline.cause)
        assertTrue(failing.disconnected)
    }

    @Test fun `cleartext is rejected before opening a connection`() {
        var opened = false
        val transport = HttpsTeamTransport {
            opened = true
            FakeConnection(it, 200, "")
        }
        rejects { transport.execute(request.copy(url = url.replace("https:", "http:"))) }
        assertFalse(opened)
    }

    private fun rejects(block: () -> Unit): TeamClientException {
        try { block() } catch (expected: TeamClientException) { return expected }
        throw AssertionError("Expected a safe client error")
    }

    private class FakeConnection(url: URL, private val status: Int, private val body: String) : HttpsURLConnection(url) {
        val output = ByteArrayOutputStream()
        var disconnected = false
        var inputRead = false
        var inputClosed = false
        var failRead = false
        override fun getOutputStream() = output
        override fun getResponseCode() = status
        override fun getInputStream(): InputStream {
            inputRead = true
            if (failRead) throw IOException("private-token in diagnostic URL")
            return object : ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)) {
                override fun close() { inputClosed = true; super.close() }
            }
        }
        override fun getErrorStream(): InputStream = inputStream
        override fun disconnect() { disconnected = true }
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun getCipherSuite() = "TLS_AES_256_GCM_SHA384"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
}
