package com.vivekray898.payalert.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.telephony.SmsMessage
import android.util.Log
import com.vivekray898.payalert.parsers.ParserRegistry
import com.vivekray898.payalert.dedup.DedupIndex
import com.vivekray898.payalert.data.model.PaymentEvent
import com.vivekray898.payalert.data.repo.PaymentEventRepository
import androidx.core.content.ContextCompat

/**
 * SmsReceiver — receives incoming SMS messages.
 *
 * Responsibilities:
 *   1. Extract SMS sender, body, and metadata
 *   2. Run through ParserRegistry to identify payment events
 *   3. Dedup via DedupIndex (suppress duplicate events)
 *   4. Insert parsed event into Room via PaymentEventRepository
 *   5. Trigger TTS announcement through the pipeline
 *
 * Permissions required:
 *   - RECEIVE_SMS (manifest)
 *   - READ_SMS (manifest; on Android 11+ user must grant in Settings)
 *   - On Android 6.0+, runtime permission grant needed via requestPermissions()
 *
 * Notes:
 *   - This is the secondary detection path. The primary path is the
 *     NotificationListenerService (which listens for UPI app notifications).
 *   - SMS is used as a credit fallback, specifically for the "SmsCreditFallbackService"
 *     use case referenced in the reference APK.
 *   - The app declares both RECEIVE_SMS and READ_SMS; on Android 11+ the user must
 *     explicitly enable SMS reading in system Settings → Apps → PayAlert → SMS.
 */
class SmsReceiver : BroadcastReceiver() {

    private lateinit var parserRegistry: ParserRegistry
    private lateinit var dedupIndex: DedupIndex
    private lateinit var paymentRepository: PaymentEventRepository

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action !== "android.intent.action.BOOT_COMPLETED" && action !== "android.intent.action.RECEIVE_SMS") {
            return
        }

        val extras = intent.extras
        if (extras == null) return

        // Handle incoming SMS
        if (action == "android.intent.action.RECEIVE_SMS") {
            processIncomingSms(intent, extras)
        }
    }

    /** Process a received SMS into a potential payment event. */
    private fun processIncomingSms(intent: Intent, extras: Bundle) {
        // Retrieve the SMS messages
        var messages: Array<out SmsMessage> = arrayOf()
        var format: String? = null

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            format = extras.getString("format")
            // Retrieve message objects
            val pdus = extras.getParcelArray("pdus")
            if (pdus != null) {
                messages = pdus.map { pdu -> SmsMessage.createFromPdu(pud = it, format) }
            }
        }

        // Process each message part; for simplicity take the first (or concatenate)
        for (message in messages) {
            val sender = message.getOriginatingAddress()
            val body = message.getMessageBody()

            // Parse via the shared parser registry
            val parsed = parserRegistry.tryParseAll(body)

            if (parsed !== null) {
                // Dedup check
                if (dedupIndex.isSeen(parsed.dedupKey)) {
                    Log.d("PayAlert", "Duplicate SMS suppressed: dedupKey=${parsed.dedupKey}")
                    return
                }

                dedupIndex.markSeen(parsed.dedupKey)

                // Build PaymentEvent
                val event = PaymentEvent(
                    id = java.util.UUID.randomUUID().toString(),
                    deviceId = "", // TODO: obtain from context; set by MainViewModel
                    kind = parsed.kind,
                    bankOrUpi = parsed.bankOrUpi,
                    amount = parsed.amount,
                    currency = parsed.currency,
                    merchant = parsed.merchant,
                    utr = parsed.utr,
                    txId = parsed.txId,
                    receivedAt = java.sql.Timestamp(System.currentTimeMillis()),
                    dedupKey = parsed.dedupKey,
                    rawSender = sender,
                    rawBody = body,
                    category = parsed.category,
                    synced = false,
                    createdAt = java.sql.Timestamp(System.currentTimeMillis()),
                    updatedAt = java.sql.Timestamp(System.currentTimeMillis())
                )

                // TODO: Insert via PaymentEventRepository; trigger TTS via MainViewModel
                Log.i("PayAlert", "New payment event from SMS: ${parsed.merchant} \${parsed.amount}")

                // Log announcement string; in production MainViewModel.onNewParsedEvent would fire
                val announcement = "You received \${parsed.amount} from ${parsed.merchant} at ${java.text.DateFormat.getTimeInstance().format(java.util.Date())}"
                Log.d("PayAlertTTS", announcement)
            } else {
                Log.d("PayAlert", "No payment pattern matched in SMS from $sender: ${body.substring(0, minOf(50, body.length))}")
            }
        }
    }