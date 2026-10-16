package com.vivekray898.payalert.onboarding

/** One onboarding step, owned by the wizard, independent of Android. */
enum class StepState { PENDING, GRANTED, SKIPPED }

/** Pure decision logic extracted from the onboarding UI. */
object PermissionState {

    /** Returns the current lifecycle state of a step given the user's choices. */
    fun onboardingStepState(granted: Boolean, skipped: Boolean): StepState = when {
        granted -> StepState.GRANTED
        skipped -> StepState.SKIPPED
        else -> StepState.PENDING
    }

    /**
     * Whether the wizard can be finished. The first three steps must be either granted
     * or skipped; the fourth (battery) is optional and may be skipped.
     */
    fun canFinishWizard(states: List<StepState>): Boolean {
        if (states.size < 3) return false
        return states.subList(0, 3).all { s -> s == StepState.GRANTED || s == StepState.SKIPPED }
    }

    /**
     * When the current step has just turned GRANTED, auto-advance to the next index.
     * Otherwise returns null and the user stays on the current step. Returns null at
     * the final step so the wizard never advances beyond the list.
     */
    fun wizardAutoAdvance(currentIndex: Int, states: List<StepState>): Int? {
        if (currentIndex < 0 || currentIndex >= states.size) return null
        if (states[currentIndex] != StepState.GRANTED) return null
        if (currentIndex + 1 >= states.size) return null
        return currentIndex + 1
    }
}