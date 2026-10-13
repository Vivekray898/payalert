package com.vivekray898.payalert.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPromptPolicyTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `does not ask before the app has proven itself`() {
        val state = ReviewPromptState()
        assertFalse(
            ReviewPromptPolicy.shouldPrompt(
                state,
                paymentsSeen = ReviewPromptPolicy.MIN_PAYMENTS_SEEN - 1,
                now = now
            )
        )
        assertTrue(
            ReviewPromptPolicy.shouldPrompt(
                state,
                paymentsSeen = ReviewPromptPolicy.MIN_PAYMENTS_SEEN,
                now = now
            )
        )
    }

    @Test
    fun `a rating takes precedence over a later prompt`() {
        val rated = ReviewPromptState(rated = true)
        assertFalse(
            ReviewPromptPolicy.shouldPrompt(rated, paymentsSeen = 100, now = now + 10 * 31L * 24 * 60 * 60 * 1000)
        )
    }

    @Test
    fun `declining is permanent`() {
        val dismissed = ReviewPromptState(dismissed = true)
        assertFalse(
            ReviewPromptPolicy.shouldPrompt(dismissed, paymentsSeen = 100, now = now + 365L * 24 * 60 * 60 * 1000)
        )
    }

    @Test
    fun `deferring suppresses the prompt for the cooldown only`() {
        val deferred = ReviewPromptPolicy.apply(ReviewPromptState(), ReviewOutcome.LATER, now)
        assertEquals(1, deferred.promptCount)
        assertFalse(ReviewPromptPolicy.shouldPrompt(deferred, paymentsSeen = 10, now = now + 1000))
        assertFalse(
            ReviewPromptPolicy.shouldPrompt(
                deferred,
                paymentsSeen = 10,
                now = now + ReviewPromptPolicy.COOLDOWN_MILLIS - 1
            )
        )
        assertTrue(
            ReviewPromptPolicy.shouldPrompt(
                deferred,
                paymentsSeen = 10,
                now = now + ReviewPromptPolicy.COOLDOWN_MILLIS
            )
        )
    }

    @Test
    fun `rating and dismissing are recorded as permanent outcomes`() {
        val rated = ReviewPromptPolicy.apply(ReviewPromptState(), ReviewOutcome.RATED, now)
        assertTrue(rated.rated)
        assertFalse(rated.dismissed)

        val dismissed = ReviewPromptPolicy.apply(ReviewPromptState(), ReviewOutcome.DISMISSED, now)
        assertTrue(dismissed.dismissed)
        assertFalse(dismissed.rated)
    }

    @Test
    fun `only one prompt is outstanding at a time`() {
        val first = ReviewPromptPolicy.apply(ReviewPromptState(), ReviewOutcome.LATER, now)
        assertFalse(ReviewPromptPolicy.shouldPrompt(first, paymentsSeen = 50, now = now + 1))
        assertEquals(1, first.promptCount)
    }
}
