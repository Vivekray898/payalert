package com.vivekray898.payalert.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vivekray898.payalert.role.LocalDeviceRole
import com.vivekray898.payalert.ui.PermissionStatus
import com.vivekray898.payalert.ui.components.OverlayHeader
import com.vivekray898.payalert.ui.components.SectionCard
import com.vivekray898.payalert.ui.components.ValueRow
import com.vivekray898.payalert.ui.formatAge
import com.vivekray898.payalert.ui.formatAmount

@Composable
fun DiagnosticsScreen(onBack: () -> Unit, viewModel: DiagnosticsViewModel = viewModel()) {
    val context = LocalContext.current
    val lastOutcome by viewModel.lastOutcome.collectAsState()
    val testOutcome by viewModel.testOutcome.collectAsState()
    val listenerConnected by viewModel.listenerConnected.collectAsState()
    val eventCount by viewModel.eventCount.collectAsState()
    val dedupCount by viewModel.dedupCount.collectAsState()
    val lastRelayPushAt by viewModel.lastRelayPushAt.collectAsState()
    val relayStatus by viewModel.relayStatus.collectAsState()

    var testText by remember {
        mutableStateOf("Rs.1,250.00 credited to A/c XX4321 on 12-05-24 from ACME STORE. UTR 412345678901")
    }
    var testSource by remember { mutableStateOf("sms") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OverlayHeader("Diagnostics", onBack)

        SectionCard(title = "Live status") {
            ValueRow("Monitoring", "Enabled")
            ValueRow("Notification listener", if (listenerConnected) "Connected" else "Not connected")
            ValueRow(
                "SMS permission",
                if (PermissionStatus.hasReceiveSms(context)) "Granted" else "Not granted"
            )
            ValueRow(
                "Notification access",
                if (PermissionStatus.hasNotificationAccess(context)) "Granted" else "Not granted"
            )
            ValueRow(
                "Background allowed",
                if (PermissionStatus.isIgnoringBatteryOptimizations(context)) "Yes" else "No"
            )
            ValueRow("Stored payments", eventCount.toString())
            ValueRow("Dedup keys", dedupCount.toString())
            ValueRow(
                "Rule set",
                if (viewModel.ruleSetLoaded) {
                    "${viewModel.bankCount} banks · ${viewModel.walletCount} wallets · " +
                        "${viewModel.senderCount} sender ids"
                } else {
                    "asset failed to load"
                }
            )
        }

        SectionCard(title = "Relay delivery") {
            ValueRow(
                "This device",
                if (viewModel.relayRole == LocalDeviceRole.EMPLOYEE) "Employee" else "Owner"
            )
            ValueRow(
                "Relay service",
                if (viewModel.relayConfigured) "Configured" else "Not configured"
            )
            ValueRow(
                "Push wake-up",
                lastRelayPushAt?.let { "Last push ${formatAge(it)}" } ?: "No push received"
            )
            if (viewModel.relayRole == LocalDeviceRole.EMPLOYEE) {
                ValueRow("Announcements received", relayStatus.receivedCount.toString())
            } else {
                ValueRow("Last forwarded", relayStatus.lastForwardedAt?.let { formatAge(it) } ?: "Never")
                ValueRow("Waiting to forward", relayStatus.pendingDeliveries.toString())
                ValueRow("Failed to forward", relayStatus.failedDeliveries.toString())
            }
            if (lastRelayPushAt == null && viewModel.relayRole == LocalDeviceRole.EMPLOYEE) {
                Text(
                    text = "No push has arrived on this device. Payments are being picked up by " +
                        "the background poll instead, which is why they can arrive late. Check " +
                        "that the relay-notify function and its database webhook are deployed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        SectionCard(title = "Last processed message") {
            val outcome = lastOutcome
            if (outcome == null) {
                Text("Nothing processed yet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                ValueRow("Result", outcome.reason)
                ValueRow("Source", outcome.source)
                outcome.amount?.let { ValueRow("Amount", formatAmount(it)) }
                outcome.dedupKey?.let { ValueRow("Dedup key", it) }
                outcome.announcement?.let { ValueRow("Announcement", it) }
            }
        }

        SectionCard(title = "Run a test message") {
            Text(
                text = "Feed text through the real pipeline: it parses, deduplicates, stores and speaks.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            OutlinedTextField(
                value = testText,
                onValueChange = { testText = it },
                label = { Text("Message text") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = testSource == "sms",
                    onClick = { testSource = "sms" },
                    label = { Text("As SMS") }
                )
                FilterChip(
                    selected = testSource == "notification",
                    onClick = { testSource = "notification" },
                    label = { Text("As notification") }
                )
            }
            Button(
                onClick = { viewModel.runTest(testText, testSource) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Run through pipeline") }
            Button(
                onClick = { viewModel.speak(testText) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Speak the raw text") }

            val outcome = testOutcome
            if (outcome != null) {
                ValueRow("Result", outcome.reason)
                outcome.amount?.let { ValueRow("Parsed amount", formatAmount(it)) }
                outcome.kind?.let { ValueRow("Direction", it) }
                outcome.merchant?.let { ValueRow("Merchant", it) }
                outcome.dedupKey?.let { ValueRow("Dedup key", it) }
                outcome.parserName?.let { ValueRow("Rule set", it) }
                outcome.announcement?.let { ValueRow("Spoken", it) }
            }
        }

        SectionCard(title = "Maintenance") {
            Text(
                text = "Clearing dedup keys lets the same payment be announced again — useful for testing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Button(
                onClick = { viewModel.clearEvents() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Clear stored payments") }
            Button(
                onClick = { viewModel.clearDedup() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Clear dedup keys") }
        }
    }
}
