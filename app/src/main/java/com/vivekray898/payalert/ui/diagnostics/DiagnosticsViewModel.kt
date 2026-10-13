package com.vivekray898.payalert.ui.diagnostics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vivekray898.payalert.AppGraph
import com.vivekray898.payalert.pipeline.ParseOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DiagnosticsViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = AppGraph.get(application)

    val lastOutcome: StateFlow<ParseOutcome?> = graph.pipeline.lastOutcome
    val listenerConnected: StateFlow<Boolean> = graph.listenerConnected

    val eventCount: StateFlow<Int> = graph.database.paymentEventDao().observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val dedupCount: StateFlow<Int> = graph.database.dedupDao().observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val ruleSetLoaded: Boolean = !graph.parserConfigLoadFailed
    val bankCount: Int = graph.parserConfig.banks.size
    val walletCount: Int = graph.parserConfig.wallets.size
    val senderCount: Int = graph.parserConfig.banks.sumOf { it.senders.size }

    private val _testOutcome = MutableStateFlow<ParseOutcome?>(null)
    val testOutcome: StateFlow<ParseOutcome?> = _testOutcome.asStateFlow()

    /** Runs arbitrary text through the real pipeline (parse -> dedup -> persist -> speak). */
    fun runTest(text: String, source: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _testOutcome.value = graph.pipeline.handle(text, source, "diagnostics")
        }
    }

    fun speak(text: String) {
        if (text.isNotBlank()) graph.tts.speak(text)
    }

    fun clearEvents() {
        viewModelScope.launch { graph.database.paymentEventDao().clearAll() }
    }

    fun clearDedup() {
        viewModelScope.launch { graph.database.dedupDao().clearAll() }
    }

}
