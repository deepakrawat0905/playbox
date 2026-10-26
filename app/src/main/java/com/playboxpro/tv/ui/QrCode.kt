package com.playboxpro.tv.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Offline QR rendering for the activation screen.
 *
 * The activation code has to be scannable from across a room, so the matrix is
 * drawn at one bitmap pixel per module and scaled up by the ImageView with
 * nearest-neighbour filtering rather than being anti-aliased into mush.
 */
object QrCode {

    /**
     * Encodes [content] as a black-on-white QR bitmap.
     *
     * Returns null rather than throwing when the content cannot be encoded, so
     * the caller can simply leave the QR slot blank.
     */
    fun encode(content: String, sizePx: Int = 512): Bitmap? {
        if (content.isBlank()) return null

        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
            val matrix = QRCodeWriter().encode(
                content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints
            )

            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    pixels[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }

            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, w, 0, 0, w, h)
            }
        } catch (_: Exception) {
            null
        }
    }
}
