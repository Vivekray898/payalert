package com.vivekray898.payalert.health

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.util.Log
import com.vivekray898.payalert.AppGraph
import com.vivekray898.payalert.reminders.ReminderScheduler
import com.vivekray898.payalert.services.PaymentMonitorService
import com.vivekray898.payalert.services.PaymentNotificationListener
import com.vivekray898.payalert.ui.PermissionStatus

/** One requirement the app needs in order to announce payments, and whether it holds now. */
data class HealthCheck(
    val name: String,
    val ok: Boolean,
    val detail: String,
    /** A blocking check stops announcements entirely; advisory ones only degrade them. */
    val blocking: Boolean
)

/** Everything the evaluation depends on, free of Android types so it can be unit-tested. */
data class HealthSnapshot(
    val monitoringEnabled: Boolean,
    val monitorServiceRunning: Boolean,
    val notificationAccessGranted: Boolean,
    val notificationListenerConnected: Boolean,
    val smsGranted: Boolean,
    val notificationsGranted: Boolean,
    val batteryExempt: Boolean,
    val pendingReminders: Int
)

/** A repair the health check can perform without user interaction. */
enum class HealthRepair {
    START_MONITOR,
    REBIND_LISTENER,
    REARM_REMINDERS
}

data class ServiceHealthReport(
    val checks: List<HealthCheck>,
    val repairs: List<HealthRepair>
) {
    val issues: List<HealthCheck> get() = checks.filter { !it.ok }

    /** Issues that actually stop announcements; these are what the banner reports. */
    val blockingIssues: List<HealthCheck> get() = issues.filter { it.blocking }

    val healthy: Boolean get() = blockingIssues.isEmpty()
}

/**
 * The reference app calls `startServiceHealthChecks` from `MainActivity` and ships a
 * `ServiceHealthWorker`: it re-checks that its detection services are still alive rather
 * than assuming they survived doze, a task-kill or a permission change. This is the same
 * idea, evaluated on every launch.
 */
object ServiceHealthEvaluator {

    fun evaluate(snapshot: HealthSnapshot): ServiceHealthReport {
        val checks = mutableListOf<HealthCheck>()
        val repairs = mutableListOf<HealthRepair>()

        // Monitoring switched off on purpose is a valid state, not a fault.
        val monitorOk = !snapshot.monitoringEnabled || snapshot.monitorServiceRunning
        if (snapshot.monitoringEnabled && !snapshot.monitorServiceRunning) {
            repairs += HealthRepair.START_MONITOR
        }
        checks += HealthCheck(
            name = "Payment monitoring",
            ok = monitorOk,
            detail = when {
                !snapshot.monitoringEnabled -> "Off (chosen in Settings)"
                snapshot.monitorServiceRunning -> "Running"
                else -> "Not running"
            },
            blocking = true
        )

        checks += HealthCheck(
            name = "Notification access",
            ok = snapshot.notificationAccessGranted,
            detail = if (snapshot.notificationAccessGranted) "Granted" else "Not granted",
            blocking = true
        )

        // The listener can be bound but not connected after the app process is rebuilt.
        val listenerOk = !snapshot.notificationAccessGranted || snapshot.notificationListenerConnected
        if (snapshot.notificationAccessGranted && !snapshot.notificationListenerConnected) {
            repairs += HealthRepair.REBIND_LISTENER
        }
        checks += HealthCheck(
            name = "Notification listener",
            ok = listenerOk,
            detail = when {
                !snapshot.notificationAccessGranted -> "Waiting for access"
                snapshot.notificationListenerConnected -> "Connected"
                else -> "Granted but not connected"
            },
            blocking = true
        )

        checks += HealthCheck(
            name = "SMS permission",
            ok = snapshot.smsGranted,
            detail = if (snapshot.smsGranted) "Granted" else "Not granted",
            blocking = true
        )

        checks += HealthCheck(
            name = "Notification permission",
            ok = snapshot.notificationsGranted,
            detail = if (snapshot.notificationsGranted) "Granted" else "Not granted",
            blocking = true
        )

        checks += HealthCheck(
            name = "Battery optimisation",
            ok = snapshot.batteryExempt,
            detail = if (snapshot.batteryExempt) "Exempt" else "Restricted",
            blocking = false
        )

        // Alarms do not survive a reboot or a force-stop, so re-arm whatever is still due.
        if (snapshot.pendingReminders > 0) {
            repairs += HealthRepair.REARM_REMINDERS
        }
        checks += HealthCheck(
            name = "Upcoming reminders",
            ok = true,
            detail = if (snapshot.pendingReminders == 0) {
                "Nothing pending"
            } else {
                "${snapshot.pendingReminders} pending, re-armed"
            },
            blocking = false
        )

        return ServiceHealthReport(checks = checks, repairs = repairs)
    }
}

/** Reads the live state, then repairs what can be repaired without asking the user. */
object ServiceHealth {

    private const val TAG = "PayAlert"

    suspend fun snapshot(context: Context, graph: AppGraph.Graph): HealthSnapshot = HealthSnapshot(
        monitoringEnabled = graph.settings.state.value.monitoringEnabled,
        monitorServiceRunning = PaymentMonitorService.isRunning,
        notificationAccessGranted = PermissionStatus.hasNotificationAccess(context),
        notificationListenerConnected = graph.listenerConnected.value,
        smsGranted = PermissionStatus.hasReceiveSms(context),
        notificationsGranted = PermissionStatus.hasPostNotifications(context),
        batteryExempt = PermissionStatus.isIgnoringBatteryOptimizations(context),
        pendingReminders = graph.database.upcomingPaymentDao().pending().size
    )

    fun evaluate(snapshot: HealthSnapshot): ServiceHealthReport =
        ServiceHealthEvaluator.evaluate(snapshot)

    /** Performs the repairs the evaluator asked for and describes what it did. */
    suspend fun repair(
        context: Context,
        graph: AppGraph.Graph,
        repairs: List<HealthRepair>
    ): List<String> {
        val done = mutableListOf<String>()
        repairs.forEach { repair ->
            when (repair) {
                HealthRepair.START_MONITOR -> {
                    if (PaymentMonitorService.start(context)) done += "started payment monitor"
                }

                HealthRepair.REBIND_LISTENER -> {
                    try {
                        NotificationListenerService.requestRebind(
                            ComponentName(context, PaymentNotificationListener::class.java)
                        )
                        done += "re-bound notification listener"
                    } catch (error: Exception) {
                        Log.w(TAG, "Could not rebind the notification listener: ${error.message}")
                    }
                }

                HealthRepair.REARM_REMINDERS -> {
                    val pending = graph.database.upcomingPaymentDao().pending()
                    ReminderScheduler.scheduleAll(context, pending)
                    done += "re-armed ${pending.size} reminder(s)"
                }
            }
        }
        return done
    }
}
