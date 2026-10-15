package com.vivekray898.payalert.tts

import com.vivekray898.payalert.settings.SoundMode

/** Pure result of choosing the side effects for one announcement. */
data class PlaybackDecision(
    val playChime: Boolean,
    val speak: Boolean,
    val vibrate: Boolean
)

/**
 * Maps the persisted sound mode to side effects without touching Android APIs or app data.
 */
fun decidePlayback(mode: SoundMode, vibrate: Boolean): PlaybackDecision = when (mode) {
    SoundMode.SILENT -> PlaybackDecision(
        playChime = false,
        speak = false,
        vibrate = vibrate
    )
    SoundMode.SPEECH -> PlaybackDecision(
        playChime = false,
        speak = true,
        vibrate = false
    )
    SoundMode.CHIME_ONLY -> PlaybackDecision(
        playChime = true,
        speak = false,
        vibrate = vibrate
    )
    SoundMode.CHIME_AND_SPEECH -> PlaybackDecision(
        playChime = true,
        speak = true,
        vibrate = vibrate
    )
}
