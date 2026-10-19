package com.example.shareadd.events

import com.example.shareadd.backup.JsonFormatException
import com.example.shareadd.backup.JsonIn
import com.example.shareadd.backup.JsonOut
import com.example.shareadd.backup.JsonToken
import java.io.StringReader
import java.io.StringWriter

/** Public details of the chosen place, kept with the event so the invitation still works if the place changes or is deleted. */
data class PlaceSnapshot(
    val placeName: String = "",
    val title: String = "",
    val url: String = ""
)

object EventTypes {
    const val PRAYER = "prayer"
    const val FAMILY = "family"
    const val SOCIAL = "social"
    const val GENERAL = "general"
    val all = listOf(PRAYER, FAMILY, SOCIAL, GENERAL)
}

object Prayers {
    const val FAJR = "fajr"
    const val DHUHRAIN = "dhuhrain"
    const val ISHAAIN = "ishaain"
    val all = listOf(FAJR, DHUHRAIN, ISHAAIN)

    fun label(prayer: String, arabic: Boolean): String = when (prayer) {
        FAJR -> if (arabic) "الفجر" else "Fajr"
        DHUHRAIN -> if (arabic) "الظهرين" else "Dhuhr & Asr"
        ISHAAIN -> if (arabic) "العشائين" else "Maghrib & Isha"
        else -> ""
    }
}

