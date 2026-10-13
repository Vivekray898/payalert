package com.vivekray898.payalert.export

import com.google.gson.GsonBuilder
import com.vivekray898.payalert.analytics.AnalyticsSummary
import com.vivekray898.payalert.data.CashbookEntry
import com.vivekray898.payalert.data.PaymentEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the export payloads for the history and cashbook screens.
 *
 * Pure functions over plain data so the CSV shape (quoting, ordering, headers) is
 * unit-tested instead of being checked by eye in a share sheet.
 */
object TransactionExporter {

    private const val TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm"

    fun eventsToCsv(events: List<PaymentEvent>): String {
        val timestamp = SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US)
        val header = listOf(
            "received_at", "direction", "amount", "currency", "bank_or_upi", "merchant",
            "reference", "account", "balance", "category", "source", "message"
        ).joinToString(",")

        val rows = events.joinToString("\n") { event ->
            listOf(
                timestamp.format(Date(event.receivedAt)),
                event.kind,
                String.format(Locale.US, "%.2f", event.amount),
                event.currency,
                event.bankOrUpi.orEmpty(),
                event.merchant.orEmpty(),
                event.utr.orEmpty(),
                event.account.orEmpty(),
                event.balance?.let { String.format(Locale.US, "%.2f", it) }.orEmpty(),
                event.category.orEmpty(),
                event.source,
                event.rawText.orEmpty()
            ).joinToString(",") { quote(it) }
        }
        return if (rows.isEmpty()) header + "\n" else "$header\n$rows\n"
    }

    fun eventsToJson(events: List<PaymentEvent>): String =
        GsonBuilder().setPrettyPrinting().create().toJson(events)

    fun cashbookToCsv(entries: List<CashbookEntry>): String {
        val timestamp = SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US)
        val header = listOf("occurred_at", "direction", "amount", "label", "category", "note")
            .joinToString(",")
        val rows = entries.joinToString("\n") { entry ->
            listOf(
                timestamp.format(Date(entry.occurredAt)),
                entry.kind,
                String.format(Locale.US, "%.2f", entry.amount),
                entry.label,
                entry.category.orEmpty(),
                entry.note.orEmpty()
            ).joinToString(",") { quote(it) }
        }
        return if (rows.isEmpty()) header + "\n" else "$header\n$rows\n"
    }

    /** Summary block prepended to an analytics export so the numbers are self-describing. */
    fun summaryToCsv(summary: AnalyticsSummary, rangeLabel: String): String = buildString {
        appendLine("range,${quote(rangeLabel)}")
        appendLine("total_in,${String.format(Locale.US, "%.2f", summary.totalIn)}")
        appendLine("total_out,${String.format(Locale.US, "%.2f", summary.totalOut)}")
        appendLine("net,${String.format(Locale.US, "%.2f", summary.net)}")
        appendLine("transactions,${summary.transactionCount}")
        appendLine()
        appendLine("category,amount")
        summary.byCategory.forEach { total ->
            appendLine("${quote(total.label)},${String.format(Locale.US, "%.2f", total.amount)}")
        }
    }

    /** RFC 4180 style quoting: only quote when needed, double embedded quotes. */
    private fun quote(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuotes) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }
}
