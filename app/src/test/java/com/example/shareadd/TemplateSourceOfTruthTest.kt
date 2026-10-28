package com.example.shareadd

import com.example.shareadd.backup.CurrentData
import com.example.shareadd.backup.ImportMode
import com.example.shareadd.backup.StageStats
import com.example.shareadd.backup.StagedBackup
import com.example.shareadd.backup.planImport
import com.example.shareadd.events.BUILTIN_PRAYER
import com.example.shareadd.events.DEFAULT_TEMPLATE_SEEDS
import com.example.shareadd.events.EventTypes
import com.example.shareadd.events.InvitationTemplate
import com.example.shareadd.events.PlaceSnapshot
import com.example.shareadd.events.Prayers
import com.example.shareadd.events.ShareEvent
import com.example.shareadd.events.ShareTextSource
import com.example.shareadd.events.ShareTimes
import com.example.shareadd.events.defaultPrayerTitle
import com.example.shareadd.events.defaultTemplateFor
import com.example.shareadd.events.defaultTemplateId
import com.example.shareadd.events.eventsToJson
import com.example.shareadd.events.isDefaultTemplate
import com.example.shareadd.events.liveInvitationText
import com.example.shareadd.events.parseEventsStrict
import com.example.shareadd.events.parseTemplatesStrict
import com.example.shareadd.events.renderEvent
import com.example.shareadd.events.resolveEventTemplate
import com.example.shareadd.events.savedTimes
import com.example.shareadd.events.seedDefaultTemplates
import com.example.shareadd.events.sessionEvent
import com.example.shareadd.events.shareInvitationText
import com.example.shareadd.events.templateChoices
import com.example.shareadd.events.templatesToJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The stored templates are the source of truth; event previews follow every value change unless the text was edited by hand. */
class TemplateSourceOfTruthTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val prayerAr = defaultTemplateId(BUILTIN_PRAYER, "ar")

    /** Stored templates after a save and a reload (what the app reads at the next start). */
    private fun reload(templates: List<InvitationTemplate>) = parseTemplatesStrict(templatesToJson(templates))!!

    private fun prayerEvent(templateId: String = prayerAr, iqama: String = "18:15", textEdited: Boolean = false, text: String = "") = ShareEvent(
        id = "ev-1", type = EventTypes.PRAYER, title = defaultPrayerTitle(Prayers.ISHAAIN, "ar"), placeId = "pl-1",
        place = PlaceSnapshot("بيت الاختبار", "حي التجربة", "https://maps.app.goo.gl/abc"), date = "2026-10-20",
        time = "18:00", openingTime = "17:30", iqamaTime = iqama, prayer = Prayers.ISHAAIN, templateId = templateId,
        language = "ar", invitationText = text, textEdited = textEdited, showDate = false, createdAt = 1, updatedAt = 2
    )

    /** The event as saved by the editor: its text generated from its stored template. */
    private fun saved(e: ShareEvent, templates: List<InvitationTemplate>) =
        e.copy(invitationText = renderEvent(e, resolveEventTemplate(templates, e.templateId, e.language, e.type).template.body).text)

    // ------------------------------------------------------------------ seeding

    @Test
    fun firstRunSeedsEveryDefaultWithAStableIdInBothLanguages() {
        val stored = seedDefaultTemplates(emptyList())
        assertEquals(12, stored.size) // 4 event + 2 place definitions × ar/en
        assertEquals(
            listOf("default-prayer-ar", "default-prayer-en", "default-family-ar", "default-family-en", "default-social-ar", "default-social-en",
                "default-general-ar", "default-general-en", "default-place-detailed-ar", "default-place-detailed-en", "default-place-short-ar", "default-place-short-en"),
            stored.map { it.id }
        )
        assertTrue(stored.all { isDefaultTemplate(it.id) && !it.builtIn })
        // stored like any template: they survive the local storage round trip and a restart
        assertEquals(stored, reload(stored))
    }

    @Test
    fun seedingIsIdempotentAndNeverDuplicatesOrOverwrites() { // B
        var stored = seedDefaultTemplates(emptyList())
        repeat(5) { stored = seedDefaultTemplates(reload(stored)) }
        assertEquals(12, stored.size)
        assertEquals(stored.size, stored.map { it.id }.toSet().size)
        assertSame("nothing missing → the same list (nothing to save)", stored, seedDefaultTemplates(stored))
    }

    @Test
    fun existingUserTemplatesAndClonesArePreservedAndOnlyMissingDefaultsAreAdded() { // C
        val clone = InvitationTemplate("tpl-clone", "صلاة جماعة (نسخة)", EventTypes.PRAYER, "ar", "نسختي {time}", createdAt = 3, updatedAt = 4)
        val place = InvitationTemplate("tpl-p", "مكاني", "place", "ar", "📍 {place_name}")
        val stored = seedDefaultTemplates(listOf(clone, place))
        assertEquals(14, stored.size)
        assertEquals(clone, stored.first { it.id == "tpl-clone" })
        assertEquals(place, stored.first { it.id == "tpl-p" })

        // an edited default is kept as edited; only the defaults that are missing (e.g. after "replace all") come back
        val edited = stored.map { if (it.id == prayerAr) it.copy(body = "معدّل {time}") else it }
            .filterNot { it.id == "default-family-en" }
        val again = seedDefaultTemplates(edited)
        assertEquals("معدّل {time}", again.first { it.id == prayerAr }.body)
        assertEquals(DEFAULT_TEMPLATE_SEEDS.first { it.id == "default-family-en" }, again.first { it.id == "default-family-en" })
        assertEquals(14, again.size)
    }

    // ------------------------------------------------------------------ the default template is edited directly

    @Test
    fun editedDefaultTemplateIsUsedByNewEventsAndSurvivesARestart() { // A
        val stored = seedDefaultTemplates(emptyList())
        val original = stored.first { it.id == prayerAr }
        assertTrue(original.body.contains("⏰ وقت الصلاة: {time}"))
        // edit the actual default (no copy): change a label, save
        val edited = original.copy(body = original.body.replace("⏰ وقت الصلاة:", "⏰ دخول وقت الصلاة:"), updatedAt = 9)
        val afterSave = stored.map { if (it.id == edited.id) edited else it }
        assertEquals("still 12, nothing cloned", 12, afterSave.size)

        // new event of this type: the stored default is its template
        val newEventTemplate = defaultTemplateId(defaultTemplateFor(EventTypes.PRAYER), "ar")
        assertEquals(prayerAr, newEventTemplate)
        val text = renderEvent(prayerEvent(newEventTemplate), resolveEventTemplate(afterSave, newEventTemplate, "ar", EventTypes.PRAYER).template.body).text
        assertTrue(text.contains("⏰ دخول وقت الصلاة: 6:00 م"))
        assertFalse(text.contains("⏰ وقت الصلاة:"))

        // restart: the edit is still there and seeding does not bring the original back
        val restarted = seedDefaultTemplates(reload(afterSave))
        assertEquals(edited, restarted.first { it.id == prayerAr })
        assertEquals(12, restarted.size)
        // and it is offered (once) among the event templates of its language
        assertEquals(1, templateChoices(restarted, "ar").count { it.id == prayerAr })
        assertFalse(templateChoices(restarted, "ar").any { it.id == "default-prayer-en" })
    }

    @Test
    fun oldBuiltInIdsOfExistingEventsResolveToTheStoredDefaults() {
        val stored = seedDefaultTemplates(emptyList()).map { if (it.id == "default-prayer-en") it.copy(body = "EN {time}") else it }
        val ar = resolveEventTemplate(stored, BUILTIN_PRAYER, "ar", EventTypes.PRAYER)
        assertEquals(prayerAr, ar.template.id); assertFalse(ar.fallback)
        val en = resolveEventTemplate(stored, BUILTIN_PRAYER, "en", EventTypes.PRAYER)
        assertEquals("default-prayer-en", en.template.id); assertEquals("EN {time}", en.template.body); assertFalse(en.fallback)
        // a default of the other language follows the invitation language
        assertEquals("default-prayer-en", resolveEventTemplate(stored, prayerAr, "en", EventTypes.PRAYER).template.id)
    }

    @Test
    fun missingTemplateFallsBackToTheTypesDefaultAndTheEventIsNotChanged() { // D
        val stored = seedDefaultTemplates(emptyList())
        val e = saved(prayerEvent("tpl-deleted"), stored)
        val r = resolveEventTemplate(stored, e.templateId, e.language, e.type)
        assertTrue(r.fallback)
        assertEquals(prayerAr, r.template.id)
        assertEquals("tpl-deleted", e.templateId) // the event itself is untouched until the user saves it
        // a blank template id is not a fallback, just the default
        assertFalse(resolveEventTemplate(stored, "", "ar", EventTypes.FAMILY).fallback)
        // storage could not be read (no stored templates): the default content is still used, never a crash
        assertEquals(prayerAr, resolveEventTemplate(emptyList(), prayerAr, "ar", EventTypes.PRAYER).template.id)
    }

    // ------------------------------------------------------------------ live update of the preview

    @Test
    fun iqamaChangeUpdatesThePreviewImmediately() { // C
        val stored = seedDefaultTemplates(emptyList())
        val body = resolveEventTemplate(stored, prayerAr, "ar", EventTypes.PRAYER).template.body
        val e = saved(prayerEvent(iqama = "18:15"), stored)
        val first = shareInvitationText(e, savedTimes(e), body)
        assertTrue(first.text.contains("🕋 وقت الإقامة: 6:15 م"))
        val changed = shareInvitationText(e, savedTimes(e).copy(iqamaTime = "18:30"), body)
        assertEquals(ShareTextSource.TEMPLATE, changed.source)
        assertTrue(changed.text.contains("🕋 وقت الإقامة: 6:30 م"))
        assertFalse(changed.text.contains("6:15 م"))
    }

    @Test
    fun iqamaUpdatesEvenWhenTheSavedTextIsFromAnOlderTemplateOrHadNoIqama() {
        // root cause of the v1.2 bug: a saved text that no longer matched its template was treated as custom text,
        // and a new iqama could not be placed in it; a text that was not edited by hand now always follows the template
        val stored = seedDefaultTemplates(emptyList())
        val body = resolveEventTemplate(stored, BUILTIN_PRAYER, "ar", EventTypes.PRAYER).template.body
        val old = prayerEvent(BUILTIN_PRAYER, iqama = "", text = "نص قديم من قالب سابق بدون إقامة")
        val r = shareInvitationText(old, savedTimes(old).copy(iqamaTime = "18:30"), body)
        assertEquals(ShareTextSource.TEMPLATE, r.source)
        assertTrue(r.text.contains("🕋 وقت الإقامة: 6:30 م"))
    }

    @Test
    fun everyValueAndTheTemplateUpdateThePreview() { // E
        val stored = seedDefaultTemplates(emptyList())
        val body = resolveEventTemplate(stored, prayerAr, "ar", EventTypes.PRAYER).template.body
        val e = saved(prayerEvent(), stored)
        val base = savedTimes(e)
        fun preview(t: ShareTimes, b: String = body) = shareInvitationText(e, t, b).text

        assertTrue(preview(base.copy(time = "18:45")).contains("⏰ وقت الصلاة: 6:45 م"))
        assertTrue(preview(base.copy(openingTime = "17:10")).contains("🚪 فتح الباب: 5:10 م"))
        assertTrue(preview(base.copy(date = "2026-10-25")).contains("— الأحد"))
        assertFalse(preview(base).contains("📅 التاريخ"))
        assertTrue(preview(base.copy(showDate = true)).contains("📅 التاريخ: 20 أكتوبر 2026"))
        assertTrue(preview(base.copy(notes = "- بند أول\n- بند ثان")).contains("- بند أول\n- بند ثان"))
        val other = InvitationTemplate("tpl-x", "قالبي", EventTypes.PRAYER, "ar", "الإقامة عند {iqama_time}")
        assertEquals("الإقامة عند 6:15 م", preview(base.copy(templateId = "tpl-x"), other.body))
        // and each value back to the saved one gives the saved text again
        assertEquals(ShareTextSource.SAVED, shareInvitationText(e, base, body).source)
    }

    // ------------------------------------------------------------------ manual text protection

    @Test
    fun manualEditIsKeptWarnedAndReplacedOnlyByTheExplicitUpdate() { // D
        val stored = seedDefaultTemplates(emptyList())
        val body = resolveEventTemplate(stored, prayerAr, "ar", EventTypes.PRAYER).template.body
        val e = saved(prayerEvent(), stored)
        fun fresh(t: ShareTimes) = renderEvent(t.sessionEvent(e), body).text

        // edited in the dialog: the text it was edited from is remembered
        val before = savedTimes(e)
        val editedFrom = fresh(before)
        val manual = "نصي اليدوي — الإقامة 6:15 م"
        val notChanged = liveInvitationText(fresh(before), manual, editedFrom)
        assertEquals(manual, notChanged.text); assertTrue(notChanged.manual); assertFalse(notChanged.stale)

        // iqama changed afterwards: the manual text is not overwritten, the change is reported
        val after = before.copy(iqamaTime = "18:30")
        val stale = liveInvitationText(fresh(after), manual, editedFrom)
        assertEquals(manual, stale.text)
        assertTrue(stale.stale)

        // explicit update (after confirmation): the manual edit is dropped, the latest iqama is in the text
        val updated = liveInvitationText(fresh(after), null, "")
        assertTrue(updated.text.contains("🕋 وقت الإقامة: 6:30 م"))
        assertFalse(updated.manual)

        // the same for every value and for the template
        listOf(
            before.copy(time = "18:40"), before.copy(openingTime = "17:00"), before.copy(date = "2026-10-21"),
            before.copy(notes = "- جديد"), before.copy(showDate = true)
        ).forEach { assertTrue(liveInvitationText(fresh(it), manual, editedFrom).stale) }
        assertTrue(liveInvitationText(renderEvent(e, "{title} {iqama_time}").text, manual, editedFrom).stale)

        // a saved hand-edited event: kept and reported, rebuilt only on request
        val handEdited = e.copy(invitationText = manual, textEdited = true)
        val kept = shareInvitationText(handEdited, after, body)
        assertEquals(manual, kept.text); assertTrue(kept.valuesDiffer)
        assertTrue(shareInvitationText(handEdited, after, body, regenerate = true).text.contains("6:30 م"))
    }

    // ------------------------------------------------------------------ persistence and old data

    @Test
    fun savedIqamaTemplateNoticesAndDisplayModePersistAcrossARestart() { // F
        val e = prayerEvent(templateId = "default-prayer-en", iqama = "18:25").copy(notes = "- أ\n- ب", showDate = true, language = "en")
        val back = parseEventsStrict(eventsToJson(listOf(e)))!!.single()
        assertEquals(e, back)
        assertEquals("18:25", back.iqamaTime); assertEquals("default-prayer-en", back.templateId)
        assertEquals("- أ\n- ب", back.notes); assertTrue(back.showDate)
    }

    @Test
    fun preV12EventWithoutIqamaOpensAndWorks() { // G
        val json = """[{"id":"ev-old","type":"prayer","title":"صلاة العشائين جماعة","placeId":null,"place":{"placeName":"بيت","title":"حي","url":"https://maps.app.goo.gl/o"},""" +
            """"host":"","date":"2026-10-20","time":"18:00","openingTime":"","prayer":"ishaain","imamLine":"","description":"","notes":"",""" +
            """"templateId":"builtin-prayer","language":"ar","invitationText":"نص قديم","textEdited":false,"createdAt":1,"updatedAt":2}]"""
        val old = parseEventsStrict(json)!!.single()
        assertEquals("", old.iqamaTime)
        assertTrue(old.showDate)
        val stored = seedDefaultTemplates(emptyList())
        val r = resolveEventTemplate(stored, old.templateId, old.language, old.type)
        assertEquals(prayerAr, r.template.id); assertFalse(r.fallback)
        val text = shareInvitationText(old, savedTimes(old), r.template.body).text
        assertTrue(text.contains("⏰ وقت الصلاة: 6:00 م"))
        assertFalse(text.contains("وقت الإقامة"))
        assertTrue(shareInvitationText(old, savedTimes(old).copy(iqamaTime = "18:15"), r.template.body).text.contains("🕋 وقت الإقامة: 6:15 م"))
    }

    @Test
    fun mergeImportDoesNotDuplicateUnchangedDefaultsButKeepsEditedOnes() {
        val current = seedDefaultTemplates(emptyList())
        val file = seedDefaultTemplates(emptyList()).map { if (it.id == prayerAr) it.copy(body = "من النسخة {time}") else it }
        val staged = StagedBackup(2, emptyList(), null, null, null, file, StageStats(), tmp.newFolder("staging"))
        var n = 0
        val plan = planImport(CurrentData(emptyList(), emptyList(), emptyList(), current), staged, ImportMode.MERGE_ALL, tmp.newFolder("images"), now = 1, newTemplateIdFn = { "tpl-new-${++n}" })
        val result = plan.templates!!
        assertEquals(13, result.size) // 12 defaults + a copy of the edited one
        assertEquals(11, plan.templatesSkipped)
        assertEquals(current.first { it.id == prayerAr }, result.first { it.id == prayerAr })
        assertEquals("من النسخة {time}", result.first { it.id == "tpl-new-1" }.body)
        assertSame(result, seedDefaultTemplates(result))
    }
}
