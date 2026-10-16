package com.vivekray898.payalert.ui.team

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.vivekray898.payalert.AppGraph

/**
 * Hosts the app-scoped [TeamController].
 *
 * The controller lives in the graph rather than the ViewModel so the encrypted relay can react to
 * pairing changes while no screen is open; the screen simply observes the same state.
 */
class TeamViewModel(application: Application) : AndroidViewModel(application) {
    val controller = AppGraph.get(application).teamController
}
