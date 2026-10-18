package com.vivekray898.payalert.ui.nav

/** The four primary dashboard destinations. */
enum class Tab(val label: String) {
    HOME("Home"),
    PASSBOOK("Passbook"),
    QR("QR"),
    SETTINGS("Settings");

    companion object {
        val all: List<Tab> = entries.toList()
    }
}

/** Secondary destinations pushed on top of the tab content. */
sealed interface Overlay {
    data object Analytics : Overlay
    data object Cashbook : Overlay
    data object Diagnostics : Overlay
    data object Faq : Overlay
    data object Terms : Overlay
    data object Qr : Overlay
    data object Backup : Overlay
    data object Team : Overlay
    data object Upcoming : Overlay
}
