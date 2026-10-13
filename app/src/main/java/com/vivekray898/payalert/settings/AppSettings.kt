package com.vivekray898.payalert.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How a payment is signalled aloud. */
enum class SoundMode {
    /** Speak the announcement only. */
    SPEECH,

    /** Play a short chime, then speak. */
    CHIME_AND_SPEECH,

    /** Play only the chime (no speech). */
    CHIME_ONLY,

    /** Say nothing and play nothing. */
    SILENT;

    companion object {
        fun from(value: String?): SoundMode =
            entries.firstOrNull { it.name == value } ?: SPEECH

        val labels: List<Pair<SoundMode, String>> = listOf(
            SPEECH to "Voice only",
            CHIME_AND_SPEECH to "Chime then voice",
            CHIME_ONLY to "Chime only",
            SILENT to "Silent (no sound)"
        )
    }
}

/**
 * Immutable view of every user setting. Persisted in SharedPreferences so all choices
 * survive restarts.
 */
data class SettingsSnapshot(
    val monitoringEnabled: Boolean = true,
    val detectSms: Boolean = true,
    val detectNotifications: Boolean = true,
    val announceCredits: Boolean = true,
    val announceDebits: Boolean = true,
    val soundMode: SoundMode = SoundMode.SPEECH,
    val vibrate: Boolean = false,
    val languageTag: String = "en-IN",
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val volume: Float = 1.0f,
    val minimumAmount: Double = 0.0,
    val dedupWindowMinutes: Int = 5,
    /** Shared fallback template; blank means the built-in wording for the direction. */
    val announcementTemplate: String = "",
    val creditTemplate: String = "",
    val debitTemplate: String = ""
) {
    /** The template that applies to [kind], falling back to the shared one. */
    fun templateFor(kind: String): String {
        val specific = if (kind == "credit") creditTemplate else debitTemplate
        return specific.ifBlank { announcementTemplate }
    }
}

