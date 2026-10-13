package com.example.shareadd

import com.example.shareadd.backup.BackupException
import com.example.shareadd.backup.BackupProblem
import com.example.shareadd.backup.BackupSettings
import com.example.shareadd.backup.BackupSnapshot
import com.example.shareadd.backup.CurrentData
import com.example.shareadd.backup.ImportMode
import com.example.shareadd.backup.StagedBackup
import com.example.shareadd.backup.StagedImage
import com.example.shareadd.backup.planImport
import com.example.shareadd.backup.stageBackup
import com.example.shareadd.backup.writeBackupV2
import com.example.shareadd.events.InvitationTemplate
import com.example.shareadd.events.PlaceSnapshot
import com.example.shareadd.events.ShareEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

class BackupV2Test {

    @get:Rule
    val tmp = TemporaryFolder()

    /** test validator: content type only (the app additionally decodes the image) */
    private val validator: (File) -> String? = { f -> detectImageExtension(f.readBytes().copyOf(16)) }
    private val trustContentOnly: (String) -> Boolean = { it.startsWith("content://") }

    private fun png(seed: Int, size: Int): ByteArray {
        val b = ByteArray(size) { ((it * 31 + seed) % 251).toByte() }
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(b)
        return b
    }

    private fun place(id: String, name: String, url: String, images: List<String> = emptyList()) = Place(
        id = id, placeName = name, title = "عنوان $name", url = url, description = "وصف\n$name", privateNotes = "خاص $name",
        categoryAr = "مطاعم", categoryEn = "Restaurants", images = images, rating = 4, favorite = true, pinned = false,
        createdAt = 1791700000000L, updatedAt = 1791700001000L
    )

    private fun event(id: String, placeId: String?, text: String = "دعوة") = ShareEvent(
        id = id, type = "prayer", title = "صلاة", placeId = placeId, place = PlaceSnapshot("بيت", "حي", "https://maps.app.goo.gl/x"),
        host = "المستضيف", date = "2026-10-20", time = "18:30", openingTime = "18:00", prayer = "ishaain", imamLine = "",
        description = "", notes = "تنويه", templateId = "builtin-prayer-ar", language = "ar", invitationText = text, textEdited = true,
        createdAt = 5L, updatedAt = 6L
    )

    private fun exportToBytes(snapshot: BackupSnapshot, files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        writeBackupV2(out, snapshot, { ref -> files[ref]?.let { ByteArrayInputStream(it) } })
        return out.toByteArray()
    }

    private fun stage(bytes: ByteArray): StagedBackup =
        stageBackup(ByteArrayInputStream(bytes), tmp.newFolder(), validator, trustContentOnly)

    @Test
    fun v2_roundTripKeepsEveryFieldImageBytesAndRelations() {
        val img1 = png(1, 200_000)   // larger than one 48 KiB chunk
        val img2 = png(2, 1000)
        val files = mapOf("/data/img/a1.png" to img1, "/data/img/a2.png" to img2)
        val places = listOf(
            place("pl-a", "A", "https://maps.app.goo.gl/a", listOf("/data/img/a1.png", "/data/img/a2.png")),
            place("pl-b", "B", "https://maps.app.goo.gl/b")
        )
        val snapshot = BackupSnapshot(
            places = places,
            categories = listOf(Category("مطاعم", "Restaurants"), Category("مخصص", "Custom")),
            settings = BackupSettings(arabic = false, darkMode = true),
            events = listOf(event("ev-1", "pl-a"), event("ev-2", null)),
            templates = listOf(InvitationTemplate("tpl-1", "قالبي", "family", "ar", "مرحبا {host}\n{date}", createdAt = 1, updatedAt = 2)),
            exportedAt = 9L, appVersionName = "1.0", appVersionCode = 1
        )
        val staged = stage(exportToBytes(snapshot, files))

        assertEquals(2, staged.formatVersion)
        assertEquals(places.map { it.copy(images = emptyList()) }, staged.places.map { it.place })
        val stagedImgs = staged.places[0].images.map { it as StagedImage.Embedded }
        assertEquals(2, stagedImgs.size)
        assertArrayEquals(img1, stagedImgs[0].file.readBytes())
        assertArrayEquals(img2, stagedImgs[1].file.readBytes())
        assertEquals(snapshot.categories, staged.categories)
        assertEquals(snapshot.settings, staged.settings)
        assertEquals(snapshot.events, staged.events)
        assertEquals(snapshot.templates, staged.templates)
        assertEquals(2, staged.stats.imagesEmbedded)
        assertEquals(0, staged.stats.imagesRejected)
    }

