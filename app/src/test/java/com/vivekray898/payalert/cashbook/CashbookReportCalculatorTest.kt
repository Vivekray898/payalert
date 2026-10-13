package com.vivekray898.payalert.cashbook

import com.vivekray898.payalert.data.CashbookEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class CashbookReportCalculatorTest {

    private fun entry(
        kind: String,
        amount: Double,
        category: String?,
        id: Long = 0
    ) = CashbookEntry(
        id = id,
        label = "entry",
        kind = kind,
        amount = amount,
        category = category,
        occurredAt = 0,
        createdAt = 0
    )

    @Test
    fun `totals in and out and net`() {
        val report = CashbookReportCalculator.build(
            listOf(
                entry("credit", 5000.0, "Salary"),
                entry("debit", 1200.0, "Groceries"),
                entry("debit", 300.0, "Groceries")
            )
        )
        assertEquals(5000.0, report.totalIn, 0.001)
        assertEquals(1500.0, report.totalOut, 0.001)
        assertEquals(3500.0, report.net, 0.001)
        assertEquals(3, report.entryCount)
    }

    @Test
    fun `groups by category and sorts by movement`() {
        val report = CashbookReportCalculator.build(
            listOf(
                entry("debit", 1200.0, "Groceries"),
                entry("debit", 300.0, "Groceries"),
                entry("debit", 2000.0, "Rent"),
                entry("credit", 100.0, "Refund")
            )
        )
        assertEquals(listOf("Rent", "Groceries", "Refund"), report.byCategory.map { it.label })
        val groceries = report.byCategory.first { it.label == "Groceries" }
        assertEquals(1500.0, groceries.outAmount, 0.001)
        assertEquals(0.0, groceries.inAmount, 0.001)
        assertEquals(-1500.0, groceries.net, 0.001)
    }

    @Test
    fun `entries without a category fall into the uncategorised group`() {
        val report = CashbookReportCalculator.build(
            listOf(entry("debit", 50.0, null), entry("debit", 25.0, "   "))
        )
        assertEquals(1, report.byCategory.size)
        assertEquals(CashbookReportCalculator.UNCATEGORISED, report.byCategory.first().label)
        assertEquals(75.0, report.byCategory.first().outAmount, 0.001)
    }

    @Test
    fun `empty input produces a zeroed report`() {
        val report = CashbookReportCalculator.build(emptyList())
        assertEquals(0.0, report.totalIn, 0.001)
        assertEquals(0.0, report.totalOut, 0.001)
        assertEquals(0, report.entryCount)
        assertEquals(emptyList<CategoryReport>(), report.byCategory)
    }
}
