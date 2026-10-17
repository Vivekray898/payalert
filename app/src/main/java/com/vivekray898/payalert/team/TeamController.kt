package com.vivekray898.payalert.team

import com.vivekray898.payalert.role.LocalDeviceRole
import com.vivekray898.payalert.role.LocalDeviceRoleState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TeamState(
    val account: TeamAccount? = null,
    val team: TeamSnapshot? = null,
    val invite: TeamInvite? = null,
    val busy: Boolean = false,
    val needsRefresh: Boolean = false,
    val error: String? = null,
    val message: String? = null
)

/**
 * UI-independent state machine. Never persists codes, auth tokens, or team snapshots.
 *
 * It is also the single place where a confirmed pairing becomes a local device role: an owner
 * device keeps detecting its own payments, an employee device stops. The role write is synchronous,
 * so the next SMS or notification is already judged under the new role, and [onPairingChanged] is
 * notified so the encrypted relay can start, stop, or purge.
 */
class TeamController(
    private val repository: TeamRepository,
    private val scope: CoroutineScope,
    initialDeviceName: String,
    private val roleState: LocalDeviceRoleState? = null,
    private val onPairingChanged: (suspend (TeamSnapshot?, LocalDeviceRole) -> Unit)? = null,
    private val saveDeviceName: (String) -> Unit = {}
) {
    val configured = repository.configured
    val authError = repository.authError
    private var deviceName = initialDeviceName
    private val mutableState = MutableStateFlow(TeamState())
    val state = mutableState.asStateFlow()
    private var action: Job? = null
    private var generation = 0
    private val refreshMutex = Mutex()

    init {
        scope.launch {
            repository.account.collect { account ->
                generation++
                action?.cancel()
                mutableState.value = TeamState(account = account)
                if (account != null) refresh()
            }
        }
    }

    fun signIn(openBrowser: (String) -> Unit) = perform(requireAccount = false) {
        openBrowser(repository.signInUrl())
        update { copy(message = "Finish Google sign-in in your browser, then return to PayAlert.") }
    }

    fun signOut() = perform {
        repository.signOut()
        // The account observer clears the snapshot and invitation, not local payments.
    }

    fun refresh() = perform { refreshNow() }

    /**
     * Refreshes pairing synchronously for cold-start workers.
     *
     * The old background-only [refresh] returned before its network request ran, so an FCM process
     * could call the relay pump while it still had no employee context. This suspendable variant
     * completes the readback and role/relay synchronization before its caller proceeds.
     */
    suspend fun refreshNow(): Boolean {
        if (!configured || repository.account.value == null) return false
        return refreshMutex.withLock {
            if (repository.account.value == null) return false
            val snapshot = repository.loadTeam(deviceName)
            update {
                copy(
                    account = repository.account.value,
                    team = snapshot,
                    needsRefresh = false,
                    invite = invite.takeIf {
                        team?.employees == snapshot.employees && snapshot.owner == null
                    }
                )
            }
            syncPairing(snapshot)
            true
        }
    }

    fun renameDevice(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank() || trimmed.length > 100 || trimmed.any { it.isISOControl() }) {
            update { copy(error = "Enter a device name between 1 and 100 characters.") }
            return
        }
        perform {
            val snapshot = repository.loadTeam(trimmed)
            saveDeviceName(trimmed)
            deviceName = trimmed
            update { copy(team = snapshot, needsRefresh = false, message = "Device name saved.") }
            syncPairing(snapshot)
        }
    }

    fun createInvite() {
        // An employee device belongs to another owner's team and must leave it before inviting.
        if (state.value.team?.owner != null) {
            update {
                copy(error = "This device is connected as an employee. Leave that team before " +
                    "pairing it as an owner.")
            }
            return
        }
        perform(ready = true) {
            val invite = repository.createInvite()
            update { copy(invite = invite) }
        }
    }

    fun cancelInvite() = perform(ready = true) {
        repository.cancelInvite()
        update { copy(invite = null, message = "Pairing code cancelled.") }
    }

    fun join(code: String) {
        // An owner with paired employees cannot also become somebody's employee through one device.
        if (state.value.team?.employees?.isNotEmpty() == true) {
            update {
                copy(error = "Remove every paired employee device before joining another owner. " +
                    "A device is either an owner or an employee.")
            }
            return
        }
        if (!PairingCode.isValid(code)) {
            update { copy(error = "Enter all 12 letters and numbers from the owner's pairing code.") }
            return
        }
        mutate("Device paired. You can now see the owner device.") {
            repository.joinTeam(PairingCode.normalize(code))
        }
    }

    fun remove(connection: TeamConnection) = mutate("Device connection removed.") {
        repository.removeConnection(connection.pairingId)
    }

    fun clearError() = update { copy(error = null) }

    private fun mutate(message: String, operation: suspend () -> Unit) = perform(ready = true) {
        // A timeout can happen after a server commit. Require readback before another mutation.
        update { copy(needsRefresh = true, invite = null) }
        operation()
        val snapshot = repository.loadTeam(deviceName)
        update { copy(team = snapshot, needsRefresh = false, message = message) }
        syncPairing(snapshot)
    }

    /**
     * Persists the role implied by [snapshot] and notifies the relay.
     *
     * A null snapshot (signed out, or no answer yet) leaves the stored role untouched: signing out
     * does not remove a server-side pairing, so it must not silently turn an employee device back
     * into a device that announces its own local payments.
     */
    private suspend fun syncPairing(snapshot: TeamSnapshot?) {
        if (snapshot == null) return
        val role = if (snapshot.owner != null) LocalDeviceRole.EMPLOYEE else LocalDeviceRole.OWNER
        roleState?.setRole(role, snapshot.owner?.deviceId, snapshot.owner?.deviceName)
        onPairingChanged?.invoke(snapshot, role)
    }

    private fun perform(
        requireAccount: Boolean = true,
        ready: Boolean = false,
        operation: suspend () -> Unit
    ) {
        val current = state.value
        if (!configured || current.busy || (requireAccount && current.account == null) ||
            (ready && (current.team == null || current.needsRefresh))) return
        val version = generation
        update { copy(busy = true, error = null, message = null) }
        action = scope.launch {
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (version == generation) {
                    // Only safe repository errors are shown, never raw exceptions/payloads.
                    val detail = (error as? TeamClientException)?.message
                        ?: "Unable to complete this action. Check your connection and try again."
                    update { copy(error = detail) }
                }
            } finally {
                if (version == generation) update { copy(busy = false) }
            }
        }
    }

    private fun update(transform: TeamState.() -> TeamState) {
        mutableState.value = state.value.transform()
    }
}