    @Test
    fun v2_missingImageIsCountedNotFaked() {
        val snapshot = BackupSnapshot(listOf(place("pl-a", "A", "https://a.test", listOf("/gone.png"))), emptyList(),
            BackupSettings(true, true), emptyList(), emptyList(), 1, "1.0", 1)
        val out = ByteArrayOutputStream()
        val stats = writeBackupV2(out, snapshot, { null })
        assertEquals(0, stats.imagesWritten)
        assertEquals(1, stats.imagesMissing)
        assertTrue(stage(out.toByteArray()).places[0].images.isEmpty())
    }

    @Test
    fun export_stopsAtCancellationInsteadOfFinishingInTheBackground() {
        val files = (1..10).associate { "img$it" to png(it, 200_000) }
        val snapshot = BackupSnapshot(listOf(place("pl-a", "A", "https://a.test", files.keys.toList())), emptyList(),
            BackupSettings(true, true), emptyList(), emptyList(), 1, "1.0", 1)
        val out = ByteArrayOutputStream()
        var checks = 0
        try {
            writeBackupV2(out, snapshot, { ref -> files[ref]?.let { ByteArrayInputStream(it) } }) {
                if (++checks > 6) throw java.util.concurrent.CancellationException("cancelled")
            }
            fail("must stop")
        } catch (_: java.util.concurrent.CancellationException) {
        }
        // full output would be > 2.6 MB; it stopped after a few chunks
        assertTrue("written ${out.size()} bytes", out.size() < 600_000)
    }

    @Test
    fun v1_oldArrayBackupIsReadSafely() {
        val good = Base64.getEncoder().encodeToString(png(3, 500))
        val notImage = Base64.getEncoder().encodeToString("plain text".toByteArray())
        val v1 = """[{"placeName":"قديم","title":"t","url":"https://maps.app.goo.gl/old","category":"مطاعم","rating":5,"favorite":true,
            "images":[{"uri":"/data/user/0/x/files/shareadd_images/a.jpg","base64":"$good"},
                      {"uri":"/data/data/com.example.shareadd/shared_prefs/shareadd_security.xml"},
                      {"uri":"y","base64":"$notImage"},
                      "content://media/external/images/media/12",
                      "file:///sdcard/x.png"]}]"""
        val staged = stage(v1.toByteArray())
        assertEquals(1, staged.formatVersion)
        assertNull(staged.categories); assertNull(staged.events); assertNull(staged.templates); assertNull(staged.settings)
        val p = staged.places.single()
        assertEquals("قديم", p.place.placeName)
        assertEquals("مطاعم", p.place.categoryAr)
        assertEquals(5, p.place.rating)
        assertEquals(2, p.images.size)
        assertTrue(p.images[0] is StagedImage.Embedded)
        assertEquals(StagedImage.LegacyRef("content://media/external/images/media/12"), p.images[1])
        assertEquals(1, staged.stats.imagesEmbedded)
        assertEquals(1, staged.stats.imagesRejected)
        assertEquals(1, staged.stats.legacyRefsKept)
        assertEquals(2, staged.stats.refsRejected)
    }

