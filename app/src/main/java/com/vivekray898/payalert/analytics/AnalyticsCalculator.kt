package com.vivekray898.payalert.analytics

import com.vivekray898.payalert.data.PaymentEvent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class CategoryTotal(val label: String, val amount: Double)

data class DayTotal(
    val dayStart: Long,
    val label: String,
    val inAmount: Double,
    val outAmount: Double
) {
    val net: Double get() = inAmount - outAmount
}

data class AnalyticsSummary(
    val totalIn: Double,
    val totalOut: Double,
    val net: Double,
    val transactionCount: Int,
    val byCategory: List<CategoryTotal>,
    val topMerchants: List<CategoryTotal>,
    val byDay: List<DayTotal>
)

/**
 * Summarises a list of events. Pure and dependency-free, so the money maths is unit-tested
 * rather than eyeballed in a screenshot.
 */
object AnalyticsCalculator {

    fun summarize(events: List<PaymentEvent>, daysInRange: Int = 30): AnalyticsSummary {
        val credits = events.filter { it.kind == "credit" }
        val debits = events.filter { it.kind == "debit" }
        val totalIn = credits.sumOf { it.amount }
        val totalOut = debits.sumOf { it.amount }

        return AnalyticsSummary(
            totalIn = totalIn,
            totalOut = totalOut,
            net = totalIn - totalOut,
            transactionCount = events.size,
            byCategory = group(debits, key = { it.category }, fallback = "uncategorised"),
            // Payees are merchants, not categories: an earlier version grouped these by
            // category (which is always "expense" for a debit), so every row read "expense".
            topMerchants = group(
                debits,
                key = { it.merchant ?: it.bankOrUpi },
                fallback = "unknown",
                limit = 5
            ),
            byDay = byDay(events, daysInRange)
        )
    }

    private fun group(
        events: List<PaymentEvent>,
        key: (PaymentEvent) -> String?,
        fallback: String,
        limit: Int = Int.MAX_VALUE
    ): List<CategoryTotal> =
        events
            .groupBy { event -> key(event)?.takeIf { it.isNotBlank() } ?: fallback }
            .map { (label, group) -> CategoryTotal(label, group.sumOf { it.amount }) }
            .sortedByDescending { it.amount }
            .take(limit)

    private fun byDay(events: List<PaymentEvent>, daysInRange: Int): List<DayTotal> {
        if (events.isEmpty()) return emptyList()
        val formatter = SimpleDateFormat("dd MMM", Locale.getDefault())
        return events
            .groupBy { startOfDay(it.receivedAt) }
            .map { (dayStart, group) ->
                DayTotal(
                    dayStart = dayStart,
                    label = formatter.format(Date(dayStart)),
                    inAmount = group.filter { it.kind == "credit" }.sumOf { it.amount },
                    outAmount = group.filter { it.kind == "debit" }.sumOf { it.amount }
                )
            }
            .sortedByDescending { it.dayStart }
            .take(daysInRange.coerceAtLeast(1))
    }

    fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Range presets used by the date filter, mirroring the reference's filter sheet. */
    fun rangeForDays(days: Int, now: Long = System.currentTimeMillis()): LongRange {
        val end = now
        val start = startOfDay(now - (days - 1).toLong() * DAY_MILLIS)
        return start..end
    }

    fun rangeForThisMonth(now: Long = System.currentTimeMillis()): LongRange {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis..now
    }

    const val DAY_MILLIS = 24L * 60 * 60 * 1000
}
