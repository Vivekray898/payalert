package com.vivekray898.payalert.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.vivekray898.payalert.R
import com.vivekray898.payalert.relay.RelayTrace
import com.vivekray898.payalert.relay.RelayTrace.Stage
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
    private val settings: AppSettings,
    private val trace: RelayTrace = RelayTrace.process
) {
    private val appContext = context.applicationContext
    private val soundExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PayAlertSound").apply { isDaemon = true }
    }
    private val soundHandler = Handler(Looper.getMainLooper())
    private val stateLock = Any()

    private var engine: TextToSpeech? = null
    private val pendingSpeech = ArrayDeque<PendingSpeech>()
    private val utteranceModes = HashMap<String, UtteranceInfo>()
    @Volatile
    private var lastTtsOnsetLatencyMs = 0L
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

    /** Relay-only entry point keeps existing one-argument announce method references compatible. */
    fun announceRelay(messageId: String, text: String) {
        if (text.isBlank()) return
        val relayMessageId = RelayTrace.messageUuid(messageId)
        traceRelay(Stage.TTS_REQUESTED, relayMessageId)
        val snapshot = settings.state.value
        submitSoundTask {
            announceInternal(
                text, snapshot, preview = false, mode = snapshot.soundMode,
                relayMessageId = relayMessageId
            )
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
            utteranceModes.clear()
            chimeQueue.clear()
            activeChime?.speechRunnable?.let(soundHandler::removeCallbacks)
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
        mode: SoundMode,
        relayMessageId: String? = null
    ) {
        synchronized(stateLock) {
            if (shutDown) return
            val decision = decidePlayback(mode, snapshot.vibrate)
            if (!decision.speak) traceRelay(Stage.TTS_SKIPPED, relayMessageId)
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
                        mode = mode,
                        relayMessageId = relayMessageId
                    )
                )
            } else if (decision.speak) {
                speakInternal(text, preview, mode, reason = "mode", relayMessageId = relayMessageId)
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
                        logEvent("tts_ready", null, preview = false, detail = "status=$status")
                        engine?.setOnUtteranceProgressListener(utteranceListener)
                        engine?.let(::applySettings)
                        warmUpTtsLocked()
                        while (pendingSpeech.isNotEmpty()) {
                            val pending = pendingSpeech.removeFirst()
                            speakNowLocked(pending)
                        }
                    } else {
                        pendingSpeech.forEach { traceRelay(Stage.TTS_INIT_FAILED, it.relayMessageId) }
                    }
                }
            }
        }
    }

    private fun speakInternal(
        text: String,
        preview: Boolean,
        mode: SoundMode?,
        reason: String,
        relayMessageId: String? = null
    ) {
        synchronized(stateLock) {
            val speechText = SpeechTextNormalizer.forSpeech(text)
            if (shutDown || speechText.isBlank()) return
            logTransition("speech_requested", mode, preview, "reason=$reason")
            ensureInitializedLocked()
            val pending = PendingSpeech(speechText, preview, mode, relayMessageId)
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
        val utteranceId = UUID.randomUUID().toString()
        val speakStartMs = SystemClock.elapsedRealtime()
        utteranceModes[utteranceId] = UtteranceInfo(pending, speakStartMs)
        logEvent("speak_start", pending.mode, pending.preview)
        try {
            // Flush stale speech so a current payment is not delayed by an older utterance.
            traceRelay(Stage.TTS_SPEAK_REQUESTED, pending.relayMessageId)
            val result = tts.speak(
                pending.text,
                TextToSpeech.QUEUE_FLUSH,
                params,
                utteranceId
            )
            if (result == TextToSpeech.ERROR) {
                utteranceModes.remove(utteranceId)
                traceRelay(Stage.TTS_SUBMIT_FAILED, pending.relayMessageId)
                logTransition("speech_failed", pending.mode, pending.preview, "result=ERROR")
            } else {
                logTransition("speech_queued", pending.mode, pending.preview, "result=$result")
            }
        } catch (_: RuntimeException) {
            utteranceModes.remove(utteranceId)
            traceRelay(Stage.TTS_SUBMIT_FAILED, pending.relayMessageId)
            logTransition("speech_failed", pending.mode, pending.preview)
        }
    }

    private fun applySettings(tts: TextToSpeech) {
        // Keep spoken payment announcements on the system notification stream instead of the
        // media/music stream. The per-utterance volume below remains PayAlert's own attenuation.
        tts.setAudioAttributes(notificationSpeechAudioAttributes)
        val snapshot = settings.state.value
        tts.setSpeechRate(snapshot.speechRate.coerceIn(0.1f, 2.0f))
        tts.setPitch(snapshot.pitch.coerceIn(0.1f, 2.0f))
        tts.setLanguage(localeFor(snapshot.languageTag))
    }

    private fun warmUpTtsLocked() {
        val tts = engine ?: return
        val utteranceId = "warmup-${UUID.randomUUID()}"
        val warmup = PendingSpeech(" ", preview = false, mode = null)
        val speakStartMs = SystemClock.elapsedRealtime()
        utteranceModes[utteranceId] = UtteranceInfo(warmup, speakStartMs)
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0f)
        }
        logEvent("tts_warmup_start", null, preview = false)
        val result = runCatching {
            tts.speak(" ", TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        }.getOrElse { TextToSpeech.ERROR }
        if (result == TextToSpeech.ERROR) {
            utteranceModes.remove(utteranceId)
            logEvent("tts_warmup_failed", null, preview = false)
        }
    }

    private fun enqueueChime(request: ChimeRequest) {
        val player = chimePlayer ?: createChimePlayer(request.preview)
        if (player == null) {
            logTransition("chime_load_failed", request.mode, request.preview)
            if (request.speakAfter) {
                // The voice path remains useful when the bundled chime cannot be opened.
                speakInternal(
                    request.text, request.preview, request.mode, reason = "chime_fallback",
                    relayMessageId = request.relayMessageId
                )
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
        val player = MediaPlayer()
        return runCatching {
            // Configure routing before prepare(). MediaPlayer.create() prepares immediately, so
            // constructing the player explicitly avoids falling back to the media/music stream.
            player.setAudioAttributes(notificationChimeAudioAttributes)
            appContext.resources.openRawResourceFd(R.raw.kaching).use { source ->
                player.setDataSource(source.fileDescriptor, source.startOffset, source.length)
                player.prepare()
            }
            player.setOnCompletionListener {
                submitSoundTask { onChimeCompleted() }
            }
            chimePlayer = player
            logTransition("chime_loaded", null, preview, "resource=kaching")
            player
        }.getOrElse {
            runCatching { player.release() }
            logTransition("chime_load_failed", null, preview)
            null
        }
    }

    private fun startNextChime(player: MediaPlayer) {
        if (chimeQueue.isEmpty()) return
        val request = chimeQueue.removeFirst()
        activeChime = request
        try {
            // Queue policy: chime cues keep payment order; a later payment waits for the current
            // chime instead of interrupting/restarting it or dropping the cue. Speech uses
            // QUEUE_FLUSH so a stale utterance cannot delay the current announcement.
            player.setVolume(request.volume, request.volume)
            player.seekTo(0)
            player.start()
            logEvent("chime_start", request.mode, request.preview)
            scheduleSpeechBeforeChimeCompletion(player, request)
            logTransition(
                "chime_started",
                request.mode,
                request.preview,
                "volume=${request.volume}"
            )
        } catch (_: RuntimeException) {
            activeChime = null
            chimePlayer?.setOnCompletionListener(null)
            chimePlayer?.runCatching { release() }
            chimePlayer = null
            logTransition("chime_start_failed", request.mode, request.preview)
            if (request.speakAfter) {
                speakInternal(
                    request.text, request.preview, request.mode, reason = "chime_fallback",
                    relayMessageId = request.relayMessageId
                )
            }
            val nextPlayer = createChimePlayer(request.preview)
            if (nextPlayer != null) startNextChime(nextPlayer)
        }
    }

    private fun onChimeCompleted() {
        synchronized(stateLock) {
            val request = activeChime ?: return
            activeChime = null
            logEvent("chime_end", request.mode, request.preview)
            logTransition("chime_completed", request.mode, request.preview)
            if (request.speakAfter) {
                triggerSpeechIfNeeded(request, reason = "completion")
            }
            chimePlayer?.let { startNextChime(it) }
        }
    }

    private fun scheduleSpeechBeforeChimeCompletion(
        player: MediaPlayer,
        request: ChimeRequest
    ) {
        if (!request.speakAfter) return
        val durationMs = player.duration.toLong().coerceAtLeast(0L)
        val leadMs = lastTtsOnsetLatencyMs.coerceIn(0L, MAX_TTS_LEAD_MS)
        val delayMs = (durationMs - leadMs).coerceAtLeast(0L)
        val runnable = Runnable {
            submitSoundTask { triggerSpeechIfNeeded(request, reason = "scheduled") }
        }
        request.speechRunnable = runnable
        soundHandler.postDelayed(runnable, delayMs)
        logEvent(
            "speech_scheduled",
            request.mode,
            request.preview,
            "delayMs=$delayMs leadMs=$leadMs chimeDurationMs=$durationMs"
        )
    }

    private fun triggerSpeechIfNeeded(request: ChimeRequest, reason: String) {
        synchronized(stateLock) {
            if (request.speechTriggered) return
            request.speechTriggered = true
            request.speechRunnable?.let(soundHandler::removeCallbacks)
            speakInternal(
                request.text, request.preview, request.mode, reason = reason,
                relayMessageId = request.relayMessageId
            )
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
        } catch (_: RuntimeException) {
            logTransition("vibration_failed", mode, preview)
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

    private fun traceRelay(stage: Stage, messageId: String?) {
        // Preview, warmup and ordinary local speech have no relay message correlation.
        if (messageId != null) trace.record(stage, clientMessageId = messageId)
    }

    private fun logTransition(
        transition: String,
        mode: SoundMode?,
        preview: Boolean,
        detail: String? = null
    ) {
        val modeText = mode?.name ?: "none"
        val suffix = detail?.let { " $it" } ?: ""
        Log.i(
            SOUND_TAG,
            "transition=$transition mode=$modeText preview=$preview t=${SystemClock.elapsedRealtime()}$suffix"
        )
    }

    private fun logEvent(
        event: String,
        mode: SoundMode?,
        preview: Boolean,
        detail: String? = null
    ) {
        val modeText = mode?.name ?: "none"
        val suffix = detail?.let { " $it" } ?: ""
        val t = SystemClock.elapsedRealtime()
        Log.i(SOUND_TAG, "mode=$modeText event=$event t=$t preview=$preview$suffix")
    }

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val info = utteranceId?.let { synchronized(stateLock) { utteranceModes[it] } }
            traceRelay(Stage.TTS_AUDIO_START, info?.pending?.relayMessageId)
            val t = SystemClock.elapsedRealtime()
            val leadMs = info?.let { (t - it.speakStartMs).coerceAtLeast(0L) }
            if (leadMs != null) lastTtsOnsetLatencyMs = leadMs
            logEvent(
                "speak_audio_start",
                info?.pending?.mode,
                info?.pending?.preview ?: false,
                leadMs?.let { "leadMs=$it" }
            )
        }

        override fun onDone(utteranceId: String?) {
            val info = utteranceId?.let { synchronized(stateLock) { utteranceModes.remove(it) } }
            traceRelay(Stage.TTS_AUDIO_DONE, info?.pending?.relayMessageId)
            logEvent("speak_end", info?.pending?.mode, info?.pending?.preview ?: false)
        }

        @Deprecated("Deprecated in the Android API")
        override fun onError(utteranceId: String?) {
            val info = utteranceId?.let { synchronized(stateLock) { utteranceModes.remove(it) } }
            traceRelay(Stage.TTS_AUDIO_ERROR, info?.pending?.relayMessageId)
            logEvent("speak_error", info?.pending?.mode, info?.pending?.preview ?: false)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            val info = utteranceId?.let { synchronized(stateLock) { utteranceModes.remove(it) } }
            traceRelay(Stage.TTS_AUDIO_ERROR, info?.pending?.relayMessageId)
            logEvent(
                "speak_error",
                info?.pending?.mode,
                info?.pending?.preview ?: false,
                "code=$errorCode"
            )
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            val info = utteranceId?.let { synchronized(stateLock) { utteranceModes.remove(it) } }
            // QUEUE_FLUSH/stop is not successful completion and may occur before onStart.
            traceRelay(Stage.TTS_STOPPED, info?.pending?.relayMessageId)
        }
    }

    private data class PendingSpeech(
        val text: String,
        val preview: Boolean,
        val mode: SoundMode?,
        val relayMessageId: String? = null
    )

    private data class UtteranceInfo(
        val pending: PendingSpeech,
        val speakStartMs: Long
    )

    private data class ChimeRequest(
        val text: String,
        val speakAfter: Boolean,
        val volume: Float,
        val preview: Boolean,
        val mode: SoundMode,
        val relayMessageId: String? = null,
        var speechTriggered: Boolean = false,
        var speechRunnable: Runnable? = null
    )

    private companion object {
        val notificationSpeechAudioAttributes: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val notificationChimeAudioAttributes: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        const val MAX_TTS_LEAD_MS = 150L
        const val SOUND_TAG = "PayAlertSound"
        const val PREVIEW_TEXT = "This is a PayAlert sound preview."
    }
}
