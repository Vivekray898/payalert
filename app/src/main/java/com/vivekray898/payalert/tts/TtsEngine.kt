package com.vivekray898.payalert.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.Map

/**
 * Thin wrapper around Android's TextToSpeech.
 * Handles init, language/speed/pitch/volume config, and announcement dispatch.
 * All UI should go through this class; it is @Singleton-scoped and
 * thread-safe for the single-use case (one TtsEngine per app process).
 */
class TtsEngine @Inject constructor(
    private val context: Context
) : AutoCloseable {

    private lateinit var tts: TextToSpeech
    private var initStatus: Int = TextToSpeech.ERROR
    private var currentLanguage: String = ""
    private var currentSpeed: Float = 1.0f
    private var currentPitch: Float = 1.0f
    private var currentVolume: Float = 1.0f
    private var currentUtteranceId: String = ""

    // --- Public API ---

    /** Non-blocking init; caller must observe via listener or query status. */
    fun init(listener: (Int) -> Unit) {
        if (initStatus != TextToSpeech.SUCCESS) {
            initStatus = tts = TextToSpeech(context, object : TextToSpeech.OnInitListener {
                override fun onInit(status: Int) {
                    initStatus = status
                    listener(status)
                }
            })
        } else {
            listener(initStatus)
        }
    }

    /** Set the spoken language. Falls back to device default if unsupported. */
    fun setLanguage(lang: String) {
        currentLanguage = lang
        if (tts.isLanguageAvailable(lang) >= TextToSpeech.AVAILABLE) {
            tts.setLanguage(Locale.forLanguageTag(lang))
        }
    }

    /** Set speech speed (range 0.1–2.0 per Android docs). */
    fun setSpeed(speed: Float) {
        currentSpeed = math.max(0.1f, math.min(2.0f, speed))
        if (tts.isSpeaking) tts.setSpeechRate(currentSpeed)
    }

    /** Set speech pitch (range 0.0–2.0 per Android docs). */
    fun setPitch(pitch: Float) {
        currentPitch = math.max(0.0f, math.min(2.0f, pitch))
        if (tts.isSpeaking) tts.setSpeechPitch(currentPitch)
    }

    /** Set volume (range 0.0–1.0). */
    fun setVolume(volume: Float) {
        currentVolume = math.max(0.0f, math.min(1.0f, volume))
        if (tts.isSpeaking) tts.setVolume(currentVolume, currentVolume)
    }

    /** Speak the given text with current settings; use a unique utteranceId for control. */
    fun announce(text: String, utteranceId: String? = null) {
        if (tts.isSpeaking) tts.stop()
        currentUtteranceId = utteranceId ?: java.util.UUID.randomUUID().toString()
        val params = mapOf(
            TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID to currentUtteranceId,
            TextToSpeech.Engine.KEY_PARAM_TEXT to text
        )
        // Apply stored settings
        tts.setSpeechRate(currentSpeed)
        tts.setSpeechPitch(currentPitch)
        tts.setVolume(currentVolume, currentVolume)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, currentUtteranceId)
    }

    /** Stop any ongoing synthesis. */
    fun stop() {
        if (tts.isSpeaking) tts.stop()
    }

    /** Release the TTS engine; call on app exit. */
    override fun close() {
        if (tts != null) {
            tts.stop()
            tts.shutdown()
            tts = null
        }
    }

    /** Quick check: is TTS currently speaking? */
    fun isSpeaking(): Boolean = tts.isSpeaking()
}