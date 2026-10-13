package com.example.shareadd

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/** Result of reading a local store. ok = false means the stored text exists but could not be read completely. */
data class StoreLoadResult<T>(
    val value: T,
    val ok: Boolean,
    val recoveryFile: String? = null,
    /** places: ids generated for older records */
    val generatedIds: Int = 0,
    /** places: duplicate ids that were given a new id */
    val repairedIds: Int = 0
)

/**
 * Keeps an untouched copy of unreadable stored text inside the app's private files directory
 * (filesDir/shareadd_recovery). The same content is stored once. The content is never logged.
 */
fun saveRecoveryCopy(context: Context, storeName: String, raw: String): String? {
    return try {
        val dir = File(context.filesDir, "shareadd_recovery").apply { mkdirs() }
        val hash = sha256(raw).take(16)
        dir.listFiles()?.firstOrNull { it.name.startsWith("${storeName}_") && it.name.contains(hash) }?.let {
            return it.name
        }
        val file = File(dir, "${storeName}_${System.currentTimeMillis()}_$hash.json")
        file.writeText(raw)
        file.name
    } catch (_: Exception) {
        null
    }
}

/**
 * SEC-02: image references the app is allowed to read, display, share or export.
 *  - absolute paths only inside the private image store (filesDir/shareadd_images), after resolving ".." and links;
 *  - content:// URIs (legacy references from older builds, granted by the system).
 * Everything else (other absolute paths, file:// URIs, unknown schemes) is refused.
 */
fun isTrustedImageRef(context: Context, imageRef: String): Boolean {
    if (imageRef.isBlank()) return false
    return try {
        val file = File(imageRef)
        if (file.isAbsolute) {
            val root = getShareAddImagesDir(context).canonicalFile
            val target = file.canonicalFile
            target.parentFile == root
        } else {
            Uri.parse(imageRef).scheme.equals("content", ignoreCase = true)
        }
    } catch (_: Exception) {
        false
    }
}

/** Image formats ShareAdd stores; detected from the file content, not from the name. */
fun detectImageExtension(header: ByteArray): String? {
    fun startsWith(vararg bytes: Int) = header.size >= bytes.size && bytes.indices.all { (header[it].toInt() and 0xFF) == bytes[it] }
    return when {
        startsWith(0x89, 0x50, 0x4E, 0x47) -> "png"
        startsWith(0xFF, 0xD8, 0xFF) -> "jpg"
        header.size >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
        else -> null
    }
}

/** True when the file decodes as an image (bounds only; the file itself is not modified). */
fun isDecodableImage(file: File): Boolean {
    return try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        options.outWidth > 0 && options.outHeight > 0
    } catch (_: Exception) {
        false
    }
}
