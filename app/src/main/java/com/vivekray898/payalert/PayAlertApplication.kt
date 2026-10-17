package com.vivekray898.payalert

import android.app.Application
import com.vivekray898.payalert.reminders.ReminderScheduler
import com.vivekray898.payalert.services.PayAlertMessagingService
import com.vivekray898.payalert.services.PaymentMonitorService
import com.vivekray898.payalert.services.RelayAnnouncementNotifier

/**
 * Builds the dependency graph once per process. Everything the app needs at runtime is
 * created here; there is no cloud dependency, so the app works with no account and no
 * network.
 */
class PayAlertApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppGraph.get(this)

        // Channels are created eagerly so the first alert is never dropped while the
        // channel is being registered.
        PaymentMonitorService.createChannel(this)
        ReminderScheduler.createChannel(this)
        RelayAnnouncementNotifier.createChannel(this)
        // onNewToken is not guaranteed to run again when a user signs in later;
        // asking for the current token on every process start closes that gap.
        PayAlertMessagingService.refreshToken(this)
    }
}
