package com.vivekray898.payalert.ui.transactions

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vivekray898.payalert.AppGraph
import com.vivekray898.payalert.analytics.AnalyticsCalculator
import com.vivekray898.payalert.data.PaymentEvent
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

/** Date-range presets offered by the history filter. */
enum class HistoryRange(val label: String, val days: Int) {
    ALL_TIME("All time", 0),
    LAST_7("7 days", 7),
    LAST_30("30 days", 30),
    THIS_MONTH("This month", -1)
}

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppGraph.get(application).database.paymentEventDao()

    private val _kind = MutableStateFlow<String?>(null)
    val kind: StateFlow<String?> = _kind.asStateFlow()

    private val _term = MutableStateFlow("")
    val term: StateFlow<String> = _term.asStateFlow()

    private val _account = MutableStateFlow<String?>(null)
    val account: StateFlow<String?> = _account.asStateFlow()

    private val _range = MutableStateFlow(HistoryRange.ALL_TIME)
    val range: StateFlow<HistoryRange> = _range.asStateFlow()

    val accounts: StateFlow<List<String>> = dao.observeAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private data class Filters(
        val kind: String?,
        val term: String,
        val account: String?,
        val from: Long,
        val to: Long
    )

    private val filters = combine(_kind, _term, _account, _range) { kind, term, account, range ->
        val bounds = boundsFor(range)
        Filters(kind, term.trim(), account, bounds.first, bounds.last)
    }

    val events: StateFlow<List<PaymentEvent>> = filters
        .flatMapLatest { filter ->
            dao.observeFiltered(filter.kind, filter.account, filter.term, filter.from, filter.to)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Totals follow the active filter, so the header always matches the list beneath it. */
    val filteredTotals: StateFlow<Pair<Double, Double>> = events
        .map { list ->
            list.filter { it.kind == "credit" }.sumOf { it.amount } to
                list.filter { it.kind == "debit" }.sumOf { it.amount }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0 to 0.0)

    fun setKind(kind: String?) {
        _kind.value = kind
    }

    fun setTerm(term: String) {
        _term.value = term
    }

    fun setAccount(account: String?) {
        _account.value = account
    }

    fun setRange(range: HistoryRange) {
        _range.value = range
    }

    fun clearFilters() {
        _kind.value = null
        _account.value = null
        _term.value = ""
        _range.value = HistoryRange.ALL_TIME
    }

    /** Current filtered rows, read fresh for an export. */
    fun snapshot(onReady: (List<PaymentEvent>) -> Unit) {
        val filter = _kind.value
        val account = _account.value
        val term = _term.value.trim()
        val bounds = boundsFor(_range.value)
        viewModelScope.launch(Dispatchers.IO) {
            onReady(dao.filtered(filter, account, term, bounds.first, bounds.last))
        }
    }

    private fun boundsFor(range: HistoryRange): LongRange = when (range) {
        HistoryRange.ALL_TIME -> 0L..Long.MAX_VALUE
        HistoryRange.THIS_MONTH -> AnalyticsCalculator.rangeForThisMonth()
        else -> AnalyticsCalculator.rangeForDays(range.days)
    }
}
