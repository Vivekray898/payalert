package com.vivekray898.payalert

import android.content.Context
import android.os.Build
import android.util.Log
import com.vivekray898.payalert.data.PayAlertDatabase
import com.vivekray898.payalert.parsers.CoordinatorConfig
import com.vivekray898.payalert.parsers.DirectionConfig
import com.vivekray898.payalert.parsers.MerchantConfig
import com.vivekray898.payalert.parsers.ParserConfig
import com.vivekray898.payalert.parsers.ParserConfigLoader
import com.vivekray898.payalert.parsers.ParserRegistry
import com.vivekray898.payalert.parsers.PatternConfig
import com.vivekray898.payalert.parsers.SkipConfig
import com.vivekray898.payalert.pipeline.PaymentPipeline
import com.vivekray898.payalert.pipeline.RoomPaymentStore
import com.vivekray898.payalert.relay.AndroidKeystoreRelayPrivateKeyStorage
import com.vivekray898.payalert.relay.EmployeeReceiver
import com.vivekray898.payalert.relay.OwnerForwarder
import com.vivekray898.payalert.relay.RelayCleanup
import com.vivekray898.payalert.relay.RelayCleanupScheduler
import com.vivekray898.payalert.relay.RelayEngine
import com.vivekray898.payalert.relay.RelayIdentity
import com.vivekray898.payalert.relay.RelayPeerKeyCache
import com.vivekray898.payalert.relay.RelayPumpScheduler
import com.vivekray898.payalert.relay.RelayStatus
import com.vivekray898.payalert.relay.EmployeeRealtimeService
import com.vivekray898.payalert.role.LocalDeviceRole
import com.vivekray898.payalert.settings.AppSettings
import com.vivekray898.payalert.services.PayAlertMessagingService
import com.vivekray898.payalert.services.PaymentMonitorService
import com.vivekray898.payalert.services.RelayAnnouncementNotifier
import com.vivekray898.payalert.team.SupabaseTeamRepository
import com.vivekray898.payalert.team.TeamController
import com.vivekray898.payalert.team.TeamRepository
import com.vivekray898.payalert.tts.TtsEngine
import com.vivekray898.payalert.widget.PayAlertWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide dependency holder. Deliberately a hand-written singleton rather than
 * a DI framework: the graph is small and services/receivers must reach it without
 * an injection entry point.
 */
object AppGraph {

    @Volatile
    private var instance: Graph? = null

    fun get(context: Context): Graph =
        instance ?: synchronized(this) {
            instance ?: Graph(context.applicationContext).also { instance = it }
        }

    class Graph(context: Context) {
        val settings: AppSettings = AppSettings(context)
        val database: PayAlertDatabase = PayAlertDatabase.get(context)

        /** Survives individual screens because relay work is not tied to a ViewModel. */
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * One instance serves both the pairing API and the encrypted relay, so the rotating refresh
         * token is used under a single mutex and the relay can never race a sign-in.
         */
        private val teamRepository: SupabaseTeamRepository by lazy { SupabaseTeamRepository(context) }
        val team: TeamRepository get() = teamRepository

        private val devicePreferences =
            context.getSharedPreferences("team_device", Context.MODE_PRIVATE)
        private val defaultDeviceName =
            "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(100).ifBlank { "My Android device" }

        /** Rule set loaded from assets; failure is surfaced in diagnostics rather than hidden. */
        val parserConfigLoadFailed: Boolean
        val parserConfig: ParserConfig
        val parser: ParserRegistry

        init {
            val loaded = try {
                ParserConfigLoader.loadFromAsset(context)
            } catch (error: Exception) {
                Log.e(TAG, "Could not load parser_config.json", error)
                null
            }
            parserConfigLoadFailed = loaded == null
            parserConfig = loaded ?: emptyConfig()
            parser = ParserRegistry(parserConfig)
        }
        val tts: TtsEngine = TtsEngine(context, settings)

