package com.vivekray898.payalert.ui.nav

/** Bottom-navigation destinations, mirroring the reference app's main tabs
 * (home / transactions / cashbook / passbook / settings). Diagnostics, analytics, help,
 * terms and the QR utility are reachable from within those tabs.
 *
 * Each tab carries exactly one label — the bar renders an icon plus that label, so there is
 * no second abbreviation to sit above it. */
enum class Tab(val label: String) {
    HOME("Home"),
    TRANSACTIONS("History"),
    CASHBOOK("Cashbook"),
    PASSBOOK("Upcoming"),
    SETTINGS("Settings");

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
