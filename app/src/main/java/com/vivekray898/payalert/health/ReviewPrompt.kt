package com.vivekray898.payalert.health

import android.content.Context
import android.content.SharedPreferences

/** What the user chose when the rating prompt was last shown. */
enum class ReviewOutcome {
    /** They went to the store listing — never ask again. */
    RATED,

    /** They went away — ask again after the cooldown. */
    LATER,

    /** They said no — never ask again. */
    DISMISSED
}

data class ReviewPromptState(
    val rated: Boolean = false,
    val dismissed: Boolean = false,
    val promptCount: Int = 0,
    val lastPromptedAt: Long = 0L
)

/**
 * The reference app prompts for a Play rating once the user has seen the app work.
 * Play Core's In-App Review flow needs Google Play Services on a Play-installed device,
 * so this is the same *behaviour* — ask once, at a sensible moment, and never nag —
 * implemented as a local dialog that opens the store listing.
 */
object ReviewPromptPolicy {

    /** Do not ask before the app has demonstrably done its job. */
    const val MIN_PAYMENTS_SEEN = 5

    /** Never ask twice within a month. */
    const val COOLDOWN_MILLIS = 30L * 24 * 60 * 60 * 1000

    fun shouldPrompt(state: ReviewPromptState, paymentsSeen: Int, now: Long): Boolean {
        if (state.rated || state.dismissed) return false
        if (paymentsSeen < MIN_PAYMENTS_SEEN) return false
        return now - state.lastPromptedAt >= COOLDOWN_MILLIS
    }

    fun apply(state: ReviewPromptState, outcome: ReviewOutcome, now: Long): ReviewPromptState =
        when (outcome) {
            ReviewOutcome.RATED -> state.copy(
                rated = true,
                promptCount = state.promptCount + 1,
                lastPromptedAt = now
            )

            ReviewOutcome.DISMISSED -> state.copy(
                dismissed = true,
                promptCount = state.promptCount + 1,
                lastPromptedAt = now
            )

            ReviewOutcome.LATER -> state.copy(
                promptCount = state.promptCount + 1,
                lastPromptedAt = now
            )
        }
}

/** Persists [ReviewPromptState] so the prompt is not shown again after a restart. */
class ReviewPromptStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): ReviewPromptState = ReviewPromptState(
        rated = prefs.getBoolean(KEY_RATED, false),
        dismissed = prefs.getBoolean(KEY_DISMISSED, false),
        promptCount = prefs.getInt(KEY_COUNT, 0),
        lastPromptedAt = prefs.getLong(KEY_LAST, 0L)
    )

    fun save(state: ReviewPromptState) {
        prefs.edit()
            .putBoolean(KEY_RATED, state.rated)
            .putBoolean(KEY_DISMISSED, state.dismissed)
            .putInt(KEY_COUNT, state.promptCount)
            .putLong(KEY_LAST, state.lastPromptedAt)
            .apply()
    }

    /** Used by the settings screen and tests to start from a clean slate. */
    fun reset() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "payalert_review"
        private const val KEY_RATED = "rated"
        private const val KEY_DISMISSED = "dismissed"
        private const val KEY_COUNT = "prompt_count"
        private const val KEY_LAST = "last_prompted_at"
    }
}
