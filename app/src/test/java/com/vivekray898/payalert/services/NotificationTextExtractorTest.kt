package com.vivekray898.payalert.services

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationTextExtractorTest {

    @Test
    fun `transaction body wins over app title that contains a misleading from name`() {
        val text = NotificationTextExtractor.choose(
            title = "PhonePe Payment from PhonePe",
            text = "BIPUL SAHA paid you Rs 10",
            bigText = "BIPUL SAHA paid you Rs 10",
            subText = null,
            summaryText = null
        )

        assertEquals("BIPUL SAHA paid you Rs 10", text)
    }

    @Test
    fun `big text is preferred when notification only exposes details there`() {
        val text = NotificationTextExtractor.choose(
            title = "PhonePe Payment",
            text = "Tap to view",
            bigText = "Received Rs 10 from BIPUL SAHA",
            subText = null,
            summaryText = null
        )

        assertEquals("Received Rs 10 from BIPUL SAHA", text)
    }

    @Test
    fun `title is a fallback when notification has no body`() {
        val text = NotificationTextExtractor.choose(
            title = "Received Rs 10 from BIPUL SAHA",
            text = null,
            bigText = null,
            subText = null,
            summaryText = null
        )

        assertEquals("Received Rs 10 from BIPUL SAHA", text)
    }

    @Test
    fun `transaction title wins over generic action body when parser accepts it`() {
        val text = NotificationTextExtractor.choose(
            title = "Received Rs 50 from RAHUL SHARMA",
            text = "Tap to view",
            bigText = null,
            subText = null,
            summaryText = null,
            isPaymentCandidate = { candidate -> candidate.startsWith("Received Rs") }
        )

        assertEquals("Received Rs 50 from RAHUL SHARMA", text)
    }

    @Test
    fun `generic body remains the fallback when no field is parseable`() {
        val text = NotificationTextExtractor.choose(
            title = "Google Pay",
            text = "Tap to view",
            bigText = null,
            subText = null,
            summaryText = null,
            isPaymentCandidate = { false }
        )

        assertEquals("Tap to view", text)
    }
}
