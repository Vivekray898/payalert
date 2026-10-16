package com.vivekray898.payalert.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vivekray898.payalert.R
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
import com.vivekray898.payalert.ui.team.TeamScreen

@Composable
fun PayAlertApp(
    healthNotice: String? = null,
    onHealthNoticeAction: (() -> Unit)? = null,
    teamAuthReturn: Int = 0,
    pairing: PairingViewModel = viewModel()
) {
    var tabIndex by rememberSaveable { mutableStateOf(0) }
    var overlayName by rememberSaveable { mutableStateOf<String?>(null) }
    val overlay = overlayName?.let { OverlayRegistry.byName(it) }
    val role by pairing.role.collectAsState()
    val relay by pairing.relay.collectAsState()
    // Only a state the user can act on becomes a banner; a failed delivery retries on its own.
    val pairingNotice = if (relay.needsRepair) {
        "Payments from your owner can't be read on this device. Re-pair to keep receiving them."
    } else {
        null
    }

    LaunchedEffect(teamAuthReturn) {
        if (teamAuthReturn > 0) overlayName = OverlayRegistry.nameOf(Overlay.Team)
    }

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
                        icon = {
                            Icon(
                                painter = painterResource(id = tabIcon(tab)),
                                contentDescription = null
                            )
                        },
                        label = { Text(tab.label) },
                        // The indicator and the selected label both derive from the icon's
                        // teal, so the pill never reads as an unrelated pale green.
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.tertiary,
                            selectedTextColor = MaterialTheme.colorScheme.tertiary,
                            indicatorColor = MaterialTheme.colorScheme.tertiaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                // The outer Scaffold owns the system-bar placement for tab content. Mark those
                // insets consumed before a tab with its own TopAppBar Scaffold is composed, so
                // the status-bar inset is not applied a second time.
                .consumeWindowInsets(padding)
                // Reserve room for the notices so they never hide the last card on a screen
                // (or the export action at the foot of Analytics).
                .padding(bottom = when {
                    healthNotice != null && pairingNotice != null -> 128.dp
                    healthNotice != null || pairingNotice != null -> 72.dp
                    else -> 0.dp
                })
        ) {
            when (Tab.all[tabIndex]) {
                Tab.HOME -> HomeScreen(
                    onOpenTransactions = { tabIndex = Tab.TRANSACTIONS.ordinal },
                    onOpenQr = { open(Overlay.Qr) },
                    role = role.badgeValue
                )
                Tab.TRANSACTIONS -> TransactionsScreen(
                    onOpenAnalytics = { open(Overlay.Analytics) },
                    role = role.badgeValue
                )
                Tab.CASHBOOK -> CashbookScreen()
                Tab.PASSBOOK -> PassbookScreen()
                Tab.SETTINGS -> SettingsScreen(
                    onOpenDiagnostics = { open(Overlay.Diagnostics) },
                    onOpenFaq = { open(Overlay.Faq) },
                    onOpenTerms = { open(Overlay.Terms) },
                    onOpenBackup = { open(Overlay.Backup) },
                    onOpenTeam = { open(Overlay.Team) },
                    role = role,
                    relay = relay
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
                        Overlay.Team -> TeamScreen(onBack = ::close)
                    }
                }
            }

            // Drawn last so a degraded-detection notice is visible over every tab and
            // every secondary screen, not just the one that happened to be open. Anchored to
            // the bottom of the content area so it can never cover a screen's top app bar
            // (which is where History keeps Analytics / Export / Clear filters).
            healthNotice?.let { notice ->
                NoticeBanner(
                    text = notice,
                    actionLabel = "Fix",
                    onAction = onHealthNoticeAction,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            // Sits above the detection notice: an unreadable owner announcement is a pairing
            // problem, and the fix is to re-pair from the team screen.
            pairingNotice?.let { notice ->
                NoticeBanner(
                    text = notice,
                    actionLabel = "Re-pair",
                    onAction = { open(Overlay.Team) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = if (healthNotice != null) 56.dp else 0.dp)
                )
            }
        }
    }
}

/** One bottom banner. Shared so the detection and pairing notices cannot drift apart in style. */
@Composable
private fun NoticeBanner(
    text: String,
    actionLabel: String,
    onAction: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
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
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            onAction?.let { action ->
                TextButton(onClick = action) { Text(actionLabel) }
            }
        }
    }
}

/** Maps a tab to its icon. Kept here so the nav enum stays free of Android resource ids. */
private fun tabIcon(tab: Tab): Int = when (tab) {
    Tab.HOME -> R.drawable.ic_home
    Tab.TRANSACTIONS -> R.drawable.ic_history
    Tab.CASHBOOK -> R.drawable.ic_cashbook
    Tab.PASSBOOK -> R.drawable.ic_upcoming
    Tab.SETTINGS -> R.drawable.ic_settings
}

/** Overlay values must survive process recreation, so they are stored by name. */
private object OverlayRegistry {
    private val all = listOf(
        Overlay.Analytics,
        Overlay.Diagnostics,
        Overlay.Faq,
        Overlay.Terms,
        Overlay.Qr,
        Overlay.Backup,
        Overlay.Team
    )

    fun nameOf(overlay: Overlay): String = overlay::class.simpleName ?: "Unknown"

    fun byName(name: String): Overlay? = all.firstOrNull { nameOf(it) == name }
}
