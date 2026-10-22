package com.vivekray898.payalert.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vivekray898.payalert.AppGraph
import com.vivekray898.payalert.relay.EmployeeRealtimeService
import com.vivekray898.payalert.role.LocalDeviceRole

/**
 * Makes sure the foreground monitor is live again after a device unlock — the point
 * at which the user is most likely to receive a payment and expect an announcement.
 */
class ServiceRestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_USER_PRESENT) return
        val graph = AppGraph.get(context)
        val role = graph.settings.localDeviceRoleState.currentRole
        if (graph.settings.state.value.monitoringEnabled && role != LocalDeviceRole.EMPLOYEE) {
            PaymentMonitorService.start(context)
        }
        if (role == LocalDeviceRole.EMPLOYEE) {
            EmployeeRealtimeService.start(context)
        }
    }
}
