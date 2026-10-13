package com.vivekray898.payalert.upi

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR encoding kept separate from Android's Bitmap so the exact hints used in the app can be
 * round-trip verified in a JVM unit test (encode, then decode with zxing's reader).
 */
object QrEncoder {

    /**
     * Error correction M balances density against a phone camera's error tolerance, and the
     * margin is kept at the 4-module quiet zone the QR spec requires so the code scans even
     * when it is drawn on a dark surface.
     */
    private fun hints(): Map<EncodeHintType, Any> = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 4,
        EncodeHintType.CHARACTER_SET to "UTF-8"
    )

    /** Returns the module matrix, or null when the payload cannot be encoded. */
    fun matrix(content: String, size: Int): BitMatrix? = try {
        QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints())
    } catch (error: Exception) {
        null
    }
}
