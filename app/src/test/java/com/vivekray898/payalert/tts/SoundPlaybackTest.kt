package com.vivekray898.payalert.tts

import com.vivekray898.payalert.settings.SoundMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SoundPlaybackTest {

    @Test
    fun `silent mode only vibrates when enabled`() {
        assertEquals(PlaybackDecision(false, false, false), decidePlayback(SoundMode.SILENT, false))
        assertEquals(PlaybackDecision(false, false, true), decidePlayback(SoundMode.SILENT, true))
    }

    @Test
    fun `voice only never adds chime or vibration`() {
        assertEquals(PlaybackDecision(false, true, false), decidePlayback(SoundMode.SPEECH, false))
        assertEquals(PlaybackDecision(false, true, false), decidePlayback(SoundMode.SPEECH, true))
    }

    @Test
    fun `chime only follows the vibration toggle`() {
        assertEquals(PlaybackDecision(true, false, false), decidePlayback(SoundMode.CHIME_ONLY, false))
        assertEquals(PlaybackDecision(true, false, true), decidePlayback(SoundMode.CHIME_ONLY, true))
    }

    @Test
    fun `chime then voice follows the vibration toggle`() {
        assertEquals(
            PlaybackDecision(true, true, false),
            decidePlayback(SoundMode.CHIME_AND_SPEECH, false)
        )
        assertEquals(
            PlaybackDecision(true, true, true),
            decidePlayback(SoundMode.CHIME_AND_SPEECH, true)
        )
    }
}
