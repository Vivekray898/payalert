package com.vivekray898.payalert.ui.nav

/**
 * Bottom-navigation destinations, mirroring the reference app's main tabs
 * (home / transactions / cashbook / passbook / settings). Diagnostics, analytics, help,
 * terms and the QR utility are reachable from within those tabs.
 */
enum class Tab(val label: String, val short: String) {
    HOME("Home", "Ho"),
    TRANSACTIONS("Transactions", "Tx"),
    CASHBOOK("Cashbook", "Cb"),
    PASSBOOK("Upcoming", "Up"),
    SETTINGS("Settings", "Se");

    companion object {
        val all: List<Tab> = entries.toList()
    }
}

/** Secondary destinations pushed on top of the tab content. */
sealed interface Overlay {
    data object Analytics : Overlay
    data object Diagnostics : Overlay
    data object Faq : Overlay
    data object Terms : Overlay
    data object Qr : Overlay
    data object Backup : Overlay
}