class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<SettingsSnapshot> = _state.asStateFlow()

    private fun load(): SettingsSnapshot = SettingsSnapshot(
        monitoringEnabled = prefs.getBoolean(KEY_MONITORING, true),
        detectSms = prefs.getBoolean(KEY_DETECT_SMS, true),
        detectNotifications = prefs.getBoolean(KEY_DETECT_NOTIF, true),
        announceCredits = prefs.getBoolean(KEY_CREDITS, true),
        announceDebits = prefs.getBoolean(KEY_DEBITS, true),
        soundMode = SoundMode.from(prefs.getString(KEY_SOUND_MODE, SoundMode.SPEECH.name)),
        vibrate = prefs.getBoolean(KEY_VIBRATE, false),
        languageTag = prefs.getString(KEY_LANGUAGE, "en-IN") ?: "en-IN",
        speechRate = prefs.getFloat(KEY_RATE, 1.0f),
        pitch = prefs.getFloat(KEY_PITCH, 1.0f),
        volume = prefs.getFloat(KEY_VOLUME, 1.0f),
        minimumAmount = prefs.getFloat(KEY_MIN_AMOUNT, 0.0f).toDouble(),
        dedupWindowMinutes = prefs.getInt(KEY_DEDUP_WINDOW, 5),
        announcementTemplate = prefs.getString(KEY_TEMPLATE, "") ?: "",
        creditTemplate = prefs.getString(KEY_TEMPLATE_CREDIT, "") ?: "",
        debitTemplate = prefs.getString(KEY_TEMPLATE_DEBIT, "") ?: ""
    )

    private inline fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _state.value = load()
    }

    fun setMonitoringEnabled(value: Boolean) = edit { putBoolean(KEY_MONITORING, value) }
    fun setDetectSms(value: Boolean) = edit { putBoolean(KEY_DETECT_SMS, value) }
    fun setDetectNotifications(value: Boolean) = edit { putBoolean(KEY_DETECT_NOTIF, value) }
    fun setAnnounceCredits(value: Boolean) = edit { putBoolean(KEY_CREDITS, value) }
    fun setAnnounceDebits(value: Boolean) = edit { putBoolean(KEY_DEBITS, value) }
    fun setSoundMode(value: SoundMode) = edit { putString(KEY_SOUND_MODE, value.name) }
    fun setVibrate(value: Boolean) = edit { putBoolean(KEY_VIBRATE, value) }
    fun setLanguageTag(value: String) = edit { putString(KEY_LANGUAGE, value) }
    fun setSpeechRate(value: Float) = edit { putFloat(KEY_RATE, value) }
    fun setPitch(value: Float) = edit { putFloat(KEY_PITCH, value) }
    fun setVolume(value: Float) = edit { putFloat(KEY_VOLUME, value) }
    fun setMinimumAmount(value: Double) = edit { putFloat(KEY_MIN_AMOUNT, value.toFloat()) }
    fun setDedupWindowMinutes(value: Int) = edit { putInt(KEY_DEDUP_WINDOW, value) }
    fun setAnnouncementTemplate(value: String) = edit { putString(KEY_TEMPLATE, value) }
    fun setCreditTemplate(value: String) = edit { putString(KEY_TEMPLATE_CREDIT, value) }
    fun setDebitTemplate(value: String) = edit { putString(KEY_TEMPLATE_DEBIT, value) }

    fun resetToDefaults() = edit {
        clear()
        putBoolean(KEY_MONITORING, true)
        putBoolean(KEY_DETECT_SMS, true)
        putBoolean(KEY_DETECT_NOTIF, true)
        putBoolean(KEY_CREDITS, true)
        putBoolean(KEY_DEBITS, true)
        putString(KEY_SOUND_MODE, SoundMode.SPEECH.name)
        putBoolean(KEY_VIBRATE, false)
        putString(KEY_LANGUAGE, "en-IN")
        putFloat(KEY_RATE, 1.0f)
        putFloat(KEY_PITCH, 1.0f)
        putFloat(KEY_VOLUME, 1.0f)
        putFloat(KEY_MIN_AMOUNT, 0.0f)
        putInt(KEY_DEDUP_WINDOW, 5)
        putString(KEY_TEMPLATE, "")
        putString(KEY_TEMPLATE_CREDIT, "")
        putString(KEY_TEMPLATE_DEBIT, "")
    }

    companion object {
        private const val PREFS_NAME = "payalert_settings"
        private const val KEY_MONITORING = "monitoring_enabled"
        private const val KEY_DETECT_SMS = "detect_sms"
        private const val KEY_DETECT_NOTIF = "detect_notifications"
        private const val KEY_CREDITS = "announce_credits"
        private const val KEY_DEBITS = "announce_debits"
        private const val KEY_SOUND_MODE = "sound_mode"
        private const val KEY_VIBRATE = "vibrate"
        private const val KEY_LANGUAGE = "language_tag"
        private const val KEY_RATE = "speech_rate"
        private const val KEY_PITCH = "pitch"
        private const val KEY_VOLUME = "volume"
        private const val KEY_MIN_AMOUNT = "minimum_amount"
        private const val KEY_DEDUP_WINDOW = "dedup_window_minutes"
        private const val KEY_TEMPLATE = "announcement_template"
        private const val KEY_TEMPLATE_CREDIT = "announcement_template_credit"
        private const val KEY_TEMPLATE_DEBIT = "announcement_template_debit"

        /** Language tags offered in the settings picker, matching the reference locales. */
        val SUPPORTED_LANGUAGES: List<Pair<String, String>> = listOf(
            "en-IN" to "English (India)",
            "hi-IN" to "हिन्दी (Hindi)",
            "bn-IN" to "বাংলা (Bengali)",
            "gu-IN" to "ગુજરાતી (Gujarati)",
            "kn-IN" to "ಕನ್ನಡ (Kannada)",
            "ml-IN" to "മലയാളം (Malayalam)",
            "mr-IN" to "मराठी (Marathi)",
            "pa-IN" to "ਪੰਜਾਬੀ (Punjabi)",
            "ta-IN" to "தமிழ் (Tamil)",
            "te-IN" to "తెలుగు (Telugu)"
        )
    }
}
