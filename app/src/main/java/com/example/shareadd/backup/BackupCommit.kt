package com.example.shareadd.backup

import android.content.Context
import com.example.shareadd.Category
import com.example.shareadd.detectImageExtension
import com.example.shareadd.events.commitEventsJson
import com.example.shareadd.events.eventsToJson
import com.example.shareadd.events.templatesToJson
import com.example.shareadd.getShareAddImagesDir
import com.example.shareadd.isDecodableImage
import com.example.shareadd.isTrustedImageRef
import com.example.shareadd.placesToJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Applying an import (all-or-nothing as far as the app can guarantee):
 *  1. staged images are moved into the private image store under new, unused names
 *     (if a move fails, the files moved by this import are removed and nothing else changes);
 *  2. the complete new state of every affected store is written to a journal file
 *     (written to a temporary file, synced, then renamed — so it is either complete or absent);
 *  3. each store is written synchronously (SharedPreferences.commit) from the journal;
 *  4. the journal is deleted.
 * If the app stops between 3 and 4, the next start finds the journal and writes it again (roll forward),
 * so the stores never stay half old / half new. Before step 2 completes, the old data is untouched.
 */
object BackupFiles {
    fun stagingRoot(context: Context) = File(context.filesDir, "shareadd_import_staging")
    fun journal(context: Context) = File(context.filesDir, "shareadd_import_journal.json")
    fun journalTemp(context: Context) = File(context.filesDir, "shareadd_import_journal.json.tmp")

    fun newStagingDir(context: Context): File =
        File(stagingRoot(context), "session_${System.currentTimeMillis()}_${UUID.randomUUID()}").apply { mkdirs() }

    /** Deletes a staging folder created by an import — refuses anything outside the staging root. */
    fun deleteStagingDir(context: Context, dir: File?) {
        if (dir == null) return
        val root = stagingRoot(context).canonicalFile
        val target = dir.canonicalFile
        if (target.parentFile == root && target.name.startsWith("session_")) target.deleteRecursively()
    }

    /** Removes staging folders left by an interrupted preview (they belong to the import feature only). */
    fun cleanupStaleStaging(context: Context) {
        stagingRoot(context).listFiles()?.forEach { deleteStagingDir(context, it) }
    }
}

/** App-side image check used while staging: content must be PNG/JPEG/WebP and decodable. */
fun validateStagedImage(file: File): String? {
    val header = ByteArray(16)
    val n = file.inputStream().use { it.read(header) }
    if (n <= 0) return null
    val ext = detectImageExtension(header.copyOf(n)) ?: return null
    return if (isDecodableImage(file)) ext else null
}

/** Legacy references from old backups are kept only if they are content:// URIs (never file paths). */
fun isTrustedLegacyBackupRef(context: Context, ref: String): Boolean =
    ref.startsWith("content://", ignoreCase = true) && isTrustedImageRef(context, ref)

class ImportCommitException(message: String) : Exception(message)

fun commitImport(context: Context, plan: ImportPlan) {
    val imagesDir = getShareAddImagesDir(context).canonicalFile
    val moved = mutableListOf<File>()

    try {
        for ((source, targetPath) in plan.imagesToCommit) {
            val target = File(targetPath)
            if (target.canonicalFile.parentFile != imagesDir || target.exists()) {
                throw ImportCommitException("invalid image target")
            }
            if (!source.renameTo(target)) {
                source.copyTo(target)
                if (!target.exists() || target.length() != source.length()) throw ImportCommitException("image copy failed")
            }
            moved.add(target)
        }

        val journal = JSONObject()
        journal.put("journalVersion", 1)
        journal.put("places", placesToJson(plan.places))
        plan.categories?.let { journal.put("categories", categoriesJson(it)) }
        plan.events?.let { journal.put("events", eventsToJson(it)) }
        plan.templates?.let { journal.put("templates", templatesToJson(it)) }
        plan.settings?.let { journal.put("settings", JSONObject().put("arabic", it.arabic).put("darkMode", it.darkMode)) }

        val temp = BackupFiles.journalTemp(context)
        FileOutputStream(temp).use { out ->
            out.write(journal.toString().toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!temp.renameTo(BackupFiles.journal(context))) {
            temp.delete()
            throw ImportCommitException("journal could not be written")
        }
    } catch (e: Exception) {
        // nothing was applied yet: remove only the image files this import moved in
        moved.forEach { it.delete() }
        throw e
    }

    // from here on the journal exists: apply it (and re-apply on next start if interrupted)
    if (!applyImportJournal(context)) throw ImportCommitException("import could not be written; it will be completed at next start")
}

/** Called at start (before stores are read) and after writing a journal. Returns true when no journal remains. */
fun applyImportJournal(context: Context): Boolean {
    BackupFiles.journalTemp(context).delete() // an incomplete journal was never committed
    val file = BackupFiles.journal(context)
    if (!file.exists()) return true

    val journal = try {
        JSONObject(file.readText(Charsets.UTF_8))
    } catch (_: Exception) {
        // cannot happen for a renamed complete journal; keep it aside for inspection, do not apply it
        file.renameTo(File(file.parentFile, "shareadd_import_journal_unreadable_${System.currentTimeMillis()}.json"))
        return false
    }

    var ok = context.getSharedPreferences("shareadd_data", Context.MODE_PRIVATE).edit()
        .putString("places", journal.getString("places")).commit()

    if (journal.has("categories")) {
        ok = ok && context.getSharedPreferences("shareadd_categories", Context.MODE_PRIVATE).edit()
            .putString("categories", journal.getString("categories")).commit()
    }
    ok = ok && commitEventsJson(
        context,
        if (journal.has("events")) journal.getString("events") else null,
        if (journal.has("templates")) journal.getString("templates") else null
    )
    if (journal.has("settings")) {
        val s = journal.getJSONObject("settings")
        ok = ok && context.getSharedPreferences("shareadd_settings", Context.MODE_PRIVATE).edit()
            .putBoolean("arabic", s.getBoolean("arabic"))
            .putBoolean("darkMode", s.getBoolean("darkMode"))
            .commit()
    }

    if (ok) file.delete()
    return ok
}

private fun categoriesJson(categories: List<Category>): String {
    val array = JSONArray()
    categories.take(15).forEach { array.put(JSONObject().put("ar", it.ar).put("en", it.en)) }
    return array.toString()
}
