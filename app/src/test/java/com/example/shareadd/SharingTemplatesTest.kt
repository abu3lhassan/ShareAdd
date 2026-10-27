package com.example.shareadd

import com.example.shareadd.backup.BackupSettings
import com.example.shareadd.backup.BackupSnapshot
import com.example.shareadd.backup.CurrentData
import com.example.shareadd.backup.ImportMode
import com.example.shareadd.backup.planImport
import com.example.shareadd.backup.stageBackup
import com.example.shareadd.backup.writeBackupV2
import com.example.shareadd.events.BUILT_IN_PLACE_TEMPLATES
import com.example.shareadd.events.BUILT_IN_TEMPLATES
import com.example.shareadd.events.BUILTIN_PLACE_DETAILED
import com.example.shareadd.events.BUILTIN_PLACE_SHORT
import com.example.shareadd.events.BUILTIN_PRAYER
import com.example.shareadd.events.EventTypes
import com.example.shareadd.events.InvitationTemplate
import com.example.shareadd.events.PLACE_TEMPLATE_TYPE
import com.example.shareadd.events.PlaceSnapshot
import com.example.shareadd.events.Prayers
import com.example.shareadd.events.ShareEvent
import com.example.shareadd.events.ShareTextSource
import com.example.shareadd.events.ShareTimes
import com.example.shareadd.events.applyShareTimes
import com.example.shareadd.events.eventsToJson
import com.example.shareadd.events.parseEventsStrict
import com.example.shareadd.events.parseTemplatesStrict
import com.example.shareadd.events.placeTemplateChoices
import com.example.shareadd.events.renderEvent
import com.example.shareadd.events.savedTimes
import com.example.shareadd.events.shareInvitationText
import com.example.shareadd.events.templateChoices
import com.example.shareadd.events.templatesToJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class SharingTemplatesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val detailedAr = BUILT_IN_PLACE_TEMPLATES.first { it.id == BUILTIN_PLACE_DETAILED }.bodyAr
    private val shortAr = BUILT_IN_PLACE_TEMPLATES.first { it.id == BUILTIN_PLACE_SHORT }.bodyAr
    private val prayerAr = BUILT_IN_TEMPLATES.first { it.id == BUILTIN_PRAYER }.bodyAr
    private val notices = "- دعاء كميل\n- زيارة الإمام الحسين\n- جلسة ودية"

    private fun place(title: String = "حي الروضة - شارع 12") = Place(
        id = "pl-1", placeName = "بيت التجربة", title = title, url = "https://maps.app.goo.gl/abc",
        description = "وصف عام للمكان", privateNotes = "رمز البوابة 4321", categoryAr = "أماكن", categoryEn = "Places",
        images = emptyList(), rating = 4, favorite = false, pinned = false, createdAt = 1, updatedAt = 1
    )

    private fun prayerEvent(notes: String = "", iqama: String = "", showDate: Boolean = false): ShareEvent {
        val e = ShareEvent(
            id = "ev-1", type = EventTypes.PRAYER, title = "صلاة العشائين جماعة", placeId = "pl-1",
            place = PlaceSnapshot("بيت التجربة", "حي الروضة", "https://maps.app.goo.gl/abc"), date = "2026-10-22",
            time = "18:00", openingTime = "17:30", iqamaTime = iqama, prayer = Prayers.ISHAAIN, notes = notes,
            templateId = BUILTIN_PRAYER, language = "ar", showDate = showDate, invitationPhoto = "/x/shareadd_images/a.jpg",
            createdAt = 1, updatedAt = 2
        )
        return e.copy(invitationText = renderEvent(e, prayerAr).text)
    }

    // ------------------------------------------------------------------ place sharing

    @Test
    fun placeShareShowsTheSavedShortAddressAndNoIntroOrHiddenSignature() { // A1, A3
        val text = placeShareText(place(), arabic = true, templateBody = detailedAr)
        assertTrue(text.contains("🏠 العنوان المختصر: حي الروضة - شارع 12"))
        assertTrue(text.contains("📍 بيت التجربة"))
        assertTrue(text.contains("📝 وصف عام للمكان"))
        assertTrue(text.contains("https://maps.app.goo.gl/abc"))
        assertFalse(text.contains("حفظت هذا المكان"))
        // the one optional signature is the template's last line, written once
        assertEquals(1, Regex("ShareAdd").findAll(text).count())
        assertTrue(text.endsWith("تمت المشاركة عبر ShareAdd"))
        // the short template has no signature at all
        assertFalse(placeShareText(place(), true, shortAr).contains("ShareAdd"))
        // the legacy entry point uses the same templates
        assertEquals(text, buildShareText(place(), arabic = true, detailed = true))
    }

    @Test
    fun emptyShortAddressLeavesNoLineAndNoNull() { // A2
        val text = placeShareText(place(title = ""), true, detailedAr)
        assertFalse(text.contains("العنوان المختصر"))
        assertFalse(text.contains("null"))
        assertFalse(text.contains("{"))
        assertFalse(text.contains("\n\n\n"))
    }

    @Test
    fun privateNotesNeverReachAPlaceShare() { // R4
        val all = BUILT_IN_PLACE_TEMPLATES.flatMap { listOf(it.bodyAr, it.bodyEn) } +
            "{privateNotes} {private_notes} {notes} {description}"
        all.forEach { body -> assertFalse(placeShareText(place(), true, body, notes = "").contains("4321")) }
    }

    @Test
    fun customPlaceTemplateControlsOrderLabelsAndSignature() { // A4
        val body = "{map_link}\nالمكان: {place_name}\nأقرب معلم: {address}"
        val text = placeShareText(place(), true, body)
        assertEquals("https://maps.app.goo.gl/abc\nالمكان: بيت التجربة\nأقرب معلم: حي الروضة - شارع 12", text)
        // event fields are not place fields: removed (the editor warns about them), never shown as "{iqama_time}"
        assertEquals("بيت التجربة\nالإقامة:", placeShareText(place(), true, "{place_name}\nالإقامة: {iqama_time}"))
    }

    @Test
    fun placeShareNoticeIsForThisShareOnlyAndKeepsItsLines() {
        val text = placeShareText(place(), true, detailedAr, notes = "- المدخل من الباب الجانبي\n- الموقف خلف المبنى")
        assertTrue(text.contains("ℹ️ تنويه:\n\n- المدخل من الباب الجانبي\n- الموقف خلف المبنى"))
        assertFalse(place().description.contains("المدخل"))
    }

    @Test
    fun placeAndEventTemplatesAreNeverMixed() {
        val custom = listOf(
            InvitationTemplate("tpl-p", "مكاني", PLACE_TEMPLATE_TYPE, "ar", "{place_name}"),
            InvitationTemplate("tpl-e", "دعوتي", EventTypes.GENERAL, "ar", "{title}")
        )
        assertEquals(listOf(BUILTIN_PLACE_DETAILED, BUILTIN_PLACE_SHORT, "tpl-p"), placeTemplateChoices(custom, "ar", true).map { it.id })
        assertFalse(templateChoices(custom, "ar", true).any { it.type == PLACE_TEMPLATE_TYPE })
        // an unknown / deleted remembered template falls back to the detailed one
        assertEquals(BUILTIN_PLACE_DETAILED, placeShareTemplate(custom, true, "tpl-deleted").id)
        assertEquals("tpl-p", placeShareTemplate(custom, true, "tpl-p").id)
    }

    @Test
    fun placeListShareHasNoShareAddIntroOrFooterAndKeepsOrderAndAddresses() {
        val a = place().copy(id = "pl-a", placeName = "أ", title = "حي أ")
        val b = place().copy(id = "pl-b", placeName = "ب", title = "")
        val c = place().copy(id = "pl-c", placeName = "ج", title = "حي ج")
        val shown = listOf(c, a)                      // a filter that shows two places, in the shown order
        val text = placesListText(shown, arabic = true, title = "القائمة الحالية")
        assertFalse(text.contains("ShareAdd"))
        assertFalse(text.contains("الحنابي"))
        assertTrue(text.startsWith("📍 القائمة الحالية\nعدد الأماكن: 2\n\n1. ج\n📍 حي ج\n"))
        assertTrue(text.contains("\n\n────────────\n\n2. أ\n📍 حي أ\n"))
        assertFalse(text.contains("4321"))            // private notes are never listed
        val all = placesListText(listOf(a, b, c), arabic = true, title = "كل العناوين")
        assertEquals(3, Regex("""(?m)^\d+\. """).findAll(all).count())
        assertTrue(all.contains("2. ب\n🗂 أماكن"))      // empty short address: no empty line
        // text written by the user inside a place's own fields is kept as is, even if it names ShareAdd
        val own = placesListText(listOf(a.copy(description = "تمت المشاركة عبر ShareAdd")), true, "x")
        assertTrue(own.contains("📝 تمت المشاركة عبر ShareAdd"))
    }

    // ------------------------------------------------------------------ labels, iqama, day/date, notices

    @Test
    fun renamedLabelKeepsTheTimeValue() { // E1
        val body = "{title}\nدخول وقت الصلاة: {time}\nموعد اللقاء: {opening_time}"
        val text = renderEvent(prayerEvent(), body).text
        assertTrue(text.contains("دخول وقت الصلاة: 6:00 م"))
        assertTrue(text.contains("موعد اللقاء: 5:30 م"))
    }

    @Test
    fun threeIndependentTimesInTextAndStorage() { // E2
        val e = prayerEvent(iqama = "18:15")
        val text = renderEvent(e, prayerAr).text
        assertTrue(text.contains("🚪 فتح الباب: 5:30 م"))
        assertTrue(text.contains("⏰ وقت الصلاة: 6:00 م"))
        assertTrue(text.contains("🕋 وقت الإقامة: 6:15 م"))
        val back = parseEventsStrict(eventsToJson(listOf(e)))!!.single()
        assertEquals(Triple("17:30", "18:00", "18:15"), Triple(back.openingTime, back.time, back.iqamaTime))
        // changing one time in a share never moves the others
        val r = shareInvitationText(e, ShareTimes("18:00", "17:30", iqamaTime = "18:20"), prayerAr)
        assertTrue(r.text.contains("🕋 وقت الإقامة: 6:20 م") && r.text.contains("⏰ وقت الصلاة: 6:00 م") && r.text.contains("🚪 فتح الباب: 5:30 م"))
    }

    @Test
    fun emptyIqamaLeavesNoLineAndOldEventsAreUnchanged() { // E3
        assertFalse(renderEvent(prayerEvent(), prayerAr).text.contains("الإقامة"))
        val old = """[{"id":"ev-old","type":"prayer","title":"T","placeId":null,"place":{"placeName":"","title":"","url":""},"host":"","date":"2026-10-22","time":"18:00","openingTime":"","prayer":"","imamLine":"","description":"","notes":"","templateId":"builtin-prayer","language":"ar","invitationText":"T","textEdited":false,"createdAt":1,"updatedAt":2}]"""
        val events = parseEventsStrict(old)!!
        assertEquals("", events.single().iqamaTime)
        assertEquals("18:00", events.single().time)        // the old time keeps its meaning; nothing is copied to iqama
        assertEquals("events without iqama keep exactly their JSON", old, eventsToJson(events))
    }

    @Test
    fun dayOnlyOrDayAndDateFollowTheDateAndKeepIt() { // E4, E5
        val body = "{title} — {day_date}"
        val dayOnly = prayerEvent(showDate = false)
        assertEquals("صلاة العشائين جماعة — الخميس", renderEvent(dayOnly, body).text)
        assertEquals("صلاة العشائين جماعة — الخميس 22 أكتوبر 2026", renderEvent(dayOnly.copy(showDate = true), body).text)
        // the built-in layout: weekday with the title, the date line only in day-and-date mode, never twice
        val builtIn = shareInvitationText(dayOnly, ShareTimes("18:00", "17:30", showDate = true), prayerAr)
        assertTrue(builtIn.text.contains("— الخميس") && builtIn.text.contains("📅 التاريخ: 22 أكتوبر 2026"))
        assertEquals(1, Regex("الخميس").findAll(builtIn.text).count())
        assertEquals("2026-10-22", dayOnly.date)          // day-only never clears the stored date
        // a new date updates the weekday in both modes
        val moved = shareInvitationText(dayOnly, ShareTimes("18:00", "17:30", date = "2026-10-25", showDate = true), prayerAr).text
        assertTrue(moved.contains("— الأحد") && moved.contains("25 أكتوبر 2026") && !moved.contains("الخميس"))
    }

    @Test
    fun multilineNoticesKeepEveryLineAndTheHeadingOnce() { // E6
        val body = "{title}\n\nتنويه/ـات:- {notes}"
        val text = renderEvent(prayerEvent(notes = notices), body).text
        assertEquals("صلاة العشائين جماعة\n\nتنويه/ـات:-\n\n- دعاء كميل\n- زيارة الإمام الحسين\n- جلسة ودية", text)
        assertEquals(1, Regex("تنويه").findAll(text).count())
        // the built-in heading is used once, the list follows on its own lines
        val builtIn = renderEvent(prayerEvent(notes = notices), prayerAr).text
        assertTrue(builtIn.endsWith("ℹ️ تنويه:\n\n- دعاء كميل\n- زيارة الإمام الحسين\n- جلسة ودية"))
        // empty notices: no heading, no bullets, the user's other template text stays
        val empty = renderEvent(prayerEvent(), "{title}\nتنويه/ـات:- {notes}\nحضوركم يسعدنا").text
        assertEquals("صلاة العشائين جماعة\nحضوركم يسعدنا", empty)
        // a one-line note stays on the heading line as before
        assertTrue(renderEvent(prayerEvent(notes = "إحضار المصحف"), prayerAr).text.contains("ℹ️ تنويه: إحضار المصحف"))
    }

    // ------------------------------------------------------------------ share session and explicit save

    @Test
    fun sessionChangesAffectOnlyThisShare() { // E7
        val e = prayerEvent()
        val before = eventsToJson(listOf(e))
        val t = ShareTimes("18:00", "17:30", iqamaTime = "18:15", showDate = true, notes = notices)
        val r = shareInvitationText(e, t, prayerAr)
        assertEquals(ShareTextSource.TEMPLATE, r.source)
        assertTrue(r.text.contains("🕋 وقت الإقامة: 6:15 م") && r.text.contains("📅 التاريخ: 22 أكتوبر 2026") && r.text.contains("- جلسة ودية"))
        assertEquals(before, eventsToJson(listOf(e)))
        assertEquals(ShareTextSource.SAVED, shareInvitationText(e, savedTimes(e), prayerAr).source)
    }

    @Test
    fun explicitSaveWritesAllValuesTogetherAndKeepsPhotoAndPlace() { // E8
        val e = prayerEvent()
        val t = ShareTimes("18:05", "17:40", date = "2026-10-23", iqamaTime = "18:20", showDate = true, notes = notices)
        val r = shareInvitationText(e, t, prayerAr)
        val saved = applyShareTimes(e, t, r, now = 9, finalText = r.text)
        assertEquals(
            e.copy(time = "18:05", openingTime = "17:40", date = "2026-10-23", iqamaTime = "18:20", showDate = true, notes = notices,
                invitationText = r.text, textEdited = false, updatedAt = 9),
            saved
        )
        assertEquals(e.invitationPhoto, saved.invitationPhoto)
        assertEquals(e.place, saved.place)
    }

    @Test
    fun finalTextEditedInTheDialogIsWhatIsSavedAsHandEdited() {
        val e = prayerEvent()
        val t = ShareTimes("18:00", "17:30", notes = notices)
        val r = shareInvitationText(e, t, prayerAr)
        val edited = r.text.replace("⏰ وقت الصلاة:", "⏰ دخول وقت الصلاة:")
        val saved = applyShareTimes(e, t, r, now = 3, finalText = edited)
        assertEquals(edited, saved.invitationText)
        assertTrue(saved.textEdited)
        assertEquals(notices, saved.notes)
    }

    @Test
    fun handEditedTextIsNeverDamagedOrShownAsUpdated() { // E9
        val custom = "تفضلوا لصلاة العشائين الساعة 6:00 م\nالإقامة 6:10 م"
        val e = prayerEvent(iqama = "18:10").copy(invitationText = custom, textEdited = true)
        // a unique saved iqama time can be replaced safely
        val r = shareInvitationText(e, ShareTimes("18:00", "17:30", iqamaTime = "18:20"), prayerAr)
        assertEquals(ShareTextSource.TIMES_REPLACED, r.source)
        assertEquals("تفضلوا لصلاة العشائين الساعة 6:00 م\nالإقامة 6:20 م", r.text)
        // new notices or another day display cannot be placed: the text stays as it was and is reported as not updated
        listOf(ShareTimes("18:00", "17:30", notes = notices), ShareTimes("18:00", "17:30", showDate = true)).forEach {
            val n = shareInvitationText(e, it, prayerAr)
            assertEquals(ShareTextSource.TIMES_NOT_APPLIED, n.source)
            assertEquals(custom, n.text)
        }
        // an iqama time that is not in the text cannot be placed either
        val noIqama = prayerEvent().copy(invitationText = "تفضلوا الساعة 6:00 م", textEdited = true)
        assertEquals(ShareTextSource.TIMES_NOT_APPLIED, shareInvitationText(noIqama, ShareTimes("18:00", "17:30", iqamaTime = "18:15"), prayerAr).source)
    }

    @Test
    fun anotherTemplateChosenForTheShareRebuildsTheTextFromIt() {
        val e = prayerEvent()
        val body = "{title}\nدخول الوقت: {time}\nالإقامة: {iqama_time}"
        val r = shareInvitationText(e, ShareTimes("18:00", "17:30", iqamaTime = "18:15", templateId = "tpl-x"), prayerAr, body)
        assertEquals(ShareTextSource.TEMPLATE, r.source)
        assertEquals("صلاة العشائين جماعة\nدخول الوقت: 6:00 م\nالإقامة: 6:15 م", r.text)
        assertEquals(BUILTIN_PRAYER, e.templateId)
    }

    // ------------------------------------------------------------------ storage and Backup v2

    @Test
    fun newFieldsAndPlaceTemplatesRoundTripThroughStorage() { // B1
        val e = prayerEvent(notes = notices, iqama = "18:15", showDate = true)
        assertEquals(e, parseEventsStrict(eventsToJson(listOf(e)))!!.single())
        val tpl = InvitationTemplate("tpl-p", "مكاني", PLACE_TEMPLATE_TYPE, "ar", "📍 {place_name}\n🏠 {address}\n\nتنويه:- {notes}", createdAt = 1, updatedAt = 2)
        assertEquals(tpl, parseTemplatesStrict(templatesToJson(listOf(tpl)))!!.single())
    }

    @Test
    fun backupV2BringsBackNewFieldsTemplatesAndMultilineNotices() { // B2
        val e = prayerEvent(notes = notices, iqama = "18:15", showDate = true).copy(invitationPhoto = "")
        val tpl = InvitationTemplate("tpl-p", "مكاني", PLACE_TEMPLATE_TYPE, "ar", "📍 {place_name}\n{notes}", createdAt = 1, updatedAt = 2)
        val out = ByteArrayOutputStream()
        writeBackupV2(out, BackupSnapshot(listOf(place()), emptyList(), BackupSettings(true, true), listOf(e), listOf(tpl), 1, "1.1", 2), { null })
        val staged = stageBackup(ByteArrayInputStream(out.toByteArray()), tmp.newFolder(), { null }, { false })
        val plan = planImport(CurrentData(emptyList(), emptyList(), emptyList(), emptyList()), staged, ImportMode.REPLACE_ALL, tmp.newFolder("img"), now = 5)
        val ev = plan.events!!.single()
        assertEquals(notices, ev.notes)
        assertEquals("18:15", ev.iqamaTime)
        assertTrue(ev.showDate)
        assertEquals(e.invitationText, ev.invitationText)
        assertEquals(tpl, plan.templates!!.single())
    }
}
