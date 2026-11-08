package com.command.reveille

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Reads a QR code out of one camera frame. Pure: it touches no camera and no Android class, so it is tested with codes made at run time.
 * The code may be held any way up (the finder patterns do not care), and dark on light or light on dark (a code on a phone in dark mode).
 */
object QrDecoder {
    private val HINTS = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    )

    /**
     * The text of the QR code in [luma], or null when there is none (or the sizes do not fit the bytes). [luma] is a frame's Y plane: one
     * byte of brightness a pixel, [width] by [height] pixels, each row [rowStride] bytes after the one before it.
     */
    fun decode(luma: ByteArray, width: Int, height: Int, rowStride: Int): String? {
        if (width <= 0 || height <= 0 || rowStride < width || luma.size < rowStride * (height - 1) + width) return null
        // The last row of a camera's plane may stop at the picture's edge; zxing wants every row whole.
        val data = if (luma.size >= rowStride * height) luma else luma.copyOf(rowStride * height)
        val source = PlanarYUVLuminanceSource(data, rowStride, height, 0, 0, width, height, false)
        return read(source) ?: read(source.invert())
    }

    private fun read(source: LuminanceSource): String? = try {
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), HINTS).text
    } catch (_: ReaderException) {
        null
    } catch (_: RuntimeException) {
        null // a frame of noise can trip zxing in ways it does not declare; it is still only a frame with no code in it
    }
}
