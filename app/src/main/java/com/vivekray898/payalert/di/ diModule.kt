package com.vivekray898.payalert.di

import androidx.lifecycle.viewmodel.compose.viewModel
import com.vivekray898.payalert.data.dao.*
import com.vivekray898.payalert.data.repo.PaymentEventRepository
import com.vivekray898.payalert.data.repo.SupabaseSyncClient
import com.vivekray898.payalert.di.internal.qualifier.ApplicationContext
import com.vivekray898.payalert.di.internal.qualifier.Names
import com.vivekray898.payalert.di.internal.qualifier.SupabaseKey
import com.vivekray898.payalert.di.internal.qualifier.UseCaseKey
import com.vivekray898.payalert.di.internal.module.DiModule
import com.vivekray898.payalert.services.PaymentMonitorService
import com.vivekray898.payalert.ui.auth.SignInViewModel
import com.vivekray898.payalert.ui.diagnostics.DiagnosticsViewModel
import com.vivekray898.payalert.ui.main.MainViewModel
import com.vivekray898.payalert.ui.transactions.TransactionsViewModel
import com.vivekray898.payalert.ui.cashbook.CashbookViewModel
import com.vivekray898.payalert.ui.settings.TtsSettingsViewModel
import com.vivekray898.payalert.ui.subscription.SubscriptionViewModel
import com.vivekray898.payalert.ui.team.TeamViewModel
import com.vivekray898.payalert.ui.referral.ReferralViewModel
import com.vivekray898.payalert.parsers.ParserRegistry
import com.vivekray898.payalert.sync.SupabaseSyncClient
import javax.inject.Singleton

/**
 * Central dependency-injection module. All @Inject / @Assisted injectors
 * should go through this module. We use manual DI (no Hilt) to keep
 * dependencies explicit and testable.
 */
object diModule {

    // -----------------------------------------------------------------
    // @Qualifier annotations — keep them simple; just enough to disambiguate
    // -----------------------------------------------------------------

    @ApplicationContext
    annotation class @ApplicationContext val()

    @Names
    annotation class @Names val()

    @SupabaseKey
    annotation class @SupabaseKey val()

    @UseCaseKey
    annotation class @UseCaseKey val()

    // -----------------------------------------------------------------
    // Module implementation — inline so callers can see what's provided
    // -----------------------------------------------------------------

    @Singleton
    @ApplicationContext
    class ApplicationContextProvider(
        private val application: Application
    ) : com.vivekray898.payalert.di.internal.qualifier.ApplicationContext

    @Singleton
    fun provideApplicationContext(application: Application): Application =
        application

    @Singleton
    @SupabaseKey
    fun provideSupabaseUrl(): String {
        return System.getenv("SUPABASE_PUBLISHABLE_URL") ?: ""
    }

    @Singleton
    @SupabaseKey
    fun provideSupabaseAnonKey(): String {
        return System.getenv("SUPABASE_PUBLISHABLE_KEY") ?: ""
    }

    @Singleton
    fun provideSupabaseClient(
        url: String = provideSupabaseUrl(),
        key: String = provideSupabaseAnonKey()
    ): SupabaseSyncClient {
        // TODO: initialise real Supabase client when dependency available.
        // For now return a stub that queues ops locally.
        return object : SupabaseSyncClient {
            override fun upsertPaymentEvent(event: PaymentEvent) { /* queue locally */ }
            override fun syncPending(): Unit { /* no-op */ }
            override fun startRealtime(listener: () -> Unit) { /* no-op */ }
        }
    }

    @Singleton
    fun provideParserRegistry(): ParserRegistry {
        return ParserRegistry()
    }

    @Singleton
    fun provideDedupIndex(): DedupIndex {
        return DedupRoomImpl() // Room-backed impl
    }

    @Singleton
    fun providePaymentEventRepository(
        dao: PaymentEventDao,
        supabase: SupabaseSyncClient,
        dedup: DedupIndex
    ): PaymentEventRepository = object : PaymentEventRepository {
        override fun insert(event: PaymentEvent) { dao.insert(event); dedup.markSeen(event.dedupKey) }
        override fun getUnsynced(): List<PaymentEvent> { return dao.getUnsynced() }
        override fun markSynced(eventId: String) { dao.markSynced(eventId); supabase.markSynced(eventId) }
        override fun syncPending(): Unit {
            val pending = getUnsynced()
            pending.forEach { supabase.upsertPaymentEvent(it) }
        }
    }

    // -----------------------------------------------------------------
    // ViewModels — each gets the deps it needs; we wire them here
    // -----------------------------------------------------------------

    @Singleton
    fun provideMainViewModel(
        repository: PaymentEventRepository,
        parser: ParserRegistry,
        dedup: DedupIndex,
        tts: TtsEngine
    ): MainViewModel = MainViewModel(repository, parser, dedup, tts)

    @Singleton
    fun provideDiagnosticsViewModel(
        repository: PaymentEventRepository,
        parser: ParserRegistry,
        dedup: DedupIndex,
        tts: TtsEngine
    ): DiagnosticsViewModel = DiagnosticsViewModel(repository, parser, dedup, tts)

    @Singleton
    fun provideTtsSettingsViewModel(
        tts: TtsEngine,
        preferences: TtsPreferences
    ): TtsSettingsViewModel = TtsSettingsViewModel(tts, preferences)

    @Singleton
    fun provideSubscriptionViewModel(
        supabase: SupabaseSyncClient
    ): SubscriptionViewModel = SubscriptionViewModel(supabase)

    @Singleton
    fun provideTeamViewModel(
        supabase: SupabaseSyncClient
    ): TeamViewModel = TeamViewModel(supabase)

    @Singleton
    fun provideReferralViewModel(
        supabase: SupabaseSyncClient
    ): ReferralViewModel = ReferralViewModel(supabase)

    @Singleton
    fun provideTransactionsViewModel(
        repository: PaymentEventRepository
    ): TransactionsViewModel = TransactionsViewModel(repository)

    @Singleton
    fun provideCashbookViewModel(
        repository: PaymentEventRepository
    ): CashbookViewModel = CashbookViewModel(repository)

    // -----------------------------------------------------------------
    // Service registration — we just note the class; the Android manifest
    // already declares it. The actual start/stop is in the UI layer /
    // SplashActivity.
    // -----------------------------------------------------------------

    @Singleton
    fun providePaymentMonitorServiceClass(): Class<PaymentMonitorService> =
        PaymentMonitorService::class.java
}