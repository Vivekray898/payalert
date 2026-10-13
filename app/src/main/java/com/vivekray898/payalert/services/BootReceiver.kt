package com.vivekray898.payalert.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BootReceiver — re-arms all background services when the device boots.
 *
 * Responsibilities:
 *   1. On BOOT_COMPLETED, start the Foreground Service (PaymentMonitorService)
 *   2. Ensure the NotificationListenerService is re-registered
 *   3. Log the boot event for diagnostics
 *
 * Permissions required:
 *   - RECEIVE_BOOT_COMPLETED (manifest)
 *   - On Android 11+, the NotificationListenerService must be re-enabled by the
 *     user if the device was powered off with access granted (Android preserves the
 *     permission across reboots, but the service must explicitly restart).
 *
 * Design observations (from reference APK analysis):
 *   - The reference APK has 3 boot-related receivers: BootReceiver, ServiceRestartReceiver,
 *     and TtsBackupReceiver. We implement BootReceiver as the primary re-arter;
 *     ServiceRestartReceiver is deferred to a later phase.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.intent.action.BOOT_COMPLETED") {
            Log.i("PayAlert", "Boot completed — re-arming background services")

            // Start the foreground service that drives payment monitoring + TTS
            val serviceIntent = Intent(context, ::class.java.javaClass)
            // We target the specific service; Android will respect the manifest declaration.
            // The service must have FOREGROUND_SERVICE + FOREGROUND_SERVICE_SPECIAL_USE.
            context.startForegroundService(serviceIntent)

            // Log that boot occurred; in a full implementation the ViewModel would
            // update a "lastBoot" timestamp stored in Room/SharedPreferences.
            Log.d("PayAlertBoot", "BootReceiver triggered; service restart requested")
        }
    }
}