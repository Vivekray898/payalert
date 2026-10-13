package com.vivekray898.payalert.ui.passbook

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vivekray898.payalert.data.UpcomingPayment
import com.vivekray898.payalert.ui.formatAmount
import com.vivekray898.payalert.ui.formatTimestamp
import java.util.Calendar

@Composable
fun PassbookScreen(viewModel: PassbookViewModel = viewModel()) {
    val entries by viewModel.entries.collectAsState()
    var editorTarget by remember { mutableStateOf<UpcomingTarget?>(null) }

    val pending = entries.filter { !it.isPaid }
    val dueToPay = pending.filter { it.kind == "debit" }.sumOf { it.amount }
    val dueToReceive = pending.filter { it.kind == "credit" }.sumOf { it.amount }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Upcoming payments",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "To pay ${formatAmount(dueToPay)} · To receive ${formatAmount(dueToReceive)}",
            style = MaterialTheme.typography.bodyMedium
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { editorTarget = UpcomingTarget(null) },
                modifier = Modifier.weight(1f)
            ) { Text("Add reminder") }
            Button(
                onClick = { viewModel.rescheduleAll() },
                modifier = Modifier.weight(1f)
            ) { Text("Re-arm alarms") }
        }

        if (entries.isEmpty()) {
            Text(
                text = "Nothing scheduled. Add a bill so PayAlert reminds you before it is due.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(entries, key = { it.id }) { entry ->
                    UpcomingRow(
                        entry = entry,
                        onEdit = { editorTarget = UpcomingTarget(entry) },
                        onMarkPaid = { viewModel.markPaid(entry) },
                        onDelete = { viewModel.delete(entry.id) }
                    )
                }
            }
        }
    }

    editorTarget?.let { target ->
        UpcomingEditorDialog(
            entry = target.entry,
            onDismiss = { editorTarget = null },
            onSave = { label, amount, kind, category, note, dueAt, repeat ->
                viewModel.save(target.entry?.id, label, amount, kind, category, note, dueAt, repeat)
                editorTarget = null
            }
        )
    }
}

private data class UpcomingTarget(val entry: UpcomingPayment?)

@Composable
private fun UpcomingRow(
    entry: UpcomingPayment,
    onEdit: () -> Unit,
    onMarkPaid: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = buildString {
                            append(formatTimestamp(entry.dueAt))
                            entry.category?.let { append(" · $it") }
                            if (entry.repeatMonthly) append(" · monthly")
                            append(if (entry.isPaid) " · paid" else " · pending")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Text(
                    text = formatAmount(entry.amount),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (entry.kind == "credit") {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!entry.isPaid) {
                    TextButton(onClick = onMarkPaid) {
                        Text(if (entry.repeatMonthly) "Mark paid & repeat" else "Mark paid")
                    }
                }
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun UpcomingEditorDialog(
    entry: UpcomingPayment?,
    onDismiss: () -> Unit,
    onSave: (String, Double, String, String?, String?, Long, Boolean) -> Unit
) {
    val context = LocalContext.current
    var label by remember { mutableStateOf(entry?.label ?: "") }
    var amount by remember { mutableStateOf(entry?.amount?.toString() ?: "") }
    var kind by remember { mutableStateOf(entry?.kind ?: "debit") }
    var category by remember { mutableStateOf(entry?.category ?: "") }
    var note by remember { mutableStateOf(entry?.note ?: "") }
    var dueAt by remember { mutableStateOf(entry?.dueAt ?: PassbookViewModel.defaultDueAt()) }
    var repeatMonthly by remember { mutableStateOf(entry?.repeatMonthly ?: false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entry == null) "Add reminder" else "Edit reminder") },
        confirmButton = {
            TextButton(onClick = {
                val parsed = amount.trim().toDoubleOrNull()
                when {
                    label.isBlank() -> error = "Enter a label"
                    parsed == null || parsed <= 0.0 -> error = "Enter a valid amount"
                    else -> onSave(label, parsed, kind, category, note, dueAt, repeatMonthly)
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("What is it for?") },
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
                    androidx.compose.material3.FilterChip(
                        selected = kind == "debit",
                        onClick = { kind = "debit" },
                        label = { Text("To pay") }
                    )
                    androidx.compose.material3.FilterChip(
                        selected = kind == "credit",
                        onClick = { kind = "credit" },
                        label = { Text("To receive") }
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
                Text("Due: ${formatTimestamp(dueAt)}")
                Button(
                    onClick = {
                        val calendar = Calendar.getInstance().apply { timeInMillis = dueAt }
                        DatePickerDialog(
                            context,
                            { _, year, month, day ->
                                dueAt = Calendar.getInstance().apply {
                                    timeInMillis = dueAt
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
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.Switch(
                        checked = repeatMonthly,
                        onCheckedChange = { repeatMonthly = it }
                    )
                    Text(
                        text = " Repeat every month",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    text = "Reminders arrive as a notification at the due time and re-arm " +
                        "after a reboot.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    )
}
