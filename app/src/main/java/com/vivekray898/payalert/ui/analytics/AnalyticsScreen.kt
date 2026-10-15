package com.vivekray898.payalert.ui.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vivekray898.payalert.R
import com.vivekray898.payalert.export.TransactionExporter
import com.vivekray898.payalert.ui.components.ExportShare
import com.vivekray898.payalert.ui.components.OverlayHeader
import com.vivekray898.payalert.ui.components.SectionCard
import com.vivekray898.payalert.ui.components.StatChip
import com.vivekray898.payalert.ui.components.ValueRow
import com.vivekray898.payalert.ui.formatAmount
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AnalyticsScreen(onBack: () -> Unit, viewModel: AnalyticsViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val summary = state.summary

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OverlayHeader("Analytics", onBack)

        // Range chips scroll instead of wrapping, so no label is ever split mid-word.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RangePreset.entries.forEach { preset ->
                FilterChip(
                    selected = state.preset == preset,
                    onClick = { viewModel.select(preset) },
                    label = { Text(preset.label) }
                )
            }
        }

        SectionCard(title = state.preset.label) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatChip(
                    "In",
                    formatAmount(summary?.totalIn ?: 0.0),
                    MaterialTheme.colorScheme.tertiary
                )
                StatChip(
                    "Out",
                    formatAmount(summary?.totalOut ?: 0.0),
                    MaterialTheme.colorScheme.secondary
                )
            }
            ValueRow("Net", formatAmount(summary?.net ?: 0.0))
            ValueRow("Payments detected", (summary?.transactionCount ?: 0).toString())
        }

        if (summary == null || summary.transactionCount == 0) {
            SectionCard(title = "Nothing to chart yet") {
                Text(
                    "No payments in this range. Choose a wider range, or check that " +
                        "detection is active on the Home tab.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            SectionCard(title = "Spending by category") {
                if (summary.byCategory.isEmpty()) {
                    Text(
                        "No outgoing payments in this range.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val largest = summary.byCategory.maxOfOrNull { it.amount } ?: 1.0
                summary.byCategory.take(8).forEach { total ->
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(total.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                formatAmount(total.amount),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth((total.amount / largest).toFloat().coerceIn(0.02f, 1f))
                                .height(6.dp)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }

            SectionCard(title = "Top payees") {
                if (summary.topMerchants.isEmpty()) {
                    Text(
                        "No outgoing payments yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    summary.topMerchants.forEach { merchant ->
                        ValueRow(merchant.label, formatAmount(merchant.amount))
                    }
                }
            }

            SectionCard(title = if (summary.byDay.size <= 1) "Today" else "Daily movement") {
                val formatter = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                summary.byDay.take(14).forEach { day ->
                    ValueRow(
                        formatter.format(Date(day.dayStart)),
                        "in ${formatAmount(day.inAmount)} · out ${formatAmount(day.outAmount)}"
                    )
                }
            }
        }

        SectionCard(title = "Export") {
            Text(
                "The export contains the summary above, so the numbers can be re-checked " +
                    "outside the app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                FilledTonalButton(
                    onClick = {
                        val csv = summary?.let {
                            TransactionExporter.summaryToCsv(it, state.preset.label)
                        } ?: "range,${state.preset.label}\n"
                        ExportShare.share(context, "payalert-summary.csv", "text/csv", csv)
                    }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_export),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        "  Share summary (CSV)",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}
