package com.vivekray898.payalert.ui.onboarding

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.vivekray898.payalert.onboarding.OnboardingRepository
import com.vivekray898.payalert.onboarding.OnboardingStep
import com.vivekray898.payalert.ui.theme.PayAlertTheme

/**
 * Full-screen onboarding wizard. Launched from MainActivity on first run and from
 * Settings → Permissions via the "Open setup wizard" button. The wizard walks the
 * four steps one at a time and sets [OnboardingRepository.onboardingComplete] on
 * Finish. Backing out mid-wizard does not set the flag, so the wizard shows again
 * on the next launch — the correct behaviour for a user who abandoned setup.
 */
class OnboardingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = OnboardingRepository(this)
        val requestedStep = intent?.extras?.let {
            it.getString(OnboardingRepository.EXTRA_STEP)
        }?.let { OnboardingStep.valueOf(it) }

        setContent {
            PayAlertTheme {
                OnboardingScreen(
                    repository = repository,
                    requestedStep = requestedStep,
                    onComplete = { finish() }
                )
            }
        }
    }
}