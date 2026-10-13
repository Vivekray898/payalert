package com.vivekray898.payalert.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.DisposableEffect
import com.vivekray898.payalert.ui.components.SectionCard
import com.vivekray898.payalert.ui.components.ValueRow
import com.vivekray898.payalert.ui.formatAmount
import com.vivekray898.payalert.ui.formatTimestamp

@Composable
fun HomeScreen(
    onOpenTransactions: () -> Unit,
    onOpenQr: () -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val monitoring by viewModel.monitoring.collectAsState()

    // Permission grants happen in system Settings, so re-read state whenever we come back.
    val context = LocalContext.current
    DisposableEffect(context) {
        val activity = context as? ComponentActivity
        if (activity == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
            }
            activity.lifecycle.addObserver(observer)
            onDispose { activity.lifecycle.removeObserver(observer) }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "PayAlert",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        item {
            SectionCard(title = "This month") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Money in", style = MaterialTheme.typography.labelMedium)
                        Text(
                            formatAmount(state.monthIn),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Column {
                        Text("Money out", style = MaterialTheme.typography.labelMedium)
                        Text(
                            formatAmount(state.monthOut),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Text(
                    text = "Net ${formatAmount(state.monthIn - state.monthOut)}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        item {
            SectionCard(title = "Detection status") {
                ValueRow("Monitoring", if (monitoring) "On" else "Off")
                ValueRow("SMS permission", if (state.smsPermission) "Granted" else "Not granted")
                ValueRow(
                    "Notification access",
                    if (state.notificationAccess) "Granted" else "Not granted"
                )
                ValueRow("Battery optimisation", if (state.batteryExempt) "Exempt" else "Restricted")
                ValueRow("Stored payments", state.storedCount.toString())
                ValueRow(
                    "Rule set",
                    if (state.parserConfigLoaded) state.parserRuleSet else "asset failed to load"
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenTransactions, modifier = Modifier.weight(1f)) {
                    Text("Transactions")
                }
                Button(onClick = onOpenQr, modifier = Modifier.weight(1f)) { Text("Payment QR") }
            }
        }

        item { Text("Recent payments", style = MaterialTheme.typography.titleMedium) }

        if (state.recent.isEmpty()) {
            item {
                Text(
                    text = "No payments detected yet. Make sure monitoring, SMS permission and " +
                        "notification access are granted.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        } else {
            items(state.recent, key = { it.id }) { event ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                event.merchant ?: event.bankOrUpi ?: "Payment",
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "${formatTimestamp(event.receivedAt)} · ${event.source}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        Text(
                            (if (event.kind == "credit") "+" else "-") + formatAmount(event.amount),
                            color = if (event.kind == "credit") {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    }
                }
            }
        }

        item {
            TextButton(onClick = viewModel::refresh) { Text("Refresh") }
        }
    }
}
