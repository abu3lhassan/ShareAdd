package com.example.shareadd.events

import com.example.shareadd.Place

/*
 * Share session: values used for ONE share from the invitation preview (date, times, house photo, QR).
 * Nothing here changes the saved event; only [applyShareTimes] builds an updated event, and the caller
 * uses it only when the user explicitly asked to update the event.
 */

/**
 * Event/prayer time and doors-open time ("HH:mm" or empty) and date ("yyyy-MM-dd") for this share. They are independent
 * values. [date] null = the event's saved date. The weekday is never stored: it is always derived from the date.
 */
data class ShareTimes(val time: String, val openingTime: String, val date: String? = null)

fun savedTimes(event: ShareEvent) = ShareTimes(event.time, event.openingTime, event.date)

/** The session values with the date resolved (null → the saved date). */
private fun ShareTimes.resolved(event: ShareEvent) = if (date == null) copy(date = event.date) else this

/** The date this share uses. */
fun ShareTimes.dateFor(event: ShareEvent): String = date ?: event.date

/** How the text shown/shared for this session was produced. */
enum class ShareTextSource {
    /** date and times equal the saved ones: the saved invitation text, unchanged */
    SAVED,
    /** template-managed text, rebuilt from its template with this session's date and times */
    TEMPLATE,
    /** hand-edited (or no longer template-generated) text: only the date/weekday and time strings were replaced */
    TIMES_REPLACED,
    /** the new date or times could not be placed safely in the custom text: the saved text is used unchanged */
    TIMES_NOT_APPLIED
}

data class ShareText(val text: String, val source: ShareTextSource)

/**
 * The invitation text for this share.
 *  - Date and times unchanged → the saved text exactly.
 *  - Text still generated from its template (not hand-edited, and equal to what the template gives for the saved
 *    values) → rebuilt from the template with the session date and times ({day} and {date} follow the session date);
 *    an empty doors-open time drops its line.
 *  - Otherwise (hand-edited text) → each changed time is replaced only where the saved time appears exactly once and
 *    cannot be confused with the other time; a changed date replaces the saved weekday and date line, each only where
 *    it appears exactly once (at least one of them must be found). If any change cannot be placed this way, the saved
 *    text is used unchanged.
 * The saved event is never modified here.
 */
fun shareInvitationText(event: ShareEvent, times: ShareTimes, templateBody: String?): ShareText {
    @Suppress("NAME_SHADOWING") val times = times.resolved(event)
    if (times == savedTimes(event)) return ShareText(event.invitationText, ShareTextSource.SAVED)

    val session = event.copy(time = times.time, openingTime = times.openingTime, date = times.dateFor(event))
    if (!event.textEdited && templateBody != null && renderEvent(event, templateBody).text == event.invitationText) {
        return ShareText(renderEvent(session, templateBody).text, ShareTextSource.TEMPLATE)
    }

    val replaced = replaceTimesInText(event, times) ?: return ShareText(event.invitationText, ShareTextSource.TIMES_NOT_APPLIED)
    return ShareText(replaced, ShareTextSource.TIMES_REPLACED)
}

/** Replaces the displayed saved time(s) by the session time(s) in a custom text; null when it cannot be done safely. */
private fun replaceTimesInText(event: ShareEvent, times: ShareTimes): String? {
    val lang = event.language
    val savedTime = formatEventTime(event.time, lang)
    val savedOpening = formatEventTime(event.openingTime, lang)
    var text = event.invitationText

    fun replaceOnce(old: String, new: String, other: String): Boolean {
        if (old.isBlank() || new.isBlank() || old == other) return false
        // the saved time must appear exactly once, not as the tail of a longer number ("6:15" inside "16:15")
        val pattern = Regex("(?<![0-9])" + Regex.escape(old) + "(?![0-9])")
        val matches = pattern.findAll(text).toList()
        if (matches.size != 1) return false
        text = text.replaceRange(matches[0].range, new)
        return true
    }

    if (times.time != event.time) {
        if (!replaceOnce(savedTime, formatEventTime(times.time, lang), savedOpening)) return null
    }
    if (times.openingTime != event.openingTime) {
        if (!replaceOnce(savedOpening, formatEventTime(times.openingTime, lang), savedTime)) return null
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
 * The event with this session's date and times saved (only used when the user ticked "update the event with these
 * values"). The invitation text follows the session text when it could be produced; a custom text that could not take
 * the new values is kept as it was.
 */
fun applyShareTimes(event: ShareEvent, times: ShareTimes, shareText: ShareText, now: Long): ShareEvent {
    val text = when (shareText.source) {
        ShareTextSource.TEMPLATE, ShareTextSource.TIMES_REPLACED -> shareText.text
        ShareTextSource.SAVED, ShareTextSource.TIMES_NOT_APPLIED -> event.invitationText
    }
    return event.copy(time = times.time, openingTime = times.openingTime, date = times.dateFor(event), invitationText = text, updatedAt = now)
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
