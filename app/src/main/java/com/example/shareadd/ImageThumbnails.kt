package com.example.shareadd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.LruCache

/**
 * PERF-01: previews are decoded at (about) the size they are shown, off the main thread,
 * with the photo's EXIF orientation applied. Stored image files are only read, never changed.
 */
object ImageThumbnails {
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 16).toInt().coerceAtLeast(4 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private fun key(ref: String, targetPx: Int) = "$targetPx|$ref"

    fun cached(ref: String, targetPx: Int): Bitmap? = cache.get(key(ref, targetPx))

    /** Decodes a preview whose shorter side is at least [targetPx] (power-of-two sampling keeps the aspect ratio). */
    fun load(context: Context, ref: String, targetPx: Int): Bitmap? {
        cached(ref, targetPx)?.let { return it }
        return try {
            // a bounds-only decode always returns null by design; only the sizes in [bounds] are used
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val stream = openShareAddImageInputStream(context, ref) ?: return null
            stream.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetPx)
            }
            val decoded = openShareAddImageInputStream(context, ref)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
            val oriented = applyOrientation(decoded, readOrientation(context, ref))
            cache.put(key(ref, targetPx), oriented)
            oriented
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    fun sampleSize(width: Int, height: Int, targetPx: Int): Int {
        if (targetPx <= 0) return 1
        var sample = 1
        while (minOf(width, height) / (sample * 2) >= targetPx) sample *= 2
        return sample
    }

    private fun readOrientation(context: Context, ref: String): Int = try {
        openShareAddImageInputStream(context, ref)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.preScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.preScale(-1f, 1f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
