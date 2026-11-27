package com.nodeseek.app.ui.share

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

data class QrBitMatrix(
    val width: Int,
    val height: Int,
    private val bits: BooleanArray,
) {
    fun get(x: Int, y: Int): Boolean {
        if (x !in 0 until width || y !in 0 until height) return false
        return bits[y * width + x]
    }

    fun darkModuleCount(): Int = bits.count { it }
}

/**
 * Pure-Kotlin / ZXing QR code matrix generator (zero `android.*` dependencies, 100% JVM-testable).
 */
object TopicLongImageQrGenerator {

    fun generateQrMatrix(
        content: String,
        sizeModulesHint: Int = 0,
    ): QrBitMatrix {
        val trimmed = content.trim()
        require(trimmed.isNotEmpty()) { "QR content must not be blank" }
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(
            trimmed,
            BarcodeFormat.QR_CODE,
            sizeModulesHint.coerceAtLeast(0),
            sizeModulesHint.coerceAtLeast(0),
            hints,
        )
        val w = matrix.width
        val h = matrix.height
        val bits = BooleanArray(w * h)
        for (y in 0 until h) {
            val rowOffset = y * w
            for (x in 0 until w) {
                bits[rowOffset + x] = matrix.get(x, y)
            }
        }
        return QrBitMatrix(width = w, height = h, bits = bits)
    }
}
