package com.vivekray898.payalert.services

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.content.Context
import android.util.Log
import com.vivekray898.payalert.ui.main.MainViewModel
import com.vivekray898.payalert.parsers.ParserRegistry
import com.vivekray898.payalert.dedup.DedupIndex
import com.vivekray898.payalert.data.model.PaymentEvent
import com.vivekray898.payalert.data.repo.PaymentEventRepository
import androidx.lifecycle.viewmodel.compose.viewModelStoreOwner

/**
 * NotificationListenerService — receives system notification events.
 *
 * Responsibilities:
 *   1. Filter notifications for payment-related content (UPI confirmations, bank alerts, etc.)
 *   2. Extract sender, amount, merchant, UTR via ParserRegistry
 *   3. Dedup via DedupIndex (suppress duplicate events)
 *   4. Insert parsed event into Room via PaymentEventRepository
 *   5. Trigger TTS announcement through MainViewModel
 *
 * Important:
 *   - Foreground service must be running for this to receive events on API 31+
 *   - Manifest declares FOREGROUND_SERVICE_SPECIAL_USE matching reference's subtype
 *   - Permission BIND_NOTIFICATION_LISTENER_SERVICE required at runtime
 *   - On Android 11+, user must grant notification access in Settings
 */
class NotificationListenerService : NotificationListenerService() {

    private lateinit var parserRegistry: ParserRegistry
    private lateinit var dedupIndex: DedupIndex
    private lateinit var paymentRepository: PaymentEventRepository

    override fun onCreate() {
        super.onCreate()
        // Dependencies are provided via the ViewModel/Service layer that instantiates this.
        // In a manual-DI setup, we look them up via static or application context.
        // For now, we defer initialization to onListeningChanged / onEvent — the
        // host MainActivity's ViewModel provides the collaborations.
    }

    /** Called system-wide whenever a notification are posted/updated/removed. */
    override fun onEvent(key: String, bundle: Bundle?) {
        super.onEvent(key, bundle)

        bundle?.let { bundle ->
            val sbn = bundle.getParcelable("sbn") as? StatusBarNotification
            sbn?.let { notify ->
                // Filter: only notify events from UPI/bank apps we care about
                // In a full app, maintain an allowlist; for now, process all and let
                // the parser decide if the body matches a payment pattern.
                parseNotification(notify)
            }
        }
    }

    /** Parse a StatusBarNotification for payment data. */
    private fun parseNotification(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        val key = sbn.key
        val txt = sbn.extras.getCharSequence("android.text")?.toString() ?: ""
        val txtRev = sbn.extras.getCharSequence("android.text")?.toString() ?: ""

        // Attempt to parse via the shared parser registry
        val parsed = parserRegistry.tryParseAll(txt)

        if (parsed !== null) {
            // Dedup check — if already seen, skip
            if (dedupIndex.isSeen(parsed.dedupKey)) {
                Log.d("PayAlert", "Duplicate notification suppressed: $key")
                return
            }

            dedupIndex.markSeen(parsed.dedupKey)

            // Build a PaymentEvent and insert via repository
            // The repository handles Room insert + best-effort Supabase sync
            val event = PaymentEvent(
                id = java.util.UUID.randomUUID().toString(),
                deviceId = "", // TODO: obtain from context; will be set by MainViewModel
                kind = parsed.kind,
                bankOrUpi = parsed.bankOrUpi,
                amount = parsed.amount,
                currency = parsed.currency,
                merchant = parsed.merchant,
                utr = parsed.utr,
                txId = parsed.txId,
                receivedAt = java.sql.Timestamp(System.currentTimeMillis()),
                dedupKey = parsed.dedupKey,
                rawSender = parsed.sender,
                rawBody = txt,
                category = parsed.category,
                synced = false,
                createdAt = java.sql.Timestamp(System.currentTimeMillis()),
                updatedAt = java.sql.Timestamp(System.currentTimeMillis())
            )

            // TODO: MainViewModel / PaymentEventRepository should handle the insert
            // For now, log; in production the ViewModel's onNewParsedEvent would be called
            Log.i("PayAlert", "New payment event from notification: ${parsed.merchant} \${parsed.amount}")

            // Trigger TTS announcement via the ViewModel
            // MainViewModel.onNewParsedEvent(parsed) — would be called from the service
            // host layer. Here we log since the collaborations aren't wired in this file alone.
            Log.d("PayAlertTTS", "Would announce: " + buildAnnouncement(parsed))
        } else {
            Log.d("PayAlert", "No payment pattern matched in notification from $pkg")
        }
    }

    /** Build a spoken announcement string from a parsed event. */
    private fun buildAnnouncement(parsed: com.vivekray898.payalert.parsers.ParsedEvent): String {
        val amount = parsed.amount
        val merchant = parsed.merchant ?: "unknown sender"
        val time = java.text.DateFormat.getTimeInstance().format(java.util.Date())
        return "You received \${amount} from \${merchant} at \${time}"
    }

    /** Android system calls this when the listener's permission state changes. */
    override fun onListeningChanged(isListening: Boolean) {
        super.onListeningChanged(isListening)
        Log.i("PayAlert", "Notification listener ${if (isListening) "granted" else "denied"}")
        // TODO: update UI state via ViewModel
    }
}