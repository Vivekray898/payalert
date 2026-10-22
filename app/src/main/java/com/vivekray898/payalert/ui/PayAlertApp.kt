package com.vivekray898.payalert.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import com.vivekray898.payalert.role.LocalDeviceRole

@Composable
fun PayAlertApp(
    healthNotice: String? = null,
    onHealthNoticeAction: (() -> Unit)? = null,
    teamAuthReturn: Int = 0,
    pairing: PairingViewModel = viewModel()
) {
    var tabIndex by rememberSaveable { mutableStateOf(0) }
    var overlayName by rememberSaveable { mutableStateOf<String?>(null) }
    var moreExpanded by rememberSaveable { mutableStateOf(false) }
    val overlay = overlayName?.let { OverlayRegistry.byName(it) }
    val selectedTab = Tab.all.getOrNull(tabIndex) ?: Tab.HOME
    val role by pairing.role.collectAsState()
    val relay by pairing.relay.collectAsState()
    // Only a state the user can act on becomes a banner; a failed delivery retries on its own.
    val pairingNotice = if (
        relay.needsRepair &&
            role.role == LocalDeviceRole.EMPLOYEE &&
            relay.paired
    ) {
        "Payments from your owner can't be read on this device. Re-pair to keep receiving them."
    } else {
        null
    }

    LaunchedEffect(teamAuthReturn) {
        if (teamAuthReturn > 0) overlayName = OverlayRegistry.nameOf(Overlay.Team)
    }

    fun open(target: Overlay) {
        moreExpanded = false
        overlayName = OverlayRegistry.nameOf(target)
    }

    fun close() {
        val closingQr = overlay == Overlay.Qr
        overlayName = null
        moreExpanded = false
        // QR is a primary action implemented by the existing overlay. Returning from it lands
        // on Home rather than leaving an empty QR tab selected underneath the closed overlay.
        if (closingQr) tabIndex = Tab.HOME.ordinal
    }

    if (overlay != null) {
        BackHandler { close() }
    }

    Scaffold(
        // Keep the status-bar region in the same blue as the reference header while the
        // padded content area below remains the light dashboard canvas.
        containerColor = MaterialTheme.colorScheme.primary,
        bottomBar = {
            Column {
                // Keep notices beside the navigation bar instead of overlaying the screen. The
                // pairing screen and every scrollable page now get their full measured height.
                pairingNotice?.let { notice ->
                    NoticeBanner(
                        text = notice,
                        actionLabel = "Re-pair",
                        onAction = { open(Overlay.Team) }
                    )
                }
                healthNotice?.let { notice ->
                    NoticeBanner(
                        text = notice,
                        actionLabel = "Fix",
                        onAction = onHealthNoticeAction
                    )
                }
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp
                ) {
                    Tab.all.forEachIndexed { position, tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = {
                                if (tab == Tab.QR) {
                                    close()
                                    tabIndex = position
                                    open(Overlay.Qr)
                                } else {
                                    // Choosing a tab also leaves any secondary screen that was open.
                                    close()
                                    tabIndex = position
                                }
                            },
                            icon = {
                                Icon(
                                    painter = painterResource(id = tabIcon(tab)),
                                    contentDescription = null
                                )
                            },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }

                // Secondary destinations stay one tap away without competing with the four
                // primary tabs for attention.
                Box(
                    modifier = Modifier.weight(0.72f),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.IconButton(
                        modifier = Modifier.semantics {
                            stateDescription = if (moreExpanded) "Expanded" else "Collapsed"
                        },
                        onClick = { moreExpanded = true }
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_more_vert),
                            contentDescription = "More tools"
                        )
                    }
                    DropdownMenu(
                        expanded = moreExpanded,
                        onDismissRequest = { moreExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Cashbook") },
                            onClick = { open(Overlay.Cashbook) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_cashbook),
                                    contentDescription = null
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Upcoming payments") },
                            onClick = { open(Overlay.Upcoming) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_upcoming),
                                    contentDescription = null
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Analytics") },
                            onClick = { open(Overlay.Analytics) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_chart),
                                    contentDescription = null
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Team & devices") },
                            onClick = { open(Overlay.Team) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_settings),
                                    contentDescription = null
                                )
                            }
                        )
                    }
                }
            }
            }
        }
    ) { padding ->
        // The tab content stays composed underneath, so scroll position and open dialogs
        // survive a trip into a secondary screen and back.
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background)
                    // The outer Scaffold owns the system-bar placement for tab content. Mark those
                    // insets consumed before a tab with its own TopAppBar Scaffold is composed, so
                    // the status-bar inset is not applied a second time.
                    .consumeWindowInsets(padding)
                    // Notices are measured in the Scaffold bottom slot, not inside this content
                    // box. This avoids a second, empty strip below the error panels.
            ) {
                when (selectedTab) {
                Tab.HOME -> HomeScreen(
                    onOpenTransactions = { tabIndex = Tab.PASSBOOK.ordinal },
                    onOpenQr = { open(Overlay.Qr) },
                    onOpenSettings = { tabIndex = Tab.SETTINGS.ordinal },
                    role = role.badgeValue
                )
                Tab.PASSBOOK -> TransactionsScreen(
                    onOpenAnalytics = { open(Overlay.Analytics) },
                    role = role.badgeValue
                )
                Tab.QR -> if (overlay == null) {
                    // A direct fallback keeps the saved route usable if the process restores the
                    // tab before the overlay state. Normal navigation always uses the overlay.
                    QrScreen(onBack = { tabIndex = Tab.HOME.ordinal })
                }
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
                            Overlay.Cashbook -> CashbookScreen()
                            Overlay.Diagnostics -> DiagnosticsScreen(onBack = ::close)
                            Overlay.Faq -> FaqScreen(onBack = ::close)
                            Overlay.Terms -> TermsScreen(onBack = ::close)
                            Overlay.Qr -> QrScreen(onBack = ::close)
                            Overlay.Backup -> BackupScreen(onBack = ::close)
                            Overlay.Team -> TeamScreen(onBack = ::close)
                            Overlay.Upcoming -> PassbookScreen()
                        }
                    }
                }

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
    Tab.PASSBOOK -> R.drawable.ic_history
    Tab.QR -> R.drawable.ic_qr_code
    Tab.SETTINGS -> R.drawable.ic_settings
}

/** Overlay values must survive process recreation, so they are stored by name. */
private object OverlayRegistry {
    private val all = listOf(
        Overlay.Analytics,
        Overlay.Cashbook,
        Overlay.Diagnostics,
        Overlay.Faq,
        Overlay.Terms,
        Overlay.Qr,
        Overlay.Backup,
        Overlay.Team,
        Overlay.Upcoming
    )

    fun nameOf(overlay: Overlay): String = overlay::class.simpleName ?: "Unknown"

    fun byName(name: String): Overlay? = all.firstOrNull { nameOf(it) == name }
}