    @Test
    fun truncatedBackupIsRejectedAsDamaged() {
        val snapshot = BackupSnapshot(listOf(place("pl-a", "A", "https://a.test", listOf("i"))), emptyList(),
            BackupSettings(true, true), listOf(event("ev-1", "pl-a")), emptyList(), 1, "1.0", 1)
        val full = exportToBytes(snapshot, mapOf("i" to png(4, 120_000)))
        for (cut in listOf(full.size - 1, full.size / 2, 40)) {
            try {
                stage(full.copyOf(cut))
                fail("truncated at $cut must fail")
            } catch (e: BackupException) {
                assertEquals(BackupProblem.DAMAGED, e.problem)
            }
        }
    }

    @Test
    fun unsupportedVersionIsRejectedBeforeAnyImageIsStaged() {
        val dir = tmp.newFolder()
        val json = """{"format":"shareadd-backup","formatVersion":3,"places":[{"id":"x","images":[{"data":["iVBORw0KGgo="],"sha256":"00"}]}]}"""
        try {
            stageBackup(ByteArrayInputStream(json.toByteArray()), dir, validator, trustContentOnly)
            fail("must fail")
        } catch (e: BackupException) {
            assertEquals(BackupProblem.UNSUPPORTED_VERSION, e.problem)
            assertEquals(3, e.version)
        }
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun notABackupIsRejected() {
        try { stage("""{"hello":"world"}""".toByteArray()); fail() } catch (e: BackupException) { assertEquals(BackupProblem.NOT_A_BACKUP, e.problem) }
        try { stage("hello".toByteArray()); fail() } catch (e: BackupException) { assertEquals(BackupProblem.NOT_A_BACKUP, e.problem) }
        try { stage("   ".toByteArray()); fail() } catch (e: BackupException) { assertEquals(BackupProblem.EMPTY, e.problem) }
    }

    @Test
    fun checksumMismatchRejectsTheWholeBackup() {
        val good = exportToBytes(
            BackupSnapshot(listOf(place("pl-a", "A", "https://a.test", listOf("i"))), emptyList(), BackupSettings(true, true), emptyList(), emptyList(), 1, "1.0", 1),
            mapOf("i" to png(5, 3000))
        )
        val text = String(good, Charsets.UTF_8)
        val shaStart = text.indexOf("\"sha256\":\"") + 10
        val tampered = text.substring(0, shaStart) + (if (text[shaStart] == 'a') 'b' else 'a') + text.substring(shaStart + 1)
        try { stage(tampered.toByteArray()); fail() } catch (e: BackupException) { assertEquals(BackupProblem.IMAGE_CHECK_FAILED, e.problem) }
    }

    // ---------------------------------------------------------------- import modes and relations

    private fun planFixture(): Pair<CurrentData, StagedBackup> {
        val current = CurrentData(
            places = listOf(place("pl-A", "A", "https://maps.app.goo.gl/a"), place("pl-B", "B", "https://maps.app.goo.gl/b")),
            categories = listOf(Category("مطاعم", "Restaurants")),
            events = listOf(event("ev-cur", "pl-A")),
            templates = emptyList()
        )
        val fileImages = mapOf("ia" to png(6, 800), "ic" to png(7, 800), "id" to png(8, 800))
        val snapshot = BackupSnapshot(
            places = listOf(
                place("pl-A", "A copy", "https://maps.app.goo.gl/a-other", listOf("ia")), // same id as current A
                place("pl-C", "C", "https://maps.app.goo.gl/c", listOf("ic")),            // new
                place("pl-D", "D", "https://maps.app.goo.gl/b", listOf("id"))             // same link as current B, other id
            ),
            categories = listOf(Category("مطاعم", "Restaurants"), Category("عائلي", "Family")),
            settings = BackupSettings(arabic = false, darkMode = false),
            events = listOf(event("ev-1", "pl-A"), event("ev-2", "pl-C"), event("ev-3", "pl-D"), event("ev-cur", "pl-C")),
            templates = listOf(InvitationTemplate("tpl-1", "T", body = "x")),
            exportedAt = 1, appVersionName = "1.0", appVersionCode = 1
        )
        return current to stage(exportToBytes(snapshot, fileImages))
    }

    @Test
    fun skipDuplicates_addsOnlyNewAndRelinksEventsToExistingPlaces() {
        val (current, staged) = planFixture()
        val imagesDir = tmp.newFolder("images")
        val plan = planImport(current, staged, ImportMode.SKIP_DUPLICATES, imagesDir, now = 100)
        assertEquals(listOf("pl-A", "pl-B", "pl-C"), plan.places.map { it.id })
        assertEquals("A", plan.places[0].placeName) // existing A untouched
        assertEquals(1, plan.placesAdded); assertEquals(2, plan.placesSkipped); assertEquals(0, plan.placesRemoved)
        assertEquals(1, plan.imagesToCommit.size)  // only C's image; skipped places bring no files
        assertTrue(plan.places[2].images.single().startsWith(imagesDir.absolutePath))
        val ev = plan.events!!.associateBy { it.id }
        assertEquals("pl-A", ev.getValue("ev-1").placeId)
        assertEquals("pl-C", ev.getValue("ev-2").placeId)
        assertEquals("pl-B", ev.getValue("ev-3").placeId)    // D was a duplicate of B by link
        assertEquals("pl-A", ev.getValue("ev-cur").placeId)  // same event id exists: kept current, file copy skipped
        assertEquals(3, plan.eventsAdded); assertEquals(1, plan.eventsSkipped)
        assertEquals(listOf(Category("مطاعم", "Restaurants"), Category("عائلي", "Family")), plan.categories)
        assertNull(plan.settings)   // settings only with replace
        assertEquals(current.places.size + plan.placesAdded, plan.places.size)
    }

    @Test
    fun mergeAll_addsEverythingAndGivesCollidingIdsNewIds() {
        val (current, staged) = planFixture()
        var n = 0
        val plan = planImport(current, staged, ImportMode.MERGE_ALL, tmp.newFolder("images"), now = 100, newPlaceIdFn = { "new-${++n}" }, newEventIdFn = { "newev-${++n}" })
        assertEquals(5, plan.places.size)
        assertEquals(3, plan.placesAdded); assertEquals(1, plan.placeIdsChanged)
        val copyOfA = plan.places.first { it.placeName == "A copy" }
        assertTrue(copyOfA.id.startsWith("new-"))
        assertEquals(plan.places.size, plan.places.map { it.id }.toSet().size)
        val ev = plan.events!!
        assertEquals(copyOfA.id, ev.first { it.id == "ev-1" }.placeId) // event points to the copy it came with
        assertEquals("pl-D", ev.first { it.id == "ev-3" }.placeId)
        assertEquals(5, ev.size) // ev-cur from the file got a new id
        assertEquals(3, plan.imagesToCommit.size)
    }

    @Test
    fun replaceAll_usesTheFileAndAppliesSettings() {
        val (current, staged) = planFixture()
        val plan = planImport(current, staged, ImportMode.REPLACE_ALL, tmp.newFolder("images"), now = 100)
        assertEquals(listOf("pl-A", "pl-C", "pl-D"), plan.places.map { it.id })
        assertEquals("A copy", plan.places[0].placeName)
        assertEquals(2, plan.placesRemoved); assertEquals(3, plan.placesAdded)
        assertEquals(listOf("ev-1", "ev-2", "ev-3", "ev-cur"), plan.events!!.map { it.id })
        assertEquals(BackupSettings(arabic = false, darkMode = false), plan.settings)
        assertEquals(listOf("tpl-1"), plan.templates!!.map { it.id })
    }

    @Test
    fun oldBackupNeverClearsCategoriesEventsOrTemplates() {
        val (current, _) = planFixture()
        val v1 = """[{"placeName":"X","title":"t","url":"https://x.test","images":[]}]"""
        val staged = stage(v1.toByteArray())
        for (mode in ImportMode.values()) {
            val plan = planImport(current, staged, mode, tmp.newFolder(), now = 1)
            assertNull(plan.categories); assertNull(plan.events); assertNull(plan.templates); assertNull(plan.settings)
            assertTrue(plan.places.all { it.id.isNotBlank() })
        }
    }
}
