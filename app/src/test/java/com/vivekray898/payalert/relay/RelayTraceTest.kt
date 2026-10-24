package com.vivekray898.payalert.relay

import com.vivekray898.payalert.relay.RelayTrace.Stage
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayTraceTest {
    @Test
    fun `schema records both clocks and distinguishes client server and fetch UUIDs`() {
        val lines = mutableListOf<String>()
        val trace = RelayTrace({ 1_700_000_000_123L }, { 456L }, DOMAIN, lines::add)

        trace.record(Stage.ENVELOPE_FETCHED, CLIENT.uppercase(), SERVER, FETCH)

        assertEquals(
            "relay_trace schema=1 stage=envelope_fetched epoch_ms=1700000000123 " +
                "elapsedRealtime_ms=456 clock_domain=$DOMAIN client_message_id=$CLIENT " +
                "server_message_id=$SERVER fetch_id=$FETCH",
            lines.single()
        )
    }

    @Test
    fun `payload token exception and malformed UUID strings never reach the sink`() {
        val forbidden = listOf(
            "", "pairing_changed", "owner-device-0001", "Payment received 750 rupees",
            "token-secret-prefix", "java.io.IOException: secret", "1-1-1-1-1",
            " $CLIENT", "$CLIENT ", "$CLIENT\ntext=secret", "$CLIENT\u0000",
            CLIENT.replace('-', '_'), "g" + CLIENT.drop(1)
        )
        val lines = mutableListOf<String>()
        val trace = RelayTrace({ 100L }, { 20L }, DOMAIN, lines::add)

        for (value in forbidden) {
            assertNull(RelayTrace.messageUuid(value))
            trace.record(Stage.FCM_RECEIVED, clientMessageId = value, serverMessageId = value)
        }
        trace.record(Stage.FETCH_START)

        val safeLine = "relay_trace schema=1 stage=fcm_received epoch_ms=100 " +
            "elapsedRealtime_ms=20 clock_domain=$DOMAIN"
        assertEquals(List(forbidden.size) { safeLine }, lines.dropLast(1))
        assertFalse(lines.any { "message_id=" in it })
    }

    @Test
    fun `an invalid server identifier does not suppress a valid client identifier`() {
        val lines = mutableListOf<String>()
        RelayTrace({ 100L }, { 20L }, DOMAIN, lines::add).record(
            Stage.ENVELOPE_FETCHED, clientMessageId = CLIENT, serverMessageId = "lease-secret"
        )
        assertTrue(lines.single().endsWith("client_message_id=$CLIENT"))
        assertFalse(lines.single().contains("server_message_id"))
        assertFalse(lines.single().contains("secret"))
    }

    @Test
    fun `wall clock adjustment does not change monotonic latency or clock domain`() {
        var epoch = 1_700_000_000_000L
        var elapsed = 100L
        val lines = mutableListOf<String>()
        val trace = RelayTrace({ epoch }, { elapsed }, DOMAIN, lines::add)
        trace.record(Stage.FETCH_START, fetchId = FETCH)
        epoch -= 1_000L
        elapsed += 250L
        trace.record(Stage.FETCH_COMPLETE, fetchId = FETCH)

        val start = fields(lines.first())
        val end = fields(lines.last())
        assertEquals(-1_000L, end.getValue("epoch_ms").toLong() - start.getValue("epoch_ms").toLong())
        assertEquals(250L, end.getValue("elapsedRealtime_ms").toLong() - start.getValue("elapsedRealtime_ms").toLong())
        assertEquals(start.getValue("clock_domain"), end.getValue("clock_domain"))
        assertEquals(start.getValue("fetch_id"), end.getValue("fetch_id"))
    }

    @Test
    fun `default helpers share one process domain and a new domain is distinguishable`() {
        val lines = mutableListOf<String>()
        repeat(2) {
            RelayTrace(epochMs = { 1L }, elapsedRealtimeMs = { 2L }, sink = lines::add)
                .record(Stage.FETCH_START)
        }
        RelayTrace({ 1L }, { 2L }, DOMAIN, lines::add).record(Stage.FETCH_START)

        val domains = lines.map { fields(it).getValue("clock_domain") }
        assertEquals(domains[0], domains[1])
        assertEquals(domains[0], UUID.fromString(domains[0]).toString())
        assertNotEquals(domains[0], domains[2])
    }

    @Test
    fun `every stage emits only schema fields with no free form detail channel`() {
        val lines = mutableListOf<String>()
        val trace = RelayTrace({ 1L }, { 2L }, DOMAIN, lines::add)
        Stage.entries.forEach { trace.record(it, CLIENT, SERVER, FETCH) }

        for (line in lines) {
            val fields = fields(line)
            assertEquals(
                setOf("schema", "stage", "epoch_ms", "elapsedRealtime_ms", "clock_domain",
                    "client_message_id", "server_message_id", "fetch_id"),
                fields.keys
            )
            assertTrue(fields.getValue("stage").matches(Regex("[a-z_]+")))
        }
        assertEquals(Stage.entries.size, lines.map { fields(it).getValue("stage") }.toSet().size)
    }

    @Test
    fun `failing diagnostic clocks and sinks cannot break callers`() {
        var calls = 0
        val trace = RelayTrace({ 1L }, { 2L }, DOMAIN) {
            calls++
            throw IllegalStateException("never log this exception")
        }
        trace.record(Stage.ANNOUNCEMENT_REQUESTED, CLIENT)
        trace.record(Stage.TTS_AUDIO_START, CLIENT)
        assertEquals(2, calls)

        RelayTrace({ throw IllegalStateException("clock secret") }, { 2L }, DOMAIN) {
            throw AssertionError("a failed clock must not emit a partial record")
        }.record(Stage.FETCH_START)
    }

    private fun fields(line: String): Map<String, String> = line.removePrefix("relay_trace ")
        .split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }

    private companion object {
        const val CLIENT = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        const val SERVER = "11111111-2222-4333-8444-555555555555"
        val DOMAIN: UUID = UUID.fromString("12345678-1234-4234-8234-123456789abc")
        val FETCH: UUID = UUID.fromString("87654321-4321-4321-8321-cba987654321")
    }
}
