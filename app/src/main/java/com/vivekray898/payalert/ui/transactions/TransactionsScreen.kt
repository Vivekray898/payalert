package com.vivekray898.payalert.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vivekray898.payalert.R
import com.vivekray898.payalert.data.PaymentEvent
import com.vivekray898.payalert.export.TransactionExporter
import com.vivekray898.payalert.ui.SourceLabel
import com.vivekray898.payalert.ui.components.EmptyState
import com.vivekray898.payalert.ui.components.SearchField
import com.vivekray898.payalert.ui.components.StatChip
import com.vivekray898.payalert.ui.formatAmount
import com.vivekray898.payalert.ui.formatTimestamp

/** Small-caps group header above a chip row, so direction and range never read as one strip. */
@Composable
private fun ChipGroupHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}

/** One chip row that scrolls horizontally instead of wrapping. */
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    onOpenAnalytics: () -> Unit = {},
    viewModel: TransactionsViewModel = viewModel()
) {
    val events by viewModel.events.collectAsState()
    val totals by viewModel.filteredTotals.collectAsState()
    val kind by viewModel.kind.collectAsState()
    val term by viewModel.term.collectAsState()
    val account by viewModel.account.collectAsState()
    val range by viewModel.range.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                actions = {
                    IconButton(onClick = onOpenAnalytics) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_chart),
                            contentDescription = "Analytics"
                        )
                    }
                    IconButton(
                        onClick = {
                            viewModel.snapshot { rows ->
                                com.vivekray898.payalert.ui.components.ExportShare.share(
                                    context,
                                    "payalert-history.csv",
                                    "text/csv",
                                    TransactionExporter.eventsToCsv(rows)
                                )
                            }
                        }
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_export),
                            contentDescription = "Export history"
                        )
                    }
                    IconButton(onClick = { viewModel.clearFilters() }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_filter),
                            contentDescription = "Clear filters"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            SearchField(
                value = term,
                onValueChange = { viewModel.setTerm(it) },
                label = "Search payments",
                placeholder = "Merchant, reference or message"
            )

            ChipGroupHeader("Direction")
            ChipRow {
                FilterChip(selected = kind == null, onClick = { viewModel.setKind(null) }, label = { Text("Both") })
                FilterChip(selected = kind == "credit", onClick = { viewModel.setKind("credit") }, label = { Text("In") })
                FilterChip(selected = kind == "debit", onClick = { viewModel.setKind("debit") }, label = { Text("Out") })
            }

            ChipGroupHeader("Range")
            ChipRow {
                HistoryRange.entries.forEach { option ->
                    FilterChip(
                        selected = range == option,
                        onClick = { viewModel.setRange(option) },
                        label = { Text(option.label) }
                    )
                }
            }

            if (accounts.isNotEmpty()) {
                ChipGroupHeader("Account")
                ChipRow {
                    FilterChip(selected = account == null, onClick = { viewModel.setAccount(null) }, label = { Text("All") })
                    accounts.forEach { name ->
                        FilterChip(
                            selected = account == name,
                            onClick = { viewModel.setAccount(name) },
                            label = { Text(name) }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatChip("In", formatAmount(totals.first), MaterialTheme.colorScheme.tertiary)
                StatChip("Out", formatAmount(totals.second), MaterialTheme.colorScheme.secondary)
                StatChip(
                    "Items",
                    events.size.toString(),
                    MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (events.isEmpty()) {
                EmptyState(
                    icon = R.drawable.ic_search,
                    title = "No transactions",
                    description = "Nothing matches these filters yet. Detected payments appear here as soon as the monitor sees them."
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(events, key = { it.id }) { event ->
                        TransactionRow(event)
                    }
                }
            }
        }
    }
}

/** A history row: a circular direction avatar, the merchant as stored, and a signed amount. */
@Composable
private fun TransactionRow(event: PaymentEvent) {
    val credit = event.kind == "credit"
    val accent = if (credit) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(accent.copy(alpha = 0.12f), MaterialTheme.shapes.large),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(
                        id = if (credit) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down
                    ),
                    contentDescription = if (credit) "Money in" else "Money out",
                    tint = accent,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.merchant ?: event.bankOrUpi ?: "Payment",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${formatTimestamp(event.receivedAt)} · ${SourceLabel.displaySource(event)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                event.category?.takeIf { it.isNotBlank() }?.let { category ->
                    Text(
                        text = category,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.size(4.dp))

            Text(
                text = (if (credit) "+" else "\u2212") + formatAmount(event.amount),
                style = MaterialTheme.typography.titleMedium,
                color = accent
            )
        }
    }
}
