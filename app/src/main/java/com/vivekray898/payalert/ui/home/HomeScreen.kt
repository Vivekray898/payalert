package com.vivekray898.payalert.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.vivekray898.payalert.R
import com.vivekray898.payalert.data.PaymentEvent
import com.vivekray898.payalert.ui.components.EmptyState
import com.vivekray898.payalert.ui.components.SectionCard
import com.vivekray898.payalert.ui.components.SectionHeader
import com.vivekray898.payalert.ui.components.StatChip
import com.vivekray898.payalert.ui.formatAmount
import com.vivekray898.payalert.ui.formatTimestamp

/** One recent-payment row. The merchant renders exactly as stored — no case transform. */
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
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth()
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
                    .size(36.dp)
                    .background(amountColor.copy(alpha = 0.12f), MaterialTheme.shapes.large),
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
                    text = event.merchant ?: event.bankOrUpi ?: "Payment",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = formatTimestamp(event.receivedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = (if (credit) "+" else "\u2212") + formatAmount(event.amount),
                style = MaterialTheme.typography.titleMedium,
                color = amountColor
            )
        }
    }
}

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
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Text(
                text = "PayAlert",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // This-month totals. Net is the headline figure; the two components are chips.
        item {
            SectionCard(title = "This month") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatChip("In", formatAmount(state.monthIn), MaterialTheme.colorScheme.tertiary)
                    StatChip("Out", formatAmount(state.monthOut), MaterialTheme.colorScheme.secondary)
                }
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Text(
                        text = "Net",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatAmount(state.monthIn - state.monthOut),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Ranked actions: the primary destination is filled, the utility is outlined.
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onOpenTransactions,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Transactions")
                }
                OutlinedButton(
                    onClick = onOpenQr,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Payment QR")
                }
            }
        }

        item {
            SectionHeader(title = "Recent payments")
        }

        if (state.recent.isEmpty()) {
            item {
                EmptyState(
                    icon = R.drawable.ic_receipt,
                    title = "No payments yet",
                    description = "Grant monitoring, SMS and notification access in Settings → Permissions, then pay with any app or receive a bank SMS — detections show up here.",
                    action = {
                        TextButton(onClick = viewModel::refresh) {
                            Text("Refresh")
                        }
                    }
                )
            }
        } else {
            items(state.recent, key = { it.id }) { event ->
                RecentPaymentCard(event = event)
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = viewModel::refresh) {
                Text("Refresh")
            }
        }
    }
}
