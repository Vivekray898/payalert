package com.vivekray898.payalert.parsers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Message formats modelled on the alert shapes the reference app supports (bank DLT
 * sender ids + UPI app packages). No reference rule or pattern was copied; these are
 * independently authored expectations for the same market.
 */
class ParserRegistryTest {

    private val parser = ParserRegistry(TestConfig.shipped)

    // ---- credits across banks -------------------------------------------------

    @Test
    fun `hdfc credit alert with account and utr`() {
        val parsed = parser.parse(
            "Your a/c XX1234 is credited with Rs 5000.00 on 12-05-24 by UPI/ACME STORE. UTR 412345678901",
            "AD-HDFCBK"
        )!!

        assertEquals("credit", parsed.kind)
        assertEquals(5000.00, parsed.amount, 0.001)
        assertEquals("XX1234", parsed.account)
        assertEquals("412345678901", parsed.utr)
        assertEquals("HDFC Bank", parsed.bankOrUpi)
        assertEquals("INR", parsed.currency)
        assertTrue(parsed.merchant!!.contains("ACME"))
    }

    @Test
    fun `sbi neft credit names the remitter`() {
        val parsed = parser.parse(
            "Dear Customer, Rs 1500 credited to your account XX5678 by NEFT from RAHUL SHARMA. Ref 987654321012",
            "AD-SBIN"
        )!!

        assertEquals("credit", parsed.kind)
        assertEquals(1500.00, parsed.amount, 0.001)
        assertEquals("RAHUL SHARMA", parsed.merchant)
        assertEquals("SBI", parsed.bankOrUpi)
        assertEquals("987654321012", parsed.utr)
    }

    // ---- debits across banks --------------------------------------------------

    @Test
    fun `icici debit extracts merchant balance and vpa`() {
        val parsed = parser.parse(
            "ICICI Bank: Rs.2,499.00 debited from A/c XX1234 on 12-05-24 to VPA acme@ybl. Ref 412345678901. Avl Bal Rs 10,000.00",
            "AD-ICICIB"
        )!!

        assertEquals("debit", parsed.kind)
        assertEquals(2499.00, parsed.amount, 0.001)
        assertEquals("acme@ybl", parsed.merchant)
        assertEquals(10000.00, parsed.balance!!, 0.001)
        assertEquals("ICICI Bank", parsed.bankOrUpi)
    }

    @Test
    fun `axis card debit at a merchant stops at the merchant name`() {
        val parsed = parser.parse(
            "Rs 800.00 debited from A/c XX9012 at BIG BAZAAR on 12-05-24",
            "AD-AXISBK"
        )!!

        assertEquals("debit", parsed.kind)
        assertEquals(800.00, parsed.amount, 0.001)
        assertEquals("BIG BAZAAR", parsed.merchant)
        assertEquals("Axis Bank", parsed.bankOrUpi)
    }

    @Test
    fun `kotak debit does not swallow the trailing bank clause`() {
        val parsed = parser.parse(
            "Sent Rs.150 to Big Bazaar from Kotak Bank A/c XX3456",
            "AD-KOTAKB"
        )!!

        assertEquals("debit", parsed.kind)
        assertEquals(150.00, parsed.amount, 0.001)
        // The merchant must stop at "from", not run to the end of the message.
        assertEquals("Big Bazaar", parsed.merchant)
        assertEquals("Kotak Bank", parsed.bankOrUpi)
    }

    // ---- wallets --------------------------------------------------------------

    @Test
    fun `phonepe notification credit`() {
        val parsed = parser.parse("You received Rs 250 from Rahul Sharma", "com.phonepe.app")!!

        assertEquals("credit", parsed.kind)
        assertEquals(250.00, parsed.amount, 0.001)
        assertEquals("Rahul Sharma", parsed.merchant)
        assertEquals("PhonePe", parsed.bankOrUpi)
    }

    @Test
    fun `google pay notification debit`() {
        val parsed = parser.parse(
            "You paid Rs 99 to Netflix",
            "com.google.android.apps.nbu.paisa.user"
        )!!

        assertEquals("debit", parsed.kind)
        assertEquals(99.00, parsed.amount, 0.001)
        assertEquals("Netflix", parsed.merchant)
        assertEquals("Google Pay", parsed.bankOrUpi)
    }

    @Test
    fun `google pay paid you notification is a credit`() {
        val parsed = parser.parse(
            "Mr SUNIL CHANDRA BARMAN paid you ₹50.00 Tap to view",
            "com.google.android.apps.nbu.paisa.user"
        )!!

        assertEquals("credit", parsed.kind)
        assertEquals(50.00, parsed.amount, 0.001)
        assertEquals("Mr SUNIL CHANDRA BARMAN", parsed.merchant)
    }

    @Test
    fun `recipient sent you notification is a credit`() {
        val parsed = parser.parse("Rahul sent you Rs 500", "com.phonepe.app")!!

        assertEquals("credit", parsed.kind)
        assertEquals(500.00, parsed.amount, 0.001)
    }

