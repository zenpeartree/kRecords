package com.zenpeartree.krecords

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrCodeGenerator {
    fun generate(content: String, sizePx: Int = 1024): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 8,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
            EncodeHintType.CHARACTER_SET to Charsets.UTF_8.name(),
        )
        val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        return matrix.toBitmap()
    }

    private fun BitMatrix.toBitmap(): Bitmap {
        val width = width
        val height = height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
