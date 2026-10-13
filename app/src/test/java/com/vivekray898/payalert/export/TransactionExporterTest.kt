package com.vivekray898.payalert.export

import com.vivekray898.payalert.data.CashbookEntry
import com.vivekray898.payalert.data.PaymentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionExporterTest {

    private fun event(
        merchant: String?,
        rawText: String? = "Rs.500.00 credited to A/c XX1234 from merchant",
        kind: String = "credit",
        amount: Double = 500.0
    ) = PaymentEvent(
        id = 1,
        kind = kind,
        amount = amount,
        currency = "INR",
        bankOrUpi = "HDFC Bank",
        merchant = merchant,
        utr = "412345678901",
        account = "XX1234",
        balance = 6000.0,
        category = "income",
        source = "sms",
        sourceDetail = "VK-HDFCBK",
        rawText = rawText,
        dedupKey = "key-1",
        receivedAt = 1_700_000_000_000
    )

    @Test
    fun `csv has a header even when there are no rows`() {
        val csv = TransactionExporter.eventsToCsv(emptyList())
        val header = csv.trim().lines().first()
        assertEquals(
            "received_at,direction,amount,currency,bank_or_upi,merchant,reference,account,balance," +
                "category,source,message",
            header
        )
        assertEquals(1, csv.trim().lines().size)
    }

    @Test
    fun `csv quotes fields that contain commas`() {
        val csv = TransactionExporter.eventsToCsv(
            listOf(event(merchant = "ACME, STORE", rawText = "Rs.500 credited from ACME, STORE"))
        )
        val row = csv.trim().lines()[1]
        assertTrue(row.contains("\"ACME, STORE\""))
        assertTrue(row.contains("\"Rs.500 credited from ACME, STORE\""))
    }

    @Test
    fun `csv doubles embedded quotes`() {
        val csv = TransactionExporter.eventsToCsv(listOf(event(merchant = "He said \"hi\"")))
        assertTrue(csv.contains("\"He said \"\"hi\"\"\""))
    }

    @Test
    fun `amount is fixed to two decimals regardless of the request locale`() {
        val csv = TransactionExporter.eventsToCsv(listOf(event(merchant = "A", rawText = "x", amount = 1234.5)))
        val row = csv.trim().lines()[1]
        assertTrue(row.contains(",1234.50,"))
    }

    @Test
    fun `cashbook csv carries its own header and rows`() {
        val csv = TransactionExporter.cashbookToCsv(
            listOf(
                CashbookEntry(
                    id = 1,
                    label = "Vegetables, market",
                    kind = "debit",
                    amount = 120.0,
                    category = "Groceries",
                    note = null,
                    occurredAt = 1_700_000_000_000,
                    createdAt = 1_700_000_000_000
                )
            )
        )
        assertEquals("occurred_at,direction,amount,label,category,note", csv.lineSequence().first())
        assertTrue(csv.contains("\"Vegetables, market\",Groceries,"))
    }
}
