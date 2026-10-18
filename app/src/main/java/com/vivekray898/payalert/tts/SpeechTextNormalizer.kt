package com.vivekray898.payalert.tts

/**
 * Small pronunciation fixes applied only at the TextToSpeech boundary.
 *
 * Android voice engines commonly treat a camel-case product name as one unfamiliar word. Keeping
 * the branded spelling everywhere else is important for the UI and stored templates, so the
 * spoken alias is deliberately isolated here.
 */
object SpeechTextNormalizer {

    private val PAY_ALERT_BRAND = Regex("(?i)\\bpayalert\\b")

    fun forSpeech(text: String): String =
        text.replace(PAY_ALERT_BRAND, "Pay Alert")
}
