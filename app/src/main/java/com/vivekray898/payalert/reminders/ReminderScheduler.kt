package com.vivekray898.payalert.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.vivekray898.payalert.R
import com.vivekray898.payalert.data.UpcomingPayment
import com.vivekray898.payalert.services.ReminderReceiver
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Schedules and posts "upcoming payment" reminders.
 *
 * Uses [AlarmManager.setAndAllowWhileIdle], which survives doze without needing the
 * SCHEDULE_EXACT_ALARM permission, so no special user grant is required.
 */
object ReminderScheduler {

    const val CHANNEL_ID = "payalert_reminders"
    private const val REQUEST_CODE_BASE = 5_000
    private const val TAG = "PayAlert"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.reminder_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    fun schedule(context: Context, payment: UpcomingPayment) {
        if (payment.isPaid) {
            cancel(context, payment.id)
            return
        }
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = maxOf(payment.dueAt, System.currentTimeMillis() + 5_000L)
        try {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(context, payment.id))
        } catch (error: Exception) {
            Log.w(TAG, "Could not schedule reminder ${payment.id}: ${error.message}")
        }
    }

    fun scheduleAll(context: Context, payments: List<UpcomingPayment>) {
        payments.forEach { schedule(context, it) }
    }

    fun cancel(context: Context, id: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        alarms.cancel(pendingIntent(context, id))
    }

    fun postNotification(context: Context, payment: UpcomingPayment) {
        createChannel(context)
        val due = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(payment.dueAt))
        val direction = if (payment.kind == "credit") "to receive" else "to pay"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Upcoming payment: ${payment.label}")
            .setContentText(
                String.format(Locale.US, "%,.2f %s due %s", payment.amount, direction, due)
            )
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(payment.id.toInt(), notification)
        } catch (error: SecurityException) {
            // POST_NOTIFICATIONS not granted — the reminder stays visible in the app list.
            Log.w(TAG, "Reminder notification blocked: ${error.message}")
        }
    }

    /** Pure helper: the same day next month, clamped by [Calendar]. */
    fun nextMonthlyDue(dueAt: Long): Long = Calendar.getInstance().apply {
        timeInMillis = dueAt
        add(Calendar.MONTH, 1)
    }.timeInMillis

    private fun pendingIntent(context: Context, id: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_BASE + (id % 10_000).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
