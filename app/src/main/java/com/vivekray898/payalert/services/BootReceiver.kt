package com.vivekray898.payalert.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vivekray898.payalert.AppGraph
import com.vivekray898.payalert.relay.EmployeeRealtimeService
import com.vivekray898.payalert.role.LocalDeviceRole
import com.vivekray898.payalert.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Restores background monitoring after a reboot or an app update, but only when the
 * user has left monitoring enabled. Pending payment reminders are re-armed too, because
 * alarms do not survive a reboot.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val graph = AppGraph.get(context)
                if (graph.settings.state.value.monitoringEnabled) {
                    PaymentMonitorService.start(context)
                }
                // Start Realtime service for employee devices after boot
                if (graph.settings.localDeviceRoleState.currentRole == LocalDeviceRole.EMPLOYEE) {
                    EmployeeRealtimeService.start(context)
                }

                val pendingResult = goAsync()
                val appContext = context.applicationContext
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        val due = graph.database.upcomingPaymentDao().pending()
                        ReminderScheduler.scheduleAll(appContext, due)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
