package com.vivekray898.payalert.tts

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import com.vivekray898.payalert.settings.AppSettings
import com.vivekray898.payalert.settings.SoundMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

/**
 * Thin, null-safe wrapper around [TextToSpeech] plus an optional chime and vibration.
 *
 * The chime is generated on the device (no bundled audio assets — none were copied from the
 * reference app). Settings are read from [AppSettings] at every announcement, so a change in
 * the settings screen takes effect on the next payment without re-creating the engine.
 */
class TtsEngine(
    private val context: Context,
    private val settings: AppSettings
) {
    private var engine: TextToSpeech? = null
    private var pendingText: String? = null

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    @Synchronized
    fun ensureInitialized() {
        if (engine != null) return
        engine = TextToSpeech(context.applicationContext) { status ->
            val ok = status == TextToSpeech.SUCCESS
            _ready.value = ok
            if (ok) {
                engine?.let { applySettings(it) }
                pendingText?.let { text ->
                    pendingText = null
                    speakNow(text)
                }
            }
        }
    }

    /** Signal a payment according to the configured sound mode. */
    fun announce(text: String) {
        val snapshot = settings.state.value
        when (snapshot.soundMode) {
            SoundMode.SPEECH -> speak(text)
            SoundMode.CHIME_AND_SPEECH -> {
                chime(snapshot.volume)
                vibrateIfEnabled(snapshot.vibrate)
                speak(text)
            }
            SoundMode.CHIME_ONLY -> {
                chime(snapshot.volume)
                vibrateIfEnabled(snapshot.vibrate)
            }
            SoundMode.SILENT -> Unit
        }
    }

    /** Speak [text], initialising the engine on first use. */
    fun speak(text: String) {
        if (text.isBlank()) return
        ensureInitialized()
        if (_ready.value) speakNow(text) else pendingText = text
    }

    /** Stop any in-progress utterance. */
    fun stop() {
        engine?.stop()
    }

    private fun speakNow(text: String) {
        val tts = engine ?: return
        val snapshot = settings.state.value
        applySettings(tts)
        // TextToSpeech has no setVolume(); volume travels in the utterance params.
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, snapshot.volume.coerceIn(0f, 1f))
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, UUID.randomUUID().toString())
    }

    private fun applySettings(tts: TextToSpeech) {
        val snapshot = settings.state.value
        tts.setSpeechRate(snapshot.speechRate.coerceIn(0.1f, 2.0f))
        tts.setPitch(snapshot.pitch.coerceIn(0.1f, 2.0f))
        tts.setLanguage(localeFor(snapshot.languageTag))
    }

    /** Short, generated tone used as the "new payment" cue. */
    private fun chime(volume: Float) {
        try {
            val generator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, (volume * 100).toInt())
            generator.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
            // Release shortly after the tone finishes so we do not leak the generator.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching { generator.release() }
            }, 700)
        } catch (error: RuntimeException) {
            // ToneGenerator can fail when audio resources are exhausted; the announcement
            // itself still happens, so this is not fatal.
        }
    }

    private fun vibrateIfEnabled(enabled: Boolean) {
        if (!enabled) return
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(VibratorManager::class.java)
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(120)
            }
        } catch (error: RuntimeException) {
            // Vibration is a nicety; never let it break the announcement.
        }
    }

    private fun localeFor(tag: String): Locale =
        if (tag.isBlank()) Locale.getDefault() else Locale.forLanguageTag(tag)

    /** True when the platform speech engine accepted the current language. */
    fun isLanguageAvailable(): Boolean {
        val tts = engine ?: return false
        return tts.isLanguageAvailable(localeFor(settings.state.value.languageTag)) >=
            TextToSpeech.LANG_AVAILABLE
    }

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        _ready.value = false
    }
}