data class ShareEvent(
    val id: String,
    val type: String = EventTypes.GENERAL,
    val title: String = "",
    val placeId: String? = null,
    val place: PlaceSnapshot = PlaceSnapshot(),
    val host: String = "",
    /** yyyy-MM-dd, or empty */
    val date: String = "",
    /** HH:mm (24h), or empty */
    val time: String = "",
    /** HH:mm (24h), or empty — door opening time, independent of the event / prayer time */
    val openingTime: String = "",
    val prayer: String = "",
    val imamLine: String = "",
    val description: String = "",
    val notes: String = "",
    val templateId: String = "",
    /** "ar" or "en" — language of the invitation text */
    val language: String = "ar",
    /** the saved invitation text (snapshot); never regenerated silently */
    val invitationText: String = "",
    /** true when the user edited the invitation text by hand */
    val textEdited: Boolean = false,
    /**
     * Preferred house/place photo for sharing: one of the linked place's image references, exactly as stored in the
     * place (never a copy, bitmap or base64). Empty = no preference. Checked against the trusted image store when used.
     */
    val invitationPhoto: String = "",
    /**
     * Show the date line in the invitation. The weekday is shown next to the title whenever a date is set.
     * Missing in events saved before this option existed → true, so their invitations keep the date.
     * New events start with false (weekday only).
     */
    val showDate: Boolean = true,
    /** Show the place name line in the invitation (true for older events). */
    val showPlaceName: Boolean = true,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

data class InvitationTemplate(
    val id: String,
    val name: String,
    val type: String = EventTypes.GENERAL,
    val language: String = "ar",
    val body: String = "",
    val builtIn: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

// ------------------------------------------------------------------ JSON (shared by local storage and Backup v2)

fun writeEvent(out: JsonOut, e: ShareEvent) {
    out.beginObject()
    out.field("id", e.id)
    out.field("type", e.type)
    out.field("title", e.title)
    out.field("placeId", e.placeId)
    out.name("place").beginObject()
        .field("placeName", e.place.placeName)
        .field("title", e.place.title)
        .field("url", e.place.url)
        .endObject()
    out.field("host", e.host)
    out.field("date", e.date)
    out.field("time", e.time)
    out.field("openingTime", e.openingTime)
    out.field("prayer", e.prayer)
    out.field("imamLine", e.imamLine)
    out.field("description", e.description)
    out.field("notes", e.notes)
    out.field("templateId", e.templateId)
    out.field("language", e.language)
    out.field("invitationText", e.invitationText)
    out.field("textEdited", e.textEdited)
    if (e.invitationPhoto.isNotBlank()) out.field("invitationPhoto", e.invitationPhoto)
    // written only when off, so events that use the defaults keep exactly their previous JSON
    if (!e.showDate) out.field("showDate", false)
    if (!e.showPlaceName) out.field("showPlaceName", false)
    out.field("createdAt", e.createdAt)
    out.field("updatedAt", e.updatedAt)
    out.endObject()
}

fun readEvent(input: JsonIn): ShareEvent {
    var e = ShareEvent(id = "")
    input.beginObject()
    while (input.hasNext()) {
        when (input.nextName()) {
            "id" -> e = e.copy(id = input.nextScalarAsText())
            "type" -> e = e.copy(type = input.nextScalarAsText())
            "title" -> e = e.copy(title = input.nextScalarAsText())
            "placeId" -> e = e.copy(placeId = if (input.peek() == JsonToken.NULL) { input.nextNull(); null } else input.nextScalarAsText().ifBlank { null })
            "place" -> e = e.copy(place = readSnapshot(input))
            "host" -> e = e.copy(host = input.nextScalarAsText())
            "date" -> e = e.copy(date = input.nextScalarAsText())
            "time" -> e = e.copy(time = input.nextScalarAsText())
            "openingTime" -> e = e.copy(openingTime = input.nextScalarAsText())
            "prayer" -> e = e.copy(prayer = input.nextScalarAsText())
            "imamLine" -> e = e.copy(imamLine = input.nextScalarAsText())
            "description" -> e = e.copy(description = input.nextScalarAsText())
            "notes" -> e = e.copy(notes = input.nextScalarAsText())
            "templateId" -> e = e.copy(templateId = input.nextScalarAsText())
            "language" -> e = e.copy(language = input.nextScalarAsText())
            "invitationText" -> e = e.copy(invitationText = input.nextScalarAsText())
            "textEdited" -> e = e.copy(textEdited = input.nextScalarAsText() == "true")
            "invitationPhoto" -> e = e.copy(invitationPhoto = input.nextScalarAsText())
            "showDate" -> e = e.copy(showDate = input.nextScalarAsText() == "true")
            "showPlaceName" -> e = e.copy(showPlaceName = input.nextScalarAsText() == "true")
            "createdAt" -> e = e.copy(createdAt = input.nextLong())
            "updatedAt" -> e = e.copy(updatedAt = input.nextLong())
            else -> input.skipValue()
        }
    }
    input.endObject()
    if (e.id.isBlank()) throw JsonFormatException("event without id")
    return e
}

private fun readSnapshot(input: JsonIn): PlaceSnapshot {
    var s = PlaceSnapshot()
    input.beginObject()
    while (input.hasNext()) {
        when (input.nextName()) {
            "placeName" -> s = s.copy(placeName = input.nextScalarAsText())
            "title" -> s = s.copy(title = input.nextScalarAsText())
            "url" -> s = s.copy(url = input.nextScalarAsText())
            else -> input.skipValue()
        }
    }
    input.endObject()
    return s
}

fun writeTemplate(out: JsonOut, t: InvitationTemplate) {
    out.beginObject()
    out.field("id", t.id)
    out.field("name", t.name)
    out.field("type", t.type)
    out.field("language", t.language)
    out.field("body", t.body)
    out.field("createdAt", t.createdAt)
    out.field("updatedAt", t.updatedAt)
    out.endObject()
}

fun readTemplate(input: JsonIn): InvitationTemplate {
    var t = InvitationTemplate(id = "", name = "")
    input.beginObject()
    while (input.hasNext()) {
        when (input.nextName()) {
            "id" -> t = t.copy(id = input.nextScalarAsText())
            "name" -> t = t.copy(name = input.nextScalarAsText())
            "type" -> t = t.copy(type = input.nextScalarAsText())
            "language" -> t = t.copy(language = input.nextScalarAsText())
            "body" -> t = t.copy(body = input.nextScalarAsText())
            "createdAt" -> t = t.copy(createdAt = input.nextLong())
            "updatedAt" -> t = t.copy(updatedAt = input.nextLong())
            else -> input.skipValue()
        }
    }
    input.endObject()
    if (t.id.isBlank()) throw JsonFormatException("template without id")
    return t.copy(builtIn = false)
}

fun eventsToJson(events: List<ShareEvent>): String {
    val sw = StringWriter()
    val out = JsonOut(sw)
    out.beginArray()
    events.forEach { writeEvent(out, it) }
    out.endArray()
    out.flush()
    return sw.toString()
}

fun templatesToJson(templates: List<InvitationTemplate>): String {
    val sw = StringWriter()
    val out = JsonOut(sw)
    out.beginArray()
    templates.filterNot { it.builtIn }.forEach { writeTemplate(out, it) }
    out.endArray()
    out.flush()
    return sw.toString()
}

/** Returns null when the stored text cannot be read completely. */
fun parseEventsStrict(text: String): List<ShareEvent>? = try {
    val input = JsonIn(StringReader(text))
    val list = mutableListOf<ShareEvent>()
    input.beginArray()
    while (input.hasNext()) list.add(readEvent(input))
    input.endArray()
    input.endDocument()
    list
} catch (_: Exception) {
    null
}

fun parseTemplatesStrict(text: String): List<InvitationTemplate>? = try {
    val input = JsonIn(StringReader(text))
    val list = mutableListOf<InvitationTemplate>()
    input.beginArray()
    while (input.hasNext()) list.add(readTemplate(input))
    input.endArray()
    input.endDocument()
    list
} catch (_: Exception) {
    null
}
