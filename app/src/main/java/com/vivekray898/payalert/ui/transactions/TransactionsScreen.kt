package com.vivekray898.payalert.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vivekray898.payalert.data.PaymentEvent
import com.vivekray898.payalert.export.TransactionExporter
import com.vivekray898.payalert.ui.components.ExportShare
import com.vivekray898.payalert.ui.components.ValueRow
import com.vivekray898.payalert.ui.formatAmount
import com.vivekray898.payalert.ui.formatTimestamp

@Composable
fun TransactionsScreen(
    onOpenAnalytics: () -> Unit,
    viewModel: TransactionsViewModel = viewModel()
) {
    val events by viewModel.events.collectAsState()
    val kind by viewModel.kind.collectAsState()
    val term by viewModel.term.collectAsState()
    val account by viewModel.account.collectAsState()
    val range by viewModel.range.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val totals by viewModel.filteredTotals.collectAsState()

    val context = LocalContext.current
    var selected by remember { mutableStateOf<PaymentEvent?>(null) }
    var showExport by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "History",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary
            )
            TextButton(onClick = onOpenAnalytics) { Text("Analytics") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "In: ${formatAmount(totals.first)}",
                color = MaterialTheme.colorScheme.primary
            )
            Text("Out: ${formatAmount(totals.second)}", color = MaterialTheme.colorScheme.error)
            Text("${events.size} shown", style = MaterialTheme.typography.bodySmall)
        }

        OutlinedTextField(
            value = term,
            onValueChange = viewModel::setTerm,
            label = { Text("Search merchant, bank, reference or message") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = kind == null,
                onClick = { viewModel.setKind(null) },
                label = { Text("Both") }
            )
            FilterChip(
                selected = kind == "credit",
                onClick = { viewModel.setKind("credit") },
                label = { Text("In") }
            )
            FilterChip(
                selected = kind == "debit",
                onClick = { viewModel.setKind("debit") },
                label = { Text("Out") }
            )
            HistoryRange.entries.forEach { preset ->
                FilterChip(
                    selected = range == preset,
                    onClick = { viewModel.setRange(preset) },
                    label = { Text(preset.label) }
                )
            }
        }

        if (accounts.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = account == null,
                    onClick = { viewModel.setAccount(null) },
                    label = { Text("Any account") }
                )
                accounts.forEach { value ->
                    FilterChip(
                        selected = account == value,
                        onClick = { viewModel.setAccount(value) },
                        label = { Text(value) }
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { showExport = true }) { Text("Export") }
            TextButton(onClick = viewModel::clearFilters) { Text("Clear filters") }
        }

        if (events.isEmpty()) {
            Text(
                text = "No payments match this filter.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(events, key = { it.id }) { event ->
                    EventRow(event = event, onClick = { selected = event })
                }
            }
        }
    }

    selected?.let { event ->
        EventDetailDialog(event = event, onDismiss = { selected = null })
    }

    if (showExport) {
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text("Export ${events.size} payments") },
            text = {
                Text("Only the payments currently shown are exported.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showExport = false
                    viewModel.snapshot { rows ->
                        ExportShare.share(
                            context,
                            "payalert-history.csv",
                            "text/csv",
                            TransactionExporter.eventsToCsv(rows)
                        )
                    }
                }) { Text("CSV") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExport = false
                    viewModel.snapshot { rows ->
                        ExportShare.share(
                            context,
                            "payalert-history.json",
                            "application/json",
                            TransactionExporter.eventsToJson(rows)
                        )
                    }
                }) { Text("JSON") }
            }
        )
    }
}

@Composable
private fun EventRow(event: PaymentEvent, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = event.merchant ?: event.bankOrUpi ?: "Unknown",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = buildString {
                        append(formatTimestamp(event.receivedAt))
                        append(" · ")
                        append(event.source)
                        event.account?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            Text(
                text = (if (event.kind == "credit") "+" else "-") + formatAmount(event.amount),
                style = MaterialTheme.typography.titleMedium,
                color = if (event.kind == "credit") {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }
    }
}

@Composable
private fun EventDetailDialog(event: PaymentEvent, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(if (event.kind == "credit") "Money received" else "Money sent") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ValueRow("Amount", formatAmount(event.amount) + " " + event.currency)
                ValueRow("Bank / app", event.bankOrUpi ?: "Unknown")
                ValueRow("Merchant", event.merchant ?: "Unknown")
                ValueRow("Reference", event.utr ?: "—")
                ValueRow("Account", event.account ?: "—")
                ValueRow("Balance quoted", event.balance?.let { formatAmount(it) } ?: "—")
                ValueRow("Category", event.category ?: "—")
                ValueRow("Detected via", event.source)
                ValueRow("Source", event.sourceDetail ?: "—")
                ValueRow("Received", formatTimestamp(event.receivedAt))
                event.rawText?.let {
                    Text(
                        text = "Message: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    )
}
