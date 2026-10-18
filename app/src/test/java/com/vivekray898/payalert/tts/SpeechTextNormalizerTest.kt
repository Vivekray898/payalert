package com.vivekray898.payalert.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextNormalizerTest {

    @Test
    fun `splits PayAlert into two spoken words`() {
        assertEquals(
            "This is a Pay Alert sound preview.",
            SpeechTextNormalizer.forSpeech("This is a PayAlert sound preview.")
        )
    }

    @Test
    fun `normalizes brand casing without changing surrounding text`() {
        assertEquals(
            "Pay Alert voice is ready. Pay Alert.",
            SpeechTextNormalizer.forSpeech("Payalert voice is ready. PAYALERT.")
        )
    }

    @Test
    fun `leaves unrelated words unchanged`() {
        assertEquals(
            "Payment received from Google Pay.",
            SpeechTextNormalizer.forSpeech("Payment received from Google Pay.")
        )
    }
}
