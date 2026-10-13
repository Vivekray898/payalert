package com.vivekray898.payalert.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.vivekray898.payalert.AppGraph
import com.vivekray898.payalert.health.ReviewPromptStore
import com.vivekray898.payalert.health.ServiceHealth
import com.vivekray898.payalert.services.PaymentMonitorService
import com.vivekray898.payalert.ui.review.ReviewPromptHost
import com.vivekray898.payalert.ui.theme.PayAlertTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var reviewStore: ReviewPromptStore

    /** Non-null while detection is degraded; rendered as a banner over the whole app. */
    private var healthNotice by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val graph = AppGraph.get(this)
        reviewStore = ReviewPromptStore(this)
        // Re-assert the monitor whenever the user opens the app.
        if (graph.settings.state.value.monitoringEnabled) {
            PaymentMonitorService.start(this)
        }

        setContent {
            PayAlertTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    PayAlertApp(
                        healthNotice = healthNotice,
                        onHealthNoticeAction = { openHealthSettings() }
                    )
                    ReviewPromptHost(store = reviewStore)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        runHealthCheck()
    }

    /**
     * The reference app calls `startServiceHealthChecks` on launch; this is the same idea.
     * Inspect, repair what can be repaired silently, inspect again, and tell the user only
     * about what is still broken.
     */
    private fun runHealthCheck() {
        lifecycleScope.launch {
            val graph = AppGraph.get(this@MainActivity)
            val before = ServiceHealth.evaluate(ServiceHealth.snapshot(this@MainActivity, graph))
            val actions = ServiceHealth.repair(this@MainActivity, graph, before.repairs)
            val after = ServiceHealth.evaluate(ServiceHealth.snapshot(this@MainActivity, graph))

            healthNotice = when {
                after.blockingIssues.isNotEmpty() ->
                    "Detection needs attention: " +
                        after.blockingIssues.joinToString(", ") { it.name.lowercase() }

                actions.isNotEmpty() -> "Detection repaired on launch: " + actions.joinToString(", ")

                else -> null
            }
        }
    }

    private fun openHealthSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }
}
