package com.vivekray898.payalert.analytics

import com.vivekray898.payalert.data.PaymentEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class AnalyticsCalculatorTest {

    private val noon = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 12)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun event(
        kind: String,
        amount: Double,
        merchant: String? = null,
        category: String? = null,
        bank: String? = null,
        at: Long = noon
    ) = PaymentEvent(
        kind = kind,
        amount = amount,
        currency = "INR",
        bankOrUpi = bank,
        merchant = merchant,
        category = category,
        source = "sms",
        dedupKey = "k-${kind}-$amount-$merchant",
        receivedAt = at
    )

    @Test
    fun `totals and net`() {
        val summary = AnalyticsCalculator.summarize(
            listOf(
                event("credit", 5000.0, merchant = "Employer"),
                event("debit", 1200.0, merchant = "Rent"),
                event("debit", 300.0, merchant = "Groceries")
            )
        )
        assertEquals(5000.0, summary.totalIn, 0.001)
        assertEquals(1500.0, summary.totalOut, 0.001)
        assertEquals(3500.0, summary.net, 0.001)
        assertEquals(3, summary.transactionCount)
    }

    @Test
    fun `top payees are merchants not categories`() {
        // Regression: grouping payees by category made every debit read "expense".
        val summary = AnalyticsCalculator.summarize(
            listOf(
                event("debit", 250.0, merchant = "swiggy@ybl", category = "expense"),
                event("debit", 150.0, merchant = "zomato@ybl", category = "expense"),
                event("debit", 900.0, merchant = "swiggy@ybl", category = "expense")
            )
        )
        assertEquals(listOf("swiggy@ybl", "zomato@ybl"), summary.topMerchants.map { it.label })
        assertEquals(1150.0, summary.topMerchants.first().amount, 0.001)
        assertEquals(listOf("expense"), summary.byCategory.map { it.label })
        assertEquals(1300.0, summary.byCategory.first().amount, 0.001)
    }

    @Test
    fun `a payee with no merchant falls back to the bank`() {
        val summary = AnalyticsCalculator.summarize(
            listOf(event("debit", 100.0, merchant = null, bank = "ICICI Bank"))
        )
        assertEquals("ICICI Bank", summary.topMerchants.first().label)
    }

    @Test
    fun `uncategorised debits are named rather than dropped`() {
        val summary = AnalyticsCalculator.summarize(
            listOf(event("debit", 75.0, merchant = "Shop", category = null))
        )
        assertEquals("uncategorised", summary.byCategory.first().label)
    }

    @Test
    fun `daily movement separates money in from money out`() {
        val yesterday = noon - AnalyticsCalculator.DAY_MILLIS
        val summary = AnalyticsCalculator.summarize(
            listOf(
                event("credit", 5000.0, at = noon),
                event("debit", 150.0, at = noon),
                event("debit", 250.0, at = yesterday)
            )
        )
        val today = summary.byDay.first { it.dayStart == AnalyticsCalculator.startOfDay(noon) }
        assertEquals(5000.0, today.inAmount, 0.001)
        assertEquals(150.0, today.outAmount, 0.001)
        assertEquals(4850.0, today.net, 0.001)
        assertEquals(2, summary.byDay.size)
    }

    @Test
    fun `summary of nothing is zeroed rather than crashing`() {
        val summary = AnalyticsCalculator.summarize(emptyList())
        assertEquals(0.0, summary.totalIn, 0.001)
        assertEquals(0.0, summary.totalOut, 0.001)
        assertEquals(0, summary.transactionCount)
        assertEquals(emptyList<CategoryTotal>(), summary.byCategory)
        assertEquals(emptyList<DayTotal>(), summary.byDay)
    }

    @Test
    fun `range presets cover the requested days and end now`() {
        val now = noon
        val seven = AnalyticsCalculator.rangeForDays(7, now)
        assertEquals(now, seven.last)
        assertEquals(AnalyticsCalculator.startOfDay(now - 6 * AnalyticsCalculator.DAY_MILLIS), seven.first)

        val month = AnalyticsCalculator.rangeForThisMonth(now)
        assertEquals(now, month.last)
        assertEquals(1, Calendar.getInstance().apply { timeInMillis = month.first }
            .get(Calendar.DAY_OF_MONTH))
    }
}
