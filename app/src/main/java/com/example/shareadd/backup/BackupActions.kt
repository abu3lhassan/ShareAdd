package com.example.shareadd.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.example.shareadd.getShareAddImagesDir
import com.example.shareadd.openShareAddImageInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

sealed class ExportOutcome {
    data class Success(val stats: ExportStats) : ExportOutcome()
    /** [incompleteFileRemoved] = the partially written file could be deleted from the chosen location */
    data class Failed(val incompleteFileRemoved: Boolean) : ExportOutcome()
}

sealed class PreviewOutcome {
    data class Ready(val staged: StagedBackup, val fileName: String) : PreviewOutcome()
    data class Rejected(val problem: BackupProblem, val version: Int = 0) : PreviewOutcome()
}

/**
 * BKP-01: streams Backup v2 to the document chosen in the Android file picker, off the main thread.
 * Success only when every byte was written and the stream was closed without error.
 * Note: this confirms the hand-off to the document provider (e.g. Google Drive), not a completed cloud upload.
 */
suspend fun exportBackup(
    context: Context,
    uri: Uri,
    snapshot: BackupSnapshot,
    onProgress: (done: Int, total: Int) -> Unit
): ExportOutcome = withContext(Dispatchers.IO) {
    try {
        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("no output stream")
        val stats = stream.use { out ->
            // cancellation (e.g. the screen is closed or recreated) stops the export between chunks
            writeBackupV2(out, snapshot, { ref -> openShareAddImageInputStream(context, ref) }, onProgress) { coroutineContext.ensureActive() }
        }
        ExportOutcome.Success(stats)
    } catch (e: CancellationException) {
        markExportInterrupted(context)
        withContext(NonCancellable) { removeIncomplete(context, uri) }
        throw e
    } catch (_: Exception) {
        ExportOutcome.Failed(removeIncomplete(context, uri))
    }
}

/** LIFE-01: an export stopped because the screen was closed/recreated is reported the next time the app opens. */
fun markExportInterrupted(context: Context) {
    context.getSharedPreferences("shareadd_settings", Context.MODE_PRIVATE).edit().putBoolean("exportInterrupted", true).apply()
}

fun consumeExportInterrupted(context: Context): Boolean {
    val prefs = context.getSharedPreferences("shareadd_settings", Context.MODE_PRIVATE)
    val interrupted = prefs.getBoolean("exportInterrupted", false)
    if (interrupted) prefs.edit().remove("exportInterrupted").apply()
    return interrupted
}

/**
 * Deletes the incomplete document. The provider handles the closing of the file asynchronously; deleting at the
 * same moment crashed the system Downloads provider on the test emulator (Android 16), so the delete waits briefly.
 */
private suspend fun removeIncomplete(context: Context, uri: Uri): Boolean {
    delay(1500)
    return try {
        DocumentsContract.deleteDocument(context.contentResolver, uri)
    } catch (_: Exception) {
        false
    }
}

/** Reads, validates and stages a backup without changing any app data. */
suspend fun readBackupForPreview(
    context: Context,
    uri: Uri,
    onProgress: (imagesDone: Int) -> Unit
): PreviewOutcome = withContext(Dispatchers.IO) {
    val staging = BackupFiles.newStagingDir(context)
    try {
        val staged = context.contentResolver.openInputStream(uri)?.use { input ->
            stageBackup(
                input = input,
                stagingDir = staging,
                validateImage = ::validateStagedImage,
                isTrustedLegacyRef = { ref -> isTrustedLegacyBackupRef(context, ref) },
                onProgress = onProgress,
                checkCancelled = { coroutineContext.ensureActive() }
            )
        } ?: throw BackupException(BackupProblem.DAMAGED, "cannot open file")

        if (staged.places.isEmpty() && staged.events.isNullOrEmpty() && staged.templates.isNullOrEmpty() && staged.categories == null) {
            BackupFiles.deleteStagingDir(context, staging)
            PreviewOutcome.Rejected(BackupProblem.EMPTY)
        } else {
            PreviewOutcome.Ready(staged, displayName(context, uri))
        }
    } catch (e: CancellationException) {
        BackupFiles.deleteStagingDir(context, staging)
        throw e
    } catch (e: BackupException) {
        BackupFiles.deleteStagingDir(context, staging)
        PreviewOutcome.Rejected(e.problem, e.version)
    } catch (_: Exception) {
        BackupFiles.deleteStagingDir(context, staging)
        PreviewOutcome.Rejected(BackupProblem.DAMAGED)
    }
}

/** Builds the plan for the preview (cheap: no file work). */
fun planForPreview(context: Context, current: CurrentData, staged: StagedBackup, mode: ImportMode): ImportPlan =
    planImport(current, staged, mode, getShareAddImagesDir(context))

/** Applies a confirmed plan (see [commitImport]); staging is removed afterwards. */
suspend fun applyImport(context: Context, plan: ImportPlan, staged: StagedBackup) = withContext(Dispatchers.IO) {
    try {
        commitImport(context, plan)
    } finally {
        BackupFiles.deleteStagingDir(context, staged.stagingDir)
    }
}

private fun displayName(context: Context, uri: Uri): String = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: ""
} catch (_: Exception) {
    ""
}
