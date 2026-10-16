package com.vivekray898.payalert.onboarding

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.net.Uri
import android.Manifest
import com.vivekray898.payalert.ui.PermissionStatus
import com.vivekray898.payalert.ui.onboarding.OnboardingActivity

/**
 * The single place that knows whether the first-run wizard has been completed and
 * whether each of the four onboarding steps is currently satisfied. Kept separate
 * from the UI so the state machine is unit-testable without Android.
 */
class OnboardingRepository(context: Context) {

    private val ctx: Context = context.applicationContext
    private val prefs = ctx
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var onboardingComplete: Boolean
        get() = prefs.getBoolean(KEY_COMPLETE, false)
        private set(value) {
            prefs.edit().putBoolean(KEY_COMPLETE, value).apply()
        }

    fun markComplete() { onboardingComplete = true }

    fun reset() { onboardingComplete = false }

    /** Snapshot of the four steps, read live from the system. */
    fun snapshot(): OnboardingSnapshot = OnboardingSnapshot(
        postNotifications = PermissionStatus.hasPostNotifications(ctx),
        sms = PermissionStatus.hasReceiveSms(ctx),
        notificationAccess = PermissionStatus.hasNotificationAccess(ctx),
        batteryExempt = PermissionStatus.isIgnoringBatteryOptimizations(ctx)
    )

    /**
     * Re-entry point from Settings → Permissions. Tapping a row starts the wizard at
     * the corresponding step; tapping "Open setup wizard" starts from the first step.
     */
    fun startOnboarding(context: Context) {
        context.startActivity(Intent(context, OnboardingActivity::class.java))
    }

    fun startStep(context: Context, step: OnboardingStep) {
        context.startActivity(
            Intent(context, OnboardingActivity::class.java).apply {
                putExtra(EXTRA_STEP, step.name)
            }
        )
    }

    companion object {
        private const val PREFS_NAME = "payalert_onboarding"
        private const val KEY_COMPLETE = "onboarding_complete"
        const val EXTRA_STEP = "onboarding_step"
    }
}

/** The four onboarding steps, in the order the wizard walks them. */
enum class OnboardingStep {
    POST_NOTIFICATIONS,
    SMS,
    NOTIFICATION_ACCESS,
    BATTERY
}

/** Live state of every onboarding step, read from the system. */
data class OnboardingSnapshot(
    val postNotifications: Boolean,
    val sms: Boolean,
    val notificationAccess: Boolean,
    val batteryExempt: Boolean
) {
    fun get(step: OnboardingStep): Boolean = when (step) {
        OnboardingStep.POST_NOTIFICATIONS -> postNotifications
        OnboardingStep.SMS -> sms
        OnboardingStep.NOTIFICATION_ACCESS -> notificationAccess
        OnboardingStep.BATTERY -> batteryExempt
    }
}