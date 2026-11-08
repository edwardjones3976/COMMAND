package com.command.reveille

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/**
 * The decoding the scanner does on each frame, without a camera: codes are made here, at run time, and laid out as a camera's Y plane (one
 * byte of brightness a pixel, rows [rowStride] bytes apart). No image is kept in the repository.
 */
class QrDecoderTest {

    /** [text] as a QR code of [size] pixels, as the luma of a frame whose rows are [rowStride] bytes apart; [inverted] is light on dark. */
    private fun frame(text: String, size: Int = 240, rowStride: Int = size, inverted: Boolean = false, rotated: Boolean = false): ByteArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 4))
        val dark: Byte = if (inverted) 0xF0.toByte() else 0x10
        val light: Byte = if (inverted) 0x10 else 0xF0.toByte()
        val luma = ByteArray(rowStride * size) { 0x7F }
        for (y in 0 until size) for (x in 0 until size) {
            // A quarter turn: the code is read whichever way up it is held.
            val black = if (rotated) matrix[y, size - 1 - x] else matrix[x, y]
            luma[y * rowStride + x] = if (black) dark else light
        }
        return luma
    }

    @Test
    fun `a code is read back as its text`() {
        assertEquals("command-qr:abcDEF123_-xyz0123456789", QrDecoder.decode(frame("command-qr:abcDEF123_-xyz0123456789"), 240, 240, 240))
    }

    @Test
    fun `rows that are longer than the picture is wide are read by their stride`() {
        assertEquals("a printed code", QrDecoder.decode(frame("a printed code", rowStride = 256), 240, 240, 256))
    }

    @Test
    fun `a code held on its side is read as well`() {
        assertEquals("on its side", QrDecoder.decode(frame("on its side", rotated = true), 240, 240, 240))
    }

    @Test
    fun `a code shown light on dark, as some screens show it, is read as well`() {
        assertEquals("shown on a phone in dark mode", QrDecoder.decode(frame("shown on a phone in dark mode", inverted = true), 240, 240, 240))
    }

    @Test
    fun `a frame with no code in it is nothing`() {
        val random = Random(42)
        assertNull(QrDecoder.decode(ByteArray(240 * 240) { random.nextInt().toByte() }, 240, 240, 240))
        assertNull(QrDecoder.decode(ByteArray(240 * 240) { 0x7F }, 240, 240, 240))
    }

    @Test
    fun `a frame whose sizes do not fit its bytes is nothing, not a crash`() {
        assertNull(QrDecoder.decode(ByteArray(10), 240, 240, 240))
        assertNull(QrDecoder.decode(frame("x"), 240, 240, 200))
        assertNull(QrDecoder.decode(ByteArray(0), 0, 0, 0))
    }
}
