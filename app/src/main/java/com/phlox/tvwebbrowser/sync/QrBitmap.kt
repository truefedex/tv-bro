package com.phlox.tvwebbrowser.sync

import android.graphics.Bitmap
import android.graphics.Color
import io.nayuki.qrcodegen.QrCode

/** QR codes as bitmaps: dark modules on white, with the quiet zone around them that readers need */
object QrBitmap {
    private const val QUIET_ZONE = 4

    /** @param moduleSize pixels of a module: the bitmap is drawn without scaling blur at that size */
    fun of(text: String, moduleSize: Int = 8): Bitmap {
        val qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
        val modules = qr.size + QUIET_ZONE * 2
        val size = modules * moduleSize
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val moduleY = y / moduleSize - QUIET_ZONE
            for (x in 0 until size) {
                val moduleX = x / moduleSize - QUIET_ZONE
                pixels[y * size + x] = if (qr.getModule(moduleX, moduleY)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }
}
