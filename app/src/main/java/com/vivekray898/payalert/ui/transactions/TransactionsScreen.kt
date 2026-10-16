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
import com.vivekray898.payalert.ui.components.RoleIndicator
import com.vivekray898.payalert.ui.components.SearchField
import com.vivekray898.payalert.ui.components.StatChip
import com.vivekray898.payalert.ui.formatAmount
import com.vivekray898.payalert.ui.formatTimestamp
import com.vivekray898.payalert.relay.ReceivedAnnouncement

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
    viewModel: TransactionsViewModel = viewModel(),
    role: String? = null
) {
    val events by viewModel.events.collectAsState()
    val receivedAnnouncements by viewModel.receivedAnnouncements.collectAsState()
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
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("History")
                        RoleIndicator(role)
                    }
                },
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
        // The filters are the first list content rather than a sibling overlay. They now scroll
        // away with the history, and the first payment row always has a real position below them.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "search") {
                SearchField(
                    value = term,
                    onValueChange = { viewModel.setTerm(it) },
                    label = "Search payments",
                    placeholder = "Merchant, reference or message"
                )
            }

            item(key = "filters") {
                Column {
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
                }
            }

            item(key = "totals") {
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
            }

            if (receivedAnnouncements.isNotEmpty()) {
                item(key = "received-owner-header") {
                    FromOwnerSectionHeader()
                }
                items(
                    items = receivedAnnouncements,
                    key = { "received-owner-${it.messageId}" }
                ) { announcement ->
                    ReceivedAnnouncementRow(announcement)
                }
            }

            if (events.isEmpty()) {
                item(key = "empty-transactions") {
                    EmptyState(
                        icon = R.drawable.ic_search,
                        title = "No transactions",
                        description = "Nothing matches these filters yet. Detected payments appear here as soon as the monitor sees them."
                    )
                }
            } else {
                items(events, key = { it.id }) { event ->
                    TransactionRow(event)
                }
            }
        }
    }
}

@Composable
private fun FromOwnerSectionHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "From owner",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        OwnerAnnouncementBadge()
    }
}

@Composable
private fun OwnerAnnouncementBadge() {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = "From owner",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

/**
 * A history row for a payment the owner forwarded.
 *
 * Only the fields decrypted on this device are shown; the announcement itself is never uploaded and
 * the owner's raw message is not part of the relay, so there is nothing else to display.
 */
@Composable
internal fun ReceivedAnnouncementRow(announcement: ReceivedAnnouncement) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.34f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OwnerAnnouncementBadge()
                Text(
                    text = receivedAnnouncementDescription(announcement),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                if (announcement.hasAmount()) {
                    Text(
                        text = "+${formatAmount(requireNotNull(announcement.amount))}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            Text(
                text = announcement.ownerLabel?.let { "From $it" } ?: "From the owner device",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = announcement.eventAt
                    ?.takeIf { it in 1 until announcement.receivedAt }
                    ?.let { "Paid ${formatTimestamp(it)} · received ${formatTimestamp(announcement.receivedAt)}" }
                    ?: "Received ${formatTimestamp(announcement.receivedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
