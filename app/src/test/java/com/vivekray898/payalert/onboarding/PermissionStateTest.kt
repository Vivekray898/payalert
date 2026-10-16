package com.vivekray898.payalert.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionStateTest {

    @Test
    fun onboardingStepState_grantedTrue_returnsGranted() {
        assertEquals(
            "When granted is true, step state should be GRANTED",
            StepState.GRANTED,
            PermissionState.onboardingStepState(granted = true, skipped = false)
        )
    }

    @Test
    fun onboardingStepState_skippedTrue_returnsSkipped() {
        assertEquals(
            "When skipped is true, step state should be SKIPPED",
            StepState.SKIPPED,
            PermissionState.onboardingStepState(granted = false, skipped = true)
        )
    }

    @Test
    fun onboardingStepState_neitherTrue_returnsPending() {
        assertEquals(
            "When neither granted nor skipped, step state should be PENDING",
            StepState.PENDING,
            PermissionState.onboardingStepState(granted = false, skipped = false)
        )
    }

    @Test
    fun canFinishWizard_allFirstThreeGrantedOrSkipped_returnsTrue() {
        val states = listOf(
            StepState.GRANTED,
            StepState.SKIPPED,
            StepState.GRANTED
        )
        assertEquals(
            "Wizard should be finishable when all first three are GRANTED or SKIPPED",
            true,
            PermissionState.canFinishWizard(states)
        )
    }

    @Test
    fun canFinishWizard_firstPending_returnsFalse() {
        val states = listOf(
            StepState.PENDING,
            StepState.GRANTED,
            StepState.SKIPPED
        )
        assertEquals(
            "Wizard should not be finishable when the first step is still PENDING",
            false,
            PermissionState.canFinishWizard(states)
        )
    }

    @Test
    fun wizardAutoAdvance_currentGrantedAdvances() {
        val states = listOf(
            StepState.GRANTED,
            StepState.GRANTED,
            StepState.PENDING
        )
        assertEquals(
            "Auto-advance should return currentIndex + 1 when the current step is GRANTED",
            1,
            PermissionState.wizardAutoAdvance(0, states)!!
        )
    }

    @Test
    fun wizardAutoAdvance_currentPending_returnsNull() {
        val states = listOf(
            StepState.PENDING,
            StepState.GRANTED,
            StepState.PENDING
        )
        assertEquals(
            "Auto-advance should return null when the current step is PENDING",
            null,
            PermissionState.wizardAutoAdvance(0, states)
        )
    }

    @Test
    fun wizardAutoAdvance_lastStepGranted_returnsNull() {
        // Last step granted should not advance beyond the list.
        val states = listOf(
            StepState.GRANTED,
            StepState.GRANTED,
            StepState.GRANTED
        )
        assertEquals(
            "Auto-advance should return null when already at the final step",
            null,
            PermissionState.wizardAutoAdvance(2, states)
        )
    }
}