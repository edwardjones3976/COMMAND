package com.command.handshake

import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import java.security.SecureRandom

/**
 * New codes for the QR handshake. A new code is only made and shown: it is registered when it is scanned, like any other code, so a code
 * that was never printed is never one an alarm asks for.
 */
object QrCodes {
    private const val PREFIX = "command-qr:"
    private const val RANDOM_BYTES = 16 // 22 characters of base64url without padding
    private val random = SecureRandom()

    /** A new payload: `command-qr:` and 22 base64url characters from [SecureRandom]. */
    fun newPayload(): String {
        val bytes = ByteArray(RANDOM_BYTES).also { random.nextBytes(it) }
        return PREFIX + Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** The code of [payload] as dark and light modules, [size] by [size], with the quiet border a reader needs around it. */
    fun matrix(payload: String, size: Int): BitMatrix =
        QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to QUIET_MODULES))

    /** The code of [payload] as a picture, dark on white whatever the theme, so that it prints and scans as it is. */
    fun image(payload: String, size: Int): ImageBitmap {
        val bits = matrix(payload, size)
        val pixels = IntArray(bits.width * bits.height) { i -> if (bits.get(i % bits.width, i / bits.width)) DARK else LIGHT }
        return Bitmap.createBitmap(pixels, bits.width, bits.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    private const val QUIET_MODULES = 4
    private const val DARK = 0xFF000000.toInt()
    private const val LIGHT = 0xFFFFFFFF.toInt()
}
