package com.example.shareadd

import com.example.shareadd.events.BUILT_IN_TEMPLATES
import com.example.shareadd.events.BUILTIN_PRAYER
import com.example.shareadd.events.EventTypes
import com.example.shareadd.events.PlaceSnapshot
import com.example.shareadd.events.ShareEvent
import com.example.shareadd.events.eventsToJson
import com.example.shareadd.events.parseEventsStrict
import com.example.shareadd.events.renderEvent
import com.example.shareadd.events.renderInvitation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Invitation layout: weekday with the title, optional date / place name lines, short address, link, then description and note. */
class InvitationLayoutTest {

    private fun event(
        language: String = "ar", date: String = "2026-10-14", showDate: Boolean = false, showPlaceName: Boolean = true,
        description: String = "الدعوة عامة للجميع", notes: String = "يرجى الحضور قبل الإقامة"
    ) = ShareEvent(
        id = "ev-1", type = EventTypes.PRAYER, title = if (language == "en") "Isha congregational prayer" else "صلاة العشاء جماعة",
        placeId = "pl-1", place = PlaceSnapshot("البيت Home", "9X3H+MHV Dammam", "https://goo.gl/maps/wBSFdiaLBrZ7kEM88"),
        host = "علي الحنابي (ابو الزهراء)", date = date, time = "17:28", openingTime = "17:15",
        description = description, notes = notes, templateId = BUILTIN_PRAYER, language = language,
        showDate = showDate, showPlaceName = showPlaceName
    )

    private fun body(id: String, language: String) = BUILT_IN_TEMPLATES.first { it.id == id }.let { if (language == "en") it.bodyEn else it.bodyAr }

    @Test
    fun arabicPrayerInvitationMatchesTheApprovedLayout() {
        val expected = """
🕌 صلاة العشاء جماعة — الأربعاء
👤 المستضيف: علي الحنابي (ابو الزهراء)
🚪 فتح الباب: 5:15 م
⏰ وقت الصلاة: 5:28 م
🏠 المكان: البيت Home
📍 العنوان المختصر: 9X3H+MHV Dammam
🗺️ للوصول إلى الموقع: https://goo.gl/maps/wBSFdiaLBrZ7kEM88

📝 الدعوة عامة للجميع
ℹ️ تنويه: يرجى الحضور قبل الإقامة
""".trim()
        assertEquals(expected, renderEvent(event(), body(BUILTIN_PRAYER, "ar")).text)
    }

    @Test
    fun englishPrayerInvitationUsesTheSameOrder() {
        val expected = """
🕌 Isha congregational prayer — Wednesday
👤 Host: علي الحنابي (ابو الزهراء)
📅 Date: October 14, 2026
🚪 Doors open: 5:15 PM
⏰ Prayer time: 5:28 PM
🏠 Place: البيت Home
📍 Short address: 9X3H+MHV Dammam
🗺️ Directions: https://goo.gl/maps/wBSFdiaLBrZ7kEM88

📝 Open to everyone
ℹ️ Note: Please arrive before the iqama
""".trim()
        val e = event(language = "en", showDate = true, description = "Open to everyone", notes = "Please arrive before the iqama")
        assertEquals(expected, renderEvent(e, body(BUILTIN_PRAYER, "en")).text)
    }

    @Test
    fun dateLineIsOptionalAndHasNoWeekdayBecauseTheTitleShowsIt() {
        val hidden = renderEvent(event(showDate = false), body(BUILTIN_PRAYER, "ar")).text
        assertFalse(hidden.contains("📅"))
        val shown = renderEvent(event(showDate = true), body(BUILTIN_PRAYER, "ar")).text
        assertTrue(shown.contains("📅 التاريخ: 14 أكتوبر 2026"))
        assertEquals("the weekday appears once", 1, Regex("الأربعاء").findAll(shown).count())
    }

