package com.vivekray898.payalert.ui.team

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vivekray898.payalert.role.LocalDeviceRole
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
    LaunchedEffect(pairing) { pairing?.refreshStatus() }
    val authError by controller.authError.collectAsState()
    var role by remember { mutableStateOf("owner") }
    var code by rememberSaveable { mutableStateOf("") }
    var codeSubmitted by rememberSaveable { mutableStateOf(false) }
    var submittedCode by rememberSaveable { mutableStateOf("") }
    var remove by remember { mutableStateOf<TeamConnection?>(null) }
    var rename by remember { mutableStateOf<String?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val team = state.team
    val ready = !state.busy && !state.needsRefresh && team != null

    LaunchedEffect(controller) { controller.refresh() }

    LaunchedEffect(state.account?.userId, team?.owner?.pairingId) {
        code = ""
        codeSubmitted = false
        submittedCode = ""
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
            val pairingError = state.error?.takeIf {
                role == "employee" && codeSubmitted && team?.owner == null && state.invite == null
            }
            val error = if (pairingError == null) state.error ?: authError else authError
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
                                    Text(
                                        when (it) {
                                            "forwarding_failed" ->
                                                "Some payments are still being forwarded. PayAlert will retry automatically."
                                            "relay_unreachable" ->
                                                "The relay is temporarily unavailable. PayAlert will retry automatically."
                                            "key_not_bound" ->
                                                "A paired device needs to open PayAlert once to refresh its secure connection."
                                            else -> "Relay: $it"
                                        },
                                        color = MaterialTheme.colorScheme.error
                                    )
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
                                    PairingCodeEntry(
                                        code = code,
                                        codeSubmitted = codeSubmitted,
                                        errorMessage = pairingError,
                                        busy = state.busy,
                                        onCodeChange = { rawValue ->
                                            val next = rawValue.filter { it in '0'..'9' }.take(6)
                                            if (next != code) {
                                                code = next
                                                controller.clearError()
                                                if (next.length < 6) {
                                                    codeSubmitted = false
                                                    submittedCode = ""
                                                } else if (next != submittedCode) {
                                                    submittedCode = next
                                                    codeSubmitted = true
                                                    controller.join(next)
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
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

@Composable
private fun PairingCodeEntry(
    code: String,
    codeSubmitted: Boolean,
    errorMessage: String?,
    busy: Boolean,
    onCodeChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }

    LaunchedEffect(codeSubmitted) {
        if (codeSubmitted) keyboardController?.hide()
    }

    Column(modifier = modifier) {
        Text("Ask the owner for a fresh pairing code. Tap the bubbles to enter it.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        BasicTextField(
            value = code,
            onValueChange = onCodeChange,
            enabled = !busy,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
            textStyle = TextStyle(color = Color.Transparent),
            cursorBrush = SolidColor(Color.Transparent),
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused }
                .semantics {
                    contentDescription = "Pairing code, 6 digits"
                    errorMessage?.let { error(it) }
                },
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(enabled = !busy) {
                            focusRequester.requestFocus()
                            keyboardController?.show()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(6) { index ->
                            Bubble(
                                digit = code.getOrNull(index),
                                active = focused && index == code.length && !codeSubmitted,
                                isError = errorMessage != null
                            )
                        }
                    }
                    Box(Modifier.size(1.dp)) { innerTextField() }
                }
            }
        )
        if (errorMessage != null) {
            Text(errorMessage,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite })
        } else if (busy) {
            Text(
                "Checking pairing code…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun Bubble(
    digit: Char?,
    active: Boolean,
    isError: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(
                when {
                    isError -> MaterialTheme.colorScheme.errorContainer
                    digit != null -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            )
            .border(
                width = if (active || isError) 2.dp else 1.dp,
                color = when {
                    isError -> MaterialTheme.colorScheme.error
                    active -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.outlineVariant
                },
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (digit != null) {
            Text(
                text = digit.toString(),
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = 24.sp,
                style = MaterialTheme.typography.titleLarge
            )
        }
    }
}
