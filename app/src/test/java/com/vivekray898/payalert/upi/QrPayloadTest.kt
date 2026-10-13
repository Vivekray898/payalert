package com.vivekray898.payalert.upi

import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the generated payment code is actually decodable: the link is encoded with the same
 * hints the app uses, then read back with zxing's decoder.
 */
class QrPayloadTest {

    /** Minimal LuminanceSource over a BitMatrix, so no extra zxing artifact is needed. */
    private class MatrixSource(private val matrix: BitMatrix) : LuminanceSource(matrix.width, matrix.height) {
        override fun getRow(y: Int, row: ByteArray?): ByteArray {
            val out = if (row == null || row.size < width) ByteArray(width) else row
            for (x in 0 until width) {
                out[x] = if (matrix.get(x, y)) 0 else 255.toByte()
            }
            return out
        }

        override fun getMatrix(): ByteArray {
            val out = ByteArray(width * height)
            for (y in 0 until height) getRow(y, null).copyInto(out, y * width)
            return out
        }
    }

    private fun decode(payload: String): String? {
        val matrix = QrEncoder.matrix(payload, 640) ?: return null
        val bitmap = BinaryBitmap(HybridBinarizer(MatrixSource(matrix)))
        return MultiFormatReader().decode(bitmap).text
    }

    @Test
    fun `a upi link round-trips through the qr code`() {
        val link = UpiLink.build("vivek@okhdfcbank", "Vivek Ray", amount = 250.0, note = "Dinner")
        assertEquals(link, decode(link))
    }

    @Test
    fun `decoded payload keeps the payee and currency`() {
        val decoded = decode(UpiLink.build("someone@ybl", "Some One"))!!
        assertTrue(decoded.startsWith("upi://pay?pa=someone%40ybl"))
        assertTrue(decoded.contains("pn=Some%20One"))
        assertTrue(decoded.contains("cu=INR"))
    }

    @Test
    fun `the code keeps a standards-compliant quiet zone`() {
        val matrix = QrEncoder.matrix(UpiLink.build("a@b", "A"), 640)!!
        // The first row of pixels must be blank margin, not modules — a camera needs this.
        assertTrue((0 until matrix.width).none { matrix.get(it, 0) })
        assertTrue((0 until matrix.height).none { matrix.get(0, it) })
    }

    @Test
    fun `an over-long payload is reported as unencodable rather than silently truncated`() {
        val huge = "upi://pay?pa=" + "a".repeat(4000)
        assertNull(QrEncoder.matrix(huge, 640))
        assertNotNull(QrEncoder.matrix(UpiLink.build("a@b", "A"), 640))
    }
}
