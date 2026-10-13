package com.vivekray898.payalert.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * ServiceRestartReceiver — re-starts the Foreground Service when the user unlocks
 * the device or when system conditions require it.
 *
 * Responsibilities:
 *   1. On USER_PRESENT, ensure PaymentMonitorService is running in foreground
 *   2. On specific system intents, re-assert foreground service status
 *
 * Design observations (from reference APK analysis):
 *   - The reference APK has a ServiceRestartReceiver that listens for USER_PRESENT
 *     and potentially other system events to ensure the foreground service stays
 *     active across doze/doze-off cycles.
 *   - This is separate from BootReceiver which runs once at boot time.
 */
class ServiceRestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action

        when (action) {
            "android.intent.action.USER_PRESENT" -> {
                // User unlocked the device; ensure the foreground service is running
                Log.i("PayAlert", "User present — ensuring foreground service is active")
                val serviceIntent = Intent(context, ::class.java.javaClass)
                // If the service is not running, start it
                if (!isServiceRunning(context)) {
                    context.startForegroundService(serviceIntent)
                }
            }
            "android.intent.action.SCREEN_OFF" -> {
                // Optional: optional logic to pause or persist state on screen off
                Log.d("PayAlert", "Screen off event")
            }
        }
    }

    /** Check if our ForegroundService is currently running. */
    private fun isServiceRunning(context: Context): Boolean {
        val intent = Intent(context, ::class.java.javaClass)
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (runningService in manager.runningServices ?: emptyList()) {
            if (runningService.service.component.className == ::PaymentMonitorService.java.name) {
                return true
            }
        }
        return false
    }
}