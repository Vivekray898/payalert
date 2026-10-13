package com.vivekray898.payalert.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vivekray898.payalert.AppGraph
import com.vivekray898.payalert.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fires when an upcoming payment becomes due: posts the reminder notification and, for
 * monthly items, rolls the reminder forward to the next month.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id <= 0L) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val database = AppGraph.get(appContext).database
                val dao = database.upcomingPaymentDao()
                val payment = dao.byId(id) ?: return@launch
                if (payment.isPaid) return@launch

                ReminderScheduler.postNotification(appContext, payment)

                if (payment.repeatMonthly) {
                    val next = payment.copy(dueAt = ReminderScheduler.nextMonthlyDue(payment.dueAt))
                    dao.update(next)
                    ReminderScheduler.schedule(appContext, next)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_ID = "reminder_id"
    }
}