    @Test
    fun `sender sent to another person remains a debit`() {
        val parsed = parser.parse("You sent Rs 500 to Rahul", "com.phonepe.app")!!

        assertEquals("debit", parsed.kind)
        assertEquals(500.00, parsed.amount, 0.001)
    }

    @Test
    fun `unicode digits are normalized before amount extraction`() {
        val parsed = parser.parse("You received ₹५०० from Rahul", "com.phonepe.app")!!

        assertEquals("credit", parsed.kind)
        assertEquals(500.00, parsed.amount, 0.001)
    }

    @Test
    fun `bank app credit merchant stops before amount clause`() {
        val parsed = parser.parse(
            "₹50.00 received from Mr SUNIL CHANDRA BARMAN Amount credited to XX8521. Check out details.",
            "com.kotak811mobilebankingapp.instantsavingsupiscanandpayrecharge"
        )!!

        assertEquals("credit", parsed.kind)
        assertEquals(50.00, parsed.amount, 0.001)
        assertEquals("Mr SUNIL CHANDRA BARMAN", parsed.merchant)
        assertEquals("XX8521", parsed.account)
    }

    @Test
    fun `paytm debit`() {
        val parsed = parser.parse("Rs.500 paid to Paytm Recharge", "net.one97.paytm")!!

        assertEquals("debit", parsed.kind)
        assertEquals(500.00, parsed.amount, 0.001)
        assertEquals("Paytm", parsed.bankOrUpi)
    }

    @Test
    fun `amazon pay is identified from text when no package is given`() {
        val parsed = parser.parse("Rs 1200 debited from your Amazon Pay balance", null)!!

        assertEquals("debit", parsed.kind)
        assertEquals(1200.00, parsed.amount, 0.001)
        assertEquals("Amazon Pay", parsed.bankOrUpi)
    }

    // ---- currency ------------------------------------------------------------

    @Test
    fun `foreign currency alerts are parsed and labelled`() {
        val parsed = parser.parse("USD 25.00 debited from your card at APPLE.COM", null)!!

        assertEquals("debit", parsed.kind)
        assertEquals(25.00, parsed.amount, 0.001)
        assertEquals("USD", parsed.currency)
        assertTrue(parsed.merchant!!.contains("APPLE"))
    }

    // ---- skip rules ----------------------------------------------------------

    @Test
    fun `otp messages are skipped`() {
        assertNull(parser.parse("123456 is your OTP for txn of Rs 500. Do not share.", "AD-SBIINB"))
        assertEquals(
            "setup",
            parser.skipCategory("123456 is your OTP for txn of Rs 500. Do not share.")
        )
    }

    @Test
    fun `due reminders are skipped`() {
        val text = "Your loan EMI of Rs 5000 is due on 05-06-24. Pay now to avoid charges."
        assertNull(parser.parse(text, "AD-ICICIB"))
        assertEquals("due_reminder", parser.skipCategory(text))
    }

    @Test
    fun `promotional offers are skipped`() {
        val text = "Get up to Rs 500 cashback on your next recharge. Offer valid till 30-06"
        assertNull(parser.parse(text, "AD-AXISBK"))
        assertEquals("promo", parser.skipCategory(text))
    }

    @Test
    fun `declined transactions are skipped`() {
        val text = "Your transaction of Rs 500 was declined due to insufficient funds"
        assertNull(parser.parse(text, "AD-HDFCBK"))
        assertEquals("declined", parser.skipCategory(text))
    }

    @Test
    fun `collect requests are skipped because no money moved`() {
        val text = "Rahul has requested Rs 500 from you on PhonePe"
        assertNull(parser.parse(text, "com.phonepe.app"))
        assertEquals("request", parser.skipCategory(text))
    }

    @Test
    fun `delivery updates are skipped`() {
        val text = "Your order worth Rs 900 has been shipped and will be delivered on 20-05"
        assertNull(parser.parse(text, "AD-AMZNIN"))
        assertEquals("delivery", parser.skipCategory(text))
    }

    // ---- non-payments and unknowns -------------------------------------------

    @Test
    fun `balance-only message without a direction is not a payment`() {
        assertNull(parser.parse("Your account balance is Rs 1,000.00 as on 12-05-24", "AD-HDFCBK"))
    }

    @Test
    fun `generic transaction label does not create a debit`() {
        assertNull(parser.parse("Txn status: Rs 50.00, tap to view", "com.google.android.apps.nbu.paisa.user"))
    }

    @Test
    fun `unknown notification without a configured source is rejected`() {
        assertTrue(
            !parser.isLikelyNotificationSource(
                "com.example.battery",
                "Payment status Rs 50.00"
            )
        )
        assertTrue(
            parser.isLikelyNotificationSource(
                "com.example.bank",
                "Kotak Bank received Rs 50.00"
            )
        )
    }

    @Test
    fun `unknown sender does not invent a bank`() {
        val parsed = parser.parse("Rs 100 debited from A/c XX1234", "AD-UNKNOWNXX")!!
        assertNull(parsed.bankOrUpi)
    }

    @Test
    fun `blank input is rejected`() {
        assertNull(parser.parse("   ", null))
    }
}
