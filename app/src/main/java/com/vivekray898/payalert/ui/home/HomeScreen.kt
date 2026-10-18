package com.vivekray898.payalert.ui.home

import androidx.activity.ComponentActivity
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vivekray898.payalert.AppGraph
import com.vivekray898.payalert.R
import com.vivekray898.payalert.data.PaymentEvent
import com.vivekray898.payalert.ui.components.BlueHeader
import com.vivekray898.payalert.ui.components.EmptyState
import com.vivekray898.payalert.ui.components.VoicePill
import com.vivekray898.payalert.ui.components.RoleIndicator
import com.vivekray898.payalert.ui.formatAmount
import com.vivekray898.payalert.ui.formatTimestamp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One reference-style payment row. The stored merchant renders without case transforms. */
@Composable
private fun RecentPaymentCard(
    event: PaymentEvent,
    modifier: Modifier = Modifier
) {
    val credit = event.kind == "credit"
    val amountColor = if (credit) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.secondary
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.extraLarge,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(
                        id = if (credit) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down
                    ),
                    contentDescription = if (credit) "Money in" else "Money out",
                    tint = amountColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = event.bankOrUpi ?: "UPI payment",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = event.merchant ?: "Payment received",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatTimestamp(event.receivedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = (if (credit) "+" else "\u2212") + formatAmount(event.amount),
                style = MaterialTheme.typography.titleLarge,
                color = amountColor
            )
        }
    }
}

@Composable
fun HomeScreen(
    onOpenTransactions: () -> Unit,
    onOpenQr: () -> Unit,
    onOpenSettings: () -> Unit = {},
    role: String? = null,
    viewModel: HomeViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val monitoring by viewModel.monitoring.collectAsState()

    val context = LocalContext.current
    val graph = androidx.compose.runtime.remember { AppGraph.get(context) }
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
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item {
            BlueHeader(title = "All Entries") {
                VoicePill(onClick = { graph.tts.speak("PayAlert voice is ready.") })
            }
        }

        item {
            CollectionCard(
                label = "This month's collection",
                amount = formatAmount(state.monthIn),
                date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            )
        }

        if (!monitoring || !state.notificationAccess) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = MaterialTheme.shapes.extraLarge,
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_warning),
                            contentDescription = "Attention needed",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(34.dp)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Soundbox not working",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Tap to enable required permissions",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = onOpenSettings) { Text("Fix now") }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "UPI Transactions",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onOpenTransactions) { Text("View all") }
            }
        }

        if (state.recent.isEmpty()) {
            item {
                EmptyState(
                    icon = R.drawable.ic_receipt,
                    title = "No payments yet",
                    description = "Grant notification access in Settings, then payments will appear here automatically.",
                    action = {
                        TextButton(onClick = viewModel::refresh) {
                            Text("Refresh")
                        }
                    }
                )
            }
        } else {
            items(state.recent.take(6), key = { it.id }) { event ->
                RecentPaymentCard(
                    event = event,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onOpenQr) { Text("Create payment QR") }
                TextButton(onClick = viewModel::refresh) { Text("Refresh") }
            }
        }
    }
}

@Composable
private fun CollectionCard(label: String, amount: String, date: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.extraLarge,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text(date, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text(
                    text = "₹$amount",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
