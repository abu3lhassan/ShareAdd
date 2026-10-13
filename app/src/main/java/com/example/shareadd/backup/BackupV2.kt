package com.example.shareadd.backup

import com.example.shareadd.Category
import com.example.shareadd.Place
import com.example.shareadd.assignPlaceIds
import com.example.shareadd.categoryFromLegacy
import com.example.shareadd.detectImageExtension
import com.example.shareadd.events.InvitationTemplate
import com.example.shareadd.events.ShareEvent
import com.example.shareadd.events.readEvent
import com.example.shareadd.events.readTemplate
import com.example.shareadd.events.writeEvent
import com.example.shareadd.events.writeTemplate
import com.example.shareadd.placeImportKey
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PushbackReader
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

const val BACKUP_FORMAT = "shareadd-backup"
const val BACKUP_VERSION = 2

/** 48 KiB of image bytes per base64 chunk (multiple of 3, so chunks decode independently). */
private const val CHUNK_BYTES = 48 * 1024

data class BackupSettings(val arabic: Boolean, val darkMode: Boolean)

data class BackupSnapshot(
    val places: List<Place>,
    val categories: List<Category>,
    val settings: BackupSettings,
    val events: List<ShareEvent>,
    val templates: List<InvitationTemplate>,
    val exportedAt: Long,
    val appVersionName: String,
    val appVersionCode: Long
)

data class ExportStats(
    val places: Int,
    val imagesWritten: Int,
    val imagesMissing: Int,
    val events: Int,
    val templates: Int
)

// ======================================================================= export (streaming)

/**
 * Writes Backup v2 directly to [out]. Only one image chunk is held in memory at a time.
 * [openImage] must return null for images that cannot / may not be read (they are counted as missing).
 * Throws on any write error; the caller must not report success in that case.
 */
fun writeBackupV2(
    out: OutputStream,
    snapshot: BackupSnapshot,
    openImage: (String) -> InputStream?,
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    checkCancelled: () -> Unit = {}
): ExportStats {
    val writer = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8), 64 * 1024)
    val json = JsonOut(writer)
    var written = 0
    var missing = 0
    val totalImages = snapshot.places.sumOf { it.images.size }
    var processed = 0

    json.beginObject()
    json.field("format", BACKUP_FORMAT)
    json.field("formatVersion", BACKUP_VERSION)
    json.field("exportedAt", snapshot.exportedAt)
    json.name("app").beginObject()
        .field("versionName", snapshot.appVersionName)
        .field("versionCode", snapshot.appVersionCode)
        .endObject()
    json.name("settings").beginObject()
        .field("arabic", snapshot.settings.arabic)
        .field("darkMode", snapshot.settings.darkMode)
        .endObject()

    json.name("categories").beginArray()
    snapshot.categories.forEach { json.beginObject().field("ar", it.ar).field("en", it.en).endObject() }
    json.endArray()

    json.name("templates").beginArray()
    snapshot.templates.filterNot { it.builtIn }.forEach { writeTemplate(json, it) }
    json.endArray()

    json.name("events").beginArray()
    snapshot.events.forEach { writeEvent(json, it) }
    json.endArray()

    json.name("places").beginArray()
    for (place in snapshot.places) {
        json.beginObject()
        writePlaceFields(json, place)
        json.name("images").beginArray()
        for (ref in place.images) {
            checkCancelled()
            val ok = writeImage(json, ref, openImage, checkCancelled)
            if (ok) written++ else missing++
            processed++
            onProgress(processed, totalImages)
        }
        json.endArray()
        json.endObject()
    }
    json.endArray()
    json.endObject()
    writer.flush()

    return ExportStats(snapshot.places.size, written, missing, snapshot.events.size, snapshot.templates.count { !it.builtIn })
}

private fun writePlaceFields(json: JsonOut, p: Place) {
    json.field("id", p.id)
    json.field("placeName", p.placeName)
    json.field("title", p.title)
    json.field("url", p.url)
    json.field("description", p.description)
    json.field("privateNotes", p.privateNotes)
    json.field("categoryAr", p.categoryAr)
    json.field("categoryEn", p.categoryEn)
    json.field("rating", p.rating)
    json.field("favorite", p.favorite)
    json.field("pinned", p.pinned)
    json.field("createdAt", p.createdAt)
    json.field("updatedAt", p.updatedAt)
}

