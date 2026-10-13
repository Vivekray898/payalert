package com.vivekray898.payalert.ui.diagnostics

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.TextStyle
import com.vivekray898.payalert.R
import com.vivekray898.payalert.data.model.PaymentEvent
import com.vivekray898.payalert.parsers.ParsedEvent
import com.vivekray898.payalert.tts.TtsEngine
import kotlinx.coroutines.flow.collectLatest

@Composable
fun DiagnosticsScreen(
    // ViewModel state flows — injected by the host
    events: List<PaymentEvent> = emptyList(),
    isListening: Boolean = false,
    lastParsed: ParsedEvent? = null,
    announcementText: String? = null,
    isAnnouncing: Boolean = false,
    onToggleAnnouncements: (Boolean) -> Unit = { },
    onRequestNotificationPermission: () -> Unit = { },
    onRequestSmsPermission: () -> Unit = { },
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.Start
    ) {

        // --- Header ---
        Text(
            text = "PayAlert Diagnostics",
            style = MaterialTheme.typography.h5,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // --- Notification listener permission ---
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(text = "Notification listener:", style = MaterialTheme.typography.body2)
            Spacer(modifier = Modifier.width(4.dp))
            OutlinedTextField(
                value = if (isListening) "Granted" else "Denied",
                readOnly = true,
                onValueChange = { },
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRequestNotificationPermission) {
                Icon(Icons.Default.LocationOn, contentDescription = "Request notification permission")
            }
        }

        // --- SMS permission ---
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(text = "SMS permission:", style = MaterialTheme.typography.body2)
            Spacer(modifier = Modifier.width(4.dp))
            OutlinedTextField(
                value = if (isListening) "Granted" else "Denied",
                readOnly = true,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRequestSmsPermission) {
                Icon(Icons.Default.Sms, contentDescription = "Request SMS permission")
            }
        }

        // --- Foreground service state ---
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(text = "Foreground service:", style = MaterialTheme.typography.body2)
            Spacer(modifier = Modifier.width(4.dp))
            OutlinedTextField(
                value = if (isListening) "Running" else "Stopped",
                readOnly = true,
                modifier = Modifier.weight(1f)
            )
        }

        // --- Last SMS received ---
        expandableSection(
            title = "Last SMS received",
            expanded = expanded
        ) {
            if (events.isEmpty()) {
                Text("No SMS events yet.", style = MaterialTheme.typography.body2)
            } else {
                val last = events.last()
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    Text("Sender: ${last.raw_sender ?: "unknown"}")
                    Text("Body excerpt: ${ truncate(last.raw_body ?: "none", 80) }")
                    Text("Kind: ${last.kind} | Amount: ${last.amount} ${last.currency}")
                    Text("Received: ${last.received_at?.toString() ?: "N/A"}")
                }
            }
        }

        // --- Last parsed payment event ---
        expandableSection(
            title = "Last parsed payment",
            expanded = expanded
        ) {
            if (lastParsed === null) {
                Text("No payment parsed yet.", style = MaterialTheme.typography.body2)
            } else {
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    Text("Kind: ${lastParsed.kind}")
                    Text("Amount: ${lastParsed.amount} ${lastParsed.currency ?: ""}")
                    Text("Bank/UPI: ${lastParsed.bankOrUpi ?: "generic"}")
                    Text("Merchant: ${lastParsed.merchant ?: "unknown"}")
                    Text("Utr: ${lastParsed.utr ?: "none"}")
                    Text("Category: ${lastParsed.category ?: "none"}")
                }
            }
        }

        // --- TTS announcement state ---
        expandableSection(
            title = "TTS announcement",
            expanded = expanded
        ) {
            Text("Currently speaking: ${if (isAnnouncing) "yes" else "no"}")
            if (announcementText != null) {
                Text("Last text: ${truncate(announcementText, 60)}", style = MaterialTheme.typography.body3)
            }
        }

        // --- Action buttons ---
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spaceEvenly) {
            OutlinedButton(
                onClick = { onToggleAnnouncements(!isAnnouncing) },
                expanded = true
            ) {
                Text(if (isAnnouncing) "Stop Announcements" else "Start Announcements")
            }
            OutlinedButton(
                onClick = { onToggleAnnouncements(false) },
                expanded = true
            ) {
                Text("Stop")
            }
        }
    }
}

/** Helper: truncate a string to max chars with ellipsis. */
private fun truncate(s: String, max: Int): String = if (s.length <= max) s else "${s.substring(0, max - 1)}…"

/** Simple expandable section composable. */
private fun expandableSection(
    title: String,
    expanded: Boolean,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.padding(bottom = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .pointerInput { } // debounce
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.subtitle1,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = "Expand/collapse $title"
            )
        }
        if (expanded) {
            content()
        }
    }
}