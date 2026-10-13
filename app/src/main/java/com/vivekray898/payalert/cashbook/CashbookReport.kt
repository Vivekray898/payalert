package com.vivekray898.payalert.cashbook

import com.vivekray898.payalert.data.CashbookEntry

data class CategoryReport(val label: String, val inAmount: Double, val outAmount: Double) {
    val net: Double get() = inAmount - outAmount
}

data class CashbookReport(
    val totalIn: Double,
    val totalOut: Double,
    val entryCount: Int,
    val byCategory: List<CategoryReport>
) {
    val net: Double get() = totalIn - totalOut
}

/**
 * Cashbook totals for a date range, grouped by category.
 *
 * Kept free of Android types so the arithmetic (and the uncategorised fallback) is
 * covered by unit tests rather than checked by eye.
 */
object CashbookReportCalculator {

    const val UNCATEGORISED = "Uncategorised"

    fun build(entries: List<CashbookEntry>): CashbookReport {
        val grouped = entries.groupBy { entry ->
            entry.category?.takeIf { it.isNotBlank() } ?: UNCATEGORISED
        }

        return CashbookReport(
            totalIn = entries.filter { it.kind == "credit" }.sumOf { it.amount },
            totalOut = entries.filter { it.kind == "debit" }.sumOf { it.amount },
            entryCount = entries.size,
            byCategory = grouped
                .map { (label, group) ->
                    CategoryReport(
                        label = label,
                        inAmount = group.filter { it.kind == "credit" }.sumOf { it.amount },
                        outAmount = group.filter { it.kind == "debit" }.sumOf { it.amount }
                    )
                }
                .sortedByDescending { it.inAmount + it.outAmount }
        )
    }
}
