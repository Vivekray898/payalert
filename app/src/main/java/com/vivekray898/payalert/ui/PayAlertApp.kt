package com.vivekray898.payalert.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vivekray898.payalert.ui.analytics.AnalyticsScreen
import com.vivekray898.payalert.ui.backup.BackupScreen
import com.vivekray898.payalert.ui.cashbook.CashbookScreen
import com.vivekray898.payalert.ui.diagnostics.DiagnosticsScreen
import com.vivekray898.payalert.ui.help.FaqScreen
import com.vivekray898.payalert.ui.help.TermsScreen
import com.vivekray898.payalert.ui.home.HomeScreen
import com.vivekray898.payalert.ui.nav.Overlay
import com.vivekray898.payalert.ui.nav.Tab
import com.vivekray898.payalert.ui.passbook.PassbookScreen
import com.vivekray898.payalert.ui.qr.QrScreen
import com.vivekray898.payalert.ui.settings.SettingsScreen
import com.vivekray898.payalert.ui.transactions.TransactionsScreen

@Composable
fun PayAlertApp(
    healthNotice: String? = null,
    onHealthNoticeAction: (() -> Unit)? = null
) {
    var tabIndex by rememberSaveable { mutableStateOf(0) }
    var overlayName by rememberSaveable { mutableStateOf<String?>(null) }
    val overlay = overlayName?.let { OverlayRegistry.byName(it) }

    fun open(target: Overlay) {
        overlayName = OverlayRegistry.nameOf(target)
    }

    fun close() {
        overlayName = null
    }

    if (overlay != null) {
        BackHandler { close() }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.all.forEachIndexed { position, tab ->
                    NavigationBarItem(
                        selected = tabIndex == position,
                        onClick = {
                            // Choosing a tab also leaves any secondary screen that was open.
                            close()
                            tabIndex = position
                        },
                        icon = { Text(tab.short) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        // The tab content stays composed underneath, so scroll position and open dialogs
        // survive a trip into a secondary screen and back.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (Tab.all[tabIndex]) {
                Tab.HOME -> HomeScreen(
                    onOpenTransactions = { tabIndex = Tab.TRANSACTIONS.ordinal },
                    onOpenQr = { open(Overlay.Qr) }
                )
                Tab.TRANSACTIONS -> TransactionsScreen(onOpenAnalytics = { open(Overlay.Analytics) })
                Tab.CASHBOOK -> CashbookScreen()
                Tab.PASSBOOK -> PassbookScreen()
                Tab.SETTINGS -> SettingsScreen(
                    onOpenDiagnostics = { open(Overlay.Diagnostics) },
                    onOpenFaq = { open(Overlay.Faq) },
                    onOpenTerms = { open(Overlay.Terms) },
                    onOpenBackup = { open(Overlay.Backup) }
                )
            }

            if (overlay != null) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (overlay) {
                        Overlay.Analytics -> AnalyticsScreen(onBack = ::close)
                        Overlay.Diagnostics -> DiagnosticsScreen(onBack = ::close)
                        Overlay.Faq -> FaqScreen(onBack = ::close)
                        Overlay.Terms -> TermsScreen(onBack = ::close)
                        Overlay.Qr -> QrScreen(onBack = ::close)
                        Overlay.Backup -> BackupScreen(onBack = ::close)
                    }
                }
            }

            // Drawn last so a degraded-detection notice is visible over every tab and
            // every secondary screen, not just the one that happened to be open.
            healthNotice?.let { notice ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = notice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        onHealthNoticeAction?.let { action ->
                            TextButton(onClick = action) { Text("Fix") }
                        }
                    }
                }
            }
        }
    }
}

/** Overlay values must survive process recreation, so they are stored by name. */
private object OverlayRegistry {
    private val all = listOf(
        Overlay.Analytics,
        Overlay.Diagnostics,
        Overlay.Faq,
        Overlay.Terms,
        Overlay.Qr,
        Overlay.Backup
    )

    fun nameOf(overlay: Overlay): String = overlay::class.simpleName ?: "Unknown"

    fun byName(name: String): Overlay? = all.firstOrNull { nameOf(it) == name }
}
