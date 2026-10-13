package com.vivekray898.payalert.upi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpiLinkTest {

    @Test
    fun `builds a collect link without an amount when none is given`() {
        val link = UpiLink.build("vivek@okhdfcbank", "Vivek Ray")
        assertTrue(link.startsWith("upi://pay?pa=vivek%40okhdfcbank&pn=Vivek%20Ray&cu=INR"))
        assertFalse(link.contains("&am="))
    }

    @Test
    fun `includes a two-decimal amount when one is given`() {
        val link = UpiLink.build("vivek@okhdfcbank", "Vivek Ray", amount = 1234.5)
        assertTrue(link.contains("&am=1234.50"))
    }

    @Test
    fun `ignores zero and negative amounts`() {
        assertFalse(UpiLink.build("a@b", "A", amount = 0.0).contains("&am="))
        assertFalse(UpiLink.build("a@b", "A", amount = -5.0).contains("&am="))
    }

    @Test
    fun `falls back to the upi id when no payee name is given`() {
        val link = UpiLink.build("  a@b  ", "   ")
        assertEquals("upi://pay?pa=a%40b&pn=a%40b&cu=INR", link)
    }

    @Test
    fun `encodes spaces as percent-20 rather than plus`() {
        val link = UpiLink.build("a@b", "A B", note = "chai and samosa")
        assertTrue(link.contains("pn=A%20B"))
        assertTrue(link.contains("tn=chai%20and%20samosa"))
        assertFalse(link.contains("+"))
    }

    @Test
    fun `omits a blank note`() {
        assertFalse(UpiLink.build("a@b", "A", note = "   ").contains("&tn="))
    }
}
