package com.vivekray898.payalert.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vivekray898.payalert.AppGraph
import com.vivekray898.payalert.analytics.AnalyticsCalculator
import com.vivekray898.payalert.data.PaymentEvent
import com.vivekray898.payalert.ui.PermissionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeState(
    val recent: List<PaymentEvent> = emptyList(),
    val monthIn: Double = 0.0,
    val monthOut: Double = 0.0,
    val storedCount: Int = 0,
    val smsPermission: Boolean = false,
    val notificationAccess: Boolean = false,
    val detectSms: Boolean = true,
    val detectNotifications: Boolean = true,
    val localDetectionEnabled: Boolean = true,
    val batteryExempt: Boolean = false,
    val listenerConnected: Boolean = false,
    val parserRuleSet: String = "",
    val parserConfigLoaded: Boolean = true
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = AppGraph.get(application)
    private val tick = MutableStateFlow(0)

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    val monitoring: StateFlow<Boolean> = graph.settings.state
        .let { settings ->
            combine(settings, tick) { snapshot, _ -> snapshot.monitoringEnabled }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
        }

    init {
        viewModelScope.launch {
            graph.database.paymentEventDao().observeAll().collect { events ->
                _state.value = _state.value.copy(
                    recent = events.take(10),
                    monthIn = events.filter { it.kind == "credit" && it.receivedAt >= monthStart() }
                        .sumOf { it.amount },
                    monthOut = events.filter { it.kind == "debit" && it.receivedAt >= monthStart() }
                        .sumOf { it.amount },
                    storedCount = events.size
                )
            }
        }
        refreshEnvironment()
    }

    /** Re-reads the system permission state; call from the UI on resume. */
    fun refresh() {
        tick.value += 1
        refreshEnvironment()
    }

    private fun refreshEnvironment() {
        val context = getApplication<Application>()
        _state.value = _state.value.copy(
            smsPermission = PermissionStatus.hasReceiveSms(context),
            notificationAccess = PermissionStatus.hasNotificationAccess(context),
            detectSms = graph.settings.state.value.detectSms,
            detectNotifications = graph.settings.state.value.detectNotifications,
            localDetectionEnabled = graph.settings.localDeviceRoleState.allowsLocalDetection,
            batteryExempt = PermissionStatus.isIgnoringBatteryOptimizations(context),
            listenerConnected = graph.listenerConnected.value,
            parserRuleSet = graph.parserConfig.banks.size.toString() + " banks / " +
                graph.parserConfig.wallets.size + " wallets / " +
                graph.parserConfig.banks.sumOf { it.senders.size } + " sender ids",
            parserConfigLoaded = !graph.parserConfigLoadFailed
        )
    }

    private fun monthStart(): Long = AnalyticsCalculator.rangeForThisMonth().first
}