/** Streams one image as base64 chunks; returns false (and writes nothing) when it cannot be opened or is not an image. */
private fun writeImage(json: JsonOut, ref: String, openImage: (String) -> InputStream?, checkCancelled: () -> Unit): Boolean {
    val input = try { openImage(ref) } catch (_: Exception) { null } ?: return false
    input.use { stream ->
        val first = ByteArray(CHUNK_BYTES)
        val firstLen = readFully(stream, first)
        if (firstLen <= 0) return false
        val type = detectImageExtension(first.copyOfRange(0, minOf(firstLen, 16))) ?: return false

        val digest = MessageDigest.getInstance("SHA-256")
        val encoder = Base64.getEncoder()
        var size = 0L

        json.beginObject()
        json.field("name", File(ref).name.ifBlank { "image" })
        json.field("type", type)
        json.name("data").beginArray()
        var chunk = first
        var len = firstLen
        while (len > 0) {
            checkCancelled()
            digest.update(chunk, 0, len)
            size += len
            json.value(encoder.encodeToString(if (len == chunk.size) chunk else chunk.copyOf(len)))
            chunk = ByteArray(CHUNK_BYTES)
            len = readFully(stream, chunk)
        }
        json.endArray()
        json.field("size", size)
        json.field("sha256", digest.digest().joinToString("") { "%02x".format(it) })
        json.endObject()
    }
    return true
}

private fun readFully(input: InputStream, buf: ByteArray): Int {
    var total = 0
    while (total < buf.size) {
        val n = input.read(buf, total, buf.size - total)
        if (n < 0) break
        total += n
    }
    return total
}

// ======================================================================= import: validate + stage

enum class BackupProblem { NOT_A_BACKUP, UNSUPPORTED_VERSION, DAMAGED, IMAGE_CHECK_FAILED, EMPTY }

class BackupException(val problem: BackupProblem, message: String, val version: Int = 0) : Exception(message)

sealed class StagedImage {
    /** image bytes decoded and validated into the staging folder */
    data class Embedded(val file: File, val extension: String) : StagedImage()
    /** legacy reference (content://) kept as it was, without image bytes */
    data class LegacyRef(val ref: String) : StagedImage()
}

data class StagedPlace(val place: Place, val images: List<StagedImage>)

data class StageStats(
    val imagesEmbedded: Int = 0,
    val imagesRejected: Int = 0,
    val legacyRefsKept: Int = 0,
    val refsRejected: Int = 0
)

data class StagedBackup(
    val formatVersion: Int,
    val places: List<StagedPlace>,
    /** null = section not present in the file (existing data is kept) */
    val categories: List<Category>?,
    val settings: BackupSettings?,
    val events: List<ShareEvent>?,
    val templates: List<InvitationTemplate>?,
    val stats: StageStats,
    val stagingDir: File
)

/**
 * Reads a whole backup (old array format = v1, or v2) and stages its images into [stagingDir].
 * Nothing outside [stagingDir] is touched. Any damage, truncation or unsupported version throws
 * [BackupException]; the caller deletes [stagingDir] in that case.
 *
 * @param validateImage returns the image extension when the staged file is a real image, else null
 * @param isTrustedLegacyRef decides whether an image reference without bytes may be kept (content:// only)
 */
fun stageBackup(
    input: InputStream,
    stagingDir: File,
    validateImage: (File) -> String?,
    isTrustedLegacyRef: (String) -> Boolean,
    onProgress: (imagesDone: Int) -> Unit = {},
    checkCancelled: () -> Unit = {}
): StagedBackup {
    stagingDir.mkdirs()
    val reader = PushbackReader(BufferedReader(InputStreamReader(input, Charsets.UTF_8), 64 * 1024), 1)
    var first: Int
    do { first = reader.read() } while (first == ' '.code || first == '\n'.code || first == '\r'.code || first == '\t'.code || first == 0xFEFF)
    if (first == -1) throw BackupException(BackupProblem.EMPTY, "empty file")
    reader.unread(first)

    val ctx = StageContext(stagingDir, validateImage, isTrustedLegacyRef, onProgress, checkCancelled)
    try {
        val json = JsonIn(reader)
        val result = when (first.toChar()) {
            '[' -> stageV1(json, ctx)
            '{' -> stageV2(json, ctx)
            else -> throw BackupException(BackupProblem.NOT_A_BACKUP, "not a ShareAdd backup")
        }
        json.endDocument()
        return result
    } catch (e: BackupException) {
        throw e
    } catch (e: JsonFormatException) {
        throw BackupException(BackupProblem.DAMAGED, "damaged or incomplete file: ${e.message}")
    } catch (e: IllegalArgumentException) {
        throw BackupException(BackupProblem.DAMAGED, "damaged image data")
    }
}

