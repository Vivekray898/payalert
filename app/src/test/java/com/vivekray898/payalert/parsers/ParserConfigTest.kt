package com.vivekray898.payalert.parsers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserConfigTest {

    private val config = TestConfig.shipped

    @Test
    fun `schema and coordinator are loaded`() {
        assertEquals(1, config.schemaVersion)
        assertEquals("INR", config.defaultCurrency)
        assertEquals(5, config.coordinator.dedupWindowMinutes)
        assertEquals(5, config.coordinator.amountBucketMinutes)
    }

    @Test
    fun `covers the same bank scope as the reference app`() {
        // The reference ships 42 bank rules (41 named + a catch-all). Scope, not rules,
        // is what we deliberately match.
        assertTrue("expected >= 40 banks, got ${config.banks.size}", config.banks.size >= 40)
        assertNotNull("a catch-all rule must exist", config.catchAllBank)

        val ids = config.banks.map { it.id }.toSet()
        listOf(
            "hdfc", "icici", "sbi", "axis", "kotak", "pnb", "bob", "canara",
            "union", "idbi", "indusind", "yesbank", "federal", "idfcfirst", "rbl"
        ).forEach { assertTrue("missing bank rule: $it", it in ids) }
    }

    @Test
    fun `covers the reference wallet scope`() {
        val ids = config.wallets.map { it.id }.toSet()
        listOf("phonepe", "gpay", "paytm", "amazonpay").forEach {
            assertTrue("missing wallet rule: $it", it in ids)
        }
    }

    @Test
    fun `every configured pattern compiles as a regex`() {
        val patterns = config.patterns.amount + config.patterns.reference +
            config.patterns.account + config.patterns.balance +
            config.merchant.credit + config.merchant.debit +
            listOfNotNull(
                config.merchant.upiNarration.takeIf { it.isNotBlank() },
                config.merchant.vpa.takeIf { it.isNotBlank() }
            )
        assertTrue("no patterns loaded", patterns.isNotEmpty())
        patterns.forEach { Regex(it) }
    }

    @Test
    fun `skip keywords are grouped into six categories`() {
        val categories = config.skip.categories()
        assertEquals(6, categories.size)
        categories.forEach { (name, keywords) ->
            assertTrue("category $name is empty", keywords.isNotEmpty())
        }
        assertTrue(config.skip.categories().any { it.first == "due_reminder" })
    }

    @Test
    fun `currency table includes the default plus foreign currencies`() {
        assertTrue("INR" in config.currency)
        assertTrue(config.currency.size > 1)
    }
}
