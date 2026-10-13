package com.example.shareadd

import com.example.shareadd.backup.BackupSettings
import com.example.shareadd.backup.BackupSnapshot
import com.example.shareadd.backup.CurrentData
import com.example.shareadd.backup.ImportMode
import com.example.shareadd.backup.planImport
import com.example.shareadd.backup.stageBackup
import com.example.shareadd.backup.writeBackupV2
import com.example.shareadd.events.BUILT_IN_TEMPLATES
import com.example.shareadd.events.BUILTIN_PRAYER
import com.example.shareadd.events.EventTypes
import com.example.shareadd.events.PlaceSnapshot
import com.example.shareadd.events.Prayers
import com.example.shareadd.events.ShareEvent
import com.example.shareadd.events.ShareTextSource
import com.example.shareadd.events.ShareTimes
import com.example.shareadd.events.applyShareTimes
import com.example.shareadd.events.defaultPrayerTitle
import com.example.shareadd.events.eventsToJson
import com.example.shareadd.events.invitationPhotoChoice
import com.example.shareadd.events.parseEventsStrict
import com.example.shareadd.events.renderEvent
import com.example.shareadd.events.savedTimes
import com.example.shareadd.events.shareInvitationText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class EventShareTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val prayerBody = BUILT_IN_TEMPLATES.first { it.id == BUILTIN_PRAYER }.bodyAr

    /** A prayer event whose saved text was generated from the built-in Arabic prayer template. */
    private fun prayerEvent(time: String = "18:15", opening: String = "17:50", photo: String = ""): ShareEvent {
        val e = ShareEvent(
            id = "ev-1", type = EventTypes.PRAYER, title = defaultPrayerTitle(Prayers.ISHAAIN, "ar"), placeId = "pl-1",
            place = PlaceSnapshot("بيت الاختبار", "حي التجربة", "https://maps.app.goo.gl/abc"), date = "2026-10-20",
            time = time, openingTime = opening, prayer = Prayers.ISHAAIN, templateId = BUILTIN_PRAYER, language = "ar",
            invitationPhoto = photo, createdAt = 1, updatedAt = 2
        )
        return e.copy(invitationText = renderEvent(e, prayerBody).text)
    }

    private fun place(id: String, images: List<String>) = Place(
        id = id, placeName = "بيت", title = "حي", url = "https://maps.app.goo.gl/$id", description = "", privateNotes = "",
        categoryAr = "أخرى", categoryEn = "Other", images = images, rating = 0, favorite = false, pinned = false,
        createdAt = 1, updatedAt = 1
    )

    // ------------------------------------------------------------------ preferred photo: storage and choice

    @Test
    fun eventWithoutPhotoPreferenceLoadsAndKeepsItsStoredJson() {
        // JSON written before this field existed
        val old = """[{"id":"ev-old","type":"general","title":"T","placeId":"pl-1","place":{"placeName":"P","title":"A","url":"https://x.test"},"host":"","date":"","time":"","openingTime":"","prayer":"","imamLine":"","description":"","notes":"","templateId":"builtin-general","language":"ar","invitationText":"T","textEdited":false,"createdAt":1,"updatedAt":2}]"""
        val events = parseEventsStrict(old)!!
        assertEquals("", events.single().invitationPhoto)
        // an event without a preference is written exactly as before (no new key)
        assertEquals(old, eventsToJson(events))
    }

    @Test
    fun preferredPhotoRoundTripsThroughLocalStorage() {
        val ref = "/data/user/0/com.example.shareadd/files/shareadd_images/shareadd_1_a.jpg"
        val e = prayerEvent(photo = ref)
        assertEquals(e, parseEventsStrict(eventsToJson(listOf(e)))!!.single())
    }

    @Test
    fun missingPreferredPhotoIsReportedNotReplacedAndEventStaysValid() {
        val e = prayerEvent(photo = "/x/shareadd_images/gone.jpg")
        val p = place("pl-1", listOf("/x/shareadd_images/a.jpg", "/x/shareadd_images/b.jpg"))
        val choice = invitationPhotoChoice(e, p, usablePlaceImage = { true }, usableOrphanImage = { true })
        assertNull("no unrelated photo is picked silently", choice.selected)
        assertTrue(choice.previousUnavailable)
        assertEquals(p.images, choice.available)          // the user can still pick one of the place's photos
        assertEquals(e, parseEventsStrict(eventsToJson(listOf(e)))!!.single())

        // no preference → first usable place image is suggested; a remembered one that still exists is kept
        assertEquals("/x/shareadd_images/a.jpg", invitationPhotoChoice(e.copy(invitationPhoto = ""), p, { true }, { true }).selected)
        assertEquals("/x/shareadd_images/b.jpg", invitationPhotoChoice(e.copy(invitationPhoto = "/x/shareadd_images/b.jpg"), p, { true }, { true }).selected)
        // untrusted place images are never offered
        assertEquals(listOf("/x/shareadd_images/b.jpg"), invitationPhotoChoice(e, p, { it.endsWith("b.jpg") }, { true }).available)
    }

    @Test
    fun deletedPlaceUsesTheRememberedPhotoOnlyIfStillSafe() {
        val e = prayerEvent(photo = "/x/shareadd_images/a.jpg")
        val ok = invitationPhotoChoice(e, null, usablePlaceImage = { false }, usableOrphanImage = { true })
        assertEquals("/x/shareadd_images/a.jpg", ok.selected)
        val gone = invitationPhotoChoice(e, null, usablePlaceImage = { true }, usableOrphanImage = { false })
        assertNull(gone.selected); assertTrue(gone.previousUnavailable); assertTrue(gone.available.isEmpty())
    }

    // ------------------------------------------------------------------ Backup v2

    private fun png(seed: Int) = ByteArray(600) { ((it * 13 + seed) % 251).toByte() }.also {
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(it)
    }

    private fun exportAndStage(places: List<Place>, events: List<ShareEvent>, files: Map<String, ByteArray>) = run {
        val out = ByteArrayOutputStream()
        writeBackupV2(out, BackupSnapshot(places, emptyList(), BackupSettings(true, true), events, emptyList(), 1, "1.0", 1), { files[it]?.let { b -> ByteArrayInputStream(b) } })
        stageBackup(ByteArrayInputStream(out.toByteArray()), tmp.newFolder(), { f -> detectImageExtension(f.readBytes().copyOf(16)) }, { it.startsWith("content://") })
    }

    @Test
    fun backupV2KeepsThePhotoChoicePointingToTheImportedCopyOfThatImage() {
        val a = "/old/files/shareadd_images/shareadd_1_a.png"
        val b = "/old/files/shareadd_images/shareadd_2_b.png"
        val staged = exportAndStage(listOf(place("pl-1", listOf(a, b))), listOf(prayerEvent(photo = b)), mapOf(a to png(1), b to png(2)))
        val imagesDir = tmp.newFolder("images")
        val empty = CurrentData(emptyList(), emptyList(), emptyList(), emptyList())

        val replace = planImport(empty, staged, ImportMode.REPLACE_ALL, imagesDir, now = 5)
        val importedPlace = replace.places.single()
        // the second image of the place, at its new path inside the image store
        assertEquals(importedPlace.images[1], replace.events!!.single().invitationPhoto)
        assertTrue(replace.events!!.single().invitationPhoto.startsWith(imagesDir.absolutePath))

        // merge with a colliding place id: the event follows the new copy and its image
        val current = CurrentData(listOf(place("pl-1", emptyList())), emptyList(), emptyList(), emptyList())
        var n = 0
        val merge = planImport(current, staged, ImportMode.MERGE_ALL, imagesDir, now = 6, newPlaceIdFn = { "new-${++n}" })
        val copy = merge.places.first { it.id.startsWith("new-") }
        val ev = merge.events!!.single()
        assertEquals(copy.id, ev.placeId)
        assertEquals(copy.images[1], ev.invitationPhoto)
    }

    @Test
    fun unsafeOrUnmatchedPhotoReferenceInABackupIsDropped() {
        val a = "/old/files/shareadd_images/shareadd_1_a.png"
        val events = listOf(
            prayerEvent(photo = "/data/data/com.example.shareadd/shared_prefs/shareadd_security.xml"),
            prayerEvent(photo = "../../shared_prefs/x.xml").copy(id = "ev-2"),
            prayerEvent(photo = a).copy(id = "ev-3", placeId = "pl-missing")
        )
        val staged = exportAndStage(listOf(place("pl-1", listOf(a))), events, mapOf(a to png(1)))
        val plan = planImport(CurrentData(emptyList(), emptyList(), emptyList(), emptyList()), staged, ImportMode.REPLACE_ALL, tmp.newFolder("images"), now = 5)
        plan.events!!.forEach { assertEquals("never a raw path from the file", "", it.invitationPhoto) }
        // the events themselves are kept
        assertEquals(3, plan.events!!.size)
    }

    // ------------------------------------------------------------------ share-time override

    @Test
    fun noOverrideSharesTheSavedTextExactly() {
        val e = prayerEvent()
        val r = shareInvitationText(e, savedTimes(e), prayerBody)
        assertEquals(ShareTextSource.SAVED, r.source)
        assertEquals(e.invitationText, r.text)
    }

    @Test
    fun previewUsesTheOverriddenTimeAndTheSavedEventIsUntouched() {
        val e = prayerEvent()
        val before = eventsToJson(listOf(e))
        val r = shareInvitationText(e, ShareTimes("18:20", "17:50"), prayerBody)
        assertEquals(ShareTextSource.TEMPLATE, r.source)
        assertTrue(r.text.contains("⏰ وقت الصلاة: 6:20 م"))
        assertFalse(r.text.contains("6:15"))
        assertTrue(r.text.contains("🚪 فتح الباب: 5:50 م"))
        assertEquals("override does not change the event", before, eventsToJson(listOf(e)))
    }

    @Test
    fun explicitUpdateSavesTheTimesAndTheMatchingText() {
        val e = prayerEvent()
        val times = ShareTimes("18:20", "17:55")
        val r = shareInvitationText(e, times, prayerBody)
        val updated = applyShareTimes(e, times, r, now = 99)
        assertEquals("18:20", updated.time)
        assertEquals("17:55", updated.openingTime)
        assertEquals(r.text, updated.invitationText)
        assertEquals(99L, updated.updatedAt)
        assertEquals(e.copy(time = "18:20", openingTime = "17:55", invitationText = r.text, updatedAt = 99), updated)
    }

    @Test
    fun openingTimeAndEventTimeChangeIndependently() {
        val e = prayerEvent()
        val onlyOpening = shareInvitationText(e, ShareTimes("18:15", "17:40"), prayerBody).text
        assertTrue(onlyOpening.contains("⏰ وقت الصلاة: 6:15 م"))
        assertTrue(onlyOpening.contains("🚪 فتح الباب: 5:40 م"))
        val onlyTime = shareInvitationText(e, ShareTimes("18:30", "17:50"), prayerBody).text
        assertTrue(onlyTime.contains("⏰ وقت الصلاة: 6:30 م"))
        assertTrue(onlyTime.contains("🚪 فتح الباب: 5:50 م"))
        // a temporary opening time on an event that has none
        val none = prayerEvent(opening = "")
        val added = shareInvitationText(none, ShareTimes("18:15", "17:45"), prayerBody).text
        assertTrue(added.contains("🚪 فتح الباب: 5:45 م"))
        assertEquals("", none.openingTime)
    }

    @Test
    fun clearedOpeningTimeRemovesItsLineCleanly() {
        val r = shareInvitationText(prayerEvent(), ShareTimes("18:15", ""), prayerBody).text
        assertFalse(r.contains("فتح الباب"))
        assertFalse(r.contains("null"))
        assertFalse(r.contains("{"))
        assertFalse(r.contains("\n\n\n"))
        assertTrue(r.contains("⏰ وقت الصلاة: 6:15 م"))
    }

    @Test
    fun handEditedTextIsNeverRebuiltOnlyItsUniqueTimeIsReplacedForTheShare() {
        val custom = "تفضلوا لصلاة العشاء الساعة 6:15 م\nالباب يفتح 5:50 م\nرقم البيت 16:15"
        val e = prayerEvent().copy(invitationText = custom, textEdited = true)
        val r = shareInvitationText(e, ShareTimes("18:20", "17:50"), prayerBody)
        assertEquals(ShareTextSource.TIMES_REPLACED, r.source)
        assertEquals("تفضلوا لصلاة العشاء الساعة 6:20 م\nالباب يفتح 5:50 م\nرقم البيت 16:15", r.text)
        assertEquals("stored hand-edited text unchanged", custom, e.invitationText)

        // cannot be placed safely: the time is not in the text / a line would have to be removed / ambiguous
        assertEquals(ShareTextSource.TIMES_NOT_APPLIED, shareInvitationText(e.copy(invitationText = "بدون وقت"), ShareTimes("18:20", "17:50"), prayerBody).source)
        val cleared = shareInvitationText(e, ShareTimes("18:15", ""), prayerBody)
        assertEquals(ShareTextSource.TIMES_NOT_APPLIED, cleared.source)
        assertEquals(custom, cleared.text)
        val same = prayerEvent(time = "18:00", opening = "18:00").let { it.copy(invitationText = "6:00 م", textEdited = true) }
        assertEquals(ShareTextSource.TIMES_NOT_APPLIED, shareInvitationText(same, ShareTimes("18:10", "18:00"), prayerBody).source)
        // explicit update with text that could not take the times keeps the text as it was
        val kept = applyShareTimes(e, ShareTimes("18:15", ""), cleared, now = 5)
        assertEquals(custom, kept.invitationText)
        assertEquals("", kept.openingTime)
    }
}