    @Test
    fun withoutADateTheTitleStaysAndNoWeekdayOrSeparatorIsLeft() {
        for (template in BUILT_IN_TEMPLATES) for (lang in listOf("ar", "en")) {
            val text = renderEvent(event(language = lang, date = "", showDate = true), if (lang == "en") template.bodyEn else template.bodyAr).text
            val first = text.lines().first()
            assertTrue("${template.id}/$lang: $first", first.endsWith(if (lang == "en") "Isha congregational prayer" else "صلاة العشاء جماعة"))
            assertFalse(text.contains("—"))
            assertFalse(text.contains("📅"))
        }
    }

    @Test
    fun placeNameLineIsOptionalAndTheAddressAndLinkStay() {
        val text = renderEvent(event(showPlaceName = false), body(BUILTIN_PRAYER, "ar")).text
        assertFalse(text.contains("🏠"))
        assertFalse(text.contains("البيت Home"))
        assertTrue(text.contains("📍 العنوان المختصر: 9X3H+MHV Dammam"))
        assertTrue(text.contains("🗺️ للوصول إلى الموقع: https://goo.gl/maps/wBSFdiaLBrZ7kEM88"))
    }

    @Test
    fun everyBuiltInTemplateHasTheSameOrderInBothLanguages() {
        for (template in BUILT_IN_TEMPLATES) for (lang in listOf("ar", "en")) {
            val text = renderEvent(event(language = lang, showDate = true), if (lang == "en") template.bodyEn else template.bodyAr).text
            val order = listOf("Wednesday|الأربعاء", "October 14, 2026|14 أكتوبر 2026", "البيت Home", "9X3H+MHV Dammam", "https://goo.gl/maps", "الدعوة عامة للجميع", "يرجى الحضور قبل الإقامة")
                .map { alternatives -> alternatives.split("|").map { text.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: -1 }
            assertFalse("${template.id}/$lang missing a part: $text", order.contains(-1))
            assertEquals("${template.id}/$lang order: $text", order.sorted(), order)
            // description and note come after the link, separated by an empty line
            assertTrue("${template.id}/$lang", Regex("""goo\.gl/maps/\S+\n\n""").containsMatchIn(text))
        }
    }

    @Test
    fun emptyDescriptionAndNoteLeaveNoTrailingGap() {
        val text = renderEvent(event(description = "", notes = ""), body(BUILTIN_PRAYER, "ar")).text
        assertTrue(text.endsWith("https://goo.gl/maps/wBSFdiaLBrZ7kEM88"))
        assertFalse(text.contains("تنويه"))
    }

    @Test
    fun dayInACustomTemplateIsOptionalInline() {
        val values = mapOf("title" to "لقاء", "day" to "")
        assertEquals("لقاء", renderInvitation("{title} - {day}", values).text)
        assertEquals("لقاء", renderInvitation("{title}\n📅 {day}", values).text)
        assertEquals("لقاء — الخميس", renderInvitation("{title} — {day}", values + ("day" to "الخميس")).text)
    }

    @Test
    fun eventsSavedBeforeTheOptionsKeepDateAndPlaceAndTheirJson() {
        val old = """[{"id":"ev-old","type":"prayer","title":"T","placeId":"pl-1","place":{"placeName":"P","title":"A","url":"https://x.test"},"host":"","date":"2026-10-14","time":"","openingTime":"","prayer":"","imamLine":"","description":"","notes":"","templateId":"builtin-prayer","language":"ar","invitationText":"T","textEdited":false,"createdAt":1,"updatedAt":2}]"""
        val e = parseEventsStrict(old)!!.single()
        assertTrue(e.showDate)
        assertTrue(e.showPlaceName)
        assertEquals(old, eventsToJson(listOf(e)))
    }

    @Test
    fun turnedOffOptionsSurviveSavingAndBackup() {
        val e = event(showDate = false, showPlaceName = false)
        val back = parseEventsStrict(eventsToJson(listOf(e)))!!.single()
        assertFalse(back.showDate)
        assertFalse(back.showPlaceName)
        assertEquals(e, back)
    }
}