private class StageContext(
    val dir: File,
    val validateImage: (File) -> String?,
    val isTrustedLegacyRef: (String) -> Boolean,
    val onProgress: (Int) -> Unit,
    val checkCancelled: () -> Unit
) {
    var counter = 0
    var embedded = 0
    var rejected = 0
    var legacyKept = 0
    var refsRejected = 0
    fun stats() = StageStats(embedded, rejected, legacyKept, refsRejected)
    fun newStagingFile(): File { checkCancelled(); return File(dir, "img_${++counter}.part") }
}

private fun stageV1(json: JsonIn, ctx: StageContext): StagedBackup {
    val places = mutableListOf<StagedPlace>()
    json.beginArray()
    while (json.hasNext()) places.add(readStagedPlace(json, ctx, v1 = true))
    json.endArray()
    return StagedBackup(1, places, null, null, null, null, ctx.stats(), ctx.dir)
}

private fun stageV2(json: JsonIn, ctx: StageContext): StagedBackup {
    var format: String? = null
    var version = -1
    var places: MutableList<StagedPlace>? = null
    var categories: MutableList<Category>? = null
    var settings: BackupSettings? = null
    var events: MutableList<ShareEvent>? = null
    var templates: MutableList<InvitationTemplate>? = null

    fun checkHeader() {
        if (format != null && format != BACKUP_FORMAT) throw BackupException(BackupProblem.NOT_A_BACKUP, "not a ShareAdd backup")
        if (version > BACKUP_VERSION) throw BackupException(BackupProblem.UNSUPPORTED_VERSION, "unsupported version $version", version)
    }

    json.beginObject()
    while (json.hasNext()) {
        when (json.nextName()) {
            "format" -> { format = json.nextScalarAsText(); checkHeader() }
            "formatVersion" -> { version = json.nextLong().toInt(); checkHeader() }
            "settings" -> settings = readSettings(json)
            "categories" -> {
                categories = mutableListOf()
                json.beginArray()
                while (json.hasNext()) readCategory(json)?.let { categories.add(it) }
                json.endArray()
            }
            "events" -> {
                events = mutableListOf()
                json.beginArray()
                while (json.hasNext()) events.add(readEvent(json))
                json.endArray()
            }
            "templates" -> {
                templates = mutableListOf()
                json.beginArray()
                while (json.hasNext()) templates.add(readTemplate(json))
                json.endArray()
            }
            "places" -> {
                checkHeader()
                if (format == null || version < 0) throw BackupException(BackupProblem.NOT_A_BACKUP, "missing format header")
                places = mutableListOf()
                json.beginArray()
                while (json.hasNext()) places.add(readStagedPlace(json, ctx, v1 = false))
                json.endArray()
            }
            else -> json.skipValue()
        }
    }
    json.endObject()

    if (format != BACKUP_FORMAT || version < 0) throw BackupException(BackupProblem.NOT_A_BACKUP, "missing format header")
    checkHeader()
    if (version < 2) throw BackupException(BackupProblem.UNSUPPORTED_VERSION, "unsupported version $version", version)
    return StagedBackup(version, places ?: mutableListOf(), categories, settings, events, templates, ctx.stats(), ctx.dir)
}

private fun readSettings(json: JsonIn): BackupSettings {
    var arabic = true
    var dark = true
    json.beginObject()
    while (json.hasNext()) {
        when (json.nextName()) {
            "arabic" -> arabic = json.nextScalarAsText() == "true"
            "darkMode" -> dark = json.nextScalarAsText() == "true"
            else -> json.skipValue()
        }
    }
    json.endObject()
    return BackupSettings(arabic, dark)
}