        private val relayIdentity = RelayIdentity(
            AndroidKeystoreRelayPrivateKeyStorage(context),
            teamRepository
        )
        private val relayPeerKeys = RelayPeerKeyCache(database.relayPeerKeyDao())
        private val appContext: Context = context.applicationContext
        private val announcementNotifier = RelayAnnouncementNotifier(appContext)
        private val fcmRegistrationLock = Any()
        private var fcmRegistrationKey: String? = null

        val relayForwarder = OwnerForwarder(
            transport = teamRepository,
            identity = relayIdentity,
            queue = database.ownerOutboundQueueDao(),
            peerKeys = relayPeerKeys
        )

        val relayReceiver = EmployeeReceiver(
            transport = teamRepository,
            identity = relayIdentity,
            peerKeys = relayPeerKeys,
            received = database.receivedAnnouncementDao(),
            announcementTemplate = { settings.state.value.creditTemplate },
            announce = tts::announce,
            notify = announcementNotifier::showAnnouncement
        )

        /**
         * Owner -> employee relay. It is inert until a pairing exists, so an app with no account
         * behaves exactly as before.
         */
        /** Exposed for EmployeeRealtimeService to check if relay is configured. */
        val isTransportConfigured: Boolean get() = teamRepository.configured

        /** Updates the FCM token on the server for this device. */
        suspend fun updateFcmToken(token: String) = team.updateFcmToken(token)

        val relayEngine: RelayEngine = RelayEngine(
            transport = teamRepository,
            identity = relayIdentity,
            peerKeys = relayPeerKeys,
            forwarder = relayForwarder,
            receiver = relayReceiver,
            outbound = database.ownerOutboundQueueDao(),
            received = database.receivedAnnouncementDao(),
            scope = appScope
        )

        /**
         * Created eagerly on app open so an existing pairing is resolved in the background, without
         * the user having to open the team screen for forwarding to work.
         */
        val teamController: TeamController by lazy {
            TeamController(
                repository = team,
                scope = appScope,
                initialDeviceName = devicePreferences.getString(KEY_DEVICE_NAME, null)
                    ?: defaultDeviceName,
                saveDeviceName = { name ->
                    devicePreferences.edit().putString(KEY_DEVICE_NAME, name).apply()
                },
                roleState = settings.localDeviceRoleState,
                onPairingChanged = { snapshot, role ->
                    relayEngine.onPairingChanged(snapshot, role)
                    if (role == LocalDeviceRole.EMPLOYEE && snapshot != null) {
                        // The generic relay loop is a 30-second fallback. Once pairing confirms
                        // this is an employee, hand delivery to the dedicated short-cadence
                        // foreground service instead of leaving both loops competing for the
                        // same delivery mutex.
                        relayEngine.stop()
                        PaymentMonitorService.stop(appContext)
                        if (!EmployeeRealtimeService.isRunning) {
                            EmployeeRealtimeService.start(appContext)
                        }
                    } else {
                        EmployeeRealtimeService.stop(appContext)
                        relayEngine.start()
                        if (settings.state.value.monitoringEnabled) {
                            PaymentMonitorService.start(appContext)
                        }
                    }
                    if (snapshot == null) {
                        synchronized(fcmRegistrationLock) { fcmRegistrationKey = null }
                    } else {
                        team.account.value?.userId?.let { accountId ->
                            val registrationKey = "$accountId:${snapshot.device.id}:$role"
                            val shouldRefresh = synchronized(fcmRegistrationLock) {
                                if (fcmRegistrationKey == registrationKey) {
                                    false
                                } else {
                                    fcmRegistrationKey = registrationKey
                                    true
                                }
                            }
                            if (shouldRefresh) PayAlertMessagingService.refreshToken(appContext)
                        }
                    }
                }
            )
        }

        val pipeline: PaymentPipeline = PaymentPipeline(
            store = RoomPaymentStore(database),
            parser = parser,
            settings = { settings.state.value },
            tts = tts::announce,
            onStored = { PayAlertWidgetProvider.refresh(appContext) },
            onAccepted = { event -> relayEngine.onStored(event) }
        )

