package com.vivekray898.payalert.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.vivekray898.payalert.AppGraph
import com.vivekray898.payalert.R
import com.vivekray898.payalert.analytics.AnalyticsCalculator
import com.vivekray898.payalert.data.PaymentEvent
import com.vivekray898.payalert.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home-screen widget: this month's money in/out plus the latest detected payment.
 *
 * The database read happens on [Dispatchers.IO] through [goAsync], so the broadcast is not
 * blocked on disk I/O. A refresh button re-reads; opening the app also refreshes on resume.
 */
class PayAlertWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val content = loadContent(context)
                withContext(Dispatchers.Main) {
                    appWidgetIds.forEach { id ->
                        appWidgetManager.updateAppWidget(id, buildViews(context, id, content))
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PayAlertWidgetProvider::class.java))
            onUpdate(context, manager, ids)
            return
        }
        super.onReceive(context, intent)
    }

    private data class Content(val monthIn: Double, val monthOut: Double, val latest: PaymentEvent?)

    private suspend fun loadContent(context: Context): Content {
        val graph = AppGraph.get(context)
        val monthStart = AnalyticsCalculator.rangeForThisMonth().first
        val events = withContext(Dispatchers.IO) {
            graph.database.paymentEventDao().inRange(monthStart, Long.MAX_VALUE)
        }
        return Content(
            monthIn = events.filter { it.kind == "credit" }.sumOf { it.amount },
            monthOut = events.filter { it.kind == "debit" }.sumOf { it.amount },
            latest = events.maxByOrNull { it.receivedAt }
        )
    }

    private fun buildViews(context: Context, widgetId: Int, content: Content): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_payalert)

        views.setTextViewText(
            R.id.widget_totals,
            context.getString(
                R.string.widget_totals,
                format(content.monthIn),
                format(content.monthOut)
            )
        )

        val latest = content.latest
        views.setTextViewText(
            R.id.widget_latest,
            if (latest == null) {
                context.getString(R.string.widget_empty)
            } else {
                val stamp = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
                    .format(Date(latest.receivedAt))
                val sign = if (latest.kind == "credit") "+" else "-"
                "$stamp · ${latest.merchant ?: latest.bankOrUpi ?: "Payment"} $sign${format(latest.amount)}"
            }
        )

        views.setOnClickPendingIntent(
            R.id.widget_refresh,
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, PayAlertWidgetProvider::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )

        val openIntent = Intent(context, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            context,
            widgetId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_open, openPending)
        views.setOnClickPendingIntent(R.id.widget_title, openPending)

        return views
    }

    private fun format(amount: Double): String = String.format(Locale.US, "%,.2f", amount)

    companion object {
        const val ACTION_REFRESH = "com.vivekray898.payalert.widget.REFRESH"

        /** Re-renders every placed widget; called after a payment is stored. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, PayAlertWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, PayAlertWidgetProvider::class.java).setAction(ACTION_REFRESH)
            )
        }
    }
}
