package com.vivekray898.payalert.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vivekray898.payalert.AppGraph
import com.vivekray898.payalert.services.PaymentMonitorService
import com.vivekray898.payalert.settings.AnnouncementFormatter
import com.vivekray898.payalert.settings.AppSettings
import com.vivekray898.payalert.settings.SoundMode
import com.vivekray898.payalert.R
import com.vivekray898.payalert.ui.PermissionStatus
import com.vivekray898.payalert.ui.components.SectionCard
import com.vivekray898.payalert.ui.components.SliderRow
import com.vivekray898.payalert.ui.components.StatusOkColor
import com.vivekray898.payalert.ui.components.StatusWarnColor
import com.vivekray898.payalert.ui.components.SwitchRow
import com.vivekray898.payalert.ui.components.ValueRow

@Composable
fun SettingsScreen(
    onOpenDiagnostics: () -> Unit,
    onOpenFaq: () -> Unit,
    onOpenTerms: () -> Unit,
    onOpenBackup: () -> Unit
) {
    val context = LocalContext.current
    val graph = remember { AppGraph.get(context) }
    val settings by graph.settings.state.collectAsState()

    var showLanguageDialog by remember { mutableStateOf(false) }
    var creditTemplate by remember(settings.creditTemplate) { mutableStateOf(settings.creditTemplate) }
    var debitTemplate by remember(settings.debitTemplate) { mutableStateOf(settings.debitTemplate) }
    // Seeded once from the stored value. Re-reading it on every settings change would
    // rewrite the field mid-keystroke (typing "1500" would become "1.0500"), so the raw
    // text is kept locally and only the parsed value is pushed to settings.
    var minimumAmountText by remember { mutableStateOf(amountFieldText(settings.minimumAmount)) }

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result is re-read from the system when the screen resumes */ }

    fun openSystemSettings(action: String) {
        runCatching { context.startActivity(Intent(action)) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            // The Scaffold's bottom bar occupies the bottom ~96 dp of the content
            // area. Without this the last interactive row (Reset all settings) sits
            // underneath the bar and is not tappable.
            .padding(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )

        SectionCard(title = "Detection") {
            SwitchRow(
                label = "Monitor payments",
                description = "Background detector; keeps the speech engine ready",
                checked = settings.monitoringEnabled,
                onCheckedChange = { enabled ->
                    graph.settings.setMonitoringEnabled(enabled)
                    if (enabled) PaymentMonitorService.start(context) else PaymentMonitorService.stop(context)
                }
            )
            SwitchRow(
                label = "Read SMS alerts",
                description = "Bank transaction SMS",
                checked = settings.detectSms,
                onCheckedChange = graph.settings::setDetectSms
            )
            SwitchRow(
                label = "Read payment notifications",
                description = "UPI and banking app notifications",
                checked = settings.detectNotifications,
                onCheckedChange = graph.settings::setDetectNotifications
            )
            // A numeric field must never rely on a placeholder to say what it is: the
            // placeholder disappears the moment a digit is typed. The label sits above the
            // field and the hint below it, so both stay on screen while the value is shown.
            Text(
                text = "Ignore amounts below (\u20B9)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
            OutlinedTextField(
                value = minimumAmountText,
                onValueChange = {
                    minimumAmountText = it
                    graph.settings.setMinimumAmount(it.toDoubleOrNull() ?: 0.0)
                },
                supportingText = { Text("Payments under this amount are not announced") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            SliderRow(
                label = "Duplicate window",
                value = settings.dedupWindowMinutes.toFloat(),
                valueRange = 1f..30f,
                valueText = "${settings.dedupWindowMinutes} min",
                rangeStartLabel = "1 min",
                rangeEndLabel = "30 min",
                onValueChange = { graph.settings.setDedupWindowMinutes(it.toInt().coerceAtLeast(1)) }
            )
            Text(
                text = "A payment seen as both an SMS and a notification inside this window is " +
                    "announced once.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }

        SectionCard(title = "Announce which payments") {
            SwitchRow(
                label = "Money received",
                checked = settings.announceCredits,
                onCheckedChange = graph.settings::setAnnounceCredits
            )
            SwitchRow(
                label = "Money sent",
                checked = settings.announceDebits,
                onCheckedChange = graph.settings::setAnnounceDebits
            )
        }

        SectionCard(title = "Sound") {
            // A scroll row keeps the mode chips on one line at any font size.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SoundMode.labels.forEach { (mode, label) ->
                    FilterChip(
                        selected = settings.soundMode == mode,
                        onClick = { graph.settings.setSoundMode(mode) },
                        label = { Text(label) }
                    )
                }
            }
            SwitchRow(
                label = "Vibrate with the announcement",
                checked = settings.vibrate,
                onCheckedChange = graph.settings::setVibrate
            )
        }

        SectionCard(title = "Voice") {
            ValueRow("Language", languageLabel(settings.languageTag))
            OutlinedButton(
                onClick = { showLanguageDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Change language") }
            SliderRow(
                label = "Speed",
                value = settings.speechRate,
                valueRange = 0.5f..2.0f,
                valueText = String.format("%.2f", settings.speechRate),
                onValueChange = graph.settings::setSpeechRate
            )
            SliderRow(
                label = "Pitch",
                value = settings.pitch,
                valueRange = 0.5f..2.0f,
                valueText = String.format("%.2f", settings.pitch),
                onValueChange = graph.settings::setPitch
            )
            SliderRow(
                label = "Volume",
                value = settings.volume,
                valueRange = 0f..1f,
                valueText = String.format("%.2f", settings.volume),
                onValueChange = graph.settings::setVolume
            )
            OutlinedButton(
                onClick = { graph.tts.speak("This is how payments will sound.") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_volume_up),
                    contentDescription = null
                )
                Text("  Test the voice")
            }
        }

        SectionCard(title = "Announcement format") {
            Text(
                text = "Placeholders: {amount} {merchant} {bank} {kind}. Leave a field blank to " +
                    "use the built-in wording for that direction.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            OutlinedTextField(
                value = creditTemplate,
                onValueChange = {
                    creditTemplate = it
                    graph.settings.setCreditTemplate(it)
                },
                label = { Text("Money received") },
                placeholder = { Text(AnnouncementFormatter.DEFAULT_CREDIT_TEMPLATE) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = debitTemplate,
                onValueChange = {
                    debitTemplate = it
                    graph.settings.setDebitTemplate(it)
                },
                label = { Text("Money sent") },
                placeholder = { Text(AnnouncementFormatter.DEFAULT_DEBIT_TEMPLATE) },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Preview: " + AnnouncementFormatter.format(
                    template = settings.templateFor("credit"),
                    amount = 1234.5,
                    merchant = "Sample Merchant",
                    bankOrUpi = "Sample Bank",
                    kind = "credit"
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Button(
                onClick = { graph.tts.announce("Payment received. 1,234.50 from Sample Merchant.") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Test announcement") }
            TextButton(onClick = {
                graph.settings.setCreditTemplate("")
                graph.settings.setDebitTemplate("")
                graph.settings.setAnnouncementTemplate("")
            }) { Text("Reset formats") }
        }

        SectionCard(title = "Permissions") {
            // Green when the capability is in place, orange when the user still has to act.
            ValueRow(
                "SMS",
                if (PermissionStatus.hasReceiveSms(context)) "Granted" else "Not granted",
                if (PermissionStatus.hasReceiveSms(context)) StatusOkColor else StatusWarnColor
            )
            ValueRow(
                "Notification access",
                if (PermissionStatus.hasNotificationAccess(context)) "Granted" else "Not granted",
                if (PermissionStatus.hasNotificationAccess(context)) StatusOkColor else StatusWarnColor
            )
            ValueRow(
                "Notifications",
                if (PermissionStatus.hasPostNotifications(context)) "Granted" else "Not granted",
                if (PermissionStatus.hasPostNotifications(context)) StatusOkColor else StatusWarnColor
            )
            ValueRow(
                "Battery optimisation",
                if (PermissionStatus.isIgnoringBatteryOptimizations(context)) "Exempt" else "Restricted",
                if (PermissionStatus.isIgnoringBatteryOptimizations(context)) StatusOkColor else StatusWarnColor
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = {
                    val permissions = mutableListOf(Manifest.permission.RECEIVE_SMS)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    smsPermissionLauncher.launch(permissions.toTypedArray())
                }) { Text("Grant SMS") }
                OutlinedButton(
                    onClick = { openSystemSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
                ) { Text("Notification access") }
                OutlinedButton(onClick = {
                    openSystemSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                }) { Text("Battery") }
            }
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Ask to be exempt from battery optimisation") }
        }

        SectionCard(title = "Diagnostics & data") {
            Button(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
                Text("Open diagnostics and test a message")
            }
            OutlinedButton(onClick = onOpenBackup, modifier = Modifier.fillMaxWidth()) {
                Text("Backup & restore")
            }
            ValueRow(
                "Rule set",
                if (graph.parserConfigLoadFailed) {
                    "asset failed to load"
                } else {
                    "${graph.parserConfig.banks.size} banks · " +
                        "${graph.parserConfig.wallets.size} wallets · " +
                        "${graph.parserConfig.banks.sumOf { it.senders.size }} sender ids"
                }
            )
            ValueRow("App version", appVersion(context))
        }

        SectionCard(title = "Help") {
            Text(
                text = "Answers to the common questions, and how PayAlert handles your data.",
                style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenFaq) { Text("Help & FAQ") }
                OutlinedButton(onClick = onOpenTerms) { Text("Privacy & terms") }
            }
        }

        SectionCard(title = "Reset") {
            Text(
                text = "Restore every setting to its default value. Your detected payments, " +
                    "cashbook and reminders are not touched.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { graph.settings.resetToDefaults() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Reset all settings") }
        }
    }

    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) { Text("Close") }
            },
            title = { Text("Announcement language") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    AppSettings.SUPPORTED_LANGUAGES.forEach { (tag, label) ->
                        Text(
                            text = if (tag == settings.languageTag) "$label  ✓" else label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    graph.settings.setLanguageTag(tag)
                                    graph.tts.speak("Language changed.")
                                    showLanguageDialog = false
                                }
                                .padding(vertical = 10.dp)
                        )
                    }
                }
            }
        )
    }
}

/** Renders a stored threshold as editable text (1000 rather than 1000.0). */
private fun amountFieldText(value: Double): String = when {
    value <= 0.0 -> ""
    value % 1.0 == 0.0 -> value.toLong().toString()
    else -> value.toString()
}

private fun languageLabel(tag: String): String =
    AppSettings.SUPPORTED_LANGUAGES.firstOrNull { it.first == tag }?.second ?: tag

private fun appVersion(context: android.content.Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
} catch (error: Exception) {
    "unknown"
}
