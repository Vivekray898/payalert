package com.vivekray898.payalert.ui.passbook

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vivekray898.payalert.R
import com.vivekray898.payalert.data.UpcomingPayment
import com.vivekray898.payalert.ui.components.EmptyState
import com.vivekray898.payalert.ui.components.StatChip
import com.vivekray898.payalert.ui.formatAmount
import com.vivekray898.payalert.ui.formatTimestamp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassbookScreen(viewModel: PassbookViewModel = viewModel()) {
    val entries by viewModel.entries.collectAsState()
    var editorTarget by remember { mutableStateOf<UpcomingTarget?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    val pending = entries.filter { !it.isPaid }
    val dueToPay = pending.filter { it.kind == "debit" }.sumOf { it.amount }
    val dueToReceive = pending.filter { it.kind == "credit" }.sumOf { it.amount }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Upcoming payments") },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_more_vert),
                                contentDescription = "More actions"
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            // Maintenance action, so it lives in the overflow rather than
                            // competing with "Add reminder" on the main surface.
                            DropdownMenuItem(
                                text = { Text("Re-arm alarms") },
                                onClick = {
                                    menuOpen = false
                                    viewModel.rescheduleAll()
                                },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_upcoming),
                                        contentDescription = null
                                    )
                                }
                            )
                        }
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatChip("To pay", formatAmount(dueToPay), MaterialTheme.colorScheme.secondary)
                StatChip("To receive", formatAmount(dueToReceive), MaterialTheme.colorScheme.tertiary)
            }

            Button(onClick = { editorTarget = UpcomingTarget(null) }) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_add),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text("  Add reminder")
            }

            if (entries.isEmpty()) {
                EmptyState(
                    icon = R.drawable.ic_upcoming,
                    title = "Nothing scheduled",
                    description = "Add a bill or an expected payment and PayAlert will remind you before it is due.",
                    action = {
                        Button(onClick = { editorTarget = UpcomingTarget(null) }) {
                            Text("+ Add reminder")
                        }
                    }
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

/** `16 Oct 2026` — the due date, without the time-of-day noise of a full timestamp. */
private fun dueDate(millis: Long): String =
    SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(millis))

@Composable
private fun UpcomingRow(
    entry: UpcomingPayment,
    onEdit: () -> Unit,
    onMarkPaid: () -> Unit,
    onDelete: () -> Unit
) {
    val credit = entry.kind == "credit"
    val accent = if (credit) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                        painter = painterResource(id = R.drawable.ic_upcoming),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = buildString {
                            append("Due ${dueDate(entry.dueAt)}")
                            entry.category?.let { append(" · $it") }
                            if (entry.repeatMonthly) append(" · monthly")
                            append(if (entry.isPaid) " · paid" else " · pending")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = (if (credit) "+" else "\u2212") + formatAmount(entry.amount),
                    style = MaterialTheme.typography.titleMedium,
                    color = accent
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!entry.isPaid) {
                    OutlinedButton(onClick = onMarkPaid) {
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
                    FilterChip(
                        selected = kind == "debit",
                        onClick = { kind = "debit" },
                        label = { Text("To pay") }
                    )
                    FilterChip(
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    )
}
