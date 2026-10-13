package com.vivekray898.payalert.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vivekray898.payalert.data.repo.PaymentEventRepository
import com.vivekray898.payalert.parsers.ParserRegistry
import com.vivekray898.payalert.dedup.DedupIndex
import com.vivekray898.payalert.tts.TtsEngine
import com.vivekray898.payalert.di.internal.module.provideTtsEngine
import org.jetbrains.kotlinx.coroutines.flow.Flow
import org.jetbrains.kotlinx.coroutines.flowMutableStateOf
import org.jetbrains.kotlinx.coroutines.flowStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive

/**
 * Main UI ViewModel — orchestrates the payment detection pipeline:
 *   SMS/notification in → Parser → Dedup → Room + Supabase sync → TTS announcement
 *
 * State exposure:
 *   - events: Flow<List<PaymentEvent>> recent list (for Transactions screen)
 *   - isListening: Boolean — is the ForegroundService running?
 *   - lastParsed: ParsedEvent? — last successful parse result (for diagnostics)
 *   - announcementText: String? — current TTS queue text (for UI status)
 *   - isAnnouncing: Boolean — is TTS currently speaking?
 */
class MainViewModel(
    private val repository: PaymentEventRepository,
    private val parser: ParserRegistry,
    private val dedup: DedupIndex,
    private val tts: TtsEngine
) : ViewModel() {

    // Recent events for the Transactions screen
    private val _events = flowMutableStateOf<List<PaymentEvent>>()
    val events: Flow<List<PaymentEvent>> = _events

    // UI status
    private val _isListening = false // set by service layer
    val isListening: Flow<Boolean> = _isListening.asFlow()

    // Last parse result shown in diagnostics
    private val _lastParsed = flowMutableStateOf<com.vivekray898.payalert.parsers.ParsedEvent?>(null)
    val lastParsed: Flow<com.vivekray898.payalert.parsers.ParsedEvent?> = _lastParsed

    // TTS announcement state
    private val _announcementText = flowMutableStateOf<String?>(null)
    val announcementText: Flow<String?> = _announcementText

    private val _isAnnouncing = false
    val isAnnouncing: Flow<Boolean> = _isAnnouncing.asFlow()

    // Scope: auto-cancel on ViewModel destroy
    private val jobs = isActive

    init {
        // Observe repository changes & dedup events; update UI state
        val repoFlow = repository.getUnsynced().map { it.asFlow() }
        // For now, we just wire the pipeline in onEvent — called from Service
    }

    /// Called by the ForegroundService when a new parsed event is ready.
    /// The service has already done dedup; if it's new we insert + announce.
    fun onNewParsedEvent(event: com.vivekray898.payalert.parsers.ParsedEvent) {
        viewModelScope.launch {
            // 1) Dedup already done by service; but double-check locally
            if (!dedup.isSeen(event.dedupKey)) {
                dedup.markSeen(event.dedupKey)
            }

            // 2) Insert into Room + optional sync
            repository.insert(PaymentEvent(
                id = event.eventId,
                deviceId = /* TODO: get from context */ "",
                kind = event.kind,
                bankOrUpi = event.bankOrUpi,
                amount = event.amount,
                currency = event.currency,
                merchant = event.merchant,
                utr = event.utr,
                txId = event.txId,
                receivedAt = Timestamp.from(event.receivedAtMillis),
                dedupKey = event.dedupKey,
                rawSender = event.sender,
                rawBody = event.rawBody,
                category = event.category,
                synced = false,
                createdAt = Timestamp.from(event.eventMillis),
                updatedAt = Timestamp.from(event.eventMillis)
            ))

            // 3) Update UI state
            _lastParsed.value = event

            // 4) Announce via TTS (non-blocking)
            val text = buildAnnouncement(event)
            _announcementText.value = text
            tts.announce(text, utteranceId = event.dedupKey)

            // 5) Sync to Supabase best-effort
            repository.syncPending()
        }
    }

    /// Build the spoken announcement string from a parsed event.
    private fun buildAnnouncement(event: com.vivekray898.payalert.parsers.ParsedEvent): String {
        val amount = event.amount
        val merchant = event.merchant ?: "unknown sender"
        val time = java.text.DateFormat.getTimeInstance().format(java.util.Date.from(event.receivedAtMillis))
        return "You received \${amount} from \${merchant} at \${time}"
    }

    /// Service-state updates — called from the ForegroundService connection layer.
    fun setListening(listening: Boolean) {
        _isListening.value = listening
    }

    /// Called when the user toggles announcements off/on in Settings.
    fun setAnnouncementsEnabled(enabled: Boolean) {
        if (enabled) {
            // ensure TTS is initialized; the service will call init()
        } else {
            tts.stop()
        }
    }
}