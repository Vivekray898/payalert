package com.vivekray898.payalert.ui.cashbook

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vivekray898.payalert.AppGraph
import com.vivekray898.payalert.analytics.AnalyticsCalculator
import com.vivekray898.payalert.cashbook.CashbookReport
import com.vivekray898.payalert.cashbook.CashbookReportCalculator
import com.vivekray898.payalert.data.CashbookEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Date ranges offered by the cashbook report filter. */
enum class CashbookRange(val label: String, val days: Int) {
    ALL_TIME("All time", 0),
    LAST_30("30 days", 30),
    LAST_90("90 days", 90),
    THIS_MONTH("This month", -1)
}

/** Create, edit and delete cashbook entries, with range totals and a category report. */
@OptIn(ExperimentalCoroutinesApi::class)
class CashbookViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppGraph.get(application).database.cashbookDao()

    private val _range = MutableStateFlow(CashbookRange.ALL_TIME)
    val range: StateFlow<CashbookRange> = _range.asStateFlow()

    private val _category = MutableStateFlow<String?>(null)
    val category: StateFlow<String?> = _category.asStateFlow()

    val categories: StateFlow<List<String>> = dao.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val inRange: StateFlow<List<CashbookEntry>> = _range
        .flatMapLatest { preset ->
            val bounds = boundsFor(preset)
            dao.observeInRange(bounds.first, bounds.last)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Entries shown in the list, with the category filter applied. */
    val entries: StateFlow<List<CashbookEntry>> =
        combine(inRange, _category) { list, filter ->
            if (filter == null) list else list.filter { it.category == filter }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Totals for the selected range (not the category filter), matching the reference's report. */
    val report: StateFlow<CashbookReport> = inRange
        .map { CashbookReportCalculator.build(it) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            CashbookReportCalculator.build(emptyList())
        )

    fun setRange(preset: CashbookRange) {
        _range.value = preset
    }

    fun setCategory(value: String?) {
        _category.value = value
    }

    fun save(
        id: Long?,
        label: String,
        kind: String,
        amount: Double,
        category: String?,
        note: String?,
        occurredAt: Long
    ) {
        val cleanLabel = label.trim().ifBlank { return }
        if (amount <= 0.0) return
        viewModelScope.launch {
            if (id == null) {
                val now = System.currentTimeMillis()
                dao.insert(
                    CashbookEntry(
                        label = cleanLabel,
                        kind = kind,
                        amount = amount,
                        category = category?.trim()?.ifBlank { null },
                        note = note?.trim()?.ifBlank { null },
                        occurredAt = occurredAt,
                        createdAt = now
                    )
                )
            } else {
                val existing = dao.byId(id) ?: return@launch
                dao.update(
                    existing.copy(
                        label = cleanLabel,
                        kind = kind,
                        amount = amount,
                        category = category?.trim()?.ifBlank { null },
                        note = note?.trim()?.ifBlank { null },
                        occurredAt = occurredAt
                    )
                )
            }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { dao.deleteById(id) }
    }

    /** Entries in the selected range, read fresh for an export. */
    fun snapshot(onReady: (List<CashbookEntry>) -> Unit) {
        val bounds = boundsFor(_range.value)
        viewModelScope.launch(Dispatchers.IO) {
            onReady(dao.inRange(bounds.first, bounds.last))
        }
    }

    private fun boundsFor(preset: CashbookRange): LongRange = when (preset) {
        CashbookRange.ALL_TIME -> 0L..Long.MAX_VALUE
        CashbookRange.THIS_MONTH -> AnalyticsCalculator.rangeForThisMonth()
        else -> AnalyticsCalculator.rangeForDays(preset.days)
    }
}
