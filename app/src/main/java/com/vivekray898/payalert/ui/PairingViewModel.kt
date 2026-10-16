package com.vivekray898.payalert.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vivekray898.payalert.AppGraph
import com.vivekray898.payalert.relay.RelayStatus
import com.vivekray898.payalert.role.DeviceRoleSnapshot
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Read-only view of the pairing role and the encrypted-relay status.
 *
 * Screens use it to show which mode this device is in, how forwarding is doing, and whether a
 * re-pair is needed. It exposes no payload content: only counts, timestamps, and fixed messages.
 */
class PairingViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = AppGraph.get(application)

    val role: StateFlow<DeviceRoleSnapshot> = graph.settings.localDeviceRoleState.snapshot

    val relay: StateFlow<RelayStatus> = graph.relayEngine.status

    init {
        // Counters come from the local queues, so they can be read before any network pass.
        refreshStatus()
    }

    fun refreshStatus() {
        viewModelScope.launch { graph.relayEngine.refreshStatus() }
    }

    fun clearError() = graph.relayEngine.clearError()
}
