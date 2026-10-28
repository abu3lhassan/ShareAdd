package com.example.shareadd

import com.example.shareadd.events.BUILT_IN_TEMPLATES
import com.example.shareadd.events.BUILTIN_PRAYER
import com.example.shareadd.events.EventTypes
import com.example.shareadd.events.PlaceSnapshot
import com.example.shareadd.events.Prayers
import com.example.shareadd.events.QrCodes
import com.example.shareadd.events.ShareEvent
import com.example.shareadd.events.defaultPrayerTitle
import com.example.shareadd.events.formatEventDate
import com.example.shareadd.events.formatEventTime
import com.example.shareadd.events.invitationValues
import com.example.shareadd.events.renderEvent
import com.example.shareadd.events.renderInvitation
import com.example.shareadd.events.seedDefaultTemplates
import com.example.shareadd.events.templateChoices
import com.example.shareadd.events.unknownTemplateVariables
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InvitationAndQrTest {

    private fun event(
        language: String = "ar", host: String = "", opening: String = "", notes: String = "", imam: String = "",
        description: String = "", url: String = "https://maps.app.goo.gl/AbC123"
    ) = ShareEvent(
        id = "ev-1", type = EventTypes.PRAYER, title = defaultPrayerTitle(Prayers.ISHAAIN, language), placeId = "pl-1",
        place = PlaceSnapshot("بيت الاختبار", "حي التجربة، شارع 5", url), host = host, date = "2026-10-20", time = "18:30",
        openingTime = opening, prayer = Prayers.ISHAAIN, imamLine = imam, description = description, notes = notes,
        templateId = BUILTIN_PRAYER, language = language
    )

    private fun prayerBody(language: String) = BUILT_IN_TEMPLATES.first { it.id == BUILTIN_PRAYER }.let { if (language == "en") it.bodyEn else it.bodyAr }

    @Test
    fun optionalLinesAreRemovedCleanly() {
        val r = renderEvent(event(), prayerBody("ar"))
        assertTrue(r.text.contains("صلاة العشائين جماعة"))
        assertTrue("weekday next to the title", r.text.startsWith("🕌 صلاة العشائين جماعة — الثلاثاء"))
        assertTrue(r.text.contains("📅 التاريخ: 20 أكتوبر 2026"))
        assertTrue(r.text.contains("6:30 م"))
        assertTrue(r.text.contains("https://maps.app.goo.gl/AbC123"))
        assertFalse("empty host line removed", r.text.contains("المستضيف"))
        assertFalse("empty opening time line removed", r.text.contains("فتح الباب"))
        assertFalse(r.text.contains("ℹ️"))
        assertFalse(r.text.contains("{"))
        assertFalse(r.text.contains("null"))
        assertFalse(r.text.contains("\n\n\n"))
        assertTrue(r.unknownVariables.isEmpty())
    }

    @Test
    fun filledOptionalLinesAppearAndOpeningTimeIsSeparateFromPrayerTime() {
        val r = renderEvent(event(host = "أبو محمد", opening = "18:00", notes = "يرجى الحضور مبكرًا", imam = "يؤم المصلين: إمام الحي"), prayerBody("ar"))
        assertTrue(r.text.contains("👤 المستضيف: أبو محمد"))
        assertTrue(r.text.contains("🚪 فتح الباب: 6:00 م"))
        assertTrue(r.text.contains("⏰ وقت الصلاة: 6:30 م"))
        assertTrue(r.text.contains("يؤم المصلين: إمام الحي"))
        assertTrue(r.text.contains("ℹ️ تنويه: يرجى الحضور مبكرًا"))
    }

    @Test
    fun englishTemplateAndFormatting() {
        val r = renderEvent(event(language = "en", host = "Abu Mohammed"), prayerBody("en"))
        assertTrue(r.text.startsWith("🕌 Maghrib & Isha congregational prayer"))
        assertTrue(r.text.startsWith("🕌 Maghrib & Isha congregational prayer — Tuesday"))
        assertTrue(r.text.contains("📅 Date: October 20, 2026"))
        assertTrue(r.text.contains("⏰ Prayer time: 6:30 PM"))
        assertTrue(r.text.contains("👤 Host: Abu Mohammed"))
        // names and addresses are not translated
        assertTrue(r.text.contains("🏠 Place: بيت الاختبار"))
    }

    @Test
    fun unknownVariablesAreReportedAndNeverShown() {
        val r = renderInvitation("Hi {host}\nSee {color} soon\n{phone}\nAt {place_name}", mapOf("host" to "A", "place_name" to "B"))
        assertEquals(listOf("color", "phone"), r.unknownVariables)
        assertEquals("Hi A\nSee  soon\nAt B", r.text)
        assertEquals(listOf("color"), unknownTemplateVariables("{title} {color} {date}"))
    }

    @Test
    fun privateNotesAreNeverATemplateValue() {
        val values = invitationValues(event())
        assertFalse(values.keys.any { it.contains("private", ignoreCase = true) })
        assertFalse(values.values.any { it.contains("خاص") })
    }

    @Test
    fun builtInTemplatesExistInBothLanguagesWithoutUnknownVariables() {
        assertEquals(setOf("prayer", "family", "social", "general"), BUILT_IN_TEMPLATES.map { it.type }.toSet())
        BUILT_IN_TEMPLATES.forEach {
            assertTrue(unknownTemplateVariables(it.bodyAr).isEmpty())
            assertTrue(unknownTemplateVariables(it.bodyEn).isEmpty())
        }
        assertEquals(4, templateChoices(seedDefaultTemplates(emptyList()), "ar").size)
    }

    @Test
    fun invalidDatesAndTimesProduceNoText() {
        assertEquals("", formatEventDate("2026-02-30", "ar"))
        assertEquals("", formatEventDate("", "en"))
        assertEquals("", formatEventTime("25:99", "en"))
        assertEquals("الأحد 25 أكتوبر 2026", formatEventDate("2026-10-25", "ar"))
        assertEquals("الفجر", Prayers.label(Prayers.FAJR, true))
        assertEquals("صلاة الظهرين جماعة", defaultPrayerTitle(Prayers.DHUHRAIN, "ar"))
    }

    // ---------------------------------------------------------------- QR: decode and compare with the original link

    private fun decode(text: String): String {
        val m = QrCodes.matrix(text, 600)
        val pixels = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val source = RGBLuminanceSource(m.width, m.height, pixels)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }

    @Test
    fun qrDecodesToExactlyTheSavedLink() {
        val links = listOf(
            "https://maps.app.goo.gl/AbC123xyz",
            "https://www.google.com/maps/place/مطعم+البيك/@24.7136,46.6753,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d24.71!4d46.67",
            "https://maps.google.com/?q=24.7136,46.6753&z=17&hl=ar"
        )
        links.forEach { assertEquals(it, decode(it)) }
    }
}
