package com.vivekray898.payalert.ui.help

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vivekray898.payalert.ui.components.OverlayHeader
import com.vivekray898.payalert.ui.components.SectionCard

private data class FaqEntry(val question: String, val answer: String)

private val FAQ = listOf(
    FaqEntry(
        "Why was my payment not announced?",
        "Check three things: monitoring is switched on, SMS permission is granted, and " +
            "notification access is enabled for PayAlert. The Diagnostics screen shows the " +
            "exact reason for the last message it saw."
    ),
    FaqEntry(
        "A payment was announced twice.",
        "Detection runs on both SMS and notifications. A payment seen through both channels " +
            "inside the duplicate window is collapsed into one event. Increase the window in " +
            "Settings if your bank sends the SMS late."
    ),
    FaqEntry(
        "The app stops announcing after a while.",
        "Android may stop background work to save battery. Exempt PayAlert from battery " +
            "optimisation in Settings, and keep the monitoring switch on. The app re-arms " +
            "itself after a reboot."
    ),
    FaqEntry(
        "Does PayAlert need internet?",
        "Detection, history, the cashbook and reminders work fully offline without an account. " +
            "Optional Team & devices uses Google sign-in and internet to share device names and " +
            "pairing records, but does not upload your payments or messages."
    ),
    FaqEntry(
        "How do I connect an employee's phone?",
        "Open Settings → Team & devices on both phones and sign in. The owner creates a code; " +
            "the employee chooses I'm an employee and enters it within 10 minutes. Tap Refresh " +
            "devices on the owner's phone after joining. Money received on the owner's phone is " +
            "then announced on the employee's phone, and the employee stops announcing its own " +
            "SMS and notifications."
    ),
    FaqEntry(
        "What is forwarded to the employee's phone?",
        "Only money received, only after pairing, and only as an encrypted envelope that is " +
            "read with a key that lives on the two phones. Money paid out is never forwarded, " +
            "and neither is anything from before pairing. The server relays an envelope it cannot " +
            "open and drops it once the employee's phone confirms delivery. On the employee's " +
            "phone the announcement is kept for 24 hours and then deleted."
    ),
    FaqEntry(
        "What happens if I leave a team?",
        "Leaving or being removed stops forwarding immediately: queued payments are discarded, " +
            "the pairing key is replaced, and announcements already received from that owner are " +
            "deleted from the employee's phone."
    ),
    FaqEntry(
        "Which banks and apps are supported?",
        "Rules are data-driven, so coverage is a configuration change rather than a new build. " +
            "The Diagnostics screen reports how many banks, wallets and sender IDs the current " +
            "rule set covers. Any bank that sends a standard Indian transaction alert is handled " +
            "even without an explicit rule."
    ),
    FaqEntry(
        "How do I export my history?",
        "Open Transactions and use Export to share a CSV or JSON file of the current filter. " +
            "Cashbook entries export the same way."
    )
)

@Composable
fun FaqScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OverlayHeader("Help & FAQ", onBack)
        FAQ.forEach { entry ->
            SectionCard(title = entry.question) {
                Text(entry.answer, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun TermsScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OverlayHeader("Privacy & terms", onBack)

        SectionCard(title = "Your data stays on your device") {
            Text(
                "PayAlert reads transaction messages and payment notifications only to detect " +
                    "payments, and stores what it detects in a local database on this device. " +
                    "These features need no account and never upload payment data. Uninstalling the app removes local data.",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        SectionCard(title = "Optional team pairing") {
            Text(
                "If you choose Team & devices, Google and Supabase handle sign-in. Supabase stores " +
                    "your account profile, a random device ID, device name, last-seen time and " +
                    "pairing records. Connected devices see each other's device names, not SMS, " +
                    "payment history, cashbook or reminders. Codes expire after 10 minutes and " +
                    "can be used once. Sign-in tokens are encrypted with Android Keystore and " +
                    "excluded from backups. Signing out clears local sign-in, but does not remove " +
                    "cloud connections: use Remove device or Leave team before signing out.",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        SectionCard(title = "Permissions and why they are needed") {
            Text(
                "SMS: read bank transaction alerts. Notification access: read payment " +
                    "notifications from UPI and banking apps. Notifications: announce and remind. " +
                    "Foreground service: keep detection alive. Each can be revoked at any time " +
                    "in system settings.",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        SectionCard(title = "No subscription") {
            Text(
                "Every feature in PayAlert is available to everyone. There is no trial, " +
                    "paywall, upgrade prompt or licence check anywhere in the app.",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        SectionCard(title = "No financial advice") {
            Text(
                "PayAlert reports what it detects in messages you already received. It is not " +
                    "a bank, does not move money, and balances quoted by a bank message may " +
                    "differ from your actual statement.",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Text(
            text = "PayAlert is an independent app. It is not affiliated with, endorsed by or " +
                "connected to any bank or payment provider whose alerts it can read.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
