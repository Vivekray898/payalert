package com.vivekray898.payalert.ui.team

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vivekray898.payalert.AppGraph
import com.vivekray898.payalert.team.TeamController

class TeamViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("team_device", Context.MODE_PRIVATE)
    private val defaultName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(100).ifBlank { "My Android device" }
    val controller = TeamController(
        AppGraph.get(application).team,
        viewModelScope,
        preferences.getString("name", null) ?: defaultName
    ) { name -> preferences.edit().putString("name", name).apply() }
}
