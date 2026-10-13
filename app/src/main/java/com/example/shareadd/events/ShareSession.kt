package com.example.shareadd.events

import com.example.shareadd.Place

/*
 * Share session: values used for ONE share from the invitation preview (times, house photo, QR).
 * Nothing here changes the saved event; only [applyShareTimes] builds an updated event, and the caller
 * uses it only when the user explicitly asked to update the event.
 */

/** Event/prayer time and doors-open time for this share ("HH:mm" or empty). They are independent values. */
data class ShareTimes(val time: String, val openingTime: String)

fun savedTimes(event: ShareEvent) = ShareTimes(event.time, event.openingTime)

/** How the text shown/shared for this session was produced. */
enum class ShareTextSource {
    /** times equal the saved ones: the saved invitation text, unchanged */
    SAVED,
    /** template-managed text, rebuilt from its template with this session's times */
    TEMPLATE,
    /** hand-edited (or no longer template-generated) text: only the time strings were replaced */
    TIMES_REPLACED,
    /** the new times could not be placed safely in the custom text: the saved text is used unchanged */
    TIMES_NOT_APPLIED
}

data class ShareText(val text: String, val source: ShareTextSource)

/**
 * The invitation text for this share.
 *  - Times unchanged → the saved text exactly.
 *  - Text still generated from its template (not hand-edited, and equal to what the template gives for the saved
 *    values) → rebuilt from the template with the session times; an empty doors-open time drops its line.
 *  - Otherwise (hand-edited text) → each changed time is replaced only where the saved time appears exactly once and
 *    cannot be confused with the other time; if any change cannot be placed this way, the saved text is used unchanged.
 * The saved event is never modified here.
 */
fun shareInvitationText(event: ShareEvent, times: ShareTimes, templateBody: String?): ShareText {
    if (times == savedTimes(event)) return ShareText(event.invitationText, ShareTextSource.SAVED)

    val session = event.copy(time = times.time, openingTime = times.openingTime)
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
    return text
}

/**
 * The event with this session's times saved (only used when the user ticked "update the event with these times").
 * The invitation text follows the session text when it could be produced; a custom text that could not take the
 * new times is kept as it was.
 */
fun applyShareTimes(event: ShareEvent, times: ShareTimes, shareText: ShareText, now: Long): ShareEvent {
    val text = when (shareText.source) {
        ShareTextSource.TEMPLATE, ShareTextSource.TIMES_REPLACED -> shareText.text
        ShareTextSource.SAVED, ShareTextSource.TIMES_NOT_APPLIED -> event.invitationText
    }
    return event.copy(time = times.time, openingTime = times.openingTime, invitationText = text, updatedAt = now)
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