private fun readCategory(json: JsonIn): Category? {
    var ar = ""
    var en = ""
    json.beginObject()
    while (json.hasNext()) {
        when (json.nextName()) {
            "ar" -> ar = json.nextScalarAsText().trim()
            "en" -> en = json.nextScalarAsText().trim()
            else -> json.skipValue()
        }
    }
    json.endObject()
    return if (ar.isNotBlank() && en.isNotBlank()) Category(ar, en) else null
}

private fun readStagedPlace(json: JsonIn, ctx: StageContext, v1: Boolean): StagedPlace {
    var id = ""
    var placeName: String? = null
    var title = ""
    var url = ""
    var description = ""
    var privateNotes = ""
    var categoryAr = ""
    var categoryEn = ""
    var legacyCategory = ""
    var rating = 0
    var favorite = false
    var pinned = false
    val now = System.currentTimeMillis()
    var createdAt = now
    var updatedAt = now
    val images = mutableListOf<StagedImage>()

    json.beginObject()
    while (json.hasNext()) {
        when (json.nextName()) {
            "id" -> id = json.nextScalarAsText()
            "placeName" -> placeName = json.nextScalarAsText()
            "title" -> title = json.nextScalarAsText()
            "url" -> url = json.nextScalarAsText()
            "description" -> description = json.nextScalarAsText()
            "privateNotes" -> privateNotes = json.nextScalarAsText()
            "categoryAr" -> categoryAr = json.nextScalarAsText()
            "categoryEn" -> categoryEn = json.nextScalarAsText()
            "category" -> legacyCategory = json.nextScalarAsText()
            "rating" -> rating = json.nextScalarAsText().toDoubleOrNull()?.toInt() ?: 0
            "favorite" -> favorite = json.nextScalarAsText() == "true"
            "pinned" -> pinned = json.nextScalarAsText() == "true"
            "createdAt" -> createdAt = json.nextScalarAsText().toDoubleOrNull()?.toLong() ?: now
            "updatedAt" -> updatedAt = json.nextScalarAsText().toDoubleOrNull()?.toLong() ?: now
            "images" -> if (json.peek() == JsonToken.NULL) json.nextNull() else {
                json.beginArray()
                while (json.hasNext()) {
                    val staged = if (v1) readV1Image(json, ctx) else readV2Image(json, ctx)
                    if (staged != null) images.add(staged)
                }
                json.endArray()
            }
            else -> json.skipValue()
        }
    }
    json.endObject()

    val category = if (categoryAr.isBlank() || categoryEn.isBlank()) categoryFromLegacy(legacyCategory) else Category(categoryAr, categoryEn)
    val place = Place(
        id = id,
        placeName = placeName ?: title,
        title = title,
        url = url,
        description = description,
        privateNotes = privateNotes,
        categoryAr = category.ar,
        categoryEn = category.en,
        images = emptyList(),
        rating = rating.coerceIn(0, 5),
        favorite = favorite,
        pinned = pinned,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
    return StagedPlace(place, images)
}

/** v1 image entry: a plain reference string, or {"uri": …, "base64": …}. */
private fun readV1Image(json: JsonIn, ctx: StageContext): StagedImage? {
    if (json.peek() != JsonToken.BEGIN_OBJECT) {
        val ref = json.nextScalarAsText()
        return legacyRef(ref, ctx)
    }
    var uri = ""
    var base64 = ""
    json.beginObject()
    while (json.hasNext()) {
        when (json.nextName()) {
            "uri" -> uri = json.nextScalarAsText()
            "base64" -> base64 = json.nextScalarAsText()
            else -> json.skipValue()
        }
    }
    json.endObject()

    if (base64.isNotBlank()) {
        val file = ctx.newStagingFile()
        val bytes = try { Base64.getMimeDecoder().decode(base64) } catch (_: IllegalArgumentException) { null }
        if (bytes == null) { ctx.rejected++; return null }
        file.writeBytes(bytes)
        return finishStagedFile(file, ctx)
    }
    return if (uri.isNotBlank()) legacyRef(uri, ctx) else null
}

/** v2 image entry: {"name","type","data":[chunks],"size","sha256"} — the hash and size must match. */
private fun readV2Image(json: JsonIn, ctx: StageContext): StagedImage? {
    val file = ctx.newStagingFile()
    val digest = MessageDigest.getInstance("SHA-256")
    var size = 0L
    var expectedSha: String? = null
    var expectedSize = -1L
    var hasData = false
    val decoder = Base64.getDecoder()

    json.beginObject()
    file.outputStream().buffered().use { out ->
        while (json.hasNext()) {
            when (json.nextName()) {
                "data" -> {
                    hasData = true
                    json.beginArray()
                    while (json.hasNext()) {
                        ctx.checkCancelled()
                        val bytes = decoder.decode(json.nextString())
                        digest.update(bytes)
                        size += bytes.size
                        out.write(bytes)
                    }
                    json.endArray()
                }
                "sha256" -> expectedSha = json.nextScalarAsText().lowercase()
                "size" -> expectedSize = json.nextLong()
                else -> json.skipValue()
            }
        }
    }
    json.endObject()

    if (!hasData) { file.delete(); ctx.rejected++; return null }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    if (expectedSha == null || actual != expectedSha || (expectedSize >= 0 && expectedSize != size)) {
        file.delete()
        throw BackupException(BackupProblem.IMAGE_CHECK_FAILED, "image data does not match its checksum")
    }
    return finishStagedFile(file, ctx)
}

private fun finishStagedFile(file: File, ctx: StageContext): StagedImage? {
    val ext = ctx.validateImage(file)
    if (ext == null) {
        file.delete()
        ctx.rejected++
        return null
    }
    val finalFile = File(file.parentFile, file.name.removeSuffix(".part") + "." + ext)
    if (!file.renameTo(finalFile)) {
        file.delete()
        ctx.rejected++
        return null
    }
    ctx.embedded++
    ctx.onProgress(ctx.embedded)
    return StagedImage.Embedded(finalFile, ext)
}

private fun legacyRef(ref: String, ctx: StageContext): StagedImage? {
    return if (ref.isNotBlank() && ctx.isTrustedLegacyRef(ref)) {
        ctx.legacyKept++
        StagedImage.LegacyRef(ref)
    } else {
        if (ref.isNotBlank()) ctx.refsRejected++
        null
    }
}

// ======================================================================= import: plan

enum class ImportMode { SKIP_DUPLICATES, MERGE_ALL, REPLACE_ALL }

data class CurrentData(
    val places: List<Place>,
    val categories: List<Category>,
    val events: List<ShareEvent>,
    val templates: List<InvitationTemplate>
)

data class ImportPlan(
    val mode: ImportMode,
    val formatVersion: Int,
    /** final list of places after import */
    val places: List<Place>,
    val placesInFile: Int,
    val placesAdded: Int,
    val placesSkipped: Int,
    val placesRemoved: Int,
    /** staged image file -> final absolute path inside the private image store (only images of added places) */
    val imagesToCommit: List<Pair<File, String>>,
    val legacyRefsAdded: Int,
    val placeIdsChanged: Int,
    /** null = unchanged */
    val categories: List<Category>?,
    val categoriesAdded: Int,
    val categoriesOverflow: Int,
    val events: List<ShareEvent>?,
    val eventsInFile: Int,
    val eventsAdded: Int,
    val eventsSkipped: Int,
    val eventsRelinked: Int,
    val templates: List<InvitationTemplate>?,
    val templatesInFile: Int,
    val templatesAdded: Int,
    val templatesSkipped: Int,
    /** settings to apply (replace mode only), null = unchanged */
    val settings: BackupSettings?,
    val settingsInFile: Boolean,
    val stats: StageStats
)

/**
 * Computes exactly what an import will do. The same plan is shown in the preview and applied,
 * so preview counts equal the applied result.
 *
 * Modes (unchanged meaning):
 *  - SKIP_DUPLICATES: adds only places that are not already present (same id, or same link / name+category key);
 *  - MERGE_ALL: adds every place from the file; an id that already exists gets a new id;
 *  - REPLACE_ALL: the place list becomes the file's list.
 * Events always end up pointing at the place the file meant (remapped when ids change or a duplicate is skipped).
 * Sections missing from the file (old backups) leave the existing data unchanged.
 */
fun planImport(
    current: CurrentData,
    staged: StagedBackup,
    mode: ImportMode,
    imagesDir: File,
    now: Long = System.currentTimeMillis(),
    newPlaceIdFn: () -> String = { "pl-" + UUID.randomUUID() },
    newEventIdFn: () -> String = { "ev-" + UUID.randomUUID() },
    newTemplateIdFn: () -> String = { "tpl-" + UUID.randomUUID() }
): ImportPlan {
    // ids inside the file: missing ones generated, duplicates repaired (first owner keeps the id)
    val originalIds = staged.places.map { it.place.id.trim() }
    val fileAssigned = assignPlaceIds(staged.places.map { it.place }, newPlaceIdFn).places
    val fileIdToAssigned = HashMap<String, String>()
    originalIds.forEachIndexed { i, original -> if (original.isNotEmpty() && original !in fileIdToAssigned) fileIdToAssigned[original] = fileAssigned[i].id }

    var imageSeq = 0
    fun materialize(index: Int, place: Place): Pair<Place, List<Pair<File, String>>> {
        val commits = mutableListOf<Pair<File, String>>()
        val refs = staged.places[index].images.map { img ->
            when (img) {
                is StagedImage.Embedded -> {
                    val target = File(imagesDir, "shareadd_restore_${now}_${++imageSeq}_${UUID.randomUUID()}.${img.extension}").absolutePath
                    commits.add(img.file to target)
                    target
                }
                is StagedImage.LegacyRef -> img.ref
            }
        }
        return place.copy(images = refs) to commits
    }

    val finalPlaces: MutableList<Place>
    val commits = mutableListOf<Pair<File, String>>()
    var added = 0
    var skipped = 0
    var removed = 0
    var idsChanged = 0
    var legacyAdded = 0
    val placeIdMap = HashMap<String, String>() // id used in the file -> final id

    when (mode) {
        ImportMode.REPLACE_ALL -> {
            finalPlaces = mutableListOf()
            removed = current.places.size
            fileAssigned.forEachIndexed { i, p ->
                val (m, c) = materialize(i, p)
                finalPlaces.add(m); commits.addAll(c); added++
                legacyAdded += staged.places[i].images.count { it is StagedImage.LegacyRef }
                placeIdMap[p.id] = p.id
            }
        }
        ImportMode.SKIP_DUPLICATES -> {
            finalPlaces = current.places.toMutableList()
            val byId = current.places.associateBy { it.id }
            val byKey = HashMap<String, Place>()
            current.places.forEach { byKey.putIfAbsent(placeImportKey(it), it) }
            fileAssigned.forEachIndexed { i, p ->
                val key = placeImportKey(p)
                val existing = byId[p.id] ?: byKey[key]
                if (existing != null) {
                    skipped++
                    placeIdMap[p.id] = existing.id
                } else {
                    val (m, c) = materialize(i, p)
                    finalPlaces.add(m); commits.addAll(c); added++
                    legacyAdded += staged.places[i].images.count { it is StagedImage.LegacyRef }
                    byKey[key] = m
                    placeIdMap[p.id] = m.id
                }
            }
        }
        ImportMode.MERGE_ALL -> {
            finalPlaces = current.places.toMutableList()
            val used = current.places.map { it.id }.toHashSet()
            fileAssigned.forEachIndexed { i, p ->
                var place = p
                if (!used.add(p.id)) {
                    var fresh = newPlaceIdFn()
                    while (!used.add(fresh)) fresh = newPlaceIdFn()
                    place = p.copy(id = fresh)
                    idsChanged++
                }
                val (m, c) = materialize(i, place)
                finalPlaces.add(m); commits.addAll(c); added++
                legacyAdded += staged.places[i].images.count { it is StagedImage.LegacyRef }
                placeIdMap[p.id] = m.id
            }
        }
    }

    // events: point to the place the file meant
    fun remapPlace(placeId: String?): String? {
        if (placeId == null) return null
        val inFile = fileIdToAssigned[placeId] ?: return placeId
        return placeIdMap[inFile] ?: inFile
    }

    var eventsAdded = 0
    var eventsSkipped = 0
    var relinked = 0
    val finalEvents: List<ShareEvent>? = staged.events?.let { fileEvents ->
        val remapped = fileEvents.map { e ->
            val target = remapPlace(e.placeId)
            if (target != e.placeId) relinked++
            e.copy(placeId = target)
        }
        when (mode) {
            ImportMode.REPLACE_ALL -> {
                val seen = HashSet<String>()
                remapped.map { e ->
                    eventsAdded++
                    if (seen.add(e.id)) e else { var f = newEventIdFn(); while (!seen.add(f)) f = newEventIdFn(); e.copy(id = f) }
                }
            }
            ImportMode.SKIP_DUPLICATES -> {
                val ids = current.events.map { it.id }.toHashSet()
                val result = current.events.toMutableList()
                remapped.forEach { e -> if (ids.add(e.id)) { result.add(e); eventsAdded++ } else eventsSkipped++ }
                result
            }
            ImportMode.MERGE_ALL -> {
                val ids = current.events.map { it.id }.toHashSet()
                val result = current.events.toMutableList()
                remapped.forEach { e ->
                    eventsAdded++
                    if (ids.add(e.id)) result.add(e) else { var f = newEventIdFn(); while (!ids.add(f)) f = newEventIdFn(); result.add(e.copy(id = f)) }
                }
                result
            }
        }
    }

    var templatesAdded = 0
    var templatesSkipped = 0
    val finalTemplates: List<InvitationTemplate>? = staged.templates?.let { fileTemplates ->
        val currentCustom = current.templates.filterNot { it.builtIn }
        when (mode) {
            ImportMode.REPLACE_ALL -> {
                val seen = HashSet<String>()
                fileTemplates.map { t ->
                    templatesAdded++
                    if (seen.add(t.id)) t else { var f = newTemplateIdFn(); while (!seen.add(f)) f = newTemplateIdFn(); t.copy(id = f) }
                }
            }
            ImportMode.SKIP_DUPLICATES -> {
                val ids = currentCustom.map { it.id }.toHashSet()
                val result = currentCustom.toMutableList()
                fileTemplates.forEach { t -> if (ids.add(t.id)) { result.add(t); templatesAdded++ } else templatesSkipped++ }
                result
            }
            ImportMode.MERGE_ALL -> {
                val ids = currentCustom.map { it.id }.toHashSet()
                val result = currentCustom.toMutableList()
                fileTemplates.forEach { t ->
                    templatesAdded++
                    if (ids.add(t.id)) result.add(t) else { var f = newTemplateIdFn(); while (!ids.add(f)) f = newTemplateIdFn(); result.add(t.copy(id = f)) }
                }
                result
            }
        }
    }

    var categoriesAdded = 0
    var overflow = 0
    val finalCategories: List<Category>? = staged.categories?.let { fileCats ->
        fun same(a: Category, b: Category) = a.ar.equals(b.ar, true) || a.en.equals(b.en, true)
        val base = if (mode == ImportMode.REPLACE_ALL) mutableListOf() else current.categories.toMutableList()
        fileCats.forEach { c ->
            if (base.none { same(it, c) }) {
                if (base.size < 15) { base.add(c); categoriesAdded++ } else overflow++
            }
        }
        if (base.isEmpty()) current.categories else base
    }

    return ImportPlan(
        mode = mode,
        formatVersion = staged.formatVersion,
        places = finalPlaces,
        placesInFile = staged.places.size,
        placesAdded = added,
        placesSkipped = skipped,
        placesRemoved = removed,
        imagesToCommit = commits,
        legacyRefsAdded = legacyAdded,
        placeIdsChanged = idsChanged,
        categories = finalCategories,
        categoriesAdded = categoriesAdded,
        categoriesOverflow = overflow,
        events = finalEvents,
        eventsInFile = staged.events?.size ?: 0,
        eventsAdded = eventsAdded,
        eventsSkipped = eventsSkipped,
        eventsRelinked = relinked,
        templates = finalTemplates,
        templatesInFile = staged.templates?.size ?: 0,
        templatesAdded = templatesAdded,
        templatesSkipped = templatesSkipped,
        settings = if (mode == ImportMode.REPLACE_ALL) staged.settings else null,
        settingsInFile = staged.settings != null,
        stats = staged.stats
    )
}
