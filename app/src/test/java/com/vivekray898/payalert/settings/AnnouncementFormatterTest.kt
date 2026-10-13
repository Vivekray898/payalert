package com.vivekray898.payalert.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AnnouncementFormatterTest {

    @Test
    fun `blank template uses the built-in credit wording`() {
        val text = AnnouncementFormatter.format(
            template = "",
            amount = 1234.5,
            merchant = "ACME",
            bankOrUpi = "HDFC Bank",
            kind = "credit"
        )

        assertEquals("Payment received. 1,234.50 from ACME.", text)
    }

    @Test
    fun `blank template uses the built-in debit wording`() {
        val text = AnnouncementFormatter.format(
            template = "",
            amount = 100.0,
            merchant = null,
            bankOrUpi = null,
            kind = "debit"
        )

        assertEquals("Payment sent. 100.00 to an unknown payee.", text)
    }

    @Test
    fun `custom template substitutes every placeholder`() {
        val text = AnnouncementFormatter.format(
            template = "{kind} {amount} {merchant} via {bank}",
            amount = 50.0,
            merchant = "X",
            bankOrUpi = "Y",
            kind = "credit"
        )

        assertEquals("credit 50.00 X via Y", text)
    }

    @Test
    fun `amounts are grouped and always show two decimals`() {
        assertEquals("1,234.50", AnnouncementFormatter.amountText(1234.5))
        assertEquals("0.99", AnnouncementFormatter.amountText(0.99))
        assertEquals("100,000.00", AnnouncementFormatter.amountText(100000.0))
    }
}
