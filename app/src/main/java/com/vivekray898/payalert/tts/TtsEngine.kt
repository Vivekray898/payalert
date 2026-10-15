package com.vivekray898.payalert.tts

import android.content.Context
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import com.vivekray898.payalert.R
import com.vivekray898.payalert.settings.AppSettings
import com.vivekray898.payalert.settings.SettingsSnapshot
import com.vivekray898.payalert.settings.SoundMode
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wrapper around [TextToSpeech], the bundled payment chime and optional vibration.
 *
 * All announcement and preview work is serialized on one worker. The process-wide engine keeps
 * one MediaPlayer instance for the lifetime of this object and releases it from [shutdown].
 */
class TtsEngine(
    context: Context,
    private val settings: AppSettings
) {
    private val appContext = context.applicationContext
    private val soundExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PayAlertSound").apply { isDaemon = true }
    }
    private val stateLock = Any()

    private var engine: TextToSpeech? = null
    private val pendingSpeech = ArrayDeque<PendingSpeech>()
    private var chimePlayer: MediaPlayer? = null
    private val chimeQueue = ArrayDeque<ChimeRequest>()
    private var activeChime: ChimeRequest? = null
    @Volatile
    private var shutDown = false

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** Initialise TTS asynchronously without blocking the caller. */
    fun ensureInitialized() {
        submitSoundTask {
            synchronized(stateLock) {
                ensureInitializedLocked()
            }
        }
    }

    /** Signal a payment according to the configured sound mode. */
    fun announce(text: String) {
        if (text.isBlank()) return
        val snapshot = settings.state.value
        submitSoundTask {
            announceInternal(text, snapshot, preview = false, mode = snapshot.soundMode)
        }
    }

    /**
     * Preview the selected chip without entering the payment pipeline.
     *
     * The executor is deliberate: a Compose chip callback can run on the UI thread, while
     * MediaPlayer and TextToSpeech work are handled by the sound worker instead.
     */
    fun preview(context: Context, mode: SoundMode) {
        val previewContext = context.applicationContext
        submitSoundTask {
            val snapshot = settings.state.value
            logTransition(
                "preview_requested",
                mode,
                preview = true,
                detail = "context=${previewContext.packageName}"
            )
            announceInternal(PREVIEW_TEXT, snapshot, preview = true, mode = mode)
        }
    }

    /** Speak [text], initialising the engine on first use. */
    fun speak(text: String) {
        if (text.isBlank()) return
        submitSoundTask {
            speakInternal(text, preview = false, mode = null, reason = "direct")
        }
    }

    /** Stop any in-progress utterance without discarding the cached chime. */
    fun stop() {
        submitSoundTask {
            synchronized(stateLock) {
                engine?.stop()
                logTransition("speech_stopped", null, preview = false)
            }
        }
    }

    /** True when the platform speech engine accepted the current language. */
    fun isLanguageAvailable(): Boolean {
        synchronized(stateLock) {
            val tts = engine ?: return false
            return tts.isLanguageAvailable(localeFor(settings.state.value.languageTag)) >=
                TextToSpeech.LANG_AVAILABLE
        }
    }

    /** Release both platform engines; the cached MediaPlayer is never left alive. */
    fun shutdown() {
        synchronized(stateLock) {
            if (shutDown) return
            shutDown = true
            pendingSpeech.clear()
            chimeQueue.clear()
            activeChime = null
            chimePlayer?.setOnCompletionListener(null)
            chimePlayer?.runCatching { release() }
            chimePlayer = null
            engine?.stop()
            engine?.shutdown()
            engine = null
            _ready.value = false
            logTransition("shutdown", null, preview = false)
        }
        soundExecutor.shutdownNow()
    }

    private fun announceInternal(
        text: String,
        snapshot: SettingsSnapshot,
        preview: Boolean,
        mode: SoundMode
    ) {
        synchronized(stateLock) {
            if (shutDown) return
            val decision = decidePlayback(mode, snapshot.vibrate)
            logTransition(
                "decision",
                mode,
                preview,
                "chime=${decision.playChime} speak=${decision.speak} vibrate=${decision.vibrate}"
            )

            if (decision.vibrate) {
                vibrate(preview, mode)
            } else {
                logTransition("vibration_skipped", mode, preview, "enabled=false")
            }

            if (decision.playChime) {
                enqueueChime(
                    ChimeRequest(
                        text = text,
                        speakAfter = decision.speak,
                        volume = snapshot.volume.coerceIn(0f, 1f),
                        preview = preview,
                        mode = mode
                    )
                )
            } else if (decision.speak) {
                speakInternal(text, preview, mode, reason = "mode")
            } else {
                logTransition("silent", mode, preview)
            }
        }
    }

    private fun ensureInitializedLocked() {
        if (shutDown || engine != null) return
        logTransition("tts_initializing", null, preview = false)
        engine = TextToSpeech(appContext) { status ->
            synchronized(stateLock) {
                if (!shutDown) {
                    val ok = status == TextToSpeech.SUCCESS
                    _ready.value = ok
                    logTransition(
                        if (ok) "tts_ready" else "tts_init_failed",
                        null,
                        preview = false,
                        detail = "status=$status"
                    )
                    if (ok) {
                        engine?.let(::applySettings)
                        while (pendingSpeech.isNotEmpty()) {
                            val pending = pendingSpeech.removeFirst()
                            speakNowLocked(pending)
                        }
                    }
                }
            }
        }
    }

    private fun speakInternal(
        text: String,
        preview: Boolean,
        mode: SoundMode?,
        reason: String
    ) {
        synchronized(stateLock) {
            if (shutDown || text.isBlank()) return
            logTransition("speech_requested", mode, preview, "reason=$reason")
            ensureInitializedLocked()
            val pending = PendingSpeech(text, preview, mode)
            if (_ready.value) {
                speakNowLocked(pending)
            } else {
                pendingSpeech.addLast(pending)
                logTransition("speech_pending", mode, preview, "queueDepth=${pendingSpeech.size}")
            }
        }
    }

    private fun speakNowLocked(pending: PendingSpeech) {
        val tts = engine ?: return
        val snapshot = settings.state.value
        applySettings(tts)
        val params = Bundle().apply {
            // TextToSpeech has no setVolume(); speech volume travels in the utterance params.
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, snapshot.volume.coerceIn(0f, 1f))
        }
        try {
            // Queue speech so two accepted payments are not silently dropped or interrupted.
            val result = tts.speak(
                pending.text,
                TextToSpeech.QUEUE_ADD,
                params,
                UUID.randomUUID().toString()
            )
            if (result == TextToSpeech.ERROR) {
                logTransition("speech_failed", pending.mode, pending.preview, "result=ERROR")
            } else {
                logTransition("speech_queued", pending.mode, pending.preview, "result=$result")
            }
        } catch (error: RuntimeException) {
            logTransition(
                "speech_failed",
                pending.mode,
                pending.preview,
                "error=${error.javaClass.simpleName}"
            )
        }
    }

    private fun applySettings(tts: TextToSpeech) {
        val snapshot = settings.state.value
        tts.setSpeechRate(snapshot.speechRate.coerceIn(0.1f, 2.0f))
        tts.setPitch(snapshot.pitch.coerceIn(0.1f, 2.0f))
        tts.setLanguage(localeFor(snapshot.languageTag))
    }

    private fun enqueueChime(request: ChimeRequest) {
        val player = chimePlayer ?: createChimePlayer(request.preview)
        if (player == null) {
            logTransition("chime_load_failed", request.mode, request.preview)
            if (request.speakAfter) {
                // The voice path remains useful when the bundled chime cannot be opened.
                speakInternal(request.text, request.preview, request.mode, reason = "chime_fallback")
            }
            return
        }

        chimeQueue.addLast(request)
        logTransition(
            "chime_queued",
            request.mode,
            request.preview,
            "queueDepth=${chimeQueue.size}"
        )
        if (activeChime == null) startNextChime(player)
    }

    private fun createChimePlayer(preview: Boolean): MediaPlayer? {
        val player = try {
            MediaPlayer.create(appContext, R.raw.kaching)
        } catch (error: RuntimeException) {
            logTransition(
                "chime_load_failed",
                null,
                preview,
                "error=${error.javaClass.simpleName}"
            )
            null
        } ?: return null

        return runCatching {
            player.setOnCompletionListener {
                submitSoundTask { onChimeCompleted() }
            }
            chimePlayer = player
            logTransition("chime_loaded", null, preview, "resource=kaching")
            player
        }.getOrElse { error ->
            runCatching { player.release() }
            logTransition(
                "chime_load_failed",
                null,
                preview,
                "error=${error.javaClass.simpleName}"
            )
            null
        }
    }

    private fun startNextChime(player: MediaPlayer) {
        if (chimeQueue.isEmpty()) return
        val request = chimeQueue.removeFirst()
        activeChime = request
        try {
            // Queue policy: chime cues keep payment order; a later payment waits for the current
            // chime instead of interrupting/restarting it or dropping the cue. TTS uses QUEUE_ADD
            // as well, so speech requests retain order even when the next chime has started.
            player.setVolume(request.volume, request.volume)
            player.seekTo(0)
            player.start()
            logTransition(
                "chime_started",
                request.mode,
                request.preview,
                "volume=${request.volume}"
            )
        } catch (error: RuntimeException) {
            activeChime = null
            chimePlayer?.setOnCompletionListener(null)
            chimePlayer?.runCatching { release() }
            chimePlayer = null
            logTransition(
                "chime_start_failed",
                request.mode,
                request.preview,
                "error=${error.javaClass.simpleName}"
            )
            if (request.speakAfter) {
                speakInternal(request.text, request.preview, request.mode, reason = "chime_fallback")
            }
            val nextPlayer = createChimePlayer(request.preview)
            if (nextPlayer != null) startNextChime(nextPlayer)
        }
    }

    private fun onChimeCompleted() {
        synchronized(stateLock) {
            val request = activeChime ?: return
            activeChime = null
            logTransition("chime_completed", request.mode, request.preview)
            if (request.speakAfter) {
                // Speech is submitted from MediaPlayer's completion transition, never before it.
                speakInternal(request.text, request.preview, request.mode, reason = "after_chime")
            }
            chimePlayer?.let { startNextChime(it) }
        }
    }

    private fun vibrate(preview: Boolean, mode: SoundMode) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = appContext.getSystemService(VibratorManager::class.java)
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator == null) {
                logTransition("vibration_unavailable", mode, preview)
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(120)
            }
            logTransition("vibration_started", mode, preview)
        } catch (error: RuntimeException) {
            logTransition(
                "vibration_failed",
                mode,
                preview,
                "error=${error.javaClass.simpleName}"
            )
        }
    }

    private fun submitSoundTask(task: () -> Unit) {
        try {
            soundExecutor.execute(task)
        } catch (_: RejectedExecutionException) {
            // shutdown() owns the final lifecycle transition; late callbacks are ignored.
        }
    }

    private fun localeFor(tag: String): Locale =
        if (tag.isBlank()) Locale.getDefault() else Locale.forLanguageTag(tag)

    private fun logTransition(
        transition: String,
        mode: SoundMode?,
        preview: Boolean,
        detail: String? = null
    ) {
        val modeText = mode?.name ?: "none"
        val suffix = detail?.let { " $it" } ?: ""
        Log.i(SOUND_TAG, "transition=$transition mode=$modeText preview=$preview$suffix")
    }

    private data class PendingSpeech(
        val text: String,
        val preview: Boolean,
        val mode: SoundMode?
    )

    private data class ChimeRequest(
        val text: String,
        val speakAfter: Boolean,
        val volume: Float,
        val preview: Boolean,
        val mode: SoundMode
    )

    private companion object {
        const val SOUND_TAG = "PayAlertSound"
        const val PREVIEW_TEXT = "This is a PayAlert sound preview."
    }
}
