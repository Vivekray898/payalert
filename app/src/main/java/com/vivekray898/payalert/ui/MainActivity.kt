package com.vivekray898.payalert.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.vivekray898.payalert.AppGraph
import com.vivekray898.payalert.health.HealthRepair
import com.vivekray898.payalert.health.ReviewPromptStore
import com.vivekray898.payalert.health.ServiceHealth
import com.vivekray898.payalert.onboarding.OnboardingRepository
import com.vivekray898.payalert.relay.EmployeeRealtimeService
import com.vivekray898.payalert.role.LocalDeviceRole
import com.vivekray898.payalert.services.PaymentMonitorService
import com.vivekray898.payalert.ui.onboarding.OnboardingScreen
import com.vivekray898.payalert.ui.review.ReviewPromptHost
import com.vivekray898.payalert.ui.theme.PayAlertTheme
import com.vivekray898.payalert.ui.team.TeamAuthViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {

    private lateinit var reviewStore: ReviewPromptStore
    private lateinit var onboardingRepo: OnboardingRepository

    /** Non-null while a required detection path is degraded; rendered in the app's bottom slot. */
    private var healthNotice by mutableStateOf<String?>(null)
    /** Non-null while the onboarding wizard should be shown instead of the main app. */
    private var showOnboarding by mutableStateOf(false)
    private val teamAuth: TeamAuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val graph = AppGraph.get(this)
        reviewStore = ReviewPromptStore(this)
        onboardingRepo = OnboardingRepository(this)
        val role = graph.settings.localDeviceRoleState.currentRole
        // Re-assert the monitor whenever the user opens the app.
        if (graph.settings.state.value.monitoringEnabled && role != LocalDeviceRole.EMPLOYEE) {
            PaymentMonitorService.start(this)
        }
        // Start the Realtime service for employee devices so payments arrive immediately.
        if (role == LocalDeviceRole.EMPLOYEE && graph.isTransportConfigured) {
            EmployeeRealtimeService.start(this)
        }
        // Expire stale received announcements, resolve the pairing in the background, and make sure
        // the relay loop and its cleanup job are running even if the team screen is never opened.
        graph.onAppOpen()

        setContent {
            PayAlertTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    val teamAuthReturn by teamAuth.returnCount.collectAsState()
                    if (showOnboarding) {
                        OnboardingScreen(
                            repository = onboardingRepo,
                            onComplete = { showOnboarding = false }
                        )
                    } else {
                        PayAlertApp(
                            healthNotice = healthNotice,
                            onHealthNoticeAction = { openHealthSettings() },
                            teamAuthReturn = teamAuthReturn
                        )
                        ReviewPromptHost(store = reviewStore)
                    }
                }
            }
        }
        if (savedInstanceState == null) {
            showOnboarding = !onboardingRepo.onboardingComplete
            handleTeamCallback(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTeamCallback(intent)
    }

    private fun handleTeamCallback(incoming: Intent) {
        val uri = incoming.data ?: return
        if (uri.scheme != "com.vivekray898.payalert" || uri.host != "auth" || uri.path != "/callback") return
        // Remove the one-time callback from the Activity's intent before any recreation.
        val callback = uri.toString()
        incoming.data = null
        teamAuth.complete(callback)
    }

    override fun onStart() {
        super.onStart()
        runHealthCheck()
        // Re-start the Realtime service if this is an employee device (handles rotation/recreation)
        val graph = AppGraph.get(this)
        if (graph.settings.localDeviceRoleState.currentRole == LocalDeviceRole.EMPLOYEE) {
            EmployeeRealtimeService.start(this)
        }
    }

    /**
     * The reference app calls `startServiceHealthChecks` on launch; this is the same idea.
     * Inspect, repair what can be repaired silently, inspect again, and tell the user only
     * about what is still broken.
     *
     * `requestRebind` is asynchronous: the system binds the listener and then calls
     * `onListenerConnected`, which sets [AppGraph.setListenerConnected]. Wait for that state
     * transition before deciding whether the listener is really broken.
     */
    private fun runHealthCheck() {
        lifecycleScope.launch {
            val graph = AppGraph.get(this@MainActivity)
            val before = ServiceHealth.evaluate(ServiceHealth.snapshot(this@MainActivity, graph))
            ServiceHealth.repair(this@MainActivity, graph, before.repairs)
            if (HealthRepair.REBIND_LISTENER in before.repairs) {
                withTimeoutOrNull(LISTENER_REBIND_TIMEOUT_MS) {
                    graph.listenerConnected.first { it }
                }
            }
            val after = ServiceHealth.evaluate(ServiceHealth.snapshot(this@MainActivity, graph))
            healthNotice = after.blockingIssues.takeIf { it.isNotEmpty() }?.let { issues ->
                "Detection needs attention: " + issues.joinToString(", ") { it.name.lowercase() }
            }
        }
    }

    private fun openHealthSettings() {
        val needsSms = healthNotice?.contains("sms permission") == true
        if (needsSms) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            }
        } else {
            runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
    }

    private companion object {
        private const val LISTENER_REBIND_TIMEOUT_MS = 2_000L
    }
}
