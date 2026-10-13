package com.vivekray898.payalert.ui.cashbook

import android.app.DatePickerDialog
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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vivekray898.payalert.data.CashbookEntry
import com.vivekray898.payalert.export.TransactionExporter
import com.vivekray898.payalert.ui.components.ExportShare
import com.vivekray898.payalert.ui.components.ValueRow
import com.vivekray898.payalert.ui.formatAmount
import com.vivekray898.payalert.ui.formatTimestamp
import java.util.Calendar

@Composable
fun CashbookScreen(viewModel: CashbookViewModel = viewModel()) {
    val entries by viewModel.entries.collectAsState()
    val report by viewModel.report.collectAsState()
    val range by viewModel.range.collectAsState()
    val category by viewModel.category.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val context = LocalContext.current

    var editorTarget by remember { mutableStateOf<EditorTarget?>(null) }
    var showReport by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Cashbook",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Balance: ${formatAmount(report.net)}",
            style = MaterialTheme.typography.bodyMedium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("In: ${formatAmount(report.totalIn)}", color = MaterialTheme.colorScheme.primary)
            Text("Out: ${formatAmount(report.totalOut)}", color = MaterialTheme.colorScheme.error)
            Text("${report.entryCount} entries", style = MaterialTheme.typography.bodySmall)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CashbookRange.entries.forEach { preset ->
                FilterChip(
                    selected = range == preset,
                    onClick = { viewModel.setRange(preset) },
                    label = { Text(preset.label) }
                )
            }
        }

        if (categories.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = category == null,
                    onClick = { viewModel.setCategory(null) },
                    label = { Text("All categories") }
                )
                categories.forEach { value ->
                    FilterChip(
                        selected = category == value,
                        onClick = { viewModel.setCategory(value) },
                        label = { Text(value) }
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { editorTarget = EditorTarget(null) },
                modifier = Modifier.weight(1f)
            ) { Text("Add entry") }
            Button(onClick = { showReport = true }, modifier = Modifier.weight(1f)) {
                Text("Report")
            }
            TextButton(onClick = {
                viewModel.snapshot { rows ->
                    ExportShare.share(
                        context,
                        "payalert-cashbook.csv",
                        "text/csv",
                        TransactionExporter.cashbookToCsv(rows)
                    )
                }
            }) { Text("Export") }
        }

        if (entries.isEmpty()) {
            Text(
                text = "No cashbook entries in this range yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(entries, key = { it.id }) { entry ->
                    CashbookRow(
                        entry = entry,
                        onEdit = { editorTarget = EditorTarget(entry) },
                        onDelete = { viewModel.delete(entry.id) }
                    )
                }
            }
        }
    }

    editorTarget?.let { target ->
        CashbookEditorDialog(
            entry = target.entry,
            onDismiss = { editorTarget = null },
            onSave = { label, kind, amount, cat, note, occurredAt ->
                viewModel.save(target.entry?.id, label, kind, amount, cat, note, occurredAt)
                editorTarget = null
            }
        )
    }

    if (showReport) {
        AlertDialog(
            onDismissRequest = { showReport = false },
            title = { Text("Report — ${range.label}") },
            confirmButton = { TextButton(onClick = { showReport = false }) { Text("Close") } },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ValueRow("Money in", formatAmount(report.totalIn))
                    ValueRow("Money out", formatAmount(report.totalOut))
                    ValueRow("Net", formatAmount(report.net))
                    ValueRow("Entries", report.entryCount.toString())
                    if (report.byCategory.isEmpty()) {
                        Text(
                            "No entries in this range.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    report.byCategory.forEach { row ->
                        ValueRow(
                            row.label,
                            "in ${formatAmount(row.inAmount)} · out ${formatAmount(row.outAmount)}"
                        )
                    }
                }
            }
        )
    }
}

private data class EditorTarget(val entry: CashbookEntry?)

@Composable
private fun CashbookRow(
    entry: CashbookEntry,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.padding(end = 8.dp)) {
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = buildString {
                        append(formatTimestamp(entry.occurredAt))
                        entry.category?.let { append(" · $it") }
                        entry.note?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = (if (entry.kind == "credit") "+" else "-") + formatAmount(entry.amount),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (entry.kind == "credit") {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
                Row {
                    TextButton(onClick = onEdit) { Text("Edit") }
                    TextButton(onClick = onDelete) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
private fun CashbookEditorDialog(
    entry: CashbookEntry?,
    onDismiss: () -> Unit,
    onSave: (String, String, Double, String?, String?, Long) -> Unit
) {
    val context = LocalContext.current
    var label by remember { mutableStateOf(entry?.label ?: "") }
    var amount by remember { mutableStateOf(entry?.amount?.toString() ?: "") }
    var kind by remember { mutableStateOf(entry?.kind ?: "debit") }
    var category by remember { mutableStateOf(entry?.category ?: "") }
    var note by remember { mutableStateOf(entry?.note ?: "") }
    var occurredAt by remember { mutableStateOf(entry?.occurredAt ?: System.currentTimeMillis()) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entry == null) "Add entry" else "Edit entry") },
        confirmButton = {
            TextButton(onClick = {
                val parsedAmount = amount.trim().toDoubleOrNull()
                when {
                    label.isBlank() -> error = "Enter a label"
                    parsedAmount == null || parsedAmount <= 0.0 -> error = "Enter a valid amount"
                    else -> onSave(label, kind, parsedAmount, category, note, occurredAt)
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = kind == "credit",
                        onClick = { kind = "credit" },
                        label = { Text("Money in") }
                    )
                    FilterChip(
                        selected = kind == "debit",
                        onClick = { kind = "debit" },
                        label = { Text("Money out") }
                    )
                }
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category (optional)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") }
                )
                Text("Entry date: ${formatTimestamp(occurredAt)}")
                Button(
                    onClick = {
                        val calendar = Calendar.getInstance().apply { timeInMillis = occurredAt }
                        DatePickerDialog(
                            context,
                            { _, year, month, day ->
                                occurredAt = Calendar.getInstance().apply {
                                    timeInMillis = occurredAt
                                    set(Calendar.YEAR, year)
                                    set(Calendar.MONTH, month)
                                    set(Calendar.DAY_OF_MONTH, day)
                                }.timeInMillis
                            },
                            calendar.get(Calendar.YEAR),
                            calendar.get(Calendar.MONTH),
                            calendar.get(Calendar.DAY_OF_MONTH)
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Change date") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    )
}
