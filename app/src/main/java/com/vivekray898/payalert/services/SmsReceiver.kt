package com.vivekray898.payalert.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.vivekray898.payalert.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob

/**
 * Secondary detection path: bank SMS alerts. Long messages arrive as several PDUs,
 * so parts are re-joined per sender before parsing.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)?.toList().orEmpty()
        } catch (error: Exception) {
            null
        } ?: return
        if (messages.isEmpty()) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val pipeline = AppGraph.get(appContext).pipeline
                messages
                    .groupBy { it.originatingAddress ?: "unknown" }
                    .forEach { (sender, parts) ->
                        val body = parts.joinToString(separator = "") { it.messageBody.orEmpty() }
                        if (body.isNotBlank()) {
                            pipeline.handle(body, SOURCE, sender)
                        }
                    }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val SOURCE = "sms"
    }
}
