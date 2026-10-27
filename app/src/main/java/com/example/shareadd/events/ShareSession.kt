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
    /** session values equal the saved ones: the saved invitation text, unchanged */
    SAVED,
    /** template-managed text (or another template chosen for this share), rebuilt from the template with the session values */
    TEMPLATE,
    /** hand-edited (or no longer template-generated) text: only the date/weekday and time strings were replaced */
    TIMES_REPLACED,
    /** the new values could not be placed safely in the custom text: the saved text is used unchanged */
    TIMES_NOT_APPLIED
}

data class ShareText(val text: String, val source: ShareTextSource)

/**
 * The invitation text for this share.
 *  - Session values unchanged → the saved text exactly.
 *  - Another template chosen for this share → the text of that template ([sessionTemplateBody]) with the session values
 *    (the caller asks before this replaces a hand-edited text).
 *  - Text still generated from its template (not hand-edited, and equal to what [templateBody] gives for the saved
 *    values) → rebuilt from the template with the session values ({day}, {date}, {day_date} follow the session date);
 *    an empty optional value drops its line.
 *  - Otherwise (hand-edited text) → each changed time (event, doors-open, iqama) is replaced only where the saved time
 *    appears exactly once and cannot be confused with another time; a changed date replaces the saved weekday and date
 *    line, each only where it appears exactly once (at least one of them must be found). A changed day/date display or
 *    changed notices cannot be placed in a custom text. If any change cannot be placed, the saved text is used unchanged.
 * The saved event is never modified here.
 */
fun shareInvitationText(
    event: ShareEvent,
    times: ShareTimes,
    templateBody: String?,
    sessionTemplateBody: String? = templateBody
): ShareText {
    @Suppress("NAME_SHADOWING") val times = times.resolved(event)
    if (times == savedTimes(event)) return ShareText(event.invitationText, ShareTextSource.SAVED)

    val session = times.sessionEvent(event)
    if (times.templateId != event.templateId && sessionTemplateBody != null) {
        return ShareText(renderEvent(session, sessionTemplateBody).text, ShareTextSource.TEMPLATE)
    }
    if (!event.textEdited && templateBody != null && renderEvent(event, templateBody).text == event.invitationText) {
        return ShareText(renderEvent(session, templateBody).text, ShareTextSource.TEMPLATE)
    }

    val replaced = replaceTimesInText(event, times) ?: return ShareText(event.invitationText, ShareTextSource.TIMES_NOT_APPLIED)
    return ShareText(replaced, ShareTextSource.TIMES_REPLACED)
}

/** Replaces the displayed saved value(s) by the session value(s) in a custom text; null when it cannot be done safely. */
private fun replaceTimesInText(event: ShareEvent, times: ShareTimes): String? {
    if (times.templateId != event.templateId || times.showDate != event.showDate || times.notes != event.notes) return null
    val lang = event.language
    val savedTime = formatEventTime(event.time, lang)
    val savedOpening = formatEventTime(event.openingTime, lang)
    val savedIqama = formatEventTime(event.iqamaTime, lang)
    var text = event.invitationText

    fun replaceOnce(old: String, new: String, vararg others: String): Boolean {
        if (old.isBlank() || new.isBlank() || others.any { it == old }) return false
        // the saved time must appear exactly once, not as the tail of a longer number ("6:15" inside "16:15")
        val pattern = Regex("(?<![0-9])" + Regex.escape(old) + "(?![0-9])")
        val matches = pattern.findAll(text).toList()
        if (matches.size != 1) return false
        text = text.replaceRange(matches[0].range, new)
        return true
    }

    if (times.time != event.time) {
        if (!replaceOnce(savedTime, formatEventTime(times.time, lang), savedOpening, savedIqama)) return null
    }
    if (times.openingTime != event.openingTime) {
        if (!replaceOnce(savedOpening, formatEventTime(times.openingTime, lang), savedTime, savedIqama)) return null
    }
    if (times.iqamaTime != event.iqamaTime) {
        if (!replaceOnce(savedIqama, formatEventTime(times.iqamaTime.orEmpty(), lang), savedTime, savedOpening)) return null
    }
    if (times.dateFor(event) != event.date) text = replaceDateInText(text, event.date, times.dateFor(event), lang) ?: return null
    return text
}

/**
 * Replaces the saved weekday ("الأحد" / "Sunday") and date line ("25 أكتوبر 2026" / "October 25, 2026") by those of the
 * new date. Each is replaced only if it appears exactly once as a whole word / number; null when neither is found, one
 * appears more than once, or a date is missing.
 */
private fun replaceDateInText(text: String, oldDate: String, newDate: String, lang: String): String? {
    val oldDay = formatEventWeekday(oldDate, lang)
    val oldLine = formatInvitationDate(oldDate, lang)
    val newDay = formatEventWeekday(newDate, lang)
    val newLine = formatInvitationDate(newDate, lang)
    if (oldDay.isBlank() || oldLine.isBlank() || newDay.isBlank() || newLine.isBlank()) return null

    val dayPattern = Regex("(?<!\\p{L})" + Regex.escape(oldDay) + "(?!\\p{L})")
    val linePattern = Regex("(?<![0-9])" + Regex.escape(oldLine) + "(?![0-9])")
    val days = dayPattern.findAll(text).toList()
    val lines = linePattern.findAll(text).toList()
    if (days.size > 1 || lines.size > 1 || (days.isEmpty() && lines.isEmpty())) return null

    // replace from the end so the earlier range stays valid
    val edits = (days.map { it.range to newDay } + lines.map { it.range to newLine }).sortedByDescending { it.first.first }
    if (edits.size == 2 && edits[0].first.first <= edits[1].first.last) return null // overlapping: not safe
    var result = text
    edits.forEach { (range, value) -> result = result.replaceRange(range, value) }
    return result
}

/**
 * The event with this session's values saved (only used when the user ticked "update the event with these values"),
 * in one update: date, times, iqama, day/date display, notices and template. Place, photo and every other field stay.
 *  - [finalText] is the text actually shared; when the user edited it in the share dialog it is saved as a hand-edited text.
 *  - Otherwise the invitation text follows the session text when it could be produced; a custom text that could not take
 *    the new values is kept as it was.
 */
fun applyShareTimes(event: ShareEvent, times: ShareTimes, shareText: ShareText, now: Long, finalText: String? = null): ShareEvent {
    val session = times.sessionEvent(event)
    val handEdited = finalText != null && finalText != shareText.text
    val text = when {
        handEdited -> finalText!!
        shareText.source == ShareTextSource.TEMPLATE || shareText.source == ShareTextSource.TIMES_REPLACED -> shareText.text
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
