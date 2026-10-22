package com.vivekray898.payalert.ui.team

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vivekray898.payalert.role.LocalDeviceRole
import com.vivekray898.payalert.team.PairingCode
import com.vivekray898.payalert.team.TeamClientException
import com.vivekray898.payalert.team.TeamConnection
import com.vivekray898.payalert.team.TeamController
import com.vivekray898.payalert.team.TeamInvite
import com.vivekray898.payalert.ui.PairingViewModel
import com.vivekray898.payalert.ui.formatTimestamp
import com.vivekray898.payalert.ui.components.OverlayHeader
import com.vivekray898.payalert.ui.components.RoleIndicator
import com.vivekray898.payalert.ui.components.SectionCard
import com.vivekray898.payalert.ui.components.SegmentedModeSelector
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable
fun TeamScreen(
    onBack: () -> Unit,
    vm: TeamViewModel = viewModel(),
    pairing: PairingViewModel = viewModel()
) {
    TeamContent(onBack, vm.controller, pairing)
}

@Composable
internal fun TeamContent(
    onBack: () -> Unit,
    controller: TeamController,
    pairing: PairingViewModel? = null
) {
    val context = LocalContext.current
    val state by controller.state.collectAsState()
    val deviceRole = pairing?.role?.collectAsState()?.value
    val relayState = pairing?.relay?.collectAsState()?.value
    // Forwarding counters come from the local queues, so they are read fresh whenever this opens.
    LaunchedEffect(pairing) { pairing?.refreshStatus() }
    val authError by controller.authError.collectAsState()
    var role by remember { mutableStateOf("owner") }
    // Keep an in-progress employee code through rotation, without persisting it to preferences.
    var code by rememberSaveable { mutableStateOf("") }
    var codeSubmitted by rememberSaveable { mutableStateOf(false) }
    var remove by remember { mutableStateOf<TeamConnection?>(null) }
    var rename by remember { mutableStateOf<String?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val team = state.team
    val ready = !state.busy && !state.needsRefresh && team != null

    LaunchedEffect(controller) { controller.refresh() }

    LaunchedEffect(state.account?.userId, team?.owner?.pairingId) {
        code = ""
        codeSubmitted = false
        remove = null
        rename = null
        confirmSignOut = false
    }

    Column(
        modifier = Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        OverlayHeader("Team & devices", onBack)
        Column(
            modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Connect your shop's devices", style = MaterialTheme.typography.headlineSmall)
            Text(
                "An owner creates a code. An employee enters it on their own phone to connect the two devices.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "Updating team"
                })
                Text("Updating team…", style = MaterialTheme.typography.bodyMedium)
            }
            val error = state.error ?: authError
            if (error != null) {
                SectionCard("Action needed") {
                    Text(error, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    if (state.account != null && team == null) {
                        OutlinedButton(onClick = controller::refresh, enabled = !state.busy) {
                            Text("Refresh devices")
                        }
                    }
                }
            }
            state.message?.let {
                Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (!controller.configured) {
                SectionCard("Team setup required") {
                    Text("This build isn't connected to Supabase. All your on-device features still work.")
                    Text("To enable pairing, configure SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY, apply the team migration, and rebuild. See README.md for the exact steps.",
                        style = MaterialTheme.typography.bodyMedium)
                }
            } else if (state.account == null) {
                SectionCard("Sign in to connect devices") {
                    Text("Use your Google account on each device. Owners and employees can use different accounts.")
                    Button(onClick = {
                        controller.signIn { url ->
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            } catch (_: Exception) {
                                throw TeamClientException("No browser could be opened. Install or enable a browser and try again.")
                            }
                        }
                    }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Continue with Google")
                    }
                }
            } else {
                SectionCard("Google account") {
                    Text(state.account?.displayName.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { confirmSignOut = true }, enabled = !state.busy) {
                        Text("Sign out")
                    }
                }
                if (team != null) {
                    SectionCard("This device") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(team.device.name, style = MaterialTheme.typography.titleMedium)
                            deviceRole?.let { RoleIndicator(it.badgeValue) }
                        }
                        Text(when {
                            team.owner != null -> "Employee device"
                            team.employees.isNotEmpty() -> "Owner device"
                            else -> "Not paired yet"
                        })
                        OutlinedButton(onClick = { rename = team.device.name }, enabled = ready) {
                            Text("Rename device")
                        }
                        OutlinedButton(onClick = controller::refresh, enabled = !state.busy) {
                            Text("Refresh devices")
                        }
                        if (state.needsRefresh) {
                            Text("The last action could not be confirmed. Refresh before making more changes.",
                                color = MaterialTheme.colorScheme.error)
                        }
                        deviceRole?.let { active ->
                            Text(
                                if (active.role == LocalDeviceRole.EMPLOYEE) {
                                    "This phone does not announce its own SMS or notifications. It " +
                                        "announces what the owner forwards."
                                } else {
                                    "This phone announces its own payments. Received payments are " +
                                        "forwarded to paired employee devices."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    relayState?.let { forwarding ->
                        if (forwarding.paired) {
                            SectionCard("Forwarding") {
                                if (forwarding.role == LocalDeviceRole.EMPLOYEE) {
                                    Text("Received from owner: ${forwarding.receivedCount} kept for 24 hours.")
                                    if (forwarding.needsRepair) {
                                        Text(
                                            "Some forwarded payments could not be read on this " +
                                                "device. Leave the team and pair again to restore them.",
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                } else {
                                    Text("Waiting to send: ${forwarding.pendingDeliveries}")
                                    Text("Retrying: ${forwarding.failedDeliveries}")
                                    Text(
                                        forwarding.lastForwardedAt?.let { "Last forwarded ${formatTimestamp(it)}" }
                                            ?: "Nothing forwarded yet."
                                    )
                                }
                                forwarding.lastError?.let {
                                    Text("Relay: $it", color = MaterialTheme.colorScheme.error)
                                }
                                Text(
                                    "Only money received is forwarded, and only as an encrypted " +
                                        "envelope. The server relays it and keeps no copy once this " +
                                        "device confirms delivery.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (team.owner != null) {
                        SectionCard("Connected owner") {
                            Text(team.owner.deviceName, style = MaterialTheme.typography.titleMedium)
                            Text("This device is connected as an employee. Leave this team before connecting to another owner.")
                            OutlinedButton(onClick = { remove = team.owner }, enabled = ready) {
                                Text("Leave team")
                            }
                        }
                    } else {
                        if (team.employees.isEmpty()) {
                            SegmentedModeSelector(role, listOf("owner" to "I'm the owner", "employee" to "I'm an employee"),
                                onSelect = { role = it; controller.clearError() }, modifier = Modifier.fillMaxWidth())
                        }
                        if (role == "owner" || team.employees.isNotEmpty()) {
                            SectionCard("Invite an employee") {
                                Text("On the employee's phone, open Settings → Team & devices, sign in, and choose “I'm an employee”.")
                                state.invite?.let { invite ->
                                    Invitation(invite, ready, controller::cancelInvite)
                                }
                                Button(onClick = controller::createInvite, enabled = ready,
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text(if (state.invite == null) "Create pairing code" else "Replace pairing code")
                                }
                                Text("Each code works once and expires in 10 minutes. Replacing it cancels the previous code.",
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        } else {
                            SectionCard("Join an owner's team") {
                                if (state.invite != null) {
                                    Text("Cancel your owner code before joining another device as an employee.")
                                    OutlinedButton(onClick = controller::cancelInvite, enabled = ready) {
                                        Text("Cancel pairing code")
                                    }
                                } else {
                                    Text("Ask the owner for a fresh pairing code. You can paste the full code below.")
                                    OutlinedTextField(value = code, onValueChange = { code = it; controller.clearError() },
                                        label = { Text("Pairing code") }, singleLine = true, enabled = !state.busy,
                                        isError = codeSubmitted && !PairingCode.isValid(code),
                                        supportingText = { Text(if (codeSubmitted && !PairingCode.isValid(code))
                                            "Enter all 12 characters, using A–F and 0–9." else "12 characters, using A–F and 0–9") },
                                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters,
                                            keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth())
                                    Button(onClick = { codeSubmitted = true; controller.join(code) }, enabled = ready && code.isNotBlank(),
                                        modifier = Modifier.fillMaxWidth()) { Text("Pair this device") }
                                }
                            }
                        }
                        SectionCard("Employee devices (${team.employees.size})") {
                            if (team.employees.isEmpty()) {
                                Text("No employee devices connected.")
                                Text("After an employee joins, tap Refresh devices to see their phone here.")
                            }
                            team.employees.forEach { employee ->
                                Text(employee.deviceName, style = MaterialTheme.typography.titleMedium)
                                OutlinedButton(onClick = { remove = employee }, enabled = ready) {
                                    Text("Remove ${employee.deviceName}")
                                }
                            }
                        }
                    }
                }
            }
            SectionCard("Your data stays local") {
                Text("Pairing shares device names and a connection record, not your payment history, SMS, cashbook, or reminders. A forwarded payment is encrypted on the owner's phone and can only be read on the paired employee phone; the server stores an envelope it cannot open.")
                Text("Device lists refresh when you open this screen or tap Refresh devices. Signing out doesn't remove a connection; use Remove device or Leave team first. Leaving or removing clears queued payments, forgets the pairing key, and deletes announcements received from that owner.",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    remove?.let { connection ->
        val leaving = team?.owner?.pairingId == connection.pairingId
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text(if (leaving) "Leave this team?" else "Remove employee device?") },
            text = { Text("Disconnect ${connection.deviceName}? No local payment data will be deleted. A new pairing code will be needed to reconnect.") },
            confirmButton = {
                TextButton(onClick = { controller.remove(connection); remove = null }, enabled = ready) {
                    Text(if (leaving) "Leave team" else "Remove device")
                }
            },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("Cancel") } }
        )
    }
    rename?.let { name ->
        val validName = name.trim().length in 1..100 && name.none { it.isISOControl() }
        AlertDialog(
            onDismissRequest = { rename = null },
            title = { Text("Rename this device") },
            text = {
                OutlinedTextField(value = name, onValueChange = { rename = it },
                    label = { Text("Device name") }, singleLine = true,
                    isError = !validName, supportingText = { Text("1–100 characters; visible to connected devices") })
            },
            confirmButton = {
                TextButton(onClick = { controller.renameDevice(name); rename = null }, enabled = ready && validName) {
                    Text("Save name")
                }
            },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("Cancel") } }
        )
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out of team management?") },
            text = { Text("Your device connections remain. To disconnect, cancel this dialog and use Remove device or Leave team first. All on-device features will keep working.") },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; controller.signOut() }, enabled = !state.busy) {
                    Text("Sign out")
                }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun Invitation(invite: TeamInvite, enabled: Boolean, onCancel: () -> Unit) {
    val context = LocalContext.current
    var now by remember(invite) { mutableStateOf(System.currentTimeMillis()) }
    var copied by remember(invite) { mutableStateOf(false) }
    val expires = invite.expiresAtMillis
    LaunchedEffect(invite) {
        while (true) {
            now = System.currentTimeMillis()
            if (now >= expires) break
            delay(1_000)
        }
    }
    val expired = now >= expires
    if (!expired) {
        Text(invite.code, style = MaterialTheme.typography.headlineMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.semantics { contentDescription = "Pairing code: ${invite.code.toList().joinToString(" ")}" })
        Text("Expires at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(expires))}. One use only.")
        OutlinedButton(onClick = {
            // Re-check the clock at the action boundary, not just at the last recomposition.
            if (System.currentTimeMillis() < expires) {
                val clip = android.content.ClipData.newPlainText("PayAlert pairing code", invite.code)
                clip.description.extras = android.os.PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
                (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(clip)
                copied = true
            }
        }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(if (copied) "Code copied" else "Copy pairing code") }
    } else {
        Text("This pairing code has expired. Create a new code to connect another device.",
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    TextButton(onClick = onCancel, enabled = enabled) { Text("Cancel pairing code") }
}
