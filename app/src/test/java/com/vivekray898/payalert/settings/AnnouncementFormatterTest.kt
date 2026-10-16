package com.vivekray898.payalert.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        assertEquals("Payment received. 1,234 rupees 50 paise from ACME.", text)
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

        assertEquals("Payment sent. 100 rupees to an unknown payee.", text)
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

        assertEquals("credit 50 rupees X via Y", text)
    }

    @Test
    fun `a whole amount is spoken as grouped rupees with no decimals`() {
        assertEquals("777 rupees", AnnouncementFormatter.amountText(777.0))
        assertEquals("100,000 rupees", AnnouncementFormatter.amountText(100000.0))
        assertEquals("0 rupees", AnnouncementFormatter.amountText(0.0))
    }

    @Test
    fun `paise are spoken as their own unit`() {
        assertEquals("1,234 rupees 50 paise", AnnouncementFormatter.amountText(1234.5))
        assertEquals("0 rupees 99 paise", AnnouncementFormatter.amountText(0.99))
        // Rounds before speaking, so three decimals cannot leak into the sentence.
        assertEquals("100 rupees", AnnouncementFormatter.amountText(99.999))
    }

    @Test
    fun `a currency without a known minor unit is still spoken without a decimal point`() {
        assertEquals("42 USD", AnnouncementFormatter.amountText(42.0, "USD"))
        assertEquals("1,234 USD and 50", AnnouncementFormatter.amountText(1234.5, "USD"))
    }

    @Test
    fun `a non-finite amount is spoken as words instead of throwing`() {
        assertEquals(
            "an unspecified amount",
            AnnouncementFormatter.amountText(Double.POSITIVE_INFINITY)
        )
        assertEquals("an unspecified amount", AnnouncementFormatter.amountText(Double.NaN))
        // The whole sentence must still be produced, with no exception escaping the formatter.
        assertEquals(
            "Payment received. an unspecified amount from ACME.",
            AnnouncementFormatter.format("", Double.POSITIVE_INFINITY, "ACME", null, "credit")
        )
    }

    @Test
    fun `no spoken amount ever contains a decimal separator`() {
        listOf(777.0, 0.99, 1234.5, 100000.0, 99.999, 0.0, 5.0).forEach { amount ->
            assertFalse(
                "amount $amount was handed to the speech engine as a decimal",
                AnnouncementFormatter.amountText(amount).contains('.')
            )
        }
    }
}
