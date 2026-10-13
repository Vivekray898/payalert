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
        "No. Detection, history, the cashbook and reminders all work fully offline. Nothing " +
            "is uploaded and no account is required."
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
                    "There is no account and no upload. Uninstalling the app removes the data.",
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
