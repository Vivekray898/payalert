package com.vivekray898.payalert.relay

import android.os.SystemClock
import android.util.Log
import java.util.Locale
import java.util.UUID

/**
 * Payload-blind latency markers. Only enumerated stages, clocks and canonical UUIDs reach the sink.
 *
 * epoch_ms is UTC wall time; elapsedRealtime_ms is Android's monotonic clock including sleep.
 * Only subtract monotonic values with the same clock_domain. The random domain is process-local,
 * never persisted, and is neither a device nor an account identifier. Wall clocks across devices
 * may be skewed. FCM carries the server UUID; receiver/speech use the stable client UUID.
 *
 * Clocks, domain and sink are injectable so the format/privacy contract needs no Android runtime.
 */
class RelayTrace(
    private val epochMs: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val clockDomain: UUID = PROCESS_CLOCK_DOMAIN,
    private val sink: (String) -> Unit = { Log.i("PayAlertRelay", it) }
) {
    enum class Stage {
        OWNER_EVENT_ACCEPTED,
        OWNER_CONTEXT_MISSING,
        OWNER_ENQUEUE_START,
        OWNER_ENQUEUED,
        OWNER_ENQUEUE_DUPLICATE,
        OWNER_SEND_START,
        OWNER_SEND_ACCEPTED,
        OWNER_SEND_FAILED,
        FCM_RECEIVED,
        FCM_WORKER_STARTED,
        FCM_WORKER_COMPLETE,
        FCM_WORKER_RETRY,
        FCM_WORKER_FAILED,
        FETCH_START,
        FETCH_COMPLETE,
        FETCH_FAILED,
        FETCH_CANCELLED,
        ENVELOPE_FETCHED,
        DECRYPT_START,
        DECRYPT_COMPLETE,
        DECRYPT_FAILED,
        STORE_START,
        STORE_COMPLETE,
        ANNOUNCEMENT_REQUESTED,
        TTS_REQUESTED,
        TTS_SKIPPED,
        TTS_SPEAK_REQUESTED,
        TTS_SUBMIT_FAILED,
        TTS_INIT_FAILED,
        TTS_AUDIO_START,
        TTS_AUDIO_DONE,
        TTS_AUDIO_ERROR,
        TTS_STOPPED
    }

    /** Invalid identifiers are omitted, never echoed or substituted with payload-derived values. */
    fun record(
        stage: Stage,
        clientMessageId: String? = null,
        serverMessageId: String? = null,
        fetchId: UUID? = null
    ) {
        try {
            val epoch = epochMs()
            val elapsed = elapsedRealtimeMs()
            sink(buildString {
                append("relay_trace schema=1 stage=").append(stage.name.lowercase(Locale.ROOT))
                append(" epoch_ms=").append(epoch)
                append(" elapsedRealtime_ms=").append(elapsed)
                append(" clock_domain=").append(clockDomain)
                messageUuid(clientMessageId)?.let { append(" client_message_id=").append(it) }
                messageUuid(serverMessageId)?.let { append(" server_message_id=").append(it) }
                fetchId?.let { append(" fetch_id=").append(it) }
            })
        } catch (_: Exception) {
            // Observability must not prevent delivery, notification or playback.
        }
    }

    companion object {
        private val PROCESS_CLOCK_DOMAIN = UUID.randomUUID()
        private val UUID_PATTERN = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
        )

        val process: RelayTrace by lazy { RelayTrace() }

        // UUID.fromString alone also accepts shortened groups; do not log those or trim input.
        fun messageUuid(value: String?): String? = value
            ?.takeIf { it.length == 36 && UUID_PATTERN.matches(it) }
            ?.lowercase(Locale.ROOT)
    }
}
