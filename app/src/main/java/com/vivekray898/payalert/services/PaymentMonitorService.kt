package com.vivekray898.payalert.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vivekray898.payalert.AppGraph
import com.vivekray898.payalert.R

/**
 * Low-priority foreground service whose only job is to keep the process and the
 * speech engine alive so a payment arriving while the app is closed is still
 * announced immediately.
 *
 * The actual detection is done by [SmsReceiver] and [PaymentNotificationListener];
 * this service intentionally does no polling.
 */
class PaymentMonitorService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannel(this)
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Warm the graph so the TTS engine is initialised before the first payment.
        AppGraph.get(this)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(this), type)

        return START_STICKY
    }

    companion object {
        private const val TAG = "PayAlert"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "payalert_monitor"

        /**
         * Liveness of the monitor, read by the launch-time health check. A foreground
         * service can be killed by the system, so this is re-read rather than assumed.
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun createChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.monitor_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.monitor_channel_description)
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }

        fun buildNotification(context: Context): Notification {
            createChannel(context)
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.monitor_notification_title))
                .setContentText(context.getString(R.string.monitor_notification_text))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()
        }

        /** Start the monitor, tolerating background-start restrictions. */
        fun start(context: Context): Boolean {
            return try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, PaymentMonitorService::class.java)
                )
                true
            } catch (error: Exception) {
                // e.g. ForegroundServiceStartNotAllowedException on Android 12+ when the
                // app is in the background and not exempt. The next user-visible launch
                // will start it again.
                Log.w(TAG, "Could not start payment monitor: ${error.message}")
                false
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PaymentMonitorService::class.java))
        }
    }
}
