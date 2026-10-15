package com.vivekray898.payalert

import android.content.Context
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
import com.vivekray898.payalert.settings.AppSettings
import com.vivekray898.payalert.tts.TtsEngine
import com.vivekray898.payalert.widget.PayAlertWidgetProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

        /** Application context, kept so the pipeline can notify home-screen widgets. */
        private val appContext: Context = context.applicationContext

        val pipeline: PaymentPipeline = PaymentPipeline(
            RoomPaymentStore(database),
            parser,
            { settings.state.value },
            tts::announce
        ) {
            PayAlertWidgetProvider.refresh(appContext)
        }

        /** Set by the notification listener so the UI can report listener health. */
        private val _listenerConnected = MutableStateFlow(false)
        val listenerConnected: StateFlow<Boolean> = _listenerConnected.asStateFlow()

        fun setListenerConnected(connected: Boolean) {
            _listenerConnected.value = connected
        }
    }

    private const val TAG = "PayAlert"

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
        wallets = emptyList()
    )
}
