package com.example.shareadd.events

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.security.MessageDigest

/**
 * Offline QR codes (ZXing core). The QR encodes exactly the link the user saved — it is not expanded,
 * shortened, rewritten or tracked. QR images are temporary files in cache/shareadd_qr and are not backed up
 * (they can always be generated again from the saved link).
 */
object QrCodes {
    private const val DIR = "shareadd_qr"
    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    /** Pure QR matrix (no Android classes) — used by the app and by the decoding tests. */
    fun matrix(text: String, sizePx: Int = 0): BitMatrix {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 2
        )
        return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    }

    fun bitmap(text: String, sizePx: Int = 720): Bitmap {
        val m = matrix(text, sizePx)
        val pixels = IntArray(m.width * m.height)
        for (y in 0 until m.height) {
            val row = y * m.width
            for (x in 0 until m.width) pixels[row + x] = if (m.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.ARGB_8888)
    }

    /** Writes the QR image for [text] into the app's QR cache folder and returns the file. Old QR files are removed. */
    fun savePng(context: Context, text: String): File {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { if (it.isFile && it.name.startsWith("qr_") && now - it.lastModified() > MAX_AGE_MS) it.delete() }
        val name = "qr_" + MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it) } + ".png"
        val file = File(dir, name)
        val bmp = bitmap(text)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    /**
     * Shares the QR image, optionally with [text]. How receiving apps show image + text together differs by app.
     */
    fun share(context: Context, file: File, text: String?, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            if (!text.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, text)
            clipData = ClipData.newRawUri("QR", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }
}
