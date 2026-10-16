package com.vivekray898.payalert.ui.team

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vivekray898.payalert.AppGraph
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Keep the one-time PKCE exchange alive through Activity recreation (e.g. rotation). */
class TeamAuthViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableReturnCount = MutableStateFlow(0)
    val returnCount = mutableReturnCount.asStateFlow()
    private var exchange: Job? = null

    fun complete(callback: String) {
        if (exchange?.isActive == true) return
        exchange = viewModelScope.launch {
            AppGraph.get(getApplication()).team.completeSignIn(callback)
            mutableReturnCount.value++
        }
    }
}
