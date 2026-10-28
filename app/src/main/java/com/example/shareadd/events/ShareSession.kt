package com.example.shareadd.events

import com.example.shareadd.Place

/*
 * Share session: values used for ONE share from the invitation preview (date, times, notices, template, house photo, QR).
 * Nothing here changes the saved event; only [applyShareTimes] builds an updated event, and the caller
 * uses it only when the user explicitly asked to update the event.
 */

/**
 * Values for this share: event/prayer time, doors-open time and iqama time ("HH:mm" or empty), date ("yyyy-MM-dd"),
 * day-only / day-and-date display, the general notices and the template. They are independent values.
 * A null field = the event's saved value. The weekday is never stored: it is always derived from the date.
 */
data class ShareTimes(
    val time: String,
    val openingTime: String,
    val date: String? = null,
    val iqamaTime: String? = null,
    /** true = weekday and date, false = weekday only (the event's [ShareEvent.showDate]) */
    val showDate: Boolean? = null,
    val notes: String? = null,
    val templateId: String? = null
)

fun savedTimes(event: ShareEvent) =
    ShareTimes(event.time, event.openingTime, event.date, event.iqamaTime, event.showDate, event.notes, event.templateId)

/** The session values with every null resolved to the saved value. */
private fun ShareTimes.resolved(event: ShareEvent) = ShareTimes(
    time, openingTime, date ?: event.date, iqamaTime ?: event.iqamaTime, showDate ?: event.showDate, notes ?: event.notes,
    templateId ?: event.templateId
)

/** The date this share uses. */
fun ShareTimes.dateFor(event: ShareEvent): String = date ?: event.date

/** The event as this share shows it (the saved event itself is not changed). */
fun ShareTimes.sessionEvent(event: ShareEvent): ShareEvent {
    val t = resolved(event)
    return event.copy(
        time = t.time, openingTime = t.openingTime, date = t.date!!, iqamaTime = t.iqamaTime!!, showDate = t.showDate!!,
        notes = t.notes!!, templateId = t.templateId!!
    )
}

/** How the text shown/shared for this session was produced. */
enum class ShareTextSource {
    /** session values equal the saved ones and the template gives the saved text: the saved invitation text, unchanged */
    SAVED,
    /** generated from the template with the session values (follows every value change immediately) */
    TEMPLATE,
    /**
     * the event's text was edited by hand: it is kept exactly as saved, and is never changed by a value change. Values
     * that differ from the saved ones are not in it until the user explicitly asks to update the text ([valuesDiffer]).
     */
    MANUAL_KEPT
}

data class ShareText(
    val text: String,
    val source: ShareTextSource,
    /** a hand-edited text is kept while the session values differ from the saved ones (the caller shows a warning) */
    val valuesDiffer: Boolean = false
)

/**
 * The invitation text for this share.
 *  - Text not edited by hand (or [regenerate] = the user explicitly asked to update the text): generated from
 *    [templateBody] — the stored template chosen for this share — with the session values, so a change of the time,
 *    iqama, doors-open time, date, day/date display, notices or template shows immediately ({day}, {date}, {day_date}
 *    follow the session date; an empty optional value drops its line). It is [ShareTextSource.SAVED] when that is
 *    exactly the saved text and nothing changed.
 *  - Hand-edited text: kept exactly as written, whatever the session values are (never rewritten silently); when the
 *    session values differ from the saved ones, [ShareText.valuesDiffer] tells the caller to warn and offer the update.
 * The saved event is never modified here.
 */
fun shareInvitationText(event: ShareEvent, times: ShareTimes, templateBody: String, regenerate: Boolean = false): ShareText {
    @Suppress("NAME_SHADOWING") val times = times.resolved(event)
    val unchanged = times == savedTimes(event)
    if (event.textEdited && !regenerate) return ShareText(event.invitationText, ShareTextSource.MANUAL_KEPT, valuesDiffer = !unchanged)
    val text = renderEvent(times.sessionEvent(event), templateBody).text
    return ShareText(text, if (unchanged && !regenerate && text == event.invitationText) ShareTextSource.SAVED else ShareTextSource.TEMPLATE)
}

/**
 * Text shown while a hand-edited text may be out of date: [manualText] (null = not edited) is kept as it is; it is
 * stale when the text the template now gives ([generated]) differs from the one it was edited from ([editedFrom]).
 */
data class LiveText(val text: String, val manual: Boolean, val stale: Boolean)

fun liveInvitationText(generated: String, manualText: String?, editedFrom: String): LiveText =
    if (manualText == null) LiveText(generated, manual = false, stale = false)
    else LiveText(manualText, manual = true, stale = editedFrom != generated)

/**
 * The event with this session's values saved (only used when the user ticked "update the event with these values"),
 * in one update: date, times, iqama, day/date display, notices and template. Place, photo and every other field stay.
 *  - [finalText] is the text actually shared; when the user edited it in the share dialog it is saved as a hand-edited text.
 *  - Otherwise the invitation text follows the generated session text; a hand-edited text that was kept stays as it was.
 */
fun applyShareTimes(event: ShareEvent, times: ShareTimes, shareText: ShareText, now: Long, finalText: String? = null): ShareEvent {
    val session = times.sessionEvent(event)
    val handEdited = finalText != null && finalText != shareText.text
    val text = when {
        handEdited -> finalText!!
        shareText.source == ShareTextSource.TEMPLATE -> shareText.text
        else -> event.invitationText
    }
    val textEdited = when {
        handEdited -> true
        shareText.source == ShareTextSource.TEMPLATE -> false
        else -> event.textEdited
    }
    return session.copy(invitationText = text, textEdited = textEdited, updatedAt = now)
}

/** House photo options for an event: the photos that can be used, the suggested one, and a lost preference. */
data class InvitationPhotoChoice(
    val available: List<String>,
    /** suggested/selected photo, or null */
    val selected: String?,
    /** the event remembered a photo that is no longer available (it is not replaced silently) */
    val previousUnavailable: Boolean
)

/**
 * - Linked place exists: only its own images (those [usablePlaceImage] accepts) can be used. The remembered photo is
 *   selected if it is still one of them; with no preference the first image is suggested; a remembered photo that is
 *   gone is reported and nothing else is picked automatically.
 * - Linked place deleted / no place: only the remembered photo, and only if [usableOrphanImage] still accepts it.
 */
fun invitationPhotoChoice(
    event: ShareEvent,
    place: Place?,
    usablePlaceImage: (String) -> Boolean,
    usableOrphanImage: (String) -> Boolean
): InvitationPhotoChoice {
    val remembered = event.invitationPhoto.trim()
    if (place != null) {
        val available = place.images.filter(usablePlaceImage)
        return when {
            remembered.isEmpty() -> InvitationPhotoChoice(available, available.firstOrNull(), false)
            remembered in available -> InvitationPhotoChoice(available, remembered, false)
            else -> InvitationPhotoChoice(available, null, true)
        }
    }
    if (remembered.isEmpty()) return InvitationPhotoChoice(emptyList(), null, false)
    return if (usableOrphanImage(remembered)) InvitationPhotoChoice(listOf(remembered), remembered, false)
    else InvitationPhotoChoice(emptyList(), null, true)
}
