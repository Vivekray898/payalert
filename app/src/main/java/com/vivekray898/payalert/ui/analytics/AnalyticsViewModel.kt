package com.vivekray898.payalert.ui.analytics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vivekray898.payalert.AppGraph
import com.vivekray898.payalert.analytics.AnalyticsCalculator
import com.vivekray898.payalert.analytics.AnalyticsSummary
import com.vivekray898.payalert.data.PaymentEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Preset ranges, mirroring the reference's analytics filter sheet. */
enum class RangePreset(val label: String, val days: Int) {
    LAST_7("Last 7 days", 7),
    LAST_30("Last 30 days", 30),
    LAST_90("Last 90 days", 90),
    THIS_MONTH("This month", 0)
}

data class AnalyticsState(
    val preset: RangePreset = RangePreset.LAST_30,
    val events: List<PaymentEvent> = emptyList(),
    val summary: AnalyticsSummary? = null
)

class AnalyticsViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppGraph.get(application).database.paymentEventDao()

    private val _state = MutableStateFlow(AnalyticsState())
    val state: StateFlow<AnalyticsState> = _state.asStateFlow()

    init {
        load(RangePreset.LAST_30)
    }

    fun select(preset: RangePreset) = load(preset)

    private fun load(preset: RangePreset) {
        viewModelScope.launch(Dispatchers.IO) {
            val range = if (preset == RangePreset.THIS_MONTH) {
                AnalyticsCalculator.rangeForThisMonth()
            } else {
                AnalyticsCalculator.rangeForDays(preset.days)
            }
            val events = dao.inRange(range.first, range.last)
            _state.value = AnalyticsState(
                preset = preset,
                events = events,
                summary = AnalyticsCalculator.summarize(events, preset.days.coerceAtLeast(31))
            )
        }
    }
}
