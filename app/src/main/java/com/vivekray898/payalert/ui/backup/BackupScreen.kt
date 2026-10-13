package com.vivekray898.payalert.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vivekray898.payalert.backup.BackupManager
import com.vivekray898.payalert.ui.components.OverlayHeader
import com.vivekray898.payalert.ui.components.SectionCard
import kotlinx.coroutines.launch

/**
 * Backup and restore with the system file picker. Export is a full local snapshot
 * (payments, cashbook and reminders) written to a file the user chooses.
 */
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { BackupManager(context) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            status = try {
                val count = manager.export(uri)
                "Exported $count records."
            } catch (error: Exception) {
                "Export failed: ${error.message}"
            }
            busy = false
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            status = try {
                val result = manager.import(uri)
                buildString {
                    append("Imported ${result.total} records")
                    append(" (${result.events} payments, ${result.cashbook} cashbook, ")
                    append("${result.upcoming} reminders)")
                    if (result.skipped > 0) {
                        append(". ${result.skipped} were already present and left untouched.")
                    }
                }
            } catch (error: Exception) {
                "Import failed: ${error.message}"
            }
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OverlayHeader("Backup & restore", onBack)

        SectionCard(title = "Export") {
            Text(
                "Writes a JSON file containing your detected payments, cashbook entries and " +
                    "reminders. The file is created on this device through the system picker — " +
                    "nothing is uploaded.",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = { exportLauncher.launch("payalert-backup.json") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Export backup") }
        }

        SectionCard(title = "Import") {
            Text(
                "Reads a PayAlert backup file. Existing records with the same identity are left " +
                    "untouched; a file from a newer version is rejected rather than partly applied.",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Import backup") }
        }

        if (busy) {
            Text("Working…", style = MaterialTheme.typography.bodyMedium)
        }
        status?.let {
            SectionCard(title = "Result") { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }

        SectionCard(title = "What a backup contains") {
            Text(
                "Detected payments (with their raw message), cashbook entries and upcoming " +
                    "payments. Settings and speech preferences are not included, because they " +
                    "are device-specific.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}