        /** Set by the notification listener so the UI can report listener health. */
        private val _listenerConnected = MutableStateFlow(false)
        val listenerConnected: StateFlow<Boolean> = _listenerConnected.asStateFlow()

        fun setListenerConnected(connected: Boolean) {
            _listenerConnected.value = connected
        }

        /**
         * Called when the app or the monitor service starts.
         *
         * Resolves the current pairing (which sets the local role and, on an employee device, starts
         * receiving), drops expired announcements, and makes sure the periodic cleanup is scheduled.
         */
        fun onAppOpen() {
            // Touching the controller resolves the pairing even when the team screen is never opened.
            val controller = teamController
            appScope.launch {
                try {
                    controller.refresh()
                } catch (_: Exception) {
                    // Offline is normal; the next open retries.
                }
            }
            appScope.launch {
                try {
                    RelayCleanup.sweepOnAppOpen(database)
                } catch (_: Exception) {
                    // Retention sweep is best effort; reads also filter by expiry.
                }
            }
            runCatching { RelayCleanupScheduler.schedule(appContext) }
            runCatching { RelayPumpScheduler.schedule(appContext) }
            startRelay()
        }

        /** Starts the foreground relay loop. Cheap and idempotent; used by the monitor service too. */
        fun startRelay() {
            teamController
            if (settings.localDeviceRoleState.currentRole == LocalDeviceRole.EMPLOYEE) {
                if (!EmployeeRealtimeService.isRunning) EmployeeRealtimeService.start(appContext)
            } else {
                relayEngine.start()
            }
        }

        /**
         * One background relay pass: resolve the current pairing, then deliver. Called by the
         * periodic worker so a paired device keeps working with no screen open.
         */
        suspend fun runRelayPump(): Boolean {
            try {
                // A pump runs every ten seconds on an employee device, and the pairing it needs
                // changes only when the team does. A recent read is reused so the pass stays on
                // a single round trip.
                teamController.refreshNow(TEAM_SNAPSHOT_MAX_AGE_MILLIS)
            } catch (_: Exception) {
                // Offline: the previously stored pairing still lets queued work be flushed.
            }
            // The result now reflects the delivery pass, not merely whether the team metadata
            // request completed. FCM can therefore retry a real fetch failure instead of marking
            // the wake-up handled after a swallowed transport exception.
            return relayEngine.pump()
        }

        /**
         * When a relay push last woke this device, or null when none ever arrived.
         *
         * This is the only local signal that says whether the push path is live. When a device
         * keeps receiving payments by polling while this stays null, the push path is the
         * reason delivery is slow.
         */
        private val _lastRelayPushAt = MutableStateFlow<Long?>(null)
        val lastRelayPushAt: StateFlow<Long?> = _lastRelayPushAt.asStateFlow()

        fun noteRelayPush(at: Long) {
            _lastRelayPushAt.value = at
        }

        /** Live relay counters for the diagnostics screen. */
        val relayStatus: StateFlow<RelayStatus> get() = relayEngine.status
    }

    private const val TAG = "PayAlert"
    private const val KEY_DEVICE_NAME = "name"

    /**
     * How long a team snapshot read is reused by the relay pump.
     *
     * A pairing change is picked up by the pairings-changed push and by the next full refresh,
     * so a minute of reuse costs nothing and removes five of every six snapshot round trips
     * from the employee pump.
     */
    private const val TEAM_SNAPSHOT_MAX_AGE_MILLIS = 60_000L

    private fun emptyConfig() = ParserConfig(
        schemaVersion = 0,
        defaultCurrency = "INR",
        coordinator = CoordinatorConfig(5, 5, 48),
        patterns = PatternConfig(emptyList(), emptyList(), emptyList(), emptyList()),
        skip = SkipConfig(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
        direction = DirectionConfig(emptyList(), emptyList(), emptyList()),
        merchant = MerchantConfig(emptyList(), emptyList(), emptyList(), "", "", emptyList()),
        currency = emptyMap(),
        banks = emptyList(),
        wallets = emptyList(),
        notificationPackages = emptyList()
    )
}
