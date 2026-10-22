package com.vivekray898.payalert.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vivekray898.payalert.AppGraph
import com.vivekray898.payalert.R
import com.vivekray898.payalert.role.LocalDeviceRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps an employee device pulling forwarded payments promptly.
 *
 * ## Why this is a short-interval pump and not a WebSocket
 *
 * The prompt asked for a `postgres_changes` subscription on the relay table. That is not
 * deliverable under this schema, and the reason is deliberate, not an oversight:
 *
 * - `relay_private.envelopes` is granted only to `postgres`; `anon` and `authenticated` hold
 *   no privilege at all (verified against the linked project), and
 *   `encrypted_relay.sql` adds a RESTRICTIVE `relay_no_client_access` policy with
 *   `USING (false)` plus `REVOKE ALL ... FROM PUBLIC, anon, authenticated`.
 * - Supabase Realtime filters each `postgres_changes` event by evaluating RLS **as the
 *   subscriber's role**. An authenticated subscriber therefore matches zero rows and receives
 *   nothing, no matter that the table is now in the `supabase_realtime` publication.
 *
 * The envelope table is invisible to clients by design; every read goes through the
 * `SECURITY DEFINER` RPCs so the server stays payload-blind. Turning Realtime into the
 * delivery channel would mean weakening that invariant, which is a security decision for the
 * owner of the schema, not a detail to change silently here.
 *
 * What *is* compatible with the model is a wake-up that carries only a message id, followed by
 * the existing authenticated `relay_fetch` RPC: that is the FCM path
 * ([RelayMessageFcmWork]). This service covers the interval between wake-ups by pumping the
 * existing delivery loop on a short cadence while the device is paired as an employee, which
 * replaces the 15-minute WorkManager floor whenever the process is alive.
 *
 * Started by [com.vivekray898.payalert.ui.MainActivity] and
 * [com.vivekray898.payalert.services.BootReceiver] when the persisted role is EMPLOYEE, and
 * stopped by itself as soon as this device is no longer an employee.
 */
class EmployeeRealtimeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pumpJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val graph = AppGraph.get(this)

        if (graph.settings.localDeviceRoleState.currentRole != LocalDeviceRole.EMPLOYEE) {
            Log.i(TAG, "not_employee stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        ServiceCompatStartForeground(this)

        if (pumpJob?.isActive == true) return START_STICKY
        pumpJob = scope.launch {
            var ticks = 0
            while (isActive) {
                try {
                    graph.runRelayPump()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Offline is the normal case; the next tick retries. runRelayPump already
                    // swallows per-message problems, so anything here is transport-level.
                }
                ticks++
                if (ticks % PUMP_LOG_EVERY == 0) {
                    Log.i(TAG, "employee_pump tick=$ticks t=${System.currentTimeMillis()}")
                }
                delay(PUMP_INTERVAL_MILLIS)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        pumpJob?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PayAlertRelay"
        const val NOTIFICATION_ID = 1002
        const val CHANNEL_ID = "payalert_relay_realtime"

        /**
         * Polling fallback while FCM is unavailable. FCM remains the immediate path; this cadence
         * limits the worst-case fallback delay to roughly thirty seconds while cutting idle fetches
         * by two thirds compared with the old ten-second loop.
         */
        private const val PUMP_INTERVAL_MILLIS = 30_000L
        private const val PUMP_LOG_EVERY = 2

        @Volatile
        var isRunning: Boolean = false
            private set

        /** Start the pump, tolerating background-start restrictions like the monitor does. */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, EmployeeRealtimeService::class.java)
            )
            true
        } catch (error: Exception) {
            Log.w(TAG, "employee_pump_start_failed ${error.message}")
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EmployeeRealtimeService::class.java))
        }

        fun createChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.relay_realtime_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.relay_realtime_channel_description)
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }

        fun buildNotification(context: Context): Notification {
            createChannel(context)
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.relay_realtime_notification_title))
                .setContentText(context.getString(R.string.relay_realtime_notification_text))
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()
        }
    }
}

/**
 * `startForeground` with the type this service declares. Kept separate so the
 * API-level branch is visible in one place.
 */
private fun ServiceCompatStartForeground(service: EmployeeRealtimeService) {
    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    } else {
        0
    }
    androidx.core.app.ServiceCompat.startForeground(
        service,
        EmployeeRealtimeService.NOTIFICATION_ID,
        EmployeeRealtimeService.buildNotification(service),
        type
    )
}
